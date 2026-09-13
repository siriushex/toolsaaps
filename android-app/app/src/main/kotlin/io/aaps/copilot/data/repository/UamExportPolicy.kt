package io.aaps.copilot.data.repository

import java.math.BigDecimal
import java.math.RoundingMode

internal data class UamExportLedgerEntry(
    val tsMs: Long,
    val grams: Double,
    val seq: Int,
    val episodeId: String? = null
)

internal data class UamExportPolicyInput(
    val nowTs: Long,
    val episodeId: String,
    val activeSinceTs: Long?,
    val confidence: Double,
    val supportedLowerBoundGrams: Double?,
    val lowerBoundStableBuckets: Int,
    val sensorTrust: Double,
    val sensorBlocked: Boolean,
    val signedResidualMmol5: Double,
    val shortAverageDeltaMmol5: Double,
    val currentGlucoseMmol: Double,
    val forecastMinimumMmol: Double,
    val effectiveCobGrams: Double,
    val therapyCoverage: Double,
    val remoteLedger: List<UamExportLedgerEntry>,
    val sourceSnapshotTs: Long = nowTs,
    val globalRemoteLedger: List<UamExportLedgerEntry> = remoteLedger,
    val maximumIncrementGrams: Double = UamExportPolicy.MAX_INCREMENT_G
)

internal sealed interface UamExportDecision {
    data class Block(val reason: String) : UamExportDecision
    data class Send(val grams: Double, val treatmentTs: Long, val seq: Int) : UamExportDecision
}

internal object UamExportPolicy {
    /** Canonical gram accounting and minimum export quantum: one decigram. */
    const val MIN_SEND_QUANTUM_G = 0.1
    const val MAX_INCREMENT_G = 15.0
    const val MIN_INTERVAL_MIN = 10L
    const val MAX_ROLLING_30_G = 30.0
    const val MAX_EPISODE_60_G = 50.0
    const val EPISODE_WINDOW_MIN = 60L
    const val MIN_ACTIVE_MIN = 10L
    const val MIN_CONFIDENCE = 0.55
    const val STOP_CONFIDENCE = 0.55
    const val MIN_SENSOR_TRUST = 0.70
    const val MIN_THERAPY_COVERAGE = 0.70
    const val MAX_SOURCE_SNAPSHOT_AGE_MIN = 10L

