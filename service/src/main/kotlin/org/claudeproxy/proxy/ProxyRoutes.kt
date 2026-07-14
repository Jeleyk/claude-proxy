package org.claudeproxy.proxy

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
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
        log.info("IN {} {} clen={} te={} expect={}", call.request.httpMethod.value, call.request.uri,
            call.request.headers["content-length"], call.request.headers["transfer-encoding"], call.request.headers["expect"])
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
        // Routing order preference: try the global pool before personal accounts, or vice versa (default).
        val personalFirst = !UserRepo.preferGlobalPoolOf(userId)
        // Routing through the shared pool requires POOL_GLOBAL_USE (admins always allowed).
        val allowGlobal = Permission.ADMIN in perms || Permission.POOL_GLOBAL_USE in perms

        val bodyBytes = runCatching { call.receive<ByteArray>() }
            .onFailure { log.warn("body read failed for {}: {}", call.request.uri, it.toString()) }
            .getOrDefault(ByteArray(0))
        val pathAndQuery = call.request.uri
        log.info("BODY {} bytes for {}", bodyBytes.size, pathAndQuery)

        // Requests that don't consume subscription quota (token counting, model listing)
        // should always work if any account exists — no limit checks, ignore rate-limit.
        if (isFreePath(pathAndQuery)) {
            val account = pool.selectAny(userId, allowedGroups, personalFirst, allowGlobal)
            if (account == null) { respondNoAccount(call, userId, allowedGroups); return }
            forwarder.forward(call, account, pathAndQuery, bodyBytes, userId, canRetry = false, allowedGroups = allowedGroups)
            return
        }

        // Per-user daily spend limit in USD. Personal-account usage is the user's own quota
        // and does not count toward this shared-pool limit. When the shared limit is reached
        // the user may still route through their own personal accounts (their own quota).
        val costLimit = UserRepo.dailyLimitOf(userId)
        val usedCost = if (costLimit != null) UsageRepo.userTotals(userId, UserRepo.startOfUtcDay(), globalOnly = true).cost else 0.0
        val overLimit = costLimit != null && usedCost >= costLimit

        // Try accounts in order; every account except the last may retry to the next one.
        // The last account's real upstream response (incl. 429/5xx + retry-after) is passed
        // straight through to the client, so Claude Code sees the true status and backoff.
        val order = if (overLimit) pool.selectionOrderOwned(userId) else pool.selectionOrder(userId, allowedGroups, personalFirst, allowGlobal)
        if (order.isEmpty()) {
            if (overLimit) {
                call.response.headers.append("x-claude-proxy-daily-limit-usd", costLimit.toString())
                call.response.headers.append("x-claude-proxy-daily-used-usd", usedCost.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ProxyError(ProxyErrorBody("rate_limit_error", "Daily spend limit reached ($%.4f/$%.4f); resets at 00:00 UTC. Add a personal account to keep working.".format(usedCost, costLimit ?: 0.0))),
                )
            } else {
                respondNoAccount(call, userId, allowedGroups)
            }
            return
        }
        for ((i, account) in order.withIndex()) {
            pool.markActive(account.id)
            val isLast = i == order.lastIndex
            when (forwarder.forward(call, account, pathAndQuery, bodyBytes, userId, canRetry = !isLast, allowedGroups = allowedGroups)) {
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
    private suspend fun respondNoAccount(call: ApplicationCall, userId: Int, allowedGroups: Set<Int>?) {
        val a = pool.availability(userId, allowedGroups)
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

private val routeLog = LoggerFactory.getLogger("ProxyRoute")

/** Mounts the proxy under Anthropic API paths. Claude Code appends /v1/... to the base URL. */
fun Route.proxyRoutes(engine: ProxyEngine) {
    route("/v1/{...}") {
        handle {
            try {
                engine.handle(call)
            } catch (t: Throwable) {
                routeLog.error("proxy handler threw for {} {}: {}", call.request.httpMethod.value, call.request.uri, t.toString(), t)
                throw t
            }
        }
    }
}
