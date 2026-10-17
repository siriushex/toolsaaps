package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NightlyAdviceTest {
    private val end = ZonedDateTime.parse("2026-10-17T05:00:00+04:00[Asia/Tbilisi]").toInstant().toEpochMilli()
    private val zone = ZoneId.of("Asia/Tbilisi")

    @Test fun windowsAreBoundedAndDoNotUseFutureRows() {
        val rows = points(14) + ClinicalGlucoseProjection(end + 1, 25.0, "xdrip", "OK")
        val report = NightlyAdviceCalculator.calculate(end, end + 100, zone, rows)
        assertEquals(listOf(3, 7, 14), report.windows.map { it.days })
        report.windows.forEach {
            assertEquals(end - it.days * DAY, it.fromTs)
            assertEquals(100.0, it.inRangePct!!, 0.01)
            assertTrue(it.usable)
        }
    }

    @Test fun duplicateSourcesAndInvalidQualityCannotInventLowPattern() {
        val rows = points(3)
        val bad = rows.map { it.copy(mmol = 2.0, quality = "SENSOR_ERROR") }
        val report = NightlyAdviceCalculator.calculate(end, end, zone, rows + rows + bad)
        val recent = report.windows.first()
        assertEquals(100.0, recent.inRangePct!!, 0.01)
        assertEquals(100.0, recent.coveragePct, 0.1)
        assertEquals(0, recent.lowDays)
    }

    @Test fun sparseOrOnlyTwoDaysCannotBecomeConfidentAdvice() {
        val two = NightlyAdviceCalculator.calculate(end, end, zone, points(2)).windows.first()
        assertFalse(two.usable)
        val sparse = NightlyAdviceCalculator.calculate(end, end, zone,
            (0..13).map { ClinicalGlucoseProjection(end - it * DAY, 2.5, "xdrip", "OK") })
        assertTrue(sparse.windows.none { it.usable })
        assertTrue(sparse.windows.all { it.lowDays == 0 })
    }

    @Test fun lowAndHighPatternsRequireSeparateWellCoveredDays() {
        val rows = points(3).mapIndexed { index, row -> row.copy(mmol = if (index % 288 < 20) 3.0 else 12.0) }
        val recent = NightlyAdviceCalculator.calculate(end, end, zone, rows).windows.first()
        assertEquals(3, recent.lowDays)
        assertEquals(3, recent.highDays)
        assertTrue(recent.belowPct!! > 0)
        assertTrue(recent.abovePct!! > 0)
    }

    @Test fun comparisonUsesNonOverlappingEarlierDaysOnly() {
        val rows = points(14).map { it.copy(mmol = if (it.ts >= end - 3 * DAY) 12.0 else 6.0) }
        val report = NightlyAdviceCalculator.calculate(end, end, zone, rows)
        assertNull(report.windows.first().recentDeltaPp)
        assertEquals(-100.0, report.windows.last().recentDeltaPp!!, 0.1)
        val sparseBaseline = NightlyAdviceCalculator.calculate(end, end, zone, rows.filter { it.ts >= end - 3 * DAY })
        assertNull(sparseBaseline.windows.last().recentDeltaPp)
    }

    private fun points(days: Int): List<ClinicalGlucoseProjection> =
        (0..days * 288).map { i -> ClinicalGlucoseProjection(end - days * DAY + i * 300_000L, 6.0, "xdrip", "OK") }

    companion object { private const val DAY = 86_400_000L }
}
