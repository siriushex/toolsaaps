package io.aaps.copilot.tools

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.ClinicalTargetManagerEvidenceProjection
import io.aaps.copilot.data.local.dao.TargetManagerDao
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.data.local.entity.TargetManagerStateEntity
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.TargetCommandDispatcher
import io.aaps.copilot.data.repository.TargetDeliveryStatusProvider
import io.aaps.copilot.data.repository.TargetManagerRepository
import io.aaps.copilot.data.repository.calculateCircadianLowerTailReplayErrors
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
import io.aaps.copilot.domain.target.CircadianTargetEvaluator
import io.aaps.copilot.domain.target.CircadianTargetSlot
import io.aaps.copilot.domain.target.CircadianTelemetrySignal
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManager
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.TargetProposal
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CircadianTargetReplayToolTest {

    @Test
    fun replayCircadianTargetsAndSingleWriterSafety() = runBlocking {
        Class.forName("org.sqlite.JDBC")
        val configuredDb = System.getenv("COPILOT_REPLAY_DB")?.trim().orEmpty()
        val managerReplay = replaySingleWriter()
        val deepGateReplay = replayDeepGateSafety()
        val report = if (configuredDb.isBlank()) {
            val trusted = syntheticData(currentSensorAgeHours = 48.0)
            val oldSensor = syntheticData(currentSensorAgeHours = 15.0 * 24.0)
            ReplayReport(
                sourceLabel = "deterministic synthetic 14-day safety fixtures",
                databaseSha256 = null,
                quickCheck = "not_applicable",
                rows = replaySnapshot(trusted, SYNTHETIC_NOW, "trusted_sensor") +
                    replaySnapshot(oldSensor, SYNTHETIC_NOW, "old_sensor_guard"),
                coverage = ReplayCoverage(
                    requestedEvaluationDays = 1,
                    loadedSourceDays = 14,
                    warmupDays = 13,
                    evaluatedDays = 1,
                    firstEvaluationDate = Instant.ofEpochMilli(SYNTHETIC_NOW)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate(),
                    lastEvaluationDate = Instant.ofEpochMilli(SYNTHETIC_NOW)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                ),
                managerReplay = managerReplay,
                deepGateReplay = deepGateReplay
            )
        } else {
            val database = requireCopiedDatabase(configuredDb)
            val loaded = loadReplayData(database.file, database.connection)
            database.connection.close()
            val replay = replayDailyWindow(loaded)
            ReplayReport(
                sourceLabel = database.file.canonicalPath,
                databaseSha256 = database.sha256,
                quickCheck = database.quickCheck,
                rows = replay.rows,
                coverage = replay.coverage,
                managerReplay = managerReplay,
                deepGateReplay = deepGateReplay
            )
        }

        writeReport(report)

        assertThat(report.rows.none { it.negativeApplied && it.protectedTrustedLowBelow4 }).isTrue()
        assertThat(report.rows.none { it.deepApplied && it.protectedTrustedLowBelow4_4 }).isTrue()
        assertThat(report.rows.all { it.deltaMmol in MIN_DELTA..MAX_DELTA }).isTrue()
        assertThat(
            report.rows
                .filter { (it.sensorAgeDays ?: Double.POSITIVE_INFINITY) >= OLD_SENSOR_DAYS }
                .none { it.negativeApplied }
        ).isTrue()
        assertThat(managerReplay.dispatchHarnessRepositoryClass).isEqualTo("TargetManagerRepository")
        assertThat(managerReplay.activeRoutingDisablesLegacyWriter).isTrue()
        assertThat(managerReplay.commandsPerSemanticEventMax).isAtMost(1)
        assertThat(deepGateReplay.deepStepReached).isTrue()
        assertThat(deepGateReplay.lowVetoPassed).isTrue()
    }

    private fun replayDailyWindow(data: ReplayData): DailyReplayOutcome {
        require(data.glucose.isNotEmpty()) { "Replay database has no glucose rows" }
        val zoneId = ZoneId.systemDefault()
        val firstDate = Instant.ofEpochMilli(data.glucose.first().ts).atZone(zoneId).toLocalDate()
        val lastTimestamp = data.glucose.last().ts
        val lastDate = Instant.ofEpochMilli(lastTimestamp).atZone(zoneId).toLocalDate()
        val preferredStart = maxOf(
            firstDate.plusDays(13),
            lastDate.minusDays((REQUESTED_EVALUATION_DAYS - 1).toLong())
        )
        val startDate = if (preferredStart > lastDate) lastDate else preferredStart
        val schedule = replaySchedule()
        val rows = mutableListOf<ReplayRow>()
        var previous = emptyMap<CircadianTargetSlot, CircadianPreviousAdjustment>()
        var date = startDate
        while (!date.isAfter(lastDate)) {
            val scheduledTs = date.atTime(LocalTime.of(9, 0)).atZone(zoneId).toInstant().toEpochMilli()
            val evaluationTs = if (date == lastDate) min(scheduledTs, lastTimestamp) else scheduledTs
            if (evaluationTs >= data.glucose.first().ts) {
                val snapshot = data.window(evaluationTs - LOOKBACK_MS, evaluationTs)
                val replay = evaluateSnapshot(
                    data = snapshot,
                    evaluationTs = evaluationTs,
                    scenario = "phone_${date}",
                    schedule = schedule,
                    previous = previous,
                    zoneId = zoneId
                )
                rows += replay.rows
                previous = replay.nextPrevious
            }
            date = date.plusDays(1)
        }
        val evaluatedDates = rows.map(ReplayRow::evaluationDate).distinct().sorted()
        return DailyReplayOutcome(
            rows = rows,
            finalPrevious = previous,
            coverage = ReplayCoverage(
                requestedEvaluationDays = REQUESTED_EVALUATION_DAYS,
                loadedSourceDays = ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1,
                warmupDays = ChronoUnit.DAYS.between(firstDate, startDate).toInt().coerceAtLeast(0),
                evaluatedDays = evaluatedDates.size,
                firstEvaluationDate = evaluatedDates.firstOrNull(),
                lastEvaluationDate = evaluatedDates.lastOrNull()
            )
        )
    }

    private fun replaySnapshot(
        data: ReplayData,
        evaluationTs: Long,
        scenario: String
    ): List<ReplayRow> = evaluateSnapshot(
        data = data.window(evaluationTs - LOOKBACK_MS, evaluationTs),
        evaluationTs = evaluationTs,
        scenario = scenario,
        schedule = replaySchedule(),
        previous = emptyMap(),
        zoneId = ZoneOffset.UTC
    ).rows

    private fun evaluateSnapshot(
        data: ReplayData,
        evaluationTs: Long,
        scenario: String,
        schedule: BaseTargetSchedule,
        previous: Map<CircadianTargetSlot, CircadianPreviousAdjustment>,
        zoneId: ZoneId
    ): SnapshotReplay {
        val evaluationDate = Instant.ofEpochMilli(evaluationTs).atZone(zoneId).toLocalDate()
        val cohorts = CircadianTargetCohortBuilder(zoneId).build(
            canonicalGlucose = data.glucose,
            telemetry = data.telemetry,
            therapyEvents = data.therapy
        )
        val currentSensorTrust = currentSensorTrust(data, evaluationTs)
        val currentSensorAgeHours = currentSensorAgeHours(data, evaluationTs)
        val deliveryTrust = cohorts.allContext.lastOrNull()?.deliveryTrust ?: DeliveryTrustState.UNKNOWN
        val lowerTailReplayErrors = calculateCircadianLowerTailReplayErrors(
            forecasts = data.forecasts,
            canonicalGlucose = data.glucose,
            zoneId = zoneId
        )
        val result = CircadianTargetEvaluator().evaluate(
            CircadianTargetEvaluationInput(
                evaluatedAt = evaluationTs,
                evaluationDate = evaluationDate,
                cohorts = cohorts,
                currentSensorTrust = currentSensorTrust,
                currentSensorAgeHours = currentSensorAgeHours,
                deliveryTrust = deliveryTrust,
                previousAdjustments = previous,
                lowerTailReplayErrors = lowerTailReplayErrors
            )
        )
        val adjustments = result.adjustments.associateBy {
            CircadianTargetSlot(it.requestedDayType, it.hour)
        }
        val rows = buildList(HOURS_PER_DAY * CircadianDayType.entries.size) {
            CircadianDayType.entries.forEach { dayType ->
                repeat(HOURS_PER_DAY) { hour ->
                    val adjustment = adjustments[CircadianTargetSlot(dayType, hour)]
                    val slotInstant = LocalDateTime.of(evaluationDate, LocalTime.of(hour, 0))
                        .atZone(zoneId)
                        .toInstant()
                    val manual = BaseTargetSchedulePolicy.resolveManual(schedule, slotInstant, zoneId).targetMmol
                    val applied = adjustment?.appliedDeltaMmol ?: 0.0
                    val effective = (manual + applied).coerceIn(MIN_TARGET, MAX_TARGET)
                    val protectedLow4 = hasProtectedLow(
                        cohorts = cohorts,
                        evaluationDate = evaluationDate,
                        dayType = dayType,
                        hour = hour,
                        threshold = 4.0,
                        days = 7
                    )
                    val protectedLow44 = hasProtectedLow(
                        cohorts = cohorts,
                        evaluationDate = evaluationDate,
                        dayType = dayType,
                        hour = hour,
                        threshold = 4.4,
                        days = 14
                    )
                    val sensorCounts = sensorCounts(data, evaluationDate, dayType, hour, zoneId)
                    add(
                        ReplayRow(
                            scenario = scenario,
                            evaluationDate = evaluationDate,
                            dayType = dayType,
                            hour = hour,
                            manualTargetMmol = manual,
                            desiredDeltaMmol = adjustment?.desiredDeltaMmol ?: 0.0,
                            deltaMmol = applied,
                            effectiveTargetMmol = effective,
                            validDays = result.validDays,
                            trustedShare = adjustment?.sensorTrustedShare ?: result.sensorTrustedShare,
                            trustedLowCount = adjustment?.trustedLowCount ?: 0,
                            sensorAgeDays = currentSensorAgeHours?.div(24.0),
                            sensorAgeCohort = sensorAgeCohort(currentSensorAgeHours, currentSensorTrust),
                            trustedSensorRows = sensorCounts.first,
                            oldOrDegradedSensorRows = sensorCounts.second,
                            state = adjustment?.status ?: result.state,
                            reasons = (adjustment?.reasonCodes ?: result.reasonCodes).sorted(),
                            managerDecision = managerDecision(
                                evaluationTs = evaluationTs,
                                dayType = dayType,
                                hour = hour,
                                manualTarget = manual,
                                effectiveTarget = effective,
                                adjustment = adjustment,
                                sensorTrust = currentSensorTrust ?: SensorTrustState.WARN
                            ),
                            protectedTrustedLowBelow4 = protectedLow4,
                            protectedTrustedLowBelow4_4 = protectedLow44
                        )
                    )
                }
            }
        }
        return SnapshotReplay(
            rows = rows,
            nextPrevious = replayPreviousAdjustments(result.adjustments, previous)
        )
    }

    private fun replayPreviousAdjustments(
        adjustments: List<CircadianTargetAdjustment>,
        previous: Map<CircadianTargetSlot, CircadianPreviousAdjustment>
    ): Map<CircadianTargetSlot, CircadianPreviousAdjustment> = adjustments.associate { adjustment ->
        val slot = CircadianTargetSlot(adjustment.requestedDayType, adjustment.hour)
        val prior = previous[slot]
        val currentEvidence = adjustment.toReplayStepEvidence()
        val sameAppliedStep = prior != null &&
            abs(prior.appliedDeltaMmol - adjustment.appliedDeltaMmol) <= EPSILON
        val carryBaseline = adjustment.status == CircadianAutoState.ACTIVE &&
            sameAppliedStep && prior?.stepBaselineEvidence != null && currentEvidence != null
        val baseline = when {
            adjustment.status != CircadianAutoState.ACTIVE -> null
            carryBaseline -> prior?.stepBaselineEvidence
            else -> currentEvidence
        }
        val unchanged = when {
            adjustment.status != CircadianAutoState.ACTIVE -> 0
            carryBaseline -> (prior?.unchangedSuccessfulRuns ?: 0) + 1
            else -> 1
        }
        val comparison = if (baseline != null && currentEvidence != null) {
            CircadianPriorStepComparison(
                lowExposureNotWorse = currentEvidence.trustedLowCount <= baseline.trustedLowCount,
                variabilityNotWorse = currentEvidence.variabilityIqrMmol <=
                    baseline.variabilityIqrMmol + EPSILON,
                lowerTailReplayErrorNotWorse = currentEvidence.lowerTailReplayErrorMmol <=
                    baseline.lowerTailReplayErrorMmol + EPSILON
            )
        } else {
            null
        }
        slot to CircadianPreviousAdjustment(
            appliedDeltaMmol = adjustment.appliedDeltaMmol,
            unchangedSuccessfulRuns = unchanged,
            priorStepComparison = comparison,
            stepBaselineEvidence = baseline
        )
    }

    private fun CircadianTargetAdjustment.toReplayStepEvidence(): CircadianStepEvidence? {
        val lowerTail = lowerTailReplayErrorMmol ?: return null
        val variability = p75 - p25
        if (!lowerTail.isFinite() || lowerTail < 0.0 || !variability.isFinite() || variability < 0.0) {
            return null
        }
        return CircadianStepEvidence(
            trustedLowCount = trustedLowCount,
            variabilityIqrMmol = variability,
            lowerTailReplayErrorMmol = lowerTail
        )
    }

    private fun managerDecision(
        evaluationTs: Long,
        dayType: CircadianDayType,
        hour: Int,
        manualTarget: Double,
        effectiveTarget: Double,
        adjustment: CircadianTargetAdjustment?,
        sensorTrust: SensorTrustState
    ): String {
        val proposal = adjustment
            ?.takeIf { it.status == CircadianAutoState.ACTIVE && abs(effectiveTarget - manualTarget) >= 0.05 }
            ?.let {
                TargetProposal(
                    sourceRuleId = "CircadianTargetReplay",
                    intent = TargetIntent.NORMAL_CONTROL,
                    targetMmol = effectiveTarget,
                    durationMinutes = 30,
                    priority = 100,
                    confidence = it.qualityScore.coerceIn(0.0, 1.0),
                    reasonCodes = it.reasonCodes,
                    generatedAt = evaluationTs,
                    inputFingerprint = "replay:${dayType.name}:$hour:$evaluationTs"
                )
            }
        return TargetManager().decide(
            managerInput(
                nowTs = evaluationTs,
                proposals = listOfNotNull(proposal),
                sensorTrust = sensorTrust,
                baseTarget = manualTarget
            )
        ).outcome.name
    }

    private suspend fun replaySingleWriter(): ManagerReplay {
        val dispatches = AtomicInteger()
        val repository = TargetManagerRepository(
            dao = ReplayTargetManagerDao(),
            gson = Gson(),
            dispatcher = TargetCommandDispatcher {
                dispatches.incrementAndGet()
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )
        val input = managerInput(
            nowTs = SYNTHETIC_NOW,
            proposals = listOf(
                TargetProposal(
                    sourceRuleId = "CircadianTargetReplay",
                    intent = TargetIntent.NORMAL_CONTROL,
                    targetMmol = 6.0,
                    durationMinutes = 30,
                    priority = 100,
                    confidence = 1.0,
                    reasonCodes = listOf("single_writer_replay"),
                    generatedAt = SYNTHETIC_NOW,
                    inputFingerprint = "single-semantic-event"
                )
            ),
            sensorTrust = SensorTrustState.TRUSTED,
            baseTarget = 5.8
        )
        val first = repository.evaluateAndDispatch(input)
        val duplicate = repository.evaluateAndDispatch(input)
        check(first.outcome == TargetDecisionOutcome.SEND)
        check(duplicate.outcome == TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE)
        val activeRouting = AutomationRepository.automaticTargetWriterRoutingStatic(TargetManagerMode.ACTIVE)
        return ManagerReplay(
            dispatchHarnessRepositoryClass = repository::class.simpleName.orEmpty(),
            activeRoutingDisablesLegacyWriter = activeRouting.evaluateManager &&
                !activeRouting.submitLegacyAutomatic &&
                !activeRouting.fallbackToLegacyOnManagerFailure,
            commandsPerSemanticEventMax = dispatches.get()
        )
    }

    private fun replayDeepGateSafety(): DeepGateReplay {
        val data = syntheticData(
            currentSensorAgeHours = 48.0,
            days = 23,
            glucoseMmol = 8.4
        )
        val replay = replayDailyWindow(data)
        val deepRow = replay.rows
            .filter { it.dayType == CircadianDayType.ALL }
            .minByOrNull(ReplayRow::deltaMmol)
        val stepTrace = replay.rows
            .filter { it.dayType == CircadianDayType.ALL && it.hour == 9 }
            .joinToString(" | ") { row ->
                "${row.evaluationDate}:${row.deltaMmol}:${row.state}:${row.reasons.joinToString("+")}"
            }
        val deepestDelta = deepRow?.deltaMmol ?: 0.0
        val targetHour = deepRow?.hour ?: 9
        val lastTimestamp = data.glucose.last().ts
        val evaluationDate = Instant.ofEpochMilli(lastTimestamp).atZone(ZoneOffset.UTC).toLocalDate()
        val lowDate = evaluationDate.minusDays(2)
        var replaced = false
        val lowGlucose = data.glucose.map { point ->
            val local = Instant.ofEpochMilli(point.ts).atZone(ZoneOffset.UTC)
            if (!replaced && local.toLocalDate() == lowDate && local.hour == targetHour) {
                replaced = true
                point.copy(valueMmol = 4.2)
            } else {
                point
            }
        }
        check(replaced) { "Synthetic deep replay could not place a protected low" }
        val lowData = data.copy(glucose = lowGlucose)
        val vetoSnapshot = evaluateSnapshot(
            data = lowData.window(lastTimestamp - LOOKBACK_MS, lastTimestamp),
            evaluationTs = lastTimestamp,
            scenario = "deep_low_veto",
            schedule = replaySchedule(),
            previous = replay.finalPrevious,
            zoneId = ZoneOffset.UTC
        )
        val vetoRow = vetoSnapshot.rows.single {
            it.dayType == CircadianDayType.ALL && it.hour == targetHour
        }
        return DeepGateReplay(
            deepStepReached = deepestDelta <= -0.4 + EPSILON,
            lowVetoPassed = vetoRow.deltaMmol >= -0.3 - EPSILON &&
                "deep_low_below_4_4" in vetoRow.reasons,
            deepestDeltaMmol = deepestDelta,
            lowVetoDeltaMmol = vetoRow.deltaMmol,
            stepTrace = stepTrace
        )
    }

    private fun managerInput(
        nowTs: Long,
        proposals: List<TargetProposal>,
        sensorTrust: SensorTrustState,
        baseTarget: Double
    ): TargetManagerInput = TargetManagerInput(
        nowTs = nowTs,
        glucoseTimestamp = nowTs - MINUTE_MS,
        therapyWatermark = nowTs - 2 * MINUTE_MS,
        mode = TargetManagerMode.ACTIVE,
        proposals = proposals,
        runtimeState = TargetManagerRuntimeState(TargetManagerMode.ACTIVE),
        activeAapsTarget = null,
        safety = TargetManagerSafetyContext(
            killSwitch = false,
            dataFresh = true,
            sensorTrust = sensorTrust,
            deliveryTrust = DeliveryTrustState.NORMAL,
            currentGlucoseMmol = 6.8,
            minimumPredictedOrCiMmol = 5.2,
            lowRiskThresholdMmol = 4.4,
            minTargetMmol = MIN_TARGET,
            maxTargetMmol = MAX_TARGET,
            minDurationMinutes = 15,
            maxDurationMinutes = 120,
            baseTargetMmol = baseTarget
        ),
        reliability = emptyMap(),
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(
            scheduleRevision = 1L,
            intervalId = null,
            adjustmentRunId = "circadian-replay"
        ),
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext()
    )

    private fun requireCopiedDatabase(pathRaw: String): CopiedDatabase {
        val file = File(pathRaw).canonicalFile
        require("/data/data/" !in file.path) { "Live-device database paths are forbidden" }
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "COPILOT_REPLAY_DB must be a regular copied database"
        }
        val connection = DriverManager.getConnection("jdbc:sqlite:file:${file.path}?mode=ro")
        connection.createStatement().use { it.execute("PRAGMA query_only=ON") }
        val quickCheck = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA quick_check").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }.joinToString("; ")
            }
        }
        require(quickCheck == "ok") { "Replay database quick_check failed: $quickCheck" }
        return CopiedDatabase(file, connection, quickCheck, sha256(file))
    }

    private fun loadReplayData(file: File, connection: Connection): ReplayData {
        val maxTs = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT MAX(timestamp) FROM glucose_samples").use { rows ->
                require(rows.next()) { "glucose_samples has no MAX(timestamp) row" }
                rows.getLong(1)
            }
        }
        require(maxTs > 0L) { "Replay database has no glucose history" }
        val minTs = maxTs - REPLAY_SOURCE_MS
        val glucose = linkedMapOf<Long, GlucosePoint>()
        connection.prepareStatement(
            "SELECT timestamp, mmol, source, quality FROM glucose_samples " +
                "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp, id"
        ).use { statement ->
            statement.setLong(1, minTs)
            statement.setLong(2, maxTs)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val quality = parseQuality(rows.getString("quality"))
                    val point = GlucosePoint(
                        ts = rows.getLong("timestamp"),
                        valueMmol = rows.getDouble("mmol"),
                        source = rows.getString("source") ?: "unknown",
                        quality = quality
                    )
                    val current = glucose[point.ts]
                    if (current == null || qualityRank(point.quality) > qualityRank(current.quality)) {
                        glucose[point.ts] = point
                    }
                }
            }
        }
        val telemetry = mutableListOf<CircadianTelemetrySignal>()
        connection.prepareStatement(
            "SELECT timestamp, key, valueDouble, valueText FROM telemetry_samples " +
                "WHERE timestamp BETWEEN ? AND ? AND key IN (${TELEMETRY_KEYS.joinToString { "?" }}) " +
                "ORDER BY timestamp, id"
        ).use { statement ->
            statement.setLong(1, minTs)
            statement.setLong(2, maxTs)
            TELEMETRY_KEYS.forEachIndexed { index, key -> statement.setString(index + 3, key) }
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val rawDouble = rows.getObject("valueDouble")
                    telemetry += CircadianTelemetrySignal(
                        timestamp = rows.getLong("timestamp"),
                        key = rows.getString("key"),
                        valueDouble = (rawDouble as? Number)?.toDouble(),
                        valueText = rows.getString("valueText")
                    )
                }
            }
        }
        val gson = Gson()
        val therapy = mutableListOf<TherapyEvent>()
        connection.prepareStatement(
            "SELECT timestamp, type, payloadJson FROM therapy_events " +
                "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp, id"
        ).use { statement ->
            statement.setLong(1, minTs)
            statement.setLong(2, maxTs)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    therapy += TherapyEvent(
                        ts = rows.getLong("timestamp"),
                        type = rows.getString("type") ?: "unknown",
                        payload = parsePayload(gson, rows.getString("payloadJson") ?: "{}")
                    )
                }
            }
        }
        val forecasts = mutableListOf<ForecastEntity>()
        connection.prepareStatement(
            "SELECT id, timestamp, horizonMinutes, valueMmol, ciLow, ciHigh, modelVersion " +
                "FROM forecasts WHERE timestamp BETWEEN ? AND ? " +
                "ORDER BY timestamp, horizonMinutes, id"
        ).use { statement ->
            statement.setLong(1, minTs)
            statement.setLong(2, maxTs)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    forecasts += ForecastEntity(
                        id = rows.getLong("id"),
                        timestamp = rows.getLong("timestamp"),
                        horizonMinutes = rows.getInt("horizonMinutes"),
                        valueMmol = rows.getDouble("valueMmol"),
                        ciLow = rows.getDouble("ciLow"),
                        ciHigh = rows.getDouble("ciHigh"),
                        modelVersion = rows.getString("modelVersion") ?: "unknown"
                    )
                }
            }
        }
        check(glucose.isNotEmpty()) { "No usable glucose rows loaded from ${file.path}" }
        return ReplayData(glucose.values.sortedBy { it.ts }, telemetry, therapy, forecasts)
    }

    private fun syntheticData(
        currentSensorAgeHours: Double,
        days: Int = 14,
        glucoseMmol: Double = 7.4
    ): ReplayData {
        require(days >= 14)
        val start = SYNTHETIC_NOW - (days - 1L) * DAY_MS - 9L * 60L * MINUTE_MS
        val glucose = mutableListOf<GlucosePoint>()
        val telemetry = mutableListOf<CircadianTelemetrySignal>()
        val forecasts = mutableListOf<ForecastEntity>()
        var forecastId = 1L
        var ts = start
        while (ts <= SYNTHETIC_NOW) {
            glucose += GlucosePoint(ts, glucoseMmol, "synthetic_replay", DataQuality.OK)
            listOf(30, 60).forEach { horizon ->
                forecasts += ForecastEntity(
                    id = forecastId++,
                    timestamp = ts,
                    horizonMinutes = horizon,
                    valueMmol = glucoseMmol,
                    ciLow = (glucoseMmol - 0.5).coerceAtLeast(2.2),
                    ciHigh = glucoseMmol + 0.5,
                    modelVersion = "synthetic-causal"
                )
            }
            val ageHours = if (ts == SYNTHETIC_NOW) currentSensorAgeHours else 48.0
            fun numeric(key: String, value: Double) {
                telemetry += CircadianTelemetrySignal(ts, key, valueDouble = value)
            }
            numeric("sensor_quality_score", 0.92)
            numeric("sensor_quality_blocked", 0.0)
            numeric("sensor_quality_suspect_false_low", 0.0)
            numeric("sensor_age_hours", ageHours)
            numeric("cob_effective_grams", 0.0)
            numeric("iob_units", 0.5)
            numeric("uam_runtime_control_flag", 0.0)
            numeric("target_low_risk_active", 0.0)
            numeric("target_low_risk_latched", 0.0)
            numeric("isf_factor_set_age_hours", 24.0)
            ts += FIVE_MIN_MS
        }
        return ReplayData(glucose, telemetry, emptyList(), forecasts)
    }

    private fun currentSensorTrust(data: ReplayData, timestamp: Long): SensorTrustState? {
        val quality = data.latestNumeric("sensor_quality_score", timestamp)
        val blocked = data.latestNumeric("sensor_quality_blocked", timestamp)?.let { it >= 0.5 }
        val falseLow = data.latestNumeric("sensor_quality_suspect_false_low", timestamp)?.let { it >= 0.5 }
        return when {
            blocked == true -> SensorTrustState.BLOCKED
            quality != null && quality >= 0.65 && falseLow == false -> SensorTrustState.TRUSTED
            quality != null || blocked != null || falseLow != null -> SensorTrustState.WARN
            else -> null
        }
    }

    private fun currentSensorAgeHours(data: ReplayData, timestamp: Long): Double? =
        data.latestNumeric("sensor_age_hours", timestamp)
            ?: data.latestNumeric("sensor_age_days", timestamp)?.times(24.0)

    private fun hasProtectedLow(
        cohorts: CircadianTargetCohorts,
        evaluationDate: LocalDate,
        dayType: CircadianDayType,
        hour: Int,
        threshold: Double,
        days: Long
    ): Boolean = cohorts.allContext.any { sample ->
        val ageDays = ChronoUnit.DAYS.between(sample.localDate, evaluationDate)
        val hourGap = abs(sample.hour - hour).let { min(it, HOURS_PER_DAY - it) }
        ageDays in 0 until days &&
            (dayType == CircadianDayType.ALL || sample.dayType == dayType) &&
            hourGap <= 1 && sample.sensorTrusted && sample.glucoseMmol < threshold
    }

    private fun sensorCounts(
        data: ReplayData,
        evaluationDate: LocalDate,
        dayType: CircadianDayType,
        hour: Int,
        zoneId: ZoneId
    ): Pair<Int, Int> {
        var trusted = 0
        var degraded = 0
        data.glucose.forEach { glucose ->
            val local = Instant.ofEpochMilli(glucose.ts).atZone(zoneId)
            val dateAge = ChronoUnit.DAYS.between(local.toLocalDate(), evaluationDate)
            if (dateAge !in 0 until 14L || local.hour != hour) return@forEach
            val sampleDayType = if (local.dayOfWeek.value >= 6) {
                CircadianDayType.WEEKEND
            } else {
                CircadianDayType.WEEKDAY
            }
            if (dayType != CircadianDayType.ALL && dayType != sampleDayType) return@forEach
            val trust = currentSensorTrust(data, glucose.ts)
            val ageHours = currentSensorAgeHours(data, glucose.ts)
            if (trust == SensorTrustState.TRUSTED && ageHours != null && ageHours < OLD_SENSOR_DAYS * 24.0) {
                trusted += 1
            } else {
                degraded += 1
            }
        }
        return trusted to degraded
    }

    private fun sensorAgeCohort(ageHours: Double?, trust: SensorTrustState?): String = when {
        ageHours == null -> "unknown"
        ageHours >= OLD_SENSOR_DAYS * 24.0 -> ">=14d"
        ageHours >= 12.0 * 24.0 -> "12-14d"
        trust != SensorTrustState.TRUSTED -> "degraded"
        else -> "trusted_<12d"
    }

    private fun replaySchedule(): BaseTargetSchedule = BaseTargetSchedule(
        revision = 1L,
        defaultTargetMmol = System.getenv("COPILOT_REPLAY_BASE_TARGET")
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.coerceIn(MIN_TARGET, MAX_TARGET)
            ?: 5.5,
        autoEnabled = true
    )

    private fun writeReport(report: ReplayReport) {
        val configured = System.getenv("COPILOT_REPLAY_OUT")?.trim().orEmpty()
        val outputDir = if (configured.isNotBlank()) {
            File(configured)
        } else {
            File("build/reports/circadian-target-replay")
        }.apply { mkdirs() }
        File(outputDir, "circadian-target-replay.csv").writeText(report.toCsv(), Charsets.UTF_8)
        File(outputDir, "circadian-target-replay.md").writeText(report.toMarkdown(), Charsets.UTF_8)
        println("circadian_target_replay_report=${File(outputDir, "circadian-target-replay.md").absolutePath}")
    }

    private fun parsePayload(gson: Gson, json: String): Map<String, String> {
        val raw = runCatching { gson.fromJson(json, Map::class.java) }.getOrNull() ?: return emptyMap()
        return raw.entries.mapNotNull { entry ->
            val key = entry.key?.toString()?.trim().orEmpty()
            if (key.isBlank()) null else key to (entry.value?.toString() ?: "")
        }.toMap()
    }

    private fun parseQuality(raw: String?): DataQuality = when (raw?.trim()?.uppercase(Locale.US)) {
        DataQuality.OK.name -> DataQuality.OK
        DataQuality.SENSOR_ERROR.name -> DataQuality.SENSOR_ERROR
        else -> DataQuality.STALE
    }

    private fun qualityRank(quality: DataQuality): Int = when (quality) {
        DataQuality.OK -> 3
        DataQuality.STALE -> 2
        DataQuality.SENSOR_ERROR -> 1
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class ReplayData(
        val glucose: List<GlucosePoint>,
        val telemetry: List<CircadianTelemetrySignal>,
        val therapy: List<TherapyEvent>,
        val forecasts: List<ForecastEntity>
    ) {
        private val telemetryByKey by lazy { telemetry.groupBy(CircadianTelemetrySignal::key) }

        fun window(since: Long, through: Long): ReplayData = ReplayData(
            glucose = glucose.filter { it.ts in since..through },
            telemetry = telemetry.filter { it.timestamp in since..through },
            therapy = therapy.filter { it.ts in since..through },
            forecasts = forecasts.filter { it.timestamp in since..through }
        )

        fun latestNumeric(key: String, timestamp: Long): Double? {
            val rows = telemetryByKey[key].orEmpty()
            var low = 0
            var high = rows.lastIndex
            var found = -1
            while (low <= high) {
                val mid = (low + high).ushr(1)
                if (rows[mid].timestamp <= timestamp) {
                    found = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            val row = rows.getOrNull(found) ?: return null
            if (timestamp - row.timestamp !in 0L..TELEMETRY_FRESHNESS_MS) return null
            return row.valueDouble
                ?: row.valueText?.trim()?.replace(',', '.')?.toDoubleOrNull()
        }
    }

    private data class SnapshotReplay(
        val rows: List<ReplayRow>,
        val nextPrevious: Map<CircadianTargetSlot, CircadianPreviousAdjustment>
    )

    private data class DailyReplayOutcome(
        val rows: List<ReplayRow>,
        val finalPrevious: Map<CircadianTargetSlot, CircadianPreviousAdjustment>,
        val coverage: ReplayCoverage
    )

    private data class ReplayCoverage(
        val requestedEvaluationDays: Int,
        val loadedSourceDays: Int,
        val warmupDays: Int,
        val evaluatedDays: Int,
        val firstEvaluationDate: LocalDate?,
        val lastEvaluationDate: LocalDate?
    )

    private data class ReplayRow(
        val scenario: String,
        val evaluationDate: LocalDate,
        val dayType: CircadianDayType,
        val hour: Int,
        val manualTargetMmol: Double,
        val desiredDeltaMmol: Double,
        val deltaMmol: Double,
        val effectiveTargetMmol: Double,
        val validDays: Int,
        val trustedShare: Double,
        val trustedLowCount: Int,
        val sensorAgeDays: Double?,
        val sensorAgeCohort: String,
        val trustedSensorRows: Int,
        val oldOrDegradedSensorRows: Int,
        val state: CircadianAutoState,
        val reasons: List<String>,
        val managerDecision: String,
        val protectedTrustedLowBelow4: Boolean,
        val protectedTrustedLowBelow4_4: Boolean
    ) {
        val negativeApplied: Boolean get() = deltaMmol < -EPSILON
        val deepApplied: Boolean get() = deltaMmol < -0.3 - EPSILON
    }

    private data class ManagerReplay(
        val dispatchHarnessRepositoryClass: String,
        val activeRoutingDisablesLegacyWriter: Boolean,
        val commandsPerSemanticEventMax: Int
    )

    private data class DeepGateReplay(
        val deepStepReached: Boolean,
        val lowVetoPassed: Boolean,
        val deepestDeltaMmol: Double,
        val lowVetoDeltaMmol: Double,
        val stepTrace: String
    )

    private data class ReplayReport(
        val sourceLabel: String,
        val databaseSha256: String?,
        val quickCheck: String,
        val rows: List<ReplayRow>,
        val coverage: ReplayCoverage,
        val managerReplay: ManagerReplay,
        val deepGateReplay: DeepGateReplay
    ) {
        fun toCsv(): String = buildString {
            appendLine(
                "scenario,evaluation_date,day_type,hour,manual_target,desired_delta,applied_delta," +
                    "effective_target,valid_days,trusted_share,trusted_low_count,sensor_age_days," +
                    "sensor_age_cohort,trusted_sensor_rows,old_or_degraded_sensor_rows,state,reasons," +
                    "manager_decision,protected_low_below_4,protected_low_below_4_4"
            )
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.scenario,
                        row.evaluationDate,
                        row.dayType,
                        row.hour,
                        format(row.manualTargetMmol),
                        format(row.desiredDeltaMmol),
                        format(row.deltaMmol),
                        format(row.effectiveTargetMmol),
                        row.validDays,
                        format(row.trustedShare),
                        row.trustedLowCount,
                        row.sensorAgeDays?.let(::format) ?: "",
                        row.sensorAgeCohort,
                        row.trustedSensorRows,
                        row.oldOrDegradedSensorRows,
                        row.state,
                        row.reasons.joinToString("|"),
                        row.managerDecision,
                        row.protectedTrustedLowBelow4,
                        row.protectedTrustedLowBelow4_4
                    ).joinToString(",") { csv(it.toString()) }
                )
            }
        }

        fun toMarkdown(): String = buildString {
            appendLine("# Circadian target replay")
            appendLine()
            appendLine("- Source: `$sourceLabel`")
            appendLine("- SHA-256: `${databaseSha256 ?: "synthetic"}`")
            appendLine("- PRAGMA quick_check: `$quickCheck`")
            appendLine("- Rows: ${rows.size}")
            appendLine("- Requested evaluation days: ${coverage.requestedEvaluationDays}")
            appendLine("- Loaded source days: ${coverage.loadedSourceDays}")
            appendLine("- Required warm-up days: ${coverage.warmupDays}")
            appendLine(
                "- Actually evaluated days: ${coverage.evaluatedDays} " +
                    "(${coverage.firstEvaluationDate ?: "none"}..${coverage.lastEvaluationDate ?: "none"})"
            )
            appendLine(
                "- Dispatch harness repository class: " +
                    managerReplay.dispatchHarnessRepositoryClass
            )
            appendLine(
                "- Production ACTIVE routing disables legacy writer/fallback: " +
                    managerReplay.activeRoutingDisablesLegacyWriter
            )
            appendLine("- Max commands per semantic event: ${managerReplay.commandsPerSemanticEventMax}")
            appendLine("- Synthetic deepest causal step: ${format(deepGateReplay.deepestDeltaMmol)}")
            appendLine("- Synthetic step trace: `${deepGateReplay.stepTrace}`")
            appendLine(
                "- Synthetic trusted <4.4 veto delta: ${format(deepGateReplay.lowVetoDeltaMmol)} " +
                    "(passed=${deepGateReplay.lowVetoPassed})"
            )
            appendLine("- Negative with trusted low <4.0: ${rows.count { it.negativeApplied && it.protectedTrustedLowBelow4 }}")
            appendLine("- Deep negative with trusted low <4.4: ${rows.count { it.deepApplied && it.protectedTrustedLowBelow4_4 }}")
            appendLine("- Negative while current sensor age >=14d/unknown: ${rows.count { it.negativeApplied && (it.sensorAgeDays ?: Double.POSITIVE_INFINITY) >= OLD_SENSOR_DAYS }}")
            appendLine()
            appendLine("| Date | Type | Hour | Manual | Desired | Applied | Effective | Days | Trusted | Low | Sensor | State | Manager | Reasons |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|")
            rows.forEach { row ->
                appendLine(
                    "| ${row.evaluationDate} | ${row.dayType} | ${row.hour} | " +
                        "${format(row.manualTargetMmol)} | ${format(row.desiredDeltaMmol)} | " +
                        "${format(row.deltaMmol)} | ${format(row.effectiveTargetMmol)} | " +
                        "${row.validDays} | ${format(row.trustedShare)} | ${row.trustedLowCount} | " +
                        "${row.sensorAgeCohort} (${row.trustedSensorRows}/${row.oldOrDegradedSensorRows}) | " +
                        "${row.state} | ${row.managerDecision} | ${row.reasons.joinToString("; ")} |"
                )
            }
        }

        private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

        private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private data class CopiedDatabase(
        val file: File,
        val connection: Connection,
        val quickCheck: String,
        val sha256: String
    )

    private class ReplayTargetManagerDao : TargetManagerDao {
        override suspend fun invalidateTargetManagerLiveStatus(): Int = 0

        private val states = mutableMapOf<String, TargetManagerStateEntity>()
        private val decisions = mutableListOf<TargetManagerDecisionEntity>()

        override suspend fun state(mode: String): TargetManagerStateEntity? = states[mode]

        override suspend fun upsertState(state: TargetManagerStateEntity) {
            states[state.mode] = state
        }

        override suspend fun insertDecision(decision: TargetManagerDecisionEntity): Long {
            if (decisions.any {
                    it.mode == decision.mode && it.semanticFingerprint == decision.semanticFingerprint
                }
            ) return -1L
            decisions += decision
            return decisions.size.toLong()
        }

        override suspend fun updateDecision(decision: TargetManagerDecisionEntity): Int {
            val index = decisions.indexOfFirst { it.id == decision.id }
            if (index < 0) return 0
            decisions[index] = decision
            return 1
        }

        override suspend fun decisionByFingerprint(
            mode: String,
            semanticFingerprint: String
        ): TargetManagerDecisionEntity? = decisions.firstOrNull {
            it.mode == mode && it.semanticFingerprint == semanticFingerprint
        }

        override suspend fun pendingDecisions(): List<TargetManagerDecisionEntity> =
            decisions.filter { it.deliveryStatus == "pending" }

        override suspend fun latestDecisions(limit: Int): List<TargetManagerDecisionEntity> =
            decisions.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun latestQuarantinedDecision(mode: String): TargetManagerDecisionEntity? =
            decisions.filter { it.mode == mode && it.deliveryStatus == "quarantined" }
                .sortedWith(compareByDescending<TargetManagerDecisionEntity> { it.timestamp }.thenByDescending { it.id })
                .firstOrNull()

        override suspend fun between(
            fromTs: Long,
            throughTs: Long
        ): List<TargetManagerDecisionEntity> = decisions.filter { it.timestamp in fromTs..throughTs }

        override suspend fun clinicalEvidenceBetween(
            fromTs: Long,
            throughTs: Long,
            limit: Int
        ): List<ClinicalTargetManagerEvidenceProjection> = decisions
            .filter { it.timestamp in fromTs..throughTs }
            .sortedWith(compareBy<TargetManagerDecisionEntity> { it.timestamp }.thenBy { it.id })
            .take(limit)
            .map {
                ClinicalTargetManagerEvidenceProjection(
                    it.id,
                    it.timestamp,
                    it.outcome,
                    it.winnerJson,
                    it.reasonCodesJson
                )
            }

        override suspend fun latestReadOnlyDiagnosticTimestamp(): Long? = null

        override suspend fun deleteReadOnlyDiagnosticsOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteReadOnlyDiagnosticsBeyondLimit(maxRows: Int): Int = 0
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FIVE_MIN_MS = 5L * MINUTE_MS
        const val DAY_MS = 24L * 60L * MINUTE_MS
        const val LOOKBACK_MS = 14L * DAY_MS
        const val REQUESTED_EVALUATION_DAYS = 30
        const val REPLAY_SOURCE_MS = 44L * DAY_MS
        const val TELEMETRY_FRESHNESS_MS = 15L * MINUTE_MS
        const val HOURS_PER_DAY = 24
        const val MIN_DELTA = -1.0
        const val MAX_DELTA = 0.6
        const val MIN_TARGET = 4.0
        const val MAX_TARGET = 10.0
        const val OLD_SENSOR_DAYS = 14.0
        const val EPSILON = 1e-9
        val SYNTHETIC_NOW: Long = LocalDateTime.of(2026, 7, 20, 9, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
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
    }
}
