package org.claudeproxy.datapath

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.claudeproxy.Config
import org.claudeproxy.accounts.*
import org.claudeproxy.api.UsageReport
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.*
import org.claudeproxy.model.*
import org.claudeproxy.repo.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.*

class OpenAIProviderTest {
    private lateinit var db: File
    private lateinit var pool: AccountPool
    private lateinit var token: String
    private var user = 0
    private var claude = 0
    private var shared = 0
    private var personal = 0

    @BeforeTest fun setup() = runBlocking {
        db = File.createTempFile("openai-provider", ".db")
        val key = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(key))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=key, sessionSecret=key, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
        user = UserRepo.create("alice", "pw", listOf("user"), emptyList(), null)
        val other = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        claude = account("claude", provider=AccountProvider.ANTHROPIC, priority=1)
        shared = account("openai-shared")
        personal = account("openai-personal", owner=user)
        account("other-personal", owner=other, priority=1)
        account("forbidden-group", group=GroupRepo.create("private"), priority=1)
        token = RoutingTokenRepo.create(user, "native").token!!
        pool = AccountPool(); pool.reload()
    }

    private fun account(name: String, provider: AccountProvider=AccountProvider.OPENAI,
                        owner: Int?=null, group: Int?=null, priority: Int=10, type: AccountType=AccountType.OAUTH) =
        AccountRepo.create(name, type, group, priority, 0.9, 1.0,
            AccountSecret(accessToken="access-$name", refreshToken="refresh-$name", apiKey="key-$name"),
            createdBy=null, ownerId=owner, accountUuid="account-$name", provider=provider)

    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }

    @Test fun `provider persists and defaults never route Claude to OpenAI`() = runBlocking {
        assertEquals(AccountProvider.OPENAI, AccountRepo.loadAll().first { it.first.id == shared }.first.provider)
        val service = DatapathService(pool)
        val old = service.resolve(token,"POST","/v1/messages", source="routing")
        assertEquals(listOf(claude), old.candidates.map { it.accountId })
        val native = service.resolve(token,"POST","/v1/responses", source="routing", provider=AccountProvider.OPENAI)
        assertEquals(listOf(personal,shared), native.candidates.map { it.accountId })
        assertTrue(native.candidates.all { it.provider == "OPENAI" })
        assertEquals(mapOf("Authorization" to "Bearer access-openai-personal", "ChatGPT-Account-Id" to "account-openai-personal"), native.candidates.first().authHeaders)
        assertEquals(listOf(claude), pool.selectAnyOrder(user,emptySet()).map { it.id })
        assertEquals(listOf(claude), pool.selectionOrder(user,emptySet()).map { it.id })
        assertEquals(claude, pool.select(user,emptySet())?.id)
        assertEquals(1,pool.availability(user,emptySet()).enabledInScope)
        assertTrue(pool.selectionOrderOwned(user).isEmpty())
    }

    @Test fun `Claude model discovery never sends OpenAI credentials to Anthropic`() = runBlocking {
        AccountRepo.delete(claude); pool.reload()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { it.write("{\"data\":[]}".toByteArray()) }
        }
        server.start()
        try {
            org.claudeproxy.chat.ChatModels.list(pool,"http://127.0.0.1:${server.address.port}")
            assertEquals(0,requests.get(),"OpenAI secrets must not reach the Anthropic model-list upstream")
        } finally { server.stop(0) }
    }

    @Test fun `provider and known cost migration preserves existing Claude records`() {
        transaction {
            exec("ALTER TABLE accounts DROP COLUMN provider")
            exec("ALTER TABLE usage_events DROP COLUMN cost_known")
            org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns(Accounts,UsageEvents)
            assertTrue(Accounts.select(Accounts.provider).all { it[Accounts.provider] == "ANTHROPIC" })
        }
    }

    @Test fun `native free models ignore limits but keep provider and scope`() = runBlocking {
        pool.markRateLimited(personal, Instant.now().plusSeconds(600))
        pool.markRateLimited(shared, Instant.now().plusSeconds(600))
        val service = DatapathService(pool)
        val blocked = service.resolve(token,"POST","/v1/responses", source="routing", provider=AccountProvider.OPENAI)
        assertTrue(blocked.candidates.isEmpty())
        val models = service.resolve(token,"GET","/v1/models", source="routing", provider=AccountProvider.OPENAI)
        assertTrue(models.free)
        assertEquals(listOf(personal,shared), models.candidates.map { it.accountId })
        assertTrue(pool.selectAnyOrder(user,emptySet(), allowGlobal=false, provider=AccountProvider.OPENAI).all { it.ownerId == user })
    }

    @Test fun `OpenAI API keys use bearer without Claude or account headers and probes are free`() = runBlocking {
        val id = account("api", owner=user, priority=0, type=AccountType.API_KEY)
        pool.reload()
        val candidate = DatapathService(pool).resolve(token,"POST","/v1/responses", source="routing", provider=AccountProvider.OPENAI).candidates.first()
        assertEquals(id,candidate.accountId)
        assertEquals(mapOf("Authorization" to "Bearer key-api"),candidate.authHeaders)
        assertEquals(LimitProbe.Outcome.REACHED,LimitProbe(pool,"https://must-not-be-called.invalid").probe(id))
        assertEquals(0L,UsageRepo.userTotals(user).requests)
    }

    @Test fun `concurrent refresh exchanges a rotating token once and persists its replacement`() = runBlocking {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/oauth/token") { exchange ->
            requests.incrementAndGet()
            val body = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""".toByteArray()
            exchange.requestBody.close()
            exchange.sendResponseHeaders(200,body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        System.setProperty("OPENAI_OAUTH_ISSUER","http://127.0.0.1:${server.address.port}")
        try {
            val refresher = TokenRefresher(pool)
            listOf(async { refresher.refreshOne(personal,"refresh-openai-personal") },
                async { refresher.refreshOne(personal,"refresh-openai-personal") }).awaitAll()
            assertEquals(1,requests.get())
            assertEquals("new-refresh",pool.get(personal)!!.secret.refreshToken)
            assertEquals("new-access",AccountRepo.loadAll().first { it.first.id == personal }.first.secret.accessToken)
            assertEquals("account-openai-personal",pool.get(personal)!!.accountUuid)
            // A rejected access token needs recovery even when its declared expiry is hours away.
            pool.snapshot().filter { it.id != personal }.forEach { pool.setHealth(it.id,AccountHealth.DEAD) }
            pool.setHealth(personal,AccountHealth.REFRESH_FAILED)
            refresher.tick()
            assertEquals(2,requests.get())
            assertEquals(AccountHealth.OK,pool.get(personal)!!.health)
        } finally { System.clearProperty("OPENAI_OAUTH_ISSUER"); server.stop(0) }
    }

    @Test fun `OpenAI quota probe recovers a reset and preserves failures`() = runBlocking {
        var rejected = true
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/wham/usage") { exchange ->
            val body = if (rejected) "{}" else """{"rate_limit":{"allowed":true,"primary_window":{"limit_window_seconds":18000,"used_percent":0,"reset_after_seconds":18000}}}"""
            exchange.sendResponseHeaders(if (rejected) 401 else 200,body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        System.setProperty("OPENAI_CHATGPT_BASE_URL","http://127.0.0.1:${server.address.port}")
        try {
            pool.markRateLimited(personal,Instant.now().plusSeconds(18000))
            val probe = LimitProbe(pool,"https://must-not-be-called.invalid")
            assertEquals(LimitProbe.Outcome.FAILED,probe.probe(personal))
            assertEquals(AccountHealth.REFRESH_FAILED,pool.get(personal)!!.health)
            assertNotNull(pool.get(personal)!!.limit.rateLimitedUntil)
            rejected = false
            assertEquals(LimitProbe.Outcome.OBSERVED,probe.probe(personal))
            assertEquals(AccountHealth.OK,pool.get(personal)!!.health)
            assertNull(pool.get(personal)!!.limit.rateLimitedUntil)
            assertEquals(0.0,pool.get(personal)!!.limit.window(WindowKind.FIVE_HOUR)!!.utilization)
            assertEquals(0L,UsageRepo.userTotals(user).requests)
        } finally { System.clearProperty("OPENAI_CHATGPT_BASE_URL"); server.stop(0) }
    }

    @Test fun `OpenAI cannot use a Claude proxy token or proxy source`() = runBlocking {
        val proxy = ProxyTokenRepo.create(user,"proxy").token!!
        val service = DatapathService(pool)
        assertEquals(ResolveError.NO_PERMISSION,service.resolve(proxy,"POST","/v1/responses", provider=AccountProvider.OPENAI).error)
        assertEquals(ResolveError.BAD_TOKEN,service.resolve(proxy,"POST","/v1/responses", source="routing", provider=AccountProvider.OPENAI).error)
    }

    @Test fun `capped shared usage requires known price while personal fallback stays available`() = runBlocking {
        UserRepo.update(user,null,null,null,null,null,false,dailyRoutingCostLimit=10.0)
        val service = DatapathService(pool)
        suspend fun resolve() = service.resolve(token,"POST","/v1/responses",source="routing",provider=AccountProvider.OPENAI,model="gpt-test")
        val noPrice = resolve()
        assertTrue(noPrice.priceMissing)
        assertEquals(listOf(personal),noPrice.candidates.map { it.accountId })
        ModelPriceRepo.set("gpt-test",2.0,8.0,0.5,0.0,0.0,1.0,0.0)
        val priced = resolve()
        assertFalse(priced.priceMissing)
        assertEquals(listOf(personal,shared),priced.candidates.map { it.accountId })
        service.applyOutcome(UsageReport(accountId=shared,userId=user,source="routing",status=200,model="gpt-test",input=1_000_000,output=1_000_000,cacheRead=1_000_000))
        assertEquals(10.5,service.cachedDailySpend(user,"routing"))
        assertTrue(resolve().overLimit)
        assertEquals(listOf(personal),resolve().candidates.map { it.accountId })
    }

    @Test fun `unknown OpenAI costs are marked and do not inherit Claude substring rates`() = runBlocking {
        val service = DatapathService(pool)
        service.applyOutcome(UsageReport(accountId=shared,userId=user,source="routing",status=200,model="gpt-opus-future",input=1_000_000))
        transaction {
            val row = UsageEvents.selectAll().single()
            assertEquals(0.0,row[UsageEvents.cost])
            assertFalse(row[UsageEvents.costKnown])
            assertEquals(1_000_000L,row[UsageEvents.inputTokens])
        }
        ModelPriceRepo.set("openai/gpt-opus-future",3.0,10.0,0.0,0.0,0.0,1.0,0.0)
        assertTrue(ModelPriceRepo.hasPrice("gpt-opus-future",AccountProvider.OPENAI))
        assertEquals(3.0,ModelPriceRepo.costOf("gpt-opus-future",BilledUsage(input=1_000_000),AccountProvider.OPENAI))
    }
}
