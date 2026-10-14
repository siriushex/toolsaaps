package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.DataQuality
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlin.math.abs
import kotlin.math.sqrt

internal enum class MealForecastBasis { LOCAL_ENGINE, ACCEPTED_CONTROL }

internal data class MealForecastIdentity(
    val predictionAtMs: Long,
    val forecastCycleId: String,
    val settingsRevision: Long,
    val acceptedForecastDigest: String,
    val source: String,
    val sensorSessionKey: String,
    val glucoseBasisId: String,
    val calibrationModelId: String?,
    val basis: MealForecastBasis,
    val recordedZoneId: String,
    val baselineModelVersion: String = "unspecified"
)

internal data class MealProspectiveValue(
    val horizonMinutes: Int, val timestampMs: Long, val mmol: Double,
    val lowerMmol: Double? = null, val upperMmol: Double? = null
)

internal data class MealProspectiveModel(
    val version: String, val evidenceDigest: String,
    val trainingThroughMs: Long, val availableAtMs: Long
)

internal class MealProspectiveCandidate(
    val identity: MealForecastIdentity,
    val model: MealProspectiveModel,
    val inputThroughMs: Long,
    val inputsAvailableAtMs: Long,
    val startedAtMs: Long,
    val completedAtMs: Long,
    values: List<MealProspectiveValue>
) {
    init { require(values.size <= 3) }
    val values: List<MealProspectiveValue> = Collections.unmodifiableList(values.sortedBy { it.horizonMinutes })
    internal fun signature(): List<Any> = listOf(identity, model, inputThroughMs,
        inputsAvailableAtMs, startedAtMs, completedAtMs, values)
}

internal enum class MealProspectiveRegistrationStatus {
    PAIRED, BASELINE_ONLY, INVALID_BASELINE, INVALID_CANDIDATE,
    CONTEXT_MISMATCH, LATE_REGISTRATION, FUTURE_MODEL, FUTURE_INPUT
}

internal data class MealProspectiveRegistration(
    val status: MealProspectiveRegistrationStatus, val frame: MealProspectiveFrame? = null
)

