package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.accounts.LimitProbe
import org.claudeproxy.repo.Totals
import org.claudeproxy.auth.clearUserSession
import org.claudeproxy.auth.currentUser
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.auth.requireUser
import org.claudeproxy.auth.setUserSession
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.Permission
import org.claudeproxy.model.PoolStatsDto
import org.claudeproxy.model.UserDto
import org.claudeproxy.model.WindowKind
import org.claudeproxy.oauth.ClaudeOAuth
import org.claudeproxy.proxy.Http
import org.claudeproxy.repo.GroupRepo
import org.claudeproxy.repo.ModelCoeffRepo
import org.claudeproxy.repo.OAuthAddRepo
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoleRepo
import org.claudeproxy.repo.SettingsRepo
import org.claudeproxy.repo.UsageRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant

fun Route.adminRoutes(pool: AccountPool, probe: LimitProbe, publicBaseUrl: String) {
    route("/api") {
        get("/config") {
            call.requireUser()
            call.respond(ConfigDto(publicBaseUrl, SettingsRepo.tokensPerWindowPercent()))
        }
        patch("/settings") {
            call.requirePermission(Permission.ADMIN)
            val req = call.receive<UpdateSettingsRequest>()
            req.tokensPerWindowPercent?.let { if (it > 0) SettingsRepo.setTokensPerWindowPercent(it) }
            call.respond(ConfigDto(publicBaseUrl, SettingsRepo.tokensPerWindowPercent()))
        }
        // model dirty-token coefficients (admin-managed)
        get("/model-coeffs") {
            call.requireUser()
            call.respond(ModelCoeffRepo.list())
        }
        post("/model-coeffs") {
            call.requirePermission(Permission.ADMIN)
            val req = call.receive<ModelCoeffRequest>()
            if (req.pattern.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("pattern required"))
            ModelCoeffRepo.set(req.pattern, req.coefficient)
            call.respond(ModelCoeffRepo.list())
        }
        delete("/model-coeffs/{pattern}") {
            call.requirePermission(Permission.ADMIN)
            val p = call.parameters["pattern"] ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad pattern"))
            ModelCoeffRepo.delete(p)
            call.respond(ModelCoeffRepo.list())
        }
        authRoutes()
        accountRoutes(pool, probe)
        groupRoutes(pool)
        userRoutes()
        roleRoutes()
        proxyTokenRoutes()
        statsRoutes()
    }
}

// ---- helpers ----

private fun userToDto(user: org.claudeproxy.repo.UserAuth): UserDto = UserRepo.get(user.id)!!

private suspend fun buildPoolStats(pool: AccountPool): PoolStatsDto {
    val createdAt = AccountRepo.createdAtMap()
    val runtimes = pool.snapshot()
    val perAcc = UsageRepo.totalsPerAccount()
    val accounts = runtimes.map { rt ->
        val counts = perAcc[rt.id] ?: Totals()
        rt.toDto(createdAt[rt.id]?.toString() ?: Instant.now().toString(), counts)
    }
    val healthy = runtimes.count { it.enabled && it.health == org.claudeproxy.model.AccountHealth.OK }
    val effRemaining = accounts.sumOf { it.effectiveRemaining ?: 0.0 }
    val effCapacity = runtimes.sumOf { it.coefficient }
    val pt = UsageRepo.poolTotals()
    return PoolStatsDto(
        totalAccounts = runtimes.size,
        healthyAccounts = healthy,
        activeAccountId = pool.activeAccountId,
        totalEffectiveRemaining = effRemaining,
        totalEffectiveCapacity = effCapacity,
        totalRequests = pt.requests,
        totalInputTokens = pt.input,
        totalOutputTokens = pt.output,
        totalDirtyTokens = pt.dirty,
        nextFiveHourReset = pool.nextReset(WindowKind.FIVE_HOUR)?.toString(),
        nextWeeklyReset = pool.nextReset(WindowKind.WEEKLY)?.toString(),
        accounts = accounts,
    )
}

// ---- auth ----

