package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import kotlin.math.abs

internal data class CalibrationTrustSnapshot(
    val sensorBlocked: Boolean,
    val sensorSuspectFalseLow: Boolean,
    val stale: Boolean,
    val available: Boolean
)

internal data class CalibrationCheckAssessment(
    val lagAlignedTs: Long?,
    val effectiveLagMinutes: Double,
    val matchedRaw: GlucosePoint?,
    val status: BloodGlucoseCheckStatus,
    val reason: String,
    val absoluteGapMmol: Double?,
    val relativeGap: Double?,
    val alignedTrust: CalibrationTrustSnapshot
)

internal fun calibrationQualityTimestamp(check: BloodGlucoseCheck): Long =
    check.lagAlignedTs ?: check.timestamp

internal object GlucoseCalibrationGuard {

    internal class PreparedRawGlucose internal constructor(
        internal val points: List<GlucosePoint>,
        internal val sensorErrorTimestamps: List<Long>,
        internal val invalidTimestamp: Boolean
    )

    fun assess(
        sessionKey: String?,
        bloodTs: Long,
        bloodMmol: Double,
        lagMinutes: Double,
        rawGlucose: List<GlucosePoint>,
        telemetryAt: (Long) -> CalibrationTrustSnapshot
    ): CalibrationCheckAssessment = assessPrepared(
        sessionKey = sessionKey,
        bloodTs = bloodTs,
        bloodMmol = bloodMmol,
        lagMinutes = lagMinutes,
        preparedRawGlucose = prepareRawGlucose(rawGlucose),
        telemetryAt = telemetryAt
    )

    fun assessPrepared(
        sessionKey: String?,
        bloodTs: Long,
        bloodMmol: Double,
        lagMinutes: Double,
        preparedRawGlucose: PreparedRawGlucose,
        telemetryAt: (Long) -> CalibrationTrustSnapshot
    ): CalibrationCheckAssessment {
        val effectiveLagMinutes = if (lagMinutes.isFinite()) {
            lagMinutes.coerceIn(0.0, MAX_CALIBRATION_LAG_MINUTES)
        } else {
            DEFAULT_CALIBRATION_LAG_MINUTES
        }
        val lagAlignedTs = checkedLagAlignedTimestamp(
            bloodTs = bloodTs,
            effectiveLagMinutes = effectiveLagMinutes
        ) ?: return invalidAssessment(
            lagAlignedTs = null,
            effectiveLagMinutes = effectiveLagMinutes
        )
        if (!bloodMmol.isFinite()) {
            return invalidAssessment(
                lagAlignedTs = lagAlignedTs,
                effectiveLagMinutes = effectiveLagMinutes
            )
        }
        val matchedRaw = matchPreparedRawGlucoseAt(preparedRawGlucose, lagAlignedTs)
        val alignedTrust = telemetryAt(lagAlignedTs)
        val absoluteGapMmol = matchedRaw?.let { abs(bloodMmol - it.valueMmol) }
        val relativeGap = matchedRaw?.let {
            abs(bloodMmol - it.valueMmol) / maxOf(abs(bloodMmol), abs(it.valueMmol), 1.0)
        }
        val (status, reason) = when {
            sessionKey.isNullOrBlank() ->
                BloodGlucoseCheckStatus.OUT_OF_WINDOW to "sensor_session_unresolved"
            alignedTrust.sensorBlocked || alignedTrust.sensorSuspectFalseLow ->
                BloodGlucoseCheckStatus.REJECTED to "sensor_blocked_aligned"
            matchedRaw == null ->
                BloodGlucoseCheckStatus.OUT_OF_WINDOW to "aligned_glucose_gap"
            !alignedTrust.available || alignedTrust.stale || matchedRaw.quality != DataQuality.OK ->
                BloodGlucoseCheckStatus.STALE to "stale_sensor_context"
            absoluteGapMmol != null && relativeGap != null &&
                (absoluteGapMmol > MAX_ABSOLUTE_GAP_MMOL || relativeGap > MAX_RELATIVE_GAP) ->
                BloodGlucoseCheckStatus.REJECTED to "raw_blood_gap_extreme"
            else -> BloodGlucoseCheckStatus.VALID to "aligned"
        }
        return CalibrationCheckAssessment(
            lagAlignedTs = lagAlignedTs,
            effectiveLagMinutes = effectiveLagMinutes,
            matchedRaw = matchedRaw,
            status = status,
            reason = reason,
            absoluteGapMmol = absoluteGapMmol,
            relativeGap = relativeGap,
            alignedTrust = alignedTrust
        )
    }

    fun matchRawGlucoseAt(
        glucose: List<GlucosePoint>,
        targetTs: Long
    ): GlucosePoint? = matchPreparedRawGlucoseAt(prepareRawGlucose(glucose), targetTs)

    fun prepareRawGlucose(glucose: List<GlucosePoint>): PreparedRawGlucose {
        val sanitized = GlucoseSanitizer.filterPoints(glucose)
        return PreparedRawGlucose(
            points = sanitized
                .filter {
                    it.valueMmol.isFinite() &&
                        it.valueMmol > 0.0 &&
                        it.quality != DataQuality.SENSOR_ERROR
                },
            sensorErrorTimestamps = sanitized
                .asSequence()
                .filter { it.quality == DataQuality.SENSOR_ERROR }
                .map { it.ts }
                .toList(),
            invalidTimestamp = glucose.any { it.ts < 0L }
        )
    }

