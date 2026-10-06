package org.claudeproxy.auth

import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.datapath.ResolveError
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.Users
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.*

class DisabledOwnerTest {
    private lateinit var dbFile: File
    private var userId = 0
    private val secret = "test-session-secret-at-least-32-chars"

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("revocation-test", ".db")
        val config = Config(
            bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbFile.absolutePath,
            masterKey = secret, sessionSecret = secret, adminUser = "admin", adminPassword = "admin",
            upstreamBaseUrl = "https://api.anthropic.com", publicBaseUrl = "", databaseUrl = "",
            databaseUser = "claudeproxy", databasePassword = "", internalToken = null,
        )
        Secrets.init(Crypto(secret))
        Db.init(config)
        MemoryCache.clear()
        userId = transaction { Users.selectAll().first()[Users.id] }
    }

    @AfterTest
    fun cleanup() { MemoryCache.clear(); dbFile.delete() }

    private fun update(enabled: Boolean? = null, password: String? = null) = UserRepo.update(
        userId, password, enabled, null, null, null, false,
    )

    @Test
    fun `disabled user loses both datapaths with warm token cache`() = runBlocking {
        val proxy = ProxyTokenRepo.create(userId, "proxy").token!!
        val routing = RoutingTokenRepo.create(userId, "routing").token!!
        val service = DatapathService(AccountPool())
        assertNull(service.resolve(proxy, "POST", "/v1/messages").error)
        assertNull(service.resolve(routing, "POST", "/v1/messages", "routing").error)
        val proxyAuth = ProxyTokenRepo.resolveAuth(proxy)!!
        val routingAuth = RoutingTokenRepo.resolveAuth(routing)!!
        update(enabled = false)
        // Upstream now evicts both mappings and refuses to load a disabled owner.
        assertNull(ProxyTokenRepo.resolveAuth(proxy))
        assertNull(RoutingTokenRepo.resolveAuth(routing))
        assertEquals(ResolveError.BAD_TOKEN, service.resolve(proxy, "POST", "/v1/messages").error)
        assertEquals(ResolveError.BAD_TOKEN, service.resolve(routing, "POST", "/v1/messages", "routing").error)
        // Simulate an in-flight lookup repopulating stale cache after eviction. Our live-state
        // authorization gate must still reject both datapaths.
        MemoryCache.getOrLoad("cp:tok:${Crypto.sha256Hex(proxy)}", 60) { "${proxyAuth.userId}:${proxyAuth.tokenId}" }
        MemoryCache.getOrLoad("cp:rtok:${Crypto.sha256Hex(routing)}", 60) { "${routingAuth.userId}:${routingAuth.tokenId}" }
        assertNotNull(ProxyTokenRepo.resolveAuth(proxy))
        assertNotNull(RoutingTokenRepo.resolveAuth(routing))
        assertEquals(ResolveError.NO_PERMISSION, service.resolve(proxy, "POST", "/v1/messages").error)
        assertEquals(ResolveError.NO_PERMISSION, service.resolve(routing, "POST", "/v1/messages", "routing").error)
        assertTrue(UserRepo.permissionsOf(userId).isEmpty()) // Kotlin fallback uses this same gate.
        update(enabled = true)
        assertNull(service.resolve(proxy, "POST", "/v1/messages").error)
    }

}
