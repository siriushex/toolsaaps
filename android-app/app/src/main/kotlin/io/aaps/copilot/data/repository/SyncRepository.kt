package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.isCopilotCloudBackendEndpoint
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
import kotlin.math.roundToInt
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
        val nowTs = System.currentTimeMillis()
        val legacySince = db.syncStateDao().bySource(SOURCE_NIGHTSCOUT)?.lastSyncedTimestamp ?: 0L
        val sgvSince = loadCursor(SOURCE_NIGHTSCOUT_SGV, legacySince)
        val treatmentSince = loadCursor(SOURCE_NIGHTSCOUT_TREATMENT_CURSOR, legacySince)
        val deviceStatusSince = loadCursor(SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR, legacySince)
        val bootstrapAttemptTs = loadCursor(SOURCE_NIGHTSCOUT_TREATMENT_BOOTSTRAP, 0L)
        val insulinLikeCount = db.therapyDao().countInsulinLikeSince(nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS)
        val shouldBootstrapTreatmentHistory =
            insulinLikeCount < THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS &&
                (nowTs - bootstrapAttemptTs >= THERAPY_BOOTSTRAP_RETRY_MS)

        val sgvQuerySince = (sgvSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        val treatmentQuerySince = if (shouldBootstrapTreatmentHistory) {
            minOf(
                (treatmentSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L),
                nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS
            )
        } else {
            (treatmentSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        }
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

        val treatmentsByDate = runCatching {
            nsApi.getTreatments(
                mapOf(
                    "count" to "2000",
                    "find[date][\$gte]" to treatmentQuerySince.toString()
                )
            )
        }.onFailure {
            auditLogger.warn(
                "nightscout_treatments_fetch_failed",
                mapOf(
                    "mode" to "date",
                    "error" to (it.message ?: "unknown")
                )
            )
        }.getOrDefault(emptyList())
        val treatmentsByCreatedAt = runCatching {
            nsApi.getTreatments(
                mapOf(
                    "count" to "2000",
                    "find[created_at][\$gte]" to Instant.ofEpochMilli(treatmentQuerySince).toString()
                )
            )
        }.onFailure {
            auditLogger.warn(
                "nightscout_treatments_fetch_failed",
                mapOf(
                    "mode" to "created_at",
                    "error" to (it.message ?: "unknown")
                )
            )
        }.getOrDefault(emptyList())
        val treatments = (treatmentsByDate + treatmentsByCreatedAt)
            .distinctBy { treatment ->
                val normalizedTs = parseNightscoutTimestamp(
                    createdAt = treatment.createdAt,
                    date = treatment.date,
                    mills = treatment.mills
                ) ?: 0L
                "${treatment.id.orEmpty()}|${treatment.eventType.orEmpty()}|$normalizedTs|${treatment.carbs ?: 0.0}|${treatment.insulin ?: 0.0}"
            }

        val treatmentRows = mutableListOf<TherapyEventEntity>()
        val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
        var insulinLikeFetched = 0
        var carbsFetched = 0

        treatments.forEach { treatment ->
            val ts = parseNightscoutTimestamp(
                createdAt = treatment.createdAt,
                date = treatment.date,
                mills = treatment.mills
            ) ?: return@forEach
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
            treatment.eventType?.let { payload["eventType"] = it }
            treatment.date?.let { payload["date"] = it.toString() }
            treatment.mills?.let { payload["mills"] = it.toString() }
            treatment.createdAt?.let { payload["createdAt"] = it }

            val normalizedType = normalizeEventType(
                eventType = treatment.eventType,
                payload = payload
            )

            val id = treatment.id ?: "ns-$ts-${normalizedType.hashCode()}"
            treatmentRows += TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = normalizedType,
                payloadJson = gson.toJson(payload)
            )
            if (normalizedType == "meal_bolus" || normalizedType == "correction_bolus" || normalizedType == "insulin") {
                insulinLikeFetched += 1
            }
            if (normalizedType == "carbs" || normalizedType == "meal_bolus") {
                carbsFetched += 1
            }
            telemetryRows += TelemetryMetricMapper.fromNightscoutTreatment(
                timestamp = ts,
                source = SOURCE_NIGHTSCOUT_TREATMENT,
                eventType = treatment.eventType ?: normalizedType,
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
            val ts = parseNightscoutTimestamp(
                createdAt = status.createdAt,
                date = status.date,
                mills = null
            )
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
        val inferredInsulinCount = inferInsulinEventsFromIob(nowTs)

        val nextSgvSince = maxOf(sgvSince, glucoseRows.maxOfOrNull { it.timestamp } ?: sgvSince)
        val nextTreatmentSince = maxOf(treatmentSince, treatmentRows.maxOfOrNull { it.timestamp } ?: treatmentSince)
        val nextDeviceStatusSince = maxOf(
            deviceStatusSince,
            deviceStatuses.maxOfOrNull {
                parseNightscoutTimestamp(
                    createdAt = it.createdAt,
                    date = it.date,
                    mills = null
                )
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
        if (shouldBootstrapTreatmentHistory) {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    source = SOURCE_NIGHTSCOUT_TREATMENT_BOOTSTRAP,
                    lastSyncedTimestamp = nowTs
                )
            )
            auditLogger.info(
                "nightscout_treatment_bootstrap_attempted",
                mapOf(
                    "insulinLikeCount" to insulinLikeCount,
                    "bootstrapAttemptTs" to bootstrapAttemptTs,
                    "querySince" to treatmentQuerySince
                )
            )
        }
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
                "treatmentsFetchedByDate" to treatmentsByDate.size,
                "treatmentsFetchedByCreatedAt" to treatmentsByCreatedAt.size,
                "deviceStatusQuerySince" to deviceStatusQuerySince,
                "insulinLikeLocal30d" to insulinLikeCount,
                "treatmentBootstrap" to shouldBootstrapTreatmentHistory,
                "glucoseFetched" to glucoseRows.size,
                "glucoseInserted" to glucoseRowsToInsert.size,
                "glucoseSkippedDuplicate" to glucoseSkippedDuplicate,
                "glucoseReplaced" to glucoseReplaced,
                "treatments" to treatmentRows.size,
                "treatmentsInsulinLike" to insulinLikeFetched,
                "treatmentsCarbLike" to carbsFetched,
                "treatmentsInsulinInferredFromIob" to inferredInsulinCount,
                "telemetry" to telemetryRows.size,
                "deviceStatus" to deviceStatuses.size
            )
        )
    }

    suspend fun pushCloudIncremental() {
        val settings = settingsStore.settings.first()
        if (!isCopilotCloudBackendEndpoint(settings.cloudBaseUrl)) {
            auditLogger.info("cloud_push_skipped", mapOf("reason" to "cloud_backend_unavailable"))
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

    private fun normalizeEventType(
        eventType: String?,
        payload: Map<String, String> = emptyMap()
    ): String {
        val normalized = eventType
            .orEmpty()
            .trim()
            .lowercase()
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
        val mapped = when (normalized) {
            "temporary target" -> "temp_target"
            "carb correction" -> "carbs"
            "meal bolus" -> "meal_bolus"
            "correction bolus" -> "correction_bolus"
            "site change", "cannula change", "infusion set change", "set change", "pump site change" ->
                "infusion_set_change"
            "sensor change", "cgm sensor change", "sensor start" -> "sensor_change"
            "insulin change", "reservoir change", "cartridge change", "pump refill", "insulin refill" ->
                "insulin_refill"
            "pump battery change", "battery change", "battery replacement", "pump battery replacement" ->
                "pump_battery_change"
            else -> normalized.replace(" ", "_")
        }
        if (mapped.isNotBlank()) return mapped

        val carbs = payload["carbs"]?.replace(",", ".")?.toDoubleOrNull()
        val insulin = payload["insulin"]?.replace(",", ".")?.toDoubleOrNull()
            ?: payload["units"]?.replace(",", ".")?.toDoubleOrNull()
            ?: payload["bolusUnits"]?.replace(",", ".")?.toDoubleOrNull()
        val hasTarget = payload.containsKey("targetTop") || payload.containsKey("targetBottom")
        return when {
            carbs != null && carbs > 0.0 && insulin != null && insulin > 0.0 -> "meal_bolus"
            insulin != null && insulin > 0.0 -> "correction_bolus"
            carbs != null && carbs > 0.0 -> "carbs"
            hasTarget -> "temp_target"
            else -> "treatment"
        }
    }

    private fun payloadFromJson(raw: String): Map<String, String> {
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return runCatching {
            gson.fromJson<Map<String, String>>(raw, mapType) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseNightscoutTimestamp(
        createdAt: String?,
        date: Long?,
        mills: Long?
    ): Long? {
        val createdAtMillis = createdAt
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                    ?: raw.toLongOrNull()?.let { ts -> if (ts < 10_000_000_000L) ts * 1000L else ts }
            }
        return normalizeTimestamp(
            createdAtMillis
                ?: normalizeTimestamp(mills ?: 0L)
                ?: normalizeTimestamp(date ?: 0L)
                ?: return null
        )
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

    private suspend fun inferInsulinEventsFromIob(nowTs: Long): Int {
        val since = nowTs - IOB_INFERENCE_LOOKBACK_MS
        val iobRows = db.telemetryDao()
            .sinceByKeys(
                since = since,
                keys = listOf("iob_units", "raw_iob")
            )
            .filter {
                it.source in IOB_INFERENCE_ALLOWED_SOURCES &&
                    it.valueDouble != null
            }
            .sortedBy { it.timestamp }
        if (iobRows.size < 2) return 0

        val iobByTs = linkedMapOf<Long, Double>()
        iobRows.forEach { sample ->
            val value = sample.valueDouble ?: return@forEach
            if (value < IOB_INFERENCE_MIN_IOB || value > IOB_INFERENCE_MAX_IOB) return@forEach
            val existing = iobByTs[sample.timestamp]
            if (existing == null || abs(value) > abs(existing)) {
                iobByTs[sample.timestamp] = value
            }
        }
        val points = iobByTs.entries
            .map { it.key to it.value }
            .sortedBy { it.first }
        if (points.size < 2) return 0

        val existingInsulin = db.therapyDao().since(since)
            .filter { it.type in IOB_INFERENCE_BLOCKING_TYPES }
            .sortedBy { it.timestamp }
        val candidates = mutableListOf<TherapyEventEntity>()

        points.zipWithNext { prev, curr ->
            val dtMin = (curr.first - prev.first) / 60_000.0
            if (dtMin < IOB_INFERENCE_MIN_DT_MIN || dtMin > IOB_INFERENCE_MAX_DT_MIN) return@zipWithNext

            val delta = curr.second - prev.second
            if (delta < IOB_INFERENCE_MIN_DELTA_UNITS) return@zipWithNext
            val inferredUnits = delta.coerceAtMost(IOB_INFERENCE_MAX_DELTA_UNITS)
            if (inferredUnits <= 0.0) return@zipWithNext

            val ts = curr.first
            val hasNearbyInsulin = existingInsulin.any { row ->
                abs(row.timestamp - ts) <= IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS
            } || candidates.any { row ->
                abs(row.timestamp - ts) <= IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS
            }
            if (hasNearbyInsulin) return@zipWithNext

            val bucket = ts / IOB_INFERENCE_BUCKET_MS
            val unitsRounded = (inferredUnits * 100.0).roundToInt()
            val id = "iob-inf-$bucket-$unitsRounded"
            val payload = mapOf(
                "insulin" to String.format("%.3f", inferredUnits),
                "inferred" to "true",
                "method" to "iob_jump",
                "source" to "aaps_ns_iob",
                "iobPrev" to String.format("%.3f", prev.second),
                "iobNow" to String.format("%.3f", curr.second),
                "deltaIob" to String.format("%.3f", delta),
                "dtMin" to String.format("%.2f", dtMin),
                "confidence" to "0.40"
            )
            candidates += TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = "correction_bolus",
                payloadJson = gson.toJson(payload)
            )
        }

        if (candidates.isEmpty()) return 0
        db.therapyDao().upsertAll(candidates)
        auditLogger.info(
            "nightscout_iob_insulin_inferred",
            mapOf(
                "since" to since,
                "samples" to points.size,
                "created" to candidates.size
            )
        )
        return candidates.size
    }

    companion object {
        private const val SOURCE_NIGHTSCOUT = "nightscout"
        private const val SOURCE_CLOUD_PUSH = "cloud_push"
        private const val SOURCE_NIGHTSCOUT_TREATMENT = "nightscout_treatment"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS = "nightscout_devicestatus"
        private const val SOURCE_AAPS_BROADCAST = "aaps_broadcast"
        private const val SOURCE_XDRIP_BROADCAST = "xdrip_broadcast"
        private const val SOURCE_LOCAL_BROADCAST = "local_broadcast"
        private const val SOURCE_NIGHTSCOUT_SGV = "nightscout_sgv_cursor"
        private const val SOURCE_NIGHTSCOUT_TREATMENT_CURSOR = "nightscout_treatment_cursor"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR = "nightscout_devicestatus_cursor"
        private const val SOURCE_NIGHTSCOUT_TREATMENT_BOOTSTRAP = "nightscout_treatment_bootstrap_cursor"
        private const val NS_CURSOR_OVERLAP_MS = 5 * 60_000L
        private const val THERAPY_BOOTSTRAP_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000
        private const val THERAPY_BOOTSTRAP_RETRY_MS = 12L * 60 * 60 * 1000
        private const val THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS = 10
        private const val GLUCOSE_REPLACE_EPSILON = 0.01
        private const val MAX_FUTURE_TIMESTAMP_SKEW_MS = 24 * 60 * 60 * 1000L
        private const val IOB_INFERENCE_LOOKBACK_MS = 24L * 60 * 60 * 1000L
        private const val IOB_INFERENCE_MIN_DELTA_UNITS = 0.20
        private const val IOB_INFERENCE_MAX_DELTA_UNITS = 4.0
        private const val IOB_INFERENCE_MIN_IOB = -1.0
        private const val IOB_INFERENCE_MAX_IOB = 30.0
        private const val IOB_INFERENCE_MIN_DT_MIN = 1.0
        private const val IOB_INFERENCE_MAX_DT_MIN = 15.0
        private const val IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS = 10 * 60_000L
        private const val IOB_INFERENCE_BUCKET_MS = 5 * 60_000L
        private val IOB_INFERENCE_ALLOWED_SOURCES = setOf(
            SOURCE_AAPS_BROADCAST,
            SOURCE_XDRIP_BROADCAST,
            SOURCE_LOCAL_BROADCAST,
            SOURCE_NIGHTSCOUT_DEVICESTATUS,
            SOURCE_NIGHTSCOUT_TREATMENT
        )
        private val IOB_INFERENCE_BLOCKING_TYPES = setOf(
            "insulin",
            "bolus",
            "correction_bolus",
            "meal_bolus"
        )
    }
}
