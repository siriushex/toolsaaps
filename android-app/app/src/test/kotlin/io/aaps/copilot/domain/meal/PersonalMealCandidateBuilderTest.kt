package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.profile.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class PersonalMealCandidateBuilderTest {
    private val cutoff = PersonalAbsorptionEpisodeFixtures.cutoff
    private val now = cutoff + 86_400_000L
    private val request = PersonalAbsorptionRequest(cutoff, MealAbsorptionProfile.MIXED, MealPortion.MEDIUM, "UTC", 720)
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
        setMealAbsorptionContext(MealAbsorptionContext(enabled = true,
            manual = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)))
    }
    private fun frames(engine: HybridPredictionEngine, giEffect: Double = 0.0, duration: Int = 150) = (1..18).map { day ->
        val meal = PersonalAbsorptionEpisodeFixtures.meal("history-$day", cutoff - day * 86_400_000L)
        val frame = PersonalAbsorptionEpisodeFixtures.evidence(meal)
        val kernel = engine.mealInsulinKernelIdentity()
        val curve = MealAbsorptionCurve(meal.profile, duration)
        val gi = if (day % 2 == 0) 20.0 else 80.0
        frame.copy(calibrationIdentity = "raw-cgm-v1", measurementBasis = GlucoseMeasurementBasis.RAW, input = frame.input.copy(
            context = frame.input.context!!.copy(insulinModelId = kernel.id, insulinDurationMinutes = kernel.durationMinutes),
            glucose = frame.input.glucose.map { sample ->
                val minute = (sample.timestampMs - meal.timestamp.toEpochMilli()) / 60_000.0
                sample.copy(mmol = 6.0 + meal.grams * 0.3 * curve.absorbedFraction(minute).pow(
                    1.15 + giEffect * (gi - 50) / 100) - minute / 5 * 0.03)
            }))
    }
    private fun source(rows: List<PersonalAbsorptionEpisodeEvidence>) =
        PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", rows.map { it.input.meal }, rows)
    private fun cohort(engine: HybridPredictionEngine): String {
        val kernel = engine.mealInsulinKernelIdentity()
        return personalAbsorptionComparisonCohort("sensor-session-1", "raw-cgm-v1", kernel.id, kernel.durationMinutes, GlucoseMeasurementBasis.RAW)
    }
    private fun giHistory() = (1..18).map { day -> PersonalGiEvidence("history-$day", "r1",
        if (day % 2 == 0) 20.0 else 80.0, "USER", null, cutoff - day * 86_400_000L + 1000) }
    private suspend fun fixture(): Triple<MealSimulationContext, MealForecastIdentity, MealPortionObservation> {
        val engine = engine()
        val meal = MealPortionObservation("12", Instant.ofEpochMilli(now - 45 * 60_000L), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM, therapyRevision = "r1")
        val therapy = listOf(TherapyEvent(meal.timestamp.toEpochMilli(), "carbs", mapOf("carbs" to "35"),
            componentTrust = TherapyEventComponentTrust(12, "r1")))
        val glucose = (0..24).map { GlucosePoint(now - (24 - it) * 300_000L, 6.0 + it * 0.02, "sensor") }
        val forecasts = engine.predict(glucose, therapy)
        val simulation = MealSimulationContext.capture(engine, glucose, therapy, forecasts, "meal-cycle-1", 7, now + 100,
            GlucoseMeasurementBasis.RAW)
        val identity = MealForecastIdentity(now, "meal-cycle-1", 7, "accepted-digest", "sensor", "sensor-session-1",
            "raw-cgm-v1", null, MealForecastBasis.LOCAL_ENGINE, "UTC", forecasts.first().modelVersion)
        return Triple(simulation, identity, meal)
    }
    private fun model(engine: HybridPredictionEngine = engine(), giEffect: Double = 0.0,
        gi: List<PersonalGiEvidence> = emptyList()) = requireNotNull(PersonalAbsorptionResearchModel.fit(
        request, source(frames(engine, giEffect)), cohort(engine), gi) { cutoff + 100 })

    @Test fun producesCausalLocalCandidateWithoutBorrowingBaselineIntervalsOrMutatingSeed() = runBlocking {
        val (simulation, identity, meal) = fixture()
        val model = model()
        val result = PersonalMealCandidateBuilder.build(simulation, identity, model, meal, null) { now + 200 }
        assertEquals(PersonalMealCandidateStatus.CREATED, result.status)
        val candidate = result.candidate!!
        assertEquals(identity, candidate.identity)
        assertEquals(model.evidenceDigest, candidate.model.evidenceDigest)
        assertEquals(cutoff, candidate.model.trainingThroughMs)
        assertEquals(now, candidate.inputThroughMs)
        assertEquals(now + 100, candidate.inputsAvailableAtMs)
        assertEquals(listOf(5, 30, 60), candidate.values.map { it.horizonMinutes })
        assertTrue(candidate.values.all { it.lowerMmol == null && it.upperMmol == null })
        assertNotEquals(simulation.baselineForecasts.map { it.valueMmol }, candidate.values.map { it.mmol })
        assertEquals(simulation.baselineForecasts, simulation.newEngine().predict(simulation.glucose, simulation.therapy))
        assertEquals("35", simulation.therapy.single().payload["carbs"])
        assertFalse(model.clinicalInfluenceAllowed)
    }

    @Test fun frozenCalibratedInputCannotBeRetaggedRawAndRequiresItsOwnQualifiedCohort() = runBlocking {
        val (rawSimulation, rawIdentity, meal) = fixture()
        val engine = engine()
        val calibrated = MealSimulationContext.capture(engine, rawSimulation.glucose, rawSimulation.therapy,
            engine.predict(rawSimulation.glucose, rawSimulation.therapy), "meal-cycle-1", 7, now + 100,
            GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED, "model-a")
        assertEquals(PersonalMealCandidateStatus.INVALID_CONTEXT,
            PersonalMealCandidateBuilder.build(calibrated, rawIdentity, model(), meal, null) { now + 200 }.status)
        val identity = rawIdentity.copy(glucoseBasisId = GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED.id,
            calibrationModelId = "model-a")
        assertEquals(PersonalMealCandidateStatus.MODEL_UNAVAILABLE,
            PersonalMealCandidateBuilder.build(calibrated, identity, model(), meal, null) { now + 200 }.status)
        val rows = frames(engine).map { it.copy(calibrationIdentity = "model-a",
            measurementBasis = GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED) }
        val kernel = engine.mealInsulinKernelIdentity()
        val cohort = personalAbsorptionComparisonCohort("sensor-session-1", "model-a", kernel.id, kernel.durationMinutes,
            GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED)
        val fitted = PersonalAbsorptionResearchModel.fit(request, source(rows), cohort, emptyList()) { cutoff + 100 }!!
        assertEquals(PersonalMealCandidateStatus.CREATED,
            PersonalMealCandidateBuilder.build(calibrated, identity, fitted, meal, null) { now + 200 }.status)
        val unknown = MealSimulationContext.capture(engine, rawSimulation.glucose, rawSimulation.therapy,
            engine.predict(rawSimulation.glucose, rawSimulation.therapy), "meal-cycle-1", 7, now + 100)
        assertEquals(PersonalMealCandidateStatus.INVALID_CONTEXT,
            PersonalMealCandidateBuilder.build(unknown, rawIdentity, model(), meal, null) { now + 200 }.status)
    }

    @Test fun futureModelLateCompletionAndClockRollbackCannotRegister() = runBlocking {
        val (simulation, identity, meal) = fixture()
        for (time in listOf(now + 99, now + 300_000)) assertEquals(PersonalMealCandidateStatus.LATE,
            PersonalMealCandidateBuilder.build(simulation, identity, model(), meal, null) { time }.status)
        val late = PersonalAbsorptionResearchModel.fit(request, source(frames(engine())), cohort(engine()), emptyList()) { now + 201 }!!
        assertEquals(PersonalMealCandidateStatus.MODEL_UNAVAILABLE,
            PersonalMealCandidateBuilder.build(simulation, identity, late, meal, null) { now + 200 }.status)
        val times = listOf(now + 200, now + 199).iterator()
        assertEquals(PersonalMealCandidateStatus.LATE,
            PersonalMealCandidateBuilder.build(simulation, identity, model(), meal, null) { times.next() }.status)
        val finishing = listOf(now + 200, now + 300_000).iterator()
        assertEquals(PersonalMealCandidateStatus.LATE,
            PersonalMealCandidateBuilder.build(simulation, identity, model(), meal, null) { finishing.next() }.status)
        val futureCutoff = now + 1
        val rows = frames(engine())
        val futureSource = PersonalAbsorptionEpisodeProducer().produce(futureCutoff, "UTC", rows.map { it.input.meal }, rows)
        val future = PersonalAbsorptionResearchModel.fit(request.copy(cutoffMs = futureCutoff), futureSource,
            cohort(engine()), emptyList()) { now + 100 }!!
        assertEquals(PersonalMealCandidateStatus.MODEL_UNAVAILABLE,
            PersonalMealCandidateBuilder.build(simulation, identity, future, meal, null) { now + 200 }.status)
    }

    @Test fun rejectsAdjustedControlUnknownBasisVersionAndChangedRuntimeIdentity() = runBlocking {
        val (simulation, identity, meal) = fixture()
        val model = model()
        for (changed in listOf(identity.copy(basis = MealForecastBasis.ACCEPTED_CONTROL),
            identity.copy(predictionAtMs = now + 1), identity.copy(settingsRevision = 8),
            identity.copy(forecastCycleId = "other-cycle"), identity.copy(source = "other-source"),
            identity.copy(glucoseBasisId = "adjusted-cgm"),
            identity.copy(calibrationModelId = "calibration-2"),
            identity.copy(baselineModelVersion = "other-version"), identity.copy(recordedZoneId = "Asia/Tbilisi"))) {
            assertEquals(PersonalMealCandidateStatus.INVALID_CONTEXT,
                PersonalMealCandidateBuilder.build(simulation, changed, model, meal, null) { now + 200 }.status)
        }
        for (changed in listOf(identity.copy(sensorSessionKey = "sensor-session-2"))) {
            assertEquals(PersonalMealCandidateStatus.MODEL_UNAVAILABLE,
                PersonalMealCandidateBuilder.build(simulation, changed, model, meal, null) { now + 200 }.status)
        }
        val otherKernel = engine().apply { setInsulinDurationHours(4.5) }
        assertEquals(PersonalMealCandidateStatus.MODEL_UNAVAILABLE,
            PersonalMealCandidateBuilder.build(simulation, identity, model(otherKernel), meal, null) { now + 200 }.status)
    }

    @Test fun profilePortionCircularTimeAndCanonicalRevisionRemainBinding() = runBlocking {
        val (simulation, identity, meal) = fixture()
        val model = model()
        for (changed in listOf(meal.copy(profile = MealAbsorptionProfile.FAST), meal.copy(portion = MealPortion.SMALL),
            meal.copy(grams = 50.0),
            meal.copy(timestamp = meal.timestamp.minusSeconds(5 * 3600L)))) assertEquals(PersonalMealCandidateStatus.MEAL_MISMATCH,
            PersonalMealCandidateBuilder.build(simulation, identity, model, changed, null) { now + 200 }.status)
        for (changed in listOf(meal.copy(therapyRevision = "r2"),
            meal.copy(provenance = MealPortionProvenance.RESCUE))) assertEquals(PersonalMealCandidateStatus.ENGINE_UNAVAILABLE,
            PersonalMealCandidateBuilder.build(simulation, identity, model, changed, null) { now + 200 }.status)
    }

    @Test fun modelIsDeterministicAndSparseUnqualifiedOrUnimprovedDataCannotFit() {
        val engine = engine()
        val rows = frames(engine)
        val first = model(engine)
        val reordered = PersonalAbsorptionResearchModel.fit(request, source(rows.reversed()), cohort(engine), emptyList()) { cutoff + 100 }!!
        assertEquals(first.evidenceDigest, reordered.evidenceDigest)
        assertEquals(first.timingShape, reordered.timingShape)
        assertNull(first.giModel)
        assertNull(PersonalAbsorptionResearchModel.fit(request, source(rows.take(3)), cohort(engine), emptyList()) { cutoff + 100 })
        val unqualified = rows.map { it.copy(input = it.input.copy(insulin = emptyList())) }
        assertNull(PersonalAbsorptionResearchModel.fit(request, source(unqualified), cohort(engine), emptyList()) { cutoff + 100 })
        val noChange = frames(engine, duration = 120).map { row ->
            val curve = MealAbsorptionCurve(MealAbsorptionProfile.MIXED, 120)
            row.copy(input = row.input.copy(glucose = row.input.glucose.map { point ->
                val age = (point.timestampMs - row.input.meal.timestamp.toEpochMilli()) / 60_000.0
                point.copy(mmol = 6 + 35 * 0.3 * curve.absorbedFraction(age) - age / 5 * 0.03)
            }))
        }
        assertNull(PersonalAbsorptionResearchModel.fit(request, source(noChange), cohort(engine), emptyList()) { cutoff + 100 })
        assertNull(PersonalAbsorptionResearchModel.fit(request, source(rows), cohort(engine), emptyList()) { cutoff - 1 })
    }

    @Test fun laterHeldoutDataChangesEvidenceButNotTimingParameters() {
        val engine = engine()
        val rows = frames(engine)
        val altered = rows.mapIndexed { index, row -> if (index >= 6) row else row.copy(input = row.input.copy(
            glucose = row.input.glucose.map { point ->
                val age = (point.timestampMs - row.input.meal.timestamp.toEpochMilli()) / 60_000.0
                point.copy(mmol = point.mmol + if (age > 0) 0.001 * age / 180 else 0.0)
            })) }
        val before = model(engine)
        val after = PersonalAbsorptionResearchModel.fit(request, source(altered), cohort(engine), emptyList()) { cutoff + 100 }!!
        assertEquals(before.timingShape, after.timingShape)
        assertNotEquals(before.evidenceDigest, after.evidenceDigest)
    }

    @Test fun supportedKnownGiChangesCandidateAndMissingGuessedOrOutOfRangeGiRejects() = runBlocking {
        val (simulation, identity, meal) = fixture()
        val model = model(giEffect = -0.15, gi = giHistory())
        assertNotNull("Qualified synthetic GI effect must pass the conditional gate", model.giModel)
        val gi = PersonalGiEvidence(meal.canonicalId, "r1", 20.0, "USER", null, meal.timestamp.toEpochMilli())
        val low = PersonalMealCandidateBuilder.build(simulation, identity, model, meal, gi) { now + 200 }
        val high = PersonalMealCandidateBuilder.build(simulation, identity, model, meal, gi.copy(value = 80.0)) { now + 200 }
        assertEquals(PersonalMealCandidateStatus.CREATED, low.status)
        assertEquals(PersonalMealCandidateStatus.CREATED, high.status)
        assertNotEquals(low.candidate!!.values, high.candidate!!.values)
        for (bad in listOf(null, gi.copy(value = 19.0), gi.copy(value = 81.0), gi.copy(source = "MODEL"),
            gi.copy(source = "CATALOG", reference = null), gi.copy(therapyRevision = "r2"),
            gi.copy(availableAtMs = now + 1))) assertEquals(PersonalMealCandidateStatus.GI_UNAVAILABLE,
            PersonalMealCandidateBuilder.build(simulation, identity, model, meal, bad) { now + 200 }.status)
    }
}
