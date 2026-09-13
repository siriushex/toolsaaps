package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.TelemetrySampleSelector
import io.aaps.copilot.data.local.dao.ClinicalForecastProjection
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.data.local.dao.ClinicalTelemetryProjection
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.domain.eating.EatingEvidencePoint
import io.aaps.copilot.domain.eating.EatingWindowSnapshotPlanner
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.model.ResolvedGlucosePoint
import io.aaps.copilot.domain.predict.CanonicalGlucoseConfig
import io.aaps.copilot.domain.predict.Glucose5mCanonicalizer
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.profile.ActivityProfile
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyEstimate
import io.aaps.copilot.domain.profile.EnergyProfilePolicy
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.EnergyRequirementEstimator
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventProfilePolicy
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.data.local.dao.PhysicalActivityMetricBucketProjection
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class ClinicalReportReadRequest(
    val glucoseFromTs: Long,
    val therapyFromTs: Long,
    val detailFromTs: Long,
    val summaryTelemetryFromTs: Long,
    val toTs: Long,
    val therapyTypes: List<String>,
    val telemetryKeys: List<String>,
    val summaryTelemetryKeys: List<String>
)

internal data class ClinicalReportSnapshot(
    val glucose: List<ClinicalGlucoseProjection>,
    val therapy: List<ClinicalTherapyProjection>,
    val forecasts: List<ClinicalForecastProjection>,
    val telemetry: List<ClinicalTelemetryProjection>,
    val summaryTelemetry: List<ClinicalTelemetryProjection> = telemetry,
    val sensitivityRuntime: SensitivityRuntimeSnapshotEntity? = null,
    val sensitivityRuntimeForecasts: List<ClinicalForecastProjection> = emptyList(),
    val sensitivityRuntimeAuthoritative: Boolean = false
)

private data class AcceptedSensitivityRuntime(
    val snapshot: SensitivityRuntimeSnapshotEntity,
    val forecasts: List<ClinicalForecastProjection>
)

private val REQUIRED_SENSITIVITY_HORIZONS = setOf(5, 30, 60)
private const val PHYSICAL_ACTIVITY_PEAK_KEY = PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_PEAK_KEY

internal fun interface ClinicalAapsTddSource {
    suspend fun load(
        detailFromTs: Long,
        from7d: Long,
        from30d: Long,
        throughTs: Long
    ): ClinicalAapsTddSnapshot?
}

internal data class ClinicalEnergyProfileSourceSnapshot(
    val settings: EnergyProfileSettings,
    val latestInference: io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity?,
    val plannedActivities: List<io.aaps.copilot.data.local.entity.PlannedActivityEventEntity>,
    val targetManagerEvidence: ClinicalTargetManagerEvidenceSnapshot =
        ClinicalTargetManagerEvidenceSnapshot.EMPTY,
    val manualMealEnergyOverrides: List<MealEnergyOverrideEntity> = emptyList()
)

internal fun interface ClinicalEnergyProfileSource {
    suspend fun load(fromTs: Long, throughTs: Long): ClinicalEnergyProfileSourceSnapshot
}

internal interface ClinicalReportDataSource {
    suspend fun glucose(fromTs: Long, toTs: Long): List<ClinicalGlucoseProjection>
    suspend fun therapy(fromTs: Long, toTs: Long, types: List<String>): List<ClinicalTherapyProjection>
    suspend fun forecasts(fromTs: Long, toTs: Long): List<ClinicalForecastProjection>
    suspend fun telemetry(
        fromTs: Long,
        toTs: Long,
        keys: List<String>
    ): List<ClinicalTelemetryProjection>

    suspend fun readSnapshot(request: ClinicalReportReadRequest): ClinicalReportSnapshot =
        ClinicalReportSnapshot(
            glucose = glucose(request.glucoseFromTs, request.toTs),
            therapy = therapy(request.therapyFromTs, request.toTs, request.therapyTypes),
            forecasts = forecasts(request.detailFromTs, request.toTs),
            telemetry = telemetry(request.detailFromTs, request.toTs, request.telemetryKeys),
            summaryTelemetry = telemetry(
                request.summaryTelemetryFromTs,
                request.toTs,
                request.summaryTelemetryKeys
            )
        )
}

internal fun interface ClinicalEventTimelineSource {
    suspend fun load(fromTs: Long, toTs: Long): List<CompensationEvent>
}

internal data class ClinicalGlucoseCanonicalBucket(
    val bucketTs: Long,
    val representativeTs: Long,
    val medianMmol: Double
)

internal data class ClinicalSelection<T>(
    val points: List<T>,
    val rejected: Int
)

internal interface ClinicalDatasetSerializationProbe {
    fun onCanonicalEventWindowsComputed() = Unit
    fun onSeriesRowEmitted() = Unit
    fun onFullDatasetAssemblyStarted() = Unit
    fun onTransportInputChunkProcessed() = Unit
    fun onClinicalInputMaterialized() = Unit
    fun onClinicalRequestBodyMaterialized() = Unit
    fun onPartitionLeafRetained(retainedLargeArtifacts: Int) = Unit
    fun onWireCoverageRowRead() = Unit
}

private class RoomClinicalReportDataSource(
    private val db: CopilotDatabase,
    private val sensitivitySettingsIdentity: (suspend () -> SensitivityRuntimeSettingsIdentity)?
) : ClinicalReportDataSource {
    private val acceptedTupleLoader = AcceptedSensitivityTupleRoomLoader(db)

    override suspend fun readSnapshot(request: ClinicalReportReadRequest): ClinicalReportSnapshot {
        val identityBefore = sensitivitySettingsIdentity?.invoke()
        val snapshot = db.withTransaction {
            val acceptedRuntime = identityBefore?.let { identity ->
                resolveAcceptedSensitivityRuntime(
                    atTs = request.toTs,
                    currentIdentity = identity
                )
            }
            val detailTelemetry = telemetry(
                request.detailFromTs,
                request.toTs,
                request.telemetryKeys.filterNot(PhysicalActivityTelemetryPolicy.CLINICAL_ACTIVITY_METRIC_KEYS::contains)
            ) + physicalActivityTelemetry(
                request.detailFromTs,
                request.toTs,
                request.telemetryKeys
            )
            val summaryTelemetry = telemetry(
                request.summaryTelemetryFromTs,
                request.toTs,
                request.summaryTelemetryKeys.filterNot(PhysicalActivityTelemetryPolicy.CLINICAL_ACTIVITY_METRIC_KEYS::contains)
            ) + physicalActivityTelemetry(
                request.summaryTelemetryFromTs,
                request.toTs,
                request.summaryTelemetryKeys
            )
            ClinicalReportSnapshot(
                glucose = glucose(request.glucoseFromTs, request.toTs),
                therapy = therapy(request.therapyFromTs, request.toTs, request.therapyTypes),
                forecasts = forecasts(request.detailFromTs, request.toTs),
                telemetry = detailTelemetry,
                summaryTelemetry = summaryTelemetry,
                sensitivityRuntime = acceptedRuntime?.snapshot,
                sensitivityRuntimeForecasts = acceptedRuntime?.forecasts.orEmpty(),
                sensitivityRuntimeAuthoritative = identityBefore != null
            )
        }
        val identityAfter = sensitivitySettingsIdentity?.invoke()
        return if (identityBefore != null && identityBefore == identityAfter) {
            snapshot
        } else {
            snapshot.copy(
                sensitivityRuntime = null,
                sensitivityRuntimeForecasts = emptyList(),
                sensitivityRuntimeAuthoritative = identityBefore != null
            )
        }
    }

    private suspend fun resolveAcceptedSensitivityRuntime(
        atTs: Long,
        currentIdentity: SensitivityRuntimeSettingsIdentity
    ): AcceptedSensitivityRuntime? {
        val accepted = acceptedTupleLoader.load(currentIdentity, atTs) ?: return null
        return AcceptedSensitivityRuntime(
            snapshot = accepted.snapshotEntity,
            forecasts = accepted.forecasts.map { forecast ->
                ClinicalForecastProjection(
                    ts = forecast.timestamp,
                    horizonMin = forecast.horizonMinutes,
                    mmol = forecast.valueMmol,
                    lower = forecast.ciLow,
                    upper = forecast.ciHigh,
                    rowId = forecast.id,
                    modelVersion = forecast.modelVersion
                )
            }
        )
    }

    override suspend fun glucose(fromTs: Long, toTs: Long) =
        db.glucoseDao().betweenForClinicalReport(fromTs, toTs)

    override suspend fun therapy(fromTs: Long, toTs: Long, types: List<String>) =
        db.therapyDao().betweenForClinicalReport(fromTs, toTs, types)

    override suspend fun forecasts(fromTs: Long, toTs: Long) =
        db.forecastDao().betweenForClinicalReport(fromTs, toTs)

    override suspend fun telemetry(fromTs: Long, toTs: Long, keys: List<String>) =
        db.telemetryDao().betweenForClinicalReport(fromTs, toTs, keys)

    private suspend fun physicalActivityTelemetry(
        fromTs: Long,
        toTsInclusive: Long,
        requestedKeys: List<String>
    ): List<ClinicalTelemetryProjection> {
        val requested = requestedKeys.toSet()
        val keys = PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.filter { key ->
            key in requested ||
                key == PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY &&
                PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_PEAK_KEY in requested
        }
        if (keys.isEmpty()) return emptyList()
        val window = PhysicalActivityTelemetryPolicy.reportBucketWindow(fromTs, toTsInclusive)
        return db.telemetryDao().physicalActivityMetric5MinuteBuckets(
            fromTs = window.fromTs,
            toTsExclusive = window.toTsExclusive,
            firstBucketTs = window.firstBucketTs,
            keys = keys,
            sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
            qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
            bucketMs = PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS
        ).flatMap { bucket -> bucket.toClinicalTelemetryProjections() }
    }
}

