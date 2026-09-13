package io.aaps.copilot.domain.alerts

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.util.ordinaryExceptionOrNull

enum class AlertCauseCode {
    SENSOR_QUALITY,
    MEAL_UAM,
    INSULIN_ACTIVITY_LOW,
    DELIVERY_NONRESPONSE,
    CIRCADIAN_PATTERN,
    TARGET_RESPONSE,
    EVENT_CONTEXT,
    DATA_INCOMPLETE,
    UNKNOWN
}

enum class CauseConfidence { LOW, MEDIUM, HIGH }

data class AlertCauseAnalysis(
    val primary: AlertCauseCode,
    val factors: List<AlertCauseCode>,
    val confidence: CauseConfidence,
    val evidenceCodes: List<String>,
    val shortAdvice: String
)

enum class AlertCauseDirection { LOW, HIGH }

enum class AlertInsulinSource {
    AAPS_COMPONENTS,
    LEGACY_AAPS,
    EXTERNAL_ESTIMATE,
    LOCAL_ESTIMATE
}

enum class AlertSensitivitySource { AAPS, EVIDENCE_BLEND, COPILOT_NATIVE }

enum class AlertUamState { INACTIVE, SUSPECTED, ACTIVE, DECAYING, BLOCKED }

enum class AlertTargetState {
    OFF,
    WAITING_DATA,
    BLOCKED_SENSOR,
    BLOCKED_LOW_RISK,
    WAITING_WRITER,
    ACTIVE
}

enum class AlertContextEventType {
    ACTIVITY,
    STRESS,
    ILLNESS,
    SLEEP,
    HORMONAL,
    STEROID,
    ALCOHOL,
    INFUSION,
    SENSOR
}

data class AlertGlucoseEvidence(
    val currentMmol: Double?,
    val currentTimestamp: Long?,
    val forecast5Mmol: Double?,
    val forecast30Mmol: Double?,
    val forecast60Mmol: Double?,
    val lowerCi5Mmol: Double?,
    val lowerCi30Mmol: Double?,
    val lowerCi60Mmol: Double?,
    val trendDelta5Mmol: Double?,
    val dataFresh: Boolean
)

data class AlertSensorEvidence(
    val score: Double,
    val blocked: Boolean,
    val suspectFalseLow: Boolean
)

data class AlertInsulinEvidence(
    val cycleTimestamp: Long,
    val snapshotTimestamp: Long,
    val evidenceTimestamp: Long?,
    val source: AlertInsulinSource,
    val netIobUnits: Double,
    val effectivePositiveIobUnits: Double,
    val insulinActivity: Double?,
    val confidence: Double
)

data class AlertSensitivityEvidence(
    val cycleId: String,
    val settingsRevision: Long,
    val timestamp: Long,
    val isfMmolPerUnit: Double,
    val crGramPerUnit: Double,
    val isfSource: AlertSensitivitySource,
    val crSource: AlertSensitivitySource,
    val confidence: Double
)

data class AlertUamEvidence(
    val timestamp: Long,
    val state: AlertUamState,
    val active: Boolean,
    val controlActive: Boolean,
    val confidence: Double,
    val signedResidualMmol5: Double,
    val equivalentCarbsGrams: Double?,
    val sensitivityCycleId: String,
    val sensitivitySettingsRevision: Long
)

data class AlertTargetEvidence(
    val manualTargetMmol: Double,
    val autoDeltaMmol: Double,
    val effectiveTargetMmol: Double,
    val state: AlertTargetState,
    val reasonCodes: List<String>
)

data class AlertCircadianEvidence(
    val applied: Boolean,
    val delta30Mmol: Double?,
    val confidence: Double
)

data class AlertCauseInput(
    val nowTs: Long,
    val cycleTimestamp: Long,
    val direction: AlertCauseDirection,
    val glucose: AlertGlucoseEvidence,
    val sensor: AlertSensorEvidence,
    val insulin: AlertInsulinEvidence?,
    val sensitivity: AlertSensitivityEvidence?,
    val uam: AlertUamEvidence?,
    val deliveryTrust: DeliveryTrustState?,
    val target: AlertTargetEvidence?,
    val circadian: AlertCircadianEvidence?,
    val activeEventTypes: Set<AlertContextEventType>,
    val eventContextAvailable: Boolean
)

object AlertCauseAnalyzer {
    const val MAX_FACTORS = 5
    const val MAX_EVIDENCE_CODES = 12
    const val MAX_EVIDENCE_CODE_LENGTH = 40

