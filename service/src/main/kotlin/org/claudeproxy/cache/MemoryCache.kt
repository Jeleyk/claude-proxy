package org.claudeproxy.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process TTL cache keeping hot-path lookups (token resolve, daily spend) off the DB.
 * Never a source of truth — every miss falls through to the DB loader, so a restart is only
 * ever a cold cache, never wrong data.
 *
 * Single-instance by design: the service is the only process that reads or writes these keys
 * (the Go gateways go through the control API, not the cache). If the service is ever scaled
 * horizontally, this must become a shared cache again — see the Redis implementation in git
 * history (`RedisCache`, removed 2026-07).
 */
object MemoryCache {
    private class Entry(val value: String, val expiresAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    // Purge expired entries once the map grows past this; keys are bounded (one per live token
    // plus one spend key per user per day), so this only guards against day-key accumulation.
    private const val SWEEP_THRESHOLD = 10_000

    /**
     * Return the cached value for [key], or compute it via [load], cache it for [ttlSeconds],
     * and return it. A null loader result is not cached.
     */
    fun getOrLoad(key: String, ttlSeconds: Long, load: () -> String?): String? {
        val now = System.currentTimeMillis()
        entries[key]?.let { if (it.expiresAtMs > now) return it.value else entries.remove(key) }
        val value = load() ?: return null
        if (entries.size > SWEEP_THRESHOLD) sweep(now)
        entries[key] = Entry(value, now + ttlSeconds * 1000)
        return value
    }

    /**
     * Increment an **existing** numeric key by [delta] (no-op if the key is absent or expired).
     * "Existing only" is deliberate: the daily-spend key is seeded from the DB on a read; if it
     * isn't cached yet, the next read recomputes from the DB (which already includes the
     * just-recorded usage), so skipping the increment can never under- or over-count.
     */
    fun incrExistingByFloat(key: String, delta: Double) {
        val now = System.currentTimeMillis()
        entries.computeIfPresent(key) { _, e ->
            val n = e.value.toDoubleOrNull()
            if (e.expiresAtMs <= now || n == null) null else Entry((n + delta).toString(), e.expiresAtMs)
        }
    }

    /** Evict a single key (a revoked token must stop resolving immediately, not after TTL). */
    fun evict(key: String) {
        entries.remove(key)
    }

    /** Drop everything. Test isolation only — production code never needs it. */
    fun clear() {
        entries.clear()
    }

    private fun sweep(now: Long) {
        entries.entries.removeIf { it.value.expiresAtMs <= now }
    }
}
