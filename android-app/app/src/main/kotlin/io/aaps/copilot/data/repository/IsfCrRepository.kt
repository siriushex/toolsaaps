package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.IsfCrEvidenceEntity
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import io.aaps.copilot.data.local.entity.IsfCrSnapshotEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.domain.isfcr.IsfCrEngine
import io.aaps.copilot.domain.isfcr.IsfCrEvidenceSample
import io.aaps.copilot.domain.isfcr.IsfCrHistoryBundle
import io.aaps.copilot.domain.isfcr.IsfCrModelState
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.isfcr.IsfCrSampleType
import io.aaps.copilot.domain.isfcr.IsfCrSettings
import io.aaps.copilot.domain.isfcr.PhysioContextTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class IsfCrRepository(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val engine: IsfCrEngine = IsfCrEngine()
) {

    suspend fun fitBaseModel(
        settings: AppSettings,
        nowTs: Long = System.currentTimeMillis()
    ): IsfCrModelState = withContext(Dispatchers.Default) {
        val config = settings.toIsfCrSettings()
        val historyStart = nowTs - config.lookbackDays * DAY_MS
        val glucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart)).map { it.toDomain() }
        val therapy = TherapySanitizer.filterEntities(db.therapyDao().since(historyStart)).map { it.toDomain(gson) }
        val telemetry = db.telemetryDao().since(historyStart).map { entity ->
            io.aaps.copilot.domain.predict.TelemetrySignal(
                ts = entity.timestamp,
                key = entity.key,
                valueDouble = entity.valueDouble,
                valueText = entity.valueText
            )
        }
        val tags = db.physioContextTagDao().activeAt(nowTs).map { it.toDomain() }
        val activeState = db.isfCrModelStateDao().active()?.toDomain()
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
                tags = tags
            ),
            settings = config,
            existingState = activeState,
            fallbackIsf = fallbackIsf,
            fallbackCr = fallbackCr
        )

        db.isfCrModelStateDao().upsert(fit.state.toEntity())
        if (fit.evidence.isNotEmpty()) {
            db.isfCrEvidenceDao().upsertAll(fit.evidence.map { it.toEntity(gson) })
        }
        cleanupRetention(settings = config, nowTs = nowTs)

        auditLogger.info(
            "isfcr_evidence_extracted",
            mapOf(
                "phase" to "base_fit",
                "isfEvidence" to fit.evidence.count { it.sampleType == IsfCrSampleType.ISF },
                "crEvidence" to fit.evidence.count { it.sampleType == IsfCrSampleType.CR },
                "droppedEvidence" to fit.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(fit.droppedReasonCounts)
            )
        )

        auditLogger.info(
            "isfcr_base_fit_completed",
            mapOf(
                "isfEvidence" to fit.evidence.count { it.sampleType == IsfCrSampleType.ISF },
                "crEvidence" to fit.evidence.count { it.sampleType == IsfCrSampleType.CR },
                "droppedEvidence" to fit.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(fit.droppedReasonCounts)
            )
        )

        fit.state
    }

    suspend fun computeRealtimeSnapshot(
        settings: AppSettings,
        nowTs: Long = System.currentTimeMillis()
    ): IsfCrRealtimeSnapshot = withContext(Dispatchers.Default) {
        val config = settings.toIsfCrSettings()
        val recentStart = nowTs - maxOf(config.lookbackDays.coerceAtMost(14), 3) * DAY_MS
        val glucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(recentStart)).map { it.toDomain() }
        val therapy = TherapySanitizer.filterEntities(db.therapyDao().since(recentStart)).map { it.toDomain(gson) }
        val telemetry = db.telemetryDao().since(recentStart).map { entity ->
            io.aaps.copilot.domain.predict.TelemetrySignal(
                ts = entity.timestamp,
                key = entity.key,
                valueDouble = entity.valueDouble,
                valueText = entity.valueText
            )
        }
        val activeTags = db.physioContextTagDao().activeAt(nowTs).map { it.toDomain() }
        val activeState = db.isfCrModelStateDao().active()?.toDomain()
        val previousSnapshot = db.isfCrSnapshotDao().latest()?.toDomain(gson)
        val fallbackProfile = db.profileEstimateDao().active()
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
            activeModel = activeState,
            previousSnapshot = previousSnapshot,
            settings = config,
            fallbackIsf = fallbackIsf,
            fallbackCr = fallbackCr
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

        db.isfCrSnapshotDao().upsert(result.snapshot.toEntity(gson))
        if (result.evidence.isNotEmpty()) {
            db.isfCrEvidenceDao().upsertAll(result.evidence.map { it.toEntity(gson) })
        }
        cleanupRetention(settings = config, nowTs = nowTs)

        auditLogger.info(
            "isfcr_evidence_extracted",
            mapOf(
                "phase" to "realtime",
                "isfEvidence" to result.diagnostics.isfEvidenceCount,
                "crEvidence" to result.diagnostics.crEvidenceCount,
                "droppedEvidence" to result.diagnostics.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(result.diagnostics.droppedReasonCounts)
            )
        )

        auditLogger.info(
            "isfcr_realtime_computed",
            mapOf(
                "mode" to result.snapshot.mode.name,
                "confidence" to result.snapshot.confidence,
                "confidenceThreshold" to config.confidenceThreshold,
                "qualityScore" to result.diagnostics.qualityScore,
                "isfEff" to result.snapshot.isfEff,
                "crEff" to result.snapshot.crEff,
                "isfEvidence" to result.snapshot.isfEvidenceCount,
                "crEvidence" to result.snapshot.crEvidenceCount,
                "usedEvidence" to result.diagnostics.usedEvidenceCount,
                "droppedEvidence" to result.diagnostics.droppedEvidenceCount,
                "droppedReasons" to encodeDroppedReasons(result.diagnostics.droppedReasonCounts),
                "currentDayType" to result.diagnostics.currentDayType,
                "isfBaseSource" to result.diagnostics.isfBaseSource,
                "crBaseSource" to result.diagnostics.crBaseSource,
                "isfDayTypeBaseAvailable" to result.diagnostics.isfDayTypeBaseAvailable,
                "crDayTypeBaseAvailable" to result.diagnostics.crDayTypeBaseAvailable,
                "hourWindowIsfEvidence" to result.diagnostics.hourWindowIsfEvidenceCount,
                "hourWindowCrEvidence" to result.diagnostics.hourWindowCrEvidenceCount,
                "hourWindowIsfSameDayType" to result.diagnostics.hourWindowIsfSameDayTypeCount,
                "hourWindowCrSameDayType" to result.diagnostics.hourWindowCrSameDayTypeCount,
                "minIsfEvidencePerHour" to result.diagnostics.minIsfEvidencePerHour,
                "minCrEvidencePerHour" to result.diagnostics.minCrEvidencePerHour,
                "crMaxGapMinutes" to result.diagnostics.crMaxGapMinutes,
                "crMaxSensorBlockedRatePct" to (result.diagnostics.crMaxSensorBlockedRate * 100.0),
                "crMaxUamAmbiguityRatePct" to (result.diagnostics.crMaxUamAmbiguityRate * 100.0),
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
            auditLogger.warn(
                "isfcr_low_confidence",
                mapOf(
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
        }
        result.snapshot
    }

    suspend fun latestSnapshot(): IsfCrRealtimeSnapshot? {
        return db.isfCrSnapshotDao().latest()?.toDomain(gson)
    }

    fun observeLatestSnapshot(): Flow<IsfCrRealtimeSnapshot?> {
        return db.isfCrSnapshotDao().observeLatest().map { it?.toDomain(gson) }
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

    suspend fun addOrUpdateTag(tag: PhysioContextTag) {
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

    fun observeRecentTags(sinceTs: Long): Flow<List<PhysioContextTag>> {
        return db.physioContextTagDao().observeRecent(sinceTs).map { rows -> rows.map { it.toDomain() } }
    }

    private suspend fun cleanupRetention(settings: IsfCrSettings, nowTs: Long) {
        val snapshotCutoff = nowTs - settings.snapshotRetentionDays.coerceAtLeast(30) * DAY_MS
        val evidenceCutoff = nowTs - settings.evidenceRetentionDays.coerceAtLeast(30) * DAY_MS
        db.isfCrSnapshotDao().deleteOlderThan(snapshotCutoff)
        db.isfCrEvidenceDao().deleteOlderThan(evidenceCutoff)
        db.physioContextTagDao().deleteOlderThan(evidenceCutoff)
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
            note = note
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
            note = note
        )
    }

    private companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_ISF = 2.5
        private const val DEFAULT_CR = 12.0
    }
}
