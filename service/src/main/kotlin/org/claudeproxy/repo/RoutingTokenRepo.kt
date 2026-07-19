package org.claudeproxy.repo

import org.claudeproxy.cache.RedisCache
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.RoutingTokens
import org.claudeproxy.model.ProxyTokenDto
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Tokens for the OpenAI/Anthropic API routing gateways. A direct analogue of
 * [ProxyTokenRepo] over its own table + cache namespace (`cp:rtok:<hash>`), so a revoked
 * routing token stops working within the 60s TTL (immediately on delete via pub/sub).
 * Raw tokens are `cxr_...`; only the SHA-256 is stored.
 */
object RoutingTokenRepo {

    /** Resolves a raw routing token to its owner + token id, or null (cached, DB fallback). */
    fun resolveAuth(rawToken: String): TokenAuth? {
        val hash = Crypto.sha256Hex(rawToken)
        return parseTokenAuth(RedisCache.getOrLoad("cp:rtok:$hash", 60) { loadByHash(hash) })
    }

    private fun loadByHash(hash: String): String? = transaction {
        val row = RoutingTokens.selectAll().where { RoutingTokens.tokenHash eq hash }.firstOrNull()
            ?: return@transaction null
        RoutingTokens.update({ RoutingTokens.tokenHash eq hash }) { it[lastUsedAt] = Instant.now() }
        "${row[RoutingTokens.userId]}:${row[RoutingTokens.id]}"
    }

    /** Names of this user's routing tokens by id, for labeling per-token stats. */
    fun namesForUser(userId: Int): Map<Int, String> = transaction {
        RoutingTokens.selectAll().where { RoutingTokens.userId eq userId }
            .associate { it[RoutingTokens.id] to it[RoutingTokens.name] }
    }

    fun create(userId: Int, name: String): ProxyTokenDto = transaction {
        val raw = "cxr_" + Crypto.randomToken(24)
        val hash = Crypto.sha256Hex(raw)
        val now = Instant.now()
        val id = RoutingTokens.insert {
            it[RoutingTokens.userId] = userId
            it[tokenHash] = hash
            it[RoutingTokens.name] = name
            it[createdAt] = now
        }[RoutingTokens.id]
        ProxyTokenDto(id, name, userId, now.toString(), null, token = raw)
    }

    fun listForUser(userId: Int): List<ProxyTokenDto> = transaction {
        RoutingTokens.selectAll().where { RoutingTokens.userId eq userId }.map { row ->
            ProxyTokenDto(
                id = row[RoutingTokens.id],
                name = row[RoutingTokens.name],
                userId = row[RoutingTokens.userId],
                createdAt = row[RoutingTokens.createdAt].toString(),
                lastUsedAt = row[RoutingTokens.lastUsedAt]?.toString(),
            )
        }
    }

    fun delete(id: Int, userId: Int): Boolean {
        val (deleted, hash) = transaction {
            val h = RoutingTokens.selectAll()
                .where { (RoutingTokens.id eq id) and (RoutingTokens.userId eq userId) }
                .firstOrNull()?.get(RoutingTokens.tokenHash)
            val n = RoutingTokens.deleteWhere { (RoutingTokens.id eq id) and (RoutingTokens.userId eq userId) }
            (n > 0) to h
        }
        if (deleted && hash != null) {
            RedisCache.evict("cp:rtok:$hash")
            RedisCache.publishInvalidate("rtok:$hash")
        }
        return deleted
    }
}
