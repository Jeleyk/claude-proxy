package org.claudeproxy.repo

import org.claudeproxy.db.Settings
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** Admin-tunable key/value settings (reserved for future use). */
object SettingsRepo {
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
        transaction { Settings.upsert { it[Settings.key] = key; it[Settings.value] = value } }
        all()[key] = value
    }
}
