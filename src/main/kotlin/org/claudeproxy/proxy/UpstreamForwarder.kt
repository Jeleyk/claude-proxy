package org.claudeproxy.proxy

import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.RateLimitHeaders
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.model.AccountType
import org.claudeproxy.repo.UsageRepo
import org.slf4j.LoggerFactory
import java.time.Instant

enum class RetryKind { RATE_LIMITED, LOST_ACCESS, UPSTREAM_ERROR }

/** Outcome of a single upstream attempt. */
sealed interface ForwardResult {
    /** Response was streamed to the client; we are done. */
    object Served : ForwardResult
    /** This account couldn't serve; caller may retry another account. */
    data class Retry(val kind: RetryKind, val until: Instant?) : ForwardResult
}

/**
 * Forwards a buffered client request to Anthropic on behalf of a chosen account,
 * streaming the response back. Swaps client credentials for the account's, records
 * usage, and updates the account's live limit state from response headers.
 */
class UpstreamForwarder(
    private val pool: AccountPool,
    private val upstreamBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger("UpstreamForwarder")
    private val json = Json { ignoreUnknownKeys = true }

    // Hop-by-hop / auth headers we never forward upstream.
    private val stripRequestHeaders = setOf(
        "host", "content-length", "transfer-encoding", "connection",
        "authorization", "x-api-key", "accept-encoding",
    )
    private val stripResponseHeaders = setOf(
        "content-length", "transfer-encoding", "connection", "content-encoding",
    )

    suspend fun forward(
        call: ApplicationCall,
        account: AccountRuntime,
        pathAndQuery: String,
        bodyBytes: ByteArray,
        userId: Int?,
    ): ForwardResult {
        val method = call.request.httpMethod
        val url = "$upstreamBaseUrl$pathAndQuery"

        val statement = Http.client.prepareRequest(url) {
            this.method = method
            // copy through client headers except stripped ones
            call.request.headers.forEach { name, values ->
                if (name.lowercase() !in stripRequestHeaders) {
                    values.forEach { v -> header(name, v) }
                }
            }
            applyAuth(this, account)
            // Anthropic requires this header; inject a default if the client omitted it.
            if (call.request.headers["anthropic-version"] == null) {
                header("anthropic-version", "2023-06-01")
            }
            if (bodyBytes.isNotEmpty()) {
                setBody(object : OutgoingContent.ByteArrayContent() {
                    override val contentType: ContentType? =
                        call.request.headers["Content-Type"]?.let { ContentType.parse(it) }
                            ?: ContentType.Application.Json
                    override val contentLength: Long = bodyBytes.size.toLong()
                    override fun bytes(): ByteArray = bodyBytes
                })
            }
        }

        return statement.execute { response ->
            val headerMap = HashMap<String, String>()
            response.headers.forEach { k, v -> headerMap[k] = v.lastOrNull() ?: "" }

            // Update live limit state from headers.
            val prev = pool.get(account.id)?.limit ?: account.limit
            val newLimit = RateLimitHeaders.parse(headerMap, prev)
            pool.updateLimit(account.id, newLimit)

            val status = response.status
            if (status == HttpStatusCode.TooManyRequests) {
                val until = resetInstantFrom(headerMap)
                    ?: newLimit.windows.values.mapNotNull { it.resetAt }.minOrNull()
                pool.markRateLimited(account.id, until)
                runCatching { response.readRawBytes() }
                UsageRepo.record(account.id, userId, 0, 0, status.value, null)
                return@execute ForwardResult.Retry(RetryKind.RATE_LIMITED, until)
            }
            // Account's credentials were rejected — flag lost access and try another account.
            if (status.value == 401) {
                pool.setHealth(account.id, org.claudeproxy.model.AccountHealth.REFRESH_FAILED)
                runCatching { AccountRepo.updateHealth(account.id, org.claudeproxy.model.AccountHealth.REFRESH_FAILED) }
                runCatching { response.readRawBytes() }
                UsageRepo.record(account.id, userId, 0, 0, status.value, null)
                return@execute ForwardResult.Retry(RetryKind.LOST_ACCESS, null)
            }
            // Transient upstream errors (overloaded/5xx) — try the next account.
            if (status.value in intArrayOf(500, 502, 503, 529)) {
                runCatching { response.readRawBytes() }
                UsageRepo.record(account.id, userId, 0, 0, status.value, null)
                return@execute ForwardResult.Retry(RetryKind.UPSTREAM_ERROR, null)
            }

            // Copy safe response headers to the client.
            response.headers.forEach { name, values ->
                if (name.lowercase() !in stripResponseHeaders) {
                    values.forEach { v -> call.response.headers.append(name, v, safeOnly = false) }
                }
            }

            val contentType = response.headers["Content-Type"]?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            val isEventStream = contentType?.match(ContentType.Text.EventStream) == true

            if (isEventStream) {
                // Stream SSE through to the client while teeing token usage out of the stream.
                val model = modelFromRequest(bodyBytes)
                val scanner = SseUsageScanner()
                val src = response.bodyAsChannel()
                // A client that disconnects mid-stream closes the write channel; that's normal,
                // not an error. Swallow it and still record whatever usage we scanned.
                try {
                    call.respondBytesWriter(status = status, contentType = contentType) {
                        while (!src.isClosedForRead) {
                            val packet = src.readRemaining(16 * 1024L)
                            while (!packet.exhausted()) {
                                val bytes = packet.readByteArray()
                                if (bytes.isNotEmpty()) {
                                    scanner.feed(bytes, 0, bytes.size)
                                    writeFully(bytes)
                                }
                            }
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log.debug("client disconnected mid-stream for account {}: {}", account.id, e.message)
                }
                UsageRepo.record(account.id, userId, scanner.totalInput(), scanner.output, status.value, model)
            } else {
                // Buffer JSON (single message) so we can extract token usage.
                val bytes = response.readRawBytes()
                recordUsageFromJson(account.id, userId, status.value, bytes)
                call.respondBytes(bytes = bytes, contentType = contentType, status = status)
            }
            ForwardResult.Served
        }
    }

    private fun applyAuth(builder: io.ktor.client.request.HttpRequestBuilder, account: AccountRuntime) {
        org.claudeproxy.accounts.UpstreamAuth.apply(builder, account.type, account.secret, account.clientId)
    }

    private fun resetInstantFrom(headers: Map<String, String>): Instant? {
        val retryAfter = headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value
        retryAfter?.trim()?.toLongOrNull()?.let { return Instant.now().plusSeconds(it) }
        return null
    }

    private fun modelFromRequest(bodyBytes: ByteArray): String? = try {
        if (bodyBytes.isEmpty()) null
        else (json.parseToJsonElement(bodyBytes.decodeToString()) as? JsonObject)
            ?.get("model")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private fun recordUsageFromJson(accountId: Int, userId: Int?, status: Int, bytes: ByteArray) {
        var input = 0L
        var output = 0L
        var model: String? = null
        try {
            val obj = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            model = obj?.get("model")?.jsonPrimitive?.contentOrNull
            val usage = obj?.get("usage")?.jsonObject
            input = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            output = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        } catch (_: Exception) {
            // non-JSON error body; still record the event
        }
        UsageRepo.record(accountId, userId, input, output, status, model)
    }
}
