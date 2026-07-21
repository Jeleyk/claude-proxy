package org.claudeproxy.cache

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryCacheTest {
    @BeforeTest
    fun reset() = MemoryCache.clear()

    @Test
    fun `getOrLoad caches within TTL — loader runs once`() {
        var loaded = 0
        assertEquals("v", MemoryCache.getOrLoad("k", 60) { loaded++; "v" })
        assertEquals("v", MemoryCache.getOrLoad("k", 60) { loaded++; "v2" })
        assertEquals(1, loaded)
    }

    @Test
    fun `null loader result is not cached`() {
        var loaded = 0
        assertNull(MemoryCache.getOrLoad("k", 60) { loaded++; null })
        assertEquals("v", MemoryCache.getOrLoad("k", 60) { loaded++; "v" })
        assertEquals(2, loaded)
    }

    @Test
    fun `evict forces a reload`() {
        var loaded = 0
        MemoryCache.getOrLoad("k", 60) { loaded++; "v" }
        MemoryCache.evict("k")
        assertEquals("v2", MemoryCache.getOrLoad("k", 60) { loaded++; "v2" })
        assertEquals(2, loaded)
    }

    @Test
    fun `expired entry reloads`() {
        var loaded = 0
        MemoryCache.getOrLoad("k", 0) { loaded++; "v" }
        assertEquals("v2", MemoryCache.getOrLoad("k", 60) { loaded++; "v2" })
        assertEquals(2, loaded)
    }

    @Test
    fun `incrExistingByFloat bumps an existing numeric key`() {
        MemoryCache.getOrLoad("spend", 60) { "1.5" }
        MemoryCache.incrExistingByFloat("spend", 0.25)
        assertEquals("1.75", MemoryCache.getOrLoad("spend", 60) { "db" })
    }

    @Test
    fun `incrExistingByFloat on an absent key is a no-op`() {
        MemoryCache.incrExistingByFloat("spend", 0.25)
        // Next read seeds from the loader (the DB in production).
        assertEquals("9.0", MemoryCache.getOrLoad("spend", 60) { "9.0" })
    }
}
