package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.domain.predict.InsulinComponentTelemetry
import io.aaps.copilot.domain.predict.InsulinRuntimeResolution
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.TimedInsulinValue
import io.aaps.copilot.util.UnitConverter
import java.util.Locale

data class PhysicalActivityTelemetryPayload(
    val persistedMetrics: List<TelemetrySampleEntity>,
    val labels: List<TelemetrySampleEntity>
)

object TelemetryMetricMapper {
    private val CAMEL_BOUNDARY_REGEX = Regex("([a-z0-9])([A-Z])")
    private val SEPARATOR_RUNS_REGEX = Regex("[^a-z0-9]+")
    private val COMPACT_NON_ALNUM_REGEX = Regex("[^a-z0-9]")
    private val PROFILE_PERCENT_REGEX = Regex("""\((\d{2,3}(?:[.,]\d+)?)%\)""")
    private val ISF_REASON_REGEX = Regex("""\bISF:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val CR_REASON_REGEX = Regex("""\bCR:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val SENSITIVE_KEY_PARTS = listOf(
        "secret",
        "token",
        "password",
        "apikey",
        "api_key",
        "authorization",
        "bearer",
        "jwt"
    )
    private val META_ONLY_KEYS = setOf(
        "timestamp",
        "time",
        "date",
        "created_at",
        "mills",
        "action",
        "eventtype",
        "event_type",
        "type",
        "units",
        "unit",
        "source",
        "id",
        "_id"
    )
    private const val MAX_RAW_SAMPLES_PER_PAYLOAD = 160
    private const val MAX_RAW_KEY_LENGTH = 84
    private const val MAX_TEXT_VALUE_LENGTH = 96
    private const val TEMP_TARGET_MGDL_THRESHOLD = 30.0
    private val TRUSTED_AAPS_IOB_SOURCES = setOf("aaps_broadcast")
    private val LEGACY_IOB_EXACT_ALIASES = listOf(
        "iob",
        "iobtotal",
        "insulinonboard",
        "openaps.iob.iob",
        "iob.iob"
    )
    private val FLATTENED_NIGHTSCOUT_IOB_EXACT_ALIASES = LEGACY_IOB_EXACT_ALIASES + listOf(
        "openaps.iob",
        "loop.iob",
        "loop.iob.iob"
    )

    fun fromKeyValueMap(
        timestamp: Long,
        source: String,
        values: Map<String, String>,
        observedAtTimestamp: Long = timestamp
    ): List<TelemetrySampleEntity> {
        if (values.isEmpty()) return emptyList()
        val output = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(canonicalKey: String, unit: String?, aliases: List<String>) {
            val raw = findValue(values, aliases) ?: return
            val parsed = raw.toDoubleOrNullLocale() ?: return
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = parsed,
                valueText = null,
                unit = unit
            )
        }

        fun addNumericExact(canonicalKey: String, unit: String?, aliases: List<String>) {
            val raw = findValueExact(values, aliases) ?: return
            val parsed = raw.toDoubleOrNullLocale() ?: return
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = parsed,
                valueText = null,
                unit = unit
            )
        }

