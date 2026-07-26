package org.claudeproxy.repo

import org.claudeproxy.Config
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.ModelPrices
import org.claudeproxy.accounts.Secrets
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Money. Every charge Anthropic applies must be reproduced here, because the number
 * [ModelPriceRepo.costOf] returns is both what the stats show and what the per-user daily limit
 * spends against.
 */
class ModelPricingTest {
    private lateinit var dbFile: File

    private fun config(dbPath: String) = Config(
        bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbPath,
        masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
        adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
        publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
        internalToken = null,
    )

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("pricing-test", ".db")
        val cfg = config(dbFile.absolutePath)
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
    }

    private fun assertMoney(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected \$$expected, got \$$actual")

    /** Opus 5: input 5, output 25, cache read 0.5, 5m write 6.25, 1h write 10 — per 1M. */
    @Test
    fun `cache writes are priced by their TTL`() {
        val fiveMinute = ModelPriceRepo.costOf(
            "claude-opus-5", BilledUsage(cacheWrite5m = 1_000_000),
        )
        val oneHour = ModelPriceRepo.costOf(
            "claude-opus-5", BilledUsage(cacheWrite1h = 1_000_000),
        )
        assertMoney(6.25, fiveMinute)
        assertMoney(10.0, oneHour)
        // Claude Code writes its main-loop prefix with ttl:"1h": pricing that at the 5m rate is
        // the ~60% under-count this split exists to fix.
        assertTrue(oneHour > fiveMinute)
    }

    @Test
    fun `a full response sums every token kind`() {
        val cost = ModelPriceRepo.costOf(
            "claude-opus-5",
            BilledUsage(
                input = 1_000_000, output = 1_000_000, cacheRead = 1_000_000,
                cacheWrite5m = 1_000_000, cacheWrite1h = 1_000_000,
            ),
        )
        assertMoney(5.0 + 25.0 + 0.5 + 6.25 + 10.0, cost)
    }

    @Test
    fun `fast mode applies the premium multiplier`() {
        val standard = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(input = 1_000_000, output = 1_000_000))
        val fast = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(input = 1_000_000, output = 1_000_000, fast = true))
        assertMoney(30.0, standard)
        // Opus 5 fast is $10/$50 against the standard $5/$25 — exactly 2×.
        assertMoney(60.0, fast)
    }

    @Test
    fun `web searches are billed per invocation on top of tokens`() {
        val cost = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(input = 1_000_000, webSearchRequests = 7))
        assertMoney(5.0 + 7 * 0.01, cost)
    }

    /** Fast mode is a tier on the same model: the per-invocation charge is not multiplied. */
    @Test
    fun `fast mode does not multiply the web search charge`() {
        val cost = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(webSearchRequests = 10, fast = true))
        assertMoney(0.10, cost)
    }

    @Test
    fun `unknown models still cost nothing`() {
        assertMoney(0.0, ModelPriceRepo.costOf("some-other-vendor-model", BilledUsage(input = 1_000_000)))
        assertMoney(0.0, ModelPriceRepo.costOf(null, BilledUsage(input = 1_000_000)))
    }

    /**
     * The 1h price column lands on existing databases defaulted to 0.0, and 0.0 means *free* —
     * a worse under-charge than the 5m approximation it replaces. Seeding must repair that.
     */
    @Test
    fun `seeding backfills a missing 1h cache-write price`() {
        transaction {
            ModelPrices.update({ ModelPrices.pattern eq "claude-opus-5" }) { it[cacheWrite1hPrice] = 0.0 }
        }
        ModelPriceRepo.seedDefaults()
        val row = transaction { ModelPrices.selectAll().first { it[ModelPrices.pattern] == "claude-opus-5" } }
        assertEquals(10.0, row[ModelPrices.cacheWrite1hPrice], "2× the \$5 input price")
    }

    /** An operator-set price must survive re-seeding, unlike an unset one. */
    @Test
    fun `seeding does not overwrite an operator-set 1h price`() {
        ModelPriceRepo.set("claude-opus-5", 5.0, 25.0, 0.5, 6.25, cacheWrite1h = 9.0, fastMultiplier = 2.0, webSearchPrice = 0.01)
        ModelPriceRepo.seedDefaults()
        val row = transaction { ModelPrices.selectAll().first { it[ModelPrices.pattern] == "claude-opus-5" } }
        assertEquals(9.0, row[ModelPrices.cacheWrite1hPrice])
    }

    /** A 0 written through the UI must not silently make 1h writes or fast mode free. */
    @Test
    fun `zero prices fall back instead of becoming free`() {
        ModelPriceRepo.set("test-model", 4.0, 20.0, 0.4, 5.0, cacheWrite1h = 0.0, fastMultiplier = 0.0, webSearchPrice = 0.01)
        val row = transaction { ModelPrices.selectAll().first { it[ModelPrices.pattern] == "test-model" } }
        assertEquals(8.0, row[ModelPrices.cacheWrite1hPrice], "derived from 2× input")
        assertEquals(1.0, row[ModelPrices.fastMultiplier], "no premium, not free")
    }

    /** The longest matching pattern wins, so a version row beats the bare family fallback. */
    @Test
    fun `version rows outrank the family fallback`() {
        // Opus 4.1 is the $15 tier; the bare `opus` fallback would price it at $5.
        assertMoney(15.0, ModelPriceRepo.costOf("claude-opus-4-1-20250805", BilledUsage(input = 1_000_000)))
        assertMoney(5.0, ModelPriceRepo.costOf("claude-opus-9-future", BilledUsage(input = 1_000_000)))
    }
}
