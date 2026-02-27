package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class UamCalculatorTest {

    @Test
    fun detectsUamSignalOnSustainedUnannouncedRise() {
        val base = 1_700_000_000_000L
        val glucose = listOf(
            point(base, 0, 6.0),
            point(base, 5, 6.0),
            point(base, 10, 6.1),
            point(base, 15, 6.3),
            point(base, 20, 6.6),
            point(base, 25, 7.0),
            point(base, 30, 7.4),
            point(base, 35, 7.7)
        )

        val latest = UamCalculator.latestSignal(
            glucose = glucose,
            therapyEvents = emptyList(),
            nowTs = base + 35 * 60_000L
        )

        assertThat(latest).isNotNull()
        assertThat(latest!!.confidence).isAtLeast(0.55)
    }

    @Test
    fun suppressesUamWhenAnnouncedCarbsNearRise() {
        val base = 1_700_000_000_000L
        val glucose = listOf(
            point(base, 0, 6.0),
            point(base, 5, 6.0),
            point(base, 10, 6.1),
            point(base, 15, 6.3),
            point(base, 20, 6.6),
            point(base, 25, 7.0),
            point(base, 30, 7.4),
            point(base, 35, 7.7)
        )
        val therapy = listOf(
            TherapyEvent(
                ts = base + 8 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "20")
            )
        )

        val latest = UamCalculator.latestSignal(
            glucose = glucose,
            therapyEvents = therapy,
            nowTs = base + 35 * 60_000L
        )

        assertThat(latest).isNull()
    }

    @Test
    fun estimatesCarbsFromSignalAndProfile() {
        val signal = CalculatedUamSignal(
            ts = 1_700_000_000_000L,
            confidence = 0.80,
            rise15Mmol = 0.90,
            rise30Mmol = 1.50,
            delta5Mmol = 0.25
        )
        val grams = UamCalculator.estimateCarbsGrams(
            signal = signal,
            isfMmolPerUnit = 3.0,
            crGramPerUnit = 10.0
        )

        assertThat(grams).isAtLeast(3.0)
        assertThat(grams).isAtMost(5.0)
    }

    @Test
    fun ignoresIsolatedSensorSpike() {
        val base = 1_700_000_000_000L
        val glucose = listOf(
            point(base, 0, 7.1),
            point(base, 5, 7.0),
            point(base, 10, 7.1),
            point(base, 15, 7.0),
            point(base, 20, 7.1),
            point(base, 25, 3.0),
            point(base, 30, 7.1),
            point(base, 35, 7.2),
            point(base, 40, 7.1)
        )

        val latest = UamCalculator.latestSignal(
            glucose = glucose,
            therapyEvents = emptyList(),
            nowTs = base + 40 * 60_000L
        )

        assertThat(latest).isNull()
    }

    @Test
    fun expiresOldSignalWithoutSustainedRise() {
        val base = 1_700_000_000_000L
        val glucose = listOf(
            point(base, 0, 6.0),
            point(base, 5, 6.2),
            point(base, 10, 6.6),
            point(base, 15, 7.1),
            point(base, 20, 7.5),
            point(base, 25, 7.3),
            point(base, 30, 7.1),
            point(base, 35, 7.0)
        )
        val config = UamCalculatorConfig(
            activeSignalMaxAgeMinutes = 60,
            activeSignalSustainAfterMinutes = 10,
            activeSignalMinRise15Mmol = 0.20
        )

        val signals = UamCalculator.detectSignals(glucose, emptyList(), config)
        assertThat(signals).isNotEmpty()

        val latest = UamCalculator.latestSignal(
            glucose = glucose,
            therapyEvents = emptyList(),
            nowTs = base + 35 * 60_000L,
            config = config
        )

        assertThat(latest).isNull()
    }

    @Test
    fun limitsRiseWhenSingleLowOutlierExists() {
        val base = 1_700_000_000_000L
        val points = listOf(
            0 to 1.83, 1 to 7.88, 2 to 7.88, 3 to 7.99, 4 to 8.21,
            5 to 8.27, 6 to 8.49, 7 to 8.49, 8 to 8.49, 9 to 8.60,
            10 to 8.60, 11 to 8.71, 12 to 8.60, 13 to 8.71, 14 to 8.71,
            15 to 8.77, 16 to 9.10, 17 to 9.10, 18 to 9.10, 19 to 9.21,
            20 to 9.21, 23 to 8.99, 24 to 8.99, 25 to 8.99, 26 to 8.99,
            27 to 8.88, 28 to 8.99, 29 to 8.88, 30 to 8.99, 31 to 8.99,
            32 to 8.99, 34 to 8.77, 35 to 8.49, 36 to 8.49, 37 to 8.38,
            38 to 8.38
        ).map { (minute, mmol) -> point(base, minute, mmol) }

        val signals = UamCalculator.detectSignals(points, emptyList())

        val maxRise30 = signals.maxOfOrNull { it.rise30Mmol } ?: 0.0
        assertThat(maxRise30).isAtMost(3.0)
    }

    private fun point(baseTs: Long, minutes: Int, mmol: Double): GlucosePoint =
        GlucosePoint(
            ts = baseTs + minutes * 60_000L,
            valueMmol = mmol,
            source = "test",
            quality = DataQuality.OK
        )
}
