package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.datapath.ResolveError

/**
 * The private control API consumed by the Go gateway. Never routed publicly by nginx
 * (only `/api`, `/healthz`, `/gateway`, `/v1` are). Every endpoint is gated by a shared
 * secret in the `X-Internal-Token` header; a missing/wrong token (or an unset server-side
 * token) is `401`. All selection/crypto/limit logic stays in [DatapathService].
 */
fun Route.internalRoutes(datapath: DatapathService, internalToken: String?) {
    // True only when a server-side token is configured and the request presents it exactly.
    fun io.ktor.server.application.ApplicationCall.authorized(): Boolean {
        if (internalToken.isNullOrBlank()) return false
        return request.headers["X-Internal-Token"] == internalToken
    }
    route("/internal") {
        post("/resolve") {
            if (!call.authorized()) return@post call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<ResolveRequest>()
            val r = datapath.resolve(req.token, req.method, req.path, req.source)
            when (r.error) {
                ResolveError.BAD_TOKEN -> call.respond(HttpStatusCode.Unauthorized)
                ResolveError.NO_PERMISSION -> call.respond(HttpStatusCode.Forbidden)
                null -> call.respond(
                    ResolveResponse(
                        userId = r.userId,
                        tokenId = r.tokenId,
                        overLimit = r.overLimit,
                        dailyLimitUsd = r.dailyLimitUsd,
                        usedUsd = r.usedUsd,
                        candidates = r.candidates,
                    ),
                )
            }
        }
        post("/usage") {
            if (!call.authorized()) return@post call.respond(HttpStatusCode.Unauthorized)
            val report = call.receive<UsageReport>()
            datapath.applyOutcome(report)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

