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

fun Application.installSecurity(sessionSecret: String) {
    install(Sessions) {
        cookie<UserSession>("CLAUDE_PROXY_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = SESSION_TTL_SECONDS
            transform(SessionTransportTransformerMessageAuthentication(sessionSecret.toByteArray()))
        }
    }
}

fun ApplicationCall.setUserSession(user: UserAuth) = sessions.set(
    UserSession(user.id, user.sessionVersion, Instant.now().epochSecond + SESSION_TTL_SECONDS),
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
