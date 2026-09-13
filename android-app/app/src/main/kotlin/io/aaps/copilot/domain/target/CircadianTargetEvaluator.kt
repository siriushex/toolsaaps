package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.CircadianDayType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class CircadianTargetSlot(
    val dayType: CircadianDayType,
    val hour: Int
)

data class CircadianPriorStepComparison(
    val lowExposureNotWorse: Boolean,
    val variabilityNotWorse: Boolean,
    val lowerTailReplayErrorNotWorse: Boolean
) {
    internal fun passes(): Boolean =
        lowExposureNotWorse && variabilityNotWorse && lowerTailReplayErrorNotWorse
}

data class CircadianPreviousAdjustment(
    val appliedDeltaMmol: Double,
    val unchangedSuccessfulRuns: Int,
    val priorStepComparison: CircadianPriorStepComparison?,
    val stepBaselineEvidence: CircadianStepEvidence? = null
)

data class CircadianStepEvidence(
    val trustedLowCount: Int,
    val variabilityIqrMmol: Double,
    val lowerTailReplayErrorMmol: Double
)

data class CircadianTargetEvaluationInput(
    val evaluatedAt: Long,
    val evaluationDate: LocalDate,
    val cohorts: CircadianTargetCohorts,
    val currentSensorTrust: SensorTrustState?,
    val currentSensorAgeHours: Double?,
    val deliveryTrust: DeliveryTrustState,
    val previousAdjustments: Map<CircadianTargetSlot, CircadianPreviousAdjustment> = emptyMap(),
    val lowerTailReplayErrors: Map<CircadianTargetSlot, Double> = emptyMap()
)

data class CircadianTargetAdjustment(
    val requestedDayType: CircadianDayType,
    val sourceDayType: CircadianDayType,
    val hour: Int,
    val desiredDeltaMmol: Double,
    val appliedDeltaMmol: Double,
    val medianMmol: Double,
    val p25: Double,
    val p75: Double,
    val trustedLowCount: Int,
    val sampleCount: Int,
    val activeDays: Int,
    val qualityScore: Double,
    val sensorTrustedShare: Double,
    val status: CircadianAutoState,
    val reasonCodes: List<String>,
    val lowerTailReplayErrorMmol: Double? = null
)

data class CircadianTargetEvaluationResult(
    val state: CircadianAutoState,
    val adjustments: List<CircadianTargetAdjustment>,
    val validDays: Int,
    val sensorTrustedShare: Double,
    val lowRiskPassed: Boolean,
    val reasonCodes: List<String>
)

class CircadianTargetEvaluator {

