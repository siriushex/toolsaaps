package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.ClinicalForecastProjection
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.data.local.dao.ClinicalTelemetryProjection
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import io.aaps.copilot.data.local.dao.PhysicalActivityBucketProjection
import io.aaps.copilot.data.local.dao.PhysicalActivityMetricBucketProjection
import io.aaps.copilot.data.local.entity.BloodGlucoseCheckEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.activity.PhysicalActivityBucket
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal enum class AlertAiDatasetSourceName {
    GLUCOSE,
    THERAPY,
    FORECAST,
    TELEMETRY,
    ACTIVITY_DETAIL_BUCKETS,
    CALIBRATION_CAUSAL_GLUCOSE,
    CALIBRATION_EVIDENCE,
    CALIBRATION_SESSION_CHECKS,
    CALIBRATION_SESSION_BOUNDARY,
    CALIBRATION_SESSION_GLUCOSE,
    CALIBRATION_ACTIVE_MODEL,
    CALIBRATION_AUTHORITY_TOKEN,
    SENSITIVITY_MARKERS,
    SENSITIVITY_MARKER_DETAIL,
    SENSITIVITY_RUNTIME_SNAPSHOT,
    SENSITIVITY_FORECASTS,
    SENSITIVITY_CALIBRATION_MARKERS,
    TIMELINE_THERAPY,
    PLANNED_ACTIVITY,
    ACTIVITY_TIMELINE_BUCKETS,
    DELIVERY_DIAGNOSTICS,
    CALIBRATION_EVENTS,
    CONTEXT_EVENTS
}

internal enum class AlertAiDatasetDerivedSourceName {
    CALIBRATED_GLUCOSE_COPY,
    ACTIVITY_DETAIL_PROJECTION,
    TIMELINE_THERAPY_PROJECTION,
    PLANNED_ACTIVITY_OCCURRENCE,
    ACTIVITY_TIMELINE_BUCKET_COPY,
    ACTUAL_ACTIVITY_EVENT,
    CALIBRATION_CHECK_COPY,
    CALIBRATION_EVENT,
    DELIVERY_TELEMETRY_COPY,
    DELIVERY_DIAGNOSTIC_EVENT,
    TIMELINE_NORMALIZED_INPUT,
    TIMELINE_AGGREGATE_OUTPUT,
    SENSITIVITY_FORECAST_PROJECTION,
    CLINICAL_GLUCOSE_14D,
    CLINICAL_DETAIL_GLUCOSE,
    CLINICAL_DETAIL_CALIBRATED_GLUCOSE,
    CLINICAL_THERAPY,
    CLINICAL_TARGET,
    CLINICAL_FORECAST,
    CLINICAL_TELEMETRY,
    CLINICAL_EVENT_SUMMARY,
    ALERT_CONTEXT_DETAIL_WINDOW,
    ALERT_CONTEXT_DATASET,
    ALERT_CONTEXT_LOCAL_CAUSE,
    ALERT_CONTEXT_LIST,
    ALERT_CONTEXT_DAILY_AGGREGATE,
    ALERT_CONTEXT_CANONICAL_EVENT,
    ALERT_CANONICAL_CONTEXT
}

internal data class AlertAiDatasetReadObservation(
    val source: AlertAiDatasetSourceName,
    val queriedRows: Int,
    val limit: Int,
    val queryLimit: Int = limit
)

internal fun interface AlertAiDatasetReadProbe {
    fun onRead(observation: AlertAiDatasetReadObservation)

    companion object {
        val NONE = AlertAiDatasetReadProbe { }
    }
}

internal data class AlertAiDatasetDerivedObservation(
    val source: AlertAiDatasetDerivedSourceName,
    val reservedRows: Int,
    val totalAfterReservation: Long,
    val limit: Int
)

internal fun interface AlertAiDatasetDerivedProbe {
    fun onReserve(observation: AlertAiDatasetDerivedObservation)

    companion object {
        val NONE = AlertAiDatasetDerivedProbe { }
    }
}

/**
 * Counts logical retained work separately from Room reads and serialized bytes. Source-to-domain
 * rows, derived copies, output DTOs and each new list container reserve before allocation. A list
 * pipeline may reuse already-charged row references without charging every reference again;
 * expansions and distinct retained outputs always charge independently.
 */
