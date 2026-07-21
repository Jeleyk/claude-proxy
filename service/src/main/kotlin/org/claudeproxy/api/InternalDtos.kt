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
    // datapath: "proxy" (Claude Code, default) or "routing" (OpenAI/Anthropic gateways).
    val source: String = "proxy",
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
    // id of the inbound token (namespace by datapath: proxy_tokens / routing_tokens);
    // the gateway echoes it back in each UsageReport so usage rows attribute per token.
    val tokenId: Int? = null,
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
    // inbound token that authenticated the request, from the resolve response.
    val tokenId: Int? = null,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val status: Int,
    val model: String? = null,
    val ratelimitHeaders: Map<String, String> = emptyMap(),
    // datapath that produced this attempt: "proxy" (default) or "routing".
    val source: String = "proxy",
    // MCP tool invocations seen in the response (tool_use blocks named "mcp__…"), by tool name.
    val mcpCalls: Map<String, Long> = emptyMap(),
)
