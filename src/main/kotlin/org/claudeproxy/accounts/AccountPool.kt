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

    /** Global (shared-pool) accounts only — for the global dashboard/stats. */
    suspend fun snapshotGlobal(): List<AccountRuntime> =
        mutex.withLock { accounts.values.filter { it.ownerId == null }.sortedBy { it.priority } }

    /** The personal accounts owned by [userId] — for the "My Accounts" view. */
    suspend fun snapshotOwned(userId: Int): List<AccountRuntime> =
        mutex.withLock { accounts.values.filter { it.ownerId == userId }.sortedBy { it.priority } }

    suspend fun get(id: Int): AccountRuntime? = mutex.withLock { accounts[id] }

    /**
     * Choose the account to serve the next request. A user's personal accounts are always
     * preferred over the shared global pool; within each tier, under-threshold beats
     * over-threshold (fallback). Returns null if nothing is usable.
     */
    suspend fun select(userId: Int?, allowedGroups: Set<Int>?, now: Instant = Instant.now()): AccountRuntime? = mutex.withLock {
        orderedCandidates(userId, allowedGroups, now).firstOrNull()?.also { activeAccountId = it.id }
    }

    /**
     * Ordered list of accounts to try for a request: personal first (by priority, under- then
     * over-threshold), then the global pool (same ordering). Excludes disabled/unhealthy/
     * hard-limited/out-of-scope accounts.
     */
    suspend fun selectionOrder(userId: Int?, allowedGroups: Set<Int>?, now: Instant = Instant.now()): List<AccountRuntime> =
        mutex.withLock { orderedCandidates(userId, allowedGroups, now) }

    /**
     * Personal-accounts-only selection order. Used when the shared-pool daily spend limit is
     * reached: the user's own accounts are their own quota and remain usable.
     */
    suspend fun selectionOrderOwned(userId: Int, now: Instant = Instant.now()): List<AccountRuntime> = mutex.withLock {
        tierOrder(accounts.values.filter {
            it.enabled && it.health == AccountHealth.OK && !it.isHardLimited(now) && it.ownerId == userId
        })
    }

    fun markActive(id: Int) { activeAccountId = id }

    /**
     * Pick any enabled+healthy account in scope, ignoring threshold and rate-limit. Personal
     * accounts are preferred. Used for requests that don't consume subscription quota
     * (token counting, model list).
     */
    suspend fun selectAny(userId: Int?, allowedGroups: Set<Int>?): AccountRuntime? = mutex.withLock {
        val usable = accounts.values.filter { it.enabled && it.health == AccountHealth.OK }
        val personal = if (userId == null) emptyList() else usable.filter { it.ownerId == userId }
        val global = usable.filter { it.ownerId == null && canUseGlobal(it, allowedGroups) }
        (personal.ifEmpty { global })
            .minWithOrNull(compareBy({ it.priority }, { it.id }))
            ?.also { activeAccountId = it.id }
    }

    /** Why the pool couldn't serve, for choosing a client-facing status. */
    data class Availability(val enabledInScope: Int, val healthy: Int, val rateLimited: Int, val lostAccess: Int)

    suspend fun availability(userId: Int?, allowedGroups: Set<Int>?, now: Instant = Instant.now()): Availability = mutex.withLock {
        val inScope = accounts.values.filter { it.enabled && inUserScope(it, userId, allowedGroups) }
        Availability(
            enabledInScope = inScope.size,
            healthy = inScope.count { it.health == AccountHealth.OK && !it.isHardLimited(now) },
            rateLimited = inScope.count { it.health == AccountHealth.OK && it.isHardLimited(now) },
            lostAccess = inScope.count { it.health != AccountHealth.OK },
        )
    }

    /** Ordered candidates for a request: personal tier first, then the global tier. */
    private fun orderedCandidates(userId: Int?, allowedGroups: Set<Int>?, now: Instant): List<AccountRuntime> {
        val healthy = accounts.values.filter { it.enabled && it.health == AccountHealth.OK && !it.isHardLimited(now) }
        val personal = if (userId == null) emptyList() else healthy.filter { it.ownerId == userId }
        val global = healthy.filter { it.ownerId == null && canUseGlobal(it, allowedGroups) }
        return tierOrder(personal) + tierOrder(global)
    }

    /** Within one tier: sorted by priority, under-threshold before over-threshold (fallback). */
    private fun tierOrder(tier: List<AccountRuntime>): List<AccountRuntime> {
        val sorted = tier.sortedWith(compareBy({ it.priority }, { it.id }))
        val under = sorted.filter { it.usageForSelection() < it.threshold }
        val over = sorted.filter { it.usageForSelection() >= it.threshold }
        return under + over
    }

    /** An account is in a user's routing scope if it is their personal account or a usable global one. */
    private fun inUserScope(a: AccountRuntime, userId: Int?, allowedGroups: Set<Int>?): Boolean =
        if (a.ownerId != null) a.ownerId == userId else canUseGlobal(a, allowedGroups)

    /** A user may use an ungrouped global account always, or a grouped one only if its group is allowed. */
    private fun canUseGlobal(a: AccountRuntime, allowedGroups: Set<Int>?): Boolean {
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

    /** Nearest upcoming reset for a given window across the global pool (dashboard hint). */
    suspend fun nextReset(kind: org.claudeproxy.model.WindowKind, now: Instant = Instant.now()): Instant? = mutex.withLock {
        accounts.values.filter { it.ownerId == null }
            .mapNotNull { it.limit.window(kind)?.resetAt }.filter { it.isAfter(now) }.minOrNull()
    }

    /** Nearest upcoming reset for a given window across a user's personal accounts. */
    suspend fun nextResetOwned(userId: Int, kind: org.claudeproxy.model.WindowKind, now: Instant = Instant.now()): Instant? = mutex.withLock {
        accounts.values.filter { it.ownerId == userId }
            .mapNotNull { it.limit.window(kind)?.resetAt }.filter { it.isAfter(now) }.minOrNull()
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
        // Append utilization history points for the trend graphs (throttled internally).
        updated.limit.windows.forEach { (kind, w) ->
            w.utilization?.let { org.claudeproxy.repo.WindowSnapshotRepo.record(id, kind, it) }
        }
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
