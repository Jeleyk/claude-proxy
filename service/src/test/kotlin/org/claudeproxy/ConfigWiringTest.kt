package org.claudeproxy

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.LimitProbe
import org.claudeproxy.accounts.LimitScheduler
import org.claudeproxy.accounts.TokenRefresher
import org.claudeproxy.auth.TEST_SECRET
import org.claudeproxy.auth.freshDb
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.proxy.ProxyEngine
import org.claudeproxy.proxy.UpstreamForwarder
import org.claudeproxy.repo.IdentityRepo
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

/** OIDC_BLOCK_PASSWORD_FOR_LINKED from the environment all the way to the login route, with OIDC off. */
class ConfigWiringTest {
    private val keys = listOf("MASTER_KEY", "OIDC_ENABLED", "OIDC_BLOCK_PASSWORD_FOR_LINKED")

    private fun withProps(vararg kv: Pair<String, String>, block: () -> Unit) {
        val saved = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach { System.clearProperty(it) }
            kv.forEach { (k, v) -> System.setProperty(k, v) }
            block()
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }

    @Test
    fun `Config load keeps the password block on by default, also with OIDC off`() {
        assumeNoEnv()
        withProps("MASTER_KEY" to TEST_SECRET) {
            val c = Config.load()
            assertNull(c.oidc)
            assertTrue(c.blockPasswordForLinked)
        }
        withProps("MASTER_KEY" to TEST_SECRET, "OIDC_ENABLED" to "false") { assertTrue(Config.load().blockPasswordForLinked) }
        withProps("MASTER_KEY" to TEST_SECRET, "OIDC_BLOCK_PASSWORD_FOR_LINKED" to "false") { assertFalse(Config.load().blockPasswordForLinked) }
    }

    @Test
    fun `module wires the block into the login route even without an OIDC config`() {
        val db = freshDb()
        try {
            transaction { IdentityRepo.link(1, "https://idp.example.com", "sub-admin") }
            for ((block, expected) in listOf(true to HttpStatusCode.Forbidden, false to HttpStatusCode.OK)) {
                val config = Config(
                    bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = db.absolutePath,
                    masterKey = TEST_SECRET, sessionSecret = TEST_SECRET, adminUser = "admin", adminPassword = "admin",
                    upstreamBaseUrl = "http://127.0.0.1:1", publicBaseUrl = "", databaseUrl = "",
                    databaseUser = "claudeproxy", databasePassword = "", internalToken = null,
                    oidc = null, blockPasswordForLinked = block,
                )
                testApplication {
                    application {
                        val pool = AccountPool()
                        val probe = LimitProbe(pool, config.upstreamBaseUrl)
                        module(config, pool, ProxyEngine(pool, UpstreamForwarder(pool, config.upstreamBaseUrl)),
                            TokenRefresher(pool), probe, LimitScheduler(pool, probe))
                    }
                    val r = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"admin","password":"admin"}""") }
                    assertEquals(expected, r.status, "block=$block ${r.bodyAsText()}")
                }
            }
        } finally { MemoryCache.clear(); db.delete() }
    }

    /** A real OIDC_* environment variable on the test machine would shadow the properties. */
    private fun assumeNoEnv() {
        keys.drop(1).forEach { assertNull(System.getenv(it), "unset $it in the test environment") }
    }
}
