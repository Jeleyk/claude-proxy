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
    // optional per-day spend limit in USD for the OpenAI/Anthropic routing gateways. null = unlimited.
    val dailyRoutingCostLimit = double("daily_routing_cost_limit").nullable()
    // routing preference: true = try the global pool before this user's personal accounts.
    val preferGlobalPool = bool("prefer_global_pool").default(false)
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

/**
 * Tokens for the OpenAI/Anthropic API routing gateways (`cxr_...`). Same shape as
 * [ProxyTokens] but a distinct namespace: routing spend is metered against a separate
 * per-user daily limit and gated by the `ROUTING_USE` permission.
 */
object RoutingTokens : Table("routing_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val name = varchar("name", 128)
    // optional static system prompt injected by the routing gateways right after the mandatory
    // Claude Code block — i.e. ahead of (higher priority than) any client-supplied system.
    val systemPrompt = text("system_prompt").nullable()
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
    // opt-in fallback: when every account is over its threshold, only accounts with this flag
    // stay usable past their threshold; others drop out of selection until their window resets.
    val overThreshold = bool("over_threshold").default(false)
    val health = varchar("health", 32).default("OK")
    val rateLimitedUntil = timestamp("rate_limited_until").nullable()
    // distinct per-account device fingerprint (64-hex) substituted into the request body's
    // metadata.user_id.device_id. DB column keeps its historical name `client_id`.
    val deviceId = varchar("client_id", 64).nullable()
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
    // datapath that produced this event: "proxy" (Claude Code) or "routing" (OpenAI/Anthropic
    // API gateways). Metered against separate per-user daily limits; stats show both together.
    val sourceCol = varchar("source", 16).default("proxy")
    // inbound token that authenticated the request. Deliberately NOT an FK: deleting a token
    // must keep its usage history. `source` picks the namespace ("proxy" → proxy_tokens.id,
    // "routing" → routing_tokens.id); null = pre-migration rows or token-less attempts.
    val tokenId = integer("token_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * MCP tool invocations observed on the Claude Code datapath: one row per (request, tool),
 * counted by the gateway from `tool_use` content blocks named `mcp__server__tool` in the
 * response. Counts only — token/cost attribution stays in [UsageEvents].
 */
object McpToolCalls : Table("mcp_tool_calls") {
    val id = long("id").autoIncrement()
    val ts = timestamp("ts")
    val userId = integer("user_id").references(Users.id).nullable()
    // inbound proxy token. Deliberately NOT an FK: deleting a token keeps its call history.
    val tokenId = integer("token_id").nullable()
    val toolName = varchar("tool_name", 256)
    val calls = integer("calls").default(1)
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId, ts) }
}

/** Time series of observed window utilization (0..1) per account per window. */
object WindowSnapshots : Table("window_snapshots") {
    val id = long("id").autoIncrement()
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val windowKind = varchar("window_kind", 8)   // "5h" | "7d"
    val utilization = double("utilization")
    // coefficient in effect when this point was recorded, FROZEN at write time so the
    // ×coef-weighted charts keep their historical weighting even after an account's coefficient
    // later changes. Nullable only for pre-migration rows; backfilled once from the account's
    // current coefficient at startup (see Db.migrateWindowSnapshotCoefficient), and always
    // written for new rows.
    val coefficient = double("coefficient").nullable()
    val ts = timestamp("ts")
    override val primaryKey = PrimaryKey(id)
    init { index(false, accountId, windowKind, ts) }
}

/**
 * Session-id rotation state. To stop Anthropic correlating one client session-id across
 * several accounts, the first account to use a given client (origin) session-id keeps it
 * unchanged; every other account gets a stable, distinct replacement.
 */
object SessionOwners : Table("session_owner") {
    // the client's original X-Claude-Code-Session-Id
    val origin = varchar("origin", 64)
    // the account that first used this origin — it presents the origin id unchanged
    val accountId = integer("account_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(origin)
}

object SessionMap : Table("session_map") {
    val origin = varchar("origin", 64)
    val accountId = integer("account_id")
    // session-id this account presents upstream: == origin for the owner, a fresh uuid otherwise
    val replaced = varchar("replaced", 64)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(origin, accountId)
}

object OAuthAddSessions : Table("oauth_add_sessions") {
    val id = varchar("id", 64)          // state
    val pkceVerifier = varchar("pkce_verifier", 256)
    val createdBy = integer("created_by").references(Users.id).nullable()
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)
}

val ALL_TABLES = arrayOf(
    Users, Settings, ModelPrices, Roles, RolePermissions, UserRoles, ProxyTokens, RoutingTokens,
    AccountGroups, UserGroupAccess,
    Accounts, AccountSecrets, AccountLimits, UsageEvents, McpToolCalls, WindowSnapshots,
    OAuthAddSessions, SessionOwners, SessionMap,
)
