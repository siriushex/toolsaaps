package io.aaps.copilot.domain.events

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalEventImpactAnalyzerTest {
    private val analyzer = LocalEventImpactAnalyzer()
    private fun meal(profile: String, duration: String? = null) = CompensationEvent("m$profile", 1, 2, CompensationEventType.MEAL, attributes = buildMap { put("profile", profile); duration?.let { put("durationMinutes", it) } })

    @Test fun taxonomyIsExact() = assertThat(CompensationEventType.entries).hasSize(12)
    @Test fun mealDurationsAreBounded() {
        assertThat(analyzer.impact(meal("FAST", "1")).absorptionMinutes).isEqualTo(30)
        assertThat(analyzer.impact(meal("MIXED", "999")).absorptionMinutes).isEqualTo(180)
        assertThat(analyzer.impact(meal("FATTY_PROTEIN", "999")).absorptionMinutes).isEqualTo(360)
    }
    @Test fun contextDefaultsAndSafetyBoundaries() {
        assertThat(analyzer.impact(CompensationEvent("s", 1, 2, CompensationEventType.STRESS)).contextHours).isEqualTo(4)
        assertThat(analyzer.impact(CompensationEvent("i", 1, 2, CompensationEventType.INFUSION_PUMP_INSULIN)).infusionGateBlocked).isTrue()
        assertThat(analyzer.impact(CompensationEvent("c", 1, 2, CompensationEventType.CUSTOM)).hormoneFactor).isEqualTo(0.0)
    }
    @Test fun overlappingHormonalEffectsUseMaximum() {
        val one = CompensationEvent("1", 1, 2, CompensationEventType.HORMONAL, severity = EventSeverity.LOW)
        val two = CompensationEvent("2", 1, 2, CompensationEventType.MENSTRUAL_CYCLE, severity = EventSeverity.HIGH, attributes = mapOf("cyclePhase" to FemaleCyclePhase.LUTEAL.name))
        val maximum = analyzer.maximumHormonalFactor(listOf(one, two))
        assertThat(maximum).isEqualTo(analyzer.impact(two).hormoneFactor)
        assertThat(maximum).isLessThan(analyzer.impact(one).hormoneFactor + analyzer.impact(two).hormoneFactor)
    }

    @Test fun everyFemaleCyclePhaseHasBoundedTrustedHormonalSemantics() {
        FemaleCyclePhase.entries.forEach { phase ->
            EventSeverity.entries.forEach { severity ->
                val impact = analyzer.impact(
                    CompensationEvent(
                        phase.name + severity.name,
                        1,
                        2,
                        CompensationEventType.MENSTRUAL_CYCLE,
                        severity = severity,
                        attributes = mapOf("cyclePhase" to phase.name, "factor" to "999")
                    )
                )
                assertThat(impact.hormoneFactor).isGreaterThan(0.0)
                assertThat(impact.hormoneFactor).isAtMost(1.0)
            }
        }
    }

    @Test fun phaseLessHormonalContextUsesConservativeSeverityDerivedEffect() {
        val low = analyzer.impact(CompensationEvent("low", 1, 2, CompensationEventType.HORMONAL, severity = EventSeverity.LOW))
        val high = analyzer.impact(CompensationEvent("high", 1, 2, CompensationEventType.HORMONAL, severity = EventSeverity.HIGH))
        assertThat(low.hormoneFactor).isGreaterThan(0.0)
        assertThat(high.hormoneFactor).isGreaterThan(low.hormoneFactor)
        assertThat(high.hormoneFactor).isAtMost(1.0)
    }

    @Test fun sensorFailurePenaltyBlocksTrustWhileNormalCheckDoesNot() {
        val repository = io.aaps.copilot.data.repository.EventTimelineRepository()
        val events = repository.aggregate(
            io.aaps.copilot.data.repository.EventTimelineSources(
                therapyEvents = listOf(
                    io.aaps.copilot.domain.model.TherapyEvent(1, "sensor_failure", mapOf("eventId" to "bad")),
                    io.aaps.copilot.domain.model.TherapyEvent(2, "calibration", mapOf("eventId" to "ok"))
                )
            ),
            3
        )
        assertThat(analyzer.impact(events.first { it.localId == "bad" }).sensorTrustPenalty).isGreaterThan(0.0)
        assertThat(analyzer.impact(events.first { it.localId == "ok" }).sensorTrustPenalty).isEqualTo(0.0)
    }
    @Test fun activityUsesDurationIntensityAndPhaseForCurrentTailAndDelayedLow() {
        val event = CompensationEvent(
            "a", 0, 60 * 60_000L, CompensationEventType.ACTIVITY,
            attributes = mapOf("intensity" to "HIGH", "tailMinutes" to "120")
        )
        val during = analyzer.impact(event, 30 * 60_000L)
        val after = analyzer.impact(event, 90 * 60_000L)
        assertThat(during.activityCurrentFactor).isGreaterThan(0.0)
        assertThat(after.activityCurrentFactor).isEqualTo(0.0)
        assertThat(after.activityTailFactor).isGreaterThan(0.0)
        assertThat(after.delayedLowFactor).isGreaterThan(0.0)
        assertThat(during.activityCurrentFactor).isAtMost(1.0)
        assertThat(after.activityTailFactor).isAtMost(1.0)
        assertThat(after.delayedLowFactor).isAtMost(1.0)
    }

    @Test fun activityBeforeStartHasNoEffect() {
        val event = CompensationEvent("future", 60 * 60_000L, 120 * 60_000L, CompensationEventType.ACTIVITY)
        assertThat(analyzer.impact(event, 30 * 60_000L)).isEqualTo(EventImpact())
    }

    @Test fun zeroMealDurationUsesProfileDefaultAndPositiveDurationIsPreserved() {
        assertThat(analyzer.impact(meal("FAST", "0")).absorptionMinutes).isEqualTo(30)
        assertThat(analyzer.impact(meal("MIXED", "90")).absorptionMinutes).isEqualTo(90)
    }
}
