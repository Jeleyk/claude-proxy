package org.claudeproxy.accounts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retry gate that keeps a dead refresh token from hitting Anthropic's token endpoint once
 * a minute. Pure policy: attempt allowed / next attempt due, no clock and no network.
 */
class RefreshBackoffTest {

    private val base = 30 * 60 * 1000L
    private val max = 6 * 60 * 60 * 1000L
    private val backoff = RefreshBackoff(baseMs = base, maxMs = max)

    @Test
    fun `an account that never failed is always allowed`() {
        assertTrue(backoff.allowed(1, "rt", now = 0))
    }

    @Test
    fun `a failure parks the account for the base interval`() {
        val next = backoff.onFailure(1, "rt", now = 1_000, permanent = false)
        assertEquals(1_000 + base, next)
        assertFalse(backoff.allowed(1, "rt", now = 1_000 + base - 1))
        assertTrue(backoff.allowed(1, "rt", now = 1_000 + base))
    }

    @Test
    fun `repeated failures double the wait up to the cap`() {
        assertEquals(base, backoff.onFailure(1, "rt", now = 0, permanent = false))
        assertEquals(2 * base, backoff.onFailure(1, "rt", now = 0, permanent = false))
        assertEquals(4 * base, backoff.onFailure(1, "rt", now = 0, permanent = false))
        repeat(10) { backoff.onFailure(1, "rt", now = 0, permanent = false) }
        assertEquals(max, backoff.onFailure(1, "rt", now = 0, permanent = false))
    }

    @Test
    fun `a permanently invalid grant waits the full cap right away`() {
        assertEquals(max, backoff.onFailure(1, "rt", now = 0, permanent = true))
    }

    @Test
    fun `a re-authorized account is retried immediately`() {
        backoff.onFailure(1, "old-token", now = 0, permanent = true)
        assertFalse(backoff.allowed(1, "old-token", now = 1))
        assertTrue(backoff.allowed(1, "fresh-token", now = 1))
    }

    @Test
    fun `a successful refresh clears the backoff`() {
        backoff.onFailure(1, "rt", now = 0, permanent = true)
        backoff.onSuccess(1)
        assertTrue(backoff.allowed(1, "rt", now = 1))
        // and the next failure starts counting from the base interval again
        assertEquals(base, backoff.onFailure(1, "rt", now = 0, permanent = false))
    }

    @Test
    fun `backoff is per account`() {
        backoff.onFailure(1, "rt", now = 0, permanent = true)
        assertTrue(backoff.allowed(2, "rt", now = 1))
    }
}
