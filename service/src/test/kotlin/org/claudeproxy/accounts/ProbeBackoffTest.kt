package org.claudeproxy.accounts

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LimitScheduler.interval] — how soon a failing probe is retried. The account keeps taking
 * traffic while its reading is frozen, so the cost of a long wait is routing against a stale
 * number, not a missed graph point.
 */
class ProbeBackoffTest {

    private val scheduler = LimitScheduler(AccountPool(), LimitProbe(AccountPool(), "https://example.invalid"))

    @Test
    fun `a healthy account waits the full baseline`() {
        assertEquals(Duration.ofSeconds(1800), scheduler.interval(0))
    }

    @Test
    fun `the first failure retries in a minute, not in thirty`() {
        assertEquals(Duration.ofSeconds(60), scheduler.interval(1))
    }

    @Test
    fun `the backoff doubles`() {
        assertEquals(Duration.ofSeconds(120), scheduler.interval(2))
        assertEquals(Duration.ofSeconds(240), scheduler.interval(3))
        assertEquals(Duration.ofSeconds(480), scheduler.interval(4))
        assertEquals(Duration.ofSeconds(960), scheduler.interval(5))
    }

    @Test
    fun `the backoff never exceeds the baseline, and does not overflow`() {
        assertEquals(Duration.ofSeconds(1800), scheduler.interval(6))
        assertEquals(Duration.ofSeconds(1800), scheduler.interval(50))
        assertEquals(Duration.ofSeconds(1800), scheduler.interval(Int.MAX_VALUE))
    }
}
