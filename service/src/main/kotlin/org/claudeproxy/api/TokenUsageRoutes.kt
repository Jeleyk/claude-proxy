package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.model.AccountProvider
import org.claudeproxy.model.Permission
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class TokenUsageWindow(val used_percentage: Double?, val resets_at: Long?, val updated_at: Long?, val stale: Boolean)
@Serializable
data class TokenRateLimits(val five_hour: TokenUsageWindow?, val seven_day: TokenUsageWindow?)
@Serializable
data class TokenAccountLimits(val scope: String, val available: Boolean, val rate_limits: TokenRateLimits)
@Serializable
data class TokenDailyLimit(val used_usd: Double, val limit_usd: Double?, val exhausted: Boolean, val resets_at: Long)
@Serializable
data class TokenUsageSnapshot(
    val snapshot_at: Long,
    val source: String,
    val basis: String = "next_request_candidate",
    val next_account_index: Int?,
    val available_accounts: Int,
    val rate_limits: TokenRateLimits?,
    val daily: TokenDailyLimit,
    val accounts: List<TokenAccountLimits>,
    val provider: String = "ANTHROPIC",
    val price_missing: Boolean = false,
    val metering_unsupported: Boolean = false,
)

/** A reset passing never proves a zero reading: retain the last value and mark it stale. */
internal fun tokenUsageWindow(window: WindowLimit?, now: Instant): TokenUsageWindow? = window?.let {
    TokenUsageWindow(it.usageFraction()?.times(100), it.resetAt?.epochSecond, it.updatedAt?.epochSecond,
        it.updatedAt == null || it.updatedAt.isBefore(now.minusSeconds(35 * 60)) ||
            (it.resetAt != null && !it.resetAt.isAfter(now)))
}

/** Read-only cached quotas. API tokens never gain management access or upstream credentials. */
fun Route.tokenUsageRoutes(pool: AccountPool, datapath: DatapathService) {
    get("/gateway/v1/usage") {
        call.response.headers.append("Cache-Control", "no-store")
        val authHeader = call.request.headers["Authorization"]
        val token = if (authHeader != null) {
            if (!authHeader.startsWith("Bearer ", ignoreCase = true)) "" else authHeader.substring(7).trim()
        } else call.request.headers["x-api-key"]?.trim().orEmpty()
        val routing = token.startsWith("cxr_")
        val auth = if (token.isBlank()) null else if (routing) RoutingTokenRepo.resolveAuth(token) else ProxyTokenRepo.resolveAuth(token)
        val user = auth?.let { UserRepo.findAuth(it.userId) }?.takeIf { it.enabled }
            ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("Invalid API token"))
        val required = if (routing) Permission.ROUTING_USE else Permission.PROXY_USE
        if (required !in user.permissions) return@get call.respond(HttpStatusCode.Forbidden, MessageResponse("Missing permission"))
        val provider = AccountProvider.fromString(call.request.queryParameters["provider"] ?: "ANTHROPIC")
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Unknown provider"))
        if (provider == AccountProvider.OPENAI && !routing)
            return@get call.respond(HttpStatusCode.Forbidden, MessageResponse("OpenAI requires a routing token"))
        val now = Instant.now()
        val source = if (routing) "routing" else "proxy"
        val cap = if (routing) UserRepo.dailyRoutingLimitOf(user.id) else UserRepo.dailyLimitOf(user.id)
        val spent = datapath.cachedDailySpend(user.id, source)
        val exhausted = cap != null && spent >= cap
        val groups = if (Permission.ADMIN in user.permissions) null else UserRepo.allowedGroupsOf(user.id)
        val allowGlobal = Permission.ADMIN in user.permissions || Permission.POOL_GLOBAL_USE in user.permissions
        val meteringUnsupported = provider == AccountProvider.OPENAI && cap != null
        val eligible = if (exhausted || meteringUnsupported) pool.selectionOrderOwned(user.id, now, provider)
            else pool.selectionOrder(user.id, groups, !UserRepo.preferGlobalPoolOf(user.id), allowGlobal, now, provider)
        val availableIds = eligible.map { it.id }.toSet()
        // Include exhausted/unhealthy accounts for visibility, but never other users' personal
        // accounts or groups. No account IDs, names, email, device IDs or credentials are emitted.
        val scoped = pool.snapshot().filter {
            it.provider == provider && it.enabled && (if (it.ownerId != null) it.ownerId == user.id
            else allowGlobal && (groups == null || it.groupId == null || it.groupId in groups))
        }.sortedWith(compareBy({ if (it.id == eligible.firstOrNull()?.id) 0 else 1 }, { it.priority }, { it.id }))
        val accounts = scoped.map { a -> TokenAccountLimits(
            scope = if (a.ownerId == null) "shared" else "personal", available = a.id in availableIds,
            rate_limits = TokenRateLimits(tokenUsageWindow(a.limit.window(WindowKind.FIVE_HOUR), now),
                tokenUsageWindow(a.limit.window(WindowKind.WEEKLY), now))) }
        val next = scoped.indexOfFirst { it.id == eligible.firstOrNull()?.id }.takeIf { it >= 0 }
        call.respond(TokenUsageSnapshot(snapshot_at=now.epochSecond, source=source, next_account_index=next,
            available_accounts=accounts.count { it.available }, rate_limits=next?.let { accounts[it].rate_limits },
            daily=TokenDailyLimit(spent, cap, exhausted, now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond()), accounts=accounts, provider=provider.name, metering_unsupported=meteringUnsupported))
    }
}
