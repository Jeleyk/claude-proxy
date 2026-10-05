package org.claudeproxy.accounts

import org.claudeproxy.model.*
import java.time.Instant
import kotlin.test.*

class OpenAILimitsTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    @Test fun `known windows map by duration not primary ordering and clear recovered cooldown`() {
        val previous = LimitState(rateLimitedUntil = now.plusSeconds(3600))
        val result = OpenAILimits.parseUsage("""{"rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"used_percent":30,"limit_window_seconds":604800,"reset_at":1791500000},"secondary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":200}}}""", previous, now)!!
        assertEquals(0.42, result.window(WindowKind.FIVE_HOUR)!!.utilization)
        assertEquals(0.30, result.window(WindowKind.WEEKLY)!!.utilization)
        assertEquals(now.plusSeconds(200), result.window(WindowKind.FIVE_HOUR)!!.resetAt)
        assertNull(result.rateLimitedUntil)
    }
    @Test fun `unknown null and unexpected windows do not fabricate zero quota`() {
        for (body in listOf("{}", """{"rate_limit":null}""", """{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":900}}}"""))
            assertNull(OpenAILimits.parseUsage(body, LimitState(), now))
    }
    @Test fun `blocked quota and headers preserve exhaustion`() {
        val r = OpenAILimits.parseUsage("""{"rate_limit":{"allowed":false,"limit_reached":true,"primary_window":{"used_percent":100,"limit_window_seconds":18000,"reset_after_seconds":30}}}""", LimitState(), now)!!
        assertEquals(now.plusSeconds(30), r.rateLimitedUntil)
        assertEquals(LimitStatus.REJECTED, r.window(WindowKind.FIVE_HOUR)!!.status)
        val headers = mapOf("X-Codex-Secondary-Used-Percent" to "55", "X-Codex-Secondary-Window-Minutes" to "10080", "X-Codex-Secondary-Reset-At" to "1791500000")
        val updated = OpenAILimits.parseHeaders(headers, r, now)
        assertEquals(0.55, updated.window(WindowKind.WEEKLY)!!.utilization)
        assertEquals(r.rateLimitedUntil, updated.rateLimitedUntil)
        assertSame(updated, OpenAILimits.parseHeaders(emptyMap(), updated, now))
    }
    @Test fun `malformed fields and overflowing timestamps do not break quota reports`() {
        assertNull(OpenAILimits.parseUsage("""{"rate_limit":{"primary_window":{"used_percent":{},"limit_window_seconds":18000}}}""", LimitState(), now))
        val r = OpenAILimits.parseUsage("""{"rate_limit":{"allowed":{},"primary_window":{"used_percent":20,"limit_window_seconds":18000,"reset_at":9223372036854775807,"reset_after_seconds":9223372036854775807}}}""", LimitState(), now)!!
        assertEquals(0.2, r.window(WindowKind.FIVE_HOUR)!!.utilization)
        assertNull(r.window(WindowKind.FIVE_HOUR)!!.resetAt)
        val updated = OpenAILimits.parseHeaders(mapOf("x-codex-primary-used-percent" to "25", "x-codex-primary-window-minutes" to "300", "x-codex-primary-reset-at" to Long.MAX_VALUE.toString()), r, now)
        assertEquals(0.25, updated.window(WindowKind.FIVE_HOUR)!!.utilization)
        assertNull(updated.window(WindowKind.FIVE_HOUR)!!.resetAt)
    }
}
