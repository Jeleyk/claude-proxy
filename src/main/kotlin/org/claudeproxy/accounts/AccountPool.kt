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
    suspend fun select(allowedGroups: Set<Int>?, now: Instant = Instant.now()): AccountRuntime? = mutex.withLock {
        val candidates = accounts.values
            .filter { it.enabled && it.health == AccountHealth.OK && !it.isHardLimited(now) }
            .filter { canUse(it, allowedGroups) }
            .sortedWith(compareBy({ it.priority }, { it.id }))

        if (candidates.isEmpty()) {
            return@withLock null
        }
        val underThreshold = candidates.firstOrNull { it.usageForSelection() < it.threshold }
        val chosen = underThreshold ?: candidates.first() // fallback: ignore threshold
        activeAccountId = chosen.id
        chosen
    }

    /**
     * Ordered list of accounts to try for a request: under-threshold first (by priority),
     * then over-threshold as fallback. Excludes disabled/unhealthy/hard-limited/out-of-scope.
     */
    suspend fun selectionOrder(allowedGroups: Set<Int>?, now: Instant = Instant.now()): List<AccountRuntime> = mutex.withLock {
        val candidates = accounts.values
            .filter { it.enabled && it.health == AccountHealth.OK && !it.isHardLimited(now) && canUse(it, allowedGroups) }
            .sortedWith(compareBy({ it.priority }, { it.id }))
        val under = candidates.filter { it.usageForSelection() < it.threshold }
        val over = candidates.filter { it.usageForSelection() >= it.threshold }
        under + over
    }

    fun markActive(id: Int) { activeAccountId = id }

    /**
     * Pick any enabled+healthy account in scope, ignoring threshold and rate-limit.
     * Used for requests that don't consume subscription quota (token counting, model list).
     */
    suspend fun selectAny(allowedGroups: Set<Int>?): AccountRuntime? = mutex.withLock {
        accounts.values
            .filter { it.enabled && it.health == AccountHealth.OK && canUse(it, allowedGroups) }
            .minWithOrNull(compareBy({ it.priority }, { it.id }))
            ?.also { activeAccountId = it.id }
    }

    /** Why the pool couldn't serve, for choosing a client-facing status. */
    data class Availability(val enabledInScope: Int, val healthy: Int, val rateLimited: Int, val lostAccess: Int)

    suspend fun availability(allowedGroups: Set<Int>?, now: Instant = Instant.now()): Availability = mutex.withLock {
        val inScope = accounts.values.filter { it.enabled && canUse(it, allowedGroups) }
        Availability(
            enabledInScope = inScope.size,
            healthy = inScope.count { it.health == AccountHealth.OK && !it.isHardLimited(now) },
            rateLimited = inScope.count { it.health == AccountHealth.OK && it.isHardLimited(now) },
            lostAccess = inScope.count { it.health != AccountHealth.OK },
        )
    }

    /** A user may use an ungrouped account always, or a grouped one only if its group is allowed. */
    private fun canUse(a: AccountRuntime, allowedGroups: Set<Int>?): Boolean {
        if (allowedGroups == null) return true          // null = all groups (admin)
        val g = a.groupId ?: return true                // ungrouped accounts are open to all
        return g in allowedGroups
    }

    /** Earliest reset time across hard-limited accounts (for the client-facing 429 hint). */
    suspend fun earliestReset(now: Instant = Instant.now()): Instant? = mutex.withLock {
        accounts.values.mapNotNull { rt ->
            rt.limit.rateLimitedUntil ?: rt.limit.windows.values.mapNotNull { it.resetAt }.minOrNull()
        }.filter { it.isAfter(now) }.minOrNull()
    }

    /** Nearest upcoming reset for a given window across the pool (dashboard hint). */
    suspend fun nextReset(kind: org.claudeproxy.model.WindowKind, now: Instant = Instant.now()): Instant? = mutex.withLock {
        accounts.values.mapNotNull { it.limit.window(kind)?.resetAt }.filter { it.isAfter(now) }.minOrNull()
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