    val ALLOWED_EVIDENCE_CODES: Set<String> = setOf(
        "CURRENT_EVIDENCE_MISSING",
        "CURRENT_EVIDENCE_STALE",
        "CYCLE_IDENTITY_MISMATCH",
        "EVENT_CONTEXT_UNAVAILABLE",
        "SENSOR_BLOCKED",
        "SENSOR_FALSE_LOW",
        "SENSOR_TRUST_POOR",
        "UAM_ACTIVE",
        "UAM_CONTROL_ACTIVE",
        "UAM_POSITIVE_RESIDUAL",
        "DELIVERY_SUSPECTED_NONRESPONSE",
        "EVENT_ACTIVITY",
        "EVENT_STRESS",
        "EVENT_ILLNESS",
        "EVENT_SLEEP",
        "EVENT_HORMONAL",
        "EVENT_STEROID",
        "EVENT_ALCOHOL",
        "EVENT_INFUSION",
        "EVENT_SENSOR",
        "INSULIN_ACTIVITY_LOW",
        "POSITIVE_IOB_LOW",
        "TARGET_AUTO_DELTA_UP",
        "TARGET_AUTO_DELTA_DOWN",
        "CIRCADIAN_DELTA_UP",
        "CIRCADIAN_DELTA_DOWN",
        "CAUSE_AMBIGUOUS"
    )

    fun analyze(input: AlertCauseInput): AlertCauseAnalysis {
        val incomplete = incompleteEvidence(input)
        if (incomplete.isNotEmpty()) {
            return analysis(
                primary = AlertCauseCode.DATA_INCOMPLETE,
                factors = listOf(AlertCauseCode.DATA_INCOMPLETE),
                confidence = CauseConfidence.LOW,
                evidence = incomplete
            )
        }

        val candidates = buildList {
            sensorCandidate(input)?.let(::add)
            uamCandidate(input)?.let(::add)
            deliveryCandidate(input)?.let(::add)
            insulinCandidate(input)?.let(::add)
            targetCandidate(input)?.let(::add)
            circadianCandidate(input)?.let(::add)
            eventCandidate(input)?.let(::add)
        }
        if (candidates.isEmpty()) {
            return analysis(
                primary = AlertCauseCode.UNKNOWN,
                factors = listOf(AlertCauseCode.UNKNOWN),
                confidence = CauseConfidence.LOW,
                evidence = listOf("CAUSE_AMBIGUOUS")
            )
        }

        val factors = candidates.map(Candidate::code).distinct().take(MAX_FACTORS)
        val evidence = candidates.asSequence()
            .flatMap { it.evidence.asSequence() }
            .filter { it in ALLOWED_EVIDENCE_CODES }
            .distinct()
            .take(MAX_EVIDENCE_CODES)
            .toList()
        return analysis(
            primary = candidates.first().code,
            factors = factors,
            confidence = candidates.first().confidence,
            evidence = evidence
        )
    }

    fun failureAnalysis(): AlertCauseAnalysis = analysis(
        primary = AlertCauseCode.DATA_INCOMPLETE,
        factors = listOf(AlertCauseCode.DATA_INCOMPLETE),
        confidence = CauseConfidence.LOW,
        evidence = listOf("CURRENT_EVIDENCE_MISSING")
    )

    fun fixedAdvice(code: AlertCauseCode): String = when (code) {
        AlertCauseCode.SENSOR_QUALITY ->
            "Sensor evidence may be unreliable; follow your usual safety checks."
        AlertCauseCode.MEAL_UAM ->
            "Meal-like glucose pressure may be contributing; follow your usual safety plan."
        AlertCauseCode.INSULIN_ACTIVITY_LOW ->
            "Low modeled insulin activity may be contributing; follow your usual safety plan."
        AlertCauseCode.DELIVERY_NONRESPONSE ->
            "Possible delivery nonresponse may be contributing; follow your usual safety plan."
        AlertCauseCode.CIRCADIAN_PATTERN ->
            "A same-time glucose pattern may be contributing; follow your usual safety plan."
        AlertCauseCode.TARGET_RESPONSE ->
            "The current target adjustment may be contributing; follow your usual safety plan."
        AlertCauseCode.EVENT_CONTEXT ->
            "An active context event may be contributing; follow your usual safety plan."
        AlertCauseCode.DATA_INCOMPLETE ->
            "Current-cycle evidence is incomplete; follow your usual safety checks."
        AlertCauseCode.UNKNOWN ->
            "No single local cause is clear; follow your usual safety plan."
    }

    private fun incompleteEvidence(input: AlertCauseInput): List<String> = buildList {
        val glucose = input.glucose
        if (
            input.nowTs <= 0L ||
            input.cycleTimestamp <= 0L ||
            glucose.currentTimestamp == null ||
            !finite(
                glucose.currentMmol,
                glucose.forecast5Mmol,
                glucose.forecast30Mmol,
                glucose.forecast60Mmol,
                glucose.lowerCi5Mmol,
                glucose.lowerCi30Mmol,
                glucose.lowerCi60Mmol,
                glucose.trendDelta5Mmol,
                input.sensor.score
            )
        ) {
            add("CURRENT_EVIDENCE_MISSING")
        }
        val currentTimestamp = glucose.currentTimestamp
        if (
            !glucose.dataFresh ||
            stale(input.nowTs, input.cycleTimestamp, MAX_CYCLE_AGE_MS) ||
            (currentTimestamp != null && stale(input.nowTs, currentTimestamp, MAX_CYCLE_AGE_MS))
        ) {
            add("CURRENT_EVIDENCE_STALE")
        }
        if (!sameCycleIdentity(input)) add("CYCLE_IDENTITY_MISMATCH")
        if (!input.eventContextAvailable) add("EVENT_CONTEXT_UNAVAILABLE")
    }.distinct().take(MAX_EVIDENCE_CODES)

