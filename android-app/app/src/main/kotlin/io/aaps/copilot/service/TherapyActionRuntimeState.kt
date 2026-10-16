package io.aaps.copilot.service

import io.aaps.copilot.config.AppSettings
import java.util.concurrent.atomic.AtomicBoolean

object TherapyActionRuntimeState {
    private val armed = AtomicBoolean(false)

    fun update(settings: AppSettings) {
        LocalObservationRuntimeState.update(settings)
        armed.set(settings.therapyActionsArmed)
    }

    fun setArmed(value: Boolean) {
        armed.set(value)
    }

    fun isArmed(): Boolean = armed.get()
}
