package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.*
import org.junit.Test

class MealTimingPlannerTest {
    @Test fun lowInOneInsulinWorldCannotBeAveragedAway() {
        val e = envelope()
        val paths = e.trajectories.flatMap { t -> listOf(
            t.copy(insulinScenarioId = "continued"),
            t.copy(insulinScenarioId = "higher", points = t.points.map { p ->
                if (t.scenarioId == MealHypothesisKind.JUST_STARTED.name && p.offsetMinutes == 180)
                    p.copy(lowMmol = 3.5) else p
            })
        ) }
        assertEquals(MealTimingReason.UNSAFE_TRAJECTORY, MealTimingPlanner().evaluate(belief,
            e.copy(trajectories = paths, insulinScenarioIds = listOf("continued", "higher")), now).reason)
    }
    @Test fun allInsulinWorldsMustAgreeOnTiming() {
        val e = envelope()
        val paths = e.trajectories.flatMap { t -> listOf(
            t.copy(insulinScenarioId = "continued"),
            t.copy(insulinScenarioId = "reduced", points = t.points.map { p ->
                val mean = if (p.offsetMinutes == 0 || t.startOffsetMinutes == 30) 6.0 else 8.0
                p.copy(meanMmol = mean, lowMmol = mean - 0.3, highMmol = mean + 0.3)
            })
        ) }
        assertEquals(MealTimingReason.CONFLICTING_SCENARIOS, MealTimingPlanner().evaluate(
            belief, e.copy(trajectories = paths, insulinScenarioIds = listOf("continued", "reduced")), now).reason)
    }

    @Test fun completeAgreeingInsulinWorldsRemainShadowOnly() {
        val e = envelope()
        val paths = e.trajectories.flatMap { t -> listOf("continued", "reduced").map {
            t.copy(insulinScenarioId = it)
        } }
        val result = MealTimingPlanner().evaluate(belief,
            e.copy(trajectories = paths, insulinScenarioIds = listOf("continued", "reduced")), now)
        assertEquals(MealTimingStatus.SHADOW_READY, result.status)
        assertFalse(result.notificationAllowed)
        assertEquals(MealTimingReason.INCOMPLETE_SCENARIOS, MealTimingPlanner().evaluate(belief,
            e.copy(trajectories = paths.dropLast(1), insulinScenarioIds = listOf("continued", "reduced")), now).reason)
    }

    @Test fun insulinWorldManifestCannotBeInferredFromIncompletePaths() {
        val e = envelope()
        for (manifest in listOf(emptyList(), listOf(""), listOf("same", "same"))) {
            assertEquals(MealTimingReason.INCOMPLETE_SCENARIOS, MealTimingPlanner().evaluate(belief,
                e.copy(insulinScenarioIds = manifest), now).reason)
        }
        assertEquals(MealTimingReason.INCOMPLETE_SCENARIOS, MealTimingPlanner().evaluate(belief,
            e.copy(insulinScenarioIds = listOf("continued")), now).reason)
        assertEquals(MealTimingReason.INCOMPLETE_SCENARIOS, MealTimingPlanner().evaluate(belief,
            e.copy(trajectories = e.trajectories.map { it.copy(insulinScenarioId = "undeclared") }), now).reason)
    }

    @Test fun malformedBeliefCannotProduceResearchReady() {
        val e = envelope()
        for (scenarios in listOf(emptyList(), belief.scenarios.dropLast(1),
            belief.scenarios + belief.scenarios.first(), belief.scenarios.map { it.weighted(1.0) })) {
            val bad = MealBelief(belief.input, scenarios, belief.revision)
            assertEquals(MealTimingReason.INVALID_CONTEXT, MealTimingPlanner().evaluate(bad, e, now).reason)
        }
    }

