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
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatment
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.util.UnitConverter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SyncRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {
    @Volatile
    private var nightscoutConsecutiveFailures = 0
    @Volatile
    private var nightscoutBackoffUntilTs = 0L
    private val lastNightscoutSyncAttemptTs = AtomicLong(0L)
    private val lastCloudPushAttemptTs = AtomicLong(0L)

    suspend fun syncNightscoutIncremental() {
        val settings = settingsStore.settings.first()
        val nightscoutUrl = settings.resolvedNightscoutUrl()
        val nowTs = System.currentTimeMillis()
        if (nightscoutUrl.isBlank()) {
            auditLogger.warn("nightscout_sync_skipped", mapOf("reason" to "missing_url"))
            return
        }
        if (nowTs < nightscoutBackoffUntilTs) {
            val remainingMs = nightscoutBackoffUntilTs - nowTs
            auditLogger.warn(
                "nightscout_sync_skipped",
                mapOf(
                    "reason" to "failure_backoff",
                    "remainingMs" to remainingMs,
                    "backoffUntilTs" to nightscoutBackoffUntilTs,
                    "consecutiveFailures" to nightscoutConsecutiveFailures
                )
            )
            return
        }
        val loopbackUrl = isLoopbackUrl(nightscoutUrl)
        if (loopbackUrl && !awaitLoopbackReachable(nightscoutUrl)) {
            auditLogger.warn(
                "nightscout_sync_skipped",
                mapOf(
                    "reason" to "loopback_unreachable",
                    "url" to sanitizedNightscoutUrl(nightscoutUrl)
                )
            )
            return
        }
        if (isWithinThrottleWindow(lastNightscoutSyncAttemptTs, nowTs, NIGHTSCOUT_MIN_SYNC_INTERVAL_MS)) {
            auditLogger.infoThrottled(
                throttleKey = "nightscout_sync_skipped:min_interval",
                intervalMs = NIGHTSCOUT_MIN_SYNC_INTERVAL_MS,
                message = "nightscout_sync_skipped",
                metadata = mapOf(
                    "reason" to "min_interval",
                    "intervalMs" to NIGHTSCOUT_MIN_SYNC_INTERVAL_MS
                )
            )
            return
        }

        val nsApi = apiFactory.nightscoutApi(nightscoutUrl, settings.apiSecret)
        val degradedMode = nightscoutConsecutiveFailures > 0
        val legacySince = db.syncStateDao().bySource(SOURCE_NIGHTSCOUT)?.lastSyncedTimestamp ?: 0L
        val sgvSince = loadCursor(SOURCE_NIGHTSCOUT_SGV, legacySince)
        val treatmentSince = loadCursor(SOURCE_NIGHTSCOUT_TREATMENT_CURSOR, legacySince)
        val deviceStatusSince = maxOf(
            loadCursor(SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR, legacySince),
            nowTs - DEVICESTATUS_MAX_LOOKBACK_MS
        )
        val bootstrapAttemptTs = loadCursor(SOURCE_NIGHTSCOUT_TREATMENT_BOOTSTRAP, 0L)
        val treatmentCreatedAtBackfillTs = loadCursor(SOURCE_NIGHTSCOUT_TREATMENT_CREATED_AT_BACKFILL, 0L)
        val insulinLikeCountRaw = db.therapyDao().countInsulinLikeSince(nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS)
        val insulinLikeCount = db.therapyDao().countInsulinLikeForBootstrapSince(nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS)
        val needsHistoricalInsulinRecovery = insulinLikeCount < THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS
        val shouldBootstrapTreatmentHistory =
            needsHistoricalInsulinRecovery &&
                (
                    nowTs - bootstrapAttemptTs >= THERAPY_BOOTSTRAP_RETRY_MS ||
                        (insulinLikeCount == 0 && nowTs - treatmentCreatedAtBackfillTs >= TREATMENT_CREATED_AT_BACKFILL_INTERVAL_MS)
                    )
        val shouldRunTreatmentCreatedAtBackfill =
            shouldBootstrapTreatmentHistory ||
                (nowTs - treatmentCreatedAtBackfillTs >= TREATMENT_CREATED_AT_BACKFILL_INTERVAL_MS)

        val sgvQuerySince = (sgvSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        val treatmentQuerySince = if (shouldBootstrapTreatmentHistory) {
            minOf(
                (treatmentSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L),
                nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS
            )
        } else {
            (treatmentSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        }
        val treatmentCreatedAtQuerySince = if (needsHistoricalInsulinRecovery) {
            (nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS).coerceAtLeast(0L)
        } else {
            treatmentQuerySince
        }
        val deviceStatusQuerySince = (deviceStatusSince - NS_CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        auditLogger.info(
            "nightscout_sync_started",
            mapOf(
                "legacySince" to legacySince,
                "sgvSince" to sgvSince,
                "treatmentSince" to treatmentSince,
                "deviceStatusSince" to deviceStatusSince,
                "sgvQuerySince" to sgvQuerySince,
                "treatmentQuerySince" to treatmentQuerySince,
                "treatmentCreatedAtQuerySince" to treatmentCreatedAtQuerySince,
                "deviceStatusQuerySince" to deviceStatusQuerySince,
                "degradedMode" to degradedMode,
                "needsHistoricalInsulinRecovery" to needsHistoricalInsulinRecovery
            )
        )

        val sgvCount = resolveIncrementalFetchCount(
            bootstrap = shouldBootstrapTreatmentHistory,
            cursorSince = sgvSince,
            nowTs = nowTs
        )
        val treatmentCount = resolveIncrementalFetchCount(
            bootstrap = shouldBootstrapTreatmentHistory,
            cursorSince = treatmentSince,
            nowTs = nowTs
        )
        val treatmentCreatedAtCount = if (needsHistoricalInsulinRecovery) {
            NS_FETCH_COUNT_BOOTSTRAP
        } else {
            treatmentCount
        }
        val query = mapOf(
            "count" to sgvCount.toString(),
            "find[date][\$gte]" to sgvQuerySince.toString()
        )

        val sgvFetchStartedAt = System.currentTimeMillis()
        var sgvFetchTimedOut = false
        val sgvResult = fetchWithLoopbackRetry(
            loopback = loopbackUrl,
            timeoutMs = NS_SGV_FETCH_TIMEOUT_MS
        ) {
            nsApi.getSgvEntries(query)
        }
        val sgv = if (sgvResult.isSuccess) {
            sgvResult.getOrNull().also {
                if (it == null) {
                    sgvFetchTimedOut = true
                    auditLogger.warn(
                        "nightscout_sgv_fetch_failed",
                        mapOf(
                            "error" to "timeout",
                            "timedOut" to true,
                            "querySince" to sgvQuerySince
                        )
                    )
                }
            } ?: emptyList()
        } else {
            val error = sgvResult.exceptionOrNull()
            error?.rethrowIfCancellation()
            auditLogger.warn(
                "nightscout_sgv_fetch_failed",
                mapOf(
                    "error" to (error?.message ?: "unknown"),
                    "timedOut" to false,
                    "querySince" to sgvQuerySince
                )
            )
            emptyList()
        }
        val sgvFetchDurationMs = System.currentTimeMillis() - sgvFetchStartedAt
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
        val glucoseInputTelemetryRows = glucoseRowsToInsert.flatMap { row ->
            listOf(
                io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                    id = "tm-${row.source}-glucose_input_key-${row.timestamp}",
                    timestamp = row.timestamp,
                    source = row.source,
                    key = "glucose_input_key",
                    valueDouble = null,
                    valueText = "sgv",
                    unit = null,
                    quality = "OK"
                ),
                io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                    id = "tm-${row.source}-glucose_input_kind-${row.timestamp}",
                    timestamp = row.timestamp,
                    source = row.source,
                    key = "glucose_input_kind",
                    valueDouble = null,
                    valueText = "sgv",
                    unit = null,
                    quality = "OK"
                )
            )
        }

        val treatmentsByDateFetchStartedAt = System.currentTimeMillis()
        var treatmentsByDateFetchTimedOut = false
        val treatmentsByDateResult = fetchWithLoopbackRetry(
            loopback = loopbackUrl,
            timeoutMs = NS_TREATMENT_FETCH_TIMEOUT_MS
        ) {
            nsApi.getTreatments(
                mapOf(
                    "count" to treatmentCount.toString(),
                    "find[date][\$gte]" to treatmentQuerySince.toString()
                )
            )
        }
        val treatmentsByDate = if (treatmentsByDateResult.isSuccess) {
            treatmentsByDateResult.getOrNull().also {
                if (it == null) {
                    treatmentsByDateFetchTimedOut = true
                    auditLogger.warn(
                        "nightscout_treatments_fetch_failed",
                        mapOf(
                            "mode" to "date",
                            "error" to "timeout",
                            "timedOut" to true,
                            "querySince" to treatmentQuerySince
                        )
                    )
                }
            } ?: emptyList()
        } else {
            val error = treatmentsByDateResult.exceptionOrNull()
            error?.rethrowIfCancellation()
            auditLogger.warn(
                "nightscout_treatments_fetch_failed",
                mapOf(
                    "mode" to "date",
                    "error" to (error?.message ?: "unknown"),
                    "timedOut" to false,
                    "querySince" to treatmentQuerySince
                )
            )
            emptyList()
        }
        val treatmentsByDateFetchDurationMs = System.currentTimeMillis() - treatmentsByDateFetchStartedAt
        val shouldFetchTreatmentsByCreatedAt = !degradedMode && (shouldRunTreatmentCreatedAtBackfill || treatmentsByDate.isEmpty())
        var treatmentsByCreatedAtFetchTimedOut = false
        var treatmentsByCreatedAtFetchDurationMs = 0L
        var treatmentsByCreatedAtFetched = false
        val treatmentsByCreatedAt = if (shouldFetchTreatmentsByCreatedAt) {
            treatmentsByCreatedAtFetched = true
            val startedAt = System.currentTimeMillis()
            val resultCatching = fetchWithLoopbackRetry(
                loopback = loopbackUrl,
                timeoutMs = NS_TREATMENT_FETCH_TIMEOUT_MS
            ) {
                nsApi.getTreatments(
                    mapOf(
                        "count" to treatmentCreatedAtCount.toString(),
                        "find[created_at][\$gte]" to Instant.ofEpochMilli(treatmentCreatedAtQuerySince).toString()
                    )
                )
            }
            val result = if (resultCatching.isSuccess) {
                resultCatching.getOrNull().also {
                    if (it == null) {
                        treatmentsByCreatedAtFetchTimedOut = true
                        auditLogger.warn(
                            "nightscout_treatments_fetch_failed",
                            mapOf(
                                "mode" to "created_at",
                                "error" to "timeout",
                                "timedOut" to true,
                                "querySince" to treatmentCreatedAtQuerySince
                            )
                        )
                    }
                } ?: emptyList()
            } else {
                val error = resultCatching.exceptionOrNull()
                error?.rethrowIfCancellation()
                auditLogger.warn(
                    "nightscout_treatments_fetch_failed",
                    mapOf(
                        "mode" to "created_at",
                        "error" to (error?.message ?: "unknown"),
                        "timedOut" to false,
                        "querySince" to treatmentCreatedAtQuerySince
                    )
                )
                emptyList()
            }
            treatmentsByCreatedAtFetchDurationMs = System.currentTimeMillis() - startedAt
            result
        } else {
            emptyList()
        }
        val treatments = (treatmentsByDate + treatmentsByCreatedAt)
            .distinctBy { treatment ->
                val normalizedTs = parseNightscoutTimestamp(
                    createdAt = treatment.createdAt,
                    date = treatment.date,
                    mills = treatment.mills
                ) ?: 0L
                "${treatment.id.orEmpty()}|${treatment.eventType.orEmpty()}|$normalizedTs|${treatment.carbs ?: 0.0}|${treatment.insulin ?: 0.0}"
            }
        val existingTherapyById = db.therapyDao()
            .since(treatmentQuerySince)
            .associateBy { it.id }

        val treatmentRows = mutableListOf<TherapyEventEntity>()
        val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
        var insulinLikeFetched = 0
        var carbsFetched = 0
        var treatmentsSkippedByClientWindow = 0
        var treatmentsDowngradedFromBolus = 0

        treatments.forEach { treatment ->
            val ts = parseNightscoutTimestamp(
                createdAt = treatment.createdAt,
                date = treatment.date,
                mills = treatment.mills
            ) ?: return@forEach
            // Server-side find filters can occasionally return wider windows.
            // Keep a strict client-side guard to prevent heavy re-processing of stale treatments.
            if (ts < treatmentQuerySince) {
                treatmentsSkippedByClientWindow += 1
                return@forEach
            }
            val payload = buildNightscoutTreatmentPayloadStatic(
                treatment = treatment,
                source = SOURCE_NIGHTSCOUT_TREATMENT
            )

            val id = treatment.id ?: "ns-$ts-${treatment.eventType.orEmpty().hashCode()}"
            val existingPayload = existingTherapyById[id]?.payloadJson?.let(::payloadFromJson).orEmpty()
            val mergedPayload = mergePayloadWithExisting(
                incoming = payload,
                existing = existingPayload
            )
            val normalizedType = normalizeEventType(
                eventType = treatment.eventType,
                payload = mergedPayload
            )
            val eventTypeNormalized = treatment.eventType
                .orEmpty()
                .trim()
                .lowercase()
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
            if (
                (eventTypeNormalized == "correction bolus" || eventTypeNormalized == "meal bolus") &&
                normalizedType !in setOf("correction_bolus", "meal_bolus")
            ) {
                treatmentsDowngradedFromBolus += 1
            }

            treatmentRows += TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = normalizedType,
                payloadJson = gson.toJson(mergedPayload)
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
                payload = mergedPayload
            )
        }

        val deviceStatusCount = if (shouldBootstrapTreatmentHistory) NS_FETCH_COUNT_BOOTSTRAP else NS_DEVICESTATUS_FETCH_COUNT_INCREMENTAL
        var deviceStatusFetchTimedOut = false
        val deviceStatuses: List<NightscoutDeviceStatus>
        val deviceStatusFetchDurationMs: Long
        if (degradedMode) {
            deviceStatuses = emptyList()
            deviceStatusFetchDurationMs = 0L
        } else {
            val deviceStatusFetchStartedAt = System.currentTimeMillis()
            val deviceStatusResult = fetchWithLoopbackRetry(
                loopback = loopbackUrl,
                timeoutMs = NS_DEVICESTATUS_FETCH_TIMEOUT_MS
            ) {
                nsApi.getDeviceStatus(
                    mapOf(
                        "count" to deviceStatusCount.toString(),
                        "find[date][\$gte]" to deviceStatusQuerySince.toString()
                    )
                )
            }
            deviceStatuses = if (deviceStatusResult.isSuccess) {
                deviceStatusResult.getOrNull().also {
                    if (it == null) {
                        deviceStatusFetchTimedOut = true
                        auditLogger.warn(
                            "nightscout_devicestatus_failed",
                            mapOf(
                                "error" to "timeout",
                                "timedOut" to true,
                                "querySince" to deviceStatusQuerySince
                            )
                        )
                    }
                } ?: emptyList()
            } else {
                val error = deviceStatusResult.exceptionOrNull()
                error?.rethrowIfCancellation()
                auditLogger.warn(
                    "nightscout_devicestatus_failed",
                    mapOf(
                        "error" to (error?.message ?: "unknown"),
                        "timedOut" to false,
                        "querySince" to deviceStatusQuerySince
                    )
                )
                emptyList()
            }
            deviceStatusFetchDurationMs = System.currentTimeMillis() - deviceStatusFetchStartedAt
        }

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
        val repairedTreatmentTypes = repairNightscoutTherapyTypes(
            since = if (shouldBootstrapTreatmentHistory) {
                nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS
            } else {
                treatmentQuerySince
            }
        )
        if (telemetryRows.isNotEmpty()) {
            db.telemetryDao().upsertAll((telemetryRows + glucoseInputTelemetryRows).distinctBy { it.id })
        } else if (glucoseInputTelemetryRows.isNotEmpty()) {
            db.telemetryDao().upsertAll(glucoseInputTelemetryRows)
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
                    "insulinLikeCountRaw" to insulinLikeCountRaw,
                    "bootstrapAttemptTs" to bootstrapAttemptTs,
                    "querySince" to treatmentQuerySince
                )
            )
        }
        if (treatmentsByCreatedAtFetched && !treatmentsByCreatedAtFetchTimedOut) {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    source = SOURCE_NIGHTSCOUT_TREATMENT_CREATED_AT_BACKFILL,
                    lastSyncedTimestamp = nowTs
                )
            )
        }
        registerNightscoutSyncOutcome(
            nowTs = nowTs,
            hadTimeout = sgvFetchTimedOut || treatmentsByDateFetchTimedOut || treatmentsByCreatedAtFetchTimedOut || deviceStatusFetchTimedOut,
            fetchedAnyPayload = glucoseRows.isNotEmpty() || treatmentRows.isNotEmpty() || deviceStatuses.isNotEmpty()
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
                    "treatmentCreatedAtQuerySince" to treatmentCreatedAtQuerySince,
                    "treatmentsFetchedByDate" to treatmentsByDate.size,
                    "treatmentsFetchedByCreatedAt" to treatmentsByCreatedAt.size,
                "treatmentsCreatedAtBackfill" to shouldFetchTreatmentsByCreatedAt,
                "treatmentsCreatedAtBackfillDue" to shouldRunTreatmentCreatedAtBackfill,
                    "treatmentsByCreatedAtFetchExecuted" to treatmentsByCreatedAtFetched,
                    "treatmentsByCreatedAtCount" to treatmentCreatedAtCount,
                    "treatmentsSkippedByClientWindow" to treatmentsSkippedByClientWindow,
                "treatmentsDowngradedFromBolus" to treatmentsDowngradedFromBolus,
                "sgvFetchDurationMs" to sgvFetchDurationMs,
                "sgvFetchTimedOut" to sgvFetchTimedOut,
                "treatmentsByDateFetchDurationMs" to treatmentsByDateFetchDurationMs,
                "treatmentsByDateFetchTimedOut" to treatmentsByDateFetchTimedOut,
                "treatmentsByCreatedAtFetchDurationMs" to treatmentsByCreatedAtFetchDurationMs,
                "treatmentsByCreatedAtFetchTimedOut" to treatmentsByCreatedAtFetchTimedOut,
                "deviceStatusFetchDurationMs" to deviceStatusFetchDurationMs,
                "deviceStatusFetchTimedOut" to deviceStatusFetchTimedOut,
                "deviceStatusQuerySince" to deviceStatusQuerySince,
                    "degradedMode" to degradedMode,
                    "consecutiveFailures" to nightscoutConsecutiveFailures,
                    "insulinLikeLocal30d" to insulinLikeCount,
                    "insulinLikeLocal30dRaw" to insulinLikeCountRaw,
                    "needsHistoricalInsulinRecovery" to needsHistoricalInsulinRecovery,
                    "treatmentBootstrap" to shouldBootstrapTreatmentHistory,
                "glucoseFetched" to glucoseRows.size,
                "glucoseInserted" to glucoseRowsToInsert.size,
                "glucoseSkippedDuplicate" to glucoseSkippedDuplicate,
                "glucoseReplaced" to glucoseReplaced,
                "treatments" to treatmentRows.size,
                "treatmentsInsulinLike" to insulinLikeFetched,
                "treatmentsCarbLike" to carbsFetched,
                "treatmentsRepairedType" to repairedTreatmentTypes,
                "treatmentsInsulinInferredFromIob" to inferredInsulinCount,
                "telemetry" to telemetryRows.size,
                "deviceStatus" to deviceStatuses.size
            )
        )
    }

    private suspend fun registerNightscoutSyncOutcome(
        nowTs: Long,
        hadTimeout: Boolean,
        fetchedAnyPayload: Boolean
    ) {
        if (hadTimeout || !fetchedAnyPayload) {
            nightscoutConsecutiveFailures += 1
            if (nightscoutConsecutiveFailures >= NIGHTSCOUT_FAILURE_BACKOFF_THRESHOLD) {
                nightscoutBackoffUntilTs = nowTs + NIGHTSCOUT_FAILURE_BACKOFF_MS
                auditLogger.warn(
                    "nightscout_sync_backoff_armed",
                    mapOf(
                        "consecutiveFailures" to nightscoutConsecutiveFailures,
                        "backoffMs" to NIGHTSCOUT_FAILURE_BACKOFF_MS,
                        "backoffUntilTs" to nightscoutBackoffUntilTs
                    )
                )
            }
            return
        }
        if (nightscoutConsecutiveFailures > 0 || nightscoutBackoffUntilTs > 0L) {
            auditLogger.info(
                "nightscout_sync_backoff_cleared",
                mapOf(
                    "previousFailures" to nightscoutConsecutiveFailures,
                    "previousBackoffUntilTs" to nightscoutBackoffUntilTs
                )
            )
        }
        nightscoutConsecutiveFailures = 0
        nightscoutBackoffUntilTs = 0L
    }

    suspend fun pushCloudIncremental() {
        val settings = settingsStore.settings.first()
        if (!isCopilotCloudBackendEndpoint(settings.cloudBaseUrl)) {
            auditLogger.info("cloud_push_skipped", mapOf("reason" to "cloud_backend_unavailable"))
            return
        }
        val nowTs = System.currentTimeMillis()
        if (isWithinThrottleWindow(lastCloudPushAttemptTs, nowTs, CLOUD_PUSH_MIN_INTERVAL_MS)) {
            auditLogger.infoThrottled(
                throttleKey = "cloud_push_skipped:min_interval",
                intervalMs = CLOUD_PUSH_MIN_INTERVAL_MS,
                message = "cloud_push_skipped",
                metadata = mapOf(
                    "reason" to "min_interval",
                    "intervalMs" to CLOUD_PUSH_MIN_INTERVAL_MS
                )
            )
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

    private fun isWithinThrottleWindow(
        lastRunRef: AtomicLong,
        nowTs: Long,
        intervalMs: Long
    ): Boolean {
        if (intervalMs <= 0L) return false
        while (true) {
            val previous = lastRunRef.get()
            if (previous > 0L && (nowTs - previous) < intervalMs) {
                return true
            }
            if (lastRunRef.compareAndSet(previous, nowTs)) {
                return false
            }
        }
    }

    private fun normalizeEventType(
        eventType: String?,
        payload: Map<String, String> = emptyMap()
    ): String = normalizeTreatmentTypeStatic(eventType = eventType, payload = payload)

    private fun payloadFromJson(raw: String): Map<String, String> {
        val anyMapType = object : TypeToken<Map<String, Any?>>() {}.type
        runCatching {
            gson.fromJson<Map<String, Any?>>(raw, anyMapType)
                ?.mapNotNull { (key, value) ->
                    val cleanKey = key?.trim().orEmpty()
                    if (cleanKey.isBlank()) {
                        null
                    } else {
                        cleanKey to payloadValueToString(value)
                    }
                }
                ?.toMap()
                .orEmpty()
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val stringMapType = object : TypeToken<Map<String, String>>() {}.type
        return runCatching {
            gson.fromJson<Map<String, String>>(raw, stringMapType) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun payloadValueToString(value: Any?): String {
        return when (value) {
            null -> ""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is String -> value
            else -> gson.toJson(value)
        }
    }

    private fun payloadNumeric(payload: Map<String, String>, vararg keys: String): Double? {
        return payloadNumericStatic(payload, *keys)
    }

    private fun hasPositivePayloadValue(payload: Map<String, String>, vararg keys: String): Boolean {
        return hasPositivePayloadValueStatic(payload, *keys)
    }

    private fun mergePayloadWithExisting(
        incoming: Map<String, String>,
        existing: Map<String, String>
    ): Map<String, String> {
        if (existing.isEmpty()) return incoming
        val merged = incoming.toMutableMap()
        CRITICAL_THERAPY_PAYLOAD_KEYS.forEach { key ->
            val incomingValue = merged[key]?.trim().orEmpty()
            if (incomingValue.isNotBlank()) return@forEach
            val existingValue = existing[key]?.trim().orEmpty()
            if (existingValue.isNotBlank()) {
                merged[key] = existingValue
            }
        }
        return merged
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

    private fun isLoopbackUrl(url: String): Boolean {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase(Locale.US) ?: return false
        return host == "127.0.0.1" || host == "localhost"
    }

    private fun sanitizedNightscoutUrl(url: String): String {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return url
        val scheme = parsed.scheme ?: "https"
        val host = parsed.host ?: "unknown"
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        return "$scheme://$host$port"
    }

    private fun resolveIncrementalFetchCount(
        bootstrap: Boolean,
        cursorSince: Long,
        nowTs: Long
    ): Int {
        if (bootstrap) return NS_FETCH_COUNT_BOOTSTRAP
        val ageMs = (nowTs - cursorSince).coerceAtLeast(0L)
        return when {
            ageMs <= 2L * 60L * 60L * 1000L -> NS_FETCH_COUNT_INCREMENTAL_HOT
            ageMs <= 6L * 60L * 60L * 1000L -> NS_FETCH_COUNT_INCREMENTAL_WARM
            ageMs <= 24L * 60L * 60L * 1000L -> NS_FETCH_COUNT_INCREMENTAL_COOL
            else -> NS_FETCH_COUNT_INCREMENTAL
        }
    }

    private suspend fun isLoopbackReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return@withContext false
        val host = parsed.host ?: return@withContext false
        val port = when {
            parsed.port > 0 -> parsed.port
            parsed.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), LOOPBACK_REACHABILITY_TIMEOUT_MS.toInt())
                socket.soTimeout = LOOPBACK_REACHABILITY_TIMEOUT_MS.toInt()
                true
            }
        }.getOrDefault(false)
    }

    private suspend fun awaitLoopbackReachable(url: String): Boolean {
        repeat(LOOPBACK_READY_RETRY_ATTEMPTS) { attempt ->
            if (isLoopbackReachable(url)) return true
            if (attempt < LOOPBACK_READY_RETRY_ATTEMPTS - 1) {
                delay(LOOPBACK_READY_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private suspend fun <T> fetchWithLoopbackRetry(
        loopback: Boolean,
        timeoutMs: Long,
        block: suspend () -> T
    ): Result<T?> {
        val firstAttempt = runCatching { withTimeoutOrNull(timeoutMs) { block() } }
        val firstError = firstAttempt.exceptionOrNull()
        if (!loopback || firstError == null || !firstError.isLoopbackConnectFailure()) {
            return firstAttempt
        }
        delay(LOOPBACK_FETCH_RETRY_DELAY_MS)
        return runCatching { withTimeoutOrNull(timeoutMs) { block() } }
    }

    private fun Throwable.isLoopbackConnectFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException) return true
            current = current.cause
        }
        return false
    }

    private suspend fun loadCursor(source: String, fallback: Long): Long {
        val raw = db.syncStateDao().bySource(source)?.lastSyncedTimestamp ?: fallback
        return if (raw <= 0L && fallback > 0L) fallback else raw
    }

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
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
        val therapyRowsSince = db.therapyDao().since(since)
        val repairedRows = therapyRowsSince
            .asSequence()
            .filter { it.type == "correction_bolus" && it.id.startsWith("iob-inf-") }
            .mapNotNull { row ->
                val payload = payloadFromJson(row.payloadJson)
                val existingInsulin = payloadDouble(payload, "insulin", "units", "bolusUnits", "enteredInsulin")
                val inferredFlag = payload["inferred"]?.trim()?.equals("true", ignoreCase = true) == true
                val method = payload["method"]?.trim()?.lowercase(Locale.US)
                if (existingInsulin != null && inferredFlag && method == "iob_jump") return@mapNotNull null

                val idMatch = IOB_INFERENCE_ID_REGEX.matchEntire(row.id) ?: return@mapNotNull null
                val unitsRounded = idMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                val inferredUnits = unitsRounded / 100.0
                if (inferredUnits <= 0.0 || inferredUnits > IOB_INFERENCE_MAX_DELTA_UNITS) return@mapNotNull null

                val repairedPayload = payload.toMutableMap()
                repairedPayload["insulin"] = String.format(Locale.US, "%.3f", inferredUnits)
                repairedPayload["inferred"] = "true"
                repairedPayload["method"] = "iob_jump"
                repairedPayload["source"] = repairedPayload["source"]?.takeIf { it.isNotBlank() } ?: "aaps_ns_iob"
                if (repairedPayload["confidence"].isNullOrBlank()) {
                    repairedPayload["confidence"] = "0.40"
                }
                TherapyEventEntity(
                    id = row.id,
                    timestamp = row.timestamp,
                    type = row.type,
                    payloadJson = gson.toJson(repairedPayload)
                )
            }
            .toList()
        if (repairedRows.isNotEmpty()) {
            db.therapyDao().upsertAll(repairedRows)
            auditLogger.info(
                "nightscout_iob_inferred_repaired",
                mapOf("since" to since, "repaired" to repairedRows.size)
            )
        }
        val repairedById = repairedRows.associateBy { it.id }
        val therapyRows = if (repairedById.isEmpty()) {
            therapyRowsSince
        } else {
            therapyRowsSince.map { row -> repairedById[row.id] ?: row }
        }

        val inferredRows = therapyRows
            .asSequence()
            .filter { it.type == "correction_bolus" }
            .filter { row ->
                val payload = payloadFromJson(row.payloadJson)
                payload["inferred"]?.trim()?.equals("true", ignoreCase = true) == true &&
                    payload["method"]?.trim()?.lowercase() == "iob_jump"
            }
            .toList()
        val staleInferredIds = inferredRows
            .mapNotNull { row ->
                val payload = payloadFromJson(row.payloadJson)
                val insulin = payloadDouble(payload, "insulin", "units", "bolusUnits", "enteredInsulin") ?: 0.0
                row.id.takeIf { insulin >= 0.0 && insulin < IOB_INFERENCE_MIN_DELTA_UNITS }
            }
            .toList()
        var cleanupDeleted = 0
        if (staleInferredIds.isNotEmpty()) {
            cleanupDeleted = staleInferredIds
                .chunked(250)
                .sumOf { ids -> db.therapyDao().deleteByIds(ids) }
        }
        if (inferredRows.isNotEmpty() || staleInferredIds.isNotEmpty()) {
            auditLogger.info(
                "nightscout_iob_inferred_cleanup",
                mapOf(
                    "since" to since,
                    "scannedInferredRows" to inferredRows.size,
                    "candidates" to staleInferredIds.size,
                    "deleted" to cleanupDeleted,
                    "thresholdUnits" to IOB_INFERENCE_MIN_DELTA_UNITS
                )
            )
        }

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

        val existingInsulinTimestamps = therapyRows
            .asSequence()
            .filter { it.type in IOB_INFERENCE_BLOCKING_TYPES }
            .mapNotNull { row ->
                val payload = payloadFromJson(row.payloadJson)
                val units = payloadDouble(payload, "insulin", "units", "bolusUnits", "enteredInsulin")
                val inferred = payload["inferred"]?.trim()?.equals("true", ignoreCase = true) == true
                if ((units ?: 0.0) > 0.0 || inferred) row.timestamp else null
            }
            .toList()
        val candidates = mutableListOf<TherapyEventEntity>()

        points.zipWithNext { prev, curr ->
            val dtMin = (curr.first - prev.first) / 60_000.0
            if (dtMin < IOB_INFERENCE_MIN_DT_MIN || dtMin > IOB_INFERENCE_MAX_DT_MIN) return@zipWithNext

            val delta = curr.second - prev.second
            if (delta < IOB_INFERENCE_MIN_DELTA_UNITS) return@zipWithNext
            val inferredUnits = delta.coerceAtMost(IOB_INFERENCE_MAX_DELTA_UNITS)
            if (inferredUnits <= 0.0) return@zipWithNext

            val ts = curr.first
            val hasNearbyInsulin = existingInsulinTimestamps.any { existingTs ->
                abs(existingTs - ts) <= IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS
            } || candidates.any { row ->
                abs(row.timestamp - ts) <= IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS
            }
            if (hasNearbyInsulin) return@zipWithNext

            val bucket = ts / IOB_INFERENCE_BUCKET_MS
            val unitsRounded = (inferredUnits * 100.0).roundToInt()
            val id = "iob-inf-$bucket-$unitsRounded"
            val payload = mapOf(
                "insulin" to String.format(Locale.US, "%.3f", inferredUnits),
                "inferred" to "true",
                "method" to "iob_jump",
                "source" to "aaps_ns_iob",
                "iobPrev" to String.format(Locale.US, "%.3f", prev.second),
                "iobNow" to String.format(Locale.US, "%.3f", curr.second),
                "deltaIob" to String.format(Locale.US, "%.3f", delta),
                "dtMin" to String.format(Locale.US, "%.2f", dtMin),
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

    private suspend fun repairNightscoutTherapyTypes(since: Long): Int {
        val normalizedSince = since.coerceAtLeast(0L)
        val repairs = db.therapyDao()
            .since(normalizedSince)
            .asSequence()
            .mapNotNull { row ->
                val payload = payloadFromJson(row.payloadJson)
                val source = payload["source"]?.trim()?.lowercase(Locale.US)
                val eventType = payload["eventType"]
                val shouldRepair = source == SOURCE_NIGHTSCOUT_TREATMENT ||
                    source == SOURCE_LOCAL_NIGHTSCOUT_TREATMENT ||
                    !eventType.isNullOrBlank()
                if (!shouldRepair) return@mapNotNull null
                val normalizedType = normalizeEventType(
                    eventType = eventType,
                    payload = payload
                )
                if (normalizedType == row.type) return@mapNotNull null
                TherapyEventEntity(
                    id = row.id,
                    timestamp = row.timestamp,
                    type = normalizedType,
                    payloadJson = row.payloadJson
                )
            }
            .toList()
        if (repairs.isEmpty()) return 0
        db.therapyDao().upsertAll(repairs)
        auditLogger.info(
            "nightscout_treatment_type_repaired",
            mapOf(
                "since" to normalizedSince,
                "repaired" to repairs.size
            )
        )
        return repairs.size
    }

    companion object {
        private const val SOURCE_NIGHTSCOUT = "nightscout"
        private const val SOURCE_CLOUD_PUSH = "cloud_push"
        private const val SOURCE_NIGHTSCOUT_TREATMENT = "nightscout_treatment"
        private const val SOURCE_LOCAL_NIGHTSCOUT_TREATMENT = "local_nightscout_treatment"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS = "nightscout_devicestatus"
        private const val SOURCE_AAPS_BROADCAST = "aaps_broadcast"
        private const val SOURCE_XDRIP_BROADCAST = "xdrip_broadcast"
        private const val SOURCE_LOCAL_BROADCAST = "local_broadcast"
        private const val SOURCE_NIGHTSCOUT_SGV = "nightscout_sgv_cursor"
        private const val SOURCE_NIGHTSCOUT_TREATMENT_CURSOR = "nightscout_treatment_cursor"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS_CURSOR = "nightscout_devicestatus_cursor"
        private const val SOURCE_NIGHTSCOUT_TREATMENT_BOOTSTRAP = "nightscout_treatment_bootstrap_cursor"
        private const val SOURCE_NIGHTSCOUT_TREATMENT_CREATED_AT_BACKFILL = "nightscout_treatment_created_at_backfill_cursor"
        private const val NS_CURSOR_OVERLAP_MS = 5 * 60_000L
        private const val DEVICESTATUS_MAX_LOOKBACK_MS = 24L * 60 * 60 * 1000
        private const val NS_FETCH_COUNT_BOOTSTRAP = 2000
        private const val NS_FETCH_COUNT_INCREMENTAL = 250
        private const val NS_FETCH_COUNT_INCREMENTAL_COOL = 160
        private const val NS_FETCH_COUNT_INCREMENTAL_WARM = 120
        private const val NS_FETCH_COUNT_INCREMENTAL_HOT = 80
        private const val NS_DEVICESTATUS_FETCH_COUNT_INCREMENTAL = 60
        private const val NS_SGV_FETCH_TIMEOUT_MS = 10_000L
        private const val NS_TREATMENT_FETCH_TIMEOUT_MS = 8_000L
        private const val NS_DEVICESTATUS_FETCH_TIMEOUT_MS = 8_000L
        private const val LOOPBACK_REACHABILITY_TIMEOUT_MS = 1_500L
        private const val LOOPBACK_READY_RETRY_ATTEMPTS = 6
        private const val LOOPBACK_READY_RETRY_DELAY_MS = 500L
        private const val LOOPBACK_FETCH_RETRY_DELAY_MS = 400L
        private const val NIGHTSCOUT_MIN_SYNC_INTERVAL_MS = 2 * 60_000L
        private const val NIGHTSCOUT_FAILURE_BACKOFF_THRESHOLD = 2
        private const val NIGHTSCOUT_FAILURE_BACKOFF_MS = 15 * 60_000L
        private const val CLOUD_PUSH_MIN_INTERVAL_MS = 5 * 60_000L
        private const val THERAPY_BOOTSTRAP_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000
        private const val THERAPY_BOOTSTRAP_RETRY_MS = 12L * 60 * 60 * 1000
        private const val TREATMENT_CREATED_AT_BACKFILL_INTERVAL_MS = 30L * 60 * 1000
        private const val THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS = 10
        private const val GLUCOSE_REPLACE_EPSILON = 0.01
        private const val MAX_FUTURE_TIMESTAMP_SKEW_MS = 24 * 60 * 60 * 1000L
        private const val IOB_INFERENCE_LOOKBACK_MS = 24L * 60 * 60 * 1000L
        private const val IOB_INFERENCE_MIN_DELTA_UNITS = 0.50
        private const val IOB_INFERENCE_MAX_DELTA_UNITS = 4.0
        private const val IOB_INFERENCE_MIN_IOB = -1.0
        private const val IOB_INFERENCE_MAX_IOB = 30.0
        private const val IOB_INFERENCE_MIN_DT_MIN = 1.0
        private const val IOB_INFERENCE_MAX_DT_MIN = 15.0
        private const val IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS = 10 * 60_000L
        private const val IOB_INFERENCE_BUCKET_MS = 5 * 60_000L
        private val IOB_INFERENCE_ID_REGEX = Regex("^iob-inf-(\\d+)-(\\d+)$")
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
        private val CRITICAL_THERAPY_PAYLOAD_KEYS = setOf(
            "eventType",
            "createdAt",
            "date",
            "mills",
            "insulin",
            "units",
            "bolusUnits",
            "enteredInsulin",
            "inferred",
            "method",
            "source",
            "iobPrev",
            "iobNow",
            "deltaIob",
            "dtMin",
            "confidence",
            "carbs",
            "grams",
            "enteredCarbs",
            "mealCarbs"
        )

        internal fun normalizeTreatmentTypeStatic(
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
            if (mapped.isNotBlank()) {
                val hasInsulinDose = hasPositivePayloadValueStatic(
                    payload,
                    "insulin",
                    "units",
                    "bolusUnits",
                    "enteredInsulin"
                )
                val hasCarbs = hasPositivePayloadValueStatic(
                    payload,
                    "carbs",
                    "grams",
                    "enteredCarbs",
                    "mealCarbs"
                )
                val inferredIobBolus = payload["inferred"]?.trim()?.equals("true", ignoreCase = true) == true &&
                    payload["method"]?.trim()?.equals("iob_jump", ignoreCase = true) == true
                val payloadDrivenType = when {
                    inferredIobBolus -> "correction_bolus"
                    hasInsulinDose && hasCarbs -> "meal_bolus"
                    hasInsulinDose -> "correction_bolus"
                    hasCarbs -> "carbs"
                    else -> null
                }
                return when (mapped) {
                    "correction_bolus" -> if (hasInsulinDose || inferredIobBolus) "correction_bolus" else "treatment"
                    "meal_bolus", "bolus", "bolus_wizard", "combo_bolus", "extended_bolus", "insulin", "carbs" ->
                        payloadDrivenType ?: "treatment"
                    "wizard", "snack_bolus", "announcement" ->
                        payloadDrivenType ?: "treatment"
                    else -> mapped
                }
            }

            val carbs = payloadNumericStatic(payload, "carbs", "grams", "enteredCarbs", "mealCarbs")
            val insulin = payloadNumericStatic(payload, "insulin", "units", "bolusUnits", "enteredInsulin")
            val hasTarget = payload.containsKey("targetTop") || payload.containsKey("targetBottom")
            return when {
                carbs != null && carbs > 0.0 && insulin != null && insulin > 0.0 -> "meal_bolus"
                insulin != null && insulin > 0.0 -> "correction_bolus"
                carbs != null && carbs > 0.0 -> "carbs"
                hasTarget -> "temp_target"
                else -> "treatment"
            }
        }

        internal fun buildNightscoutTreatmentPayloadStatic(
            treatment: NightscoutTreatment,
            source: String
        ): MutableMap<String, String> {
            return linkedMapOf<String, String>().apply {
                treatment.duration?.let { put("duration", it.toString()) }
                treatment.durationInMilliseconds?.let { put("durationInMilliseconds", it.toString()) }
                treatment.targetTop?.let { put("targetTop", it.toString()) }
                treatment.targetBottom?.let { put("targetBottom", it.toString()) }
                treatment.carbs?.let { put("carbs", it.toString()) }
                treatment.enteredCarbs?.let {
                    put("enteredCarbs", it.toString())
                    put("mealCarbs", it.toString())
                }
                treatment.insulin?.let { put("insulin", it.toString()) }
                treatment.enteredInsulin?.let {
                    put("enteredInsulin", it.toString())
                    put("bolusUnits", it.toString())
                }
                treatment.enteredBy?.let { put("enteredBy", it) }
                treatment.absolute?.let { put("absolute", it.toString()) }
                treatment.rate?.let { put("rate", it.toString()) }
                treatment.percentage?.let { put("percentage", it.toString()) }
                treatment.eventType?.let { put("eventType", it) }
                treatment.date?.let { put("date", it.toString()) }
                treatment.mills?.let { put("mills", it.toString()) }
                treatment.createdAt?.let { put("createdAt", it) }
                treatment.units?.let { put("units", it) }
                treatment.isValid?.let { put("isValid", it.toString()) }
                treatment.reason?.let { put("reason", it) }
                treatment.notes?.let { put("notes", it) }
                put("source", source)
            }
        }

        internal fun buildNightscoutTreatmentPayloadStatic(
            request: NightscoutTreatmentRequest,
            source: String
        ): LinkedHashMap<String, String> {
            return linkedMapOf<String, String>().apply {
                request.duration?.let { put("duration", it.toString()) }
                request.durationInMilliseconds?.let { put("durationInMilliseconds", it.toString()) }
                request.targetTop?.let { put("targetTop", it.toString()) }
                request.targetBottom?.let { put("targetBottom", it.toString()) }
                request.carbs?.let { put("carbs", it.toString()) }
                request.enteredCarbs?.let {
                    put("enteredCarbs", it.toString())
                    put("mealCarbs", it.toString())
                }
                request.insulin?.let { put("insulin", it.toString()) }
                request.enteredInsulin?.let {
                    put("enteredInsulin", it.toString())
                    put("bolusUnits", it.toString())
                }
                request.units?.let { put("units", it) }
                request.isValid?.let { put("isValid", it.toString()) }
                request.reason?.let { put("reason", it) }
                request.notes?.let { put("notes", it) }
                put("eventType", request.eventType)
                request.date?.let { put("date", it.toString()) }
                request.mills?.let { put("mills", it.toString()) }
                request.createdAt?.let { put("createdAt", it) }
                put("source", source)
            }
        }

        internal fun payloadNumericStatic(payload: Map<String, String>, vararg keys: String): Double? {
            return keys.firstNotNullOfOrNull { key ->
                payload[key]?.replace(",", ".")?.toDoubleOrNull()
            }
        }

        internal fun hasPositivePayloadValueStatic(payload: Map<String, String>, vararg keys: String): Boolean {
            return payloadNumericStatic(payload, *keys)?.let { it > 0.0 } == true
        }
    }

    private fun payloadDouble(payload: Map<String, String>, vararg keys: String): Double? {
        val normalized = payload.entries.associate { normalizeKey(it.key) to it.value }
        return keys.firstNotNullOfOrNull { key ->
            normalized[normalizeKey(key)]?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun normalizeKey(raw: String): String = raw
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
}
