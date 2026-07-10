package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.ModelPrices
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

@Serializable
data class ModelPriceDto(
    val pattern: String,
    val inputPrice: Double,
    val outputPrice: Double,
    val cacheReadPrice: Double,
    val cacheWritePrice: Double,
)

/**
 * Per-model pricing in USD per 1M tokens, split by token kind (input, output, cache read,
 * cache write). A request's model id is matched against each pattern by case-insensitive
 * substring; the longest matching pattern wins. Defaults use Anthropic list prices.
 */
object ModelPriceRepo {

    @Volatile
    private var cache: List<ModelPriceDto>? = null

    fun seedDefaults() {
        transaction {
            // pattern to (input, output, cacheRead, cacheWrite) in USD per 1M tokens.
            // Anthropic list prices: cache-read ≈ 0.1× input, cache-write ≈ 1.25× input (5-min TTL).
            listOf(
                Quad("haiku", 1.0, 5.0, 0.1, 1.25),
                Quad("sonnet", 3.0, 15.0, 0.3, 3.75),
                Quad("opus", 5.0, 25.0, 0.5, 6.25),
            ).forEach { p ->
                ModelPrices.insertIgnore {
                    it[pattern] = p.pattern
                    it[inputPrice] = p.input
                    it[outputPrice] = p.output
                    it[cacheReadPrice] = p.cacheRead
                    it[cacheWritePrice] = p.cacheWrite
                }
            }
            // One-time self-heal: earlier builds seeded Opus at the retired 4.1 list price
            // (15/75/1.5/18.75) — exactly 3× the current Opus 4.x price — and insertIgnore never
            // overwrote it. Correct only rows still holding those exact stale values, so an admin's
            // custom price is never clobbered. Idempotent: after the fix the row no longer matches.
            ModelPrices.update({
                (ModelPrices.pattern eq "opus") and (ModelPrices.inputPrice eq 15.0) and
                    (ModelPrices.outputPrice eq 75.0) and (ModelPrices.cacheReadPrice eq 1.5) and
                    (ModelPrices.cacheWritePrice eq 18.75)
            }) {
                it[inputPrice] = 5.0
                it[outputPrice] = 25.0
                it[cacheReadPrice] = 0.5
                it[cacheWritePrice] = 6.25
            }
        }
        invalidate()
    }

    fun list(): List<ModelPriceDto> {
        cache?.let { return it }
        val loaded = transaction {
            ModelPrices.selectAll().map {
                ModelPriceDto(
                    it[ModelPrices.pattern], it[ModelPrices.inputPrice], it[ModelPrices.outputPrice],
                    it[ModelPrices.cacheReadPrice], it[ModelPrices.cacheWritePrice],
                )
            }
        }.sortedByDescending { it.pattern.length }
        cache = loaded
        return loaded
    }

    fun set(pattern: String, input: Double, output: Double, cacheRead: Double, cacheWrite: Double) {
        transaction {
            ModelPrices.upsert {
                it[ModelPrices.pattern] = pattern.trim().lowercase()
                it[inputPrice] = input
                it[outputPrice] = output
                it[cacheReadPrice] = cacheRead
                it[cacheWritePrice] = cacheWrite
            }
        }
        invalidate()
    }

    fun delete(pattern: String) {
        transaction { ModelPrices.deleteWhere { ModelPrices.pattern eq pattern } }
        invalidate()
    }

    private fun priceFor(model: String?): ModelPriceDto? {
        if (model.isNullOrBlank()) return null
        val m = model.lowercase()
        return list().firstOrNull { m.contains(it.pattern) }
    }

    /** USD cost from the full token breakdown, each kind priced separately (per 1M). */
    fun costOf(model: String?, input: Long, cacheRead: Long, cacheWrite: Long, output: Long): Double {
        val p = priceFor(model) ?: return 0.0
        return (input * p.inputPrice + output * p.outputPrice +
            cacheRead * p.cacheReadPrice + cacheWrite * p.cacheWritePrice) / 1_000_000.0
    }

    private fun invalidate() { cache = null }

    private data class Quad(val pattern: String, val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)
}
