package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.TelemetrySampleLite
import io.aaps.copilot.data.local.entity.CircadianTargetAdjustmentEntity
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.BaseTargetSchedulePolicy
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.target.CircadianPreviousAdjustment
import io.aaps.copilot.domain.target.CircadianPriorStepComparison
import io.aaps.copilot.domain.target.CircadianStepEvidence
import io.aaps.copilot.domain.target.CircadianTargetAdjustment
import io.aaps.copilot.domain.target.CircadianTargetCohortBuilder
import io.aaps.copilot.domain.target.CircadianTargetCohorts
import io.aaps.copilot.domain.target.CircadianTargetEvaluationInput
import io.aaps.copilot.domain.target.CircadianTargetEvaluationResult
import io.aaps.copilot.domain.target.CircadianTargetEvaluator
import io.aaps.copilot.domain.target.CircadianTargetSlot
import io.aaps.copilot.domain.target.CircadianTelemetrySignal
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.EffectiveBaseTarget
import io.aaps.copilot.domain.target.EffectiveBaseTargetResolver
import io.aaps.copilot.domain.target.EffectiveTargetAdjustment
import io.aaps.copilot.domain.target.EffectiveTargetRuntimeGates
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetManagerMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

internal interface CircadianTargetRepositorySource {
    suspend fun glucoseBetween(since: Long, through: Long): List<GlucoseSampleEntity>

    suspend fun therapyBetween(since: Long, through: Long): List<TherapyEventEntity>

    suspend fun forecastsBetween(since: Long, through: Long): List<ForecastEntity>

    suspend fun telemetryBetweenByKeysPage(
        since: Long,
        through: Long,
        keys: List<String>,
        afterTimestamp: Long,
        afterId: String,
        limit: Int
    ): List<TelemetrySampleLite>

    suspend fun publishRun(
        run: CircadianTargetRunEntity,
        adjustments: List<CircadianTargetAdjustmentEntity>
    )

    suspend fun insertFailedRun(run: CircadianTargetRunEntity)

    suspend fun latestCompletedRun(scheduleRevision: Long): CircadianTargetRunEntity?

    suspend fun runForLocalDate(
        scheduleRevision: Long,
        localRunDate: String
    ): CircadianTargetRunEntity?

    suspend fun adjustmentsForRun(runId: String): List<CircadianTargetAdjustmentEntity>

    suspend fun latestCompletedLocalRunDate(scheduleRevision: Long): String?

    suspend fun deleteRunsCompletedBefore(cutoff: Long)
}

internal fun interface CircadianGlucoseHistoryResolver {
    suspend fun resolve(rawGlucose: List<GlucoseSampleEntity>, now: Long): List<GlucosePoint>
}

internal fun interface CircadianCohortAssembler {
    fun build(
        zoneId: ZoneId,
        canonicalGlucose: List<GlucosePoint>,
        telemetry: List<CircadianTelemetrySignal>,
        therapyEvents: List<TherapyEvent>
    ): CircadianTargetCohorts
}

internal fun interface CircadianEvaluationEngine {
    fun evaluate(input: CircadianTargetEvaluationInput): CircadianTargetEvaluationResult
}

