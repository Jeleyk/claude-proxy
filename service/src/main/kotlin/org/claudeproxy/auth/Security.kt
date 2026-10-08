package org.claudeproxy.auth

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.sessions.clear
import kotlinx.serialization.Serializable
import java.time.Instant
import org.claudeproxy.model.Permission
import org.claudeproxy.repo.UserAuth
import org.claudeproxy.repo.UserRepo

@Serializable
data class UserSession(val userId: Int, val sessionVersion: Long = -1L, val expiresAt: Long = 0L)

internal const val SESSION_TTL_SECONDS = 60L * 60 * 24 * 30

/**
 * SameSite policy of the session cookie. Lax (default) keeps the cookie off cross-site POSTs and
 * subrequests while still allowing it on top-level navigation — which the OIDC callback's redirect
 * to `/` needs. Strict is stricter still and works too (the SPA's own API calls are same-site).
 * `None` is deliberately not offered.
 */
enum class SameSitePolicy(val attr: String) {
    LAX("Lax"), STRICT("Strict");

    companion object {
        fun parse(raw: String?, onInvalid: (String) -> Unit = {}): SameSitePolicy = when (raw?.trim()?.lowercase()) {
            null, "", "lax" -> LAX
            "strict" -> STRICT
            else -> LAX.also { onInvalid("SESSION_COOKIE_SAMESITE=$raw is not lax|strict; using Lax") }
        }
    }
}

fun Application.installSecurity(sessionSecret: String, sameSite: SameSitePolicy = SameSitePolicy.LAX) {
    install(Sessions) {
        cookie<UserSession>("CLAUDE_PROXY_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.extensions["SameSite"] = sameSite.attr
            cookie.maxAgeInSeconds = SESSION_TTL_SECONDS
            transform(SessionTransportTransformerMessageAuthentication(sessionSecret.toByteArray()))
        }
    }
}

/**
 * [ttlSeconds] is the server-enforced lifetime (signed into the cookie). SSO logins pass a shorter
 * one, so an IdP-side removal also ends an open panel session within that bound.
 */
fun ApplicationCall.setUserSession(user: UserAuth, ttlSeconds: Long = SESSION_TTL_SECONDS) = sessions.set(
    UserSession(user.id, user.sessionVersion, Instant.now().epochSecond + ttlSeconds.coerceIn(1L, SESSION_TTL_SECONDS)),
)
fun ApplicationCall.clearUserSession() {
    currentUser()?.let { UserRepo.revokeSessions(it.id, it.sessionVersion) }
    sessions.clear<UserSession>()
}
fun ApplicationCall.userSession(): UserSession? = sessions.get<UserSession>()

/** Loads the currently authenticated user (with permissions), or null. */
fun ApplicationCall.currentUser(): UserAuth? {
    val session = userSession() ?: return null
    if (session.expiresAt <= Instant.now().epochSecond) return null
    return UserRepo.findAuth(session.userId)?.takeIf {
        it.enabled && it.sessionVersion == session.sessionVersion
    }
}

/**
 * CSRF guard for cookie-authenticated, body-less state changes (offboard, unlink). JSON endpoints
 * are already covered by the content type (a cross-site form cannot send application/json without
 * a CORS preflight); these have no body, so they demand:
 *  - `X-Requested-With` — a custom header, which a cross-site page can only send after a CORS
 *    preflight that the service's CORS policy refuses for foreign origins; and
 *  - when the browser sends `Origin`, its host must be the request's own host (port ignored: the
 *    router may drop it from `Host`) or one of [trustedHosts].
 */
fun ApplicationCall.requireSameOriginWrite(trustedHosts: Set<String> = emptySet()) {
    if (request.headers["X-Requested-With"].isNullOrBlank()) throw ForbiddenException("Missing X-Requested-With header")
    val origin = request.headers["Origin"] ?: return
    val originHost = runCatching { java.net.URI(origin).host?.lowercase() }.getOrNull()
        ?: throw ForbiddenException("Cross-origin request refused")
    // The raw Host header (what nginx forwards), not X-Forwarded-*-derived values.
    val ownHost = (request.headers["Host"]?.substringBefore(':') ?: request.local.serverHost).lowercase()
    if (originHost != ownHost && originHost !in trustedHosts) throw ForbiddenException("Cross-origin request refused")
}

class UnauthorizedException(message: String) : RuntimeException(message)
class ForbiddenException(message: String) : RuntimeException(message)

/** Require an authenticated user; throws [UnauthorizedException] otherwise. */
fun ApplicationCall.requireUser(): UserAuth =
    currentUser() ?: throw UnauthorizedException("Not authenticated")

/** Require an authenticated user holding [permission]; throws on failure. */
fun ApplicationCall.requirePermission(permission: Permission): UserAuth {
    val user = requireUser()
    if (permission !in user.permissions) throw ForbiddenException("Missing permission ${permission.name}")
    return user
}