    fun decide(input: UamExportPolicyInput): UamExportDecision {
        if (input.episodeId.isBlank()) return block("invalid_episode_id")
        if (input.nowTs < 0L) return block("invalid_now_timestamp")
        if (input.sourceSnapshotTs < 0L) return block("invalid_source_snapshot_timestamp")
        if (input.sourceSnapshotTs > input.nowTs) return block("source_snapshot_in_future")
        if (input.nowTs - input.sourceSnapshotTs > MAX_SOURCE_SNAPSHOT_AGE_MS) {
            return block("source_snapshot_stale")
        }
        if (!input.confidence.isNormalized()) return block("invalid_confidence")
        if (!input.sensorTrust.isNormalized()) return block("invalid_sensor_trust")
        if (!input.signedResidualMmol5.isFinite()) return block("invalid_signed_residual")
        if (!input.shortAverageDeltaMmol5.isFinite()) return block("invalid_short_average")
        if (!input.currentGlucoseMmol.isFinite()) return block("invalid_current_glucose")
        if (!input.forecastMinimumMmol.isFinite()) return block("invalid_forecast_minimum")
        if (input.currentGlucoseMmol < LOW_GLUCOSE_FLOOR_MMOL) return block("current_glucose_below_4")
        if (input.forecastMinimumMmol < LOW_GLUCOSE_FLOOR_MMOL) return block("forecast_below_4")
        if (!input.effectiveCobGrams.isFinite() || input.effectiveCobGrams < 0.0) {
            return block("invalid_effective_cob")
        }
        if (!input.therapyCoverage.isNormalized()) return block("invalid_therapy_coverage")
        if (
            !input.maximumIncrementGrams.isFinite() ||
            input.maximumIncrementGrams < MIN_SEND_QUANTUM_G ||
            input.maximumIncrementGrams > MAX_INCREMENT_G
        ) {
            return block("invalid_maximum_increment")
        }

        val supportedLowerBound = input.supportedLowerBoundGrams
        if (supportedLowerBound == null || !supportedLowerBound.isFinite() || supportedLowerBound <= 0.0) {
            return block("invalid_supported_lower_bound")
        }

        val activeSinceTs = input.activeSinceTs ?: return block("active_time_missing")
        if (activeSinceTs < 0L) return block("invalid_active_timestamp")
        if (activeSinceTs > input.nowTs) return block("active_time_in_future")

        val ledger = reconcileLedger(input)
        if (ledger is LedgerResult.Invalid) return block(ledger.reason)
        ledger as LedgerResult.Valid
        val globalLedger = reconcileGlobalLedger(input)
        if (globalLedger is GlobalLedgerResult.Invalid) return block(globalLedger.reason)
        globalLedger as GlobalLedgerResult.Valid
        val supportedLowerBoundUnits = gramsToUnits(supportedLowerBound, RoundingMode.FLOOR)
            ?: return block("invalid_supported_lower_bound")
        val maximumIncrementUnits = gramsToUnits(input.maximumIncrementGrams, RoundingMode.FLOOR)
            ?: return block("invalid_maximum_increment")

        if (ledger.firstTs != null && input.nowTs - ledger.firstTs >= EPISODE_WINDOW_MS) {
            return block("episode_window_closed")
        }
        if (ledger.totalUnits >= MAX_EPISODE_UNITS) return block("episode_capacity_exhausted")
        if (ledger.maxSeq == Int.MAX_VALUE) return block("ledger_sequence_exhausted")
        if (input.nowTs - activeSinceTs < MIN_ACTIVE_MS) return block("active_under_10m")

        if (input.sensorBlocked) return block("sensor_blocked")
        if (input.lowerBoundStableBuckets < MIN_STABLE_BUCKETS) return block("lower_bound_not_stable")
        if (input.sensorTrust < MIN_SENSOR_TRUST) return block("sensor_trust_below_min")
        if (input.signedResidualMmol5 <= 0.0) return block("signed_residual_not_positive")
        if (input.shortAverageDeltaMmol5 <= 0.0) return block("short_average_not_positive")
        if (input.therapyCoverage < MIN_THERAPY_COVERAGE) return block("therapy_coverage_below_min")

        val minimumConfidence = if (input.remoteLedger.isEmpty()) MIN_CONFIDENCE else STOP_CONFIDENCE
        if (input.confidence < minimumConfidence) {
            val reason = if (input.remoteLedger.isEmpty()) {
                "confidence_below_initial_min"
            } else {
                "confidence_below_continuation_min"
            }
            return block(reason)
        }

        val lastGlobalWriteTs = listOfNotNull(ledger.lastTs, globalLedger.lastTs).maxOrNull()
        if (lastGlobalWriteTs != null && input.nowTs - lastGlobalWriteTs < MIN_INTERVAL_MS) {
            return block("write_interval_under_10m")
        }

        val supportedRemainingUnits = supportedLowerBoundUnits - ledger.totalUnits
        if (supportedRemainingUnits < MIN_SEND_QUANTUM_UNITS) {
            return block("supported_lower_bound_fulfilled")
        }

        val rollingRemainingUnits = MAX_ROLLING_30_UNITS - ledger.rollingUnits
        if (rollingRemainingUnits < MIN_SEND_QUANTUM_UNITS) {
            return block("rolling_30m_capacity_exhausted")
        }

        val globalRolling30RemainingUnits = MAX_ROLLING_30_UNITS - globalLedger.rolling30Units
        if (globalRolling30RemainingUnits < MIN_SEND_QUANTUM_UNITS) {
            return block("global_rolling_30m_capacity_exhausted")
        }

        val globalRolling60RemainingUnits = MAX_EPISODE_UNITS - globalLedger.rolling60Units
        if (globalRolling60RemainingUnits < MIN_SEND_QUANTUM_UNITS) {
            return block("global_rolling_60m_capacity_exhausted")
        }

        val episodeRemainingUnits = MAX_EPISODE_UNITS - ledger.totalUnits
        if (episodeRemainingUnits < MIN_SEND_QUANTUM_UNITS) {
            return block("episode_capacity_exhausted")
        }

        val sendUnits = minOf(
            supportedRemainingUnits,
            maximumIncrementUnits,
            rollingRemainingUnits,
            episodeRemainingUnits,
            globalRolling30RemainingUnits,
            globalRolling60RemainingUnits
        )

        return UamExportDecision.Send(
            grams = sendUnits.toDouble() / GRAM_UNITS_PER_GRAM,
            treatmentTs = (input.nowTs / FIVE_MINUTE_MS) * FIVE_MINUTE_MS,
            seq = ledger.maxSeq + 1
        )
    }

