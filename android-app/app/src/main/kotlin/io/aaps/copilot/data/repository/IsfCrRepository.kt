package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.IsfCrEvidenceEntity
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import io.aaps.copilot.data.local.entity.IsfCrSnapshotEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.isfcr.IsfCrEngine
import io.aaps.copilot.domain.isfcr.IsfCrEvidenceSample
import io.aaps.copilot.domain.isfcr.IsfCrHistoryBundle
import io.aaps.copilot.domain.isfcr.IsfCrModelState
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.isfcr.IsfCrSampleType
import io.aaps.copilot.domain.isfcr.IsfCrSettings
import io.aaps.copilot.domain.isfcr.PhysioContextTag
import io.aaps.copilot.domain.events.CompensationEventProfilePolicy
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal const val ISFCR_BASE_MODEL_REVISION_FACTOR = "base_model_updated_at_ms"
internal const val ISFCR_PROFILE_REVISION_FACTOR = "profile_estimate_timestamp_ms"

internal data class IsfCrInputGeneration(
    val modelRevision: Long,
    val profileRevision: Long
)

internal fun isfCrInputGenerationOrNull(
    modelUpdatedAt: Long?,
    profileTimestamp: Long?
): IsfCrInputGeneration? {
    val modelRevision = modelUpdatedAt?.takeIf { it > 0L } ?: return null
    val profileRevision = profileTimestamp?.takeIf { it > 0L } ?: return null
    return IsfCrInputGeneration(modelRevision, profileRevision)
}

internal fun bindRealtimeSnapshotToInputGeneration(
    snapshot: IsfCrRealtimeSnapshot,
    modelUpdatedAt: Long?,
    profileTimestamp: Long?
): IsfCrRealtimeSnapshot {
    val generation = requireNotNull(
        isfCrInputGenerationOrNull(modelUpdatedAt, profileTimestamp)
    ) {
        "ISF/CR input generation requires active model and profile revisions"
    }
    return snapshot.copy(
        factors = snapshot.factors + mapOf(
            ISFCR_BASE_MODEL_REVISION_FACTOR to generation.modelRevision.toDouble(),
            ISFCR_PROFILE_REVISION_FACTOR to generation.profileRevision.toDouble()
        )
    )
}

private fun exactPositiveRevision(raw: Double?): Long? {
    raw ?: return null
    if (!raw.isFinite() || raw <= 0.0) return null
    val revision = raw.toLong()
    return revision.takeIf { it.toDouble() == raw }
}

internal fun realtimeSnapshotInputGeneration(snapshot: IsfCrRealtimeSnapshot): IsfCrInputGeneration? {
    val modelRevision = exactPositiveRevision(snapshot.factors[ISFCR_BASE_MODEL_REVISION_FACTOR])
        ?: return null
    val profileRevision = exactPositiveRevision(snapshot.factors[ISFCR_PROFILE_REVISION_FACTOR])
        ?: return null
    return IsfCrInputGeneration(modelRevision, profileRevision)
}

internal fun realtimeSnapshotMatchesInputGeneration(
    snapshot: IsfCrRealtimeSnapshot,
    modelUpdatedAt: Long?,
    profileTimestamp: Long?
): Boolean {
    val currentGeneration = isfCrInputGenerationOrNull(modelUpdatedAt, profileTimestamp)
        ?: return false
    return realtimeSnapshotInputGeneration(snapshot) == currentGeneration
}

private val REALTIME_ISFCR_SET_CHANGE_TYPES = setOf(
    "infusion_set_change",
    "site_change",
    "set_change",
    "cannula_change"
)

private val REALTIME_ISFCR_SENSOR_CHANGE_TYPES = setOf(
    "sensor_change",
    "cgm_sensor_change",
    "sensor_start",
    "sensor_started"
)

internal fun deliveryDiagnosticEventsFromTelemetry(
    rows: Iterable<TelemetrySampleEntity>
): List<CompensationEvent> = EventTimelineRepository().deliveryDiagnosticEvents(
    deliveryDiagnosticSamplesFromTelemetry(
        rows.map { row ->
            DeliveryTrustTelemetryValue(row.timestamp, row.source, row.key, row.valueDouble)
        }
    )
)

internal fun shouldWidenRealtimeTherapyScan(
    rawRowCount: Int,
    relevantRowCount: Int,
    currentLimit: Int
): Boolean {
    if (currentLimit <= 0) return false
    if (rawRowCount < currentLimit) return false
    return relevantRowCount < 24
}

internal fun isRelevantRealtimeIsfCrTherapyEvent(event: TherapyEvent): Boolean {
    val type = event.type.trim().lowercase()
    if (type in REALTIME_ISFCR_SET_CHANGE_TYPES || type in REALTIME_ISFCR_SENSOR_CHANGE_TYPES) {
        return true
    }
    if (type == "meal_bolus" || type == "correction_bolus" || type == "carbs") {
        return true
    }
    if (realtimePayloadDouble(event, "units", "insulin", "enteredInsulin", "bolusUnits", "insulinUnits", "amount") > 0.0) {
        return true
    }
    if (realtimePayloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs") > 0.0) {
        return true
    }
    return false
}