class AlertAiRetainedDerivedBudget internal constructor(
    private val maxRows: Int = MAX_RETAINED_DERIVED_ROWS,
    private val probe: AlertAiDatasetDerivedProbe = AlertAiDatasetDerivedProbe.NONE
) {
    private var totalRows = 0L

    init {
        require(maxRows >= 0)
    }

    internal fun reserve(source: AlertAiDatasetDerivedSourceName, rows: Int) {
        if (rows < 0) throw AlertAiContextException.LimitExceeded()
        val next = try {
            Math.addExact(totalRows, rows.toLong())
        } catch (_: ArithmeticException) {
            throw AlertAiContextException.LimitExceeded()
        }
        probe.onReserve(AlertAiDatasetDerivedObservation(source, rows, next, maxRows))
        if (next > maxRows.toLong()) throw AlertAiContextException.LimitExceeded()
        totalRows = next
    }

    companion object {
        const val MAX_RETAINED_DERIVED_ROWS = 12_000
    }
}

internal data class AlertAiDatasetReadLimits(
    val glucoseRows: Int = 24_000,
    val therapyRows: Int = 16_000,
    val forecastRows: Int = 8_000,
    val telemetryRows: Int = 16_000,
    val activityDetailBucketRows: Int = 4_000,
    val sensitivityMarkerRows: Int = 64,
    val sensitivityForecastRows: Int = 64,
    val timelineTherapyRows: Int = 8_000,
    val plannedActivityRows: Int = 512,
    val activityTimelineBucketRows: Int = 9_000,
    val deliveryDiagnosticRows: Int = 8_000,
    val calibrationEventRows: Int = 2_000,
    val contextEventRows: Int = 4_000,
    val totalDaoRows: Int = 80_000
) {
    init {
        listOf(
            glucoseRows,
            therapyRows,
            forecastRows,
            telemetryRows,
            activityDetailBucketRows,
            sensitivityMarkerRows,
            sensitivityForecastRows,
            timelineTherapyRows,
            plannedActivityRows,
            activityTimelineBucketRows,
            deliveryDiagnosticRows,
            calibrationEventRows,
            contextEventRows,
            totalDaoRows
        ).forEach { require(it in 1 until Int.MAX_VALUE) }
    }

    companion object {
        internal fun testing(
            defaultPerSourceRows: Int,
            glucoseRows: Int = defaultPerSourceRows,
            plannedActivityRows: Int = defaultPerSourceRows,
            totalDaoRows: Int
        ) = AlertAiDatasetReadLimits(
            glucoseRows = glucoseRows,
            therapyRows = defaultPerSourceRows,
            forecastRows = defaultPerSourceRows,
            telemetryRows = defaultPerSourceRows,
            activityDetailBucketRows = defaultPerSourceRows,
            sensitivityMarkerRows = defaultPerSourceRows,
            sensitivityForecastRows = defaultPerSourceRows,
            timelineTherapyRows = defaultPerSourceRows,
            plannedActivityRows = plannedActivityRows,
            activityTimelineBucketRows = defaultPerSourceRows,
            deliveryDiagnosticRows = defaultPerSourceRows,
            calibrationEventRows = defaultPerSourceRows,
            contextEventRows = defaultPerSourceRows,
            totalDaoRows = totalDaoRows
        )
    }
}

internal data class AlertAiDatasetSourceSnapshot(
    val report: ClinicalReportSnapshot,
    val calibratedGlucose: List<ClinicalGlucoseProjection>,
    val timelineEvents: List<CompensationEvent>,
    val derivedBudget: AlertAiRetainedDerivedBudget? = null
)

internal fun interface AlertAiDatasetSource {
    suspend fun load(nowTs: Long): AlertAiDatasetSourceSnapshot
}

