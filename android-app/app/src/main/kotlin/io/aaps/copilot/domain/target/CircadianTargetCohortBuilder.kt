package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import kotlin.math.abs

class CircadianTargetCohortBuilder(
    private val zoneId: ZoneId,
    private val config: CircadianTargetCohortConfig = CircadianTargetCohortConfig(),
    private val deliveryTrustEvaluator: DeliveryTrustEvaluator = DeliveryTrustEvaluator()
) {

    fun build(
        canonicalGlucose: List<GlucosePoint>,
        telemetry: List<CircadianTelemetrySignal>,
        therapyEvents: List<TherapyEvent>
    ): CircadianTargetCohorts {
        val clean = mutableListOf<CircadianTargetSample>()
        val allContext = mutableListOf<CircadianTargetSample>()
        val validDates = linkedSetOf<java.time.LocalDate>()
        val reasons = linkedMapOf<String, Int>()
        val telemetryCursor = TelemetryCursor(telemetry, config.telemetryFreshnessMinutes)
        val carbTracker = CarbWindowTracker(therapyEvents, config.announcedCarbAbsorptionMinutes)
        val recentGlucose = ArrayDeque<GlucosePoint>(DELIVERY_RISE_POINT_COUNT)
        var previousTimestamp: Long? = null

        canonicalGlucose.forEach { glucose ->
            if (!glucose.isCanonicalInput()) {
                reasons.increment("malformed_glucose")
                return@forEach
            }
            if (previousTimestamp?.let { glucose.ts <= it } == true) {
                reasons.increment("duplicate_or_unsorted_glucose")
                return@forEach
            }
            previousTimestamp = glucose.ts
            recentGlucose.addLast(glucose)
            while (recentGlucose.size > DELIVERY_RISE_POINT_COUNT) recentGlucose.removeFirst()

            val signals = telemetryCursor.snapshotAt(glucose.ts)
            val carbs = carbTracker.snapshotAt(glucose.ts)
            val evidence = resolveEvidence(signals)
            val sensorTrusted = evidence.sensorQuality
                ?.let { it >= config.minimumTrustedSensorQuality } == true &&
                evidence.sensorBlocked == false &&
                evidence.suspectFalseLow == false
            val sensorTrust = when {
                evidence.sensorBlocked == true -> SensorTrustState.BLOCKED
                sensorTrusted -> SensorTrustState.TRUSTED
                else -> SensorTrustState.WARN
            }
            val deliveryTrust = deliveryTrustEvaluator.evaluate(
                DeliveryTrustInput(
                    evaluationTimestamp = glucose.ts,
                    canonicalGlucose = recentGlucose.toList(),
                    sensorTrust = sensorTrust,
                    iobUnits = evidence.iobUnits,
                    effectiveCobGrams = evidence.effectiveCobGrams,
                    uamActive = evidence.uamActive,
                    announcedCarbsKnown = !carbs.malformed,
                    announcedCarbTimestamps = carbs.timestamps,
                    setAgeHours = evidence.setAgeHours
                )
            )
            val cleanWeight = when {
                evidence.sensorAgeHours == null -> 0.0
                evidence.sensorAgeHours >= config.maximumSensorAgeHours -> 0.0
                evidence.sensorAgeHours >= config.downWeightSensorAgeHours -> 0.5
                else -> 1.0
            }
            val local = Instant.ofEpochMilli(glucose.ts).atZone(zoneId)
            val sample = CircadianTargetSample(
                timestamp = glucose.ts,
                localDate = local.toLocalDate(),
                hour = local.hour,
                dayType = local.dayOfWeek.toCircadianDayType(),
                glucoseMmol = glucose.valueMmol,
                sensorTrusted = sensorTrusted,
                sensorAgeHours = evidence.sensorAgeHours,
                sensorBlocked = evidence.sensorBlocked == true,
                suspectFalseLow = evidence.suspectFalseLow == true,
                effectiveCobGrams = evidence.effectiveCobGrams,
                uamActive = evidence.uamActive == true,
                acuteCarbs = carbs.timestamps.isNotEmpty(),
                postHypo = evidence.lowRiskActive == true,
                lowRiskLatched = evidence.lowRiskLatched == true,
                deliveryTrust = deliveryTrust,
                cleanWeight = cleanWeight
            )

            val exclusionReasons = cleanExclusionReasons(
                evidence = evidence,
                sensorTrusted = sensorTrusted,
                carbs = carbs,
                deliveryTrust = deliveryTrust
            )
            exclusionReasons.forEach(reasons::increment)
            if (cleanWeight == 0.5) reasons.increment("sensor_age_down_weighted")

            if (sensorTrusted) {
                allContext += sample
                validDates += sample.localDate
            }
            if (exclusionReasons.isEmpty()) clean += sample
        }

        return CircadianTargetCohorts(
            clean = clean,
            allContext = allContext,
            validDates = validDates,
            reasonCounts = reasons.toSortedMap()
        )
    }

    private fun cleanExclusionReasons(
        evidence: AlignedEvidence,
        sensorTrusted: Boolean,
        carbs: CarbWindow,
        deliveryTrust: DeliveryTrustState
    ): Set<String> = buildSet {
        if (evidence.sensorQuality == null ||
            evidence.sensorBlocked == null ||
            evidence.suspectFalseLow == null
        ) {
            add("sensor_signal_missing_or_stale")
        }
        if (!sensorTrusted) add("sensor_untrusted")
        if (evidence.sensorBlocked == true) add("sensor_blocked")
        if (evidence.suspectFalseLow == true) add("sensor_suspect_false_low")
        when {
            evidence.sensorAgeHours == null -> add("sensor_age_unknown")
            evidence.sensorAgeHours >= config.maximumSensorAgeHours -> add("sensor_age_old")
        }
        when {
            evidence.effectiveCobGrams == null -> add("cob_unknown")
            evidence.effectiveCobGrams > config.maximumCleanCobGrams -> add("cob_active")
        }
        when (evidence.uamActive) {
            null -> add("uam_unknown")
            true -> add("uam_active")
            false -> Unit
        }
        if (carbs.malformed) add("carbs_unknown")
        if (carbs.timestamps.isNotEmpty()) add("acute_carbs")
        when (evidence.lowRiskActive) {
            null -> add("post_hypo_unknown")
            true -> add("post_hypo")
            false -> Unit
        }
        when (evidence.lowRiskLatched) {
            null -> add("low_risk_latch_unknown")
            true -> add("low_risk_latched")
            false -> Unit
        }
        if (deliveryTrust == DeliveryTrustState.SUSPECTED_NONRESPONSE) {
            add("delivery_suspected_nonresponse")
        }
    }

    private fun resolveEvidence(signals: Map<String, CircadianTelemetrySignal?>): AlignedEvidence {
        val sensorQuality = signals.numericInRange("sensor_quality_score", 0.0, 1.0)
        val sensorBlocked = signals.flag("sensor_quality_blocked")
        val suspectFalseLow = signals.flag("sensor_quality_suspect_false_low")
        val sensorAgeHours = signals.preferredNonNegative(
            primary = "sensor_age_hours",
            fallback = "sensor_age_days",
            fallbackMultiplier = 24.0
        )
        val effectiveCob = signals.preferredNonNegative(
            primary = "cob_effective_grams",
            fallback = "cob_grams"
        )
        val iob = signals.nonNegative("iob_units")
        val uam = signals.unifiedFlag("uam_runtime_control_flag", "uam_runtime_flag")
        val lowRiskActive = signals.flag("target_low_risk_active")
        val lowRiskLatched = signals.flag("target_low_risk_latched")
        val setAgeHours = signals.preferredNonNegative(
            primary = "isf_factor_set_age_hours",
            fallback = "cage_days",
            fallbackMultiplier = 24.0
        )
        return AlignedEvidence(
            sensorQuality = sensorQuality,
            sensorBlocked = sensorBlocked,
            suspectFalseLow = suspectFalseLow,
            sensorAgeHours = sensorAgeHours,
            effectiveCobGrams = effectiveCob,
            iobUnits = iob,
            uamActive = uam,
            lowRiskActive = lowRiskActive,
            lowRiskLatched = lowRiskLatched,
            setAgeHours = setAgeHours
        )
    }

    private data class AlignedEvidence(
        val sensorQuality: Double?,
        val sensorBlocked: Boolean?,
        val suspectFalseLow: Boolean?,
        val sensorAgeHours: Double?,
        val effectiveCobGrams: Double?,
        val iobUnits: Double?,
        val uamActive: Boolean?,
        val lowRiskActive: Boolean?,
        val lowRiskLatched: Boolean?,
        val setAgeHours: Double?
    )

    private class TelemetryCursor(
        telemetry: List<CircadianTelemetrySignal>,
        freshnessMinutes: Long
    ) {
        private val freshnessMs = freshnessMinutes * MINUTE_MS
        private val cursors: Map<String, KeyCursor>

        init {
            val rowsByKey = telemetry.asSequence()
                .filter { it.timestamp >= 0L && it.key in WHITELISTED_TELEMETRY_KEYS }
                .groupBy(CircadianTelemetrySignal::key)
            cursors = WHITELISTED_TELEMETRY_KEYS.associateWith { key ->
                KeyCursor(rowsByKey[key].orEmpty().sortedBy(CircadianTelemetrySignal::timestamp))
            }
        }

        fun snapshotAt(timestamp: Long): Map<String, CircadianTelemetrySignal?> =
            cursors.mapValues { (_, cursor) -> cursor.latestAt(timestamp, freshnessMs) }

        private class KeyCursor(private val rows: List<CircadianTelemetrySignal>) {
            private var index = -1

            fun latestAt(timestamp: Long, freshnessMs: Long): CircadianTelemetrySignal? {
                while (index + 1 < rows.size && rows[index + 1].timestamp <= timestamp) index += 1
                val row = rows.getOrNull(index) ?: return null
                val age = timestamp - row.timestamp
                return row.takeIf { age in 0L..freshnessMs }
            }
        }
    }

    private class CarbWindowTracker(
        therapyEvents: List<TherapyEvent>,
        absorptionMinutes: Long
    ) {
        private val windowMs = absorptionMinutes * MINUTE_MS
        private val evidence = therapyEvents.asSequence()
            .mapNotNull(::toCarbEvidence)
            .sortedBy { it.timestamp }
            .toList()
        private val active = ArrayDeque<CarbEvidence>()
        private var cursor = 0

        fun snapshotAt(timestamp: Long): CarbWindow {
            while (cursor < evidence.size && evidence[cursor].timestamp <= timestamp) {
                active.addLast(evidence[cursor])
                cursor += 1
            }
            val earliest = timestamp - windowMs
            while (active.peekFirst()?.timestamp?.let { it < earliest } == true) active.removeFirst()
            return CarbWindow(
                timestamps = active.asSequence()
                    .filter { it.grams != null && it.grams > 0.0 }
                    .map { it.timestamp }
                    .toList(),
                malformed = active.any { it.malformed }
            )
        }

        private companion object {
            fun toCarbEvidence(event: TherapyEvent): CarbEvidence? {
                val payload = event.payload.entries.associate { normalize(it.key) to it.value }
                val carbKey = CARB_PAYLOAD_KEYS.firstOrNull(payload::containsKey)
                val carbType = normalize(event.type).let { it.contains("carb") || it.contains("meal") }
                if (!carbType && carbKey == null) return null
                val raw = carbKey?.let(payload::get)
                val grams = raw?.replace(',', '.')?.toDoubleOrNull()
                val malformed = grams == null || !grams.isFinite() || grams < 0.0 || event.ts < 0L
                return CarbEvidence(
                    timestamp = event.ts,
                    grams = grams?.takeIf { it.isFinite() && it >= 0.0 },
                    malformed = malformed
                )
            }

            fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

            val CARB_PAYLOAD_KEYS = listOf("grams", "carbs", "enteredcarbs", "mealcarbs")
        }
    }

    private data class CarbEvidence(
        val timestamp: Long,
        val grams: Double?,
        val malformed: Boolean
    )

    private data class CarbWindow(
        val timestamps: List<Long>,
        val malformed: Boolean
    )

    private fun GlucosePoint.isCanonicalInput(): Boolean =
        ts >= 0L && valueMmol.isFinite() && valueMmol > 0.0 && quality == DataQuality.OK

    private fun DayOfWeek.toCircadianDayType(): CircadianDayType = when (this) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> CircadianDayType.WEEKEND
        else -> CircadianDayType.WEEKDAY
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DELIVERY_RISE_POINT_COUNT = 4

        val WHITELISTED_TELEMETRY_KEYS = setOf(
            "cob_effective_grams",
            "cob_grams",
            "iob_units",
            "uam_runtime_control_flag",
            "uam_runtime_flag",
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low",
            "sensor_age_hours",
            "sensor_age_days",
            "target_low_risk_active",
            "target_low_risk_latched",
            "cage_days",
            "isf_factor_set_age_hours"
        )
    }
}

