package io.aaps.copilot

import android.app.Application
import androidx.work.Configuration
import io.aaps.copilot.service.AppVisibilityTracker
import io.aaps.copilot.service.AppContainer
import io.aaps.copilot.service.CopilotDatabaseIntegrityManager
import io.aaps.copilot.service.LocalNightscoutSecretClipboard
import io.aaps.copilot.scheduler.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CopilotApp : Application(), Configuration.Provider, AlertNavigationHost {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppVisibilityTracker.markForeground(false)
        LocalNightscoutSecretClipboard.reconcile(this)
        val startupRecovery = CopilotDatabaseIntegrityManager.restoreIfNeeded(this)
        container = AppContainer(this)
        WorkScheduler.scheduleEatingWindowRefresh(this)
        startupRecovery?.let { result ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val metadata = buildMap<String, Any?> {
                    put("startupProbe", result.startupProbe)
                    put("quickCheck", result.quickCheck)
                    put("restored", result.restored)
                    put("restoredFrom", result.restoredFrom)
                    put("backupQuickCheck", result.backupQuickCheck)
                    put("snapshotQuickCheck", result.snapshotQuickCheck)
                    put("error", result.error)
                }
                if (result.restored) {
                    container.auditLogger.warn("db_integrity_restore_completed", metadata)
                } else {
                    container.auditLogger.error("db_integrity_restore_failed", metadata)
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun enqueueAlertNavigation(candidate: String?): Boolean =
        container.alertNavigationCoordinator.enqueue(candidate)
}
