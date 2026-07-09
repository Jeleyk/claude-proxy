package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.Accounts
import org.claudeproxy.db.UsageEvents
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class UsageEventDto(
    val id: Int, val accountId: Int, val accountName: String?, val userId: Int?, val ts: String,
    val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long,
    val cost: Double, val httpStatus: Int, val model: String?,
)

@Serializable
data class UsageSummaryDto(
    val accountId: Int, val accountName: String?, val requests: Long,
    val inputTokens: Long, val outputTokens: Long, val cost: Double,
)

@Serializable
data class ModelUsageDto(val model: String?, val requests: Long, val cleanTokens: Long, val cost: Double)

/** One (account, day) bucket of usage. */
@Serializable
data class DailyBucketDto(val accountId: Int, val date: String, val cost: Double, val requests: Long, val tokens: Long)

/** Token counters + USD cost bundle. */
class Totals(
    val requests: Long = 0, val input: Long = 0, val output: Long = 0,
    val cacheRead: Long = 0, val cacheWrite: Long = 0, val cost: Double = 0.0,
) {
    val clean: Long get() = input + output + cacheRead + cacheWrite
}

object UsageRepo {

    fun record(
        accountId: Int, userId: Int?, input: Long, cacheRead: Long, cacheWrite: Long, output: Long,
        status: Int, model: String?,
    ) {
        val cost = ModelPriceRepo.costOf(model, input, cacheRead, cacheWrite, output)
        runCatching {
            transaction {
                UsageEvents.insert {
                    it[UsageEvents.accountId] = accountId
                    it[UsageEvents.userId] = userId
                    it[ts] = Instant.now()
                    it[inputTokens] = input
                    it[outputTokens] = output
                    it[cacheReadTokens] = cacheRead
                    it[cacheWriteTokens] = cacheWrite
                    it[UsageEvents.cost] = cost
                    it[httpStatus] = status
                    it[UsageEvents.model] = model
                }
            }
        }
    }

    private fun accumulate(rows: Iterable<ResultRow>): Totals {
        var req = 0L; var input = 0L; var output = 0L; var cr = 0L; var cw = 0L; var cost = 0.0
        rows.forEach {
            req += 1; input += it[UsageEvents.inputTokens]; output += it[UsageEvents.outputTokens]
            cr += it[UsageEvents.cacheReadTokens]; cw += it[UsageEvents.cacheWriteTokens]; cost += it[UsageEvents.cost]
        }
        return Totals(req, input, output, cr, cw, cost)
    }

    fun userTotals(userId: Int, since: Instant? = null): Totals = transaction {
        val q = if (since != null)
            UsageEvents.selectAll().where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq since) }
        else UsageEvents.selectAll().where { UsageEvents.userId eq userId }
        accumulate(q)
    }

    fun userPerModel(userId: Int): List<ModelUsageDto> = transaction {
        val acc = HashMap<String?, DoubleArray>()
        UsageEvents.selectAll().where { UsageEvents.userId eq userId }.forEach { row ->
            val a = acc.getOrPut(row[UsageEvents.model]) { DoubleArray(3) }
            a[0] += 1
            a[1] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            a[2] += row[UsageEvents.cost]
        }
        acc.map { (m, a) -> ModelUsageDto(m, a[0].toLong(), a[1].toLong(), a[2]) }.sortedByDescending { it.cost }
    }

    fun recentForUser(userId: Int, limit: Int = 100): List<UsageEventDto> = transaction {
        val names = accountNames()
        UsageEvents.selectAll().where { UsageEvents.userId eq userId }
            .orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun totalsPerAccount(): Map<Int, Totals> = transaction {
        val acc = HashMap<Int, MutableList<ResultRow>>()
        UsageEvents.selectAll().forEach { acc.getOrPut(it[UsageEvents.accountId]) { mutableListOf() }.add(it) }
        acc.mapValues { (_, rows) -> accumulate(rows) }
    }

    fun poolTotals(): Totals = transaction { accumulate(UsageEvents.selectAll()) }

    /** Daily (account, day) buckets in [start, end), bucketed by UTC date. */
    fun dailyBuckets(start: Instant, end: Instant): List<DailyBucketDto> = transaction {
        val acc = HashMap<Pair<Int, String>, DoubleArray>() // (accountId, date) -> [cost, requests, tokens]
        UsageEvents.selectAll()
            .where { (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                val date = row[UsageEvents.ts].atZone(ZoneOffset.UTC).toLocalDate().toString()
                val a = acc.getOrPut(row[UsageEvents.accountId] to date) { DoubleArray(3) }
                a[0] += row[UsageEvents.cost]
                a[1] += 1
                a[2] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> DailyBucketDto(k.first, k.second, a[0], a[1].toLong(), a[2].toLong()) }
    }

    fun clearAll(): Int = transaction { UsageEvents.deleteAll() }
    fun clearUser(userId: Int): Int = transaction { UsageEvents.deleteWhere { UsageEvents.userId eq userId } }

    fun recent(limit: Int = 200): List<UsageEventDto> = transaction {
        val names = accountNames()
        UsageEvents.selectAll().orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun summarySince(since: Instant): List<UsageSummaryDto> = transaction {
        val names = accountNames()
        val acc = HashMap<Int, MutableList<ResultRow>>()
        UsageEvents.selectAll().where { UsageEvents.ts greaterEq since }.forEach { acc.getOrPut(it[UsageEvents.accountId]) { mutableListOf() }.add(it) }
        acc.map { (aid, rows) ->
            val t = accumulate(rows)
            UsageSummaryDto(aid, names[aid], t.requests, t.input, t.output, t.cost)
        }.sortedByDescending { it.requests }
    }

    private fun accountNames() = Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.name] }

    private fun ResultRow.toEventDto(names: Map<Int, String>): UsageEventDto {
        val aid = this[UsageEvents.accountId]
        return UsageEventDto(
            id = this[UsageEvents.id], accountId = aid, accountName = names[aid],
            userId = this[UsageEvents.userId], ts = this[UsageEvents.ts].toString(),
            inputTokens = this[UsageEvents.inputTokens], outputTokens = this[UsageEvents.outputTokens],
            cacheReadTokens = this[UsageEvents.cacheReadTokens], cacheWriteTokens = this[UsageEvents.cacheWriteTokens],
            cost = this[UsageEvents.cost], httpStatus = this[UsageEvents.httpStatus], model = this[UsageEvents.model],
        )
    }
}
