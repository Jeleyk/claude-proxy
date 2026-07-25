package org.claudeproxy.datapath

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-flight datapath requests — the ones being streamed from Anthropic *right now*.
 *
 * A session opens when a request resolves and closes when the gateway says it's done. The Go
 * gateway can't be trusted to always send that close (a killed container, a dropped control-API
 * call), so entries also expire on their own: anything older than [TTL] is treated as finished.
 * The ceiling is generous because a single Opus stream legitimately runs for many minutes, and
 * over-counting a stale session is a smaller lie than dropping a live one.
 *
 * Deliberately in-process and unpersisted: this is a liveness gauge, not accounting. A restart
 * shows zero, which is exactly right — nothing is streaming through a process that just started.
 */
object ActiveSessions {
    private val TTL: Duration = Duration.ofMinutes(30)

    private data class Session(val userId: Int?, val routing: Boolean, val startedAt: Instant)

    private val open = ConcurrentHashMap<String, Session>()

    /** Counts of live sessions, split by datapath. */
    data class Counts(val proxy: Int, val routing: Int) {
        val total: Int get() = proxy + routing
    }

    fun begin(id: String, userId: Int?, routing: Boolean, now: Instant = Instant.now()) {
        sweep(now)
        open[id] = Session(userId, routing, now)
    }

    fun end(id: String) {
        open.remove(id)
    }

    /** Live sessions across every user — the pool-wide "what's running" number. */
    fun counts(now: Instant = Instant.now()): Counts = count(now) { true }

    /** Live sessions belonging to one user. */
    fun countsForUser(userId: Int, now: Instant = Instant.now()): Counts = count(now) { it.userId == userId }

    private inline fun count(now: Instant, predicate: (Session) -> Boolean): Counts {
        sweep(now)
        var proxy = 0
        var routing = 0
        open.values.forEach { s ->
            if (!predicate(s)) return@forEach
            if (s.routing) routing++ else proxy++
        }
        return Counts(proxy, routing)
    }

    /** Drop sessions whose close never arrived. Cheap enough to run on every read. */
    private fun sweep(now: Instant) {
        val cutoff = now.minus(TTL)
        open.entries.removeIf { it.value.startedAt.isBefore(cutoff) }
    }

    /** Test seam — the map is process-global. */
    internal fun clear() = open.clear()
}
