package io.aaps.copilot.ui

import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.CompensationEventType

internal enum class ManualCompensationEventRoute {
    CONTEXT,
    MEAL_ACTION,
    PLANNED_ACTIVITY,
    BLOOD_CHECK,
    READ_ONLY_DIAGNOSTIC
}

internal fun manualCompensationEventRoute(
    type: CompensationEventType
): ManualCompensationEventRoute = when {
    CompensationEventManualPolicy.isContextOnly(type) -> ManualCompensationEventRoute.CONTEXT
    type == CompensationEventType.MEAL -> ManualCompensationEventRoute.MEAL_ACTION
    type == CompensationEventType.ACTIVITY -> ManualCompensationEventRoute.PLANNED_ACTIVITY
    type == CompensationEventType.SENSOR_CALIBRATION -> ManualCompensationEventRoute.BLOOD_CHECK
    else -> ManualCompensationEventRoute.READ_ONLY_DIAGNOSTIC
}
