package org.claudeproxy.repo

import org.claudeproxy.auth.Passwords
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.RolePermissions
import org.claudeproxy.db.Roles
import org.claudeproxy.db.UserGroupAccess
import org.claudeproxy.db.UserRoles
import org.claudeproxy.db.Users
import org.claudeproxy.model.Permission
import org.claudeproxy.model.UserDto
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class UserAuth(val id: Int, val username: String, val enabled: Boolean, val permissions: Set<Permission>)

object UserRepo {

    fun authenticate(username: String, password: String): UserAuth? = transaction {
        val row = Users.selectAll().where { Users.username eq username }.firstOrNull() ?: return@transaction null
        if (!row[Users.enabled]) return@transaction null
        if (!Passwords.verify(password, row[Users.passwordHash])) return@transaction null
        toAuth(row)
    }

    fun findAuth(userId: Int): UserAuth? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        toAuth(row)
    }

    private fun toAuth(row: ResultRow): UserAuth {
        val uid = row[Users.id]
        return UserAuth(uid, row[Users.username], row[Users.enabled], permissionsOf(uid))
    }

    fun permissionsOf(userId: Int): Set<Permission> = transaction {
        val roleIds = UserRoles.selectAll().where { UserRoles.userId eq userId }.map { it[UserRoles.roleId] }
        if (roleIds.isEmpty()) return@transaction emptySet()
        val perms = RolePermissions.selectAll().where { RolePermissions.roleId inList roleIds }
            .mapNotNull { Permission.fromString(it[RolePermissions.permission]) }
            .toMutableSet()
        if (Permission.ADMIN in perms) return@transaction Permission.entries.toSet()
        perms
    }

    fun rolesOf(userId: Int): List<String> = transaction {
        val roleIds = UserRoles.selectAll().where { UserRoles.userId eq userId }.map { it[UserRoles.roleId] }
        if (roleIds.isEmpty()) return@transaction emptyList()
        Roles.selectAll().where { Roles.id inList roleIds }.map { it[Roles.name] }
    }

    fun list(): List<UserDto> = transaction {
        Users.selectAll().map { row -> toDto(row) }
    }

    fun get(userId: Int): UserDto? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        toDto(row)
    }

    /** Per-day spend limit in USD for a user; null = unlimited. */
    fun dailyLimitOf(userId: Int): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.dailyCostLimit)
    }

    /** Per-day routing (OpenAI/Anthropic gateway) spend limit in USD for a user; null = unlimited. */
    fun dailyRoutingLimitOf(userId: Int): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.dailyRoutingCostLimit)
    }

    /** Per-day chat-UI spend limit in USD for a user; null = unlimited. */
    fun dailyChatLimitOf(userId: Int): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.dailyChatCostLimit)
    }

    /** Routing preference: true = try the global pool before the user's own personal accounts. */
    fun preferGlobalPoolOf(userId: Int): Boolean = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.preferGlobalPool) ?: false
    }

    fun setPreferGlobalPool(userId: Int, prefer: Boolean) = transaction {
        Users.update({ Users.id eq userId }) { it[preferGlobalPool] = prefer }
    }

    /** Verify a plaintext password against the stored hash (self-service profile edits). */
    fun verifyPassword(userId: Int, plain: String): Boolean = transaction {
        val hash = Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.passwordHash)
            ?: return@transaction false
        Passwords.verify(plain, hash)
    }

    /** True if [username] is already used by a different user (case-sensitive, matches the unique index). */
    fun usernameTaken(username: String, exceptUserId: Int): Boolean = transaction {
        Users.selectAll().where { (Users.username eq username) and (Users.id neq exceptUserId) }.any()
    }

    /** Self-service update of one's own username/password. Nulls leave the field unchanged. */
    fun updateSelf(userId: Int, username: String?, password: String?) = transaction {
        Users.update({ Users.id eq userId }) {
            if (username != null) it[Users.username] = username
            if (password != null) it[passwordHash] = Passwords.hash(password)
        }
    }

    private fun toDto(row: ResultRow): UserDto {
        val uid = row[Users.id]
        val perms = permissionsOf(uid)
        // "today" here reflects shared-pool spend (what the daily limit governs); personal-account usage is excluded.
        // Split by datapath so proxy, routing and chat spend are metered against their own daily limits.
        val today = UsageRepo.userTotals(uid, startOfUtcDay(), globalOnly = true, source = "proxy")
        val todayRouting = UsageRepo.userTotals(uid, startOfUtcDay(), globalOnly = true, source = "routing")
        val todayChat = UsageRepo.userTotals(uid, startOfUtcDay(), globalOnly = true, source = "chat")
        return UserDto(
            id = uid,
            username = row[Users.username],
            enabled = row[Users.enabled],
            roles = rolesOf(uid),
            permissions = perms.map { it.name },
            allowedGroups = allowedGroupsOf(uid).toList(),
            allGroups = Permission.ADMIN in perms,
            dailyCostLimit = row[Users.dailyCostLimit],
            dailyRoutingCostLimit = row[Users.dailyRoutingCostLimit],
            dailyChatCostLimit = row[Users.dailyChatCostLimit],
            preferGlobalPool = row[Users.preferGlobalPool],
            todayCost = today.cost,
            todayRoutingCost = todayRouting.cost,
            todayChatCost = todayChat.cost,
            todayInputTokens = today.input,
            todayOutputTokens = today.output,
        )
    }

    /**
     * Start of today on the *limit* clock. The per-user daily USD limits are enforced on UTC days,
     * so anything read against a limit must use this — never the viewer's zone.
     */
    fun startOfUtcDay(): Instant = startOfDayIn(java.time.ZoneOffset.UTC)

    /**
     * Start of today on [zone]'s clock — the basis for *displayed* "today" counters, so they line
     * up with the charts (which are already sliced on the viewer's days).
     */
    fun startOfDayIn(zone: java.time.ZoneId): Instant =
        java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()

    fun allowedGroupsOf(userId: Int): Set<Int> = transaction {
        UserGroupAccess.selectAll().where { UserGroupAccess.userId eq userId }
            .map { it[UserGroupAccess.groupId] }.toSet()
    }

    fun setAllowedGroups(userId: Int, groupIds: List<Int>) = transaction {
        UserGroupAccess.deleteWhere { UserGroupAccess.userId eq userId }
        groupIds.distinct().forEach { gid ->
            UserGroupAccess.insert {
                it[UserGroupAccess.userId] = userId
                it[groupId] = gid
            }
        }
    }

    fun create(
        username: String, password: String, roleNames: List<String>, groupIds: List<Int>,
        dailyCostLimit: Double?, dailyRoutingCostLimit: Double? = null, dailyChatCostLimit: Double? = null,
    ): Int = transaction {
        val uid = Users.insert {
            it[Users.username] = username
            it[passwordHash] = Passwords.hash(password)
            it[enabled] = true
            it[Users.dailyCostLimit] = dailyCostLimit
            it[Users.dailyRoutingCostLimit] = dailyRoutingCostLimit
            it[Users.dailyChatCostLimit] = dailyChatCostLimit
            it[createdAt] = Instant.now()
        }[Users.id]
        setRoles(uid, roleNames)
        setAllowedGroups(uid, groupIds)
        uid
    }

    fun update(
        userId: Int, password: String?, enabled: Boolean?, roleNames: List<String>?, groupIds: List<Int>?,
        dailyCostLimit: Double?, clearDailyLimit: Boolean,
        dailyRoutingCostLimit: Double? = null, clearRoutingLimit: Boolean = false,
        dailyChatCostLimit: Double? = null, clearChatLimit: Boolean = false,
    ) {
        transaction {
            Users.update({ Users.id eq userId }) {
                if (password != null) it[passwordHash] = Passwords.hash(password)
                if (enabled != null) it[Users.enabled] = enabled
                if (clearDailyLimit) it[Users.dailyCostLimit] = null else if (dailyCostLimit != null) it[Users.dailyCostLimit] = dailyCostLimit
                if (clearRoutingLimit) it[Users.dailyRoutingCostLimit] = null else if (dailyRoutingCostLimit != null) it[Users.dailyRoutingCostLimit] = dailyRoutingCostLimit
                if (clearChatLimit) it[Users.dailyChatCostLimit] = null else if (dailyChatCostLimit != null) it[Users.dailyChatCostLimit] = dailyChatCostLimit
            }
            if (roleNames != null) setRoles(userId, roleNames)
            if (groupIds != null) setAllowedGroups(userId, groupIds)
        }
        // Token resolves are cached for 60s; a disable has to bite on the very next request.
        // Evicted after the commit, or a concurrent resolve could re-cache the old state.
        if (enabled == false) tokenCacheKeys(userId).forEach(MemoryCache::evict)
    }

    /** Resolve-cache keys of every token this user owns, both namespaces. */
    private fun tokenCacheKeys(userId: Int): List<String> = transaction {
        org.claudeproxy.db.ProxyTokens.selectAll().where { org.claudeproxy.db.ProxyTokens.userId eq userId }
            .map { "cp:tok:${it[org.claudeproxy.db.ProxyTokens.tokenHash]}" } +
            org.claudeproxy.db.RoutingTokens.selectAll().where { org.claudeproxy.db.RoutingTokens.userId eq userId }
                .map { "cp:rtok:${it[org.claudeproxy.db.RoutingTokens.tokenHash]}" }
    }

    fun delete(userId: Int) {
        val cacheKeys = tokenCacheKeys(userId)
        transaction {
            // clear rows that reference the user (Postgres enforces these FKs)
            org.claudeproxy.db.ProxyTokens.deleteWhere { org.claudeproxy.db.ProxyTokens.userId eq userId }
            org.claudeproxy.db.RoutingTokens.deleteWhere { org.claudeproxy.db.RoutingTokens.userId eq userId }
            org.claudeproxy.db.UsageEvents.update({ org.claudeproxy.db.UsageEvents.userId eq userId }) {
                it[org.claudeproxy.db.UsageEvents.userId] = null
            }
            McpUsageRepo.detachUser(userId)
            // delete this user's personal accounts (their secrets/limits/usage cascade off Accounts)
            org.claudeproxy.db.Accounts.deleteWhere { org.claudeproxy.db.Accounts.ownerId eq userId }
            // detach global accounts this user created so the users row can be removed (Postgres FK)
            org.claudeproxy.db.Accounts.update({ org.claudeproxy.db.Accounts.createdBy eq userId }) {
                it[org.claudeproxy.db.Accounts.createdBy] = null
            }
            UserGroupAccess.deleteWhere { UserGroupAccess.userId eq userId }
            UserRoles.deleteWhere { UserRoles.userId eq userId }
            Users.deleteWhere { Users.id eq userId }
        }
        // a deleted user's tokens are gone from the DB; their cached resolves must go too
        cacheKeys.forEach(MemoryCache::evict)
    }

    private fun setRoles(userId: Int, roleNames: List<String>) {
        UserRoles.deleteWhere { UserRoles.userId eq userId }
        roleNames.forEach { rn ->
            val rid = Roles.selectAll().where { Roles.name eq rn }.firstOrNull()?.get(Roles.id) ?: return@forEach
            UserRoles.insert {
                it[UserRoles.userId] = userId
                it[roleId] = rid
            }
        }
    }
}
