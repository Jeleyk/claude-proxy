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
import org.claudeproxy.repo.ModelPriceRepo
import org.claudeproxy.repo.OAuthAddRepo
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.RoleRepo
import org.claudeproxy.repo.SettingsRepo
import org.claudeproxy.repo.UsageRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant

fun Route.adminRoutes(pool: AccountPool, probe: LimitProbe, publicBaseUrl: String) {
    route("/api") {
        get("/config") {
            call.requireUser()
            call.respond(ConfigDto(publicBaseUrl))
        }
        // per-model pricing ($ / 1M tokens), admin-managed
        get("/model-prices") {
            call.requireUser()
            call.respond(ModelPriceRepo.list())
        }
        post("/model-prices") {
            call.requirePermission(Permission.ADMIN)
            val req = call.receive<ModelPriceRequest>()
            if (req.pattern.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("pattern required"))
            ModelPriceRepo.set(req.pattern, req.inputPrice, req.outputPrice, req.cacheReadPrice, req.cacheWritePrice)
            call.respond(ModelPriceRepo.list())
        }
        delete("/model-prices/{pattern}") {
            call.requirePermission(Permission.ADMIN)
            val p = call.parameters["pattern"] ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad pattern"))
            ModelPriceRepo.delete(p)
            call.respond(ModelPriceRepo.list())
        }
        authRoutes()
        profileRoutes()
        accountRoutes(pool, probe)
        myAccountRoutes(pool, probe)
        groupRoutes(pool)
        userRoutes()
        userAccountRoutes(pool, probe)
        roleRoutes()
        proxyTokenRoutes()
        routingTokenRoutes()
        statsRoutes()
    }
}

// ---- helpers ----

private fun userToDto(user: org.claudeproxy.repo.UserAuth): UserDto = UserRepo.get(user.id)!!

/** Global (shared-pool) stats — personal accounts are excluded everywhere. */
private suspend fun buildPoolStats(pool: AccountPool): PoolStatsDto =
    assembleStats(
        runtimes = pool.snapshotGlobal(),
        perAcc = UsageRepo.totalsPerAccount(),
        poolWide = UsageRepo.poolTotals(),
        activeAccountId = pool.activeAccountId,
        nextFiveHourReset = pool.nextReset(WindowKind.FIVE_HOUR)?.toString(),
        nextWeeklyReset = pool.nextReset(WindowKind.WEEKLY)?.toString(),
    )

/** Stats for one user's personal accounts (the "My Accounts" view + admin oversight). */
private suspend fun buildOwnedStats(pool: AccountPool, userId: Int): PoolStatsDto {
    val runtimes = pool.snapshotOwned(userId)
    val ids = runtimes.map { it.id }.toSet()
    val perAcc = UsageRepo.totalsForAccounts(ids)
    val poolWide = perAcc.values.fold(Totals()) { a, b ->
        Totals(a.requests + b.requests, a.input + b.input, a.output + b.output,
            a.cacheRead + b.cacheRead, a.cacheWrite + b.cacheWrite, a.cost + b.cost)
    }
    return assembleStats(
        runtimes = runtimes,
        perAcc = perAcc,
        poolWide = poolWide,
        activeAccountId = pool.activeAccountId.takeIf { it in ids },
        nextFiveHourReset = pool.nextResetOwned(userId, WindowKind.FIVE_HOUR)?.toString(),
        nextWeeklyReset = pool.nextResetOwned(userId, WindowKind.WEEKLY)?.toString(),
    )
}

