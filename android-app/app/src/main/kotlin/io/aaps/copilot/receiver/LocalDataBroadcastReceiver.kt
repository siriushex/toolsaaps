package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.scheduler.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LocalDataBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? CopilotApp ?: return
        val payload = intent ?: return

        val pendingResult = goAsync()
        receiverScope.launch {
            runCatching {
                val action = payload.action.orEmpty()
                val settings = app.container.settingsStore.settings.first()
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
                val result = app.container.broadcastIngestRepository.ingest(payload)
                if (result.glucoseImported > 0 || result.therapyImported > 0) {
                    WorkScheduler.triggerReactiveAutomation(context.applicationContext)
                    app.container.auditLogger.info(
                        "broadcast_reactive_automation_enqueued",
                        mapOf(
                            "action" to payload.action.orEmpty(),
                            "glucose" to result.glucoseImported,
                            "therapy" to result.therapyImported,
                            "telemetry" to result.telemetryImported
                        )
                    )
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
        if (action == TEST_ACTION) {
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
            action.startsWith("com.eveningoutpost.dexdrip.") -> senderPackage.startsWith("com.eveningoutpost.dexdrip")
            action.startsWith("com.microtechmd.cgms.aidex.") -> senderPackage.startsWith("com.microtechmd.cgms.aidex")
            action.startsWith("com.fanqies.tomatofn.") -> senderPackage.startsWith("com.fanqies.tomatofn")
            isAapsBroadcastAction(action) -> senderPackage in TRUSTED_AAPS_PACKAGES
            else -> senderPackage == appPackage
        }
    }

    private fun isAapsBroadcastAction(action: String): Boolean {
        return action.startsWith("info.nightscout.client.") ||
            action.startsWith("info.nightscout.androidaps.") ||
            action.startsWith("app.aaps.")
    }

    private companion object {
        const val TEST_ACTION = "io.aaps.copilot.BROADCAST_TEST_INGEST"
        val TRUSTED_AAPS_PACKAGES = setOf(
            "info.nightscout.androidaps",
            "info.nightscout.aaps",
            "app.aaps"
        )
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
