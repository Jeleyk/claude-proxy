package org.claudeproxy.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.slf4j.LoggerFactory

/**
 * Service-side Redis accelerator: a cache-with-DB-fallback plus pub/sub cache invalidation.
 * Redis is **never** a source of truth — every miss (or any Redis failure) falls through to the
 * DB loader, so an empty or restarted Redis is only ever a cold cache, never wrong data.
 *
 * When [init] is given a null/blank URL, or the connection fails, [enabled] stays false and all
 * methods degrade to the DB path (getOrLoad calls the loader directly; evict/publish are no-ops).
 * This is the durability guarantee the design requires.
 */
object RedisCache {
    private val log = LoggerFactory.getLogger("RedisCache")

    @Volatile
    var enabled: Boolean = false
        private set

    private var client: RedisClient? = null
    private var conn: StatefulRedisConnection<String, String>? = null
    private var commands: RedisCommands<String, String>? = null

    /** Local listeners invoked when an invalidation message arrives on [INVALIDATE_CHANNEL]. */
    private val invalidationListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    const val INVALIDATE_CHANNEL = "cp:invalidate"

    /** Connect to Redis. Tolerates an absent/unreachable server by staying disabled (DB-only). */
    fun init(url: String?) {
        if (url.isNullOrBlank()) {
            log.info("RedisCache disabled (no REDIS_URL) — cache is DB-only")
            return
        }
        runCatching {
            val c = RedisClient.create(url)
            val connection = c.connect()
            // Fail fast if the server is unreachable.
            connection.sync().ping()
            client = c
            conn = connection
            commands = connection.sync()
            subscribeInvalidations(c)
            enabled = true
            log.info("RedisCache enabled ({})", url.substringBefore('@').substringBefore("://").let { "redis" })
        }.onFailure {
            log.warn("RedisCache init failed ({}); falling back to DB-only cache", it.message)
            enabled = false
        }
    }

    /**
     * Return the cached value for [key], or compute it via [load], cache it under [ttlSeconds],
     * and return it. Any Redis error falls back to [load] (logged once). A null loader result is
     * not cached.
     */
    fun getOrLoad(key: String, ttlSeconds: Long, load: () -> String?): String? {
        val cmds = commands
        if (!enabled || cmds == null) return load()
        val hit = runCatching { cmds.get(key) }.getOrNull()
        if (hit != null) return hit
        val value = load() ?: return null
        runCatching { cmds.setex(key, ttlSeconds, value) }
            .onFailure { log.debug("redis setex failed for {}: {}", key, it.message) }
        return value
    }

    /**
     * Increment an **existing** numeric key by [delta] (no-op if the key is absent or Redis is
     * disabled). "Existing only" is deliberate: the daily-spend key is seeded from the DB on a
     * read; if it isn't cached yet, the next read recomputes from the DB (which already includes
     * the just-recorded usage), so skipping the increment can never under- or over-count.
     */
    fun incrExistingByFloat(key: String, delta: Double) {
        val cmds = commands ?: return
        runCatching { if ((cmds.exists(key) ?: 0L) > 0L) cmds.incrbyfloat(key, delta) }
    }

    /** Evict a single key (no-op when disabled). */
    fun evict(key: String) {
        val cmds = commands ?: return
        runCatching { cmds.del(key) }
    }

    /** Publish a cache-invalidation message to all service instances (no-op when disabled). */
    fun publishInvalidate(msg: String) {
        val cmds = commands ?: return
        runCatching { cmds.publish(INVALIDATE_CHANNEL, msg) }
    }

    /** Register a local handler for invalidation messages (e.g. to evict in-process caches). */
    fun onInvalidate(listener: (String) -> Unit) {
        invalidationListeners.add(listener)
    }

    private fun subscribeInvalidations(c: RedisClient) {
        runCatching {
            val pubSub = c.connectPubSub()
            pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == INVALIDATE_CHANNEL) {
                        invalidationListeners.forEach { l -> runCatching { l(message) } }
                    }
                }
            })
            pubSub.sync().subscribe(INVALIDATE_CHANNEL)
        }.onFailure { log.warn("redis pub/sub subscribe failed: {}", it.message) }
    }
}
