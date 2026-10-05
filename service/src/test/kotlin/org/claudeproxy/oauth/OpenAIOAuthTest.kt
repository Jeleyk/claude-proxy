package org.claudeproxy.oauth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.claudeproxy.api.OpenAIDeviceFlows
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class OpenAIOAuthTest {
    private lateinit var server: HttpServer
    private var polls = 0
    private var redirect = false
    private var leaked = false
    private val accountId = "acct_test-123"
    private fun jwt(payload: String) = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"
    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            val body = e.requestBody.use { String(it.readBytes()) }
            var status = 200
            val response = when (e.requestURI.path) {
                "/api/accounts/deviceauth/usercode" -> """{"device_auth_id":"secret-device-id","user_code":"ABCD-1234","interval":"5"}"""
                "/api/accounts/deviceauth/token" -> {
                    polls++
                    if (polls == 1) { status = 403; "{}" }
                    else """{"authorization_code":"one-time-code","code_verifier":"verifier","code_challenge":"challenge"}"""
                }
                "/oauth/token" -> {
                    if (redirect) { status = 307; e.responseHeaders.add("Location", "/leak"); "echo-secret" }
                    else {
                        if (body.contains("authorization_code")) assertTrue(body.contains("code_verifier=verifier"))
                        """{"access_token":"${jwt("""{"exp":1900000000,"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}""")}","refresh_token":"rotated-secret","expires_in":3600}"""
                    }
                }
                else -> { leaked = true; "{}" }
            }
            val bytes = response.toByteArray()
            e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        System.setProperty("OPENAI_OAUTH_ISSUER", "http://127.0.0.1:${server.address.port}")
    }
    @AfterTest fun cleanup() { System.clearProperty("OPENAI_OAUTH_ISSUER"); server.stop(0) }
    @Test fun `device code pending success and refresh preserve account identity`() = runBlocking {
        val code = OpenAIOAuth.start()
        assertEquals(5, code.intervalSeconds)
        assertEquals("ABCD-1234", code.userCode)
        assertNull(OpenAIOAuth.poll(code))
        val token = OpenAIOAuth.poll(code)!!
        assertEquals(accountId, token.accountId)
        assertEquals("rotated-secret", token.refreshToken)
        assertTrue(token.expiresAtMillis!! > System.currentTimeMillis())
        assertEquals(accountId, OpenAIOAuth.refresh(OpenAIHttp.client, "refresh-secret").accountId)
    }
    @Test fun `refresh does not follow redirects or echo secrets`() = runBlocking {
        redirect = true
        val error = assertFailsWith<OpenAIHttpException> { OpenAIOAuth.refresh(OpenAIHttp.client, "refresh-secret") }
        assertEquals(307, error.status)
        assertFalse(error.message!!.contains("secret"))
        assertFalse(leaked)
    }
    @Test fun `device flows enforce owner scope cadence single consumption and expiry`() = runBlocking {
        var now = Instant.parse("2026-10-05T00:00:00Z")
        var upstreamCalls = 0; var saves = 0
        val flows = OpenAIDeviceFlows(startDevice = { OpenAIOAuth.DeviceCode("private", "CODE", 5, "https://auth.openai.com/codex/device") },
            pollDevice = { upstreamCalls++; OpenAIOAuth.TokenResult("secret", "refresh", null, accountId) }, now = { now })
        val start = flows.start(1, true)
        suspend fun poll(user: Int = 1, personal: Boolean = true) = flows.poll(user, personal, start.flowId) { saves++; 7 }
        assertEquals("expired", poll(2).status)
        assertEquals("expired", poll(personal = false).status)
        assertEquals("pending", poll().status)
        assertEquals(0, upstreamCalls)
        now = now.plusSeconds(5)
        assertEquals(7, poll().accountId)
        assertEquals(7, poll().accountId)
        assertEquals(1, saves); assertEquals(1, upstreamCalls)
        now = now.plusSeconds(900)
        assertEquals("expired", poll().status)
    }
    @Test fun `disconnect during persistence retains completed login without duplicate account`() = runBlocking {
        var now = Instant.parse("2026-10-05T00:00:00Z")
        var saves = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flows = OpenAIDeviceFlows(startDevice = { OpenAIOAuth.DeviceCode("private", "CODE", 5, "https://auth.openai.com/codex/device") },
            pollDevice = { OpenAIOAuth.TokenResult("secret", "refresh", null, accountId) }, now = { now })
        val flow = flows.start(1, false)
        now = now.plusSeconds(5)
        val request = launch {
            flows.poll(1, false, flow.flowId) { saves++; entered.complete(Unit); release.await(); 9 }
        }
        entered.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertEquals(9, flows.poll(1, false, flow.flowId) { saves++; 99 }.accountId)
        assertEquals(1, saves)
    }
}
