package io.aaps.copilot.domain.target

import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// A generated data-class copy() cannot defensively snapshot a caller-owned mutable list.
@Serializable(with = BaseTargetScheduleSerializer::class)
class BaseTargetSchedule(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 0L,
    val defaultTargetMmol: Double,
    val autoEnabled: Boolean = false,
    intervals: List<BaseTargetInterval> = emptyList()
) {
    val intervals: List<BaseTargetInterval> = Collections.unmodifiableList(intervals.toList())

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        revision: Long = this.revision,
        defaultTargetMmol: Double = this.defaultTargetMmol,
        autoEnabled: Boolean = this.autoEnabled,
        intervals: List<BaseTargetInterval> = this.intervals
    ): BaseTargetSchedule {
        return BaseTargetSchedule(
            schemaVersion = schemaVersion,
            revision = revision,
            defaultTargetMmol = defaultTargetMmol,
            autoEnabled = autoEnabled,
            intervals = intervals
        )
    }

    operator fun component1(): Int = schemaVersion

    operator fun component2(): Long = revision

    operator fun component3(): Double = defaultTargetMmol

    operator fun component4(): Boolean = autoEnabled

    operator fun component5(): List<BaseTargetInterval> = intervals

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BaseTargetSchedule) return false

        return schemaVersion == other.schemaVersion &&
            revision == other.revision &&
            defaultTargetMmol.compareTo(other.defaultTargetMmol) == 0 &&
            autoEnabled == other.autoEnabled &&
            intervals == other.intervals
    }

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + revision.hashCode()
        result = 31 * result + defaultTargetMmol.hashCode()
        result = 31 * result + autoEnabled.hashCode()
        result = 31 * result + intervals.hashCode()
        return result
    }

    override fun toString(): String {
        return "BaseTargetSchedule(" +
            "schemaVersion=$schemaVersion, " +
            "revision=$revision, " +
            "defaultTargetMmol=$defaultTargetMmol, " +
            "autoEnabled=$autoEnabled, " +
            "intervals=$intervals)"
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_INTERVALS = 24

        fun legacy(targetMmol: Double) = BaseTargetSchedule(defaultTargetMmol = targetMmol)
    }
}

@Serializable
data class BaseTargetInterval(
    val id: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val targetMmol: Double
)

@Serializable
@SerialName("io.aaps.copilot.domain.target.BaseTargetSchedule")
private data class BaseTargetScheduleSurrogate(
    val schemaVersion: Int = BaseTargetSchedule.CURRENT_SCHEMA_VERSION,
    val revision: Long = 0L,
    val defaultTargetMmol: Double,
    val autoEnabled: Boolean = false,
    val intervals: List<BaseTargetInterval> = emptyList()
)

private object BaseTargetScheduleSerializer : KSerializer<BaseTargetSchedule> {
    private val surrogateSerializer = BaseTargetScheduleSurrogate.serializer()

    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: BaseTargetSchedule) {
        surrogateSerializer.serialize(
            encoder,
            BaseTargetScheduleSurrogate(
                schemaVersion = value.schemaVersion,
                revision = value.revision,
                defaultTargetMmol = value.defaultTargetMmol,
                autoEnabled = value.autoEnabled,
                intervals = value.intervals
            )
        )
    }

    override fun deserialize(decoder: Decoder): BaseTargetSchedule {
        val value = surrogateSerializer.deserialize(decoder)
        return BaseTargetSchedule(
            schemaVersion = value.schemaVersion,
            revision = value.revision,
            defaultTargetMmol = value.defaultTargetMmol,
            autoEnabled = value.autoEnabled,
            intervals = value.intervals
        )
    }
}

data class ScheduleValidationError(
    val code: String,
    val intervalIds: List<String>
)

data class ResolvedManualTarget(
    val targetMmol: Double,
    val intervalId: String?
)

object BaseTargetSchedulePolicy {

    fun validate(
        schedule: BaseTargetSchedule,
        minTarget: Double,
        maxTarget: Double
    ): List<ScheduleValidationError> {
        val errors = mutableListOf<ScheduleValidationError>()

        if (schedule.schemaVersion != BaseTargetSchedule.CURRENT_SCHEMA_VERSION) {
            errors += ScheduleValidationError(UNSUPPORTED_SCHEMA, emptyList())
        }
        if (schedule.intervals.size > BaseTargetSchedule.MAX_INTERVALS) {
            errors += ScheduleValidationError(
                code = TOO_MANY_INTERVALS,
                intervalIds = schedule.intervals.map { it.id }
            )
        }

        schedule.intervals.groupBy { it.id }.forEach { (id, intervals) ->
            if (intervals.size > 1) {
                errors += ScheduleValidationError(DUPLICATE_INTERVAL_ID, listOf(id))
            }
        }

        if (!isValidTarget(schedule.defaultTargetMmol, minTarget, maxTarget)) {
            errors += ScheduleValidationError(INVALID_DEFAULT_TARGET, emptyList())
        }

        schedule.intervals.forEach { interval ->
            if (!interval.hasValidMinutes()) {
                errors += ScheduleValidationError(INVALID_TIME, listOf(interval.id))
            } else if (interval.hasZeroDuration()) {
                errors += ScheduleValidationError(ZERO_DURATION, listOf(interval.id))
            }

            if (!isValidTarget(interval.targetMmol, minTarget, maxTarget)) {
                errors += ScheduleValidationError(INVALID_INTERVAL_TARGET, listOf(interval.id))
            }
        }

        errors += findOverlapErrors(schedule.intervals)

        return errors.distinct()
    }

