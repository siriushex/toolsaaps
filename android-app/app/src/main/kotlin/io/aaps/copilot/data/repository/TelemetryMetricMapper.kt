package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.util.UnitConverter
import java.util.Locale

object TelemetryMetricMapper {
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

    fun fromKeyValueMap(
        timestamp: Long,
        source: String,
        values: Map<String, String>
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
            val extracted = Regex("""\((\d{2,3}(?:[.,]\d+)?)%\)""")
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
            val isfRaw = Regex("""\bISF:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
                .find(reason)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
            val crRaw = Regex("""\bCR:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
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

        addNumeric("iob_units", "U", listOf("iob", "iobtotal", "insulinonboard"))
        addNumeric("cob_grams", "g", listOf("cob", "carbsonboard"))
        addNumericExact("carbs_grams", "g", listOf("carbs", "grams", "enteredCarbs", "mealCarbs"))
        addNumericExact("insulin_units", "U", listOf("insulin", "insulinUnits", "bolus", "enteredInsulin"))
        addDiaHours("dia_hours", listOf("dia", "insulinActionTime", "insulinactiontime", "insulinEndTime"))
        addNumeric("steps_count", "steps", listOf("steps", "stepCount", "step_count"))
        addNumeric("activity_ratio", null, listOf("activity", "activityRatio", "sensitivityRatio"))
        addNumeric("heart_rate_bpm", "bpm", listOf("heartRate", "heart_rate", "bpm"))
        addTempTargetNumeric("temp_target_low_mmol", listOf("targetBottom", "target_bottom", "targetLow"))
        addTempTargetNumeric("temp_target_high_mmol", listOf("targetTop", "target_top", "targetHigh"))
        addNumeric("temp_target_duration_min", "min", listOf("duration", "durationInMinutes"))
        addProfilePercent("profile_percent", listOf("percentage", "profilePercentage"))
        addUam("uam_value", listOf("enableUAM", "unannouncedMeal", "uamDetected", "hasUam", "isUam", "uam"))
        addNumeric("isf_value", null, listOf("isf", "sens", "sensitivity"))
        addNumeric("cr_value", null, listOf("cr", "carbRatio", "carb_ratio", "icRatio"))
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

        addText("activity_label", listOf("exercise", "activityType", "sport", "workout"))
        addText("dia_source", listOf("diaSource", "insulinCurve"))

        appendRawSamples(
            output = output,
            timestamp = timestamp,
            source = source,
            keyPrefix = "raw",
            values = values
        )

        return sanitizeSamples(output)
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

        addPattern("iob_units", "U", listOf("iob.iob", ".iob"))
        addPattern("cob_grams", "g", listOf(".cob", "cob"))
        addPattern("activity_ratio", null, listOf("activity"))
        addPattern("dia_hours", "h", listOf("dia"))
        addPattern("steps_count", "steps", listOf("steps", "step"))
        addPattern("insulin_units", "U", listOf("insulin"))
        addPattern("carbs_grams", "g", listOf("carbs"))
        addPattern("heart_rate_bpm", "bpm", listOf("heart", "heartrate"))
        addPattern("profile_percent", "%", listOf("profilepercentage", "percent"))
        findUamValue(normalized, listOf("enableUAM", "unannouncedMeal", "uamDetected", "hasUam", "isUam", "uam"))
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
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
        val keyTokens = normalizedKey.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (keyTokens.any { it == aliasLower }) return true

        val compactAlias = aliasLower.replace(Regex("[^a-z0-9]"), "")
        if (compactAlias.isBlank()) return false
        val compactKey = normalizedKey.replace(Regex("[^a-z0-9]"), "")
        return compactKey.endsWith(compactAlias)
    }

    private fun normalizeAliasKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
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

    private fun normalizeRawKey(rawKey: String, keyPrefix: String): String? {
        val normalized = rawKey
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
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
        return samples
            .asSequence()
            .mapNotNull(::sanitizeSample)
            .distinctBy { it.id }
            .toList()
    }

    private fun sanitizeSample(sample: TelemetrySampleEntity): TelemetrySampleEntity? {
        val value = sample.valueDouble ?: return sample
        val inRange = when (sample.key) {
            "iob_units" -> value in 0.0..30.0
            "cob_grams" -> value in 0.0..400.0
            "carbs_grams" -> value in 0.0..400.0
            "insulin_units" -> value in 0.0..40.0
            "dia_hours" -> value in 0.5..24.0
            "steps_count" -> value in 0.0..150_000.0
            "activity_ratio" -> value in 0.2..3.0
            "heart_rate_bpm" -> value in 25.0..240.0
            "temp_target_low_mmol", "temp_target_high_mmol" -> value in 3.0..15.0
            "temp_target_duration_min" -> value in 5.0..720.0
            "profile_percent" -> value in 10.0..300.0
            "uam_value" -> value in 0.0..1.5
            "isf_value" -> value in 0.2..18.0
            "cr_value" -> value in 2.0..60.0
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
        val fingerprint = valueText ?: valueDouble?.let { String.format(Locale.US, "%.4f", it) } ?: "null"
        return TelemetrySampleEntity(
            id = "tm-$source-$key-$timestamp-${fingerprint.hashCode()}",
            timestamp = timestamp,
            source = source,
            key = key,
            valueDouble = valueDouble,
            valueText = valueText,
            unit = unit,
            quality = "OK"
        )
    }

    private fun String.toDoubleOrNullLocale(): Double? = replace(",", ".").toDoubleOrNull()
}
