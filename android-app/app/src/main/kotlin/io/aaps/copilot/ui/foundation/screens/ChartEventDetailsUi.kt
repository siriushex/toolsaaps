package io.aaps.copilot.ui.foundation.screens

import com.google.gson.JsonParser
import io.aaps.copilot.data.repository.CopilotContextNoteMarker
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventLifecycle
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.MealAbsorptionProfile
import io.aaps.copilot.domain.events.eventDisplayQuantities
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import java.util.Locale

internal enum class ChartEventHeading {
    TYPE, BLOOD_CHECK, CALIBRATION, SENSOR_ISSUE, SENSOR_EVENT,
    DELIVERY_ISSUE, DELIVERY_DIAGNOSTIC, UAM, PLANNED_ACTIVITY, RECORDED_ACTIVITY
}

internal data class ChartEventDetailsUi(
    val id: String,
    val type: CompensationEventType,
    val heading: ChartEventHeading,
    val title: String?,
    val note: String?,
    val startTs: Long,
    val contextEndTs: Long?,
    val source: EventSource,
    val lifecycle: CompensationEventLifecycle?,
    val carbsGrams: Double? = null,
    val recordedInsulinUnits: Double? = null,
    val mealProfile: MealAbsorptionProfile? = null,
    val bloodMmol: Double? = null,
    val matchedCgmMmol: Double? = null,
    val matchedCgmTs: Long? = null,
    val bloodStatus: BloodGlucoseCheckStatus? = null,
    val intensity: EventSeverity? = null,
    val durationMinutes: Double? = null,
    val steps: Long? = null
)

internal fun CompensationEvent.toChartEventDetails(nowTs: Long): ChartEventDetailsUi {
    val rawHeading = when (subtype.uppercase(Locale.US)) {
        "MANUAL_BLOOD_CHECK" -> ChartEventHeading.BLOOD_CHECK
        "CALIBRATION" -> ChartEventHeading.CALIBRATION
        "POSSIBLE_SENSOR_ISSUE" -> ChartEventHeading.SENSOR_ISSUE
        "SENSOR_EVENT" -> ChartEventHeading.SENSOR_EVENT
        "DELIVERY_PROBLEM" -> ChartEventHeading.DELIVERY_ISSUE
        "DELIVERY_DIAGNOSTIC" -> ChartEventHeading.DELIVERY_DIAGNOSTIC
        "UAM" -> ChartEventHeading.UAM
        "PLANNED" -> if (type == CompensationEventType.ACTIVITY) ChartEventHeading.PLANNED_ACTIVITY else ChartEventHeading.TYPE
        "ACTUAL" -> if (type == CompensationEventType.ACTIVITY) ChartEventHeading.RECORDED_ACTIVITY else ChartEventHeading.TYPE
        else -> ChartEventHeading.TYPE
    }
    val heading = rawHeading.takeIf { when (it) {
        ChartEventHeading.BLOOD_CHECK, ChartEventHeading.CALIBRATION, ChartEventHeading.SENSOR_ISSUE,
        ChartEventHeading.SENSOR_EVENT -> type == CompensationEventType.SENSOR_CALIBRATION
        ChartEventHeading.DELIVERY_ISSUE -> type == CompensationEventType.INFUSION_PUMP_INSULIN
        ChartEventHeading.UAM, ChartEventHeading.DELIVERY_DIAGNOSTIC -> type == CompensationEventType.CUSTOM
        else -> true
    } } ?: ChartEventHeading.TYPE
    val point = heading in setOf(ChartEventHeading.BLOOD_CHECK, ChartEventHeading.CALIBRATION, ChartEventHeading.SENSOR_EVENT)
    val cleanTitle = humanEventText(title)?.takeUnless {
        it.lowercase(Locale.US) in MACHINE_EVENT_TITLES || it.equals(type.name, true) ||
            it.equals(subtype, true)
    }
    val cleanNote = humanEventText(note)?.takeUnless { it == cleanTitle }
    val meal = type == CompensationEventType.MEAL
    val blood = heading == ChartEventHeading.BLOOD_CHECK && provenance == "blood_glucose_checks"
    val activity = type == CompensationEventType.ACTIVITY
    val quantities = if (meal) eventDisplayQuantities("meal", attributes) else null
    val carbs = if (provenance == "therapy_events") {
        attributes.finite("displayCarbsG", 0.0..500.0)
    } else quantities?.carbsGrams
    return ChartEventDetailsUi(
        id = localId, type = type, heading = heading, title = cleanTitle, note = cleanNote,
        startTs = startTs, contextEndTs = endTs.takeIf { !point && it > startTs }, source = source,
        lifecycle = lifecycleAt(nowTs).takeUnless { point },
        carbsGrams = carbs.takeIf { meal },
        recordedInsulinUnits = attributes.finite("displayInsulinU", 0.0..100.0)
            .takeIf { meal && provenance == "therapy_events" },
        mealProfile = enumValueOrNull<MealAbsorptionProfile>(attributes["profile"]).takeIf { meal },
        bloodMmol = attributes.finite("bloodMmol", 0.1..100.0).takeIf { blood },
        matchedCgmMmol = attributes.finite("matchedCgmMmol", 0.1..100.0).takeIf { blood },
        matchedCgmTs = attributes["matchedCgmTs"]?.toLongOrNull()?.takeIf { blood && it > 0 },
        bloodStatus = enumValueOrNull<BloodGlucoseCheckStatus>(attributes["status"]).takeIf { blood },
        intensity = enumValueOrNull<EventSeverity>(attributes["intensity"]).takeIf { activity },
        durationMinutes = attributes.finite("durationMinutes", 0.0..10_080.0).takeIf { activity },
        steps = attributes["steps"]?.toLongOrNull()?.takeIf { activity && it >= 0 }
    )
}

private fun Map<String, String>.finite(key: String, range: ClosedFloatingPointRange<Double>): Double? =
    get(key)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
    enumValues<T>().firstOrNull { it.name.equals(value?.trim(), true) }

private fun humanEventText(value: String?): String? {
    val text = value?.let(CopilotContextNoteMarker::stripProtectedMarkers)?.trim() ?: return null
    if (text.startsWith("{") || text.startsWith("[")) {
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (json?.isJsonObject == true || json?.isJsonArray == true || text.startsWith("{\"")) return null
    }
    return text.lineSequence().map(String::trim)
        .filterNot { it.contains("UAM_ENGINE|", true) || it.contains("COPILOT_CONTEXT_V1", true) }
        .joinToString("\n").trim('|', ' ', '\n').takeIf(String::isNotBlank)
}

private val MACHINE_EVENT_TITLES = setOf("meal", "meal_bolus", "carbs", "activity", "exercise",
    "stress", "illness", "sleep", "hormonal", "steroid", "alcohol", "note", "custom",
    "blood glucose check", "calibration", "sensor change", "sensor_change", "sensor_start",
    "uam context", "possible delivery issue", "delivery diagnostic", "planned activity", "actual activity",
    "possible sensor issue", "aaps note", "high activity", "medium activity", "low activity")
