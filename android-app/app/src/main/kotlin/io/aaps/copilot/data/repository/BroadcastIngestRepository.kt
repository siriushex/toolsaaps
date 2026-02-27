package io.aaps.copilot.data.repository

import android.content.Intent
import android.os.Bundle
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.util.UnitConverter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class BroadcastIngestRepository(
    private val db: CopilotDatabase,
    private val auditLogger: AuditLogger
) {

    suspend fun ingest(intent: Intent): IngestResult {
        val action = intent.action.orEmpty()
        if (action.isBlank()) {
            auditLogger.warn("broadcast_ingest_skipped", mapOf("reason" to "missing_action"))
            return IngestResult(0, 0, 0, "missing_action")
        }

        pruneLegacyInvalidLocalBroadcastGlucose()

        val extras = flattenExtras(intent)
        val source = resolveSource(action)
        val glucose = parseGlucose(action, extras)
        val therapy = parseTherapy(action, extras)
        val telemetry = parseTelemetry(action, source, extras)

        var importedGlucose = 0
        var importedTherapy = 0
        var importedTelemetry = 0

        glucose?.let { sample ->
            val maxTs = db.glucoseDao().maxTimestamp() ?: 0L
            if (sample.timestamp > maxTs) {
                db.glucoseDao().upsertAll(listOf(sample))
                importedGlucose = 1
            }
        }
        if (therapy.isNotEmpty()) {
            db.therapyDao().upsertAll(therapy)
            importedTherapy = therapy.size
        }
        if (telemetry.isNotEmpty()) {
            db.telemetryDao().upsertAll(telemetry)
            importedTelemetry = telemetry.size
        }

        if (importedGlucose == 0 && importedTherapy == 0 && importedTelemetry == 0) {
            auditLogger.warn(
                "broadcast_ingest_no_data",
                mapOf("action" to action, "keys" to extras.keys.take(20))
            )
            return IngestResult(0, 0, 0, "no_supported_payload")
        }

        auditLogger.info(
            "broadcast_ingest_completed",
            mapOf(
                "action" to action,
                "glucose" to importedGlucose,
                "therapy" to importedTherapy,
                "telemetry" to importedTelemetry
            )
        )
        return IngestResult(importedGlucose, importedTherapy, importedTelemetry, null)
    }

    @Suppress("DEPRECATION")
    private fun flattenExtras(intent: Intent): Map<String, String> {
        val bundle = intent.extras ?: return emptyMap()
        val raw = linkedMapOf<String, String>()
        bundle.keySet().forEach { key ->
            flattenExtraValue(prefix = key, value = bundle.get(key), out = raw)
        }
        return raw
    }

    private fun flattenExtraValue(
        prefix: String,
        value: Any?,
        out: MutableMap<String, String>
    ) {
        when (value) {
            null -> return
            is Bundle -> {
                value.keySet().forEach { key ->
                    val nextPrefix = if (prefix.isBlank()) key else "$prefix.$key"
                    @Suppress("DEPRECATION")
                    flattenExtraValue(nextPrefix, value.get(key), out)
                }
            }
            is Map<*, *> -> {
                value.forEach { (k, v) ->
                    val mapKey = k?.toString()?.trim().orEmpty()
                    if (mapKey.isEmpty()) return@forEach
                    val nextPrefix = if (prefix.isBlank()) mapKey else "$prefix.$mapKey"
                    flattenExtraValue(nextPrefix, v, out)
                }
            }
            is Array<*> -> value.forEachIndexed { index, item ->
                val nextPrefix = "$prefix[$index]"
                flattenExtraValue(nextPrefix, item, out)
            }
            is Iterable<*> -> value.forEachIndexed { index, item ->
                val nextPrefix = "$prefix[$index]"
                flattenExtraValue(nextPrefix, item, out)
            }
            is String -> {
                val text = value.trim()
                if (text.isNotEmpty()) {
                    out[prefix] = text
                    parseJsonPayload(text).forEach { (k, v) ->
                        out.putIfAbsent(k, v)
                        if (prefix.isNotBlank()) out.putIfAbsent("$prefix.$k", v)
                    }
                }
            }
            else -> if (prefix.isNotBlank()) {
                out[prefix] = value.toString()
            }
        }
    }

    private fun parseJsonPayload(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyMap()
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val out = linkedMapOf<String, String>()
                    flattenJsonValue(prefix = "", value = JSONObject(trimmed), out = out)
                    out
                }
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    if (array.length() == 0) {
                        emptyMap()
                    } else {
                        val out = linkedMapOf<String, String>()
                        flattenJsonValue(prefix = "", value = array, out = out)
                        out
                    }
                }
                else -> emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun flattenJsonValue(prefix: String, value: Any?, out: MutableMap<String, String>) {
        when (value) {
            null -> return
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val nextPrefix = if (prefix.isBlank()) key else "$prefix.$key"
                    flattenJsonValue(nextPrefix, value.opt(key), out)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val nextPrefix = if (prefix.isBlank()) "[$index]" else "$prefix[$index]"
                    flattenJsonValue(nextPrefix, value.opt(index), out)
                }
            }
            else -> if (prefix.isNotBlank()) {
                out[prefix] = value.toString()
            }
        }
    }

    private fun parseGlucose(action: String, extras: Map<String, String>): GlucoseSampleEntity? {
        if (action.endsWith("NEW_TREATMENT") || action.endsWith("BgEstimateNoData")) return null
        if (action.startsWith("info.nightscout.androidaps.")) return null

        val valueWithKey = findDoubleWithKey(
            extras,
            listOf(
                "com.eveningoutpost.dexdrip.Extras.BgEstimate",
                "sgv",
                "mgdl",
                "glucose",
                "bgestimate"
            )
        ) ?: return null
        val valueRaw = valueWithKey.first
        val valueKey = valueWithKey.second

        val units = findStringExact(
            extras,
            listOf("com.eveningoutpost.dexdrip.Extras.Display.Units", "display.units", "display_units", "units", "unit")
        )?.lowercase()
            ?: if (valueKey.lowercase(Locale.US).contains("mgdl")) "mgdl" else null
        val mmol = normalizeGlucoseMmol(
            valueRaw = valueRaw,
            valueKey = valueKey,
            units = units
        ).coerceIn(1.0, 33.0)

        val ts = normalizeTimestamp(
            findLong(
                extras,
                listOf(
                    "com.eveningoutpost.dexdrip.Extras.Time",
                    "timestamp",
                    "time",
                    "date",
                    "mills",
                    "created_at"
                )
            ) ?: System.currentTimeMillis()
        )

        val source = resolveSource(action)

        return GlucoseSampleEntity(
            timestamp = ts,
            mmol = mmol,
            source = source,
            quality = "OK"
        )
    }

    private fun parseTherapy(action: String, extras: Map<String, String>): List<TherapyEventEntity> {
        val ts = extractTimestamp(extras)
        val source = resolveSource(action)

        if (action.endsWith("BgEstimateNoData")) {
            return listOf(
                TherapyEventEntity(
                    id = "br-$source-sensor_state-$ts-${action.hashCode()}",
                    timestamp = ts,
                    type = "sensor_state",
                    payloadJson = JSONObject(
                        mapOf(
                            "blocked" to "true",
                            "reason" to "no_data_broadcast",
                            "action" to action
                        )
                    ).toString()
                )
            )
        }

        if (action.endsWith("NEW_SGV") || action.endsWith("BgEstimate")) {
            // Glucose-only broadcast.
            return emptyList()
        }

        val carbs = findDouble(extras, listOf("carbs", "grams", "enteredCarbs"))
        val insulin = findDouble(extras, listOf("insulin", "insulinUnits", "bolus", "enteredInsulin"))
        val duration = findLong(extras, listOf("duration", "durationInMinutes"))?.toInt()
        val targetBottom = findDouble(extras, listOf("targetBottom", "target_bottom", "targetLow"))
        val targetTop = findDouble(extras, listOf("targetTop", "target_top", "targetHigh"))
        val eventType = findString(extras, listOf("eventType", "event_type", "type"))?.lowercase()

        val typeAndPayload = when {
            eventType?.contains("temp") == true && duration != null && (targetBottom != null || targetTop != null) -> {
                "temp_target" to buildMap {
                    put("duration", duration.toString())
                    targetBottom?.let { put("targetBottom", it.toString()) }
                    targetTop?.let { put("targetTop", it.toString()) }
                }
            }
            carbs != null && insulin != null && carbs > 0.0 && insulin > 0.0 -> {
                "meal_bolus" to mapOf("grams" to carbs.toString(), "bolusUnits" to insulin.toString())
            }
            insulin != null && insulin > 0.0 -> {
                "correction_bolus" to mapOf("units" to insulin.toString())
            }
            carbs != null && carbs > 0.0 -> {
                "carbs" to mapOf("grams" to carbs.toString())
            }
            else -> null
        } ?: return emptyList()

        val id = "br-$source-${typeAndPayload.first}-$ts-${typeAndPayload.second.hashCode()}"
        return listOf(
            TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = typeAndPayload.first,
                payloadJson = JSONObject(typeAndPayload.second).toString()
            )
        )
    }

    private fun findString(extras: Map<String, String>, keys: List<String>): String? {
        keys.firstNotNullOfOrNull { key ->
            extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return findStringByToken(extras, keys)
    }

    private fun findStringExact(extras: Map<String, String>, keys: List<String>): String? {
        return keys.firstNotNullOfOrNull { key ->
            extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        }
    }

    private fun findDouble(extras: Map<String, String>, keys: List<String>): Double? {
        return findString(extras, keys)?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun findDoubleWithKey(extras: Map<String, String>, keys: List<String>): Pair<Double, String>? {
        keys.forEach { key ->
            extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { entry ->
                entry.value.replace(",", ".").toDoubleOrNull()?.let { parsed ->
                    return parsed to entry.key
                }
            }
        }
        val tokenMatch = findEntryByToken(extras, keys) ?: return null
        val parsed = tokenMatch.value.replace(",", ".").toDoubleOrNull() ?: return null
        return parsed to tokenMatch.key
    }

    private fun findLong(extras: Map<String, String>, keys: List<String>): Long? {
        val raw = findString(extras, keys) ?: return null
        return raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun parseTelemetry(
        action: String,
        source: String,
        extras: Map<String, String>
    ) = TelemetryMetricMapper.fromKeyValueMap(
        timestamp = extractTimestamp(extras),
        source = source,
        values = extras
    )

    private fun extractTimestamp(extras: Map<String, String>): Long = normalizeTimestamp(
        findLong(
            extras,
            listOf(
                "com.eveningoutpost.dexdrip.Extras.Time",
                "timestamp",
                "time",
                "date",
                "mills",
                "created_at"
            )
        ) ?: System.currentTimeMillis()
    )

    private fun resolveSource(action: String): String = when {
        action.startsWith("info.nightscout.androidaps.") -> "aaps_broadcast"
        action.startsWith("info.nightscout.client.") -> "aaps_broadcast"
        action.startsWith("com.eveningoutpost.dexdrip.") -> "xdrip_broadcast"
        else -> "local_broadcast"
    }

    private fun normalizeGlucoseMmol(
        valueRaw: Double,
        valueKey: String,
        units: String?
    ): Double {
        val normalizedKey = normalizeKey(valueKey)
        val keyIndicatesMgdl = normalizedKey.contains("mgdl")
        val keyIndicatesMmol = normalizedKey.contains("mmol")
        val keySuggestsBgEstimate = normalizedKey.contains("bgestimate")
        val explicitMmol = units?.contains("mmol") == true
        val explicitMg = units?.contains("mg") == true

        return when {
            keyIndicatesMgdl -> UnitConverter.mgdlToMmol(valueRaw)
            keyIndicatesMmol -> valueRaw
            explicitMg -> UnitConverter.mgdlToMmol(valueRaw)
            // Some AAPS/xDrip payloads publish mg/dL values with display units=mmol.
            explicitMmol && (valueRaw > 35.0 || (keySuggestsBgEstimate && valueRaw >= 18.0)) ->
                UnitConverter.mgdlToMmol(valueRaw)
            explicitMmol -> valueRaw
            valueRaw > 35.0 -> UnitConverter.mgdlToMmol(valueRaw)
            else -> valueRaw
        }
    }

    private suspend fun pruneLegacyInvalidLocalBroadcastGlucose() {
        val removed = db.glucoseDao().deleteBySourceAndThreshold(
            source = "local_broadcast",
            thresholdMmol = 30.0
        )
        if (removed > 0) {
            auditLogger.info(
                "broadcast_glucose_cleanup",
                mapOf("removed" to removed, "source" to "local_broadcast", "thresholdMmol" to 30.0)
            )
        }
    }

    private fun normalizeTimestamp(ts: Long): Long {
        if (ts < 10_000_000_000L) return ts * 1000L
        return ts
    }

    private fun findStringByToken(extras: Map<String, String>, keys: List<String>): String? {
        return findEntryByToken(extras, keys)?.value?.takeIf { it.isNotBlank() }
    }

    private fun findEntryByToken(extras: Map<String, String>, keys: List<String>): Map.Entry<String, String>? {
        val tokens = keys.map { normalizeKey(it) }.filter { it.isNotBlank() }
        return extras.entries.firstOrNull { entry ->
            val key = normalizeKey(entry.key)
            val keyParts = key.split('_').filter { it.isNotBlank() }
            tokens.any { token ->
                key == token || key.endsWith("_$token") || keyParts.contains(token)
            }
        }
    }

    private fun normalizeKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    data class IngestResult(
        val glucoseImported: Int,
        val therapyImported: Int,
        val telemetryImported: Int,
        val warning: String?
    )
}