    fun resolveManual(
        schedule: BaseTargetSchedule,
        instant: Instant,
        zoneId: ZoneId
    ): ResolvedManualTarget {
        val localTime = instant.atZone(zoneId).toLocalTime()
        val minuteOfDay = localTime.hour * MINUTES_PER_HOUR + localTime.minute
        val interval = schedule.intervals.firstOrNull { candidate ->
            candidate.contains(minuteOfDay)
        }

        return if (interval == null) {
            ResolvedManualTarget(
                targetMmol = schedule.defaultTargetMmol,
                intervalId = null
            )
        } else {
            ResolvedManualTarget(
                targetMmol = interval.targetMmol,
                intervalId = interval.id
            )
        }
    }

    private fun isValidTarget(
        target: Double,
        minTarget: Double,
        maxTarget: Double
    ): Boolean {
        return target.isFinite() &&
            minTarget.isFinite() &&
            maxTarget.isFinite() &&
            minTarget <= maxTarget &&
            target in minTarget..maxTarget &&
            abs(target * 10.0 - round(target * 10.0)) <= TARGET_PRECISION_TOLERANCE
    }

    private fun findOverlapErrors(
        intervals: List<BaseTargetInterval>
    ): List<ScheduleValidationError> {
        val expandedRanges = intervals
            .asSequence()
            .filter { interval -> interval.hasValidMinutes() && !interval.hasZeroDuration() }
            .flatMap { interval -> interval.expandedRanges().asSequence() }
            .sortedWith(
                compareBy<ExpandedRange> { it.start }
                    .thenBy { it.end }
            )
            .toList()

        val overlapPairs = mutableSetOf<CanonicalIntervalPair>()
        expandedRanges.indices.forEach { leftIndex ->
            for (rightIndex in leftIndex + 1 until expandedRanges.size) {
                val left = expandedRanges[leftIndex]
                val right = expandedRanges[rightIndex]
                if (left.intervalId != right.intervalId && left.overlaps(right)) {
                    overlapPairs += CanonicalIntervalPair.of(left.intervalId, right.intervalId)
                }
            }
        }

        return overlapPairs
            .sortedWith(
                compareBy<CanonicalIntervalPair> { it.firstId }
                    .thenBy { it.secondId }
            )
            .map { pair ->
                ScheduleValidationError(
                    code = INTERVAL_OVERLAP,
                    intervalIds = listOf(pair.firstId, pair.secondId)
                )
            }
    }

    private fun BaseTargetInterval.hasValidMinutes(): Boolean {
        return startMinuteOfDay in MINUTE_OF_DAY_RANGE && endMinuteOfDay in MINUTE_OF_DAY_RANGE
    }

    private fun BaseTargetInterval.hasZeroDuration(): Boolean {
        return startMinuteOfDay == endMinuteOfDay
    }

    private fun BaseTargetInterval.expandedRanges(): List<ExpandedRange> {
        return if (endMinuteOfDay > startMinuteOfDay) {
            listOf(ExpandedRange(startMinuteOfDay, endMinuteOfDay, id))
        } else {
            listOf(
                ExpandedRange(startMinuteOfDay, MINUTES_PER_DAY, id),
                ExpandedRange(0, endMinuteOfDay, id)
            )
        }
    }

    private fun BaseTargetInterval.contains(minuteOfDay: Int): Boolean {
        return when {
            endMinuteOfDay > startMinuteOfDay -> {
                minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay
            }
            endMinuteOfDay < startMinuteOfDay -> {
                minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
            }
            else -> false
        }
    }

    private data class ExpandedRange(
        val start: Int,
        val end: Int,
        val intervalId: String
    ) {
        fun overlaps(other: ExpandedRange): Boolean {
            return start < other.end && other.start < end
        }
    }

    private data class CanonicalIntervalPair(
        val firstId: String,
        val secondId: String
    ) {
        companion object {
            fun of(leftId: String, rightId: String): CanonicalIntervalPair {
                return if (leftId <= rightId) {
                    CanonicalIntervalPair(leftId, rightId)
                } else {
                    CanonicalIntervalPair(rightId, leftId)
                }
            }
        }
    }

    private const val UNSUPPORTED_SCHEMA = "unsupported_schema"
    private const val TOO_MANY_INTERVALS = "too_many_intervals"
    private const val DUPLICATE_INTERVAL_ID = "duplicate_interval_id"
    private const val INVALID_DEFAULT_TARGET = "invalid_default_target"
    private const val INVALID_TIME = "invalid_time"
    private const val ZERO_DURATION = "zero_duration"
    private const val INVALID_INTERVAL_TARGET = "invalid_interval_target"
    private const val INTERVAL_OVERLAP = "interval_overlap"

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 1440
    private val MINUTE_OF_DAY_RANGE = 0 until MINUTES_PER_DAY
    private const val TARGET_PRECISION_TOLERANCE = 1e-8
}
