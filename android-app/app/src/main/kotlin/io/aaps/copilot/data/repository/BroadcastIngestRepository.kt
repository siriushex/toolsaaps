package io.aaps.copilot.data.repository

import android.content.Intent
import android.os.Bundle
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.util.GlucoseUnitNormalizer
import kotlin.math.abs
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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

        runPeriodicMaintenanceIfNeeded()

        val extras = flattenExtras(intent)
        val source = resolveSource(action)
        val glucose = parseGlucose(action, extras)
        val therapy = parseTherapy(action, extras)
        val telemetry = parseTelemetry(action, source, extras)

        var importedGlucose = 0
        var importedTherapy = 0
        var importedTelemetry = 0

        glucose?.let { sample ->
            val outlier = isGlucoseOutlier(sample)
            if (outlier) {
                auditLogger.warn(
                    "broadcast_glucose_outlier_skipped",
                    mapOf(
                        "action" to action,
                        "source" to sample.source,
                        "timestamp" to sample.timestamp,
                        "mmol" to sample.mmol
                    )
                )
            } else {
                val existing = db.glucoseDao().bySourceAndTimestamp(sample.source, sample.timestamp)
                if (existing != null) {
                    val differs = abs(existing.mmol - sample.mmol) > GLUCOSE_REPLACE_EPSILON
                    if (differs || existing.quality != sample.quality) {
                        db.glucoseDao().deleteBySourceAndTimestamp(sample.source, sample.timestamp)
                        db.glucoseDao().upsertAll(listOf(sample))
                        importedGlucose = 1
                    }
                } else {
                    db.glucoseDao().upsertAll(listOf(sample))
                    importedGlucose = 1
                }
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
        if (!isSupportedGlucoseAction(action)) return null
        if (action.endsWith("BgEstimateNoData")) return null
        if (action.startsWith("info.nightscout.androidaps.")) return null

        val glucose = GlucoseValueResolver.resolve(extras) ?: return null

        val units = findStringExact(
            extras,
            listOf("com.eveningoutpost.dexdrip.Extras.Display.Units", "display.units", "display_units", "units", "unit")
        )?.lowercase(Locale.US)
            ?: if (glucose.key.lowercase(Locale.US).contains("mgdl")) "mgdl" else null
        val mmol = GlucoseUnitNormalizer.normalizeToMmol(
            valueRaw = glucose.valueRaw,
            valueKey = glucose.key,
            units = units
        )
        if (mmol !in 1.0..33.0) return null

        val ts = normalizeTimestamp(
            findTimestamp(
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

        if (action.startsWith("info.nightscout.androidaps.")) {
            // androidaps.* broadcasts contain status/derived fields, not authoritative therapy events.
            return emptyList()
        }

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

        val carbs = findDoubleExact(extras, listOf("carbs", "grams", "enteredCarbs", "mealCarbs"))
        val insulin = findDoubleExact(extras, listOf("insulin", "insulinUnits", "bolus", "enteredInsulin", "bolusUnits"))
        val duration = findLongExact(extras, listOf("duration", "durationInMinutes"))?.toInt()
        val targetBottom = findDoubleExact(extras, listOf("targetBottom", "target_bottom", "targetLow"))
        val targetTop = findDoubleExact(extras, listOf("targetTop", "target_top", "targetHigh"))
        val eventType = findStringExact(extras, listOf("eventType", "event_type", "type"))?.lowercase()

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

    private fun findDoubleExact(extras: Map<String, String>, keys: List<String>): Double? {
        return findStringExact(extras, keys)?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun findLong(extras: Map<String, String>, keys: List<String>): Long? {
        val raw = findString(extras, keys) ?: return null
        return raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun findLongExact(extras: Map<String, String>, keys: List<String>): Long? {
        val raw = findStringExact(extras, keys) ?: return null
        return raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun findTimestamp(extras: Map<String, String>, keys: List<String>): Long? {
        keys.forEach { key ->
            val exact = extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            val parsedExact = parseTimestampValue(exact)
            if (parsedExact != null) return parsedExact
        }
        keys.forEach { key ->
            val byToken = findStringByToken(extras, listOf(key))
            val parsedToken = parseTimestampValue(byToken)
            if (parsedToken != null) return parsedToken
        }
        return null
    }

    private fun parseTimestampValue(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val numeric = trimmed.toLongOrNull()
            ?: trimmed.toDoubleOrNull()?.toLong()
        if (numeric != null) {
            return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        }

        val iso = runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
        if (iso != null) return iso

        val normalized = if (trimmed.endsWith("Z") || trimmed.contains("+")) {
            trimmed
        } else {
            trimmed.replace(' ', 'T') + "Z"
        }
        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
    }

    private fun parseTelemetry(
        action: String,
        source: String,
        extras: Map<String, String>
    ): List<TelemetrySampleEntity> {
        val mapped = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = extractTimestamp(extras),
            source = source,
            values = extras
        )
        if (!isHighFrequencyStatusAction(action)) return mapped

        // Status broadcasts may arrive every few seconds; keep canonical metrics +
        // a minimal raw diagnostic subset to avoid DB contention and drops.
        val canonical = mapped.filterNot {
            it.key.startsWith("raw_") || it.key in STATUS_EXCLUDED_CANONICAL_KEYS
        }
        val diagnostic = mapped.filter { it.key in STATUS_DIAGNOSTIC_RAW_KEYS }
        return (canonical + diagnostic).distinctBy { it.id }
    }

    private fun extractTimestamp(extras: Map<String, String>): Long = normalizeTimestamp(
        findTimestamp(
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

    private suspend fun pruneLegacyBroadcastArtifacts() {
        val removedTherapy = db.therapyDao().deleteLegacyBroadcastArtifacts()
        val removedUam = db.telemetryDao().deleteByKeyAboveThreshold(
            key = "uam_value",
            threshold = 1.5
        )
        val removedStatusCarbsNoise = db.telemetryDao().deleteBySourceAndKeyAtOrBelow(
            source = "aaps_broadcast",
            key = "carbs_grams",
            threshold = 0.0
        )
        val removedStatusInsulinNoise = db.telemetryDao().deleteBySourceAndKeyAtOrBelow(
            source = "aaps_broadcast",
            key = "insulin_units",
            threshold = 0.0
        )
        val dedupNightscout = db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source = "nightscout")
        val dedupAaps = db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source = "aaps_broadcast")
        val dedupLocal = db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source = "local_broadcast")
        if (
            removedTherapy > 0 ||
            removedUam > 0 ||
            removedStatusCarbsNoise > 0 ||
            removedStatusInsulinNoise > 0 ||
            dedupNightscout > 0 ||
            dedupAaps > 0 ||
            dedupLocal > 0
        ) {
            auditLogger.info(
                "broadcast_legacy_cleanup",
                mapOf(
                    "therapyRemoved" to removedTherapy,
                    "telemetryRemoved" to removedUam,
                    "statusCarbsNoiseRemoved" to removedStatusCarbsNoise,
                    "statusInsulinNoiseRemoved" to removedStatusInsulinNoise,
                    "glucoseDedupNightscout" to dedupNightscout,
                    "glucoseDedupAaps" to dedupAaps,
                    "glucoseDedupLocal" to dedupLocal
                )
            )
        }
    }

    private suspend fun runPeriodicMaintenanceIfNeeded() {
        val now = System.currentTimeMillis()
        val last = lastMaintenanceAtMs.get()
        if (now - last < MAINTENANCE_INTERVAL_MS) return
        if (!lastMaintenanceAtMs.compareAndSet(last, now)) return
        runCatching {
            pruneLegacyInvalidLocalBroadcastGlucose()
            pruneLegacyBroadcastArtifacts()
        }.onFailure {
            auditLogger.warn(
                "broadcast_maintenance_failed",
                mapOf("error" to (it.message ?: "unknown"))
            )
        }
    }

    private fun isHighFrequencyStatusAction(action: String): Boolean {
        return action == "info.nightscout.androidaps.status" || action == "app.aaps.status"
    }

    private fun normalizeTimestamp(ts: Long): Long {
        if (ts < 10_000_000_000L) return ts * 1000L
        return ts
    }

    private fun isSupportedGlucoseAction(action: String): Boolean {
        return action == "info.nightscout.client.NEW_SGV" ||
            action == "com.eveningoutpost.dexdrip.BgEstimate"
    }

    private suspend fun isGlucoseOutlier(sample: GlucoseSampleEntity): Boolean {
        val latest = db.glucoseDao().latestOne() ?: return false
        val deltaTs = abs(sample.timestamp - latest.timestamp)
        if (deltaTs > GLUCOSE_OUTLIER_WINDOW_MS) return false
        val deltaMmol = abs(sample.mmol - latest.mmol)
        return deltaMmol >= GLUCOSE_OUTLIER_DELTA_MMOL
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

    companion object {
        private val lastMaintenanceAtMs = AtomicLong(0L)
        private const val MAINTENANCE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val GLUCOSE_OUTLIER_WINDOW_MS = 10 * 60_000L
        private const val GLUCOSE_OUTLIER_DELTA_MMOL = 8.0
        private const val GLUCOSE_REPLACE_EPSILON = 0.01

        private val STATUS_EXCLUDED_CANONICAL_KEYS = setOf(
            // status payloads may carry these as predictive/placeholder values (often zero),
            // not confirmed therapy entries.
            "carbs_grams",
            "insulin_units"
        )

        private val STATUS_DIAGNOSTIC_RAW_KEYS = setOf(
            "raw_reason",
            "raw_profile",
            "raw_algorithm",
            "raw_bg",
            "raw_glucosemgdl",
            "raw_deltamgdl",
            "raw_avgdeltamgdl",
            "raw_slopearrow",
            "raw_insulinreq",
            "raw_futurecarbs"
        )
    }
}
