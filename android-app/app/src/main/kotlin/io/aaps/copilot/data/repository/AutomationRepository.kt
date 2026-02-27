package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.remote.cloud.CloudGlucosePoint
import io.aaps.copilot.data.remote.cloud.CloudTherapyEvent
import io.aaps.copilot.data.remote.cloud.PredictRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.RuleRuntimeConfig
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import io.aaps.copilot.service.ApiFactory
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first

class AutomationRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val syncRepository: SyncRepository,
    private val exportRepository: AapsExportRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val actionRepository: NightscoutActionRepository,
    private val predictionEngine: PredictionEngine,
    private val ruleEngine: RuleEngine,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

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

    suspend fun runAutomationCycle() {
        val settings = settingsStore.settings.first()
        syncRepository.syncNightscoutIncremental()
        syncRepository.pushCloudIncremental()
        exportRepository.importBaselineFromExports()
        analyticsRepository.recalculate(settings.baseTargetMmol)

        val glucose = syncRepository.recentGlucose(limit = 72)
        if (glucose.isEmpty()) {
            auditLogger.warn("automation_skipped", mapOf("reason" to "no_glucose_data"))
            return
        }

        val therapy = syncRepository.recentTherapyEvents(hoursBack = 24)
        val localForecasts = predictionEngine.predict(glucose, therapy)
        val mergedForecasts = maybeMergeCloudPrediction(glucose, therapy, localForecasts)

        db.forecastDao().insertAll(mergedForecasts.map { it.toEntity() })
        db.forecastDao().deleteOlderThan(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)

        val now = System.currentTimeMillis()
        val latest = glucose.maxBy { it.ts }
        val dataFresh = now - latest.ts <= settings.staleDataMaxMinutes * 60 * 1000L
        val actionsLast6h = actionRepository.countSentActionsLast6h()
        val activeTempTarget = resolveActiveTempTarget(now)

        val zoned = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) "WEEKEND" else "WEEKDAY"
        val currentPattern = db.patternDao().byDayAndHour(dayType, zoned.hour)?.let {
            io.aaps.copilot.domain.model.PatternWindow(
                dayType = io.aaps.copilot.domain.model.DayType.valueOf(it.dayType),
                hour = it.hour,
                lowRate = it.lowRate,
                highRate = it.highRate,
                recommendedTargetMmol = it.recommendedTargetMmol
            )
        }

        val context = RuleContext(
            nowTs = now,
            glucose = glucose,
            therapyEvents = therapy,
            forecasts = mergedForecasts,
            currentDayPattern = currentPattern,
            baseTargetMmol = settings.baseTargetMmol,
            dataFresh = dataFresh,
            activeTempTargetMmol = activeTempTarget,
            actionsLast6h = actionsLast6h,
            sensorBlocked = false
        )

        val decisions = ruleEngine.evaluate(
            context = context,
            config = SafetyPolicyConfig(
                killSwitch = settings.killSwitch,
                maxActionsIn6Hours = settings.maxActionsIn6Hours
            ),
            runtimeConfig = runtimeConfig(settings)
        )

        for (decision in decisions) {
            db.ruleExecutionDao().insert(
                RuleExecutionEntity(
                    timestamp = now,
                    ruleId = decision.ruleId,
                    state = decision.state.name,
                    reasonsJson = gson.toJson(decision.reasons),
                    actionJson = decision.actionProposal?.let { gson.toJson(it) }
                )
            )

            if (decision.state == RuleState.TRIGGERED && decision.actionProposal != null) {
                val idempotencyKey = "${decision.ruleId}:${now / (30 * 60 * 1000L)}"
                val command = ActionCommand(
                    id = UUID.randomUUID().toString(),
                    type = decision.actionProposal.type,
                    params = mapOf(
                        "targetMmol" to decision.actionProposal.targetMmol.toString(),
                        "durationMinutes" to decision.actionProposal.durationMinutes.toString(),
                        "reason" to decision.actionProposal.reason
                    ),
                    safetySnapshot = SafetySnapshot(
                        killSwitch = settings.killSwitch,
                        dataFresh = dataFresh,
                        activeTempTargetMmol = activeTempTarget,
                        actionsLast6h = actionsLast6h
                    ),
                    idempotencyKey = idempotencyKey
                )

                actionRepository.submitTempTarget(command)
            }
        }

        auditLogger.info(
            "automation_cycle_completed",
            mapOf(
                "glucosePoints" to glucose.size,
                "therapyEvents" to therapy.size,
                "forecasts" to mergedForecasts.size,
                "decisions" to decisions.size
            )
        )
    }

    suspend fun runDryRunSimulation(days: Int): DryRunReport {
        val settings = settingsStore.settings.first()
        val periodDays = days.coerceIn(1, 60)
        val startTs = System.currentTimeMillis() - periodDays * 24L * 60 * 60 * 1000

        val glucose = db.glucoseDao().since(startTs).map { it.toDomain() }.sortedBy { it.ts }
        val therapy = db.therapyDao().since(startTs).map { it.toDomain(gson) }.sortedBy { it.ts }
        val patterns = db.patternDao().all()

        if (glucose.size < 8) {
            return DryRunReport(periodDays, glucose.size, emptyList())
        }

        val counters = mutableMapOf<String, Triple<Int, Int, Int>>()
        val runtimeConfig = runtimeConfig(settings)

        for (index in 7 until glucose.size step 2) {
            val point = glucose[index]
            val pointTs = point.ts
            val windowStart = pointTs - 6L * 60 * 60 * 1000
            val therapyStart = pointTs - 24L * 60 * 60 * 1000
            val gWindow = glucose.filter { it.ts in windowStart..pointTs }
            val tWindow = therapy.filter { it.ts in therapyStart..pointTs }
            val forecasts = predictionEngine.predict(gWindow, tWindow)

            val zoned = Instant.ofEpochMilli(pointTs).atZone(ZoneId.systemDefault())
            val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
            val pattern = patterns.firstOrNull {
                it.dayType == dayType.name && it.hour == zoned.hour
            }?.let {
                io.aaps.copilot.domain.model.PatternWindow(
                    dayType = dayType,
                    hour = it.hour,
                    lowRate = it.lowRate,
                    highRate = it.highRate,
                    recommendedTargetMmol = it.recommendedTargetMmol
                )
            }

            val context = RuleContext(
                nowTs = pointTs,
                glucose = gWindow,
                therapyEvents = tWindow,
                forecasts = forecasts,
                currentDayPattern = pattern,
                baseTargetMmol = settings.baseTargetMmol,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = false
            )

            val decisions = ruleEngine.evaluate(
                context = context,
                config = SafetyPolicyConfig(
                    killSwitch = false,
                    maxActionsIn6Hours = settings.maxActionsIn6Hours
                ),
                runtimeConfig = runtimeConfig
            )

            decisions.forEach { decision ->
                val current = counters[decision.ruleId] ?: Triple(0, 0, 0)
                counters[decision.ruleId] = when (decision.state) {
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

    private fun runtimeConfig(settings: io.aaps.copilot.config.AppSettings): RuleRuntimeConfig {
        val enabled = buildSet {
            if (settings.rulePostHypoEnabled) add("PostHypoReboundGuard.v1")
            if (settings.rulePatternEnabled) add("PatternAdaptiveTarget.v1")
        }
        val priorities = mapOf(
            "PostHypoReboundGuard.v1" to settings.rulePostHypoPriority,
            "PatternAdaptiveTarget.v1" to settings.rulePatternPriority
        )
        return RuleRuntimeConfig(enabledRuleIds = enabled, priorities = priorities)
    }
}
