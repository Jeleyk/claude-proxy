package org.claudeproxy.repo

import org.claudeproxy.db.Settings
import org.claudeproxy.envOrProp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Admin-tunable settings. Currently: how many tokens are considered equivalent to 1%
 * of a "normal" (coefficient ×1) account's window — used to translate between token
 * budgets and pseudo session-percent.
 */
object SettingsRepo {
    const val KEY_TOKENS_PER_PERCENT = "tokens_per_window_percent"

    @Volatile
    private var cache: MutableMap<String, String>? = null

    private fun all(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = transaction {
            Settings.selectAll().associate { it[Settings.key] to it[Settings.value] }.toMutableMap()
        }
        cache = loaded
        return loaded
    }

    fun get(key: String): String? = all()[key]

    fun set(key: String, value: String) {
        transaction { Settings.replace { it[Settings.key] = key; it[Settings.value] = value } }
        all()[key] = value
    }

    /** Tokens equal to 1% of a normal window. Falls back to env then a sane default. */
    fun tokensPerWindowPercent(): Double {
        get(KEY_TOKENS_PER_PERCENT)?.toDoubleOrNull()?.let { return it }
        return envOrProp("TOKENS_PER_WINDOW_PERCENT")?.toDoubleOrNull() ?: 10_000.0
    }

    fun setTokensPerWindowPercent(v: Double) = set(KEY_TOKENS_PER_PERCENT, v.toString())

    /** Convert a token count into pseudo window-percent for an account of given coefficient. */
    fun tokensToPercent(tokens: Long, coefficient: Double = 1.0): Double {
        val perPct = tokensPerWindowPercent() * coefficient
        if (perPct <= 0) return 0.0
        return tokens / perPct
    }

    fun percentToTokens(percent: Double, coefficient: Double = 1.0): Long =
        (percent * tokensPerWindowPercent() * coefficient).toLong()
}