    private fun sameCycleIdentity(input: AlertCauseInput): Boolean {
        val insulin = input.insulin ?: return false
        val sensitivity = input.sensitivity ?: return false
        val uam = input.uam ?: return false
        if (insulin.cycleTimestamp != input.cycleTimestamp) return false
        if (
            sensitivity.timestamp <= 0L ||
            sensitivity.timestamp > input.cycleTimestamp ||
            input.cycleTimestamp - sensitivity.timestamp > MAX_CYCLE_AGE_MS ||
            uam.timestamp <= 0L ||
            uam.timestamp > minOf(input.nowTs, input.cycleTimestamp) ||
            input.cycleTimestamp - uam.timestamp > MAX_CYCLE_AGE_MS ||
            stale(input.nowTs, uam.timestamp, MAX_CYCLE_AGE_MS)
        ) return false
        if (
            uam.sensitivityCycleId != sensitivity.cycleId ||
            uam.sensitivitySettingsRevision != sensitivity.settingsRevision
        ) return false
        if (!CODE_PATTERN.matches(sensitivity.cycleId) || sensitivity.settingsRevision < 0L) return false
        if (stale(input.nowTs, insulin.snapshotTimestamp, MAX_INSULIN_AGE_MS)) return false
        if (insulin.evidenceTimestamp?.let { stale(input.nowTs, it, MAX_INSULIN_AGE_MS) } == true) return false
        return finite(
            insulin.netIobUnits,
            insulin.effectivePositiveIobUnits,
            insulin.insulinActivity,
            insulin.confidence,
            sensitivity.isfMmolPerUnit,
            sensitivity.crGramPerUnit,
            sensitivity.confidence,
            uam.confidence,
            uam.signedResidualMmol5
        ) && (uam.equivalentCarbsGrams == null || uam.equivalentCarbsGrams.isFinite())
    }

    private fun sensorCandidate(input: AlertCauseInput): Candidate? {
        val poor = input.sensor.score < SENSOR_POOR_THRESHOLD
        if (!input.sensor.blocked && !input.sensor.suspectFalseLow && !poor) return null
        val evidence = buildList {
            if (input.sensor.blocked) add("SENSOR_BLOCKED")
            if (input.sensor.suspectFalseLow) add("SENSOR_FALSE_LOW")
            if (poor) add("SENSOR_TRUST_POOR")
        }
        return Candidate(
            code = AlertCauseCode.SENSOR_QUALITY,
            confidence = if (input.sensor.blocked || input.sensor.suspectFalseLow) {
                CauseConfidence.HIGH
            } else {
                CauseConfidence.MEDIUM
            },
            evidence = evidence
        )
    }

    private fun uamCandidate(input: AlertCauseInput): Candidate? {
        val uam = input.uam ?: return null
        if (
            input.direction != AlertCauseDirection.HIGH ||
            uam.state != AlertUamState.ACTIVE ||
            !uam.active ||
            uam.confidence < MIN_QUALIFIED_CONFIDENCE ||
            uam.signedResidualMmol5 <= MIN_POSITIVE_RESIDUAL_MMOL
        ) return null
        return Candidate(
            code = AlertCauseCode.MEAL_UAM,
            confidence = if (uam.controlActive && uam.confidence >= HIGH_CONFIDENCE) {
                CauseConfidence.HIGH
            } else {
                CauseConfidence.MEDIUM
            },
            evidence = buildList {
                add("UAM_ACTIVE")
                if (uam.controlActive) add("UAM_CONTROL_ACTIVE")
                add("UAM_POSITIVE_RESIDUAL")
            }
        )
    }

    private fun deliveryCandidate(input: AlertCauseInput): Candidate? =
        if (
            input.direction == AlertCauseDirection.HIGH &&
            input.deliveryTrust == DeliveryTrustState.SUSPECTED_NONRESPONSE
        ) {
            Candidate(
                AlertCauseCode.DELIVERY_NONRESPONSE,
                CauseConfidence.MEDIUM,
                listOf("DELIVERY_SUSPECTED_NONRESPONSE")
            )
        } else {
            null
        }

