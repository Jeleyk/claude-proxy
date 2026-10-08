package org.claudeproxy.auth

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as routeGet
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.LimitProbe
import org.claudeproxy.api.adminRoutes
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.chat.ChatEngine
import org.claudeproxy.datapath.DatapathService
import java.io.File
import kotlin.test.*

class OidcConfigTest {
    private val base = mapOf(
        "OIDC_ENABLED" to "true",
        "OIDC_ISSUER" to "https://sso.example.com/application/o/claude-proxy/",
        "OIDC_CLIENT_ID" to "cid",
        "OIDC_CLIENT_SECRET" to "s3cret-value",
        "OIDC_REDIRECT_URI" to "https://panel.example.com/api/auth/oidc/callback",
    )

    private fun load(env: Map<String, String?>) = OidcConfig.load { env[it] }

    @Test
    fun `off unless explicitly enabled`() {
        assertNull(load(emptyMap()))
        assertNull(load(base - "OIDC_ENABLED"))
        assertNull(load(base + ("OIDC_ENABLED" to "false")))
    }

    @Test
    fun `restrictive defaults`() {
        val c = load(base)!!
        assertFalse(c.linkByEmail)
        assertFalse(c.jitProvisioning)
        assertFalse(c.disablePasswordLogin)
        assertTrue(c.allowedGroups.isEmpty() && c.allowedEmailDomains.isEmpty())
        assertEquals(listOf("openid", "email", "profile"), c.scopes)
        assertEquals("groups", c.groupsClaim)
        assertEquals(12 * 3600L, c.sessionTtlSeconds)
        assertEquals("https://panel.example.com/api/auth/oidc/start", c.startUrl)
        assertTrue(c.secureCookies)
    }

    @Test
    fun `parses lists and flags`() {
        val c = load(base + mapOf(
            "OIDC_ALLOWED_GROUPS" to "admin, claude-proxy-user",
            "OIDC_ALLOWED_EMAIL_DOMAINS" to "@Example.com,b.org",
            "OIDC_LINK_BY_EMAIL" to "true", "OIDC_JIT_PROVISIONING" to "1", "OIDC_DISABLE_PASSWORD_LOGIN" to "yes",
            "OIDC_SESSION_TTL_HOURS" to "8", "OIDC_REDIRECT_URI" to "http://claude.internal:8080/api/auth/oidc/callback",
        ))!!
        assertEquals(setOf("admin", "claude-proxy-user"), c.allowedGroups)
        assertEquals(setOf("example.com", "b.org"), c.allowedEmailDomains)
        assertTrue(c.linkByEmail && c.jitProvisioning && c.disablePasswordLogin)
        assertEquals(8 * 3600L, c.sessionTtlSeconds)
        assertEquals("http://claude.internal:8080/api/auth/oidc/start", c.startUrl)
        assertFalse(c.secureCookies)
    }

    @Test
    fun `secret file wins and the secret never shows in toString`() {
        val f = File.createTempFile("oidc", ".secret").apply { writeText("from-file-secret\n"); deleteOnExit() }
        val c = load(base + ("OIDC_CLIENT_SECRET_FILE" to f.absolutePath))!!
        assertEquals("from-file-secret", c.clientSecret)
        assertFalse("from-file-secret" in c.toString())
        assertFalse("s3cret-value" in load(base)!!.toString())
    }

    @Test
    fun `invalid OIDC config disables OIDC instead of failing the service`() {
        val errors = mutableListOf<String>()
        assertNull(OidcConfig.loadOrDisable({ (base - "OIDC_CLIENT_ID")[it] }, errors::add))
        assertTrue(errors.single().contains("DISABLED") && errors.single().contains("OIDC_CLIENT_ID"))
        assertFalse(errors.single().contains("s3cret-value"))
        assertNotNull(OidcConfig.loadOrDisable({ base[it] }, errors::add))
        assertEquals(1, errors.size)
    }

    @Test
    fun `JIT needs a group gate, role and password-block flags parse`() {
        assertFails { load(base + ("OIDC_JIT_PROVISIONING" to "true")) }
        val c = load(base + mapOf("OIDC_JIT_PROVISIONING" to "true", "OIDC_ALLOWED_GROUPS" to "g", "OIDC_JIT_ROLE" to "viewer"))!!
        assertEquals("viewer", c.jitRole)
        assertTrue(c.blockPasswordForLinked, "on by default")
        assertNull(load(base + mapOf("OIDC_JIT_PROVISIONING" to "true", "OIDC_ALLOWED_GROUPS" to "g"))!!.jitRole, "default = pending")
        assertFalse(load(base + ("OIDC_BLOCK_PASSWORD_FOR_LINKED" to "false"))!!.blockPasswordForLinked)
    }