    fun evaluate(input: CircadianTargetEvaluationInput): CircadianTargetEvaluationResult {
        val indexed = IndexedCohorts.build(input)
        if (indexed.validDates.size < MINIMUM_VALID_DAYS) {
            return CircadianTargetEvaluationResult(
                state = CircadianAutoState.WAITING_DATA,
                adjustments = emptyList(),
                validDays = indexed.validDates.size,
                sensorTrustedShare = 0.0,
                lowRiskPassed = false,
                reasonCodes = listOf("fewer_than_seven_valid_days")
            )
        }

        val evidenceBySlot = buildMap {
            CircadianDayType.entries.forEach { dayType ->
                repeat(HOURS_PER_DAY) { hour ->
                    val key = CircadianTargetSlot(dayType, hour)
                    put(key, aggregate(indexed.cleanBySlot[key].orEmpty(), dayType, input.evaluationDate))
                }
            }
        }

        val rawByDayType = CircadianDayType.entries.associateWith { requestedDayType ->
            List(HOURS_PER_DAY) { hour ->
                val requestedEvidence = evidenceBySlot.getValue(
                    CircadianTargetSlot(requestedDayType, hour)
                )
                val allEvidence = evidenceBySlot.getValue(
                    CircadianTargetSlot(CircadianDayType.ALL, hour)
                )
                val sourceEvidence = if (
                    requestedDayType != CircadianDayType.ALL && !requestedEvidence.enoughDays
                ) {
                    allEvidence
                } else {
                    requestedEvidence
                }
                val sourceDayType = if (sourceEvidence === allEvidence) {
                    CircadianDayType.ALL
                } else {
                    requestedDayType
                }
                evaluateRawAdjustment(
                    input = input,
                    indexed = indexed,
                    requestedDayType = requestedDayType,
                    sourceDayType = sourceDayType,
                    hour = hour,
                    evidence = sourceEvidence,
                    fallbackUsed = requestedDayType != sourceDayType
                )
            }
        }

        val adjustments = buildList(MAX_ADJUSTMENTS) {
            CircadianDayType.entries.forEach { requestedDayType ->
                val rawRows = rawByDayType.getValue(requestedDayType)
                val smoothed = smoothCircadianDesiredDeltas(rawRows.map(RawAdjustment::desiredDeltaMmol))
                rawRows.forEachIndexed { hour, raw ->
                    val desired = if (raw.status == CircadianAutoState.ACTIVE) {
                        smoothed[hour]
                    } else {
                        raw.desiredDeltaMmol.coerceAtLeast(0.0)
                    }
                    val previous = input.previousAdjustments[
                        CircadianTargetSlot(requestedDayType, hour)
                    ].validAppliedDelta()
                    val resetNegative = previous < 0.0 && (
                        raw.protectiveReset || raw.forceNonNegativeApplied
                    )
                    val applied = when {
                        resetNegative -> 0.0
                        else -> normalDailyMove(previous, desired)
                    }
                    val reasons = buildList {
                        addAll(raw.reasonCodes)
                        if (desired != raw.desiredDeltaMmol) add("three_hour_median_smoothed")
                        if (resetNegative) add("protective_reset_to_zero")
                    }.distinct().sorted()
                    add(
                        CircadianTargetAdjustment(
                            requestedDayType = requestedDayType,
                            sourceDayType = raw.sourceDayType,
                            hour = hour,
                            desiredDeltaMmol = desired,
                            appliedDeltaMmol = applied,
                            medianMmol = raw.evidence.medianMmol,
                            p25 = raw.evidence.p25,
                            p75 = raw.evidence.p75,
                            trustedLowCount = raw.trustedLowCount,
                            sampleCount = raw.evidence.sampleCount,
                            activeDays = raw.evidence.activeDays,
                            qualityScore = raw.evidence.qualityScore,
                            sensorTrustedShare = raw.evidence.sensorTrustedShare,
                            status = raw.status,
                            reasonCodes = reasons,
                            lowerTailReplayErrorMmol = input.lowerTailReplayErrors[
                                CircadianTargetSlot(requestedDayType, hour)
                            ]
                        )
                    )
                }
            }
        }

        val state = when {
            adjustments.any { it.status == CircadianAutoState.ACTIVE } -> CircadianAutoState.ACTIVE
            adjustments.any { it.status == CircadianAutoState.BLOCKED_LOW_RISK } ->
                CircadianAutoState.BLOCKED_LOW_RISK
            adjustments.any { it.status == CircadianAutoState.BLOCKED_SENSOR } ->
                CircadianAutoState.BLOCKED_SENSOR
            else -> CircadianAutoState.WAITING_DATA
        }
        val trustedShares = adjustments.asSequence()
            .filter { it.sampleCount > 0 }
            .map(CircadianTargetAdjustment::sensorTrustedShare)
            .filter(Double::isFinite)
            .toList()
        val reasonCodes = buildSet {
            input.cohorts.reasonCounts.keys.forEach(::add)
            adjustments.flatMapTo(this) { it.reasonCodes }
            if (indexed.malformedEvidence) add("malformed_or_future_evidence")
        }.sorted()

        return CircadianTargetEvaluationResult(
            state = state,
            adjustments = adjustments,
            validDays = indexed.validDates.size,
            sensorTrustedShare = trustedShares.averageOrZero(),
            lowRiskPassed = adjustments.none { adjustment ->
                adjustment.reasonCodes.any { it in LOW_RISK_REASON_CODES }
            },
            reasonCodes = reasonCodes
        )
    }

