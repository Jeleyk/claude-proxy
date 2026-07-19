package org.claudeproxy.repo

import org.claudeproxy.cache.RedisCache
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

/** Owner + token identity a raw inbound token resolves to (tokenId attributes usage rows). */
data class TokenAuth(val userId: Int, val tokenId: Int?)

/**
 * Parse a cached resolve value: `"<userId>:<tokenId>"`, or the legacy plain `"<userId>"`
 * (pre-tokenId cache entries during a rolling deploy) which yields tokenId=null.
 */
internal fun parseTokenAuth(cached: String?): TokenAuth? {
    if (cached == null) return null
    val userId = cached.substringBefore(':').toIntOrNull() ?: return null
    return TokenAuth(userId, cached.substringAfter(':', "").toIntOrNull())
}

object ProxyTokenRepo {

    /**
     * Resolves a raw proxy token to its owner + token id, or null. Hot path: the mapping is
     * cached in Redis (`cp:tok:<hash>`, TTL 60s) with a DB fallback, so a cold/absent
     * Redis is only ever a miss. `last_used_at` is bumped on cache misses (~once per 60s per
     * token) rather than every request — this avoids a DB write on the hot path.
     */
    fun resolveAuth(rawToken: String): TokenAuth? {
        val hash = Crypto.sha256Hex(rawToken)
        return parseTokenAuth(RedisCache.getOrLoad("cp:tok:$hash", 60) { loadByHash(hash) })
    }

    private fun loadByHash(hash: String): String? = transaction {
        val row = ProxyTokens.selectAll().where { ProxyTokens.tokenHash eq hash }.firstOrNull()
            ?: return@transaction null
        ProxyTokens.update({ ProxyTokens.tokenHash eq hash }) { it[lastUsedAt] = Instant.now() }
        "${row[ProxyTokens.userId]}:${row[ProxyTokens.id]}"
    }

    /** Names of this user's tokens by id, for labeling per-token stats. */
    fun namesForUser(userId: Int): Map<Int, String> = transaction {
        ProxyTokens.selectAll().where { ProxyTokens.userId eq userId }
            .associate { it[ProxyTokens.id] to it[ProxyTokens.name] }
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

    fun delete(id: Int, userId: Int): Boolean {
        val (deleted, hash) = transaction {
            val h = ProxyTokens.selectAll()
                .where { (ProxyTokens.id eq id) and (ProxyTokens.userId eq userId) }
                .firstOrNull()?.get(ProxyTokens.tokenHash)
            val n = ProxyTokens.deleteWhere { (ProxyTokens.id eq id) and (ProxyTokens.userId eq userId) }
            (n > 0) to h
        }
        if (deleted && hash != null) {
            // A revoked token must stop working immediately, not after the 60s TTL.
            RedisCache.evict("cp:tok:$hash")
            RedisCache.publishInvalidate("tok:$hash")
        }
        return deleted
    }
}