class CircadianTargetRepository internal constructor(
    private val source: CircadianTargetRepositorySource,
    private val glucoseResolver: CircadianGlucoseHistoryResolver,
    private val cohortAssembler: CircadianCohortAssembler,
    private val evaluationEngine: CircadianEvaluationEngine,
    private val gson: Gson,
    private val runIdFactory: () -> String
) {

    private val effectiveTargetResolver = EffectiveBaseTargetResolver()
    @Volatile
    private var effectiveAdjustmentCache: EffectiveAdjustmentCache? = null

    constructor(
        db: CopilotDatabase,
        gson: Gson,
        glucoseCalibrationRepository: GlucoseCalibrationRepository
    ) : this(
        source = RoomCircadianTargetRepositorySource(db),
        glucoseResolver = CircadianGlucoseHistoryResolver { rawGlucose, now ->
            glucoseCalibrationRepository.resolveDomainGlucoseHistory(rawGlucose, now)
        },
        cohortAssembler = CircadianCohortAssembler { zoneId, glucose, telemetry, therapy ->
            CircadianTargetCohortBuilder(zoneId).build(glucose, telemetry, therapy)
        },
        evaluationEngine = CircadianEvaluationEngine(CircadianTargetEvaluator()::evaluate),
        gson = gson,
        runIdFactory = { "circadian-target-${UUID.randomUUID()}" }
    )

    constructor(
        db: CopilotDatabase,
        glucoseCalibrationRepository: GlucoseCalibrationRepository
    ) : this(db, Gson(), glucoseCalibrationRepository)

    suspend fun evaluateAndPublish(
        now: Long,
        schedule: BaseTargetSchedule,
        zoneId: ZoneId
    ): CircadianTargetRunEntity {
        val runId = normalizedRunId(runIdFactory())
        val localRunDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val lookbackStart = saturatingSubtract(now, LOOKBACK_MS)
        source.runForLocalDate(schedule.revision, localRunDate.toString())
            ?.takeUnless { it.status == FAILED_STATUS }
            ?.let { return it }

        if (!schedule.autoEnabled) {
            val offRun = runEntity(
                runId = runId,
                schedule = schedule,
                localRunDate = localRunDate,
                now = now,
                lookbackStart = lookbackStart,
                status = CircadianAutoState.OFF.name,
                validDays = 0,
                trustedShare = 0.0,
                lowRiskPassed = true,
                reasons = listOf("auto_disabled")
            )
            return publishOrRecordFailure(offRun, emptyList(), now)
        }

        return try {
            val rawGlucose = source.glucoseBetween(lookbackStart, now)
            val rawTherapy = source.therapyBetween(lookbackStart, now)
            val forecasts = source.forecastsBetween(lookbackStart, now)
            validateSourceWindow(rawGlucose, rawTherapy, lookbackStart, now)
            validateForecastWindow(forecasts, lookbackStart, now)

            val telemetry = loadTelemetry(lookbackStart, now)
            val calibratedGlucose = glucoseResolver.resolve(rawGlucose, now)
            val therapy = rawTherapy.map { it.toSafeDomain(gson) }
            val cohorts = cohortAssembler.build(
                zoneId = zoneId,
                canonicalGlucose = calibratedGlucose,
                telemetry = telemetry,
                therapyEvents = therapy
            )
            val previous = loadPreviousAdjustments(schedule.revision)
            val lowerTailReplayErrors = calculateCircadianLowerTailReplayErrors(
                forecasts = forecasts,
                canonicalGlucose = calibratedGlucose,
                zoneId = zoneId
            )
            val currentContext = resolveCurrentContext(
                now = now,
                calibratedGlucose = calibratedGlucose,
                telemetry = telemetry,
                cohorts = cohorts
            )
            val result = evaluationEngine.evaluate(
                CircadianTargetEvaluationInput(
                    evaluatedAt = now,
                    evaluationDate = localRunDate,
                    cohorts = cohorts,
                    currentSensorTrust = currentContext.sensorTrust,
                    currentSensorAgeHours = currentContext.sensorAgeHours,
                    deliveryTrust = currentContext.deliveryTrust,
                    previousAdjustments = previous,
                    lowerTailReplayErrors = lowerTailReplayErrors
                )
            )
            validateEvaluationResult(result)

            val run = runEntity(
                runId = runId,
                schedule = schedule,
                localRunDate = localRunDate,
                now = now,
                lookbackStart = lookbackStart,
                status = result.state.name,
                validDays = result.validDays,
                trustedShare = result.sensorTrustedShare,
                lowRiskPassed = result.lowRiskPassed,
                reasons = result.reasonCodes
            )
            val adjustments = result.adjustments.map { adjustment ->
                adjustment.toEntity(
                    runId = runId,
                    schedule = schedule,
                    evaluationDate = localRunDate,
                    zoneId = zoneId,
                    generatedAt = now,
                    previous = previous[CircadianTargetSlot(
                        adjustment.requestedDayType,
                        adjustment.hour
                    )]
                )
            }
            publishOrRecordFailure(run, adjustments, now)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Throwable) {
            recordFailure(
                runId = runId,
                schedule = schedule,
                localRunDate = localRunDate,
                now = now,
                lookbackStart = lookbackStart,
                reason = (error as? CircadianRepositoryException)?.code ?: "evaluation_failed",
                cause = error
            )
        }
    }

    suspend fun latestCompletedRun(scheduleRevision: Long): CircadianTargetRunEntity? =
        source.latestCompletedRun(scheduleRevision)

    suspend fun adjustmentsForRun(runId: String): List<CircadianTargetAdjustmentEntity> =
        source.adjustmentsForRun(runId)

    suspend fun latestCompletedLocalRunDate(scheduleRevision: Long): LocalDate? =
        source.latestCompletedLocalRunDate(scheduleRevision)
            ?.let { stored -> runCatching { LocalDate.parse(stored) }.getOrNull() }

    suspend fun resolveEffectiveTarget(
        now: Long,
        schedule: BaseTargetSchedule,
        zoneId: ZoneId,
        targetManagerMode: TargetManagerMode,
        hardMinTargetMmol: Double,
        hardMaxTargetMmol: Double,
        gates: EffectiveTargetRuntimeGates
    ): EffectiveBaseTarget {
        fun resolveWith(adjustments: List<EffectiveTargetAdjustment>): EffectiveBaseTarget =
            effectiveTargetResolver.resolve(
                nowTs = now,
                zoneId = zoneId,
                schedule = schedule,
                targetManagerMode = targetManagerMode,
                hardMinTargetMmol = hardMinTargetMmol,
                hardMaxTargetMmol = hardMaxTargetMmol,
                gates = gates,
                adjustments = adjustments
            )

        if (!schedule.autoEnabled) return resolveWith(emptyList())

        val adjustments = try {
            val latestRun = source.latestCompletedRun(schedule.revision)
            latestRun?.let { run ->
                val cached = effectiveAdjustmentCache
                if (cached?.matches(run) == true) {
                    cached.adjustments
                } else {
                    loadEffectiveAdjustmentSnapshot(run).also { loaded ->
                        effectiveAdjustmentCache = EffectiveAdjustmentCache(
                            runId = run.runId,
                            scheduleRevision = run.scheduleRevision,
                            completedAt = run.completedAt,
                            adjustments = loaded
                        )
                    }
                }
            }.orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            return resolveWith(emptyList()).copy(reasonCodes = listOf("adjustment_source_failed"))
        }

        return resolveWith(adjustments)
    }

    private suspend fun loadEffectiveAdjustmentSnapshot(
        run: CircadianTargetRunEntity
    ): List<EffectiveTargetAdjustment> {
        val runState = runCatching { CircadianAutoState.valueOf(run.status) }.getOrNull()
            ?: return emptyList()
        if (run.completedAt <= 0L || run.runId.isBlank()) return emptyList()

        val raw = source.adjustmentsForRun(run.runId)
        if (raw.size > MAX_ADJUSTMENTS || raw.any { entity ->
                entity.runId != run.runId || entity.scheduleRevision != run.scheduleRevision
            }
        ) {
            return emptyList()
        }
        val mapped = raw.map { entity ->
            entity.toEffectiveTargetAdjustmentOrNull() ?: return emptyList()
        }
        if (runState != CircadianAutoState.ACTIVE && mapped.any { it.state == CircadianAutoState.ACTIVE }) {
            return emptyList()
        }
        return mapped
    }

    private suspend fun loadTelemetry(
        since: Long,
        through: Long
    ): List<CircadianTelemetrySignal> {
        val output = mutableListOf<CircadianTelemetrySignal>()
        var afterTimestamp = if (since == Long.MIN_VALUE) Long.MIN_VALUE else since - 1L
        var afterId = ""

        while (true) {
            val page = source.telemetryBetweenByKeysPage(
                since = since,
                through = through,
                keys = TELEMETRY_KEYS,
                afterTimestamp = afterTimestamp,
                afterId = afterId,
                limit = TELEMETRY_PAGE_SIZE
            )
            if (page.size > TELEMETRY_PAGE_SIZE) {
                throw CircadianRepositoryException("telemetry_page_exceeds_2000")
            }
            if (page.isEmpty()) break
            if (output.size > MAX_TELEMETRY_ROWS - page.size) {
                throw CircadianRepositoryException("telemetry_row_limit_exceeded")
            }

            page.forEach { row ->
                val advancesCursor = row.timestamp > afterTimestamp ||
                    (row.timestamp == afterTimestamp && row.id > afterId)
                if (!advancesCursor || row.timestamp !in since..through || row.key !in TELEMETRY_KEY_SET) {
                    throw CircadianRepositoryException("invalid_telemetry_page")
                }
                afterTimestamp = row.timestamp
                afterId = row.id
                output += CircadianTelemetrySignal(
                    timestamp = row.timestamp,
                    key = row.key,
                    valueDouble = row.valueDouble,
                    valueText = row.valueText
                )
            }
            if (page.size < TELEMETRY_PAGE_SIZE) break
        }
        return output
    }

    private suspend fun loadPreviousAdjustments(
        scheduleRevision: Long
    ): Map<CircadianTargetSlot, CircadianPreviousAdjustment> {
        val previousRun = source.latestCompletedRun(scheduleRevision) ?: return emptyMap()
        return source.adjustmentsForRun(previousRun.runId)
            .asSequence()
            .mapNotNull { entity -> entity.toPreviousAdjustment(previousRun) }
            .groupBy(Pair<CircadianTargetSlot, CircadianPreviousAdjustment>::first)
            .mapNotNull { (slot, rows) -> rows.singleOrNull()?.second?.let { slot to it } }
            .toMap()
    }

    private fun resolveCurrentContext(
        now: Long,
        calibratedGlucose: List<GlucosePoint>,
        telemetry: List<CircadianTelemetrySignal>,
        cohorts: CircadianTargetCohorts
    ): CurrentContext {
        val latestSignals = linkedMapOf<String, CircadianTelemetrySignal>()
        telemetry.forEach { signal ->
            if (signal.timestamp <= now && signal.key in TELEMETRY_KEY_SET) {
                latestSignals[signal.key] = signal
            }
        }
        val latestGlucose = calibratedGlucose.lastOrNull { it.ts <= now }
        val glucoseFresh = latestGlucose?.let { now - it.ts in 0L..CURRENT_FRESHNESS_MS } == true
        val quality = latestSignals.strictNumber("sensor_quality_score")
        val blocked = latestSignals.strictFlag("sensor_quality_blocked")
        val suspectFalseLow = latestSignals.strictFlag("sensor_quality_suspect_false_low")
        val signalsFresh = listOf(
            latestSignals["sensor_quality_score"],
            latestSignals["sensor_quality_blocked"],
            latestSignals["sensor_quality_suspect_false_low"]
        ).all { signal -> signal != null && now - signal.timestamp in 0L..CURRENT_FRESHNESS_MS }
        val sensorTrust = when {
            blocked == true -> SensorTrustState.BLOCKED
            glucoseFresh && signalsFresh && quality != null && quality >= MIN_SENSOR_QUALITY &&
                blocked == false && suspectFalseLow == false -> SensorTrustState.TRUSTED
            else -> SensorTrustState.WARN
        }
        val sensorAgeHours = latestSignals.preferredNonNegative(
            primary = "sensor_age_hours",
            fallback = "sensor_age_days",
            fallbackMultiplier = 24.0,
            now = now
        )
        val latestContext = cohorts.allContext.lastOrNull { sample ->
            sample.timestamp <= now && now - sample.timestamp in 0L..CURRENT_FRESHNESS_MS
        }
        return CurrentContext(
            sensorTrust = sensorTrust,
            sensorAgeHours = sensorAgeHours,
            deliveryTrust = if (sensorTrust == SensorTrustState.TRUSTED) {
                latestContext?.deliveryTrust ?: DeliveryTrustState.UNKNOWN
            } else {
                DeliveryTrustState.UNKNOWN
            }
        )
    }

    private suspend fun publishOrRecordFailure(
        run: CircadianTargetRunEntity,
        adjustments: List<CircadianTargetAdjustmentEntity>,
        now: Long
    ): CircadianTargetRunEntity {
        try {
            source.publishRun(run, adjustments)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Throwable) {
            val existing = existingRunForLocalDate(
                scheduleRevision = run.scheduleRevision,
                localRunDate = run.localRunDate
            )
            if (existing != null) return existing
            return recordFailure(
                runId = run.runId,
                scheduleRevision = run.scheduleRevision,
                localRunDate = run.localRunDate,
                now = now,
                lookbackStart = run.lookbackStart,
                reason = "publication_failed",
                cause = error
            )
        }
        cleanupBestEffort(now)
        return run
    }

    private suspend fun recordFailure(
        runId: String,
        schedule: BaseTargetSchedule,
        localRunDate: LocalDate,
        now: Long,
        lookbackStart: Long,
        reason: String,
        cause: Throwable
    ): CircadianTargetRunEntity = recordFailure(
        runId = runId,
        scheduleRevision = schedule.revision,
        localRunDate = localRunDate.toString(),
        now = now,
        lookbackStart = lookbackStart,
        reason = reason,
        cause = cause
    )

    private suspend fun recordFailure(
        runId: String,
        scheduleRevision: Long,
        localRunDate: String,
        now: Long,
        lookbackStart: Long,
        reason: String,
        cause: Throwable
    ): CircadianTargetRunEntity {
        val failed = CircadianTargetRunEntity(
            runId = runId,
            scheduleRevision = scheduleRevision,
            localRunDate = localRunDate,
            startedAt = now,
            completedAt = now,
            lookbackStart = lookbackStart,
            lookbackEnd = now,
            status = FAILED_STATUS,
            validDays = 0,
            trustedShare = 0.0,
            lowRiskPassed = false,
            reasonCodesJson = encodeReasons(
                listOf(reason, "failure_type=${cause.javaClass.simpleName.ifBlank { "unknown" }}")
            )
        )
        try {
            source.insertFailedRun(failed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (insertError: Throwable) {
            val existing = existingRunForLocalDate(
                scheduleRevision = scheduleRevision,
                localRunDate = localRunDate
            )
            if (existing != null) return existing
            cause.addSuppressed(insertError)
            throw cause
        }
        cleanupBestEffort(now)
        return failed
    }

    private suspend fun existingRunForLocalDate(
        scheduleRevision: Long,
        localRunDate: String
    ): CircadianTargetRunEntity? = try {
        source.runForLocalDate(scheduleRevision, localRunDate)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Throwable) {
        null
    }

    private suspend fun cleanupBestEffort(now: Long) {
        try {
            source.deleteRunsCompletedBefore(saturatingSubtract(now, RETENTION_MS))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            // Retention failure must not invalidate an already committed daily run.
        }
    }

    private fun runEntity(
        runId: String,
        schedule: BaseTargetSchedule,
        localRunDate: LocalDate,
        now: Long,
        lookbackStart: Long,
        status: String,
        validDays: Int,
        trustedShare: Double,
        lowRiskPassed: Boolean,
        reasons: List<String>
    ): CircadianTargetRunEntity = CircadianTargetRunEntity(
        runId = runId,
        scheduleRevision = schedule.revision,
        localRunDate = localRunDate.toString(),
        startedAt = now,
        completedAt = now,
        lookbackStart = lookbackStart,
        lookbackEnd = now,
        status = status,
        validDays = validDays,
        trustedShare = trustedShare,
        lowRiskPassed = lowRiskPassed,
        reasonCodesJson = encodeReasons(reasons)
    )

    private fun CircadianTargetAdjustment.toEntity(
        runId: String,
        schedule: BaseTargetSchedule,
        evaluationDate: LocalDate,
        zoneId: ZoneId,
        generatedAt: Long,
        previous: CircadianPreviousAdjustment?
    ): CircadianTargetAdjustmentEntity {
        val currentStepEvidence = currentStepEvidenceOrNull()
        val sameAppliedStep = previous != null &&
            abs(previous.appliedDeltaMmol - appliedDeltaMmol) <= EPSILON
        val canCarryBaseline = status == CircadianAutoState.ACTIVE &&
            sameAppliedStep &&
            previous?.stepBaselineEvidence.isValidStepEvidence() &&
            currentStepEvidence != null
        val baselineEvidence = when {
            status != CircadianAutoState.ACTIVE -> null
            canCarryBaseline -> previous?.stepBaselineEvidence
            else -> currentStepEvidence
        }
        val unchangedRuns = when {
            status != CircadianAutoState.ACTIVE -> 0
            canCarryBaseline ->
                (previous.unchangedSuccessfulRuns + 1).coerceAtMost(MAX_UNCHANGED_RUNS)
            else -> 1
        }
        val persistedReasons = buildList {
            addAll(reasonCodes)
            if (sourceDayType != requestedDayType) add("source_day_type=${sourceDayType.name}")
            if (status == CircadianAutoState.ACTIVE && currentStepEvidence == null) {
                add("step_evidence_missing")
            }
            add("unchanged_successful_runs=$unchangedRuns")
        }
        return CircadianTargetAdjustmentEntity(
            id = "$runId:${requestedDayType.name}:$hour",
            runId = runId,
            scheduleRevision = schedule.revision,
            dayType = requestedDayType.name,
            hour = hour,
            manualTargetMmol = resolveManualTarget(schedule, evaluationDate, hour, zoneId),
            desiredDeltaMmol = desiredDeltaMmol,
            appliedDeltaMmol = appliedDeltaMmol,
            medianMmol = medianMmol,
            p25 = p25,
            p75 = p75,
            trustedLowCount = trustedLowCount,
            sampleCount = sampleCount,
            activeDays = activeDays,
            qualityScore = qualityScore,
            sensorTrustedShare = sensorTrustedShare,
            generatedAt = generatedAt,
            validUntil = saturatingAdd(generatedAt, ADJUSTMENT_VALIDITY_MS),
            status = status.name,
            reasonCodesJson = encodeReasons(persistedReasons),
            lowerTailReplayErrorMmol = currentStepEvidence?.lowerTailReplayErrorMmol,
            stepBaselineTrustedLowCount = baselineEvidence?.trustedLowCount,
            stepBaselineVariabilityIqrMmol = baselineEvidence?.variabilityIqrMmol,
            stepBaselineLowerTailReplayErrorMmol = baselineEvidence?.lowerTailReplayErrorMmol
        )
    }

    private fun CircadianTargetAdjustment.currentStepEvidenceOrNull(): CircadianStepEvidence? {
        val replayError = lowerTailReplayErrorMmol ?: return null
        val variability = p75 - p25
        return CircadianStepEvidence(
            trustedLowCount = trustedLowCount,
            variabilityIqrMmol = variability,
            lowerTailReplayErrorMmol = replayError
        ).takeIf(CircadianStepEvidence::isValidStepEvidence)
    }

    private fun resolveManualTarget(
        schedule: BaseTargetSchedule,
        evaluationDate: LocalDate,
        hour: Int,
        zoneId: ZoneId
    ): Double {
        val localInstant = (0L..7L).firstNotNullOfOrNull { daysBack ->
            val date = evaluationDate.minusDays(daysBack)
            val zoned = date.atTime(hour, 0).atZone(zoneId)
            zoned.takeIf { it.toLocalDate() == date && it.hour == hour }?.toInstant()
        } ?: throw CircadianRepositoryException("unresolvable_local_hour")
        val target = BaseTargetSchedulePolicy.resolveManual(schedule, localInstant, zoneId).targetMmol
        if (!target.isFinite()) throw CircadianRepositoryException("invalid_manual_target")
        return target
    }

    private fun validateSourceWindow(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        since: Long,
        through: Long
    ) {
        if (glucose.any { it.timestamp !in since..through } ||
            therapy.any { it.timestamp !in since..through }
        ) {
            throw CircadianRepositoryException("source_row_outside_lookback")
        }
    }

    private fun validateForecastWindow(
        forecasts: List<ForecastEntity>,
        since: Long,
        through: Long
    ) {
        if (forecasts.size > MAX_FORECAST_ROWS) {
            throw CircadianRepositoryException("forecast_row_limit_exceeded")
        }
        if (forecasts.any { it.timestamp !in since..through }) {
            throw CircadianRepositoryException("forecast_outside_window")
        }
    }

    private fun validateEvaluationResult(result: CircadianTargetEvaluationResult) {
        if (result.adjustments.size > MAX_ADJUSTMENTS) {
            throw CircadianRepositoryException("adjustment_count_exceeds_72")
        }
        if (result.validDays !in 0..LOOKBACK_DAYS ||
            !result.sensorTrustedShare.isFinite() || result.sensorTrustedShare !in 0.0..1.0
        ) {
            throw CircadianRepositoryException("invalid_run_summary")
        }
        val slots = result.adjustments.map { CircadianTargetSlot(it.requestedDayType, it.hour) }
        if (slots.size != slots.distinct().size) {
            throw CircadianRepositoryException("duplicate_adjustment_slot")
        }
        result.adjustments.forEach { adjustment ->
            val finiteValues = listOf(
                adjustment.desiredDeltaMmol,
                adjustment.appliedDeltaMmol,
                adjustment.medianMmol,
                adjustment.p25,
                adjustment.p75,
                adjustment.qualityScore,
                adjustment.sensorTrustedShare
            )
            if (adjustment.hour !in 0 until HOURS_PER_DAY ||
                finiteValues.any { !it.isFinite() } ||
                adjustment.desiredDeltaMmol !in MIN_DELTA..MAX_DELTA ||
                adjustment.appliedDeltaMmol !in MIN_DELTA..MAX_DELTA ||
                adjustment.trustedLowCount < 0 || adjustment.sampleCount < 0 ||
                adjustment.activeDays < 0 || adjustment.qualityScore !in 0.0..1.0 ||
                adjustment.sensorTrustedShare !in 0.0..1.0 ||
                adjustment.lowerTailReplayErrorMmol?.let { !it.isFinite() || it < 0.0 } == true
            ) {
                throw CircadianRepositoryException("invalid_adjustment")
            }
        }
    }

    private fun encodeReasons(reasons: Collection<String>): String {
        val normalized = reasons.asSequence()
            .map(::normalizeReason)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .take(MAX_REASON_CODES)
            .toMutableList()
        var encoded = gson.toJson(normalized)
        while (encoded.toByteArray(Charsets.UTF_8).size > MAX_REASON_JSON_BYTES && normalized.isNotEmpty()) {
            normalized.removeAt(normalized.lastIndex)
            encoded = gson.toJson(normalized)
        }
        return encoded
    }

    private fun normalizeReason(reason: String): String = reason.trim()
        .asSequence()
        .map { character -> if (character.code in 0x20..0x7e) character else '_' }
        .take(MAX_REASON_LENGTH)
        .joinToString("")

    private fun normalizedRunId(candidate: String): String = candidate.trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_RUN_ID_LENGTH }
        ?: "circadian-target-${UUID.randomUUID()}"

    private data class CurrentContext(
        val sensorTrust: SensorTrustState,
        val sensorAgeHours: Double?,
        val deliveryTrust: DeliveryTrustState
    )

    private data class EffectiveAdjustmentCache(
        val runId: String,
        val scheduleRevision: Long,
        val completedAt: Long,
        val adjustments: List<EffectiveTargetAdjustment>
    ) {
        fun matches(run: CircadianTargetRunEntity): Boolean =
            runId == run.runId &&
                scheduleRevision == run.scheduleRevision &&
                completedAt == run.completedAt
    }

    private class CircadianRepositoryException(val code: String) : IllegalStateException(code)

    private companion object {
        const val HOURS_PER_DAY = 24
        const val LOOKBACK_DAYS = 14
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        const val LOOKBACK_MS = LOOKBACK_DAYS * DAY_MS
        const val RETENTION_MS = 90L * DAY_MS
        const val ADJUSTMENT_VALIDITY_MS = 36L * 60L * 60L * 1_000L
        const val CURRENT_FRESHNESS_MS = 15L * 60L * 1_000L
        const val TELEMETRY_PAGE_SIZE = 2_000
        const val MAX_TELEMETRY_ROWS = 200_000
        const val MAX_ADJUSTMENTS = HOURS_PER_DAY * 3
        const val MAX_REASON_JSON_BYTES = 64 * 1_024
        const val MAX_REASON_CODES = 128
        const val MAX_REASON_LENGTH = 192
        const val MAX_RUN_ID_LENGTH = 128
        const val MAX_UNCHANGED_RUNS = 365
        const val MAX_FORECAST_ROWS = 50_000
        const val MIN_SENSOR_QUALITY = 0.65
        const val MIN_DELTA = -1.0
        const val MAX_DELTA = 0.6
        const val EPSILON = 1e-9
        const val FAILED_STATUS = "FAILED"

        val TELEMETRY_KEYS = listOf(
            "cage_days",
            "cob_effective_grams",
            "cob_grams",
            "iob_units",
            "isf_factor_set_age_hours",
            "sensor_age_days",
            "sensor_age_hours",
            "sensor_quality_blocked",
            "sensor_quality_score",
            "sensor_quality_suspect_false_low",
            "target_low_risk_active",
            "target_low_risk_latched",
            "uam_runtime_control_flag",
            "uam_runtime_flag"
        )
        val TELEMETRY_KEY_SET = TELEMETRY_KEYS.toSet()
    }
}

private class RoomCircadianTargetRepositorySource(
    private val db: CopilotDatabase
) : CircadianTargetRepositorySource {
    override suspend fun glucoseBetween(since: Long, through: Long): List<GlucoseSampleEntity> =
        db.glucoseDao().between(since, through)

    override suspend fun therapyBetween(since: Long, through: Long): List<TherapyEventEntity> =
        db.therapyDao().between(since, through)

    override suspend fun forecastsBetween(since: Long, through: Long): List<ForecastEntity> =
        db.forecastDao().between(since, through)

    override suspend fun telemetryBetweenByKeysPage(
        since: Long,
        through: Long,
        keys: List<String>,
        afterTimestamp: Long,
        afterId: String,
        limit: Int
    ): List<TelemetrySampleLite> = db.telemetryDao().betweenByKeysPage(
        since = since,
        through = through,
        keys = keys,
        afterTimestamp = afterTimestamp,
        afterId = afterId,
        limit = limit
    )

    override suspend fun publishRun(
        run: CircadianTargetRunEntity,
        adjustments: List<CircadianTargetAdjustmentEntity>
    ) {
        db.circadianTargetDao().publishRun(run, adjustments)
    }

    override suspend fun insertFailedRun(run: CircadianTargetRunEntity) {
        db.circadianTargetDao().insertFailedRun(run)
    }

    override suspend fun latestCompletedRun(scheduleRevision: Long): CircadianTargetRunEntity? =
        db.circadianTargetDao().latestCompletedRun(scheduleRevision)

    override suspend fun runForLocalDate(
        scheduleRevision: Long,
        localRunDate: String
    ): CircadianTargetRunEntity? = db.circadianTargetDao().runForLocalDate(
        scheduleRevision = scheduleRevision,
        localRunDate = localRunDate
    )

    override suspend fun adjustmentsForRun(runId: String): List<CircadianTargetAdjustmentEntity> =
        db.circadianTargetDao().adjustmentsByRunId(runId)

    override suspend fun latestCompletedLocalRunDate(scheduleRevision: Long): String? =
        db.circadianTargetDao().latestCompletedLocalRunDate(scheduleRevision)

    override suspend fun deleteRunsCompletedBefore(cutoff: Long) {
        db.circadianTargetDao().deleteRunsCompletedBefore(cutoff)
    }
}

private fun TherapyEventEntity.toSafeDomain(gson: Gson): TherapyEvent = try {
    toDomain(gson)
} catch (_: RuntimeException) {
    TherapyEvent(ts = timestamp, type = type, payload = emptyMap())
}

private fun CircadianTargetAdjustmentEntity.toEffectiveTargetAdjustmentOrNull(): EffectiveTargetAdjustment? {
    val parsedDayType = runCatching { CircadianDayType.valueOf(dayType) }.getOrNull() ?: return null
    val parsedState = runCatching { CircadianAutoState.valueOf(status) }.getOrNull() ?: return null
    if (hour !in 0 until 24) return null
    val reasons = decodeRuntimeReasonCodes(reasonCodesJson) ?: return null
    return EffectiveTargetAdjustment(
        runId = runId,
        scheduleRevision = scheduleRevision,
        dayType = parsedDayType,
        hour = hour,
        appliedDeltaMmol = appliedDeltaMmol,
        generatedAt = generatedAt,
        validUntil = validUntil,
        state = parsedState,
        reasonCodes = reasons
    )
}

private fun decodeRuntimeReasonCodes(json: String): List<String>? = runCatching {
    require(json.toByteArray(Charsets.UTF_8).size <= 64 * 1_024)
    val root = JsonParser.parseString(json)
    require(root.isJsonArray)
    val array = root.asJsonArray
    require(array.size() <= 128)
    buildList {
        repeat(array.size()) { index ->
            val element = array[index]
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
            add(element.asString)
        }
    }
}.getOrNull()

private fun CircadianTargetAdjustmentEntity.toPreviousAdjustment(
    run: CircadianTargetRunEntity
): Pair<CircadianTargetSlot, CircadianPreviousAdjustment>? {
    if (runId != run.runId || scheduleRevision != run.scheduleRevision ||
        hour !in 0 until 24 || !appliedDeltaMmol.isFinite() || appliedDeltaMmol !in -1.0..0.6
    ) {
        return null
    }
    val parsedDayType = runCatching { CircadianDayType.valueOf(dayType) }.getOrNull() ?: return null
    if (reasonCodesJson.toByteArray(Charsets.UTF_8).size > 64 * 1_024) return null
    val reasons = runCatching {
        val root = JsonParser.parseString(reasonCodesJson)
        require(root.isJsonArray)
        val array = root.asJsonArray
        buildList<String> {
            repeat(array.size()) { index ->
                val element = array[index]
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
                add(element.asString)
            }
        }
    }.getOrNull() ?: return null
    val unchangedEntries = reasons.filter { it.startsWith("unchanged_successful_runs=") }
    if (unchangedEntries.size != 1) return null
    val unchanged = unchangedEntries.single()
        .substringAfter('=', missingDelimiterValue = "")
        .toIntOrNull()
        ?.takeIf { it in 0..365 }
        ?: return null
    val baselineEvidence = stepBaselineEvidenceOrNull()
    val currentEvidence = currentStepEvidenceOrNull()
    val comparison = if (baselineEvidence != null && currentEvidence != null) {
        CircadianPriorStepComparison(
            lowExposureNotWorse = currentEvidence.trustedLowCount <= baselineEvidence.trustedLowCount,
            variabilityNotWorse = currentEvidence.variabilityIqrMmol <=
                baselineEvidence.variabilityIqrMmol + STEP_EVIDENCE_EPSILON,
            lowerTailReplayErrorNotWorse = currentEvidence.lowerTailReplayErrorMmol <=
                baselineEvidence.lowerTailReplayErrorMmol + STEP_EVIDENCE_EPSILON
        )
    } else {
        null
    }
    return CircadianTargetSlot(parsedDayType, hour) to CircadianPreviousAdjustment(
        appliedDeltaMmol = appliedDeltaMmol,
        unchangedSuccessfulRuns = if (status == CircadianAutoState.ACTIVE.name) unchanged else 0,
        priorStepComparison = if (status == CircadianAutoState.ACTIVE.name) comparison else null,
        stepBaselineEvidence = if (status == CircadianAutoState.ACTIVE.name) baselineEvidence else null
    )
}

private fun CircadianTargetAdjustmentEntity.stepBaselineEvidenceOrNull(): CircadianStepEvidence? {
    val values = listOf(
        stepBaselineTrustedLowCount,
        stepBaselineVariabilityIqrMmol,
        stepBaselineLowerTailReplayErrorMmol
    )
    if (values.all { it == null }) return null
    if (values.any { it == null }) return null
    return CircadianStepEvidence(
        trustedLowCount = stepBaselineTrustedLowCount ?: return null,
        variabilityIqrMmol = stepBaselineVariabilityIqrMmol ?: return null,
        lowerTailReplayErrorMmol = stepBaselineLowerTailReplayErrorMmol ?: return null
    ).takeIf(CircadianStepEvidence::isValidStepEvidence)
}

private fun CircadianTargetAdjustmentEntity.currentStepEvidenceOrNull(): CircadianStepEvidence? {
    val replayError = lowerTailReplayErrorMmol ?: return null
    return CircadianStepEvidence(
        trustedLowCount = trustedLowCount,
        variabilityIqrMmol = p75 - p25,
        lowerTailReplayErrorMmol = replayError
    ).takeIf(CircadianStepEvidence::isValidStepEvidence)
}

private fun CircadianStepEvidence?.isValidStepEvidence(): Boolean =
    this != null &&
        trustedLowCount >= 0 &&
        variabilityIqrMmol.isFinite() && variabilityIqrMmol >= 0.0 &&
        lowerTailReplayErrorMmol.isFinite() && lowerTailReplayErrorMmol >= 0.0

internal fun calculateCircadianLowerTailReplayErrors(
    forecasts: List<ForecastEntity>,
    canonicalGlucose: List<GlucosePoint>,
    zoneId: ZoneId
): Map<CircadianTargetSlot, Double> {
    if (forecasts.isEmpty() || canonicalGlucose.isEmpty()) return emptyMap()
    val glucose = canonicalGlucose.asSequence()
        .filter { point ->
            point.ts >= 0L && point.valueMmol.isFinite() && point.valueMmol > 0.0 &&
                point.quality == DataQuality.OK
        }
        .sortedWith(compareBy<GlucosePoint> { it.ts }.thenBy { it.source })
        .distinctBy(GlucosePoint::ts)
        .toList()
    if (glucose.isEmpty()) return emptyMap()

    val deduplicated = linkedMapOf<Pair<Long, Int>, ForecastEntity>()
    forecasts.asSequence()
        .sortedWith(compareBy<ForecastEntity> { it.timestamp }.thenBy { it.horizonMinutes }.thenBy { it.id })
        .forEach { forecast ->
            deduplicated[forecast.timestamp to forecast.horizonMinutes] = forecast
        }

    val accumulators = mutableMapOf<Pair<CircadianTargetSlot, Int>, LowerTailAccumulator>()
    deduplicated.values.forEach { forecast ->
        if (forecast.horizonMinutes !in LOWER_TAIL_HORIZONS ||
            forecast.timestamp < 0L ||
            !forecast.valueMmol.isFinite() ||
            !forecast.ciLow.isFinite() ||
            !forecast.ciHigh.isFinite() ||
            forecast.ciLow > forecast.valueMmol ||
            forecast.valueMmol > forecast.ciHigh
        ) {
            return@forEach
        }
        val actual = nearestMatureActual(glucose, forecast.timestamp) ?: return@forEach
        val local = Instant.ofEpochMilli(forecast.timestamp).atZone(zoneId)
        val dayType = when (local.dayOfWeek) {
            java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY -> CircadianDayType.WEEKEND
            else -> CircadianDayType.WEEKDAY
        }
        val error = (forecast.ciLow - actual.valueMmol).coerceAtLeast(0.0)
        listOf(CircadianDayType.ALL, dayType).forEach { requestedDayType ->
            val key = CircadianTargetSlot(requestedDayType, local.hour) to forecast.horizonMinutes
            accumulators.getOrPut(key, ::LowerTailAccumulator)
                .add(error = error, date = local.toLocalDate())
        }
    }

    return buildMap {
        CircadianDayType.entries.forEach { dayType ->
            repeat(HOURS_PER_DAY) { hour ->
                val slot = CircadianTargetSlot(dayType, hour)
                val minimumDays = when (dayType) {
                    CircadianDayType.ALL -> 7
                    CircadianDayType.WEEKDAY -> 7
                    CircadianDayType.WEEKEND -> 4
                }
                val horizonErrors = LOWER_TAIL_HORIZONS.mapNotNull { horizon ->
                    accumulators[slot to horizon]?.rmseOrNull(minimumDays)
                }
                if (horizonErrors.size == LOWER_TAIL_HORIZONS.size) {
                    put(slot, horizonErrors.maxOrNull() ?: return@repeat)
                }
            }
        }
    }
}

private fun nearestMatureActual(
    glucose: List<GlucosePoint>,
    targetTimestamp: Long
): GlucosePoint? {
    val index = glucose.binarySearchBy(targetTimestamp) { point -> point.ts }
    if (index >= 0) return glucose[index]
    val insertion = -index - 1
    val candidates = listOfNotNull(
        glucose.getOrNull(insertion - 1),
        glucose.getOrNull(insertion)
    )
    return candidates.minWithOrNull(
        compareBy<GlucosePoint> { point -> absoluteTimestampDistance(point.ts, targetTimestamp) }
            .thenBy(GlucosePoint::ts)
    )?.takeIf { point ->
        absoluteTimestampDistance(point.ts, targetTimestamp) <= ACTUAL_MATCH_TOLERANCE_MS
    }
}

private fun absoluteTimestampDistance(left: Long, right: Long): Long = when {
    left >= right -> left - right
    else -> right - left
}

private class LowerTailAccumulator {
    private var squaredErrorSum = 0.0
    private var sampleCount = 0
    private val dates = linkedSetOf<LocalDate>()

    fun add(error: Double, date: LocalDate) {
        if (!error.isFinite() || error < 0.0) return
        squaredErrorSum += error * error
        sampleCount += 1
        dates += date
    }

    fun rmseOrNull(minimumDays: Int): Double? {
        if (sampleCount < minimumDays || dates.size < minimumDays || !squaredErrorSum.isFinite()) {
            return null
        }
        return sqrt(squaredErrorSum / sampleCount.toDouble()).takeIf(Double::isFinite)
    }
}

private const val HOURS_PER_DAY = 24
private const val ACTUAL_MATCH_TOLERANCE_MS = 7L * 60L * 1_000L + 30_000L
private const val STEP_EVIDENCE_EPSILON = 1e-9
private val LOWER_TAIL_HORIZONS = setOf(30, 60)

private fun Map<String, CircadianTelemetrySignal>.strictNumber(key: String): Double? {
    val signal = this[key] ?: return null
    val numeric = signal.valueDouble?.takeIf(Double::isFinite)
    val textual = signal.valueText
        ?.trim()
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    if (signal.valueDouble != null && numeric == null) return null
    if (signal.valueText != null && textual == null) return null
    if (numeric != null && textual != null && abs(numeric - textual) > 1e-9) return null
    return numeric ?: textual
}

private fun Map<String, CircadianTelemetrySignal>.strictFlag(key: String): Boolean? {
    val signal = this[key] ?: return null
    val numeric = signal.valueDouble?.toStrictFlag()
    val textual = signal.valueText?.toStrictFlag()
    if (signal.valueDouble != null && numeric == null) return null
    if (signal.valueText != null && textual == null) return null
    if (numeric != null && textual != null && numeric != textual) return null
    return numeric ?: textual
}

private fun Map<String, CircadianTelemetrySignal>.preferredNonNegative(
    primary: String,
    fallback: String,
    fallbackMultiplier: Double,
    now: Long
): Double? {
    val primarySignal = this[primary]
    if (primarySignal != null) {
        if (now - primarySignal.timestamp !in 0L..15L * 60L * 1_000L) return null
        return strictNumber(primary)?.takeIf { it >= 0.0 }
    }
    val fallbackSignal = this[fallback] ?: return null
    if (now - fallbackSignal.timestamp !in 0L..15L * 60L * 1_000L) return null
    return strictNumber(fallback)
        ?.takeIf { it >= 0.0 }
        ?.times(fallbackMultiplier)
        ?.takeIf(Double::isFinite)
}

private fun Double.toStrictFlag(): Boolean? =
    takeIf { isFinite() && it in 0.0..1.0 }?.let { it >= 0.5 }

private fun String.toStrictFlag(): Boolean? = when (val normalized = trim().lowercase()) {
    "true", "active", "yes", "on" -> true
    "false", "inactive", "no", "off" -> false
    else -> normalized.replace(',', '.').toDoubleOrNull()?.toStrictFlag()
}

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

private fun saturatingSubtract(value: Long, decrement: Long): Long =
    if (value < Long.MIN_VALUE + decrement) Long.MIN_VALUE else value - decrement
