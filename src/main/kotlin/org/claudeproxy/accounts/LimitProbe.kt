package org.claudeproxy.accounts

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.claudeproxy.envOrProp
import org.claudeproxy.proxy.Http
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Actively refreshes an account's live limit state by making a lightweight upstream
 * request and reading its rate-limit headers. Uses the token-counting endpoint by
 * default, which does not consume message quota. Best-effort: failures are logged and
 * ignored (passive tracking from real traffic remains the primary source).
 */
class LimitProbe(
    private val pool: AccountPool,
    private val upstreamBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger("LimitProbe")

    private val probePath: String get() = envOrProp("LIMIT_PROBE_PATH") ?: "/v1/messages/count_tokens"
    private val probeModel: String get() = envOrProp("LIMIT_PROBE_MODEL") ?: "claude-3-5-haiku-latest"
    private val probeBody: String
        get() = """{"model":"$probeModel","messages":[{"role":"user","content":"ping"}]}"""

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
            if (resp.status.value == 429) pool.markRateLimited(accountId, newLimit.windows.values.mapNotNull { it.resetAt }.minOrNull())
            log.info("Probed account {} -> status {} (headers observed: {})", accountId, resp.status.value, observed)
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
