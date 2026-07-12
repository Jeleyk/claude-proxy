package org.claudeproxy.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedisCacheTest {
    @Test
    fun `getOrLoad falls through to loader when Redis is absent`() {
        RedisCache.init(null)
        assertFalse(RedisCache.enabled)
        var loaded = 0
        val v = RedisCache.getOrLoad("k", 60) { loaded++; "v" }
        assertEquals("v", v)
        assertEquals(1, loaded)
    }

    @Test
    fun `unreachable Redis degrades gracefully (still disabled, loader used)`() {
        RedisCache.init("redis://127.0.0.1:1")
        assertFalse(RedisCache.enabled)
        assertEquals("db", RedisCache.getOrLoad("k", 60) { "db" })
        // evict/publish are no-ops, must not throw.
        RedisCache.evict("k")
        RedisCache.publishInvalidate("pool")
    }
}