    private fun eventCandidate(input: AlertCauseInput): Candidate? {
        if (!eventDirectionCorroborated(input)) return null
        val compatibleTypes = when (input.direction) {
            AlertCauseDirection.LOW -> LOW_EVENT_TYPES
            AlertCauseDirection.HIGH -> HIGH_EVENT_TYPES
        }
        val evidence = AlertContextEventType.entries
            .filter { it in compatibleTypes && it in input.activeEventTypes }
            .map { "EVENT_${it.name}" }
        if (evidence.isEmpty()) return null
        return Candidate(AlertCauseCode.EVENT_CONTEXT, CauseConfidence.MEDIUM, evidence)
    }

    private fun eventDirectionCorroborated(input: AlertCauseInput): Boolean {
        val glucose = input.glucose
        val current = glucose.currentMmol?.takeIf(Double::isFinite) ?: return false
        val trend = glucose.trendDelta5Mmol?.takeIf(Double::isFinite)
        val forecast30Delta = glucose.forecast30Mmol?.takeIf(Double::isFinite)?.minus(current)
        val forecast60Delta = glucose.forecast60Mmol?.takeIf(Double::isFinite)?.minus(current)
        return when (input.direction) {
            AlertCauseDirection.LOW ->
                trend?.let { it <= -MIN_EVENT_TREND_MMOL } == true ||
                    forecast30Delta?.let { it <= -MIN_EVENT_FORECAST_DELTA_MMOL } == true ||
                    forecast60Delta?.let { it <= -MIN_EVENT_FORECAST_DELTA_MMOL } == true
            AlertCauseDirection.HIGH ->
                trend?.let { it >= MIN_EVENT_TREND_MMOL } == true ||
                    forecast30Delta?.let { it >= MIN_EVENT_FORECAST_DELTA_MMOL } == true ||
                    forecast60Delta?.let { it >= MIN_EVENT_FORECAST_DELTA_MMOL } == true
        }
    }

    private fun insulinCandidate(input: AlertCauseInput): Candidate? {
        val insulin = input.insulin ?: return null
        if (
            input.direction != AlertCauseDirection.HIGH ||
            insulin.confidence < MIN_QUALIFIED_CONFIDENCE ||
            (insulin.insulinActivity ?: Double.POSITIVE_INFINITY) > LOW_INSULIN_ACTIVITY ||
            insulin.effectivePositiveIobUnits > LOW_POSITIVE_IOB
        ) return null
        return Candidate(
            AlertCauseCode.INSULIN_ACTIVITY_LOW,
            CauseConfidence.MEDIUM,
            listOf("INSULIN_ACTIVITY_LOW", "POSITIVE_IOB_LOW")
        )
    }

    private fun targetCandidate(input: AlertCauseInput): Candidate? {
        val target = input.target ?: return null
        if (
            target.state != AlertTargetState.ACTIVE ||
            !finite(target.manualTargetMmol, target.autoDeltaMmol, target.effectiveTargetMmol)
        ) return null
        val code = when {
            input.direction == AlertCauseDirection.HIGH && target.autoDeltaMmol >= MIN_DIRECTIONAL_DELTA ->
                "TARGET_AUTO_DELTA_UP"
            input.direction == AlertCauseDirection.LOW && target.autoDeltaMmol <= -MIN_DIRECTIONAL_DELTA ->
                "TARGET_AUTO_DELTA_DOWN"
            else -> return null
        }
        return Candidate(AlertCauseCode.TARGET_RESPONSE, CauseConfidence.MEDIUM, listOf(code))
    }

    private fun circadianCandidate(input: AlertCauseInput): Candidate? {
        val circadian = input.circadian ?: return null
        val delta = circadian.delta30Mmol?.takeIf(Double::isFinite) ?: return null
        if (!circadian.applied || circadian.confidence < MIN_QUALIFIED_CONFIDENCE) return null
        val code = when {
            input.direction == AlertCauseDirection.HIGH && delta >= MIN_DIRECTIONAL_DELTA ->
                "CIRCADIAN_DELTA_UP"
            input.direction == AlertCauseDirection.LOW && delta <= -MIN_DIRECTIONAL_DELTA ->
                "CIRCADIAN_DELTA_DOWN"
            else -> return null
        }
        return Candidate(AlertCauseCode.CIRCADIAN_PATTERN, CauseConfidence.MEDIUM, listOf(code))
    }

    private fun analysis(
        primary: AlertCauseCode,
        factors: List<AlertCauseCode>,
        confidence: CauseConfidence,
        evidence: List<String>
    ) = AlertCauseAnalysis(
        primary = primary,
        factors = factors.distinct().take(MAX_FACTORS),
        confidence = confidence,
        evidenceCodes = evidence.asSequence()
            .filter { it in ALLOWED_EVIDENCE_CODES && it.length <= MAX_EVIDENCE_CODE_LENGTH }
            .distinct()
            .take(MAX_EVIDENCE_CODES)
            .toList(),
        shortAdvice = fixedAdvice(primary)
    )

    private fun finite(vararg values: Double?): Boolean = values.all { it != null && it.isFinite() }

