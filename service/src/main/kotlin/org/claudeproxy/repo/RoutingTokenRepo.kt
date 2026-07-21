package org.claudeproxy.repo

import org.claudeproxy.cache.MemoryCache
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
 * routing token stops working immediately (evicted on delete).
 * Raw tokens are `cxr_...`; only the SHA-256 is stored.
 */
object RoutingTokenRepo {

    /** Resolves a raw routing token to its owner + token id, or null (cached, DB fallback). */
    fun resolveAuth(rawToken: String): TokenAuth? {
        val hash = Crypto.sha256Hex(rawToken)
        return parseTokenAuth(MemoryCache.getOrLoad("cp:rtok:$hash", 60) { loadByHash(hash) })
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

    fun create(userId: Int, name: String, systemPrompt: String? = null): ProxyTokenDto = transaction {
        val raw = "cxr_" + Crypto.randomToken(24)
        val hash = Crypto.sha256Hex(raw)
        val now = Instant.now()
        val prompt = systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        val id = RoutingTokens.insert {
            it[RoutingTokens.userId] = userId
            it[tokenHash] = hash
            it[RoutingTokens.name] = name
            it[RoutingTokens.systemPrompt] = prompt
            it[createdAt] = now
        }[RoutingTokens.id]
        ProxyTokenDto(id, name, userId, now.toString(), null, token = raw, systemPrompt = prompt)
    }

    fun listForUser(userId: Int): List<ProxyTokenDto> = transaction {
        RoutingTokens.selectAll().where { RoutingTokens.userId eq userId }.map { row ->
            ProxyTokenDto(
                id = row[RoutingTokens.id],
                name = row[RoutingTokens.name],
                userId = row[RoutingTokens.userId],
                createdAt = row[RoutingTokens.createdAt].toString(),
                lastUsedAt = row[RoutingTokens.lastUsedAt]?.toString(),
                systemPrompt = row[RoutingTokens.systemPrompt],
            )
        }
    }

    /**
     * The token's static system prompt for the resolve hot path (cached 60s, "" = none).
     * Evicted on [updatePrompt]/[delete], so edits apply within a minute at worst — immediately
     * on this instance.
     */
    fun promptOf(tokenId: Int): String? =
        MemoryCache.getOrLoad("cp:rtoksp:$tokenId", 60) {
            transaction {
                RoutingTokens.selectAll().where { RoutingTokens.id eq tokenId }
                    .firstOrNull()?.get(RoutingTokens.systemPrompt) ?: ""
            }
        }?.takeIf { it.isNotEmpty() }

    /** Set or clear (null/blank) the token's static system prompt. Own tokens only. */
    fun updatePrompt(id: Int, userId: Int, systemPrompt: String?): Boolean {
        val prompt = systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        val updated = transaction {
            RoutingTokens.update({ (RoutingTokens.id eq id) and (RoutingTokens.userId eq userId) }) {
                it[RoutingTokens.systemPrompt] = prompt
            }
        }
        if (updated > 0) MemoryCache.evict("cp:rtoksp:$id")
        return updated > 0
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
            MemoryCache.evict("cp:rtok:$hash")
            MemoryCache.evict("cp:rtoksp:$id")
        }
        return deleted
    }
}