    private fun evaluateRawAdjustment(
        input: CircadianTargetEvaluationInput,
        indexed: IndexedCohorts,
        requestedDayType: CircadianDayType,
        sourceDayType: CircadianDayType,
        hour: Int,
        evidence: HourlyEvidence,
        fallbackUsed: Boolean
    ): RawAdjustment {
        val protected = indexed.protectedBySlot[
            CircadianTargetSlot(sourceDayType, hour)
        ].orEmpty()
        val recentProtected = protected.filter { it.localDate.isRecent(input.evaluationDate) }
        val trustedLowCount = recentProtected.count {
            it.sensorTrusted && it.glucoseMmol < TRUSTED_LOW_MMOl
        }
        val postHypoOrLatch = recentProtected.any { it.postHypo || it.lowRiskLatched }
        val protectedLow = trustedLowCount > 0
        val protectiveEvidence = protectedLow || postHypoOrLatch
        val reasons = linkedSetOf<String>()
        if (fallbackUsed) reasons += "fallback_to_all"
        if (protectedLow) reasons += "protected_low_below_4_0"
        if (postHypoOrLatch) reasons += "post_hypo_or_low_risk_latch"
        if (indexed.malformedEvidence) reasons += "malformed_or_future_evidence"

        if (!evidence.enoughDays) {
            reasons += "insufficient_hourly_coverage"
            return RawAdjustment.waiting(
                sourceDayType = sourceDayType,
                evidence = evidence,
                trustedLowCount = trustedLowCount,
                reasons = reasons,
                protectiveReset = protectiveEvidence
            )
        }

        val qualityPassed = evidence.qualityScore.isFinite() &&
            evidence.sensorTrustedShare.isFinite() &&
            evidence.qualityScore >= MINIMUM_QUALITY_SCORE &&
            evidence.sensorTrustedShare >= MINIMUM_SENSOR_TRUSTED_SHARE &&
            !indexed.malformedEvidence
        if (!qualityPassed) reasons += "quality_gate_failed"

        val currentSensorHealthy = input.currentSensorTrust == SensorTrustState.TRUSTED &&
            input.currentSensorAgeHours.isKnownCurrentSensorAge()
        val baseline = baselineDesiredDelta(
            median = evidence.medianMmol,
            trustedLow = protectiveEvidence
        )
        if (baseline >= 0.0 && qualityPassed && currentSensorHealthy) {
            return RawAdjustment(
                sourceDayType = sourceDayType,
                evidence = evidence,
                desiredDeltaMmol = baseline,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.ACTIVE,
                reasonCodes = reasons.toList(),
                protectiveReset = protectiveEvidence,
                forceNonNegativeApplied = protectiveEvidence
            )
        }
        if (baseline >= 0.0) {
            if (!currentSensorHealthy) reasons += "current_sensor_not_trusted"
            return RawAdjustment(
                sourceDayType = sourceDayType,
                evidence = evidence,
                desiredDeltaMmol = if (protectiveEvidence) 0.6 else 0.0,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.BLOCKED_SENSOR,
                reasonCodes = reasons.toList(),
                protectiveReset = protectiveEvidence || !currentSensorHealthy,
                forceNonNegativeApplied = true
            )
        }

        val previous = input.previousAdjustments[
            CircadianTargetSlot(requestedDayType, hour)
        ]
        val hasFullWeightEvidence = evidence.hasFullWeightEvidence
        val deliveryBlocksLowering = input.deliveryTrust == DeliveryTrustState.SUSPECTED_NONRESPONSE

        if (protectiveEvidence) {
            return RawAdjustment(
                sourceDayType = sourceDayType,
                evidence = evidence,
                desiredDeltaMmol = 0.6,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.BLOCKED_LOW_RISK,
                reasonCodes = reasons.toList(),
                protectiveReset = true,
                forceNonNegativeApplied = true
            )
        }
        if (!qualityPassed || !hasFullWeightEvidence || !currentSensorHealthy) {
            if (!hasFullWeightEvidence) reasons += "full_weight_evidence_missing"
            if (!currentSensorHealthy) reasons += "current_sensor_not_trusted"
            return RawAdjustment.blocked(
                sourceDayType = sourceDayType,
                evidence = evidence,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.BLOCKED_SENSOR,
                reasons = reasons,
                protectiveReset = !currentSensorHealthy
            )
        }
        if (deliveryBlocksLowering) {
            reasons += "delivery_suspected_nonresponse"
            return RawAdjustment.blocked(
                sourceDayType = sourceDayType,
                evidence = evidence,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.BLOCKED_LOW_RISK,
                reasons = reasons,
                protectiveReset = false
            )
        }
        if (previous != null && !previous.isValidPersisted()) {
            reasons += "previous_adjustment_invalid"
            return RawAdjustment.blocked(
                sourceDayType = sourceDayType,
                evidence = evidence,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.BLOCKED_SENSOR,
                reasons = reasons,
                protectiveReset = false
            )
        }
        val desired = deepOrOrdinaryDesiredDelta(
            baseline = baseline,
            previous = previous,
            evidence = evidence,
            validDays = indexed.validDates.size,
            fullProtected = protected,
            currentDeliveryTrust = input.deliveryTrust,
            reasons = reasons
        )
        return RawAdjustment(
            sourceDayType = sourceDayType,
            evidence = evidence,
            desiredDeltaMmol = desired,
            trustedLowCount = trustedLowCount,
            status = CircadianAutoState.ACTIVE,
            reasonCodes = reasons.toList(),
            protectiveReset = false,
            forceNonNegativeApplied = false
        )
    }