internal class RoomAlertAiDatasetSource(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val glucoseCalibrationRepository: GlucoseCalibrationRepository,
    private val sensitivitySettingsIdentity: (suspend () -> SensitivityRuntimeSettingsIdentity)?,
    private val limits: AlertAiDatasetReadLimits = AlertAiDatasetReadLimits(),
    private val probe: AlertAiDatasetReadProbe = AlertAiDatasetReadProbe.NONE,
    private val derivedRowLimit: Int = AlertAiRetainedDerivedBudget.MAX_RETAINED_DERIVED_ROWS,
    private val derivedProbe: AlertAiDatasetDerivedProbe = AlertAiDatasetDerivedProbe.NONE
) : AlertAiDatasetSource {
    private val timelineRepository = EventTimelineRepository(gson)
    private val acceptedTupleLoader = AcceptedSensitivityTupleRoomLoader(db)

    override suspend fun load(nowTs: Long): AlertAiDatasetSourceSnapshot {
        val from14d = subtractExactBounded(nowTs, FOURTEEN_DAYS_MS)
        val from24h = subtractExactBounded(nowTs, DAY_MS)
        val targetLookback = subtractExactBounded(
            from14d,
            ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS
        )
        val identityBefore = sensitivitySettingsIdentity?.invoke()
        val readBudget = AlertAiDaoReadBudget(limits.totalDaoRows, probe)
        val derivedBudget = AlertAiRetainedDerivedBudget(derivedRowLimit, derivedProbe)
        val rows = db.withTransaction {
            val glucose = readBudget.read(
                AlertAiDatasetSourceName.GLUCOSE,
                limits.glucoseRows
            ) {
                db.glucoseDao().betweenForAlertAi(from14d, nowTs, it)
            }
            val therapy = readBudget.read(
                AlertAiDatasetSourceName.THERAPY,
                limits.therapyRows
            ) {
                db.therapyDao().betweenForAlertAi(
                    targetLookback,
                    nowTs,
                    ClinicalReportDatasetBuilder.THERAPY_TYPES,
                    it
                )
            }
            val forecasts = readBudget.read(
                AlertAiDatasetSourceName.FORECAST,
                limits.forecastRows
            ) {
                db.forecastDao().betweenForAlertAi(from24h, nowTs, it)
            }
            val telemetryKeys = ClinicalReportDatasetBuilder.TELEMETRY_KEYS
            val regularTelemetry = readBudget.read(
                AlertAiDatasetSourceName.TELEMETRY,
                limits.telemetryRows
            ) { queryLimit ->
                db.telemetryDao().betweenForAlertAi(
                    from24h,
                    nowTs,
                    telemetryKeys.filterNot(
                        PhysicalActivityTelemetryPolicy.CLINICAL_ACTIVITY_METRIC_KEYS::contains
                    ),
                    queryLimit
                )
            }
            val detailActivityBuckets = readActivityMetricBuckets(
                accumulator = readBudget,
                sourceName = AlertAiDatasetSourceName.ACTIVITY_DETAIL_BUCKETS,
                fromTs = from24h,
                throughTs = nowTs,
                keys = telemetryKeys,
                limit = limits.activityDetailBucketRows
            )
            val calibrationAuthority = loadCurrentGlucoseCalibrationAuthorityInTransaction(
                db = db,
                atTs = nowTs,
                failOnEvidenceOverflow = true,
                readBudget = readBudget
            )
            val acceptedRuntime = identityBefore?.let { identity ->
                acceptedTupleLoader.loadBoundedInTransaction(
                    currentSettings = identity,
                    atTs = nowTs,
                    currentCalibrationAuthority = calibrationAuthority,
                    markerLimit = limits.sensitivityMarkerRows,
                    forecastLimit = limits.sensitivityForecastRows,
                    readBudget = readBudget
                )
            }
            val timelineTherapy = readBudget.read(
                AlertAiDatasetSourceName.TIMELINE_THERAPY,
                limits.timelineTherapyRows
            ) {
                db.therapyDao().betweenForAlertAiTimeline(
                    subtractExactBounded(from14d, DAY_MS),
                    nowTs,
                    it
                )
            }
            val plannedActivities = readPlannedActivities(readBudget, from14d, nowTs)
            val activityTimelineBuckets = readActivityTimelineBuckets(
                readBudget,
                from14d,
                nowTs
            )
            val deliveryDiagnostics = readBudget.read(
                AlertAiDatasetSourceName.DELIVERY_DIAGNOSTICS,
                limits.deliveryDiagnosticRows
            ) {
                db.telemetryDao().betweenForAlertAi(
                    deliveryDiagnosticLookbackFrom(from14d),
                    nowTs,
                    DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS,
                    it
                )
            }
            val calibrationEvents = readBudget.read(
                AlertAiDatasetSourceName.CALIBRATION_EVENTS,
                limits.calibrationEventRows
            ) {
                db.bloodGlucoseCheckDao().betweenForAlertAi(from14d, nowTs, it)
            }
            val contextEvents = readBudget.read(
                AlertAiDatasetSourceName.CONTEXT_EVENTS,
                limits.contextEventRows
            ) {
                db.alertAiDatasetDao().contextEvents(from14d, nowTs, it)
            }
            val projectedActivity = ArrayList<ClinicalTelemetryProjection>(
                detailActivityBuckets.size.coerceAtMost(limits.activityDetailBucketRows)
            )
            detailActivityBuckets.forEach { bucket ->
                projectedActivity += bucket.toClinicalTelemetryProjections { count ->
                    derivedBudget.reserve(
                        AlertAiDatasetDerivedSourceName.ACTIVITY_DETAIL_PROJECTION,
                        count
                    )
                }
            }
            AlertAiRoomRows(
                glucose = glucose,
                therapy = therapy,
                forecasts = forecasts,
                telemetry = regularTelemetry + projectedActivity,
                acceptedRuntime = acceptedRuntime,
                calibrationAuthority = calibrationAuthority,
                timelineTherapy = timelineTherapy,
                plannedActivities = plannedActivities,
                activityTimelineBuckets = activityTimelineBuckets,
                deliveryDiagnostics = deliveryDiagnostics,
                calibrationEvents = calibrationEvents,
                contextEvents = contextEvents
            )
        }
        val identityAfter = sensitivitySettingsIdentity?.invoke()
        val acceptedRuntime = rows.acceptedRuntime.takeIf {
            identityBefore != null && identityBefore == identityAfter
        }
        derivedBudget.reserve(
            AlertAiDatasetDerivedSourceName.CALIBRATED_GLUCOSE_COPY,
            rows.glucose.size
        )
        val calibratedGlucose = glucoseCalibrationRepository.resolveAlertAiGlucosePoints(
            rawGlucose = rows.glucose.map { row ->
                GlucosePoint(
                    ts = row.ts,
                    valueMmol = row.mmol,
                    source = row.source,
                    quality = ClinicalReportDatasetBuilder.qualityForCanonicalizer(row.quality)
                )
            },
            nowTs = nowTs,
            authority = rows.calibrationAuthority
        ).map { point ->
            ClinicalGlucoseProjection(
                ts = point.ts,
                mmol = point.calibratedMmol,
                source = point.source,
                quality = point.quality.name
            )
        }
        derivedBudget.reserve(
            AlertAiDatasetDerivedSourceName.TIMELINE_THERAPY_PROJECTION,
            rows.timelineTherapy.size
        )
        val timelineTherapy = TherapySanitizer.toDomainEvents(rows.timelineTherapy, gson)
        derivedBudget.reserve(
            AlertAiDatasetDerivedSourceName.ACTIVITY_TIMELINE_BUCKET_COPY,
            rows.activityTimelineBuckets.size
        )
        val activityTimelineBuckets = rows.activityTimelineBuckets.map(
            PhysicalActivityBucketProjection::toDomain
        )
        derivedBudget.reserve(
            AlertAiDatasetDerivedSourceName.CALIBRATION_CHECK_COPY,
            rows.calibrationEvents.size
        )
        val calibrationChecks = rows.calibrationEvents.map { it.toDomain() }
        derivedBudget.reserve(
            AlertAiDatasetDerivedSourceName.DELIVERY_TELEMETRY_COPY,
            rows.deliveryDiagnostics.size
        )
        val deliveryTelemetry = rows.deliveryDiagnostics.map { row ->
            DeliveryTrustTelemetryValue(row.ts, row.source, row.key, row.value)
        }
        val timeline = timelineRepository.aggregate(
            EventTimelineSources(
                therapyEvents = timelineTherapy,
                contextTags = rows.contextEvents,
                plannedActivity = timelineRepository.plannedActivityEvents(
                    rows = rows.plannedActivities,
                    fromTs = from14d,
                    throughTs = nowTs,
                    statusAtTs = nowTs,
                    reserveOccurrence = { count ->
                        derivedBudget.reserve(
                            AlertAiDatasetDerivedSourceName.PLANNED_ACTIVITY_OCCURRENCE,
                            count
                        )
                    }
                ),
                actualActivity = timelineRepository.actualActivityEventsFromBuckets(
                    activityTimelineBuckets,
                    reserveEvent = { count ->
                        derivedBudget.reserve(
                            AlertAiDatasetDerivedSourceName.ACTUAL_ACTIVITY_EVENT,
                            count
                        )
                    }
                ),
                calibrationEvents = timelineRepository.calibrationEvents(
                    calibrationChecks,
                    reserveEvent = { count ->
                        derivedBudget.reserve(
                            AlertAiDatasetDerivedSourceName.CALIBRATION_EVENT,
                            count
                        )
                    }
                ),
                deliveryDiagnostics = deliveryDiagnosticEventsForWindow(
                    rows = deliveryTelemetry,
                    fromTs = from14d,
                    throughTs = nowTs,
                    reserveEvent = { count ->
                        derivedBudget.reserve(
                            AlertAiDatasetDerivedSourceName.DELIVERY_DIAGNOSTIC_EVENT,
                            count
                        )
                    }
                )
            ),
            nowTs,
            reserveInput = { count ->
                derivedBudget.reserve(
                    AlertAiDatasetDerivedSourceName.TIMELINE_NORMALIZED_INPUT,
                    count
                )
            },
            reserveOutput = { count ->
                derivedBudget.reserve(
                    AlertAiDatasetDerivedSourceName.TIMELINE_AGGREGATE_OUTPUT,
                    count
                )
            }
        )
        val runtimeForecasts = acceptedRuntime?.forecasts.orEmpty()
        derivedBudget.reserve(
            AlertAiDatasetDerivedSourceName.SENSITIVITY_FORECAST_PROJECTION,
            runtimeForecasts.size
        )
        return AlertAiDatasetSourceSnapshot(
            report = ClinicalReportSnapshot(
                glucose = rows.glucose,
                therapy = rows.therapy,
                forecasts = rows.forecasts,
                telemetry = rows.telemetry,
                summaryTelemetry = emptyList(),
                sensitivityRuntime = acceptedRuntime?.snapshotEntity,
                sensitivityRuntimeForecasts = runtimeForecasts.map { forecast ->
                    ClinicalForecastProjection(
                        ts = forecast.timestamp,
                        horizonMin = forecast.horizonMinutes,
                        mmol = forecast.valueMmol,
                        lower = forecast.ciLow,
                        upper = forecast.ciHigh,
                        rowId = forecast.id,
                        modelVersion = forecast.modelVersion
                    )
                },
                sensitivityRuntimeAuthoritative = identityBefore != null
            ),
            calibratedGlucose = calibratedGlucose,
            timelineEvents = timeline,
            derivedBudget = derivedBudget
        )
    }

    private suspend fun readActivityMetricBuckets(
        accumulator: AlertAiDaoReadBudget,
        sourceName: AlertAiDatasetSourceName,
        fromTs: Long,
        throughTs: Long,
        keys: List<String>,
        limit: Int
    ): List<PhysicalActivityMetricBucketProjection> {
        val requested = keys.toSet()
        val activityKeys = PhysicalActivityTelemetryPolicy.PERSISTED_ACTIVITY_METRIC_KEYS.filter { key ->
            key in requested ||
                key == PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY &&
                PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_PEAK_KEY in requested
        }
        if (activityKeys.isEmpty()) {
            accumulator.accept(sourceName, 0, limit)
            return emptyList()
        }
        val window = PhysicalActivityTelemetryPolicy.reportBucketWindow(fromTs, throughTs)
        return accumulator.read(sourceName, limit) { queryLimit ->
            db.telemetryDao().physicalActivityMetric5MinuteBucketsForAlertAi(
                fromTs = window.fromTs,
                toTsExclusive = window.toTsExclusive,
                firstBucketTs = window.firstBucketTs,
                keys = activityKeys,
                sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
                qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
                bucketMs = PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS,
                limit = queryLimit
            )
        }
    }

    private suspend fun readActivityTimelineBuckets(
        accumulator: AlertAiDaoReadBudget,
        fromTs: Long,
        throughTs: Long
    ): List<PhysicalActivityBucketProjection> {
        val window = PhysicalActivityTelemetryPolicy.reportBucketWindow(fromTs, throughTs)
        return accumulator.read(
            AlertAiDatasetSourceName.ACTIVITY_TIMELINE_BUCKETS,
            limits.activityTimelineBucketRows
        ) { queryLimit ->
            db.telemetryDao().physicalActivity5MinuteBucketsForAlertAi(
                fromTs = window.fromTs,
                toTsExclusive = window.toTsExclusive,
                key = PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY,
                sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
                qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
                bucketMs = PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS,
                limit = queryLimit
            )
        }
    }

    private suspend fun readPlannedActivities(
        accumulator: AlertAiDaoReadBudget,
        fromTs: Long,
        throughTs: Long
    ): List<PlannedActivityEventEntity> {
        val earliest = subtractExactBounded(
            subtractExactBounded(fromTs, DAY_MS * 2L),
            ClinicalPlannedActivityPeriodPolicy.MAX_SUPPORTED_DURATION_MS
        )
        val latest = addExactBounded(throughTs, DAY_MS * 2L)
        val earliestLocal = Instant.ofEpochMilli(earliest).atOffset(ZoneOffset.UTC).toLocalDateTime()
        val latestLocal = Instant.ofEpochMilli(latest).atOffset(ZoneOffset.UTC).toLocalDateTime()
        return accumulator.read(
            AlertAiDatasetSourceName.PLANNED_ACTIVITY,
            limits.plannedActivityRows
        ) { queryLimit ->
            db.alertAiDatasetDao().plannedActivityCandidates(
                earliestCandidateLocalIso = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(earliestLocal),
                latestCandidateLocalIso = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(latestLocal),
                earliestCandidateEpochDay = earliestLocal.toLocalDate().toEpochDay(),
                limit = queryLimit
            )
        }
    }
}

