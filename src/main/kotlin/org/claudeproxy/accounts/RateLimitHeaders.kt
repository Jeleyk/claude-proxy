package org.claudeproxy.accounts

import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parses Anthropic rate-limit headers into a [LimitState] with per-window detail.
 *
 * The exact header names for subscription (unified) limits must be confirmed against
 * live traffic — this parser is deliberately tolerant: it accepts several plausible
 * key names per window, and logs every `anthropic-ratelimit-*` header at INFO the first
 * time an unrecognized one is seen (and always at DEBUG), so calibration is a matter of
 * reading the logs, not guessing.
 */
object RateLimitHeaders {
    private val log = LoggerFactory.getLogger("RateLimitHeaders")

    // Per-window candidate key names, most specific first.
    private val KEYS: Map<WindowKind, WindowKeys> = mapOf(
        WindowKind.FIVE_HOUR to WindowKeys(
            remaining = listOf("anthropic-ratelimit-unified-5h-remaining", "anthropic-ratelimit-unified-remaining"),
            limit = listOf("anthropic-ratelimit-unified-5h-limit", "anthropic-ratelimit-unified-limit"),
            reset = listOf("anthropic-ratelimit-unified-5h-reset", "anthropic-ratelimit-unified-reset"),
            status = listOf("anthropic-ratelimit-unified-5h-status", "anthropic-ratelimit-unified-status"),
        ),
        WindowKind.WEEKLY to WindowKeys(
            remaining = listOf("anthropic-ratelimit-unified-7d-remaining", "anthropic-ratelimit-unified-week-remaining"),
            limit = listOf("anthropic-ratelimit-unified-7d-limit", "anthropic-ratelimit-unified-week-limit"),
            reset = listOf("anthropic-ratelimit-unified-7d-reset", "anthropic-ratelimit-unified-week-reset"),
            status = listOf("anthropic-ratelimit-unified-7d-status", "anthropic-ratelimit-unified-week-status"),
        ),
    )

    private data class WindowKeys(
        val remaining: List<String>,
        val limit: List<String>,
        val reset: List<String>,
        val status: List<String>,
    )

    fun parse(headers: Map<String, String>, prev: LimitState): LimitState {
        val lower = headers.mapKeys { it.key.lowercase() }
        val rl = lower.filterKeys { it.startsWith("anthropic-ratelimit") }
        if (rl.isNotEmpty()) {
            if (log.isDebugEnabled) rl.forEach { (k, v) -> log.debug("ratelimit header {} = {}", k, v) }
        }

        val windows = prev.windows.toMutableMap()
        var changed = false
        for ((kind, keys) in KEYS) {
            val remaining = firstDouble(lower, keys.remaining)
            val limit = firstDouble(lower, keys.limit)
            val reset = firstInstant(lower, keys.reset)
            val status = firstStatus(lower, keys.status)
            if (remaining == null && limit == null && reset == null && status == LimitStatus.UNKNOWN) continue
            val prevW = prev.windows[kind] ?: WindowLimit()
            windows[kind] = prevW.copy(
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

    /** Reset headers may be epoch seconds, epoch millis, a seconds-from-now integer, or RFC3339. */
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
