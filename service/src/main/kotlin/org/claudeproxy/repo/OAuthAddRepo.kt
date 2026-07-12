package org.claudeproxy.repo

import org.claudeproxy.db.OAuthAddSessions
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

object OAuthAddRepo {

    fun create(state: String, verifier: String, createdBy: Int?, ttlSeconds: Long = 900) = transaction {
        OAuthAddSessions.insert {
            it[id] = state
            it[pkceVerifier] = verifier
            it[OAuthAddSessions.createdBy] = createdBy
            it[expiresAt] = Instant.now().plusSeconds(ttlSeconds)
        }
    }

    /** Returns the verifier for a valid, unexpired state, consuming (deleting) it. */
    fun consume(state: String): String? = transaction {
        val row = OAuthAddSessions.selectAll().where { OAuthAddSessions.id eq state }.firstOrNull()
            ?: return@transaction null
        OAuthAddSessions.deleteWhere { id eq state }
        if (row[OAuthAddSessions.expiresAt].isBefore(Instant.now())) return@transaction null
        row[OAuthAddSessions.pkceVerifier]
    }

    fun purgeExpired() = transaction {
        OAuthAddSessions.deleteWhere { expiresAt less Instant.now() }
    }
}