private fun Route.authRoutes() {
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val user = UserRepo.authenticate(req.username, req.password)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, MessageResponse("Invalid credentials"))
            return@post
        }
        call.setUserSession(user.id)
        call.respond(userToDto(user))
    }
    post("/auth/logout") {
        call.clearUserSession()
        call.respond(OkResponse())
    }
    get("/auth/me") {
        val user = call.currentUser()
        if (user == null) call.respond(HttpStatusCode.Unauthorized, MessageResponse("Not authenticated"))
        else call.respond(userToDto(user))
    }
}

// ---- accounts ----

private fun Route.accountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/accounts") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        call.respond(buildPoolStats(pool))
    }

    post("/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val req = call.receive<CreateAccountRequest>()
        val type = AccountType.fromString(req.type)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Unknown type ${req.type}"))
        val secret = when (type) {
            AccountType.API_KEY -> {
                val key = req.apiKey ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("apiKey required"))
                AccountSecret(apiKey = key)
            }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                val access = req.accessToken ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("accessToken required"))
                AccountSecret(accessToken = access, refreshToken = req.refreshToken, expiresAt = req.expiresAt)
            }
        }
        val id = AccountRepo.create(req.name, type, req.groupId, req.priority, req.threshold, req.coefficient, secret, user.id)
        pool.reload()
        runCatching { probe.probe(id) } // scrape limits on add (best effort)
        call.respond(buildPoolStats(pool))
    }

    patch("/accounts/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateAccountRequest>()
        AccountRepo.updateConfig(id, req.name, req.groupId, req.priority, req.threshold, req.coefficient, req.enabled, req.clientId, req.clearGroup)
        pool.reload()
        call.respond(buildPoolStats(pool))
    }

    delete("/accounts/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        AccountRepo.delete(id)
        pool.reload()
        call.respond(OkResponse())
    }

    // Refresh live limits for one account.
    post("/accounts/{id}/refresh-limits") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        probe.probe(id)
        call.respond(buildPoolStats(pool))
    }

    // Refresh live limits for every account.
    post("/accounts/refresh-limits") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        probe.probeAll()
        call.respond(buildPoolStats(pool))
    }

    // OAuth "Login with Claude" add flow
    post("/accounts/oauth/start") {
        val user = call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val pkce = ClaudeOAuth.newPkce()
        val state = ClaudeOAuth.randomState()
        OAuthAddRepo.create(state, pkce.verifier, user.id)
        call.respond(OAuthStartResponse(ClaudeOAuth.buildAuthorizeUrl(pkce.challenge, state), state))
    }

    post("/accounts/oauth/complete") {
        val user = call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val req = call.receive<OAuthCompleteRequest>()
        val verifier = OAuthAddRepo.consume(req.state)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state"))
        val (code, stateFromCode) = ClaudeOAuth.splitCode(req.code)
        try {
            val result = ClaudeOAuth.exchangeCode(Http.client, code, verifier, stateFromCode ?: req.state)
            val secret = AccountSecret(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                expiresAt = result.expiresAtMillis,
            )
            val type = if (result.refreshToken != null) AccountType.OAUTH else AccountType.OAUTH_STATIC
            val id = AccountRepo.create(req.name, type, req.groupId, req.priority, req.threshold, req.coefficient, secret, user.id)
            pool.reload()
            runCatching { probe.probe(id) }
            call.respond(buildPoolStats(pool))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, MessageResponse("OAuth exchange failed: ${e.message}"))
        }
    }
}

// ---- groups ----

private fun Route.groupRoutes(pool: AccountPool) {
    get("/groups") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        call.respond(GroupRepo.list())
    }
    post("/groups") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val req = call.receive<CreateGroupRequest>()
        GroupRepo.create(req.name)
        call.respond(GroupRepo.list())
    }
    patch("/groups/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateGroupRequest>()
        GroupRepo.rename(id, req.name)
        call.respond(GroupRepo.list())
    }
    delete("/groups/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        GroupRepo.delete(id)
        pool.reload()
        call.respond(OkResponse())
    }
}

// ---- users ----