    private fun reconcileLedger(input: UamExportPolicyInput): LedgerResult {
        val sequences = HashSet<Int>(input.remoteLedger.size)
        val canonicalEntries = ArrayList<CanonicalLedgerEntry>(input.remoteLedger.size)
        var hasStructuralInvalidity = false
        var hasFutureTimestamp = false

        // Complete the scan before deciding so validation priority cannot depend on list order.
        input.remoteLedger.forEach { entry ->
            val duplicateSequence = !sequences.add(entry.seq)
            val units = if (entry.grams.isFinite() && entry.grams > 0.0) {
                gramsToUnits(entry.grams, RoundingMode.CEILING)
            } else {
                null
            }
            val structurallyInvalid = entry.tsMs < 0L ||
                entry.grams <= 0.0 ||
                !entry.grams.isFinite() ||
                entry.seq <= 0 ||
                duplicateSequence ||
                units == null

            hasStructuralInvalidity = hasStructuralInvalidity || structurallyInvalid
            hasFutureTimestamp = hasFutureTimestamp || entry.tsMs > input.nowTs
            if (!structurallyInvalid) {
                canonicalEntries += CanonicalLedgerEntry(
                    tsMs = entry.tsMs,
                    units = requireNotNull(units),
                    seq = entry.seq
                )
            }
        }

        if (hasStructuralInvalidity) return LedgerResult.Invalid("invalid_ledger")

        val rollingCutoff = input.nowTs - ROLLING_WINDOW_MS
        var totalUnits = 0L
        var rollingUnits = 0L
        var firstTs: Long? = null
        var lastTs: Long? = null
        var maxSeq = 0

        canonicalEntries.forEach { entry ->
            totalUnits = addUnitsOrNull(totalUnits, entry.units)
                ?: return LedgerResult.Invalid("invalid_ledger")
            if (entry.tsMs > rollingCutoff) {
                rollingUnits = addUnitsOrNull(rollingUnits, entry.units)
                    ?: return LedgerResult.Invalid("invalid_ledger")
            }
            firstTs = firstTs?.let { minOf(it, entry.tsMs) } ?: entry.tsMs
            lastTs = lastTs?.let { maxOf(it, entry.tsMs) } ?: entry.tsMs
            maxSeq = maxOf(maxSeq, entry.seq)
        }

        if (hasFutureTimestamp) return LedgerResult.Invalid("ledger_entry_in_future")

        return LedgerResult.Valid(
            totalUnits = totalUnits,
            rollingUnits = rollingUnits,
            firstTs = firstTs,
            lastTs = lastTs,
            maxSeq = maxSeq
        )
    }