/** Frozen before the first outcome. Caller must retain an independently trusted receipt. */
internal class MealProspectiveFrame private constructor(
    val identity: MealForecastIdentity,
    val baselineCompletedAtMs: Long,
    val registeredAtMs: Long,
    baseline: List<MealProspectiveValue>,
    val candidate: MealProspectiveCandidate?
) {
    val baseline: List<MealProspectiveValue> = Collections.unmodifiableList(baseline.sortedBy { it.horizonMinutes })
    internal fun signature(): List<Any?> = listOf(identity, baselineCompletedAtMs, baseline, candidate?.signature())

    companion object {
        fun register(identity: MealForecastIdentity, baselineCompletedAtMs: Long,
            baseline: List<MealProspectiveValue>, candidate: MealProspectiveCandidate?,
            registeredAtMs: Long): MealProspectiveRegistration {
            fun reject(status: MealProspectiveRegistrationStatus) = MealProspectiveRegistration(status)
            val anchor = identity.predictionAtMs
            if (anchor <= 0 || anchor > Long.MAX_VALUE - 60 * MINUTE_MS || identity.settingsRevision < 0 ||
                !label(identity.forecastCycleId) || !label(identity.acceptedForecastDigest) ||
                !label(identity.source) || !label(identity.sensorSessionKey) || !label(identity.glucoseBasisId) ||
                identity.basis !in MealForecastBasis.entries ||
                (identity.calibrationModelId != null && !label(identity.calibrationModelId)) ||
                !label(identity.baselineModelVersion) ||
                !label(identity.recordedZoneId) || runCatching { ZoneId.of(identity.recordedZoneId) }.isFailure ||
                baselineCompletedAtMs < anchor || !validValues(baseline, anchor))
                return reject(MealProspectiveRegistrationStatus.INVALID_BASELINE)
            if (registeredAtMs < baselineCompletedAtMs || registeredAtMs >= anchor + 5 * MINUTE_MS)
                return reject(MealProspectiveRegistrationStatus.LATE_REGISTRATION)
            if (candidate != null) {
                if (candidate.identity != identity) return reject(MealProspectiveRegistrationStatus.CONTEXT_MISMATCH)
                if (!label(candidate.model.version) || !label(candidate.model.evidenceDigest) ||
                    !validValues(candidate.values, anchor) || candidate.startedAtMs < baselineCompletedAtMs ||
                    candidate.completedAtMs < candidate.startedAtMs || candidate.completedAtMs > registeredAtMs)
                    return reject(MealProspectiveRegistrationStatus.INVALID_CANDIDATE)
                if (candidate.model.trainingThroughMs <= 0 || candidate.model.trainingThroughMs > anchor ||
                    candidate.model.availableAtMs < candidate.model.trainingThroughMs ||
                    candidate.model.availableAtMs > candidate.startedAtMs)
                    return reject(MealProspectiveRegistrationStatus.FUTURE_MODEL)
                if (candidate.inputThroughMs <= 0 || candidate.inputThroughMs > anchor ||
                    candidate.inputsAvailableAtMs < candidate.inputThroughMs ||
                    candidate.inputsAvailableAtMs > candidate.startedAtMs)
                    return reject(MealProspectiveRegistrationStatus.FUTURE_INPUT)
            }
            return MealProspectiveRegistration(if (candidate == null) MealProspectiveRegistrationStatus.BASELINE_ONLY
                else MealProspectiveRegistrationStatus.PAIRED,
                MealProspectiveFrame(identity, baselineCompletedAtMs, registeredAtMs, baseline, candidate))
        }

        private fun validValues(values: List<MealProspectiveValue>, anchor: Long): Boolean =
            values.size == 3 && values.map { it.horizonMinutes }.toSet() == setOf(5, 30, 60) && values.all {
                it.timestampMs == anchor + it.horizonMinutes * MINUTE_MS && it.mmol.isFinite() && it.mmol in 0.1..100.0 &&
                    ((it.lowerMmol == null && it.upperMmol == null) ||
                        (it.lowerMmol != null && it.upperMmol != null && it.lowerMmol.isFinite() &&
                            it.upperMmol.isFinite() && it.lowerMmol <= it.mmol && it.mmol <= it.upperMmol))
            }

        private fun label(value: String): Boolean = value.isNotBlank() && value.length <= 512
    }
}

internal data class MealProspectiveOutcome(
    val timestampMs: Long, val availableAtMs: Long?, val mmol: Double,
    val quality: DataQuality, val source: String, val sensorSessionKey: String,
    val glucoseBasisId: String, val calibrationModelId: String?
)

internal data class MealProspectiveCohort(
    val settingsRevision: Long, val source: String, val sensorSessionKey: String,
    val glucoseBasisId: String, val calibrationModelId: String?, val basis: MealForecastBasis,
    val recordedZoneId: String, val model: MealProspectiveModel?, val baselineModelVersion: String
)

internal data class MealProspectiveErrors(
    val maeMmol: Double, val rmseMmol: Double, val biasMmol: Double,
    val maximumOverpredictionMmol: Double
)

internal data class MealProspectiveHorizon(
    val cohort: MealProspectiveCohort, val horizonMinutes: Int,
    val pairedSamples: Int, val observedDays: Int, val pending: Int,
    val missingOutcome: Int, val contextMismatch: Int, val rejectedOutcome: Int,
    val conflictingOutcome: Int, val baseline: MealProspectiveErrors?, val candidate: MealProspectiveErrors?,
    val actualLowSamples: Int, val baselineMissedLows: Int, val candidateMissedLows: Int,
    val pairedLowerBoundLowSamples: Int, val baselineLowerBoundMissedLows: Int, val candidateLowerBoundMissedLows: Int,
    val pairedIntervalSamples: Int, val baselineIntervalCoverage: Double?, val candidateIntervalCoverage: Double?,
    val observedSamples: Int = pairedSamples, val baselineObservedIntervalSamples: Int = pairedIntervalSamples
)

