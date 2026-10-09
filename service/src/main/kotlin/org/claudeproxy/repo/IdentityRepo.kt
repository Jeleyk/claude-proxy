package org.claudeproxy.repo

import org.claudeproxy.db.UserIdentities
import org.claudeproxy.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * External (OIDC) identities linked to local users. Functions without their own transaction are
 * meant to run inside the caller's, so a link decision and its write commit together.
 */
object IdentityRepo {

    fun userIdFor(issuer: String, subject: String): Int? =
        UserIdentities.selectAll()
            .where { (UserIdentities.issuer eq issuer) and (UserIdentities.subject eq subject) }
            .firstOrNull()?.get(UserIdentities.userId)

    fun hasIdentityFor(userId: Int, issuer: String): Boolean =
        UserIdentities.selectAll()
            .where { (UserIdentities.userId eq userId) and (UserIdentities.issuer eq issuer) }
            .any()

    /**
     * Links and ends every existing panel session of [userId] (session generation bump): sessions
     * opened with the password before the link would otherwise outlive it by up to 30 days and
     * survive an IdP-side removal. Callers that go on to open a session must re-read the user.
     */
    fun link(userId: Int, issuer: String, subject: String) {
        Users.update({ Users.id eq userId }) { it[Users.sessionVersion] = Users.sessionVersion + 1L }
        val now = Instant.now()
        UserIdentities.insert {
            it[UserIdentities.userId] = userId
            it[UserIdentities.issuer] = issuer
            it[UserIdentities.subject] = subject
            it[createdAt] = now
            it[lastLoginAt] = now
        }
    }

    fun touch(issuer: String, subject: String) {
        UserIdentities.update({ (UserIdentities.issuer eq issuer) and (UserIdentities.subject eq subject) }) {
            it[lastLoginAt] = Instant.now()
        }
    }

    fun isLinked(userId: Int): Boolean =
        UserIdentities.selectAll().where { UserIdentities.userId eq userId }.any()

    fun unlinkAll(userId: Int): Int =
        UserIdentities.deleteWhere { UserIdentities.userId eq userId }
}
