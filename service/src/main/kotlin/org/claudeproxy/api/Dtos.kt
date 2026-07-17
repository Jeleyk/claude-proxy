package org.claudeproxy.api

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class CreateAccountRequest(
    val name: String,
    val type: String,               // API_KEY | OAUTH | OAUTH_STATIC
    val groupId: Int? = null,
    val priority: Int = 100,
    val threshold: Double = 0.9,
    val coefficient: Double = 1.0,
    val apiKey: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,    // epoch millis
)

@Serializable
data class UpdateAccountRequest(
    val name: String? = null,
    val groupId: Int? = null,
    val clearGroup: Boolean = false,
    val priority: Int? = null,
    val threshold: Double? = null,
    val coefficient: Double? = null,
    val enabled: Boolean? = null,
    val overThreshold: Boolean? = null,
    val deviceId: String? = null,
)

@Serializable
data class CreateGroupRequest(val name: String)

@Serializable
data class UpdateGroupRequest(val name: String)

@Serializable
data class OAuthStartResponse(val authorizeUrl: String, val state: String)

@Serializable
data class OAuthCompleteRequest(
    val state: String,
    val code: String,
    val name: String,
    val groupId: Int? = null,
    val priority: Int = 100,
    val threshold: Double = 0.9,
    val coefficient: Double = 1.0,
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val roles: List<String> = emptyList(),
    val allowedGroups: List<Int> = emptyList(),
    val dailyCostLimit: Double? = null,
    val dailyRoutingCostLimit: Double? = null,
)

@Serializable
data class UpdateUserRequest(
    val password: String? = null,
    val enabled: Boolean? = null,
    val roles: List<String>? = null,
    val allowedGroups: List<Int>? = null,
    val dailyCostLimit: Double? = null,
    val clearDailyLimit: Boolean = false,
    val dailyRoutingCostLimit: Double? = null,
    val clearRoutingLimit: Boolean = false,
)

@Serializable
data class UpdateProfileRequest(
    // current password is required to authorize any self-service change
    val currentPassword: String,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class AccountOrderRequest(val preferGlobalPool: Boolean)

@Serializable
data class ModelPriceRequest(
    val pattern: String,
    val inputPrice: Double,
    val outputPrice: Double,
    val cacheReadPrice: Double = 0.0,
    val cacheWritePrice: Double = 0.0,
)

@Serializable
data class ConfigDto(val publicBaseUrl: String)

@Serializable
data class CreateRoleRequest(val name: String, val permissions: List<String> = emptyList())

@Serializable
data class UpdateRoleRequest(val permissions: List<String>)

@Serializable
data class CreateProxyTokenRequest(val name: String)

@Serializable
data class RolesPayload(val roles: List<org.claudeproxy.repo.RoleDto>, val allPermissions: List<String>)

@Serializable
data class StatsPayload(
    val summary: List<org.claudeproxy.repo.UsageSummaryDto>,
    val recent: List<org.claudeproxy.repo.UsageEventDto>,
)

@Serializable
data class MyStatsPayload(
    val todayCost: Double,
    val todayClean: Long,
    val todayRequests: Long,
    val totalCost: Double,
    val totalClean: Long,
    val totalRequests: Long,
    val dailyCostLimit: Double?,
    val perModel: List<org.claudeproxy.repo.ModelUsageDto>,        // all-time, per model
    val perModelToday: List<org.claudeproxy.repo.ModelUsageDto>,   // since start of the UTC day
    val recent: List<org.claudeproxy.repo.UsageEventDto>,
)

/** Pool-wide per-model breakdown for the global Statistics page (today vs all-time toggle). */
@Serializable
data class ModelBreakdownPayload(
    val today: List<org.claudeproxy.repo.ModelUsageDto>,
    val allTime: List<org.claudeproxy.repo.ModelUsageDto>,
)

@Serializable
data class AccountSeriesDto(
    val accountId: Int,
    val accountName: String?,
    val cost: List<Double>,
    val requests: List<Long>,
)

@Serializable
data class DailyStatsPayload(
    val days: List<String>,               // date labels (UTC), oldest→newest
    val totalCost: List<Double>,          // combined cost per day
    val totalRequests: List<Long>,
    val perAccount: List<AccountSeriesDto>, // empty if the viewer can't see accounts
    val canViewAccounts: Boolean,
)

@Serializable
data class TokenTotalsDto(                  // per-day token counts, four kinds kept apart
    val input: List<Long>,
    val output: List<Long>,
    val cacheRead: List<Long>,
    val cacheWrite: List<Long>,
)

@Serializable
data class TokenModelSeriesDto(
    val model: String,                     // "unknown" when the DB model is null
    val input: List<Long>,
    val output: List<Long>,
    val cacheRead: List<Long>,
    val cacheWrite: List<Long>,
)

@Serializable
data class TokenAccountSeriesDto(
    val accountId: Int,
    val accountName: String?,
    val input: List<Long>,
    val output: List<Long>,
    val cacheRead: List<Long>,
    val cacheWrite: List<Long>,
)

@Serializable
data class TokenStatsPayload(
    val days: List<String>,                // date labels (UTC), oldest→newest
    val total: TokenTotalsDto,             // summed across all accounts and models
    val perModel: List<TokenModelSeriesDto>,   // one entry per distinct model, sorted alphabetically
    val perAccount: List<TokenAccountSeriesDto>, // empty if the viewer can't see accounts
    val models: List<String>,              // sorted distinct model labels (incl. "unknown")
    val canViewAccounts: Boolean,
)

@Serializable
data class WindowSeriesDto(
    val accountId: Int,
    val accountName: String?,
    val fiveHour: List<Double?>,          // 0..1 utilization per bucket (null = no data)
    val weekly: List<Double?>,
    val fiveHourWeighted: List<Double?>,  // coefficient × utilization (may exceed 1)
    val weeklyWeighted: List<Double?>,
)

@Serializable
data class WindowStatsPayload(
    val buckets: List<String>,                 // time labels, oldest→newest
    val totalFiveHour: List<Double?>,          // Σ raw 5h utilization across accounts (may exceed 1)
    val totalWeekly: List<Double?>,
    val totalFiveHourWeighted: List<Double?>,  // Σ coefficient × 5h utilization across accounts
    val totalWeeklyWeighted: List<Double?>,
    val perAccount: List<WindowSeriesDto>,     // empty if the viewer can't see accounts
    val canViewAccounts: Boolean,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class MessageResponse(val message: String)
