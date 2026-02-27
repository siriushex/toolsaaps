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
                val senderPackage = senderPackageOrNull()
                if (!isTrustedSender(action = action, senderPackage = senderPackage, appPackage = context.packageName)) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf(
                            "reason" to "untrusted_sender",
                            "action" to action,
                            "senderPackage" to senderPackage.orEmpty()
                        )
                    )
                    return@runCatching
                }

                val settings = app.container.settingsStore.settings.first()
                if (!settings.localBroadcastIngestEnabled) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf("reason" to "disabled_in_settings", "action" to action)
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
                            "therapy" to result.therapyImported
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

    private fun isTrustedSender(action: String, senderPackage: String?, appPackage: String): Boolean {
        if (senderPackage.isNullOrBlank()) return true
        if (action == TEST_ACTION) {
            return senderPackage == "com.android.shell" || senderPackage == appPackage
        }
        return when {
            action.startsWith("com.eveningoutpost.dexdrip.") -> senderPackage.startsWith("com.eveningoutpost.dexdrip")
            action.startsWith("info.nightscout.client.") -> senderPackage in TRUSTED_AAPS_PACKAGES
            else -> senderPackage == appPackage
        }
    }

    private companion object {
        const val TEST_ACTION = "io.aaps.copilot.BROADCAST_TEST_INGEST"
        val TRUSTED_AAPS_PACKAGES = setOf(
            "info.nightscout.androidaps",
            "info.nightscout.aaps"
        )
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
