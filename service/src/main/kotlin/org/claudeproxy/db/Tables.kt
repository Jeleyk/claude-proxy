package org.claudeproxy.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 256)
    val enabled = bool("enabled").default(true)
    // optional per-day spend limit in USD. null = unlimited.
    val dailyCostLimit = double("daily_cost_limit").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Simple key/value settings store (admin-tunable). */
object Settings : Table("settings") {
    val key = varchar("key", 64)
    val value = varchar("value", 256)
    override val primaryKey = PrimaryKey(key)
}

/** Per-model pricing ($ per million tokens), matched by substring of the request model id. */
object ModelPrices : Table("model_prices") {
    val pattern = varchar("pattern", 64)   // e.g. "haiku", "sonnet", "opus"
    val inputPrice = double("input_price").default(0.0)             // USD / 1M input tokens
    val outputPrice = double("output_price").default(0.0)          // USD / 1M output tokens
    val cacheReadPrice = double("cache_read_price").default(0.0)    // USD / 1M cache-read tokens
    val cacheWritePrice = double("cache_write_price").default(0.0)  // USD / 1M cache-write tokens
    override val primaryKey = PrimaryKey(pattern)
}

object Roles : Table("roles") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 64).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object RolePermissions : Table("role_permissions") {
    val roleId = integer("role_id").references(Roles.id)
    val permission = varchar("permission", 64)
    override val primaryKey = PrimaryKey(roleId, permission)
}

object UserRoles : Table("user_roles") {
    val userId = integer("user_id").references(Users.id)
    val roleId = integer("role_id").references(Roles.id)
    override val primaryKey = PrimaryKey(userId, roleId)
}

object ProxyTokens : Table("proxy_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val name = varchar("name", 128)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Named groups of upstream accounts, used to scope which users may use which accounts. */
object AccountGroups : Table("account_groups") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 128).uniqueIndex()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Grants a user access to all accounts in a group. */
object UserGroupAccess : Table("user_group_access") {
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val groupId = integer("group_id").references(AccountGroups.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, groupId)
}

object Accounts : Table("accounts") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 128)
    val type = varchar("type", 32)
    val groupId = integer("group_id").references(AccountGroups.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.SET_NULL).nullable()
    val priority = integer("priority").default(100)
    val threshold = double("threshold").default(0.9)
    val coefficient = double("coefficient").default(1.0)
    val enabled = bool("enabled").default(true)
    val health = varchar("health", 32).default("OK")
    val rateLimitedUntil = timestamp("rate_limited_until").nullable()
    // distinct per-account device/client identifier sent upstream
    val clientId = varchar("client_id", 64).nullable()
    // null = global (shared pool); otherwise a personal account owned by this user, tried
    // before the global pool and excluded from global statistics.
    val ownerId = integer("owner_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE).nullable()
    val createdBy = integer("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Encrypted credential blob (JSON) per account. */
object AccountSecrets : Table("account_secrets") {
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val cipherBlob = text("cipher_blob")
    override val primaryKey = PrimaryKey(accountId)
}

/** Live limit state per account per window, persisted so it survives restarts. */
object AccountLimits : Table("account_limits") {
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val windowKind = varchar("window_kind", 8)   // "5h" | "7d"
    val utilization = double("utilization").nullable()
    val remaining = double("remaining").nullable()
    val limitTotal = double("limit_total").nullable()
    val resetAt = timestamp("reset_at").nullable()
    val status = varchar("status", 32).default("UNKNOWN")
    val updatedAt = timestamp("updated_at").nullable()
    override val primaryKey = PrimaryKey(accountId, windowKind)
}

object UsageEvents : Table("usage_events") {
    val id = integer("id").autoIncrement()
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id).nullable()
    val ts = timestamp("ts")
    val inputTokens = long("input_tokens").default(0)          // base (non-cache) input
    val outputTokens = long("output_tokens").default(0)
    val cacheReadTokens = long("cache_read_tokens").default(0)
    val cacheWriteTokens = long("cache_write_tokens").default(0)
    // computed USD cost of this request from model pricing at record time
    val cost = double("cost").default(0.0)
    val httpStatus = integer("http_status").default(0)
    val model = varchar("model", 128).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Time series of observed window utilization (0..1) per account per window. */
object WindowSnapshots : Table("window_snapshots") {
    val id = long("id").autoIncrement()
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val windowKind = varchar("window_kind", 8)   // "5h" | "7d"
    val utilization = double("utilization")
    val ts = timestamp("ts")
    override val primaryKey = PrimaryKey(id)
    init { index(false, accountId, windowKind, ts) }
}

object OAuthAddSessions : Table("oauth_add_sessions") {
    val id = varchar("id", 64)          // state
    val pkceVerifier = varchar("pkce_verifier", 256)
    val createdBy = integer("created_by").references(Users.id).nullable()
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)
}

val ALL_TABLES = arrayOf(
    Users, Settings, ModelPrices, Roles, RolePermissions, UserRoles, ProxyTokens,
    AccountGroups, UserGroupAccess,
    Accounts, AccountSecrets, AccountLimits, UsageEvents, WindowSnapshots, OAuthAddSessions,
)