    private fun deepOrOrdinaryDesiredDelta(
        baseline: Double,
        previous: CircadianPreviousAdjustment?,
        evidence: HourlyEvidence,
        validDays: Int,
        fullProtected: List<CircadianTargetSample>,
        currentDeliveryTrust: DeliveryTrustState,
        reasons: MutableSet<String>
    ): Double {
        val previousDelta = previous.validAppliedDelta()
        if (previousDelta > ORDINARY_MINIMUM_DELTA) return baseline

        val trustedDeepLow = fullProtected.any {
            it.sensorTrusted && it.glucoseMmol < DEEP_LOW_MMOl
        }
        val deepContextSafe = fullProtected.isNotEmpty() && fullProtected.all(::isDeepSafeContext)
        val dwellPassed = previous != null && previous.unchangedSuccessfulRuns >= DEEP_DWELL_RUNS
        val coveragePassed = validDays >= DEEP_MINIMUM_VALID_DAYS
        val directionPassed = evidence.directionallyHighShare >= DEEP_DIRECTIONAL_SHARE
        val distributionPassed = evidence.medianMmol > TARGET_BAND_HIGH_MMOl &&
            evidence.p25 >= TARGET_BAND_LOW_MMOl
        val comparisonPassed = previous?.priorStepComparison?.passes() == true
        val deliveryPassed = currentDeliveryTrust == DeliveryTrustState.NORMAL

        if (!dwellPassed) reasons += "deep_dwell_failed"
        if (!coveragePassed) reasons += "deep_coverage_failed"
        if (!directionPassed) reasons += "deep_direction_failed"
        if (!distributionPassed) reasons += "deep_distribution_failed"
        if (trustedDeepLow) reasons += "deep_low_below_4_4"
        if (!deepContextSafe) reasons += "deep_context_confounded"
        if (!deliveryPassed) reasons += "deep_delivery_not_normal"
        if (!comparisonPassed) {
            reasons += "deep_prior_step_comparison_failed"
            when (val comparison = previous?.priorStepComparison) {
                null -> reasons += "deep_prior_step_evidence_missing"
                else -> {
                    if (!comparison.lowExposureNotWorse) reasons += "deep_low_exposure_worse"
                    if (!comparison.variabilityNotWorse) reasons += "deep_variability_worse"
                    if (!comparison.lowerTailReplayErrorNotWorse) {
                        reasons += "deep_lower_tail_replay_error_worse"
                    }
                }
            }
        }

        val deepSafetyPassed = coveragePassed && directionPassed && distributionPassed &&
            !trustedDeepLow && deepContextSafe && deliveryPassed && comparisonPassed
        if (!deepSafetyPassed) {
            return if (previousDelta < ORDINARY_MINIMUM_DELTA) {
                max(baseline, ORDINARY_MINIMUM_DELTA)
            } else {
                baseline
            }
        }
        if (!dwellPassed) {
            return if (previousDelta < ORDINARY_MINIMUM_DELTA) previousDelta else baseline
        }
        reasons += "deep_step_passed"
        return quantizeDelta(max(MINIMUM_DELTA, previousDelta - DAILY_NEGATIVE_STEP))
    }

