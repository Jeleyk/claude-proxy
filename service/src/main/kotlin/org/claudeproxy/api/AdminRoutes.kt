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
        accountRoutes(pool, probe)
        myAccountRoutes(pool, probe)
        groupRoutes(pool)
        userRoutes()
        userAccountRoutes(pool, probe)
        roleRoutes()
        proxyTokenRoutes()
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
    return PoolStatsDto(
        totalAccounts = runtimes.size,
        healthyAccounts = runtimes.count { it.enabled && it.health == org.claudeproxy.model.AccountHealth.OK },
        activeAccountId = activeAccountId,
        totalEffectiveRemaining = accounts.sumOf { it.effectiveRemaining ?: 0.0 },
        totalEffectiveCapacity = runtimes.sumOf { it.coefficient },
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

// ---- personal accounts (per-user, requires accounts.own.manage) ----

private fun Route.myAccountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/my/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        call.respond(buildOwnedStats(pool, user.id))
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
        AccountRepo.updateConfig(id, req.name, null, req.priority, req.threshold, req.coefficient, req.enabled, req.clientId, clearGroup = true)
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
        AccountRepo.updateConfig(aid, req.name, null, req.priority, req.threshold, req.coefficient, req.enabled, req.clientId, clearGroup = true)
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
        val id = UserRepo.create(req.username, req.password, req.roles, req.allowedGroups, req.dailyCostLimit)
        call.respond(UserRepo.get(id) ?: MessageResponse("created"))
    }
    patch("/users/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateUserRequest>()
        UserRepo.update(id, req.password, req.enabled, req.roles, req.allowedGroups, req.dailyCostLimit, req.clearDailyLimit)
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

private fun org.claudeproxy.repo.UserAuth.canRecent() =
    Permission.STATS_VIEW_RECENT in permissions || Permission.STATS_VIEW in permissions
private fun org.claudeproxy.repo.UserAuth.canAccounts() =
    Permission.STATS_VIEW_ACCOUNTS in permissions || Permission.STATS_VIEW in permissions
private fun org.claudeproxy.repo.UserAuth.canOwn() =
    Permission.STATS_VIEW_OWN in permissions || Permission.STATS_VIEW in permissions

/** Build the daily-cost time series for a window of [days] ending at [endDate] (UTC). */
private fun buildDaily(days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean): DailyStatsPayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }
    val totalCost = DoubleArray(n); val totalReq = LongArray(n)
    val perAcc = HashMap<Int, Pair<DoubleArray, LongArray>>()
    UsageRepo.dailyBuckets(start, end).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        totalCost[i] += b.cost; totalReq[i] += b.requests
        val (c, r) = perAcc.getOrPut(b.accountId) { DoubleArray(n) to LongArray(n) }
        c[i] += b.cost; r[i] += b.requests
    }
    val names = AccountRepo.namesMap()
    val series = if (includeAccounts)
        perAcc.entries.sortedByDescending { it.value.first.sum() }
            .map { (aid, cr) -> AccountSeriesDto(aid, names[aid], cr.first.toList(), cr.second.toList()) }
    else emptyList()
    return DailyStatsPayload(dayLabels, totalCost.toList(), totalReq.toList(), series, includeAccounts)
}

/** Build per-day token breakdowns (by model + account) for a window of [days] ending at [endDate] (UTC). */
private fun buildTokens(days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean): TokenStatsPayload {
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
    UsageRepo.tokenBuckets(start, end).forEach { b ->
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
        perAccount.entries.sortedByDescending { (_, k) -> k.sumOf { it.sum() } }
            .map { (aid, k) -> TokenAccountSeriesDto(aid, names[aid], k[0].toList(), k[1].toList(), k[2].toList(), k[3].toList()) }
    else emptyList()

    return TokenStatsPayload(
        dayLabels,
        TokenTotalsDto(total[0].toList(), total[1].toList(), total[2].toList(), total[3].toList()),
        modelSeries, accountSeries, models, includeAccounts,
    )
}

/** Build bucketed window-utilization series (5h + weekly) for a range, with carry-forward. */
private fun buildWindows(days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean): WindowStatsPayload {
    val n = days.coerceIn(1, 30)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    val buckets = (n * 24).coerceAtMost(168)  // ~hourly, capped
    val widthMs = (end.toEpochMilli() - start.toEpochMilli()).toDouble() / buckets
    val labels = (0 until buckets).map {
        java.time.Instant.ofEpochMilli(start.toEpochMilli() + (it * widthMs).toLong())
            .atZone(java.time.ZoneOffset.UTC).toLocalDateTime().toString().substring(5, 16)
    }

    // (accountId, kind) -> per-bucket [sum, count]
    val personal = AccountRepo.personalIds()
    val agg = HashMap<Pair<Int, String>, Array<DoubleArray>>()
    org.claudeproxy.repo.WindowSnapshotRepo.fetch(start, end).forEach { s ->
        if (s.accountId in personal) return@forEach
        val i = ((s.ts.toEpochMilli() - start.toEpochMilli()) / widthMs).toInt().coerceIn(0, buckets - 1)
        val arr = agg.getOrPut(s.accountId to s.kind) { arrayOf(DoubleArray(buckets), DoubleArray(buckets)) }
        arr[0][i] += s.util; arr[1][i] += 1.0
    }
    // average per bucket then carry the last known value forward across gaps
    fun series(accountId: Int, kind: String): List<Double?> {
        val arr = agg[accountId to kind] ?: return List(buckets) { null }
        val out = arrayOfNulls<Double>(buckets)
        var last: Double? = null
        for (i in 0 until buckets) {
            if (arr[1][i] > 0) last = arr[0][i] / arr[1][i]
            out[i] = last
        }
        return out.toList()
    }

    val accountIds = agg.keys.map { it.first }.distinct()
    val names = AccountRepo.namesMap()
    val perAccount = if (includeAccounts)
        accountIds.sorted().map { WindowSeriesDto(it, names[it], series(it, "5h"), series(it, "7d")) }
    else emptyList()

    fun total(kind: String): List<Double?> = (0 until buckets).map { i ->
        val vals = accountIds.mapNotNull { series(it, kind)[i] }
        if (vals.isEmpty()) null else vals.average()
    }

    return WindowStatsPayload(labels, total("5h"), total("7d"), perAccount, includeAccounts)
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
    // Recent requests list. Account attribution only if the viewer may see accounts.
    get("/stats/recent") {
        val user = call.requireUser()
        if (!user.canRecent()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_RECENT")
        val recent = UsageRepo.recent(200)
        call.respond(if (user.canAccounts()) recent else recent.map { it.copy(accountName = null, accountId = 0) })
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
        // "today" mirrors the daily-limit basis (shared-pool spend only); all-time/history stay complete.
        val today = UsageRepo.userTotals(user.id, UserRepo.startOfUtcDay(), globalOnly = true)
        val total = UsageRepo.userTotals(user.id)
        val recent = UsageRepo.recentForUser(user.id, 100)
        call.respond(
            MyStatsPayload(
                todayCost = today.cost, todayClean = today.clean, todayRequests = today.requests,
                totalCost = total.cost, totalClean = total.clean, totalRequests = total.requests,
                dailyCostLimit = UserRepo.dailyLimitOf(user.id),
                perModel = UsageRepo.userPerModel(user.id),
                recent = if (user.canAccounts()) recent else recent.map { it.copy(accountName = null, accountId = 0) },
            ),
        )
    }
}
