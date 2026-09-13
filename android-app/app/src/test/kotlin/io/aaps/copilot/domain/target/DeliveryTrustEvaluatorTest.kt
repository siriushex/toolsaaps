package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.DataQuality
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class DeliveryTrustEvaluatorTest {

    private val evaluator = DeliveryTrustEvaluator()

    @Test
    fun minuteCadenceUsesActualMeasurementsAcrossFifteenMinutes() {
        val points = (0..20).map { minute ->
            glucose(TS - minutes(20 - minute.toLong()), 5.0 + minute * 0.11)
        }
        assertThat(evaluator.evaluate(input(canonicalGlucose = points)))
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(evaluator.evaluate(input(canonicalGlucose = points.reversed())))
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun slightlyShortMinuteCadenceUsesACompleteFifteenMinuteSpan() {
        val points = (0..25).map { age -> glucose(TS - 59_999L * age, 8.0 - 0.11 * age) }
        assertThat(evaluator.evaluate(input(canonicalGlucose = points)))
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(evaluator.evaluate(input(canonicalGlucose = points.reversed())))
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun jitteredCadenceChoosesACompleteSequenceInsteadOfAGreedyDeadEnd() {
        val ages = listOf(0L, 300_001L, 600_002L, 660_001L, 1_020_000L)
        val points = ages.map { age -> glucose(TS - age, 8.0 - age / 600_000.0) }
        assertThat(evaluator.evaluate(input(canonicalGlucose = points)))
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        val outsideCadence = points.dropLast(1) + points.last().copy(ts = TS - 1_020_002L)
        assertThat(evaluator.evaluate(input(canonicalGlucose = outsideCadence)))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    @Test
    fun alternateSamplesCannotReplaceSelectedNonFiniteOrFallingEvidence() {
        val points = (0..25).map { age -> glucose(TS - 59_999L * age, 8.0 - 0.11 * age) }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            val malformed = points.mapIndexed { age, point ->
                if (age == 10) point.copy(valueMmol = invalid) else point
            }
            assertThat(evaluator.evaluate(input(canonicalGlucose = malformed)))
                .isEqualTo(DeliveryTrustState.UNKNOWN)
        }
        val falling = points.mapIndexed { age, point ->
            if (age == 10) point.copy(valueMmol = 9.0) else point
        }
        assertThat(evaluator.evaluate(input(canonicalGlucose = falling)))
            .isEqualTo(DeliveryTrustState.NORMAL)
    }

    @Test
    fun duplicateTransportRowsDoNotShortenObservationWindow() {
        val points = risingGlucose().flatMap { point ->
            listOf(point, point.copy(source = "second_transport"))
        }
        assertThat(evaluator.evaluate(input(canonicalGlucose = points)))
            .isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun conflictingDuplicatesAndPoorQualityCannotBecomeDeliveryEvidence() {
        val points = risingGlucose()
        assertThat(evaluator.evaluate(input(canonicalGlucose = points + points.last().copy(valueMmol = 5.0))))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(evaluator.evaluate(input(canonicalGlucose = points.mapIndexed { index, point ->
            if (index == 2) point.copy(quality = DataQuality.STALE) else point
        })))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    @Test
    fun missingIntervalsAndShortMinuteBurstsAreNotInterpolated() {
        assertThat(evaluator.evaluate(input(canonicalGlucose = risingGlucose().filterIndexed { index, _ -> index != 2 })))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
        val burst = (0..11).map { minute -> glucose(TS - minutes(11 - minute.toLong()), 5.0 + minute * 0.2) }
        assertThat(evaluator.evaluate(input(canonicalGlucose = burst)))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    @Test
    fun staleOrFutureMeasurementsCannotAuthorizeNonresponse() {
        assertThat(evaluator.evaluate(input(canonicalGlucose = risingGlucose().map { it.copy(ts = it.ts - minutes(7)) })))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(evaluator.evaluate(input(canonicalGlucose = risingGlucose().map { it.copy(ts = it.ts + minutes(20)) })))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    @Test
    fun suspectedDeliveryRequiresTrustedSensorPersistentRiseIobAndOldSet() {
        assertThat(evaluator.evaluate(input())).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(
            evaluator.evaluate(input(sensorTrust = SensorTrustState.WARN))
        ).isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(
            evaluator.evaluate(input(iobUnits = null))
        ).isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(
            evaluator.evaluate(input(announcedCarbsKnown = false))
        ).isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(
            evaluator.evaluate(input(setAgeHours = 24.0))
        ).isEqualTo(DeliveryTrustState.NORMAL)
        assertThat(
            evaluator.evaluate(input(canonicalGlucose = flatGlucose()))
        ).isEqualTo(DeliveryTrustState.NORMAL)
        assertThat(
            evaluator.evaluate(input(announcedCarbTimestamps = listOf(TS - minutes(30))))
        ).isEqualTo(DeliveryTrustState.NORMAL)
    }

    @Test
    fun activeCobOrUamPreventsSuspectedDeliveryClassification() {
        assertThat(
            evaluator.evaluate(input(effectiveCobGrams = 5.1))
        ).isEqualTo(DeliveryTrustState.NORMAL)
        assertThat(
            evaluator.evaluate(input(uamActive = true))
        ).isEqualTo(DeliveryTrustState.NORMAL)
    }

    @Test
    fun exactDeliveryThresholdsAuthorizeClassification() {
        val exactRise = listOf(
            glucose(TS - minutes(15), 5.0),
            glucose(TS - minutes(10), 5.4),
            glucose(TS - minutes(5), 5.9),
            glucose(TS, 6.5)
        )

        assertThat(
            evaluator.evaluate(
                input(
                    canonicalGlucose = exactRise,
                    iobUnits = 1.0,
                    effectiveCobGrams = 5.0,
                    setAgeHours = 72.0
                )
            )
        ).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun missingCobUamOrSetAgeReturnsUnknownDuringPersistentRise() {
        assertThat(evaluator.evaluate(input(effectiveCobGrams = null)))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(evaluator.evaluate(input(uamActive = null)))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(evaluator.evaluate(input(setAgeHours = null)))
            .isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    @Test
    fun futureDatedCarbEvidenceFailsClosed() {
        assertThat(
            evaluator.evaluate(input(announcedCarbTimestamps = listOf(TS + minutes(5))))
        ).isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    @Test
    fun overflowingDeliveryDurationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DeliveryTrustConfig(announcedCarbLookbackMinutes = Long.MAX_VALUE)
        }
    }

    private fun input(
        canonicalGlucose: List<GlucosePoint> = risingGlucose(),
        sensorTrust: SensorTrustState = SensorTrustState.TRUSTED,
        iobUnits: Double? = 1.2,
        effectiveCobGrams: Double? = 0.0,
        uamActive: Boolean? = false,
        announcedCarbsKnown: Boolean = true,
        announcedCarbTimestamps: List<Long> = emptyList(),
        setAgeHours: Double? = 96.0
    ) = DeliveryTrustInput(
        evaluationTimestamp = TS,
        canonicalGlucose = canonicalGlucose,
        sensorTrust = sensorTrust,
        iobUnits = iobUnits,
        effectiveCobGrams = effectiveCobGrams,
        uamActive = uamActive,
        announcedCarbsKnown = announcedCarbsKnown,
        announcedCarbTimestamps = announcedCarbTimestamps,
        setAgeHours = setAgeHours
    )

    private fun risingGlucose(): List<GlucosePoint> = listOf(
        glucose(TS - minutes(15), 5.0),
        glucose(TS - minutes(10), 5.4),
        glucose(TS - minutes(5), 5.9),
        glucose(TS, 6.6)
    )

    private fun flatGlucose(): List<GlucosePoint> = listOf(
        glucose(TS - minutes(15), 5.0),
        glucose(TS - minutes(10), 5.2),
        glucose(TS - minutes(5), 5.1),
        glucose(TS, 5.3)
    )

    private fun glucose(timestamp: Long, valueMmol: Double) = GlucosePoint(
        ts = timestamp,
        valueMmol = valueMmol,
        source = "canonical"
    )

    private fun minutes(value: Long): Long = value * 60_000L

    private companion object {
        val TS: Long = Instant.parse("2026-07-20T09:00:00Z").toEpochMilli()
    }
}