    private fun aggregate(
        samples: List<CircadianTargetSample>,
        dayType: CircadianDayType,
        evaluationDate: LocalDate
    ): HourlyEvidence {
        if (samples.isEmpty()) return HourlyEvidence.empty(dayType)
        val byDate = samples.groupBy(CircadianTargetSample::localDate)
        val activeDays = byDate.size
        val sampleCount = samples.size
        val enoughDays = when (dayType) {
            CircadianDayType.ALL -> activeDays >= MINIMUM_VALID_DAYS &&
                sampleCount >= ALL_MINIMUM_SAMPLES
            CircadianDayType.WEEKDAY -> activeDays >= WEEKDAY_MINIMUM_DAYS
            CircadianDayType.WEEKEND -> activeDays >= WEEKEND_MINIMUM_DAYS
        }
        val recentDates = byDate.keys.filter { it.isRecent(evaluationDate) }.sorted()
        val olderDates = byDate.keys.filter { it.isOlder(evaluationDate) }.sorted()
        val dateWeights = buildMap {
            recentDates.forEach { put(it, RECENT_COHORT_WEIGHT / recentDates.size) }
            olderDates.forEach { put(it, OLDER_COHORT_WEIGHT / olderDates.size) }
        }
        val weightedValues = buildList(sampleCount) {
            byDate.forEach { (date, daySamples) ->
                val dayWeight = dateWeights[date] ?: return@forEach
                val totalCleanWeight = daySamples.sumOf(CircadianTargetSample::cleanWeight)
                if (!totalCleanWeight.isFinite() || totalCleanWeight <= 0.0) return@forEach
                daySamples.forEach { sample ->
                    add(
                        WeightedValue(
                            value = sample.glucoseMmol,
                            weight = dayWeight * sample.cleanWeight / totalCleanWeight,
                            timestamp = sample.timestamp
                        )
                    )
                }
            }
        }
        val dailyCohortWeights = dateWeights.entries.associate { (date, weight) -> date to weight }
        val totalDateWeight = dailyCohortWeights.values.sum()
        val qualityScore = if (totalDateWeight > 0.0) {
            byDate.entries.sumOf { (date, daySamples) ->
                val dayQuality = daySamples.map(CircadianTargetSample::cleanWeight).averageOrZero()
                (dailyCohortWeights[date] ?: 0.0) * dayQuality
            } / totalDateWeight
        } else {
            0.0
        }
        val sensorTrustedShare = if (totalDateWeight > 0.0) {
            byDate.entries.sumOf { (date, daySamples) ->
                val dayTrustedShare = daySamples.count(CircadianTargetSample::sensorTrusted)
                    .toDouble() / daySamples.size
                (dailyCohortWeights[date] ?: 0.0) * dayTrustedShare
            } / totalDateWeight
        } else {
            0.0
        }
        val directionallyHighDays = byDate.values.count { daySamples ->
            weightedQuantile(
                daySamples.map {
                    WeightedValue(it.glucoseMmol, it.cleanWeight, it.timestamp)
                },
                MEDIAN_QUANTILE
            ) > TARGET_BAND_HIGH_MMOl
        }

        return HourlyEvidence(
            dayType = dayType,
            medianMmol = weightedQuantile(weightedValues, MEDIAN_QUANTILE),
            p25 = weightedQuantile(weightedValues, LOWER_QUANTILE),
            p75 = weightedQuantile(weightedValues, UPPER_QUANTILE),
            sampleCount = sampleCount,
            activeDays = activeDays,
            qualityScore = qualityScore,
            sensorTrustedShare = sensorTrustedShare,
            directionallyHighShare = directionallyHighDays.toDouble() / activeDays,
            hasFullWeightEvidence = samples.any { it.cleanWeight >= FULL_SAMPLE_WEIGHT },
            enoughDays = enoughDays
        )
    }

    private data class RawAdjustment(
        val sourceDayType: CircadianDayType,
        val evidence: HourlyEvidence,
        val desiredDeltaMmol: Double,
        val trustedLowCount: Int,
        val status: CircadianAutoState,
        val reasonCodes: List<String>,
        val protectiveReset: Boolean,
        val forceNonNegativeApplied: Boolean
    ) {
        companion object {
            fun waiting(
                sourceDayType: CircadianDayType,
                evidence: HourlyEvidence,
                trustedLowCount: Int,
                reasons: Set<String>,
                protectiveReset: Boolean
            ) = RawAdjustment(
                sourceDayType = sourceDayType,
                evidence = evidence,
                desiredDeltaMmol = 0.0,
                trustedLowCount = trustedLowCount,
                status = CircadianAutoState.WAITING_DATA,
                reasonCodes = reasons.toList(),
                protectiveReset = protectiveReset,
                forceNonNegativeApplied = true
            )

            fun blocked(
                sourceDayType: CircadianDayType,
                evidence: HourlyEvidence,
                trustedLowCount: Int,
                status: CircadianAutoState,
                reasons: Set<String>,
                protectiveReset: Boolean
            ) = RawAdjustment(
                sourceDayType = sourceDayType,
                evidence = evidence,
                desiredDeltaMmol = 0.0,
                trustedLowCount = trustedLowCount,
                status = status,
                reasonCodes = reasons.toList(),
                protectiveReset = protectiveReset,
                forceNonNegativeApplied = true
            )
        }
    }

    private data class HourlyEvidence(
        val dayType: CircadianDayType,
        val medianMmol: Double,
        val p25: Double,
        val p75: Double,
        val sampleCount: Int,
        val activeDays: Int,
        val qualityScore: Double,
        val sensorTrustedShare: Double,
        val directionallyHighShare: Double,
        val hasFullWeightEvidence: Boolean,
        val enoughDays: Boolean
    ) {
        companion object {
            fun empty(dayType: CircadianDayType) = HourlyEvidence(
                dayType = dayType,
                medianMmol = 0.0,
                p25 = 0.0,
                p75 = 0.0,
                sampleCount = 0,
                activeDays = 0,
                qualityScore = 0.0,
                sensorTrustedShare = 0.0,
                directionallyHighShare = 0.0,
                hasFullWeightEvidence = false,
                enoughDays = false
            )
        }
    }

