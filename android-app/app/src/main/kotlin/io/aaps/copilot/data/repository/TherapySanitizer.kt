package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.util.Locale

object TherapySanitizer {

    fun filterEntities(events: List<TherapyEventEntity>): List<TherapyEventEntity> =
        events.filter(::isUsable)

    private fun isUsable(event: TherapyEventEntity): Boolean {
        val type = event.type.lowercase(Locale.US)

        if (isBroadcastArtifact(event, type)) return false

        val payload = parsePayload(event.payloadJson)
        return when (type) {
            "correction_bolus" -> {
                val units = payload.doubleOf("units", "bolusUnits", "insulin", "enteredInsulin")
                    ?: return false
                units in 0.05..15.0
            }
            "meal_bolus" -> {
                val grams = payload.doubleOf("grams", "carbs", "enteredCarbs", "mealCarbs")
                    ?: return false
                val units = payload.doubleOf("bolusUnits", "units", "insulin", "enteredInsulin")
                    ?: return false
                if (grams !in 1.0..300.0) return false
                if (units !in 0.05..25.0) return false
                val ratio = grams / units
                ratio in 1.5..80.0
            }
            "carbs" -> {
                val grams = payload.doubleOf("grams", "carbs", "enteredCarbs", "mealCarbs")
                    ?: return false
                grams in 1.0..300.0
            }
            "temp_target" -> {
                val duration = payload.intOf("duration", "durationInMinutes")
                val low = payload.doubleOf("targetBottom", "target_bottom", "targetLow")
                val high = payload.doubleOf("targetTop", "target_top", "targetHigh")
                val durationOk = duration == null || duration in 5..720
                val lowOk = low == null || isTargetInKnownRange(low)
                val highOk = high == null || isTargetInKnownRange(high)
                durationOk && lowOk && highOk
            }
            else -> true
        }
    }

    private fun isBroadcastArtifact(event: TherapyEventEntity, type: String): Boolean {
        val isBroadcastId = event.id.startsWith("br-aaps_broadcast-") || event.id.startsWith("br-local_broadcast-")
        if (!isBroadcastId) return false
        return type == "correction_bolus" || type == "meal_bolus" || type == "carbs" || type == "temp_target"
    }

    private fun parsePayload(payloadJson: String): Map<String, String> {
        val text = payloadJson.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return emptyMap()
        val out = linkedMapOf<String, String>()
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|([-0-9.,]+)|true|false|null)")
        regex.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val quotedValue = match.groupValues[2]
            val numericValue = match.groupValues[3]
            val value = quotedValue.ifBlank { numericValue }
            if (key.isNotBlank() && value.isNotBlank()) {
                out[key] = value
            }
        }
        return out
    }

    private fun Map<String, String>.doubleOf(vararg keys: String): Double? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun Map<String, String>.intOf(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.toIntOrNull() ?: this[key]?.replace(",", ".")?.toDoubleOrNull()?.toInt()
        }
    }

    private fun isTargetInKnownRange(value: Double): Boolean {
        return value in 2.2..15.0 || value in 40.0..270.0
    }
}