private fun MutableMap<String, Int>.increment(reason: String) {
    this[reason] = getOrDefault(reason, 0) + 1
}

private fun Map<String, CircadianTelemetrySignal?>.numeric(key: String): Double? {
    val signal = this[key] ?: return null
    val hasDouble = signal.valueDouble != null
    val hasText = signal.valueText != null
    val doubleValue = signal.valueDouble?.takeIf(Double::isFinite)
    val textValue = signal.valueText
        ?.trim()
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    if (hasDouble && doubleValue == null) return null
    if (hasText && textValue == null) return null
    if (doubleValue != null && textValue != null && abs(doubleValue - textValue) > VALUE_EPSILON) {
        return null
    }
    return doubleValue ?: textValue
}

private fun Map<String, CircadianTelemetrySignal?>.numericInRange(
    key: String,
    minimum: Double,
    maximum: Double
): Double? = numeric(key)?.takeIf { it in minimum..maximum }

private fun Map<String, CircadianTelemetrySignal?>.nonNegative(key: String): Double? =
    numeric(key)?.takeIf { it >= 0.0 }

private fun Map<String, CircadianTelemetrySignal?>.preferredNonNegative(
    primary: String,
    fallback: String,
    fallbackMultiplier: Double = 1.0
): Double? {
    if (this[primary] != null) return nonNegative(primary)
    return nonNegative(fallback)?.times(fallbackMultiplier)?.takeIf(Double::isFinite)
}

