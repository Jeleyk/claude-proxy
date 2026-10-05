package org.claudeproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.claudeproxy.envOrProp
import java.time.Instant
import java.util.Base64

/** Never include response bodies in exceptions: OAuth errors can echo credentials. */
class OpenAIHttpException(val status: Int) : RuntimeException("OpenAI request failed (HTTP $status)")

object OpenAIHttp {
    val client = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }
}

/** Codex device authorization; protocol follows openai/codex's login implementation. */
object OpenAIOAuth {
    val issuer: String get() = (envOrProp("OPENAI_OAUTH_ISSUER") ?: "https://auth.openai.com").trimEnd('/')
    val clientId: String get() = envOrProp("OPENAI_OAUTH_CLIENT_ID") ?: "app_EMoamEEZ73f0CkXaXp7hrann"
    data class DeviceCode(val deviceAuthId: String, val userCode: String, val intervalSeconds: Int, val verificationUri: String)
    data class TokenResult(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long?, val accountId: String?)

    suspend fun start(client: HttpClient = OpenAIHttp.client): DeviceCode {
        val body = post(client, "$issuer/api/accounts/deviceauth/usercode", buildJsonObject { put("client_id", clientId) })
        return DeviceCode(required(body, "device_auth_id"),
            body["user_code"]?.jsonPrimitive?.contentOrNull ?: required(body, "usercode"),
            (body["interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5).coerceIn(5, 60), "$issuer/codex/device")
    }

    /** One poll only. 403/404 are the pending statuses of the Codex device-code protocol. */
    suspend fun poll(code: DeviceCode, client: HttpClient = OpenAIHttp.client): TokenResult? {
        val resp = client.post("$issuer/api/accounts/deviceauth/token") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("device_auth_id", code.deviceAuthId); put("user_code", code.userCode) }.toString())
        }
        val text = resp.bodyAsText()
        if (resp.status.value in setOf(403, 404, 429)) return null
        if (resp.status.value !in 200..299) throw OpenAIHttpException(resp.status.value)
        val body = parse(text)
        val exchange = client.post("$issuer/oauth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(Parameters.build {
                append("grant_type", "authorization_code"); append("client_id", clientId)
                append("code", required(body, "authorization_code"))
                append("code_verifier", required(body, "code_verifier"))
                append("redirect_uri", "$issuer/deviceauth/callback")
            }.formUrlEncode())
        }
        val result = exchange.bodyAsText()
        if (exchange.status.value !in 200..299) throw OpenAIHttpException(exchange.status.value)
        return tokens(parse(result)).also { require(it.accountId != null) { "OpenAI did not return an account identity" } }
    }

    suspend fun refresh(client: HttpClient, refreshToken: String): TokenResult {
        // Callers historically share a redirect-following Anthropic client. Do not expose OpenAI
        // refresh credentials through redirects or inherit an unbounded inference timeout.
        return client.config {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
        }.use { safe -> tokens(post(safe, "$issuer/oauth/token", buildJsonObject {
            put("grant_type", "refresh_token"); put("client_id", clientId); put("refresh_token", refreshToken)
        })) }
    }

    internal fun tokens(body: JsonObject): TokenResult {
        val access = required(body, "access_token")
        val accessClaims = claims(access)
        val idClaims = body["id_token"]?.jsonPrimitive?.contentOrNull?.let(::claims)
        // Claims are metadata from the TLS-authenticated token endpoint, not local authorization.
        val account = sequenceOf(idClaims, accessClaims).filterNotNull().mapNotNull {
            (it["https://api.openai.com/auth"] as? JsonObject)?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
        }.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        val expires = body["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { Instant.now().plusSeconds(it).toEpochMilli() }
            ?: accessClaims?.get("exp")?.jsonPrimitive?.longOrNull?.let { it * 1000 }
        return TokenResult(access, body["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank), expires, account)
    }
    private fun claims(token: String): JsonObject? = runCatching {
        val encoded = token.split('.')[1]
        parse(String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
    }.getOrNull()
    private suspend fun post(client: HttpClient, url: String, body: JsonObject): JsonObject {
        val resp = client.post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw OpenAIHttpException(resp.status.value)
        return parse(text)
    }
    private fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw IllegalStateException("Invalid OpenAI response") }
    private fun required(body: JsonObject, key: String): String = body[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("Incomplete OpenAI response")
}