private fun assembleStats(
    runtimes: List<org.claudeproxy.accounts.AccountRuntime>,
    perAcc: Map<Int, Totals>,
    poolWide: Totals,
    activeAccountId: Int?,
    nextFiveHourReset: String?,
    nextWeeklyReset: String?,
): PoolStatsDto {
    val createdAt = AccountRepo.createdAtMap()
    val accounts = runtimes.map { rt ->
        rt.toDto(createdAt[rt.id]?.toString() ?: Instant.now().toString(), perAcc[rt.id] ?: Totals())
    }
    // Weekly headroom mirrors the 5-hour `effectiveRemaining` logic: coefficient-weighted
    // remaining over the accounts that actually report a weekly reading (API keys count as
    // full capacity, since they have no subscription window). Accounts with no weekly reading
    // are excluded from both sums so the resulting % stays meaningful.
    val weeklyContribs = runtimes.mapNotNull { rt ->
        val wu = rt.limit.window(WindowKind.WEEKLY)?.usageFraction()
        val rem = wu?.let { rt.coefficient * (1.0 - it) }
            ?: if (rt.type == org.claudeproxy.model.AccountType.API_KEY) rt.coefficient else null
        rem?.let { it to rt.coefficient }
    }
    return PoolStatsDto(
        totalAccounts = runtimes.size,
        healthyAccounts = runtimes.count { it.enabled && it.health == org.claudeproxy.model.AccountHealth.OK },
        activeAccountId = activeAccountId,
        totalEffectiveRemaining = accounts.sumOf { it.effectiveRemaining ?: 0.0 },
        totalEffectiveCapacity = runtimes.sumOf { it.coefficient },
        totalWeeklyRemaining = weeklyContribs.sumOf { it.first },
        totalWeeklyCapacity = weeklyContribs.sumOf { it.second },
        totalRequests = poolWide.requests,
        totalInputTokens = poolWide.input,
        totalOutputTokens = poolWide.output,
        totalCacheReadTokens = poolWide.cacheRead,
        totalCacheWriteTokens = poolWide.cacheWrite,
        totalCost = poolWide.cost,
        nextFiveHourReset = nextFiveHourReset,
        nextWeeklyReset = nextWeeklyReset,
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

// ---- self-service profile (any authenticated user) ----

private fun Route.profileRoutes() {
    // Change your own username and/or password. Current password gates the change.
    patch("/account") {
        val user = call.requireUser()
        val req = call.receive<UpdateProfileRequest>()
        if (!UserRepo.verifyPassword(user.id, req.currentPassword)) {
            return@patch call.respond(HttpStatusCode.Unauthorized, MessageResponse("Current password is incorrect"))
        }
        val newName = req.username?.trim()?.takeIf { it.isNotBlank() }
        if (newName != null && newName != user.username && UserRepo.usernameTaken(newName, user.id)) {
            return@patch call.respond(HttpStatusCode.Conflict, MessageResponse("Username already taken"))
        }
        val newPassword = req.password?.takeIf { it.isNotBlank() }
        UserRepo.updateSelf(user.id, newName, newPassword)
        call.respond(UserRepo.get(user.id) ?: MessageResponse("updated"))
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
        AccountRepo.updateConfig(id, req.name, req.groupId, req.priority, req.threshold, req.coefficient, req.enabled, req.deviceId, req.clearGroup, req.overThreshold)
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

// ---- personal accounts (per-user, requires accounts.own.manage) ----

private fun Route.myAccountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/my/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        call.respond(buildOwnedStats(pool, user.id))
    }
    // Shared-pool headroom shown on "My Accounts": same global stats as the Dashboard, but
    // available to anyone allowed to route through the global pool (not just ACCOUNTS_VIEW).
    get("/my/global-pool") {
        call.requirePermission(Permission.POOL_GLOBAL_USE)
        call.respond(buildPoolStats(pool))
    }
    post("/my/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
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
        val id = AccountRepo.create(req.name, type, null, req.priority, req.threshold, req.coefficient, secret, createdBy = user.id, ownerId = user.id)
        pool.reload()
        runCatching { probe.probe(id) }
        call.respond(buildOwnedStats(pool, user.id))
    }
    patch("/my/accounts/{id}") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        val req = call.receive<UpdateAccountRequest>()
        // personal accounts are never grouped
        AccountRepo.updateConfig(id, req.name, null, req.priority, req.threshold, req.coefficient, req.enabled, req.deviceId, clearGroup = true, overThreshold = req.overThreshold)
        pool.reload()
        call.respond(buildOwnedStats(pool, user.id))
    }
    delete("/my/accounts/{id}") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        AccountRepo.delete(id)
        pool.reload()
        call.respond(OkResponse())
    }
    post("/my/accounts/{id}/refresh-limits") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@post call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        probe.probe(id)
        call.respond(buildOwnedStats(pool, user.id))
    }
    post("/my/accounts/refresh-limits") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        pool.snapshotOwned(user.id).forEach { probe.probe(it.id) }
        call.respond(buildOwnedStats(pool, user.id))
    }
    post("/my/accounts/oauth/start") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val pkce = ClaudeOAuth.newPkce()
        val state = ClaudeOAuth.randomState()
        OAuthAddRepo.create(state, pkce.verifier, user.id)
        call.respond(OAuthStartResponse(ClaudeOAuth.buildAuthorizeUrl(pkce.challenge, state), state))
    }
    post("/my/accounts/oauth/complete") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val req = call.receive<OAuthCompleteRequest>()
        val verifier = OAuthAddRepo.consume(req.state)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state"))
        val (code, stateFromCode) = ClaudeOAuth.splitCode(req.code)
        try {
            val result = ClaudeOAuth.exchangeCode(Http.client, code, verifier, stateFromCode ?: req.state)
            val secret = AccountSecret(accessToken = result.accessToken, refreshToken = result.refreshToken, expiresAt = result.expiresAtMillis)
            val type = if (result.refreshToken != null) AccountType.OAUTH else AccountType.OAUTH_STATIC
            val id = AccountRepo.create(req.name, type, null, req.priority, req.threshold, req.coefficient, secret, createdBy = user.id, ownerId = user.id)
            pool.reload()
            runCatching { probe.probe(id) }
            call.respond(buildOwnedStats(pool, user.id))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, MessageResponse("OAuth exchange failed: ${e.message}"))
        }
    }
    // Switch whether the global pool or personal accounts are tried first for this user's requests.
    patch("/my/account-order") {
        val user = call.requirePermission(Permission.ACCOUNTS_ORDER_TOGGLE)
        val req = call.receive<AccountOrderRequest>()
        UserRepo.setPreferGlobalPool(user.id, req.preferGlobalPool)
        call.respond(UserRepo.get(user.id) ?: MessageResponse("updated"))
    }
}

