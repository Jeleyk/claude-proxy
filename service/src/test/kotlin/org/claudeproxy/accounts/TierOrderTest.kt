package org.claudeproxy.accounts

import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [AccountPool.tierOrder] ordering with the opt-in `overThreshold` fallback flag. tierOrder is a
 * pure function over the passed list, so we can exercise it without any DB or mutex state.
 */
class TierOrderTest {

    private fun acct(id: Int, priority: Int, usage: Double, overThreshold: Boolean, threshold: Double = 0.9) =
        AccountRuntime(
            id = id, name = "a$id", type = AccountType.OAUTH, groupId = null, ownerId = null,
            priority = priority, threshold = threshold, coefficient = 1.0, enabled = true,
            overThreshold = overThreshold, health = AccountHealth.OK, deviceId = null,
            secret = AccountSecret(),
            limit = LimitState(windows = mapOf(WindowKind.FIVE_HOUR to WindowLimit(utilization = usage))),
        )

    private val pool = AccountPool()

    @Test
    fun `under-threshold accounts come first in priority order`() {
        val a = acct(id = 1, priority = 20, usage = 0.1, overThreshold = false)
        val b = acct(id = 2, priority = 10, usage = 0.1, overThreshold = false)
        assertEquals(listOf(2, 1), pool.tierOrder(listOf(a, b)).map { it.id })
    }

    @Test
    fun `over-threshold account without the flag is dropped from selection`() {
        val under = acct(id = 1, priority = 10, usage = 0.1, overThreshold = false)
        val over = acct(id = 2, priority = 5, usage = 0.95, overThreshold = false)
        // `over` has better priority but is past threshold and did not opt in => excluded.
        assertEquals(listOf(1), pool.tierOrder(listOf(under, over)).map { it.id })
    }

    @Test
    fun `over-threshold account with the flag is appended as fallback`() {
        val under = acct(id = 1, priority = 10, usage = 0.1, overThreshold = false)
        val overOptedIn = acct(id = 2, priority = 5, usage = 0.95, overThreshold = true)
        // opted-in fallback still sorts AFTER any under-threshold account despite better priority.
        assertEquals(listOf(1, 2), pool.tierOrder(listOf(under, overOptedIn)).map { it.id })
    }

    @Test
    fun `when all are over threshold only opted-in accounts remain`() {
        val a = acct(id = 1, priority = 10, usage = 0.95, overThreshold = false)
        val b = acct(id = 2, priority = 20, usage = 0.99, overThreshold = true)
        val c = acct(id = 3, priority = 30, usage = 0.92, overThreshold = true)
        // a drops out; b and c survive, ordered by priority.
        assertEquals(listOf(2, 3), pool.tierOrder(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `opted-in fallbacks are ordered among themselves by priority`() {
        val b = acct(id = 2, priority = 30, usage = 0.95, overThreshold = true)
        val c = acct(id = 3, priority = 15, usage = 0.95, overThreshold = true)
        assertEquals(listOf(3, 2), pool.tierOrder(listOf(b, c)).map { it.id })
    }
}
