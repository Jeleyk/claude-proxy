package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.auth.requireSameOriginWrite
import org.claudeproxy.model.Permission
import org.claudeproxy.repo.IdentityRepo
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

@Serializable
data class OffboardResponse(val userId: Int, val proxyTokensRevoked: Int, val routingTokensRevoked: Int)

@Serializable
data class UnlinkResponse(val userId: Int, val identitiesUnlinked: Int)

private val log = LoggerFactory.getLogger("UserLifecycle")

/**
 * Offboarding for admins and automation driven by a USERS_MANAGE session (any USERS_MANAGE holder
 * can offboard any other user, admins included — the same reach as the existing user edit). Both
 * are body-less, so they require `X-Requested-With` and a same-origin `Origin` (CSRF):
 *  - `POST /api/users/{id}/offboard` — disable + end all panel sessions + delete every cxp_/cxr_
 *    token, effective on the next request on every datapath (resolve caches are evicted).
 *  - `DELETE /api/users/{id}/identities` — drop the user's SSO links (e.g. a wrong email link),
 *    so the next SSO login goes through the linking rules again.
 */
fun Route.userLifecycleRoutes(trustedHosts: Set<String> = emptySet()) {
    post("/users/{id}/offboard") {
        val me = call.requirePermission(Permission.USERS_MANAGE)
        call.requireSameOriginWrite(trustedHosts)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (id == me.id) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("cannot offboard yourself"))
        val r = UserRepo.offboard(id) ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        log.info("offboarded user={} by={} proxyTokens={} routingTokens={}", id, me.id, r.proxyTokensRevoked, r.routingTokensRevoked)
        call.respond(OffboardResponse(id, r.proxyTokensRevoked, r.routingTokensRevoked))
    }
    delete("/users/{id}/identities") {
        val me = call.requirePermission(Permission.USERS_MANAGE)
        call.requireSameOriginWrite(trustedHosts)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (UserRepo.get(id) == null) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        val n = transaction { IdentityRepo.unlinkAll(id) }
        log.info("unlinked SSO identities user={} by={} count={}", id, me.id, n)
        call.respond(UnlinkResponse(id, n))
    }
}