    fun matchPreparedRawGlucoseAt(
        prepared: PreparedRawGlucose,
        targetTs: Long
    ): GlucosePoint? {
        if (targetTs < 0L || prepared.invalidTimestamp) return null
        if (hasSensorErrorBetween(prepared.sensorErrorTimestamps, targetTs, targetTs)) return null
        val points = prepared.points
        if (points.isEmpty()) return null
        val insertionIndex = lowerBound(points, targetTs)
        val exact = points.getOrNull(insertionIndex)?.takeIf { it.ts == targetTs }
        if (exact != null) return exact

        val before = points.getOrNull(insertionIndex - 1)
        val after = points.getOrNull(insertionIndex)
        if (before != null && after != null) {
            val beforeDelta = timestampDistanceMs(targetTs, before.ts)
            val afterDelta = timestampDistanceMs(after.ts, targetTs)
            val totalGap = timestampDistanceMs(after.ts, before.ts)
            if (beforeDelta <= INTERPOLATION_MAX_SIDE_MS &&
                afterDelta <= INTERPOLATION_MAX_SIDE_MS &&
                totalGap in 1..INTERPOLATION_MAX_TOTAL_GAP_MS &&
                !hasSensorErrorBetween(prepared.sensorErrorTimestamps, before.ts, after.ts)
            ) {
                val ratio = beforeDelta.toDouble() / totalGap.toDouble()
                return before.copy(
                    ts = targetTs,
                    valueMmol = before.valueMmol + (after.valueMmol - before.valueMmol) * ratio,
                    quality = worstQuality(before.quality, after.quality)
                )
            }
        }

        val nearest = when {
            before == null -> after
            after == null -> before
            timestampDistanceMs(before.ts, targetTs) <= timestampDistanceMs(after.ts, targetTs) -> before
            else -> after
        } ?: return null
        if (hasSensorErrorBetween(
                prepared.sensorErrorTimestamps,
                minOf(targetTs, nearest.ts),
                maxOf(targetTs, nearest.ts)
            )
        ) {
            return null
        }
        return nearest.takeIf { timestampDistanceMs(it.ts, targetTs) <= NEAREST_MATCH_MAX_MS }
            ?.copy(ts = targetTs)
    }

    private fun hasSensorErrorBetween(
        timestamps: List<Long>,
        startInclusive: Long,
        endInclusive: Long
    ): Boolean {
        var low = 0
        var high = timestamps.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (timestamps[middle] < startInclusive) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low < timestamps.size && timestamps[low] <= endInclusive
    }

    private fun lowerBound(points: List<GlucosePoint>, targetTs: Long): Int {
        var low = 0
        var high = points.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (points[middle].ts < targetTs) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    private fun checkedLagAlignedTimestamp(
        bloodTs: Long,
        effectiveLagMinutes: Double
    ): Long? {
        if (bloodTs < 0L) return null
        val lagMs = (effectiveLagMinutes * MINUTE_MS).toLong()
        if (bloodTs > Long.MAX_VALUE - lagMs) return null
        return bloodTs + lagMs
    }

    private fun invalidAssessment(
        lagAlignedTs: Long?,
        effectiveLagMinutes: Double
    ): CalibrationCheckAssessment = CalibrationCheckAssessment(
        lagAlignedTs = lagAlignedTs,
        effectiveLagMinutes = effectiveLagMinutes,
        matchedRaw = null,
        status = BloodGlucoseCheckStatus.REJECTED,
        reason = "invalid_calibration_input",
        absoluteGapMmol = null,
        relativeGap = null,
        alignedTrust = CalibrationTrustSnapshot(
            sensorBlocked = false,
            sensorSuspectFalseLow = false,
            stale = true,
            available = false
        )
    )

    private fun worstQuality(first: DataQuality, second: DataQuality): DataQuality =
        if (first.ordinal >= second.ordinal) first else second

    private fun timestampDistanceMs(firstTs: Long, secondTs: Long): Long =
        if (firstTs >= secondTs) firstTs - secondTs else secondTs - firstTs

    private const val MINUTE_MS = 60_000L
    private const val DEFAULT_CALIBRATION_LAG_MINUTES = 10.0
    private const val MAX_CALIBRATION_LAG_MINUTES = 20.0
    private const val MAX_ABSOLUTE_GAP_MMOL = 3.0
    private const val MAX_RELATIVE_GAP = 0.40
    private const val INTERPOLATION_MAX_SIDE_MS = CALIBRATION_MATCH_MAX_FORWARD_MS
    private const val INTERPOLATION_MAX_TOTAL_GAP_MS = 7L * MINUTE_MS
    private const val NEAREST_MATCH_MAX_MS = 3L * MINUTE_MS
}

internal const val CALIBRATION_MATCH_MAX_FORWARD_MS = 6L * 60_000L