    private fun stale(nowTs: Long, timestamp: Long, maxAgeMs: Long): Boolean =
        timestamp <= 0L || timestamp > nowTs || nowTs - timestamp > maxAgeMs

    private data class Candidate(
        val code: AlertCauseCode,
        val confidence: CauseConfidence,
        val evidence: List<String>
    )

    private const val MAX_CYCLE_AGE_MS = 15L * 60_000L
    private const val MAX_INSULIN_AGE_MS = 10L * 60_000L
    private const val SENSOR_POOR_THRESHOLD = 0.55
    private const val MIN_QUALIFIED_CONFIDENCE = 0.55
    private const val HIGH_CONFIDENCE = 0.80
    private const val MIN_POSITIVE_RESIDUAL_MMOL = 0.10
    private const val LOW_INSULIN_ACTIVITY = 0.01
    private const val LOW_POSITIVE_IOB = 0.25
    private const val MIN_DIRECTIONAL_DELTA = 0.05
    private const val MIN_EVENT_TREND_MMOL = 0.10
    private const val MIN_EVENT_FORECAST_DELTA_MMOL = 0.30
    private val CODE_PATTERN = Regex("[A-Za-z0-9._:-]{1,80}")
    private val LOW_EVENT_TYPES = setOf(
        AlertContextEventType.ACTIVITY,
        AlertContextEventType.ALCOHOL
    )
    private val HIGH_EVENT_TYPES = setOf(
        AlertContextEventType.STRESS,
        AlertContextEventType.ILLNESS,
        AlertContextEventType.SLEEP,
        AlertContextEventType.HORMONAL,
        AlertContextEventType.STEROID,
        AlertContextEventType.INFUSION
    )
}

class AlertCauseSnapshot internal constructor(val canonicalJson: String)

object AlertCauseSnapshotCodec {
    const val MAX_JSON_CHARS = 4_096
    private const val MAX_REASON_CODES = 6

    // Fixed outputs emitted by EffectiveBaseTargetResolver/CircadianTargetEvaluator and its cohort builder.
    internal val ALLOWED_TARGET_REASON_CODES = setOf(
        "auto_disabled",
        "invalid_hard_bounds",
        "duplicate_requested_adjustment",
        "duplicate_all_adjustment",
        "adjustment_missing",
        "day_type_fallback_all",
        "adjustment_run_id_invalid",
        "adjustment_revision_mismatch",
        "adjustment_delta_invalid",
        "adjustment_stale",
        "adjustment_state_off",
        "adjustment_state_waiting_data",
        "adjustment_state_blocked_sensor",
        "adjustment_state_blocked_low_risk",
        "adjustment_state_waiting_writer",
        "sensor_not_trusted",
        "sensor_age_unknown",
        "sensor_age_old",
        "low_risk_latched",
        "iob_missing",
        "forecast_5m_missing",
        "forecast_5m_low",
        "forecast_30m_missing",
        "forecast_30m_low",
        "iob_not_below_2_5",
        "effective_cob_missing",
        "effective_cob_above_5",
        "uam_active",
        "target_manager_not_active",
        "auto_adjustment_applied",
        "adjustment_source_failed",
        "fewer_than_seven_valid_days",
        "three_hour_median_smoothed",
        "protective_reset_to_zero",
        "fallback_to_all",
        "protected_low_below_4_0",
        "post_hypo_or_low_risk_latch",
        "malformed_or_future_evidence",
        "insufficient_hourly_coverage",
        "quality_gate_failed",
        "current_sensor_not_trusted",
        "full_weight_evidence_missing",
        "delivery_suspected_nonresponse",
        "previous_adjustment_invalid",
        "deep_dwell_failed",
        "deep_coverage_failed",
        "deep_direction_failed",
        "deep_distribution_failed",
        "deep_low_below_4_4",
        "deep_context_confounded",
        "deep_delivery_not_normal",
        "deep_prior_step_comparison_failed",
        "deep_prior_step_evidence_missing",
        "deep_low_exposure_worse",
        "deep_variability_worse",
        "deep_lower_tail_replay_error_worse",
        "deep_step_passed",
        "malformed_glucose",
        "duplicate_or_unsorted_glucose",
        "sensor_signal_missing_or_stale",
        "sensor_untrusted",
        "sensor_blocked",
        "sensor_suspect_false_low",
        "sensor_age_down_weighted",
        "cob_unknown",
        "cob_active",
        "uam_unknown",
        "carbs_unknown",
        "acute_carbs",
        "post_hypo_unknown",
        "post_hypo",
        "low_risk_latch_unknown"
    )

