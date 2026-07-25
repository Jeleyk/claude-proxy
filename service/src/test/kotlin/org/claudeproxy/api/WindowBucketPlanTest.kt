package org.claudeproxy.api

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window-utilization charts must not draw into the future. Sizing the axis to the end of the
 * last calendar day leaves the rest of today empty, and because the aggregator carries the last
 * reading forward across gaps, those buckets render as a flat line running hours ahead of now —
 * which reads as current data. The grid is therefore truncated at `now`, without changing the
 * bucket width.
 */
class WindowBucketPlanTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val halfHourMs = 30 * 60 * 1000.0

    @Test
    fun `a range that already ended keeps the full day grid`() {
        val plan = planWindowBuckets(
            days = 1, endDate = LocalDate.parse("2026-01-10"), zone = utc,
            now = Instant.parse("2026-01-15T12:00:00Z"),
        )
        assertEquals(Instant.parse("2026-01-10T00:00:00Z"), plan.start)
        assertEquals(Instant.parse("2026-01-11T00:00:00Z"), plan.end)
        assertEquals(48, plan.buckets)
        assertEquals(halfHourMs, plan.widthMs)
    }

    @Test
    fun `a range ending today stops at the current bucket, not at midnight`() {
        val now = Instant.parse("2026-01-15T06:10:00Z")
        val plan = planWindowBuckets(days = 1, endDate = LocalDate.parse("2026-01-15"), zone = utc, now = now)

        // Bucket width is unchanged — only the tail is dropped.
        assertEquals(halfHourMs, plan.widthMs)
        // 06:10 falls in the 13th half-hour bucket (06:00–06:30), which is kept: it is partially
        // elapsed but already holds readings.
        assertEquals(13, plan.buckets)
        assertEquals(Instant.parse("2026-01-15T06:30:00Z"), plan.end)
        assertTrue(plan.end.isAfter(now), "the bucket containing now must be included")
        assertTrue(plan.buckets < 48, "the rest of the day must not be charted")
    }

    @Test
    fun `truncation works across a multi-day range`() {
        val now = Instant.parse("2026-01-21T02:00:00Z")
        val plan = planWindowBuckets(days = 7, endDate = LocalDate.parse("2026-01-21"), zone = utc, now = now)

        assertEquals(Instant.parse("2026-01-15T00:00:00Z"), plan.start)
        assertEquals(halfHourMs, plan.widthMs)
        // 6 whole days (288 buckets) + 4 buckets of the 7th day, plus the in-progress one.
        assertEquals(293, plan.buckets)
        assertTrue(plan.end.isAfter(now))
        assertTrue(plan.end.isBefore(Instant.parse("2026-01-22T00:00:00Z")))
    }

    @Test
    fun `a coarsened long range keeps its wider buckets when truncated`() {
        val now = Instant.parse("2026-02-14T00:00:00Z")
        val full = planWindowBuckets(days = 30, endDate = LocalDate.parse("2026-01-30"), zone = utc, now = now)
        val truncated = planWindowBuckets(days = 30, endDate = LocalDate.parse("2026-02-14"), zone = utc, now = now)

        // 30 days over the 336-bucket cap => buckets wider than half an hour…
        assertTrue(full.widthMs > halfHourMs)
        // …and truncating the range must not silently change that width.
        assertEquals(full.widthMs, truncated.widthMs)
        assertEquals(336, full.buckets)
        assertTrue(truncated.buckets < 336)
    }

    @Test
    fun `a range starting in the future degrades to a single bucket`() {
        val plan = planWindowBuckets(
            days = 1, endDate = LocalDate.parse("2026-03-20"), zone = utc,
            now = Instant.parse("2026-01-15T00:00:00Z"),
        )
        assertEquals(1, plan.buckets)
        assertTrue(plan.end.isAfter(plan.start))
    }

    @Test
    fun `the grid follows the viewer's zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val plan = planWindowBuckets(
            days = 1, endDate = LocalDate.parse("2026-01-15"), zone = tokyo,
            now = Instant.parse("2026-01-14T16:10:00Z"), // 01:10 on the 15th in Tokyo
        )
        assertEquals(Instant.parse("2026-01-14T15:00:00Z"), plan.start) // 00:00 JST
        assertEquals(3, plan.buckets)                                    // 00:00–01:30 JST
    }
}