// ---- admin oversight of any user's personal accounts (requires users.manage) ----

private fun Route.userAccountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/users/{id}/accounts") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        call.respond(buildOwnedStats(pool, uid))
    }
    patch("/users/{id}/accounts/{aid}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
        val aid = call.parameters["aid"]?.toIntOrNull()
        if (uid == null || aid == null) return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(aid, uid)) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        val req = call.receive<UpdateAccountRequest>()
        AccountRepo.updateConfig(aid, req.name, null, req.priority, req.threshold, req.coefficient, req.enabled, req.deviceId, clearGroup = true, overThreshold = req.overThreshold)
        pool.reload()
        call.respond(buildOwnedStats(pool, uid))
    }
    delete("/users/{id}/accounts/{aid}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
        val aid = call.parameters["aid"]?.toIntOrNull()
        if (uid == null || aid == null) return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(aid, uid)) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        AccountRepo.delete(aid)
        pool.reload()
        call.respond(buildOwnedStats(pool, uid))
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
        val id = UserRepo.create(req.username, req.password, req.roles, req.allowedGroups, req.dailyCostLimit, req.dailyRoutingCostLimit)
        call.respond(UserRepo.get(id) ?: MessageResponse("created"))
    }
    patch("/users/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateUserRequest>()
        UserRepo.update(id, req.password, req.enabled, req.roles, req.allowedGroups, req.dailyCostLimit, req.clearDailyLimit, req.dailyRoutingCostLimit, req.clearRoutingLimit)
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

// ---- routing tokens (per-user, requires routing.use) — OpenAI/Anthropic API gateways ----

private fun Route.routingTokenRoutes() {
    get("/routing-tokens") {
        val user = call.requireUser()
        call.respond(RoutingTokenRepo.listForUser(user.id))
    }
    post("/routing-tokens") {
        val user = call.requirePermission(Permission.ROUTING_USE)
        val req = call.receive<CreateProxyTokenRequest>()
        call.respond(RoutingTokenRepo.create(user.id, req.name))
    }
    delete("/routing-tokens/{id}") {
        val user = call.requireUser()
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val ok = RoutingTokenRepo.delete(id, user.id)
        call.respond(if (ok) OkResponse() else MessageResponse("not found"))
    }
}

// ---- stats ----

private fun org.claudeproxy.repo.UserAuth.canRecent() =
    Permission.STATS_VIEW_RECENT in permissions || Permission.STATS_VIEW in permissions
private fun org.claudeproxy.repo.UserAuth.canAccounts() =
    Permission.STATS_VIEW_ACCOUNTS in permissions || Permission.STATS_VIEW in permissions
private fun org.claudeproxy.repo.UserAuth.canOwn() =
    Permission.STATS_VIEW_OWN in permissions || Permission.STATS_VIEW in permissions

/**
 * Build the daily-cost time series for a window of [days] ending at [endDate] (UTC).
 * [fetch] supplies the buckets (pool-wide or one user's); [accountFilter], when set, limits the
 * per-account series to those account ids (the total always reflects every bucket returned).
 */
private fun buildDaily(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    accountFilter: Set<Int>? = null,
    fetch: (java.time.Instant, java.time.Instant) -> List<org.claudeproxy.repo.DailyBucketDto> = UsageRepo::dailyBuckets,
): DailyStatsPayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }
    val totalCost = DoubleArray(n); val totalReq = LongArray(n)
    val perAcc = HashMap<Int, Pair<DoubleArray, LongArray>>()
    fetch(start, end).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        totalCost[i] += b.cost; totalReq[i] += b.requests
        val (c, r) = perAcc.getOrPut(b.accountId) { DoubleArray(n) to LongArray(n) }
        c[i] += b.cost; r[i] += b.requests
    }
    val names = AccountRepo.namesMap()
    val series = if (includeAccounts)
        perAcc.entries.filter { accountFilter == null || it.key in accountFilter }
            .sortedByDescending { it.value.first.sum() }
            .map { (aid, cr) -> AccountSeriesDto(aid, names[aid], cr.first.toList(), cr.second.toList()) }
    else emptyList()
    return DailyStatsPayload(dayLabels, totalCost.toList(), totalReq.toList(), series, includeAccounts)
}

