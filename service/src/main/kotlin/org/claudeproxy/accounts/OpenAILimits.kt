package org.claudeproxy.accounts

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import org.claudeproxy.envOrProp
import org.claudeproxy.model.*
import org.claudeproxy.oauth.OpenAIHttp
import org.claudeproxy.oauth.OpenAIHttpException
import java.time.Instant

/** Codex subscription quotas. API keys have no equivalent subscription window endpoint. */
object OpenAILimits {
    suspend fun probe(account: AccountRuntime): LimitState? {
        if (account.type == AccountType.API_KEY) return null
        val token = account.secret.accessToken ?: return null
        val accountId = account.accountUuid ?: return null
        val url = (envOrProp("OPENAI_CHATGPT_BASE_URL") ?: "https://chatgpt.com/backend-api").trimEnd('/') + "/wham/usage"
        val resp = OpenAIHttp.client.get(url) {
            header("Authorization", "Bearer $token")
            header("ChatGPT-Account-Id", accountId)
        }
        val body = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw OpenAIHttpException(resp.status.value)
        return parseUsage(body, account.limit)
    }

    internal fun parseUsage(body: String, previous: LimitState, now: Instant = Instant.now()): LimitState? {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val limits = obj["rate_limit"] as? JsonObject ?: return null
        val windows = previous.windows.toMutableMap()
        var observed = false
        for (name in listOf("primary_window", "secondary_window")) {
            val w = limits[name] as? JsonObject ?: continue
            val kind = kind((w["limit_window_seconds"] as? JsonPrimitive)?.longOrNull) ?: continue
            val used = (w["used_percent"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: continue
            val reset = epoch((w["reset_at"] as? JsonPrimitive)?.longOrNull)
                ?: (w["reset_after_seconds"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                    ?.let { runCatching { now.plusSeconds(it) }.getOrNull() }
            windows[kind] = WindowLimit(utilization = used / 100, resetAt = reset,
                status = if (used >= 100) LimitStatus.REJECTED else LimitStatus.ALLOWED, updatedAt = now)
            observed = true
        }
        if (!observed) return null // Unknown shapes must not reset previously observed quotas.
        val allowed = (limits["allowed"] as? JsonPrimitive)?.booleanOrNull
        val reached = (limits["limit_reached"] as? JsonPrimitive)?.booleanOrNull == true
        val blockedUntil = if (allowed == false || reached) windows.values.filter { (it.utilization ?: 0.0) >= 1 }
            .mapNotNull { it.resetAt?.takeIf { r -> r.isAfter(now) } }.maxOrNull() ?: now.plusSeconds(60)
            else if (allowed == true) null else previous.rateLimitedUntil
        return previous.copy(windows = windows, updatedAt = now, rateLimitedUntil = blockedUntil)
    }

    fun parseHeaders(headers: Map<String, String>, previous: LimitState, now: Instant = Instant.now()): LimitState {
        val h = headers.mapKeys { it.key.lowercase() }
        val windows = previous.windows.toMutableMap()
        var observed = false
        for (prefix in listOf("x-codex-primary", "x-codex-secondary")) {
            val minutes = h["$prefix-window-minutes"]?.toLongOrNull()
            val k = when (minutes) { 300L -> WindowKind.FIVE_HOUR; 10080L -> WindowKind.WEEKLY; else -> continue }
            val used = h["$prefix-used-percent"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: continue
            val reset = epoch(h["$prefix-reset-at"]?.toLongOrNull())
            windows[k] = WindowLimit(utilization = used / 100, resetAt = reset,
                status = if (used >= 100) LimitStatus.REJECTED else LimitStatus.ALLOWED, updatedAt = now)
            observed = true
        }
        return if (observed) previous.copy(windows = windows, updatedAt = now) else previous
    }
    private fun epoch(seconds: Long?): Instant? = seconds?.takeIf { it > 0 }
        ?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
    private fun kind(seconds: Long?): WindowKind? = when (seconds) {
        18000L -> WindowKind.FIVE_HOUR
        604800L -> WindowKind.WEEKLY
        else -> null
    }
}
