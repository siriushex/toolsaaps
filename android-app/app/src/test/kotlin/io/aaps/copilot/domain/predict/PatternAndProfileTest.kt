package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class PatternAndProfileTest {

    @Test
    fun patternAnalyzer_detectsWeekendWindow() {
        val zone = ZoneId.systemDefault()
        val saturdayNoon = LocalDateTime.of(2026, 2, 21, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val points = (0 until 24).map {
            GlucosePoint(
                ts = saturdayNoon + it * 5 * 60_000L,
                valueMmol = if (it % 2 == 0) 3.7 else 4.2,
                source = "test",
                quality = DataQuality.OK
            )
        }

        val windows = PatternAnalyzer(zone).analyze(points, baseTargetMmol = 5.5)
        val weekendNoon = windows.firstOrNull { it.dayType.name == "WEEKEND" && it.hour == 12 }

        assertThat(weekendNoon).isNotNull()
        assertThat(weekendNoon!!.lowRate).isGreaterThan(0.3)
        assertThat(weekendNoon.recommendedTargetMmol).isGreaterThan(5.5)
    }

    @Test
    fun profileEstimator_returnsNonNull_whenDataEnough() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 95 * 60_000, 9.0, "test", DataQuality.OK),
            GlucosePoint(now - 5 * 60_000, 7.0, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(
                ts = now - 90 * 60_000,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            ),
            TherapyEvent(
                ts = now - 120 * 60_000,
                type = "meal_bolus",
                payload = mapOf("grams" to "30", "bolusUnits" to "3")
            )
        )

        val estimate = ProfileEstimator().estimate(glucose, therapy)
        assertThat(estimate).isNotNull()
        assertThat(estimate!!.isfMmolPerUnit).isWithin(0.01).of(2.0)
        assertThat(estimate.crGramPerUnit).isWithin(0.01).of(10.0)
    }
}