/**
 * Build per-day token breakdowns (by model + account) for a window of [days] ending at [endDate] (UTC).
 * [fetch] supplies the buckets (pool-wide or one user's); [accountFilter], when set, limits the
 * per-account series to those account ids (total + per-model always reflect every bucket returned).
 */
private fun buildTokens(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    accountFilter: Set<Int>? = null,
    fetch: (java.time.Instant, java.time.Instant) -> List<org.claudeproxy.repo.TokenBucketDto> = UsageRepo::tokenBuckets,
): TokenStatsPayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }

    // Each series is four per-day arrays: [input, output, cacheRead, cacheWrite].
    fun kinds() = Array(4) { LongArray(n) }
    val total = kinds()
    val perModel = HashMap<String, Array<LongArray>>()
    val perAccount = HashMap<Int, Array<LongArray>>()
    fetch(start, end).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        val m = perModel.getOrPut(b.model ?: "unknown") { kinds() }
        val a = perAccount.getOrPut(b.accountId) { kinds() }
        // total, per-model and per-account all accumulate the same four kinds at day i
        listOf(total, m, a).forEach { k ->
            k[0][i] += b.input; k[1][i] += b.output; k[2][i] += b.cacheRead; k[3][i] += b.cacheWrite
        }
    }

    val models = perModel.keys.sorted()
    val modelSeries = models.map { model ->
        val k = perModel.getValue(model)
        TokenModelSeriesDto(model, k[0].toList(), k[1].toList(), k[2].toList(), k[3].toList())
    }
    val names = AccountRepo.namesMap()
    val accountSeries = if (includeAccounts)
        perAccount.entries.filter { accountFilter == null || it.key in accountFilter }
            .sortedByDescending { (_, k) -> k.sumOf { it.sum() } }
            .map { (aid, k) -> TokenAccountSeriesDto(aid, names[aid], k[0].toList(), k[1].toList(), k[2].toList(), k[3].toList()) }
    else emptyList()

    return TokenStatsPayload(
        dayLabels,
        TokenTotalsDto(total[0].toList(), total[1].toList(), total[2].toList(), total[3].toList()),
        modelSeries, accountSeries, models, includeAccounts,
    )
}