        fun addTempTargetNumeric(canonicalKey: String, aliases: List<String>) {
            val raw = findValue(values, aliases) ?: return
            val parsed = raw.toDoubleOrNullLocale() ?: return
            val mmol = normalizeTempTargetMmol(parsed)
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = mmol,
                valueText = null,
                unit = "mmol/L"
            )
        }

        fun addTempTargetNumericExact(canonicalKey: String, aliases: List<String>) {
            val raw = findValueExact(values, aliases) ?: return
            val parsed = raw.toDoubleOrNullLocale() ?: return
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = normalizeTempTargetMmol(parsed),
                valueText = null,
                unit = "mmol/L"
            )
        }

        fun addBooleanFlagExact(canonicalKey: String, aliases: List<String>) {
            val raw = findValueExact(values, aliases)?.trim()?.lowercase(Locale.US) ?: return
            val value = when (raw) {
                "true", "1", "yes", "on" -> 1.0
                "false", "0", "no", "off" -> 0.0
                else -> return
            }
            output += sample(timestamp, source, canonicalKey, value, null, null)
        }

        fun addText(canonicalKey: String, aliases: List<String>) {
            val raw = findValue(values, aliases)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = null,
                valueText = raw.take(64),
                unit = null
            )
        }

        fun addUam(canonicalKey: String, aliases: List<String>) {
            val raw = findUamValue(values, aliases) ?: return
            val parsed = parseUamFlag(raw) ?: return
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = parsed,
                valueText = null,
                unit = null
            )
        }

        fun addUamFromPredictions() {
            if (output.any { it.key == "uam_value" }) return
            val uamPred = findPredictionValue(values, listOf("predBGs.UAM[0]", "predBGs.UAM.0", "predbg_uam_0", "uamPred0"))
                ?: return
            val currentMgdl = findCurrentGlucoseMgdl(values)
                ?: return
            val iobPred = findPredictionValue(values, listOf("predBGs.IOB[0]", "predBGs.IOB.0", "predbg_iob_0", "iobPred0"))
            val cobPred = findPredictionValue(values, listOf("predBGs.COB[0]", "predBGs.COB.0", "predbg_cob_0", "cobPred0"))
            val baseline = listOfNotNull(currentMgdl, iobPred, cobPred).maxOrNull() ?: currentMgdl
            val uplift = uamPred - baseline
            val threshold = if (iobPred != null || cobPred != null) 12.0 else 18.0
            val uam = if (uplift >= threshold) 1.0 else 0.0
            output += sample(
                timestamp = timestamp,
                source = source,
                key = "uam_value",
                valueDouble = uam,
                valueText = null,
                unit = null
            )
        }

        fun addDiaHours(canonicalKey: String, aliases: List<String>) {
            val raw = findValue(values, aliases) ?: return
            val parsed = raw.toDoubleOrNullLocale() ?: return
            val hours = when {
                parsed in 0.0..24.0 -> parsed
                parsed in 25.0..1440.0 -> parsed / 60.0
                parsed in 1441.0..100_000_000.0 -> parsed / 3_600_000.0
                else -> return
            }
            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = hours,
                valueText = null,
                unit = "h"
            )
        }

        fun addProfilePercent(canonicalKey: String, aliases: List<String>) {
            val exactNumeric = findValueExact(values, aliases)?.toDoubleOrNullLocale()
            if (exactNumeric != null && exactNumeric in 10.0..300.0) {
                output += sample(
                    timestamp = timestamp,
                    source = source,
                    key = canonicalKey,
                    valueDouble = exactNumeric,
                    valueText = null,
                    unit = "%"
                )
                return
            }

            val profileText = findValue(values, listOf("profile")) ?: return
            val extracted = PROFILE_PERCENT_REGEX
                .find(profileText)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?.takeIf { it in 10.0..300.0 }
                ?: return

            output += sample(
                timestamp = timestamp,
                source = source,
                key = canonicalKey,
                valueDouble = extracted,
                valueText = null,
                unit = "%"
            )
        }

        fun addIsfCrFromReason(reasonAliases: List<String>) {
            val reason = findValue(values, reasonAliases) ?: return
            val isfRaw = ISF_REASON_REGEX
                .find(reason)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
            val crRaw = CR_REASON_REGEX
                .find(reason)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()

            if (isfRaw != null) {
                val isfMmol = if (isfRaw > 12.0) UnitConverter.mgdlToMmol(isfRaw) else isfRaw
                if (isfMmol in 0.2..18.0) {
                    output += sample(
                        timestamp = timestamp,
                        source = source,
                        key = "isf_value",
                        valueDouble = isfMmol,
                        valueText = null,
                        unit = "mmol/L/U"
                    )
                }
            }
            if (crRaw != null && crRaw in 2.0..60.0) {
                output += sample(
                    timestamp = timestamp,
                    source = source,
                    key = "cr_value",
                    valueDouble = crRaw,
                    valueText = null,
                    unit = "g/U"
                )
            }
        }

        appendInsulinRuntimeSamples(
            output = output,
            timestamp = timestamp,
            source = source,
            mapping = resolveInsulinRuntime(
                source = source,
                values = values,
                sampleTimestamp = timestamp,
                observedAtTimestamp = observedAtTimestamp,
                allowTrustedAaps = true
            )
        )
        addNumeric("cob_grams", "g", listOf("cob", "carbsonboard", "openaps.suggested.cob"))
        addNumericExact("carbs_grams", "g", listOf("carbs", "grams", "enteredCarbs", "mealCarbs"))
        addNumericExact("insulin_units", "U", listOf("insulin", "insulinUnits", "bolus", "enteredInsulin"))
        addNumeric("future_carbs_grams", "g", listOf("futureCarbs", "future_carbs", "openaps.suggested.futureCarbs"))
        addDiaHours(
            "dia_hours",
            listOf("dia", "insulinActionTime", "insulinactiontime", "insulinEndTime", "insulinendtime", "iat")
        )
        addNumeric("steps_count", "steps", listOf("steps", "stepCount", "step_count", "stepcounter", "totalSteps", "dailySteps"))
        addNumeric("activity_ratio", null, listOf("activity", "activityRatio", "sensitivityRatio", "autosensRatio", "sensitivity_ratio"))
        addNumeric(
            "distance_km",
            "km",
            listOf("distanceKm", "distance_km", "walkingDistanceKm", "totalDistanceKm", "distance")
        )
        addNumeric(
            "active_minutes",
            "min",
            listOf("activeMinutes", "active_minutes", "exerciseMinutes", "workoutMinutes", "activityMinutes")
        )
        addNumeric(
            "calories_active_kcal",
            "kcal",
            listOf("activeCalories", "activeCaloriesKcal", "caloriesActive", "calories_active", "kcalActive")
        )
        addNumeric("heart_rate_bpm", "bpm", listOf("heartRate", "heart_rate", "bpm"))
        addTempTargetNumeric("temp_target_low_mmol", listOf("targetBottom", "target_bottom", "targetLow"))
        addTempTargetNumeric("temp_target_high_mmol", listOf("targetTop", "target_top", "targetHigh"))
        addNumeric("temp_target_duration_min", "min", listOf("duration", "durationInMinutes"))
        addTempTargetNumericExact("profile_target_low_mmol", listOf("profileTargetBottom"))
        addTempTargetNumericExact("profile_target_high_mmol", listOf("profileTargetTop"))
        addBooleanFlagExact("aaps_temp_target_active", listOf("tempTargetActive"))
        addNumericExact("aaps_temp_target_started_at_ms", "epoch_ms", listOf("tempTargetStartedAt"))
        addNumericExact("aaps_temp_target_expires_at_ms", "epoch_ms", listOf("tempTargetExpiresAt"))
        addNumericExact("aaps_temp_target_duration_ms", "ms", listOf("tempTargetDurationMs"))
        addText("aaps_temp_target_reason", listOf("tempTargetReason"))
        addText("aaps_temp_target_id", listOf("tempTargetId"))
        addProfilePercent("profile_percent", listOf("percentage", "profilePercentage"))
        addUam("uam_value", listOf("unannouncedMeal", "uamDetected", "hasUam", "isUam"))
        addNumeric("isf_value", null, listOf("isf", "sens", "sensitivity"))
        addNumeric("cr_value", null, listOf("cr", "carbRatio", "carb_ratio", "icRatio"))
        addNumericExact("sensor_age_days", "d", listOf("sensorAgeDays", "sensor_age_days"))
        addNumericExact("sensor_age_hours", "h", listOf("sensorAgeHours", "sensor_age_hours"))
        addNumericExact("sage_days", "d", listOf("sageDays", "sage_days", "sage"))
        addNumericExact("cage_days", "d", listOf("cageDays", "cage_days", "cage", "cannulaAgeDays", "cannula_age_days"))
        addNumericExact("basal_rate_u_h", "U/h", listOf("rate", "absolute", "basalRate", "basal_rate"))
        addNumeric("insulin_req_units", "U", listOf("insulinReq", "insulin_required"))
        addIsfCrFromReason(
            listOf(
                "reason",
                "enacted.reason",
                "suggested.reason",
                "openaps.enacted.reason",
                "openaps.suggested.reason"
            )
        )
        addUamFromPredictions()

        addText("activity_label", listOf("exercise", "activityType", "sport", "workout"))
        addText("dia_source", listOf("diaSource", "insulinCurve"))

        val sensorAgeDays = output.firstOrNull { it.key == "sensor_age_days" }?.valueDouble
        val sensorAgeHours = output.firstOrNull { it.key == "sensor_age_hours" }?.valueDouble
        if (sensorAgeDays != null && sensorAgeHours == null) {
            output += sample(timestamp, source, "sensor_age_hours", sensorAgeDays * 24.0, null, "h")
        } else if (sensorAgeHours != null && sensorAgeDays == null) {
            output += sample(timestamp, source, "sensor_age_days", sensorAgeHours / 24.0, null, "d")
        }
        deriveSensorAgeFromStartedAt(
            output = output,
            timestamp = timestamp,
            source = source,
            entries = values.entries
        )

        if (!shouldSkipRawForSource(source)) {
            appendRawSamples(
                output = output,
                timestamp = timestamp,
                source = source,
                keyPrefix = "raw",
                values = values
            )
        }

        return sanitizeSamples(output)
    }

    fun fromPhysicalActivityKeyValueMap(
        timestamp: Long,
        source: String,
        values: Map<String, String>
    ): PhysicalActivityTelemetryPayload {
        if (source !in PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES) {
            return PhysicalActivityTelemetryPayload(emptyList(), emptyList())
        }
        val mapped = fromKeyValueMap(timestamp, source, values)
        return PhysicalActivityTelemetryPayload(
            persistedMetrics = mapped.filter { sample ->
                sample.key in PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS &&
                    sample.valueDouble?.isFinite() == true &&
                    sample.valueText == null
            },
            labels = mapped.filter { sample ->
                sample.key == ACTIVITY_LABEL_KEY &&
                    sample.valueDouble == null &&
                    !sample.valueText.isNullOrBlank() &&
                    sample.valueText.length <= MAX_ACTIVITY_LABEL_LENGTH
            }
        )
    }

    fun fromNightscoutTreatment(
        timestamp: Long,
        source: String,
        eventType: String?,
        payload: Map<String, String>
    ): List<TelemetrySampleEntity> {
        val output = fromKeyValueMap(timestamp, source, payload).toMutableList()
        val event = eventType.orEmpty().lowercase(Locale.US)
        if (event.contains("temp") && event.contains("target")) {
            val lowMmol = payload["targetBottomMmol"]?.toDoubleOrNullLocale()
                ?: payload["targetBottom"]?.toDoubleOrNullLocale()?.let(::normalizeTempTargetMmol)
            val highMmol = payload["targetTopMmol"]?.toDoubleOrNullLocale()
                ?: payload["targetTop"]?.toDoubleOrNullLocale()?.let(::normalizeTempTargetMmol)
            lowMmol?.let {
                output += sample(timestamp, source, "temp_target_low_mmol", it, null, "mmol/L")
            }
            highMmol?.let {
                output += sample(timestamp, source, "temp_target_high_mmol", it, null, "mmol/L")
            }
        }
        return sanitizeSamples(output)
    }

    fun fromFlattenedNightscoutDeviceStatus(
        timestamp: Long,
        source: String,
        flattened: Map<String, String>
    ): List<TelemetrySampleEntity> {
        if (flattened.isEmpty()) return emptyList()
        val normalized = flattened.mapKeys { it.key.lowercase(Locale.US) }
        val output = mutableListOf<TelemetrySampleEntity>()

        fun addPattern(canonicalKey: String, unit: String?, patterns: List<String>) {
            val raw = normalized.entries.firstOrNull { entry ->
                patterns.any { p -> entry.key.contains(p) }
            }?.value ?: return
            val parsed = raw.toDoubleOrNullLocale() ?: return
            output += sample(timestamp, source, canonicalKey, parsed, null, unit)
        }

        fun firstMatchingEntry(patterns: List<String>): Map.Entry<String, String>? {
            return normalized.entries.firstOrNull { entry ->
                patterns.any { pattern -> entry.key.contains(pattern) }
            }
        }

        fun firstSuffixEntry(suffixes: List<String>): Map.Entry<String, String>? {
            return normalized.entries.firstOrNull { entry ->
                suffixes.any { suffix ->
                    entry.key.endsWith(suffix) || entry.key.contains(".$suffix")
                }
            }
        }

        fun addAgePattern(
            canonicalKey: String,
            unit: String?,
            patterns: List<String>,
            transform: (Double) -> Double = { it }
        ) {
            val entry = firstMatchingEntry(patterns) ?: return
            val parsed = entry.value.toDoubleOrNullLocale() ?: return
            val transformed = transform(parsed)
            output += sample(timestamp, source, canonicalKey, transformed, null, unit)
            if (canonicalKey == "sensor_age_days" && output.none { it.key == "sensor_age_source_raw" }) {
                output += sample(timestamp, source, "sensor_age_source_raw", null, entry.key, null)
            }
        }

        appendInsulinRuntimeSamples(
            output = output,
            timestamp = timestamp,
            source = source,
            mapping = resolveInsulinRuntime(
                source = source,
                values = flattened,
                sampleTimestamp = timestamp,
                observedAtTimestamp = timestamp,
                legacyIobAliases = FLATTENED_NIGHTSCOUT_IOB_EXACT_ALIASES,
                allowTrustedAaps = false
            )
        )
        addPattern("cob_grams", "g", listOf(".cob", "cob"))
        addPattern("activity_ratio", null, listOf("activity"))
        addPattern("distance_km", "km", listOf("distance", "distancekm"))
        addPattern("active_minutes", "min", listOf("active_minutes", "activeminutes", "exerciseminutes"))
        addPattern("calories_active_kcal", "kcal", listOf("activecalories", "caloriesactive", "kcals"))
        addPattern("dia_hours", "h", listOf("dia"))
        addPattern("steps_count", "steps", listOf("steps", "step"))
        addPattern("insulin_units", "U", listOf("insulin"))
        addPattern("carbs_grams", "g", listOf("carbs"))
        addPattern("heart_rate_bpm", "bpm", listOf("heart", "heartrate"))
        addPattern("profile_percent", "%", listOf("profilepercentage", "percent"))
        addAgePattern(
            canonicalKey = "sensor_age_days",
            unit = "d",
            patterns = listOf("sensoragedays", "sensor.age.days")
        )
        addAgePattern(
            canonicalKey = "sensor_age_hours",
            unit = "h",
            patterns = listOf("sensoragehours", "sensor.age.hours")
        )
        firstSuffixEntry(listOf("sensorage"))?.let { entry ->
            val parsed = entry.value.toDoubleOrNullLocale()
            if (parsed != null && output.none { it.key == "sensor_age_days" }) {
                output += sample(timestamp, source, "sensor_age_days", parsed, null, "d")
                if (output.none { it.key == "sensor_age_source_raw" }) {
                    output += sample(timestamp, source, "sensor_age_source_raw", null, entry.key, null)
                }
            }
        }
        addAgePattern(
            canonicalKey = "sage_days",
            unit = "d",
            patterns = listOf("sagedays", ".sage")
        )
        addAgePattern(
            canonicalKey = "cage_days",
            unit = "d",
            patterns = listOf("cagedays", "cannulaagedays", ".cage", "cannulaage")
        )
        val sensorAgeDays = output.firstOrNull { it.key == "sensor_age_days" }?.valueDouble
        val sensorAgeHours = output.firstOrNull { it.key == "sensor_age_hours" }?.valueDouble
        if (sensorAgeDays != null && sensorAgeHours == null) {
            output += sample(timestamp, source, "sensor_age_hours", sensorAgeDays * 24.0, null, "h")
        } else if (sensorAgeHours != null && sensorAgeDays == null) {
            output += sample(timestamp, source, "sensor_age_days", sensorAgeHours / 24.0, null, "d")
        }
        deriveSensorAgeFromStartedAt(
            output = output,
            timestamp = timestamp,
            source = source,
            entries = normalized.entries
        )
        findUamValue(normalized, listOf("unannouncedMeal", "uamDetected", "hasUam", "isUam"))
            ?.let(::parseUamFlag)
            ?.let { parsed ->
                output += sample(
                    timestamp = timestamp,
                    source = source,
                    key = "uam_value",
                    valueDouble = parsed,
                    valueText = null,
                    unit = null
                )
            }
        addPattern("isf_value", null, listOf("sens", "isf"))
        addPattern("cr_value", null, listOf("carb_ratio", "carbratio", "icratio"))
        addPattern("basal_rate_u_h", "U/h", listOf("absolute", "basalrate", "tempbasal"))

        appendRawSamples(
            output = output,
            timestamp = timestamp,
            source = source,
            keyPrefix = "ns",
            values = normalized
        )

        return sanitizeSamples(output)
    }

    private fun normalizeTempTargetMmol(raw: Double): Double {
        return if (raw > TEMP_TARGET_MGDL_THRESHOLD) UnitConverter.mgdlToMmol(raw) else raw
    }

    private fun shouldSkipRawForSource(source: String): Boolean {
        val normalized = source.lowercase(Locale.US)
        return normalized == "local_sensor"
    }

    fun flattenAny(
        prefix: String,
        value: Any?,
        out: MutableMap<String, String>
    ) {
        when (value) {
            null -> return
            is Map<*, *> -> value.forEach { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                if (key.isEmpty()) return@forEach
                val nextPrefix = if (prefix.isBlank()) key else "$prefix.$key"
                flattenAny(nextPrefix, v, out)
            }
            is List<*> -> value.forEachIndexed { index, item ->
                val nextPrefix = "$prefix[$index]"
                flattenAny(nextPrefix, item, out)
            }
            else -> if (prefix.isNotBlank()) {
                out[prefix] = value.toString()
            }
        }
    }

    private fun findValue(values: Map<String, String>, aliases: List<String>): String? {
        aliases.forEach { alias ->
            values.entries.firstOrNull { it.key.equals(alias, ignoreCase = true) }?.value?.let {
                return it
            }
        }

        aliases.forEach { alias ->
            val aliasLower = alias.lowercase(Locale.US)
            values.entries.firstOrNull { (key, _) -> keyContainsAliasToken(key, aliasLower) }?.value?.let {
                return it
            }
        }
        return null
    }

    private fun findValueExact(values: Map<String, String>, aliases: List<String>): String? {
        aliases.forEach { alias ->
            values.entries.firstOrNull { it.key.equals(alias, ignoreCase = true) }?.value?.let {
                return it
            }
        }
        return null
    }

    private fun findUamValue(values: Map<String, String>, aliases: List<String>): String? {
        val normalizedAliases = aliases
            .map { normalizeAliasKey(it) }
            .filter { it.isNotBlank() }
        if (normalizedAliases.isEmpty()) return null

        return values.entries.firstOrNull { (key, _) ->
            val normalizedKey = normalizeAliasKey(key)
            normalizedAliases.any { alias ->
                normalizedKey == alias || normalizedKey.endsWith("_$alias")
            }
        }?.value
    }

    private fun findPredictionValue(values: Map<String, String>, aliases: List<String>): Double? {
        val direct = findValue(values, aliases)?.toDoubleOrNullLocale()
        if (direct != null) return direct

        val normalizedAliases = aliases
            .map(::normalizeAliasKey)
            .filter { it.isNotBlank() }
        if (normalizedAliases.isEmpty()) return null
        return values.entries.firstNotNullOfOrNull { (key, value) ->
            val normalizedKey = normalizeAliasKey(key)
            val match = normalizedAliases.any { alias ->
                normalizedKey == alias || normalizedKey.endsWith("_$alias")
            }
            if (!match) return@firstNotNullOfOrNull null
            value.toDoubleOrNullLocale()
        }
    }

    private fun findCurrentGlucoseMgdl(values: Map<String, String>): Double? {
        val exact = findValueExact(values, listOf("glucoseMgdl", "bg", "mgdl", "sgv", "glucose"))
            ?.toDoubleOrNullLocale()
        if (exact != null) return exact
        return values.entries.firstNotNullOfOrNull { (key, value) ->
            val normalized = normalizeAliasKey(key)
            val directBgKey = (normalized == "bg" || normalized.endsWith("_bg")) && !normalized.contains("predbg")
            val isMatch = normalized == "glucosemgdl" ||
                normalized == "mgdl" ||
                normalized == "sgv" ||
                normalized == "glucose" ||
                normalized.endsWith("_glucosemgdl") ||
                normalized.endsWith("_sgv") ||
                directBgKey
            if (!isMatch) return@firstNotNullOfOrNull null
            value.toDoubleOrNullLocale()
        }
    }

    private fun parseUamFlag(raw: String): Double? {
        val normalized = raw.trim().lowercase(Locale.US)
        return when (normalized) {
            "true", "yes", "on", "enabled", "active" -> 1.0
            "false", "no", "off", "disabled", "inactive" -> 0.0
            else -> normalized.toDoubleOrNullLocale()?.takeIf { it in 0.0..1.0 }
        }
    }

    private fun keyContainsAliasToken(key: String, aliasLower: String): Boolean {
        if (aliasLower.isBlank()) return false
        val normalizedKey = key
            .replace(CAMEL_BOUNDARY_REGEX, "$1_$2")
            .lowercase(Locale.US)
        val keyTokens = normalizedKey.split(SEPARATOR_RUNS_REGEX).filter { it.isNotBlank() }
        if (keyTokens.any { it == aliasLower }) return true

        val compactAlias = aliasLower.replace(COMPACT_NON_ALNUM_REGEX, "")
        if (compactAlias.isBlank()) return false
        val compactKey = normalizedKey.replace(COMPACT_NON_ALNUM_REGEX, "")
        return compactKey.endsWith(compactAlias)
    }

    private fun normalizeAliasKey(value: String): String {
        return value
            .replace(CAMEL_BOUNDARY_REGEX, "$1_$2")
            .lowercase(Locale.US)
            .replace(SEPARATOR_RUNS_REGEX, "_")
            .trim('_')
    }

    private fun appendRawSamples(
        output: MutableList<TelemetrySampleEntity>,
        timestamp: Long,
        source: String,
        keyPrefix: String,
        values: Map<String, String>
    ) {
        var added = 0
        values.entries
            .asSequence()
            .sortedBy { it.key.length }
            .forEach { (rawKey, rawValue) ->
                if (added >= MAX_RAW_SAMPLES_PER_PAYLOAD) return@forEach
                val key = normalizeRawKey(rawKey, keyPrefix) ?: return@forEach
                if (isSensitiveKey(rawKey) || isSensitiveKey(key)) return@forEach
                val text = rawValue.trim()
                if (text.isBlank()) return@forEach
                if (text.length > 400 && (text.startsWith("{") || text.startsWith("["))) return@forEach

                val numeric = text.toDoubleOrNullLocale()
                if (numeric != null && numeric.isFinite()) {
                    output += sample(
                        timestamp = timestamp,
                        source = source,
                        key = key,
                        valueDouble = numeric,
                        valueText = null,
                        unit = null
                    )
                } else {
                    output += sample(
                        timestamp = timestamp,
                        source = source,
                        key = key,
                        valueDouble = null,
                        valueText = text.take(MAX_TEXT_VALUE_LENGTH),
                        unit = null
                    )
                }
                added += 1
            }
    }

    private fun deriveSensorAgeFromStartedAt(
        output: MutableList<TelemetrySampleEntity>,
        timestamp: Long,
        source: String,
        entries: Collection<Map.Entry<String, String>>
    ) {
        if (output.any { it.key == "sensor_age_hours" || it.key == "sensor_age_days" }) return
        val startedAtEntry = entries.firstOrNull { entry ->
            entry.key.lowercase(Locale.US).contains("sensorstartedat")
        } ?: return
        val rawStartedAt = startedAtEntry.value.toDoubleOrNullLocale() ?: return
        val startedAtMs = normalizeEpochMillis(rawStartedAt) ?: return
        if (startedAtMs <= 0L || startedAtMs > timestamp) return
        val ageHours = ((timestamp - startedAtMs).coerceAtLeast(0L)) / 3_600_000.0
        if (ageHours !in 0.0..720.0) return
        output += sample(timestamp, source, "sensor_age_hours", ageHours, null, "h")
        output += sample(timestamp, source, "sensor_age_days", ageHours / 24.0, null, "d")
        if (output.none { it.key == "sensor_age_source_raw" }) {
            output += sample(timestamp, source, "sensor_age_source_raw", null, startedAtEntry.key, null)
        }
    }

    private fun normalizeEpochMillis(raw: Double): Long? {
        if (!raw.isFinite() || raw <= 0.0) return null
        return when {
            raw > 1_000_000_000_000.0 -> raw.toLong()
            raw > 1_000_000_000.0 -> (raw * 1000.0).toLong()
            else -> null
        }
    }

    private fun normalizeRawKey(rawKey: String, keyPrefix: String): String? {
        val normalized = rawKey
            .lowercase(Locale.US)
            .replace(SEPARATOR_RUNS_REGEX, "_")
            .trim('_')
            .take(MAX_RAW_KEY_LENGTH)
        if (normalized.isBlank()) return null
        if (normalized in META_ONLY_KEYS) return null
        if (normalized.length < 2) return null
        return "${keyPrefix}_$normalized"
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lowered = key.lowercase(Locale.US)
        return SENSITIVE_KEY_PARTS.any { lowered.contains(it) }
    }

    private fun sanitizeSamples(samples: List<TelemetrySampleEntity>): List<TelemetrySampleEntity> {
        val deduped = linkedMapOf<String, TelemetrySampleEntity>()
        samples.forEach { raw ->
            val sanitized = sanitizeSample(raw) ?: return@forEach
            deduped[sanitized.id] = sanitized
        }
        return deduped.values.toList()
    }

    private fun sanitizeSample(sample: TelemetrySampleEntity): TelemetrySampleEntity? {
        val value = sample.valueDouble ?: return sample
        val inRange = when (sample.key) {
            "iob_units", "iob_net_units", "iob_basal_units" -> value in -30.0..30.0
            "iob_bolus_units" -> value in 0.0..30.0
            "insulin_activity" -> value in -5.0..5.0
            "iob_effective_positive_units" -> value in 0.0..60.0
            "iob_relay_timestamp_ms" -> value in 1_000_000_000_000.0..10_000_000_000_000.0
            "iob_runtime_confidence" -> value in 0.0..1.0
            "iob_runtime_source_code" -> value in 1.0..4.0 && value % 1.0 == 0.0
            "cob_grams" -> value in 0.0..400.0
            "carbs_grams" -> value in 0.1..400.0
            "insulin_units" -> value in 0.01..40.0
            "future_carbs_grams" -> value in 0.0..400.0
            "dia_hours" -> value in 0.5..24.0
            "steps_count" -> value in 0.0..150_000.0
            "activity_ratio" -> value in 0.2..3.0
            "distance_km" -> value in 0.0..250.0
            "active_minutes" -> value in 0.0..1_440.0
            "calories_active_kcal" -> value in 0.0..12_000.0
            "heart_rate_bpm" -> value in 25.0..240.0
            "temp_target_low_mmol", "temp_target_high_mmol",
            "profile_target_low_mmol", "profile_target_high_mmol" -> value in 3.0..15.0
            "temp_target_duration_min" -> value in 5.0..720.0
            "aaps_temp_target_active" -> value == 0.0 || value == 1.0
            "aaps_temp_target_started_at_ms", "aaps_temp_target_expires_at_ms" -> value in 1_000_000_000_000.0..10_000_000_000_000.0
            "aaps_temp_target_duration_ms" -> value in 0.0..86_400_000.0
            "profile_percent" -> value in 10.0..300.0
            "uam_value" -> value in 0.0..1.5
            "isf_value" -> value in 0.2..18.0
            "cr_value" -> value in 2.0..60.0
            "sensor_age_days", "sage_days" -> value in 0.0..30.0
            "sensor_age_hours" -> value in 0.0..720.0
            "cage_days" -> value in 0.0..30.0
            "basal_rate_u_h" -> value in 0.0..15.0
            "insulin_req_units" -> value in -5.0..20.0
            else -> true
        }
        return sample.takeIf { inRange }
    }

    private fun sample(
        timestamp: Long,
        source: String,
        key: String,
        valueDouble: Double?,
        valueText: String?,
        unit: String?
    ): TelemetrySampleEntity {
        val safeTimestamp = sanitizeTimestamp(timestamp)
        val fingerprint = valueText ?: valueDouble?.let { String.format(Locale.US, "%.4f", it) } ?: "null"
        val isRaw = key.startsWith("raw_") || key.startsWith("ns_")
        val id = if (isRaw) {
            "tm-$source-$key-$safeTimestamp-${fingerprint.hashCode()}"
        } else {
            // Canonical telemetry must be deterministic per source/key/timestamp to avoid
            // conflicting values at identical timestamps.
            "tm-$source-$key-$safeTimestamp"
        }
        return TelemetrySampleEntity(
            id = id,
            timestamp = safeTimestamp,
            source = source,
            key = key,
            valueDouble = valueDouble,
            valueText = valueText,
            unit = unit,
            quality = "OK"
        )
    }

    private fun sanitizeTimestamp(timestamp: Long): Long {
        if (timestamp <= 0L) return System.currentTimeMillis()
        return timestamp
    }

    private fun String.toDoubleOrNullLocale(): Double? = replace(",", ".").toDoubleOrNull()

    private const val ACTIVITY_LABEL_KEY = "activity_label"
    private const val MAX_ACTIVITY_LABEL_LENGTH = 64

    private fun resolveInsulinRuntime(
        source: String,
        values: Map<String, String>,
        sampleTimestamp: Long,
        observedAtTimestamp: Long,
        legacyIobAliases: List<String> = LEGACY_IOB_EXACT_ALIASES,
        allowTrustedAaps: Boolean
    ): InsulinRuntimeMapping? {
        val safeObservedAtTimestamp = observedAtTimestamp
            .takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val componentKeys = listOf("netIob", "bolusIob", "basalIob", "insulinActivity")
        val hasComponentFields = componentKeys.any { key ->
            findValueExact(values, listOf(key)) != null
        }
        val isAaps = allowTrustedAaps && source.lowercase(Locale.US) in TRUSTED_AAPS_IOB_SOURCES
        val legacyRaw = findValueExact(values, legacyIobAliases)
        if (!hasComponentFields && legacyRaw == null) return null

        val sharedTimestampRaw = findValueExact(values, listOf("iobTimestamp"))
        val sharedTimestamp = sharedTimestampRaw.toEpochMillisOrNull()

        fun timedComponent(valueKey: String, timestampKey: String): TimedInsulinValue? {
            val rawValue = findValueExact(values, listOf(valueKey)) ?: return null
            val value = rawValue.toDoubleOrNullLocale() ?: return null
            val specificTimestampRaw = findValueExact(values, listOf(timestampKey))
            val componentTimestamp = when {
                specificTimestampRaw != null -> specificTimestampRaw.toEpochMillisOrNull() ?: 0L
                sharedTimestampRaw != null -> sharedTimestamp ?: 0L
                else -> 0L
            }
            return TimedInsulinValue(value = value, timestamp = componentTimestamp)
        }

        val components = if (hasComponentFields && isAaps) {
            InsulinComponentTelemetry(
                netIob = timedComponent("netIob", "netIobTimestamp"),
                bolusIob = timedComponent("bolusIob", "bolusIobTimestamp"),
                basalIob = timedComponent("basalIob", "basalIobTimestamp"),
                insulinActivity = timedComponent("insulinActivity", "insulinActivityTimestamp")
            )
        } else {
            null
        }

        val fallbackTimestamp = when {
            sharedTimestampRaw == null && isAaps -> 0L
            sharedTimestampRaw == null -> sampleTimestamp.takeIf { it > 0L } ?: 0L
            sharedTimestamp != null -> sharedTimestamp
            else -> 0L
        }
        val fallback = legacyRaw
            ?.toDoubleOrNullLocale()
            ?.let { TimedInsulinValue(value = it, timestamp = fallbackTimestamp) }
        val resolution = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = safeObservedAtTimestamp,
            components = components,
            legacyIob = fallback.takeIf { isAaps },
            externalEstimate = fallback.takeUnless { isAaps }
        )
        return InsulinRuntimeMapping(
            resolution = resolution,
            emitRejectionTombstones = isAaps && hasComponentFields
        )
    }

    private fun appendInsulinRuntimeSamples(
        output: MutableList<TelemetrySampleEntity>,
        timestamp: Long,
        source: String,
        mapping: InsulinRuntimeMapping?
    ) {
        mapping ?: return
        val snapshot = mapping.resolution.snapshot
        if (snapshot == null && !mapping.emitRejectionTombstones) return

        output += sample(timestamp, source, "iob_units", snapshot?.effectivePositiveIobUnits, null, "U")
        output += sample(timestamp, source, "iob_net_units", snapshot?.netIobUnits, null, "U")
        output += sample(timestamp, source, "iob_bolus_units", snapshot?.bolusIobUnits, null, "U")
        output += sample(timestamp, source, "iob_basal_units", snapshot?.basalIobUnits, null, "U")
        output += sample(timestamp, source, "insulin_activity", snapshot?.insulinActivity, null, "U/min")
        output += sample(
            timestamp,
            source,
            "iob_effective_positive_units",
            snapshot?.effectivePositiveIobUnits,
            null,
            "U"
        )
        output += sample(
            timestamp,
            source,
            "iob_relay_timestamp_ms",
            snapshot?.timestamp?.toDouble(),
            null,
            "epoch_ms"
        )
        output += sample(
            timestamp,
            source,
            "iob_runtime_confidence",
            snapshot?.confidence,
            null,
            null
        )
        output += sample(
            timestamp,
            source,
            "iob_runtime_source",
            null,
            snapshot?.source?.name,
            null
        )
        output += sample(
            timestamp,
            source,
            "iob_runtime_source_code",
            snapshot?.source?.let(InsulinRuntimeSnapshotResolver::sourceCode),
            null,
            null
        )
        output += sample(
            timestamp,
            source,
            "iob_runtime_fallback_reason",
            null,
            snapshot?.fallbackReason ?: mapping.resolution.rejectionReason,
            null
        )
    }

    private fun String?.toEpochMillisOrNull(): Long? {
        val value = this?.toDoubleOrNullLocale() ?: return null
        if (
            !value.isFinite() ||
            value < MIN_AUTHORITATIVE_TIMESTAMP_MS.toDouble() ||
            value > MAX_AUTHORITATIVE_TIMESTAMP_MS.toDouble() ||
            value != value.toLong().toDouble()
        ) return null
        val timestamp = value.toLong()
        return timestamp
    }

    private data class InsulinRuntimeMapping(
        val resolution: InsulinRuntimeResolution,
        val emitRejectionTombstones: Boolean
    )

    private const val MIN_AUTHORITATIVE_TIMESTAMP_MS = 1_000_000_000_000L
    private const val MAX_AUTHORITATIVE_TIMESTAMP_MS = 10_000_000_000_000L
}
