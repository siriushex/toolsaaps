package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents
import java.util.Locale

object TherapySanitizer {

    fun filterEntities(events: List<TherapyEventEntity>): List<TherapyEventEntity> =
        events.filter(::isUsable)

    fun toDomainEvents(events: List<TherapyEventEntity>, gson: Gson): List<TherapyEvent> =
        filterEntities(events).map { it.toDomain(gson) }

    private fun isUsable(event: TherapyEventEntity): Boolean {
        val type = event.type.lowercase(Locale.US)

        if (isLocalBroadcastArtifact(event.id, type)) return false

        val payload = BoundedClinicalPayloadParser.parse(event.payloadJson) ?: return false
        return when (type) {
            "correction_bolus", "bolus", "insulin" -> {
                val components = resolvedComponents(type, payload)
                if (!components.wholeEventValid && !components.canonicalCarbAuthoritative) return false
                val maxUnits = if (type == "correction_bolus") 15.0 else 25.0
                components.insulinU?.let { it in 0.05..maxUnits } == true
            }
            "meal_bolus" -> {
                val components = resolvedComponents(type, payload)
                if (!components.wholeEventValid && !components.canonicalCarbAuthoritative) return false
                val grams = components.carbsG?.takeIf { it in 1.0..300.0 }
                val units = components.insulinU?.takeIf { it in 0.05..25.0 }
                when {
                    grams != null && units != null -> grams / units in 1.5..80.0
                    else -> grams != null || units != null
                }
            }
            "carbs" -> {
                resolvedComponents(type, payload).carbsG?.let { it in 1.0..300.0 } == true
            }
            "temp_target" -> {
                val duration = payload.number("duration", "durationInMinutes")?.toInt()
                val low = payload.number("targetBottom", "target_bottom", "targetLow")
                val high = payload.number("targetTop", "target_top", "targetHigh")
                val durationOk = duration == null || duration in 5..720
                val lowOk = low == null || isTargetInKnownRange(low)
                val highOk = high == null || isTargetInKnownRange(high)
                durationOk && lowOk && highOk
            }
            else -> true
        }
    }

    private fun resolvedComponents(type: String, payload: ParsedClinicalPayload) =
        resolveTherapyComponents(
            type = type,
            payload = payload.scalarValues(),
            componentTrust = payload.componentTrust()
        )

    internal fun isLocalBroadcastArtifact(id: String, type: String): Boolean {
        val isBroadcastId = id.startsWith("br-local_broadcast-")
        if (!isBroadcastId) return false
        val normalizedType = type.lowercase(Locale.US)
        return normalizedType == "correction_bolus" ||
            normalizedType == "meal_bolus" ||
            normalizedType == "carbs" ||
            normalizedType == "temp_target"
    }

    private fun isTargetInKnownRange(value: Double): Boolean {
        return value in 2.2..15.0 || value in 40.0..270.0
    }
}