internal enum class MealProspectiveEvaluationStatus { EMPTY, OBSERVATIONAL, OVERFLOW }

internal class MealProspectiveEvaluation(
    val status: MealProspectiveEvaluationStatus, val registeredFrames: Int,
    val baselineOnlyFrames: Int, val duplicateFrames: Int, val conflictingFrames: Int,
    val unregisteredFutureFrames: Int, val evaluatedAtMs: Long, val lowThresholdMmol: Double,
    horizons: List<MealProspectiveHorizon>, baselineOnlyHorizons: List<MealProspectiveHorizon> = emptyList()
) {
    val horizons: List<MealProspectiveHorizon> = Collections.unmodifiableList(horizons.toList())
    val baselineOnlyHorizons: List<MealProspectiveHorizon> = Collections.unmodifiableList(baselineOnlyHorizons.toList())
    val clinicalInfluenceAllowed: Boolean get() = false
    val controlSafetyValidated: Boolean get() = false
}

/** Paired observational scores, never an activation decision or counterfactual control outcome. */
internal class MealProspectiveEvaluator {
    fun evaluate(frames: List<MealProspectiveFrame>, outcomes: List<MealProspectiveOutcome>,
        evaluatedAtMs: Long, lowThresholdMmol: Double): MealProspectiveEvaluation {
        require(evaluatedAtMs > 0 && lowThresholdMmol.isFinite() && lowThresholdMmol in 0.1..100.0)
        fun report(status: MealProspectiveEvaluationStatus, baselineOnly: Int = 0, duplicates: Int = 0,
            conflicts: Int = 0, future: Int = 0, horizons: List<MealProspectiveHorizon> = emptyList(),
            baselineHorizons: List<MealProspectiveHorizon> = emptyList()) =
            MealProspectiveEvaluation(status, frames.size, baselineOnly, duplicates, conflicts,
                future, evaluatedAtMs, lowThresholdMmol, horizons, baselineHorizons)
        if (frames.size > 7_000 || outcomes.size > 24_000) return report(MealProspectiveEvaluationStatus.OVERFLOW)
        val availableFrames = frames.filter { it.registeredAtMs <= evaluatedAtMs }
        val futureFrames = frames.size - availableFrames.size
        val baselineOnly = availableFrames.count { it.candidate == null }
        val grouped = availableFrames.groupBy { cohort(it) }
        if (grouped.size > 256) return report(MealProspectiveEvaluationStatus.OVERFLOW)
        val byTime = outcomes.filter { it.availableAtMs == null || it.availableAtMs <= evaluatedAtMs }
            .groupBy { it.timestampMs }
        var duplicates = 0
        var conflicts = 0
        val horizons = mutableListOf<MealProspectiveHorizon>()
        val baselineHorizons = mutableListOf<MealProspectiveHorizon>()
        for ((context, cohortFrames) in grouped) {
            // Repeated cycles for one anchor cannot vote multiple times or select the best forecast.
            val uniqueFrames = cohortFrames.groupBy { it.identity.predictionAtMs }.mapNotNull { (_, sameAnchor) ->
                if (sameAnchor.map { it.signature() }.distinct().size != 1) {
                    conflicts += sameAnchor.size
                    null
                } else {
                    duplicates += sameAnchor.size - 1
                    sameAnchor.minBy { it.registeredAtMs }
                }
            }
            for (horizon in listOf(5, 30, 60)) {
                var pending = 0; var missing = 0; var mismatch = 0; var rejected = 0; var conflicting = 0
                val pairs = mutableListOf<PairScore>()
                for (frame in uniqueFrames) {
                    val target = frame.identity.predictionAtMs + horizon * MINUTE_MS
                    if (target > evaluatedAtMs) { pending++; continue }
                    val all = byTime[target].orEmpty()
                    val same = all.filter { it.source == context.source && it.sensorSessionKey == context.sensorSessionKey &&
                        it.glucoseBasisId == context.glucoseBasisId && it.calibrationModelId == context.calibrationModelId }
                    if (same.isEmpty()) {
                        if (all.isEmpty()) missing++ else mismatch++
                        continue
                    }
                    if (same.map { it.mmol to it.quality }.distinct().size != 1) { conflicting++; continue }
                    if (same.any { !it.mmol.isFinite() || it.mmol !in 0.1..100.0 || it.quality != DataQuality.OK ||
                            it.availableAtMs == null || it.availableAtMs < target || it.availableAtMs <= frame.registeredAtMs }) {
                        rejected++; continue
                    }
                    val actual = same.first().mmol
                    val base = frame.baseline.single { it.horizonMinutes == horizon }
                    val candidate = frame.candidate?.values?.single { it.horizonMinutes == horizon }
                    val day = Instant.ofEpochMilli(frame.identity.predictionAtMs).atZone(ZoneId.of(context.recordedZoneId)).toLocalDate().toString()
                    pairs += PairScore(day, actual, base, candidate)
                }
                val baselineIntervals = pairs.filter { it.baseline.lowerMmol != null }
                val intervals = baselineIntervals.filter { it.candidate?.lowerMmol != null }
                val lows = pairs.filter { it.actual < lowThresholdMmol }
                val intervalLows = intervals.filter { it.actual < lowThresholdMmol }
                val results = if (context.model == null) baselineHorizons else horizons
                results += MealProspectiveHorizon(context, horizon, if (context.model == null) 0 else pairs.size,
                    pairs.map { it.day }.distinct().size,
                    pending, missing, mismatch, rejected, conflicting, errors(pairs, false), errors(pairs, true),
                    lows.size, lows.count { it.baseline.mmol >= lowThresholdMmol },
                    lows.count { it.candidate != null && it.candidate.mmol >= lowThresholdMmol }, intervalLows.size,
                    intervalLows.count { it.baseline.lowerMmol!! >= lowThresholdMmol },
                    intervalLows.count { it.candidate!!.lowerMmol!! >= lowThresholdMmol }, intervals.size,
                    coverage(if (context.model == null) baselineIntervals else intervals, false),
                    coverage(intervals, true), pairs.size, baselineIntervals.size)
            }
        }
        return report(if (horizons.isEmpty() && baselineHorizons.isEmpty()) MealProspectiveEvaluationStatus.EMPTY
            else MealProspectiveEvaluationStatus.OBSERVATIONAL,
            baselineOnly, duplicates, conflicts, futureFrames, horizons, baselineHorizons)
    }

