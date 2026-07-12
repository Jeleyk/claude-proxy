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

/** One (account, model, day) bucket with the four token kinds kept separate. */
@Serializable
data class TokenBucketDto(
    val accountId: Int, val model: String?, val date: String,
    val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long,
)

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

    /** Ids of personal (owner-scoped) accounts; their usage is excluded from global stats. */
    private fun personalAccountIds(): Set<Int> =
        Accounts.selectAll().mapNotNull { row -> row[Accounts.id].takeIf { row[Accounts.ownerId] != null } }.toSet()

    /**
     * Totals for one user. [globalOnly] excludes usage that went through the user's own
     * personal accounts — used for the daily-limit check, which governs only shared-pool spend.
     */
    fun userTotals(userId: Int, since: Instant? = null, globalOnly: Boolean = false): Totals = transaction {
        val personal = if (globalOnly) personalAccountIds() else emptySet()
        val q = if (since != null)
            UsageEvents.selectAll().where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq since) }
        else UsageEvents.selectAll().where { UsageEvents.userId eq userId }
        accumulate(if (personal.isEmpty()) q else q.filter { it[UsageEvents.accountId] !in personal })
    }

    /** Per-account totals for a specific set of accounts (used by the personal "My Accounts" view). */
    fun totalsForAccounts(ids: Set<Int>): Map<Int, Totals> = transaction {
        if (ids.isEmpty()) return@transaction emptyMap()
        val acc = HashMap<Int, MutableList<ResultRow>>()
        UsageEvents.selectAll().where { UsageEvents.accountId inList ids }
            .forEach { acc.getOrPut(it[UsageEvents.accountId]) { mutableListOf() }.add(it) }
        acc.mapValues { (_, rows) -> accumulate(rows) }
    }

    private fun perModelOf(rows: Iterable<ResultRow>): List<ModelUsageDto> {
        val acc = HashMap<String?, DoubleArray>()
        rows.forEach { row ->
            val a = acc.getOrPut(row[UsageEvents.model]) { DoubleArray(3) }
            a[0] += 1
            a[1] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            a[2] += row[UsageEvents.cost]
        }
        return acc.map { (m, a) -> ModelUsageDto(m, a[0].toLong(), a[1].toLong(), a[2]) }.sortedByDescending { it.cost }
    }

    /** Per-model breakdown for a single user; [since] limits to events at/after that instant. */
    fun userPerModel(userId: Int, since: Instant? = null): List<ModelUsageDto> = transaction {
        val q = if (since != null)
            UsageEvents.selectAll().where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq since) }
        else UsageEvents.selectAll().where { UsageEvents.userId eq userId }
        perModelOf(q)
    }

    /** Pool-wide per-model breakdown (excludes personal accounts); [since] limits the window. */
    fun perModel(since: Instant? = null): List<ModelUsageDto> = transaction {
        val personal = personalAccountIds()
        val q = if (since != null) UsageEvents.selectAll().where { UsageEvents.ts greaterEq since } else UsageEvents.selectAll()
        perModelOf(if (personal.isEmpty()) q else q.filter { it[UsageEvents.accountId] !in personal })
    }

    fun recentForUser(userId: Int, limit: Int = 100): List<UsageEventDto> = transaction {
        val names = accountNames()
        UsageEvents.selectAll().where { UsageEvents.userId eq userId }
            .orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun totalsPerAccount(): Map<Int, Totals> = transaction {
        val personal = personalAccountIds()
        val acc = HashMap<Int, MutableList<ResultRow>>()
        UsageEvents.selectAll().forEach {
            val aid = it[UsageEvents.accountId]
            if (aid in personal) return@forEach
            acc.getOrPut(aid) { mutableListOf() }.add(it)
        }
        acc.mapValues { (_, rows) -> accumulate(rows) }
    }

    fun poolTotals(): Totals = transaction {
        val personal = personalAccountIds()
        accumulate(if (personal.isEmpty()) UsageEvents.selectAll()
                   else UsageEvents.selectAll().filter { it[UsageEvents.accountId] !in personal })
    }

    /** Daily (account, day) buckets in [start, end), bucketed by UTC date. Excludes personal accounts. */
    fun dailyBuckets(start: Instant, end: Instant): List<DailyBucketDto> = transaction {
        val personal = personalAccountIds()
        val acc = HashMap<Pair<Int, String>, DoubleArray>() // (accountId, date) -> [cost, requests, tokens]
        UsageEvents.selectAll()
            .where { (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (row[UsageEvents.accountId] in personal) return@forEach
                val date = row[UsageEvents.ts].atZone(ZoneOffset.UTC).toLocalDate().toString()
                val a = acc.getOrPut(row[UsageEvents.accountId] to date) { DoubleArray(3) }
                a[0] += row[UsageEvents.cost]
                a[1] += 1
                a[2] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> DailyBucketDto(k.first, k.second, a[0], a[1].toLong(), a[2].toLong()) }
    }

    /** Daily (account, day) buckets for one user's own usage in [start, end), across ALL accounts (incl. personal). */
    fun dailyBucketsForUser(userId: Int, start: Instant, end: Instant): List<DailyBucketDto> = transaction {
        val acc = HashMap<Pair<Int, String>, DoubleArray>() // (accountId, date) -> [cost, requests, tokens]
        UsageEvents.selectAll()
            .where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                val date = row[UsageEvents.ts].atZone(ZoneOffset.UTC).toLocalDate().toString()
                val a = acc.getOrPut(row[UsageEvents.accountId] to date) { DoubleArray(3) }
                a[0] += row[UsageEvents.cost]
                a[1] += 1
                a[2] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> DailyBucketDto(k.first, k.second, a[0], a[1].toLong(), a[2].toLong()) }
    }

    /** Daily (account, model, day) token buckets for one user's own usage in [start, end), across ALL accounts (incl. personal). */
    fun tokenBucketsForUser(userId: Int, start: Instant, end: Instant): List<TokenBucketDto> = transaction {
        val acc = HashMap<Triple<Int, String?, String>, LongArray>() // (accountId, model, date) -> [in, out, cacheRead, cacheWrite]
        UsageEvents.selectAll()
            .where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                val date = row[UsageEvents.ts].atZone(ZoneOffset.UTC).toLocalDate().toString()
                val a = acc.getOrPut(Triple(row[UsageEvents.accountId], row[UsageEvents.model], date)) { LongArray(4) }
                a[0] += row[UsageEvents.inputTokens]
                a[1] += row[UsageEvents.outputTokens]
                a[2] += row[UsageEvents.cacheReadTokens]
                a[3] += row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> TokenBucketDto(k.first, k.second, k.third, a[0], a[1], a[2], a[3]) }
    }

    /** Daily (account, model, day) token buckets in [start, end), bucketed by UTC date, kinds kept apart. Excludes personal accounts. */
    fun tokenBuckets(start: Instant, end: Instant): List<TokenBucketDto> = transaction {
        val personal = personalAccountIds()
        val acc = HashMap<Triple<Int, String?, String>, LongArray>() // (accountId, model, date) -> [in, out, cacheRead, cacheWrite]
        UsageEvents.selectAll()
            .where { (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (row[UsageEvents.accountId] in personal) return@forEach
                val date = row[UsageEvents.ts].atZone(ZoneOffset.UTC).toLocalDate().toString()
                val a = acc.getOrPut(Triple(row[UsageEvents.accountId], row[UsageEvents.model], date)) { LongArray(4) }
                a[0] += row[UsageEvents.inputTokens]
                a[1] += row[UsageEvents.outputTokens]
                a[2] += row[UsageEvents.cacheReadTokens]
                a[3] += row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> TokenBucketDto(k.first, k.second, k.third, a[0], a[1], a[2], a[3]) }
    }

    fun clearAll(): Int = transaction { UsageEvents.deleteAll() }
    fun clearUser(userId: Int): Int = transaction { UsageEvents.deleteWhere { UsageEvents.userId eq userId } }

    fun recent(limit: Int = 200): List<UsageEventDto> = transaction {
        val names = accountNames()
        val personal = personalAccountIds()
        val base = UsageEvents.selectAll()
        val q = if (personal.isEmpty()) base else base.where { UsageEvents.accountId notInList personal }
        q.orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun summarySince(since: Instant): List<UsageSummaryDto> = transaction {
        val names = accountNames()
        val personal = personalAccountIds()
        val acc = HashMap<Int, MutableList<ResultRow>>()
        UsageEvents.selectAll().where { UsageEvents.ts greaterEq since }.forEach {
            val aid = it[UsageEvents.accountId]
            if (aid in personal) return@forEach
            acc.getOrPut(aid) { mutableListOf() }.add(it)
        }
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
