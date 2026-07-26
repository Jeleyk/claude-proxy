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
            // pattern to (input, output, cacheRead, cacheWrite) in USD per 1M tokens, from
            // Anthropic's list prices (platform.claude.com/docs/en/about-claude/pricing).
            // cacheWrite is the "5m Cache Writes" column (= 1.25× input); cacheRead the
            // "Cache Hits & Refreshes" column (= 0.1× input). Version-specific full-id patterns
            // come first so the longest-match rule (see list()) prices each version correctly —
            // e.g. Opus 4.1 ($15) is distinguished from Opus 4.8 ($5), and Fable 5 no longer
            // falls through to $0. The bare haiku/sonnet/opus rows stay as catch-all fallbacks
            // for any unrecognised future id.
            listOf(
                // Fable / Mythos
                Quad("claude-fable-5", 10.0, 50.0, 1.0, 12.5),
                Quad("claude-mythos-5", 10.0, 50.0, 1.0, 12.5),
                // Opus 5 — current flagship. Priced identically to Opus 4.8, but spelled out
                // rather than left to the bare `opus` fallback: the fallback is only correct by
                // coincidence, and a future edit to it would silently misprice the flagship.
                Quad("claude-opus-5", 5.0, 25.0, 0.5, 6.25),
                // Opus 4.x current ($5 tier)
                Quad("claude-opus-4-8", 5.0, 25.0, 0.5, 6.25),
                Quad("claude-opus-4-7", 5.0, 25.0, 0.5, 6.25),
                Quad("claude-opus-4-6", 5.0, 25.0, 0.5, 6.25),
                Quad("claude-opus-4-5", 5.0, 25.0, 0.5, 6.25),
                // Opus 4.1 / 4.0 (deprecated, $15 tier). 4.0 is listed explicitly because the
                // bare `opus` fallback prices it at the $5 tier — a silent 3× under-charge on
                // any traffic that still names it.
                Quad("claude-opus-4-1", 15.0, 75.0, 1.5, 18.75),
                Quad("claude-opus-4-0", 15.0, 75.0, 1.5, 18.75),
                // Sonnet 5 introductory pricing, in effect through 2026-08-31.
                // Reverts to standard 3.0 / 15.0 / 0.3 / 3.75 on 2026-09-01 — bump then (or via the UI).
                Quad("claude-sonnet-5", 2.0, 10.0, 0.2, 2.5),
                // Sonnet 4.x ($3 tier)
                Quad("claude-sonnet-4-6", 3.0, 15.0, 0.3, 3.75),
                Quad("claude-sonnet-4-5", 3.0, 15.0, 0.3, 3.75),
                // Haiku
                Quad("claude-haiku-4-5", 1.0, 5.0, 0.1, 1.25),
                Quad("claude-3-5-haiku", 0.8, 4.0, 0.08, 1.0),
                // Generic fallbacks (shortest patterns → matched only when no version row does).
                Quad("haiku", 1.0, 5.0, 0.1, 1.25),
                Quad("sonnet", 3.0, 15.0, 0.3, 3.75),
                Quad("opus", 5.0, 25.0, 0.5, 6.25),
                Quad("fable", 10.0, 50.0, 1.0, 12.5),
            ).forEach { p ->
                ModelPrices.insertIgnore {
                    it[pattern] = p.pattern
                    it[inputPrice] = p.input
                    it[outputPrice] = p.output
                    it[cacheReadPrice] = p.cacheRead
                    it[cacheWritePrice] = p.cacheWrite
                }
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
