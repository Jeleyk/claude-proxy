package org.claudeproxy.repo

import org.claudeproxy.Config
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.cache.RedisCache
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

/** With Redis disabled the token cache must be fully transparent (DB fallback). */
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
            internalToken = null, redisUrl = null,
        )
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        RedisCache.init(null) // disabled
    }

    @AfterTest
    fun teardown() = dbFile.delete().let {}

    @Test
    fun `resolveUser returns the owner with Redis disabled`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val token = ProxyTokenRepo.create(adminId, "t").token!!
        assertEquals(adminId, ProxyTokenRepo.resolveUser(token))
    }

    @Test
    fun `deleted token no longer resolves`() {
        val adminId = transaction { Users.selectAll().first()[Users.id] }
        val dto = ProxyTokenRepo.create(adminId, "t2")
        assertEquals(adminId, ProxyTokenRepo.resolveUser(dto.token!!))
        ProxyTokenRepo.delete(dto.id, adminId)
        assertNull(ProxyTokenRepo.resolveUser(dto.token!!))
    }
}