    internal data class WeightedValue(
        val value: Double,
        val weight: Double,
        val timestamp: Long
    )

    private data class IndexedCohorts(
        val cleanBySlot: Map<CircadianTargetSlot, List<CircadianTargetSample>>,
        val protectedBySlot: Map<CircadianTargetSlot, List<CircadianTargetSample>>,
        val validDates: Set<LocalDate>,
        val malformedEvidence: Boolean
    ) {
        companion object {
            fun build(input: CircadianTargetEvaluationInput): IndexedCohorts {
                val clean = mutableMapOf<CircadianTargetSlot, MutableList<CircadianTargetSample>>()
                val protected = mutableMapOf<CircadianTargetSlot, MutableList<CircadianTargetSample>>()
                val structurallyValidContextDates = linkedSetOf<LocalDate>()
                var malformed = input.evaluatedAt < 0L ||
                    input.cohorts.reasonCounts.any { (reason, count) ->
                        count < 0 || count > 0 && reason in MALFORMED_COHORT_REASON_CODES
                    } ||
                    !input.cohorts.clean.hasStrictlyIncreasingTimestamps() ||
                    !input.cohorts.allContext.hasStrictlyIncreasingTimestamps()

                input.cohorts.clean.forEach { sample ->
                    if (sample.timestamp !in 0L..input.evaluatedAt) {
                        malformed = true
                        return@forEach
                    }
                    if (sample.localDate.isOlderThanLookback(input.evaluationDate)) return@forEach
                    if (!sample.isValid(input)) {
                        malformed = true
                        return@forEach
                    }
                    clean.getOrPut(CircadianTargetSlot(CircadianDayType.ALL, sample.hour)) {
                        mutableListOf()
                    } += sample
                    clean.getOrPut(CircadianTargetSlot(sample.dayType, sample.hour)) {
                        mutableListOf()
                    } += sample
                }

                input.cohorts.allContext.forEach { sample ->
                    if (sample.timestamp !in 0L..input.evaluatedAt) {
                        malformed = true
                        return@forEach
                    }
                    if (sample.localDate.isOlderThanLookback(input.evaluationDate)) return@forEach
                    if (!sample.isValidContext(input)) {
                        malformed = true
                        return@forEach
                    }
                    structurallyValidContextDates += sample.localDate
                    val targetSlots = protectedTargetSlots(sample)
                    if (targetSlots == null) {
                        malformed = true
                        return@forEach
                    }
                    targetSlots.forEach { targetSlot ->
                        protected.getOrPut(
                            CircadianTargetSlot(CircadianDayType.ALL, targetSlot.hour)
                        ) { mutableListOf() } += sample
                        protected.getOrPut(
                            targetSlot
                        ) { mutableListOf() } += sample
                    }
                }

                val validDates = input.cohorts.validDates.filterTo(linkedSetOf()) { date ->
                    date.isInLookback(input.evaluationDate) && date in structurallyValidContextDates
                }
                if (input.cohorts.validDates.any { it.isFutureOf(input.evaluationDate) }) {
                    malformed = true
                }

                return IndexedCohorts(
                    cleanBySlot = clean.mapValues { (_, rows) -> rows.sortedBy { it.timestamp } },
                    protectedBySlot = protected.mapValues { (_, rows) -> rows.sortedBy { it.timestamp } },
                    validDates = validDates,
                    malformedEvidence = malformed
                )
            }

            private fun CircadianTargetSample.isValid(
                input: CircadianTargetEvaluationInput
            ): Boolean {
                val ageHours = sensorAgeHours ?: return false
                val expectedWeight = if (ageHours >= DOWN_WEIGHT_SENSOR_AGE_HOURS) 0.5 else 1.0
                return isValidContext(input) &&
                    cleanWeight > 0.0 &&
                    ageHours.isKnownHistoricalAge() &&
                    abs(cleanWeight - expectedWeight) < 1e-9 &&
                    effectiveCobGrams?.let {
                        it.isFinite() && it in 0.0..MAXIMUM_DEEP_COB_GRAMS
                    } == true &&
                    !uamActive &&
                    !acuteCarbs &&
                    !postHypo &&
                    !lowRiskLatched &&
                    deliveryTrust != DeliveryTrustState.SUSPECTED_NONRESPONSE
            }

            private fun CircadianTargetSample.isValidContext(
                input: CircadianTargetEvaluationInput
            ): Boolean = timestamp in 0L..input.evaluatedAt &&
                localDate.isInLookback(input.evaluationDate) &&
                localDate in input.cohorts.validDates &&
                hour in 0 until HOURS_PER_DAY &&
                dayType != CircadianDayType.ALL &&
                dayType == localDate.dayOfWeek.toCircadianDayType() &&
                glucoseMmol.isFinite() && glucoseMmol > 0.0 &&
                cleanWeight.isFinite() && cleanWeight in 0.0..FULL_SAMPLE_WEIGHT &&
                sensorTrusted && !sensorBlocked && !suspectFalseLow &&
                (sensorAgeHours == null || sensorAgeHours.isPlausibleHistoricalAge()) &&
                (effectiveCobGrams == null ||
                    effectiveCobGrams.isFinite() && effectiveCobGrams >= 0.0)
        }
    }

