package org.claudeproxy.accounts

import org.claudeproxy.db.AccountLimits
import org.claudeproxy.db.AccountSecrets
import org.claudeproxy.db.Accounts
import org.claudeproxy.model.AccountDto
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** Full config + secret + live limit state for one account, as held in the pool. */
data class AccountRuntime(
    val id: Int,
    val name: String,
    val type: AccountType,
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    val health: AccountHealth,
    val secret: AccountSecret,
    val limit: LimitState,
) {
    fun toDto(createdAt: String): AccountDto {
        val usage = limit.usageFraction()
        val effRemaining = usage?.let { coefficient * (1.0 - it) }
            ?: if (type == AccountType.API_KEY) coefficient else null
        return AccountDto(
            id = id, name = name, type = type.name, priority = priority,
            threshold = threshold, coefficient = coefficient, enabled = enabled,
            health = health.name,
            usageFraction = usage,
            remaining = limit.remaining, limitTotal = limit.limitTotal,
            resetAt = limit.resetAt?.toString(), status = limit.status.name.takeIf { limit.status != LimitStatus.UNKNOWN },
            rateLimitedUntil = limit.rateLimitedUntil?.toString(),
            effectiveRemaining = effRemaining,
            createdAt = createdAt,
        )
    }
}

object AccountRepo {

    fun loadAll(): List<Pair<AccountRuntime, Instant>> = transaction {
        Accounts.selectAll().map { row ->
            val id = row[Accounts.id]
            val secretBlob = AccountSecrets.selectAll().where { AccountSecrets.accountId eq id }
                .firstOrNull()?.get(AccountSecrets.cipherBlob)
            val secret = secretBlob?.let { Secrets.decode(it) } ?: AccountSecret()
            val limitRow = AccountLimits.selectAll().where { AccountLimits.accountId eq id }.firstOrNull()
            val limit = limitRow?.let {
                LimitState(
                    remaining = it[AccountLimits.remaining],
                    limitTotal = it[AccountLimits.limitTotal],
                    resetAt = it[AccountLimits.resetAt],
                    status = runCatching { LimitStatus.valueOf(it[AccountLimits.status]) }.getOrDefault(LimitStatus.UNKNOWN),
                    rateLimitedUntil = it[AccountLimits.rateLimitedUntil],
                    updatedAt = it[AccountLimits.updatedAt],
                )
            } ?: LimitState()
            val rt = AccountRuntime(
                id = id,
                name = row[Accounts.name],
                type = AccountType.fromString(row[Accounts.type]) ?: AccountType.API_KEY,
                priority = row[Accounts.priority],
                threshold = row[Accounts.threshold],
                coefficient = row[Accounts.coefficient],
                enabled = row[Accounts.enabled],
                health = runCatching { AccountHealth.valueOf(row[Accounts.health]) }.getOrDefault(AccountHealth.OK),
                secret = secret,
                limit = limit,
            )
            rt to row[Accounts.createdAt]
        }
    }

    fun createdAtOf(id: Int): Instant? = transaction {
        Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()?.get(Accounts.createdAt)
    }

    fun createdAtMap(): Map<Int, Instant> = transaction {
        Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.createdAt] }
    }

    fun create(
        name: String, type: AccountType, priority: Int, threshold: Double, coefficient: Double,
        secret: AccountSecret, createdBy: Int?,
    ): Int = transaction {
        val id = Accounts.insert {
            it[Accounts.name] = name
            it[Accounts.type] = type.name
            it[Accounts.priority] = priority
            it[Accounts.threshold] = threshold
            it[Accounts.coefficient] = coefficient
            it[enabled] = true
            it[health] = AccountHealth.OK.name
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
        id: Int, name: String?, priority: Int?, threshold: Double?, coefficient: Double?, enabled: Boolean?,
    ) = transaction {
        Accounts.update({ Accounts.id eq id }) {
            if (name != null) it[Accounts.name] = name
            if (priority != null) it[Accounts.priority] = priority
            if (threshold != null) it[Accounts.threshold] = threshold
            if (coefficient != null) it[Accounts.coefficient] = coefficient
            if (enabled != null) it[Accounts.enabled] = enabled
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
        AccountLimits.replace {
            it[accountId] = id
            it[remaining] = limit.remaining
            it[limitTotal] = limit.limitTotal
            it[resetAt] = limit.resetAt
            it[status] = limit.status.name
            it[rateLimitedUntil] = limit.rateLimitedUntil
            it[updatedAt] = limit.updatedAt ?: Instant.now()
        }
    }
}