    @Test fun splittingPlausibleStageMustNotHideConflictingWorld() {
        val e = envelope()
        val expanded = belief.scenarios.flatMap { s ->
            if (s.kind != MealHypothesisKind.JUST_STARTED) listOf(s.weighted(
                if (s.kind == MealHypothesisKind.UPCOMING) 0.80 else 0.01
            )) else (0..3).map { index -> MealScenario("started-$index", s.kind, s.inferredStart,
                s.carbs, s.absorption, 0.04) }
        }
        val b = MealStateEstimator().initialize(belief.input, expanded)
        val paths = e.trajectories.flatMap { t ->
            if (t.scenarioId != MealHypothesisKind.JUST_STARTED.name) listOf(t)
            else (0..3).map { index -> t.copy(scenarioId = "started-$index", points = t.points.map { p ->
                val mean = if (p.offsetMinutes == 0 || t.startOffsetMinutes == 30) 6.0 else 8.0
                p.copy(meanMmol = mean, lowMmol = mean - 0.3, highMmol = mean + 0.3)
            }) }
        }
        assertEquals(MealTimingReason.CONFLICTING_SCENARIOS,
            MealTimingPlanner().evaluate(b, e.copy(trajectories = paths), now).reason)
    }
    private val now = 20_000_000L
    private val belief = MealStateEstimator().initialize(
        MealInput("meal", now - 300_000L, MealCarbRange(15.0, 40.0)),
        MealHypothesisKind.entries.map { kind -> MealScenario(kind.name, kind,
            if (kind in setOf(MealHypothesisKind.UPCOMING, MealHypothesisKind.JUST_STARTED,
                MealHypothesisKind.STARTED_EARLIER)) MealStartInterval(now - 600_000, now + 600_000) else null,
            MealCarbRange(15.0, 40.0), listOf(MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 1.0)),
            if (kind == MealHypothesisKind.UPCOMING) 995.0 else 1.0) }
    )
    private fun envelope(): MealTimingEnvelope = MealTimingEnvelope(
        generatedAtMs = now, beliefRevision = belief.revision, runtimeIdentity = "runtime-1",
        currentGlucoseMmol = 6.0, targetMmol = 6.0, requiredHorizonMinutes = 360,
        qualityTrusted = true, deliveryReliable = true, futureInsulinSupported = true,
        uncertainty = MealTimingUncertainty(
            "synthetic-path-test", "runtime-1", now, now - 1, 360,
            MealUncertaintyScope.SIMULTANEOUS_TRAJECTORY
        ),
        trajectories = belief.scenarios.flatMap { s -> MealTimingPlanner.START_OFFSETS.flatMap { start ->
            MealTimingPlanner.REACTION_DELAYS.map { delay -> MealTimingTrajectory(
                s.id, start, delay, (0..72).map { step ->
                    val mean = if (step == 0 || start == 0) 6.0 else 7.0
                    MealTimingPoint(step * 5, mean, mean - 0.3, mean + 0.3)
                }
            )
        } } }
    )
    @Test fun unvalidatedModelStaysShadowEvenWhenEveryScenarioAgrees() {
        val result = MealTimingPlanner().evaluate(belief, envelope(), now)
        assertEquals(MealTimingStatus.SHADOW_READY, result.status)
        assertFalse(result.notificationAllowed)
        assertEquals(0, result.candidateOffsetMinutes)
        assertNotNull(result.noFoodRisk)
        assertNull(result.noFoodRisk?.firstLowOffsetMinutes)
    }
    @Test fun missingOrPointwiseUncertaintyCannotSupportMealTiming() {
        val e = envelope()
        for (uncertainty in listOf(null, e.uncertainty!!.copy(scope = MealUncertaintyScope.POINTWISE))) {
            val result = MealTimingPlanner().evaluate(belief, e.copy(uncertainty = uncertainty), now)
            assertEquals(MealTimingReason.UNCERTAINTY_UNSUPPORTED, result.reason)
            assertNull(result.candidateOffsetMinutes)
            assertFalse(result.notificationAllowed)
        }
    }
    @Test fun sixtyMinuteCalibrationCannotBeExtrapolatedToMealTail() {
        val e = envelope()
        assertEquals(MealTimingReason.UNCERTAINTY_UNSUPPORTED, MealTimingPlanner().evaluate(
            belief, e.copy(uncertainty = e.uncertainty!!.copy(supportedThroughMinutes = 60)), now).reason)
    }
    @Test fun uncertaintyMustCoverFoodTailEvenWhenRequestedHorizonIsShorter() {
        val e = envelope()
        assertEquals(MealTimingReason.UNCERTAINTY_UNSUPPORTED, MealTimingPlanner().evaluate(
            belief, e.copy(requiredHorizonMinutes = 60,
                uncertainty = e.uncertainty!!.copy(supportedThroughMinutes = 165)), now).reason)
        assertEquals(MealTimingStatus.SHADOW_READY, MealTimingPlanner().evaluate(
            belief, e.copy(requiredHorizonMinutes = 60,
                uncertainty = e.uncertainty!!.copy(supportedThroughMinutes = 170)), now).status)
    }
    @Test fun uncertaintyMustBeCausalAndBelongToExactRuntimeAndCycle() {
        val e = envelope()
        val u = e.uncertainty!!
        for (bad in listOf(u.copy(modelIdentity = ""), u.copy(runtimeIdentity = "other-runtime"),
            u.copy(generatedAtMs = now - 1), u.copy(calibrationAvailableAtMs = now + 1),
            u.copy(calibrationAvailableAtMs = 0), u.copy(supportedThroughMinutes = 361),
            u.copy(supportedThroughMinutes = 725))) {
            assertEquals(MealTimingReason.UNCERTAINTY_UNSUPPORTED,
                MealTimingPlanner().evaluate(belief, e.copy(uncertainty = bad), now).reason)
        }
    }
    @Test fun distantNoMealRiskDoesNotVetoSafeImmediateMeal() {
        val e = envelope()
        val paths = e.trajectories.map { t ->
            if (t.scenarioId == MealHypothesisKind.NOT_HAPPENING.name) t.copy(points = t.points.map { p ->
                if (p.offsetMinutes == 180) p.copy(lowMmol = 3.5) else p
            }) else t
        }
        val result = MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now)
        assertEquals(MealTimingStatus.SHADOW_READY, result.status)
        assertEquals(0, result.candidateOffsetMinutes)
        assertEquals(180, result.noFoodRisk?.firstLowOffsetMinutes)
        assertFalse(result.notificationAllowed)
    }
    @Test fun earliestNearEquivalentWindowWinsOverTinyLateImprovement() {
        val e = envelope()
        val paths = e.trajectories.map { t -> t.copy(points = t.points.map { p ->
            val mean = if (p.offsetMinutes == 0 || t.startOffsetMinutes == 30) 6.0 else 6.1
            p.copy(meanMmol = mean, lowMmol = mean - 0.3, highMmol = mean + 0.3)
        }) }
        assertEquals(0, MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now).candidateOffsetMinutes)
    }
    @Test fun nearTermNoMealLowDoesNotProduceRoutineStartOrWaitAdvice() {
        val e = envelope()
        val paths = e.trajectories.map { t ->
            if (t.scenarioId == MealHypothesisKind.NOT_HAPPENING.name) t.copy(points = t.points.map { p ->
                if (p.offsetMinutes == 15) p.copy(lowMmol = 3.5) else p
            }) else t
        }
        val result = MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now)
        assertEquals(MealTimingStatus.ABSTAIN, result.status)
        assertEquals(MealTimingReason.NO_FOOD_URGENCY, result.reason)
        assertEquals(15, result.noFoodRisk?.firstLowOffsetMinutes)
        assertNull(result.candidateOffsetMinutes)
    }
    @Test fun waitingRequiresMaterialImprovementOverEatingNow() {
        val e = envelope()
        val paths = e.trajectories.map { t -> t.copy(points = t.points.map { p ->
            val mean = if (p.offsetMinutes == 0 || t.startOffsetMinutes >= 10) 6.0 else 8.0
            p.copy(meanMmol = mean, lowMmol = mean - 0.3, highMmol = mean + 0.3)
        }) }
        val result = MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now)
        assertEquals(MealTimingStatus.OBSERVE, result.status)
        assertEquals(10, result.candidateOffsetMinutes)
        assertFalse(result.notificationAllowed)
    }
    @Test fun waitingCannotConsumeNoFoodReactionMargin() {
        val e = envelope()
        val paths = e.trajectories.map { t -> t.copy(points = t.points.map { p ->
            val mean = if (p.offsetMinutes == 0 || t.startOffsetMinutes == 30) 6.0 else 8.0
            val low = if (t.scenarioId == MealHypothesisKind.NOT_HAPPENING.name &&
                p.offsetMinutes == 35) 3.5 else mean - 0.3
            p.copy(meanMmol = mean, lowMmol = low, highMmol = mean + 0.3)
        }) }
        val result = MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now)
        assertEquals(0, result.candidateOffsetMinutes)
        assertEquals(35, result.noFoodRisk?.firstLowOffsetMinutes)
    }
    @Test fun evenLowProbabilityAlreadyStartedRiskIsChecked() {
        val e = envelope()
        val paths = e.trajectories.map { t ->
            if (t.scenarioId == MealHypothesisKind.JUST_STARTED.name) t.copy(points = t.points.map { p ->
                if (p.offsetMinutes == 180) p.copy(lowMmol = 3.5) else p
            }) else t
        }
        assertEquals(MealTimingReason.UNSAFE_TRAJECTORY,
            MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now).reason)
    }
    @Test fun noFoodUrgencyIncludesExactMaximumReactionDelay() {
        val e = envelope()
        val paths = e.trajectories.map { t ->
            if (t.scenarioId == MealHypothesisKind.NOT_HAPPENING.name) t.copy(points = t.points.map { p ->
                if (p.offsetMinutes == 20) p.copy(lowMmol = 3.9) else p
            }) else t
        }
        assertEquals(MealTimingReason.NO_FOOD_URGENCY,
            MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now).reason)
    }
    @Test fun lowAfterEatingWithDelayedReactionCannotBeIgnored() {
        val e = envelope()
        val paths = e.trajectories.map { t ->
            if (t.scenarioId == MealHypothesisKind.UPCOMING.name && t.reactionDelayMinutes == 20)
                t.copy(points = t.points.map { p -> if (p.offsetMinutes == 25) p.copy(lowMmol = 3.5) else p })
            else t
        }
        assertEquals(MealTimingReason.UNSAFE_TRAJECTORY,
            MealTimingPlanner().evaluate(belief, e.copy(trajectories = paths), now).reason)
    }
    @Test fun delayedReactionAndAlreadyStartedScenariosCannotBeOmitted() {
        val e = envelope()
        assertEquals(MealTimingReason.INCOMPLETE_SCENARIOS, MealTimingPlanner().evaluate(
            belief, e.copy(trajectories = e.trajectories.filter { it.reactionDelayMinutes != 20 }), now).reason)
    }
    @Test fun conflictingPreferredTimesProduceNoCommand() {
        val e = envelope()
        // Use a broad posterior: both upcoming and already-started remain plausible.
        val broad = MealStateEstimator().initialize(belief.input, belief.scenarios.map { it.weighted(1.0) })
        val paths = e.trajectories.map { t ->
            if (t.scenarioId == MealHypothesisKind.JUST_STARTED.name) t.copy(points = t.points.map { p ->
                val mean = if (p.offsetMinutes == 0 || t.startOffsetMinutes == 30) 6.0 else 8.0
                p.copy(meanMmol = mean, lowMmol = mean - 0.3, highMmol = mean + 0.3)
            }) else t
        }
        assertEquals(MealTimingReason.CONFLICTING_SCENARIOS,
            MealTimingPlanner().evaluate(broad, e.copy(trajectories = paths), now).reason)
    }
    @Test fun lateEntrySuppressesStartSignal() {
        val late = MealStateEstimator().initialize(belief.input, belief.scenarios.map {
            it.weighted(if (it.kind == MealHypothesisKind.STARTED_EARLIER) 995.0 else 1.0)
        })
        assertEquals(MealTimingStatus.LATE_ENTRY, MealTimingPlanner().evaluate(late, envelope(), now).status)
    }
    @Test fun sixtyMinutesCannotStandInForSlowMealTail() {
        val e = envelope()
        assertEquals(MealTimingReason.INCOMPLETE_HORIZON, MealTimingPlanner().evaluate(belief,
            e.copy(trajectories = e.trajectories.map { it.copy(points = it.points.take(13)) }), now).reason)
    }
    @Test fun staleOrUnsupportedPredictionCannotNotify() {
        val e = envelope()
        for (bad in listOf(e.copy(generatedAtMs = now - 600_000), e.copy(futureInsulinSupported = false),
            e.copy(deliveryReliable = false), e.copy(qualityTrusted = false))) {
            assertEquals(MealTimingStatus.ABSTAIN, MealTimingPlanner().evaluate(belief, bad, now).status)
        }
    }
    @Test fun malformedTrajectoryCannotBeRanked() {
        val e = envelope()
        val bad = e.trajectories.toMutableList()
        bad[0] = bad[0].copy(points = listOf(MealTimingPoint(0, Double.NaN, 5.0, 7.0)))
        assertEquals(MealTimingStatus.ABSTAIN,
            MealTimingPlanner().evaluate(belief, e.copy(trajectories = bad), now).status)
    }
}
