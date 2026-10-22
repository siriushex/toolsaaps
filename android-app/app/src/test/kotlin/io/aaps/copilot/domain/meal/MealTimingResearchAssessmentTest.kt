package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.predict.*
import io.aaps.copilot.domain.profile.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MealTimingResearchAssessmentTest {
    private val now = 1_700_000_000_000L
    private val record = MealRecordRevision("12", 3, now, 35.0, false)
    private val episode = MealMessageEpisode("episode-1", record, "bolus-1", now - 600_000L, "r1")
    private val shape = PersonalAbsorptionShape(MealAbsorptionProfile.MIXED, 120, 1.0)
    private val giModel = PersonalGiModel(shape, 50.0, -0.2, 20.0, 80.0)
    private fun gi(value: Double = 50.0) = MealGiScenarioInput(
        PersonalGiEvidence("12", "r1", value, "USER", null, now), giModel)
    private fun telemetry(value: Double = 2.2, timestamp: Long = now) = mapOf<String, Double?>(
        "iob_relay_timestamp_ms" to timestamp.toDouble(), "iob_net_units" to value,
        "iob_bolus_units" to 2.2, "iob_basal_units" to value - 2.2, "insulin_activity" to 0.02)
    private suspend fun context(current: Double = 6.0, previousFood: Boolean = false,
        transform: (TherapyEvent) -> TherapyEvent = { it }): MealSimulationContext {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
            setMealAbsorptionContext(MealAbsorptionContext(enabled = true,
                manual = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)))
        }
        val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, current, "sensor") }
        val events = (listOf(
            TherapyEvent(now, "carbs", mapOf("carbs" to "35"), componentTrust = TherapyEventComponentTrust(12, "r1")),
            TherapyEvent(now - 600_000, "meal_bolus", mapOf("insulin" to "2.2"), sourceRowId = "bolus-1")
        ) + if (previousFood) listOf(TherapyEvent(now - 1_800_000, "carbs", mapOf("carbs" to "15"),
            componentTrust = TherapyEventComponentTrust(11, "r1"))) else emptyList()).map(transform)
        return MealSimulationContext.capture(engine, glucose, events, engine.predict(glucose, events), "cycle-1", 7, now)
    }
    private fun belief() = MealBelief(MealInput("input-1", now, MealCarbRange(35.0, 35.0)),
        MealHypothesisKind.entries.map { kind ->
            val absent = kind in setOf(MealHypothesisKind.NO_NEW_MEAL, MealHypothesisKind.NOT_HAPPENING)
            val start = if (kind == MealHypothesisKind.UPCOMING) now else now - 300_000L
            MealScenario(kind.name, kind, if (absent) null else MealStartInterval(start, start),
                MealCarbRange(if (absent) 0.0 else 35.0, if (absent) 0.0 else 35.0),
                listOf(MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 1.0)), 1.0 / 6, "12")
        }, 3)

    @Test fun giChangesFoodTimingButCannotChangeAbsentFoodOrBaseline() = runBlocking {
        val context = context(previousFood = true)
        val evaluator = MealTimingResearchEvaluator()
        val slow = evaluator.evaluate(context, belief(), episode, telemetry(), 180, gi(20.0))
        val fast = evaluator.evaluate(context, belief(), episode, telemetry(), 180, gi(80.0))
        assertEquals(MealTimingResearchStatus.EVALUATED, slow.status)
        assertTrue(slow.giApplied)
        assertEquals(20.0, slow.giInput!!.evidence.value, 0.0)
        val slowBatch = slow.batch!!
        val fastBatch = fast.batch!!
        assertNotEquals(slowBatch.trajectories.first().forecast.glucoseMmol,
            fastBatch.trajectories.first().forecast.glucoseMmol)
        for (kind in listOf("NOT_HAPPENING", "NO_NEW_MEAL")) assertEquals(
            slowBatch.trajectories.filter { it.scenarioId == kind }.map { it.forecast.glucoseMmol },
            fastBatch.trajectories.filter { it.scenarioId == kind }.map { it.forecast.glucoseMmol })
        assertTrue(slowBatch.trajectories.all { it.forecast.remainingFoodGrams < 1e-6 })
        assertEquals(context.baselineForecasts, context.newEngine().predict(context.glucose, context.therapy))
        assertEquals(3, context.therapy.size)
        val withoutPrevious = evaluator.evaluate(context(), belief(), episode, telemetry(), 180, gi(20.0))
        assertNotEquals(slow.noFoodPaths.first().trajectory.forecast.glucoseMmol,
            withoutPrevious.noFoodPaths.first().trajectory.forecast.glucoseMmol)
    }

    @Test fun postedAssumptionRetainsEveryMissedFoodAndInsulinWorld() = runBlocking {
        val context = context()
        val worlds = listOf(MealInsulinScenario("none", MealFutureInsulinPlan(now, emptyList())),
            MealInsulinScenario("pulse", MealFutureInsulinPlan(now, listOf(MealFutureInsulinDelivery(15, 0.5)))))
        val evaluator = MealTimingResearchEvaluator()
        val before = evaluator.evaluate(context, belief(), episode, telemetry(), 180, gi(), worlds)
        val after = evaluator.evaluate(context, belief(), episode.recordPosted(now), telemetry(), 180, gi(), worlds)
        assertEquals(before.batch!!.trajectories.map { it.forecast.glucoseMmol }, after.batch!!.trajectories.map { it.forecast.glucoseMmol })
        assertEquals(96, after.noFoodPaths.size)
        assertTrue(after.noFoodPaths.any { it.below46 != null && it.below46.byMinutes > 0 })
        assertEquals(setOf("none", "pulse"), after.noFoodPaths.map { it.trajectory.insulinScenarioId }.toSet())
        assertTrue(after.candidateHighlighted)
        assertFalse(after.notificationAllowed)
        assertFalse(after.therapyAllowed)
        assertFalse(after.episode.intakeConfirmed)
        assertNull(after.recommendedStartAtMs)
    }

    @Test fun signedAndPositiveIobStayDistinctAndSmallIobDoesNotHideCurrentRisk() = runBlocking {
        val result = MealTimingResearchEvaluator().evaluate(context(2.9), belief(), episode, telemetry(-0.8), 180)
        assertEquals(-0.8, result.iob!!.netIobUnits, 1e-9)
        assertEquals(2.2, result.iob.effectivePositiveIobUnits, 1e-9)
        assertTrue(result.noFoodPaths.all { it.below30 == MealThresholdWindow(0, 0) })
        val small = mapOf<String, Double?>("iob_sample_ts" to now.toDouble(), "iob_units" to 0.5)
        val lower = MealTimingResearchEvaluator().evaluate(context(2.9), belief(), episode, small, 180)
        assertFalse(lower.candidateHighlighted)
        assertTrue(lower.noFoodPaths.all { it.below39 != null })
    }

    @Test fun missingStaleFutureIobAndWrongEpisodeNeverProduceARecommendation() = runBlocking {
        val context = context()
        val evaluator = MealTimingResearchEvaluator()
        for (values in listOf(emptyMap(), telemetry(timestamp = now - 600_001), telemetry(timestamp = now + 1))) {
            val result = evaluator.evaluate(context, belief(), episode, values, 180)
            assertEquals(MealTimingResearchStatus.IOB_UNAVAILABLE, result.status)
            assertNull(result.batch)
            assertFalse(result.notificationAllowed)
        }
        for (changed in listOf(episode.reconcile(record.copy(revision = 4), "r2"),
            episode.copy(meal = record.copy(canonicalId = "other")),
            episode.reconcile(record.copy(revision = 4, deleted = true)),
            episode.copy(messagePostedAtMs = now + 1))) {
            assertEquals(MealTimingResearchStatus.EPISODE_MISMATCH,
                evaluator.evaluate(context, belief(), changed, telemetry(), 180).status)
        }
    }

    @Test fun giMustBeIndependentKnownInRangeAndLinkedToExactCanonicalRevision() = runBlocking {
        val context = context()
        val evaluator = MealTimingResearchEvaluator()
        val valid = gi()
        for (evidence in listOf(valid.evidence.copy(canonicalId = "other"), valid.evidence.copy(therapyRevision = "r2"),
            valid.evidence.copy(value = 90.0), valid.evidence.copy(value = Double.NaN), valid.evidence.copy(source = "MODEL_ESTIMATE"),
            valid.evidence.copy(source = "CATALOG", reference = null), valid.evidence.copy(availableAtMs = now + 1))) {
            val result = evaluator.evaluate(context, belief(), episode, telemetry(), 180, valid.copy(evidence = evidence))
            assertEquals(MealTimingResearchStatus.GI_UNAVAILABLE, result.status)
            assertNull(result.batch)
        }
        val prior = evaluator.evaluate(context, belief(), episode, telemetry(), 180)
        assertEquals(MealTimingResearchStatus.EVALUATED, prior.status)
        assertFalse(prior.giApplied)
    }

    @Test fun gridWindowsAreIntervalsNotInterpolatedSafeTimes() {
        assertEquals(MealThresholdWindow(5, 10), firstMealThresholdWindow(listOf(6.0, 5.0, 4.5, 4.8), 4.6))
        assertEquals(MealThresholdWindow(0, 0), firstMealThresholdWindow(listOf(3.0, 5.0), 3.9))
        assertNull(firstMealThresholdWindow(listOf(4.6, 4.6), 4.6))
        assertThrows(IllegalArgumentException::class.java) { firstMealThresholdWindow(listOf(Double.NaN), 4.6) }
    }

    @Test fun cancelledAssessmentNeverReturnsAPartialResult() = runBlocking {
        val context = context()
        val job = Job().apply { cancel() }
        try { withContext(job) { MealTimingResearchEvaluator().evaluate(context, belief(), episode, telemetry(), 180) }; fail("Cancelled work returned") }
        catch (_: CancellationException) { }
    }

    @Test fun simulatorCannotSilentlyApplyAPersonalShapeToAnotherProfile() = runBlocking {
        val context = context()
        try {
            MealScenarioSimulator().simulate(context, belief(), 180,
                absorptionShape = PersonalAbsorptionShape(MealAbsorptionProfile.FAST, 60, 1.0))
            fail("Incompatible personal shape accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun giProjectionConservesMassAcrossFullTailAndCannotReabsorbThePast() {
        for (value in listOf(20.0, 80.0)) {
            val personal = giModel.shapeFor(value)!!
            for (start in listOf(now - 1_800_000L, now, now + 1_800_000L)) {
                val projection = MealAbsorptionProjection.fromCumulative(now, start, 35.0, 180, personal::cumulative)
                assertEquals(35.0, projection.alreadyAbsorbedGrams + projection.points.sumOf { it.absorbedStepGrams } +
                    projection.remainingAtHorizonGrams, 1e-9)
                assertTrue(projection.completeByHorizon)
                assertEquals(0.0, projection.points.first().absorbedStepGrams, 0.0)
            }
        }
    }

    @Test fun uncertaintyExpansionStillKeepsAllNoFoodPathsAndNeverClaimsSafeOrTrainingAuthority() = runBlocking {
        val source = belief()
        val cases = source.scenarios.map { case -> if (case.carbs.maximumGrams == 0.0) case else
            MealScenario(case.id, case.kind, case.inferredStart, MealCarbRange(30.0, 40.0),
                case.absorption, case.probability, case.canonicalMealId) }
        val uncertain = MealBelief(source.input, cases, 4)
        val result = MealTimingResearchEvaluator().evaluate(context(), uncertain, episode, telemetry(), 180, gi())
        assertEquals(240, result.batch!!.trajectories.size)
        assertEquals(48, result.noFoodPaths.size)
        assertEquals(4L, result.batch.beliefRevision)
        assertTrue(result.batch.trajectories.all { !it.forecast.trajectoryUncertaintyValidated && !it.forecast.futureControlSimulated })
        assertTrue(result.blockers.contains("IOB_CURVE_RECONCILIATION_REQUIRED"))
        assertFalse(result.episode.trainingLabelEligible)
        assertThrows(UnsupportedOperationException::class.java) { (result.noFoodPaths as MutableList).clear() }
        Unit
    }

    @Test fun syntheticFoodAndUnconfirmedInsulinCannotAnchorAnEpisode() = runBlocking {
        val changes = listOf<Pair<String, Map<String, String>>>(
            "carbs" to mapOf("synthetic" to "true"),
            "meal_bolus" to mapOf("inferred" to "true"),
            "meal_bolus" to mapOf("isSMB" to "true"),
            "meal_bolus" to linkedMapOf("isSMB" to "true", "is_smb" to "false"),
            "meal_bolus" to mapOf("bolusType" to "PRIMING"),
            "meal_bolus" to mapOf("classification" to "LOCAL_ACTION"),
            "meal_bolus" to mapOf("isValid" to "false")
        )
        for ((type, fields) in changes) {
            val context = context(transform = { row -> if (row.type == type) row.copy(payload = row.payload + fields) else row })
            assertEquals("Rejected $fields", MealTimingResearchStatus.EPISODE_MISMATCH,
                MealTimingResearchEvaluator().evaluate(context, belief(), episode, telemetry(), 180).status)
        }
    }

    @Test fun accountingMismatchIsVisibleButCannotRescaleOrDoubleSubtractInsulin() = runBlocking {
        val context = context()
        val evaluator = MealTimingResearchEvaluator()
        val first = evaluator.evaluate(context, belief(), episode, telemetry(), 180,
            referenceInsulinKernel = context.insulinKernelIdentity())
        val larger = telemetry().plus(mapOf("iob_net_units" to 5.2, "iob_bolus_units" to 5.2, "iob_basal_units" to 0.0))
        val second = evaluator.evaluate(context, belief(), episode, larger, 180,
            referenceInsulinKernel = context.insulinKernelIdentity())
        assertNotEquals(first.insulinAccounting!!.bolusDifferenceUnits, second.insulinAccounting!!.bolusDifferenceUnits)
        assertEquals(first.batch!!.trajectories.map { it.forecast.glucoseMmol }, second.batch!!.trajectories.map { it.forecast.glucoseMmol })
        assertFalse(second.insulinAccounting.fullIobCoverage)
        assertFalse(second.notificationAllowed)
    }

    @Test fun basalInventoryAddsAccountingNotASecondGlucoseCorrectionOrMealRecommendation() = runBlocking {
        val context = context()
        val kernel = context.insulinKernelIdentity()
        val history = MealBasalHistory(now, now, "history-1", listOf(
            MealBasalInterval("part-1", "r1", now - 86_400_000L, now, 1.5, 1.0, kernel)))
        val evaluator = MealTimingResearchEvaluator()
        val before = evaluator.evaluate(context, belief(), episode.recordPosted(now), telemetry(), 180,
            referenceInsulinKernel = kernel)
        val after = evaluator.evaluate(context, belief(), episode.recordPosted(now), telemetry(), 180,
            referenceInsulinKernel = kernel, basalHistory = history)
        assertEquals(MealInsulinAccountingStatus.COMPARABLE_COMPONENTS, after.insulinAccounting!!.status)
        assertNotNull(after.insulinAccounting.basalDifferenceUnits)
        assertEquals(before.batch!!.trajectories.map { it.forecast.glucoseMmol },
            after.batch!!.trajectories.map { it.forecast.glucoseMmol })
        assertEquals(before.noFoodPaths.size, after.noFoodPaths.size)
        assertFalse(after.insulinAccounting.fullIobCoverage)
        assertFalse(after.notificationAllowed)
        assertNull(after.recommendedStartAtMs)
    }

    @Test fun incompleteOrLateBasalInventoryRejectsWithoutSilentlyReturningABolusOnlyBatch() = runBlocking {
        val context = context()
        val kernel = context.insulinKernelIdentity()
        val intervals = listOf(MealBasalInterval("part-1", "r1", now - 60_000L, now, 1.0, 1.0, kernel))
        val short = MealBasalHistory(now, now, "short-1", intervals)
        val late = MealBasalHistory(now, now + 1, "late-1", intervals.map { it.copy(startMs = now - 86_400_000L) })
        for (history in listOf(short, late)) {
            val result = MealTimingResearchEvaluator().evaluate(context, belief(), episode, telemetry(), 180,
                referenceInsulinKernel = kernel, basalHistory = history)
            assertEquals(MealTimingResearchStatus.BASAL_HISTORY_UNAVAILABLE, result.status)
            assertNull(result.batch)
            assertNull(result.insulinAccounting)
        }
    }
}
