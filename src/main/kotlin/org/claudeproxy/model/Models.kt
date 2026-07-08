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

// ---- API DTOs ----

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    val enabled: Boolean,
    val roles: List<String>,
    val permissions: List<String>,
)

@Serializable
data class AccountDto(
    val id: Int,
    val name: String,
    val type: String,
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    val health: String,
    // live limit state (nullable when never observed)
    val usageFraction: Double?,      // 0..1 self-normalized usage of the active window
    val remaining: Double?,
    val limitTotal: Double?,
    val resetAt: String?,
    val status: String?,
    val rateLimitedUntil: String?,
    val effectiveRemaining: Double?, // coefficient-weighted remaining capacity
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
    val accounts: List<AccountDto>,
)

/** Immutable snapshot of an account's live limit state, held in memory + persisted. */
data class LimitState(
    val remaining: Double? = null,
    val limitTotal: Double? = null,
    val resetAt: Instant? = null,
    val status: LimitStatus = LimitStatus.UNKNOWN,
    val rateLimitedUntil: Instant? = null,
    val updatedAt: Instant? = null,
) {
    /** Self-normalized usage fraction 0..1, or null if unknown. */
    fun usageFraction(): Double? {
        val r = remaining ?: return null
        val t = limitTotal ?: return null
        if (t <= 0.0) return null
        return (1.0 - (r / t)).coerceIn(0.0, 1.0)
    }
}