/**
 * Build the per-inbound-token usage payload for one user on one datapath ("proxy" | "routing"):
 * range-aligned daily cost/token/request series per token, plus all-time totals per token.
 * Deleted tokens keep their series with name=null; tokenId=null groups unattributed rows.
 */
private fun buildTokenUsage(userId: Int, source: String, days: Int, endDate: java.time.LocalDate): TokenUsagePayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }

    val perToken = HashMap<Int?, Triple<DoubleArray, LongArray, LongArray>>() // tokenId -> (cost, tokens, requests)
    UsageRepo.tokenDailyBucketsForUser(userId, source, start, end).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        val (c, t, r) = perToken.getOrPut(b.tokenId) { Triple(DoubleArray(n), LongArray(n), LongArray(n)) }
        c[i] += b.cost; t[i] += b.tokens; r[i] += b.requests
    }
    val totals = UsageRepo.totalsPerTokenForUser(userId, source)
    val names = if (source == "routing") RoutingTokenRepo.namesForUser(userId) else ProxyTokenRepo.namesForUser(userId)

    // Every token with any usage (all-time or in range) gets a series; usage-less tokens are
    // omitted — the UI shows zeros for them from the table side.
    val ids = (perToken.keys + totals.keys)
    val series = ids.map { id ->
        val (c, t, r) = perToken[id] ?: Triple(DoubleArray(n), LongArray(n), LongArray(n))
        val tot = totals[id] ?: Totals()
        TokenUsageSeriesDto(
            tokenId = id, name = id?.let { names[it] },
            cost = c.toList(), tokens = t.toList(), requests = r.toList(),
            totalCost = tot.cost, totalTokens = tot.clean, totalRequests = tot.requests,
        )
    }.sortedByDescending { it.totalCost }
    return TokenUsagePayload(dayLabels, series)
}

/**
 * Build bucketed window-utilization series (5h + weekly) for a range, with carry-forward.
 * [keep] decides which account ids contribute — pool-wide excludes personal accounts; the
 * per-user view keeps only that user's personal accounts.
 */
private fun buildWindows(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    keep: ((Int) -> Boolean)? = null,
): WindowStatsPayload {
    val n = days.coerceIn(1, 30)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    // 30-min bins: the limit probe samples each account every ~30 min, so half-hour
    // buckets are the finest resolution the data actually supports. Capped at 336
    // (= 7 days of 30-min bins); longer ranges coarsen to keep the payload sane.
    val buckets = (n * 48).coerceAtMost(336)
    val widthMs = (end.toEpochMilli() - start.toEpochMilli()).toDouble() / buckets
    val labels = (0 until buckets).map {
        java.time.Instant.ofEpochMilli(start.toEpochMilli() + (it * widthMs).toLong())
            .atZone(java.time.ZoneOffset.UTC).toLocalDateTime().toString().substring(5, 16)
    }
    // Default keep: exclude personal accounts (pool-wide view).
    val includeAccount = keep ?: AccountRepo.personalIds().let { personal -> { id: Int -> id !in personal } }
    val samples = org.claudeproxy.repo.WindowSnapshotRepo.fetch(start, end).filter { includeAccount(it.accountId) }
    return aggregateWindows(samples, start.toEpochMilli(), widthMs, buckets, labels, includeAccounts, AccountRepo.namesMap())
}

/**
 * Pure bucketing/aggregation for the window-utilization charts — split out of [buildWindows] so
 * the sum + carry-forward + coefficient-weighting math is unit-testable without a DB. [samples]
 * are already filtered to the accounts that should contribute.
 *
 * Each (account, window) is binned to per-bucket averages (raw utilization and coefficient×util,
 * using the coefficient frozen on each sample), then carried forward across gaps. Pool totals are
 * the **sum** across accounts of those carried series (so a multi-account pool can exceed 100%),
 * for both the raw and the ×coef-weighted variants.
 */
