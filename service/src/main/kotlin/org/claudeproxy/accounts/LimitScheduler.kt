package org.claudeproxy.accounts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.claudeproxy.envOrProp
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Background limit refresher. For each account it re-probes:
 *   - shortly after any limit window's reset time passes,
 *   - otherwise every [baselineInterval] (default 30 min) as a fallback, and
 *   - sooner than that, on a doubling backoff, while the probe keeps failing.
 *
 * The backoff is the point of the failure bookkeeping, not politeness. A probe that throws leaves
 * the account's limit reading frozen at whatever it last was while the account still presents as
 * healthy and keeps taking traffic; waiting the full baseline to try again turns one dropped
 * connection into half an hour of routing against a stale reading. Observed as a seven-hour-old
 * reading on 2026-09-14, whose `resetAt` had passed four hours earlier and so rendered as the
 * most imminent recovery in the pool.
 */
class LimitScheduler(
    private val pool: AccountPool,
    private val probe: LimitProbe,
) {
    private val log = LoggerFactory.getLogger("LimitScheduler")
    private val enabled: Boolean get() = envOrProp("ENABLE_LIMIT_PROBE")?.lowercase() != "false"
    private val baselineInterval: Duration = Duration.ofSeconds(
        envOrProp("LIMIT_PROBE_INTERVAL_SECONDS")?.toLongOrNull() ?: 1800L,
    )
    private val tickMs = 60_000L
    /** First retry after a failure; doubles up to [baselineInterval]. */
    private val retryInterval: Duration = Duration.ofSeconds(
        envOrProp("LIMIT_PROBE_RETRY_SECONDS")?.toLongOrNull() ?: 60L,
    )

    private val lastProbe = HashMap<Int, Instant>()
    private val consecutiveFailures = HashMap<Int, Int>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        if (!enabled) {
            log.info("Active limit probing disabled (ENABLE_LIMIT_PROBE=false)")
            return@launch
        }
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("scheduler tick failed: {}", it.message) }
            delay(tickMs)
        }
    }

    private suspend fun tick() {
        val now = Instant.now()
        for (acc in pool.snapshot()) {
            val last = lastProbe[acc.id]
            val failures = consecutiveFailures[acc.id] ?: 0
            val baselineDue = last == null || Duration.between(last, now) >= interval(failures)
            val resetDue = acc.limit.windows.values.any { w ->
                val r = w.resetAt
                r != null && r.isBefore(now) && (last == null || r.isAfter(last))
            }
            if (baselineDue || resetDue) {
                lastProbe[acc.id] = now
                if (probe.probe(acc.id) == LimitProbe.Outcome.FAILED) {
                    val n = failures + 1
                    consecutiveFailures[acc.id] = n
                    log.warn(
                        "Probe for account {} has failed {} time(s) in a row; its limit reading is stale",
                        acc.id, n,
                    )
                } else {
                    consecutiveFailures.remove(acc.id)
                }
            }
        }
    }

    /**
     * How long to wait before the next probe of an account that has failed [failures] times in a
     * row. Doubles from [retryInterval], never past [baselineInterval] — a persistently broken
     * account settles back to the normal cadence rather than retrying forever at one minute.
     */
    internal fun interval(failures: Int): Duration {
        if (failures <= 0) return baselineInterval
        val backoff = retryInterval.multipliedBy(1L shl minOf(failures - 1, 16))
        return if (backoff < baselineInterval) backoff else baselineInterval
    }
}
