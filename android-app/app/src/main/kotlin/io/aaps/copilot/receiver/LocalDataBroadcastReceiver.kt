package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.service.LocalNightscoutServiceController
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class LocalDataBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? CopilotApp ?: return
        val payload = intent ?: return

        val pendingResult = goAsync()
        receiverScope.launch {
            runCatching {
                val action = payload.action.orEmpty()
                val settings = app.container.settingsStore.settings.first()
                if (action == TEST_SEND_TEMP_TARGET_ACTION) {
                    handleTestTempTarget(app, settings, payload)
                    return@runCatching
                }
                if (action == TEST_SEND_CARBS_ACTION) {
                    handleTestCarbs(app, settings, payload)
                    return@runCatching
                }
                if (!settings.localBroadcastIngestEnabled) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf("reason" to "disabled_in_settings", "action" to action)
                    )
                    return@runCatching
                }

                val senderPackage = senderPackageOrNull()
                if (
                    !isTrustedSender(
                        action = action,
                        senderPackage = senderPackage,
                        appPackage = context.packageName,
                        strictValidation = settings.strictBroadcastSenderValidation
                    )
                ) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf(
                            "reason" to "untrusted_sender",
                            "action" to action,
                            "senderPackage" to senderPackage.orEmpty(),
                            "strictValidation" to settings.strictBroadcastSenderValidation
                        )
                    )
                    return@runCatching
                }
                ensureRuntimeService(context.applicationContext)
                val result = app.container.broadcastIngestRepository.ingest(payload)
                if (result.glucoseImported > 0 || result.therapyImported > 0 || result.telemetryImported > 0) {
                    val enqueued = WorkScheduler.triggerReactiveAutomation(context.applicationContext)
                    val message = if (enqueued) {
                        "broadcast_reactive_automation_enqueued"
                    } else {
                        "broadcast_reactive_automation_skipped"
                    }
                    val metadata = mapOf(
                        "reason" to if (enqueued) "scheduled" else "debounced",
                        "action" to payload.action.orEmpty(),
                        "glucose" to result.glucoseImported,
                        "therapy" to result.therapyImported,
                        "telemetry" to result.telemetryImported
                    )
                    if (enqueued) {
                        app.container.auditLogger.info(message, metadata)
                    } else {
                        app.container.auditLogger.infoThrottled(
                            throttleKey = "broadcast_reactive_automation_skipped:${payload.action.orEmpty()}",
                            intervalMs = 5 * 60_000L,
                            message = message,
                            metadata = metadata
                        )
                    }
                }
            }.onFailure { error ->
                app.container.auditLogger.warn(
                    "broadcast_ingest_failed",
                    mapOf(
                        "action" to payload.action.orEmpty(),
                        "message" to (error.message ?: error::class.java.simpleName)
                    )
                )
            }
            pendingResult.finish()
        }
    }

    private fun senderPackageOrNull(): String? =
        if (Build.VERSION.SDK_INT >= 34) getSentFromPackage() else null

    private fun isTrustedSender(
        action: String,
        senderPackage: String?,
        appPackage: String,
        strictValidation: Boolean
    ): Boolean {
        if (
            action == TEST_ACTION ||
            action == TEST_SEND_TEMP_TARGET_ACTION ||
            action == TEST_SEND_CARBS_ACTION
        ) {
            if (senderPackage.isNullOrBlank()) return true
            return senderPackage == "com.android.shell" || senderPackage == appPackage
        }
        // Allow adb diagnostics for local USB validation.
        if (senderPackage == "com.android.shell") {
            return true
        }
        if (senderPackage.isNullOrBlank()) {
            if (!strictValidation) return true
            // Some devices/OS builds do not expose sender package for implicit broadcasts.
            // In strict mode, still allow known glucose channels to prevent stale CGM stream.
            return action.startsWith("com.eveningoutpost.dexdrip.") ||
                action.startsWith("com.microtechmd.cgms.aidex.") ||
                action.startsWith("com.fanqies.tomatofn.") ||
                isAapsBroadcastAction(action)
        }
        return when {
            action.startsWith("com.eveningoutpost.dexdrip.") ->
                senderPackage.startsWith("com.eveningoutpost.dexdrip") || senderPackage in TRUSTED_AAPS_PACKAGES
            action.startsWith("com.microtechmd.cgms.aidex.") ->
                senderPackage.startsWith("com.microtechmd.cgms.aidex") || senderPackage in TRUSTED_AAPS_PACKAGES
            action.startsWith("com.fanqies.tomatofn.") ->
                senderPackage.startsWith("com.fanqies.tomatofn") || senderPackage in TRUSTED_AAPS_PACKAGES
            isAapsBroadcastAction(action) -> senderPackage in TRUSTED_AAPS_PACKAGES
            else -> senderPackage == appPackage
        }
    }

    private fun isAapsBroadcastAction(action: String): Boolean {
        return action.startsWith("info.nightscout.client.") ||
            action.startsWith("info.nightscout.androidaps.") ||
            action.startsWith("app.aaps.")
    }

    private suspend fun handleTestTempTarget(
        app: CopilotApp,
        settings: io.aaps.copilot.config.AppSettings,
        payload: Intent
    ) {
        val target = parseDoubleExtra(
            payload = payload,
            keys = arrayOf("targetMmol", "target", "value"),
            defaultValue = settings.baseTargetMmol
        )?.coerceIn(4.0, 10.0)
        val duration = parseIntExtra(
            payload = payload,
            keys = arrayOf("durationMinutes", "duration"),
            defaultValue = 30
        )?.coerceIn(5, 720)
        if (target == null || duration == null) {
            app.container.auditLogger.warn(
                "broadcast_test_send_temp_target_failed",
                mapOf("reason" to "invalid_payload")
            )
            return
        }
        val reason = payload.getStringExtra("reason")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "manual_broadcast_temp_target"
        val now = System.currentTimeMillis()
        val sentCount = runCatching { app.container.actionRepository.countSentActionsLast6h() }
            .getOrDefault(0)
        val command = ActionCommand(
            id = "manual-test-temp-$now",
            type = "temp_target",
            params = mapOf(
                "targetMmol" to String.format(Locale.US, "%.2f", target),
                "durationMinutes" to duration.toString(),
                "reason" to reason
            ),
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = sentCount
            ),
            idempotencyKey = "manual:broadcast:temp:${now / 1000}:${UUID.randomUUID()}"
        )
        val sent = app.container.actionRepository.submitTempTarget(command)
        app.container.auditLogger.info(
            "broadcast_test_send_temp_target",
            mapOf(
                "sent" to sent,
                "targetMmol" to target,
                "durationMinutes" to duration,
                "reason" to reason
            )
        )
    }

    private suspend fun handleTestCarbs(
        app: CopilotApp,
        settings: io.aaps.copilot.config.AppSettings,
        payload: Intent
    ) {
        val safetyCap = settings.carbComputationMaxGrams.coerceIn(20.0, 60.0)
        val carbs = parseDoubleExtra(
            payload = payload,
            keys = arrayOf("carbsGrams", "carbs", "grams"),
            defaultValue = 10.0
        )?.coerceIn(1.0, safetyCap)
        if (carbs == null) {
            app.container.auditLogger.warn(
                "broadcast_test_send_carbs_failed",
                mapOf("reason" to "invalid_payload")
            )
            return
        }
        val reason = payload.getStringExtra("reason")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "manual_broadcast_carbs"
        val now = System.currentTimeMillis()
        val sentCount = runCatching { app.container.actionRepository.countSentActionsLast6h() }
            .getOrDefault(0)
        val command = ActionCommand(
            id = "manual-test-carbs-$now",
            type = "carbs",
            params = mapOf(
                "carbsGrams" to String.format(Locale.US, "%.1f", carbs),
                "reason" to reason
            ),
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = sentCount
            ),
            idempotencyKey = "manual:broadcast:carbs:${now / 1000}:${UUID.randomUUID()}"
        )
        val sent = app.container.actionRepository.submitCarbs(command)
        app.container.auditLogger.info(
            "broadcast_test_send_carbs",
            mapOf(
                "sent" to sent,
                "carbsGrams" to carbs,
                "reason" to reason
            )
        )
    }

    private fun parseDoubleExtra(payload: Intent, keys: Array<String>, defaultValue: Double): Double? {
        for (key in keys) {
            if (!payload.hasExtra(key)) continue
            val value = payload.extras?.get(key) ?: continue
            val parsed = when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is String -> value.trim().replace(',', '.').toDoubleOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return defaultValue
    }

    private fun parseIntExtra(payload: Intent, keys: Array<String>, defaultValue: Int): Int? {
        for (key in keys) {
            if (!payload.hasExtra(key)) continue
            val value = payload.extras?.get(key) ?: continue
            val parsed = when (value) {
                is Int -> value
                is Long -> value.toInt()
                is Float -> value.toInt()
                is Double -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return defaultValue
    }

    private companion object {
        const val TEST_ACTION = "io.aaps.copilot.BROADCAST_TEST_INGEST"
        const val TEST_SEND_TEMP_TARGET_ACTION = "io.aaps.copilot.BROADCAST_TEST_SEND_TEMP_TARGET"
        const val TEST_SEND_CARBS_ACTION = "io.aaps.copilot.BROADCAST_TEST_SEND_CARBS"
        val lastServiceEnsureAtMs = AtomicLong(0L)
        const val SERVICE_ENSURE_INTERVAL_MS = 10 * 60 * 1000L
        val TRUSTED_AAPS_PACKAGES = setOf(
            "info.nightscout.androidaps",
            "info.nightscout.aaps",
            "app.aaps"
        )
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun ensureRuntimeService(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastServiceEnsureAtMs.get()
        if (now - last < SERVICE_ENSURE_INTERVAL_MS) return
        if (!lastServiceEnsureAtMs.compareAndSet(last, now)) return
        runCatching {
            LocalNightscoutServiceController.start(context, allowBackground = false)
        }
    }
}
