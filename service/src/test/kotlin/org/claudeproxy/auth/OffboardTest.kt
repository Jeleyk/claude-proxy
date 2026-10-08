package org.claudeproxy.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.api.userLifecycleRoutes
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.datapath.ResolveError
import org.claudeproxy.repo.IdentityRepo
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.*

/** Offboarding: one call disables the user, ends sessions, and kills every cxp_/cxr_ token on every datapath. */
class OffboardTest {
    private lateinit var dbFile: File
    private var bob = 0

    @BeforeTest
    fun setup() {
        dbFile = freshDb()
        bob = UserRepo.create("bob", "pw-bob", listOf("user"), emptyList(), null)
    }

    @AfterTest
    fun cleanup() { MemoryCache.clear(); dbFile.delete() }

    @Test
    fun `offboarded user's tokens are rejected by the gateway resolve path, warm cache included`() = runBlocking {
        val proxy = ProxyTokenRepo.create(bob, "laptop").token!!
        val routing = RoutingTokenRepo.create(bob, "codex").token!!
        val service = DatapathService(AccountPool())
        // Warm the resolve caches first: the revocation must not wait for the 60s TTL.
        assertNotNull(ProxyTokenRepo.resolveAuth(proxy))
        assertNotNull(RoutingTokenRepo.resolveAuth(routing))
        val before = UserRepo.findAuth(bob)!!.sessionVersion

        val r = UserRepo.offboard(bob)!!
        assertEquals(1 to 1, r.proxyTokensRevoked to r.routingTokensRevoked)

        assertEquals(ResolveError.BAD_TOKEN, service.resolve(proxy, "POST", "/v1/messages").error)
        assertEquals(ResolveError.BAD_TOKEN, service.resolve(routing, "POST", "/v1/messages", "routing").error)
        val after = UserRepo.findAuth(bob)!!
        assertFalse(after.enabled)
        assertTrue(after.sessionVersion > before, "panel sessions revoked")
        assertNull(UserRepo.authenticate("bob", "pw-bob"))

        // Re-enabling does not resurrect anything: the tokens are gone, not just switched off.
        UserRepo.update(bob, null, true, null, null, null, false)
        assertNull(ProxyTokenRepo.resolveAuth(proxy))
        assertNull(RoutingTokenRepo.resolveAuth(routing))
    }

    @Test
    fun `offboarded user cannot come back through SSO`() {
        transaction { IdentityRepo.link(bob, "https://idp.example.com", "sub-bob") }
        UserRepo.offboard(bob)
        val r = OidcLogin(oidcConfig()).resolve(OidcIdentity("https://idp.example.com", "sub-bob", null, false, null, null))
        assertEquals(OidcError.FORBIDDEN, assertIs<OidcLoginResult.Denied>(r).error)
    }

    @Test
    fun `offboard API requires USERS_MANAGE and refuses self`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installSecurity(TEST_SECRET)
            install(StatusPages) {
                exception<UnauthorizedException> { call, _ -> call.respond(HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, _ -> call.respond(HttpStatusCode.Forbidden) }
            }
            routing {
                post("/as/{id}") { call.setUserSession(UserRepo.findAuth(call.parameters["id"]!!.toInt())!!); call.respond(HttpStatusCode.OK) }
                route("/api") { userLifecycleRoutes() }
            }
        }
        val carol = UserRepo.create("carol", "pw", listOf("user"), emptyList(), null)
        val asCarol = client.post("/as/$carol").headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val asAdmin = client.post("/as/1").headers[HttpHeaders.SetCookie]!!.substringBefore(';')

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/users/$bob/offboard").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/users/$bob/offboard") { header(HttpHeaders.Cookie, asCarol) }.status)
        assertTrue(UserRepo.findAuth(bob)!!.enabled)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/users/1/offboard") { header(HttpHeaders.Cookie, asAdmin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/users/9999/offboard") { header(HttpHeaders.Cookie, asAdmin) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/users/$bob/offboard") { header(HttpHeaders.Cookie, asAdmin) }.status)
        assertFalse(UserRepo.findAuth(bob)!!.enabled)

        transaction { IdentityRepo.link(carol, "https://idp.example.com", "sub-carol") }
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/users/$carol/identities") { header(HttpHeaders.Cookie, asCarol) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/users/$carol/identities") { header(HttpHeaders.Cookie, asAdmin) }.status)
        assertFalse(transaction { IdentityRepo.isLinked(carol) })
    }
}