    fun encode(input: AlertCauseInput, analysis: AlertCauseAnalysis): AlertCauseSnapshot {
        val root = JsonObject().apply {
            addProperty("version", 1)
            addProperty("nowTs", input.nowTs)
            addProperty("cycleTimestamp", input.cycleTimestamp)
            addProperty("direction", input.direction.name)
            addProperty("cause", analysis.primary.name)
            addProperty("confidence", analysis.confidence.name)
            add("factors", enumArray(analysis.factors.map(AlertCauseCode::name)))
            add("evidence", enumArray(analysis.evidenceCodes))
            addProperty(
                "identityStatus",
                if ("CYCLE_IDENTITY_MISMATCH" in analysis.evidenceCodes) "MISMATCH" else "MATCHED"
            )
            add("glucose", glucoseObject(input.glucose))
            add("sensor", sensorObject(input.sensor))
            input.insulin?.let { add("insulin", insulinObject(it)) }
            input.sensitivity?.let { add("sensitivity", sensitivityObject(it)) }
            input.uam?.let { add("uam", uamObject(it)) }
            input.deliveryTrust?.let { addProperty("deliveryTrust", it.name) }
            input.target?.let { add("target", targetObject(it)) }
            input.circadian?.let { add("circadian", circadianObject(it)) }
            add(
                "events",
                enumArray(AlertContextEventType.entries.filter(input.activeEventTypes::contains).map { it.name })
            )
            addProperty("eventContextAvailable", input.eventContextAvailable)
        }
        return sanitize(AlertCauseSnapshot(root.toString())) ?: failureSnapshot()
    }

