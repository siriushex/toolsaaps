package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.remote.cloud.CloudGlucosePoint
import io.aaps.copilot.data.remote.cloud.CloudTherapyEvent
import io.aaps.copilot.data.remote.cloud.PredictRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamCalculator
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.RuleRuntimeConfig
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import io.aaps.copilot.service.ApiFactory
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

class AutomationRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val syncRepository: SyncRepository,
    private val exportRepository: AapsExportRepository,
    private val autoConnectRepository: AapsAutoConnectRepository,
    private val rootDbRepository: RootDbExperimentalRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val actionRepository: NightscoutActionRepository,
    private val predictionEngine: PredictionEngine,
    private val ruleEngine: RuleEngine,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    private val cycleMutex = Mutex()

    data class DryRunRuleSummary(
        val ruleId: String,
        val triggered: Int,
        val blocked: Int,
        val noMatch: Int
    )

    data class DryRunReport(
        val periodDays: Int,
        val samplePoints: Int,
        val rules: List<DryRunRuleSummary>
    )

    private data class CalculatedUamSnapshot(
        val flag: Double,
        val confidence: Double,
        val estimatedCarbsGrams: Double?,
        val rise15Mmol: Double?,
        val rise30Mmol: Double?,
        val delta5Mmol: Double?
    )

    data class ForecastCalibrationPoint(
        val horizonMinutes: Int,
        val errorMmol: Double,
        val ageMs: Long
    )

    suspend fun runAutomationCycle() {
        if (!cycleMutex.tryLock()) {
            auditLogger.info("automation_cycle_skipped", mapOf("reason" to "already_running"))
            return
        }
        try {
            runAutomationCycleLocked()
        } finally {
            cycleMutex.unlock()
        }
    }

    private suspend fun runAutomationCycleLocked() {
        autoConnectRepository.bootstrap()
        val settings = settingsStore.settings.first()
        configurePredictionEngine(settings)
        rootDbRepository.syncIfEnabled()
        syncRepository.syncNightscoutIncremental()
        syncRepository.pushCloudIncremental()
        exportRepository.importBaselineFromExports()
        analyticsRepository.recalculate(settings)
        val removedInvalidTelemetryTs = db.telemetryDao().deleteByTimestampAtOrBelow(0L)
        if (removedInvalidTelemetryTs > 0) {
            auditLogger.info(
                "telemetry_invalid_timestamp_cleanup",
                mapOf("removedRows" to removedInvalidTelemetryTs)
            )
        }

        val glucose = syncRepository.recentGlucose(limit = 72)
        if (glucose.isEmpty()) {
            auditLogger.warn("automation_skipped", mapOf("reason" to "no_glucose_data"))
            return
        }

        val therapy = syncRepository.recentTherapyEvents(hoursBack = 24)
        val now = System.currentTimeMillis()
        val latestTelemetry = resolveLatestTelemetry(now).toMutableMap()
        val effectiveBaseTarget = resolveEffectiveBaseTarget(settings.baseTargetMmol, latestTelemetry)
        val localForecasts = predictionEngine.predict(glucose, therapy)
        val mergedForecastsRaw = ensureForecast30(
            maybeMergeCloudPrediction(glucose, therapy, localForecasts)
        )
        val calibrationErrors = collectForecastCalibrationErrors(nowTs = now)
        val mergedForecastsCalibrated = applyRecentForecastCalibrationBias(
            forecasts = mergedForecastsRaw,
            history = calibrationErrors
        )
        if (mergedForecastsCalibrated != mergedForecastsRaw) {
            auditLogger.info(
                "forecast_calibration_bias_applied",
                buildCalibrationAuditMeta(
                    source = mergedForecastsRaw,
                    adjusted = mergedForecastsCalibrated
                )
            )
        }
        val mergedForecasts = applyCobIobForecastBias(
            forecasts = mergedForecastsCalibrated,
            cobGrams = latestTelemetry["cob_grams"],
            iobUnits = latestTelemetry["iob_units"]
        )
        if (mergedForecasts != mergedForecastsCalibrated) {
            auditLogger.info(
                "forecast_bias_applied",
                mapOf(
                    "cobGrams" to latestTelemetry["cob_grams"],
                    "iobUnits" to latestTelemetry["iob_units"]
                )
            )
        }

        db.forecastDao().insertAll(mergedForecasts.map { it.toForecastEntity() })
        db.forecastDao().deleteOlderThan(System.currentTimeMillis() - FORECAST_RETENTION_MS)

        val latest = glucose.maxBy { it.ts }
        val effectiveStaleMaxMinutes = resolveEffectiveStaleMaxMinutes(settings)
        val dataFresh = now - latest.ts <= effectiveStaleMaxMinutes * 60 * 1000L
        val actionsLast6h = actionRepository.countSentActionsLast6h()
        val activeTempTarget = resolveActiveTempTarget(now)
        val sensorBlocked = isSensorBlocked(therapy, now)

        val zoned = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
        val currentPattern = db.patternDao().byDayAndHour(dayType.name, zoned.hour)?.let {
            io.aaps.copilot.domain.model.PatternWindow(
                dayType = io.aaps.copilot.domain.model.DayType.valueOf(it.dayType),
                hour = it.hour,
                sampleCount = it.sampleCount,
                activeDays = it.activeDays,
                lowRate = it.lowRate,
                highRate = it.highRate,
                recommendedTargetMmol = it.recommendedTargetMmol,
                isRiskWindow = it.isRiskWindow
            )
        }
        val currentProfile = db.profileEstimateDao().active()?.toProfileEstimate()
        val currentSlot = resolveTimeSlot(zoned.hour)
        val currentSegment = db.profileSegmentEstimateDao()
            .byDayTypeAndTimeSlot(dayType.name, currentSlot.name)
            ?.toProfileSegmentEstimate()
        val calculatedUam = calculateCalculatedUamSnapshot(
            glucose = glucose,
            therapy = therapy,
            profile = currentProfile,
            nowTs = now
        )
        latestTelemetry["uam_value"] = calculatedUam.flag
        latestTelemetry["uam_calculated_flag"] = calculatedUam.flag
        latestTelemetry["uam_calculated_confidence"] = calculatedUam.confidence
        calculatedUam.estimatedCarbsGrams?.let { latestTelemetry["uam_calculated_carbs_grams"] = it }
        persistCalculatedUamTelemetry(nowTs = now, snapshot = calculatedUam)

        val context = RuleContext(
            nowTs = now,
            glucose = glucose,
            therapyEvents = therapy,
            forecasts = mergedForecasts,
            currentDayPattern = currentPattern,
            baseTargetMmol = effectiveBaseTarget,
            postHypoThresholdMmol = settings.postHypoThresholdMmol,
            postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
            postHypoTargetMmol = settings.postHypoTargetMmol,
            postHypoDurationMinutes = settings.postHypoDurationMinutes,
            postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
            dataFresh = dataFresh,
            activeTempTargetMmol = activeTempTarget,
            actionsLast6h = actionsLast6h,
            sensorBlocked = sensorBlocked,
            currentProfileEstimate = currentProfile,
            currentProfileSegment = currentSegment,
            latestTelemetry = latestTelemetry,
            retargetCooldownMinutes = settings.adaptiveControllerRetargetMinutes,
            adaptiveMaxStepMmol = settings.adaptiveControllerMaxStepMmol
        )

        val decisions = ruleEngine.evaluate(
            context = context,
            config = SafetyPolicyConfig(
                killSwitch = settings.killSwitch,
                maxActionsIn6Hours = resolveEffectiveMaxActions6h(settings)
            ),
            runtimeConfig = runtimeConfig(settings)
        )

        val effectiveDecisions = mutableListOf<RuleDecision>()
        var adaptiveTriggeredThisCycle = false
        for (decision in decisions) {
            val effectiveDecision = if (decision.state == RuleState.TRIGGERED && decision.actionProposal != null) {
                val cooldownMinutes = ruleCooldownMinutes(decision.ruleId, settings)
                if (cooldownMinutes > 0 && isRuleInCooldown(decision.ruleId, now, cooldownMinutes)) {
                    val cooldownReason = if (decision.ruleId == AdaptiveTargetControllerRule.RULE_ID) {
                        "retarget_cooldown_${cooldownMinutes}m"
                    } else {
                        "rule_cooldown_active:${cooldownMinutes}m"
                    }
                    decision.copy(
                        state = RuleState.BLOCKED,
                        reasons = decision.reasons + cooldownReason,
                        actionProposal = null
                    )
                } else {
                    decision
                }
            } else {
                decision
            }
            effectiveDecisions += effectiveDecision

            db.ruleExecutionDao().insert(
                RuleExecutionEntity(
                    timestamp = now,
                    ruleId = effectiveDecision.ruleId,
                    state = effectiveDecision.state.name,
                    reasonsJson = gson.toJson(effectiveDecision.reasons),
                    actionJson = effectiveDecision.actionProposal?.let { gson.toJson(it) }
                )
            )

            if (effectiveDecision.state == RuleState.TRIGGERED && effectiveDecision.actionProposal != null) {
                if (effectiveDecision.ruleId == AdaptiveTargetControllerRule.RULE_ID &&
                    effectiveDecision.actionProposal.type.equals("temp_target", ignoreCase = true)
                ) {
                    adaptiveTriggeredThisCycle = true
                }
                val idempotencyKey = buildIdempotencyKey(effectiveDecision.ruleId, now, settings)
                val normalizedAction = alignTempTargetToBaseTarget(
                    action = effectiveDecision.actionProposal,
                    forecasts = mergedForecasts,
                    baseTargetMmol = effectiveBaseTarget,
                    sourceRuleId = effectiveDecision.ruleId
                )
                val command = ActionCommand(
                    id = UUID.randomUUID().toString(),
                    type = normalizedAction.type,
                    params = mapOf(
                        "targetMmol" to normalizedAction.targetMmol.toString(),
                        "durationMinutes" to normalizedAction.durationMinutes.toString(),
                        "reason" to normalizedAction.reason
                    ),
                    safetySnapshot = SafetySnapshot(
                        killSwitch = settings.killSwitch,
                        dataFresh = dataFresh,
                        activeTempTargetMmol = activeTempTarget,
                        actionsLast6h = actionsLast6h
                    ),
                    idempotencyKey = idempotencyKey
                )

                when (command.type.lowercase()) {
                    "temp_target" -> actionRepository.submitTempTarget(command)
                    "carbs" -> actionRepository.submitCarbs(command)
                    else -> auditLogger.warn(
                        "automation_action_skipped",
                        mapOf("reason" to "unsupported_action_type", "type" to command.type)
                    )
                }
            }
        }

        if (!adaptiveTriggeredThisCycle) {
            maybeSendAdaptiveKeepaliveTempTarget(
                settings = settings,
                nowTs = now,
                dataFresh = dataFresh,
                sensorBlocked = sensorBlocked,
                activeTempTarget = activeTempTarget,
                actionsLast6h = actionsLast6h,
                forecasts = mergedForecasts,
                baseTargetMmol = effectiveBaseTarget
            )
        }

        auditAdaptiveController(effectiveDecisions, context, settings, mergedForecasts)

        auditLogger.info(
            "automation_cycle_completed",
            mapOf(
                "glucosePoints" to glucose.size,
                "therapyEvents" to therapy.size,
                "forecasts" to mergedForecasts.size,
                "decisions" to decisions.size,
                "staleMaxMin" to effectiveStaleMaxMinutes,
                "actionsLimit6h" to resolveEffectiveMaxActions6h(settings)
            )
        )
    }

    private fun alignTempTargetToBaseTarget(
        action: ActionProposal,
        forecasts: List<Forecast>,
        baseTargetMmol: Double,
        sourceRuleId: String? = null
    ): ActionProposal {
        if (!action.type.equals("temp_target", ignoreCase = true)) return action
        val boundedBase = baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val boundedProposed = action.targetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        if (shouldSkipBaseAlignmentStatic(sourceRuleId = sourceRuleId, actionReason = action.reason)) {
            return action.copy(targetMmol = boundedProposed)
        }
        val forecast60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
            ?: return action.copy(targetMmol = boundedProposed)

        val driftVsBase = forecast60 - boundedBase
        if (abs(driftVsBase) < 0.15) {
            return action.copy(targetMmol = boundedProposed)
        }

        // Move temp target so 1h trajectory drifts toward base target.
        val correction = (-driftVsBase * ALIGN_GAIN).coerceIn(-MAX_ALIGN_STEP_MMOL, MAX_ALIGN_STEP_MMOL)
        val aligned = roundToStep((boundedProposed + correction).coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL), 0.05)
        val reason = if (action.reason.contains("base_align_60m")) action.reason else "${action.reason}|base_align_60m"
        return action.copy(targetMmol = aligned, reason = reason)
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val scaled = value / step
        return floor(scaled + 0.5) * step
    }

    private fun ensureForecast30(forecasts: List<Forecast>): List<Forecast> {
        val has30 = forecasts.any { it.horizonMinutes == 30 }
        if (has30) return forecasts.sortedBy { it.horizonMinutes }

        val f5 = forecasts.firstOrNull { it.horizonMinutes == 5 }?.valueMmol
        val f60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
        if (f5 == null || f60 == null) return forecasts
        val baseTs = forecasts.maxOfOrNull { it.ts } ?: System.currentTimeMillis()
        val f30 = 0.55 * f5 + 0.45 * f60
        val synthetic = Forecast(
            ts = baseTs + 30 * 60_000L,
            horizonMinutes = 30,
            valueMmol = f30,
            ciLow = (f30 - 0.8).coerceAtLeast(2.2),
            ciHigh = f30 + 0.8,
            modelVersion = "local-interpolated-30m-v1"
        )
        return (forecasts + synthetic).sortedBy { it.horizonMinutes }
    }

    private suspend fun collectForecastCalibrationErrors(nowTs: Long): List<ForecastCalibrationPoint> {
        val forecastHistory = db.forecastDao().latest(CALIBRATION_FORECAST_LIMIT)
        if (forecastHistory.isEmpty()) return emptyList()
        val glucoseHistory = db.glucoseDao().latest(CALIBRATION_GLUCOSE_LIMIT).sortedBy { it.timestamp }
        if (glucoseHistory.isEmpty()) return emptyList()

        return forecastHistory.asSequence()
            .filter { row ->
                val age = nowTs - row.timestamp
                age in CALIBRATION_MIN_AGE_MS..CALIBRATION_LOOKBACK_MS
            }
            .mapNotNull { row ->
                val nearest = nearestGlucoseAt(
                    targetTs = row.timestamp,
                    sorted = glucoseHistory,
                    toleranceMs = CALIBRATION_MATCH_TOLERANCE_MS
                ) ?: return@mapNotNull null
                ForecastCalibrationPoint(
                    horizonMinutes = row.horizonMinutes,
                    errorMmol = nearest.mmol - row.valueMmol,
                    ageMs = nowTs - row.timestamp
                )
            }
            .toList()
    }

    private fun nearestGlucoseAt(
        targetTs: Long,
        sorted: List<GlucoseSampleEntity>,
        toleranceMs: Long
    ): GlucoseSampleEntity? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val midTs = sorted[mid].timestamp
            when {
                midTs < targetTs -> lo = mid + 1
                midTs > targetTs -> hi = mid - 1
                else -> return sorted[mid]
            }
        }

        val right = sorted.getOrNull(lo)
        val left = sorted.getOrNull(lo - 1)
        val rightDiff = right?.let { abs(it.timestamp - targetTs) } ?: Long.MAX_VALUE
        val leftDiff = left?.let { abs(it.timestamp - targetTs) } ?: Long.MAX_VALUE
        val best = if (rightDiff < leftDiff) right else left
        return best?.takeIf { abs(it.timestamp - targetTs) <= toleranceMs }
    }

    private fun applyRecentForecastCalibrationBias(
        forecasts: List<Forecast>,
        history: List<ForecastCalibrationPoint>
    ): List<Forecast> {
        return applyRecentForecastCalibrationBiasStatic(
            forecasts = forecasts,
            history = history
        )
    }

    private fun buildCalibrationAuditMeta(
        source: List<Forecast>,
        adjusted: List<Forecast>
    ): Map<String, Any> {
        val byH = source.associateBy { it.horizonMinutes }
        val shifts = adjusted.associate { forecast ->
            val src = byH[forecast.horizonMinutes]
            val delta = if (src == null) 0.0 else forecast.valueMmol - src.valueMmol
            "h${forecast.horizonMinutes}" to roundToStep(delta, 0.001)
        }
        return shifts + mapOf("model" to "recent_calibration_v1")
    }

    private fun applyCobIobForecastBias(
        forecasts: List<Forecast>,
        cobGrams: Double?,
        iobUnits: Double?
    ): List<Forecast> {
        return applyCobIobForecastBiasStatic(
            forecasts = forecasts,
            cobGrams = cobGrams,
            iobUnits = iobUnits
        )
    }

    private fun resolveEffectiveBaseTarget(
        configuredBaseTargetMmol: Double,
        telemetry: Map<String, Double?>
    ): Double {
        val base = configuredBaseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val cob = telemetry["cob_grams"]?.coerceIn(0.0, 400.0) ?: return base
        if (cob < COB_FORCE_BASE_THRESHOLD_G) return base
        return COB_FORCE_BASE_TARGET_MMOL.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
    }

    private suspend fun resolveLatestTelemetry(nowTs: Long): Map<String, Double?> {
        val rows = db.telemetryDao().since(nowTs - TELEMETRY_LOOKBACK_MS)
        if (rows.isEmpty()) return emptyMap()
        val todayStart = Instant.ofEpochMilli(nowTs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val latestByKey = rows
            .filter { it.valueDouble != null && telemetryValueUsable(it.key, it.valueDouble) }
            .groupBy { it.key }
            .mapValues { (key, values) ->
                if (key in CUMULATIVE_ACTIVITY_KEYS) {
                    val dayValues = values.filter { it.timestamp >= todayStart }
                    val sourceValues = if (dayValues.isNotEmpty()) dayValues else values
                    sourceValues.maxWithOrNull(
                        compareBy<TelemetrySampleEntity> { it.valueDouble ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it.timestamp }
                    )?.valueDouble
                } else {
                    values.maxByOrNull { it.timestamp }?.valueDouble
                }
            }
            .toMutableMap()

        fun alias(targetKey: String, tokenAliases: List<String>) {
            if (latestByKey[targetKey] != null) return
            val fallback = latestByKey.entries.firstOrNull { (key, value) ->
                value != null && tokenAliases.any { alias -> telemetryKeyContainsAlias(key, alias) }
            }?.value
            latestByKey[targetKey] = fallback
        }

        alias("iob_units", listOf("iob", "insulinonboard"))
        alias("cob_grams", listOf("cob", "carbsonboard"))
        alias("activity_ratio", listOf("activity", "activityratio", "sensitivityratio"))
        alias("uam_value", listOf("uam_calculated_flag", "enable_uam", "uam_detected", "unannounced_meal", "has_uam", "is_uam"))
        return latestByKey
    }

    private fun telemetryValueUsable(key: String, value: Double?): Boolean {
        if (value == null) return false
        val normalizedKey = normalizeTelemetryKey(key)
        if (normalizedKey == "uam_value") {
            return value in 0.0..1.5
        }
        return true
    }

    private fun telemetryKeyContainsAlias(key: String, alias: String): Boolean {
        val normalizedAlias = normalizeTelemetryKey(alias)
        if (normalizedAlias.isBlank()) return false
        val normalizedKey = normalizeTelemetryKey(key)
        if (normalizedKey == normalizedAlias || normalizedKey.endsWith("_$normalizedAlias")) return true
        return normalizedKey.split('_').any { it == normalizedAlias }
    }

    private fun normalizeTelemetryKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun configurePredictionEngine(settings: AppSettings) {
        val profileId = InsulinActionProfileId.fromRaw(settings.insulinProfileId)
        (predictionEngine as? HybridPredictionEngine)?.setInsulinProfile(profileId)
    }

    private fun resolveEffectiveStaleMaxMinutes(settings: AppSettings): Int {
        val global = settings.staleDataMaxMinutes
        if (!settings.adaptiveControllerEnabled) return global
        val profileLimit = when (settings.adaptiveControllerSafetyProfile.uppercase(Locale.US)) {
            "STRICT" -> 10
            "AGGRESSIVE" -> 20
            else -> 15
        }
        val adaptiveLimit = min(settings.adaptiveControllerStaleMaxMinutes, profileLimit)
        return min(global, adaptiveLimit)
    }

    private fun resolveEffectiveMaxActions6h(settings: AppSettings): Int {
        val global = settings.maxActionsIn6Hours
        if (!settings.adaptiveControllerEnabled) return global
        val profileLimit = when (settings.adaptiveControllerSafetyProfile.uppercase(Locale.US)) {
            "STRICT" -> 3
            "AGGRESSIVE" -> 6
            else -> 4
        }
        val adaptiveLimit = min(settings.adaptiveControllerMaxActions6h, profileLimit)
        return min(global, adaptiveLimit)
    }

    private fun buildIdempotencyKey(ruleId: String, nowTs: Long, settings: AppSettings): String {
        val bucketMinutes = if (ruleId == AdaptiveTargetControllerRule.RULE_ID) {
            settings.adaptiveControllerRetargetMinutes.coerceIn(5, 30)
        } else {
            30
        }
        return "$ruleId:${nowTs / (bucketMinutes * 60_000L)}"
    }

    private suspend fun auditAdaptiveController(
        decisions: List<RuleDecision>,
        context: RuleContext,
        settings: AppSettings,
        forecasts: List<Forecast>
    ) {
        val adaptive = decisions.firstOrNull { it.ruleId == AdaptiveTargetControllerRule.RULE_ID } ?: return
        val f30 = forecasts.firstOrNull { it.horizonMinutes == 30 }?.valueMmol
        val f60 = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol
        val weightedError = if (f30 != null && f60 != null) {
            val e30 = f30 - context.baseTargetMmol
            val e60 = f60 - context.baseTargetMmol
            0.65 * e30 + 0.35 * e60
        } else {
            null
        }

        val metadata = linkedMapOf<String, Any?>(
            "state" to adaptive.state.name,
            "reasons" to adaptive.reasons,
            "target" to adaptive.actionProposal?.targetMmol,
            "duration" to adaptive.actionProposal?.durationMinutes,
            "f30" to f30,
            "f60" to f60,
            "weightedError" to weightedError,
            "dataFresh" to context.dataFresh,
            "actionsLast6h" to context.actionsLast6h,
            "adaptiveEnabled" to settings.adaptiveControllerEnabled,
            "retargetMinutes" to settings.adaptiveControllerRetargetMinutes
        )
        auditLogger.info("adaptive_controller_evaluated", metadata)

        when (adaptive.state) {
            RuleState.TRIGGERED -> auditLogger.info("adaptive_controller_triggered", metadata)
            RuleState.BLOCKED -> auditLogger.warn("adaptive_controller_blocked", metadata)
            RuleState.NO_MATCH -> Unit
        }

        val fallbackTriggered = decisions.firstOrNull {
            it.ruleId != AdaptiveTargetControllerRule.RULE_ID && it.state == RuleState.TRIGGERED
        }
        if (adaptive.state != RuleState.TRIGGERED && fallbackTriggered != null) {
            auditLogger.info(
                "adaptive_controller_fallback_to_rules",
                mapOf(
                    "adaptiveState" to adaptive.state.name,
                    "fallbackRuleId" to fallbackTriggered.ruleId,
                    "fallbackTarget" to fallbackTriggered.actionProposal?.targetMmol
                )
            )
        }
    }

    private fun calculateCalculatedUamSnapshot(
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        profile: ProfileEstimate?,
        nowTs: Long
    ): CalculatedUamSnapshot {
        val signal = UamCalculator.latestSignal(
            glucose = glucose,
            therapyEvents = therapy,
            nowTs = nowTs,
            lookbackMinutes = CALCULATED_UAM_LOOKBACK_MINUTES
        )
        val carbs = UamCalculator.estimateCarbsGrams(
            signal = signal,
            isfMmolPerUnit = profile?.isfMmolPerUnit,
            crGramPerUnit = profile?.crGramPerUnit
        ).takeIf { it > 0.0 }
        return CalculatedUamSnapshot(
            flag = if (signal != null) 1.0 else 0.0,
            confidence = signal?.confidence ?: 0.0,
            estimatedCarbsGrams = carbs,
            rise15Mmol = signal?.rise15Mmol,
            rise30Mmol = signal?.rise30Mmol,
            delta5Mmol = signal?.delta5Mmol
        )
    }

    private suspend fun persistCalculatedUamTelemetry(nowTs: Long, snapshot: CalculatedUamSnapshot) {
        val source = "copilot_calculated"
        val rows = mutableListOf<TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            val numeric = value ?: return
            rows += TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = numeric,
                valueText = null,
                unit = unit,
                quality = "OK"
            )
        }

        addNumeric("uam_calculated_flag", snapshot.flag)
        addNumeric("uam_calculated_confidence", snapshot.confidence)
        addNumeric("uam_calculated_carbs_grams", snapshot.estimatedCarbsGrams ?: 0.0, "g")
        addNumeric("uam_calculated_rise15_mmol", snapshot.rise15Mmol ?: 0.0, "mmol/L")
        addNumeric("uam_calculated_rise30_mmol", snapshot.rise30Mmol ?: 0.0, "mmol/L")
        addNumeric("uam_calculated_delta5_mmol", snapshot.delta5Mmol ?: 0.0, "mmol/5m")
        if (rows.isNotEmpty()) {
            db.telemetryDao().upsertAll(rows)
        }
    }

    suspend fun runDryRunSimulation(days: Int): DryRunReport {
        val settings = settingsStore.settings.first()
        configurePredictionEngine(settings)
        val periodDays = days.coerceIn(1, 60)
        val startTs = System.currentTimeMillis() - periodDays * 24L * 60 * 60 * 1000

        val glucose = db.glucoseDao().since(startTs).map { it.toGlucosePoint() }.sortedBy { it.ts }
        val therapy = db.therapyDao().since(startTs).map { it.toTherapyEvent(gson) }.sortedBy { it.ts }
        val patterns = db.patternDao().all()
        val profile = db.profileEstimateDao().active()?.toProfileEstimate()
        val segments = db.profileSegmentEstimateDao().all().associateBy { it.dayType to it.timeSlot }

        if (glucose.size < 8) {
            return DryRunReport(periodDays, glucose.size, emptyList())
        }

        val counters = mutableMapOf<String, Triple<Int, Int, Int>>()
        val lastTriggeredTsByRule = mutableMapOf<String, Long>()
        val runtimeConfig = runtimeConfig(settings)

        for (index in 7 until glucose.size step 2) {
            val point = glucose[index]
            val pointTs = point.ts
            val windowStart = pointTs - 6L * 60 * 60 * 1000
            val therapyStart = pointTs - 24L * 60 * 60 * 1000
            val gWindow = glucose.filter { it.ts in windowStart..pointTs }
            val tWindow = therapy.filter { it.ts in therapyStart..pointTs }
            val forecasts = ensureForecast30(predictionEngine.predict(gWindow, tWindow))

            val zoned = Instant.ofEpochMilli(pointTs).atZone(ZoneId.systemDefault())
            val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
            val pattern = patterns.firstOrNull {
                it.dayType == dayType.name && it.hour == zoned.hour
            }?.let {
                io.aaps.copilot.domain.model.PatternWindow(
                    dayType = dayType,
                    hour = it.hour,
                    sampleCount = it.sampleCount,
                    activeDays = it.activeDays,
                    lowRate = it.lowRate,
                    highRate = it.highRate,
                    recommendedTargetMmol = it.recommendedTargetMmol,
                    isRiskWindow = it.isRiskWindow
                )
            }
            val segmentSlot = resolveTimeSlot(zoned.hour)
            val segment = segments[dayType.name to segmentSlot.name]?.toProfileSegmentEstimate()

            val context = RuleContext(
                nowTs = pointTs,
                glucose = gWindow,
                therapyEvents = tWindow,
                forecasts = forecasts,
                currentDayPattern = pattern,
                baseTargetMmol = settings.baseTargetMmol,
                postHypoThresholdMmol = settings.postHypoThresholdMmol,
                postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
                postHypoTargetMmol = settings.postHypoTargetMmol,
                postHypoDurationMinutes = settings.postHypoDurationMinutes,
                postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = isSensorBlocked(tWindow, pointTs),
                currentProfileEstimate = profile,
                currentProfileSegment = segment,
                adaptiveMaxStepMmol = settings.adaptiveControllerMaxStepMmol
            )

            val decisions = ruleEngine.evaluate(
                context = context,
                config = SafetyPolicyConfig(
                    killSwitch = false,
                    maxActionsIn6Hours = resolveEffectiveMaxActions6h(settings)
                ),
                runtimeConfig = runtimeConfig
            )

            decisions.forEach { decision ->
                val effectiveDecision = if (decision.state == RuleState.TRIGGERED && decision.actionProposal != null) {
                    val cooldown = ruleCooldownMinutes(decision.ruleId, settings)
                    val lastTs = lastTriggeredTsByRule[decision.ruleId]
                    if (cooldown > 0 && lastTs != null && (pointTs - lastTs) < cooldown * 60_000L) {
                        decision.copy(
                            state = RuleState.BLOCKED,
                            reasons = decision.reasons + "rule_cooldown_active:${cooldown}m",
                            actionProposal = null
                        )
                    } else {
                        lastTriggeredTsByRule[decision.ruleId] = pointTs
                        decision
                    }
                } else {
                    decision
                }

                val current = counters[effectiveDecision.ruleId] ?: Triple(0, 0, 0)
                counters[effectiveDecision.ruleId] = when (effectiveDecision.state) {
                    RuleState.TRIGGERED -> Triple(current.first + 1, current.second, current.third)
                    RuleState.BLOCKED -> Triple(current.first, current.second + 1, current.third)
                    RuleState.NO_MATCH -> Triple(current.first, current.second, current.third + 1)
                }
            }
        }

        return DryRunReport(
            periodDays = periodDays,
            samplePoints = glucose.size,
            rules = counters.map { (ruleId, triple) ->
                DryRunRuleSummary(
                    ruleId = ruleId,
                    triggered = triple.first,
                    blocked = triple.second,
                    noMatch = triple.third
                )
            }.sortedBy { it.ruleId }
        )
    }

    private suspend fun maybeMergeCloudPrediction(
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        localForecasts: List<io.aaps.copilot.domain.model.Forecast>
    ): List<io.aaps.copilot.domain.model.Forecast> {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) return localForecasts

        return runCatching {
            val cloudApi = apiFactory.cloudApi(settings)
            val response = cloudApi.predict(
                PredictRequest(
                    glucose = glucose.map {
                        CloudGlucosePoint(
                            ts = it.ts,
                            valueMmol = it.valueMmol,
                            source = it.source,
                            quality = it.quality.name
                        )
                    },
                    therapyEvents = therapy.map {
                        CloudTherapyEvent(
                            id = "local-${it.ts}-${it.type}",
                            ts = it.ts,
                            type = it.type,
                            payload = it.payload
                        )
                    }
                )
            )
            val cloud = response.forecasts.map {
                io.aaps.copilot.domain.model.Forecast(
                    ts = it.ts,
                    horizonMinutes = it.horizon,
                    valueMmol = it.valueMmol,
                    ciLow = it.ciLow,
                    ciHigh = it.ciHigh,
                    modelVersion = it.modelVersion
                )
            }
            mergeForecasts(localForecasts, cloud)
        }.onFailure {
            auditLogger.warn("cloud_predict_failed", mapOf("error" to (it.message ?: "unknown")))
        }.getOrDefault(localForecasts)
    }

    private fun mergeForecasts(
        local: List<io.aaps.copilot.domain.model.Forecast>,
        cloud: List<io.aaps.copilot.domain.model.Forecast>
    ): List<io.aaps.copilot.domain.model.Forecast> {
        if (cloud.isEmpty()) return local
        val byHorizon = local.associateBy { it.horizonMinutes }.toMutableMap()
        cloud.forEach { byHorizon[it.horizonMinutes] = it }
        return byHorizon.values.sortedBy { it.horizonMinutes }
    }

    private suspend fun resolveActiveTempTarget(now: Long): Double? {
        val since = now - 3L * 60 * 60 * 1000
        val recentTargets = db.therapyDao().byTypeSince("temp_target", since)
        val active = recentTargets.lastOrNull() ?: return null
        val payload = runCatching {
            gson.fromJson(active.payloadJson, MutableMap::class.java)
        }.getOrNull() ?: return null
        val target = payload["targetBottom"]?.toString()?.toDoubleOrNull()
        val duration = payload["duration"]?.toString()?.toIntOrNull() ?: 0
        val activeUntil = active.timestamp + duration * 60_000L
        return target?.takeIf { now <= activeUntil }
    }

    private fun runtimeConfig(settings: AppSettings): RuleRuntimeConfig {
        val enabled = buildSet {
            add(AdaptiveTargetControllerRule.RULE_ID)
            if (settings.rulePostHypoEnabled) add("PostHypoReboundGuard.v1")
            if (settings.rulePatternEnabled) add("PatternAdaptiveTarget.v1")
            if (settings.ruleSegmentEnabled) add("SegmentProfileGuard.v1")
        }
        val priorities = mapOf(
            AdaptiveTargetControllerRule.RULE_ID to settings.adaptiveControllerPriority,
            "PostHypoReboundGuard.v1" to settings.rulePostHypoPriority,
            "PatternAdaptiveTarget.v1" to settings.rulePatternPriority,
            "SegmentProfileGuard.v1" to settings.ruleSegmentPriority
        )
        return RuleRuntimeConfig(enabledRuleIds = enabled, priorities = priorities)
    }

    private suspend fun maybeSendAdaptiveKeepaliveTempTarget(
        settings: AppSettings,
        nowTs: Long,
        dataFresh: Boolean,
        sensorBlocked: Boolean,
        activeTempTarget: Double?,
        actionsLast6h: Int,
        forecasts: List<Forecast>,
        baseTargetMmol: Double
    ) {
        if (settings.killSwitch) {
            auditLogger.info("adaptive_keepalive_skipped", mapOf("reason" to "kill_switch"))
            return
        }
        if (!dataFresh) {
            auditLogger.info("adaptive_keepalive_skipped", mapOf("reason" to "stale_data"))
            return
        }
        if (sensorBlocked) {
            auditLogger.info("adaptive_keepalive_skipped", mapOf("reason" to "sensor_blocked"))
            return
        }

        val lastAutoSentTs = db.actionCommandDao().latestTimestampByTypeAndStatusExcludingPrefix(
            type = "temp_target",
            status = NightscoutActionRepository.STATUS_SENT,
            excludedPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%"
        )
        if (lastAutoSentTs != null && nowTs - lastAutoSentTs < ADAPTIVE_KEEPALIVE_INTERVAL_MS) {
            return
        }

        val baseTarget = baseTargetMmol.coerceIn(MIN_TARGET_MMOL, MAX_TARGET_MMOL)
        val proposal = alignTempTargetToBaseTarget(
            action = ActionProposal(
                type = "temp_target",
                targetMmol = baseTarget,
                durationMinutes = ADAPTIVE_KEEPALIVE_DURATION_MINUTES,
                reason = "adaptive_keepalive_30m"
            ),
            forecasts = forecasts,
            baseTargetMmol = baseTarget,
            sourceRuleId = AdaptiveTargetControllerRule.RULE_ID
        )
        if (activeTempTarget != null && abs(activeTempTarget - proposal.targetMmol) < 0.05) {
            auditLogger.info(
                "adaptive_keepalive_skipped",
                mapOf("reason" to "already_active", "activeTarget" to activeTempTarget)
            )
            return
        }

        val idempotencyKey = "${NightscoutActionRepository.KEEPALIVE_IDEMPOTENCY_PREFIX}${nowTs / ADAPTIVE_KEEPALIVE_INTERVAL_MS}"
        val command = ActionCommand(
            id = UUID.randomUUID().toString(),
            type = "temp_target",
            params = mapOf(
                "targetMmol" to proposal.targetMmol.toString(),
                "durationMinutes" to proposal.durationMinutes.toString(),
                "reason" to proposal.reason
            ),
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = dataFresh,
                activeTempTargetMmol = activeTempTarget,
                actionsLast6h = actionsLast6h
            ),
            idempotencyKey = idempotencyKey
        )
        val sent = actionRepository.submitTempTarget(command)
        if (sent) {
            auditLogger.info(
                "adaptive_keepalive_sent",
                mapOf(
                    "targetMmol" to proposal.targetMmol,
                    "durationMinutes" to proposal.durationMinutes,
                    "reason" to proposal.reason
                )
            )
        } else {
            auditLogger.warn(
                "adaptive_keepalive_failed",
                mapOf(
                    "targetMmol" to proposal.targetMmol,
                    "durationMinutes" to proposal.durationMinutes
                )
            )
        }
    }

    private fun ruleCooldownMinutes(ruleId: String, settings: AppSettings): Int = when (ruleId) {
        AdaptiveTargetControllerRule.RULE_ID -> settings.adaptiveControllerRetargetMinutes.coerceIn(5, 30)
        "PostHypoReboundGuard.v1" -> settings.rulePostHypoCooldownMinutes
        "PatternAdaptiveTarget.v1" -> settings.rulePatternCooldownMinutes
        "SegmentProfileGuard.v1" -> settings.ruleSegmentCooldownMinutes
        else -> 0
    }

    private suspend fun isRuleInCooldown(ruleId: String, nowTs: Long, cooldownMinutes: Int): Boolean {
        if (cooldownMinutes <= 0) return false
        val since = nowTs - cooldownMinutes * 60_000L
        return db.ruleExecutionDao()
            .findByStateSince(ruleId = ruleId, state = RuleState.TRIGGERED.name, since = since)
            .isNotEmpty()
    }

    private fun isSensorBlocked(
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        nowTs: Long
    ): Boolean {
        val lastSensorState = therapy
            .asSequence()
            .filter { it.type.equals("sensor_state", ignoreCase = true) }
            .maxByOrNull { it.ts } ?: return false

        val blocked = lastSensorState.payload["blocked"]?.equals("true", ignoreCase = true) == true
        if (!blocked) return false
        return nowTs - lastSensorState.ts <= SENSOR_BLOCK_TTL_MS
    }

    private fun resolveTimeSlot(hour: Int): ProfileTimeSlot = when (hour) {
        in 0..5 -> ProfileTimeSlot.NIGHT
        in 6..11 -> ProfileTimeSlot.MORNING
        in 12..17 -> ProfileTimeSlot.AFTERNOON
        else -> ProfileTimeSlot.EVENING
    }

    companion object {
        private const val SENSOR_BLOCK_TTL_MS = 30 * 60 * 1000L
        private const val MIN_TARGET_MMOL = 4.0
        private const val MAX_TARGET_MMOL = 10.0
        private const val ALIGN_GAIN = 0.35
        private const val MAX_ALIGN_STEP_MMOL = 1.20
        private const val ADAPTIVE_KEEPALIVE_INTERVAL_MS = 30 * 60 * 1000L
        private const val ADAPTIVE_KEEPALIVE_DURATION_MINUTES = 30
        private const val TELEMETRY_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val CALCULATED_UAM_LOOKBACK_MINUTES = 120
        private const val FORECAST_RETENTION_MS = 400L * 24 * 60 * 60 * 1000

        private const val COB_FORCE_BASE_THRESHOLD_G = 20.0
        private const val COB_FORCE_BASE_TARGET_MMOL = 4.2
        private val CUMULATIVE_ACTIVITY_KEYS = setOf(
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal"
        )

        private const val COB_FORECAST_GAIN_5 = 0.006
        private const val COB_FORECAST_GAIN_30 = 0.012
        private const val COB_FORECAST_GAIN_60 = 0.018
        private const val COB_FORECAST_BIAS_MAX = 2.5

        private const val IOB_FORECAST_GAIN_5 = 0.14
        private const val IOB_FORECAST_GAIN_30 = 0.28
        private const val IOB_FORECAST_GAIN_60 = 0.42
        private const val IOB_FORECAST_BIAS_MAX = 4.0

        private const val FORECAST_BIAS_MIN = -4.0
        private const val FORECAST_BIAS_MAX = 3.0
        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private const val CALIBRATION_FORECAST_LIMIT = 4_000
        private const val CALIBRATION_GLUCOSE_LIMIT = 8_000
        private const val CALIBRATION_LOOKBACK_MS = 12L * 60 * 60 * 1000
        private const val CALIBRATION_MIN_AGE_MS = 2L * 60 * 1000
        private const val CALIBRATION_MATCH_TOLERANCE_MS = 2L * 60 * 1000
        private const val CALIBRATION_HALF_LIFE_MS = 90.0 * 60 * 1000

        private data class CalibrationConfig(
            val minSamples: Int,
            val gain: Double,
            val maxUp: Double,
            val maxDown: Double
        )

        private fun calibrationConfig(horizonMinutes: Int): CalibrationConfig? = when (horizonMinutes) {
            5 -> CalibrationConfig(minSamples = 24, gain = 0.35, maxUp = 0.35, maxDown = 0.25)
            30 -> CalibrationConfig(minSamples = 18, gain = 0.45, maxUp = 0.70, maxDown = 0.45)
            60 -> CalibrationConfig(minSamples = 12, gain = 0.55, maxUp = 1.10, maxDown = 0.65)
            else -> null
        }

        internal fun applyRecentForecastCalibrationBiasStatic(
            forecasts: List<Forecast>,
            history: List<ForecastCalibrationPoint>
        ): List<Forecast> {
            if (forecasts.isEmpty() || history.isEmpty()) return forecasts
            val historyByHorizon = history.groupBy { it.horizonMinutes }
            return forecasts.map { forecast ->
                val cfg = calibrationConfig(forecast.horizonMinutes) ?: return@map forecast
                val points = historyByHorizon[forecast.horizonMinutes]
                    .orEmpty()
                    .filter { it.ageMs in CALIBRATION_MIN_AGE_MS..CALIBRATION_LOOKBACK_MS }
                if (points.size < cfg.minSamples) return@map forecast

                var sumW = 0.0
                var sumErr = 0.0
                points.forEach { point ->
                    val age = point.ageMs.coerceAtLeast(0L).toDouble()
                    val weight = exp(-age / CALIBRATION_HALF_LIFE_MS)
                    sumW += weight
                    sumErr += weight * point.errorMmol
                }
                if (sumW <= 1e-9) return@map forecast

                val meanErr = sumErr / sumW
                val bias = (meanErr * cfg.gain).coerceIn(-cfg.maxDown, cfg.maxUp)
                if (abs(bias) < 0.02) return@map forecast

                val shiftedValue = (forecast.valueMmol + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedLow = (forecast.ciLow + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + bias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
                val version = if (forecast.modelVersion.contains("|calib_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|calib_v1"
                }
                forecast.copy(
                    valueMmol = shiftedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun applyCobIobForecastBiasStatic(
            forecasts: List<Forecast>,
            cobGrams: Double?,
            iobUnits: Double?
        ): List<Forecast> {
            if (forecasts.isEmpty()) return forecasts
            val cob = (cobGrams ?: 0.0).coerceIn(0.0, 400.0)
            val iob = (iobUnits ?: 0.0).coerceIn(0.0, 30.0)
            if (cob <= 0.0 && iob <= 0.0) return forecasts

            return forecasts.map { forecast ->
                val cobGain = when (forecast.horizonMinutes) {
                    5 -> COB_FORECAST_GAIN_5
                    30 -> COB_FORECAST_GAIN_30
                    60 -> COB_FORECAST_GAIN_60
                    else -> COB_FORECAST_GAIN_60 * (forecast.horizonMinutes / 60.0)
                }
                val iobGain = when (forecast.horizonMinutes) {
                    5 -> IOB_FORECAST_GAIN_5
                    30 -> IOB_FORECAST_GAIN_30
                    60 -> IOB_FORECAST_GAIN_60
                    else -> IOB_FORECAST_GAIN_60 * (forecast.horizonMinutes / 60.0)
                }

                val cobBias = (cob * cobGain).coerceIn(0.0, COB_FORECAST_BIAS_MAX)
                val iobBias = (iob * iobGain).coerceIn(0.0, IOB_FORECAST_BIAS_MAX)
                val totalBias = (cobBias - iobBias).coerceIn(FORECAST_BIAS_MIN, FORECAST_BIAS_MAX)
                if (abs(totalBias) < 1e-6) return@map forecast

                val shiftedValue = (forecast.valueMmol + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedLow = (forecast.ciLow + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                var shiftedHigh = (forecast.ciHigh + totalBias).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
                if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
                if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
                val version = if (forecast.modelVersion.contains("|cob_iob_bias_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|cob_iob_bias_v1"
                }
                forecast.copy(
                    valueMmol = shiftedValue,
                    ciLow = shiftedLow,
                    ciHigh = shiftedHigh,
                    modelVersion = version
                )
            }
        }

        internal fun shouldSkipBaseAlignmentStatic(
            sourceRuleId: String?,
            actionReason: String
        ): Boolean {
            return sourceRuleId == AdaptiveTargetControllerRule.RULE_ID ||
                actionReason.contains("adaptive_pi_ci", ignoreCase = true) ||
                actionReason.contains("adaptive_keepalive", ignoreCase = true)
        }
    }

    private fun io.aaps.copilot.data.local.entity.ProfileEstimateEntity.toProfileEstimate(): ProfileEstimate {
        val useCalculatedIsf = calculatedIsfMmolPerUnit != null && calculatedIsfSampleCount > 0
        val useCalculatedCr = calculatedCrGramPerUnit != null && calculatedCrSampleCount > 0
        val realFirstIsf = if (useCalculatedIsf) calculatedIsfMmolPerUnit!! else isfMmolPerUnit
        val realFirstCr = if (useCalculatedCr) calculatedCrGramPerUnit!! else crGramPerUnit
        val realFirstIsfSamples = if (useCalculatedIsf) calculatedIsfSampleCount else isfSampleCount
        val realFirstCrSamples = if (useCalculatedCr) calculatedCrSampleCount else crSampleCount
        val realFirstSampleCount = if (useCalculatedIsf || useCalculatedCr) {
            maxOf(1, calculatedSampleCount)
        } else {
            sampleCount
        }
        val realFirstConfidence = if (useCalculatedIsf || useCalculatedCr) {
            calculatedConfidence ?: confidence
        } else {
            confidence
        }
        return ProfileEstimate(
            isfMmolPerUnit = realFirstIsf,
            crGramPerUnit = realFirstCr,
            confidence = realFirstConfidence,
            sampleCount = realFirstSampleCount,
            isfSampleCount = realFirstIsfSamples,
            crSampleCount = realFirstCrSamples,
            lookbackDays = lookbackDays,
            telemetryIsfSampleCount = telemetryIsfSampleCount,
            telemetryCrSampleCount = telemetryCrSampleCount,
            uamObservedCount = uamObservedCount,
            uamFilteredIsfSamples = uamFilteredIsfSamples,
            uamEpisodeCount = uamEpisodeCount,
            uamEstimatedCarbsGrams = uamEstimatedCarbsGrams,
            uamEstimatedRecentCarbsGrams = uamEstimatedRecentCarbsGrams
        )
    }

    private fun io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity.toProfileSegmentEstimate(): ProfileSegmentEstimate =
        ProfileSegmentEstimate(
            dayType = DayType.valueOf(dayType),
            timeSlot = ProfileTimeSlot.valueOf(timeSlot),
            isfMmolPerUnit = isfMmolPerUnit,
            crGramPerUnit = crGramPerUnit,
            confidence = confidence,
            isfSampleCount = isfSampleCount,
            crSampleCount = crSampleCount,
            lookbackDays = lookbackDays
        )

    private fun io.aaps.copilot.data.local.entity.GlucoseSampleEntity.toGlucosePoint():
        io.aaps.copilot.domain.model.GlucosePoint {
        val quality = runCatching {
            io.aaps.copilot.domain.model.DataQuality.valueOf(this.quality)
        }.getOrDefault(io.aaps.copilot.domain.model.DataQuality.OK)
        return io.aaps.copilot.domain.model.GlucosePoint(
            ts = timestamp,
            valueMmol = mmol,
            source = source,
            quality = quality
        )
    }

    private fun io.aaps.copilot.data.local.entity.TherapyEventEntity.toTherapyEvent(gson: Gson):
        io.aaps.copilot.domain.model.TherapyEvent {
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        val payload = gson.fromJson<Map<String, String>>(payloadJson, mapType) ?: emptyMap()
        return io.aaps.copilot.domain.model.TherapyEvent(
            ts = timestamp,
            type = type,
            payload = payload
        )
    }

    private fun Forecast.toForecastEntity() = io.aaps.copilot.data.local.entity.ForecastEntity(
        timestamp = ts,
        horizonMinutes = horizonMinutes,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )
}
