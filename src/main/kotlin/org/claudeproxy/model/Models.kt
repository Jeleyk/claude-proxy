package org.claudeproxy.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Permissions a user can hold (directly, via roles). */
enum class Permission {
    PROXY_USE,        // may route requests through the proxy
    ACCOUNTS_VIEW,    // may view upstream accounts + their limits
    STATS_VIEW,       // may view usage statistics
    ACCOUNTS_MANAGE,  // may create/edit/delete upstream accounts
    USERS_MANAGE,     // may create/edit/delete users and roles
    ADMIN;            // superuser: implies everything

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
    // per-day budget value (interpreted per basis); null = unlimited
    val dailyTokenLimit: Long? = null,
    val dailyLimitBasis: String = "DIRTY",   // CLEAN | DIRTY | PERCENT
    // tokens the user has spent since the start of the current UTC day
    val todayCleanTokens: Long = 0,
    val todayDirtyTokens: Long = 0,
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
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    val health: String,
    // live limit state per window (nullable when never observed)
    val fiveHour: WindowLimitDto?,
    val weekly: WindowLimitDto?,
    // usage fraction driving selection (max across windows), 0..1
    val usageFraction: Double?,
    val rateLimitedUntil: String?,
    val effectiveRemaining: Double?, // coefficient-weighted remaining capacity
    // cumulative token counters for this account (all-time)
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalDirtyTokens: Long,
    val totalRequests: Long,
    val clientId: String?,
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
)

@Serializable
data class PoolStatsDto(
    val totalAccounts: Int,
    val healthyAccounts: Int,
    val activeAccountId: Int?,
    val totalEffectiveRemaining: Double,   // Σ coefficient-weighted remaining
    val totalEffectiveCapacity: Double,    // Σ coefficient
    // pool-wide token counters (all-time)
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalDirtyTokens: Long,
    val totalRequests: Long,
    // nearest reset times across the pool, per window
    val nextFiveHourReset: String?,
    val nextWeeklyReset: String?,
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
