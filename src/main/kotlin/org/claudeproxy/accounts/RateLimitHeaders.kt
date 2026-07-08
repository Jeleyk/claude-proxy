package org.claudeproxy.accounts

import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parses Anthropic rate-limit headers into a [LimitState].
 *
 * The exact header names for subscription (unified) limits must be confirmed against
 * live traffic — this parser is deliberately tolerant: it accepts the `unified` family,
 * an optional `5h` window variant, and logs every `anthropic-ratelimit-*` header at DEBUG
 * so calibration is a matter of reading the logs, not guessing.
 */
object RateLimitHeaders {
    private val log = LoggerFactory.getLogger("RateLimitHeaders")

    // Preference order: an explicit 5h window beats the generic unified family.
    private val REMAINING_KEYS = listOf(
        "anthropic-ratelimit-unified-5h-remaining",
        "anthropic-ratelimit-unified-remaining",
    )
    private val LIMIT_KEYS = listOf(
        "anthropic-ratelimit-unified-5h-limit",
        "anthropic-ratelimit-unified-limit",
    )
    private val RESET_KEYS = listOf(
        "anthropic-ratelimit-unified-5h-reset",
        "anthropic-ratelimit-unified-reset",
    )
    private val STATUS_KEYS = listOf(
        "anthropic-ratelimit-unified-5h-status",
        "anthropic-ratelimit-unified-status",
    )

    /**
     * @param headers case-insensitive map of response headers (any casing accepted).
     * @param prev the previous known limit state, to preserve fields not present this time.
     */
    fun parse(headers: Map<String, String>, prev: LimitState): LimitState {
        val lower = headers.mapKeys { it.key.lowercase() }

        if (log.isDebugEnabled) {
            lower.filterKeys { it.startsWith("anthropic-ratelimit") }
                .forEach { (k, v) -> log.debug("ratelimit header {} = {}", k, v) }
        }

        val remaining = firstDouble(lower, REMAINING_KEYS)
        val limit = firstDouble(lower, LIMIT_KEYS)
        val reset = firstInstant(lower, RESET_KEYS)
        val status = firstStatus(lower, STATUS_KEYS)

        // Nothing recognized — keep previous state.
        if (remaining == null && limit == null && reset == null && status == LimitStatus.UNKNOWN) {
            return prev
        }

        return prev.copy(
            remaining = remaining ?: prev.remaining,
            limitTotal = limit ?: prev.limitTotal,
            resetAt = reset ?: prev.resetAt,
            status = if (status != LimitStatus.UNKNOWN) status else prev.status,
            updatedAt = Instant.now(),
        )
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
                    asLong > 100_000_000_000L -> Instant.ofEpochMilli(asLong)     // epoch millis
                    asLong > 1_000_000_000L -> Instant.ofEpochSecond(asLong)      // epoch seconds
                    else -> Instant.now().plusSeconds(asLong)                     // seconds-from-now
                }
            }
            try {
                return Instant.parse(raw)
            } catch (_: DateTimeParseException) {
                // ignore, try next key
            }
        }
        return null
    }
}
