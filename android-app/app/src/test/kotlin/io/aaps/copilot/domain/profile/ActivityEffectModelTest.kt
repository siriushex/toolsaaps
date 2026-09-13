package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.Forecast
import java.time.Instant
import org.junit.Test

class ActivityEffectModelTest {

    private val model = ActivityEffectModel()
    private val event = PlannedActivityOccurrence(
        eventId = "walk",
        revision = 1L,
        type = PlannedActivityType.WALKING,
        intensity = PlannedActivityIntensity.MEDIUM,
        start = Instant.parse("2026-08-08T09:00:00Z"),
        end = Instant.parse("2026-08-08T10:00:00Z"),
        evaluationStarts = Instant.parse("2026-08-08T07:45:00Z"),
        dstResolution = DstResolution.EXACT
    )

    @Test
    fun scheduleHasNoPhysicalEffectBeforeStart() {
        val context = model.evaluate(
            enabled = true,
            now = event.start.minusSeconds(60),
            planned = event,
            measured = null
        )

        assertThat(context.factor5).isEqualTo(1.0)
        assertThat(context.factor30).isEqualTo(1.0)
        assertThat(context.factor60).isEqualTo(1.0)
        assertThat(context.source).isEqualTo(ActivityContextSource.SCHEDULED)
    }

    @Test
    fun actualMovementSupersedesStartedPlanImmediately() {
        val context = model.evaluate(
            enabled = true,
            now = event.start.plusSeconds(300),
            planned = event,
            measured = ActivityMeasurement(
                observedAt = event.start.plusSeconds(240),
                activityRatio = 1.35,
                coverage = ActivityCoverage.AVAILABLE
            )
        )

        assertThat(context.source).isEqualTo(ActivityContextSource.MEASURED)
        assertThat(context.factor5).isGreaterThan(1.0)
    }

    @Test
    fun longEventKeepsFullScheduledPriorThroughoutItsDuration() {
        val context = model.evaluate(
            enabled = true,
            now = event.start.plusSeconds(35 * 60),
            planned = event,
            measured = null
        )

        assertThat(context.factor5).isEqualTo(1.0)
        assertThat(context.factor30).isEqualTo(1.07)
        assertThat(context.factor60).isEqualTo(1.12)
        assertThat(context.ciWidthMultiplier30).isEqualTo(1.0)
        assertThat(context.blockers).doesNotContain(ActivityContextBlocker.MEASUREMENT_GRACE_EXPIRED)
    }

    @Test
    fun postEndGraceThenDecayUsesPlannedEndAsItsAnchor() {
        val context = model.evaluate(
            enabled = true,
            now = event.end.plusSeconds(20 * 60),
            planned = event,
            measured = null
        )

        assertThat(context.factor30).isGreaterThan(1.0)
        assertThat(context.factor30).isLessThan(1.07)
        assertThat(context.ciWidthMultiplier30).isGreaterThan(1.0)
        assertThat(context.blockers).contains(ActivityContextBlocker.MEASUREMENT_GRACE_EXPIRED)
    }

    @Test
    fun shortEventRemainsEligibleUntilThePostEndDecayCompletes() {
        val short = event.copy(end = event.start.plusSeconds(60))
        val duringDecay = short.end.plusSeconds(20 * 60)

        assertThat(model.isWithinPlannedEffectWindow(duringDecay, short)).isTrue()
        assertThat(model.evaluate(enabled = true, now = duringDecay, planned = short, measured = null).factor30)
            .isGreaterThan(1.0)
    }

    @Test
    fun effectWindowIsFullAtExactEndAndHalfOpenAfterPostEndDecay() {
        val atEnd = model.evaluate(enabled = true, now = event.end, planned = event, measured = null)
        val outside = event.end.plusSeconds(45 * 60)

        assertThat(atEnd.factor30).isEqualTo(1.07)
        assertThat(model.isWithinPlannedEffectWindow(event.end, event)).isTrue()
        assertThat(model.isWithinPlannedEffectWindow(outside, event)).isFalse()
    }

    @Test
    fun widenedIntervalsAtGlucoseBoundsContainTheirOriginalInterval() {
        val context = ActivityEffectContext(
            source = ActivityContextSource.SCHEDULED,
            factor5 = 1.0,
            factor30 = 1.30,
            factor60 = 1.30,
            ciWidthMultiplier30 = 1.32,
            ciWidthMultiplier60 = 1.32,
            confidence = 0.5,
            blockers = emptySet()
        )
        val input = listOf(
            Forecast(1L, 30, 2.25, 2.2, 2.6, "test"),
            Forecast(2L, 60, 2.25, 2.2, 2.6, "test"),
            Forecast(3L, 30, 21.95, 21.5, 22.0, "test"),
            Forecast(4L, 60, 21.95, 21.5, 22.0, "test")
        )

        val adjusted = model.applyForecastContext(input, context)

        adjusted.zip(input).forEach { (result, original) ->
            assertThat(result.ciLow).isAtMost(original.ciLow)
            assertThat(result.ciHigh).isAtLeast(original.ciHigh)
            assertThat(result.ciLow).isAtMost(result.valueMmol)
            assertThat(result.ciHigh).isAtLeast(result.valueMmol)
            assertThat(result.ciHigh - result.ciLow).isAtLeast(original.ciHigh - original.ciLow)
        }
    }

    @Test
    fun highStrengthIsDirectionNeutralWithoutReplayEvidence() {
        val strength = event.copy(type = PlannedActivityType.STRENGTH, intensity = PlannedActivityIntensity.HIGH)

        val context = model.evaluate(
            enabled = true,
            now = strength.start.plusSeconds(5 * 60),
            planned = strength,
            measured = null
        )

        assertThat(context.factor30).isEqualTo(1.0)
        assertThat(context.factor60).isEqualTo(1.0)
        assertThat(context.ciWidthMultiplier60).isGreaterThan(1.0)
        assertThat(context.blockers).contains(ActivityContextBlocker.DIRECTION_UNPROVEN)
    }

    @Test
    fun highStrengthUsesOnlyQualifiedReplayDirectionEvidence() {
        val strength = event.copy(type = PlannedActivityType.STRENGTH, intensity = PlannedActivityIntensity.HIGH)

        val context = model.evaluate(
            enabled = true,
            now = strength.start.plusSeconds(5 * 60),
            planned = strength,
            measured = null,
            replayEvidence = ActivityReplayEvidence(factor30 = 1.10, factor60 = 1.18, confidence = 0.80)
        )

        assertThat(context.factor30).isEqualTo(1.10)
        assertThat(context.factor60).isEqualTo(1.18)
        assertThat(context.blockers).doesNotContain(ActivityContextBlocker.DIRECTION_UNPROVEN)
    }

    @Test
    fun missingCoverageIsUnknownNotInactive() {
        val context = model.evaluate(
            enabled = true,
            now = event.start.plusSeconds(5 * 60),
            planned = null,
            measured = null
        )

        assertThat(context.source).isEqualTo(ActivityContextSource.UNKNOWN)
        assertThat(context.blockers).contains(ActivityContextBlocker.MISSING_HEALTH_CONNECT_COVERAGE)
    }

    @Test
    fun disabledModuleIsIdentity() {
        val context = model.evaluate(
            enabled = false,
            now = event.start.plusSeconds(5 * 60),
            planned = event,
            measured = ActivityMeasurement(event.start, 1.5, ActivityCoverage.AVAILABLE)
        )

        assertThat(context).isEqualTo(ActivityEffectContext.DISABLED)
    }
}
