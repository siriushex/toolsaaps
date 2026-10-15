package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventCompensationContext
import io.aaps.copilot.domain.events.EventCompensationReview
import io.aaps.copilot.domain.events.EventContextSample
import io.aaps.copilot.domain.events.EventContextStatus
import io.aaps.copilot.domain.events.EventSensorAge
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import io.aaps.copilot.domain.profile.MealTherapyReferenceTrust
import io.aaps.copilot.domain.profile.toMealTherapyReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bounded, read-only snapshot when a meal's popup is opened. */
class EventCompensationContextRepository(private val db: CopilotDatabase, private val gson: Gson) {
    suspend fun load(selected: CompensationEvent, nowTs: Long): EventCompensationContext = withContext(Dispatchers.IO) {
        if (selected.type != CompensationEventType.MEAL || selected.provenance != "therapy_events" ||
            selected.startTs > nowTs) return@withContext EventCompensationContext()
        try {
            db.withTransaction {
                val row = db.therapyDao().byId(selected.localId)
                    ?: return@withTransaction EventCompensationContext(EventContextStatus.SOURCE_CHANGED)
                val therapy = TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
                    ?: return@withTransaction EventCompensationContext(EventContextStatus.SOURCE_CHANGED)
                val timeline = EventTimelineRepository(gson)
                val current = timeline.prepareTherapyEvent(therapy)
                if (current == null || current.startTs != selected.startTs || current.type != selected.type ||
                    current.revision != selected.revision || current.attributes != selected.attributes ||
                    current.displayMealIdentity != selected.displayMealIdentity ||
                    current.displayMealRevision != selected.displayMealRevision ||
                    current.displayCarbsGrams != selected.displayCarbsGrams ||
                    current.displayRecordedInsulinUnits != selected.displayRecordedInsulinUnits) {
                    return@withTransaction EventCompensationContext(EventContextStatus.SOURCE_CHANGED)
                }
                val reference = therapy.toMealTherapyReference()
                val override = if (reference.trust == MealTherapyReferenceTrust.TRUSTED &&
                    !therapy.componentTrust.legacyValidityConflict) {
                    db.energyProfileDao().matchingMealOverrideForIdentity(reference.identity, reference.revision!!)
                        ?.takeIf { it.updatedAtMs <= nowTs }
                } else null
                val gi = override?.let { MealGlycemicIndex.fromStored(it.glycemicIndexValue,
                    it.glycemicIndexSource, it.glycemicIndexReference) }
                val start = selected.startTs
                val through = start + minOf(EventCompensationReview.WINDOW, nowTs - start)
                val from = (start - 90 * EventCompensationReview.MINUTE).coerceAtLeast(0)
                val glucoseRows = db.glucoseDao().betweenForAlertAi(
                    (start - 10 * EventCompensationReview.MINUTE).coerceAtLeast(0), through, 601)
                val telemetry = db.telemetryDao().betweenForAlertAi(from, through,
                    listOf("isf_value", "sensor_age_hours", "sensor_quality_blocked", "sensor_quality_suspect_false_low"), 1201)
                val nearby = db.therapyDao().betweenForAlertAiTimeline(
                    (start - 10 * EventCompensationReview.MINUTE).coerceAtLeast(0), through, 401)
                if (glucoseRows.size > 600 || telemetry.size > 1200 || nearby.size > 400) {
                    return@withTransaction EventCompensationContext(EventContextStatus.OVERFLOW, glycemicIndex = gi)
                }
                val sensorConcern = nearby.any { it.type.lowercase() in SENSOR_EVENTS } ||
                    telemetry.any { it.key in setOf("sensor_quality_blocked", "sensor_quality_suspect_false_low") &&
                    it.ts >= start - 10 * EventCompensationReview.MINUTE &&
                    it.value?.let { value -> value.isFinite() && value >= 0.5 } == true }
                val glucose = if (sensorConcern || glucoseRows.any { it.quality != "OK" || it.source !in CGM_SOURCES }) null else
                    EventCompensationReview.glucose(start, through,
                        glucoseRows.map { EventContextSample(it.ts, it.mmol, it.source) })
                val isfRows = telemetry.filter { it.key == "isf_value" && it.ts <= start }
                val isf = if (isfRows.any { it.quality != "OK" || it.unit !in ISF_UNITS ||
                    it.value == null || !it.value.isFinite() }) null else EventCompensationReview.isf(start,
                    isfRows.map { EventContextSample(it.ts,
                        if (it.unit == "mg/dL/U") it.value!! / 18.0 else it.value!!, it.source) })
                val ages = telemetry.filter { it.key == "sensor_age_hours" && it.ts in
                    (start - 10 * EventCompensationReview.MINUTE)..start }
                val age = ages.maxOfOrNull { it.ts }?.let { ts ->
                    ages.filter { it.ts == ts }.takeIf { candidates ->
                        candidates.all { it.quality == "OK" && it.unit == "h" && it.source.isNotBlank() &&
                            it.value?.let { v -> v.isFinite() && v in 0.0..720.0 } == true } &&
                            candidates.map { it.value to it.source }.distinct().size == 1
                    }?.first()?.let { EventSensorAge(it.value!!, it.ts, it.source) }
                }
                val overlapping = TherapySanitizer.toDomainEvents(nearby, gson).any {
                    it.ts > start && timeline.prepareTherapyEvent(it)?.type == CompensationEventType.MEAL
                }
                EventCompensationContext(EventContextStatus.READY, gi, glucose, isf, age, sensorConcern, overlapping)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EventCompensationContext()
        }
    }

    private companion object {
        val ISF_UNITS = setOf("mmol/L/U", "mg/dL/U")
        val CGM_SOURCES = setOf("aaps_broadcast", "nightscout", "xdrip_broadcast", "local_nightscout_entry", "local_broadcast", "aaps_root_db")
        val SENSOR_EVENTS = setOf("sensor_change", "cgm_sensor_change", "sensor_start", "sensor_started", "sensor_error", "sensor_failure")
    }
}