    private companion object {
        const val HOURS_PER_DAY = 24
        const val MAX_ADJUSTMENTS = HOURS_PER_DAY * 3
        const val MINIMUM_VALID_DAYS = 7
        const val ALL_MINIMUM_SAMPLES = 40
        const val WEEKDAY_MINIMUM_DAYS = 7
        const val WEEKEND_MINIMUM_DAYS = 4
        const val LOOKBACK_DAYS = 14L
        const val RECENT_DAYS = 7L
        const val RECENT_COHORT_WEIGHT = 0.70
        const val OLDER_COHORT_WEIGHT = 0.30
        const val MINIMUM_QUALITY_SCORE = 0.65
        const val MINIMUM_SENSOR_TRUSTED_SHARE = 0.90
        const val DOWN_WEIGHT_SENSOR_AGE_HOURS = 12.0 * 24.0
        const val MAXIMUM_SENSOR_AGE_HOURS = 14.0 * 24.0
        const val MAXIMUM_DEEP_COB_GRAMS = 5.0
        const val TRUSTED_LOW_MMOl = 4.0
        const val DEEP_LOW_MMOl = 4.4
        const val TARGET_BAND_LOW_MMOl = 5.6
        const val TARGET_BAND_HIGH_MMOl = 6.4
        const val ORDINARY_MINIMUM_DELTA = -0.3
        const val MINIMUM_DELTA = -1.0
        const val MAXIMUM_DELTA = 0.6
        const val DAILY_NEGATIVE_STEP = 0.1
        const val DAILY_POSITIVE_STEP = 0.2
        const val DEEP_DWELL_RUNS = 3
        const val DEEP_MINIMUM_VALID_DAYS = 10
        const val DEEP_DIRECTIONAL_SHARE = 0.80
        const val FULL_SAMPLE_WEIGHT = 1.0
        const val LOWER_QUANTILE = 0.25
        const val MEDIAN_QUANTILE = 0.50
        const val UPPER_QUANTILE = 0.75

        val LOW_RISK_REASON_CODES = setOf(
            "protected_low_below_4_0",
            "post_hypo_or_low_risk_latch",
            "deep_low_below_4_4",
            "delivery_suspected_nonresponse"
        )

        val MALFORMED_COHORT_REASON_CODES = setOf(
            "carbs_unknown",
            "malformed_glucose",
            "duplicate_or_unsorted_glucose"
        )

        fun protectedTargetSlots(sample: CircadianTargetSample): List<CircadianTargetSlot>? =
            runCatching {
                val previousDate = if (sample.hour == 0) {
                    sample.localDate.minusDays(1)
                } else {
                    sample.localDate
                }
                val nextDate = if (sample.hour == HOURS_PER_DAY - 1) {
                    sample.localDate.plusDays(1)
                } else {
                    sample.localDate
                }
                listOf(
                    CircadianTargetSlot(
                        previousDate.dayOfWeek.toCircadianDayType(),
                        (sample.hour - 1 + HOURS_PER_DAY) % HOURS_PER_DAY
                    ),
                    CircadianTargetSlot(sample.dayType, sample.hour),
                    CircadianTargetSlot(
                        nextDate.dayOfWeek.toCircadianDayType(),
                        (sample.hour + 1) % HOURS_PER_DAY
                    )
                )
            }.getOrNull()
    }
}

internal fun baselineDesiredDelta(median: Double, trustedLow: Boolean): Double = when {
    trustedLow -> 0.6
    median < 4.4 -> 0.6
    median < 5.0 -> 0.4
    median < 5.6 -> 0.1
    median <= 6.4 -> 0.0
    median <= 7.2 -> -0.1
    median <= 8.0 -> -0.2
    else -> -0.3
}

internal fun normalDailyMove(previous: Double, desired: Double): Double = quantizeDelta(
    when {
        desired < previous -> max(desired, previous - 0.1)
        desired > previous -> min(desired, previous + 0.2)
        else -> previous
    }.coerceIn(-1.0, 0.6)
)

