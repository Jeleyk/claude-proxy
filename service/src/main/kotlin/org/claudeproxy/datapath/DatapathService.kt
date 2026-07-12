package org.claudeproxy.datapath

import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.accounts.RateLimitHeaders
import org.claudeproxy.api.CandidateDto
import org.claudeproxy.api.UsageReport
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.Permission
import org.claudeproxy.model.WindowKind
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.UsageRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant

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
    val error: ResolveError?,
    val overLimit: Boolean,
    val dailyLimitUsd: Double?,
    val usedUsd: Double?,
    val candidates: List<CandidateDto>,
)

/**
 * The reusable datapath decision logic, extracted from `ProxyRoutes`/`UpstreamForwarder`
 * so both the in-process Kotlin datapath and the `/internal/` control API (Go gateway)
 * share one implementation. `resolve` turns a request into an ordered plan; `applyOutcome`
 * applies a single attempt's result to account bookkeeping. The service stays the single
 * source of truth — selection, decryption, and limit state never leave it.
 */
class DatapathService(private val pool: AccountPool) {

    /** Resolve a request into an ordered, ready-to-forward candidate list. */
    suspend fun resolve(token: String, method: String, path: String): ResolveResult {
        val userId = ProxyTokenRepo.resolveUser(token)
            ?: return ResolveResult(null, ResolveError.BAD_TOKEN, false, null, null, emptyList())
        val perms = UserRepo.permissionsOf(userId)
        if (Permission.PROXY_USE !in perms) {
            return ResolveResult(userId, ResolveError.NO_PERMISSION, false, null, null, emptyList())
        }

        // Admins may use any account; others are scoped to their granted groups.
        val allowedGroups: Set<Int>? = if (Permission.ADMIN in perms) null else UserRepo.allowedGroupsOf(userId)
        val personalFirst = !UserRepo.preferGlobalPoolOf(userId)

        // Free paths (token counting, model listing) never consume quota: any account, no limits.
        if (isFreePath(path)) {
            val account = pool.selectAny(userId, allowedGroups, personalFirst)
            return ResolveResult(userId, null, false, null, null, listOfNotNull(account).map { it.toCandidate() })
        }

        // Per-user daily USD limit is a shared-pool constraint; personal accounts are exempt.
        val costLimit = UserRepo.dailyLimitOf(userId)
        val usedCost = if (costLimit != null) UsageRepo.userTotals(userId, UserRepo.startOfUtcDay(), globalOnly = true).cost else 0.0
        val overLimit = costLimit != null && usedCost >= costLimit

        val order = if (overLimit) pool.selectionOrderOwned(userId) else pool.selectionOrder(userId, allowedGroups, personalFirst)
        return ResolveResult(userId, null, overLimit, costLimit, usedCost, order.map { it.toCandidate() })
    }

    /**
     * Apply one upstream attempt's outcome: record usage, refresh limit state from headers,
     * and run the per-status account bookkeeping that `UpstreamForwarder` did inline.
     */
    suspend fun applyOutcome(o: UsageReport) {
        UsageRepo.record(o.accountId, o.userId, o.input, o.cacheRead, o.cacheWrite, o.output, o.status, o.model)

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