internal fun aggregateWindows(
    samples: List<org.claudeproxy.repo.WindowSample>,
    startMs: Long, widthMs: Double, buckets: Int,
    labels: List<String>, includeAccounts: Boolean, names: Map<Int, String>,
): WindowStatsPayload {
    // (accountId, kind) -> per-bucket [Σutil, Σ(coef·util), count]
    val agg = HashMap<Pair<Int, String>, Array<DoubleArray>>()
    samples.forEach { s ->
        val i = ((s.ts.toEpochMilli() - startMs) / widthMs).toInt().coerceIn(0, buckets - 1)
        val arr = agg.getOrPut(s.accountId to s.kind) {
            arrayOf(DoubleArray(buckets), DoubleArray(buckets), DoubleArray(buckets))
        }
        arr[0][i] += s.util
        arr[1][i] += s.util * s.coef
        arr[2][i] += 1.0
    }
    // per-bucket average of one accumulator lane (0 = util, 1 = coef·util), carried forward across gaps
    fun series(key: Pair<Int, String>, lane: Int): List<Double?> {
        val arr = agg[key] ?: return List(buckets) { null }
        val out = arrayOfNulls<Double>(buckets)
        var last: Double? = null
        for (i in 0 until buckets) {
            if (arr[2][i] > 0) last = arr[lane][i] / arr[2][i]
            out[i] = last
        }
        return out.toList()
    }

    val accountIds = agg.keys.map { it.first }.distinct().sorted()
    val perAccount = if (includeAccounts) accountIds.map { aid ->
        WindowSeriesDto(
            aid, names[aid],
            series(aid to "5h", 0), series(aid to "7d", 0),
            series(aid to "5h", 1), series(aid to "7d", 1),
        )
    } else emptyList()

    // pool total = SUM across accounts of each account's carried series (raw or ×coef-weighted)
    fun total(kind: String, lane: Int): List<Double?> {
        val all = accountIds.map { series(it to kind, lane) }
        return (0 until buckets).map { i ->
            val vals = all.mapNotNull { it[i] }
            if (vals.isEmpty()) null else vals.sum()
        }
    }

    return WindowStatsPayload(
        labels,
        total("5h", 0), total("7d", 0),
        total("5h", 1), total("7d", 1),
        perAccount, includeAccounts,
    )
}

