package org.claudeproxy.api

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the private `/internal/` control API consumed by the Go gateway.
 * The gateway calls `/internal/resolve` once per inbound request and `/internal/usage`
 * after each upstream attempt. All selection/crypto/limit logic stays in the service.
 */

@Serializable
data class ResolveRequest(
    val token: String,
    val method: String,
    val path: String,
)

/**
 * One try-list entry: an account already ordered by the pool, carrying the *decrypted*
 * upstream auth headers so the gateway performs no crypto. Reset epochs feed the
 * gateway's mid-stream "retry in Ns" message.
 */
@Serializable
data class CandidateDto(
    val accountId: Int,
    val type: String,
    val deviceId: String? = null,
    val authHeaders: Map<String, String> = emptyMap(),
    val fiveHourResetEpoch: Long? = null,
    val weeklyResetEpoch: Long? = null,
)

@Serializable
data class ResolveResponse(
    val userId: Int?,
    val overLimit: Boolean,
    val dailyLimitUsd: Double? = null,
    val usedUsd: Double? = null,
    val candidates: List<CandidateDto> = emptyList(),
)

/**
 * One upstream attempt's outcome, reported by the gateway. Doubles as the domain input
 * for [org.claudeproxy.datapath.DatapathService.applyOutcome] — no separate mapping type.
 */
@Serializable
data class UsageReport(
    val accountId: Int,
    val userId: Int? = null,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val status: Int,
    val model: String? = null,
    val ratelimitHeaders: Map<String, String> = emptyMap(),
)
