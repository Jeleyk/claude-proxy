package org.claudeproxy.auth

import org.claudeproxy.db.Crypto
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One in-flight login: everything the callback needs, held server-side only. */
data class OidcTransaction(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val createdAtMs: Long,
)

/**
 * Server-side store for in-flight OIDC logins, keyed by a random id that lives only in the
 * browser's HttpOnly transaction cookie. Binding `state` to that cookie is what stops login CSRF
 * (an attacker's callback URL is useless without the victim's cookie), and [take] removes the
 * entry, so a transaction — and with it the PKCE verifier and nonce — is single use.
 *
 * In-process on purpose, like the rest of the service's caches (single instance by design); a
 * restart only aborts logins that are mid-redirect. Bounded so unauthenticated `/start` spam
 * cannot grow it without limit.
 */
class OidcTransactions(
    private val ttlMs: Long = TTL_SECONDS * 1000,
    private val maxEntries: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = ConcurrentHashMap<String, OidcTransaction>()

    /** Creates a transaction; returns its cookie id, or null when the store is full of live entries. */
    fun begin(): Pair<String, OidcTransaction>? {
        if (entries.size >= maxEntries) sweep()
        if (entries.size >= maxEntries) return null
        val txn = OidcTransaction(
            state = Crypto.randomToken(32),
            nonce = Crypto.randomToken(32),
            // RFC 7636: 43..128 chars of [A-Za-z0-9-._~]; 32 random bytes base64url = 43 chars.
            codeVerifier = Crypto.randomToken(32),
            createdAtMs = clock(),
        )
        val id = Crypto.randomToken(32)
        entries[id] = txn
        return id to txn
    }

    /**
     * Consumes the transaction for [id] if it exists, is fresh and its `state` equals [state]
     * (constant-time). The entry is removed whatever the outcome — a probe cannot retry it.
     */
    fun take(id: String?, state: String?): OidcTransaction? {
        if (id.isNullOrEmpty() || state.isNullOrEmpty()) return null
        val txn = entries.remove(id) ?: return null
        if (clock() - txn.createdAtMs > ttlMs) return null
        if (!MessageDigest.isEqual(txn.state.toByteArray(), state.toByteArray())) return null
        return txn
    }

    fun discard(id: String) { entries.remove(id) }

    internal fun size() = entries.size

    private fun sweep() {
        val now = clock()
        entries.entries.removeIf { now - it.value.createdAtMs > ttlMs }
    }

    companion object {
        const val TTL_SECONDS = 600L
    }
}
