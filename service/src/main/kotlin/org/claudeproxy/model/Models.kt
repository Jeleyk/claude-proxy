package org.claudeproxy.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Permissions a user can hold (directly, via roles). */
enum class Permission {
    PROXY_USE,          // may route requests through the proxy
    STATS_VIEW_OWN,     // may view only their own usage statistics
    STATS_RESET_OWN,    // may reset their own stats (resets daily spend — can bypass a limit)
    ACCOUNTS_OWN_MANAGE,// may manage their own personal accounts (tried before the global pool)
    ACCOUNTS_ORDER_TOGGLE,// may switch whether their personal accounts or the global pool are tried first
    POOL_GLOBAL_USE,    // may route requests through the shared (global) account pool
    ROUTING_USE,        // may use the OpenAI/Anthropic API routing gateways + manage routing tokens
    STATS_VIEW_RECENT,  // may view the list of recent requests (pool-wide)
    STATS_VIEW_ACCOUNTS,// may see which account each request/stat came from
    ACCOUNTS_VIEW,      // may view upstream accounts + their limits
    STATS_VIEW,         // full statistics (implies recent + accounts + graphs)
    ACCOUNTS_MANAGE,    // may create/edit/delete upstream accounts
    USERS_MANAGE,       // may create/edit/delete users and roles
    ADMIN;              // superuser: implies everything

    companion object {
        fun fromString(s: String): Permission? = entries.firstOrNull { it.name == s }
    }
}

/** How an upstream account authenticates to Anthropic. */
enum class AccountType {
    OAUTH,         // access_token + refresh_token, auto-refreshed
    OAUTH_STATIC,  // access_token only (no refresh), works until expiry
    API_KEY;       // static x-api-key

    companion object {
        fun fromString(s: String): AccountType? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

enum class AccountHealth { OK, REFRESH_FAILED, DEAD }

enum class LimitStatus { ALLOWED, ALLOWED_WARNING, REJECTED, UNKNOWN }

/** Rolling limit windows Anthropic exposes for subscription accounts. */
enum class WindowKind(val code: String, val label: String) {
    FIVE_HOUR("5h", "5-hour"),
    WEEKLY("7d", "weekly");

    companion object {
        fun fromCode(s: String): WindowKind? = entries.firstOrNull { it.code == s }
    }
}

// ---- API DTOs ----

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    val enabled: Boolean,
    val roles: List<String>,
    val permissions: List<String>,
    // ids of account-groups this user may route through (empty = only ungrouped accounts,
    // unless the user is an admin, who may use everything)
    val allowedGroups: List<Int> = emptyList(),
    val allGroups: Boolean = false,
    // per-day spend limit in USD; null = unlimited
    val dailyCostLimit: Double? = null,
    // per-day spend limit in USD for the OpenAI/Anthropic routing gateways; null = unlimited.
    // Tracked separately from dailyCostLimit — routing spend is metered against this one.
    val dailyRoutingCostLimit: Double? = null,
    // routing preference: true = try the global pool before personal accounts (default false = personal first)
    val preferGlobalPool: Boolean = false,
    // usage since the start of the current UTC day
    val todayCost: Double = 0.0,
    // routing spend (source=routing) since the start of the current UTC day
    val todayRoutingCost: Double = 0.0,
    val todayInputTokens: Long = 0,
    val todayOutputTokens: Long = 0,
)

@Serializable
data class AccountGroupDto(
    val id: Int,
    val name: String,
    val accountCount: Int,
    val createdAt: String,
)

@Serializable
data class WindowLimitDto(
    val usageFraction: Double?,
    val remaining: Double?,
    val limitTotal: Double?,
    val resetAt: String?,
    val status: String?,
    val updatedAt: String?,
)

@Serializable
data class AccountDto(
    val id: Int,
    val name: String,
    val type: String,
    val groupId: Int?,
    // null = global (shared pool); otherwise the owning user's id (personal account)
    val ownerId: Int? = null,
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    // opt-in fallback: keep using this account past its threshold when all accounts are saturated
    val overThreshold: Boolean = false,
    val health: String,
    // live limit state per window (nullable when never observed)
    val fiveHour: WindowLimitDto?,
    val weekly: WindowLimitDto?,
    // usage fraction driving selection (max across windows), 0..1
    val usageFraction: Double?,
    val rateLimitedUntil: String?,
    val effectiveRemaining: Double?, // coefficient-weighted remaining capacity
    // cumulative counters for this account (all-time)
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCacheWriteTokens: Long,
    val totalCost: Double,
    val totalRequests: Long,
    val deviceId: String?,
    val createdAt: String,
)

@Serializable
data class ProxyTokenDto(
    val id: Int,
    val name: String,
    val userId: Int,
    val createdAt: String,
    val lastUsedAt: String?,
    // full token value only returned once, at creation time
    val token: String? = null,
    // static system prompt (routing tokens only; null for proxy tokens / unset)
    val systemPrompt: String? = null,
    // off = the token no longer authenticates, without being revoked
    val enabled: Boolean = true,
)

@Serializable
data class PoolStatsDto(
    val totalAccounts: Int,
    val healthyAccounts: Int,
    val activeAccountId: Int?,
    val totalEffectiveRemaining: Double,   // Σ coefficient-weighted remaining (5-hour)
    val totalEffectiveCapacity: Double,    // Σ coefficient
    // same, for the weekly window — drives the "Pool headroom" card subtitle
    val totalWeeklyRemaining: Double,
    val totalWeeklyCapacity: Double,
    // pool-wide counters (all-time)
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCacheWriteTokens: Long,
    val totalCost: Double,
    val totalRequests: Long,
    // nearest reset times across the pool, per window
    val nextFiveHourReset: String?,
    val nextWeeklyReset: String?,
    // requests being streamed from Anthropic right now, by datapath. Scoped like the rest of the
    // payload: pool-wide view = everyone, personal view = that user's own.
    val activeProxySessions: Int = 0,
    val activeRoutingSessions: Int = 0,
    val accounts: List<AccountDto>,
)

/** Live limit state for a single rolling window. */
data class WindowLimit(
    // Anthropic reports usage directly as a 0..1 `utilization` fraction (subscription).
    val utilization: Double? = null,
    // API-key style limits report remaining/limit instead.
    val remaining: Double? = null,
    val limitTotal: Double? = null,
    val resetAt: Instant? = null,
    val status: LimitStatus = LimitStatus.UNKNOWN,
    val updatedAt: Instant? = null,
) {
    /** Usage fraction 0..1: prefer the reported utilization, else derive from remaining/limit. */
    fun usageFraction(): Double? {
        utilization?.let { return it.coerceIn(0.0, 1.0) }
        val r = remaining ?: return null
        val t = limitTotal ?: return null
        if (t <= 0.0) return null
        return (1.0 - (r / t)).coerceIn(0.0, 1.0)
    }

    fun isEmpty(): Boolean =
        utilization == null && remaining == null && limitTotal == null && resetAt == null && status == LimitStatus.UNKNOWN
}

/** Immutable snapshot of an account's live limit state across all windows. */
data class LimitState(
    val windows: Map<WindowKind, WindowLimit> = emptyMap(),
    val rateLimitedUntil: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun window(kind: WindowKind): WindowLimit? = windows[kind]

    /** Usage fraction that drives selection: the max across known windows. */
    fun usageFraction(): Double? =
        windows.values.mapNotNull { it.usageFraction() }.maxOrNull()
}
