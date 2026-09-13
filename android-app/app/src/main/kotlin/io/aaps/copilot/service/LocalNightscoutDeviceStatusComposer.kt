package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.util.LinkedHashMap
import kotlin.math.max

internal object LocalNightscoutDeviceStatusComposer {

    private const val SOURCE_LOCAL_NS_DEVICESTATUS = "local_nightscout_devicestatus"

    fun compose(
        timestamp: Long,
        latestByKey: Map<String, TelemetrySampleEntity>,
        latestGlucose: GlucoseSampleEntity?
    ): Map<String, Any?> {
        val openapsIob = linkedMapOf<String, Any?>().apply {
            numeric(latestByKey, "iob_units")?.let { put("iob", it) }
        }
        val openapsSuggested = linkedMapOf<String, Any?>().apply {
            numeric(latestByKey, "cob_grams")?.let { put("cob", it) }
            numeric(latestByKey, "activity_ratio")?.let { put("activity", it) }
            numeric(latestByKey, "insulin_req_units")?.let { put("insulinReq", it) }
            numeric(latestByKey, "dia_hours")?.let { put("dia", it) }
            numeric(latestByKey, "profile_percent")?.let { put("profilePercentage", it) }
            numeric(latestByKey, "isf_value")?.let { put("isf", it) }
            numeric(latestByKey, "cr_value")?.let { put("carb_ratio", it) }
            numeric(latestByKey, "temp_target_low_mmol")?.let { put("targetBottom", UnitConverter.mmolToMgdl(it)) }
            numeric(latestByKey, "temp_target_high_mmol")?.let { put("targetTop", UnitConverter.mmolToMgdl(it)) }
            numeric(latestByKey, "temp_target_duration_min")?.let { put("duration", it.toInt()) }
            numeric(latestByKey, "uam_value")?.let { put("uamDetected", it >= 0.5) }
        }

        val cgm = linkedMapOf<String, Any?>().apply {
            numeric(latestByKey, "sensor_age_days")?.let { put("sensorAgeDays", it) }
            numeric(latestByKey, "sensor_age_hours")?.let { put("sensorAgeHours", it) }
            numeric(latestByKey, "sage_days")?.let { put("sageDays", it) }
            latestByKey["sensor_age_source_raw"]?.valueText?.takeIf { it.isNotBlank() }?.let { put("sensorAgeSourceRaw", it) }
            latestGlucose?.mmol?.let { mmol ->
                put("sgv", UnitConverter.mmolToMgdl(mmol))
                put("glucoseMgdl", UnitConverter.mmolToMgdl(mmol))
            }
        }

        val pump = linkedMapOf<String, Any?>().apply {
            numeric(latestByKey, "cage_days")?.let { put("cageDays", it) }
            numeric(latestByKey, "basal_rate_u_h")?.let { put("basalRate", it) }
            numericFromSources(
                latestByKey = latestByKey,
                key = "insulin_units",
                allowedSources = setOf(SOURCE_LOCAL_NS_DEVICESTATUS)
            )?.let { put("insulin", it) }
            numericFromSources(
                latestByKey = latestByKey,
                key = "carbs_grams",
                allowedSources = setOf(SOURCE_LOCAL_NS_DEVICESTATUS)
            )?.let { put("carbs", it) }
        }

        val uploader = linkedMapOf<String, Any?>().apply {
            numeric(latestByKey, "steps_count")?.let { put("steps", it.toInt()) }
            numeric(latestByKey, "active_minutes")?.let { put("activeMinutes", it) }
            numeric(latestByKey, "distance_km")?.let { put("distanceKm", it) }
            numeric(latestByKey, "calories_active_kcal")?.let { put("activeCalories", it) }
            numeric(latestByKey, "heart_rate_bpm")?.let { put("heartRate", it) }
        }

        val loop = linkedMapOf<String, Any?>().apply {
            if (openapsIob.isNotEmpty()) put("iob", openapsIob)
            if (openapsSuggested.isNotEmpty()) put("suggested", openapsSuggested)
        }

        val payload = LinkedHashMap<String, Any?>().apply {
            put("_id", "local-devicestatus-$timestamp")
            put("created_at", Instant.ofEpochMilli(timestamp).toString())
            put("date", timestamp)
            if (openapsIob.isNotEmpty() || openapsSuggested.isNotEmpty()) {
                put(
                    "openaps",
                    linkedMapOf<String, Any?>().apply {
                        if (openapsIob.isNotEmpty()) put("iob", openapsIob)
                        if (openapsSuggested.isNotEmpty()) put("suggested", openapsSuggested)
                    }
                )
            }
            if (loop.isNotEmpty()) put("loop", loop)
            if (pump.isNotEmpty()) put("pump", pump)
            if (cgm.isNotEmpty()) put("cgm", cgm)
            if (uploader.isNotEmpty()) put("uploader", uploader)
        }

        return payload
    }

    internal fun resolveAnchorTimestamp(
        recentRows: List<TelemetrySampleEntity>,
        latestGlucose: GlucoseSampleEntity?,
        nowTs: Long
    ): Long {
        val telemetryMax = recentRows.maxOfOrNull { it.timestamp } ?: 0L
        val glucoseMax = latestGlucose?.timestamp ?: 0L
        return max(max(telemetryMax, glucoseMax), nowTs)
    }

    private fun numeric(
        latestByKey: Map<String, TelemetrySampleEntity>,
        key: String
    ): Double? = latestByKey[key]?.valueDouble

    private fun numericFromSources(
        latestByKey: Map<String, TelemetrySampleEntity>,
        key: String,
        allowedSources: Set<String>
    ): Double? {
        val sample = latestByKey[key] ?: return null
        if (sample.source !in allowedSources) return null
        return sample.valueDouble
    }
}