class ClinicalReportDatasetBuilder internal constructor(
    private val source: ClinicalReportDataSource,
    private val resolveCalibratedGlucose:
        suspend (List<GlucosePoint>, Long) -> List<ResolvedGlucosePoint>,
    private val aapsTddSource: ClinicalAapsTddSource? = null,
    private val energyProfileSource: ClinicalEnergyProfileSource? = null,
    private val eventTimelineSource: ClinicalEventTimelineSource? = null,
    private val alertAiSource: AlertAiDatasetSource? = null
) {
    internal constructor(source: ClinicalReportDataSource) : this(
        source = source,
        resolveCalibratedGlucose = ::resolveRawGlucose,
        aapsTddSource = null
    )

    @Suppress("UNUSED_PARAMETER")
    internal constructor(source: ClinicalReportDataSource, gson: Gson) : this(source)

    constructor(
        db: CopilotDatabase,
        glucoseCalibrationRepository: GlucoseCalibrationRepository
    ) : this(
        source = RoomClinicalReportDataSource(db, sensitivitySettingsIdentity = null),
        resolveCalibratedGlucose = glucoseCalibrationRepository::resolveGlucosePoints,
        aapsTddSource = null,
        alertAiSource = RoomAlertAiDatasetSource(
            db = db,
            gson = Gson(),
            glucoseCalibrationRepository = glucoseCalibrationRepository,
            sensitivitySettingsIdentity = null
        )
    )

    internal constructor(
        db: CopilotDatabase,
        glucoseCalibrationRepository: GlucoseCalibrationRepository,
        aapsTddSource: ClinicalAapsTddSource,
        sensitivitySettingsIdentity: suspend () -> SensitivityRuntimeSettingsIdentity,
        energyProfileSource: ClinicalEnergyProfileSource? = null,
        eventTimelineSource: ClinicalEventTimelineSource? = null,
        gson: Gson = Gson()
    ) : this(
        source = RoomClinicalReportDataSource(db, sensitivitySettingsIdentity),
        resolveCalibratedGlucose = glucoseCalibrationRepository::resolveGlucosePoints,
        aapsTddSource = aapsTddSource,
        energyProfileSource = energyProfileSource,
        eventTimelineSource = eventTimelineSource,
        alertAiSource = RoomAlertAiDatasetSource(
            db = db,
            gson = gson,
            glucoseCalibrationRepository = glucoseCalibrationRepository,
            sensitivitySettingsIdentity = sensitivitySettingsIdentity
        )
    )

    suspend fun build(nowTs: Long, zoneId: ZoneId): ClinicalReportPayload {
        val through = floorToBucket(nowTs)
        val from30d = subtractExact(through, multiplyExact(30L, DAY_MS))
        val from7d = subtractExact(through, multiplyExact(7L, DAY_MS))
        val from24hSummary = subtractExact(through, DAY_MS)
        val from24h = subtractExact(nowTs, DAY_MS)
        val plannedActivityWindow = ClinicalPreparedPeriodWindow(
            fromTs = minOf(from24h, from7d, from30d),
            throughTs = maxOf(nowTs, through)
        )
        val plannedActivityEvidenceFromTs = ClinicalPlannedActivityPeriodPolicy
            .earliestPotentialStart(plannedActivityWindow.fromTs)
        val targetLookback = subtractExact(from30d, MAX_TARGET_DURATION_MS)
        val request = ClinicalReportReadRequest(
            glucoseFromTs = from30d,
            therapyFromTs = targetLookback,
            detailFromTs = from24h,
            summaryTelemetryFromTs = plannedActivityEvidenceFromTs,
            toTs = nowTs,
            therapyTypes = THERAPY_TYPES,
            telemetryKeys = TELEMETRY_KEYS,
            summaryTelemetryKeys = SUMMARY_TELEMETRY_KEYS
        )
        val snapshot = source.readSnapshot(request)
        val energyProfileSourceSnapshot = runCatching {
            energyProfileSource?.load(
                plannedActivityWindow.fromTs,
                plannedActivityWindow.throughTs
            )
        }.getOrNull()
        val aapsTdd = runCatching {
            aapsTddSource?.load(
                from24hSummary,
                from7d,
                from30d,
                through
            )
        }.getOrNull()
        currentCoroutineContext().ensureActive()

        val selectedGlucose = selectGlucoseRows(snapshot.glucose)
        val resolvedGlucose = resolveCalibratedGlucose(
            selectedGlucose.map {
                GlucosePoint(
                    ts = it.ts,
                    valueMmol = it.mmol,
                    source = it.source,
                    quality = qualityForCanonicalizer(it.quality)
                )
            },
            nowTs
        )
        val calibratedGlucose = resolvedGlucose.map {
            ClinicalGlucoseProjection(
                ts = it.ts,
                mmol = it.calibratedMmol,
                source = it.source,
                quality = it.quality.name
            )
        }
        val coroutineContext = currentCoroutineContext()
        val glucoseBuckets30d = canonicalizeGlucoseBuckets(
            calibratedGlucose,
            from30d,
            through,
            checkpoint = coroutineContext::ensureActive
        )
        val glucoseBuckets7d = glucoseBuckets30d.filter { it.bucketTs >= from7d }
        val glucoseBuckets24h = glucoseBuckets30d.filter { it.bucketTs >= from24hSummary }
        val detailGlucose = canonicalGlucose(
            selectedGlucose.filter { it.ts in from24h..nowTs }
                .map { ClinicalGlucosePoint(it.ts, canonicalDouble(it.mmol)) }
        )
        val detailCalibratedGlucose = canonicalGlucose(
            calibratedGlucose.filter { it.ts in from24h..nowTs }
                .map { ClinicalGlucosePoint(it.ts, canonicalDouble(it.mmol)) }
        )
        currentCoroutineContext().ensureActive()

        val boundedTherapyRows = snapshot.therapy.asSequence()
            .filter { it.ts in targetLookback..nowTs }
            .filter { it.type.trim().lowercase(Locale.US) in THERAPY_TYPES }
            .filterNot(ClinicalTherapyProjection::isBroadcastArtifact)
            .toList()
        val deduplicatedRows = deduplicateTherapyRows(boundedTherapyRows)
        val therapyRows = deduplicatedRows.filterNot { it.type.equals("temp_target", true) }
        val targetRows = deduplicatedRows.filter { it.type.equals("temp_target", true) }
        val therapyEvents = mapChecked(therapyRows, ::sanitizeTherapyEvent)
        val targetEvents = mapChecked(targetRows, ::sanitizeTargetEvent)
        val malformedTherapyRows = therapyRows.count { sanitizeTherapyEvent(it) == null }
        val malformedTargetRows = targetRows.count { sanitizeTargetEvent(it) == null }
        val canonicalTherapyEvents = canonicalTherapyEvents(therapyEvents)
        val canonicalTherapy = canonicalTherapyEvents.map(ClinicalTherapyEvent::point)
        val canonicalTargets = canonicalTargetEvents(targetEvents)

        val resolved30d = resolveTargets(canonicalTargets, from30d, through)
        val resolved7d = resolveTargets(canonicalTargets, from7d, through)
        val resolved24hSummary = resolveTargets(canonicalTargets, from24hSummary, through)
        val resolved24h = resolveTargets(canonicalTargets, from24h, nowTs)
        val forecasts = selectForecasts(snapshot.forecasts, from24h, nowTs)
        val telemetry = selectTelemetry(snapshot.telemetry, from24h, nowTs)
        val summaryTelemetry = selectTelemetry(
            snapshot.summaryTelemetry,
            from30d,
            through
        )
        val plannedActivityTelemetry = selectTelemetry(
            snapshot.summaryTelemetry,
            plannedActivityEvidenceFromTs,
            plannedActivityWindow.throughTs
        )
        currentCoroutineContext().ensureActive()

        fun rejections(fromTs: Long) = ClinicalRejectionCounts(
            glucose = snapshot.glucose.count {
                it.ts in fromTs..through && !isValidGlucoseRow(it)
            },
            therapy = therapyRows.count {
                it.ts in fromTs..through && sanitizeTherapyEvent(it) == null
            }.coerceAtMost(malformedTherapyRows),
            target = targetRows.count {
                it.ts in subtractExact(fromTs, MAX_TARGET_DURATION_MS)..through &&
                    sanitizeTargetEvent(it) == null
            }.coerceAtMost(malformedTargetRows),
            forecast = forecasts.rejected,
            telemetry = telemetry.rejected
        )

        val therapyEvents30d = canonicalTherapyEvents.filter { it.point.ts in from30d..through }
        val therapyEvents7d = therapyEvents30d.filter { it.point.ts >= from7d }
        val therapyEvents24h = therapyEvents30d.filter { it.point.ts >= from24hSummary }
        val therapy30d = therapyEvents30d.map(ClinicalTherapyEvent::point)
        val therapy7d = therapyEvents7d.map(ClinicalTherapyEvent::point)
        val therapy24h = therapyEvents24h.map(ClinicalTherapyEvent::point)
        val summary24hBase = ClinicalSummaryCalculator.calculate(
            1, from24hSummary, through, zoneId, summaryGlucose(glucoseBuckets24h), therapy24h,
            resolved24hSummary.events.map(ClinicalTargetEvent::point),
            resolved24hSummary.intervals,
            rejections(from24hSummary),
            summaryTelemetry.points.filter { it.ts >= from24hSummary },
            aapsTdd?.detail24h
        )
        val summary7dBase = ClinicalSummaryCalculator.calculate(
            7, from7d, through, zoneId, summaryGlucose(glucoseBuckets7d), therapy7d,
            resolved7d.events.map(ClinicalTargetEvent::point),
            resolved7d.intervals,
            rejections(from7d),
            summaryTelemetry.points.filter { it.ts >= from7d },
            aapsTdd?.period7d
        )
        val mealWindowSnapshot = EatingWindowSnapshotPlanner.plan(
            evidence = therapy30d.mapNotNull { point ->
                point.carbsG?.let { carbs ->
                    EatingEvidencePoint(point.ts, carbs, point.syntheticUam)
                }
            },
            generatedAt = nowTs,
            zoneId = zoneId
        )
        val summary30dBase = ClinicalSummaryCalculator.calculate(
            30, from30d, through, zoneId, summaryGlucose(glucoseBuckets30d), therapy30d,
            resolved30d.events.map(ClinicalTargetEvent::point),
            resolved30d.intervals,
            rejections(from30d),
            summaryTelemetry.points,
            aapsTdd?.period30d
        ).copy(
            probableMealWindows = mealWindowSnapshot.stable.windows,
            recentProbableMealWindows = mealWindowSnapshot.recent.windows
        )
        val energyProfile = energyProfileSourceSnapshot?.toClinicalEnergyProfile(nowTs, zoneId)
        val manualMealEnergyOverrides = energyProfileSourceSnapshot
            ?.manualMealEnergyOverrides
            .orEmpty()
        val summary24h = summary24hBase.withMealEnergy(
            energyProfile,
            therapyEvents24h,
            manualMealEnergyOverrides
        )
        val summary7d = summary7dBase.withMealEnergy(
            energyProfile,
            therapyEvents7d,
            manualMealEnergyOverrides
        )
        val summary30d = summary30dBase.withMealEnergy(
            energyProfile,
            therapyEvents30d,
            manualMealEnergyOverrides
        )
        val detail24h = ClinicalDetailWindow(
            from24h,
            nowTs,
            detailGlucose,
            detailCalibratedGlucose,
            canonicalTherapy.filter { it.ts in from24h..nowTs },
            resolved24h.events.map(ClinicalTargetEvent::point),
            forecasts.points,
            telemetry.points,
            forecastQuality(forecasts.points, detailGlucose)
        )
        val eventWindowFromTs = minOf(
            detail24h.fromTs,
            summary7d.fromTs,
            summary30d.fromTs
        )
        val eventWindowThroughTs = maxOf(
            detail24h.throughTs,
            summary7d.throughTs,
            summary30d.throughTs
        )
        val retainedTimelineEvents = retainedTimelineEvents(
            eventTimelineSource?.load(eventWindowFromTs, eventWindowThroughTs).orEmpty(),
            eventWindowFromTs,
            eventWindowThroughTs
        )
        val eventSummaries = retainedTimelineEvents.map(::toClinicalEventSummary)
        val eventTypeAssociations = eventTypeAssociations(
            events = retainedTimelineEvents,
            glucose = reportGlucose(glucoseBuckets30d),
            therapy = therapy30d,
            forecasts = forecasts.points,
            telemetry = summaryTelemetry.points
        )
        val plannedActivities = ArrayList<ClinicalPlannedActivitySummary>()
        energyProfileSourceSnapshot?.plannedActivities.orEmpty()
            .forEachIndexed { index, event ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) {
                    currentCoroutineContext().ensureActive()
                }
                plannedActivities += toClinicalPlannedActivities(
                    event = event,
                    window = plannedActivityWindow,
                    telemetry = plannedActivityTelemetry.points,
                    targetManagerEvidenceSnapshot = energyProfileSourceSnapshot
                        ?.targetManagerEvidence
                        ?: ClinicalTargetManagerEvidenceSnapshot.EMPTY
                )
            }
        val dataset = ClinicalReportDataset(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = nowTs,
            zoneId = zoneId.id,
            currentSnapshot = currentSnapshot(
                nowTs,
                detail24h,
                snapshot.sensitivityRuntime,
                selectForecasts(
                    snapshot.sensitivityRuntimeForecasts,
                    0L,
                    Long.MAX_VALUE
                ).points,
                snapshot.sensitivityRuntimeAuthoritative
            ),
            detail24h = detail24h,
            glucose7d = reportGlucose(glucoseBuckets7d),
            therapy7d = therapy7d,
            targets7d = resolved7d.events.map(ClinicalTargetEvent::point),
            glucose30d = reportGlucose(glucoseBuckets30d),
            therapy30d = therapy30d,
            targets30d = resolved30d.events.map(ClinicalTargetEvent::point),
            summary24h = summary24h,
            summary7d = summary7d,
            summary30d = summary30d,
            energyProfile = energyProfile,
            plannedActivities = plannedActivities.sortedWith(CLINICAL_PLANNED_ACTIVITY_ORDER),
            eventSummaries = eventSummaries,
            eventTypeAssociations = eventTypeAssociations
        )
        val compact = serializeCancellable(dataset)
        return ClinicalReportPayload(dataset, compact, sha256Cancellable(compact))
    }

    suspend fun buildAlertAiDataset(nowTs: Long): AlertAiContextDataset {
        val from14d = subtractExact(nowTs, 14L * DAY_MS)
        val from24h = subtractExact(nowTs, DAY_MS)
        val targetLookback = subtractExact(from14d, MAX_TARGET_DURATION_MS)
        val alertSnapshot = alertAiSource?.load(nowTs)
            ?: throw AlertAiContextException.LimitExceeded()
        val snapshot = alertSnapshot.report
        val derivedBudget = alertSnapshot.derivedBudget
        currentCoroutineContext().ensureActive()

        val selectedGlucose = selectGlucoseRows(
            snapshot.glucose.filter { it.ts in from14d..nowTs }
        )
        val calibratedGlucose = alertSnapshot.calibratedGlucose
        val glucose14d = reportGlucose(
            canonicalizeGlucoseBuckets(
                calibratedGlucose,
                from14d,
                nowTs,
                checkpoint = currentCoroutineContext()::ensureActive,
                beforeCanonicalPoint = {
                    derivedBudget?.reserve(AlertAiDatasetDerivedSourceName.CLINICAL_GLUCOSE_14D, 1)
                }
            )
        )
        val detailGlucoseRows = selectedGlucose.filter { it.ts in from24h..nowTs }
        derivedBudget?.reserve(
            AlertAiDatasetDerivedSourceName.CLINICAL_DETAIL_GLUCOSE,
            detailGlucoseRows.size
        )
        val detailGlucose = canonicalGlucose(
            detailGlucoseRows.asSequence()
                .map { ClinicalGlucosePoint(it.ts, canonicalDouble(it.mmol)) }
                .toList()
        )
        val detailCalibratedRows = calibratedGlucose.filter { it.ts in from24h..nowTs }
        derivedBudget?.reserve(
            AlertAiDatasetDerivedSourceName.CLINICAL_DETAIL_CALIBRATED_GLUCOSE,
            detailCalibratedRows.size
        )
        val detailCalibratedGlucose = canonicalGlucose(
            detailCalibratedRows.asSequence()
                .map { ClinicalGlucosePoint(it.ts, canonicalDouble(it.mmol)) }
                .toList()
        )

        val boundedTherapyRows = snapshot.therapy.asSequence()
            .filter { it.ts in targetLookback..nowTs }
            .filter { it.type.trim().lowercase(Locale.US) in THERAPY_TYPES }
            .filterNot(ClinicalTherapyProjection::isBroadcastArtifact)
            .toList()
        val deduplicatedRows = deduplicateTherapyRows(boundedTherapyRows)
        val nonTargetRows = deduplicatedRows.filterNot { it.type.equals("temp_target", true) }
        derivedBudget?.reserve(
            AlertAiDatasetDerivedSourceName.CLINICAL_THERAPY,
            nonTargetRows.size
        )
        val therapyEvents = mapChecked(
            nonTargetRows,
            ::sanitizeTherapyEvent
        )
        val targetRows = deduplicatedRows.filter { it.type.equals("temp_target", true) }
        derivedBudget?.reserve(
            AlertAiDatasetDerivedSourceName.CLINICAL_TARGET,
            targetRows.size
        )
        val targetEvents = mapChecked(
            targetRows,
            ::sanitizeTargetEvent
        )
        val canonicalTherapy = canonicalTherapyEvents(therapyEvents)
            .map(ClinicalTherapyEvent::point)
        val detailTargets = resolveTargets(
            canonicalTargetEvents(targetEvents),
            from24h,
            nowTs
        ).events.map(ClinicalTargetEvent::point)
        val forecasts = selectForecasts(snapshot.forecasts, from24h, nowTs) { count ->
            derivedBudget?.reserve(AlertAiDatasetDerivedSourceName.CLINICAL_FORECAST, count)
        }
        val telemetry = selectTelemetry(snapshot.telemetry, from24h, nowTs) { count ->
            derivedBudget?.reserve(AlertAiDatasetDerivedSourceName.CLINICAL_TELEMETRY, count)
        }
        derivedBudget?.reserve(AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DETAIL_WINDOW, 1)
        val detail24h = ClinicalDetailWindow(
            fromTs = from24h,
            throughTs = nowTs,
            glucose = detailGlucose,
            calibratedGlucose = detailCalibratedGlucose,
            therapy = canonicalTherapy.filter { it.ts in from24h..nowTs },
            targets = detailTargets,
            forecasts = forecasts.points,
            telemetry = telemetry.points,
            forecastQuality = forecastQuality(forecasts.points, detailGlucose)
        )
        currentCoroutineContext().ensureActive()

        val timelineEvents = alertSnapshot.timelineEvents
        if (timelineEvents.size > AlertAiContextBuilder.MAX_CONTEXT_ROWS) {
            throw AlertAiContextException.LimitExceeded()
        }
        val retainedEvents = retainedTimelineEvents(timelineEvents, from14d, nowTs)
        derivedBudget?.reserve(
            AlertAiDatasetDerivedSourceName.CLINICAL_EVENT_SUMMARY,
            retainedEvents.size
        )
        val events14d = retainedEvents.map(::toClinicalEventSummary)
        derivedBudget?.reserve(AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DATASET, 1)
        return AlertAiContextDataset(
            generatedAt = nowTs,
            detail24h = detail24h,
            glucose14d = glucose14d,
            therapy14d = canonicalTherapy.filter { it.ts in from14d..nowTs },
            events14d = events14d,
            retainedWorkBudget = derivedBudget
        )
    }

    private suspend fun retainedTimelineEvents(
        events: List<CompensationEvent>,
        fromTs: Long,
        throughTs: Long
    ): List<CompensationEvent> {
        val retainedById = linkedMapOf<String, CompensationEvent>()
        events.forEachIndexed { index, event ->
            if (index % CHECKPOINT_BATCH_SIZE == 0) currentCoroutineContext().ensureActive()
            if (event.startTs <= throughTs && event.endTs >= fromTs &&
                CompensationEventProfilePolicy.retainForHistory(event)
            ) {
                val retained = retainedById[event.localId]
                if (retained == null || COMPENSATION_EVENT_CANONICAL_ORDER.compare(event, retained) > 0) {
                    retainedById[event.localId] = event
                }
                if (retainedById.size > ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS) {
                    throw ClinicalOpenAiException.DatasetTooLarge()
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return retainedById.values.sortedWith(
            compareBy<CompensationEvent> { it.startTs }
                .thenBy { it.endTs }
                .thenBy { it.type.name }
                .thenBy { it.subtype }
                .thenBy { it.localId }
        )
    }

    private fun toClinicalEventSummary(event: CompensationEvent) = ClinicalEventSummary(
        localId = event.localId,
        type = event.type.name,
        subtype = event.subtype,
        startTs = event.startTs,
        endTs = event.endTs,
        severity = event.severity.name,
        source = event.source.name,
        title = event.title,
        note = event.note,
        status = event.status.name,
        provenance = event.provenance.take(80),
        syntheticUam = event.attributes.any { (key, value) ->
            key.equals("source", true) && value.equals("uam_engine", true)
        }
    )

    private suspend fun <T, R : Any> mapChecked(rows: List<T>, transform: (T) -> R?): List<R> {
        val output = ArrayList<R>(rows.size)
        rows.forEachIndexed { index, row ->
            if (index % CHECKPOINT_BATCH_SIZE == 0) currentCoroutineContext().ensureActive()
            transform(row)?.let(output::add)
        }
        return output
    }

    private fun ClinicalEnergyProfileSourceSnapshot.toClinicalEnergyProfile(
        nowTs: Long,
        zoneId: ZoneId
    ): ClinicalEnergyProfileSummary {
        val today = java.time.Instant.ofEpochMilli(nowTs).atZone(zoneId).toLocalDate()
        val resolution = EnergyProfilePolicy().resolve(settings, today)
        val estimate = EnergyRequirementEstimator().estimate(resolution)
        val snapshot = latestInference
        val inferredFood = snapshot?.foodProfile
            ?.takeIf { snapshot.qualityDays >= MIN_PROFILE_EVIDENCE_DAYS }
            ?.let { raw -> runCatching { MealAbsorptionProfile.valueOf(raw) }.getOrNull() }
        val inferredActivity = snapshot?.activityProfile
            ?.takeIf { snapshot.qualityDays >= MIN_PROFILE_EVIDENCE_DAYS }
            ?.let { raw -> runCatching { ActivityProfile.valueOf(raw) }.getOrNull() }
        val food = when (settings.foodProfileMode) {
            FoodProfileMode.MANUAL -> settings.manualFoodProfile to "MANUAL"
            FoodProfileMode.AUTO -> inferredFood?.let { it to "AUTO" } ?: (null to "DEFAULT")
        }
        val activity = when (settings.activityProfileMode) {
            ActivityProfileMode.MANUAL -> settings.manualActivityProfile to "MANUAL"
            ActivityProfileMode.AUTO -> inferredActivity?.let { it to "AUTO" } ?: (null to "DEFAULT")
        }
        val maintenance = (estimate as? EnergyEstimate.Available)?.maintenanceKcal?.let {
            ClinicalRange(canonicalDouble(it.minimum), canonicalDouble(it.maximum))
        }
        return ClinicalEnergyProfileSummary(
            derivedAgeYears = (resolution as? io.aaps.copilot.domain.profile.ProfileResolution.Complete)
                ?.ageYears,
            sex = settings.physiologicalSex.name.takeUnless { it == "UNSPECIFIED" },
            foodProfile = food.first?.name,
            foodProfileSource = food.second,
            foodDurationMinutes = snapshot?.foodDurationMinutes
                ?.takeIf { food.second == "AUTO" },
            activityProfile = activity.first?.name,
            activityProfileSource = activity.second,
            confidence = snapshot?.tier ?: "INSUFFICIENT_DATA",
            evidenceDays = snapshot?.qualityDays ?: 0,
            maintenanceEnergyKcal = maintenance,
            calorieGoalMode = settings.calorieGoalMode.name,
            shareProfileWithAi = settings.shareProfileWithAi
        )
    }

    private fun ClinicalPeriodSummary.withMealEnergy(
        profile: ClinicalEnergyProfileSummary?,
        therapyEvents: List<ClinicalTherapyEvent>,
        manualOverrides: List<MealEnergyOverrideEntity>
    ): ClinicalPeriodSummary {
        val carbEnergy = carbohydrateEnergyKcal
        val manualOverrideByReference = manualOverrides.asSequence()
            .filter { override ->
                override.canonicalTherapyIdentity.isNotBlank() &&
                    override.canonicalTherapyIdentity.length <= MAX_MEAL_REFERENCE_LENGTH &&
                    override.therapyRevisionHash.isNotBlank() &&
                    override.therapyRevisionHash.length <= MAX_MEAL_REFERENCE_LENGTH &&
                    override.caloriesKcal.isFinite() &&
                    override.caloriesKcal in MIN_MANUAL_MEAL_KCAL..MAX_MANUAL_MEAL_KCAL
            }
            .associateBy { override ->
                override.canonicalTherapyIdentity to override.therapyRevisionHash
            }
        val manualEntries = therapyEvents.asSequence()
            .filter { event -> event.point.carbsG != null && !event.point.syntheticUam }
            .mapNotNull { event ->
                val identity = event.canonicalCarbId?.toString() ?: return@mapNotNull null
                val revision = event.canonicalCarbRevision ?: return@mapNotNull null
                manualOverrideByReference[identity to revision]?.let { override ->
                    event.point.carbsG!! to override.caloriesKcal
                }
            }
            .toList()
        val manualMealEnergy = manualEntries.sumOf { (_, calories) -> calories }
            .takeIf { manualEntries.isNotEmpty() }
            ?.let(::canonicalDouble)
        val manualCarbEnergy = manualEntries.sumOf { (carbs, _) ->
            carbs * ClinicalPeriodSummary.CARBOHYDRATE_KCAL_PER_GRAM
        }
        val estimatedCarbEnergy = (carbEnergy - manualCarbEnergy).coerceAtLeast(0.0)
        val foodProfile = profile?.foodProfile
            ?.let { raw -> runCatching { MealAbsorptionProfile.valueOf(raw) }.getOrNull() }
        val estimatedRemainder = foodProfile?.let { mealProfile ->
            val share = when (mealProfile) {
                MealAbsorptionProfile.FAST -> 0.75..1.00
                MealAbsorptionProfile.MIXED -> 0.45..0.70
                MealAbsorptionProfile.FAT_PROTEIN -> 0.25..0.50
            }
            ClinicalRange(
                minimum = canonicalDouble(estimatedCarbEnergy / share.endInclusive),
                maximum = canonicalDouble(estimatedCarbEnergy / share.start)
            )
        }
        val totalMealEnergy = when {
            manualMealEnergy == null -> estimatedRemainder
            estimatedRemainder != null -> ClinicalRange(
                minimum = canonicalDouble(manualMealEnergy + estimatedRemainder.minimum),
                maximum = canonicalDouble(manualMealEnergy + estimatedRemainder.maximum)
            )
            estimatedCarbEnergy <= ENERGY_EPSILON ->
                ClinicalRange(manualMealEnergy, manualMealEnergy)
            else -> null
        }
        val netEnergy = totalMealEnergy?.takeIf {
            coveragePct >= MIN_ENERGY_COVERAGE_PCT &&
                activity.coveragePct >= MIN_ENERGY_COVERAGE_PCT
        }?.let { intake ->
            val active = activity.activeCaloriesKcal?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return@let null
            ClinicalRange(
                minimum = canonicalDouble(intake.minimum - active),
                maximum = canonicalDouble(intake.maximum - active)
            )
        }
        return copy(
            mealEnergy = ClinicalMealEnergySummary(
                carbohydrateEnergyKcal = carbEnergy,
                manualMealEnergyKcal = manualMealEnergy,
                estimatedTotalMealEnergyKcal = totalMealEnergy,
                source = when {
                    manualMealEnergy != null && estimatedCarbEnergy <= ENERGY_EPSILON ->
                        ClinicalMealEnergySource.MANUAL_MEAL_ENTRY
                    manualMealEnergy != null && totalMealEnergy != null ->
                        ClinicalMealEnergySource.MIXED_MANUAL_AND_ESTIMATED
                    manualMealEnergy != null -> ClinicalMealEnergySource.MANUAL_MEAL_ENTRY
                    totalMealEnergy != null -> ClinicalMealEnergySource.ESTIMATED_FROM_CARB_SHARE
                    else -> ClinicalMealEnergySource.NOT_AVAILABLE
                },
                netEnergyKcal = netEnergy
            )
        )
    }

    private suspend fun toClinicalPlannedActivities(
        event: io.aaps.copilot.data.local.entity.PlannedActivityEventEntity,
        window: ClinicalPreparedPeriodWindow,
        telemetry: List<ClinicalTelemetryPoint>,
        targetManagerEvidenceSnapshot: ClinicalTargetManagerEvidenceSnapshot
    ): List<ClinicalPlannedActivitySummary> {
        if (!event.enabled ||
            event.durationMinutes !in 1..ClinicalPlannedActivityPeriodPolicy.MAX_SUPPORTED_DURATION_MINUTES
        ) {
            return emptyList()
        }
        val schedule = runCatching {
            PlannedActivitySchedule(
                eventId = event.eventId,
                enabled = event.enabled,
                title = event.title,
                type = PlannedActivityType.valueOf(event.activityType.trim().uppercase(Locale.US)),
                intensity = PlannedActivityIntensity.valueOf(event.intensity.trim().uppercase(Locale.US)),
                localStart = LocalDateTime.parse(event.localStartIso),
                durationMinutes = event.durationMinutes,
                timezoneId = event.timezoneId,
                recurrenceDays = DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
                    event.recurrenceDaysMask and (1 shl (day.value - 1)) != 0
                },
                recurrenceEndEpochDay = event.recurrenceEndEpochDay,
                revision = event.revision,
                createdAtMs = event.createdAtMs,
                updatedAtMs = event.updatedAtMs
            )
        }.getOrNull() ?: return emptyList()
        val zone = runCatching { ZoneId.of(schedule.timezoneId) }.getOrNull() ?: return emptyList()
        val firstDate = Instant.ofEpochMilli(
            ClinicalPlannedActivityPeriodPolicy.earliestPotentialStart(window.fromTs)
        ).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(window.throughTs).atZone(zone).toLocalDate()
        if (firstDate > lastDate) return emptyList()
        val engine = ActivityScheduleEngine()
        val output = ArrayList<ClinicalPlannedActivitySummary>()
        var date = firstDate
        var dateIndex = 0
        while (date <= lastDate) {
            if (dateIndex % CHECKPOINT_BATCH_SIZE == 0) {
                currentCoroutineContext().ensureActive()
            }
            val occurrence = engine.materialize(schedule, date)
            if (occurrence != null && ClinicalPlannedActivityPeriodPolicy.overlaps(
                    startTs = occurrence.start.toEpochMilli(),
                    endTsExclusive = occurrence.end.toEpochMilli(),
                    window = window
                )
            ) {
                val startMs = occurrence.start.toEpochMilli()
                val endMs = occurrence.end.toEpochMilli()
                val observed = observedActivityMinutes(startMs, endMs, telemetry)
                val targetEvidence = targetManagerEvidence(
                    occurrence,
                    targetManagerEvidenceSnapshot
                )
                output += ClinicalPlannedActivitySummary(
                    type = occurrence.type.name,
                    intensity = occurrence.intensity.name,
                    plannedStartMs = startMs,
                    plannedDurationMinutes = event.durationMinutes,
                    observedMinutes = observed,
                    adherence = if (observed == null) "NOT_MEASURED" else "MEASURED",
                    targetDecision = targetEvidence.decision,
                    targetBlockers = targetEvidence.blockers
                )
            }
            if (date == lastDate) break
            date = date.plusDays(1L)
            dateIndex++
        }
        return output
    }

    /** Decision records are read-only diagnostic evidence; uncorrelated records stay absent. */
    private fun targetManagerEvidence(
        occurrence: io.aaps.copilot.domain.profile.PlannedActivityOccurrence,
        evidence: ClinicalTargetManagerEvidenceSnapshot
    ): ActivityTargetEvidence {
        if (!evidence.complete) return ActivityTargetEvidence(decision = null)
        val fromTs = occurrence.evaluationStarts.toEpochMilli()
        val throughTs = occurrence.end.toEpochMilli()
        val decision = evidence.rows.asSequence()
            .filter { it.timestamp in fromTs..throughTs }
            .filter { it.referencesActivityOccurrence(occurrence.eventId, occurrence.revision) }
            .maxWithOrNull(compareBy<ClinicalTargetManagerEvidence> { it.timestamp }.thenBy { it.id })
        return if (decision == null) {
            ActivityTargetEvidence(NO_TARGET_MANAGER_DECISION)
        } else {
            ActivityTargetEvidence(
                decision = decision.outcome.takeIf { it in TARGET_MANAGER_OUTCOMES }
                    ?: NO_TARGET_MANAGER_DECISION,
                blockers = if (decision.outcome.startsWith("BLOCK_")) {
                    decision.safeReasonCodes()
                } else {
                    emptyList()
                }
            )
        }
    }

    private fun ClinicalTargetManagerEvidence.referencesActivityOccurrence(
        occurrenceId: String,
        occurrenceRevision: Long
    ): Boolean {
        val matchesWinner = winnerJson
            ?.takeIf { utf8ByteSize(it) <= MAX_TARGET_MANAGER_JSON_BYTES }
            ?.let { raw -> runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() }
            ?.get("activityProposal")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { activity ->
                activity.get("occurrenceId")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString == occurrenceId &&
                    activity.get("occurrenceRevision")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asLong == occurrenceRevision
            } == true
        if (matchesWinner) return true
        return safeReasonCodes().contains("occurrence=$occurrenceId")
    }

    private fun ClinicalTargetManagerEvidence.safeReasonCodes(): List<String> = reasonCodesJson
        .takeIf { utf8ByteSize(it) <= MAX_TARGET_MANAGER_JSON_BYTES }
        ?.let { raw -> runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() }
        ?.asSequence()
        ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
        ?.filter { code -> code.matches(TARGET_MANAGER_REASON_CODE) }
        ?.distinct()
        ?.sorted()
        ?.take(MAX_TARGET_MANAGER_REASONS)
        ?.toList()
        .orEmpty()

    private data class ActivityTargetEvidence(
        val decision: String?,
        val blockers: List<String> = emptyList()
    )

    private fun utf8ByteSize(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    /**
     * Active-minute telemetry is cumulative, therefore only a trusted pair inside the
     * concrete occurrence can support an observed duration. Missing or sparse telemetry
     * stays explicitly NOT_MEASURED instead of claiming adherence from the schedule.
     */
    private fun observedActivityMinutes(
        startMs: Long,
        endMs: Long,
        telemetry: List<ClinicalTelemetryPoint>
    ): Int? {
        val trusted = telemetry.asSequence()
            .filter { it.ts in startMs..endMs }
            .filter {
                it.key == PhysicalActivityTelemetryPolicy.ACTIVE_MINUTES_KEY &&
                    PhysicalActivityTelemetryPolicy.normalizeQuality(it.quality) in
                    PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES
            }
            .filter {
                it.origin == ClinicalTelemetryOrigin.LOCAL_ACTIVITY ||
                    it.origin == ClinicalTelemetryOrigin.HEALTH_CONNECT
            }
            .filter { it.value.isFinite() && it.value >= 0.0 }
            .sortedBy(ClinicalTelemetryPoint::ts)
            .toList()
        if (trusted.size < 2) return null
        val elapsed = trusted.last().value - trusted.first().value
        return elapsed.takeIf { it.isFinite() && it >= 0.0 }
            ?.roundToLong()
            ?.toInt()
            ?.coerceAtMost(((endMs - startMs) / 60_000L).toInt())
    }

    private fun forecastQuality(
        forecasts: List<ClinicalForecastPoint>,
        actuals: List<ClinicalGlucosePoint>
    ): List<ClinicalForecastQuality> = listOf(5, 30, 60).map { horizon ->
        val pairs = forecasts.asSequence()
            .filter { it.horizonMin == horizon }
            .mapNotNull { forecast ->
                val expectedTs = forecast.ts + horizon * 60_000L
                actuals.minByOrNull { kotlin.math.abs(it.ts - expectedTs) }
                    ?.takeIf { kotlin.math.abs(it.ts - expectedTs) <= 150_000L }
                    ?.let { actual -> forecast to actual }
            }
            .toList()
        if (pairs.size < MIN_FORECAST_QUALITY_SAMPLES) {
            ClinicalForecastQuality(horizonMin = horizon, sampleCount = pairs.size)
        } else {
            ClinicalForecastQuality(
                horizonMin = horizon,
                sampleCount = pairs.size,
                meanAbsoluteErrorMmol = canonicalDouble(
                    pairs.map { (forecast, actual) -> abs(forecast.mmol - actual.mmol) }.average()
                ),
                ciCoveragePct = canonicalDouble(
                    pairs.count { (forecast, actual) -> actual.mmol in forecast.lower..forecast.upper }
                        .toDouble() * 100.0 / pairs.size
                )
            )
        }
    }

    companion object {
        private val COMPENSATION_EVENT_CANONICAL_ORDER = Comparator<CompensationEvent>(
            ::compareCompensationEvents
        )

        private fun compareCompensationEvents(
            left: CompensationEvent,
            right: CompensationEvent
        ): Int {
            var result = left.revision.compareTo(right.revision)
            if (result != 0) return result
            result = left.startTs.compareTo(right.startTs)
            if (result != 0) return result
            result = left.endTs.compareTo(right.endTs)
            if (result != 0) return result
            result = left.type.name.compareTo(right.type.name)
            if (result != 0) return result
            result = left.subtype.compareTo(right.subtype)
            if (result != 0) return result
            result = left.severity.name.compareTo(right.severity.name)
            if (result != 0) return result
            result = left.source.name.compareTo(right.source.name)
            if (result != 0) return result
            result = left.title.compareTo(right.title)
            if (result != 0) return result
            result = compareNullableStrings(left.note, right.note)
            if (result != 0) return result
            result = left.status.name.compareTo(right.status.name)
            if (result != 0) return result
            result = left.provenance.compareTo(right.provenance)
            if (result != 0) return result
            result = compareAttributes(left.attributes, right.attributes)
            if (result != 0) return result
            return left.localId.compareTo(right.localId)
        }

        private fun compareNullableStrings(left: String?, right: String?): Int = when {
            left === right -> 0
            left == null -> -1
            right == null -> 1
            else -> left.compareTo(right)
        }

        private fun compareAttributes(
            left: Map<String, String>,
            right: Map<String, String>
        ): Int {
            val entryOrder = compareBy<Map.Entry<String, String>> { it.key }.thenBy { it.value }
            val leftEntries = left.entries.sortedWith(entryOrder)
            val rightEntries = right.entries.sortedWith(entryOrder)
            val commonSize = minOf(leftEntries.size, rightEntries.size)
            for (index in 0 until commonSize) {
                var result = leftEntries[index].key.compareTo(rightEntries[index].key)
                if (result != 0) return result
                result = leftEntries[index].value.compareTo(rightEntries[index].value)
                if (result != 0) return result
            }
            return leftEntries.size.compareTo(rightEntries.size)
        }

        const val SCHEMA_VERSION = 14
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        internal const val MAX_TARGET_DURATION_MS = 7L * DAY_MS
        private const val TARGET_END_TOLERANCE_MS = 60_000L
        private const val CHECKPOINT_BATCH_SIZE = 256
        private const val MIN_PROFILE_EVIDENCE_DAYS = 7
        private const val MIN_ENERGY_COVERAGE_PCT = 70.0
        private const val MIN_FORECAST_QUALITY_SAMPLES = 3
        private const val MIN_MANUAL_MEAL_KCAL = 1.0
        private const val MAX_MANUAL_MEAL_KCAL = 10_000.0
        private const val MAX_MEAL_REFERENCE_LENGTH = 128
        private const val MAX_TARGET_MANAGER_JSON_BYTES = 16 * 1_024
        private const val MAX_TARGET_MANAGER_REASONS = 12
        private const val MAX_EVENT_TYPE_CHARS = 32
        private const val MAX_EVENT_SUBTYPE_CHARS = 48
        private const val MAX_EVENT_SEVERITY_CHARS = 16
        private const val MAX_EVENT_TITLE_CHARS = 60
        private const val MAX_EVENT_NOTE_CHARS = 500
        private const val ENERGY_EPSILON = 0.000_001
        private const val NO_TARGET_MANAGER_DECISION = "NO_DECISION"
        private val TARGET_MANAGER_REASON_CODE = Regex("[A-Za-z0-9_.:=#-]{1,120}")
        private val TARGET_MANAGER_OUTCOMES = TargetDecisionOutcome.entries
            .map(TargetDecisionOutcome::name)
            .toSet()
        private val PLANNED_ACTIVITY_TYPES = setOf("WALKING", "AEROBIC", "STRENGTH", "MIXED")
        private val PLANNED_ACTIVITY_INTENSITIES = setOf("LIGHT", "MEDIUM", "HIGH")
        private val CANONICAL_GSON: Gson =
            GsonBuilder().disableHtmlEscaping().serializeNulls().create()

        internal val THERAPY_TYPES = listOf(
            "bolus", "insulin", "correction_bolus", "meal_bolus", "carbs", "temp_target",
            "infusion_set_change", "sensor_change", "insulin_refill",
            "pump_battery_change", "exercise", "profile_switch"
        )
        internal val TELEMETRY_KEYS = listOf(
            "iob_effective_units", "iob_units",
            "cob_effective_grams", "cob_grams",
            "isf_runtime_selected_value", "cr_runtime_selected_value",
            "isf_runtime_source_resolved", "cr_runtime_source_resolved",
            "uam_runtime_control_flag",
            "uam_runtime_equivalent_carbs_grams", "uam_runtime_confidence",
            "uam_calculated_carbs_grams", "uam_calculated_confidence",
            "sensor_quality_score", "sensor_quality_blocked",
            "sensor_age_hours", "sensor_lag_minutes",
            "activity_ratio", "steps_count", "distance_km",
            "active_minutes", "calories_active_kcal",
            "basal_rate_u_h", "profile_percent"
        )
        internal val SUMMARY_TELEMETRY_KEYS = listOf(
            "isf_runtime_selected_value", "cr_runtime_selected_value",
            "uam_runtime_control_flag", "uam_runtime_equivalent_carbs_grams",
            "activity_ratio", "steps_count", "distance_km",
            "active_minutes", "calories_active_kcal",
            "basal_rate_u_h", "profile_percent"
        )

        internal fun sanitizeTherapy(row: ClinicalTherapyProjection): ClinicalTherapyPoint? =
            sanitizeTherapyEvent(row)?.point

        internal fun sanitizeTherapyEvent(row: ClinicalTherapyProjection): ClinicalTherapyEvent? {
            val type = row.type.trim().lowercase(Locale.US)
            if (type !in THERAPY_TYPES || type == "temp_target" || row.isBroadcastArtifact) return null
            val payload = BoundedClinicalPayloadParser.parse(row.payloadJson) ?: return null
            val components = resolveTherapyComponents(
                type = type,
                payload = payload.scalarValues(),
                componentTrust = payload.componentTrust()
            )
            val contextKind = CONTEXT_TYPES[type]
            if (contextKind != null) {
                if (!components.wholeEventValid) return null
                return ClinicalTherapyEvent(
                    rowId = row.rowId,
                    point = ClinicalTherapyPoint(
                        ts = row.ts,
                        contextKind = contextKind
                    ).also { it.internalEventId = row.rowId.takeIf(String::isNotBlank) }
                )
            }
            val insulin = components.insulinU
            val carbs = components.carbsG
            if (insulin == null && carbs == null) return null
            return ClinicalTherapyEvent(
                rowId = row.rowId,
                point = ClinicalTherapyPoint(
                    ts = row.ts,
                    insulinU = insulin?.let(::canonicalDouble),
                    carbsG = carbs?.let(::canonicalDouble),
                    syntheticUam = components.carbKind == TherapyCarbComponentKind.UAM_SYNTHETIC,
                    insulinEvidence = insulin?.let {
                        insulinEvidence(row, payload)
                    }
                ).also { it.internalEventId = row.rowId.takeIf(String::isNotBlank) },
                canonicalCarbId = components.canonicalCarbId,
                canonicalCarbRevision = payload.componentTrust().canonicalCarbRevision
                    ?.takeIf {
                        components.carbKind == TherapyCarbComponentKind.REAL &&
                            !payload.canonicalReferenceConflict &&
                            "aapscarbid" !in payload.duplicateSemanticKeys
                    }
            )
        }

        internal fun sanitizeTarget(row: ClinicalTherapyProjection): ClinicalTargetPoint? =
            sanitizeTargetEvent(row)?.point

        internal fun sanitizeTargetEvent(row: ClinicalTherapyProjection): ClinicalTargetEvent? {
            if (!row.type.equals("temp_target", true) || row.isBroadcastArtifact) return null
            val payload = BoundedClinicalPayloadParser.parse(row.payloadJson) ?: return null
            val directMmolKeys = arrayOf("targetMmol")
            val directGenericKeys = arrayOf("target")
            val lowMmolKeys = arrayOf("targetBottomMmol", "targetLowMmol")
            val lowGenericKeys = arrayOf("targetBottom", "target_bottom", "targetLow")
            val highMmolKeys = arrayOf("targetTopMmol", "targetHighMmol")
            val highGenericKeys = arrayOf("targetTop", "target_top", "targetHigh", "target_high")
            val direct = mmolValue(payload, directMmolKeys, directGenericKeys)
            var low = mmolValue(
                payload,
                lowMmolKeys,
                lowGenericKeys
            )
            var high = mmolValue(
                payload,
                highMmolKeys,
                highGenericKeys
            )
            if (payload.hasField(*(directMmolKeys + directGenericKeys)) && direct == null) return null
            if (payload.hasField(*(lowMmolKeys + lowGenericKeys)) && low == null) return null
            if (payload.hasField(*(highMmolKeys + highGenericKeys)) && high == null) return null
            if (low == null && high == null && direct != null) {
                low = direct
                high = direct
            }
            if (low != null && high != null && low > high) return null

            val hasDuration = payload.hasNumber(
                "durationInMilliseconds", "durationMs", "targetDurationMs",
                "durationMinutes", "durationInMinutes", "duration"
            )
            val parsedDuration = parseDurationMs(payload)
            if (hasDuration && parsedDuration == null) return null
            val durationMs = parsedDuration ?: payload.exactLong(
                "endTs", "endTimestamp", "expiresAt", "targetEndTs", "tempTargetExpiresAt"
            )?.let { end -> runCatching { subtractExact(end, row.ts) }.getOrNull() }
            val explicitEnd = payload.exactLong(
                "endTs", "endTimestamp", "expiresAt", "targetEndTs", "tempTargetExpiresAt"
            )
            val endKeys = arrayOf(
                "endTs", "endTimestamp", "expiresAt", "targetEndTs", "tempTargetExpiresAt"
            )
            if (payload.hasField(*endKeys) && explicitEnd == null) return null
            if (durationMs != null && durationMs !in 0L..MAX_TARGET_DURATION_MS) return null
            if (explicitEnd != null) {
                val explicitDuration = runCatching { subtractExact(explicitEnd, row.ts) }.getOrNull()
                    ?: return null
                if (explicitDuration !in 0L..MAX_TARGET_DURATION_MS) return null
                if (durationMs != null && abs(explicitDuration - durationMs) > TARGET_END_TOLERANCE_MS) {
                    return null
                }
            }
            val endTs = explicitEnd ?: durationMs?.let {
                runCatching { addExact(row.ts, it) }.getOrNull() ?: return null
            }
            val cancellationKeys = arrayOf("cancelled", "canceled", "isCancelled", "isCanceled")
            val cancelled = payload.boolean(*cancellationKeys)
                ?: payload.boolean("isValid")?.not()
            if (payload.hasField(*cancellationKeys) && payload.boolean(*cancellationKeys) == null) {
                return null
            }
            if (payload.hasField("isValid") && payload.boolean("isValid") == null) return null
            if (durationMs == 0L && cancelled != true) return null
            if (low == null && high == null && cancelled != true) return null
            return ClinicalTargetEvent(
                row.rowId,
                ClinicalTargetPoint(
                    row.ts,
                    low?.let(::canonicalDouble),
                    high?.let(::canonicalDouble),
                    durationMs,
                    endTs,
                    cancelled
                ).also { it.internalEventId = row.rowId.takeIf(String::isNotBlank) }
            )
        }

        internal fun canonicalTherapyEvents(
            events: List<ClinicalTherapyEvent>
        ): List<ClinicalTherapyEvent> {
            val sorted = deduplicateByInternalId(events) { it.rowId }.sortedWith(
                compareBy<ClinicalTherapyEvent> { it.point.ts }
                    .thenBy { it.point.insulinU }
                    .thenBy { it.point.carbsG }
                    .thenBy { it.point.syntheticUam }
                    .thenBy { it.rowId }
            )
            val seenCanonicalCarbIds = mutableSetOf<Long>()
            return sorted.mapNotNull { event ->
                val carbId = event.canonicalCarbId
                if (carbId == null || event.point.carbsG == null || seenCanonicalCarbIds.add(carbId)) {
                    return@mapNotNull event
                }
                val insulinOnly = event.point.copy(carbsG = null, syntheticUam = false).also {
                    it.internalEventId = event.point.internalEventId
                }
                event.copy(point = insulinOnly).takeIf {
                    insulinOnly.insulinU != null || insulinOnly.contextKind != null
                }
            }
        }

        internal fun canonicalTargetEvents(
            events: List<ClinicalTargetEvent>
        ): List<ClinicalTargetEvent> = deduplicateByInternalId(events) { it.rowId }
            .sortedWith(targetEventComparator)

        internal fun resolveTargets(
            sourceEvents: List<ClinicalTargetEvent>,
            fromTs: Long,
            throughTs: Long
        ): ResolvedClinicalTargets {
            require(fromTs <= throughTs)
            val events = canonicalTargetEvents(sourceEvents)
                .filter { it.point.ts <= throughTs }
            val intervals = mutableListOf<Pair<ClinicalTargetInterval, String>>()
            var active: ClinicalTargetEvent? = null
            var activeEnd = Long.MIN_VALUE

            fun closeActive(endExclusive: Long) {
                val event = active ?: return
                val point = event.point
                val end = minOf(activeEnd, endExclusive, throughTs)
                val start = maxOf(point.ts, fromTs)
                val low = point.lowMmol ?: point.highMmol
                val high = point.highMmol ?: point.lowMmol
                if (low != null && high != null && end > start) {
                    intervals += ClinicalTargetInterval(start, end, low, high) to event.rowId
                }
                if (endExclusive >= activeEnd) active = null
            }

            events.forEach { event ->
                active?.let { closeActive(event.point.ts) }
                if (event.point.cancelled == true) {
                    active = null
                } else if (event.point.targetMmol != null) {
                    active = event
                    activeEnd = event.point.endTs ?: runCatching {
                        addExact(event.point.ts, MAX_TARGET_DURATION_MS)
                    }.getOrDefault(Long.MAX_VALUE)
                }
            }
            closeActive(throughTs)
            val relevantIds = intervals.mapTo(mutableSetOf()) { it.second }
            val relevantEvents = events.filter {
                it.point.ts in fromTs..throughTs || it.rowId in relevantIds
            }
            return ResolvedClinicalTargets(
                relevantEvents,
                intervals.map { it.first }.sortedBy(ClinicalTargetInterval::startTs)
            )
        }

        internal fun selectForecasts(
            rows: List<ClinicalForecastProjection>,
            fromTs: Long,
            throughTs: Long,
            reserveOutput: (Int) -> Unit = {}
        ): ClinicalSelection<ClinicalForecastPoint> {
            val inWindow = rows.filter { it.ts in fromTs..throughTs }
            val valid = inWindow.filter(::validForecast)
            val winnerGroups = valid.groupBy { it.ts to it.horizonMin }
                .toSortedMap(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
            reserveOutput(winnerGroups.size)
            val winners = winnerGroups
                .values
                .map { candidates -> candidates.maxWith(forecastComparator) }
                .map {
                    ClinicalForecastPoint(
                        it.ts, it.horizonMin, canonicalDouble(it.mmol),
                        canonicalDouble(it.lower), canonicalDouble(it.upper)
                    )
                }
            return ClinicalSelection(winners, inWindow.size - valid.size)
        }

        internal fun selectTelemetry(
            rows: List<ClinicalTelemetryProjection>,
            fromTs: Long,
            throughTs: Long,
            reserveOutput: (Int) -> Unit = {}
        ): ClinicalSelection<ClinicalTelemetryPoint> {
            val inWindow = rows.filter { it.ts in fromTs..throughTs }
            val valid = inWindow.filter(::validTelemetry)
            val winnerGroups = valid.groupBy {
                Triple(it.ts, it.key, telemetryOrigin(it.source))
            }
                .toSortedMap(
                    compareBy<Triple<Long, String, ClinicalTelemetryOrigin>> { it.first }
                        .thenBy { it.second }
                        .thenBy { it.third.wireCode }
                )
            reserveOutput(winnerGroups.size)
            val winners = winnerGroups
                .values
                .map { candidates -> candidates.maxWith(telemetryComparator) }
                .map {
                    ClinicalTelemetryPoint(
                        it.ts,
                        it.key,
                        canonicalDouble(requireNotNull(it.value)),
                        normalizeQuality(it.quality),
                        telemetryOrigin(it.source)
                    )
                }
            return ClinicalSelection(winners, inWindow.size - valid.size)
        }

        internal fun currentSnapshot(
            atTs: Long,
            detail: ClinicalDetailWindow,
            sensitivityRuntime: SensitivityRuntimeSnapshotEntity? = null,
            sensitivityRuntimeForecasts: List<ClinicalForecastPoint> = emptyList(),
            sensitivityRuntimeAuthoritative: Boolean = false
        ): ClinicalCurrentSnapshot {
            val raw = latestGlucose(detail.glucose, atTs)
            val calibrated = latestGlucose(detail.calibratedGlucose, atTs)
            val sampleTs = calibrated?.ts ?: raw?.ts
            val activeTarget = activeTarget(detail.targets, atTs)

            fun isFresh(ts: Long): Boolean = AcceptedSensitivityTupleFreshness.isFresh(ts, atTs)

            fun telemetryPoint(vararg preferredKeys: String): ClinicalTelemetryPoint? {
                val preference = preferredKeys.withIndex().associate { it.value to it.index }
                return detail.telemetry.asSequence()
                    .filter {
                        it.key in preference &&
                            it.value.isFinite() &&
                            isFresh(it.ts)
                    }
                    .maxWithOrNull(
                        compareBy<ClinicalTelemetryPoint> { it.ts }
                            .thenBy { preferredKeys.size - requireNotNull(preference[it.key]) }
                            .thenBy { it.value }
                            .thenBy { it.quality }
                    )
            }

            fun telemetry(vararg preferredKeys: String): Double? =
                telemetryPoint(*preferredKeys)?.value?.let(::canonicalDouble)

            fun physicalTelemetry(key: String): Double? =
                detail.telemetry.asSequence()
                    .filter {
                        it.key == key &&
                            it.value.isFinite() &&
                            isFresh(it.ts) &&
                            (
                                it.origin == ClinicalTelemetryOrigin.LOCAL_ACTIVITY ||
                                    it.origin == ClinicalTelemetryOrigin.HEALTH_CONNECT
                                )
                    }
                    .maxWithOrNull(
                        compareBy<ClinicalTelemetryPoint> { it.ts }
                            .thenBy { it.value }
                    )
                    ?.value
                    ?.let(::canonicalDouble)

            fun valuePairedWithSource(
                valueKey: String,
                source: ClinicalTelemetryPoint?
            ): Double? = source?.let { sourcePoint ->
                detail.telemetry.asSequence()
                    .filter {
                        it.key == valueKey &&
                            it.ts == sourcePoint.ts &&
                            it.value.isFinite()
                    }
                    .maxWithOrNull(
                        compareBy<ClinicalTelemetryPoint> { it.value }
                            .thenBy { it.quality }
                    )
                    ?.value
                    ?.let(::canonicalDouble)
            }

            val isfSource = telemetryPoint("isf_runtime_source_resolved")
            val crSource = telemetryPoint("cr_runtime_source_resolved")
            val acceptedForecastTimestamp = sensitivityRuntimeForecasts
                .takeIf { forecasts ->
                    forecasts.size == REQUIRED_SENSITIVITY_HORIZONS.size &&
                        forecasts.map(ClinicalForecastPoint::horizonMin).toSet() ==
                        REQUIRED_SENSITIVITY_HORIZONS
                }
                ?.mapNotNull { forecast ->
                    runCatching {
                        Math.subtractExact(
                            forecast.ts,
                            Math.multiplyExact(forecast.horizonMin.toLong(), 60_000L)
                        )
                    }.getOrNull()
                }
                ?.takeIf { it.size == REQUIRED_SENSITIVITY_HORIZONS.size }
                ?.distinct()
                ?.singleOrNull()
                ?.takeIf(::isFresh)
            val atomicSensitivity = sensitivityRuntime?.takeIf {
                isFresh(it.generatedAt) &&
                    it.settingsRevision >= 0L &&
                    (!sensitivityRuntimeAuthoritative || acceptedForecastTimestamp != null)
            }
            val loosePrediction30m = detail.forecasts.asSequence()
                .filter {
                    it.horizonMin == 30 &&
                        validMmol(it.mmol) &&
                        isFresh(it.ts)
                }
                .maxWithOrNull(
                    compareBy<ClinicalForecastPoint> { it.ts }
                        .thenBy { it.mmol }
                        .thenBy { it.lower }
                        .thenBy { it.upper }
                )
            val prediction30m = when {
                atomicSensitivity != null && acceptedForecastTimestamp != null ->
                    sensitivityRuntimeForecasts.singleOrNull { it.horizonMin == 30 }
                sensitivityRuntimeAuthoritative -> null
                else -> loosePrediction30m
            }

            return ClinicalCurrentSnapshot(
                rawGlucoseMmol = raw?.mmol,
                calibratedGlucoseMmol = calibrated?.mmol,
                glucoseSampleAgeMinutes = sampleTs?.let { timestamp ->
                    runCatching { subtractExact(atTs, timestamp) }.getOrNull()
                        ?.takeIf { it >= 0L }
                        ?.div(60_000.0)
                        ?.let(::canonicalDouble)
                },
                prediction30mMmol = prediction30m?.mmol?.let(::canonicalDouble),
                prediction30mAgeMinutes = (
                    acceptedForecastTimestamp?.takeIf { atomicSensitivity != null }
                        ?: prediction30m?.ts
                    )?.let { timestamp ->
                    subtractExact(atTs, timestamp)
                        .div(60_000.0)
                        .let(::canonicalDouble)
                },
                effectiveIobUnits = telemetry("iob_effective_units", "iob_units"),
                effectiveCobGrams = telemetry("cob_effective_grams", "cob_grams"),
                selectedIsfMmolPerUnit = atomicSensitivity?.isfEffective?.let(::canonicalDouble)
                    ?: valuePairedWithSource("isf_runtime_selected_value", isfSource)
                        .takeUnless { sensitivityRuntimeAuthoritative },
                selectedIsfSourceCode = atomicSensitivity?.isfResolvedSource?.runtimeSourceCode()
                    ?: isfSource?.value?.let(::canonicalDouble)
                        .takeUnless { sensitivityRuntimeAuthoritative },
                selectedCrGramsPerUnit = atomicSensitivity?.crEffective?.let(::canonicalDouble)
                    ?: valuePairedWithSource("cr_runtime_selected_value", crSource)
                        .takeUnless { sensitivityRuntimeAuthoritative },
                selectedCrSourceCode = atomicSensitivity?.crResolvedSource?.runtimeSourceCode()
                    ?: crSource?.value?.let(::canonicalDouble)
                        .takeUnless { sensitivityRuntimeAuthoritative },
                sensitivityCycleId = atomicSensitivity?.cycleId,
                sensitivitySettingsRevision = atomicSensitivity?.settingsRevision,
                selectedIsfSource = atomicSensitivity?.isfResolvedSource,
                selectedCrSource = atomicSensitivity?.crResolvedSource,
                uamActiveFlag = telemetry("uam_runtime_control_flag"),
                uamEquivalentCarbsGrams = telemetry(
                    "uam_runtime_equivalent_carbs_grams",
                    "uam_calculated_carbs_grams"
                ),
                uamConfidence = telemetry(
                    "uam_runtime_confidence",
                    "uam_calculated_confidence"
                ),
                sensorQualityScore = telemetry("sensor_quality_score"),
                sensorBlockedFlag = telemetry("sensor_quality_blocked"),
                sensorAgeHours = telemetry("sensor_age_hours"),
                sensorLagMinutes = telemetry("sensor_lag_minutes"),
                activityRatio = physicalTelemetry("activity_ratio"),
                stepsCount = physicalTelemetry("steps_count"),
                activeTargetLowMmol = activeTarget?.lowMmol,
                activeTargetHighMmol = activeTarget?.highMmol
            )
        }

        private fun latestGlucose(
            points: List<ClinicalGlucosePoint>,
            atTs: Long
        ): ClinicalGlucosePoint? = points.asSequence()
            .filter { it.ts <= atTs && validMmol(it.mmol) }
            .maxWithOrNull(compareBy<ClinicalGlucosePoint> { it.ts }.thenBy { it.mmol })
            ?.let { it.copy(mmol = canonicalDouble(it.mmol)) }

        private fun activeTarget(
            points: List<ClinicalTargetPoint>,
            atTs: Long
        ): ClinicalTargetPoint? {
            var active: ClinicalTargetPoint? = null
            points.filter { it.ts <= atTs }
                .sortedWith(targetPointComparator)
                .forEach { point ->
                    active = if (point.cancelled == true || point.targetMmol == null) {
                        null
                    } else {
                        val endTs = point.endTs ?: runCatching {
                            addExact(
                                point.ts,
                                point.durationMs ?: MAX_TARGET_DURATION_MS
                            )
                        }.getOrDefault(Long.MAX_VALUE)
                        point.takeIf { endTs > atTs }
                    }
                }
            return active
        }

        internal fun canonicalizeGlucoseBuckets(
            rows: List<ClinicalGlucoseProjection>,
            fromTs: Long,
            throughTs: Long,
            checkpoint: (() -> Unit)? = null,
            beforeCanonicalPoint: () -> Unit = {}
        ): List<ClinicalGlucoseCanonicalBucket> {
            require(fromTs <= throughTs)
            val selected = selectGlucoseRows(rows).filter { it.ts in fromTs..throughTs }
            if (selected.isEmpty()) return emptyList()
            val anchor = selected.last().ts
            val canonical = Glucose5mCanonicalizer.build(
                selected.map {
                    GlucosePoint(it.ts, it.mmol, it.source, qualityForCanonicalizer(it.quality))
                },
                CanonicalGlucoseConfig(
                    fromTs = fromTs,
                    throughTs = throughTs,
                    anchorTs = anchor,
                    maxLookbackMs = subtractExact(throughTs, fromTs)
                ),
                checkpoint,
                beforeCanonicalPoint
            )
            return canonical.points.zip(canonical.representativeTimestamps).map { (point, representativeTs) ->
                ClinicalGlucoseCanonicalBucket(
                    point.ts,
                    representativeTs ?: point.ts,
                    canonicalDouble(point.valueMmol)
                )
            }
        }

        internal fun selectGlucoseRows(rows: List<ClinicalGlucoseProjection>) =
            rows.filter(::isValidGlucoseRow)
                .groupBy(ClinicalGlucoseProjection::ts)
                .toSortedMap()
                .values
                .map { candidates ->
                    candidates.maxWith(
                        compareBy<ClinicalGlucoseProjection> {
                            GlucoseSanitizer.clinicalPriority(it.source, it.quality)
                        }
                            .thenBy { it.source.lowercase(Locale.US) }
                            .thenBy { it.quality.uppercase(Locale.US) }
                            .thenBy { canonicalDouble(it.mmol) }
                            .thenBy { it.rowId }
                    )
                }

        internal fun reportGlucose(buckets: List<ClinicalGlucoseCanonicalBucket>) =
            buckets.map { ClinicalGlucosePoint(it.bucketTs, canonicalDouble(it.medianMmol)) }

        internal fun summaryGlucose(buckets: List<ClinicalGlucoseCanonicalBucket>) =
            buckets.map { ClinicalGlucosePoint(it.representativeTs, it.medianMmol) }

        internal fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it) }

        internal suspend fun sha256Cancellable(value: String): String =
            clinicalSha256Cancellable(value)

        internal fun serialize(dataset: ClinicalReportDataset): String = serializeInternal(
            dataset = dataset,
            maxBytes = ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
            checkpoint = null,
            probe = null,
            eventWindowBounds = ClinicalPartitionEventWindowBounds.capture(dataset)
        )

        internal suspend fun serializeCancellable(
            dataset: ClinicalReportDataset
        ): String = serializeCancellable(dataset, probe = null)

        internal suspend fun serializeCancellable(
            dataset: ClinicalReportDataset,
            probe: ClinicalDatasetSerializationProbe?
        ): String {
            val context = currentCoroutineContext()
            return serializeInternal(
                dataset = dataset,
                maxBytes = ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
                checkpoint = context::ensureActive,
                probe = probe,
                eventWindowBounds = ClinicalPartitionEventWindowBounds.capture(dataset)
            )
        }

        internal suspend fun serializePartitionCancellable(
            dataset: ClinicalReportDataset,
            eventWindowBounds: ClinicalPartitionEventWindowBounds
        ): String = serializePartitionCancellable(dataset, eventWindowBounds, probe = null)

        internal suspend fun serializePartitionCancellable(
            dataset: ClinicalReportDataset,
            eventWindowBounds: ClinicalPartitionEventWindowBounds,
            probe: ClinicalDatasetSerializationProbe?
        ): String {
            val context = currentCoroutineContext()
            return serializeInternal(
                dataset = dataset,
                maxBytes = ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
                checkpoint = context::ensureActive,
                probe = probe,
                eventWindowBounds = eventWindowBounds
            )
        }

        internal suspend fun validateCanonicalCancellable(
            dataset: ClinicalReportDataset,
            expected: String,
            probe: ClinicalDatasetSerializationProbe? = null
        ) {
            val context = currentCoroutineContext()
            validateCanonicalInternal(
                dataset = dataset,
                expected = expected,
                maxBytes = ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
                checkpoint = context::ensureActive,
                probe = probe,
                eventWindowBounds = ClinicalPartitionEventWindowBounds.capture(dataset)
            )
        }

        internal fun serializeForTest(
            dataset: ClinicalReportDataset,
            maxBytes: Int,
            probe: ClinicalDatasetSerializationProbe? = null
        ): String = serializeInternal(
            dataset = dataset,
            maxBytes = maxBytes,
            checkpoint = null,
            probe = probe,
            eventWindowBounds = ClinicalPartitionEventWindowBounds.capture(dataset)
        )

        internal fun validateCanonicalForTest(
            dataset: ClinicalReportDataset,
            expected: String,
            maxBytes: Int,
            probe: ClinicalDatasetSerializationProbe? = null
        ) = validateCanonicalInternal(
            dataset = dataset,
            expected = expected,
            maxBytes = maxBytes,
            checkpoint = null,
            probe = probe,
            eventWindowBounds = ClinicalPartitionEventWindowBounds.capture(dataset)
        )

        private fun serializeInternal(
            dataset: ClinicalReportDataset,
            maxBytes: Int,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?,
            eventWindowBounds: ClinicalPartitionEventWindowBounds
        ): String {
            val output = ClinicalCanonicalUtf8Writer(maxBytes)
            emitCanonicalDataset(
                dataset = dataset,
                maxBytes = maxBytes,
                checkpoint = checkpoint,
                probe = probe,
                eventWindowBounds = eventWindowBounds,
                output = output
            )
            probe?.onFullDatasetAssemblyStarted()
            return output.finish()
        }

        private fun validateCanonicalInternal(
            dataset: ClinicalReportDataset,
            expected: String,
            maxBytes: Int,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?,
            eventWindowBounds: ClinicalPartitionEventWindowBounds
        ) {
            emitCanonicalDataset(
                dataset = dataset,
                maxBytes = maxBytes,
                checkpoint = checkpoint,
                probe = probe,
                eventWindowBounds = eventWindowBounds,
                output = ClinicalCanonicalUtf8Writer.comparing(maxBytes, expected)
            )
        }

        private fun emitCanonicalDataset(
            dataset: ClinicalReportDataset,
            maxBytes: Int,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?,
            eventWindowBounds: ClinicalPartitionEventWindowBounds,
            output: ClinicalCanonicalUtf8Writer
        ) {
            require(dataset.schemaVersion == SCHEMA_VERSION) {
                "Unsupported clinical report schema version"
            }
            require(maxBytes >= 0)
            if (dataset.eventSummaries.size > ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS) {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
            val eventWindows = canonicalEventWindows(dataset, eventWindowBounds, checkpoint)
            probe?.onCanonicalEventWindowsComputed()
            val baseTs = dataset.generatedAt
            val writer = CANONICAL_GSON.newJsonWriter(output)
            writeCanonicalDataset(
                writer = writer,
                dataset = dataset,
                eventWindows = eventWindows,
                baseTs = baseTs,
                checkpoint = checkpoint,
                probe = probe
            )
            writer.close()
            checkpoint?.invoke()
        }

        internal fun canonicalPdfSnapshot(dataset: ClinicalReportDataset): ClinicalReportDataset =
            dataset.copy(
                detail24h = dataset.detail24h.copy(
                    glucose = canonicalGlucose(dataset.detail24h.glucose),
                    calibratedGlucose = canonicalGlucose(dataset.detail24h.calibratedGlucose),
                    therapy = canonicalTherapyPoints(dataset.detail24h.therapy),
                    targets = canonicalTargetPoints(dataset.detail24h.targets),
                    forecasts = canonicalForecastPoints(dataset.detail24h.forecasts),
                    telemetry = canonicalTelemetryPoints(dataset.detail24h.telemetry),
                    forecastQuality = dataset.detail24h.forecastQuality.toList()
                ),
                glucose7d = canonicalGlucose(dataset.glucose7d),
                therapy7d = canonicalTherapyPoints(dataset.therapy7d),
                targets7d = canonicalTargetPoints(dataset.targets7d),
                glucose30d = canonicalGlucose(dataset.glucose30d),
                therapy30d = canonicalTherapyPoints(dataset.therapy30d),
                targets30d = canonicalTargetPoints(dataset.targets30d),
                summary24h = dataset.summary24h?.immutablePdfSnapshot(),
                summary7d = dataset.summary7d.immutablePdfSnapshot(),
                summary30d = dataset.summary30d.immutablePdfSnapshot(),
                plannedActivities = dataset.plannedActivities
                    .map { activity ->
                        activity.copy(targetBlockers = activity.targetBlockers.toList())
                    }
                    .sortedWith(CLINICAL_PLANNED_ACTIVITY_ORDER),
                eventSummaries = dataset.eventSummaries.sortedWith(EVENT_WIRE_ORDER),
                eventTypeAssociations = dataset.eventTypeAssociations.toList()
            )

        private fun ClinicalPeriodSummary.immutablePdfSnapshot(): ClinicalPeriodSummary = copy(
            weekdayPattern = weekdayPattern.toList(),
            weekendPattern = weekendPattern.toList(),
            probableMealWindows = probableMealWindows.toList(),
            recentProbableMealWindows = recentProbableMealWindows.toList()
        )

        internal fun remoteEventPreviewJson(dataset: ClinicalReportDataset): String {
            val root = JsonParser.parseString(serialize(dataset)).asJsonObject
            return CANONICAL_GSON.toJson(JsonObject().apply {
                add("ev24", root.get("ev24").deepCopy())
                add("ev7", root.get("ev7").deepCopy())
                add("ev30", root.get("ev30").deepCopy())
            })
        }

        internal fun eventTypeAssociations(
            events: List<CompensationEvent>,
            glucose: List<ClinicalGlucosePoint>,
            therapy: List<ClinicalTherapyPoint>,
            forecasts: List<ClinicalForecastPoint>,
            telemetry: List<ClinicalTelemetryPoint>
        ): List<ClinicalEventTypeAssociation> = ClinicalEventAssociationEngine.calculate(
            events,
            glucose,
            therapy,
            forecasts,
            telemetry
        )

        @Suppress("UNUSED_PARAMETER")
        internal fun serialize(dataset: ClinicalReportDataset, gson: Gson): String = serialize(dataset)

        internal fun canonicalSelectedEventUnion(
            dataset: ClinicalReportDataset,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalEventSummary> = canonicalEventWindows(
            dataset,
            ClinicalPartitionEventWindowBounds.capture(dataset),
            checkpoint
        )
            .rows
            .map(CanonicalEventWindowRow::event)

        internal fun canonicalSelectedEventUnionForPartition(
            dataset: ClinicalReportDataset,
            eventWindowBounds: ClinicalPartitionEventWindowBounds,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalEventSummary> = canonicalEventWindows(
            dataset,
            eventWindowBounds,
            checkpoint
        )
            .rows
            .map(CanonicalEventWindowRow::event)

        private fun canonicalEventWindows(
            dataset: ClinicalReportDataset,
            eventWindowBounds: ClinicalPartitionEventWindowBounds,
            checkpoint: (() -> Unit)?
        ): CanonicalEventWindows {
            if (dataset.eventSummaries.size > ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS) {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
            val ordered = ArrayList<ClinicalEventSummary>(dataset.eventSummaries.size)
            dataset.eventSummaries.forEachIndexed { index, event ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                ordered += event
            }
            checkpoint?.invoke()
            ordered.sortWith(EVENT_WIRE_ORDER)
            checkpoint?.invoke()
            val rows = ArrayList<CanonicalEventWindowRow>(ordered.size)
            ordered.forEachIndexed { index, event ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                val in24h = event.overlaps(eventWindowBounds.from24h, eventWindowBounds.through24h)
                val in7d = event.overlaps(eventWindowBounds.from7d, eventWindowBounds.through7d)
                val in30d = event.overlaps(eventWindowBounds.from30d, eventWindowBounds.through30d)
                if (in24h || in7d || in30d) {
                    rows += CanonicalEventWindowRow(event, in24h, in7d, in30d)
                }
            }
            return CanonicalEventWindows(rows)
        }

        private fun ClinicalEventSummary.overlaps(fromTs: Long, throughTs: Long): Boolean =
            startTs <= throughTs && endTs >= fromTs

        private data class CanonicalEventWindows(val rows: List<CanonicalEventWindowRow>)

        private data class CanonicalEventWindowRow(
            val event: ClinicalEventSummary,
            val in24h: Boolean,
            val in7d: Boolean,
            val in30d: Boolean
        )

        private val EVENT_WIRE_ORDER = compareBy<ClinicalEventSummary> { it.startTs }
            .thenBy { it.endTs }
            .thenBy { it.type }
            .thenBy { it.subtype }
            .thenBy { it.source }
            .thenBy { it.severity }
            .thenBy { it.title }
            .thenBy { it.note.orEmpty() }
            .thenBy { it.localId }

        private fun sanitizedEventText(value: String, maxChars: Int): String? {
            if (value.isBlank()) return null
            val withoutMarkers = CopilotContextNoteMarker.stripProtectedMarkers(value)
            val sanitized = if (withoutMarkers == value) {
                value
            } else {
                withoutMarkers.trim('|', ' ')
            }
            return sanitized.takeIf(String::isNotBlank)?.take(maxChars)
        }

        private fun ClinicalEventSummary.toClinicalContextEvent(): ClinicalContextEvent =
            ClinicalContextEvent(
                type = type.take(MAX_EVENT_TYPE_CHARS),
                subtype = sanitizedEventText(subtype, MAX_EVENT_SUBTYPE_CHARS),
                startTs = startTs,
                endTs = endTs.takeIf { it > startTs },
                severity = severity.take(MAX_EVENT_SEVERITY_CHARS),
                source = coarseEventSource(source),
                title = sanitizedEventText(title, MAX_EVENT_TITLE_CHARS),
                note = note?.let { sanitizedEventText(it, MAX_EVENT_NOTE_CHARS) }
            )

        private fun coarseEventSource(source: String): String = when (source.trim().uppercase(Locale.US)) {
            "USER" -> "USER"
            "AAPS" -> "AAPS"
            else -> "AUTOMATIC"
        }

        private fun String.runtimeSourceCode(): Double? = when (trim().uppercase(Locale.US)) {
            "AAPS" -> 1.0
            "EVIDENCE_BLEND" -> 2.0
            "COPILOT_NATIVE" -> 3.0
            else -> null
        }

        private fun writeCanonicalDataset(
            writer: JsonWriter,
            dataset: ClinicalReportDataset,
            eventWindows: CanonicalEventWindows,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginObject()
            writer.name("v").value(dataset.schemaVersion)
            writer.name("generatedAt").value(baseTs)
            writer.name("zone").value(dataset.zoneId)
            writer.name("c")
            CANONICAL_GSON.toJson(ClinicalCurrentWireSchema.encode(dataset.currentSnapshot), writer)
            writer.name("d24")
            writeDetail(writer, dataset.detail24h, baseTs, checkpoint, probe)
            writer.name("g7")
            writeGlucoseArray(writer, dataset.glucose7d, baseTs, checkpoint, probe)
            writer.name("e7")
            writeTherapyArray(writer, dataset.therapy7d, baseTs, checkpoint, probe)
            writer.name("t7")
            writeTargetArray(writer, dataset.targets7d, baseTs, checkpoint, probe)
            writer.name("g30")
            writeGlucoseArray(writer, dataset.glucose30d, baseTs, checkpoint, probe)
            writer.name("e30")
            writeTherapyArray(writer, dataset.therapy30d, baseTs, checkpoint, probe)
            writer.name("t30")
            writeTargetArray(writer, dataset.targets30d, baseTs, checkpoint, probe)
            dataset.summary24h?.let {
                writer.name("s24")
                writeSummary(writer, it, baseTs, checkpoint, probe)
            }
            writer.name("s7")
            writeSummary(writer, dataset.summary7d, baseTs, checkpoint, probe)
            writer.name("s30")
            writeSummary(writer, dataset.summary30d, baseTs, checkpoint, probe)
            dataset.energyProfile?.takeIf(ClinicalEnergyProfileSummary::shareProfileWithAi)
                ?.let { profile ->
                    writer.name("ep")
                    writeEnergyProfile(writer, profile)
                    writer.name("pa")
                    writePlannedActivityArray(
                        writer,
                        dataset.plannedActivities,
                        baseTs,
                        checkpoint,
                        probe
                    )
                }
            writer.name("ev24")
            writeEventArray(writer, eventWindows.rows, checkpoint, probe) { it.in24h }
            writer.name("ev7")
            writeEventArray(writer, eventWindows.rows, checkpoint, probe) { it.in7d }
            writer.name("ev30")
            writeEventArray(writer, eventWindows.rows, checkpoint, probe) { it.in30d }
            writer.endObject()
        }

        private fun writeDetail(
            writer: JsonWriter,
            detail: ClinicalDetailWindow,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginObject()
            writer.name("from").value(minuteOffset(detail.fromTs, baseTs))
            writer.name("through").value(minuteOffset(detail.throughTs, baseTs))
            writer.name("g")
            writeGlucoseArray(writer, detail.glucose, baseTs, checkpoint, probe)
            writer.name("gc")
            writeGlucoseArray(writer, detail.calibratedGlucose, baseTs, checkpoint, probe)
            writer.name("e")
            writeTherapyArray(writer, detail.therapy, baseTs, checkpoint, probe)
            writer.name("t")
            writeTargetArray(writer, detail.targets, baseTs, checkpoint, probe)
            writer.name("f")
            writeForecastArray(writer, detail.forecasts, baseTs, checkpoint, probe)
            writer.name("fq")
            writeForecastQualityArray(writer, detail.forecastQuality, checkpoint, probe)
            writer.name("m")
            writeTelemetryArray(writer, detail.telemetry, baseTs, checkpoint, probe)
            writer.endObject()
        }

        private fun writeGlucoseArray(
            writer: JsonWriter,
            points: List<ClinicalGlucosePoint>,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            canonicalGlucose(points, checkpoint).forEachCanonicalRow(checkpoint, probe) {
                writer.writeRow(minuteOffset(it.ts, baseTs), it.mmol)
            }
            writer.endArray()
        }

        private fun writeTherapyArray(
            writer: JsonWriter,
            points: List<ClinicalTherapyPoint>,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            canonicalTherapyPoints(points, checkpoint).forEachCanonicalRow(checkpoint, probe) {
                writer.writeRow(
                    minuteOffset(it.ts, baseTs),
                    it.insulinU,
                    it.carbsG,
                    if (it.syntheticUam) 1 else 0,
                    it.insulinEvidence?.wireCode,
                    it.contextKind?.wireCode
                )
            }
            writer.endArray()
        }

        private fun writeTargetArray(
            writer: JsonWriter,
            points: List<ClinicalTargetPoint>,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            canonicalTargetPoints(points, checkpoint).forEachCanonicalRow(checkpoint, probe) {
                writer.writeRow(
                    minuteOffset(it.ts, baseTs),
                    it.lowMmol,
                    it.highMmol,
                    it.durationMs?.let { duration -> Math.floorDiv(duration, 60_000L) },
                    it.endTs?.let { end -> minuteOffset(end, baseTs) },
                    it.cancelled?.let { cancelled -> if (cancelled) 1 else 0 }
                )
            }
            writer.endArray()
        }

        private fun writeForecastArray(
            writer: JsonWriter,
            points: List<ClinicalForecastPoint>,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            canonicalForecastPoints(points, checkpoint).forEachCanonicalRow(checkpoint, probe) {
                writer.writeRow(
                    minuteOffset(it.ts, baseTs),
                    it.horizonMin,
                    it.mmol,
                    it.lower,
                    it.upper
                )
            }
            writer.endArray()
        }

        private fun writeForecastQualityArray(
            writer: JsonWriter,
            points: List<ClinicalForecastQuality>,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            points.sortedBy(ClinicalForecastQuality::horizonMin)
                .forEachCanonicalRow(checkpoint, probe) {
                    writer.beginObject()
                    writer.name("horizonMin").value(it.horizonMin)
                    writer.name("samples").value(it.sampleCount)
                    writer.writeCanonical("mae", it.meanAbsoluteErrorMmol)
                    writer.writeCanonical("ciCoverage", it.ciCoveragePct)
                    writer.endObject()
                }
            writer.endArray()
        }

        private fun writeTelemetryArray(
            writer: JsonWriter,
            points: List<ClinicalTelemetryPoint>,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            canonicalTelemetryPoints(points, checkpoint).forEachCanonicalRow(checkpoint, probe) {
                writer.writeRow(
                    minuteOffset(it.ts, baseTs),
                    it.key,
                    it.value,
                    it.quality,
                    it.origin.wireCode
                )
            }
            writer.endArray()
        }

        private fun writeSummary(
            writer: JsonWriter,
            summary: ClinicalPeriodSummary,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginObject()
            writer.name("days").value(summary.days)
            writer.name("from").value(minuteOffset(summary.fromTs, baseTs))
            writer.name("through").value(minuteOffset(summary.throughTs, baseTs))
            writer.writeCanonical("coverage", summary.coveragePct)
            writer.writeCanonical("mean", summary.meanMmol)
            writer.writeCanonical("median", summary.medianMmol)
            writer.writeCanonical("cv", summary.coefficientOfVariationPct)
            writer.writeCanonical("below4", summary.timeBelow4Pct)
            writer.writeCanonical("inRange", summary.timeInRangePct)
            writer.writeCanonical("aboveRange", summary.timeAboveRangePct)
            writer.writeCanonical("insulin", summary.totalInsulinU)
            writer.writeCanonical("insulinConfirmed", summary.confirmedInsulinU)
            writer.writeCanonical("insulinIobDerived", summary.estimatedInsulinU)
            writer.writeCanonical("insulinBasal", summary.deliveredBasalInsulinU)
            writer.writeCanonical("insulinBolus", summary.deliveredBolusInsulinU)
            writer.name("insulinSource").value(summary.insulinTotalSource.name)
            writer.name("insulinEvents").value(summary.insulinEventCount)
            writer.name("insulinEstimatedEvents").value(summary.estimatedInsulinEventCount)
            writer.writeCanonical("carbs", summary.totalCarbsG)
            writer.writeCanonical("carbsEnergyKcal", summary.carbohydrateEnergyKcal)
            writer.name("carbsSource").value("COPILOT_CANONICAL_EVENTS")
            writer.writeCanonical("carbsEntered", summary.enteredCarbsG)
            writer.writeCanonical("carbsUam", summary.uamCarbsG)
            writer.writeCanonical("carbsAaps", summary.aapsCarbsG)
            writer.name("carbEnteredEvents").value(summary.enteredCarbEventCount)
            writer.name("carbUamEvents").value(summary.uamCarbEventCount)
            writer.name("mealEnergy").beginObject()
            writer.writeCanonical("carbKcal", summary.mealEnergy.carbohydrateEnergyKcal)
            writer.name("estimatedKcal")
            writeRange(writer, summary.mealEnergy.estimatedTotalMealEnergyKcal)
            writer.name("source").value(summary.mealEnergy.source.name)
            writer.name("netKcal")
            writeRange(writer, summary.mealEnergy.netEnergyKcal)
            writer.endObject()
            writer.writeCanonical("target", summary.meanTargetMmol)
            writer.name("activity").beginObject()
            writer.writeCanonical("coverage", summary.activity.coveragePct)
            writer.writeCanonical("steps", summary.activity.steps)
            writer.writeCanonical("distanceKm", summary.activity.distanceKm)
            writer.writeCanonical("activeMinutes", summary.activity.activeMinutes)
            writer.writeCanonical("activeKcal", summary.activity.activeCaloriesKcal)
            writer.writeCanonical("meanRatio", summary.activity.meanActivityRatio)
            writer.writeCanonical("maxRatio", summary.activity.maxActivityRatio)
            writer.endObject()
            writer.name("basalContext").beginObject()
            writer.writeCanonical("coverage", summary.basalContext.coveragePct)
            writer.writeCanonical("meanRateUph", summary.basalContext.meanProfileRateUph)
            writer.writeCanonical("minRateUph", summary.basalContext.minProfileRateUph)
            writer.writeCanonical("maxRateUph", summary.basalContext.maxProfileRateUph)
            writer.writeCanonical("meanProfilePct", summary.basalContext.meanProfilePercent)
            writer.endObject()
            writer.name("therapyContext").beginObject()
            writer.name("infusionSetChanges").value(summary.therapyContext.infusionSetChanges)
            writer.name("sensorChanges").value(summary.therapyContext.sensorChanges)
            writer.name("insulinRefills").value(summary.therapyContext.insulinRefills)
            writer.name("pumpBatteryChanges").value(summary.therapyContext.pumpBatteryChanges)
            writer.name("exerciseEvents").value(summary.therapyContext.exerciseEvents)
            writer.name("profileSwitches").value(summary.therapyContext.profileSwitches)
            writer.endObject()
            writeMealWindows(writer, "mealWindows", summary.probableMealWindows, checkpoint, probe)
            writeMealWindows(
                writer,
                "recentMealWindows",
                summary.recentProbableMealWindows,
                checkpoint,
                probe
            )
            writer.name("weekday")
            writeHourlyArray(writer, summary.weekdayPattern, checkpoint, probe)
            writer.name("weekend")
            writeHourlyArray(writer, summary.weekendPattern, checkpoint, probe)
            writer.name("quality").beginObject()
            writer.name("expected").value(summary.quality.expectedBuckets)
            writer.name("covered").value(summary.quality.coveredBuckets)
            writer.name("missing").value(summary.quality.missingBuckets)
            writer.writeNullable("maxGapMin", summary.quality.maxGapMinutes)
            writer.name("rejectedGlucose").value(summary.quality.rejected.glucose)
            writer.name("rejectedTherapy").value(summary.quality.rejected.therapy)
            writer.name("rejectedTarget").value(summary.quality.rejected.target)
            writer.name("rejectedForecast").value(summary.quality.rejected.forecast)
            writer.name("rejectedTelemetry").value(summary.quality.rejected.telemetry)
            writer.endObject()
            writer.endObject()
        }

        private fun writeMealWindows(
            writer: JsonWriter,
            name: String,
            windows: List<io.aaps.copilot.domain.eating.ProbableEatingWindow>,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            if (windows.isEmpty()) return
            writer.name(name).beginArray()
            windows.sortedBy { it.medianMinuteOfDay }.forEachCanonicalRow(checkpoint, probe) { window ->
                writer.beginObject()
                writer.name("medianMinute").value(window.medianMinuteOfDay)
                writer.name("startMinute").value(window.startMinuteOfDay)
                writer.name("endMinute").value(window.endMinuteOfDay)
                writer.name("iqrMinutes").value(window.iqrMinutes)
                writer.name("supportDays").value(window.supportDays)
                writer.name("lookbackDays").value(window.lookbackDays)
                writer.name("episodes").value(window.episodeCount)
                writer.name("enteredEpisodes").value(window.enteredEpisodeCount)
                writer.name("uamEpisodes").value(window.uamEpisodeCount)
                writer.writeCanonical("confidencePct", window.confidencePct)
                writer.endObject()
            }
            writer.endArray()
        }

        private fun writeHourlyArray(
            writer: JsonWriter,
            metrics: List<ClinicalHourlyMetric>,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            metrics.sortedBy(ClinicalHourlyMetric::hour).forEachCanonicalRow(checkpoint, probe) {
                writer.writeRow(
                    it.hour,
                    it.sampleCount,
                    it.meanMmol?.let(::canonicalDouble),
                    it.medianMmol?.let(::canonicalDouble)
                )
            }
            writer.endArray()
        }

        private fun writeEnergyProfile(writer: JsonWriter, profile: ClinicalEnergyProfileSummary) {
            writer.beginObject()
            writer.writeNullable("derivedAge", profile.derivedAgeYears)
            writer.writeNullable("sex", profile.sex)
            writer.writeNullable("food", profile.foodProfile)
            writer.name("foodSource").value(profile.foodProfileSource)
            writer.writeNullable("foodDurationMin", profile.foodDurationMinutes)
            writer.writeNullable("activity", profile.activityProfile)
            writer.name("activitySource").value(profile.activityProfileSource)
            writer.name("confidence").value(profile.confidence)
            writer.name("evidenceDays").value(profile.evidenceDays)
            writer.name("maintenanceKcal")
            writeRange(writer, profile.maintenanceEnergyKcal)
            writer.name("calorieGoal").value(profile.calorieGoalMode)
            writer.endObject()
        }

        private fun writePlannedActivityArray(
            writer: JsonWriter,
            activities: List<ClinicalPlannedActivitySummary>,
            baseTs: Long,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?
        ) {
            writer.beginArray()
            activities.sortedWith(CLINICAL_PLANNED_ACTIVITY_ORDER)
                .forEachCanonicalRow(checkpoint, probe) { activity ->
                    writer.beginObject()
                    writer.name("type").value(activity.type)
                    writer.name("intensity").value(activity.intensity)
                    writer.name("start").value(minuteOffset(activity.plannedStartMs, baseTs))
                    writer.name("durationMin").value(activity.plannedDurationMinutes)
                    writer.writeNullable("observedMin", activity.observedMinutes)
                    writer.name("adherence").value(activity.adherence)
                    writer.endObject()
                }
            writer.endArray()
        }

        private fun writeEventArray(
            writer: JsonWriter,
            rows: List<CanonicalEventWindowRow>,
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?,
            include: (CanonicalEventWindowRow) -> Boolean
        ) {
            writer.beginArray()
            rows.forEachIndexed { index, row ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                if (include(row)) {
                    writeEvent(writer, row.event)
                    probe?.onSeriesRowEmitted()
                }
            }
            checkpoint?.invoke()
            writer.endArray()
        }

        private fun writeEvent(writer: JsonWriter, event: ClinicalEventSummary) =
            CANONICAL_GSON.toJson(event.toClinicalContextEvent(), ClinicalContextEvent::class.java, writer)

        private fun writeRange(writer: JsonWriter, range: ClinicalRange?) {
            writer.beginObject()
            writer.writeCanonical("min", range?.minimum)
            writer.writeCanonical("max", range?.maximum)
            writer.endObject()
        }

        private fun JsonWriter.writeRow(vararg values: Any?) {
            beginArray()
            values.forEach { value ->
                when (value) {
                    null -> nullValue()
                    is Number -> value(value)
                    is String -> value(value)
                    is Boolean -> value(value)
                    else -> error("Unsupported canonical value")
                }
            }
            endArray()
        }

        private fun JsonWriter.writeCanonical(key: String, value: Double?) {
            name(key)
            value?.takeIf(Double::isFinite)
                ?.let { value(canonicalDouble(it)) }
                ?: nullValue()
        }

        private fun JsonWriter.writeNullable(key: String, value: Number?) {
            name(key)
            value?.let { value(it) } ?: nullValue()
        }

        private fun JsonWriter.writeNullable(key: String, value: String?) {
            name(key)
            value?.let { value(it) } ?: nullValue()
        }

        private fun <T> List<T>.forEachCanonicalRow(
            checkpoint: (() -> Unit)?,
            probe: ClinicalDatasetSerializationProbe?,
            emit: (T) -> Unit
        ) {
            forEachIndexed { index, value ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                emit(value)
                probe?.onSeriesRowEmitted()
            }
            checkpoint?.invoke()
        }

        internal fun canonicalGlucose(
            points: List<ClinicalGlucosePoint>,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalGlucosePoint> {
            val selected = sortedMapOf<Long, Double>()
            points.forEachIndexed { index, point ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                if (validMmol(point.mmol)) {
                    val current = selected[point.ts]
                    if (current == null || point.mmol > current) selected[point.ts] = point.mmol
                }
            }
            checkpoint?.invoke()
            return selected.map { (ts, mmol) -> ClinicalGlucosePoint(ts, canonicalDouble(mmol)) }
        }

        internal fun canonicalForecastPoints(
            points: List<ClinicalForecastPoint>,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalForecastPoint> {
            val keyOrder = compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second }
            val selected = java.util.TreeMap<Pair<Long, Int>, ClinicalForecastPoint>(keyOrder)
            points.forEachIndexed { index, point ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                val canonical = point.copy(
                    mmol = canonicalDouble(point.mmol),
                    lower = canonicalDouble(point.lower),
                    upper = canonicalDouble(point.upper)
                )
                val key = point.ts to point.horizonMin
                val current = selected[key]
                if (current == null || forecastPointComparator.compare(canonical, current) > 0) {
                    selected[key] = canonical
                }
            }
            checkpoint?.invoke()
            return selected.values.toList()
        }

        internal fun canonicalTelemetryPoints(
            points: List<ClinicalTelemetryPoint>,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalTelemetryPoint> {
            val keyOrder = compareBy<Triple<Long, String, ClinicalTelemetryOrigin>> { it.first }
                .thenBy { it.second }
                .thenBy { it.third.wireCode }
            val selected = java.util.TreeMap<Triple<Long, String, ClinicalTelemetryOrigin>, ClinicalTelemetryPoint>(
                keyOrder
            )
            points.forEachIndexed { index, point ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                val canonical = point.copy(value = canonicalDouble(point.value))
                val key = Triple(point.ts, point.key, point.origin)
                val current = selected[key]
                if (current == null || telemetryPointComparator.compare(canonical, current) > 0) {
                    selected[key] = canonical
                }
            }
            checkpoint?.invoke()
            return selected.values.toList()
        }

        internal fun isValidCanonicalTelemetryPoint(point: ClinicalTelemetryPoint): Boolean {
            val spec = TELEMETRY_SPECS[point.key] ?: return false
            if (!point.value.isFinite() || point.value !in spec.range) return false
            if (spec.allowedValues != null && point.value !in spec.allowedValues) return false
            if (
                point.key in PhysicalActivityTelemetryPolicy.CLINICAL_ACTIVITY_METRIC_KEYS &&
                (
                    point.origin !in setOf(
                        ClinicalTelemetryOrigin.LOCAL_ACTIVITY,
                        ClinicalTelemetryOrigin.HEALTH_CONNECT
                    ) ||
                        PhysicalActivityTelemetryPolicy.normalizeQuality(point.quality) !in
                        PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES
                    )
            ) return false
            return normalizeQuality(point.quality) != "ERROR"
        }

        internal fun canonicalTherapyPoints(
            points: List<ClinicalTherapyPoint>,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalTherapyPoint> {
            val canonical = ArrayList<ClinicalTherapyPoint>(points.size)
            points.forEachIndexed { index, point ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                canonical += point.copy(
                    insulinU = point.insulinU?.let(::canonicalDouble),
                    carbsG = point.carbsG?.let(::canonicalDouble)
                ).also { copy -> copy.internalEventId = point.internalEventId }
            }
            checkpoint?.invoke()
            return canonical.distinctBy(::therapySerializationIdentity)
                .sortedWith(therapyPointComparator)
        }

        internal fun canonicalTargetPoints(
            points: List<ClinicalTargetPoint>,
            checkpoint: (() -> Unit)? = null
        ): List<ClinicalTargetPoint> {
            val canonical = ArrayList<ClinicalTargetPoint>(points.size)
            points.forEachIndexed { index, point ->
                if (index % CHECKPOINT_BATCH_SIZE == 0) checkpoint?.invoke()
                canonical += point.copy(
                    lowMmol = point.lowMmol?.let(::canonicalDouble),
                    highMmol = point.highMmol?.let(::canonicalDouble)
                ).also { copy -> copy.internalEventId = point.internalEventId }
            }
            checkpoint?.invoke()
            return canonical.distinctBy(::targetSerializationIdentity)
                .sortedWith(targetPointComparator)
        }

        private fun parseDurationMs(payload: ParsedClinicalPayload): Long? {
            val milliseconds = payload.number(
                "durationInMilliseconds", "durationMs", "targetDurationMs"
            )
            if (milliseconds != null) {
                if (milliseconds !in 0.0..MAX_TARGET_DURATION_MS.toDouble()) return null
                return milliseconds.roundToLong()
            }
            val minutes = payload.number("durationMinutes", "durationInMinutes", "duration") ?: return null
            if (minutes !in 0.0..MAX_TARGET_DURATION_MS / 60_000.0) return null
            val roundedMinutes = minutes.roundToLong()
            return runCatching { multiplyExact(roundedMinutes, 60_000L) }.getOrNull()
        }

        private fun mmolValue(
            payload: ParsedClinicalPayload,
            mmolKeys: Array<String>,
            genericKeys: Array<String>
        ): Double? {
            payload.number(*mmolKeys)?.takeIf { it in 2.2..15.0 }?.let { return it }
            val raw = payload.number(*genericKeys) ?: return null
            val mmol = if (raw > 25.0) raw / 18.0 else raw
            return mmol.takeIf { it in 2.2..15.0 }
        }

        private fun isSyntheticUam(payload: ParsedClinicalPayload): Boolean {
            val markers = payload.markers
            return payload.boolean("synthetic") == true ||
                markers["source"].equals("uam_engine", true) ||
                markers["reason"]?.contains("uam_engine", true) == true ||
                markers["synthetictype"]?.contains("uam", true) == true ||
                markers["note"]?.contains("UAM_ENGINE|", true) == true ||
                markers["notes"]?.contains("UAM_ENGINE|", true) == true
        }

        private fun insulinEvidence(
            row: ClinicalTherapyProjection,
            payload: ParsedClinicalPayload
        ): ClinicalInsulinEvidence {
            val classification = payload.markers["classification"].orEmpty()
            val method = payload.markers["method"].orEmpty()
            val source = payload.markers["source"].orEmpty()
            val id = row.rowId.lowercase(Locale.US)
            val iobDerived =
                classification.equals("INFERRED_IOB", true) ||
                    classification.equals("RECOVERED_MICROBOLUS", true) ||
                    method.equals("iob_jump", true) ||
                    method.equals("iob_micro_jump", true) ||
                    payload.boolean("inferred") == true ||
                    payload.boolean("recovered") == true ||
                    source.contains("devicestatus_recovered", true) ||
                    id.startsWith("iob-inf-") ||
                    id.startsWith("iob-mb-rec-")
            return if (iobDerived) {
                ClinicalInsulinEvidence.IOB_DERIVED
            } else {
                ClinicalInsulinEvidence.CONFIRMED
            }
        }

        private fun telemetryOrigin(source: String): ClinicalTelemetryOrigin {
            val normalized = source.trim().lowercase(Locale.US)
            return when {
                normalized == PhysicalActivityTelemetryPolicy.LOCAL_SENSOR_SOURCE ->
                    ClinicalTelemetryOrigin.LOCAL_ACTIVITY
                normalized == PhysicalActivityTelemetryPolicy.HEALTH_CONNECT_SOURCE ->
                    ClinicalTelemetryOrigin.HEALTH_CONNECT
                normalized.startsWith("aaps") ||
                    normalized.contains("androidaps") ->
                    ClinicalTelemetryOrigin.AAPS
                normalized.startsWith("copilot") ||
                    normalized.contains("automation") ->
                    ClinicalTelemetryOrigin.COPILOT_RUNTIME
                else -> ClinicalTelemetryOrigin.OTHER
            }
        }

        private fun validForecast(row: ClinicalForecastProjection): Boolean =
            row.horizonMin in 0..180 &&
                validMmol(row.mmol) && validMmol(row.lower) && validMmol(row.upper) &&
                row.lower <= row.mmol && row.mmol <= row.upper

        private fun validTelemetry(row: ClinicalTelemetryProjection): Boolean {
            val spec = TELEMETRY_SPECS[row.key] ?: return false
            val value = row.value ?: return false
            if (!value.isFinite() || value !in spec.range) return false
            if (spec.allowedValues != null && value !in spec.allowedValues) return false
            if (
                row.key in PhysicalActivityTelemetryPolicy.CLINICAL_ACTIVITY_METRIC_KEYS &&
                !PhysicalActivityTelemetryPolicy.isTrustedClinicalMetric(
                    source = row.source,
                    key = row.key,
                    quality = row.quality
                )
            ) return false
            if (normalizeQuality(row.quality) == "ERROR") return false
            return row.unit?.trim() in spec.units
        }

        private fun isValidGlucoseRow(row: ClinicalGlucoseProjection) =
            validMmol(row.mmol) &&
                !GlucoseSanitizer.isKnownInvalidQuality(row.quality) &&
                !GlucoseSanitizer.isLegacyStatusArtifact(row.source, row.mmol)

        internal fun qualityForCanonicalizer(raw: String) = when (normalizeQuality(raw)) {
            "OK", "TRUSTED" -> DataQuality.OK
            "STALE" -> DataQuality.STALE
            else -> DataQuality.SENSOR_ERROR
        }

        private fun normalizeQuality(raw: String) = when (raw.trim().uppercase(Locale.US)) {
            "OK", "GOOD", "VALID" -> "OK"
            "TRUSTED" -> "TRUSTED"
            "STALE" -> "STALE"
            "SENSOR_ERROR", "ERROR", "INVALID" -> "ERROR"
            else -> "UNKNOWN"
        }

        private fun qualityRank(raw: String) = when (normalizeQuality(raw)) {
            "TRUSTED" -> 4
            "OK" -> 3
            "STALE" -> 2
            "UNKNOWN" -> 1
            else -> 0
        }

        private fun modelRank(version: String) = when {
            version.contains("v3", true) -> 300
            version.contains("v2", true) -> 200
            version.contains("v1", true) -> 100
            else -> 0
        }

        private fun deduplicateTherapyRows(rows: List<ClinicalTherapyProjection>) =
            deduplicateByInternalId(rows) { it.rowId }

        private fun <T> deduplicateByInternalId(rows: List<T>, id: (T) -> String): List<T> {
            val indexed = rows.withIndex()
            val identified = indexed.filter { id(it.value).isNotBlank() }
                .groupBy { id(it.value) }
                .values
                .map { group -> group.minBy { it.index }.value }
            val unidentified = indexed.filter { id(it.value).isBlank() }.map { it.value }
            return identified + unidentified
        }

        private val CONTEXT_TYPES = mapOf(
            "infusion_set_change" to ClinicalTherapyContextKind.INFUSION_SET_CHANGE,
            "sensor_change" to ClinicalTherapyContextKind.SENSOR_CHANGE,
            "insulin_refill" to ClinicalTherapyContextKind.INSULIN_REFILL,
            "pump_battery_change" to ClinicalTherapyContextKind.PUMP_BATTERY_CHANGE,
            "exercise" to ClinicalTherapyContextKind.EXERCISE,
            "profile_switch" to ClinicalTherapyContextKind.PROFILE_SWITCH
        )
        private data class TelemetrySpec(
            val range: ClosedFloatingPointRange<Double>,
            val units: Set<String?>,
            val allowedValues: Set<Double>? = null
        )
        private val TELEMETRY_SPECS = mapOf(
            "iob_effective_units" to TelemetrySpec(-30.0..30.0, setOf("U")),
            "iob_units" to TelemetrySpec(-30.0..30.0, setOf("U")),
            "cob_effective_grams" to TelemetrySpec(0.0..500.0, setOf("g")),
            "cob_grams" to TelemetrySpec(0.0..500.0, setOf("g")),
            "isf_runtime_selected_value" to TelemetrySpec(0.2..18.0, setOf("mmol/L/U")),
            "cr_runtime_selected_value" to TelemetrySpec(2.0..60.0, setOf("g/U")),
            "isf_runtime_source_resolved" to TelemetrySpec(
                0.0..3.0,
                setOf(null),
                setOf(0.0, 1.0, 2.0, 3.0)
            ),
            "cr_runtime_source_resolved" to TelemetrySpec(
                0.0..3.0,
                setOf(null),
                setOf(0.0, 1.0, 2.0, 3.0)
            ),
            "uam_runtime_control_flag" to TelemetrySpec(
                0.0..1.0,
                setOf(null),
                setOf(0.0, 1.0)
            ),
            "uam_runtime_equivalent_carbs_grams" to TelemetrySpec(0.0..500.0, setOf("g")),
            "uam_runtime_confidence" to TelemetrySpec(0.0..1.0, setOf(null, "ratio")),
            "uam_calculated_carbs_grams" to TelemetrySpec(0.0..500.0, setOf("g")),
            "uam_calculated_confidence" to TelemetrySpec(0.0..1.0, setOf(null, "ratio")),
            "sensor_quality_score" to TelemetrySpec(0.0..1.0, setOf(null, "ratio")),
            "sensor_quality_blocked" to TelemetrySpec(
                0.0..1.0,
                setOf(null),
                setOf(0.0, 1.0)
            ),
            "sensor_age_hours" to TelemetrySpec(0.0..720.0, setOf("h")),
            "sensor_lag_minutes" to TelemetrySpec(0.0..60.0, setOf("min")),
            "activity_ratio" to TelemetrySpec(0.2..3.0, setOf(null, "ratio")),
            "activity_ratio_peak" to TelemetrySpec(0.2..3.0, setOf(null, "ratio")),
            "steps_count" to TelemetrySpec(0.0..150_000.0, setOf("steps")),
            "distance_km" to TelemetrySpec(0.0..250.0, setOf("km")),
            "active_minutes" to TelemetrySpec(0.0..1_440.0, setOf("min")),
            "calories_active_kcal" to TelemetrySpec(0.0..12_000.0, setOf("kcal")),
            "basal_rate_u_h" to TelemetrySpec(0.0..15.0, setOf("U/h")),
            "profile_percent" to TelemetrySpec(1.0..500.0, setOf("%"))
        )

        private val forecastComparator =
            compareBy<ClinicalForecastProjection> { modelRank(it.modelVersion) }
                .thenBy { it.modelVersion }
                .thenBy { it.rowId }
                .thenBy { canonicalDouble(it.mmol) }
                .thenBy { canonicalDouble(it.lower) }
                .thenBy { canonicalDouble(it.upper) }
        private val telemetryComparator =
            compareBy<ClinicalTelemetryProjection> { qualityRank(it.quality) }
                .thenBy { TelemetrySampleSelector.sourcePriority(it.source) }
                .thenBy { it.rowId }
                .thenBy { canonicalDouble(requireNotNull(it.value)) }
                .thenBy { it.unit.orEmpty() }
        private val therapyPointComparator =
            compareBy<ClinicalTherapyPoint> { it.ts }
                .thenBy { it.insulinU }
                .thenBy { it.carbsG }
                .thenBy { it.syntheticUam }
                .thenBy { it.insulinEvidence }
                .thenBy { it.contextKind }
                .thenBy { it.internalEventId }
        private val targetPointComparator =
            compareBy<ClinicalTargetPoint> { it.ts }
                .thenBy { it.lowMmol }
                .thenBy { it.highMmol }
                .thenBy { it.durationMs }
                .thenBy { it.endTs }
                .thenBy { it.cancelled }
                .thenBy { it.internalEventId }
        private val targetEventComparator =
            compareBy<ClinicalTargetEvent> { it.point.ts }
                .thenBy { it.point.cancelled == true }
                .thenBy { it.point.lowMmol }
                .thenBy { it.point.highMmol }
                .thenBy { it.point.durationMs }
                .thenBy { it.point.endTs }
                .thenBy { it.rowId }
        private val forecastPointComparator =
            compareBy<ClinicalForecastPoint> { it.mmol }
                .thenBy { it.lower }
                .thenBy { it.upper }
        private val telemetryPointComparator =
            compareBy<ClinicalTelemetryPoint> { qualityRank(it.quality) }
                .thenBy { it.value }
                .thenBy { it.quality }

        private fun minuteOffset(ts: Long, baseTs: Long) =
            Math.floorDiv(subtractExact(ts, baseTs), 60_000L)

        private fun floorToBucket(ts: Long) =
            subtractExact(ts, Math.floorMod(ts, ClinicalSummaryCalculator.BUCKET_MS))

        private fun validMmol(value: Double) = value.isFinite() && value in 1.0..40.0
        private fun canonicalDouble(value: Double) =
            BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toDouble()

        private fun therapySerializationIdentity(point: ClinicalTherapyPoint): Any =
            point.internalEventId?.let { "id:$it" }
                ?: listOf(
                    point.ts,
                    point.insulinU,
                    point.carbsG,
                    point.syntheticUam,
                    point.insulinEvidence,
                    point.contextKind
                )

        private fun targetSerializationIdentity(point: ClinicalTargetPoint): Any =
            point.internalEventId?.let { "id:$it" }
                ?: listOf(
                    point.ts, point.lowMmol, point.highMmol,
                    point.durationMs, point.endTs, point.cancelled
                )

        private fun addExact(left: Long, right: Long) = Math.addExact(left, right)
        private fun subtractExact(left: Long, right: Long) = Math.subtractExact(left, right)
        private fun multiplyExact(left: Long, right: Long) = Math.multiplyExact(left, right)

        private suspend fun resolveRawGlucose(
            points: List<GlucosePoint>,
            @Suppress("UNUSED_PARAMETER") nowTs: Long
        ): List<ResolvedGlucosePoint> = points.map {
            ResolvedGlucosePoint(
                ts = it.ts,
                rawMmol = it.valueMmol,
                calibratedMmol = it.valueMmol,
                gain = 1.0,
                offsetMmolApplied = 0.0,
                calibrationApplied = false,
                source = it.source,
                quality = it.quality
            )
        }
    }
}
