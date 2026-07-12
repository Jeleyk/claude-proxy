package org.claudeproxy.repo

import org.claudeproxy.db.SessionMap
import org.claudeproxy.db.SessionOwners
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Exercises [SessionMapRepo] against a throwaway on-disk SQLite database. */
class SessionMapRepoTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("sessionmap-test", ".db")
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        transaction { SchemaUtils.create(SessionOwners, SessionMap) }
        SessionMapRepo.clearCaches()
    }

    @AfterTest
    fun teardown() {
        SessionMapRepo.clearCaches()
        dbFile.delete()
    }

    @Test
    fun `first account to use an origin keeps it unchanged`() {
        assertEquals("o1", SessionMapRepo.resolve("o1", accountId = 1))
        // repeat is stable
        assertEquals("o1", SessionMapRepo.resolve("o1", accountId = 1))
    }

    @Test
    fun `a second account gets a distinct, stable replacement`() {
        assertEquals("o1", SessionMapRepo.resolve("o1", accountId = 1)) // account 1 owns it
        val custom = SessionMapRepo.resolve("o1", accountId = 2)
        assertNotEquals("o1", custom)
        // stable across repeats
        assertEquals(custom, SessionMapRepo.resolve("o1", accountId = 2))
    }

    @Test
    fun `replacement survives a cache flush (read from DB)`() {
        SessionMapRepo.resolve("o1", accountId = 1) // owner
        val custom = SessionMapRepo.resolve("o1", accountId = 2)
        SessionMapRepo.clearCaches()
        assertEquals(custom, SessionMapRepo.resolve("o1", accountId = 2))
        assertEquals("o1", SessionMapRepo.resolve("o1", accountId = 1))
    }

    @Test
    fun `each origin is owned independently`() {
        assertEquals("o1", SessionMapRepo.resolve("o1", accountId = 1))
        // a different origin, first seen on account 2, is owned by account 2
        assertEquals("o2", SessionMapRepo.resolve("o2", accountId = 2))
    }

    @Test
    fun `prune drops rows older than the cutoff and keeps newer ones`() {
        SessionMapRepo.resolve("fresh", accountId = 1) // created now
        val old = Instant.now().minusSeconds(60L * 24 * 3600) // 60 days ago
        transaction {
            SessionOwners.insertIgnore { it[origin] = "old"; it[accountId] = 9; it[createdAt] = old }
            SessionMap.insertIgnore { it[origin] = "old"; it[accountId] = 9; it[replaced] = "old"; it[createdAt] = old }
        }
        val deleted = SessionMapRepo.pruneOlderThan(Instant.now().minusSeconds(30L * 24 * 3600))
        assertTrue(deleted >= 2, "expected the two old rows deleted, got $deleted")
        transaction {
            assertTrue(SessionMap.selectAll().where { SessionMap.origin eq "fresh" }.any(), "fresh row must survive")
            assertTrue(!SessionMap.selectAll().where { SessionMap.origin eq "old" }.any(), "old row must be gone")
        }
    }
}
