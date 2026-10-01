package org.claudeproxy.repo

import org.claudeproxy.Config
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.Users
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-process token cache must be transparent: resolve, cache, and evict on delete or on a
 * disable/enable switch — a token switched off must stop resolving at once, not after the TTL.
 */
class ProxyTokenCacheTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("token-cache-test", ".db")
        val cfg = Config(
            bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbFile.absolutePath,
            masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
            adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
            publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
            internalToken = null,
        )
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        MemoryCache.clear() // fresh DB per test — drop entries cached by earlier tests
    }

    @AfterTest
    fun teardown() = dbFile.delete().let {}

    @Test
    fun `resolveAuth returns the owner and token id`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val dto = ProxyTokenRepo.create(adminId, "t")
        assertEquals(TokenAuth(adminId, dto.id), ProxyTokenRepo.resolveAuth(dto.token!!))
    }

    @Test
    fun `deleted token no longer resolves`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val dto = ProxyTokenRepo.create(adminId, "t2")
        assertEquals(TokenAuth(adminId, dto.id), ProxyTokenRepo.resolveAuth(dto.token!!))
        ProxyTokenRepo.delete(dto.id, adminId)
        assertNull(ProxyTokenRepo.resolveAuth(dto.token!!))
    }

    @Test
    fun `disabled token stops resolving and resolves again once re-enabled`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val dto = ProxyTokenRepo.create(adminId, "t3")
        // Resolve first so the mapping is cached: the switch must evict, not wait out the TTL.
        assertEquals(TokenAuth(adminId, dto.id), ProxyTokenRepo.resolveAuth(dto.token!!))

        assertTrue(ProxyTokenRepo.setEnabled(dto.id, adminId, false))
        assertNull(ProxyTokenRepo.resolveAuth(dto.token!!))
        assertFalse(ProxyTokenRepo.listForUser(adminId).first { it.id == dto.id }.enabled)

        assertTrue(ProxyTokenRepo.setEnabled(dto.id, adminId, true))
        assertEquals(TokenAuth(adminId, dto.id), ProxyTokenRepo.resolveAuth(dto.token!!))
    }

    @Test
    fun `tokens are enabled by default and only their owner may switch them`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val dto = ProxyTokenRepo.create(adminId, "t4")
        assertTrue(dto.enabled)
        assertFalse(ProxyTokenRepo.setEnabled(dto.id, adminId + 1, false))
        assertEquals(TokenAuth(adminId, dto.id), ProxyTokenRepo.resolveAuth(dto.token!!))
    }

    @Test
    fun `routing tokens switch off and on the same way`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val dto = RoutingTokenRepo.create(adminId, "r1")
        assertTrue(dto.enabled)
        assertEquals(TokenAuth(adminId, dto.id), RoutingTokenRepo.resolveAuth(dto.token!!))

        assertTrue(RoutingTokenRepo.setEnabled(dto.id, adminId, false))
        assertNull(RoutingTokenRepo.resolveAuth(dto.token!!))

        assertTrue(RoutingTokenRepo.setEnabled(dto.id, adminId, true))
        assertEquals(TokenAuth(adminId, dto.id), RoutingTokenRepo.resolveAuth(dto.token!!))
    }

    @Test
    fun `legacy cached value without token id parses gracefully`() {
        assertEquals(TokenAuth(5, null), parseTokenAuth("5"))
        assertEquals(TokenAuth(5, 12), parseTokenAuth("5:12"))
        assertNull(parseTokenAuth(null))
        assertNull(parseTokenAuth("garbage"))
    }

    @Test
    fun `disabling the owner revokes both token kinds at once, cache or not`() {
        val bob = UserRepo.create("bob", "pw", emptyList(), emptyList(), null)
        val proxy = ProxyTokenRepo.create(bob, "p")
        val routing = RoutingTokenRepo.create(bob, "r")
        // Cached first: the disable has to evict, not wait out the 60s TTL.
        assertEquals(TokenAuth(bob, proxy.id), ProxyTokenRepo.resolveAuth(proxy.token!!))
        assertEquals(TokenAuth(bob, routing.id), RoutingTokenRepo.resolveAuth(routing.token!!))

        UserRepo.update(bob, null, false, null, null, null, false)
        assertNull(ProxyTokenRepo.resolveAuth(proxy.token!!))
        assertNull(RoutingTokenRepo.resolveAuth(routing.token!!))

        UserRepo.update(bob, null, true, null, null, null, false)
        assertEquals(TokenAuth(bob, proxy.id), ProxyTokenRepo.resolveAuth(proxy.token!!))
    }
}
