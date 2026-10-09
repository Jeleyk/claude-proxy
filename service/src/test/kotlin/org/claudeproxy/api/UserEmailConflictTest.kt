package org.claudeproxy.api

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import org.claudeproxy.auth.TEST_SECRET
import org.claudeproxy.auth.freshDb
import org.claudeproxy.auth.installSecurity
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.chat.ChatEngine
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.repo.UserRepo
import kotlin.test.*

/** A duplicate email is a 409 with a fixed message — via the pre-check and via the unique index (lost race). */
class UserEmailConflictTest {
    @Test
    fun `duplicate email on create and update answers 409, also when the pre-check lost the race`() {
        val db = freshDb()
        try {
            val alice = UserRepo.create("alice", "pw", listOf("user"), emptyList(), null, email = "alice@example.com")
            val bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    installSecurity(TEST_SECRET)
                    val pool = AccountPool()
                    val datapath = DatapathService(pool)
                    routing {
                        adminRoutes(pool, LimitProbe(pool, "http://127.0.0.1:1"), "", ChatEngine(pool, "http://127.0.0.1:1", datapath),
                            datapath, "http://127.0.0.1:1")
                    }
                }
                val c = createClient { install(HttpCookies) }
                val login = c.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"admin","password":"admin"}""") }
                assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())

                suspend fun patchBob() = c.patch("/api/users/$bob") { contentType(ContentType.Application.Json); setBody("""{"email":"Alice@Example.com"}""") }
                suspend fun createCarol() = c.post("/api/users") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"carol","password":"pw","roles":["user"],"allowedGroups":[],"email":"alice@example.com"}""")
                }
                for (precheck in listOf(true, false)) {
                    UserRepo.emailPrecheck = precheck
                    try {
                        for (r in listOf(patchBob(), createCarol())) {
                            assertEquals(HttpStatusCode.Conflict, r.status, "precheck=$precheck ${r.bodyAsText()}")
                            val body = r.bodyAsText()
                            assertTrue("Email already used" in body, body)
                            assertFalse("users_email_unique" in body || "SQL" in body, body)
                        }
                    } finally { UserRepo.emailPrecheck = true }
                }
                assertNull(UserRepo.get(bob)!!.email)
                assertEquals("alice@example.com", UserRepo.get(alice)!!.email)
                assertFalse(UserRepo.list().any { it.username == "carol" })
            }
        } finally { MemoryCache.clear(); db.delete() }
    }
}
