package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class BaseTargetScheduleTest {

    @Test
    fun legacyUsesCurrentSchemaAndStableDefaults() {
        val schedule = BaseTargetSchedule.legacy(targetMmol = 6.0)

        assertThat(schedule.schemaVersion).isEqualTo(BaseTargetSchedule.CURRENT_SCHEMA_VERSION)
        assertThat(schedule.revision).isEqualTo(0L)
        assertThat(schedule.defaultTargetMmol).isEqualTo(6.0)
        assertThat(schedule.autoEnabled).isFalse()
        assertThat(schedule.intervals).isEmpty()
    }

    @Test
    fun touchingIntervalsAndInclusiveTargetBoundsAreValid() {
        val schedule = schedule(
            defaultTargetMmol = MIN_TARGET,
            intervals = listOf(
                interval("night", start = 0, end = 360, target = MIN_TARGET),
                interval("morning", start = 360, end = 720, target = MAX_TARGET)
            )
        )

        assertThat(validate(schedule)).isEmpty()
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        val schedule = schedule().copy(schemaVersion = BaseTargetSchedule.CURRENT_SCHEMA_VERSION + 1)

        assertThat(validate(schedule)).containsExactly(error("unsupported_schema"))
    }

    @Test
    fun intervalLimitAllowsTwentyFourAndRejectsTwentyFive() {
        val firstTwentyFour = (0 until BaseTargetSchedule.MAX_INTERVALS).map { index ->
            interval(
                id = "interval-$index",
                start = index * 10,
                end = index * 10 + 5
            )
        }
        val twentyFive = firstTwentyFour +
            interval(id = "interval-24", start = 240, end = 245)

        assertThat(validate(schedule(intervals = firstTwentyFour))).isEmpty()
        assertThat(validate(schedule(intervals = twentyFive))).containsExactly(
            ScheduleValidationError(
                code = "too_many_intervals",
                intervalIds = twentyFive.map { it.id }
            )
        )
    }

    @Test
    fun eachDuplicateIdProducesSeparateErrorInGroupInsertionOrder() {
        val schedule = schedule(
            intervals = listOf(
                interval("z-duplicate", start = 60, end = 120),
                interval("single", start = 180, end = 240),
                interval("a-duplicate", start = 300, end = 360),
                interval("z-duplicate", start = 420, end = 480),
                interval("a-duplicate", start = 540, end = 600)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("duplicate_interval_id", "z-duplicate"),
            error("duplicate_interval_id", "a-duplicate")
        ).inOrder()
    }

    @Test
    fun defaultTargetMustStayInsideHardBounds() {
        assertThat(validate(schedule(defaultTargetMmol = MIN_TARGET - 0.1))).containsExactly(
            error("invalid_default_target")
        )
        assertThat(validate(schedule(defaultTargetMmol = MAX_TARGET + 0.1))).containsExactly(
            error("invalid_default_target")
        )
    }

    @Test
    fun defaultTargetRequiresTenthPrecisionWithinTolerance() {
        assertThat(validate(schedule(defaultTargetMmol = 6.1 + 5e-10))).isEmpty()
        assertThat(validate(schedule(defaultTargetMmol = 6.1 + 2e-9))).containsExactly(
            error("invalid_default_target")
        )
    }

    @Test
    fun nonFiniteBoundsInvalidateOtherwiseValidTargets() {
        val schedule = schedule(
            defaultTargetMmol = 6.0,
            intervals = listOf(interval("finite", 60, 120, 6.0))
        )
        val nonFiniteBounds = listOf(
            Double.NaN to MAX_TARGET,
            Double.NEGATIVE_INFINITY to MAX_TARGET,
            Double.POSITIVE_INFINITY to MAX_TARGET,
            MIN_TARGET to Double.NaN,
            MIN_TARGET to Double.NEGATIVE_INFINITY,
            MIN_TARGET to Double.POSITIVE_INFINITY
        )

        nonFiniteBounds.forEach { (minTarget, maxTarget) ->
            assertThat(validate(schedule, minTarget, maxTarget)).containsExactly(
                error("invalid_default_target"),
                error("invalid_interval_target", "finite")
            ).inOrder()
        }
    }

    @Test
    fun reversedBoundsInvalidateOtherwiseValidTargets() {
        val schedule = schedule(
            defaultTargetMmol = 6.0,
            intervals = listOf(interval("finite", 60, 120, 6.0))
        )

        assertThat(validate(schedule, minTarget = 10.0, maxTarget = 4.0)).containsExactly(
            error("invalid_default_target"),
            error("invalid_interval_target", "finite")
        ).inOrder()
    }

    @Test
    fun nonFiniteDefaultTargetsAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { target ->
            assertThat(validate(schedule(defaultTargetMmol = target))).containsExactly(
                error("invalid_default_target")
            )
        }
    }

    @Test
    fun intervalTargetMustStayInsideHardBoundsAndUseTenthPrecision() {
        listOf(MIN_TARGET - 0.1, MAX_TARGET + 0.1, 6.15).forEach { target ->
            val schedule = schedule(intervals = listOf(interval("invalid", 60, 120, target)))

            assertThat(validate(schedule)).containsExactly(
                error("invalid_interval_target", "invalid")
            )
        }

        val withinTolerance = schedule(
            intervals = listOf(interval("precise", 60, 120, 6.1 + 5e-10))
        )
        val outsideTolerance = schedule(
            intervals = listOf(interval("imprecise", 60, 120, 6.1 + 2e-9))
        )

        assertThat(validate(withinTolerance)).isEmpty()
        assertThat(validate(outsideTolerance)).containsExactly(
            error("invalid_interval_target", "imprecise")
        )
    }

    @Test
    fun nonFiniteIntervalTargetsAreRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { target ->
            val schedule = schedule(intervals = listOf(interval("invalid", 60, 120, target)))

            assertThat(validate(schedule)).containsExactly(
                error("invalid_interval_target", "invalid")
            )
        }
    }

    @Test
    fun invalidIntervalTargetsProduceSeparateErrorsInInputOrder() {
        val schedule = schedule(
            intervals = listOf(
                interval("z-low", start = 60, end = 120, target = MIN_TARGET - 0.1),
                interval("valid", start = 120, end = 180, target = 6.0),
                interval("a-high", start = 180, end = 240, target = MAX_TARGET + 0.1),
                interval("m-precision", start = 240, end = 300, target = 6.15)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("invalid_interval_target", "z-low"),
            error("invalid_interval_target", "a-high"),
            error("invalid_interval_target", "m-precision")
        ).inOrder()
    }

    @Test
    fun minutesOutsideDayProduceSeparateErrorsInInputOrder() {
        val invalidIntervals = listOf(
            interval("negative-start", start = -1, end = 60),
            interval("large-start", start = 1440, end = 60),
            interval("negative-end", start = 60, end = -1),
            interval("large-end", start = 60, end = 1440)
        )

        assertThat(validate(schedule(intervals = invalidIntervals))).containsExactly(
            error("invalid_time", "negative-start"),
            error("invalid_time", "large-start"),
            error("invalid_time", "negative-end"),
            error("invalid_time", "large-end")
        ).inOrder()
    }

    @Test
    fun zeroDurationProducesSeparateErrorsInInputOrder() {
        val schedule = schedule(
            intervals = listOf(
                interval("z-zero", start = 60, end = 60),
                interval("valid", start = 120, end = 180),
                interval("a-zero", start = 240, end = 240)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("zero_duration", "z-zero"),
            error("zero_duration", "a-zero")
        ).inOrder()
    }

    @Test
    fun invalidMinuteTakesPrecedenceOverZeroDuration() {
        val schedule = schedule(intervals = listOf(interval("invalid-equal", start = -1, end = -1)))

        assertThat(validate(schedule)).containsExactly(error("invalid_time", "invalid-equal"))
    }

    @Test
    fun ordinaryOverlapIsRejectedWithSortedIds() {
        val schedule = schedule(
            intervals = listOf(
                interval("later-id", start = 60, end = 420),
                interval("earlier-id", start = 360, end = 600)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("interval_overlap", "earlier-id", "later-id")
        )
    }

    @Test
    fun wrappingIntervalCollidesWithOrdinaryInterval() {
        val schedule = schedule(
            intervals = listOf(
                interval("wrap", start = 1320, end = 360),
                interval("morning", start = 300, end = 480)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("interval_overlap", "morning", "wrap")
        )
    }

    @Test
    fun wrappingPairCollisionIsDistinctAndIndependentOfInputOrder() {
        val first = interval("z-wrap", start = 1320, end = 360)
        val second = interval("a-wrap", start = 1380, end = 120)
        val expected = listOf(error("interval_overlap", "a-wrap", "z-wrap"))

        assertThat(validate(schedule(intervals = listOf(first, second)))).isEqualTo(expected)
        assertThat(validate(schedule(intervals = listOf(second, first)))).isEqualTo(expected)
    }

    @Test
    fun nestedRangesReportEveryOverlappingPair() {
        val schedule = schedule(
            intervals = listOf(
                interval("A", start = 0, end = 100),
                interval("B", start = 10, end = 20),
                interval("C", start = 30, end = 40)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("interval_overlap", "A", "B"),
            error("interval_overlap", "A", "C")
        ).inOrder()
    }

    @Test
    fun identicalRangesReportEveryCanonicalPair() {
        val schedule = schedule(
            intervals = listOf(
                interval("z-first", start = 60, end = 120),
                interval("a-second", start = 60, end = 120),
                interval("m-third", start = 60, end = 120)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("interval_overlap", "a-second", "m-third"),
            error("interval_overlap", "a-second", "z-first"),
            error("interval_overlap", "m-third", "z-first")
        ).inOrder()
    }

    @Test
    fun overlapErrorsUseCanonicalPairOrder() {
        val schedule = schedule(
            intervals = listOf(
                interval("z-first", start = 0, end = 100),
                interval("y-second", start = 50, end = 150),
                interval("a-third", start = 100, end = 200)
            )
        )

        assertThat(validate(schedule)).containsExactly(
            error("interval_overlap", "a-third", "y-second"),
            error("interval_overlap", "y-second", "z-first")
        ).inOrder()
    }

    @Test
    fun wrappingNestedOverlapsAreCompleteAcrossInputPermutations() {
        val wrapping = interval("A-wrap", start = 1320, end = 360)
        val nestedFirst = interval("B-early", start = 10, end = 20)
        val nestedSecond = interval("C-early", start = 30, end = 40)
        val expected = listOf(
            error("interval_overlap", "A-wrap", "B-early"),
            error("interval_overlap", "A-wrap", "C-early")
        )
        val permutations = listOf(
            listOf(wrapping, nestedFirst, nestedSecond),
            listOf(nestedSecond, wrapping, nestedFirst),
            listOf(nestedFirst, nestedSecond, wrapping)
        )

        permutations.forEach { intervals ->
            assertThat(validate(schedule(intervals = intervals))).isEqualTo(expected)
        }
    }

    @Test
    fun uncoveredFifteenHundredUsesDefaultTarget() {
        val schedule = schedule(
            defaultTargetMmol = 6.5,
            intervals = listOf(interval("morning", start = 360, end = 720, target = 5.5))
        )

        assertThat(resolve(schedule, "2026-01-01T15:00:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 6.5, intervalId = null)
        )
    }

    @Test
    fun exactSixHundredEndBoundarySelectsNextInterval() {
        val schedule = schedule(
            intervals = listOf(
                interval("night", start = 0, end = 360, target = 5.5),
                interval("day", start = 360, end = 720, target = 6.5)
            )
        )

        assertThat(resolve(schedule, "2026-01-01T05:59:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 5.5, intervalId = "night")
        )
        assertThat(resolve(schedule, "2026-01-01T06:00:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 6.5, intervalId = "day")
        )
    }

    @Test
    fun wrappingIntervalUsesHalfOpenRangesAcrossMidnight() {
        val schedule = schedule(
            defaultTargetMmol = 6.5,
            intervals = listOf(interval("sleep", start = 1320, end = 360, target = 5.5))
        )

        assertThat(resolve(schedule, "2026-01-01T22:00:00Z").intervalId).isEqualTo("sleep")
        assertThat(resolve(schedule, "2026-01-02T05:59:00Z").intervalId).isEqualTo("sleep")
        assertThat(resolve(schedule, "2026-01-02T06:00:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 6.5, intervalId = null)
        )
    }

    @Test
    fun zeroDurationIntervalDoesNotResolveAsAllDay() {
        val schedule = schedule(
            defaultTargetMmol = 6.5,
            intervals = listOf(interval("zero", start = 60, end = 60, target = 5.5))
        )

        assertThat(resolve(schedule, "2026-01-01T15:00:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 6.5, intervalId = null)
        )
    }

    @Test
    fun resolutionUsesWallClockInSuppliedTimezone() {
        val schedule = schedule(
            defaultTargetMmol = 6.5,
            intervals = listOf(interval("local-morning", start = 480, end = 540, target = 5.5))
        )
        val instant = Instant.parse("2026-01-01T04:30:00Z")

        assertThat(BaseTargetSchedulePolicy.resolveManual(schedule, instant, UTC)).isEqualTo(
            ResolvedManualTarget(targetMmol = 6.5, intervalId = null)
        )
        assertThat(
            BaseTargetSchedulePolicy.resolveManual(schedule, instant, ZoneId.of("Asia/Tbilisi"))
        ).isEqualTo(
            ResolvedManualTarget(targetMmol = 5.5, intervalId = "local-morning")
        )
    }

    @Test
    fun validationAndResolutionAreDeterministicWithoutMutatingIntervals() {
        val intervals = mutableListOf(
            interval("first", start = 300, end = 480, target = 5.5),
            interval("second", start = 360, end = 540, target = 6.5)
        )
        val originalOrder = intervals.toList()
        val schedule = schedule(intervals = intervals)
        val instant = Instant.parse("2026-01-01T06:30:00Z")

        val firstValidation = validate(schedule)
        val firstResolution = BaseTargetSchedulePolicy.resolveManual(schedule, instant, UTC)
        val secondValidation = validate(schedule)
        val secondResolution = BaseTargetSchedulePolicy.resolveManual(schedule, instant, UTC)

        assertThat(firstValidation).isEqualTo(secondValidation)
        assertThat(firstResolution).isEqualTo(secondResolution)
        assertThat(firstResolution).isEqualTo(
            ResolvedManualTarget(targetMmol = 5.5, intervalId = "first")
        )
        assertThat(intervals).containsExactlyElementsIn(originalOrder).inOrder()
    }

    @Test
    fun constructionSnapshotsMutableIntervals() {
        val original = interval("original", start = 360, end = 420, target = 5.5)
        val replacement = interval("replacement", start = 360, end = 420, target = 9.0)
        val mutableIntervals = mutableListOf(original)
        val schedule = BaseTargetSchedule(
            defaultTargetMmol = 6.5,
            intervals = mutableIntervals
        )

        mutableIntervals.clear()
        mutableIntervals += replacement

        assertThat(schedule.intervals).containsExactly(original)
        assertThat(resolve(schedule, "2026-01-01T06:30:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 5.5, intervalId = "original")
        )
    }

    @Test
    fun copySnapshotsMutableIntervals() {
        val baseInterval = interval("base", start = 60, end = 120, target = 6.0)
        val copiedInterval = interval("copied", start = 360, end = 420, target = 5.5)
        val replacement = interval("replacement", start = 360, end = 420, target = 9.0)
        val mutableIntervals = mutableListOf(copiedInterval)
        val base = schedule(intervals = listOf(baseInterval))
        val copied = base.copy(
            revision = 7L,
            defaultTargetMmol = 6.5,
            intervals = mutableIntervals
        )

        mutableIntervals.clear()
        mutableIntervals += replacement

        assertThat(copied.revision).isEqualTo(7L)
        assertThat(copied.intervals).containsExactly(copiedInterval)
        assertThat(resolve(copied, "2026-01-01T06:30:00Z")).isEqualTo(
            ResolvedManualTarget(targetMmol = 5.5, intervalId = "copied")
        )
        assertThat(base.intervals).containsExactly(baseInterval)
    }

    @Test
    fun scheduleRetainsDataClassValueSemantics() {
        val interval = interval("night", start = 1320, end = 360, target = 5.5)
        val schedule = BaseTargetSchedule(
            revision = 7L,
            defaultTargetMmol = 6.5,
            autoEnabled = true,
            intervals = listOf(interval)
        )
        val (schemaVersion, revision, defaultTargetMmol, autoEnabled, intervals) = schedule

        assertThat(schedule.copy()).isEqualTo(schedule)
        assertThat(schedule.copy().hashCode()).isEqualTo(schedule.hashCode())
        assertThat(schedule.toString()).isEqualTo(
            "BaseTargetSchedule(schemaVersion=1, revision=7, defaultTargetMmol=6.5, " +
                "autoEnabled=true, intervals=[$interval])"
        )
        assertThat(schemaVersion).isEqualTo(1)
        assertThat(revision).isEqualTo(7L)
        assertThat(defaultTargetMmol).isEqualTo(6.5)
        assertThat(autoEnabled).isTrue()
        assertThat(intervals).containsExactly(interval)
    }

    @Test
    fun serializationRoundTripPreservesApprovedJsonShape() {
        val schedule = BaseTargetSchedule(
            revision = 7L,
            defaultTargetMmol = 6.5,
            autoEnabled = true,
            intervals = listOf(interval("night", start = 1320, end = 360, target = 5.5))
        )
        val json = Json { encodeDefaults = true }

        val encoded = json.encodeToString(schedule)
        val decoded = json.decodeFromString<BaseTargetSchedule>(encoded)

        assertThat(encoded).isEqualTo(
            "{\"schemaVersion\":1,\"revision\":7,\"defaultTargetMmol\":6.5," +
                "\"autoEnabled\":true,\"intervals\":[{\"id\":\"night\"," +
                "\"startMinuteOfDay\":1320,\"endMinuteOfDay\":360,\"targetMmol\":5.5}]}"
        )
        assertThat(decoded).isEqualTo(schedule)
    }

    private fun validate(
        schedule: BaseTargetSchedule,
        minTarget: Double = MIN_TARGET,
        maxTarget: Double = MAX_TARGET
    ): List<ScheduleValidationError> {
        return BaseTargetSchedulePolicy.validate(
            schedule = schedule,
            minTarget = minTarget,
            maxTarget = maxTarget
        )
    }

    private fun resolve(
        schedule: BaseTargetSchedule,
        instant: String
    ): ResolvedManualTarget {
        return BaseTargetSchedulePolicy.resolveManual(schedule, Instant.parse(instant), UTC)
    }

    private fun schedule(
        defaultTargetMmol: Double = 6.0,
        intervals: List<BaseTargetInterval> = emptyList()
    ): BaseTargetSchedule {
        return BaseTargetSchedule(
            defaultTargetMmol = defaultTargetMmol,
            intervals = intervals
        )
    }

    private fun interval(
        id: String,
        start: Int,
        end: Int,
        target: Double = 6.0
    ): BaseTargetInterval {
        return BaseTargetInterval(
            id = id,
            startMinuteOfDay = start,
            endMinuteOfDay = end,
            targetMmol = target
        )
    }

    private fun error(code: String, vararg intervalIds: String): ScheduleValidationError {
        return ScheduleValidationError(code = code, intervalIds = intervalIds.toList())
    }

    private companion object {
        const val MIN_TARGET = 4.0
        const val MAX_TARGET = 10.0
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
