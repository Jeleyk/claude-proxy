package org.claudeproxy.auth

import java.io.File
import java.net.URI

/**
 * Opt-in OpenID Connect login for the panel. Absent `OIDC_ENABLED=true` nothing here is active
 * and the panel behaves exactly as before (password login only).
 *
 * Every policy knob defaults to the restrictive side: no email linking, no JIT provisioning,
 * password login stays on (it is the break-glass path when the IdP is down).
 */
data class OidcConfig(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: URI,
    val scopes: List<String>,
    val groupsClaim: String,
    /** Empty = no group gate (the IdP's own app binding is then the only gate). */
    val allowedGroups: Set<String>,
    /** Lower-cased; empty = any domain. Matching needs a verified email. */
    val allowedEmailDomains: Set<String>,
    val buttonLabel: String,
    val disablePasswordLogin: Boolean,
    /** First login may claim an unlinked local user whose admin-set email equals the verified email. */
    val linkByEmail: Boolean,
    /** First login with no matching account creates a least-privileged `user` (no account groups). */
    val jitProvisioning: Boolean,
    /** Server-enforced lifetime of a panel session established through OIDC. */
    val sessionTtlSeconds: Long,
) {
    /** Where the browser has to start the flow: cookies are host-bound, so start on the callback's origin. */
    val startUrl: String get() = originOf(redirectUri) + START_PATH

    /** The transaction cookie only gets `Secure` when the callback is HTTPS — it would never be sent back otherwise. */
    val secureCookies: Boolean get() = redirectUri.scheme.equals("https", ignoreCase = true)

    override fun toString(): String =
        "OidcConfig(issuer=$issuer, clientId=$clientId, clientSecret=<redacted>, redirectUri=$redirectUri, " +
            "scopes=$scopes, groupsClaim=$groupsClaim, allowedGroups=$allowedGroups, " +
            "allowedEmailDomains=$allowedEmailDomains, disablePasswordLogin=$disablePasswordLogin, " +
            "linkByEmail=$linkByEmail, jitProvisioning=$jitProvisioning, sessionTtlSeconds=$sessionTtlSeconds)"

    companion object {
        const val START_PATH = "/api/auth/oidc/start"
        const val CALLBACK_PATH = "/api/auth/oidc/callback"
        const val DEFAULT_SESSION_TTL_HOURS = 12L

        /**
         * Reads `OIDC_*` settings. Returns null when OIDC is not enabled; throws when it is enabled
         * but misconfigured, so a typo fails the boot instead of silently weakening the gate.
         */
        fun load(env: (String) -> String?): OidcConfig? {
            if (!env("OIDC_ENABLED").toBool()) return null
            fun required(key: String) = env(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("$key is required when OIDC_ENABLED=true")

            val issuer = required("OIDC_ISSUER")
            val issuerUri = URI(issuer)
            require(issuerUri.scheme == "https" || isLoopback(issuerUri)) {
                "OIDC_ISSUER must be an https URL (plain http is only accepted for localhost)"
            }

            val secretFile = env("OIDC_CLIENT_SECRET_FILE")?.trim()?.takeIf { it.isNotEmpty() }
            val secret = if (secretFile != null) {
                File(secretFile).readText().trim()
            } else {
                env("OIDC_CLIENT_SECRET")?.trim().orEmpty()
            }
            require(secret.isNotEmpty()) {
                "OIDC_CLIENT_SECRET_FILE (preferred) or OIDC_CLIENT_SECRET is required when OIDC_ENABLED=true"
            }

            val redirect = URI(required("OIDC_REDIRECT_URI"))
            require(redirect.isAbsolute && (redirect.scheme == "https" || redirect.scheme == "http") && redirect.host != null) {
                "OIDC_REDIRECT_URI must be an absolute http(s) URL"
            }
            require(redirect.rawPath == CALLBACK_PATH && redirect.rawQuery == null && redirect.rawFragment == null) {
                "OIDC_REDIRECT_URI must point at <panel origin>$CALLBACK_PATH"
            }

            val scopes = (env("OIDC_SCOPES") ?: "openid email profile").split(' ', ',')
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require("openid" in scopes) { "OIDC_SCOPES must include openid" }

            val ttlHours = env("OIDC_SESSION_TTL_HOURS")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { it.toLongOrNull() ?: error("OIDC_SESSION_TTL_HOURS must be a whole number of hours") }
                ?: DEFAULT_SESSION_TTL_HOURS
            require(ttlHours in 1..(SESSION_TTL_SECONDS / 3600)) {
                "OIDC_SESSION_TTL_HOURS must be between 1 and ${SESSION_TTL_SECONDS / 3600}"
            }

            return OidcConfig(
                issuer = issuer,
                clientId = required("OIDC_CLIENT_ID"),
                clientSecret = secret,
                redirectUri = redirect,
                scopes = scopes,
                groupsClaim = env("OIDC_GROUPS_CLAIM")?.trim()?.takeIf { it.isNotEmpty() } ?: "groups",
                allowedGroups = env("OIDC_ALLOWED_GROUPS").csv().toSet(),
                allowedEmailDomains = env("OIDC_ALLOWED_EMAIL_DOMAINS").csv().map { it.lowercase().removePrefix("@") }.toSet(),
                buttonLabel = env("OIDC_BUTTON_LABEL")?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: "Sign in with SSO",
                disablePasswordLogin = env("OIDC_DISABLE_PASSWORD_LOGIN").toBool(),
                linkByEmail = env("OIDC_LINK_BY_EMAIL").toBool(),
                jitProvisioning = env("OIDC_JIT_PROVISIONING").toBool(),
                sessionTtlSeconds = ttlHours * 3600,
            )
        }

        private fun String?.toBool(): Boolean = this?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

        private fun String?.csv(): List<String> =
            this.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun isLoopback(u: URI) = u.scheme == "http" && (u.host == "localhost" || u.host == "127.0.0.1")

        internal fun originOf(u: URI): String =
            "${u.scheme}://${u.host}" + (if (u.port == -1) "" else ":${u.port}")
    }
}
