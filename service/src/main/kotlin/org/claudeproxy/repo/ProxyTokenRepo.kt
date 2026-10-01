package org.claudeproxy.repo

import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.ProxyTokens
import org.claudeproxy.db.Users
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
 * Parse a cached resolve value: `"<userId>:<tokenId>"`; a plain `"<userId>"` (no token id)
 * yields tokenId=null.
 */
internal fun parseTokenAuth(cached: String?): TokenAuth? {
    if (cached == null) return null
    val userId = cached.substringBefore(':').toIntOrNull() ?: return null
    return TokenAuth(userId, cached.substringAfter(':', "").toIntOrNull())
}

/**
 * A token is only as live as its owner: disabling a user has to cut off the datapaths too, not
 * just the UI session. Called inside the token lookup's own transaction.
 */
internal fun ownerEnabled(userId: Int): Boolean =
    !Users.selectAll().where { (Users.id eq userId) and (Users.enabled eq true) }.empty()

object ProxyTokenRepo {

    /**
     * Resolves a raw proxy token to its owner + token id, or null. Hot path: the mapping is
     * cached in-process (`cp:tok:<hash>`, TTL 60s) with a DB fallback. `last_used_at` is bumped
     * on cache misses (~once per 60s per token) rather than every request — this avoids a DB
     * write on the hot path.
     *
     * A disabled token resolves to null, i.e. the datapath rejects it exactly like an unknown
     * token (401). Disabled tokens are never cached, so each attempt costs one SELECT — the
     * same as any bad token.
     */
    fun resolveAuth(rawToken: String): TokenAuth? {
        val hash = Crypto.sha256Hex(rawToken)
        return parseTokenAuth(MemoryCache.getOrLoad("cp:tok:$hash", 60) { loadByHash(hash) })
    }

    private fun loadByHash(hash: String): String? = transaction {
        val row = ProxyTokens.selectAll()
            .where { (ProxyTokens.tokenHash eq hash) and (ProxyTokens.enabled eq true) }
            .firstOrNull() ?: return@transaction null
        if (!ownerEnabled(row[ProxyTokens.userId])) return@transaction null
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
                enabled = row[ProxyTokens.enabled],
                defaultModel = row[ProxyTokens.defaultModel],
            )
        }
    }

    /**
     * Turn a token on/off (own tokens only). Evicts the resolve cache so the switch takes effect
     * immediately rather than after the 60s TTL.
     */
    fun setEnabled(id: Int, userId: Int, enabled: Boolean): Boolean {
        val (updated, hash) = transaction {
            val h = ProxyTokens.selectAll()
                .where { (ProxyTokens.id eq id) and (ProxyTokens.userId eq userId) }
                .firstOrNull()?.get(ProxyTokens.tokenHash)
            val n = ProxyTokens.update({ (ProxyTokens.id eq id) and (ProxyTokens.userId eq userId) }) {
                it[ProxyTokens.enabled] = enabled
            }
            (n > 0) to h
        }
        if (updated && hash != null) MemoryCache.evict("cp:tok:$hash")
        return updated
    }

    /**
     * The token's forced model for the resolve hot path (cached 60s, "" = none). Evicted on
     * [updateDefaultModel]/[delete], so an edit applies on the next request.
     */
    fun defaultModelOf(tokenId: Int): String? =
        MemoryCache.getOrLoad("cp:tokmodel:$tokenId", 60) {
            transaction {
                ProxyTokens.selectAll().where { ProxyTokens.id eq tokenId }
                    .firstOrNull()?.get(ProxyTokens.defaultModel) ?: ""
            }
        }?.takeIf { it.isNotEmpty() }

    /** Set or clear (null/blank) the model forced onto the token's requests. Own tokens only. */
    fun updateDefaultModel(id: Int, userId: Int, model: String?): Boolean {
        val m = normalizeModel(model)
        val updated = transaction {
            ProxyTokens.update({ (ProxyTokens.id eq id) and (ProxyTokens.userId eq userId) }) {
                it[defaultModel] = m
            }
        }
        if (updated > 0) MemoryCache.evict("cp:tokmodel:$id")
        return updated > 0
    }

    /** Blank clears; anything else is kept verbatim (trimmed) — new model ids need no release. */
    internal fun normalizeModel(model: String?): String? {
        val m = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(m.length <= 128 && m.none { it.isWhitespace() || it == '"' || it == '\\' }) { "bad model id" }
        return m
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
            MemoryCache.evict("cp:tok:$hash")
            MemoryCache.evict("cp:tokmodel:$id")
        }
        return deleted
    }
}
