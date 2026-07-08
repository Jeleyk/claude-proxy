package org.claudeproxy.api

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class CreateAccountRequest(
    val name: String,
    val type: String,               // API_KEY | OAUTH | OAUTH_STATIC
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
    val priority: Int? = null,
    val threshold: Double? = null,
    val coefficient: Double? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class OAuthStartResponse(val authorizeUrl: String, val state: String)

@Serializable
data class OAuthCompleteRequest(
    val state: String,
    val code: String,
    val name: String,
    val priority: Int = 100,
    val threshold: Double = 0.9,
    val coefficient: Double = 1.0,
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val roles: List<String> = emptyList(),
)

@Serializable
data class UpdateUserRequest(
    val password: String? = null,
    val enabled: Boolean? = null,
    val roles: List<String>? = null,
)

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
data class OkResponse(val ok: Boolean = true)

@Serializable
data class MessageResponse(val message: String)
