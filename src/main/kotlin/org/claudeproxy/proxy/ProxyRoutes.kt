package org.claudeproxy.proxy

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.model.Permission
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.UserRepo
import org.slf4j.LoggerFactory

@Serializable
private data class ProxyError(val error: ProxyErrorBody)
@Serializable
private data class ProxyErrorBody(val type: String, val message: String)

/**
 * Ties inbound auth (proxy tokens) → account selection → forwarding with transparent
 * retry across accounts on 429.
 */
class ProxyEngine(
    private val pool: AccountPool,
    private val forwarder: UpstreamForwarder,
) {
    private val log = LoggerFactory.getLogger("ProxyEngine")

    suspend fun handle(call: ApplicationCall) {
        // Inbound auth: proxy token from Authorization: Bearer, or x-api-key.
        val token = extractInboundToken(call)
        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ProxyError(ProxyErrorBody("authentication_error", "Missing proxy token")))
            return
        }
        val userId = ProxyTokenRepo.resolveUser(token)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, ProxyError(ProxyErrorBody("authentication_error", "Invalid proxy token")))
            return
        }
        val perms = UserRepo.permissionsOf(userId)
        if (Permission.PROXY_USE !in perms) {
            call.respond(HttpStatusCode.Forbidden, ProxyError(ProxyErrorBody("permission_error", "Token lacks proxy.use")))
            return
        }
        // Admins may use any account; others are scoped to their granted groups
        // (ungrouped accounts are always available).
        val allowedGroups: Set<Int>? = if (Permission.ADMIN in perms) null else UserRepo.allowedGroupsOf(userId)

        val bodyBytes = runCatching { call.receive<ByteArray>() }.getOrDefault(ByteArray(0))
        val pathAndQuery = call.request.uri

        val maxAttempts = maxOf(1, pool.snapshot().size)
        val tried = HashSet<Int>()
        repeat(maxAttempts) {
            val account = pool.select(allowedGroups) ?: run {
                respondExhausted(call)
                return
            }
            if (!tried.add(account.id)) {
                respondExhausted(call)
                return
            }
            when (val result = forwarder.forward(call, account, pathAndQuery, bodyBytes, userId)) {
                is ForwardResult.Served -> return
                is ForwardResult.RateLimited -> {
                    log.info("Account {} rate-limited, trying next", account.id)
                    // loop continues, next select() excludes this account
                }
            }
        }
        respondExhausted(call)
    }

    private suspend fun respondExhausted(call: ApplicationCall) {
        val reset = pool.earliestReset()
        val msg = if (reset != null) "All accounts are at their limit; earliest reset at $reset"
        else "No available account to serve the request"
        call.response.headers.append("x-claude-proxy-exhausted", "true")
        reset?.let { call.response.headers.append("x-claude-proxy-reset", it.toString()) }
        call.respond(HttpStatusCode.TooManyRequests, ProxyError(ProxyErrorBody("rate_limit_error", msg)))
    }

    private fun extractInboundToken(call: ApplicationCall): String? {
        call.request.headers["x-api-key"]?.takeIf { it.isNotBlank() }?.let { return it }
        val auth = call.request.headers["Authorization"] ?: return null
        return auth.removePrefix("Bearer ").trim().takeIf { it.isNotBlank() }
    }
}

/** Mounts the proxy under Anthropic API paths. Claude Code appends /v1/... to the base URL. */
fun Route.proxyRoutes(engine: ProxyEngine) {
    route("/v1/{...}") {
        handle {
            engine.handle(call)
        }
    }
}
