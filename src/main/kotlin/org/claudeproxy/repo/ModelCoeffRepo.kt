package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.ModelCoeffs
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ModelCoeffDto(val pattern: String, val coefficient: Double)

/**
 * Per-model dirty-token multipliers. A request's model id is matched against each
 * pattern by case-insensitive substring; the longest matching pattern wins (so
 * "opus" matches "claude-opus-4-8", "opus-4.8", etc.). Defaults: haiku ×1, sonnet ×3, opus ×5.
 */
object ModelCoeffRepo {

    @Volatile
    private var cache: List<ModelCoeffDto>? = null

    fun seedDefaults() {
        transaction {
            listOf("haiku" to 1.0, "sonnet" to 3.0, "opus" to 5.0).forEach { (p, c) ->
                ModelCoeffs.insertIgnore { it[pattern] = p; it[coefficient] = c }
            }
        }
        invalidate()
    }

    fun list(): List<ModelCoeffDto> {
        cache?.let { return it }
        val loaded = transaction {
            ModelCoeffs.selectAll().map { ModelCoeffDto(it[ModelCoeffs.pattern], it[ModelCoeffs.coefficient]) }
        }.sortedByDescending { it.pattern.length }
        cache = loaded
        return loaded
    }

    fun set(pattern: String, coefficient: Double) {
        transaction { ModelCoeffs.replace { it[ModelCoeffs.pattern] = pattern.trim().lowercase(); it[ModelCoeffs.coefficient] = coefficient } }
        invalidate()
    }

    fun delete(pattern: String) {
        transaction { ModelCoeffs.deleteWhere { ModelCoeffs.pattern eq pattern } }
        invalidate()
    }

    /** Coefficient for a model id; longest matching pattern wins, default 1.0. */
    fun coeffFor(model: String?): Double {
        if (model.isNullOrBlank()) return 1.0
        val m = model.lowercase()
        return list().firstOrNull { m.contains(it.pattern) }?.coefficient ?: 1.0
    }

    private fun invalidate() { cache = null }
}
