package io.aaps.copilot.service

import io.aaps.copilot.config.AppSettings
import java.util.concurrent.atomic.AtomicBoolean

internal fun AppSettings.localObservationRuntimeAllowed(): Boolean =
    therapyActionsArmed || localObservationEnabled

internal suspend fun runAdmittedLocalRuntimeCycle(
    therapyArmed: Boolean,
    observationEnabled: Boolean,
    powerSaveActive: Boolean,
    normal: suspend () -> Unit,
    local: suspend () -> Unit
) {
    if (powerSaveActive) return
    when {
        therapyArmed -> normal()
        observationEnabled -> local()
    }
}

// Process hint only. Service/worker admission also reads the install-bound preference.
internal object LocalObservationRuntimeState {
    private val enabled = AtomicBoolean(false)

    fun update(settings: AppSettings) { enabled.set(settings.localObservationEnabled) }

    fun isAllowed(): Boolean = TherapyActionRuntimeState.isArmed() || enabled.get()
}