private fun Map<String, CircadianTelemetrySignal?>.flag(key: String): Boolean? {
    val signal = this[key] ?: return null
    val hasDouble = signal.valueDouble != null
    val hasText = signal.valueText != null
    val doubleFlag = signal.valueDouble?.toFlag()
    val textFlag = signal.valueText?.toFlag()
    if (hasDouble && doubleFlag == null) return null
    if (hasText && textFlag == null) return null
    if (doubleFlag != null && textFlag != null && doubleFlag != textFlag) return null
    return doubleFlag ?: textFlag
}

private fun Map<String, CircadianTelemetrySignal?>.unifiedFlag(
    first: String,
    second: String
): Boolean? {
    val presentKeys = listOf(first, second).filter { this[it] != null }
    if (presentKeys.isEmpty()) return null
    val values = presentKeys.map(::flag)
    if (values.any { it == true }) return true
    if (values.any { it == null }) return null
    return false
}

private fun Double.toFlag(): Boolean? =
    takeIf { isFinite() && it in 0.0..1.0 }?.let { it >= 0.5 }

private fun String.toFlag(): Boolean? = when (val normalized = trim().lowercase()) {
    "true", "active", "yes", "on" -> true
    "false", "inactive", "no", "off" -> false
    else -> normalized.replace(',', '.').toDoubleOrNull()?.toFlag()
}

private const val VALUE_EPSILON = 1e-9
