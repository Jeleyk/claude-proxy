package org.claudeproxy.datapath

import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.accounts.RateLimitHeaders
import org.claudeproxy.api.CandidateDto
import org.claudeproxy.api.UsageReport
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.Permission
import org.claudeproxy.model.WindowKind
import org.claudeproxy.repo.McpUsageRepo
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.UsageRepo
import org.claudeproxy.repo.ModelPriceRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Why a resolve failed, mapped by the route to 401/403. */
enum class ResolveError { BAD_TOKEN, NO_PERMISSION }

/**
 * Result of resolving an inbound datapath request into an ordered try-list. On error,
 * [error] is set and [candidates] is empty. Empty candidates with [overLimit]=true means
 * the daily spend limit was hit with no personal fallback; empty with overLimit=false
 * means no usable account.
 */
data class ResolveResult(
    val userId: Int?,
    val tokenId: Int?,
    val error: ResolveError?,
    val overLimit: Boolean,
    val dailyLimitUsd: Double?,
    val usedUsd: Double?,
    val candidates: List<CandidateDto>,
    // routing only: the token's static system prompt, injected by the gateway ahead of the
    // client's own system content (but after the mandatory Claude Code block).
    val systemPrompt: String? = null,
)

/**
 * The reusable datapath decision logic, extracted from `ProxyRoutes`/`UpstreamForwarder`
 * so both the in-process Kotlin datapath and the `/internal/` control API (Go gateway)
 * share one implementation. `resolve` turns a request into an ordered plan; `applyOutcome`
 * applies a single attempt's result to account bookkeeping. The service stays the single
 * source of truth — selection, decryption, and limit state never leave it.
 */
class DatapathService(private val pool: AccountPool) {

    /**
     * Resolve a request into an ordered, ready-to-forward candidate list. [source] selects the
     * datapath: `"proxy"` (Claude Code, the default) or `"routing"` (the OpenAI/Anthropic API
     * gateways). Routing resolves the token in the routing-token namespace, requires
     * `ROUTING_USE`, and meters spend against the separate per-user routing daily limit. Account
     * selection (personal-first, group scope, global-pool gating) is identical for both.
     *
     * [requestId], when the caller supplies one, opens an [ActiveSessions] entry for the "active
     * now" gauges; the caller closes it when the request finishes.
     */
    suspend fun resolve(
        token: String, method: String, path: String, source: String = "proxy", requestId: String? = null,
    ): ResolveResult {
        val routing = source == "routing"
        val auth = (if (routing) RoutingTokenRepo.resolveAuth(token) else ProxyTokenRepo.resolveAuth(token))
            ?: return ResolveResult(null, null, ResolveError.BAD_TOKEN, false, null, null, emptyList())
        val userId = auth.userId
        val tokenId = auth.tokenId
        val perms = UserRepo.permissionsOf(userId)
        val required = if (routing) Permission.ROUTING_USE else Permission.PROXY_USE
        if (required !in perms) {
            return ResolveResult(userId, tokenId, ResolveError.NO_PERMISSION, false, null, null, emptyList())
        }
        // Only a resolve the caller can act on opens a session — a rejected one never gets a
        // matching close, and would sit in the map until it aged out.
        if (requestId != null) ActiveSessions.begin(requestId, userId, routing)

        // Admins may use any account; others are scoped to their granted groups.
        val allowedGroups: Set<Int>? = if (Permission.ADMIN in perms) null else UserRepo.allowedGroupsOf(userId)
        val personalFirst = !UserRepo.preferGlobalPoolOf(userId)
        // Routing through the shared pool requires POOL_GLOBAL_USE (admins always allowed).
        // Without it a user reaches only their own personal accounts.
        val allowGlobal = Permission.ADMIN in perms || Permission.POOL_GLOBAL_USE in perms

        val sysPrompt = if (routing) tokenId?.let { RoutingTokenRepo.promptOf(it) } else null

        // Free paths (token counting, model listing) never consume quota: any account, no limits.
        if (isFreePath(path)) {
            val account = pool.selectAny(userId, allowedGroups, personalFirst, allowGlobal)
            return ResolveResult(userId, tokenId, null, false, null, null, listOfNotNull(account).map { it.toCandidate() }, sysPrompt)
        }

        // Per-user daily USD limit is a shared-pool constraint; personal accounts are exempt.
        val costLimit = if (routing) UserRepo.dailyRoutingLimitOf(userId) else UserRepo.dailyLimitOf(userId)
        val usedCost = if (costLimit != null) cachedDailySpend(userId, routing) else 0.0
        val overLimit = costLimit != null && usedCost >= costLimit

        val order = if (overLimit) pool.selectionOrderOwned(userId) else pool.selectionOrder(userId, allowedGroups, personalFirst, allowGlobal)
        return ResolveResult(userId, tokenId, null, overLimit, costLimit, usedCost, order.map { it.toCandidate() }, sysPrompt)
    }