internal fun sanitizeRealtimeIsfCrTherapyRows(
    rows: List<TherapyEventEntity>,
    gson: Gson
): List<TherapyEvent> = TherapySanitizer.toDomainEvents(rows, gson)
    .filter(::isRelevantRealtimeIsfCrTherapyEvent)

internal fun mergeIsfRevisionModelState(
    refitted: IsfCrModelState,
    existing: IsfCrModelState
): IsfCrModelState = refitted.copy(
    hourlyCr = existing.hourlyCr,
    params = refitted.params.filterKeys { !it.contains("cr", ignoreCase = true) } +
        existing.params.filterKeys { it.contains("cr", ignoreCase = true) },
    fitMetrics = refitted.fitMetrics.filterKeys { !it.contains("cr", ignoreCase = true) } +
        existing.fitMetrics.filterKeys { it.contains("cr", ignoreCase = true) }
)

internal fun mergeIsfRevisionRealtimeSnapshot(
    refitted: IsfCrRealtimeSnapshot,
    existing: IsfCrRealtimeSnapshot
): IsfCrRealtimeSnapshot = refitted.copy(
    crEff = existing.crEff,
    crBase = existing.crBase,
    ciCrLow = existing.ciCrLow,
    ciCrHigh = existing.ciCrHigh,
    crEvidenceCount = existing.crEvidenceCount
)

