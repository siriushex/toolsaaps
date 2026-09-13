package io.aaps.copilot.ui

import io.aaps.copilot.R
import io.aaps.copilot.domain.events.CompensationEventType

enum class EventIconSource { GENERATED_WEBP }

data class EventIconResource(
    val resourceId: Int,
    val resourceName: String,
    val domain: String,
    val source: EventIconSource
)

object EventIconResources {
    val byType: Map<CompensationEventType, EventIconResource> = mapOf(
        CompensationEventType.MEAL to icon(R.drawable.event_meal, "event_meal", "nutrition"),
        CompensationEventType.ACTIVITY to icon(R.drawable.event_activity, "event_activity", "movement"),
        CompensationEventType.STRESS to icon(R.drawable.event_stress, "event_stress", "stress"),
        CompensationEventType.ILLNESS to icon(R.drawable.event_illness, "event_illness", "illness"),
        CompensationEventType.SLEEP to icon(R.drawable.event_sleep, "event_sleep", "sleep"),
        CompensationEventType.HORMONAL to icon(R.drawable.event_hormonal, "event_hormonal", "hormonal"),
        CompensationEventType.MEDICATION_STEROID to icon(R.drawable.event_medication_steroid, "event_medication_steroid", "medication"),
        CompensationEventType.ALCOHOL to icon(R.drawable.event_alcohol, "event_alcohol", "alcohol"),
        CompensationEventType.SENSOR_CALIBRATION to icon(R.drawable.event_sensor_calibration, "event_sensor_calibration", "sensor"),
        CompensationEventType.INFUSION_PUMP_INSULIN to icon(R.drawable.event_infusion_pump_insulin, "event_infusion_pump_insulin", "insulin"),
        CompensationEventType.CUSTOM to icon(R.drawable.event_custom, "event_custom", "custom"),
        CompensationEventType.MENSTRUAL_CYCLE to icon(R.drawable.event_menstrual_cycle, "event_menstrual_cycle", "cycle")
    )

    private fun icon(resourceId: Int, resourceName: String, domain: String) =
        EventIconResource(resourceId, resourceName, domain, EventIconSource.GENERATED_WEBP)
}
