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
import org.claudeproxy.model.Permission
import org.claudeproxy.repo.UserAuth
import org.claudeproxy.repo.UserRepo

@Serializable
data class UserSession(val userId: Int)

fun Application.installSecurity(sessionSecret: String) {
    install(Sessions) {
        cookie<UserSession>("CLAUDE_PROXY_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 60L * 60 * 24 * 30
            transform(SessionTransportTransformerMessageAuthentication(sessionSecret.toByteArray()))
        }
    }
}

fun ApplicationCall.setUserSession(userId: Int) = sessions.set(UserSession(userId))
fun ApplicationCall.clearUserSession() = sessions.clear<UserSession>()
fun ApplicationCall.userSession(): UserSession? = sessions.get<UserSession>()

/** Loads the currently authenticated user (with permissions), or null. */
fun ApplicationCall.currentUser(): UserAuth? {
    val uid = userSession()?.userId ?: return null
    return UserRepo.findAuth(uid)?.takeIf { it.enabled }
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