    @Test
    fun `session cookie SameSite is configurable, Lax by default, never None`() {
        assertEquals(SameSitePolicy.LAX, SameSitePolicy.parse(null))
        assertEquals(SameSitePolicy.STRICT, SameSitePolicy.parse(" Strict "))
        val warnings = mutableListOf<String>()
        assertEquals(SameSitePolicy.LAX, SameSitePolicy.parse("none", warnings::add))
        assertEquals(1, warnings.size)
        for (policy in SameSitePolicy.entries) testApplication {
            application {
                installSecurity(TEST_SECRET, policy)
                routing { routeGet("/s") { call.sessions.set(UserSession(1, 0, 1)); call.respondText("") } }
            }
            val sc = client.get("/s").headers[io.ktor.http.HttpHeaders.SetCookie]!!
            assertTrue("SameSite=${policy.attr}" in sc, sc)
        }
    }

    @Test
    fun `misconfiguration fails the boot`() {
        listOf("OIDC_ISSUER", "OIDC_CLIENT_ID", "OIDC_CLIENT_SECRET", "OIDC_REDIRECT_URI").forEach { k ->
            assertFails("missing $k") { load(base - k) }
        }
        assertFails { load(base + ("OIDC_ISSUER" to "http://sso.example.com")) }
        assertFails { load(base + ("OIDC_REDIRECT_URI" to "https://panel.example.com/other")) }
        assertFails { load(base + ("OIDC_REDIRECT_URI" to "https://panel.example.com/api/auth/oidc/callback?x=1")) }
        assertFails { load(base + ("OIDC_REDIRECT_URI" to "/api/auth/oidc/callback")) }
        assertFails { load(base + ("OIDC_SCOPES" to "email profile")) }
        assertFails { load(base + ("OIDC_SESSION_TTL_HOURS" to "0")) }
        assertFails { load(base + ("OIDC_SESSION_TTL_HOURS" to "100000")) }
        assertFails { load(base + ("OIDC_CLIENT_SECRET_FILE" to "/nonexistent/secret")) }
    }

    @Test
    fun `transactions are single use, state-bound and expire`() {
        var now = 0L
        val store = OidcTransactions(ttlMs = 1000, maxEntries = 2, clock = { now })
        val (id, txn) = store.begin()!!
        assertNull(store.take(id, "wrong"))
        assertNull(store.take(id, txn.state), "a failed attempt burns the transaction")
        val (id2, txn2) = store.begin()!!
        now = 2000
        assertNull(store.take(id2, txn2.state), "expired")
        val (id3, txn3) = store.begin()!!
        store.begin()!!
        assertNull(store.begin(), "bounded")
        now = 4000
        assertNotNull(store.begin(), "expired entries are swept to make room")
        assertNull(store.take(id3, txn3.state))
        assertNull(store.take(null, "x"))
    }

    @Test
    fun `password login is refused for SSO-linked users while OIDC is active, kept for unlinked ones`() {
        val db = freshDb()
        try {
            val svc = org.claudeproxy.repo.UserRepo.create("svc", "svc-pw", listOf("admin"), emptyList(), null)
            org.jetbrains.exposed.sql.transactions.transaction { org.claudeproxy.repo.IdentityRepo.link(1, "https://idp.example.com", "sub-admin") }
            data class Case(val oidc: OidcConfig?, val user: String, val pw: String, val expected: HttpStatusCode)
            val cases = listOf(
                Case(oidcConfig(), "admin", "admin", HttpStatusCode.Forbidden),            // linked → SSO only
                Case(oidcConfig(), "svc", "svc-pw", HttpStatusCode.OK),                    // unlinked service/break-glass user
                Case(oidcConfig(blockPasswordForLinked = false), "admin", "admin", HttpStatusCode.OK),
                Case(null, "admin", "admin", HttpStatusCode.OK),                           // OIDC off/broken → break-glass
                Case(oidcConfig(), "admin", "wrong", HttpStatusCode.Unauthorized),         // no oracle before the password
            )
            for (c in cases) testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    installSecurity(TEST_SECRET)
                    val pool = AccountPool()
                    val datapath = DatapathService(pool)
                    routing {
                        adminRoutes(pool, LimitProbe(pool, "http://127.0.0.1:1"), "", ChatEngine(pool, "http://127.0.0.1:1", datapath),
                            datapath, "http://127.0.0.1:1", c.oidc)
                    }
                }
                val r = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"${c.user}","password":"${c.pw}"}""") }
                assertEquals(c.expected, r.status, "$c ${r.bodyAsText()}")
            }
            assertTrue(svc > 1)
        } finally { MemoryCache.clear(); db.delete() }
    }

    @Test
    fun `OIDC_DISABLE_PASSWORD_LOGIN closes the password endpoint, default keeps it`() {
        val db = freshDb()
        try {
            for ((disabled, expected) in listOf(false to HttpStatusCode.OK, true to HttpStatusCode.Forbidden)) testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    installSecurity(TEST_SECRET)
                    val pool = AccountPool()
                    val datapath = DatapathService(pool)
                    routing {
                        adminRoutes(pool, LimitProbe(pool, "http://127.0.0.1:1"), "", ChatEngine(pool, "http://127.0.0.1:1", datapath),
                            datapath, "http://127.0.0.1:1", oidcConfig(disablePasswordLogin = disabled))
                    }
                }
                val r = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"admin","password":"admin"}""") }
                assertEquals(expected, r.status, r.bodyAsText())
            }
        } finally { MemoryCache.clear(); db.delete() }
    }
}
