package org.claudeproxy.db

import org.claudeproxy.auth.freshDbAt
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.repo.IdentityRepo
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.*

/**
 * Upgrade path: a database created by the previous release (users without `email`, no
 * `user_identities`) gains both on boot, keeps its rows, and stays readable by old code
 * (purely additive: a nullable column + a new table — rollback needs no down-migration).
 */
class OidcSchemaMigrationTest {
    @Test
    fun `existing users table gains email and identities additively`() {
        val f = File.createTempFile("oidc-migration", ".db")
        try {
            val legacy = Database.connect("jdbc:sqlite:${f.absolutePath}", "org.sqlite.JDBC")
            transaction(legacy) {
                exec("CREATE TABLE users (session_version BIGINT DEFAULT 0 NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT, username VARCHAR(128) NOT NULL UNIQUE, password_hash VARCHAR(256) NOT NULL, enabled BOOLEAN DEFAULT 1 NOT NULL, daily_cost_limit DOUBLE PRECISION NULL, daily_routing_cost_limit DOUBLE PRECISION NULL, daily_chat_cost_limit DOUBLE PRECISION NULL, prefer_global_pool BOOLEAN DEFAULT 0 NOT NULL, created_at TEXT NOT NULL)")
                exec("INSERT INTO users (username, password_hash, enabled, created_at) VALUES ('old1', 'x', 1, '2026-01-01 00:00:00.000000'), ('old2', 'x', 1, '2026-01-01 00:00:00.000000')")
            }
            freshDbAt(f)
            val users = UserRepo.list()
            assertEquals(setOf("old1", "old2"), users.map { it.username }.toSet())
            assertTrue(users.all { it.email == null && !it.ssoLinked })
            // Two NULL emails coexist under the unique index; a duplicate value does not.
            val id1 = users.first { it.username == "old1" }.id
            UserRepo.update(id1, null, null, null, null, null, false, email = "a@example.com")
            assertFails("unique index enforced by the database itself") {
                transaction { exec("UPDATE users SET email = 'a@example.com' WHERE username = 'old2'") }
            }
            transaction { IdentityRepo.link(id1, "https://idp", "sub") }
            assertFails("(issuer, sub) is unique") { transaction { IdentityRepo.link(users.first { it.username == "old2" }.id, "https://idp", "sub") } }
            assertTrue(UserRepo.get(id1)!!.ssoLinked)
            // The old writer's INSERT (no email column) still works against the new schema.
            transaction { exec("INSERT INTO users (username, password_hash, enabled, created_at) VALUES ('old3', 'x', 1, '2026-01-01 00:00:00.000000')") }
            assertNull(UserRepo.list().first { it.username == "old3" }.email)
        } finally { MemoryCache.clear(); f.delete() }
    }
}
