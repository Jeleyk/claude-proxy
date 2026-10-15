package org.claudeproxy.auth

import org.claudeproxy.Config
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import java.io.File
import java.net.URI

internal const val TEST_SECRET = "test-session-secret-at-least-32-chars"

/** Fresh SQLite DB per test (seeds the bootstrap admin, id 1). Returns the file to delete. */
internal fun freshDb(): File = freshDbAt(File.createTempFile("oidc-test", ".db"))

/** Runs the service's normal DB init (schema create/upgrade + seeds) on [dbFile]. */
internal fun freshDbAt(dbFile: File): File {
    Secrets.init(Crypto(TEST_SECRET))
    Db.init(
        Config(
            bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbFile.absolutePath,
            masterKey = TEST_SECRET, sessionSecret = TEST_SECRET, adminUser = "admin", adminPassword = "admin",
            upstreamBaseUrl = "https://api.anthropic.com", publicBaseUrl = "", databaseUrl = "",
            databaseUser = "claudeproxy", databasePassword = "", internalToken = null,
        ),
    )
    MemoryCache.clear()
    return dbFile
}

internal fun oidcConfig(
    issuer: String = "https://idp.example.com",
    allowedGroups: Set<String> = emptySet(),
    allowedEmailDomains: Set<String> = emptySet(),
    linkByEmail: Boolean = false,
    jit: Boolean = false,
    jitRole: String? = null,
    disablePasswordLogin: Boolean = false,
    clientSecret: String = "test-client-secret",
    metadataMaxStaleSeconds: Long = OidcConfig.DEFAULT_METADATA_MAX_STALE_HOURS * 3600,
) = OidcConfig(
    issuer = issuer,
    clientId = "claude-proxy",
    clientSecret = clientSecret,
    redirectUri = URI("http://localhost/api/auth/oidc/callback"),
    scopes = listOf("openid", "email", "profile"),
    groupsClaim = "groups",
    allowedGroups = allowedGroups,
    allowedEmailDomains = allowedEmailDomains,
    buttonLabel = "Sign in with SSO",
    disablePasswordLogin = disablePasswordLogin,
    linkByEmail = linkByEmail,
    jitProvisioning = jit,
    jitRole = jitRole,
    sessionTtlSeconds = 3600,
    metadataMaxStaleSeconds = metadataMaxStaleSeconds,
)
