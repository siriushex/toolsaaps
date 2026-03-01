package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.remote.cloud.CloudGlucosePoint
import io.aaps.copilot.data.remote.cloud.CloudTherapyEvent
import io.aaps.copilot.data.remote.cloud.SyncPushRequest
import io.aaps.copilot.data.remote.nightscout.NightscoutDeviceStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.flow.first

class SyncRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    suspend fun syncNightscoutIncremental() {
        val settings = settingsStore.settings.first()
        val nightscoutUrl = settings.resolvedNightscoutUrl()
        if (nightscoutUrl.isBlank()) {
            auditLogger.warn("nightscout_sync_skipped", mapOf("reason" to "missing_url"))
            return
        }

        val nsApi = apiFactory.nightscoutApi(nightscoutUrl, settings.apiSecret)
        val legacySince = db.syncStateDao().bySource(SOURCE_NIGHTSCOUT)?.lastSyncedTimestamp ?: 0L
        val sgvSince = loadCursor(SOURCE_NIGHTSCOUT_SGV, legacySince)
        val treatmentSince = loadCursor(SOURCE_NIGHTSCOUT_TREATMENT_CURSOR, legacySince)
        val deviceStatusSince = loadCursor(SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR, legacySince)

        val sgvQuerySince = (sgvSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        val treatmentQuerySince = (treatmentSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        val deviceStatusQuerySince = (deviceStatusSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)

        val query = mapOf(
            "count" to "2000",
            "find[date][\$gte]" to sgvQuerySince.toString()
        )

        val sgv = nsApi.getSgvEntries(query)
        val glucoseRows = sgv.mapNotNull { entry ->
            val ts = normalizeTimestamp(entry.date) ?: return@mapNotNull null
            GlucosePoint(
                ts = ts,
                valueMmol = UnitConverter.mgdlToMmol(entry.sgv),
                source = "nightscout",
                quality = DataQuality.OK
            ).toEntity()
        }
        val existingNightscoutGlucose = db.glucoseDao()
            .bySourceSince(source = "nightscout", since = sgvQuerySince)
            .associateBy { it.timestamp }
            .toMutableMap()
        val glucoseRowsByTs = glucoseRows
            .associateBy { it.timestamp }
            .toSortedMap()
        val glucoseRowsToInsert = mutableListOf<io.aaps.copilot.data.local.entity.GlucoseSampleEntity>()
        var glucoseSkippedDuplicate = 0
        var glucoseReplaced = 0
        glucoseRowsByTs.forEach { (timestamp, row) ->
            val existing = existingNightscoutGlucose[timestamp]
            if (existing != null && abs(existing.mmol - row.mmol) <= GLUCOSE_REPLACE_EPSILON) {
                glucoseSkippedDuplicate += 1
                return@forEach
            }
            if (existing != null) {
                db.glucoseDao().deleteBySourceAndTimestamp(source = "nightscout", timestamp = timestamp)
                glucoseReplaced += 1
            }
            glucoseRowsToInsert += row
            existingNightscoutGlucose[timestamp] = row
        }

        val treatments = nsApi.getTreatments(
            mapOf(
                "count" to "2000",
                "find[created_at][\$gte]" to Instant.ofEpochMilli(treatmentQuerySince).toString()
            )
        )

        val treatmentRows = mutableListOf<TherapyEventEntity>()
        val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()

        treatments.forEach { treatment ->
            val ts = parseNightscoutTimestamp(treatment.createdAt) ?: return@forEach
            val payload = mutableMapOf<String, String>()
            treatment.duration?.let { payload["duration"] = it.toString() }
            treatment.targetTop?.let { payload["targetTop"] = it.toString() }
            treatment.targetBottom?.let { payload["targetBottom"] = it.toString() }
            treatment.reason?.let { payload["reason"] = it }
            treatment.notes?.let { payload["notes"] = it }
            treatment.carbs?.let { payload["carbs"] = it.toString() }
            treatment.insulin?.let { payload["insulin"] = it.toString() }
            treatment.enteredBy?.let { payload["enteredBy"] = it }
            treatment.absolute?.let { payload["absolute"] = it.toString() }
            treatment.rate?.let { payload["rate"] = it.toString() }
            treatment.percentage?.let { payload["percentage"] = it.toString() }

            val id = treatment.id ?: "ns-$ts-${treatment.eventType.hashCode()}"
            treatmentRows += TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = normalizeEventType(treatment.eventType),
                payloadJson = gson.toJson(payload)
            )
            telemetryRows += TelemetryMetricMapper.fromNightscoutTreatment(
                timestamp = ts,
                source = SOURCE_NIGHTSCOUT_TREATMENT,
                eventType = treatment.eventType,
                payload = payload
            )
        }

        val deviceStatuses = runCatching {
            nsApi.getDeviceStatus(
                mapOf(
                    "count" to "2000",
                    "find[created_at][\$gte]" to Instant.ofEpochMilli(deviceStatusQuerySince).toString()
                )
            )
        }.onFailure {
            auditLogger.warn("nightscout_devicestatus_failed", mapOf("error" to (it.message ?: "unknown")))
        }.getOrDefault(emptyList())

        deviceStatuses.forEach { status ->
            val ts = parseNightscoutTimestamp(status.createdAt)
                ?: normalizeTimestamp(status.date ?: 0L)
                ?: return@forEach
            telemetryRows += telemetryFromDeviceStatus(status, ts)
        }

        if (glucoseRowsToInsert.isNotEmpty()) {
            db.glucoseDao().upsertAll(glucoseRowsToInsert)
        }
        if (treatmentRows.isNotEmpty()) {
            db.therapyDao().upsertAll(treatmentRows)
        }
        if (telemetryRows.isNotEmpty()) {
            db.telemetryDao().upsertAll(telemetryRows.distinctBy { it.id })
        }

        val nextSgvSince = maxOf(sgvSince, glucoseRows.maxOfOrNull { it.timestamp } ?: sgvSince)
        val nextTreatmentSince = maxOf(treatmentSince, treatmentRows.maxOfOrNull { it.timestamp } ?: treatmentSince)
        val nextDeviceStatusSince = maxOf(
            deviceStatusSince,
            deviceStatuses.maxOfOrNull {
                parseNightscoutTimestamp(it.createdAt)
                    ?: normalizeTimestamp(it.date ?: 0L)
                    ?: 0L
            } ?: deviceStatusSince
        )
        val nextSince = maxOf(nextSgvSince, nextTreatmentSince, nextDeviceStatusSince)

        db.syncStateDao().upsert(
            SyncStateEntity(source = SOURCE_NIGHTSCOUT_SGV, lastSyncedTimestamp = nextSgvSince)
        )
        db.syncStateDao().upsert(
            SyncStateEntity(source = SOURCE_NIGHTSCOUT_TREATMENT_CURSOR, lastSyncedTimestamp = nextTreatmentSince)
        )
        db.syncStateDao().upsert(
            SyncStateEntity(source = SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR, lastSyncedTimestamp = nextDeviceStatusSince)
        )
        db.syncStateDao().upsert(
            SyncStateEntity(source = SOURCE_NIGHTSCOUT, lastSyncedTimestamp = nextSince)
        )
        auditLogger.info(
            "nightscout_sync_completed",
            mapOf(
                "since" to legacySince,
                "nextSince" to nextSince,
                "sgvSince" to sgvSince,
                "treatmentSince" to treatmentSince,
                "deviceStatusSince" to deviceStatusSince,
                "sgvQuerySince" to sgvQuerySince,
                "treatmentQuerySince" to treatmentQuerySince,
                "deviceStatusQuerySince" to deviceStatusQuerySince,
                "glucoseFetched" to glucoseRows.size,
                "glucoseInserted" to glucoseRowsToInsert.size,
                "glucoseSkippedDuplicate" to glucoseSkippedDuplicate,
                "glucoseReplaced" to glucoseReplaced,
                "treatments" to treatmentRows.size,
                "telemetry" to telemetryRows.size,
                "deviceStatus" to deviceStatuses.size
            )
        )
    }

