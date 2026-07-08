package org.claudeproxy.accounts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * In-memory pool of upstream accounts. Owns selection (priority + threshold + fallback)
 * and live limit state. All mutation is serialized through a mutex so concurrent proxy
 * requests share a consistent view of usage.
 */
class AccountPool {
    private val log = LoggerFactory.getLogger("AccountPool")
    private val mutex = Mutex()
    private var accounts: Map<Int, AccountRuntime> = emptyMap()

    /** Id of the account chosen for the most recent request (for the dashboard "active" marker). */
    @Volatile
    var activeAccountId: Int? = null
        private set

    suspend fun reload() {
        val loaded = AccountRepo.loadAll().associate { (rt, _) -> rt.id to rt }
        mutex.withLock { accounts = loaded }
        log.info("Loaded {} accounts", loaded.size)
    }

    suspend fun snapshot(): List<AccountRuntime> = mutex.withLock { accounts.values.sortedBy { it.priority } }

    suspend fun get(id: Int): AccountRuntime? = mutex.withLock { accounts[id] }

    /**
     * Choose the account to serve the next request.
     * Normal mode: highest-priority healthy account under its threshold.
     * Fallback mode: if all are at/over threshold, highest-priority healthy account
     * that is not hard rate-limited, ignoring the threshold.
     */
    suspend fun select(now: Instant = Instant.now()): AccountRuntime? = mutex.withLock {
        val candidates = accounts.values
            .filter { it.enabled && it.health == AccountHealth.OK && !it.isHardLimited(now) }
            .sortedWith(compareBy({ it.priority }, { it.id }))

        if (candidates.isEmpty()) {
            activeAccountId = null
            return@withLock null
        }
        val underThreshold = candidates.firstOrNull { it.usageForSelection() < it.threshold }
        val chosen = underThreshold ?: candidates.first() // fallback: ignore threshold
        activeAccountId = chosen.id
        chosen
    }

    /** Earliest reset time across hard-limited accounts (for the client-facing 429 hint). */
    suspend fun earliestReset(now: Instant = Instant.now()): Instant? = mutex.withLock {
        accounts.values.mapNotNull { it.limit.rateLimitedUntil ?: it.limit.resetAt }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    /** Merge freshly-observed limit state into an account and persist it. */
    suspend fun updateLimit(id: Int, newLimit: LimitState) {
        val updated = mutex.withLock {
            val cur = accounts[id] ?: return@withLock null
            val merged = cur.copy(limit = newLimit.copy(updatedAt = newLimit.updatedAt ?: Instant.now()))
            accounts = accounts + (id to merged)
            merged
        } ?: return
        runCatching { AccountRepo.persistLimit(id, updated.limit) }
            .onFailure { log.warn("persist limit failed for {}: {}", id, it.message) }
    }

    /** Mark an account hard rate-limited until the given reset instant (429 handling). */
    suspend fun markRateLimited(id: Int, until: Instant?) {
        val updated = mutex.withLock {
            val cur = accounts[id] ?: return@withLock null
            val merged = cur.copy(
                limit = cur.limit.copy(
                    status = LimitStatus.REJECTED,
                    rateLimitedUntil = until ?: cur.limit.rateLimitedUntil,
                    updatedAt = Instant.now(),
                ),
            )
            accounts = accounts + (id to merged)
            merged
        } ?: return
        runCatching { AccountRepo.persistLimit(id, updated.limit) }
        log.warn("Account {} hard rate-limited until {}", id, until)
    }

    suspend fun updateSecretInMemory(id: Int, secret: AccountSecret, health: AccountHealth) {
        mutex.withLock {
            val cur = accounts[id] ?: return@withLock
            accounts = accounts + (id to cur.copy(secret = secret, health = health))
        }
    }

    suspend fun setHealth(id: Int, health: AccountHealth) {
        mutex.withLock {
            val cur = accounts[id] ?: return@withLock
            accounts = accounts + (id to cur.copy(health = health))
        }
    }

    private fun AccountRuntime.isHardLimited(now: Instant): Boolean {
        val until = limit.rateLimitedUntil ?: return false
        return until.isAfter(now)
    }

    /** Usage value used for threshold comparison; unknown/API-key => 0 (freely usable). */
    private fun AccountRuntime.usageForSelection(): Double =
        limit.usageFraction() ?: 0.0
}
