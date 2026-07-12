package org.claudeproxy.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.claudeproxy.envOrProp
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Replicates the Claude Code "Login with Claude" OAuth (PKCE) flow used to obtain
 * subscription access/refresh tokens, plus refresh-token exchange.
 *
 * The client_id / endpoints below are the well-known public Claude Code values and can
 * be overridden via env (OAUTH_CLIENT_ID, OAUTH_AUTHORIZE_URL, OAUTH_TOKEN_URL,
 * OAUTH_REDIRECT_URI, OAUTH_SCOPES) once confirmed against the live flow.
 */
object ClaudeOAuth {
    val clientId: String get() = envOrProp("OAUTH_CLIENT_ID") ?: "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    val authorizeUrl: String get() = envOrProp("OAUTH_AUTHORIZE_URL") ?: "https://claude.ai/oauth/authorize"
    val tokenUrl: String get() = envOrProp("OAUTH_TOKEN_URL") ?: "https://console.anthropic.com/v1/oauth/token"
    val redirectUri: String get() = envOrProp("OAUTH_REDIRECT_URI") ?: "https://console.anthropic.com/oauth/code/callback"
    val scopes: String get() = envOrProp("OAUTH_SCOPES") ?: "org:create_api_key user:profile user:inference"

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    data class Pkce(val verifier: String, val challenge: String)
    data class TokenResult(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long?)

    fun newPkce(): Pkce {
        val verifierBytes = ByteArray(32).also { random.nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return Pkce(verifier, challenge)
    }

    fun randomState(): String {
        val b = ByteArray(24).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun buildAuthorizeUrl(challenge: String, state: String): String {
        val params = listOf(
            "code" to "true",
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to scopes,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        ).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return "$authorizeUrl?$params"
    }

    /** The pasted code may arrive as "code#state"; split defensively. */
    fun splitCode(input: String): Pair<String, String?> {
        val trimmed = input.trim()
        val hash = trimmed.indexOf('#')
        return if (hash >= 0) trimmed.substring(0, hash) to trimmed.substring(hash + 1)
        else trimmed to null
    }

    suspend fun exchangeCode(client: HttpClient, code: String, verifier: String, state: String?): TokenResult {
        val body = buildJsonObject {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", redirectUri)
            put("client_id", clientId)
            put("code_verifier", verifier)
            if (state != null) put("state", state)
        }
        return postToken(client, body)
    }

    suspend fun refresh(client: HttpClient, refreshToken: String): TokenResult {
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", clientId)
        }
        return postToken(client, body)
    }

    private suspend fun postToken(client: HttpClient, body: JsonObject): TokenResult {
        val resp: HttpResponse = client.post(tokenUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(body.toString())
        }
        val text = resp.bodyAsText()
        if (resp.status != HttpStatusCode.OK) {
            throw OAuthException("token endpoint ${resp.status}: ${text.take(300)}")
        }
        val obj = json.parseToJsonElement(text) as JsonObject
        val access = obj["access_token"]?.jsonPrimitive?.content
            ?: throw OAuthException("no access_token in response")
        val refresh = obj["refresh_token"]?.jsonPrimitive?.content
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
        val expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000 }
        return TokenResult(access, refresh, expiresAt)
    }
}

class OAuthException(message: String) : RuntimeException(message)
