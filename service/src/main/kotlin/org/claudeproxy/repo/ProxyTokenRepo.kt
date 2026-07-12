package org.claudeproxy.repo

import org.claudeproxy.db.Crypto
import org.claudeproxy.db.ProxyTokens
import org.claudeproxy.model.ProxyTokenDto
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

object ProxyTokenRepo {

    /** Returns the userId that owns a given raw proxy token, or null. Also bumps last_used_at. */
    fun resolveUser(rawToken: String): Int? = transaction {
        val hash = Crypto.sha256Hex(rawToken)
        val row = ProxyTokens.selectAll().where { ProxyTokens.tokenHash eq hash }.firstOrNull()
            ?: return@transaction null
        ProxyTokens.update({ ProxyTokens.tokenHash eq hash }) { it[lastUsedAt] = Instant.now() }
        row[ProxyTokens.userId]
    }

    fun create(userId: Int, name: String): ProxyTokenDto = transaction {
        val raw = "cxp_" + Crypto.randomToken(24)
        val hash = Crypto.sha256Hex(raw)
        val now = Instant.now()
        val id = ProxyTokens.insert {
            it[ProxyTokens.userId] = userId
            it[tokenHash] = hash
            it[ProxyTokens.name] = name
            it[createdAt] = now
        }[ProxyTokens.id]
        ProxyTokenDto(id, name, userId, now.toString(), null, token = raw)
    }

    fun listForUser(userId: Int): List<ProxyTokenDto> = transaction {
        ProxyTokens.selectAll().where { ProxyTokens.userId eq userId }.map { row ->
            ProxyTokenDto(
                id = row[ProxyTokens.id],
                name = row[ProxyTokens.name],
                userId = row[ProxyTokens.userId],
                createdAt = row[ProxyTokens.createdAt].toString(),
                lastUsedAt = row[ProxyTokens.lastUsedAt]?.toString(),
            )
        }
    }

    fun delete(id: Int, userId: Int): Boolean = transaction {
        ProxyTokens.deleteWhere { (ProxyTokens.id eq id) and (ProxyTokens.userId eq userId) } > 0
    }
}
