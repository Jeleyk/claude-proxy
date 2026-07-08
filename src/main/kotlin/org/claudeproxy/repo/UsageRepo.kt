package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.Accounts
import org.claudeproxy.db.UsageEvents
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

@Serializable
data class UsageEventDto(
    val id: Int,
    val accountId: Int,
    val accountName: String?,
    val userId: Int?,
    val ts: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val httpStatus: Int,
    val model: String?,
)

@Serializable
data class UsageSummaryDto(
    val accountId: Int,
    val accountName: String?,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
)

object UsageRepo {

    fun record(accountId: Int, userId: Int?, input: Long, output: Long, status: Int, model: String?) {
        runCatching {
            transaction {
                UsageEvents.insert {
                    it[UsageEvents.accountId] = accountId
                    it[UsageEvents.userId] = userId
                    it[ts] = Instant.now()
                    it[inputTokens] = input
                    it[outputTokens] = output
                    it[httpStatus] = status
                    it[UsageEvents.model] = model
                }
            }
        }
    }

    fun recent(limit: Int = 200): List<UsageEventDto> = transaction {
        val names = Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.name] }
        UsageEvents.selectAll()
            .orderBy(UsageEvents.ts, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                val aid = row[UsageEvents.accountId]
                UsageEventDto(
                    id = row[UsageEvents.id],
                    accountId = aid,
                    accountName = names[aid],
                    userId = row[UsageEvents.userId],
                    ts = row[UsageEvents.ts].toString(),
                    inputTokens = row[UsageEvents.inputTokens],
                    outputTokens = row[UsageEvents.outputTokens],
                    httpStatus = row[UsageEvents.httpStatus],
                    model = row[UsageEvents.model],
                )
            }
    }

    /** Aggregate usage since a given instant, grouped per account (computed in-app for simplicity). */
    fun summarySince(since: Instant): List<UsageSummaryDto> = transaction {
        val names = Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.name] }
        val acc = HashMap<Int, LongArray>() // [requests, input, output]
        UsageEvents.selectAll().where { UsageEvents.ts greaterEq since }.forEach { row ->
            val aid = row[UsageEvents.accountId]
            val a = acc.getOrPut(aid) { LongArray(3) }
            a[0] += 1
            a[1] += row[UsageEvents.inputTokens]
            a[2] += row[UsageEvents.outputTokens]
        }
        acc.map { (aid, a) -> UsageSummaryDto(aid, names[aid], a[0], a[1], a[2]) }
            .sortedByDescending { it.requests }
    }
}
