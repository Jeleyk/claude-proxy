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
import org.claudeproxy.repo.SettingsRepo
import org.claudeproxy.repo.UsageRepo
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

        // Requests that don't consume subscription quota (token counting, model listing)
        // should always work if any account exists — no limit checks, ignore rate-limit.
        if (isFreePath(pathAndQuery)) {
            val account = pool.selectAny(allowedGroups)
            if (account == null) { respondNoAccount(call, allowedGroups); return }
            forwarder.forward(call, account, pathAndQuery, bodyBytes, userId, canRetry = false)
            return
        }

        // Per-user daily spend limit in USD.
        val costLimit = UserRepo.dailyLimitOf(userId)
        if (costLimit != null) {
            val usedCost = UsageRepo.userTotals(userId, UserRepo.startOfUtcDay()).cost
            if (usedCost >= costLimit) {
                call.response.headers.append("x-claude-proxy-daily-limit-usd", costLimit.toString())
                call.response.headers.append("x-claude-proxy-daily-used-usd", usedCost.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ProxyError(ProxyErrorBody("rate_limit_error", "Daily spend limit reached ($%.4f/$%.4f); resets at 00:00 UTC".format(usedCost, costLimit))),
                )
                return
            }
        }

        // Try accounts in order; every account except the last may retry to the next one.
        // The last account's real upstream response (incl. 429/5xx + retry-after) is passed
        // straight through to the client, so Claude Code sees the true status and backoff.
        val order = pool.selectionOrder(allowedGroups)
        if (order.isEmpty()) {
            respondNoAccount(call, allowedGroups)
            return
        }
        for ((i, account) in order.withIndex()) {
            pool.markActive(account.id)
            val isLast = i == order.lastIndex
            when (forwarder.forward(call, account, pathAndQuery, bodyBytes, userId, canRetry = !isLast)) {
                is ForwardResult.Served -> return
                is ForwardResult.Retry -> log.info("Account {} unavailable, trying next", account.id)
            }
        }
    }

    /** Paths that don't consume subscription usage. */
    private fun isFreePath(pathAndQuery: String): Boolean {
        val p = pathAndQuery.substringBefore('?')
        return p.contains("count_tokens") || p.endsWith("/v1/models") || p.contains("/v1/models/")
    }

    /** No usable account at all (none enabled/healthy in scope, or all hard rate-limited). */
    private suspend fun respondNoAccount(call: ApplicationCall, allowedGroups: Set<Int>?) {
        val a = pool.availability(allowedGroups)
        if (a.lostAccess > 0 && a.rateLimited == 0 && a.healthy == 0) {
            call.respond(
                HttpStatusCode.fromValue(529),
                ProxyError(ProxyErrorBody("overloaded_error", "One of the accounts lost access — retrying")),
            )
            return
        }
        val reset = pool.earliestReset()
        val msg = when {
            a.enabledInScope == 0 -> "No account is available for this token"
            reset != null -> "All accounts are rate-limited; earliest reset at $reset"
            else -> "All accounts are exhausted"
        }
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
