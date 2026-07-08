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
 *   - shortly after any limit window's reset time passes, and
 *   - otherwise every [baselineInterval] (default 30 min) as a fallback.
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

    private val lastProbe = HashMap<Int, Instant>()

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
            val baselineDue = last == null || Duration.between(last, now) >= baselineInterval
            val resetDue = acc.limit.windows.values.any { w ->
                val r = w.resetAt
                r != null && r.isBefore(now) && (last == null || r.isAfter(last))
            }
            if (baselineDue || resetDue) {
                lastProbe[acc.id] = now
                probe.probe(acc.id)
            }
        }
    }
}
