package org.claudeproxy.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.UserInfoRequest
import com.nimbusds.openid.connect.sdk.UserInfoResponse
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import java.net.URI

/** The identity facts the login policy acts on — taken only from a validated ID token (+ userinfo for the same `sub`). */
data class OidcIdentity(
    val issuer: String,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    /** null = the groups claim was absent (distinct from an empty list). */
    val groups: List<String>?,
    val preferredUsername: String?,
)

class OidcException(val code: OidcError, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Fixed, non-reflective error codes: the only thing about a failure that ever reaches the browser. */
enum class OidcError(val code: String) {
    IDP_ERROR("idp_error"),
    INVALID_REQUEST("invalid_request"),
    TOKEN_ERROR("token_error"),
    FORBIDDEN("forbidden"),
    NO_ACCOUNT("no_account"),
    PENDING("pending"),
    UNAVAILABLE("unavailable"),
}

/**
 * Relying-party side of the Authorization Code + PKCE (S256) flow, built entirely on the Nimbus
 * OAuth 2.0 / OIDC SDK: discovery, the token request, and ID-token validation (signature against
 * the provider's JWKS, `iss`, `aud`/`azp`, `exp`/`iat` with bounded skew, `nonce`).
 *
 * Blocking I/O — callers run it on [kotlinx.coroutines.Dispatchers.IO].
 */
class OidcClient(
    private val config: OidcConfig,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Runs a due metadata refresh off the login path. Tests pass a synchronous or capturing executor. */
    private val refreshInBackground: (Runnable) -> Unit = { r -> Thread(r, "oidc-metadata-refresh").apply { isDaemon = true }.start() },
) {
    private val log = org.slf4j.LoggerFactory.getLogger("OidcClient")

    private class Provider(
        val metadata: OIDCProviderMetadata,
        val validator: IDTokenValidator,
        val clientAuth: ClientAuthentication,
        /** Wall-clock ms of the successful discovery this provider came from. */
        val loadedAt: Long,
        /** Wall-clock ms after which discovery is re-fetched. */
        val refreshAt: Long,
    )

    @Volatile private var provider: Provider? = null
    /** After a failed synchronous load: fail fast until then instead of queueing every login behind timeouts. */
    @Volatile private var failedUntil: Long = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Last good metadata may serve until it is this old (hourly refresh + `OIDC_METADATA_MAX_STALE_HOURS`). */
    private fun servable(p: Provider, now: Long) = now - p.loadedAt <= METADATA_TTL_MS + config.metadataMaxStaleSeconds * 1000

    /**
     * Discovery is resolved lazily (an IdP outage must not stop the service from booting) and
     * refreshed hourly. A due refresh runs in the background while logins keep using the current
     * metadata/JWKS source; a failed refresh is retried a minute later (WARN). The last good
     * metadata is served for at most `OIDC_METADATA_MAX_STALE_HOURS` past its refresh time — after
     * that, or before the first successful discovery, a login loads it synchronously and fails with
     * `unavailable` if the IdP is unreachable.
     */
    private fun provider(): Provider {
        val now = clock()
        val current = provider
        if (current != null && now < current.refreshAt) return current
        if (current != null && servable(current, now)) {
            if (refreshing.compareAndSet(false, true)) {
                try {
                    refreshInBackground(Runnable { try { refresh() } finally { refreshing.set(false) } })
                } catch (e: Exception) {
                    refreshing.set(false)
                    log.warn("OIDC metadata refresh could not be scheduled ({})", e.javaClass.simpleName)
                }
            }
            return current
        }
        if (now < failedUntil) throw OidcException(OidcError.UNAVAILABLE, "OIDC discovery failed recently; retrying after a short backoff")
        return refresh()
            ?: throw OidcException(OidcError.UNAVAILABLE, "OIDC discovery failed and no usable metadata is cached")
    }

    /** Loads discovery under the lock. Null when it failed (the reason is logged). */
    private fun refresh(): Provider? = synchronized(this) {
        val current = provider
        if (current != null && clock() < current.refreshAt) return current // someone else just refreshed
        try {
            load().also { provider = it; failedUntil = 0L }
        } catch (e: Exception) {
            val now = clock()
            if (current != null && servable(current, now)) {
                log.warn("OIDC discovery refresh failed ({}); keeping the previous metadata (loaded {} s ago)",
                    e.javaClass.simpleName, (now - current.loadedAt) / 1000)
                provider = Provider(current.metadata, current.validator, current.clientAuth, current.loadedAt, now + RETRY_MS)
            } else {
                log.error("OIDC discovery failed ({}){}", e.javaClass.simpleName,
                    if (current != null) "; cached metadata is older than OIDC_METADATA_MAX_STALE_HOURS and no longer used" else "")
                failedUntil = now + FAILURE_BACKOFF_MS
            }
            null
        }
    }

    private fun load(): Provider {
        // resolve() also checks that the document's `issuer` equals the configured one (OIDC Discovery §4.3).
        val meta = OIDCProviderMetadata.resolve(Issuer(config.issuer), connectTimeoutMs, readTimeoutMs)
        require(meta.jwkSetURI != null) { "provider metadata has no jwks_uri" }
        require(meta.tokenEndpointURI != null) { "provider metadata has no token_endpoint" }
        meta.codeChallengeMethods?.let {
            require(CodeChallengeMethod.S256 in it) { "provider does not advertise PKCE S256" }
        }

        // Asymmetric algorithms only: an ID token "signed" with HS* (keyed by the client secret)
        // or `none` is never accepted. Default per OIDC Discovery is RS256.
        val advertised = meta.idTokenJWSAlgs?.toSet() ?: setOf(JWSAlgorithm.RS256)
        val algs = advertised.filter { it in ASYMMETRIC_ALGS }.toSet()
        require(algs.isNotEmpty()) { "provider advertises no supported asymmetric ID-token algorithm" }

        val jwks: JWKSource<SecurityContext> = JWKSourceBuilder
            .create<SecurityContext>(meta.jwkSetURI.toURL(), com.nimbusds.jose.util.DefaultResourceRetriever(connectTimeoutMs, readTimeoutMs, JWKS_SIZE_LIMIT))
            .retrying(true)
            .build()
        val validator = IDTokenValidator(meta.issuer, ClientID(config.clientId), JWSVerificationKeySelector(algs, jwks), null)
        validator.maxClockSkew = MAX_CLOCK_SKEW_SECONDS

        val methods = meta.tokenEndpointAuthMethods
        val clientId = ClientID(config.clientId)
        val secret = Secret(config.clientSecret)
        // client_secret_basic is the spec default; fall back to _post only when it is all the provider offers.
        val clientAuth = if (methods == null || ClientAuthenticationMethod.CLIENT_SECRET_BASIC in methods) {
            ClientSecretBasic(clientId, secret)
        } else if (ClientAuthenticationMethod.CLIENT_SECRET_POST in methods) {
            ClientSecretPost(clientId, secret)
        } else {
            error("provider supports neither client_secret_basic nor client_secret_post")
        }
        val now = clock()
        return Provider(meta, validator, clientAuth, now, now + METADATA_TTL_MS)
    }

    /** Builds the authorization redirect for a fresh transaction. */
    fun authorizationUri(txn: OidcTransaction): URI {
        val meta = provider().metadata
        return AuthenticationRequest.Builder(
            ResponseType.CODE,
            Scope(*config.scopes.toTypedArray()),
            ClientID(config.clientId),
            config.redirectUri,
        )
            .endpointURI(meta.authorizationEndpointURI)
            .state(State(txn.state))
            .nonce(Nonce(txn.nonce))
            .codeChallenge(CodeVerifier(txn.codeVerifier), CodeChallengeMethod.S256)
            .build()
            .toURI()
    }

    /** Exchanges the code (with the PKCE verifier) and validates the ID token against the transaction's nonce. */
    fun exchange(code: String, txn: OidcTransaction): OidcIdentity {
        val p = provider()
        val tokenRequest = TokenRequest.Builder(
            p.metadata.tokenEndpointURI,
            p.clientAuth,
            AuthorizationCodeGrant(AuthorizationCode(code), config.redirectUri, CodeVerifier(txn.codeVerifier)),
        ).build()
        val response = try {
            val http = tokenRequest.toHTTPRequest()
            http.connectTimeout = connectTimeoutMs
            http.readTimeout = readTimeoutMs
            OIDCTokenResponseParser.parse(http.send())
        } catch (e: Exception) {
            throw OidcException(OidcError.TOKEN_ERROR, "token request failed: ${e.javaClass.simpleName}", e)
        }
        if (!response.indicatesSuccess()) {
            // Only the standard error code is logged — never the request, the code or any token.
            val err = response.toErrorResponse().errorObject?.code
            throw OidcException(OidcError.TOKEN_ERROR, "token endpoint returned error=$err")
        }
        val tokens = (response.toSuccessResponse() as? OIDCTokenResponse)?.oidcTokens
        val idToken = tokens?.idToken
            ?: throw OidcException(OidcError.TOKEN_ERROR, "token response carries no id_token")

        val claims = try {
            p.validator.validate(idToken, Nonce(txn.nonce))
        } catch (e: Exception) {
            throw OidcException(OidcError.TOKEN_ERROR, "ID token rejected: ${e.message}", e)
        }

        // Authorized party (OIDC Core §2, §3.1.3.7): with several audiences azp must be present,
        // and whenever present it must be us. Checked here explicitly rather than relying on the
        // library's defaults.
        val audiences = claims.audience.map { it.value }
        val azp = claims.getStringClaim("azp")
        if (audiences.size > 1 && azp == null) throw OidcException(OidcError.TOKEN_ERROR, "ID token has multiple audiences but no azp")
        if (azp != null && azp != config.clientId) throw OidcException(OidcError.TOKEN_ERROR, "ID token azp is not this client")

        val subject = claims.subject.value
        var all: Map<String, Any?> = claims.toJSONObject()
        // Some providers keep email/groups out of the ID token. Fill only *missing* claims from
        // userinfo, and only if userinfo speaks for the same subject (OIDC Core §5.3.2).
        val needs = listOf("email", "email_verified", config.groupsClaim).any { it !in all }
        val userInfoUri = p.metadata.userInfoEndpointURI
        if (needs && userInfoUri != null && tokens.accessToken is BearerAccessToken) {
            val extra = fetchUserInfo(userInfoUri, tokens.accessToken as BearerAccessToken)
            if (extra != null) {
                if (extra["sub"] != subject) throw OidcException(OidcError.TOKEN_ERROR, "userinfo sub does not match ID token sub")
                all = extra + all // ID-token claims win on conflict
            }
        }

        return OidcIdentity(
            issuer = claims.issuer.value,
            subject = subject,
            email = (all["email"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            emailVerified = when (val v = all["email_verified"]) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true) // a few providers send it as a string
                else -> false
            },
            groups = when (val g = all[config.groupsClaim]) {
                null -> null
                is String -> listOf(g)
                is Collection<*> -> g.filterIsInstance<String>()
                else -> emptyList()
            },
            preferredUsername = (all["preferred_username"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun fetchUserInfo(uri: URI, token: BearerAccessToken): Map<String, Any?>? = try {
        val http = UserInfoRequest(uri, token).toHTTPRequest()
        http.connectTimeout = connectTimeoutMs
        http.readTimeout = readTimeoutMs
        val resp = UserInfoResponse.parse(http.send())
        // A signed/encrypted userinfo (JWT) is not used — the ID token is the authority; plain JSON only.
        if (resp.indicatesSuccess()) resp.toSuccessResponse().userInfo?.toJSONObject() else null
    } catch (e: Exception) {
        throw OidcException(OidcError.TOKEN_ERROR, "userinfo request failed: ${e.javaClass.simpleName}", e)
    }

    companion object {
        private const val METADATA_TTL_MS = 60L * 60 * 1000
        private const val RETRY_MS = 60L * 1000
        private const val FAILURE_BACKOFF_MS = 10L * 1000
        private const val MAX_CLOCK_SKEW_SECONDS = 60
        private const val JWKS_SIZE_LIMIT = 256 * 1024
        private val ASYMMETRIC_ALGS = setOf(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
            JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519,
        )
    }
}
