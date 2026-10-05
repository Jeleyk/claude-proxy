package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.claudeproxy.accounts.*
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.model.*
import org.claudeproxy.oauth.OpenAIOAuth
import org.claudeproxy.repo.GroupRepo
import java.time.Instant
import java.util.UUID

@Serializable data class OpenAIDeviceStart(val flowId: String, val verificationUri: String, val userCode: String, val expiresAt: String, val intervalSeconds: Int)
@Serializable data class OpenAIDevicePoll(val flowId: String, val name: String, val groupId: Int? = null,
    val priority: Int = 10, val threshold: Double = 0.9, val coefficient: Double = 1.0)
@Serializable data class OpenAIDeviceStatus(val status: String, val accountId: Int? = null)

/** Short-lived single-instance authorization state; no durable plaintext OAuth secrets. */
class OpenAIDeviceFlows(
    private val startDevice: suspend () -> OpenAIOAuth.DeviceCode = { OpenAIOAuth.start() },
    private val pollDevice: suspend (OpenAIOAuth.DeviceCode) -> OpenAIOAuth.TokenResult? = { OpenAIOAuth.poll(it) },
    private val now: () -> Instant = Instant::now,
) {
    private data class Flow(val userId: Int, val personal: Boolean, var code: OpenAIOAuth.DeviceCode?,
        val expires: Instant, var nextPoll: Instant, val mutex: Mutex = Mutex(), var accountId: Int? = null)
    private val flows = java.util.concurrent.ConcurrentHashMap<String, Flow>()
    private val starts = Mutex()

    suspend fun start(userId: Int, personal: Boolean): OpenAIDeviceStart = starts.withLock {
        val t = now()
        flows.entries.removeIf { !it.value.expires.isAfter(t) }
        require(flows.size < 64 && flows.values.count { it.userId == userId } < 4) { "Too many active OpenAI logins; wait for an existing code to expire" }
        val code = startDevice()
        val id = UUID.randomUUID().toString()
        val expiry = t.plusSeconds(900)
        flows[id] = Flow(userId, personal, code, expiry, t.plusSeconds(code.intervalSeconds.toLong()))
        OpenAIDeviceStart(id, code.verificationUri, code.userCode, expiry.toString(), code.intervalSeconds)
    }

    suspend fun poll(userId: Int, personal: Boolean, flowId: String,
        save: suspend (OpenAIOAuth.TokenResult) -> Int): OpenAIDeviceStatus {
        val flow = flows[flowId]?.takeIf { it.userId == userId && it.personal == personal }
            ?: return OpenAIDeviceStatus("expired")
        return flow.mutex.withLock {
            if (!flow.expires.isAfter(now())) { flows.remove(flowId); return@withLock OpenAIDeviceStatus("expired") }
            flow.accountId?.let { return@withLock OpenAIDeviceStatus("complete", it) }
            if (flow.nextPoll.isAfter(now())) return@withLock OpenAIDeviceStatus("pending")
            val code = flow.code ?: return@withLock OpenAIDeviceStatus("expired")
            flow.nextPoll = now().plusSeconds(code.intervalSeconds.toLong())
            try {
                val token = pollDevice(code) ?: return@withLock OpenAIDeviceStatus("pending")
                // The upstream code is consumed. Never retry its token exchange after a save failure.
                flow.code = null
                // Persist and mark completion together even if the browser disconnects. This
                // callback must contain only local persistence, never an upstream request.
                val account = withContext(NonCancellable) {
                    save(token).also { flow.accountId = it }
                }
                OpenAIDeviceStatus("complete", account)
            } catch (e: CancellationException) {
                if (flow.accountId == null) flows.remove(flowId)
                throw e
            } catch (e: Exception) {
                flows.remove(flowId); throw e
            }
        }
    }
}

fun Route.openAIOAuthRoutes(pool: AccountPool, probe: LimitProbe, flows: OpenAIDeviceFlows = OpenAIDeviceFlows()) {
    for (personal in listOf(false, true)) {
        val path = if (personal) "/api/my/accounts/openai/oauth" else "/api/accounts/openai/oauth"
        val permission = if (personal) Permission.ACCOUNTS_OWN_MANAGE else Permission.ACCOUNTS_MANAGE
        post("$path/start") {
            val user = call.requirePermission(permission)
            call.response.headers.append("Cache-Control", "no-store")
            try { call.respond(flows.start(user.id, personal)) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { call.respond(HttpStatusCode.TooManyRequests, MessageResponse("Too many active OpenAI logins")) }
            catch (e: Exception) { call.respond(HttpStatusCode.BadGateway, MessageResponse("OpenAI login could not be started; retry later")) }
        }
        post("$path/poll") {
            val user = call.requirePermission(permission)
            call.response.headers.append("Cache-Control", "no-store")
            val req = call.receive<OpenAIDevicePoll>()
            if (req.name.isBlank() || req.name.length > 128 || !req.threshold.isFinite() || req.threshold !in 0.0..1.0 ||
                !req.coefficient.isFinite() || req.coefficient <= 0 || req.coefficient > 1000 ||
                (req.groupId != null && (personal || GroupRepo.list().none { it.id == req.groupId }))) {
                return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid account settings"))
            }
            try {
                val status = flows.poll(user.id, personal, req.flowId) { token ->
                    val accountId = token.accountId ?: error("Missing OpenAI account identity")
                    val id = AccountRepo.create(name = req.name.trim(), type = if (token.refreshToken == null) AccountType.OAUTH_STATIC else AccountType.OAUTH,
                        groupId = if (personal) null else req.groupId, priority = req.priority, threshold = req.threshold,
                        coefficient = req.coefficient, secret = AccountSecret(accessToken = token.accessToken, refreshToken = token.refreshToken, expiresAt = token.expiresAtMillis),
                        createdBy = user.id, ownerId = if (personal) user.id else null, provider = AccountProvider.OPENAI,
                        accountUuid = accountId)
                    pool.reload()
                    id
                }
                status.accountId?.let { id ->
                    try { probe.probe(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Saved account remains complete; background probing retries. */ }
                }
                call.respond(status)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { call.respond(HttpStatusCode.BadGateway, MessageResponse("OpenAI login failed; start a new login")) }
        }
    }
}