private data class AlertAiRoomRows(
    val glucose: List<ClinicalGlucoseProjection>,
    val therapy: List<ClinicalTherapyProjection>,
    val forecasts: List<ClinicalForecastProjection>,
    val telemetry: List<ClinicalTelemetryProjection>,
    val acceptedRuntime: AcceptedSensitivityRoomTuple?,
    val calibrationAuthority: CurrentGlucoseCalibrationAuthority,
    val timelineTherapy: List<TherapyEventEntity>,
    val plannedActivities: List<PlannedActivityEventEntity>,
    val activityTimelineBuckets: List<PhysicalActivityBucketProjection>,
    val deliveryDiagnostics: List<ClinicalTelemetryProjection>,
    val calibrationEvents: List<BloodGlucoseCheckEntity>,
    val contextEvents: List<PhysioContextTagEntity>
)

/** Counts every Room row returned to the alert path, including bounded lookup rows and scalars. */
internal class AlertAiDaoReadBudget(
    private val totalLimit: Int,
    private val probe: AlertAiDatasetReadProbe
) {
    private var totalRows = 0L

    suspend fun <T> read(
        source: AlertAiDatasetSourceName,
        limit: Int,
        query: suspend (limitPlusOne: Int) -> List<T>
    ): List<T> {
        val queryLimit = availableLimit(limit)
        val rows = query(limitPlusOneBounded(queryLimit))
        acceptWithQueryLimit(source, rows.size, limit, queryLimit)
        return rows
    }

    suspend fun <T> readFixedLookup(
        source: AlertAiDatasetSourceName,
        maximumRows: Int,
        query: suspend (boundedLimit: Int) -> List<T>
    ): List<T> {
        val queryLimit = availableLimit(maximumRows)
        val boundedQueryLimit = if (queryLimit < maximumRows) {
            limitPlusOneBounded(queryLimit)
        } else {
            maximumRows
        }
        val rows = query(boundedQueryLimit)
        acceptWithQueryLimit(source, rows.size, maximumRows, queryLimit)
        return rows
    }

    suspend fun <T> readOptional(
        source: AlertAiDatasetSourceName,
        query: suspend () -> T?
    ): T? {
        val row = query()
        acceptWithQueryLimit(source, if (row == null) 0 else 1, 1, availableLimit(1))
        return row
    }

    fun accept(source: AlertAiDatasetSourceName, queriedRows: Int, limit: Int) {
        acceptWithQueryLimit(source, queriedRows, limit, availableLimit(limit))
    }

    private fun acceptWithQueryLimit(
        source: AlertAiDatasetSourceName,
        queriedRows: Int,
        limit: Int,
        queryLimit: Int
    ) {
        probe.onRead(AlertAiDatasetReadObservation(source, queriedRows, limit, queryLimit))
        if (queriedRows > queryLimit) throw AlertAiContextException.LimitExceeded()
        totalRows = try {
            Math.addExact(totalRows, queriedRows.toLong())
        } catch (_: ArithmeticException) {
            throw AlertAiContextException.LimitExceeded()
        }
        if (totalRows > totalLimit.toLong()) throw AlertAiContextException.LimitExceeded()
    }

    private fun availableLimit(sourceLimit: Int): Int {
        val remaining = totalLimit.toLong() - totalRows
        if (remaining < 0L) throw AlertAiContextException.LimitExceeded()
        return minOf(sourceLimit.toLong(), remaining).toInt()
    }
}