internal fun smoothCircadianDesiredDeltas(raw: List<Double>): List<Double> {
    require(raw.size == 24) { "Circadian smoothing requires exactly 24 hourly values" }
    val safe = raw.map { value ->
        if (value.isFinite()) quantizeDelta(value.coerceIn(-1.0, 0.6)) else 0.0
    }
    return List(safe.size) { hour ->
        val previous = safe[(hour - 1 + safe.size) % safe.size]
        val current = safe[hour]
        val next = safe[(hour + 1) % safe.size]
        val median = listOf(previous, current, next).sorted()[1]
        quantizeDelta(
            when {
                current > 0.0 -> max(current, median)
                previous > 0.0 || next > 0.0 -> max(0.0, median)
                else -> median
            }
        )
    }
}

private fun weightedQuantile(values: List<CircadianTargetEvaluator.WeightedValue>, quantile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.asSequence()
        .filter { it.value.isFinite() && it.weight.isFinite() && it.weight > 0.0 }
        .sortedWith(compareBy<CircadianTargetEvaluator.WeightedValue> { it.value }.thenBy { it.timestamp })
        .toList()
    if (sorted.isEmpty()) return 0.0
    val totalWeight = sorted.sumOf { it.weight }
    if (!totalWeight.isFinite() || totalWeight <= 0.0) return 0.0
    val threshold = totalWeight * quantile.coerceIn(0.0, 1.0)
    var cumulative = 0.0
    sorted.forEach { value ->
        cumulative += value.weight
        if (cumulative + 1e-12 >= threshold) return value.value
    }
    return sorted.last().value
}

private fun quantizeDelta(value: Double): Double {
    if (!value.isFinite()) return 0.0
    val quantized = round(value.coerceIn(-1.0, 0.6) * 10.0) / 10.0
    return if (abs(quantized) < 0.05) 0.0 else quantized
}

private fun CircadianPreviousAdjustment?.validAppliedDelta(): Double {
    val value = this?.appliedDeltaMmol ?: return 0.0
    return if (value.isFinite() && value in -1.0..0.6) quantizeDelta(value) else 0.0
}

private fun Double?.isKnownCurrentSensorAge(): Boolean =
    this != null && isFinite() && this >= 0.0 && this < 14.0 * 24.0

private fun Double?.isKnownHistoricalAge(): Boolean =
    this != null && isFinite() && this >= 0.0 && this < 14.0 * 24.0

private fun Double.isPlausibleHistoricalAge(): Boolean = isFinite() && this >= 0.0

private fun CircadianPreviousAdjustment.isValidPersisted(): Boolean =
    appliedDeltaMmol.isFinite() &&
        appliedDeltaMmol in -1.0..0.6 &&
        abs(appliedDeltaMmol - quantizeDelta(appliedDeltaMmol)) < 1e-9 &&
        unchangedSuccessfulRuns >= 0

private fun List<CircadianTargetSample>.hasStrictlyIncreasingTimestamps(): Boolean =
    zipWithNext().all { (previous, next) -> previous.timestamp < next.timestamp }

private fun LocalDate.isInLookback(evaluationDate: LocalDate): Boolean =
    ChronoUnit.DAYS.between(this, evaluationDate) in 0 until 14L

private fun LocalDate.isOlderThanLookback(evaluationDate: LocalDate): Boolean =
    ChronoUnit.DAYS.between(this, evaluationDate) >= 14L

private fun LocalDate.isFutureOf(evaluationDate: LocalDate): Boolean =
    ChronoUnit.DAYS.between(this, evaluationDate) < 0L

private fun LocalDate.isRecent(evaluationDate: LocalDate): Boolean =
    ChronoUnit.DAYS.between(this, evaluationDate) in 0 until 7L

private fun LocalDate.isOlder(evaluationDate: LocalDate): Boolean =
    ChronoUnit.DAYS.between(this, evaluationDate) in 7 until 14L

private fun DayOfWeek.toCircadianDayType(): CircadianDayType = when (this) {
    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> CircadianDayType.WEEKEND
    else -> CircadianDayType.WEEKDAY
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun isDeepSafeContext(sample: CircadianTargetSample): Boolean =
    sample.sensorTrusted &&
        !sample.sensorBlocked &&
        !sample.suspectFalseLow &&
        (sample.sensorAgeHours == null || sample.sensorAgeHours.isKnownHistoricalAge()) &&
        sample.effectiveCobGrams?.let {
            it.isFinite() && it in 0.0..5.0
        } == true &&
        !sample.uamActive &&
        !sample.acuteCarbs &&
        !sample.postHypo &&
        !sample.lowRiskLatched &&
        sample.deliveryTrust == DeliveryTrustState.NORMAL
