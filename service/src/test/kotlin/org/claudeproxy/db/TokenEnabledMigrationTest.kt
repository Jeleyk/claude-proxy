package org.claudeproxy.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `proxy_tokens.enabled` / `routing_tokens.enabled` are added to databases that already hold
 * tokens (prod runs `createMissingTablesAndColumns` on every boot). The generated ALTER must
 * carry the DEFAULT — otherwise adding a NOT NULL column to a populated table fails, and any
 * pre-existing token must come back enabled, not disabled.
 */
class TokenEnabledMigrationTest {
    private val dbFile = File.createTempFile("token-enabled-migration", ".db")

    @AfterTest
    fun teardown() = dbFile.delete().let {}

    @Test
    fun `existing tokens survive the added column as enabled`() {
        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        transaction(db) {
            maxAttempts = 1 // a retry would re-run the CREATE and mask the real failure
            // The pre-feature schema, with one token already stored.
            TransactionManager.current().exec(
                "CREATE TABLE proxy_tokens (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INT NOT NULL, " +
                    "token_hash VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(128) NOT NULL, " +
                    "created_at TEXT NOT NULL, last_used_at TEXT NULL)",
            )
            TransactionManager.current().exec(
                "INSERT INTO proxy_tokens (user_id, token_hash, name, created_at) VALUES (1, 'hash', 'old', '2026-01-01T00:00:00Z')",
            )

            SchemaUtils.createMissingTablesAndColumns(ProxyTokens)

            // Only the new column is read: the legacy row's timestamps are raw test text.
            assertTrue(ProxyTokens.select(ProxyTokens.enabled).first()[ProxyTokens.enabled])
        }
    }
}