internal fun PhysicalActivityMetricBucketProjection.toClinicalTelemetryProjections(
    reserve: (Int) -> Unit = {}
): List<ClinicalTelemetryProjection> {
    if (key == PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY) {
        reserve(2)
        return listOf(
            clinicalProjection("mean", lastTs, key, meanValue, "ratio"),
            clinicalProjection(
                "peak",
                lastTs,
                PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_PEAK_KEY,
                maxValue,
                "ratio"
            )
        )
    }
    val unit = when (key) {
        PhysicalActivityTelemetryPolicy.STEPS_COUNT_KEY -> "steps"
        PhysicalActivityTelemetryPolicy.DISTANCE_KM_KEY -> "km"
        PhysicalActivityTelemetryPolicy.ACTIVE_MINUTES_KEY -> "min"
        PhysicalActivityTelemetryPolicy.ACTIVE_CALORIES_KCAL_KEY -> "kcal"
        else -> return emptyList()
    }
    val projectionCount = if (firstTs == lastTs && firstValue == maxValue) 1 else 2
    reserve(projectionCount)
    val first = clinicalProjection("first", firstTs, key, firstValue, unit)
    if (projectionCount == 1) return listOf(first)
    return listOf(first, clinicalProjection("max", lastTs, key, maxValue, unit))
}

