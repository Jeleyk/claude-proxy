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
import kotlin.test.assertNull

/** The in-process token cache must be transparent: resolve, cache, and evict on delete. */
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
    fun `legacy cached value without token id parses gracefully`() {
        assertEquals(TokenAuth(5, null), parseTokenAuth("5"))
        assertEquals(TokenAuth(5, 12), parseTokenAuth("5:12"))
        assertNull(parseTokenAuth(null))
        assertNull(parseTokenAuth("garbage"))
    }
}
