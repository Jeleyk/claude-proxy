package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.ModelPrices
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

@Serializable
data class ModelPriceDto(val pattern: String, val inputPrice: Double, val outputPrice: Double)

/**
 * Per-model pricing in USD per 1M tokens. A request's model id is matched against each
 * pattern by case-insensitive substring; the longest matching pattern wins (so "opus"
 * matches "claude-opus-4-8"). Defaults use Anthropic list prices.
 */
object ModelPriceRepo {

    @Volatile
    private var cache: List<ModelPriceDto>? = null

    fun seedDefaults() {
        transaction {
            listOf(
                Triple("haiku", 1.0, 5.0),
                Triple("sonnet", 3.0, 15.0),
                Triple("opus", 15.0, 75.0),
            ).forEach { (p, i, o) ->
                ModelPrices.insertIgnore { it[pattern] = p; it[inputPrice] = i; it[outputPrice] = o }
            }
        }
        invalidate()
    }

    fun list(): List<ModelPriceDto> {
        cache?.let { return it }
        val loaded = transaction {
            ModelPrices.selectAll().map { ModelPriceDto(it[ModelPrices.pattern], it[ModelPrices.inputPrice], it[ModelPrices.outputPrice]) }
        }.sortedByDescending { it.pattern.length }
        cache = loaded
        return loaded
    }

    fun set(pattern: String, inputPrice: Double, outputPrice: Double) {
        transaction {
            ModelPrices.upsert {
                it[ModelPrices.pattern] = pattern.trim().lowercase()
                it[ModelPrices.inputPrice] = inputPrice
                it[ModelPrices.outputPrice] = outputPrice
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

    /** USD cost of a request: (input×inPrice + output×outPrice) / 1M. Unknown model => 0. */
    fun costOf(model: String?, inputTokens: Long, outputTokens: Long): Double {
        val p = priceFor(model) ?: return 0.0
        return (inputTokens * p.inputPrice + outputTokens * p.outputPrice) / 1_000_000.0
    }

    private fun invalidate() { cache = null }
}
