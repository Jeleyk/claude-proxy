package org.claudeproxy.api

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.claudeproxy.auth.OidcClient
import org.claudeproxy.auth.OidcConfig
import org.claudeproxy.auth.OidcError
import org.claudeproxy.auth.OidcException
import org.claudeproxy.auth.OidcLogin
import org.claudeproxy.auth.OidcLoginResult
import org.claudeproxy.auth.OidcTransactions
import org.claudeproxy.auth.setUserSession
import org.slf4j.LoggerFactory

/** Public login-page hints. Never carries anything secret. */
@Serializable
data class OidcPublicConfig(
    val enabled: Boolean,
    val passwordLogin: Boolean,
    val buttonLabel: String? = null,
    val startUrl: String? = null,
)

private val log = LoggerFactory.getLogger("OidcRoutes")
private const val TXN_COOKIE = "CLAUDE_PROXY_OIDC"
private const val TXN_COOKIE_PATH = "/api/auth/oidc"

/**
 * `/api/auth/oidc/{config,start,callback}`. With OIDC off only `config` exists (answering
 * `enabled=false`), so the login page needs no build-time switch.
 *
 * The callback never echoes IdP or library error text into the URL: the browser is sent to
 * `/?sso_error=<fixed code>` and the detail goes to the server log (without codes or tokens).
 */
fun Route.oidcRoutes(
    config: OidcConfig?,
    client: OidcClient? = config?.let { OidcClient(it) },
    transactions: OidcTransactions = OidcTransactions(),
    login: OidcLogin? = config?.let { OidcLogin(it) },
) {
    get("/auth/oidc/config") {
        call.respond(
            if (config == null) OidcPublicConfig(enabled = false, passwordLogin = true)
            else OidcPublicConfig(true, !config.disablePasswordLogin, config.buttonLabel, config.startUrl),
        )
    }
    if (config == null || client == null || login == null) return

    get("/auth/oidc/start") {
        call.noStore()
        val (id, txn) = transactions.begin()
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, MessageResponse("Too many pending sign-ins"))
        val authorize = try {
            withContext(Dispatchers.IO) { client.authorizationUri(txn) }
        } catch (e: OidcException) {
            transactions.discard(id) // never sent to the browser; don't let an outage fill the store
            log.warn("OIDC start failed: {}", e.message)
            return@get call.respondRedirect(errorTarget(e.code))
        }
        call.response.cookies.append(txnCookie(config, id, OidcTransactions.TTL_SECONDS))
        call.respondRedirect(authorize.toString())
    }

    get("/auth/oidc/callback") {
        call.noStore()
        // The transaction is single use whatever happens next.
        val txnId = call.request.cookies[TXN_COOKIE, CookieEncoding.RAW]
        call.response.cookies.append(txnCookie(config, "", 0))
        val q = call.request.queryParameters
        val txn = transactions.take(txnId, q["state"])

        q["error"]?.let { err ->
            log.info("OIDC callback: provider returned error={}", err.filter { it.isLetterOrDigit() || it == '_' }.take(64))
            return@get call.respondRedirect(errorTarget(OidcError.IDP_ERROR))
        }
        if (txn == null) {
            log.info("OIDC callback: missing, expired, reused or mismatched state")
            return@get call.respondRedirect(errorTarget(OidcError.INVALID_REQUEST))
        }
        val code = q["code"]?.takeIf { it.isNotEmpty() }
            ?: return@get call.respondRedirect(errorTarget(OidcError.INVALID_REQUEST))

        val identity = try {
            withContext(Dispatchers.IO) { client.exchange(code, txn) }
        } catch (e: OidcException) {
            log.warn("OIDC callback: {}", e.message)
            return@get call.respondRedirect(errorTarget(e.code))
        }

        when (val result = withContext(Dispatchers.IO) { login.resolve(identity) }) {
            is OidcLoginResult.Denied -> {
                log.info("OIDC login denied: sub={} reason={}", identity.subject.logSafe(), result.reason)
                call.respondRedirect(errorTarget(result.error))
            }
            is OidcLoginResult.Success -> {
                log.info("OIDC login: user={} sub={} via={}", result.user.id, identity.subject.logSafe(), result.how)
                call.setUserSession(result.user, config.sessionTtlSeconds)
                // Fixed, same-origin target: no return-to parameter, so no open redirect.
                call.respondRedirect("/")
            }
        }
    }
}

private fun errorTarget(e: OidcError) = "/?sso_error=${e.code}"

private fun String.logSafe() = filterNot { it.isISOControl() }.take(128)

private fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Referrer-Policy", "no-referrer")
}

/**
 * SameSite=Lax, not Strict: the callback is a top-level cross-site navigation from the IdP, and a
 * Strict cookie would not come back on it. Lax still withholds it from cross-site subrequests and
 * POSTs. Scoped to the OIDC path, HttpOnly, short-lived, `Secure` whenever the callback is HTTPS.
 */
private fun txnCookie(config: OidcConfig, value: String, maxAge: Long) = Cookie(
    name = TXN_COOKIE,
    value = value,
    encoding = CookieEncoding.RAW,
    maxAge = maxAge.toInt(),
    expires = if (maxAge == 0L) io.ktor.util.date.GMTDate.START else null,
    path = TXN_COOKIE_PATH,
    secure = config.secureCookies,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