    private fun cohort(frame: MealProspectiveFrame): MealProspectiveCohort = with(frame.identity) {
        MealProspectiveCohort(settingsRevision, source, sensorSessionKey, glucoseBasisId,
            calibrationModelId, basis, recordedZoneId, frame.candidate?.model, baselineModelVersion)
    }

    private data class PairScore(val day: String, val actual: Double,
        val baseline: MealProspectiveValue, val candidate: MealProspectiveValue?)

    private fun errors(pairs: List<PairScore>, candidate: Boolean): MealProspectiveErrors? {
        if (pairs.isEmpty() || (candidate && pairs.any { it.candidate == null })) return null
        val days = pairs.groupBy { it.day }.values.map { samples -> samples.map {
            (if (candidate) it.candidate!!.mmol else it.baseline.mmol) - it.actual
        } }
        return MealProspectiveErrors(days.map { it.map(::abs).average() }.average(),
            sqrt(days.map { it.map { error -> error * error }.average() }.average()),
            days.map { it.average() }.average(), maxOf(0.0, days.maxOf { it.max() }))
    }

    private fun coverage(pairs: List<PairScore>, candidate: Boolean): Double? {
        if (pairs.isEmpty()) return null
        return pairs.groupBy { it.day }.values.map { samples -> samples.count {
            val value = if (candidate) it.candidate!! else it.baseline
            it.actual >= value.lowerMmol!! && it.actual <= value.upperMmol!!
        }.toDouble() / samples.size }.average()
    }
}

private const val MINUTE_MS = 60_000L
