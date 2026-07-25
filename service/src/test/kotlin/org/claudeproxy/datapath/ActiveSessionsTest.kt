package org.claudeproxy.datapath

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The map is process-global, so each test starts and leaves it empty. */
class ActiveSessionsTest {
    private val t0: Instant = Instant.parse("2026-07-25T12:00:00Z")

    @BeforeTest fun setUp() = ActiveSessions.clear()
    @AfterTest fun tearDown() = ActiveSessions.clear()

    @Test
    fun `sessions are counted per datapath and summed`() {
        ActiveSessions.begin("a", 1, routing = false, now = t0)
        ActiveSessions.begin("b", 1, routing = false, now = t0)
        ActiveSessions.begin("c", 2, routing = true, now = t0)

        val all = ActiveSessions.counts(t0)
        assertEquals(2, all.proxy)
        assertEquals(1, all.routing)
        assertEquals(3, all.total)
    }

    @Test
    fun `per-user counts only include that user's sessions`() {
        ActiveSessions.begin("a", 1, routing = false, now = t0)
        ActiveSessions.begin("b", 2, routing = false, now = t0)
        ActiveSessions.begin("c", 1, routing = true, now = t0)

        assertEquals(ActiveSessions.Counts(1, 1), ActiveSessions.countsForUser(1, t0))
        assertEquals(ActiveSessions.Counts(1, 0), ActiveSessions.countsForUser(2, t0))
    }

    @Test
    fun `ending a session removes it, and ending it twice is harmless`() {
        ActiveSessions.begin("a", 1, routing = false, now = t0)
        ActiveSessions.end("a")
        ActiveSessions.end("a")
        ActiveSessions.end("never-existed")
        assertEquals(0, ActiveSessions.counts(t0).total)
    }

    @Test
    fun `a session whose close never arrived expires instead of counting forever`() {
        ActiveSessions.begin("stuck", 1, routing = false, now = t0)
        // 29 minutes in it's still plausibly a long Opus stream…
        assertEquals(1, ActiveSessions.counts(t0.plusSeconds(29 * 60)).total)
        // …past the 30-minute ceiling it's treated as lost.
        assertEquals(0, ActiveSessions.counts(t0.plusSeconds(31 * 60)).total)
    }

    @Test
    fun `re-registering the same id does not double count`() {
        ActiveSessions.begin("a", 1, routing = false, now = t0)
        ActiveSessions.begin("a", 1, routing = false, now = t0)
        assertEquals(1, ActiveSessions.counts(t0).total)
    }

    @Test
    fun `sessions without a user still count pool-wide`() {
        ActiveSessions.begin("anon", null, routing = true, now = t0)
        assertEquals(1, ActiveSessions.counts(t0).routing)
        assertEquals(0, ActiveSessions.countsForUser(1, t0).total)
    }
}
