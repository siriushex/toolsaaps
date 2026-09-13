package io.aaps.copilot.config

import io.aaps.copilot.domain.predict.UamExportMode

enum class UamExportUiMode {
    OFF,
    OBSERVE,
    AUTO
}

object UamExportUiModePolicy {

    fun resolve(settings: AppSettings): UamExportUiMode {
        if (!settings.enableUamInference) return UamExportUiMode.OFF
        return resolve(
            enabled = settings.enableUamExportToAaps,
            persistedMode = settings.uamExportMode,
            dryRun = settings.dryRunExport
        )
    }

    fun resolve(
        enabled: Boolean,
        persistedMode: UamExportMode,
        dryRun: Boolean
    ): UamExportUiMode {
        if (!enabled || persistedMode == UamExportMode.OFF) {
            return UamExportUiMode.OFF
        }
        if (persistedMode != UamExportMode.INCREMENTAL) {
            return UamExportUiMode.OBSERVE
        }
        return if (dryRun) UamExportUiMode.OBSERVE else UamExportUiMode.AUTO
    }

    fun apply(settings: AppSettings, mode: UamExportUiMode): AppSettings = when (mode) {
        UamExportUiMode.OFF -> settings.copy(
            enableUamExportToAaps = false,
            uamExportMode = UamExportMode.OFF,
            dryRunExport = true
        )
        UamExportUiMode.OBSERVE -> settings.copy(
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.INCREMENTAL,
            dryRunExport = true
        )
        UamExportUiMode.AUTO -> settings.copy(
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.INCREMENTAL,
            dryRunExport = false
        )
    }
}

object UamExportUiModeCommand {

    fun parse(modeRaw: String): UamExportUiMode? =
        UamExportUiMode.entries.firstOrNull { it.name == modeRaw }

    fun apply(settings: AppSettings, modeRaw: String): AppSettings? =
        if (!settings.enableUamInference) {
            null
        } else {
            parse(modeRaw)?.let { mode -> UamExportUiModePolicy.apply(settings, mode) }
        }

    fun resolve(
        enabled: Boolean,
        persistedModeRaw: String,
        dryRun: Boolean
    ): UamExportUiMode {
        val persistedMode = UamExportMode.entries.firstOrNull { it.name == persistedModeRaw }
            ?: return UamExportUiMode.OFF
        return UamExportUiModePolicy.resolve(enabled, persistedMode, dryRun)
    }
}