    suspend fun pushCloudIncremental() {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            auditLogger.warn("cloud_push_skipped", mapOf("reason" to "missing_cloud_url"))
            return
        }

        val since = db.syncStateDao().bySource(SOURCE_CLOUD_PUSH)?.lastSyncedTimestamp ?: 0L
        val glucoseRows = GlucoseSanitizer.filterEntities(db.glucoseDao().since(since)).map { sample ->
            CloudGlucosePoint(
                ts = sample.timestamp,
                valueMmol = sample.mmol,
                source = sample.source,
                quality = sample.quality
            )
        }
        val therapyRows = TherapySanitizer.filterEntities(db.therapyDao().since(since)).map { event ->
            CloudTherapyEvent(
                id = event.id,
                ts = event.timestamp,
                type = event.type,
                payload = payloadFromJson(event.payloadJson)
            )
        }

        if (glucoseRows.isEmpty() && therapyRows.isEmpty()) {
            auditLogger.info("cloud_push_skipped", mapOf("reason" to "no_local_delta", "since" to since))
            return
        }

        runCatching {
            apiFactory.cloudApi(settings).pushSync(
                SyncPushRequest(
                    glucose = glucoseRows,
                    therapyEvents = therapyRows
                )
            )
        }.onSuccess { response ->
            val nextSince = maxOf(since, response.nextSince)
            db.syncStateDao().upsert(
                SyncStateEntity(
                    source = SOURCE_CLOUD_PUSH,
                    lastSyncedTimestamp = nextSince
                )
            )
            auditLogger.info(
                "cloud_push_completed",
                mapOf(
                    "since" to since,
                    "nextSince" to nextSince,
                    "sentGlucose" to glucoseRows.size,
                    "sentTherapyEvents" to therapyRows.size,
                    "acceptedGlucose" to response.acceptedGlucose,
                    "acceptedTherapyEvents" to response.acceptedTherapyEvents
                )
            )
        }.onFailure {
            auditLogger.warn(
                "cloud_push_failed",
                mapOf(
                    "since" to since,
                    "error" to (it.message ?: "unknown")
                )
            )
        }
    }

    suspend fun recentGlucose(limit: Int): List<GlucosePoint> =
        GlucoseSanitizer.filterEntities(db.glucoseDao().latest(limit)).map { it.toDomain() }

    suspend fun recentTherapyEvents(hoursBack: Int): List<TherapyEvent> {
        val since = System.currentTimeMillis() - hoursBack * 60 * 60 * 1000L
        return TherapySanitizer.filterEntities(db.therapyDao().since(since)).map { it.toDomain(gson) }
    }

    private fun normalizeEventType(eventType: String): String {
        return when (eventType.lowercase()) {
            "temporary target" -> "temp_target"
            "carb correction" -> "carbs"
            "meal bolus" -> "meal_bolus"
            "correction bolus" -> "correction_bolus"
            else -> eventType.lowercase().replace(" ", "_")
        }
    }

    private fun payloadFromJson(raw: String): Map<String, String> {
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return runCatching {
            gson.fromJson<Map<String, String>>(raw, mapType) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseNightscoutTimestamp(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val parsed = runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
            ?: trimmed.toLongOrNull()?.let { ts -> if (ts < 10_000_000_000L) ts * 1000L else ts }
        return normalizeTimestamp(parsed ?: return null)
    }

    private fun normalizeTimestamp(raw: Long): Long? {
        if (raw <= 0L) return null
        val now = System.currentTimeMillis()
        val millis = if (raw < 10_000_000_000L) raw * 1000L else raw
        return if (millis > now + MAX_FUTURE_TIMESTAMP_SKEW_MS) now else millis
    }

    private suspend fun loadCursor(source: String, fallback: Long): Long {
        return db.syncStateDao().bySource(source)?.lastSyncedTimestamp ?: fallback
    }

    private fun telemetryFromDeviceStatus(
        status: NightscoutDeviceStatus,
        timestamp: Long
    ): List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity> {
        val flattened = linkedMapOf<String, String>()
        TelemetryMetricMapper.flattenAny("openaps", status.openaps, flattened)
        TelemetryMetricMapper.flattenAny("pump", status.pump, flattened)
        TelemetryMetricMapper.flattenAny("uploader", status.uploader, flattened)
        return TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
            timestamp = timestamp,
            source = SOURCE_NIGHTSCOUT_DEVICESTATUS,
            flattened = flattened
        )
    }

    companion object {
        private const val SOURCE_NIGHTSCOUT = "nightscout"
        private const val SOURCE_CLOUD_PUSH = "cloud_push"
        private const val SOURCE_NIGHTSCOUT_TREATMENT = "nightscout_treatment"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS = "nightscout_devicestatus"
        private const val SOURCE_NIGHTSCOUT_SGV = "nightscout_sgv_cursor"
        private const val SOURCE_NIGHTSCOUT_TREATMENT_CURSOR = "nightscout_treatment_cursor"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR = "nightscout_devicestatus_cursor"
        private const val NS_CURSOR_OVERLAP_MS = 5 * 60_000L
        private const val GLUCOSE_REPLACE_EPSILON = 0.01
        private const val MAX_FUTURE_TIMESTAMP_SKEW_MS = 24 * 60 * 60 * 1000L
    }
}
