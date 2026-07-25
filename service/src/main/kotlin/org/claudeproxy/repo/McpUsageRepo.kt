package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.McpToolCalls
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** One (tool, day) bucket of a user's MCP calls, bucketed by the viewer's local date. */
@Serializable
data class McpDailyBucketDto(val toolName: String, val date: String, val calls: Long)

object McpUsageRepo {

    /** Persist one request's MCP tool-call counts (one row per tool). Failures never propagate. */
    fun record(userId: Int?, tokenId: Int?, calls: Map<String, Long>) {
        if (calls.isEmpty()) return
        runCatching {
            transaction {
                val now = Instant.now()
                calls.forEach { (tool, n) ->
                    if (n <= 0) return@forEach
                    McpToolCalls.insert {
                        it[McpToolCalls.userId] = userId
                        it[McpToolCalls.tokenId] = tokenId
                        it[ts] = now
                        it[toolName] = tool.take(256)
                        it[McpToolCalls.calls] = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                }
            }
        }
    }

    /** Daily (tool, day) call buckets for one user in [start, end), bucketed by date in [zone]. */
    fun dailyBucketsForUser(
        userId: Int, start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC,
    ): List<McpDailyBucketDto> = transaction {
        val acc = HashMap<Pair<String, String>, Long>() // (tool, date) -> calls
        McpToolCalls.selectAll()
            .where { (McpToolCalls.userId eq userId) and (McpToolCalls.ts greaterEq start) and (McpToolCalls.ts less end) }
            .forEach { row ->
                val date = row[McpToolCalls.ts].atZone(zone).toLocalDate().toString()
                val key = row[McpToolCalls.toolName] to date
                acc[key] = (acc[key] ?: 0L) + row[McpToolCalls.calls]
            }
        acc.map { (k, n) -> McpDailyBucketDto(k.first, k.second, n) }
    }

    /** Detach a deleted user's rows (history kept, mirrors UsageEvents handling). */
    fun detachUser(userId: Int) {
        McpToolCalls.update({ McpToolCalls.userId eq userId }) { it[McpToolCalls.userId] = null }
    }

    fun clearAll(): Int = transaction { McpToolCalls.deleteAll() }
    fun clearUser(userId: Int): Int = transaction { McpToolCalls.deleteWhere { McpToolCalls.userId eq userId } }
}
