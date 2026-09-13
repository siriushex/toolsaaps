package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventDurationPolicy
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.activity.ActualActivityEpisodeBuilder
import io.aaps.copilot.domain.activity.PhysicalActivityBucket
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.util.ordinaryExceptionOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.Locale

data class ActualActivityTimelineSample(
    val timestamp: Long,
    val source: String,
    val activityRatio: Double,
    val label: String? = null,
    val quality: String = "OK"
)

data class DeliveryDiagnosticTimelineSample(
    val timestamp: Long,
    val source: String,
    val state: DeliveryTrustState
)

data class EventTimelineSources(
    val therapyEvents: List<TherapyEvent> = emptyList(),
    val contextTags: List<PhysioContextTagEntity> = emptyList(),
    val plannedActivity: List<CompensationEvent> = emptyList(),
    val actualActivity: List<CompensationEvent> = emptyList(),
    val calibrationEvents: List<CompensationEvent> = emptyList(),
    val deliveryDiagnostics: List<CompensationEvent> = emptyList()
)

internal inline fun <T> parseAlertContextEnumOrDefault(
    raw: String,
    fallback: T,
    parse: (String) -> T
): T = ordinaryExceptionOrNull { parse(raw) } ?: fallback

class EventTimelineRepository(private val gson: Gson = Gson()) {
    internal fun isAlertCauseContextTherapyEvent(event: TherapyEvent): Boolean {
        val normalizedType = event.type.trim().lowercase(Locale.US)
        return normalizedType in ACTIVITY_TYPES ||
            normalizedType in CALIBRATION_TYPES ||
            normalizedType in SENSOR_FAILURE_TYPES ||
            normalizedType in SENSOR_LIFECYCLE_TYPES ||
            normalizedType in DELIVERY_PROBLEM_TYPES ||
            normalizedType in ALERT_CONTEXT_TYPES
    }

    fun aggregate(
        sources: EventTimelineSources,
        nowTs: Long,
        reserveInput: (Int) -> Unit = {},
        reserveOutput: (Int) -> Unit = {}
    ): List<CompensationEvent> {
        val normalized = buildList {
            sources.therapyEvents
                .filterNot(::isProtectedContextNoteEcho)
                .forEach { event -> therapyEvent(event, reserveInput)?.let(::add) }
            addOtherSources(sources, reserveInput)
        }
        return aggregateNormalized(normalized, nowTs, reserveOutput)
    }

    // Pure source conversion only. Lifecycle and deduplication still run at each captured UI time.
    internal fun prepareTherapyEvent(event: TherapyEvent): CompensationEvent? =
        if (isProtectedContextNoteEcho(event)) null else therapyEvent(event) {}?.let { prepared ->
            prepared.copy(attributes = Collections.unmodifiableMap(LinkedHashMap(prepared.attributes)))
        }

    internal fun aggregatePreparedTherapy(
        sources: EventTimelineSources,
        preparedTherapyEvents: List<CompensationEvent>,
        nowTs: Long
    ): List<CompensationEvent> {
        require(sources.therapyEvents.isEmpty())
        val normalized = buildList {
            addAll(preparedTherapyEvents)
            addOtherSources(sources) {}
        }
        return aggregateNormalized(normalized, nowTs) {}
    }

    private fun MutableList<CompensationEvent>.addOtherSources(
        sources: EventTimelineSources,
        reserveInput: (Int) -> Unit
    ) {
        sources.contextTags.forEach { tag -> add(tag.toEvent(reserveInput)) }
        addRetained(sources.plannedActivity, reserveInput)
        addRetained(sources.actualActivity, reserveInput)
        addRetained(sources.calibrationEvents, reserveInput)
        addRetained(sources.deliveryDiagnostics, reserveInput)
    }

