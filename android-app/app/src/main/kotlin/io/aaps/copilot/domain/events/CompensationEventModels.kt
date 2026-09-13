package io.aaps.copilot.domain.events

import io.aaps.copilot.domain.profile.PhysiologicalSex
import java.util.Locale

enum class CompensationEventType {
    MEAL, ACTIVITY, STRESS, ILLNESS, SLEEP, HORMONAL,
    MEDICATION_STEROID, ALCOHOL, SENSOR_CALIBRATION,
    INFUSION_PUMP_INSULIN, CUSTOM, MENSTRUAL_CYCLE
}

enum class FemaleCyclePhase { MENSTRUATION, FOLLICULAR, OVULATION, LUTEAL }
enum class EventSource { USER, AAPS, AUTOMATIC }
enum class EventSeverity { LOW, MEDIUM, HIGH }
enum class CompensationEventStatus { ACTIVE, CLOSED }
enum class CompensationEventLifecycle { UPCOMING, ACTIVE, ELAPSED, CLOSED }

data class CompensationEvent(
    val localId: String,
    val startTs: Long,
    val endTs: Long,
    val type: CompensationEventType,
    val subtype: String = "",
    val severity: EventSeverity = EventSeverity.MEDIUM,
    val source: EventSource = EventSource.USER,
    val title: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val note: String? = null,
    val revision: Long = 1L,
    val status: CompensationEventStatus = CompensationEventStatus.ACTIVE,
    val provenance: String = ""
) {
    init {
        require(localId.isNotBlank())
        require(startTs >= 0L && endTs >= startTs)
        require(revision > 0L)
        require(title.length <= 60)
        require(note == null || note.length <= 500)
        require(attributes.size <= 24)
        require(attributes.keys.all { it.isNotBlank() && it.length <= 48 })
        require(attributes.values.all { it.length <= 240 })
    }

    val isActive: Boolean get() = status == CompensationEventStatus.ACTIVE

    fun lifecycleAt(nowTs: Long): CompensationEventLifecycle = when {
        status == CompensationEventStatus.CLOSED -> CompensationEventLifecycle.CLOSED
        nowTs < startTs -> CompensationEventLifecycle.UPCOMING
        nowTs >= endTs -> CompensationEventLifecycle.ELAPSED
        else -> CompensationEventLifecycle.ACTIVE
    }

    fun isActiveAt(nowTs: Long): Boolean = lifecycleAt(nowTs) == CompensationEventLifecycle.ACTIVE

    fun femaleCyclePhase(): FemaleCyclePhase? = attributes[CompensationEventHormonePolicy.CYCLE_PHASE_KEY]
        ?.trim()
        ?.uppercase(Locale.US)
        ?.let { runCatching { FemaleCyclePhase.valueOf(it) }.getOrNull() }

    fun withFemaleCyclePhase(phase: FemaleCyclePhase): CompensationEvent {
        val retained = attributes.entries
            .filterNot { it.key == CompensationEventHormonePolicy.CYCLE_PHASE_KEY }
            .sortedBy(Map.Entry<String, String>::key)
            .take(23)
            .associate { it.toPair() }
        return copy(
            subtype = phase.name,
            attributes = retained + (CompensationEventHormonePolicy.CYCLE_PHASE_KEY to phase.name)
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun visibleFor(sex: PhysiologicalSex): Boolean = true
}

object CompensationEventHormonePolicy {
    const val CYCLE_PHASE_KEY = "cyclePhase"

    fun normalizedFactor(event: CompensationEvent): Double {
        if (event.type != CompensationEventType.HORMONAL && event.type != CompensationEventType.MENSTRUAL_CYCLE) {
            return 0.0
        }
        val severityScale = when (event.severity) {
            EventSeverity.LOW -> 0.65
            EventSeverity.MEDIUM -> 0.82
            EventSeverity.HIGH -> 1.0
        }
        val phaseBase = when (event.femaleCyclePhase()) {
            FemaleCyclePhase.MENSTRUATION -> 0.35
            FemaleCyclePhase.FOLLICULAR -> 0.20
            FemaleCyclePhase.OVULATION -> 0.30
            FemaleCyclePhase.LUTEAL -> 0.60
            null -> 0.30
        }
        return (phaseBase * severityScale).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    private const val MIN_FACTOR = 0.10
    private const val MAX_FACTOR = 0.60
}

data class EventTimeline(
    val events: List<CompensationEvent>,
    val generatedAt: Long
)

object CompensationEventProfilePolicy {
    fun canCreate(type: CompensationEventType, sex: PhysiologicalSex): Boolean =
        type != CompensationEventType.MENSTRUAL_CYCLE || sex == PhysiologicalSex.FEMALE

    fun visibleForControl(type: CompensationEventType, sex: PhysiologicalSex): Boolean =
        canCreate(type, sex)

    fun retainForHistory(event: CompensationEvent): Boolean = true
}

object CompensationEventDurationPolicy {
    fun defaultMinutes(
        type: CompensationEventType,
        attributes: Map<String, String> = emptyMap()
    ): Long = when (type) {
        CompensationEventType.MEAL -> when (attributes["profile"]?.uppercase()) {
            "FAST" -> 60L
            "FATTY_PROTEIN" -> 360L
            else -> 180L
        }
        CompensationEventType.ACTIVITY -> 60L
        CompensationEventType.STRESS -> 240L
        CompensationEventType.ILLNESS -> 720L
        CompensationEventType.SLEEP -> 480L
        CompensationEventType.HORMONAL, CompensationEventType.MENSTRUAL_CYCLE -> 1_440L
        CompensationEventType.MEDICATION_STEROID -> 720L
        CompensationEventType.ALCOHOL -> 720L
        CompensationEventType.SENSOR_CALIBRATION -> 360L
        CompensationEventType.INFUSION_PUMP_INSULIN -> 240L
        CompensationEventType.CUSTOM -> 60L
    }

    fun boundedEnd(startTs: Long, durationMinutes: Long): Long {
        val durationMs = durationMinutes
            .coerceIn(1L, MAX_DURATION_MINUTES)
            .times(MINUTE_MS)
        return startTs + durationMs.coerceAtMost(Long.MAX_VALUE - startTs)
    }

    private const val MINUTE_MS = 60_000L
    internal const val MAX_DURATION_MINUTES = 7L * 24L * 60L
}

object CompensationEventManualPolicy {
    val contextOnlyTypes: Set<CompensationEventType> = setOf(
        CompensationEventType.STRESS,
        CompensationEventType.ILLNESS,
        CompensationEventType.SLEEP,
        CompensationEventType.HORMONAL,
        CompensationEventType.MEDICATION_STEROID,
        CompensationEventType.ALCOHOL,
        CompensationEventType.CUSTOM,
        CompensationEventType.MENSTRUAL_CYCLE
    )

    fun isContextOnly(type: CompensationEventType): Boolean = type in contextOnlyTypes

    fun normalizeContext(event: CompensationEvent): CompensationEvent =
        requireNotNull(
            normalizeContextOrNull(
                event = event,
                physiologicalSex = PhysiologicalSex.FEMALE,
                nowTs = Long.MAX_VALUE
            )
        ) { "Invalid manual context event" }

    fun normalizeContextOrNull(
        event: CompensationEvent,
        physiologicalSex: PhysiologicalSex,
        nowTs: Long
    ): CompensationEvent? {
        require(isContextOnly(event.type)) { "Only context events use manual context storage" }
        if (event.source != EventSource.USER || !validLocalEventId(event.localId)) return null
        val normalizedEvent = if (event.type == CompensationEventType.MENSTRUAL_CYCLE) {
            val phase = event.femaleCyclePhase()
            if (physiologicalSex != PhysiologicalSex.FEMALE || phase == null) {
                return null
            }
            event.withFemaleCyclePhase(phase)
        } else {
            event
        }
        if (normalizedEvent.startTs <= 0L || nowTs <= 0L) return null
        val latestStart = safeAdd(nowTs, MAX_FUTURE_START_MS)
        if (normalizedEvent.startTs > latestStart) return null
        if (normalizedEvent.endTs > normalizedEvent.startTs) {
            return normalizedEvent.takeIf { validEnvelope(it.startTs, it.endTs) }
        }
        if (normalizedEvent.endTs < normalizedEvent.startTs) return null
        val durationAttribute = normalizedEvent.attributes["durationMinutes"]
        val requestedMinutes = if (durationAttribute == null) {
            CompensationEventDurationPolicy.defaultMinutes(
                normalizedEvent.type,
                normalizedEvent.attributes
            )
        } else {
            durationAttribute.toLongOrNull()
                ?.takeIf { it in 1L..CompensationEventDurationPolicy.MAX_DURATION_MINUTES }
                ?: return null
        }
        val durationMs = try {
            Math.multiplyExact(requestedMinutes, MINUTE_MS)
        } catch (_: ArithmeticException) {
            return null
        }
        val endTs = try {
            Math.addExact(normalizedEvent.startTs, durationMs)
        } catch (_: ArithmeticException) {
            return null
        }
        if (!validEnvelope(normalizedEvent.startTs, endTs)) return null
        return normalizedEvent.copy(endTs = endTs)
    }

    fun validateCloseOrNull(event: CompensationEvent, nowTs: Long): CompensationEvent? = event.takeIf {
        validLifecycleIdentity(it, nowTs) &&
            it.status == CompensationEventStatus.CLOSED &&
            it.endTs <= nowTs
    }

    fun validateDeleteOrNull(event: CompensationEvent, nowTs: Long): CompensationEvent? = event.takeIf {
        validLifecycleIdentity(it, nowTs)
    }

    fun validLocalEventId(localEventId: String): Boolean =
        localEventId.length in 1..MAX_LOCAL_EVENT_ID_CHARS &&
            localEventId.all { it.isLetterOrDigit() || it in "._:-" } &&
            localEventId.all { it.code in 0x21..0x7e }

    fun validEnvelope(startTs: Long, endTs: Long): Boolean {
        if (startTs <= 0L || endTs < startTs) return false
        val duration = try {
            Math.subtractExact(endTs, startTs)
        } catch (_: ArithmeticException) {
            return false
        }
        return duration <= MAX_DURATION_MS
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun validLifecycleIdentity(event: CompensationEvent, nowTs: Long): Boolean =
        event.source == EventSource.USER &&
            isContextOnly(event.type) &&
            validLocalEventId(event.localId) &&
            nowTs > 0L &&
            validEnvelope(event.startTs, event.endTs) &&
            event.revision > 0L

    private const val MINUTE_MS = 60_000L
    private const val MAX_DURATION_MS =
        CompensationEventDurationPolicy.MAX_DURATION_MINUTES * MINUTE_MS
    private const val MAX_FUTURE_START_MS = 7L * 24L * 60L * MINUTE_MS
    private const val MAX_LOCAL_EVENT_ID_CHARS = 248
}