private fun Route.statsRoutes() {
    // Window-utilization (5h + weekly) trend over time.
    get("/stats/windows") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val days = call.parameters["days"]?.toIntOrNull() ?: 7
        val endDate = call.parameters["end"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        call.respond(buildWindows(days, endDate, user.canAccounts()))
    }
    // Per-account summary over the last N hours (full stats).
    get("/stats/summary") {
        call.requirePermission(Permission.STATS_VIEW)
        val sinceHours = call.parameters["sinceHours"]?.toLongOrNull() ?: 24L
        call.respond(UsageRepo.summarySince(Instant.now().minusSeconds(sinceHours * 3600)))
    }
    // Recent requests list (latest 20). Account attribution only if the viewer may see accounts.
    get("/stats/recent") {
        val user = call.requireUser()
        if (!user.canRecent()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_RECENT")
        val recent = UsageRepo.recent(20)
        call.respond(if (user.canAccounts()) recent else recent.map { it.copy(accountName = null, accountId = 0) })
    }
    // Pool-wide per-model breakdown: today (UTC) + all-time, for the client-side toggle.
    get("/stats/models") {
        call.requirePermission(Permission.STATS_VIEW)
        call.respond(ModelBreakdownPayload(today = UsageRepo.perModel(UserRepo.startOfUtcDay()), allTime = UsageRepo.perModel()))
    }
    // Daily cost time series (graphs). Default: last 7 days ending today (UTC).
    get("/stats/daily") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val days = call.parameters["days"]?.toIntOrNull() ?: 7
        val endDate = call.parameters["end"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        call.respond(buildDaily(days, endDate, user.canAccounts()))
    }
    // Per-day token breakdown (by model + account) for the token charts. Default: last 7 days ending today (UTC).
    get("/stats/tokens") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val days = call.parameters["days"]?.toIntOrNull() ?: 7
        val endDate = call.parameters["end"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        call.respond(buildTokens(days, endDate, user.canAccounts()))
    }
    post("/stats/reset") {
        call.requirePermission(Permission.STATS_VIEW)
        call.respond(MessageResponse("Cleared ${UsageRepo.clearAll()} usage records for all users"))
    }
    post("/users/{id}/stats/reset") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        call.respond(MessageResponse("Cleared ${UsageRepo.clearUser(id)} usage records"))
    }
    post("/stats/mine/reset") {
        val user = call.requireUser()
        // Resetting own stats zeroes today's spend, so it can bypass a daily limit — gate it.
        val ok = Permission.STATS_RESET_OWN in user.permissions || Permission.ADMIN in user.permissions
        if (!ok) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_RESET_OWN")
        call.respond(MessageResponse("Cleared ${UsageRepo.clearUser(user.id)} of your usage records"))
    }
    get("/stats/mine") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        // "today" mirrors the daily-limit basis (shared-pool PROXY spend, paired with dailyCostLimit
        // below); routing spend has its own limit + page. All-time/history/charts stay complete.
        val today = UsageRepo.userTotals(user.id, UserRepo.startOfUtcDay(), globalOnly = true, source = "proxy")
        val total = UsageRepo.userTotals(user.id)
        val recent = UsageRepo.recentForUser(user.id, 20)
        call.respond(
            MyStatsPayload(
                todayCost = today.cost, todayClean = today.clean, todayRequests = today.requests,
                totalCost = total.cost, totalClean = total.clean, totalRequests = total.requests,
                dailyCostLimit = UserRepo.dailyLimitOf(user.id),
                perModel = UsageRepo.userPerModel(user.id),
                perModelToday = UsageRepo.userPerModel(user.id, UserRepo.startOfUtcDay()),
                recent = if (user.canAccounts()) recent else recent.map { it.copy(accountName = null, accountId = 0) },
            ),
        )
    }
    // Per-user charts mirroring the pool-wide graphs: Spend/Tokens cover ALL of the user's own
    // usage (any account), while per-account + window series are scoped to the user's personal accounts.
    get("/stats/mine/daily") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate) = call.rangeParams()
        call.respond(buildDaily(days, endDate, includeAccounts = true,
            accountFilter = AccountRepo.personalIdsOf(user.id),
            fetch = { s, e -> UsageRepo.dailyBucketsForUser(user.id, s, e) }))
    }
    get("/stats/mine/tokens") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate) = call.rangeParams()
        call.respond(buildTokens(days, endDate, includeAccounts = true,
            accountFilter = AccountRepo.personalIdsOf(user.id),
            fetch = { s, e -> UsageRepo.tokenBucketsForUser(user.id, s, e) }))
    }
    // Per-inbound-token usage for the caller's Tokens / API Routing pages: all-time totals per
    // token (table summary) + daily cost/token series for the charts. Strictly the caller's own
    // data (incl. personal accounts), so a session is the only requirement.
    get("/stats/mine/token-usage") {
        val user = call.requireUser()
        val source = if (call.parameters["source"] == "routing") "routing" else "proxy"
        val (days, endDate) = call.rangeParams()
        call.respond(buildTokenUsage(user.id, source, days, endDate))
    }
    get("/stats/mine/windows") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate) = call.rangeParams()
        val mine = AccountRepo.personalIdsOf(user.id)
        call.respond(buildWindows(days, endDate, includeAccounts = true, keep = { it in mine }))
    }
}

/** Parse the shared `days` + `end` (UTC) query params used by every time-series endpoint. */
private fun io.ktor.server.application.ApplicationCall.rangeParams(): Pair<Int, java.time.LocalDate> {
    val days = parameters["days"]?.toIntOrNull() ?: 7
    val endDate = parameters["end"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    return days to endDate
}