    /**
     * Apply one upstream attempt's outcome: record usage, refresh limit state from headers,
     * and run the per-status account bookkeeping that `UpstreamForwarder` did inline.
     */
    suspend fun applyOutcome(o: UsageReport) {
        val routing = o.source == "routing"
        val source = if (routing) "routing" else "proxy"
        UsageRepo.record(o.accountId, o.userId, o.input, o.cacheRead, o.cacheWrite, o.output, o.status, o.model, source, o.tokenId)
        if (o.mcpCalls.isNotEmpty()) McpUsageRepo.record(o.userId, o.tokenId, o.mcpCalls)

        // Keep the cached daily spend fresh. Only *global* (shared-pool) usage counts toward the
        // per-user daily limit; personal accounts are the user's own quota (exempt). Proxy and
        // routing spend accumulate under separate keys so each limit meters only its own datapath.
        // The increment is a no-op when the key isn't cached — the next resolve recomputes from DB.
        val ownerId = pool.get(o.accountId)?.ownerId
        if (o.userId != null && ownerId == null) {
            val cost = ModelPriceRepo.costOf(o.model, o.input, o.cacheRead, o.cacheWrite, o.output)
            if (cost > 0.0) MemoryCache.incrExistingByFloat(spendKey(o.userId, routing), cost)
        }

        val prev = pool.get(o.accountId)?.limit ?: LimitState()
        val newLimit = RateLimitHeaders.parse(o.ratelimitHeaders, prev)
        pool.updateLimit(o.accountId, newLimit)

        when {
            o.status == 429 -> {
                // Park the account: honor retry-after; else park to the window reset only when
                // genuinely at the limit (util ~full), otherwise a short burst-limit backoff.
                val retryAfter = resetInstantFrom(o.ratelimitHeaders)
                val maxUtil = newLimit.windows.values.mapNotNull { it.utilization }.maxOrNull() ?: 0.0
                val until = when {
                    retryAfter != null -> retryAfter
                    maxUtil >= 0.95 -> newLimit.windows.values.mapNotNull { it.resetAt }.minOrNull()
                    else -> Instant.now().plusSeconds(60)
                }
                pool.markRateLimited(o.accountId, until)
            }
            o.status == 401 -> {
                pool.setHealth(o.accountId, AccountHealth.REFRESH_FAILED)
                runCatching { AccountRepo.updateHealth(o.accountId, AccountHealth.REFRESH_FAILED) }
            }
        }
    }

    /** Format an ordered account into a gateway-ready candidate (decrypted auth headers). */
    private fun AccountRuntime.toCandidate(): CandidateDto = CandidateDto(
        accountId = id,
        type = type.name,
        deviceId = deviceId,
        authHeaders = authHeadersFor(this),
        fiveHourResetEpoch = limit.window(WindowKind.FIVE_HOUR)?.resetAt?.epochSecond,
        weeklyResetEpoch = limit.window(WindowKind.WEEKLY)?.resetAt?.epochSecond,
    )

    /**
     * Build the upstream auth headers exactly like `UpstreamAuth.apply`: OAUTH →
     * `Authorization: Bearer <access>` + `anthropic-beta: oauth-2025-04-20`; API_KEY → `x-api-key`.
     */
    private fun authHeadersFor(a: AccountRuntime): Map<String, String> = buildMap {
        when (a.type) {
            AccountType.API_KEY -> a.secret.apiKey?.let { put("x-api-key", it) }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                a.secret.accessToken?.let { put("Authorization", "Bearer $it") }
                put("anthropic-beta", "oauth-2025-04-20")
            }
        }
    }

    /**
     * Cached shared-pool daily spend (USD) for a user on the given datapath. Cached in-process
     * under `cp:spend:<user>:<utcDate>` (proxy) / `cp:rspend:<user>:<utcDate>` (routing) until
     * the next UTC midnight, with a DB recompute on miss.
     */
    private fun cachedDailySpend(userId: Int, routing: Boolean): Double {
        val ttl = secondsToUtcMidnight()
        val cached = MemoryCache.getOrLoad(spendKey(userId, routing), ttl) {
            UsageRepo.userTotals(userId, UserRepo.startOfUtcDay(), globalOnly = true, source = if (routing) "routing" else "proxy").cost.toString()
        }
        return cached?.toDoubleOrNull() ?: 0.0
    }

    private fun spendKey(userId: Int, routing: Boolean): String =
        "cp:${if (routing) "rspend" else "spend"}:$userId:${LocalDate.now(ZoneOffset.UTC)}"

    private fun secondsToUtcMidnight(): Long {
        val nextMidnight = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return maxOf(1L, java.time.Duration.between(Instant.now(), nextMidnight).seconds)
    }

    private fun resetInstantFrom(headers: Map<String, String>): Instant? {
        val retryAfter = headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value
        retryAfter?.trim()?.toLongOrNull()?.let { return Instant.now().plusSeconds(it) }
        return null
    }

    /** Paths that don't consume subscription usage (mirrors `ProxyRoutes.isFreePath`). */
    private fun isFreePath(pathAndQuery: String): Boolean {
        val p = pathAndQuery.substringBefore('?')
        return p.contains("count_tokens") || p.endsWith("/v1/models") || p.contains("/v1/models/")
    }
}