private fun PhysicalActivityMetricBucketProjection.clinicalProjection(
    suffix: String,
    ts: Long,
    projectionKey: String,
    value: Double,
    unit: String
) = ClinicalTelemetryProjection(
    ts = ts,
    key = projectionKey,
    value = value,
    quality = qualityEvidence,
    rowId = "physical:$source:$projectionKey:$bucketTs:$suffix",
    source = source,
    unit = unit
)

private fun PhysicalActivityBucketProjection.toDomain() = PhysicalActivityBucket(
    bucketTs = bucketTs,
    firstTs = firstTs,
    lastTs = lastTs,
    peakRatio = peakRatio,
    meanRatio = meanRatio,
    sampleCount = sampleCount,
    source = source,
    qualityEvidence = qualityEvidence
)

private fun limitPlusOneBounded(limit: Int): Int = try {
    Math.addExact(limit, 1)
} catch (_: ArithmeticException) {
    throw AlertAiContextException.LimitExceeded()
}

private fun subtractExactBounded(value: Long, amount: Long): Long = try {
    Math.subtractExact(value, amount)
} catch (_: ArithmeticException) {
    throw AlertAiContextException.LimitExceeded()
}

private fun addExactBounded(value: Long, amount: Long): Long = try {
    Math.addExact(value, amount)
} catch (_: ArithmeticException) {
    throw AlertAiContextException.LimitExceeded()
}

private const val DAY_MS = 24L * 60L * 60L * 1_000L
private const val FOURTEEN_DAYS_MS = 14L * DAY_MS
