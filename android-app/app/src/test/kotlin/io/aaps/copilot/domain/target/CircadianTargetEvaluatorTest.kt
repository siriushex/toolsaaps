package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.CircadianDayType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class CircadianTargetEvaluatorTest {

    private val evaluator = CircadianTargetEvaluator()

    @Test
    fun fewerThanSevenValidDaysReturnsWaitingData() {
        val result = evaluate(hourlyCohorts(days = 6, samplesPerDay = 7, glucoseMmol = 6.0))

        assertThat(result.state).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(result.adjustments).isEmpty()
    }

    @Test
    fun allDayTypeRequiresSevenDaysAndFortySamples() {
        val result = evaluate(hourlyCohorts(days = 7, samplesPerDay = 5, glucoseMmol = 6.0))

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.status).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(adjustment.sampleCount).isEqualTo(35)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun weekdayFallsBackToAllWhenSevenWeekdaysAreUnavailable() {
        val result = evaluate(hourlyCohorts(days = 7, samplesPerDay = 6, glucoseMmol = 6.0))

        val adjustment = result.adjustment(CircadianDayType.WEEKDAY, TEST_HOUR)
        assertThat(adjustment.sourceDayType).isEqualTo(CircadianDayType.ALL)
        assertThat(adjustment.activeDays).isEqualTo(7)
        assertThat(adjustment.status).isEqualTo(CircadianAutoState.ACTIVE)
    }

    @Test
    fun latestSevenDaysReceiveSeventyPercentAggregateWeight() {
        val samples = buildList {
            repeat(14) { daysAgo ->
                repeat(6) { sampleIndex ->
                    add(
                        sample(
                            daysAgo = daysAgo,
                            hour = TEST_HOUR,
                            sampleIndex = sampleIndex,
                            glucoseMmol = if (daysAgo < 7) 6.0 else 9.0
                        )
                    )
                }
            }
        }
        val result = evaluate(cohorts(samples))

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.medianMmol).isWithin(EPSILON).of(6.0)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun medianInsideFiveSixToSixFourProducesZeroDelta() {
        val result = evaluate(threeHourCohorts(glucoseMmol = 6.2))

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.medianMmol).isWithin(EPSILON).of(6.2)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun trustedLowBelowFourProducesProtectivePositiveDelta() {
        val base = threeHourSamples(glucoseMmol = 8.4)
        val low = sample(
            daysAgo = 0,
            hour = TEST_HOUR,
            sampleIndex = 10,
            glucoseMmol = 3.9
        )
        val result = evaluate(cohorts(clean = base, allContext = base + low))

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.trustedLowCount).isEqualTo(1)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.6)
    }

    @Test
    fun anyProtectedWindowLowBlocksNegativeDelta() {
        val base = threeHourSamples(glucoseMmol = 8.4)
        val adjacentLow = sample(
            daysAgo = 1,
            hour = TEST_HOUR - 1,
            sampleIndex = 10,
            glucoseMmol = 3.8
        )
        val result = evaluate(cohorts(clean = base, allContext = base + adjacentLow))

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.6)
        assertThat(adjustment.appliedDeltaMmol).isAtLeast(0.0)
        assertThat(adjustment.reasonCodes).contains("protected_low_below_4_0")
    }

    @Test
    fun ordinaryHighMedianCannotGoBelowMinusPointThree() {
        val result = evaluate(
            cohorts = threeHourCohorts(glucoseMmol = 12.0),
            previous = previousForHours(delta = -0.3, unchangedRuns = 10, comparison = null)
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.3)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(-0.3)
    }

    @Test
    fun deepStepRequiresTenDaysEightyPercentAndThreeRunDwell() {
        val comparison = passingComparison()
        val result = evaluate(
            cohorts = threeHourCohorts(days = 10, samplesPerDay = 4, glucoseMmol = 8.4),
            previous = previousForHours(
                delta = -0.3,
                unchangedRuns = 3,
                comparison = comparison
            )
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.4)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(-0.4)
        assertThat(adjustment.reasonCodes).contains("deep_step_passed")
    }

    @Test
    fun deepStepNeverGoesBelowMinusOne() {
        val result = evaluate(
            cohorts = threeHourCohorts(days = 10, samplesPerDay = 4, glucoseMmol = 8.4),
            previous = previousForHours(
                delta = -1.0,
                unchangedRuns = 3,
                comparison = passingComparison()
            )
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-1.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(-1.0)
    }

    @Test
    fun lowBelowFourFourAcrossFourteenDaysBlocksDeepStep() {
        val base = threeHourSamples(days = 14, samplesPerDay = 4, glucoseMmol = 8.4)
        val olderLow = sample(
            daysAgo = 13,
            hour = TEST_HOUR,
            sampleIndex = 10,
            glucoseMmol = 4.2
        )
        val result = evaluate(
            cohorts = cohorts(clean = base, allContext = base + olderLow),
            previous = previousForHours(
                delta = -0.5,
                unchangedRuns = 3,
                comparison = passingComparison()
            )
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.3)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(-0.3)
        assertThat(adjustment.reasonCodes).contains("deep_low_below_4_4")
    }

    @Test
    fun safeDeepStepIsHeldWhileNextThreeRunDwellAccumulates() {
        val result = evaluate(
            cohorts = threeHourCohorts(days = 10, samplesPerDay = 4, glucoseMmol = 8.4),
            previous = previousForHours(
                delta = -0.8,
                unchangedRuns = 2,
                comparison = passingComparison()
            )
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.8)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(-0.8)
        assertThat(adjustment.reasonCodes).contains("deep_dwell_failed")
    }

    @Test
    fun negativeMovementIsAtMostPointOnePerNormalRun() {
        val result = evaluate(threeHourCohorts(glucoseMmol = 9.0))

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.3)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(-0.1)
    }

    @Test
    fun protectiveResetMayRemoveNegativeDeltaImmediately() {
        val result = evaluate(
            cohorts = threeHourCohorts(glucoseMmol = 9.0),
            currentSensorTrust = SensorTrustState.WARN,
            previous = previousForHours(
                delta = -0.8,
                unchangedRuns = 4,
                comparison = passingComparison()
            )
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.status).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
    }

    @Test
    fun smoothingCannotTurnProtectivePositiveIntoNegative() {
        val raw = List(24) { hour -> if (hour == TEST_HOUR) 0.6 else -0.3 }

        val smoothed = smoothCircadianDesiredDeltas(raw)

        assertThat(smoothed[TEST_HOUR]).isEqualTo(0.6)
        assertThat(smoothed[TEST_HOUR - 1]).isAtLeast(0.0)
        assertThat(smoothed[TEST_HOUR + 1]).isAtLeast(0.0)
    }

    @Test
    fun smoothingCannotCreateNegativeDeltaForBlockedHour() {
        val samples = buildList {
            repeat(7) { daysAgo ->
                listOf(TEST_HOUR - 1, TEST_HOUR + 1).forEach { hour ->
                    repeat(6) { sampleIndex ->
                        add(sample(daysAgo, hour, sampleIndex, 9.0))
                    }
                }
            }
        }

        val adjustment = evaluate(cohorts(samples))
            .adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.status).isEqualTo(CircadianAutoState.WAITING_DATA)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun degradedSensorResetsNegativeDeltaAtNeutralMedian() {
        val result = evaluate(
            cohorts = threeHourCohorts(glucoseMmol = 6.2),
            currentSensorTrust = SensorTrustState.WARN,
            previous = previousForHours(
                delta = -0.8,
                unchangedRuns = 4,
                comparison = passingComparison()
            )
        )

        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.status).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
    }

    @Test
    fun malformedInputReasonBlocksNegativeDelta() {
        val malformed = threeHourCohorts(glucoseMmol = 9.0).copy(
            reasonCounts = mapOf("malformed_glucose" to 1)
        )

        val adjustment = evaluate(malformed)
            .adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.status).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun oldSensorSafetyContextDoesNotPoisonFreshLoweringEvidence() {
        val clean = threeHourSamples(glucoseMmol = 9.0)
        val oldSafetySample = sample(
            daysAgo = 6,
            hour = TEST_HOUR,
            sampleIndex = 10,
            glucoseMmol = 9.0,
            cleanWeight = 0.0
        ).copy(sensorAgeHours = 14.0 * 24.0)

        val result = evaluate(cohorts(clean = clean, allContext = clean + oldSafetySample))
        val adjustment = result.adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.3)
        assertThat(adjustment.reasonCodes).doesNotContain("malformed_or_future_evidence")
    }

    @Test
    fun weekendLowBeforeMidnightBlocksWeekdayHourZero() {
        val clean = buildList {
            repeat(14) { daysAgo ->
                listOf(23, 0, 1).forEach { hour ->
                    repeat(6) { sampleIndex ->
                        add(sample(daysAgo, hour, sampleIndex, 9.0))
                    }
                }
            }
        }
        val sundayLow = sample(
            daysAgo = 1,
            hour = 23,
            sampleIndex = 10,
            glucoseMmol = 3.8
        )

        val adjustment = evaluate(cohorts(clean = clean, allContext = clean + sundayLow))
            .adjustment(CircadianDayType.WEEKDAY, 0)

        assertThat(adjustment.sourceDayType).isEqualTo(CircadianDayType.WEEKDAY)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.6)
        assertThat(adjustment.reasonCodes).contains("protected_low_below_4_0")
    }

    @Test
    fun mixedFreshAndOldSensorEvidenceCanLower() {
        val mixed = threeHourSamples(glucoseMmol = 9.0).map { sample ->
            if (sample.localDate in setOf(END_DATE.minusDays(5), END_DATE.minusDays(6))) {
                sample.copy(sensorAgeHours = 13.0 * 24.0, cleanWeight = 0.5)
            } else {
                sample
            }
        }

        val adjustment = evaluate(cohorts(mixed))
            .adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.3)
    }

    @Test
    fun weekendDeepStepUsesOverallTenDayCoverage() {
        val allContext = threeHourSamples(days = 10, samplesPerDay = 6, glucoseMmol = 8.4)
        val weekendClean = allContext.filter { it.dayType == CircadianDayType.WEEKEND }
        val previous = previousForHours(
            delta = -0.3,
            unchangedRuns = 3,
            comparison = passingComparison(),
            dayType = CircadianDayType.WEEKEND
        )

        val adjustment = evaluate(
            cohorts = cohorts(clean = weekendClean, allContext = allContext),
            previous = previous
        ).adjustment(CircadianDayType.WEEKEND, TEST_HOUR)

        assertThat(adjustment.sourceDayType).isEqualTo(CircadianDayType.WEEKEND)
        assertThat(adjustment.activeDays).isEqualTo(4)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.4)
    }

    @Test
    fun malformedPreviousAdjustmentBlocksNewLowering() {
        val malformedPrevious = previousForHours(
            delta = Double.NaN,
            unchangedRuns = 3,
            comparison = passingComparison()
        )

        val adjustment = evaluate(
            cohorts = threeHourCohorts(glucoseMmol = 9.0),
            previous = malformedPrevious
        ).adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.status).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun untrustedSampleInjectedIntoCleanCohortFailsClosed() {
        val clean = threeHourSamples(glucoseMmol = 9.0).toMutableList()
        clean[0] = clean[0].copy(sensorTrusted = false)
        val trustedContext = threeHourSamples(glucoseMmol = 9.0)

        val adjustment = evaluate(
            cohorts = cohorts(clean = clean, allContext = trustedContext)
        ).adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.status).isEqualTo(CircadianAutoState.BLOCKED_SENSOR)
        assertThat(adjustment.desiredDeltaMmol).isEqualTo(0.0)
        assertThat(adjustment.appliedDeltaMmol).isEqualTo(0.0)
    }

    @Test
    fun malformedPreviousAdjustmentOnlyBlocksItsOwnSlot() {
        val previous = previousForHours(
            delta = -0.3,
            unchangedRuns = 3,
            comparison = passingComparison()
        ).toMutableMap().apply {
            put(
                CircadianTargetSlot(CircadianDayType.ALL, 0),
                CircadianPreviousAdjustment(
                    appliedDeltaMmol = Double.NaN,
                    unchangedSuccessfulRuns = 3,
                    priorStepComparison = passingComparison()
                )
            )
        }

        val result = evaluate(
            cohorts = threeHourCohorts(glucoseMmol = 9.0),
            previous = previous
        )

        val healthySlot = result.adjustment(CircadianDayType.ALL, TEST_HOUR)
        assertThat(healthySlot.status).isEqualTo(CircadianAutoState.ACTIVE)
        assertThat(healthySlot.desiredDeltaMmol).isEqualTo(-0.3)
    }

    @Test
    fun unknownHistoricalAgeInSafetyContextDoesNotBlockDeepStep() {
        val clean = threeHourSamples(days = 10, samplesPerDay = 4, glucoseMmol = 8.4)
        val unknownAgeContext = sample(
            daysAgo = 5,
            hour = TEST_HOUR,
            sampleIndex = 10,
            glucoseMmol = 8.4,
            cleanWeight = 0.0
        ).copy(sensorAgeHours = null)

        val adjustment = evaluate(
            cohorts = cohorts(clean = clean, allContext = clean + unknownAgeContext),
            previous = previousForHours(
                delta = -0.3,
                unchangedRuns = 3,
                comparison = passingComparison()
            )
        ).adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.desiredDeltaMmol).isEqualTo(-0.4)
        assertThat(adjustment.reasonCodes).contains("deep_step_passed")
    }

    @Test
    fun exactRollingBoundaryDayIsIgnoredWithoutPoisoningCurrentFourteenDates() {
        val currentWindow = threeHourSamples(days = 14, samplesPerDay = 4, glucoseMmol = 8.4)
        val boundary = listOf(
            sample(
                daysAgo = 14,
                hour = TEST_HOUR,
                sampleIndex = 0,
                glucoseMmol = 8.4
            )
        )

        val adjustment = evaluate(cohorts(currentWindow + boundary))
            .adjustment(CircadianDayType.ALL, TEST_HOUR)

        assertThat(adjustment.status).isEqualTo(CircadianAutoState.ACTIVE)
        assertThat(adjustment.reasonCodes).doesNotContain("malformed_or_future_evidence")
    }

    private fun evaluate(
        cohorts: CircadianTargetCohorts,
        currentSensorTrust: SensorTrustState? = SensorTrustState.TRUSTED,
        currentSensorAgeHours: Double? = 24.0,
        deliveryTrust: DeliveryTrustState = DeliveryTrustState.NORMAL,
        previous: Map<CircadianTargetSlot, CircadianPreviousAdjustment> = emptyMap()
    ): CircadianTargetEvaluationResult = evaluator.evaluate(
        CircadianTargetEvaluationInput(
            evaluatedAt = EVALUATED_AT,
            evaluationDate = END_DATE,
            cohorts = cohorts,
            currentSensorTrust = currentSensorTrust,
            currentSensorAgeHours = currentSensorAgeHours,
            deliveryTrust = deliveryTrust,
            previousAdjustments = previous
        )
    )

    private fun CircadianTargetEvaluationResult.adjustment(
        dayType: CircadianDayType,
        hour: Int
    ): CircadianTargetAdjustment = adjustments.single {
        it.requestedDayType == dayType && it.hour == hour
    }

    private fun hourlyCohorts(
        days: Int,
        samplesPerDay: Int,
        glucoseMmol: Double,
        hour: Int = TEST_HOUR
    ): CircadianTargetCohorts {
        val samples = buildList {
            repeat(days) { daysAgo ->
                repeat(samplesPerDay) { sampleIndex ->
                    add(sample(daysAgo, hour, sampleIndex, glucoseMmol))
                }
            }
        }
        return cohorts(samples)
    }

    private fun threeHourCohorts(
        days: Int = 7,
        samplesPerDay: Int = 6,
        glucoseMmol: Double
    ): CircadianTargetCohorts = cohorts(
        threeHourSamples(
            days = days,
            samplesPerDay = samplesPerDay,
            glucoseMmol = glucoseMmol
        )
    )

    private fun threeHourSamples(
        days: Int = 7,
        samplesPerDay: Int = 6,
        glucoseMmol: Double
    ): List<CircadianTargetSample> = buildList {
        repeat(days) { daysAgo ->
            for (hour in TEST_HOUR - 1..TEST_HOUR + 1) {
                repeat(samplesPerDay) { sampleIndex ->
                    add(sample(daysAgo, hour, sampleIndex, glucoseMmol))
                }
            }
        }
    }

    private fun cohorts(samples: List<CircadianTargetSample>): CircadianTargetCohorts =
        cohorts(clean = samples, allContext = samples)

    private fun cohorts(
        clean: List<CircadianTargetSample>,
        allContext: List<CircadianTargetSample>
    ) = CircadianTargetCohorts(
        clean = clean.sortedBy(CircadianTargetSample::timestamp),
        allContext = allContext.sortedBy(CircadianTargetSample::timestamp),
        validDates = allContext.mapTo(linkedSetOf(), CircadianTargetSample::localDate),
        reasonCounts = emptyMap()
    )

    private fun sample(
        daysAgo: Int,
        hour: Int,
        sampleIndex: Int,
        glucoseMmol: Double,
        cleanWeight: Double = 1.0
    ): CircadianTargetSample {
        val date = END_DATE.minusDays(daysAgo.toLong())
        val timestamp = date.atTime(hour, sampleIndex * 5)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        return CircadianTargetSample(
            timestamp = timestamp,
            localDate = date,
            hour = hour,
            dayType = date.dayOfWeek.toCircadianDayType(),
            glucoseMmol = glucoseMmol,
            sensorTrusted = true,
            sensorAgeHours = 24.0,
            sensorBlocked = false,
            suspectFalseLow = false,
            effectiveCobGrams = 0.0,
            uamActive = false,
            acuteCarbs = false,
            postHypo = false,
            lowRiskLatched = false,
            deliveryTrust = DeliveryTrustState.NORMAL,
            cleanWeight = cleanWeight
        )
    }

    private fun previousForHours(
        delta: Double,
        unchangedRuns: Int,
        comparison: CircadianPriorStepComparison?,
        dayType: CircadianDayType = CircadianDayType.ALL
    ): Map<CircadianTargetSlot, CircadianPreviousAdjustment> =
        (TEST_HOUR - 1..TEST_HOUR + 1).associate { hour ->
            CircadianTargetSlot(dayType, hour) to CircadianPreviousAdjustment(
                appliedDeltaMmol = delta,
                unchangedSuccessfulRuns = unchangedRuns,
                priorStepComparison = comparison
            )
        }

    private fun passingComparison() = CircadianPriorStepComparison(
        lowExposureNotWorse = true,
        variabilityNotWorse = true,
        lowerTailReplayErrorNotWorse = true
    )

    private fun DayOfWeek.toCircadianDayType(): CircadianDayType = when (this) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> CircadianDayType.WEEKEND
        else -> CircadianDayType.WEEKDAY
    }

    private companion object {
        const val TEST_HOUR = 10
        const val EPSILON = 1e-9
        val END_DATE: LocalDate = LocalDate.of(2026, 7, 20)
        val EVALUATED_AT: Long = Instant.parse("2026-07-20T23:59:00Z").toEpochMilli()
    }
}
