package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.withTransaction
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
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.util.UnitConverter
import java.io.EOFException
import java.io.IOException
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

internal suspend fun writeSyncRepositoryRemoteTherapyRows(
    incomingRows: List<TherapyEventEntity>,
    transactionRunner: RemoteTherapyWriteTransactionRunner,
    loadLatestByIds: suspend (List<String>) -> List<TherapyEventEntity>,
    upsertAll: suspend (List<TherapyEventEntity>) -> Unit,
    gson: Gson
) {
    RemoteTherapyBatchWriter(
        transactionRunner = transactionRunner,
        loadLatestByIds = loadLatestByIds,
        upsertAll = upsertAll,
        decodePayload = ::parseTherapyPayloadJsonObject,
        encodePayload = gson::toJson
    ).write(incomingRows)
}

internal fun isAuthoritativeNightscoutFetchSuccessful(result: Result<*>): Boolean =
    result.isSuccess && result.getOrNull() != null

class SyncRepository(
    private val context: Context,
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val onSuccessfulNightscoutSync: suspend () -> Unit = {}
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
        val loopbackPrecheckFailed = loopbackUrl && !awaitLoopbackReachable(nightscoutUrl)
        if (loopbackPrecheckFailed) {
            LocalNightscoutServiceController.start(context, allowBackground = true)
            delay(LOOPBACK_READY_RETRY_DELAY_MS)
            auditLogger.warn(
                "nightscout_sync_loopback_recovering",
                mapOf(
                    "reason" to "loopback_unreachable",
                    "url" to sanitizedNightscoutUrl(nightscoutUrl)
                )
            )
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

        val nsApi = apiFactory.nightscoutApi(nightscoutUrl, settings)
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
        val bootstrapSince = nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS
        val insulinLikeCountRaw = db.therapyDao().countInsulinLikeSince(bootstrapSince)
        val insulinLikeCountInferred = db.therapyDao().countInferredInsulinLikeSince(bootstrapSince)
        val insulinLikeCountRealFetched = db.therapyDao().countRealFetchedInsulinLikeSince(bootstrapSince)
        val insulinLikeCountRecovered = db.therapyDao().countRecoveredInsulinLikeSince(bootstrapSince)
        val insulinLikeCountUsable = db.therapyDao().countInsulinLikeForBootstrapSince(bootstrapSince)
        val needsHistoricalInsulinRecovery = insulinLikeCountUsable < THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS
        val plateauOnlyBootstrap =
            insulinLikeCountRealFetched == 0 && insulinLikeCountRecovered > 0
        val shouldBootstrapTreatmentHistory =
            needsHistoricalInsulinRecovery &&
                (
                    nowTs - bootstrapAttemptTs >= THERAPY_BOOTSTRAP_RETRY_MS ||
                        (insulinLikeCountUsable == 0 && nowTs - treatmentCreatedAtBackfillTs >= TREATMENT_CREATED_AT_BACKFILL_INTERVAL_MS)
                    )
        val shouldRunTreatmentCreatedAtBackfill =
            shouldBootstrapTreatmentHistory ||
                (
                    needsHistoricalInsulinRecovery &&
                        plateauOnlyBootstrap &&
                        nowTs - treatmentCreatedAtBackfillTs >= TREATMENT_CREATED_AT_PLATEAU_RETRY_MS
                    ) ||
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
                "needsHistoricalInsulinRecovery" to needsHistoricalInsulinRecovery,
                "plateauOnlyBootstrap" to plateauOnlyBootstrap
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
        val sgvFetchSucceeded = isAuthoritativeNightscoutFetchSuccessful(sgvResult)
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
        val dateFetchSuggestsCreatedAtRecovery = hasRecoveryRelevantTreatmentsOutsideClientWindowStatic(
            treatments = treatmentsByDate,
            clientWindowSince = treatmentQuerySince,
            source = "nightscout_treatment"
        )
        val shouldFetchTreatmentsByCreatedAt =
            !degradedMode &&
                (
                    shouldRunTreatmentCreatedAtBackfill ||
                        treatmentsByDate.isEmpty() ||
                        (needsHistoricalInsulinRecovery && dateFetchSuggestsCreatedAtRecovery)
                    )
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
        val treatmentSelection = selectTreatmentsWithinClientWindowStatic(
            treatmentsByDate = treatmentsByDate,
            treatmentsByCreatedAt = treatmentsByCreatedAt,
            treatmentQuerySince = treatmentQuerySince,
            treatmentCreatedAtQuerySince = treatmentCreatedAtQuerySince
        )
        val treatments = treatmentSelection.treatments
        val treatmentsByDateEventTypeSummary = summarizeTreatmentEventTypesStatic(treatmentsByDate)
        val treatmentsByCreatedAtEventTypeSummary = summarizeTreatmentEventTypesStatic(treatmentsByCreatedAt)
        val treatmentRows = mutableListOf<TherapyEventEntity>()
        val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
        var insulinLikeFetched = 0
        var carbsFetched = 0
        var localActionFetched = 0
        var upstreamTempTargetFetched = 0
        var treatmentsSkippedByClientWindow = treatmentSelection.skippedByClientWindow
        var treatmentsDowngradedFromBolus = 0
        val treatmentTypeByClassificationCounts = linkedMapOf<String, Int>()

        treatments.forEach { treatment ->
            val ts = parseNightscoutTimestamp(
                createdAt = treatment.createdAt,
                date = treatment.date,
                mills = treatment.mills
            ) ?: return@forEach
            val payload = buildNightscoutTreatmentPayloadStatic(
                treatment = treatment,
                source = SOURCE_NIGHTSCOUT_TREATMENT
            )

            val id = treatment.id ?: "ns-$ts-${treatment.eventType.orEmpty().hashCode()}"
            val classifiedPayload = normalizeNightscoutFetchedPayloadStatic(
                eventId = id,
                payload = payload,
                defaultSource = SOURCE_NIGHTSCOUT_TREATMENT
            )
            val normalizedType = normalizeEventType(
                eventType = treatment.eventType,
                payload = classifiedPayload
            )
            val classification = classifiedPayload["classification"].orEmpty().ifBlank { "UNCLASSIFIED" }
            val typeByClassificationKey = "$classification:$normalizedType"
            treatmentTypeByClassificationCounts[typeByClassificationKey] =
                (treatmentTypeByClassificationCounts[typeByClassificationKey] ?: 0) + 1
            val isLocalAction = classification == "LOCAL_ACTION"
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
                payloadJson = gson.toJson(classifiedPayload)
            )
            if (isLocalAction) {
                localActionFetched += 1
            } else {
                if (normalizedType == "temp_target") {
                    upstreamTempTargetFetched += 1
                }
                if (normalizedType == "meal_bolus" || normalizedType == "correction_bolus" || normalizedType == "insulin") {
                    insulinLikeFetched += 1
                }
                if (normalizedType == "carbs" || normalizedType == "meal_bolus") {
                    carbsFetched += 1
                }
            }
            telemetryRows += TelemetryMetricMapper.fromNightscoutTreatment(
                timestamp = ts,
                source = SOURCE_NIGHTSCOUT_TREATMENT,
                eventType = treatment.eventType ?: normalizedType,
                payload = classifiedPayload
            )
        }
        val treatmentsNormalizedTypeSummary = treatmentRows
            .groupingBy { it.type }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .joinToString(separator = ",") { "${it.first}:${it.second}" }
        val treatmentsNormalizedClassificationSummary = treatmentTypeByClassificationCounts
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .joinToString(separator = ",") { "${it.first}:${it.second}" }

        val deviceStatusCount = if (shouldBootstrapTreatmentHistory) NS_FETCH_COUNT_BOOTSTRAP else NS_DEVICESTATUS_FETCH_COUNT_INCREMENTAL
        var deviceStatusFetchTimedOut = false
        val deviceStatuses: List<NightscoutDeviceStatus>
        val deviceStatusFetchDurationMs: Long
        if (!shouldFetchRemoteDeviceStatus(loopbackUrl = loopbackUrl, degradedMode = degradedMode)) {
            deviceStatuses = emptyList()
            deviceStatusFetchDurationMs = 0L
            if (loopbackUrl) {
                auditLogger.infoThrottled(
                    throttleKey = "nightscout_devicestatus_self_sync_skipped",
                    intervalMs = NIGHTSCOUT_DEVICESTATUS_SELF_SYNC_AUDIT_INTERVAL_MS,
                    message = "nightscout_devicestatus_self_sync_skipped",
                    metadata = mapOf("reason" to "local_loopback_is_already_canonical")
                )
            }
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
            writeSyncRepositoryRemoteTherapyRows(
                incomingRows = treatmentRows,
                transactionRunner = RemoteTherapyWriteTransactionRunner { block ->
                    db.withTransaction { block() }
                },
                loadLatestByIds = { ids -> db.therapyDao().byIds(ids) },
                upsertAll = { rows -> db.therapyDao().upsertAll(rows) },
                gson = gson
            )
        }
        val treatmentRepairHistorySince = nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS
        val legacyTreatmentRepairCandidates = db.therapyDao()
            .countNightscoutRepairCandidatesSince(treatmentRepairHistorySince)
        val shouldRepairTreatmentHistory = shouldRunNightscoutTreatmentRepairStatic(
            importedTreatmentCount = treatmentRows.size,
            historicalBootstrap = shouldBootstrapTreatmentHistory,
            legacyCandidateCount = legacyTreatmentRepairCandidates
        )
        val treatmentRepairSince = when {
            shouldBootstrapTreatmentHistory || legacyTreatmentRepairCandidates > 0 ->
                treatmentRepairHistorySince
            treatmentRows.isNotEmpty() ->
                treatmentRows.minOf { it.timestamp }
            else -> null
        }
        val repairedTreatmentTypes = if (shouldRepairTreatmentHistory && treatmentRepairSince != null) {
            repairNightscoutTherapyTypes(since = treatmentRepairSince)
        } else {
            0
        }
        val repairedTreatmentMetadata = if (shouldRepairTreatmentHistory && treatmentRepairSince != null) {
            repairNightscoutTreatmentMetadata(since = treatmentRepairSince)
        } else {
            0
        }
        if (telemetryRows.isNotEmpty()) {
            db.telemetryDao().upsertAll((telemetryRows + glucoseInputTelemetryRows).distinctBy { it.id })
        } else if (glucoseInputTelemetryRows.isNotEmpty()) {
            db.telemetryDao().upsertAll(glucoseInputTelemetryRows)
        }
        val recoveredTelemetryTherapy = recoverTherapyEventsFromDeviceStatusTelemetry(
            nowTs = nowTs,
            lookbackMs = if (needsHistoricalInsulinRecovery) {
                THERAPY_BOOTSTRAP_LOOKBACK_MS
            } else {
                TELEMETRY_THERAPY_RECOVERY_INCREMENTAL_LOOKBACK_MS
            }
        )
        val inferredInsulinCount = inferInsulinEventsFromIob(nowTs)
        val insulinLikeCountRawAfterRecovery = db.therapyDao().countInsulinLikeSince(bootstrapSince)
        val insulinLikeCountInferredAfterRecovery = db.therapyDao().countInferredInsulinLikeSince(bootstrapSince)
        val insulinLikeCountRealFetchedAfterRecovery = db.therapyDao().countRealFetchedInsulinLikeSince(bootstrapSince)
        val insulinLikeCountRecoveredAfterRecovery = db.therapyDao().countRecoveredInsulinLikeSince(bootstrapSince)
        val insulinLikeCountUsableAfterRecovery = db.therapyDao().countInsulinLikeForBootstrapSince(bootstrapSince)
        val needsHistoricalInsulinRecoveryAfterRecovery =
            insulinLikeCountUsableAfterRecovery < THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS
        val therapyHistorySourceMode = therapyHistorySourceModeStatic(
            rawCount = insulinLikeCountRawAfterRecovery,
            inferredCount = insulinLikeCountInferredAfterRecovery,
            realFetchedCount = insulinLikeCountRealFetchedAfterRecovery,
            recoveredCount = insulinLikeCountRecoveredAfterRecovery,
            usableCount = insulinLikeCountUsableAfterRecovery
        )
        val therapyHistorySyntheticRatioPct = if (insulinLikeCountRawAfterRecovery > 0) {
            insulinLikeCountInferredAfterRecovery.toDouble() * 100.0 / insulinLikeCountRawAfterRecovery.toDouble()
        } else {
            null
        }
        val therapyHistoryPlateauOnly =
            insulinLikeCountRealFetchedAfterRecovery == 0 &&
                insulinLikeCountRecoveredAfterRecovery > 0
        db.telemetryDao().upsertAll(
            buildTherapyHistoryTelemetryRowsStatic(
                nowTs = nowTs,
                sourceMode = therapyHistorySourceMode,
                rawCount = insulinLikeCountRawAfterRecovery,
                inferredCount = insulinLikeCountInferredAfterRecovery,
                realFetchedCount = insulinLikeCountRealFetchedAfterRecovery,
                recoveredCount = insulinLikeCountRecoveredAfterRecovery,
                usableCount = insulinLikeCountUsableAfterRecovery,
                needsRecovery = needsHistoricalInsulinRecoveryAfterRecovery,
                plateauOnly = therapyHistoryPlateauOnly,
                syntheticRatioPct = therapyHistorySyntheticRatioPct,
                fetchedTreatmentCount = treatmentRows.size - localActionFetched,
                fetchedInsulinLikeCount = insulinLikeFetched,
                fetchedCarbLikeCount = carbsFetched,
                fetchedLocalActionCount = localActionFetched,
                fetchedByDateEventTypeSummary = treatmentsByDateEventTypeSummary,
                fetchedByCreatedAtEventTypeSummary = treatmentsByCreatedAtEventTypeSummary,
                normalizedTypeSummary = treatmentsNormalizedTypeSummary,
                normalizedClassificationSummary = treatmentsNormalizedClassificationSummary,
                upstreamTempTargetOnly =
                    (treatmentRows.size - localActionFetched) > 0 &&
                        insulinLikeFetched == 0 &&
                        carbsFetched == 0 &&
                        upstreamTempTargetFetched > 0
            )
        )

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
                    "insulinLikeCountUsable" to insulinLikeCountUsable,
                    "insulinLikeCountRaw" to insulinLikeCountRaw,
                    "insulinLikeCountInferred" to insulinLikeCountInferred,
                    "insulinLikeCountRealFetched" to insulinLikeCountRealFetched,
                    "insulinLikeCountRecovered" to insulinLikeCountRecovered,
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
                    "treatmentsByDateEventTypeSummary" to treatmentsByDateEventTypeSummary,
                    "treatmentsByCreatedAtEventTypeSummary" to treatmentsByCreatedAtEventTypeSummary,
                "dateFetchSuggestsCreatedAtRecovery" to dateFetchSuggestsCreatedAtRecovery,
                "treatmentsCreatedAtBackfill" to shouldFetchTreatmentsByCreatedAt,
                "treatmentsCreatedAtBackfillDue" to shouldRunTreatmentCreatedAtBackfill,
                "treatmentsByCreatedAtFetchExecuted" to treatmentsByCreatedAtFetched,
                    "treatmentsByCreatedAtCount" to treatmentCreatedAtCount,
                    "treatmentsSkippedByClientWindow" to treatmentsSkippedByClientWindow,
                "treatmentsDowngradedFromBolus" to treatmentsDowngradedFromBolus,
                "treatmentsNormalizedTypeSummary" to treatmentsNormalizedTypeSummary,
                "treatmentsNormalizedClassificationSummary" to treatmentsNormalizedClassificationSummary,
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
                    "insulinLikeLocal30d" to insulinLikeCountUsable,
                    "insulinLikeLocal30dRaw" to insulinLikeCountRaw,
                    "insulinLikeLocal30dInferred" to insulinLikeCountInferred,
                    "insulinLikeLocal30dRealFetched" to insulinLikeCountRealFetched,
                    "insulinLikeLocal30dRecovered" to insulinLikeCountRecovered,
                    "insulinLikeLocal30dAfterRecovery" to insulinLikeCountUsableAfterRecovery,
                    "insulinLikeLocal30dRawAfterRecovery" to insulinLikeCountRawAfterRecovery,
                    "insulinLikeLocal30dInferredAfterRecovery" to insulinLikeCountInferredAfterRecovery,
                    "insulinLikeLocal30dRealFetchedAfterRecovery" to insulinLikeCountRealFetchedAfterRecovery,
                    "insulinLikeLocal30dRecoveredAfterRecovery" to insulinLikeCountRecoveredAfterRecovery,
                    "needsHistoricalInsulinRecovery" to needsHistoricalInsulinRecovery,
                    "needsHistoricalInsulinRecoveryAfterRecovery" to needsHistoricalInsulinRecoveryAfterRecovery,
                    "plateauOnlyBootstrap" to plateauOnlyBootstrap,
                    "therapyHistorySourceMode" to therapyHistorySourceMode,
                    "therapyHistorySyntheticRatioPct" to therapyHistorySyntheticRatioPct,
                    "therapyHistoryPlateauOnly" to therapyHistoryPlateauOnly,
                    "treatmentBootstrap" to shouldBootstrapTreatmentHistory,
                "glucoseFetched" to glucoseRows.size,
                "glucoseInserted" to glucoseRowsToInsert.size,
                "glucoseSkippedDuplicate" to glucoseSkippedDuplicate,
                "glucoseReplaced" to glucoseReplaced,
                "treatments" to treatmentRows.size,
                "treatmentsInsulinLike" to insulinLikeFetched,
                "treatmentsCarbLike" to carbsFetched,
                "treatmentsRecoveredFromDeviceStatus" to recoveredTelemetryTherapy.totalRecovered,
                "treatmentsRecoveredInsulinFromDeviceStatus" to recoveredTelemetryTherapy.insulinLikeRecovered,
                "treatmentsRecoveredCarbFromDeviceStatus" to recoveredTelemetryTherapy.carbLikeRecovered,
                "treatmentsRepairedType" to repairedTreatmentTypes,
                "treatmentsRepairedMetadata" to repairedTreatmentMetadata,
                "treatmentsInsulinInferredFromIob" to inferredInsulinCount,
                "telemetry" to telemetryRows.size,
                "deviceStatus" to deviceStatuses.size
            )
        )
        if (sgvFetchSucceeded) {
            try {
                onSuccessfulNightscoutSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                auditLogger.warn(
                    "context_event_reconciliation_after_sync_failed",
                    mapOf("error" to error.javaClass.simpleName.take(80))
                )
            }
        }
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
                payload = event.toDomain(gson).payload
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

    suspend fun recentTherapyEvents(hoursBack: Int, nowTs: Long): List<TherapyEvent> {
        val since = nowTs - hoursBack * 60 * 60 * 1000L
        return TherapySanitizer.filterEntities(db.therapyDao().between(since, nowTs)).map { it.toDomain(gson) }
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
        return runCatching {
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
        if (!loopback || firstError == null || !firstError.isLoopbackRetryableFailure()) {
            return firstAttempt
        }
        delay(LOOPBACK_FETCH_RETRY_DELAY_MS)
        return runCatching { withTimeoutOrNull(timeoutMs) { block() } }
    }

    private fun Throwable.isLoopbackRetryableFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException) return true
            if (current is EOFException) return true
            if (current is IOException) {
                val message = current.message.orEmpty().lowercase(Locale.US)
                if (
                    "unexpected end of stream" in message ||
                    "stream was reset" in message ||
                    "connection reset" in message
                ) {
                    return true
                }
            }
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
        TelemetryMetricMapper.flattenAny("loop", status.loop, flattened)
        TelemetryMetricMapper.flattenAny("pump", status.pump, flattened)
        TelemetryMetricMapper.flattenAny("cgm", status.cgm, flattened)
        TelemetryMetricMapper.flattenAny("uploader", status.uploader, flattened)
        return TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
            timestamp = timestamp,
            source = SOURCE_NIGHTSCOUT_DEVICESTATUS,
            flattened = flattened
        )
    }


    private suspend fun recoverTherapyEventsFromDeviceStatusTelemetry(
        nowTs: Long,
        lookbackMs: Long
    ): TelemetryTherapyRecoveryResult {
        val since = (nowTs - lookbackMs).coerceAtLeast(0L)
        val (telemetryRowsRaw, _) = collectTelemetryEntitiesByKeysPaged(
            telemetryDao = db.telemetryDao(),
            since = since,
            keys = DEVICE_STATUS_THERAPY_TELEMETRY_KEYS,
            callerTag = "sync_devicestatus_therapy_recovery",
            auditLogger = auditLogger
        )
        val telemetryRows = selectDeviceStatusTherapyTelemetryRowsStatic(telemetryRowsRaw)
        if (telemetryRows.isEmpty()) {
            return TelemetryTherapyRecoveryResult.EMPTY
        }
        val existingTherapyRows = db.therapyDao().since(since)
        val existingRecoveredRows = existingTherapyRows.filter { row ->
            payloadFromJson(row.payloadJson)["source"] == "nightscout_devicestatus_recovered"
        }
        val existingNonRecoveredRows = if (existingRecoveredRows.isEmpty()) {
            existingTherapyRows
        } else {
            existingTherapyRows.filterNot { row -> existingRecoveredRows.any { it.id == row.id } }
        }
        val candidates = buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = telemetryRows,
            existingTherapyRows = existingNonRecoveredRows,
            existingRecoveredRows = existingRecoveredRows,
            gson = gson
        )
        val obsoleteRecoveredIds = existingRecoveredRows
            .map { it.id }
            .filterNot { existingId -> candidates.any { it.id == existingId } }
        if (candidates.isEmpty()) {
            if (obsoleteRecoveredIds.isNotEmpty()) {
                db.therapyDao().deleteByIds(obsoleteRecoveredIds)
            }
            val realFetchedCount = db.therapyDao().countRealFetchedInsulinLikeSince(nowTs - THERAPY_BOOTSTRAP_LOOKBACK_MS)
            if (realFetchedCount == 0) {
                auditLogger.warn(
                    "nightscout_treatment_bootstrap_no_real_insulin_persisted",
                    mapOf(
                        "since" to since,
                        "telemetryRows" to telemetryRows.size,
                        "telemetrySource" to (telemetryRows.firstOrNull()?.source ?: "none"),
                        "lookbackMs" to lookbackMs,
                        "deletedRecoveredRows" to obsoleteRecoveredIds.size
                    )
                )
            }
            return TelemetryTherapyRecoveryResult.EMPTY
        }
        if (obsoleteRecoveredIds.isNotEmpty()) {
            db.therapyDao().deleteByIds(obsoleteRecoveredIds)
        }
        db.therapyDao().upsertAll(candidates)
        val insulinLikeRecovered = candidates.count { it.type in IOB_INFERENCE_BLOCKING_TYPES }
        val carbLikeRecovered = candidates.count { it.type == "carbs" || it.type == "meal_bolus" }
        auditLogger.info(
            "nightscout_devicestatus_therapy_recovered",
            mapOf(
                "since" to since,
                "lookbackMs" to lookbackMs,
                "telemetryRows" to telemetryRows.size,
                "recovered" to candidates.size,
                "recoveredRebuilt" to existingRecoveredRows.size,
                "recoveredDeleted" to obsoleteRecoveredIds.size,
                "recoveredInsulinLike" to insulinLikeRecovered,
                "recoveredCarbLike" to carbLikeRecovered
            )
        )
        return TelemetryTherapyRecoveryResult(
            totalRecovered = candidates.size,
            insulinLikeRecovered = insulinLikeRecovered,
            carbLikeRecovered = carbLikeRecovered
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

        val (iobRowsRaw, _) = collectTelemetryEntitiesByKeysPaged(
            telemetryDao = db.telemetryDao(),
            since = since,
            keys = listOf("iob_units", "raw_iob"),
            callerTag = "sync_iob_microbolus_recovery",
            auditLogger = auditLogger
        )
        val iobRows = iobRowsRaw
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
            val derivedEvent = buildIobDerivedInsulinEventStatic(
                ts = curr.first,
                prevIob = prev.second,
                currIob = curr.second,
                dtMin = dtMin
            ) ?: return@zipWithNext

            val hasNearbyInsulin = existingInsulinTimestamps.any { existingTs ->
                abs(existingTs - derivedEvent.timestamp) <= IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS
            } || candidates.any { row ->
                abs(row.timestamp - derivedEvent.timestamp) <= IOB_INFERENCE_NEARBY_EVENT_WINDOW_MS
            }
            if (hasNearbyInsulin) return@zipWithNext

            candidates += TherapyEventEntity(
                id = derivedEvent.id,
                timestamp = derivedEvent.timestamp,
                type = "correction_bolus",
                payloadJson = gson.toJson(derivedEvent.payload)
            )
        }

        if (candidates.isEmpty()) return 0
        db.therapyDao().upsertAll(candidates)
        val inferredCreated = candidates.count { row ->
            row.id.startsWith("iob-inf-")
        }
        val microRecoveredCreated = candidates.count { row ->
            row.id.startsWith("iob-mb-rec-")
        }
        auditLogger.info(
            "nightscout_iob_insulin_inferred",
            mapOf(
                "since" to since,
                "samples" to points.size,
                "created" to candidates.size,
                "createdInferredIob" to inferredCreated,
                "createdRecoveredMicrobolus" to microRecoveredCreated
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

    private suspend fun repairNightscoutTreatmentMetadata(since: Long): Int {
        val normalizedSince = since.coerceAtLeast(0L)
        val repairs = db.therapyDao()
            .since(normalizedSince)
            .asSequence()
            .mapNotNull { row ->
                val payload = payloadFromJson(row.payloadJson)
                val source = payload["source"]?.trim()?.lowercase(Locale.US)
                val shouldRepair = source == SOURCE_NIGHTSCOUT_TREATMENT ||
                    source == SOURCE_LOCAL_NIGHTSCOUT_TREATMENT ||
                    row.id.startsWith("iob-inf-") ||
                    payload["classification"].isNullOrBlank()
                if (!shouldRepair) return@mapNotNull null
                val normalizedPayload = normalizeNightscoutFetchedPayloadStatic(
                    eventId = row.id,
                    payload = payload,
                    defaultSource = source ?: SOURCE_NIGHTSCOUT_TREATMENT
                )
                val normalizedType = normalizeEventType(
                    eventType = normalizedPayload["eventType"],
                    payload = normalizedPayload
                )
                val normalizedPayloadJson = gson.toJson(normalizedPayload)
                if (normalizedPayloadJson == row.payloadJson && normalizedType == row.type) {
                    return@mapNotNull null
                }
                TherapyEventEntity(
                    id = row.id,
                    timestamp = row.timestamp,
                    type = normalizedType,
                    payloadJson = normalizedPayloadJson
                )
            }
            .toList()
        if (repairs.isEmpty()) return 0
        db.therapyDao().upsertAll(repairs)
        auditLogger.info(
            "nightscout_treatment_metadata_repaired",
            mapOf(
                "since" to normalizedSince,
                "repaired" to repairs.size
            )
        )
        return repairs.size
    }

    companion object {
        internal data class TreatmentSelection(
            val treatments: List<NightscoutTreatment>,
            val skippedByClientWindow: Int
        )

        internal fun shouldFetchRemoteDeviceStatus(
            loopbackUrl: Boolean,
            degradedMode: Boolean
        ): Boolean = !loopbackUrl && !degradedMode

        internal fun shouldRunNightscoutTreatmentRepairStatic(
            importedTreatmentCount: Int,
            historicalBootstrap: Boolean,
            legacyCandidateCount: Int
        ): Boolean = importedTreatmentCount > 0 ||
            historicalBootstrap ||
            legacyCandidateCount > 0

        private const val SOURCE_NIGHTSCOUT = "nightscout"
        private const val SOURCE_CLOUD_PUSH = "cloud_push"
        private const val SOURCE_NIGHTSCOUT_TREATMENT = "nightscout_treatment"
        private const val SOURCE_LOCAL_NIGHTSCOUT_TREATMENT = "local_nightscout_treatment"
        private const val SOURCE_NIGHTSCOUT_DEVICESTATUS = "nightscout_devicestatus"
        private const val SOURCE_LOCAL_NIGHTSCOUT_DEVICESTATUS = "local_nightscout_devicestatus"
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
        private const val NIGHTSCOUT_DEVICESTATUS_SELF_SYNC_AUDIT_INTERVAL_MS = 15 * 60_000L
        private const val NIGHTSCOUT_FAILURE_BACKOFF_THRESHOLD = 2
        private const val NIGHTSCOUT_FAILURE_BACKOFF_MS = 15 * 60_000L
        private const val CLOUD_PUSH_MIN_INTERVAL_MS = 5 * 60_000L
        private const val THERAPY_BOOTSTRAP_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000
        private const val THERAPY_BOOTSTRAP_RETRY_MS = 12L * 60 * 60 * 1000
        private const val TREATMENT_CREATED_AT_BACKFILL_INTERVAL_MS = 30L * 60 * 1000
        private const val TREATMENT_CREATED_AT_PLATEAU_RETRY_MS = 10L * 60 * 1000
        private const val THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS = 10
        private const val SPARSE_REAL_FETCHED_MIN_COUNT = 4
        private const val REAL_FETCHED_FULL_MIN_COUNT = 10
        private const val TELEMETRY_THERAPY_RECOVERY_INCREMENTAL_LOOKBACK_MS = 24L * 60 * 60 * 1000
        private const val DEVICE_STATUS_THERAPY_CLUSTER_GAP_MS = 15L * 60 * 1000
        private const val DEVICE_STATUS_THERAPY_CONFIRM_WINDOW_MS = 20L * 60 * 1000
        private const val DEVICE_STATUS_THERAPY_SIGNATURE_REARM_GAP_MS = 90L * 60 * 1000
        private const val DEVICE_STATUS_THERAPY_MATCH_WINDOW_MS = 15L * 60 * 1000
        private const val DEVICE_STATUS_THERAPY_MIN_CLUSTER_SAMPLES = 2
        private const val DEVICE_STATUS_THERAPY_MIN_INSULIN_UNITS = 0.05
        private const val DEVICE_STATUS_THERAPY_MIN_CARBS_GRAMS = 0.5
        private const val DEVICE_STATUS_THERAPY_INSULIN_MATCH_EPSILON = 0.05
        private const val DEVICE_STATUS_THERAPY_CARBS_MATCH_EPSILON = 1.0
        private const val GLUCOSE_REPLACE_EPSILON = 0.01
        private const val MAX_FUTURE_TIMESTAMP_SKEW_MS = 24 * 60 * 60 * 1000L
        private const val IOB_INFERENCE_LOOKBACK_MS = 24L * 60 * 60 * 1000L
        private const val IOB_MICROBOLUS_MIN_DELTA_UNITS = 0.03
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
        private val DEVICE_STATUS_THERAPY_TELEMETRY_KEYS = listOf("insulin_units", "carbs_grams")

        internal data class IobDerivedInsulinEvent(
            val id: String,
            val timestamp: Long,
            val payload: Map<String, String>
        )

        internal fun buildIobDerivedInsulinEventStatic(
            ts: Long,
            prevIob: Double,
            currIob: Double,
            dtMin: Double
        ): IobDerivedInsulinEvent? {
            val delta = currIob - prevIob
            if (delta < IOB_MICROBOLUS_MIN_DELTA_UNITS) return null
            val inferredUnits = delta.coerceAtMost(IOB_INFERENCE_MAX_DELTA_UNITS)
            if (inferredUnits <= 0.0) return null

            val bucket = ts / IOB_INFERENCE_BUCKET_MS
            val unitsRounded = (inferredUnits * 100.0).roundToInt()
            val isMicrobolus = inferredUnits < IOB_INFERENCE_MIN_DELTA_UNITS
            val id = if (isMicrobolus) {
                "iob-mb-rec-$bucket-$unitsRounded"
            } else {
                "iob-inf-$bucket-$unitsRounded"
            }
            val payload = linkedMapOf<String, String>().apply {
                put("insulin", String.format(Locale.US, "%.3f", inferredUnits))
                put("source", if (isMicrobolus) "aaps_iob_microbolus" else "aaps_ns_iob")
                put("iobPrev", String.format(Locale.US, "%.3f", prevIob))
                put("iobNow", String.format(Locale.US, "%.3f", currIob))
                put("deltaIob", String.format(Locale.US, "%.3f", delta))
                put("dtMin", String.format(Locale.US, "%.2f", dtMin))
                if (isMicrobolus) {
                    put("recovered", "true")
                    put("method", "iob_micro_jump")
                    put("classification", "RECOVERED_MICROBOLUS")
                    put("confidence", "0.18")
                } else {
                    put("inferred", "true")
                    put("method", "iob_jump")
                    put("classification", "INFERRED_IOB")
                    put("confidence", "0.40")
                }
            }
            return IobDerivedInsulinEvent(
                id = id,
                timestamp = ts,
                payload = payload
            )
        }

        internal fun selectTreatmentsWithinClientWindowStatic(
            treatmentsByDate: List<NightscoutTreatment>,
            treatmentsByCreatedAt: List<NightscoutTreatment>,
            treatmentQuerySince: Long,
            treatmentCreatedAtQuerySince: Long
        ): TreatmentSelection {
            data class Candidate(
                val treatment: NightscoutTreatment,
                val clientWindowSince: Long
            )

            val candidates = buildList {
                treatmentsByDate.forEach { add(Candidate(it, treatmentQuerySince)) }
                treatmentsByCreatedAt.forEach { add(Candidate(it, treatmentCreatedAtQuerySince)) }
            }

            val deduped = LinkedHashMap<String, Candidate>()
            candidates.forEach { candidate ->
                val normalizedTs = parseNightscoutTimestampStatic(
                    createdAt = candidate.treatment.createdAt,
                    date = candidate.treatment.date,
                    mills = candidate.treatment.mills
                ) ?: 0L
                val key =
                    "${candidate.treatment.id.orEmpty()}|${candidate.treatment.eventType.orEmpty()}|$normalizedTs|" +
                        "${candidate.treatment.carbs ?: 0.0}|${candidate.treatment.insulin ?: 0.0}"
                deduped[key] = candidate
            }

            var skippedByClientWindow = 0
            val selected = deduped.values.mapNotNull { candidate ->
                val ts = parseNightscoutTimestampStatic(
                    createdAt = candidate.treatment.createdAt,
                    date = candidate.treatment.date,
                    mills = candidate.treatment.mills
                ) ?: return@mapNotNull null
                if (ts < candidate.clientWindowSince) {
                    skippedByClientWindow += 1
                    null
                } else {
                    candidate.treatment
                }
            }
            return TreatmentSelection(
                treatments = selected,
                skippedByClientWindow = skippedByClientWindow
            )
        }

        internal fun hasRecoveryRelevantTreatmentsOutsideClientWindowStatic(
            treatments: List<NightscoutTreatment>,
            clientWindowSince: Long,
            source: String
        ): Boolean {
            return treatments.any { treatment ->
                val normalizedTs = parseNightscoutTimestampStatic(
                    createdAt = treatment.createdAt,
                    date = treatment.date,
                    mills = treatment.mills
                ) ?: return@any false
                if (normalizedTs >= clientWindowSince) return@any false
                val payload = buildNightscoutTreatmentPayloadStatic(treatment, source)
                when (normalizeTreatmentTypeStatic(treatment.eventType, payload)) {
                    "meal_bolus", "correction_bolus", "carbs" -> true
                    else -> false
                }
            }
        }

        private fun parseNightscoutTimestampStatic(
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
            return normalizeTimestampStatic(
                createdAtMillis
                    ?: normalizeTimestampStatic(mills ?: 0L)
                    ?: normalizeTimestampStatic(date ?: 0L)
                    ?: return null
            )
        }

        private fun normalizeTimestampStatic(raw: Long): Long? {
            if (raw <= 0L) return null
            val now = System.currentTimeMillis()
            val millis = if (raw < 10_000_000_000L) raw * 1000L else raw
            return if (millis > now + MAX_FUTURE_TIMESTAMP_SKEW_MS) now else millis
        }

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

        internal fun normalizeNightscoutFetchedPayloadStatic(
            eventId: String?,
            payload: Map<String, String>,
            defaultSource: String
        ): LinkedHashMap<String, String> {
            val normalized = LinkedHashMap(payload)
            val existingSource = normalized["source"]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.US)
                ?: defaultSource
            val notes = normalized["notes"].orEmpty()
            val reason = normalized["reason"].orEmpty()
            val enteredBy = normalized["enteredBy"].orEmpty()
            val inferred = normalized["inferred"]?.trim()?.equals("true", ignoreCase = true) == true
            val iobJump = normalized["method"]?.trim()?.equals("iob_jump", ignoreCase = true) == true
            val looksLikeIobInference = eventId?.startsWith("iob-inf-") == true || (inferred && iobJump)
            val looksLikeUamSynthetic = notes.contains("UAM_ENGINE|", ignoreCase = true) ||
                reason.contains("uam_engine", ignoreCase = true)
            val looksLikeLocalCopilotAction =
                eventId?.startsWith("local-ns-") == true ||
                    notes.contains("copilot:", ignoreCase = true) ||
                    reason.contains("copilot:", ignoreCase = true) ||
                    enteredBy.contains("copilot", ignoreCase = true)

            return when {
                looksLikeIobInference -> normalized.apply {
                    put("source", "aaps_ns_iob")
                    put("inferred", "true")
                    put("method", "iob_jump")
                    put("classification", "INFERRED_IOB")
                }
                looksLikeUamSynthetic -> normalized.apply {
                    put("source", "uam_engine")
                    put("synthetic", "true")
                    put("classification", "UAM_SYNTHETIC")
                }
                looksLikeLocalCopilotAction -> normalized.apply {
                    put("source", "local_nightscout_treatment")
                    put("classification", "LOCAL_ACTION")
                    if (get("synthetic")?.trim()?.equals("true", ignoreCase = true) == true) {
                        remove("synthetic")
                    }
                }
                else -> normalized.apply {
                    put("source", existingSource)
                    put("classification", "REAL_FETCHED")
                    if (get("synthetic")?.trim()?.equals("true", ignoreCase = true) == true) {
                        remove("synthetic")
                    }
                }
            }
        }

        internal fun therapyHistorySourceModeStatic(
            rawCount: Int,
            inferredCount: Int,
            realFetchedCount: Int,
            recoveredCount: Int,
            usableCount: Int
        ): String = when {
            realFetchedCount in SPARSE_REAL_FETCHED_MIN_COUNT until REAL_FETCHED_FULL_MIN_COUNT -> "SPARSE_REAL_FETCHED"
            realFetchedCount > 0 -> "REAL_FETCHED"
            recoveredCount > 0 -> "RECOVERED_PLATEAU_ONLY"
            rawCount > 0 && inferredCount >= rawCount -> "SYNTHETIC_ONLY"
            rawCount > 0 -> "MIXED_NO_REAL"
            else -> "EMPTY"
        }

        internal fun summarizeTreatmentEventTypesStatic(
            treatments: List<NightscoutTreatment>
        ): String = treatments
            .groupingBy {
                it.eventType
                    ?.trim()
                    ?.ifBlank { "<blank>" }
                    ?: "<null>"
            }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(10)
            .joinToString(separator = ",") { "${it.first}:${it.second}" }

        internal fun buildTherapyHistoryTelemetryRowsStatic(
            nowTs: Long,
            sourceMode: String,
            rawCount: Int,
            inferredCount: Int,
            realFetchedCount: Int,
            recoveredCount: Int,
            usableCount: Int,
            needsRecovery: Boolean,
            plateauOnly: Boolean,
            syntheticRatioPct: Double?,
            fetchedTreatmentCount: Int,
            fetchedInsulinLikeCount: Int,
            fetchedCarbLikeCount: Int,
            fetchedLocalActionCount: Int,
            fetchedByDateEventTypeSummary: String,
            fetchedByCreatedAtEventTypeSummary: String,
            normalizedTypeSummary: String,
            normalizedClassificationSummary: String,
            upstreamTempTargetOnly: Boolean
        ): List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity> {
            val source = "nightscout_sync"
            fun numeric(
                key: String,
                value: Double?,
                unit: String? = null
            ) = io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                id = "ns-sync-$nowTs-$key",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = value,
                valueText = null,
                unit = unit,
                quality = "derived"
            )
            fun text(
                key: String,
                value: String
            ) = io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                id = "ns-sync-$nowTs-$key",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = value,
                unit = null,
                quality = "derived"
            )
            return buildList {
                add(text("therapy_history_source_mode", sourceMode))
                add(numeric("therapy_history_raw_insulin_30d", rawCount.toDouble()))
                add(numeric("therapy_history_inferred_insulin_30d", inferredCount.toDouble()))
                add(numeric("therapy_history_real_fetched_insulin_30d", realFetchedCount.toDouble()))
                add(numeric("therapy_history_recovered_insulin_30d", recoveredCount.toDouble()))
                add(numeric("therapy_history_usable_insulin_30d", usableCount.toDouble()))
                add(numeric("therapy_history_bootstrap_needed", if (needsRecovery) 1.0 else 0.0))
                add(numeric("therapy_history_plateau_only", if (plateauOnly) 1.0 else 0.0))
                syntheticRatioPct?.let { add(numeric("therapy_history_synthetic_ratio_pct", it, "%")) }
                add(numeric("therapy_history_last_sync_treatment_count", fetchedTreatmentCount.toDouble()))
                add(numeric("therapy_history_last_sync_insulin_like_count", fetchedInsulinLikeCount.toDouble()))
                add(numeric("therapy_history_last_sync_carb_like_count", fetchedCarbLikeCount.toDouble()))
                add(numeric("therapy_history_last_sync_local_action_count", fetchedLocalActionCount.toDouble()))
                add(numeric("therapy_history_upstream_temp_target_only", if (upstreamTempTargetOnly) 1.0 else 0.0))
                add(text("therapy_history_last_sync_event_types_date", fetchedByDateEventTypeSummary))
                add(text("therapy_history_last_sync_event_types_created_at", fetchedByCreatedAtEventTypeSummary))
                add(text("therapy_history_last_sync_normalized_types", normalizedTypeSummary))
                add(text("therapy_history_last_sync_normalized_classification", normalizedClassificationSummary))
            }
        }


        internal fun buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows: List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>,
            existingTherapyRows: List<TherapyEventEntity>,
            existingRecoveredRows: List<TherapyEventEntity> = emptyList(),
            gson: Gson = Gson()
        ): List<TherapyEventEntity> {
            if (telemetryRows.isEmpty()) return emptyList()
            val signals = telemetryRows
                .asSequence()
                .filter { it.source == SOURCE_NIGHTSCOUT_DEVICESTATUS }
                .groupBy { it.timestamp }
                .mapNotNull { (timestamp, rows) ->
                    val insulin = rows.firstNotNullOfOrNull { row ->
                        row.valueDouble?.takeIf { row.key == "insulin_units" }
                    }
                    val carbs = rows.firstNotNullOfOrNull { row ->
                        row.valueDouble?.takeIf { row.key == "carbs_grams" }
                    }
                    val normalizedInsulin = insulin?.takeIf { it >= DEVICE_STATUS_THERAPY_MIN_INSULIN_UNITS }
                    val normalizedCarbs = carbs?.takeIf { it >= DEVICE_STATUS_THERAPY_MIN_CARBS_GRAMS }
                    if (normalizedInsulin == null && normalizedCarbs == null) {
                        null
                    } else {
                        DeviceStatusTherapySignal(
                            timestamp = timestamp,
                            insulinUnits = normalizedInsulin,
                            carbsGrams = normalizedCarbs
                        )
                    }
                }
                .sortedBy { it.timestamp }
            if (signals.isEmpty()) return emptyList()
            val existingRowsById = existingTherapyRows.associateBy { it.id }
            val recovered = mutableListOf<TherapyEventEntity>()
            val signatureArmed = mutableMapOf<DeviceStatusTherapyDoseSignature, Boolean>()
            val lastObservedAtBySignature = mutableMapOf<DeviceStatusTherapyDoseSignature, Long>()
            var candidateStart: DeviceStatusTherapySignal? = null
            var candidateLast: DeviceStatusTherapySignal? = null
            var candidateSignature: DeviceStatusTherapyDoseSignature? = null
            var candidateCount = 0
            var candidateClosed = false
            var previousSignalSignature: DeviceStatusTherapyDoseSignature? = null

            fun resetCandidate(signal: DeviceStatusTherapySignal) {
                candidateStart = signal
                candidateLast = signal
                candidateSignature = DeviceStatusTherapyDoseSignature(
                    insulinUnits = signal.insulinUnits,
                    carbsGrams = signal.carbsGrams
                )
                candidateCount = 1
                candidateClosed = false
            }

            fun maybeEmitCandidate(confirmationSignal: DeviceStatusTherapySignal) {
                val start = candidateStart ?: return
                val signature = candidateSignature ?: return
                if (candidateClosed || candidateCount < DEVICE_STATUS_THERAPY_MIN_CLUSTER_SAMPLES) return
                candidateClosed = true
                if (signatureArmed[signature] == false) return
                val type = when {
                    start.insulinUnits != null && start.carbsGrams != null -> "meal_bolus"
                    start.insulinUnits != null -> "correction_bolus"
                    start.carbsGrams != null -> "carbs"
                    else -> null
                } ?: return
                if (hasCoveredExistingRealTherapyStatic(start, confirmationSignal, type, existingTherapyRows)) return
                val insulinRounded = ((start.insulinUnits ?: 0.0) * 100.0).roundToInt()
                val carbsRounded = ((start.carbsGrams ?: 0.0) * 10.0).roundToInt()
                val id = "ns-ds-rec-${start.timestamp / IOB_INFERENCE_BUCKET_MS}-$type-$insulinRounded-$carbsRounded"
                if (existingRowsById.containsKey(id) || recovered.any { it.id == id }) return
                if (hasCoveredExistingRecoveredTherapyStatic(start, type, existingRecoveredRows)) return
                val payload = linkedMapOf<String, String>().apply {
                    put("eventType", when (type) {
                        "meal_bolus" -> "Meal Bolus"
                        "correction_bolus" -> "Correction Bolus"
                        else -> "Carb Correction"
                    })
                    start.insulinUnits?.let {
                        val value = String.format(Locale.US, "%.3f", it)
                        put("insulin", value)
                        put("enteredInsulin", value)
                        put("bolusUnits", value)
                    }
                    start.carbsGrams?.let {
                        val value = String.format(Locale.US, "%.1f", it)
                        put("carbs", value)
                        put("enteredCarbs", value)
                        put("mealCarbs", value)
                    }
                    put("source", "nightscout_devicestatus_recovered")
                    put("recoveredFromTelemetry", "true")
                    put("recoverySource", SOURCE_NIGHTSCOUT_DEVICESTATUS)
                    put("classification", "REAL_RECOVERED")
                    put("clusterSamples", candidateCount.toString())
                    put("clusterStartTs", start.timestamp.toString())
                    put("clusterEndTs", confirmationSignal.timestamp.toString())
                    put("boundedWindowMs", (confirmationSignal.timestamp - start.timestamp).toString())
                }
                recovered += TherapyEventEntity(
                    id = id,
                    timestamp = start.timestamp,
                    type = type,
                    payloadJson = gson.toJson(payload)
                )
                signatureArmed[signature] = false
            }

            signals.forEach { signal ->
                val signalSignature = DeviceStatusTherapyDoseSignature(
                    insulinUnits = signal.insulinUnits,
                    carbsGrams = signal.carbsGrams
                )
                val previousObservedAt = lastObservedAtBySignature[signalSignature]
                if (previousObservedAt == null ||
                    (signal.timestamp - previousObservedAt) >= DEVICE_STATUS_THERAPY_SIGNATURE_REARM_GAP_MS
                ) {
                    signatureArmed[signalSignature] = true
                }
                val previousSignature = previousSignalSignature
                if (previousSignature != null && previousSignature != signalSignature) {
                    signatureArmed[previousSignature] = true
                }
                lastObservedAtBySignature[signalSignature] = signal.timestamp
                previousSignalSignature = signalSignature

                val currentStart = candidateStart
                val currentLast = candidateLast
                val currentSignature = candidateSignature
                if (currentStart == null || currentLast == null || currentSignature == null) {
                    resetCandidate(signal)
                    return@forEach
                }
                val sameDose = currentSignature == signalSignature
                val withinGap = (signal.timestamp - currentLast.timestamp) <= DEVICE_STATUS_THERAPY_CLUSTER_GAP_MS
                if (!sameDose || !withinGap) {
                    resetCandidate(signal)
                    return@forEach
                }
                candidateLast = signal
                if (candidateClosed) {
                    return@forEach
                }
                val withinConfirmationWindow =
                    (signal.timestamp - currentStart.timestamp) <= DEVICE_STATUS_THERAPY_CONFIRM_WINDOW_MS
                if (!withinConfirmationWindow) {
                    candidateClosed = true
                    return@forEach
                }
                candidateCount += 1
                if (candidateCount >= DEVICE_STATUS_THERAPY_MIN_CLUSTER_SAMPLES) {
                    maybeEmitCandidate(signal)
                }
            }
            return recovered
        }

        internal fun selectDeviceStatusTherapyTelemetryRowsStatic(
            telemetryRows: List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>
        ): List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity> {
            if (telemetryRows.isEmpty()) return emptyList()
            val upstreamRows = telemetryRows.filter { it.source == SOURCE_NIGHTSCOUT_DEVICESTATUS }
            return if (upstreamRows.isNotEmpty()) {
                upstreamRows
            } else {
                telemetryRows.filter { it.source == SOURCE_LOCAL_NIGHTSCOUT_DEVICESTATUS }
            }
        }

        private fun sameTelemetryTherapyDoseStatic(
            first: DeviceStatusTherapySignal,
            second: DeviceStatusTherapySignal
        ): Boolean {
            return abs((first.insulinUnits ?: 0.0) - (second.insulinUnits ?: 0.0)) <= DEVICE_STATUS_THERAPY_INSULIN_MATCH_EPSILON &&
                abs((first.carbsGrams ?: 0.0) - (second.carbsGrams ?: 0.0)) <= DEVICE_STATUS_THERAPY_CARBS_MATCH_EPSILON
        }

        private fun hasCoveredExistingRealTherapyStatic(
            start: DeviceStatusTherapySignal,
            end: DeviceStatusTherapySignal,
            type: String,
            existingTherapyRows: List<TherapyEventEntity>
        ): Boolean {
            return existingTherapyRows.any { row ->
                if (row.type != type) return@any false
                val payload = Gson().fromJson(row.payloadJson, Map::class.java)
                    ?.entries
                    ?.associate { it.key.toString() to (it.value?.toString() ?: "") }
                    .orEmpty()
                val inferred = payload["inferred"]?.equals("true", ignoreCase = true) == true
                if (inferred) return@any false
                val existingInsulin = payloadNumericStatic(payload, "insulin", "units", "bolusUnits", "enteredInsulin")
                val existingCarbs = payloadNumericStatic(payload, "carbs", "grams", "enteredCarbs", "mealCarbs")
                abs(row.timestamp - start.timestamp) <= DEVICE_STATUS_THERAPY_MATCH_WINDOW_MS &&
                    abs((existingInsulin ?: 0.0) - (start.insulinUnits ?: 0.0)) <= DEVICE_STATUS_THERAPY_INSULIN_MATCH_EPSILON &&
                    abs((existingCarbs ?: 0.0) - (start.carbsGrams ?: 0.0)) <= DEVICE_STATUS_THERAPY_CARBS_MATCH_EPSILON
            }
        }

        private fun hasCoveredExistingRecoveredTherapyStatic(
            start: DeviceStatusTherapySignal,
            type: String,
            existingRecoveredRows: List<TherapyEventEntity>
        ): Boolean {
            val startSignature = DeviceStatusTherapyDoseSignature(
                insulinUnits = start.insulinUnits,
                carbsGrams = start.carbsGrams
            )
            return existingRecoveredRows.any { row ->
                if (row.type != type) return@any false
                val payload = Gson().fromJson(row.payloadJson, Map::class.java)
                    ?.entries
                    ?.associate { it.key.toString() to (it.value?.toString() ?: "") }
                    .orEmpty()
                if (payload["source"] != "nightscout_devicestatus_recovered") return@any false
                val existingSignature = DeviceStatusTherapyDoseSignature(
                    insulinUnits = payloadNumericStatic(payload, "insulin", "units", "bolusUnits", "enteredInsulin"),
                    carbsGrams = payloadNumericStatic(payload, "carbs", "grams", "enteredCarbs", "mealCarbs")
                )
                if (existingSignature != startSignature) return@any false
                abs(row.timestamp - start.timestamp) < DEVICE_STATUS_THERAPY_SIGNATURE_REARM_GAP_MS
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

        internal data class DeviceStatusTherapySignal(
            val timestamp: Long,
            val insulinUnits: Double?,
            val carbsGrams: Double?
        )

        internal data class DeviceStatusTherapyDoseSignature(
            val insulinUnits: Double?,
            val carbsGrams: Double?
        )
    }

    private data class TelemetryTherapyRecoveryResult(
        val totalRecovered: Int,
        val insulinLikeRecovered: Int,
        val carbLikeRecovered: Int
    ) {
        companion object {
            val EMPTY = TelemetryTherapyRecoveryResult(
                totalRecovered = 0,
                insulinLikeRecovered = 0,
                carbLikeRecovered = 0
            )
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
