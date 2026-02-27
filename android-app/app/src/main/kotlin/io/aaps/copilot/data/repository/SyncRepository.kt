package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.remote.cloud.CloudGlucosePoint
import io.aaps.copilot.data.remote.cloud.CloudTherapyEvent
import io.aaps.copilot.data.remote.cloud.SyncPushRequest
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
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
        if (settings.nightscoutUrl.isBlank()) {
            auditLogger.warn("nightscout_sync_skipped", mapOf("reason" to "missing_url"))
            return
        }

        val nsApi = apiFactory.nightscoutApi(settings)
        val since = db.syncStateDao().bySource(SOURCE_NIGHTSCOUT)?.lastSyncedTimestamp ?: 0L
        val query = mapOf(
            "count" to "2000",
            "find[date][\$gte]" to since.toString()
        )

        val sgv = nsApi.getSgvEntries(query)
        val glucoseRows = sgv.map { entry ->
            GlucosePoint(
                ts = entry.date,
                valueMmol = UnitConverter.mgdlToMmol(entry.sgv),
                source = "nightscout",
                quality = DataQuality.OK
            ).toEntity()
        }

        val treatments = nsApi.getTreatments(
            mapOf(
                "count" to "2000",
                "find[created_at][\$gte]" to Instant.ofEpochMilli(since).toString()
            )
        ).mapNotNull { treatment ->
            val ts = runCatching { Instant.parse(treatment.createdAt).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val payload = mutableMapOf<String, String>()
            treatment.duration?.let { payload["duration"] = it.toString() }
            treatment.targetTop?.let { payload["targetTop"] = it.toString() }
            treatment.targetBottom?.let { payload["targetBottom"] = it.toString() }
            treatment.reason?.let { payload["reason"] = it }
            treatment.notes?.let { payload["notes"] = it }

            val id = treatment.id ?: "ns-$ts-${treatment.eventType.hashCode()}"
            TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = normalizeEventType(treatment.eventType),
                payloadJson = gson.toJson(payload)
            )
        }

        if (glucoseRows.isNotEmpty()) {
            db.glucoseDao().upsertAll(glucoseRows)
        }
        if (treatments.isNotEmpty()) {
            db.therapyDao().upsertAll(treatments)
        }

        val nextSince = maxOf(
            glucoseRows.maxOfOrNull { it.timestamp } ?: since,
            treatments.maxOfOrNull { it.timestamp } ?: since
        )

        db.syncStateDao().upsert(SyncStateEntity(source = SOURCE_NIGHTSCOUT, lastSyncedTimestamp = nextSince))
        auditLogger.info(
            "nightscout_sync_completed",
            mapOf("since" to since, "nextSince" to nextSince, "glucose" to glucoseRows.size, "treatments" to treatments.size)
        )
    }

    suspend fun pushCloudIncremental() {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            auditLogger.warn("cloud_push_skipped", mapOf("reason" to "missing_cloud_url"))
            return
        }

        val since = db.syncStateDao().bySource(SOURCE_CLOUD_PUSH)?.lastSyncedTimestamp ?: 0L
        val glucoseRows = db.glucoseDao().since(since).map { sample ->
            CloudGlucosePoint(
                ts = sample.timestamp,
                valueMmol = sample.mmol,
                source = sample.source,
                quality = sample.quality
            )
        }
        val therapyRows = db.therapyDao().since(since).map { event ->
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

    suspend fun recentGlucose(limit: Int): List<GlucosePoint> = db.glucoseDao().latest(limit).map { it.toDomain() }

    suspend fun recentTherapyEvents(hoursBack: Int): List<TherapyEvent> {
        val since = System.currentTimeMillis() - hoursBack * 60 * 60 * 1000L
        return db.therapyDao().since(since).map { it.toDomain(gson) }
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

    companion object {
        private const val SOURCE_NIGHTSCOUT = "nightscout"
        private const val SOURCE_CLOUD_PUSH = "cloud_push"
    }
}
