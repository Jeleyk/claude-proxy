package org.claudeproxy.accounts

import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parses Anthropic subscription rate-limit headers into a [LimitState].
 *
 * Calibrated against live traffic. Anthropic reports usage as a direct 0..1 `utilization`
 * per window, plus a `status` and epoch-seconds `reset`, e.g.:
 *   anthropic-ratelimit-unified-5h-utilization: 0.14
 *   anthropic-ratelimit-unified-5h-status: allowed
 *   anthropic-ratelimit-unified-5h-reset: 1783621800
 *   anthropic-ratelimit-unified-7d-utilization: 0.38
 * There is no remaining/limit header for subscriptions; those are only parsed as a
 * fallback for API-key style limits. Every `anthropic-ratelimit-*` header is logged once
 * per shape at INFO for ongoing calibration.
 */
object RateLimitHeaders {
    private val log = LoggerFactory.getLogger("RateLimitHeaders")
    private val loggedSignatures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private data class WindowKeys(
        val utilization: List<String>,
        val remaining: List<String>,
        val limit: List<String>,
        val reset: List<String>,
        val status: List<String>,
    )

    private val KEYS: Map<WindowKind, WindowKeys> = mapOf(
        WindowKind.FIVE_HOUR to WindowKeys(
            utilization = listOf("anthropic-ratelimit-unified-5h-utilization"),
            remaining = listOf("anthropic-ratelimit-unified-5h-remaining", "anthropic-ratelimit-tokens-remaining"),
            limit = listOf("anthropic-ratelimit-unified-5h-limit", "anthropic-ratelimit-tokens-limit"),
            reset = listOf("anthropic-ratelimit-unified-5h-reset", "anthropic-ratelimit-unified-reset", "anthropic-ratelimit-tokens-reset"),
            status = listOf("anthropic-ratelimit-unified-5h-status", "anthropic-ratelimit-unified-status"),
        ),
        WindowKind.WEEKLY to WindowKeys(
            utilization = listOf("anthropic-ratelimit-unified-7d-utilization"),
            remaining = listOf("anthropic-ratelimit-unified-7d-remaining"),
            limit = listOf("anthropic-ratelimit-unified-7d-limit"),
            reset = listOf("anthropic-ratelimit-unified-7d-reset"),
            status = listOf("anthropic-ratelimit-unified-7d-status"),
        ),
    )

    fun parse(headers: Map<String, String>, prev: LimitState): LimitState {
        val lower = headers.mapKeys { it.key.lowercase() }
        val rl = lower.filterKeys { it.startsWith("anthropic-ratelimit") }
        if (rl.isNotEmpty()) {
            val signature = rl.keys.sorted().joinToString(",")
            if (loggedSignatures.add(signature)) {
                log.info("observed rate-limit headers: {}", rl.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
        }

        val windows = prev.windows.toMutableMap()
        var changed = false
        for ((kind, keys) in KEYS) {
            val util = firstDouble(lower, keys.utilization)
            val remaining = firstDouble(lower, keys.remaining)
            val limit = firstDouble(lower, keys.limit)
            val reset = firstInstant(lower, keys.reset)
            val status = firstStatus(lower, keys.status)
            if (util == null && remaining == null && limit == null && reset == null && status == LimitStatus.UNKNOWN) continue
            val prevW = prev.windows[kind] ?: WindowLimit()
            windows[kind] = prevW.copy(
                utilization = util ?: prevW.utilization,
                remaining = remaining ?: prevW.remaining,
                limitTotal = limit ?: prevW.limitTotal,
                resetAt = reset ?: prevW.resetAt,
                status = if (status != LimitStatus.UNKNOWN) status else prevW.status,
                updatedAt = Instant.now(),
            )
            changed = true
        }
        if (!changed) return prev
        return prev.copy(windows = windows, updatedAt = Instant.now())
    }

    private fun firstDouble(h: Map<String, String>, keys: List<String>): Double? {
        for (k in keys) h[k]?.trim()?.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun firstStatus(h: Map<String, String>, keys: List<String>): LimitStatus {
        for (k in keys) {
            val v = h[k]?.trim()?.lowercase() ?: continue
            return when {
                v.contains("reject") -> LimitStatus.REJECTED
                v.contains("warn") -> LimitStatus.ALLOWED_WARNING
                v.contains("allow") -> LimitStatus.ALLOWED
                else -> LimitStatus.UNKNOWN
            }
        }
        return LimitStatus.UNKNOWN
    }

    private fun firstInstant(h: Map<String, String>, keys: List<String>): Instant? {
        for (k in keys) {
            val raw = h[k]?.trim() ?: continue
            val asLong = raw.toLongOrNull()
            if (asLong != null) {
                return when {
                    asLong > 100_000_000_000L -> Instant.ofEpochMilli(asLong)
                    asLong > 1_000_000_000L -> Instant.ofEpochSecond(asLong)
                    else -> Instant.now().plusSeconds(asLong)
                }
            }
            try {
                return Instant.parse(raw)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }
}
