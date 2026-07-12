package org.claudeproxy.accounts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.oauth.ClaudeOAuth
import org.claudeproxy.proxy.Http
import org.slf4j.LoggerFactory

/**
 * Background loop that refreshes OAuth access tokens ~5 minutes before expiry.
 * Accounts whose refresh fails are marked REFRESH_FAILED and excluded from selection
 * until an operator fixes them.
 */
class TokenRefresher(private val pool: AccountPool) {
    private val log = LoggerFactory.getLogger("TokenRefresher")
    private val refreshMarginMs = 5 * 60 * 1000L
    private val pollIntervalMs = 60 * 1000L

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("refresh tick failed: {}", it.message) }
            delay(pollIntervalMs)
        }
    }

    suspend fun tick() {
        val now = System.currentTimeMillis()
        for (acc in pool.snapshot()) {
            if (acc.type != AccountType.OAUTH) continue
            if (acc.health == AccountHealth.DEAD) continue
            val refreshToken = acc.secret.refreshToken ?: continue
            val expiresAt = acc.secret.expiresAt
            val dueSoon = expiresAt == null || expiresAt - now <= refreshMarginMs
            if (!dueSoon) continue
            refreshOne(acc.id, refreshToken)
        }
    }

    suspend fun refreshOne(accountId: Int, refreshToken: String) {
        try {
            val result = ClaudeOAuth.refresh(Http.client, refreshToken)
            val cur = pool.get(accountId)?.secret ?: AccountSecret()
            val updated = cur.copy(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken ?: cur.refreshToken,
                expiresAt = result.expiresAtMillis ?: cur.expiresAt,
            )
            AccountRepo.updateSecret(accountId, updated)
            AccountRepo.updateHealth(accountId, AccountHealth.OK)
            pool.updateSecretInMemory(accountId, updated, AccountHealth.OK)
            log.info("Refreshed token for account {}", accountId)
        } catch (e: Exception) {
            log.warn("Refresh failed for account {}: {}", accountId, e.message)
            AccountRepo.updateHealth(accountId, AccountHealth.REFRESH_FAILED)
            pool.setHealth(accountId, AccountHealth.REFRESH_FAILED)
        }
    }
}