private fun realtimePayloadDouble(event: TherapyEvent, vararg keys: String): Double {
    val payload = event.payload
    keys.forEach { key ->
        payload.entries.firstOrNull { entry -> entry.key.equals(key, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.let { return it }
    }
    return 0.0
}

class IsfCrRepository(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val glucoseCalibrationRepository: GlucoseCalibrationRepository,
    private val engine: IsfCrEngine = IsfCrEngine()
) {

    private val realtimeDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun fitBaseModel(
        settings: AppSettings,
        nowTs: Long = System.currentTimeMillis(),
        resetExistingState: Boolean = false
    ): IsfCrModelState = withContext(Dispatchers.Default) {
        val config = settings.toIsfCrSettings()
        val historyStart = nowTs - config.lookbackDays * DAY_MS
        val glucose = glucoseCalibrationRepository.resolveDomainGlucoseHistory(
            rawGlucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart)),
            nowTs = nowTs
        )
        val therapy = TherapySanitizer.toDomainEvents(db.therapyDao().since(historyStart), gson)
        val telemetry = mutableListOf<io.aaps.copilot.domain.predict.TelemetrySignal>()
        val deliveryTelemetry = mutableListOf<DeliveryTrustTelemetryValue>()
        val deliveryHistoryStart = deliveryDiagnosticLookbackFrom(historyStart)
        if (deliveryHistoryStart < historyStart) {
            scanTelemetryByKeysPaged(
                telemetryDao = db.telemetryDao(),
                since = deliveryHistoryStart,
                through = historyStart,
                keys = DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS,
                callerTag = "isfcr_fit_base_delivery_lookback",
                auditLogger = auditLogger
            ) { page ->
                deliveryTelemetry += page.map { row ->
                    DeliveryTrustTelemetryValue(row.timestamp, row.source, row.key, row.valueDouble)
                }
            }
        }
        scanTelemetryByKeysPaged(
            telemetryDao = db.telemetryDao(),
            since = historyStart,
            keys = ISFCR_BASE_FIT_TELEMETRY_KEYS,
            callerTag = "isfcr_fit_base_model",
            auditLogger = auditLogger
        ) { page ->
            deliveryTelemetry += page.map { row ->
                DeliveryTrustTelemetryValue(row.timestamp, row.source, row.key, row.valueDouble)
            }
            telemetry += page.map { entity ->
                io.aaps.copilot.domain.predict.TelemetrySignal(
                    ts = entity.timestamp,
                    key = entity.key,
                    valueDouble = entity.valueDouble,
                    valueText = entity.valueText,
                    source = entity.source,
                    quality = entity.quality
                )
            }
        }
        val tags = db.physioContextTagDao().between(historyStart, nowTs).map { it.toDomain() }
        val eventTimeline = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = therapy,
                contextTags = db.physioContextTagDao().between(historyStart, nowTs),
                deliveryDiagnostics = deliveryDiagnosticEventsForWindow(
                    rows = deliveryTelemetry,
                    fromTs = historyStart,
                    throughTs = nowTs
                )
            ),
            nowTs
        )
        val storedActiveState = db.isfCrModelStateDao().active()?.toDomain()
        val activeState = storedActiveState.takeUnless { resetExistingState }
        val fallbackProfile = db.profileEstimateDao().active()
        val fallbackIsf = (fallbackProfile?.calculatedIsfMmolPerUnit ?: fallbackProfile?.isfMmolPerUnit ?: DEFAULT_ISF)
            .coerceIn(0.8, 18.0)
        val fallbackCr = (fallbackProfile?.calculatedCrGramPerUnit ?: fallbackProfile?.crGramPerUnit ?: DEFAULT_CR)
            .coerceIn(2.0, 60.0)

        val fit = engine.fitBaseModel(
            history = IsfCrHistoryBundle(
                glucose = glucose,
                therapy = therapy,
                telemetry = telemetry,
                tags = tags,
                events = eventTimeline
            ),
            settings = config,
            existingState = activeState,
            fallbackIsf = fallbackIsf,
            fallbackCr = fallbackCr
        )

        val persistedState = if (resetExistingState && storedActiveState != null) {
            mergeIsfRevisionModelState(refitted = fit.state, existing = storedActiveState)
        } else {
            fit.state
        }
        val persistedEvidence = if (resetExistingState && storedActiveState != null) {
            fit.evidence.filter { it.sampleType == IsfCrSampleType.ISF }
        } else {
            fit.evidence
        }
        val publishedState = db.withTransaction {
            val publicationRevision = nextStrictlyMonotonicPublicationRevision(
                requestedRevision = persistedState.updatedAt,
                currentRevisions = listOfNotNull(db.isfCrModelStateDao().active()?.updatedAt)
            )
            val versionedState = persistedState.copy(updatedAt = publicationRevision)
            if (resetExistingState) {
                db.isfCrEvidenceDao().deleteBySampleType(IsfCrSampleType.ISF.name)
            }
            db.isfCrModelStateDao().upsert(versionedState.toEntity())
            if (persistedEvidence.isNotEmpty()) {
                db.isfCrEvidenceDao().upsertAll(persistedEvidence.map { it.toEntity(gson) })
            }
            versionedState
        }
        if (!resetExistingState) {
            cleanupRetention(settings = config, nowTs = nowTs)
        }

        auditLogger.info(
            "isfcr_evidence_extracted",
            mapOf(
                "phase" to "base_fit",
                "isfEvidence" to persistedEvidence.count { it.sampleType == IsfCrSampleType.ISF },
                "crEvidence" to persistedEvidence.count { it.sampleType == IsfCrSampleType.CR },
                "droppedEvidence" to fit.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(fit.droppedReasonCounts)
            )
        )

        auditLogger.info(
            "isfcr_base_fit_completed",
            mapOf(
                "isfEvidence" to persistedEvidence.count { it.sampleType == IsfCrSampleType.ISF },
                "crEvidence" to persistedEvidence.count { it.sampleType == IsfCrSampleType.CR },
                "droppedEvidence" to fit.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(fit.droppedReasonCounts)
            )
        )

        publishedState
    }

    suspend fun computeRealtimeSnapshot(
        settings: AppSettings,
        nowTs: Long = System.currentTimeMillis(),
        resetIsfRevisionState: Boolean = false
    ): IsfCrRealtimeSnapshot = withContext(realtimeDispatcher) {
        val config = settings.toIsfCrSettings()
        // Realtime path must stay cheap and deterministic; bound each input stream explicitly.
        val recentStart = nowTs - ISFCR_REALTIME_LOOKBACK_HOURS * HOUR_MS
        val glucoseRows = db.glucoseDao()
            .sinceDescLimit(recentStart, ISFCR_REALTIME_MAX_GLUCOSE_ROWS)
            .asReversed()
        val glucose = glucoseCalibrationRepository.resolveDomainGlucoseHistory(
            rawGlucose = glucoseRows,
            nowTs = nowTs
        )
        var therapyRows = db.therapyDao()
            .sinceDescLimit(recentStart, ISFCR_REALTIME_MAX_THERAPY_ROWS)
            .asReversed()
        var therapy = sanitizeRealtimeIsfCrTherapyRows(therapyRows, gson)
        if (shouldWidenRealtimeTherapyScan(therapyRows.size, therapy.size, ISFCR_REALTIME_MAX_THERAPY_ROWS)) {
            therapyRows = db.therapyDao()
                .sinceDescLimit(recentStart, ISFCR_REALTIME_MAX_THERAPY_ROWS_WIDE)
                .asReversed()
            therapy = sanitizeRealtimeIsfCrTherapyRows(therapyRows, gson)
        }
        val telemetryRowsRaw = db.telemetryDao()
            .sinceByKeysDescLimit(
                since = recentStart,
                keys = ISFCR_REALTIME_TELEMETRY_KEYS,
                limit = ISFCR_REALTIME_TELEMETRY_QUERY_LIMIT
            )
            .asReversed()
        val telemetryRows = trimRealtimeTelemetryRows(
            rows = telemetryRowsRaw,
            nowTs = nowTs
        )
        val deliveryTelemetry = db.telemetryDao().betweenForClinicalReport(
            fromTs = deliveryDiagnosticLookbackFrom(recentStart),
            toTs = nowTs,
            keys = DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS
        ).map { row ->
            DeliveryTrustTelemetryValue(row.ts, row.source, row.key, row.value)
        }
        val telemetry = telemetryRows.map { entity ->
            io.aaps.copilot.domain.predict.TelemetrySignal(
                ts = entity.timestamp,
                key = entity.key,
                valueDouble = entity.valueDouble,
                valueText = entity.valueText,
                source = entity.source,
                quality = entity.quality
            )
        }
        val activeTags = db.physioContextTagDao().activeAt(nowTs).map { it.toDomain() }
        val eventTimeline = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = therapy,
                contextTags = db.physioContextTagDao().activeAt(nowTs),
                deliveryDiagnostics = deliveryDiagnosticEventsForWindow(
                    rows = deliveryTelemetry,
                    fromTs = recentStart,
                    throughTs = nowTs
                )
            ),
            nowTs
        )
        val realtimeInputs = db.withTransaction {
            Triple(
                db.isfCrModelStateDao().active(),
                db.isfCrSnapshotDao().latest(),
                db.profileEstimateDao().active()
            )
        }
        val activeStateEntity = realtimeInputs.first
        val previousSnapshotEntity = realtimeInputs.second
        val fallbackProfile = realtimeInputs.third
        val activeModelRevision = activeStateEntity?.updatedAt
        val activeProfileRevision = fallbackProfile?.timestamp
        val activeState = activeStateEntity?.toDomain()
        val previousSnapshot = previousSnapshotEntity
            ?.toDomain(gson)
            ?.takeIf {
                realtimeSnapshotMatchesInputGeneration(
                    snapshot = it,
                    modelUpdatedAt = activeModelRevision,
                    profileTimestamp = activeProfileRevision
                )
            }
        val fallbackIsf = (fallbackProfile?.calculatedIsfMmolPerUnit ?: fallbackProfile?.isfMmolPerUnit ?: DEFAULT_ISF)
            .coerceIn(0.8, 18.0)
        val fallbackCr = (fallbackProfile?.calculatedCrGramPerUnit ?: fallbackProfile?.crGramPerUnit ?: DEFAULT_CR)
            .coerceIn(2.0, 60.0)

        val result = engine.computeRealtime(
            nowTs = nowTs,
            glucose = glucose,
            therapy = therapy,
            telemetry = telemetry,
            tags = activeTags,
            events = eventTimeline,
            activeModel = activeState,
            previousSnapshot = previousSnapshot,
            settings = config,
            fallbackIsf = fallbackIsf,
            fallbackCr = fallbackCr,
            resetPreviousIsfRateLimit = resetIsfRevisionState
        )
        val factors = result.snapshot.factors
        val setAgeHours = factors["set_age_hours"]
        val sensorAgeHours = factors["sensor_age_hours"]
        val setFactor = factors["set_factor"]
        val sensorAgeFactor = factors["sensor_age_factor"]
        val sensorFactor = factors["sensor_factor"]
        val sensorQualitySuspectFalseLow = factors["sensor_quality_suspect_false_low"] ?: 0.0
        val latentStress = factors["latent_stress"]
        val uamPenaltyFactor = factors["uam_penalty_factor"]
        val contextAmbiguity = listOfNotNull(
            factors["manual_stress_tag"],
            factors["manual_illness_tag"],
            factors["manual_hormone_tag"],
            factors["manual_steroid_tag"],
            factors["manual_dawn_tag"],
            latentStress
        ).maxOrNull()
            ?: 0.0
        val wearConfidencePenalty = (
            (((setAgeHours ?: 0.0) - 72.0).coerceAtLeast(0.0) / 96.0).coerceIn(0.0, 1.0) * 0.08 +
                (((sensorAgeHours ?: 0.0) - 120.0).coerceAtLeast(0.0) / 96.0).coerceIn(0.0, 1.0) * 0.08
            )
            .coerceIn(0.0, 0.16)

        val mergedSnapshot = if (resetIsfRevisionState && previousSnapshot != null) {
            mergeIsfRevisionRealtimeSnapshot(refitted = result.snapshot, existing = previousSnapshot)
        } else {
            result.snapshot
        }
        val persistedSnapshot = bindRealtimeSnapshotToInputGeneration(
            snapshot = mergedSnapshot,
            modelUpdatedAt = activeModelRevision,
            profileTimestamp = activeProfileRevision
        )
        val persistedEvidence = if (resetIsfRevisionState && previousSnapshot != null) {
            result.evidence.filter { it.sampleType == IsfCrSampleType.ISF }
        } else {
            result.evidence
        }
        db.withTransaction {
            require(db.isfCrModelStateDao().active()?.updatedAt == activeModelRevision) {
                "ISF/CR base model revision changed during realtime calculation"
            }
            require(db.profileEstimateDao().active()?.timestamp == activeProfileRevision) {
                "ISF/CR profile revision changed during realtime calculation"
            }
            db.isfCrSnapshotDao().upsert(persistedSnapshot.toEntity(gson))
            if (persistedEvidence.isNotEmpty()) {
                db.isfCrEvidenceDao().upsertAll(persistedEvidence.map { it.toEntity(gson) })
            }
        }
        if (!resetIsfRevisionState) {
            cleanupRetention(settings = config, nowTs = nowTs)
        }

        auditLogger.infoThrottled(
            throttleKey = "isfcr_evidence_extracted:realtime",
            intervalMs = ISFCR_REALTIME_EVIDENCE_LOG_INTERVAL_MS,
            message = "isfcr_evidence_extracted",
            metadata = mapOf(
                "phase" to "realtime",
                "isfEvidence" to result.diagnostics.isfEvidenceCount,
                "crEvidence" to result.diagnostics.crEvidenceCount,
                "droppedEvidence" to result.diagnostics.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(result.diagnostics.droppedReasonCounts)
            )
        )

        auditLogger.infoThrottled(
            throttleKey = "isfcr_realtime_computed:${result.snapshot.mode.name}",
            intervalMs = ISFCR_REALTIME_COMPUTED_LOG_INTERVAL_MS,
            message = "isfcr_realtime_computed",
            metadata = mapOf(
                "mode" to result.snapshot.mode.name,
                "confidence" to result.snapshot.confidence,
                "confidenceThreshold" to config.confidenceThreshold,
                "qualityScore" to result.diagnostics.qualityScore,
                "isfEff" to persistedSnapshot.isfEff,
                "crEff" to persistedSnapshot.crEff,
                "isfEvidence" to persistedSnapshot.isfEvidenceCount,
                "crEvidence" to persistedSnapshot.crEvidenceCount,
                "usedEvidence" to result.diagnostics.usedEvidenceCount,
                "droppedEvidence" to result.diagnostics.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(result.diagnostics.droppedReasonCounts),
                "currentDayType" to result.diagnostics.currentDayType,
                "isfBaseSource" to result.diagnostics.isfBaseSource,
                "crBaseSource" to result.diagnostics.crBaseSource,
                "hourWindowIsfEvidence" to result.diagnostics.hourWindowIsfEvidenceCount,
                "hourWindowCrEvidence" to result.diagnostics.hourWindowCrEvidenceCount,
                "setAgeHours" to setAgeHours,
                "sensorAgeHours" to sensorAgeHours,
                "setFactor" to setFactor,
                "sensorAgeFactor" to sensorAgeFactor,
                "sensorFactor" to sensorFactor,
                "sensorQualitySuspectFalseLow" to sensorQualitySuspectFalseLow,
                "contextAmbiguity" to contextAmbiguity,
                "latentStress" to latentStress,
                "uamPenaltyFactor" to uamPenaltyFactor,
                "wearConfidencePenalty" to wearConfidencePenalty,
                "coverageHoursIsf" to result.diagnostics.coverageHoursIsf,
                "coverageHoursCr" to result.diagnostics.coverageHoursCr,
                "reasons" to result.diagnostics.reasons.joinToString(",")
            )
        )
        if (result.diagnostics.lowConfidence) {
            auditLogger.warnThrottled(
                throttleKey = "isfcr_low_confidence:${result.diagnostics.currentDayType}",
                intervalMs = ISFCR_REALTIME_LOW_CONFIDENCE_LOG_INTERVAL_MS,
                message = "isfcr_low_confidence",
                metadata = mapOf(
                    "confidence" to result.snapshot.confidence,
                    "confidenceThreshold" to config.confidenceThreshold,
                    "qualityScore" to result.diagnostics.qualityScore,
                    "usedEvidence" to result.diagnostics.usedEvidenceCount,
                    "droppedEvidence" to result.diagnostics.droppedEvidenceCount,
                    "droppedReasons" to encodeDroppedReasons(result.diagnostics.droppedReasonCounts),
                    "currentDayType" to result.diagnostics.currentDayType,
                    "isfBaseSource" to result.diagnostics.isfBaseSource,
                    "crBaseSource" to result.diagnostics.crBaseSource,
                    "isfDayTypeBaseAvailable" to result.diagnostics.isfDayTypeBaseAvailable,
                    "crDayTypeBaseAvailable" to result.diagnostics.crDayTypeBaseAvailable,
                    "hourWindowIsfSameDayType" to result.diagnostics.hourWindowIsfSameDayTypeCount,
                    "hourWindowCrSameDayType" to result.diagnostics.hourWindowCrSameDayTypeCount,
                    "hourWindowIsfEvidence" to result.diagnostics.hourWindowIsfEvidenceCount,
                    "hourWindowCrEvidence" to result.diagnostics.hourWindowCrEvidenceCount,
                    "minIsfEvidencePerHour" to result.diagnostics.minIsfEvidencePerHour,
                    "minCrEvidencePerHour" to result.diagnostics.minCrEvidencePerHour,
                    "crMaxGapMinutes" to result.diagnostics.crMaxGapMinutes,
                    "crMaxSensorBlockedRatePct" to (result.diagnostics.crMaxSensorBlockedRate * 100.0),
                    "crMaxUamAmbiguityRatePct" to (result.diagnostics.crMaxUamAmbiguityRate * 100.0),
                    "reasons" to result.diagnostics.reasons.joinToString(",")
                )
            )
        }
        if (result.snapshot.mode == IsfCrRuntimeMode.FALLBACK) {
            auditLogger.warn(
                "isfcr_fallback_applied",
                mapOf("reasons" to result.snapshot.reasons.joinToString(","))
            )
        } else if (result.snapshot.mode == IsfCrRuntimeMode.SPARSE_REAL_FETCHED) {
            auditLogger.info(
                "isfcr_sparse_real_fetched_applied",
                mapOf(
                    "confidence" to result.snapshot.confidence,
                    "qualityScore" to result.snapshot.qualityScore,
                    "reasons" to result.snapshot.reasons.joinToString(","),
                    "realFetchedCount30d" to (result.snapshot.factors["therapy_history_real_fetched_insulin_30d"] ?: 0.0),
                    "usableCount30d" to (result.snapshot.factors["therapy_history_usable_insulin_30d"] ?: 0.0)
                )
            )
        }
        persistedSnapshot
    }

    suspend fun latestSnapshot(): IsfCrRealtimeSnapshot? {
        return db.withTransaction {
            val modelRevision = db.isfCrModelStateDao().active()?.updatedAt
            val profileRevision = db.profileEstimateDao().active()?.timestamp
            db.isfCrSnapshotDao()
                .latest()
                ?.toDomain(gson)
                ?.takeIf {
                    realtimeSnapshotMatchesInputGeneration(
                        snapshot = it,
                        modelUpdatedAt = modelRevision,
                        profileTimestamp = profileRevision
                    )
                }
        }
    }

    fun observeLatestSnapshot(): Flow<IsfCrRealtimeSnapshot?> {
        return combine(
            db.isfCrSnapshotDao().observeLatest(),
            db.isfCrModelStateDao().observeActive(),
            db.profileEstimateDao().observeActive()
        ) { snapshotEntity, modelEntity, profileEntity ->
            snapshotEntity
                ?.toDomain(gson)
                ?.takeIf {
                    realtimeSnapshotMatchesInputGeneration(
                        snapshot = it,
                        modelUpdatedAt = modelEntity?.updatedAt,
                        profileTimestamp = profileEntity?.timestamp
                    )
                }
        }
    }

    fun observeSnapshotHistory(limit: Int = 20_000): Flow<List<IsfCrRealtimeSnapshot>> {
        val safeLimit = limit.coerceIn(100, 50_000)
        return db.isfCrSnapshotDao()
            .observeHistory(limit = safeLimit)
            .map { rows ->
                rows
                    .map { it.toDomain(gson) }
                    .sortedBy { it.ts }
            }
    }

    suspend fun addOrUpdateTag(tag: PhysioContextTag, sex: PhysiologicalSex = PhysiologicalSex.UNSPECIFIED) {
        val type = runCatching { CompensationEventType.valueOf(tag.tagType.uppercase()) }
            .getOrDefault(CompensationEventType.CUSTOM)
        require(CompensationEventProfilePolicy.canCreate(type, sex)) { "Event type is not allowed for this profile" }
        db.physioContextTagDao().upsert(tag.toEntity())
    }

    suspend fun closeActiveTags(nowTs: Long = System.currentTimeMillis()) {
        val active = db.physioContextTagDao().activeAt(nowTs)
        if (active.isEmpty()) return
        db.physioContextTagDao().upsertAll(
            active.map { tag ->
                tag.copy(tsEnd = minOf(tag.tsEnd, nowTs))
            }
        )
    }

    suspend fun closeTag(tagId: String, nowTs: Long = System.currentTimeMillis()): Boolean {
        val updatedRows = db.physioContextTagDao().closeById(id = tagId, closeTs = nowTs)
        return updatedRows > 0
    }

    suspend fun deleteTag(tagId: String): Boolean = db.physioContextTagDao().deleteById(tagId) > 0

    fun observeRecentTags(sinceTs: Long): Flow<List<PhysioContextTag>> {
        return db.physioContextTagDao().observeRecent(sinceTs).map { rows -> rows.map { it.toDomain() } }
    }

    private suspend fun cleanupRetention(settings: IsfCrSettings, nowTs: Long) {
        val snapshotCutoff = nowTs - settings.snapshotRetentionDays.coerceAtLeast(30) * DAY_MS
        val evidenceCutoff = nowTs - settings.evidenceRetentionDays.coerceAtLeast(30) * DAY_MS
        db.isfCrSnapshotDao().deleteOlderThan(snapshotCutoff)
        db.isfCrEvidenceDao().deleteOlderThan(evidenceCutoff)
        db.physioContextTagDao().deleteOlderThanWithoutPendingSync(evidenceCutoff)
    }

    private fun AppSettings.toIsfCrSettings(): IsfCrSettings {
        return IsfCrSettings(
            lookbackDays = analyticsLookbackDays.coerceIn(30, 730),
            confidenceThreshold = isfCrConfidenceThreshold.coerceIn(0.2, 0.95),
            shadowMode = isfCrShadowMode,
            useActivityFactor = isfCrUseActivity,
            useManualTags = isfCrUseManualTags,
            minIsfEvidencePerHour = isfCrMinIsfEvidencePerHour.coerceIn(0, 12),
            minCrEvidencePerHour = isfCrMinCrEvidencePerHour.coerceIn(0, 12),
            crGrossGapMinutes = isfCrCrMaxGapMinutes.toDouble().coerceIn(10.0, 60.0),
            crSensorBlockedRateThreshold = (isfCrCrMaxSensorBlockedRatePct / 100.0).coerceIn(0.0, 1.0),
            crUamAmbiguityRateThreshold = (isfCrCrMaxUamAmbiguityRatePct / 100.0).coerceIn(0.0, 1.0),
            snapshotRetentionDays = isfCrSnapshotRetentionDays.coerceIn(30, 730),
            evidenceRetentionDays = isfCrEvidenceRetentionDays.coerceIn(30, 1095)
        )
    }

    private fun IsfCrSnapshotEntity.toDomain(gson: Gson): IsfCrRealtimeSnapshot {
        val factorType = object : TypeToken<Map<String, Double>>() {}.type
        val factorsMap = runCatching { gson.fromJson<Map<String, Double>>(factorsJson, factorType) }.getOrNull()
            ?: emptyMap()
        return IsfCrRealtimeSnapshot(
            id = id,
            ts = ts,
            isfEff = isfEff,
            crEff = crEff,
            isfBase = isfBase,
            crBase = crBase,
            ciIsfLow = ciIsfLow,
            ciIsfHigh = ciIsfHigh,
            ciCrLow = ciCrLow,
            ciCrHigh = ciCrHigh,
            confidence = confidence,
            qualityScore = qualityScore,
            factors = factorsMap,
            mode = runCatching { IsfCrRuntimeMode.valueOf(mode) }.getOrDefault(IsfCrRuntimeMode.FALLBACK),
            isfEvidenceCount = (factorsMap["isf_evidence_count"] ?: 0.0).toInt(),
            crEvidenceCount = (factorsMap["cr_evidence_count"] ?: 0.0).toInt(),
            reasons = emptyList()
        )
    }

    private fun IsfCrRealtimeSnapshot.toEntity(gson: Gson): IsfCrSnapshotEntity {
        val factorJson = gson.toJson(
            factors + mapOf(
                "isf_evidence_count" to isfEvidenceCount.toDouble(),
                "cr_evidence_count" to crEvidenceCount.toDouble()
            )
        )
        return IsfCrSnapshotEntity(
            id = id,
            ts = ts,
            isfEff = isfEff,
            crEff = crEff,
            isfBase = isfBase,
            crBase = crBase,
            ciIsfLow = ciIsfLow,
            ciIsfHigh = ciIsfHigh,
            ciCrLow = ciCrLow,
            ciCrHigh = ciCrHigh,
            confidence = confidence,
            qualityScore = qualityScore,
            factorsJson = factorJson,
            mode = mode.name
        )
    }

    private fun IsfCrEvidenceSample.toEntity(gson: Gson): IsfCrEvidenceEntity {
        return IsfCrEvidenceEntity(
            id = id,
            ts = ts,
            sampleType = sampleType.name,
            hourLocal = hourLocal,
            dayType = dayType.name,
            value = value,
            weight = weight,
            qualityScore = qualityScore,
            contextJson = gson.toJson(context),
            windowJson = gson.toJson(window)
        )
    }

    private fun IsfCrModelState.toEntity(): IsfCrModelStateEntity {
        return IsfCrModelStateEntity(
            updatedAt = updatedAt,
            hourlyIsfJson = gson.toJson(hourlyIsf),
            hourlyCrJson = gson.toJson(hourlyCr),
            paramsJson = gson.toJson(params),
            fitMetricsJson = gson.toJson(fitMetrics)
        )
    }

    private fun IsfCrModelStateEntity.toDomain(): IsfCrModelState {
        val listType = object : TypeToken<List<Double?>>() {}.type
        val mapType = object : TypeToken<Map<String, Double>>() {}.type
        return IsfCrModelState(
            updatedAt = updatedAt,
            hourlyIsf = runCatching { gson.fromJson<List<Double?>>(hourlyIsfJson, listType) }.getOrNull()
                ?.let(::normalizeHourlyList)
                ?: List(24) { null },
            hourlyCr = runCatching { gson.fromJson<List<Double?>>(hourlyCrJson, listType) }.getOrNull()
                ?.let(::normalizeHourlyList)
                ?: List(24) { null },
            params = runCatching { gson.fromJson<Map<String, Double>>(paramsJson, mapType) }.getOrDefault(emptyMap()),
            fitMetrics = runCatching { gson.fromJson<Map<String, Double>>(fitMetricsJson, mapType) }.getOrDefault(emptyMap())
        )
    }

    private fun normalizeHourlyList(input: List<Double?>): List<Double?> {
        if (input.size == 24) return input
        return List(24) { index -> input.getOrNull(index) }
    }

    private fun trimRealtimeTelemetryRows(
        rows: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<TelemetrySampleEntity> {
        if (rows.isEmpty()) return emptyList()
        val minTs = nowTs - ISFCR_REALTIME_TELEMETRY_MAX_AGE_MS
        val recentRows = rows
            .asSequence()
            .filter { it.timestamp >= minTs }
            .toList()
        if (recentRows.isEmpty()) return emptyList()
        val perKeyTrimmed = recentRows
            .groupBy { it.key }
            .values
            .flatMap { keyRows ->
                keyRows
                    .sortedBy { it.timestamp }
                    .takeLast(ISFCR_REALTIME_TELEMETRY_MAX_ROWS_PER_KEY)
            }
        return perKeyTrimmed
            .sortedBy { it.timestamp }
            .takeLast(ISFCR_REALTIME_TELEMETRY_HARD_LIMIT)
    }

    private fun encodeDroppedReasons(reasons: Map<String, Int>): String {
        if (reasons.isEmpty()) return ""
        return reasons.entries
            .sortedByDescending { it.value }
            .joinToString(";") { entry -> "${entry.key}=${entry.value}" }
    }

    private fun PhysioContextTag.toEntity(): PhysioContextTagEntity {
        return PhysioContextTagEntity(
            id = id,
            tsStart = tsStart,
            tsEnd = tsEnd,
            tagType = tagType,
            severity = severity,
            source = source,
            note = note,
            subtype = subtype,
            title = title,
            attributesJson = attributesJson,
            revision = revision,
            status = status
        )
    }

    private fun PhysioContextTagEntity.toDomain(): PhysioContextTag {
        return PhysioContextTag(
            id = id,
            tsStart = tsStart,
            tsEnd = tsEnd,
            tagType = tagType,
            severity = severity,
            source = source,
            note = note,
            subtype = subtype,
            title = title,
            attributesJson = attributesJson,
            revision = revision,
            status = status
        )
    }

    private companion object {
        private const val HOUR_MS = 60L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_ISF = 2.5
        private const val DEFAULT_CR = 12.0
        private const val ISFCR_REALTIME_EVIDENCE_LOG_INTERVAL_MS = 10L * 60 * 1000
        private const val ISFCR_REALTIME_COMPUTED_LOG_INTERVAL_MS = 5L * 60 * 1000
        private const val ISFCR_REALTIME_LOW_CONFIDENCE_LOG_INTERVAL_MS = 15L * 60 * 1000
        private const val ISFCR_REALTIME_LOOKBACK_HOURS = 72L
        private const val ISFCR_REALTIME_MAX_GLUCOSE_ROWS = 5_000
        private const val ISFCR_REALTIME_MAX_THERAPY_ROWS = 720
        private const val ISFCR_REALTIME_MAX_THERAPY_ROWS_WIDE = 4_000
        private const val ISFCR_REALTIME_TELEMETRY_MAX_AGE_MS = 18L * HOUR_MS
        private const val ISFCR_REALTIME_TELEMETRY_MAX_ROWS_PER_KEY = 180
        private const val ISFCR_REALTIME_TELEMETRY_HARD_LIMIT = 2_400
        private const val ISFCR_REALTIME_TELEMETRY_QUERY_LIMIT = 3_000
        private val ISFCR_REALTIME_TELEMETRY_KEYS = listOf(
            "activity_ratio",
            "activity",
            "sensitivity_ratio",
            "autosens_ratio",
            "raw_activityratio",
            "steps_count",
            "raw_steps_count",
            "steps_rate_15m",
            "set_age_hours",
            "set_age_days",
            "sensor_age_hours",
            "sensor_age_days",
            "sensor_age_source_raw",
            "sage_days",
            "cage_days",
            "sensor_quality_score",
            "sensor_quality_noise_std5",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low",
            "sensor_blocked",
            "dawn_factor_hint",
            "dawn_resistance_score",
            "stress_score",
            "uam_stress_index",
            "uam_value",
            "uam_calculated_flag",
            "uam_inferred_flag",
            "uam_active",
            "uam_detected",
            "has_uam",
            "is_uam",
            "iob",
            "iob_units",
            "iob_effective_units",
            "raw_iob",
            "raw_iob_units",
            "raw_iob_iob",
            "openaps_iob",
            "openaps_iob_iob",
            "openaps_iob_basaliob",
            "openaps_iob_activity",
            "iob_iob",
            "iob_basaliob",
            "iob_real_units",
            "therapy_history_source_mode",
            "therapy_history_real_fetched_insulin_30d",
            "therapy_history_recovered_insulin_30d",
            "therapy_history_usable_insulin_30d",
            "therapy_history_bootstrap_needed",
            DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
            DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY
        )
        private val ISFCR_BASE_FIT_TELEMETRY_KEYS = ISFCR_REALTIME_TELEMETRY_KEYS

    }
}
