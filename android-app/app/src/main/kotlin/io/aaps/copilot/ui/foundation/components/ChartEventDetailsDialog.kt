package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventLifecycle
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.MealAbsorptionProfile
import io.aaps.copilot.domain.events.EventCompensationContext
import io.aaps.copilot.domain.events.EventContextLoader
import io.aaps.copilot.domain.events.EventContextStatus
import io.aaps.copilot.domain.profile.GlycemicIndexSource
import kotlinx.coroutines.CancellationException
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.ui.EventIconResources
import io.aaps.copilot.ui.motion.ClinicalGlyph
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.screens.ChartEventDetailsUi
import io.aaps.copilot.ui.foundation.screens.ChartEventHeading
import io.aaps.copilot.ui.foundation.screens.eventTypeLabelRes
import io.aaps.copilot.ui.foundation.screens.toChartEventDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ChartEventDetailsDialog(events: List<CompensationEvent>, nowTs: Long,
    loadContext: EventContextLoader = { EventCompensationContext() }, onDismiss: () -> Unit) {
    val details = remember(events, nowTs) { events.map { it.toChartEventDetails(nowTs) } }
    var contexts by remember(events) { mutableStateOf<Map<String, EventCompensationContext>>(emptyMap()) }
    val loader by rememberUpdatedState(loadContext)
    LaunchedEffect(events) {
        // Read once per selected-record version, not once per CGM tick/recomposition.
        contexts = events.filter { it.type == CompensationEventType.MEAL }.drop(8)
            .associate { it.localId to EventCompensationContext(EventContextStatus.OVERFLOW) }
        events.filter { it.type == CompensationEventType.MEAL }.take(8).forEach { event ->
            val result = try { loader(event) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { EventCompensationContext() }
            contexts = contexts + (event.localId to result)
        }
    }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val zone = ZoneId.systemDefault()
    val formatter = remember(locale, zone) { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone) }
    fun date(ts: Long) = formatter.format(Instant.ofEpochMilli(ts))
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("chartEventDetailsDialog"),
        title = { Text(stringResource(R.string.events_details_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = (configuration.screenHeightDp * 0.6f).coerceIn(120f, 440f).dp)
                    .testTag("eventClusterDetails"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(details, key = { _, item -> item.id }) { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        if (index > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClinicalGlyph(id = EventIconResources.byType.getValue(item.type).resourceName,
                                size = 22.dp, animate = index == 0 && events.firstOrNull()?.severity != EventSeverity.HIGH,
                                motionKey = "event-detail:${item.id}")
                            Text(stringResource(headingRes(item)), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f).testTag("eventDetailHeading:${item.id}"))
                        }
                        Text(date(item.startTs), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.title?.let { title ->
                            if (!title.equals(stringResource(headingRes(item)), true)) Text(title, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(sourceRes(item)), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.type == CompensationEventType.MEAL) {
                            Fact(R.string.event_detail_carbs, item.carbsGrams?.let {
                                "${UiFormatters.formatExactGrams(it, locale)} ${stringResource(R.string.unit_g)}"
                            })
                            item.recordedInsulinUnits?.let {
                                Fact(R.string.event_detail_insulin, "${UiFormatters.formatExactGrams(it, locale)} ${stringResource(R.string.unit_u)}")
                            }
                            val context = contexts[item.id]
                            Fact(R.string.event_detail_gi, context?.glycemicIndex?.let {
                                stringResource(if (it.source == GlycemicIndexSource.USER) R.string.event_detail_gi_user
                                    else R.string.event_detail_gi_catalog, UiFormatters.formatExactGrams(it.value, locale))
                            } ?: stringResource(if (context == null)
                                R.string.event_detail_context_loading else R.string.event_detail_gi_unknown))
                            item.mealProfile?.let { Fact(R.string.event_detail_food_type, stringResource(when (it) {
                                MealAbsorptionProfile.FAST -> R.string.food_profile_fast_label
                                MealAbsorptionProfile.MIXED -> R.string.food_profile_mixed_label
                                MealAbsorptionProfile.FATTY_PROTEIN -> R.string.food_profile_slow_label
                            })) }
                        }
                        if (item.heading == ChartEventHeading.BLOOD_CHECK) {
                            Fact(R.string.event_detail_blood, item.bloodMmol?.let {
                                "${UiFormatters.formatExactGrams(it, locale)} ${stringResource(R.string.unit_mmol_l)}"
                            })
                            Fact(R.string.event_detail_matched_cgm, item.matchedCgmMmol?.let {
                                "${UiFormatters.formatExactGrams(it, locale)} ${stringResource(R.string.unit_mmol_l)}"
                            })
                            item.matchedCgmTs?.takeIf { item.matchedCgmMmol != null }?.let { Fact(R.string.event_detail_matched_time, date(it)) }
                            Fact(R.string.event_detail_status, stringResource(when (item.bloodStatus) {
                                BloodGlucoseCheckStatus.VALID -> R.string.event_detail_reference_accepted
                                BloodGlucoseCheckStatus.REJECTED -> R.string.event_detail_reference_rejected
                                BloodGlucoseCheckStatus.STALE -> R.string.event_detail_reference_stale
                                BloodGlucoseCheckStatus.OUT_OF_WINDOW -> R.string.event_detail_reference_unpaired
                                null -> R.string.event_detail_unavailable
                            }))
                        }
                        item.intensity?.let { Fact(R.string.event_detail_intensity, stringResource(when (it) {
                            EventSeverity.LOW -> R.string.energy_activity_intensity_light
                            EventSeverity.MEDIUM -> R.string.energy_activity_intensity_medium
                            EventSeverity.HIGH -> R.string.energy_activity_intensity_high
                        })) }
                        item.durationMinutes?.let { Fact(R.string.event_detail_duration,
                            "${UiFormatters.formatExactGrams(it, locale)} ${stringResource(R.string.unit_minutes)}") }
                        item.steps?.let { Fact(R.string.event_detail_steps, it.toString()) }
                        item.contextEndTs?.takeIf { item.type != CompensationEventType.MEAL }
                            ?.let { Fact(R.string.event_detail_context_until, date(it)) }
                        item.lifecycle?.takeIf { item.type != CompensationEventType.MEAL }?.let { lifecycle ->
                            Text(stringResource(when (lifecycle) {
                                CompensationEventLifecycle.UPCOMING -> R.string.event_detail_upcoming
                                CompensationEventLifecycle.ACTIVE -> R.string.event_detail_context_active
                                CompensationEventLifecycle.ELAPSED -> R.string.event_detail_context_ended
                                CompensationEventLifecycle.CLOSED -> R.string.event_detail_recorded
                            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (item.type == CompensationEventType.MEAL) {
                            CompensationNotes(contexts[item.id], item.startTs, ::date)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("chartEventDetailsClose")) {
                Text(stringResource(R.string.events_close))
            }
        }
    )
}

@Composable
private fun CompensationNotes(context: EventCompensationContext?, startTs: Long, date: (Long) -> String) {
    Text(stringResource(R.string.event_detail_review_title), style = MaterialTheme.typography.titleSmall)
    if (context == null) {
        Text(stringResource(R.string.event_detail_context_loading), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (context.status != EventContextStatus.READY) {
        Text(stringResource(if (context.status == EventContextStatus.SOURCE_CHANGED) R.string.event_detail_context_changed
            else R.string.event_detail_context_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    fun number(value: Double) = String.format(locale, "%.1f", value)
    fun note(id: Int, vararg args: Any): Pair<Int, List<Any>> = id to args.toList()
    val notes = buildList {
        if (context.sensorConcern) add(note(R.string.event_detail_sensor_concern))
        context.glucose?.let { glucose ->
            add(note(R.string.event_detail_glucose_review, ((glucose.throughTs - startTs) / 60_000).toInt(),
                number(glucose.baseline), number(glucose.minimum), number(glucose.peak), glucose.peakAfterMinutes))
            if (glucose.minimum < 3.9 || glucose.baseline < 3.9) add(note(R.string.event_detail_low_review))
            else if (glucose.peak >= 10.0) add(note(R.string.event_detail_high_review))
        } ?: add(note(R.string.event_detail_glucose_insufficient))
        if (context.overlappingMeal) add(note(R.string.event_detail_overlap_review))
        context.isf?.let { isf ->
            val percent = number(kotlin.math.abs(isf.changePercent))
            add(note(R.string.event_detail_isf_review, number(isf.before), number(isf.atEvent),
                (if (isf.changePercent >= 0) "+" else "−") + percent,
                date(isf.beforeTs), date(isf.atEventTs)))
            if (kotlin.math.abs(isf.changePercent) >= 0.05) add(note(if (isf.changePercent > 0)
                R.string.event_detail_isf_up else R.string.event_detail_isf_down))
        }
        context.sensorAge?.let { sensor ->
            add(note(R.string.event_detail_sensor_age, number(sensor.hours / 24.0), date(sensor.observedAt)))
        }
        add(note(R.string.event_detail_timing_unknown))
    }
    notes.forEach { (id, args) ->
        Text(stringResource(id, *args.toTypedArray()), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Fact(label: Int, value: String?) {
    val labelText = stringResource(label)
    val valueText = value ?: stringResource(R.string.event_detail_unavailable)
    Text(buildAnnotatedString {
        append(labelText); append(": ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(valueText) }
    }, style = MaterialTheme.typography.bodyMedium)
}

private fun headingRes(item: ChartEventDetailsUi): Int = when (item.heading) {
    ChartEventHeading.TYPE -> eventTypeLabelRes(item.type)
    ChartEventHeading.BLOOD_CHECK -> R.string.events_blood_check
    ChartEventHeading.CALIBRATION -> R.string.event_detail_calibration
    ChartEventHeading.SENSOR_ISSUE -> R.string.event_detail_sensor_issue
    ChartEventHeading.SENSOR_EVENT -> R.string.event_detail_sensor_event
    ChartEventHeading.DELIVERY_ISSUE -> R.string.event_detail_delivery_issue
    ChartEventHeading.DELIVERY_DIAGNOSTIC -> R.string.event_detail_delivery_diagnostic
    ChartEventHeading.UAM -> R.string.event_detail_uam
    ChartEventHeading.PLANNED_ACTIVITY -> R.string.event_detail_planned_activity
    ChartEventHeading.RECORDED_ACTIVITY -> R.string.event_detail_recorded_activity
}

private fun sourceRes(item: ChartEventDetailsUi): Int = if (item.heading == ChartEventHeading.UAM) {
    R.string.event_detail_model
} else when (item.source) {
    EventSource.USER -> R.string.event_detail_manual
    EventSource.AAPS -> R.string.event_detail_aaps
    EventSource.AUTOMATIC -> R.string.event_detail_automatic
}
