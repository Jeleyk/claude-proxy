package org.claudeproxy.accounts

import org.claudeproxy.db.AccountLimits
import org.claudeproxy.db.AccountSecrets
import org.claudeproxy.db.Accounts
import org.claudeproxy.model.AccountDto
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import org.claudeproxy.model.WindowLimitDto
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.claudeproxy.repo.Totals
import java.time.Instant

/** Full config + secret + live limit state for one account, as held in the pool. */
data class AccountRuntime(
    val id: Int,
    val name: String,
    val type: AccountType,
    val groupId: Int?,
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    val health: AccountHealth,
    val clientId: String?,
    val secret: AccountSecret,
    val limit: LimitState,
) {
    fun toDto(createdAt: String, counts: Totals): AccountDto {
        val usage = limit.usageFraction()
        val effRemaining = usage?.let { coefficient * (1.0 - it) }
            ?: if (type == AccountType.API_KEY) coefficient else null
        return AccountDto(
            id = id, name = name, type = type.name, groupId = groupId, priority = priority,
            threshold = threshold, coefficient = coefficient, enabled = enabled,
            health = health.name,
            fiveHour = limit.window(WindowKind.FIVE_HOUR)?.toDto(),
            weekly = limit.window(WindowKind.WEEKLY)?.toDto(),
            usageFraction = usage,
            rateLimitedUntil = limit.rateLimitedUntil?.toString(),
            effectiveRemaining = effRemaining,
            totalInputTokens = counts.input,
            totalOutputTokens = counts.output,
            totalDirtyTokens = counts.dirty,
            totalRequests = counts.requests,
            clientId = clientId,
            createdAt = createdAt,
        )
    }
}

private fun WindowLimit.toDto() = WindowLimitDto(
    usageFraction = usageFraction(),
    remaining = remaining,
    limitTotal = limitTotal,
    resetAt = resetAt?.toString(),
    status = status.name.takeIf { status != LimitStatus.UNKNOWN },
    updatedAt = updatedAt?.toString(),
)

object AccountRepo {

    fun loadAll(): List<Pair<AccountRuntime, Instant>> = transaction {
        Accounts.selectAll().map { row ->
            val id = row[Accounts.id]
            val secretBlob = AccountSecrets.selectAll().where { AccountSecrets.accountId eq id }
                .firstOrNull()?.get(AccountSecrets.cipherBlob)
            val secret = secretBlob?.let { Secrets.decode(it) } ?: AccountSecret()
            val windows = AccountLimits.selectAll().where { AccountLimits.accountId eq id }.mapNotNull { lr ->
                val kind = WindowKind.fromCode(lr[AccountLimits.windowKind]) ?: return@mapNotNull null
                kind to WindowLimit(
                    utilization = lr[AccountLimits.utilization],
                    remaining = lr[AccountLimits.remaining],
                    limitTotal = lr[AccountLimits.limitTotal],
                    resetAt = lr[AccountLimits.resetAt],
                    status = runCatching { LimitStatus.valueOf(lr[AccountLimits.status]) }.getOrDefault(LimitStatus.UNKNOWN),
                    updatedAt = lr[AccountLimits.updatedAt],
                )
            }.toMap()
            val limit = LimitState(windows = windows, rateLimitedUntil = row[Accounts.rateLimitedUntil])
            val rt = AccountRuntime(
                id = id,
                name = row[Accounts.name],
                type = AccountType.fromString(row[Accounts.type]) ?: AccountType.API_KEY,
                groupId = row[Accounts.groupId],
                priority = row[Accounts.priority],
                threshold = row[Accounts.threshold],
                coefficient = row[Accounts.coefficient],
                enabled = row[Accounts.enabled],
                health = runCatching { AccountHealth.valueOf(row[Accounts.health]) }.getOrDefault(AccountHealth.OK),
                clientId = row[Accounts.clientId],
                secret = secret,
                limit = limit,
            )
            rt to row[Accounts.createdAt]
        }
    }

    fun createdAtMap(): Map<Int, Instant> = transaction {
        Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.createdAt] }
    }

    fun create(
        name: String, type: AccountType, groupId: Int?, priority: Int, threshold: Double, coefficient: Double,
        secret: AccountSecret, createdBy: Int?, clientId: String? = java.util.UUID.randomUUID().toString(),
    ): Int = transaction {
        val id = Accounts.insert {
            it[Accounts.name] = name
            it[Accounts.type] = type.name
            it[Accounts.groupId] = groupId
            it[Accounts.priority] = priority
            it[Accounts.threshold] = threshold
            it[Accounts.coefficient] = coefficient
            it[enabled] = true
            it[health] = AccountHealth.OK.name
            it[Accounts.clientId] = clientId
            it[Accounts.createdBy] = createdBy
            it[createdAt] = Instant.now()
        }[Accounts.id]
        AccountSecrets.insert {
            it[accountId] = id
            it[cipherBlob] = Secrets.encode(secret)
        }
        id
    }

    fun updateConfig(
        id: Int, name: String?, groupId: Int?, priority: Int?, threshold: Double?, coefficient: Double?,
        enabled: Boolean?, clientId: String?, clearGroup: Boolean = false,
    ) = transaction {
        Accounts.update({ Accounts.id eq id }) {
            if (name != null) it[Accounts.name] = name
            if (clearGroup) it[Accounts.groupId] = null else if (groupId != null) it[Accounts.groupId] = groupId
            if (priority != null) it[Accounts.priority] = priority
            if (threshold != null) it[Accounts.threshold] = threshold
            if (coefficient != null) it[Accounts.coefficient] = coefficient
            if (enabled != null) it[Accounts.enabled] = enabled
            if (clientId != null) it[Accounts.clientId] = clientId
        }
    }

    fun updateSecret(id: Int, secret: AccountSecret) = transaction {
        AccountSecrets.replace {
            it[accountId] = id
            it[cipherBlob] = Secrets.encode(secret)
        }
    }

    fun updateHealth(id: Int, health: AccountHealth) = transaction {
        Accounts.update({ Accounts.id eq id }) { it[Accounts.health] = health.name }
    }

    fun delete(id: Int): Boolean = transaction {
        AccountLimits.deleteWhere { accountId eq id }
        AccountSecrets.deleteWhere { accountId eq id }
        Accounts.deleteWhere { Accounts.id eq id } > 0
    }

    fun persistLimit(id: Int, limit: LimitState) = transaction {
        limit.windows.forEach { (kind, w) ->
            AccountLimits.replace {
                it[accountId] = id
                it[windowKind] = kind.code
                it[utilization] = w.utilization
                it[remaining] = w.remaining
                it[limitTotal] = w.limitTotal
                it[resetAt] = w.resetAt
                it[status] = w.status.name
                it[updatedAt] = w.updatedAt ?: Instant.now()
            }
        }
        Accounts.update({ Accounts.id eq id }) {
            it[rateLimitedUntil] = limit.rateLimitedUntil
        }
    }
}
