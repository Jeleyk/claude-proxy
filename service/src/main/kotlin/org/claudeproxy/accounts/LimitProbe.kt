package org.claudeproxy.accounts

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.claudeproxy.envOrProp
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.proxy.Http
import org.slf4j.LoggerFactory

/**
 * Actively refreshes an account's live limit state by making a minimal upstream request
 * and reading its rate-limit headers.
 *
 * For subscription (OAuth) accounts the request must look like Claude Code — same auth,
 * beta flag and a Claude Code system prompt — otherwise Anthropic rejects it (401/403).
 * We therefore send a 1-token `/v1/messages` call framed like Claude Code. It costs a
 * negligible amount of quota but works for both OAuth and API-key accounts. A dedicated
 * limits endpoint can be substituted via `LIMIT_PROBE_PATH` if one becomes known.
 */
class LimitProbe(
    private val pool: AccountPool,
    private val upstreamBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger("LimitProbe")

    private val probePath: String get() = envOrProp("LIMIT_PROBE_PATH") ?: "/v1/messages"
    private val probeModel: String get() = envOrProp("LIMIT_PROBE_MODEL") ?: "claude-haiku-4-5-20251001"
    private val systemPrompt = "You are Claude Code, Anthropic's official CLI for Claude."
    private val probeBody: String
        get() = """{"model":"$probeModel","max_tokens":1,"system":"$systemPrompt","messages":[{"role":"user","content":"."}]}"""

    /** Probe a single account. Returns true if any rate-limit header was observed. */
    suspend fun probe(accountId: Int): Boolean {
        val account = pool.get(accountId) ?: return false
        return try {
            val resp: HttpResponse = Http.client.post("$upstreamBaseUrl$probePath") {
                contentType(ContentType.Application.Json)
                header("anthropic-version", "2023-06-01")
                UpstreamAuth.apply(this, account.type, account.secret, account.clientId)
                setBody(probeBody)
            }
            val headerMap = HashMap<String, String>()
            resp.headers.forEach { k, v -> headerMap[k] = v.lastOrNull() ?: "" }
            runCatching { resp.readRawBytes() } // drain

            val prev = pool.get(accountId)?.limit ?: account.limit
            val newLimit = RateLimitHeaders.parse(headerMap, prev)
            val observed = headerMap.keys.any { it.lowercase().startsWith("anthropic-ratelimit") }
            if (newLimit !== prev) pool.updateLimit(accountId, newLimit)
            when (resp.status.value) {
                429 -> pool.markRateLimited(accountId, newLimit.windows.values.mapNotNull { it.resetAt }.minOrNull())
                401, 403 -> {
                    // Token no longer accepted. Try to recover OAuth accounts; otherwise flag lost access.
                    log.warn("Probe for account {} returned {} (lost access)", accountId, resp.status.value)
                    AccountRepo.updateHealth(accountId, AccountHealth.REFRESH_FAILED)
                    pool.setHealth(accountId, AccountHealth.REFRESH_FAILED)
                }
                in 200..299 -> {
                    if (account.health != AccountHealth.OK) {
                        AccountRepo.updateHealth(accountId, AccountHealth.OK)
                        pool.setHealth(accountId, AccountHealth.OK)
                    }
                }
            }
            log.info("Probed account {} -> status {} (rate-limit headers: {})", accountId, resp.status.value, observed)
            observed
        } catch (e: Exception) {
            log.warn("Probe failed for account {}: {}", accountId, e.message)
            false
        }
    }

    /** Probe every account. Returns the number that reported rate-limit headers. */
    suspend fun probeAll(): Int {
        var n = 0
        for (acc in pool.snapshot()) {
            if (probe(acc.id)) n++
        }
        return n
    }
}
