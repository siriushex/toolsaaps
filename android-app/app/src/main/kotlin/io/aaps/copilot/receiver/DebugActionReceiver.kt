package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.aaps.copilot.BuildConfig
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.GlucoseAlertRuntimeState
import io.aaps.copilot.data.repository.GlucoseAlertState
import io.aaps.copilot.service.LocalNightscoutForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DebugActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext as? CopilotApp ?: return
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_EVAL_FORECAST_VIRTUAL_MEAL_GATE -> {
                        val usingVirtualMeal = intent.getBooleanExtra(EXTRA_USING_VIRTUAL_MEAL, true)
                        val carbs = intent.getStringExtra(EXTRA_CARBS)?.toDoubleOrNull()
                        val confidence = intent.getStringExtra(EXTRA_CONFIDENCE)?.toDoubleOrNull()
                        val forecastUam60 = intent.getStringExtra(EXTRA_FORECAST_UAM60)?.toDoubleOrNull()
                        val uci0 = intent.getStringExtra(EXTRA_UCI0)?.toDoubleOrNull()
                        val rocPer5 = intent.getStringExtra(EXTRA_ROC_PER5)?.toDoubleOrNull()
                        val inferredStaleDecay = intent.getBooleanExtra(EXTRA_INFERRED_STALE_DECAY, false)
                        val result = AutomationRepository.evaluateForecastVirtualMealRuntimeGateStatic(
                            usingVirtualMeal = usingVirtualMeal,
                            virtualMealCarbs = carbs,
                            virtualMealConfidence = confidence,
                            forecastUam60Mmol = forecastUam60,
                            forecastUci0 = uci0,
                            forecastRocPer5Used = rocPer5,
                            inferredStaleDecayApplied = inferredStaleDecay
                        )
                        app.container.auditLogger.info(
                            "forecast_virtual_meal_gate_debug_evaluated",
                            mapOf(
                                "active" to result.active,
                                "reason" to result.reason,
                                "usingVirtualMeal" to usingVirtualMeal,
                                "carbs" to carbs,
                                "confidence" to confidence,
                                "forecastUam60" to forecastUam60,
                                "uci0" to uci0,
                                "rocPer5" to rocPer5,
                                "inferredStaleDecay" to inferredStaleDecay
                            )
                        )
                        Log.i(
                            "CopilotUamGateDebug",
                            "active=${result.active} reason=${result.reason} usingVirtualMeal=$usingVirtualMeal carbs=${carbs ?: 0.0} confidence=${confidence ?: 0.0} forecastUam60=${forecastUam60 ?: 0.0} uci0=${uci0 ?: 0.0} rocPer5=${rocPer5 ?: 0.0} inferredStaleDecay=$inferredStaleDecay"
                        )
                    }

                    ACTION_RUN_AUTOMATION_CYCLE -> {
                        app.container.auditLogger.info(
                            "automation_debug_trigger_requested",
                            mapOf("source" to "adb_broadcast")
                        )
                        app.container.automationRepository.runAutomationCycle()
                        app.container.auditLogger.info(
                            "automation_debug_trigger_completed",
                            mapOf("source" to "adb_broadcast")
                        )
                    }

                    ACTION_RUN_DB_MAINTENANCE -> {
                        Log.i("CopilotDebugMaintenance", "scheduled")
                        app.container.auditLogger.info(
                            "db_maintenance_debug_trigger_requested",
                            mapOf("source" to "adb_broadcast")
                        )
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            runCatching {
                                app.container.automationRepository.runDbHousekeepingNowForDebug()
                                app.container.broadcastIngestRepository.runPeriodicMaintenanceNowForDebug()
                            }.onSuccess {
                                app.container.auditLogger.info(
                                    "db_maintenance_debug_trigger_completed",
                                    mapOf("source" to "adb_broadcast")
                                )
                                Log.i("CopilotDebugMaintenance", "completed")
                            }.onFailure { error ->
                                app.container.auditLogger.error(
                                    "db_maintenance_debug_trigger_failed",
                                    mapOf(
                                        "action" to action,
                                        "error" to (error.message ?: error::class.java.simpleName).take(320)
                                    )
                                )
                                Log.e(
                                    "CopilotDebugMaintenance",
                                    "failed: ${(error.message ?: error::class.java.simpleName)}",
                                    error
                                )
                            }
                        }
                        return@launch
                    }

                    ACTION_RUN_DB_VACUUM -> {
                        Log.i("CopilotDebugVacuum", "foreground_service_scheduled")
                        app.container.auditLogger.info(
                            "db_vacuum_debug_trigger_requested",
                            mapOf("source" to "adb_broadcast")
                        )
                        ContextCompat.startForegroundService(
                            context.applicationContext,
                            Intent(context.applicationContext, LocalNightscoutForegroundService::class.java)
                                .setAction(LocalNightscoutForegroundService.ACTION_DEBUG_VACUUM)
                        )
                    }

                    ACTION_RUN_DB_DIAGNOSTICS -> {
                        Log.i("CopilotDbDiagnostics", "foreground_service_scheduled")
                        app.container.auditLogger.info(
                            "db_table_diagnostics_debug_requested",
                            mapOf("source" to "adb_broadcast")
                        )
                        ContextCompat.startForegroundService(
                            context.applicationContext,
                            Intent(context.applicationContext, LocalNightscoutForegroundService::class.java)
                                .setAction(LocalNightscoutForegroundService.ACTION_DEBUG_DB_DIAGNOSTICS)
                        )
                    }

                    ACTION_RUN_DAILY_ANALYSIS -> {
                        app.container.auditLogger.info(
                            "daily_analysis_debug_trigger_requested",
                            mapOf("source" to "adb_broadcast")
                        )
                        val result = app.container.insightsRepository.runDailyAnalysis()
                        val replayKeys = listOf(
                            "daily_report_replay_top_pair_hint_30m",
                            "daily_report_replay_top_pair_hint_60m",
                            "daily_report_replay_error_cluster_hint_30m",
                            "daily_report_replay_error_cluster_hint_60m",
                            "daily_report_replay_top_pair_30m",
                            "daily_report_replay_top_pair_60m",
                            "daily_report_replay_error_cluster_30m",
                            "daily_report_replay_error_cluster_60m"
                        )
                        val replayHints = app.container.db.telemetryDao()
                            .latestByKeysSince(
                                since = System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L,
                                keys = replayKeys
                            )
                            .associate { sample ->
                                sample.key to (sample.valueText ?: sample.valueDouble?.toString().orEmpty())
                            }
                        app.container.auditLogger.info(
                            "daily_analysis_debug_trigger_completed",
                            buildMap {
                                put("result", result.take(320))
                                replayHints.forEach { (key, value) ->
                                    put(key, value.take(320))
                                }
                            }
                        )
                        Log.i(
                            "CopilotReplayDebug",
                            buildString {
                                append("result=")
                                append(result.take(160))
                                replayKeys.forEach { key ->
                                    val value = replayHints[key]?.take(240)
                                    if (!value.isNullOrBlank()) {
                                        append(" | ")
                                        append(key)
                                        append("=")
                                        append(value)
                                    }
                                }
                            }
                        )
                    }

                    ACTION_ADD_BLOOD_CHECK -> {
                        val latest = app.container.db.glucoseDao().latestOne()
                        val explicitValue = intent.getStringExtra(EXTRA_VALUE)?.toDoubleOrNull()
                        val autoFromLatest = explicitValue == null
                        val value = explicitValue ?: latest?.mmol
                        if (value == null) {
                            app.container.auditLogger.warn(
                                "blood_glucose_check_debug_trigger_failed",
                                mapOf("reason" to "missing_value_and_no_latest_glucose")
                            )
                            return@launch
                        }
                        val units = intent.getStringExtra(EXTRA_UNITS)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: DEFAULT_UNITS
                        val explicitTimestamp = intent.hasExtra(EXTRA_TIMESTAMP)
                        val timestamp = when {
                            explicitTimestamp -> intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                            autoFromLatest && latest != null -> latest.timestamp - DEFAULT_AUTO_BACKDATE_MINUTES * 60_000L
                            else -> System.currentTimeMillis()
                        }
                        val note = intent.getStringExtra(EXTRA_NOTE)
                            ?: if (intent.hasExtra(EXTRA_VALUE)) {
                                "debug_adb_blood_check"
                            } else {
                                "debug_adb_blood_check_from_latest_raw_backdated"
                            }
                        app.container.auditLogger.info(
                            "blood_glucose_check_debug_trigger_requested",
                            mapOf(
                                "value" to value,
                                "units" to units,
                                "timestamp" to timestamp,
                                "autoFromLatest" to autoFromLatest
                            )
                        )
                        val check = app.container.glucoseCalibrationRepository.addManualBloodGlucoseCheck(
                            value = value,
                            units = units,
                            timestamp = timestamp,
                            note = note
                        )
                        app.container.automationRepository.runAutomationCycle()
                        app.container.auditLogger.info(
                            "blood_glucose_check_debug_trigger_completed",
                            mapOf(
                                "id" to check.id,
                                "mmol" to check.mmol,
                                "status" to check.status.name,
                                "reason" to check.reason
                            )
                        )
                    }

                    ACTION_TRIGGER_SOFT_ALERT -> {
                        val latestForecast = app.container.db.forecastDao().latestByHorizon(30)
                        val latestGlucose = app.container.db.glucoseDao().latestOne()
                        val anchor = latestForecast?.valueMmol ?: latestGlucose?.mmol
                        if (anchor == null) {
                            app.container.auditLogger.warn(
                                "glucose_alert_debug_trigger_failed",
                                mapOf("reason" to "missing_forecast30_and_glucose", "kind" to "soft")
                            )
                            return@launch
                        }
                        val stateStore = app.container.glucoseAlertStateStore
                        val originalSettings = app.container.settingsStore.settings.first()
                        val forcedHigh = (anchor - 0.1).coerceIn(7.0, 13.9)
                        app.container.auditLogger.info(
                            "glucose_alert_debug_trigger_requested",
                            mapOf("kind" to "soft_high", "anchor" to anchor, "forcedHighThreshold" to forcedHigh)
                        )
                        stateStore.update { GlucoseAlertRuntimeState() }
                        app.container.settingsStore.update {
                            it.copy(
                                softAlertEnabled = true,
                                softAlertHighMmol = forcedHigh,
                                softAlertLowMmol = originalSettings.softAlertLowMmol,
                                urgentLowMmol = originalSettings.urgentLowMmol
                            )
                        }
                        app.container.automationRepository.runAutomationCycle()
                        app.container.automationRepository.runAutomationCycle()
                        app.container.settingsStore.update { originalSettings }
                        app.container.automationRepository.runAutomationCycle()
                        app.container.auditLogger.info(
                            "glucose_alert_debug_trigger_completed",
                            mapOf("kind" to "soft_high", "anchor" to anchor)
                        )
                    }

                    ACTION_TRIGGER_STRONG_ALERT -> {
                        val latestGlucose = app.container.db.glucoseDao().latestOne()
                        val current = latestGlucose?.mmol
                        if (current == null) {
                            app.container.auditLogger.warn(
                                "glucose_alert_debug_trigger_failed",
                                mapOf("reason" to "missing_current_glucose", "kind" to "strong")
                            )
                            return@launch
                        }
                        val stateStore = app.container.glucoseAlertStateStore
                        val originalSettings = app.container.settingsStore.settings.first()
                        val forcedUrgentLow = (current + 0.2).coerceIn(3.0, 4.4)
                        app.container.auditLogger.info(
                            "glucose_alert_debug_trigger_requested",
                            mapOf("kind" to "strong_low", "currentGlucose" to current, "forcedUrgentLow" to forcedUrgentLow)
                        )
                        stateStore.update {
                            it.copy(
                                lastStrongAlertAtTs = 0L,
                                activeAlertState = GlucoseAlertState.NONE,
                                activeDirection = null
                            )
                        }
                        app.container.settingsStore.update {
                            it.copy(
                                urgentLowMmol = forcedUrgentLow,
                                softAlertLowMmol = maxOf(it.softAlertLowMmol, forcedUrgentLow)
                            )
                        }
                        app.container.automationRepository.runAutomationCycle()
                        app.container.settingsStore.update { originalSettings }
                        app.container.automationRepository.runAutomationCycle()
                        app.container.auditLogger.info(
                            "glucose_alert_debug_trigger_completed",
                            mapOf("kind" to "strong_low", "currentGlucose" to current)
                        )
                    }
                }
            } catch (error: Throwable) {
                app.container.auditLogger.error(
                    when (action) {
                        ACTION_EVAL_FORECAST_VIRTUAL_MEAL_GATE -> "forecast_virtual_meal_gate_debug_failed"
                        ACTION_RUN_AUTOMATION_CYCLE -> "automation_debug_trigger_failed"
                        ACTION_RUN_DB_MAINTENANCE -> "db_maintenance_debug_trigger_failed"
                        ACTION_RUN_DB_VACUUM -> "db_vacuum_debug_trigger_failed"
                        ACTION_RUN_DB_DIAGNOSTICS -> "db_table_diagnostics_debug_failed"
                        ACTION_RUN_DAILY_ANALYSIS -> "daily_analysis_debug_trigger_failed"
                        ACTION_ADD_BLOOD_CHECK -> "blood_glucose_check_debug_trigger_failed"
                        ACTION_TRIGGER_SOFT_ALERT,
                        ACTION_TRIGGER_STRONG_ALERT -> "glucose_alert_debug_trigger_failed"
                        else -> "debug_trigger_failed"
                    },
                    mapOf(
                        "action" to action,
                        "error" to (error.message ?: error::class.java.simpleName).take(320)
                    )
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_EVAL_FORECAST_VIRTUAL_MEAL_GATE = "io.aaps.copilot.DEBUG_EVAL_FORECAST_VIRTUAL_MEAL_GATE"
        const val ACTION_RUN_AUTOMATION_CYCLE = "io.aaps.copilot.DEBUG_RUN_AUTOMATION_CYCLE"
        const val ACTION_RUN_DB_MAINTENANCE = "io.aaps.copilot.DEBUG_RUN_DB_MAINTENANCE"
        const val ACTION_RUN_DB_VACUUM = "io.aaps.copilot.DEBUG_RUN_DB_VACUUM"
        const val ACTION_RUN_DB_DIAGNOSTICS = "io.aaps.copilot.DEBUG_RUN_DB_DIAGNOSTICS"
        const val ACTION_RUN_DAILY_ANALYSIS = "io.aaps.copilot.DEBUG_RUN_DAILY_ANALYSIS"
        const val ACTION_ADD_BLOOD_CHECK = "io.aaps.copilot.DEBUG_ADD_BLOOD_CHECK"
        const val ACTION_TRIGGER_SOFT_ALERT = "io.aaps.copilot.DEBUG_TRIGGER_SOFT_ALERT"
        const val ACTION_TRIGGER_STRONG_ALERT = "io.aaps.copilot.DEBUG_TRIGGER_STRONG_ALERT"
        const val EXTRA_VALUE = "value"
        const val EXTRA_UNITS = "units"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_NOTE = "note"
        const val EXTRA_USING_VIRTUAL_MEAL = "usingVirtualMeal"
        const val EXTRA_CARBS = "carbs"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_FORECAST_UAM60 = "forecastUam60"
        const val EXTRA_UCI0 = "uci0"
        const val EXTRA_ROC_PER5 = "rocPer5"
        const val EXTRA_INFERRED_STALE_DECAY = "inferredStaleDecay"
        private const val DEFAULT_UNITS = "mmol/L"
        private const val DEFAULT_AUTO_BACKDATE_MINUTES = 10L
    }
}