    private fun aggregateNormalized(
        normalized: List<CompensationEvent>,
        nowTs: Long,
        reserveOutput: (Int) -> Unit
    ): List<CompensationEvent> {
        val retained = linkedMapOf<String, CompensationEvent>()
        normalized.forEach { event ->
            val current = retained[event.localId]
            if (current == null || aggregateComparator(nowTs).compare(event, current) > 0) {
                retained[event.localId] = event
            }
        }
        reserveOutput(retained.size)
        return retained.values
            .map { event -> withDefaultDuration(event, nowTs) }
            .sortedWith(compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId })
    }

    private fun MutableList<CompensationEvent>.addRetained(
        events: List<CompensationEvent>,
        reserveInput: (Int) -> Unit
    ) {
        reserveInput(events.size)
        addAll(events)
    }

    private fun aggregateComparator(nowTs: Long) = Comparator<CompensationEvent> { left, right ->
        compareValuesBy(left, right, CompensationEvent::revision)
            .takeIf { it != 0 }
            ?: sourcePrecedence(left.source).compareTo(sourcePrecedence(right.source))
                .takeIf { it != 0 }
            ?: aggregateEndTs(left, nowTs).compareTo(aggregateEndTs(right, nowTs))
                .takeIf { it != 0 }
            ?: left.localId.compareTo(right.localId)
    }

    private fun aggregateEndTs(event: CompensationEvent, nowTs: Long): Long {
        if (event.provenance == ACTUAL_ACTIVITY_EPISODE_PROVENANCE) return event.endTs
        if (event.provenance == "derived_delivery_trust" && event.status == CompensationEventStatus.CLOSED) {
            return event.endTs
        }
        if (event.endTs > event.startTs) {
            if (event.provenance == "therapy_events") return event.endTs
            if (CompensationEventManualPolicy.validEnvelope(event.startTs, event.endTs)) return event.endTs
            return safeEnd(event.startTs, event.endTs - event.startTs)
        }
        val minutes = event.attributes["durationMinutes"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: defaultDurationMinutes(event.type, event.attributes)
        return safeEnd(event.startTs, minutes.coerceAtMost(24L * 60L) * 60_000L)
    }

    private fun therapyEvent(event: TherapyEvent, reserveEvent: (Int) -> Unit): CompensationEvent? {
        val normalizedType = event.type.trim().lowercase(Locale.US)
        val components = resolveTherapyComponents(event)
        val syntheticUam = components.carbKind == TherapyCarbComponentKind.UAM_SYNTHETIC ||
            event.payload.any { (key, value) ->
                key.equals("source", true) && value.equals("uam_engine", true)
            } || event.payload.values.any { it.contains("UAM_ENGINE", true) }
        val (type, subtype, source) = when {
            syntheticUam -> Triple(CompensationEventType.CUSTOM, "UAM", EventSource.AUTOMATIC)
            normalizedType in MEAL_TYPES && components.carbKind == TherapyCarbComponentKind.REAL ->
                Triple(CompensationEventType.MEAL, "", EventSource.AAPS)
            normalizedType in ACTIVITY_TYPES ->
                Triple(CompensationEventType.ACTIVITY, "ACTUAL", EventSource.AAPS)
            normalizedType in CALIBRATION_TYPES ->
                Triple(CompensationEventType.SENSOR_CALIBRATION, "CALIBRATION", EventSource.AAPS)
            normalizedType in SENSOR_FAILURE_TYPES ->
                Triple(CompensationEventType.SENSOR_CALIBRATION, "POSSIBLE_SENSOR_ISSUE", EventSource.AAPS)
            normalizedType in SENSOR_LIFECYCLE_TYPES ->
                Triple(CompensationEventType.SENSOR_CALIBRATION, "SENSOR_EVENT", EventSource.AAPS)
            normalizedType in DELIVERY_PROBLEM_TYPES ->
                Triple(CompensationEventType.INFUSION_PUMP_INSULIN, "DELIVERY_PROBLEM", EventSource.AAPS)
            normalizedType in DELIVERY_DIAGNOSTIC_TYPES ->
                Triple(CompensationEventType.CUSTOM, "DELIVERY_DIAGNOSTIC", EventSource.AAPS)
            else -> Triple(
                when (normalizedType) {
                    "stress" -> CompensationEventType.STRESS
                    "illness" -> CompensationEventType.ILLNESS
                    "sleep" -> CompensationEventType.SLEEP
                    "hormonal" -> CompensationEventType.HORMONAL
                    "steroid" -> CompensationEventType.MEDICATION_STEROID
                    "alcohol" -> CompensationEventType.ALCOHOL
                    "note", "custom" -> CompensationEventType.CUSTOM
                    else -> return null
                },
                "",
                EventSource.AAPS
            )
        }
        val id = event.sourceRowId
            ?: event.payload["eventId"]
            ?: event.payload["id"]
            ?: "therapy:${event.ts}:${event.type}"
        val duration = event.payload["durationMinutes"]?.toLongOrNull()?.coerceIn(0L, 24 * 60L)
            ?: defaultDurationMinutes(type, event.payload)
        val explicitEnd = event.payload["endTs"]?.toLongOrNull()
        val endTs = when {
            explicitEnd != null && CompensationEventManualPolicy.validEnvelope(event.ts, explicitEnd) ->
                explicitEnd
            explicitEnd != null && explicitEnd >= event.ts -> safeEnd(event.ts, explicitEnd - event.ts)
            else -> safeEnd(event.ts, duration * 60_000L)
        }
        val attributes = boundedAttributes(event.payload).toMutableMap().apply {
            if (normalizedType in SENSOR_FAILURE_TYPES && this["trustPenalty"].isNullOrBlank()) {
                this["trustPenalty"] = DEFAULT_SENSOR_FAILURE_TRUST_PENALTY.toString()
            }
        }
        val authoritativeNote = authoritativeNote(event)?.take(MAX_NOTE_CHARS)
        val noteTitle = authoritativeNote
            ?.lineSequence()
            ?.map { line ->
                CopilotContextNoteMarker.stripProtectedMarkers(line)
                    .trim('|', ' ')
                    .replace(Regex("\\s+"), " ")
            }
            ?.firstOrNull(String::isNotBlank)
            ?.take(MAX_TITLE_CHARS)
        reserveEvent(1)
        return CompensationEvent(
            localId = id,
            startTs = event.ts,
            endTs = endTs,
            type = type,
            subtype = subtype,
            source = source,
            title = when {
                syntheticUam -> "UAM context"
                normalizedType in SENSOR_FAILURE_TYPES -> "Possible sensor issue"
                normalizedType == "note" -> noteTitle ?: "AAPS note"
                else -> event.type.take(60)
            },
            attributes = attributes,
            note = authoritativeNote,
            provenance = "therapy_events"
        )
    }

    fun plannedActivityEvents(
        rows: List<PlannedActivityEventEntity>,
        fromTs: Long,
        throughTs: Long,
        statusAtTs: Long = throughTs,
        reserveOccurrence: (Int) -> Unit = {}
    ): List<CompensationEvent> {
        if (throughTs < fromTs) return emptyList()
        val engine = ActivityScheduleEngine()
        return rows.asSequence()
            .mapNotNull(::plannedSchedule)
            .flatMap { schedule ->
                val zone = ZoneId.of(schedule.timezoneId)
                val firstDate = Instant.ofEpochMilli(fromTs).atZone(zone).toLocalDate().minusDays(1)
                val lastDate = Instant.ofEpochMilli(throughTs).atZone(zone).toLocalDate().plusDays(1)
                generateSequence(firstDate) { it.plusDays(1) }
                    .takeWhile { it <= lastDate }
                    .mapNotNull { date ->
                        engine.materialize(schedule, date) { reserveOccurrence(1) }
                    }
                    .filter { occurrence ->
                        occurrence.start.toEpochMilli() <= throughTs &&
                            occurrence.end.toEpochMilli() >= fromTs
                    }
                    .map { occurrence ->
                        val startTs = occurrence.start.toEpochMilli()
                        val endTs = occurrence.end.toEpochMilli()
                        CompensationEvent(
                            localId = "planned:${schedule.eventId}:$startTs",
                            startTs = startTs,
                            endTs = endTs,
                            type = CompensationEventType.ACTIVITY,
                            subtype = "PLANNED",
                            source = EventSource.USER,
                            title = schedule.title.take(60),
                            attributes = mapOf(
                                "activityType" to occurrence.type.name,
                                "intensity" to occurrence.intensity.name,
                                "durationMinutes" to schedule.durationMinutes.toString()
                            ),
                            revision = schedule.revision.coerceAtLeast(1L),
                            status = if (endTs <= statusAtTs) {
                                CompensationEventStatus.CLOSED
                            } else {
                                CompensationEventStatus.ACTIVE
                            },
                            provenance = "planned_activity_events"
                        )
                    }
            }
            .sortedWith(compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId })
            .toList()
    }

    fun actualActivityEvents(samples: List<ActualActivityTimelineSample>): List<CompensationEvent> =
        actualActivityEventsFromBuckets(
            samples.asSequence()
                .filter { sample ->
                    PhysicalActivityTelemetryPolicy.isTrustedPhysicalActivity(
                        source = sample.source,
                        key = PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY,
                        quality = sample.quality,
                        value = sample.activityRatio
                    )
                }
                .map { sample ->
                    PhysicalActivityBucket(
                        bucketTs = Math.floorDiv(
                            sample.timestamp,
                            PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS
                        ) * PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS,
                        firstTs = sample.timestamp,
                        lastTs = sample.timestamp,
                        peakRatio = sample.activityRatio,
                        meanRatio = sample.activityRatio,
                        sampleCount = 1,
                        source = sample.source,
                        qualityEvidence = sample.quality
                    )
                }
                .toList()
        )

    fun actualActivityEventsFromBuckets(
        buckets: List<PhysicalActivityBucket>,
        reserveEvent: (Int) -> Unit = {}
    ): List<CompensationEvent> =
        ActualActivityEpisodeBuilder().build(buckets) { reserveEvent(1) }.map { episode ->
            CompensationEvent(
                localId = episode.stableId,
                startTs = episode.startTs,
                endTs = episode.endTs,
                type = CompensationEventType.ACTIVITY,
                subtype = "ACTUAL",
                source = EventSource.AUTOMATIC,
                title = "${episode.dominantIntensity.name.lowercase(Locale.US).replaceFirstChar(Char::uppercase)} activity",
                attributes = mapOf(
                    "intensity" to episode.dominantIntensity.name,
                    "peakRatio" to String.format(Locale.US, "%.3f", episode.peakRatio),
                    "meanRatio" to String.format(Locale.US, "%.3f", episode.weightedMeanRatio),
                    "sampleCount" to episode.sampleCount.toString(),
                    "sources" to episode.sources.joinToString(","),
                    "qualityEvidence" to episode.qualityEvidence.joinToString(","),
                    "intensityTransitions" to episode.intensityTransitions.joinToString(",") {
                        "${it.timestamp}:${it.intensity.name}"
                    }.take(240)
                ),
                status = CompensationEventStatus.CLOSED,
                provenance = ACTUAL_ACTIVITY_EPISODE_PROVENANCE
            )
        }

    fun calibrationEvents(
        checks: List<BloodGlucoseCheck>,
        reserveEvent: (Int) -> Unit = {}
    ): List<CompensationEvent> = checks
        .asSequence()
        .filter { it.timestamp >= 0L }
        .map { check ->
            reserveEvent(1)
            val trustPenalty = when (check.status) {
                BloodGlucoseCheckStatus.VALID -> 0.0
                BloodGlucoseCheckStatus.STALE, BloodGlucoseCheckStatus.OUT_OF_WINDOW -> 0.25
                BloodGlucoseCheckStatus.REJECTED -> 0.5
            }
            CompensationEvent(
                localId = "calibration:${check.id}",
                startTs = check.timestamp,
                endTs = safeEnd(check.timestamp, 6L * 60L * 60_000L),
                type = CompensationEventType.SENSOR_CALIBRATION,
                subtype = "MANUAL_BLOOD_CHECK",
                severity = when {
                    trustPenalty >= 0.5 -> EventSeverity.HIGH
                    trustPenalty > 0.0 -> EventSeverity.MEDIUM
                    else -> EventSeverity.LOW
                },
                source = if (check.source.equals("MANUAL", true)) EventSource.USER else EventSource.AAPS,
                title = "Blood glucose check",
                attributes = mapOf(
                    "status" to check.status.name,
                    "trustPenalty" to trustPenalty.toString()
                ),
                note = check.note?.take(500),
                status = CompensationEventStatus.CLOSED,
                provenance = "blood_glucose_checks"
            )
        }
        .sortedWith(compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId })
        .toList()

    fun deliveryDiagnosticEvents(
        samples: List<DeliveryDiagnosticTimelineSample>,
        reserveEvent: (Int) -> Unit = {}
    ): List<CompensationEvent> {
        val episodes = mutableListOf<CompensationEvent>()
        val openEpisodeIndicesBySource = mutableMapOf<String, MutableList<Int>>()
        val lastSuspectBySource = mutableMapOf<String, DeliveryDiagnosticTimelineSample>()
        samples.asSequence()
            .filter { it.timestamp >= 0L }
            .distinctBy { Triple(it.timestamp, it.source, it.state) }
            .sortedWith(
                compareBy<DeliveryDiagnosticTimelineSample> { it.timestamp }
                    .thenBy { it.source }
                    .thenBy { deliveryStateTransitionOrder(it.state) }
            )
            .forEach { sample ->
                val openIndices = openEpisodeIndicesBySource[sample.source]
                openIndices?.removeAll { index -> episodes[index].endTs <= sample.timestamp }
                if (openIndices?.isEmpty() == true) {
                    openEpisodeIndicesBySource.remove(sample.source)
                }
                val previousSuspect = lastSuspectBySource[sample.source]
                val activeIndex = openEpisodeIndicesBySource[sample.source]?.lastOrNull()
                if (
                    sample.state == DeliveryTrustState.SUSPECTED_NONRESPONSE &&
                    previousSuspect != null &&
                    activeIndex != null &&
                    sample.timestamp - previousSuspect.timestamp <= DELIVERY_EPISODE_MAX_GAP_MS
                ) {
                    episodes[activeIndex] = episodes[activeIndex].copy(
                        endTs = safeEnd(sample.timestamp, DELIVERY_DIAGNOSTIC_MAX_LOOKBACK_MS)
                    )
                    lastSuspectBySource[sample.source] = sample
                } else if (sample.state == DeliveryTrustState.SUSPECTED_NONRESPONSE) {
                    reserveEvent(1)
                    episodes += deliveryDiagnosticEvent(sample)
                    openEpisodeIndicesBySource.getOrPut(sample.source) { mutableListOf() }
                        .add(episodes.lastIndex)
                    lastSuspectBySource[sample.source] = sample
                } else if (
                    sample.state in setOf(DeliveryTrustState.NORMAL, DeliveryTrustState.WATCH)
                ) {
                    openEpisodeIndicesBySource.remove(sample.source).orEmpty().forEach { index ->
                        val active = episodes[index]
                        episodes[index] = active.copy(
                            endTs = sample.timestamp.coerceAtLeast(active.startTs),
                            status = CompensationEventStatus.CLOSED
                        )
                    }
                    lastSuspectBySource.remove(sample.source)
                } else {
                    // UNKNOWN is not evidence that insulin delivery recovered.
                }
            }
        return episodes.sortedWith(compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId })
    }

    private fun deliveryStateTransitionOrder(state: DeliveryTrustState): Int = when (state) {
        DeliveryTrustState.SUSPECTED_NONRESPONSE -> 0
        DeliveryTrustState.UNKNOWN -> 1
        DeliveryTrustState.WATCH -> 2
        DeliveryTrustState.NORMAL -> 3
    }

    private fun deliveryDiagnosticEvent(sample: DeliveryDiagnosticTimelineSample) = CompensationEvent(
        localId = stableDeliveryEventLocalId(sample.source, sample.timestamp),
        startTs = sample.timestamp,
        endTs = safeEnd(sample.timestamp, DELIVERY_DIAGNOSTIC_MAX_LOOKBACK_MS),
        type = CompensationEventType.INFUSION_PUMP_INSULIN,
        subtype = "POSSIBLE_DELIVERY_NONRESPONSE",
        severity = EventSeverity.HIGH,
        source = EventSource.AUTOMATIC,
        title = "Possible delivery issue",
        attributes = mapOf("deliveryTrust" to sample.state.name),
        status = CompensationEventStatus.ACTIVE,
        provenance = "derived_delivery_trust"
    )

    private fun isProtectedContextNoteEcho(event: TherapyEvent): Boolean {
        if (!event.type.trim().equals("note", ignoreCase = true)) return false
        return authoritativeNote(event)?.let(CopilotContextNoteMarker::parseAtStart) != null
    }

    private fun authoritativeNote(event: TherapyEvent): String? =
        event.payload["notes"] ?: event.payload["note"]

    private fun sourcePrecedence(source: EventSource): Int = when (source) {
        EventSource.USER -> 1
        EventSource.AUTOMATIC -> 2
        EventSource.AAPS -> 3
    }

    private fun defaultDurationMinutes(type: CompensationEventType, payload: Map<String, String>): Long =
        CompensationEventDurationPolicy.defaultMinutes(type, payload)

    private fun withDefaultDuration(event: CompensationEvent, nowTs: Long): CompensationEvent {
        if (event.provenance == ACTUAL_ACTIVITY_EPISODE_PROVENANCE) return event
        if (event.provenance == "derived_delivery_trust" && event.status == CompensationEventStatus.CLOSED) {
            return event
        }
        if (event.endTs > event.startTs) {
            if (event.provenance == "therapy_events") return closeCompletedSourceEvent(event, nowTs)
            if (CompensationEventManualPolicy.validEnvelope(event.startTs, event.endTs)) {
                return closeCompletedSourceEvent(event, nowTs)
            }
            val boundedEnd = safeEnd(event.startTs, event.endTs - event.startTs)
            val bounded = if (boundedEnd == event.endTs) event else event.copy(endTs = boundedEnd)
            return closeCompletedSourceEvent(bounded, nowTs)
        }
        val minutes = event.attributes["durationMinutes"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: defaultDurationMinutes(event.type, event.attributes)
        return closeCompletedSourceEvent(
            event.copy(endTs = safeEnd(event.startTs, minutes.coerceAtMost(24L * 60L) * 60_000L)),
            nowTs
        )
    }

    private fun closeCompletedSourceEvent(event: CompensationEvent, nowTs: Long): CompensationEvent =
        if (event.source != EventSource.USER && event.endTs <= nowTs) {
            event.copy(status = CompensationEventStatus.CLOSED)
        } else {
            event
        }

    private fun safeEnd(startTs: Long, durationMs: Long): Long =
        if (durationMs <= 0L) startTs else startTs + durationMs.coerceAtMost(MAX_DURATION_MS).coerceAtMost(Long.MAX_VALUE - startTs)

    private fun plannedSchedule(row: PlannedActivityEventEntity): PlannedActivitySchedule? =
        ordinaryExceptionOrNull {
            val zone = ZoneId.of(row.timezoneId)
            PlannedActivitySchedule(
                eventId = row.eventId,
                enabled = row.enabled,
                title = row.title,
                type = PlannedActivityType.valueOf(row.activityType.trim().uppercase(Locale.US)),
                intensity = PlannedActivityIntensity.valueOf(row.intensity.trim().uppercase(Locale.US)),
                localStart = LocalDateTime.parse(row.localStartIso),
                durationMinutes = row.durationMinutes,
                timezoneId = zone.id,
                recurrenceDays = DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
                    row.recurrenceDaysMask and (1 shl (day.value - 1)) != 0
                },
                recurrenceEndEpochDay = row.recurrenceEndEpochDay,
                revision = row.revision,
                createdAtMs = row.createdAtMs,
                updatedAtMs = row.updatedAtMs
            )
        }

    private fun boundedAttributes(payload: Map<String, String>): Map<String, String> = payload.entries
        .asSequence()
        .filter { (key, value) -> key.isNotBlank() && key.length <= 48 && value.length <= 240 }
        .sortedBy(Map.Entry<String, String>::key)
        .take(24)
        .associate { it.toPair() }

    private fun PhysioContextTagEntity.toEvent(reserveEvent: (Int) -> Unit): CompensationEvent {
        val type = when (tagType.trim().lowercase()) {
            "steroid", "steroids" -> CompensationEventType.MEDICATION_STEROID
            "hormonal_phase" -> CompensationEventType.HORMONAL
            else -> parseAlertContextEnumOrDefault(
                raw = tagType.uppercase(),
                fallback = CompensationEventType.CUSTOM,
                parse = CompensationEventType::valueOf
            )
        }
        val end = tsEnd.takeIf { it > tsStart } ?: safeEnd(tsStart, defaultDurationMinutes(type, emptyMap()) * 60_000L)
        reserveEvent(1)
        return CompensationEvent(
            localId = id, startTs = tsStart, endTs = end, type = type,
            subtype = subtype, severity = when { severity >= 0.8 -> EventSeverity.HIGH; severity >= 0.4 -> EventSeverity.MEDIUM; else -> EventSeverity.LOW },
            source = parseAlertContextEnumOrDefault(
                raw = source.uppercase(),
                fallback = EventSource.USER,
                parse = EventSource::valueOf
            ), title = title,
            attributes = decodeAttributes(attributesJson), note = note, revision = revision,
            status = parseAlertContextEnumOrDefault(
                raw = status,
                fallback = CompensationEventStatus.ACTIVE,
                parse = CompensationEventStatus::valueOf
            ), provenance = "physio_context_tags"
        )
    }

    private fun decodeAttributes(raw: String): Map<String, String> {
        if (raw.length > MAX_ATTRIBUTES_JSON_CHARS) return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return ordinaryExceptionOrNull { gson.fromJson<Map<String, String>>(raw, type).orEmpty() }
            .orEmpty()
            .takeIf { attributes ->
                attributes.size <= 24 &&
                    attributes.keys.all { it.isNotBlank() && it.length <= 48 } &&
                    attributes.values.all { it.length <= 240 }
            }
            .orEmpty()
    }

    private companion object {
        private const val MAX_DURATION_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_ATTRIBUTES_JSON_CHARS = 8_192
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_NOTE_CHARS = 500
        private const val ACTUAL_ACTIVITY_EPISODE_PROVENANCE = "telemetry_activity_episode"
        private const val DELIVERY_EPISODE_MAX_GAP_MS = 30L * 60_000L
        private val MEAL_TYPES = setOf("meal", "carbs", "meal_bolus")
        private val ACTIVITY_TYPES = setOf("exercise", "activity")
        private val CALIBRATION_TYPES = setOf("calibration", "blood_glucose_check", "sensor_calibration")
        private const val DEFAULT_SENSOR_FAILURE_TRUST_PENALTY = 0.5
        private val SENSOR_LIFECYCLE_TYPES = setOf("sensor_change", "sensor_start", "sensor_started")
        private val SENSOR_FAILURE_TYPES = setOf(
            "sensor_error", "sensor_failure", "sensor_problem", "sensor_issue", "cgm_error", "cgm_failure"
        )
        private val DELIVERY_PROBLEM_TYPES = setOf(
            "infusion_problem", "infusion_issue", "site_failure", "delivery_failure",
            "insulin_delivery_failure", "pump_error", "pump_failure", "occlusion", "no_delivery"
        )
        private val DELIVERY_DIAGNOSTIC_TYPES = setOf(
            "infusion_set_change", "site_change", "set_change", "cannula_change",
            "insulin_refill", "insulin_change", "reservoir_change", "cartridge_change",
            "pump_refill", "pump_battery_change"
        )
        private val ALERT_CONTEXT_TYPES = setOf(
            "stress", "illness", "sleep", "hormonal", "steroid", "alcohol"
        )
    }
}

internal fun stableDeliveryEventLocalId(source: String, startTimestamp: Long): String {
    val sourceDigest = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    return "delivery:$sourceDigest:$startTimestamp"
}
