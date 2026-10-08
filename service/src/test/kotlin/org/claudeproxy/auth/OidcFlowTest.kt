package org.claudeproxy.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.claudeproxy.api.oidcRoutes
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.repo.IdentityRepo
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.net.URI
import kotlin.test.*

/**
 * End-to-end callback validation against [FakeIdp] through the real routes and the real Nimbus
 * client. Every failure must end on `/?sso_error=<code>` with no panel session issued.
 */
class OidcFlowTest {
    private lateinit var dbFile: File
    private lateinit var idp: FakeIdp
    private val sub = "subject-123"

    @BeforeTest
    fun setup() {
        dbFile = freshDb()
        idp = FakeIdp()
        // The bootstrap admin (id 1) is pre-linked; linking rules are covered in OidcLoginTest.
        transaction { IdentityRepo.link(1, idp.issuer, sub) }
    }

    @AfterTest
    fun cleanup() { idp.close(); MemoryCache.clear(); dbFile.delete() }

    private fun goodClaims(extra: Map<String, Any?> = emptyMap()) =
        mapOf("sub" to sub, "email" to "admin@example.com", "email_verified" to true, "groups" to listOf("panel")) + extra

    private fun scenario(
        config: OidcConfig = oidcConfig(issuer = idp.issuer),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installSecurity(TEST_SECRET)
            routing {
                route("/api") { oidcRoutes(config) }
                get("/whoami") { call.respond(if (call.currentUser() == null) HttpStatusCode.Unauthorized else HttpStatusCode.OK) }
            }
        }
        val c = createClient { followRedirects = false }
        block(c)
    }

    /** /start → (IdP) → callback. Returns the callback response; [tamper] may rewrite code/state/cookie. */
    private suspend fun login(
        c: HttpClient,
        grant: FakeIdp.Grant,
        tamper: (code: String, state: String, cookie: String?) -> Triple<String?, String?, String?> = { a, b, d -> Triple(a, b, d) },
    ): HttpResponse {
        val start = c.get("/api/auth/oidc/start")
        assertEquals(HttpStatusCode.Found, start.status)
        val setCookie = start.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("CLAUDE_PROXY_OIDC=") }
        assertTrue("HttpOnly" in setCookie && "SameSite=Lax" in setCookie && "Path=/api/auth/oidc" in setCookie, setCookie)
        val (code, state) = idp.authorize(URI(start.headers[HttpHeaders.Location]!!), grant)
        val (c2, s2, ck) = tamper(code, state, setCookie.substringBefore(';'))
        val qs = listOfNotNull(c2?.let { "code=$it" }, s2?.let { "state=$it" }).joinToString("&")
        return c.get("/api/auth/oidc/callback?$qs") { ck?.let { header(HttpHeaders.Cookie, it) } }
    }

    private fun HttpResponse.sessionCookie(): String? =
        headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("CLAUDE_PROXY_SESSION=") }?.substringBefore(';')

    private suspend fun assertDenied(r: HttpResponse, code: String) {
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/?sso_error=$code", r.headers[HttpHeaders.Location])
        assertNull(r.sessionCookie(), "no session on failure")
    }

    @Test
    fun `authorize request uses PKCE S256, state and nonce`() = scenario { c ->
        val loc = URI(c.get("/api/auth/oidc/start").headers[HttpHeaders.Location]!!)
        val q = FakeIdp.query(loc.rawQuery)
        assertEquals("S256", q["code_challenge_method"])
        assertTrue(q["state"]!!.length >= 43 && q["nonce"]!!.length >= 43 && q["code_challenge"]!!.isNotEmpty())
        assertEquals("http://localhost/api/auth/oidc/callback", q["redirect_uri"])
        assertEquals("openid email profile", q["scope"])
    }

    @Test
    fun `happy path issues the normal panel session`() = scenario { c ->
        val r = login(c, FakeIdp.Grant(goodClaims()))
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/", r.headers[HttpHeaders.Location])
        val session = r.sessionCookie()!!
        assertEquals(HttpStatusCode.OK, c.get("/whoami") { header(HttpHeaders.Cookie, session) }.status)
    }

    @Test
    fun `state mismatch is rejected`() = scenario { c ->
        assertDenied(login(c, FakeIdp.Grant(goodClaims())) { code, _, ck -> Triple(code, "forged", ck) }, "invalid_request")
    }

    @Test
    fun `callback without the transaction cookie is rejected (login CSRF)`() = scenario { c ->
        assertDenied(login(c, FakeIdp.Grant(goodClaims())) { code, st, _ -> Triple(code, st, null) }, "invalid_request")
        assertEquals(0, idp.tokenRequests, "no code exchange without a bound transaction")
    }

    @Test
    fun `callback cannot be replayed`() = scenario { c ->
        var replay: Triple<String?, String?, String?>? = null
        val first = login(c, FakeIdp.Grant(goodClaims())) { a, b, d -> Triple(a, b, d).also { replay = it } }
        assertNotNull(first.sessionCookie())
        val (code, state, cookie) = replay!!
        assertDenied(c.get("/api/auth/oidc/callback?code=$code&state=$state") { header(HttpHeaders.Cookie, cookie!!) }, "invalid_request")
    }

    @Test
    fun `missing code is rejected`() = scenario { c ->
        assertDenied(login(c, FakeIdp.Grant(goodClaims())) { _, st, ck -> Triple(null, st, ck) }, "invalid_request")
    }

    @Test
    fun `provider error is reported without echoing it`() = scenario { c ->
        val start = c.get("/api/auth/oidc/start")
        val cookie = start.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("CLAUDE_PROXY_OIDC=") }.substringBefore(';')
        val state = FakeIdp.query(URI(start.headers[HttpHeaders.Location]!!).rawQuery)["state"]
        assertDenied(c.get("/api/auth/oidc/callback?error=access_denied&error_description=%3Cscript%3E&state=$state") { header(HttpHeaders.Cookie, cookie) }, "idp_error")
    }

    @Test fun `token endpoint error`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), tokenError = "invalid_grant")), "token_error") }
    @Test fun `wrong nonce`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), nonceOverride = "other")), "token_error") }
    @Test fun `wrong audience`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), audience = "another-client")), "token_error") }
    @Test fun `wrong issuer`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), issuerOverride = "https://evil.example.com")), "token_error") }
    @Test fun `expired id token`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), expiresInSeconds = -600)), "token_error") }
    @Test fun `signature by a foreign key`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), signer = FakeIdp.Signer.FOREIGN_KEY)), "token_error") }
    @Test fun `unsigned alg none token`() = scenario { c -> assertDenied(login(c, FakeIdp.Grant(goodClaims(), signer = FakeIdp.Signer.NONE)), "token_error") }
    @Test fun `HS256 token keyed by the client secret`() = scenario(oidcConfig(issuer = idp.issuer, clientSecret = idp.clientSecret)) { c ->
        assertDenied(login(c, FakeIdp.Grant(goodClaims(), signer = FakeIdp.Signer.HMAC_CLIENT_SECRET)), "token_error")
    }

    @Test
    fun `group gate refuses a linked user outside the allowed groups`() = scenario(oidcConfig(issuer = idp.issuer, allowedGroups = setOf("panel"))) { c ->
        assertDenied(login(c, FakeIdp.Grant(goodClaims(mapOf("groups" to listOf("other"))))), "forbidden")
        assertNotNull(login(c, FakeIdp.Grant(goodClaims())).sessionCookie())
    }

    @Test
    fun `groups missing from the ID token are read from userinfo for the same sub only`() = scenario(oidcConfig(issuer = idp.issuer, allowedGroups = setOf("panel"))) { c ->
        val noGroups = goodClaims() - "groups"
        assertNotNull(login(c, FakeIdp.Grant(noGroups, userinfo = mapOf("sub" to sub, "groups" to listOf("panel")))).sessionCookie())
        assertDenied(login(c, FakeIdp.Grant(noGroups, userinfo = mapOf("sub" to "someone-else", "groups" to listOf("panel")))), "token_error")
    }

    @Test
    fun `unreachable IdP gives unavailable`() = scenario(oidcConfig(issuer = "http://127.0.0.1:1")) { c ->
        val r = c.get("/api/auth/oidc/start")
        assertEquals("/?sso_error=unavailable", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `public config hides nothing secret and reports disabled state`() = scenario(oidcConfig(issuer = idp.issuer, disablePasswordLogin = true)) { c ->
        val text = c.get("/api/auth/oidc/config").bodyAsText()
        assertTrue("\"enabled\":true" in text && "\"passwordLogin\":false" in text, text)
        assertFalse(idp.clientSecret in text)
    }
}
