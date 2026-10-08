package org.claudeproxy.auth

import org.claudeproxy.repo.IdentityRepo
import org.claudeproxy.repo.UserAuth
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

sealed interface OidcLoginResult {
    data class Success(val user: UserAuth, val how: How) : OidcLoginResult
    /** [reason] is for the server log only; the browser gets [error]. */
    data class Denied(val error: OidcError, val reason: String) : OidcLoginResult

    enum class How { LINKED, LINKED_BY_EMAIL, PROVISIONED }
}

/**
 * Decides whether a *validated* OIDC identity may enter the panel, and as which local user.
 *
 * Order matters and is deliberately strict:
 *  1. Gates first (groups, email domain) — they apply to every login, including already linked
 *     users, so removing someone from the IdP group locks them out on their next login.
 *  2. A returning identity is matched on (issuer, sub) only. Never on username, never on email.
 *  3. Only an *unlinked* identity may be linked by email, only when enabled, only with
 *     `email_verified=true`, only to an enabled local user that has no identity for this issuer.
 *  4. Otherwise JIT provisioning (when enabled) creates a least-privileged `user`.
 * A disabled local user is refused at every step.
 */
class OidcLogin(private val config: OidcConfig) {
    private val log = LoggerFactory.getLogger("OidcLogin")

    fun resolve(id: OidcIdentity): OidcLoginResult {
        gate(id)?.let { return it }
        return try {
            transaction { resolveInTx(id) }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // A concurrent first login for the same identity/email lost the unique-index race.
            OidcLoginResult.Denied(OidcError.NO_ACCOUNT, "link race: ${e.sqlState}")
        }
    }

    private fun gate(id: OidcIdentity): OidcLoginResult.Denied? {
        if (config.allowedGroups.isNotEmpty()) {
            val groups = id.groups ?: return OidcLoginResult.Denied(OidcError.FORBIDDEN, "groups claim '${config.groupsClaim}' missing")
            if (groups.none { it in config.allowedGroups }) {
                return OidcLoginResult.Denied(OidcError.FORBIDDEN, "not in any allowed group")
            }
        }
        if (config.allowedEmailDomains.isNotEmpty()) {
            val email = id.email
            if (email == null || !id.emailVerified) {
                return OidcLoginResult.Denied(OidcError.FORBIDDEN, "email domain gate needs a verified email")
            }
            // Exact match on the part after the single '@' — no suffix/subdomain matching.
            val domain = email.substringAfterLast('@')
            if (email.count { it == '@' } != 1 || domain !in config.allowedEmailDomains) {
                return OidcLoginResult.Denied(OidcError.FORBIDDEN, "email domain not allowed")
            }
        }
        return null
    }

    private fun resolveInTx(id: OidcIdentity): OidcLoginResult {
        IdentityRepo.userIdFor(id.issuer, id.subject)?.let { uid ->
            val user = UserRepo.findAuth(uid)
            if (user == null || !user.enabled) return OidcLoginResult.Denied(OidcError.FORBIDDEN, "linked user $uid is disabled")
            IdentityRepo.touch(id.issuer, id.subject)
            return OidcLoginResult.Success(user, OidcLoginResult.How.LINKED)
        }

        val verifiedEmail = id.email?.takeIf { id.emailVerified }

        if (config.linkByEmail && verifiedEmail != null) {
            val uid = UserRepo.userIdByEmail(verifiedEmail)
            if (uid != null) {
                val user = UserRepo.findAuth(uid)
                if (user == null || !user.enabled) return OidcLoginResult.Denied(OidcError.FORBIDDEN, "email-matched user $uid is disabled")
                if (IdentityRepo.hasIdentityFor(uid, id.issuer)) {
                    // Someone else's IdP account already owns this local user: never re-point it.
                    return OidcLoginResult.Denied(OidcError.NO_ACCOUNT, "email-matched user $uid is already linked to another subject")
                }
                IdentityRepo.link(uid, id.issuer, id.subject)
                log.info("OIDC: linked subject to existing user id={} by verified email", uid)
                return OidcLoginResult.Success(user, OidcLoginResult.How.LINKED_BY_EMAIL)
            }
        }

        if (config.jitProvisioning) {
            val username = uniqueUsername(id)
            // Keep the verified email only if no other user holds it (with linking off it may still match one).
            val email = verifiedEmail?.takeIf { UserRepo.userIdByEmail(it) == null }
            val uid = UserRepo.createProvisioned(username, email)
            IdentityRepo.link(uid, id.issuer, id.subject)
            log.info("OIDC: provisioned user id={} username={}", uid, username)
            return OidcLoginResult.Success(UserRepo.findAuth(uid)!!, OidcLoginResult.How.PROVISIONED)
        }

        return OidcLoginResult.Denied(OidcError.NO_ACCOUNT, "no linked account (linkByEmail=${config.linkByEmail}, emailVerified=${id.emailVerified})")
    }

    /** preferred_username → email local part → "user", restricted to a safe charset; suffixed on collision. */
    private fun uniqueUsername(id: OidcIdentity): String {
        val raw = id.preferredUsername ?: id.email?.substringBefore('@') ?: "user"
        // ASCII only: no look-alike (homoglyph) usernames next to existing ones.
        val base = raw.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-@" }.take(64).ifEmpty { "user" }
        if (!UserRepo.usernameExists(base)) return base
        for (i in 2..50) {
            val candidate = "$base-$i"
            if (!UserRepo.usernameExists(candidate)) return candidate
        }
        return "$base-${org.claudeproxy.db.Crypto.randomToken(4)}"
    }
}
