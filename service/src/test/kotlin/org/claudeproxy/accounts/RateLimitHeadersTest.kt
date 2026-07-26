package org.claudeproxy.accounts

import org.claudeproxy.model.LimitState
import org.claudeproxy.model.WindowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the shape of Anthropic's unified rate-limit headers, including the credit ("extra
 * usage") and grace families that arrived with the 2.1 clients. Getting these wrong is not
 * cosmetic: a weekly window pinned at 100% while credits quietly serve the traffic looks
 * identical to a dead account.
 */
class RateLimitHeadersTest {

    @Test
    fun `parses the plain 5h and 7d windows`() {
        val s = RateLimitHeaders.parse(
            mapOf(
                "anthropic-ratelimit-unified-5h-utilization" to "0.14",
                "anthropic-ratelimit-unified-5h-status" to "allowed",
                "anthropic-ratelimit-unified-5h-reset" to "1783621800",
                "anthropic-ratelimit-unified-7d-utilization" to "0.38",
            ),
            LimitState(),
        )
        assertEquals(0.14, s.window(WindowKind.FIVE_HOUR)?.utilization)
        assertEquals(0.38, s.window(WindowKind.WEEKLY)?.utilization)
        assertEquals(1783621800L, s.window(WindowKind.FIVE_HOUR)?.resetAt?.epochSecond)
    }

    @Test
    fun `parses paid usage credits`() {
        val s = RateLimitHeaders.parse(
            mapOf(
                "anthropic-ratelimit-unified-7d-utilization" to "1.0",
                "anthropic-ratelimit-unified-7d_oi-utilization" to "0.42",
                "anthropic-ratelimit-unified-overage-status" to "available",
                "anthropic-ratelimit-unified-overage-in-use" to "true",
                "anthropic-ratelimit-unified-overage-period-monthly-utilization" to "0.31",
                "anthropic-ratelimit-unified-overage-period-channel-utilization" to "0.05",
                "anthropic-ratelimit-unified-overage-reset" to "1783621800",
            ),
            LimitState(),
        )
        val o = requireNotNull(s.overage)
        assertTrue(o.inUse, "this response was served from credits — i.e. it cost money")
        assertEquals("available", o.status)
        assertEquals(0.31, o.monthlyUtilization)
        assertEquals(0.31, o.spentFraction(), "the monthly budget wins when both are reported")
        assertEquals(0.05, o.channelUtilization)
        // The subscription week is full, but with credits included only 42% is gone.
        assertEquals(1.0, s.window(WindowKind.WEEKLY)?.utilization)
        assertEquals(0.42, o.weeklyWithOverage)
    }

    /**
     * The shape production actually sends (captured from live traffic): the credit allowance is
     * reported as its own `overage` window, and the disabled reason can be org-level.
     */
    @Test
    fun `parses the live overage window shape`() {
        val s = RateLimitHeaders.parse(
            mapOf(
                "anthropic-ratelimit-unified-5h-utilization" to "0.0",
                "anthropic-ratelimit-unified-7d-utilization" to "0.1",
                "anthropic-ratelimit-unified-overage-status" to "allowed",
                "anthropic-ratelimit-unified-overage-utilization" to "0.25",
                "anthropic-ratelimit-unified-overage-reset" to "1785542400",
                "anthropic-ratelimit-unified-status" to "allowed",
                "anthropic-ratelimit-unified-fallback-percentage" to "0.5",
            ),
            LimitState(),
        )
        val o = requireNotNull(s.overage)
        assertEquals("allowed", o.status)
        assertEquals(0.25, o.utilization)
        assertEquals(0.25, o.spentFraction(), "with no monthly figure, the overage window is the answer")
        assertEquals(1785542400L, o.resetAt?.epochSecond)
    }

    @Test
    fun `parses why credits are unavailable`() {
        val s = RateLimitHeaders.parse(
            mapOf(
                "anthropic-ratelimit-unified-overage-status" to "disabled",
                "anthropic-ratelimit-unified-overage-disabled-reason" to "extra_usage_disabled",
            ),
            LimitState(),
        )
        assertEquals("extra_usage_disabled", s.overage?.disabledReason)
        assertEquals("rejected", RateLimitHeaders.parse(
            mapOf(
                "anthropic-ratelimit-unified-overage-status" to "rejected",
                "anthropic-ratelimit-unified-overage-disabled-reason" to "org_level_disabled",
            ),
            LimitState(),
        ).overage?.status)
        assertEquals(false, s.overage?.inUse)
    }

    @Test
    fun `parses the grace allowance`() {
        val s = RateLimitHeaders.parse(
            mapOf(
                "anthropic-ratelimit-unified-grace-status" to "active",
                "anthropic-ratelimit-unified-grace-5h-utilization" to "0.25",
                "anthropic-ratelimit-unified-grace-7d-utilization" to "0",
            ),
            LimitState(),
        )
        val g = requireNotNull(s.grace)
        assertEquals(0.25, g.fiveHourUtilization)
        assertTrue(g.active())
    }

    /** A response that says nothing about credits must not erase what we already knew. */
    @Test
    fun `credit state survives a response that omits the headers`() {
        val first = RateLimitHeaders.parse(
            mapOf("anthropic-ratelimit-unified-overage-in-use" to "true"),
            LimitState(),
        )
        val second = RateLimitHeaders.parse(
            mapOf("anthropic-ratelimit-unified-5h-utilization" to "0.2"),
            first,
        )
        assertEquals(true, second.overage?.inUse)
        assertEquals(0.2, second.window(WindowKind.FIVE_HOUR)?.utilization)
    }

    @Test
    fun `a response with no rate-limit headers at all changes nothing`() {
        val before = LimitState()
        assertEquals(before, RateLimitHeaders.parse(mapOf("content-type" to "application/json"), before))
        assertNull(RateLimitHeaders.parse(emptyMap(), before).overage)
    }
}
