package org.claudeproxy.api

import org.claudeproxy.repo.WindowSample
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [aggregateWindowDaily] turns the raw utilization series into "how much of the window was burned
 * each day". Everything interesting lives in the reset detection and the day keying, both of which
 * are pure over the sample list — no DB or route wiring needed.
 */
class WindowDailyAggregationTest {
    private val days = listOf("2026-07-20", "2026-07-21", "2026-07-22")

    /** Sample at [day] (index into [days]) and [hour], in the given zone. */
    private fun sample(
        acct: Int, day: Int, hour: Int, util: Double, kind: String = "5h",
        zone: ZoneId = ZoneOffset.UTC, minute: Int = 0,
    ) = WindowSample(
        acct, kind,
        java.time.LocalDate.parse(days[day]).atTime(hour, minute).atZone(zone).toInstant(),
        util, 1.0,
    )

    private fun assertSeries(expected: List<Double>, actual: List<Double>, msg: String) {
        assertEquals(expected.size, actual.size, "$msg: size")
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertTrue(kotlin.math.abs(e - a) < 1e-9, "$msg[$i] expected $e got $a")
        }
    }

    private fun aggregate(samples: List<WindowSample>, zone: ZoneId = ZoneOffset.UTC, includeAccounts: Boolean = true) =
        aggregateWindowDaily(samples, zone, days, includeAccounts, mapOf(1 to "acc1", 2 to "acc2"))

    @Test
    fun `a rising series burns the difference, first sample is only a baseline`() {
        // 0.10 → 0.30 → 0.60 within one day: 0.50 burned. The opening 0.10 has nothing to diff
        // against, so it is a baseline, not spend (the range lookback is what recovers it).
        val p = aggregate(listOf(
            sample(1, 1, 1, 0.10), sample(1, 1, 5, 0.30), sample(1, 1, 9, 0.60),
        ))
        assertSeries(listOf(0.0, 0.5, 0.0), p.totalFiveHour, "totalFiveHour")
        assertSeries(listOf(0.0, 0.0, 0.0), p.totalWeekly, "totalWeekly")
    }

    @Test
    fun `a drop means the window reset, so the new reading counts in full`() {
        // 0.10 (baseline) → 0.90 → [reset] 0.20 → 0.50
        //  = 0.80 + 0.20 + 0.30 = 1.30 of window burned that day
        val p = aggregate(listOf(
            sample(1, 1, 0, 0.10), sample(1, 1, 4, 0.90),
            sample(1, 1, 6, 0.20), sample(1, 1, 10, 0.50),
        ))
        assertSeries(listOf(0.0, 1.3, 0.0), p.totalFiveHour, "one reset")
    }

    @Test
    fun `several resets in a day accumulate past 100 percent`() {
        // three near-full 5h windows in one day — the gauge never reads above 1.0, the burn does
        val p = aggregate(listOf(
            sample(1, 1, 0, 0.0),
            sample(1, 1, 4, 0.95), sample(1, 1, 5, 0.90), // reset → 0.90 fresh
            sample(1, 1, 10, 0.98), sample(1, 1, 11, 0.85), // reset → 0.85 fresh
        ))
        // 0.95 + 0.90 + 0.08 + 0.85 = 2.78
        assertSeries(listOf(0.0, 2.78, 0.0), p.totalFiveHour, "multiple resets")
    }

    @Test
    fun `a sample before the range acts as the baseline for the first day`() {
        // The last sample of the previous day continues the series: day 0 opens at 0.20, not 0.
        val prior = WindowSample(1, "5h", Instant.parse("2026-07-19T23:30:00Z"), 0.20, 1.0)
        val p = aggregate(listOf(prior, sample(1, 0, 2, 0.70)))
        assertSeries(listOf(0.5, 0.0, 0.0), p.totalFiveHour, "prior baseline")
    }

    @Test
    fun `burn is attributed to the day the sample lands in, per timezone`() {
        // Same instants, two zones. At UTC+03:00 the 22:00 UTC reading is already the next day,
        // so its step moves with it.
        val samples = listOf(
            sample(1, 0, 12, 0.10), sample(1, 0, 22, 0.60), // +0.50
            sample(1, 1, 12, 0.90),                          // +0.30
        )
        assertSeries(listOf(0.5, 0.3, 0.0), aggregate(samples).totalFiveHour, "utc")
        assertSeries(listOf(0.0, 0.8, 0.0), aggregate(samples, ZoneId.of("+03:00")).totalFiveHour, "utc+3")
    }

    @Test
    fun `five-hour and weekly windows are tracked apart`() {
        val p = aggregate(listOf(
            sample(1, 1, 0, 0.10, kind = "5h"), sample(1, 1, 4, 0.50, kind = "5h"),
            sample(1, 1, 0, 0.30, kind = "7d"), sample(1, 1, 4, 0.35, kind = "7d"),
        ))
        assertSeries(listOf(0.0, 0.4, 0.0), p.totalFiveHour, "5h lane")
        assertSeries(listOf(0.0, 0.05, 0.0), p.totalWeekly, "7d lane")
    }

    @Test
    fun `totals sum across accounts and per-account series are sorted by burn`() {
        val p = aggregate(listOf(
            sample(1, 1, 0, 0.0), sample(1, 1, 4, 0.20),
            sample(2, 1, 0, 0.0), sample(2, 1, 4, 0.70),
        ))
        assertSeries(listOf(0.0, 0.9, 0.0), p.totalFiveHour, "pool total")
        assertEquals(listOf(2, 1), p.perAccount.map { it.accountId }, "biggest burner first")
        assertEquals("acc2", p.perAccount.first().accountName)
        assertSeries(listOf(0.0, 0.7, 0.0), p.perAccount.first().fiveHour, "acc2 series")
    }

    @Test
    fun `per-account series are withheld when the viewer cannot see accounts`() {
        val p = aggregate(listOf(sample(1, 1, 0, 0.0), sample(1, 1, 4, 0.20)), includeAccounts = false)
        assertTrue(p.perAccount.isEmpty(), "perAccount hidden")
        assertSeries(listOf(0.0, 0.2, 0.0), p.totalFiveHour, "total still reported")
    }

    @Test
    fun `no samples yields zeros, not an empty series`() {
        val p = aggregate(emptyList())
        assertEquals(days, p.days)
        assertSeries(listOf(0.0, 0.0, 0.0), p.totalFiveHour, "zeros")
        assertSeries(listOf(0.0, 0.0, 0.0), p.totalWeekly, "zeros")
        assertTrue(p.perAccount.isEmpty())
    }

    @Test
    fun `a single sample burns nothing - there is no step to measure`() {
        val p = aggregate(listOf(sample(1, 1, 4, 0.75)))
        assertSeries(listOf(0.0, 0.0, 0.0), p.totalFiveHour, "single sample")
    }

    @Test
    fun `samples arrive out of order and are still read chronologically`() {
        val p = aggregate(listOf(
            sample(1, 1, 9, 0.60), sample(1, 1, 1, 0.10), sample(1, 1, 5, 0.30),
        ))
        assertSeries(listOf(0.0, 0.5, 0.0), p.totalFiveHour, "reordered")
    }

    // ---- coefficient-weighted 5-hour burn (base-subscription scale) ----

    /** Sample carrying an explicit capacity coefficient. */
    private fun weighted(acct: Int, day: Int, hour: Int, util: Double, coef: Double, kind: String = "5h") =
        WindowSample(
            acct, kind,
            java.time.LocalDate.parse(days[day]).atTime(hour, 0).atZone(ZoneOffset.UTC).toInstant(),
            util, coef,
        )

    @Test
    fun `weighted 5h burn restates each account's spend in base-subscription windows`() {
        // acct1 is a x5 plan and burns 0.4 of its own window; acct2 is x1 and burns 0.4 of its own.
        // Raw, they are equal. On the base scale acct1 spent 5x as much real capacity.
        val samples = listOf(
            weighted(1, 0, 1, 0.1, 5.0), weighted(1, 0, 3, 0.5, 5.0),
            weighted(2, 0, 1, 0.1, 1.0), weighted(2, 0, 3, 0.5, 1.0),
        )
        val p = aggregate(samples)

        assertSeries(listOf(0.8, 0.0, 0.0), p.totalFiveHour, "raw total")          // 0.4 + 0.4
        assertSeries(listOf(2.4, 0.0, 0.0), p.totalFiveHourWeighted, "weighted")   // 0.4*5 + 0.4*1

        val a1 = p.perAccount.single { it.accountId == 1 }
        assertSeries(listOf(0.4, 0.0, 0.0), a1.fiveHour, "acct1 raw")
        assertSeries(listOf(2.0, 0.0, 0.0), a1.fiveHourWeighted, "acct1 weighted")
    }

    @Test
    fun `the weekly window is never weighted`() {
        // The weekly budget is the same size on every plan, so a x20 account's weekly burn must not
        // be inflated -- and there is deliberately no weighted weekly series to inflate.
        val samples = listOf(
            weighted(1, 0, 1, 0.1, 20.0, kind = "7d"),
            weighted(1, 0, 5, 0.4, 20.0, kind = "7d"),
        )
        val p = aggregate(samples)

        assertSeries(listOf(0.3, 0.0, 0.0), p.totalWeekly, "weekly stays raw")
        // Weekly samples contribute nothing to the 5h lanes, weighted included.
        assertSeries(listOf(0.0, 0.0, 0.0), p.totalFiveHourWeighted, "weekly must not leak into weighted 5h")
    }

    @Test
    fun `a reset counts the fresh reading in full on the weighted scale too`() {
        // 0.2 -> 0.9 -> 0.3 (reset): 0.7 then the whole 0.3 = 1.0 of its own window, x5 = 5.0.
        val samples = listOf(
            weighted(1, 0, 1, 0.2, 5.0), weighted(1, 0, 3, 0.9, 5.0), weighted(1, 0, 6, 0.3, 5.0),
        )
        val p = aggregate(samples)
        assertSeries(listOf(1.0, 0.0, 0.0), p.totalFiveHour, "raw")
        assertSeries(listOf(5.0, 0.0, 0.0), p.totalFiveHourWeighted, "weighted")
    }
}