    fun failureSnapshot(): AlertCauseSnapshot = AlertCauseSnapshot(
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("cause", AlertCauseCode.DATA_INCOMPLETE.name)
            addProperty("confidence", CauseConfidence.LOW.name)
            add("factors", enumArray(listOf(AlertCauseCode.DATA_INCOMPLETE.name)))
            add("evidence", enumArray(listOf("CURRENT_EVIDENCE_MISSING")))
            addProperty("identityStatus", "UNAVAILABLE")
        }.toString()
    )

    fun repairSnapshot(cause: AlertCauseCode): AlertCauseSnapshot = AlertCauseSnapshot(
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("cause", cause.name)
            addProperty("confidence", CauseConfidence.LOW.name)
            add("factors", enumArray(listOf(cause.name)))
            add("evidence", enumArray(emptyList()))
            addProperty("identityStatus", "LEGACY_REPAIRED")
        }.toString()
    )

    fun sanitize(snapshot: AlertCauseSnapshot): AlertCauseSnapshot? = sanitize(snapshot) { json ->
        JsonParser.parseString(json).asJsonObject
    }

    internal fun sanitize(
        snapshot: AlertCauseSnapshot,
        parseObject: (String) -> JsonObject
    ): AlertCauseSnapshot? {
        if (snapshot.canonicalJson.length > MAX_JSON_CHARS) return null
        val source = ordinaryExceptionOrNull {
            parseObject(snapshot.canonicalJson)
        } ?: return null
        if (source.longValue("version") != 1L) return null
        val cause = source.enumValue("cause", AlertCauseCode.entries.map { it.name }.toSet())
            ?: return null
        val confidence = source.enumValue("confidence", CauseConfidence.entries.map { it.name }.toSet())
            ?: return null
        val factors = source.enumValues(
            "factors",
            AlertCauseCode.entries.map { it.name }.toSet(),
            AlertCauseAnalyzer.MAX_FACTORS
        ) ?: return null
        if (cause !in factors) return null
        val evidence = source.enumValues(
            "evidence",
            AlertCauseAnalyzer.ALLOWED_EVIDENCE_CODES,
            AlertCauseAnalyzer.MAX_EVIDENCE_CODES
        ) ?: return null
        val identityStatus = source.enumValue("identityStatus", IDENTITY_STATUSES) ?: return null

        val root = JsonObject().apply {
            addProperty("version", 1)
            source.longValue("nowTs")?.takeIf { it >= 0L }?.let { addProperty("nowTs", it) }
            source.longValue("cycleTimestamp")?.takeIf { it >= 0L }?.let {
                addProperty("cycleTimestamp", it)
            }
            source.enumValue("direction", AlertCauseDirection.entries.map { it.name }.toSet())?.let {
                addProperty("direction", it)
            }
            addProperty("cause", cause)
            addProperty("confidence", confidence)
            add("factors", enumArray(factors))
            add("evidence", enumArray(evidence))
            addProperty("identityStatus", identityStatus)
            source.objectValue("glucose")?.let { add("glucose", sanitizeGlucose(it)) }
            source.objectValue("sensor")?.let { add("sensor", sanitizeSensor(it)) }
            source.objectValue("insulin")?.let { add("insulin", sanitizeInsulin(it)) }
            source.objectValue("sensitivity")?.let { add("sensitivity", sanitizeSensitivity(it)) }
            source.objectValue("uam")?.let { add("uam", sanitizeUam(it)) }
            source.enumValue("deliveryTrust", DeliveryTrustState.entries.map { it.name }.toSet())?.let {
                addProperty("deliveryTrust", it)
            }
            source.objectValue("target")?.let { add("target", sanitizeTarget(it)) }
            source.objectValue("circadian")?.let { add("circadian", sanitizeCircadian(it)) }
            source.enumValues(
                "events",
                AlertContextEventType.entries.map { it.name }.toSet(),
                AlertContextEventType.entries.size
            )?.let { add("events", enumArray(it)) }
            source.booleanValue("eventContextAvailable")?.let {
                addProperty("eventContextAvailable", it)
            }
        }
        val canonical = root.toString()
        return canonical.takeIf { it.length <= MAX_JSON_CHARS }?.let(::AlertCauseSnapshot)
    }

    private fun glucoseObject(value: AlertGlucoseEvidence) = JsonObject().apply {
        addFinite("currentMmol", value.currentMmol)
        value.currentTimestamp?.takeIf { it >= 0L }?.let { addProperty("currentTimestamp", it) }
        addFinite("forecast5Mmol", value.forecast5Mmol)
        addFinite("forecast30Mmol", value.forecast30Mmol)
        addFinite("forecast60Mmol", value.forecast60Mmol)
        addFinite("lowerCi5Mmol", value.lowerCi5Mmol)
        addFinite("lowerCi30Mmol", value.lowerCi30Mmol)
        addFinite("lowerCi60Mmol", value.lowerCi60Mmol)
        addFinite("trendDelta5Mmol", value.trendDelta5Mmol)
        addProperty("dataFresh", value.dataFresh)
    }

    private fun sensorObject(value: AlertSensorEvidence) = JsonObject().apply {
        addFinite("score", value.score)
        addProperty("blocked", value.blocked)
        addProperty("suspectFalseLow", value.suspectFalseLow)
    }

    private fun insulinObject(value: AlertInsulinEvidence) = JsonObject().apply {
        addProperty("cycleTimestamp", value.cycleTimestamp)
        addProperty("snapshotTimestamp", value.snapshotTimestamp)
        value.evidenceTimestamp?.let { addProperty("evidenceTimestamp", it) }
        addProperty("source", value.source.name)
        addFinite("netIobUnits", value.netIobUnits)
        addFinite("effectivePositiveIobUnits", value.effectivePositiveIobUnits)
        addFinite("insulinActivity", value.insulinActivity)
        addFinite("confidence", value.confidence)
    }

    private fun sensitivityObject(value: AlertSensitivityEvidence) = JsonObject().apply {
        addProperty("settingsRevision", value.settingsRevision)
        addProperty("timestamp", value.timestamp)
        addFinite("isfMmolPerUnit", value.isfMmolPerUnit)
        addFinite("crGramPerUnit", value.crGramPerUnit)
        addProperty("isfSource", value.isfSource.name)
        addProperty("crSource", value.crSource.name)
        addFinite("confidence", value.confidence)
    }

    private fun uamObject(value: AlertUamEvidence) = JsonObject().apply {
        addProperty("timestamp", value.timestamp)
        addProperty("state", value.state.name)
        addProperty("active", value.active)
        addProperty("controlActive", value.controlActive)
        addFinite("confidence", value.confidence)
        addFinite("signedResidualMmol5", value.signedResidualMmol5)
        addFinite("equivalentCarbsGrams", value.equivalentCarbsGrams)
        addProperty("sensitivitySettingsRevision", value.sensitivitySettingsRevision)
    }

    private fun targetObject(value: AlertTargetEvidence) = JsonObject().apply {
        addFinite("manualTargetMmol", value.manualTargetMmol)
        addFinite("autoDeltaMmol", value.autoDeltaMmol)
        addFinite("effectiveTargetMmol", value.effectiveTargetMmol)
        addProperty("state", value.state.name)
        add(
            "reasonCodes",
            enumArray(
                value.reasonCodes.asSequence()
                    .filter(ALLOWED_TARGET_REASON_CODES::contains)
                    .distinct()
                    .sorted()
                    .take(MAX_REASON_CODES)
                    .toList()
            )
        )
    }

    private fun circadianObject(value: AlertCircadianEvidence) = JsonObject().apply {
        addProperty("applied", value.applied)
        addFinite("delta30Mmol", value.delta30Mmol)
        addFinite("confidence", value.confidence)
    }

    private fun sanitizeGlucose(source: JsonObject) = JsonObject().apply {
        copyFinite(source, "currentMmol")
        source.longValue("currentTimestamp")?.takeIf { it >= 0L }?.let {
            addProperty("currentTimestamp", it)
        }
        copyFinite(source, "forecast5Mmol")
        copyFinite(source, "forecast30Mmol")
        copyFinite(source, "forecast60Mmol")
        copyFinite(source, "lowerCi5Mmol")
        copyFinite(source, "lowerCi30Mmol")
        copyFinite(source, "lowerCi60Mmol")
        copyFinite(source, "trendDelta5Mmol")
        source.booleanValue("dataFresh")?.let { addProperty("dataFresh", it) }
    }

    private fun sanitizeSensor(source: JsonObject) = JsonObject().apply {
        copyFinite(source, "score")
        source.booleanValue("blocked")?.let { addProperty("blocked", it) }
        source.booleanValue("suspectFalseLow")?.let { addProperty("suspectFalseLow", it) }
    }

    private fun sanitizeInsulin(source: JsonObject) = JsonObject().apply {
        copyNonNegativeLong(source, "cycleTimestamp")
        copyNonNegativeLong(source, "snapshotTimestamp")
        copyNonNegativeLong(source, "evidenceTimestamp")
        source.enumValue("source", AlertInsulinSource.entries.map { it.name }.toSet())?.let {
            addProperty("source", it)
        }
        copyFinite(source, "netIobUnits")
        copyFinite(source, "effectivePositiveIobUnits")
        copyFinite(source, "insulinActivity")
        copyFinite(source, "confidence")
    }

    private fun sanitizeSensitivity(source: JsonObject) = JsonObject().apply {
        copyNonNegativeLong(source, "settingsRevision")
        copyNonNegativeLong(source, "timestamp")
        copyFinite(source, "isfMmolPerUnit")
        copyFinite(source, "crGramPerUnit")
        val sources = AlertSensitivitySource.entries.map { it.name }.toSet()
        source.enumValue("isfSource", sources)?.let { addProperty("isfSource", it) }
        source.enumValue("crSource", sources)?.let { addProperty("crSource", it) }
        copyFinite(source, "confidence")
    }

    private fun sanitizeUam(source: JsonObject) = JsonObject().apply {
        copyNonNegativeLong(source, "timestamp")
        source.enumValue("state", AlertUamState.entries.map { it.name }.toSet())?.let {
            addProperty("state", it)
        }
        source.booleanValue("active")?.let { addProperty("active", it) }
        source.booleanValue("controlActive")?.let { addProperty("controlActive", it) }
        copyFinite(source, "confidence")
        copyFinite(source, "signedResidualMmol5")
        copyFinite(source, "equivalentCarbsGrams")
        copyNonNegativeLong(source, "sensitivitySettingsRevision")
    }

    private fun sanitizeTarget(source: JsonObject) = JsonObject().apply {
        copyFinite(source, "manualTargetMmol")
        copyFinite(source, "autoDeltaMmol")
        copyFinite(source, "effectiveTargetMmol")
        source.enumValue("state", AlertTargetState.entries.map { it.name }.toSet())?.let {
            addProperty("state", it)
        }
        source.enumValues("reasonCodes", ALLOWED_TARGET_REASON_CODES, MAX_REASON_CODES)?.let {
            add("reasonCodes", enumArray(it.sorted()))
        }
    }

    private fun sanitizeCircadian(source: JsonObject) = JsonObject().apply {
        source.booleanValue("applied")?.let { addProperty("applied", it) }
        copyFinite(source, "delta30Mmol")
        copyFinite(source, "confidence")
    }

    private fun JsonObject.copyFinite(source: JsonObject, name: String) {
        source.finiteDouble(name)?.let { addProperty(name, it) }
    }

    private fun JsonObject.copyNonNegativeLong(source: JsonObject, name: String) {
        source.longValue(name)?.takeIf { it >= 0L }?.let { addProperty(name, it) }
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.booleanValue(name: String): Boolean? {
        val primitive = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive ?: return null
        return primitive.takeIf { it.isBoolean }?.asBoolean
    }

    private fun JsonObject.longValue(name: String): Long? {
        val primitive = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive ?: return null
        if (!primitive.isNumber) return null
        return primitive.asString.toLongOrNull()
    }

    private fun JsonObject.finiteDouble(name: String): Double? {
        val primitive = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive ?: return null
        if (!primitive.isNumber) return null
        return ordinaryExceptionOrNull { primitive.asDouble }?.takeIf(Double::isFinite)
    }

    private fun JsonObject.enumValue(name: String, allowed: Set<String>): String? {
        val primitive = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.asString.takeIf(allowed::contains)
    }

    private fun JsonObject.enumValues(
        name: String,
        allowed: Set<String>,
        maxCount: Int
    ): List<String>? {
        val array = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null
        return array.asSequence()
            .mapNotNull { element ->
                element.takeIf(JsonElement::isJsonPrimitive)
                    ?.asJsonPrimitive
                    ?.takeIf { it.isString }
                    ?.asString
                    ?.takeIf(allowed::contains)
            }
            .distinct()
            .take(maxCount)
            .toList()
    }

    private fun JsonObject.addFinite(name: String, value: Double?) {
        value?.takeIf(Double::isFinite)?.let { addProperty(name, it) }
    }

    private fun enumArray(values: Iterable<String>) = JsonArray().apply {
        values.forEach(::add)
    }

    private val IDENTITY_STATUSES = setOf("MATCHED", "MISMATCH", "UNAVAILABLE", "LEGACY_REPAIRED")
}