private fun Route.userRoutes() {
    get("/users") {
        call.requirePermission(Permission.USERS_MANAGE)
        call.respond(UserRepo.list())
    }
    post("/users") {
        call.requirePermission(Permission.USERS_MANAGE)
        val req = call.receive<CreateUserRequest>()
        val id = UserRepo.create(req.username, req.password, req.roles, req.allowedGroups, req.dailyTokenLimit, req.dailyLimitBasis)
        call.respond(UserRepo.get(id) ?: MessageResponse("created"))
    }
    patch("/users/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateUserRequest>()
        UserRepo.update(id, req.password, req.enabled, req.roles, req.allowedGroups, req.dailyTokenLimit, req.dailyLimitBasis, req.clearDailyLimit)
        call.respond(UserRepo.get(id) ?: MessageResponse("updated"))
    }
    delete("/users/{id}") {
        val me = call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (id == me.id) return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("cannot delete yourself"))
        UserRepo.delete(id)
        call.respond(OkResponse())
    }
}

// ---- roles ----

private fun Route.roleRoutes() {
    get("/roles") {
        call.requirePermission(Permission.USERS_MANAGE)
        call.respond(RolesPayload(RoleRepo.list(), RoleRepo.allPermissions()))
    }
    post("/roles") {
        call.requirePermission(Permission.USERS_MANAGE)
        val req = call.receive<CreateRoleRequest>()
        RoleRepo.create(req.name, req.permissions)
        call.respond(RolesPayload(RoleRepo.list(), RoleRepo.allPermissions()))
    }
    patch("/roles/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateRoleRequest>()
        RoleRepo.setPermissions(id, req.permissions)
        call.respond(RolesPayload(RoleRepo.list(), RoleRepo.allPermissions()))
    }
    delete("/roles/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        RoleRepo.delete(id)
        call.respond(OkResponse())
    }
}

// ---- proxy tokens (per-user, requires proxy.use) ----

private fun Route.proxyTokenRoutes() {
    get("/proxy-tokens") {
        val user = call.requireUser()
        call.respond(ProxyTokenRepo.listForUser(user.id))
    }
    post("/proxy-tokens") {
        val user = call.requirePermission(Permission.PROXY_USE)
        val req = call.receive<CreateProxyTokenRequest>()
        call.respond(ProxyTokenRepo.create(user.id, req.name))
    }
    delete("/proxy-tokens/{id}") {
        val user = call.requireUser()
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val ok = ProxyTokenRepo.delete(id, user.id)
        call.respond(if (ok) OkResponse() else MessageResponse("not found"))
    }
}

// ---- stats ----

private fun Route.statsRoutes() {
    get("/stats/usage") {
        call.requirePermission(Permission.STATS_VIEW)
        val sinceHours = call.parameters["sinceHours"]?.toLongOrNull() ?: 24L
        val since = Instant.now().minusSeconds(sinceHours * 3600)
        call.respond(StatsPayload(UsageRepo.summarySince(since), UsageRepo.recent(200)))
    }
    // Reset usage statistics for ALL users.
    post("/stats/reset") {
        call.requirePermission(Permission.STATS_VIEW)
        val n = UsageRepo.clearAll()
        call.respond(MessageResponse("Cleared $n usage records for all users"))
    }
    // Reset usage statistics for one user.
    post("/users/{id}/stats/reset") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val n = UsageRepo.clearUser(id)
        call.respond(MessageResponse("Cleared $n usage records"))
    }
    // Reset the caller's own statistics.
    post("/stats/mine/reset") {
        val user = call.requireUser()
        val ok = user.permissions.any { it == Permission.STATS_VIEW_OWN || it == Permission.STATS_VIEW || it == Permission.ADMIN }
        if (!ok) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val n = UsageRepo.clearUser(user.id)
        call.respond(MessageResponse("Cleared $n of your usage records"))
    }
    // A user's own statistics — gated by STATS_VIEW_OWN (STATS_VIEW / ADMIN also allowed).
    get("/stats/mine") {
        val user = call.requireUser()
        val ok = user.permissions.any { it == Permission.STATS_VIEW_OWN || it == Permission.STATS_VIEW || it == Permission.ADMIN }
        if (!ok) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val today = UsageRepo.userTotals(user.id, UserRepo.startOfUtcDay())
        val total = UsageRepo.userTotals(user.id)
        call.respond(
            MyStatsPayload(
                todayClean = today.clean, todayDirty = today.dirty, todayRequests = today.requests,
                totalClean = total.clean, totalDirty = total.dirty, totalRequests = total.requests,
                perModel = UsageRepo.userPerModel(user.id),
                recent = UsageRepo.recentForUser(user.id, 100),
            ),
        )
    }
}
