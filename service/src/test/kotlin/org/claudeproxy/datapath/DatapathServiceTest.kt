package org.claudeproxy.datapath

import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.model.AccountType
import org.claudeproxy.repo.ProxyTokenRepo
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behavioral, DB-backed tests for [DatapathService] against throwaway SQLite. */
class DatapathServiceTest {
    private lateinit var dbFile: File
    private lateinit var pool: AccountPool
    private var adminId: Int = 0
    private lateinit var seededToken: String

    private fun config(dbPath: String) = Config(
        bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbPath,
        masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
        adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
        publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
        internalToken = null, redisUrl = null,
    )

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("datapath-test", ".db")
        val cfg = config(dbFile.absolutePath)
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        // The seeded bootstrap admin has PROXY_USE (all perms). Use it as the token owner.
        adminId = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.Users.selectAll().first()[org.claudeproxy.db.Users.id]
        }
        seededToken = ProxyTokenRepo.create(adminId, "test").token!!
        // One OAUTH account in the global pool.
        AccountRepo.create(
            name = "acc1", type = AccountType.OAUTH, groupId = null, priority = 10,
            threshold = 0.8, coefficient = 1.0,
            secret = AccountSecret(accessToken = "sk-ant-oat-TESTTOKEN", refreshToken = "r"),
            createdBy = adminId,
        )
        pool = AccountPool()
        runBlocking { pool.reload() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
    }

    @Test
    fun `resolve returns BAD_TOKEN for unknown token`() = runBlocking {
        val r = DatapathService(pool).resolve("cxp_nope", "POST", "/v1/messages")
        assertEquals(ResolveError.BAD_TOKEN, r.error)
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun `resolve orders candidates and includes decrypted auth headers`() = runBlocking {
        val r = DatapathService(pool).resolve(seededToken, "POST", "/v1/messages")
        assertEquals(adminId, r.userId)
        assertTrue(r.candidates.isNotEmpty())
        val c = r.candidates.first()
        assertEquals("Bearer sk-ant-oat-TESTTOKEN", c.authHeaders["Authorization"])
        assertEquals("oauth-2025-04-20", c.authHeaders["anthropic-beta"])
    }

    @Test
    fun `free path count_tokens resolves an any-account candidate ignoring limits`() = runBlocking {
        val r = DatapathService(pool).resolve(seededToken, "POST", "/v1/messages/count_tokens")
        assertTrue(r.candidates.isNotEmpty())
    }
}
