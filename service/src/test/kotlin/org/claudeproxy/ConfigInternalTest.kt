package org.claudeproxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigInternalTest {
    @Test
    fun `internalToken reads INTERNAL_TOKEN`() {
        System.setProperty("MASTER_KEY", "test-master-key-32-chars-minimum-xx")
        System.setProperty("INTERNAL_TOKEN", "secret123")
        try {
            val c = Config.load()
            assertEquals("secret123", c.internalToken)
        } finally {
            System.clearProperty("INTERNAL_TOKEN")
        }
    }

    @Test
    fun `redisUrl reads REDIS_URL`() {
        System.setProperty("MASTER_KEY", "test-master-key-32-chars-minimum-xx")
        System.setProperty("REDIS_URL", "redis://localhost:6379")
        try {
            val c = Config.load()
            assertEquals("redis://localhost:6379", c.redisUrl)
        } finally {
            System.clearProperty("REDIS_URL")
        }
    }

    @Test
    fun `internalToken and redisUrl are null when unset`() {
        System.setProperty("MASTER_KEY", "test-master-key-32-chars-minimum-xx")
        System.clearProperty("INTERNAL_TOKEN")
        System.clearProperty("REDIS_URL")
        val c = Config.load()
        assertNull(c.internalToken)
        assertNull(c.redisUrl)
    }
}
