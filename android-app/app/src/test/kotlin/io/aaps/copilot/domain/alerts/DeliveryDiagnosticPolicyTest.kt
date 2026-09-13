package io.aaps.copilot.domain.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeliveryDiagnosticPolicyTest {

    @Test fun growingSampleLagCannotTurnTwentyFourMinutesOfGlucoseIntoThirty() {
        val samples = history(agesMinutes = (30 downTo 0 step 5).map(Int::toLong)).mapIndexed { index, o ->
            o.copy(glucoseTs = o.glucoseTs - index * MINUTE_MS)
        }
        assertThat(DeliveryDiagnosticPolicy.assess(samples, NOW).reason).isEqualTo(DeliveryDiagnosticReason.WARMING_UP)
    }

    @Test fun activityObservedAfterTheGlucoseSampleIsNotRetroactivelyCounted() {
        val samples = unexpectedRiseHistory().map { it.copy(glucoseTs = it.glucoseTs - 5 * MINUTE_MS) }
        val assessment = DeliveryDiagnosticPolicy.assess(samples, NOW)
        assertThat(assessment.expectedInsulinEffectMmol).isWithin(1e-9).of(0.625)
    }

    @Test fun oneMinuteReboundDoesNotEraseAFallingFiveMinuteTrend() {
        val history = unexpectedRiseHistory()
        val falling = history.dropLast(1).mapIndexed { index, point ->
            if (index == history.lastIndex - 1) point.copy(glucoseMmol = 14.0) else point
        } + observation(observedAt = NOW - MINUTE_MS, glucoseTs = NOW - MINUTE_MS,
            glucoseMmol = 11.9, forecast30TargetTs = NOW + 29 * MINUTE_MS) +
            history.last().copy(glucoseMmol = 12.0)
        assertThat(DeliveryDiagnosticPolicy.assess(falling, NOW).reason).isEqualTo(DeliveryDiagnosticReason.NONE)
    }

    @Test fun recoveryCannotBeDeclaredWhileTheCurrentForecastIncludesLowGlucose() {
        val history = history(agesMinutes = (30 downTo 0 step 5).map(Int::toLong)).map {
            it.copy(glucoseMmol = 7.0, forecast30Mmol = 5.0, forecast30CiLow = 3.0, forecast30CiHigh = 7.0)
        }
        assertThat(DeliveryDiagnosticPolicy.assess(history, NOW).reason).isEqualTo(DeliveryDiagnosticReason.NONE)
    }

    @Test
    fun validObservationAcceptsInclusivePlausibleBoundsAndForecastLag() {
        val observation = observation(
            observedAt = NOW,
            glucoseTs = NOW - DeliveryDiagnosticPolicy.MAX_GAP_MS,
            glucoseMmol = 2.2,
            positiveIobUnits = 0.0,
            activityUnitsPerMinute = 0.0,
            isfMmolPerUnit = 0.8,
            crGramsPerUnit = 2.0,
            cobGrams = 0.0,
            forecast30Mmol = 22.0,
            forecast30CiLow = 2.2,
            forecast30CiHigh = 22.0,
            forecast30TargetTs = NOW + 24 * MINUTE_MS,
            basisKey = "b"
        )

        assertThat(DeliveryDiagnosticPolicy.validObservation(observation)).isTrue()
    }

    @Test
    fun validObservationRejectsInvalidTimesForecastShapeAndBasis() {
        val valid = observation()
        val invalid = listOf(
            valid.copy(observedAt = 0L),
            valid.copy(glucoseTs = 0L),
            valid.copy(glucoseTs = valid.observedAt + 1L),
            valid.copy(glucoseTs = valid.observedAt - DeliveryDiagnosticPolicy.MAX_GAP_MS - 1L),
            valid.copy(forecast30TargetTs = valid.observedAt + 30 * MINUTE_MS + 1L),
            valid.copy(forecast30TargetTs = valid.observedAt + 24 * MINUTE_MS - 1L),
            valid.copy(forecast30CiLow = 11.1, forecast30Mmol = 11.0),
            valid.copy(forecast30CiHigh = 10.9, forecast30Mmol = 11.0),
            valid.copy(basisKey = ""),
            valid.copy(basisKey = "x".repeat(257))
        )

        invalid.forEach { assertThat(DeliveryDiagnosticPolicy.validObservation(it)).isFalse() }
    }

    @Test
    fun validObservationRejectsNonFiniteAndImplausibleNumericValues() {
        val valid = observation()
        val invalid = listOf(
            valid.copy(glucoseMmol = Double.NaN),
            valid.copy(positiveIobUnits = Double.POSITIVE_INFINITY),
            valid.copy(activityUnitsPerMinute = Double.NEGATIVE_INFINITY),
            valid.copy(isfMmolPerUnit = Double.NaN),
            valid.copy(crGramsPerUnit = Double.POSITIVE_INFINITY),
            valid.copy(cobGrams = Double.NaN),
            valid.copy(forecast30Mmol = Double.NEGATIVE_INFINITY),
            valid.copy(glucoseMmol = 2.19),
            valid.copy(glucoseMmol = 22.01),
            valid.copy(positiveIobUnits = -0.01),
            valid.copy(positiveIobUnits = 60.01),
            valid.copy(activityUnitsPerMinute = -0.01),
            valid.copy(activityUnitsPerMinute = 5.01),
            valid.copy(isfMmolPerUnit = 0.79),
            valid.copy(isfMmolPerUnit = 18.01),
            valid.copy(crGramsPerUnit = 1.99),
            valid.copy(crGramsPerUnit = 60.01),
            valid.copy(cobGrams = -0.01),
            valid.copy(cobGrams = 400.01),
            valid.copy(forecast30CiLow = 2.19),
            valid.copy(forecast30CiHigh = 22.01)
        )

        invalid.forEach { assertThat(DeliveryDiagnosticPolicy.validObservation(it)).isFalse() }
    }

    @Test
    fun missingHistoryIsUnavailableAndShortValidHistoryIsWarmingUp() {
        assertThat(DeliveryDiagnosticPolicy.assess(emptyList(), NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.UNAVAILABLE)
        assertThat(DeliveryDiagnosticPolicy.assess(history(agesMinutes = listOf(20L, 15L, 10L, 5L, 0L)), NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.WARMING_UP)
    }

    @Test
    fun malformedHistoryFailsClosedWithoutSortingOrSelectingASubset() {
        val valid = unexpectedRiseHistory()
        val outOfOrder = valid.toMutableList().apply {
            val point = removeAt(2)
            add(4, point)
        }
        val exactDuplicate = valid.toMutableList().apply { add(3, get(2)) }
        val conflictingTimestamp = valid.toMutableList().apply {
            add(3, get(2).copy(glucoseMmol = get(2).glucoseMmol + 0.1))
        }
        val duplicateGlucoseTimestamp = valid.toMutableList().apply {
            this[3] = this[3].copy(glucoseTs = this[2].glucoseTs)
        }
        val mixedBasis = valid.toMutableList().apply {
            this[3] = this[3].copy(basisKey = "other-basis")
        }
        val gap = valid.filterIndexed { index, _ -> index != 3 }
        val invalidValue = valid.toMutableList().apply {
            this[2] = this[2].copy(activityUnitsPerMinute = Double.NaN)
        }

        listOf(
            outOfOrder,
            exactDuplicate,
            conflictingTimestamp,
            duplicateGlucoseTimestamp,
            mixedBasis,
            gap,
            invalidValue
        ).forEach { malformed ->
            assertThat(DeliveryDiagnosticPolicy.assess(malformed, NOW).reason)
                .isEqualTo(DeliveryDiagnosticReason.UNAVAILABLE)
        }
    }

    @Test
    fun overCapacityAndOverlongHistoryFailClosed() {
        val overCapacity = historySeconds(
            agesSeconds = (96 downTo 0).map { it * 30L }
        )
        val overlong = history(agesMinutes = (95 downTo 0 step 5).map(Int::toLong))

        assertThat(DeliveryDiagnosticPolicy.assess(overCapacity, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.UNAVAILABLE)
        assertThat(DeliveryDiagnosticPolicy.assess(overlong, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.UNAVAILABLE)
    }

    @Test
    fun staleOrFutureLatestEvidenceIsUnavailable() {
        val valid = unexpectedRiseHistory()
        val stale = valid.map { point ->
            point.copy(
                observedAt = point.observedAt - DeliveryDiagnosticPolicy.MAX_GAP_MS - 1L,
                glucoseTs = point.glucoseTs - DeliveryDiagnosticPolicy.MAX_GAP_MS - 1L,
                forecast30TargetTs = point.forecast30TargetTs - DeliveryDiagnosticPolicy.MAX_GAP_MS - 1L
            )
        }
        val future = valid.mapIndexed { index, point ->
            if (index == valid.lastIndex) {
                point.copy(
                    observedAt = NOW + 1L,
                    glucoseTs = NOW + 1L,
                    forecast30TargetTs = NOW + 30 * MINUTE_MS + 1L
                )
            } else {
                point
            }
        }

        assertThat(DeliveryDiagnosticPolicy.assess(stale, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.UNAVAILABLE)
        assertThat(DeliveryDiagnosticPolicy.assess(future, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.UNAVAILABLE)
    }

    @Test
    fun mealWithModeledInsulinAndMissedForecastIsUnexpectedRise() {
        val assessment = DeliveryDiagnosticPolicy.assess(unexpectedRiseHistory(), NOW)

        assertThat(assessment.reason).isEqualTo(DeliveryDiagnosticReason.UNEXPECTED_RISE)
        assertThat(assessment.expectedInsulinEffectMmol).isWithin(1e-9).of(0.75)
        assertThat(assessment.observedRiseMmol).isWithin(1e-9).of(2.0)
        assertThat(assessment.foodConfounded).isTrue()
    }

    @Test
    fun ordinaryMealWithinForecastBandIsNotSuspiciousButKeepsFoodConfounder() {
        val history = unexpectedRiseHistory().mapIndexed { index, point ->
            if (index == 0) {
                point.copy(forecast30Mmol = 11.6, forecast30CiHigh = 11.6)
            } else {
                point
            }
        }

        val assessment = DeliveryDiagnosticPolicy.assess(history, NOW)

        assertThat(assessment.reason).isEqualTo(DeliveryDiagnosticReason.NONE)
        assertThat(assessment.foodConfounded).isTrue()
    }

    @Test
    fun newlyActiveBolusAndFallingLatestTrendCannotQualify() {
        val newlyActive = unexpectedRiseHistory().mapIndexed { index, point ->
            if (index == pointCount(30) - 1) {
                point.copy(positiveIobUnits = 2.0, activityUnitsPerMinute = 0.05)
            } else {
                point.copy(positiveIobUnits = 0.0, activityUnitsPerMinute = 0.0)
            }
        }
        val falling = unexpectedRiseHistory().mapIndexed { index, point ->
            when (index) {
                pointCount(30) - 2 -> point.copy(glucoseMmol = 12.1)
                pointCount(30) - 1 -> point.copy(glucoseMmol = 12.0)
                else -> point
            }
        }

        assertThat(DeliveryDiagnosticPolicy.assess(newlyActive, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
        assertThat(DeliveryDiagnosticPolicy.assess(falling, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
    }

    @Test
    fun unexpectedRiseThresholdsAreInclusiveAndEachLowerBoundaryFails() {
        val exact = unexpectedBoundaryHistory()

        assertThat(DeliveryDiagnosticPolicy.assess(exact, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.UNEXPECTED_RISE)

        val currentBelow = exact.map { it.copy(glucoseMmol = it.glucoseMmol - 0.001) }
        val riseBelow = exact.mapIndexed { index, point ->
            if (index == 0) point.copy(glucoseMmol = 8.501) else point
        }
        val effectBelow = exact.map { it.copy(activityUnitsPerMinute = 0.00799) }
        val supportBelow = exact.mapIndexed { index, point ->
            if (index < 3) {
                point.copy(positiveIobUnits = 0.0, activityUnitsPerMinute = 0.0)
            } else {
                point.copy(positiveIobUnits = 1.0, activityUnitsPerMinute = 0.02)
            }
        }
        val forecastBelow = exact.mapIndexed { index, point ->
            if (index == 0) {
                point.copy(forecast30Mmol = 9.001, forecast30CiHigh = 9.001)
            } else {
                point
            }
        }

        listOf(currentBelow, riseBelow, effectBelow, supportBelow, forecastBelow).forEach { history ->
            assertThat(DeliveryDiagnosticPolicy.assess(history, NOW).reason)
                .isEqualTo(DeliveryDiagnosticReason.NONE)
        }
    }

    @Test
    fun forecastUsesTargetTimestampMatchAndAcceptsCadenceJitter() {
        val agesSeconds = listOf(1_830L, 1_530L, 1_220L, 910L, 600L, 300L, 0L)
        val history = historySeconds(
            agesSeconds = agesSeconds,
            glucose = { index, lastIndex -> 10.0 + 2.0 * index / lastIndex },
            forecastTarget = { index, observedAt ->
                if (index == 0) NOW - 30_000L else observedAt + 30 * MINUTE_MS
            },
            forecastHigh = { index -> if (index == 0) 10.9 else 12.5 }
        )

        val assessment = DeliveryDiagnosticPolicy.assess(history, NOW)

        assertThat(assessment.reason).isEqualTo(DeliveryDiagnosticReason.UNEXPECTED_RISE)
        assertThat(assessment.expectedInsulinEffectMmol).isWithin(1e-9).of(0.7625)
    }

    @Test
    fun forecastTargetOutsideNinetySecondsCannotSupportUnexpectedRise() {
        val history = unexpectedRiseHistory().mapIndexed { index, point ->
            if (index == 0) {
                point.copy(forecast30TargetTs = NOW - 90_001L)
            } else {
                point
            }
        }

        assertThat(DeliveryDiagnosticPolicy.assess(history, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
    }

    @Test
    fun currentOrCurrentForecastLowerCiBelowFourVetoesSuspicion() {
        val lowCi = unexpectedRiseHistory().mapIndexed { index, point ->
            if (index == pointCount(30) - 1) {
                point.copy(forecast30CiLow = 3.99)
            } else {
                point
            }
        }
        val currentLow = unexpectedRiseHistory().mapIndexed { index, point ->
            point.copy(glucoseMmol = 2.4 + index * 1.5 / (pointCount(30) - 1))
        }

        assertThat(DeliveryDiagnosticPolicy.assess(lowCi, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
        assertThat(DeliveryDiagnosticPolicy.assess(currentLow, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
    }

    @Test
    fun persistentHighFallbackAcceptsInclusiveThresholds() {
        val history = persistentHighHistory()

        val assessment = DeliveryDiagnosticPolicy.assess(history, NOW)

        assertThat(assessment.reason).isEqualTo(DeliveryDiagnosticReason.PERSISTENT_HIGH)
        assertThat(assessment.expectedInsulinEffectMmol).isWithin(1e-9).of(1.328125)
        assertThat(assessment.observedRiseMmol).isWithin(1e-9).of(-0.5)
        assertThat(assessment.foodConfounded).isTrue()
    }

    @Test
    fun persistentHighFallbackRejectsEachLowerBoundary() {
        val exact = persistentHighHistory()
        val glucoseBelow = exact.mapIndexed { index, point ->
            if (index == 4) point.copy(glucoseMmol = 13.899) else point
        }
        val dropBelow = exact.mapIndexed { index, point ->
            if (index == exact.lastIndex) point.copy(glucoseMmol = 13.899) else point
        }
        val effectBelow = exact.map { it.copy(activityUnitsPerMinute = it.activityUnitsPerMinute * 0.75) }
        val supportBelow = exact.mapIndexed { index, point ->
            if (index <= 4) {
                point.copy(positiveIobUnits = 0.0, activityUnitsPerMinute = 0.0)
            } else {
                point.copy(positiveIobUnits = 1.0, activityUnitsPerMinute = 0.025)
            }
        }

        listOf(glucoseBelow, dropBelow, effectBelow, supportBelow).forEach { history ->
            assertThat(DeliveryDiagnosticPolicy.assess(history, NOW).reason)
                .isEqualTo(DeliveryDiagnosticReason.NONE)
        }
    }

    @Test
    fun recoveryRequiresCompleteThirtyMinutesInRangeAndCurrentBelowNine() {
        val recovered = history(
            agesMinutes = listOf(30L, 25L, 20L, 15L, 10L, 5L, 0L),
            glucose = { index, _ ->
                when (index) {
                    0 -> 4.0
                    3 -> 10.0
                    6 -> 8.999
                    else -> 7.0
                }
            }
        )
        val currentNine = recovered.mapIndexed { index, point ->
            if (index == recovered.lastIndex) point.copy(glucoseMmol = 9.0) else point
        }
        val oneBelowRange = recovered.mapIndexed { index, point ->
            if (index == 2) point.copy(glucoseMmol = 3.999) else point
        }

        assertThat(DeliveryDiagnosticPolicy.assess(recovered, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.RECOVERY)
        assertThat(DeliveryDiagnosticPolicy.assess(currentNine, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
        assertThat(DeliveryDiagnosticPolicy.assess(oneBelowRange, NOW).reason)
            .isEqualTo(DeliveryDiagnosticReason.NONE)
    }

    private fun unexpectedRiseHistory(): List<DeliveryDiagnosticObservation> = history(
        agesMinutes = listOf(30L, 25L, 20L, 15L, 10L, 5L, 0L),
        glucose = { index, lastIndex -> 10.0 + 2.0 * index / lastIndex },
        positiveIob = { 2.0 },
        activity = { 0.01 },
        cob = { 40.0 },
        uam = { true },
        forecastHigh = { index -> if (index == 0) 10.8 else 12.5 }
    )

    private fun unexpectedBoundaryHistory(): List<DeliveryDiagnosticObservation> = history(
        agesMinutes = listOf(30L, 25L, 20L, 15L, 10L, 5L, 0L),
        glucose = { index, lastIndex -> 8.5 + 1.5 * index / lastIndex },
        positiveIob = { 1.0 },
        activity = { 0.008 },
        forecastHigh = { index -> if (index == 0) 9.0 else 10.5 }
    )

    private fun persistentHighHistory(): List<DeliveryDiagnosticObservation> = history(
        agesMinutes = (60 downTo 0 step 5).map(Int::toLong),
        glucose = { index, lastIndex -> 14.4 - 0.5 * index / lastIndex },
        positiveIob = { index -> if (index >= 4) 1.0 else 0.0 },
        activity = { index -> if (index >= 4) 0.0125 else 0.0 },
        cob = { 20.0 },
        forecastHigh = { 15.0 }
    )

    private fun history(
        agesMinutes: List<Long>,
        glucose: (index: Int, lastIndex: Int) -> Double = { _, _ -> 8.0 },
        positiveIob: (index: Int) -> Double = { 1.0 },
        activity: (index: Int) -> Double = { 0.01 },
        cob: (index: Int) -> Double = { 0.0 },
        uam: (index: Int) -> Boolean = { false },
        forecastHigh: (index: Int) -> Double = { 12.5 }
    ): List<DeliveryDiagnosticObservation> = historySeconds(
        agesSeconds = agesMinutes.map { it * 60L },
        glucose = glucose,
        positiveIob = positiveIob,
        activity = activity,
        cob = cob,
        uam = uam,
        forecastHigh = forecastHigh
    )

    private fun historySeconds(
        agesSeconds: List<Long>,
        glucose: (index: Int, lastIndex: Int) -> Double = { _, _ -> 8.0 },
        positiveIob: (index: Int) -> Double = { 1.0 },
        activity: (index: Int) -> Double = { 0.01 },
        cob: (index: Int) -> Double = { 0.0 },
        uam: (index: Int) -> Boolean = { false },
        forecastTarget: (index: Int, observedAt: Long) -> Long = { _, observedAt ->
            observedAt + 30 * MINUTE_MS
        },
        forecastHigh: (index: Int) -> Double = { 12.5 }
    ): List<DeliveryDiagnosticObservation> = agesSeconds.mapIndexed { index, ageSeconds ->
        val observedAt = NOW - ageSeconds * 1_000L
        val glucoseMmol = glucose(index, agesSeconds.lastIndex)
        val ciHigh = forecastHigh(index)
        observation(
            observedAt = observedAt,
            glucoseTs = observedAt,
            glucoseMmol = glucoseMmol,
            positiveIobUnits = positiveIob(index),
            activityUnitsPerMinute = activity(index),
            cobGrams = cob(index),
            uamActive = uam(index),
            forecast30Mmol = minOf(glucoseMmol, ciHigh),
            forecast30CiLow = minOf(4.5, glucoseMmol, ciHigh),
            forecast30CiHigh = ciHigh,
            forecast30TargetTs = forecastTarget(index, observedAt)
        )
    }

    private fun observation(
        observedAt: Long = NOW,
        glucoseTs: Long = observedAt,
        glucoseMmol: Double = 11.0,
        positiveIobUnits: Double = 2.0,
        activityUnitsPerMinute: Double = 0.01,
        isfMmolPerUnit: Double = 2.5,
        crGramsPerUnit: Double = 10.0,
        cobGrams: Double = 0.0,
        uamActive: Boolean = false,
        forecast30Mmol: Double = 11.0,
        forecast30CiLow: Double = 10.0,
        forecast30CiHigh: Double = 12.0,
        forecast30TargetTs: Long = observedAt + 30 * MINUTE_MS,
        basisKey: String = "basis-v1"
    ) = DeliveryDiagnosticObservation(
        observedAt = observedAt,
        glucoseTs = glucoseTs,
        glucoseMmol = glucoseMmol,
        positiveIobUnits = positiveIobUnits,
        activityUnitsPerMinute = activityUnitsPerMinute,
        isfMmolPerUnit = isfMmolPerUnit,
        crGramsPerUnit = crGramsPerUnit,
        cobGrams = cobGrams,
        uamActive = uamActive,
        forecast30Mmol = forecast30Mmol,
        forecast30CiLow = forecast30CiLow,
        forecast30CiHigh = forecast30CiHigh,
        forecast30TargetTs = forecast30TargetTs,
        basisKey = basisKey
    )

    private fun pointCount(durationMinutes: Int): Int = durationMinutes / 5 + 1

    private companion object {
        const val MINUTE_MS = 60_000L
        const val NOW = 2_000_000_000_000L
    }
}
