package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettingsStore
import kotlinx.coroutines.flow.first

class RootDbExperimentalRepository(
    private val settingsStore: AppSettingsStore,
    private val auditLogger: AuditLogger
) {

    suspend fun syncIfEnabled() {
        val settings = settingsStore.settings.first()
        if (!settings.rootExperimentalEnabled) return

        // Reserved for rooted devices only. For non-root builds the app uses
        // Nightscout + exports as the primary, supported integration path.
        auditLogger.warn(
            "root_db_mode_not_implemented",
            mapOf("reason" to "experimental_stub", "enabled" to true)
        )
    }
}
