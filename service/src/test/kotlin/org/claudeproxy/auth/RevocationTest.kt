package org.claudeproxy.auth

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import org.claudeproxy.Config
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.Users
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.*

class RevocationTest {
    private lateinit var dbFile: File
    private var userId = 0
    private val secret = "test-session-secret-at-least-32-chars"

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("revocation-test", ".db")
        val config = Config(
            bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbFile.absolutePath,
            masterKey = secret, sessionSecret = secret, adminUser = "admin", adminPassword = "admin",
            upstreamBaseUrl = "https://api.anthropic.com", publicBaseUrl = "", databaseUrl = "",
            databaseUser = "claudeproxy", databasePassword = "", internalToken = null,
        )
        Secrets.init(Crypto(secret))
        Db.init(config)
        MemoryCache.clear()
        userId = transaction { Users.selectAll().first()[Users.id] }
    }

    @AfterTest
    fun cleanup() { MemoryCache.clear(); dbFile.delete() }

    private fun update(enabled: Boolean? = null, password: String? = null) = UserRepo.update(
        userId, password, enabled, null, null, null, false,
    )

    private fun sessionScenario(action: () -> Unit, issue: String = "/login") = testApplication {
        application {
            installSecurity(secret)
            routing {
                post("/login") { call.setUserSession(UserRepo.findAuth(userId)!!); call.respond(HttpStatusCode.OK) }
                post("/expired") {
                    call.sessions.set(UserSession(userId, UserRepo.findAuth(userId)!!.sessionVersion, Instant.now().epochSecond - 1))
                    call.respond(HttpStatusCode.OK)
                }
                post("/legacy") { call.sessions.set(UserSession(userId)); call.respond(HttpStatusCode.OK) }
                post("/logout") { call.clearUserSession(); call.respond(HttpStatusCode.OK) }
                get("/me") { call.respond(if (call.currentUser() == null) HttpStatusCode.Unauthorized else HttpStatusCode.OK) }
            }
        }
        val cookie = client.post(issue).headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        if (issue == "/login") assertEquals(HttpStatusCode.OK, client.get("/me") { header(HttpHeaders.Cookie, cookie) }.status)
        action()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { header(HttpHeaders.Cookie, cookie) }.status)
        // A fresh session works after revocation; replaying an old logout cannot kill it.
        val fresh = client.post("/login").headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        client.post("/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, client.get("/me") { header(HttpHeaders.Cookie, fresh) }.status)
    }

    @Test fun `self password change revokes old cookie`() = sessionScenario({ UserRepo.updateSelf(userId, null, "new-password") })
    @Test fun `admin password reset revokes old cookie`() = sessionScenario({ update(password = "new-password") })
    @Test fun `disable and re-enable never resurrects old session`() = sessionScenario({ update(enabled = false); update(enabled = true) })
    @Test fun `expired signed cookie is rejected server side`() = sessionScenario({}, "/expired")
    @Test fun `old cookies without generation and expiry are rejected`() = sessionScenario({}, "/legacy")

    @Test
    fun `logout revokes copied cookie`() = testApplication {
        application {
            installSecurity(secret)
            routing {
                post("/login") { call.setUserSession(UserRepo.findAuth(userId)!!); call.respond(HttpStatusCode.OK) }
                post("/logout") { call.clearUserSession(); call.respond(HttpStatusCode.OK) }
                get("/me") { call.respond(if (call.currentUser() == null) HttpStatusCode.Unauthorized else HttpStatusCode.OK) }
            }
        }
        val cookie = client.post("/login").headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        client.post("/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { header(HttpHeaders.Cookie, cookie) }.status)
    }
}