    private fun reconcileGlobalLedger(input: UamExportPolicyInput): GlobalLedgerResult {
        val identities = HashSet<Pair<String, Int>>(input.globalRemoteLedger.size)
        val rolling30Cutoff = input.nowTs - ROLLING_WINDOW_MS
        val rolling60Cutoff = input.nowTs - EPISODE_WINDOW_MS
        var rolling30Units = 0L
        var rolling60Units = 0L
        var lastTs: Long? = null
        var hasFutureTimestamp = false

        input.globalRemoteLedger.forEach { entry ->
            val episodeId = entry.episodeId ?: input.episodeId
            val units = if (entry.grams.isFinite() && entry.grams > 0.0) {
                gramsToUnits(entry.grams, RoundingMode.CEILING)
            } else {
                null
            }
            val structurallyInvalid = episodeId.isBlank() ||
                entry.tsMs < 0L ||
                entry.seq <= 0 ||
                units == null ||
                !identities.add(episodeId to entry.seq)
            if (structurallyInvalid) return GlobalLedgerResult.Invalid("invalid_global_ledger")

            hasFutureTimestamp = hasFutureTimestamp || entry.tsMs > input.nowTs
            lastTs = lastTs?.let { maxOf(it, entry.tsMs) } ?: entry.tsMs
            if (entry.tsMs > rolling30Cutoff) {
                rolling30Units = addUnitsOrNull(rolling30Units, requireNotNull(units))
                    ?: return GlobalLedgerResult.Invalid("invalid_global_ledger")
            }
            if (entry.tsMs > rolling60Cutoff) {
                rolling60Units = addUnitsOrNull(rolling60Units, requireNotNull(units))
                    ?: return GlobalLedgerResult.Invalid("invalid_global_ledger")
            }
        }

        if (hasFutureTimestamp) return GlobalLedgerResult.Invalid("global_ledger_entry_in_future")
        return GlobalLedgerResult.Valid(
            rolling30Units = rolling30Units,
            rolling60Units = rolling60Units,
            lastTs = lastTs
        )
    }

    private fun gramsToUnits(grams: Double, roundingMode: RoundingMode): Long? = try {
        BigDecimal.valueOf(grams)
            .movePointRight(GRAM_SCALE)
            .setScale(0, roundingMode)
            .longValueExact()
    } catch (_: ArithmeticException) {
        null
    }

    private fun addUnitsOrNull(left: Long, right: Long): Long? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun Double.isNormalized(): Boolean = isFinite() && this in 0.0..1.0

    private fun block(reason: String) = UamExportDecision.Block(reason)

    private data class CanonicalLedgerEntry(
        val tsMs: Long,
        val units: Long,
        val seq: Int
    )

    private sealed interface LedgerResult {
        data class Valid(
            val totalUnits: Long,
            val rollingUnits: Long,
            val firstTs: Long?,
            val lastTs: Long?,
            val maxSeq: Int
        ) : LedgerResult

        data class Invalid(val reason: String) : LedgerResult
    }

    private sealed interface GlobalLedgerResult {
        data class Valid(
            val rolling30Units: Long,
            val rolling60Units: Long,
            val lastTs: Long?
        ) : GlobalLedgerResult

        data class Invalid(val reason: String) : GlobalLedgerResult
    }

    private const val MIN_STABLE_BUCKETS = 2
    private const val GRAM_SCALE = 1
    private const val GRAM_UNITS_PER_GRAM = 10.0
    private const val MIN_SEND_QUANTUM_UNITS = 1L
    private const val MAX_ROLLING_30_UNITS = 300L
    private const val MAX_EPISODE_UNITS = 500L
    private const val LOW_GLUCOSE_FLOOR_MMOL = 4.0
    private const val MINUTE_MS = 60_000L
    private const val FIVE_MINUTE_MS = 5 * MINUTE_MS
    private const val ROLLING_WINDOW_MS = 30 * MINUTE_MS
    private const val MIN_INTERVAL_MS = MIN_INTERVAL_MIN * MINUTE_MS
    private const val EPISODE_WINDOW_MS = EPISODE_WINDOW_MIN * MINUTE_MS
    private const val MIN_ACTIVE_MS = MIN_ACTIVE_MIN * MINUTE_MS
    private const val MAX_SOURCE_SNAPSHOT_AGE_MS = MAX_SOURCE_SNAPSHOT_AGE_MIN * MINUTE_MS
}
