package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.Accounts
import org.claudeproxy.db.UsageEvents
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
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
    val dirtyTokens: Long,
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
    val dirtyTokens: Long,
)

/** [requests, input, output, dirty] bundle. */
class Totals(val requests: Long = 0, val input: Long = 0, val output: Long = 0, val dirty: Long = 0) {
    val clean: Long get() = input + output
}

object UsageRepo {

    fun record(accountId: Int, userId: Int?, input: Long, output: Long, status: Int, model: String?) {
        val coeff = ModelCoeffRepo.coeffFor(model)
        val dirty = Math.round((input + output) * coeff)
        runCatching {
            transaction {
                UsageEvents.insert {
                    it[UsageEvents.accountId] = accountId
                    it[UsageEvents.userId] = userId
                    it[ts] = Instant.now()
                    it[inputTokens] = input
                    it[outputTokens] = output
                    it[dirtyTokens] = dirty
                    it[httpStatus] = status
                    it[UsageEvents.model] = model
                }
            }
        }
    }

    /** [clean, dirty] tokens a user has spent since [since]. */
    fun userTotalsSince(userId: Int, since: Instant): LongArray = transaction {
        var clean = 0L; var dirty = 0L
        UsageEvents.selectAll()
            .where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq since) }
            .forEach { clean += it[UsageEvents.inputTokens] + it[UsageEvents.outputTokens]; dirty += it[UsageEvents.dirtyTokens] }
        longArrayOf(clean, dirty)
    }

    fun totalsPerAccount(): Map<Int, Totals> = transaction {
        val acc = HashMap<Int, LongArray>()
        UsageEvents.selectAll().forEach { row ->
            val a = acc.getOrPut(row[UsageEvents.accountId]) { LongArray(4) }
            a[0] += 1; a[1] += row[UsageEvents.inputTokens]; a[2] += row[UsageEvents.outputTokens]; a[3] += row[UsageEvents.dirtyTokens]
        }
        acc.mapValues { (_, a) -> Totals(a[0], a[1], a[2], a[3]) }
    }

    fun poolTotals(): Totals = transaction {
        val a = LongArray(4)
        UsageEvents.selectAll().forEach { row ->
            a[0] += 1; a[1] += row[UsageEvents.inputTokens]; a[2] += row[UsageEvents.outputTokens]; a[3] += row[UsageEvents.dirtyTokens]
        }
        Totals(a[0], a[1], a[2], a[3])
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
                    dirtyTokens = row[UsageEvents.dirtyTokens],
                    httpStatus = row[UsageEvents.httpStatus],
                    model = row[UsageEvents.model],
                )
            }
    }

    fun summarySince(since: Instant): List<UsageSummaryDto> = transaction {
        val names = Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.name] }
        val acc = HashMap<Int, LongArray>() // [requests, input, output, dirty]
        UsageEvents.selectAll().where { UsageEvents.ts greaterEq since }.forEach { row ->
            val aid = row[UsageEvents.accountId]
            val a = acc.getOrPut(aid) { LongArray(4) }
            a[0] += 1; a[1] += row[UsageEvents.inputTokens]; a[2] += row[UsageEvents.outputTokens]; a[3] += row[UsageEvents.dirtyTokens]
        }
        acc.map { (aid, a) -> UsageSummaryDto(aid, names[aid], a[0], a[1], a[2], a[3]) }
            .sortedByDescending { it.requests }
    }
}
