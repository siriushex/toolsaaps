package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.profile.MealAbsorptionContext
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Test

class HybridPredictionEngineV3Test {

    @Test
    fun modeledActiveInsulinUsesPredictionEligibilityAndInferredScale() {
        val now = 1_700_000_000_000L
        val explicit = TherapyEvent(now - 30 * 60_000L, "bolus", mapOf("units" to "1.0"))
        val inferred = TherapyEvent(
            now - 30 * 60_000L,
            "bolus",
            mapOf("units" to "1.0", "inferred" to "true")
        )
        val nonCarrying = TherapyEvent(now - 30 * 60_000L, "meal", mapOf("units" to "5.0"))
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        val explicitOnly = engine.modeledActiveInsulinUnitsForTest(listOf(explicit), now)
        val inferredOnly = engine.modeledActiveInsulinUnitsForTest(listOf(inferred), now)
        val withNonCarrying = engine.modeledActiveInsulinUnitsForTest(listOf(explicit, nonCarrying), now)
        val inferredEvidence = engine.modeledActiveInsulinEvidence(listOf(inferred), now)

        assertThat(inferredOnly).isLessThan(explicitOnly)
        assertThat(withNonCarrying).isWithin(1e-9).of(explicitOnly)
        assertThat(inferredEvidence.qualifiedEventCount).isEqualTo(0)
        assertThat(inferredEvidence.latestQualifiedEvidenceTimestamp).isNull()
    }

    @Test
    fun modeledActiveInsulinUsesSameDiaAndOnsetAdjustmentAsForecastCurve() {
        val now = 1_700_000_000_000L
        val event = TherapyEvent(now - 90 * 60_000L, "bolus", mapOf("units" to "2.0"))
        val default = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val shortDia = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
            setInsulinDurationHours(3.0)
        }
        val delayedOnset = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
            setInsulinOnsetMinutes(baseOnsetMinutes = 15.0, realOnsetMinutes = 45.0)
        }

        val defaultActive = default.modeledActiveInsulinUnitsForTest(listOf(event), now)
        val shortDiaActive = shortDia.modeledActiveInsulinUnitsForTest(listOf(event), now)
        val delayedActive = delayedOnset.modeledActiveInsulinUnitsForTest(listOf(event), now)

        assertThat(shortDiaActive).isLessThan(defaultActive)
        assertThat(delayedActive).isGreaterThan(defaultActive)
    }

    @Test
    fun dryRunEngineOutputIsIndependentOfPriorLiveMealContext() = runBlocking {
        val glucose = listOf(6.0, 6.05, 6.10, 6.15, 6.20, 6.25, 6.30, 6.35).series(790_000_000L)
        val meal = canonicalCarbEvent(
            ts = glucose.last().ts - 15 * MINUTE_MS,
            grams = 30,
            identity = 700,
            revision = "revision-1"
        )
        val live = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        live.setMealAbsorptionContext(mealContext("700", "revision-1", MealAbsorptionProfile.FAST, 60))
        live.predict(glucose, listOf(meal))
        val afterFastLiveCycle = live.newSimulationEngine().predict(glucose, listOf(meal))

        live.setMealAbsorptionContext(mealContext("700", "revision-1", MealAbsorptionProfile.FAT_PROTEIN, 360))
        live.predict(glucose, listOf(meal))
        val afterSlowLiveCycle = live.newSimulationEngine().predict(glucose, listOf(meal))

        assertThat(afterFastLiveCycle).isEqualTo(afterSlowLiveCycle)
    }

    @Test
    fun mealProfilesDisabledKeepV3OutputIdenticalDespitePayloadMetadata() = runBlocking {
        val glucose = listOf(6.0, 6.05, 6.1, 6.18, 6.26, 6.34, 6.42, 6.50).series(800_000_000L)
        val plain = TherapyEvent(glucose.last().ts - 20 * MINUTE_MS, "carbs", mapOf("grams" to "30"))
        val metadata = plain.copy(payload = plain.payload + mapOf(
            "foodProfile" to "fat_protein",
            "absorptionDurationMinutes" to "360"
        ))
        val plainEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val metadataEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        assertThat(plainEngine.predict(glucose, listOf(plain))).isEqualTo(
            metadataEngine.predict(glucose, listOf(metadata))
        )
        assertThat(plainEngine.lastDiagnosticsForTest()!!.announcedCarbStep).isEqualTo(
            metadataEngine.lastDiagnosticsForTest()!!.announcedCarbStep
        )
    }

    @Test
    fun enabledProfilesChangeSameCycleKnownCarbImpactBeforeUam() = runBlocking {
        val glucose = listOf(5.0, 6.5, 8.0, 9.5, 11.0, 12.5, 14.0, 15.5).series(850_000_000L)
        val fast = canonicalCarbEvent(
            ts = glucose.last().ts - MINUTE_MS,
            grams = 30,
            identity = 701,
            revision = "revision-1"
        )
        val slow = fast
        val noCarbEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val fastEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val slowEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        fastEngine.setMealAbsorptionContext(mealContext("701", "revision-1", MealAbsorptionProfile.FAST, 60))
        slowEngine.setMealAbsorptionContext(mealContext("701", "revision-1", MealAbsorptionProfile.FAT_PROTEIN, 360))

        noCarbEngine.predict(glucose, emptyList())
        fastEngine.predict(glucose, listOf(fast))
        slowEngine.predict(glucose, listOf(slow))

        val noCarbDiagnostics = checkNotNull(noCarbEngine.lastDiagnosticsForTest())
        val fastDiagnostics = checkNotNull(fastEngine.lastDiagnosticsForTest())
        val slowDiagnostics = checkNotNull(slowEngine.lastDiagnosticsForTest())
        assertThat(noCarbDiagnostics.rawUnifiedUamStep.sum()).isGreaterThan(0.0)
        assertThat(fastDiagnostics.rawUnifiedUamStep.sum()).isGreaterThan(0.0)
        assertThat(slowDiagnostics.rawUnifiedUamStep.sum()).isGreaterThan(0.0)
        assertThat(fastDiagnostics.unifiedUamSignedResidualMmol5).isGreaterThan(0.0)
        assertThat(slowDiagnostics.unifiedUamSignedResidualMmol5).isGreaterThan(0.0)
        assertThat(fastDiagnostics.knownInputAnnouncedCarbStep1)
            .isGreaterThan(slowDiagnostics.knownInputAnnouncedCarbStep1)
        assertThat(fastDiagnostics.rawUnifiedUamStep.sum())
            .isAtMost(slowDiagnostics.rawUnifiedUamStep.sum() * 0.9)
        assertThat(fastDiagnostics.unifiedUamSignedResidualMmol5)
            .isLessThan(noCarbDiagnostics.unifiedUamSignedResidualMmol5)
        assertThat(fastDiagnostics.rawUnifiedUamStep.sum())
            .isLessThan(noCarbDiagnostics.rawUnifiedUamStep.sum())
    }

    @Test
    fun completedFastAndMixedProfilesDoNotRevertToLegacyMedium() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(860_000_000L)
        val cases = listOf(
            canonicalCarbEvent(
                ts = glucose.last().ts - 70 * MINUTE_MS,
                grams = 30,
                identity = 702,
                revision = "revision-1"
            ) to mealContext("702", "revision-1", MealAbsorptionProfile.FAST, 60),
            canonicalCarbEvent(
                ts = glucose.last().ts - 130 * MINUTE_MS,
                grams = 30,
                identity = 703,
                revision = "revision-1"
            ) to mealContext("703", "revision-1", MealAbsorptionProfile.MIXED, 120)
        )

        cases.forEach { (event, context) ->
            val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
            engine.setMealAbsorptionContext(context)

            engine.predict(glucose, listOf(event))

            val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())
            assertThat(diagnostics.announcedCarbStep.drop(1).sum()).isWithin(1e-9).of(0.0)
            assertThat(diagnostics.residualCarbsNowGrams).isWithin(1e-9).of(0.0)
            assertThat(diagnostics.knownInputAnnouncedCarbStep1).isWithin(1e-9).of(0.0)
        }
    }

    @Test
    fun matchingPerMealRevisionIsAuditableAndStaleRevisionFallsBackToManual() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(870_000_000L)
        val event = canonicalCarbEvent(
            ts = glucose.last().ts - 15 * MINUTE_MS,
            grams = 25,
            identity = 704,
            revision = "revision-current",
            extraJson = ",\"foodProfile\":\"fat_protein\",\"absorptionDurationMinutes\":360"
        )
        val matching = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        matching.setMealAbsorptionContext(
            MealAbsorptionContext(
                enabled = true,
                perMealOverrides = mapOf(
                    "704" to MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60, "revision-current")
                )
            )
        )
        val stale = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        stale.setMealAbsorptionContext(
            MealAbsorptionContext(
                enabled = true,
                perMealOverrides = mapOf(
                    "704" to MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60, "revision-stale")
                ),
                manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)
            )
        )

        matching.predict(glucose, listOf(event))
        stale.predict(glucose, listOf(event))

        assertThat(checkNotNull(matching.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=704;revision=revision-current;reference=TRUSTED;profile=FAST;duration=60;source=PER_MEAL")
            .inOrder()
        assertThat(checkNotNull(stale.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=704;revision=revision-current;reference=TRUSTED;profile=FAT_PROTEIN;duration=360;source=MANUAL")
            .inOrder()
    }

    @Test
    fun typedCanonicalReferenceRejectsSpoofedPayloadAliasesForPerMealSelection() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(872_000_000L)
        val event = canonicalCarbEvent(
            ts = glucose.last().ts - 15 * MINUTE_MS,
            grams = 25,
            identity = 705,
            revision = "canonical-revision",
            extraJson = ",\"carbId\":\"spoofed-identity\",\"therapyRevisionHash\":\"spoofed-revision\",\"aapsCarbDigest\":\"spoofed-digest\""
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        engine.setMealAbsorptionContext(
            MealAbsorptionContext(
                enabled = true,
                perMealOverrides = mapOf(
                    "705" to MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60, "spoofed-digest")
                ),
                manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)
            )
        )

        engine.predict(glucose, listOf(event))

        assertThat(checkNotNull(engine.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=705;revision=canonical-revision;reference=TRUSTED;profile=FAT_PROTEIN;duration=360;source=MANUAL")
            .inOrder()
    }

    @Test
    fun conflictingCanonicalReferenceFallsBackToManualInsteadOfPerMeal() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(873_000_000L)
        val event = TherapyEventEntity(
            id = "canonical-reference-conflict",
            timestamp = glucose.last().ts - 15 * MINUTE_MS,
            type = "carbs",
            payloadJson = "{\"grams\":25,\"aapsCarbId\":706,\"aapsRevisionId\":\"revision-a\",\"aaps_revision_id\":\"revision-b\"}"
        ).toDomain(Gson())
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        engine.setMealAbsorptionContext(
            MealAbsorptionContext(
                enabled = true,
                perMealOverrides = mapOf(
                    "706" to MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60, "revision-a")
                ),
                manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)
            )
        )

        engine.predict(glucose, listOf(event))

        assertThat(checkNotNull(engine.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=absent;revision=absent;reference=CONFLICT;profile=FAT_PROTEIN;duration=360;source=MANUAL")
            .inOrder()
    }

    @Test
    fun conflictingCanonicalIdentityFallsBackToManualInsteadOfPerMeal() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(873_500_000L)
        val event = TherapyEventEntity(
            id = "canonical-identity-conflict",
            timestamp = glucose.last().ts - 15 * MINUTE_MS,
            type = "carbs",
            payloadJson = "{\"grams\":25,\"aapsCarbId\":706,\"aaps_carb_id\":707,\"aapsRevisionId\":\"revision-a\"}"
        ).toDomain(Gson())
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        engine.setMealAbsorptionContext(
            MealAbsorptionContext(
                enabled = true,
                perMealOverrides = mapOf(
                    "706" to MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60, "revision-a")
                ),
                manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)
            )
        )

        engine.predict(glucose, listOf(event))

        assertThat(checkNotNull(engine.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=absent;revision=absent;reference=CONFLICT;profile=FAT_PROTEIN;duration=360;source=MANUAL")
            .inOrder()
    }

    @Test
    fun missingTypedReferenceRejectsSpoofedRawAliasesInProvenance() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(874_000_000L)
        val event = TherapyEvent(
            ts = glucose.last().ts - 15 * MINUTE_MS,
            type = "carbs",
            payload = mapOf("grams" to "25", "carbId" to "710", "therapyRevisionHash" to "revision-raw")
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        engine.setMealAbsorptionContext(
            MealAbsorptionContext(
                enabled = true,
                perMealOverrides = mapOf(
                    "710" to MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60, "revision-raw")
                ),
                manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)
            )
        )

        engine.predict(glucose, listOf(event))

        assertThat(checkNotNull(engine.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=absent;revision=absent;reference=MISSING;profile=FAT_PROTEIN;duration=360;source=MANUAL")
            .inOrder()
    }

    @Test
    fun manualAutoAndDefaultProvenanceAlwaysIncludesTreatmentReference() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(875_000_000L)
        val event = canonicalCarbEvent(
            ts = glucose.last().ts - 15 * MINUTE_MS,
            grams = 25,
            identity = 707,
            revision = "revision-audit"
        )
        val manual = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val auto = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val default = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        manual.setMealAbsorptionContext(
            MealAbsorptionContext(enabled = true, manual = MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60))
        )
        auto.setMealAbsorptionContext(
            MealAbsorptionContext(enabled = true, auto = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360))
        )
        default.setMealAbsorptionContext(MealAbsorptionContext(enabled = true))

        manual.predict(glucose, listOf(event))
        auto.predict(glucose, listOf(event))
        default.predict(glucose, listOf(event))

        assertThat(checkNotNull(manual.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=707;revision=revision-audit;reference=TRUSTED;profile=FAST;duration=60;source=MANUAL")
            .inOrder()
        assertThat(checkNotNull(auto.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=707;revision=revision-audit;reference=TRUSTED;profile=FAT_PROTEIN;duration=360;source=AUTO")
            .inOrder()
        assertThat(checkNotNull(default.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=707;revision=revision-audit;reference=TRUSTED;profile=MIXED;duration=120;source=DEFAULT")
            .inOrder()
    }

    @Test
    fun slowProfileAppliesToHistoricalIntervalComponents() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(880_000_000L)
        val slow = canonicalCarbEvent(
            ts = glucose.last().ts - 200 * MINUTE_MS,
            grams = 30,
            identity = 708,
            revision = "revision-1"
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        engine.setMealAbsorptionContext(mealContext("708", "revision-1", MealAbsorptionProfile.FAT_PROTEIN, 360))

        engine.predict(glucose, listOf(slow))

        assertThat(checkNotNull(engine.lastDiagnosticsForTest()).knownInputAnnouncedCarbStep1).isGreaterThan(0.0)
    }

    @Test
    fun ultraFastRescueCarbsKeepLegacyCurveWhenProfilesAreEnabled() = runBlocking {
        val glucose = listOf(3.8, 3.9, 4.0, 4.1, 4.25, 4.4, 4.55, 4.7).series(875_000_000L)
        val rescue = TherapyEvent(
            glucose.last().ts - 15 * MINUTE_MS,
            "carbs",
            mapOf(
                "grams" to "15",
                "carbType" to "ultra_fast",
                "foodProfile" to "fat_protein",
                "absorptionDurationMinutes" to "360"
            )
        )
        val legacy = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val profiled = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        profiled.setMealAbsorptionContext(MealAbsorptionContext(enabled = true))

        assertThat(profiled.predict(glucose, listOf(rescue))).isEqualTo(legacy.predict(glucose, listOf(rescue)))
        assertThat(profiled.lastDiagnosticsForTest()!!.carbAbsorptionProvenance).isEmpty()
    }

    @Test
    fun importedCarbsWithoutMetadataUseMixedFallbackWithProvenance() = runBlocking {
        val glucose = listOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0).series(890_000_000L)
        val imported = canonicalCarbEvent(
            ts = glucose.last().ts - 15 * MINUTE_MS,
            grams = 25,
            identity = 709,
            revision = "revision-1"
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        engine.setMealAbsorptionContext(MealAbsorptionContext(enabled = true))

        engine.predict(glucose, listOf(imported))

        assertThat(checkNotNull(engine.lastDiagnosticsForTest()).carbAbsorptionProvenance)
            .containsExactly("identity=709;revision=revision-1;reference=TRUSTED;profile=MIXED;duration=120;source=DEFAULT")
            .inOrder()
    }

    @Test
    fun t1_v3DisabledMatchesLegacy() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val now = 1_000_000_000L
        val glucose = listOf(6.1, 6.2, 6.25, 6.35, 6.4, 6.5, 6.6, 6.75).series(now)
        val events = listOf(
            TherapyEvent(glucose.last().ts - 25 * 60_000L, "carbs", mapOf("grams" to "18")),
            TherapyEvent(glucose.last().ts - 15 * 60_000L, "correction_bolus", mapOf("units" to "0.9"))
        )

        val actual = engine.predict(glucose, events)
        val legacy = engine.predictLegacyForTest(glucose, events)

        assertThat(actual).isEqualTo(legacy)
    }

    @Test
    fun t2_kalmanAdaptiveRReducesOutlierImpact() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val start = 2_000_000_000L

        val cycle1 = listOf(6.00, 6.05, 6.10, 6.15, 6.20, 6.25, 6.30, 6.35).series(start)
        engine.predict(cycle1, emptyList())
        val d1 = engine.lastDiagnosticsForTest()!!

        val cycle2Start = cycle1.last().ts + FIVE_MINUTES
        val cycle2 = listOf(6.40, 6.45, 6.50, 12.50, 6.60, 6.65, 6.70, 6.75).series(cycle2Start)
        engine.predict(cycle2, emptyList())
        val d2 = engine.lastDiagnosticsForTest()!!

        val cycle3Start = cycle2.last().ts + FIVE_MINUTES
        val cycle3 = listOf(6.80, 6.85, 6.90, 6.95, 7.00, 7.05, 7.10, 7.15).series(cycle3Start)
        engine.predict(cycle3, emptyList())
        val d3 = engine.lastDiagnosticsForTest()!!

        assertThat(d2.kfEwmaNis).isGreaterThan(d1.kfEwmaNis)
        assertThat(d2.kfSigmaZ).isGreaterThan(d1.kfSigmaZ)
        assertThat(abs(d2.rocPer5Used)).isAtMost(1.2)
        assertThat(d3.kfSigmaZ).isAtMost(d2.kfSigmaZ)
    }

    @Test
    fun t3_ar1CapturesPersistentDrift() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val baseStart = 3_000_000_000L

        repeat(12) { idx ->
            val start = baseStart + idx * 10 * 60_000L
            // Advancing the window must not rewrite its overlapping historical samples.
            val glucose = List(8) { pointIndex -> 6.0 + (idx * 2 + pointIndex) * 0.08 }.series(start)
            engine.predict(glucose, emptyList())
            assertThat(engine.lastDiagnosticsForTest()!!.kfHistoryRebuilt).isFalse()
        }

        val d = engine.lastDiagnosticsForTest()!!
        assertThat(d.arMu).isGreaterThan(0.0)
        assertThat(d.arPhi).isAtLeast(0.0)
        assertThat(d.arPhi).isAtMost(0.97)
        assertThat(d.trendCum60Clamped).isAtMost(0.55 * 12 + 0.7)
        assertThat(d.trendCum60Clamped).isAtLeast(-(0.55 * 12 + 0.7))
    }

    @Test
    fun t4_uamRisingNoTherapy() = runBlocking {
        val withUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val noUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        val now = 4_000_000_000L
        val glucose = listOf(6.0, 6.01, 6.03, 6.05, 6.07, 6.10, 6.14, 6.72).series(now)

        val predWith = withUam.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val dWith = withUam.lastDiagnosticsForTest()!!
        val predNo = noUam.predict(glucose, emptyList()).associateBy { it.horizonMinutes }

        assertThat(dWith.uci0).isGreaterThan(0.0)
        assertThat(dWith.uamStep[1]).isGreaterThan(0.0)
        assertThat(dWith.residualRoc0).isAtMost(0.0)
        dWith.resolvedMealPressureStep.indices.forEach { index ->
            assertThat(dWith.uamStep[index]).isWithin(1e-9).of(dWith.resolvedMealPressureStep[index])
        }
        assertThat(predWith.getValue(60).valueMmol).isGreaterThan(glucose.last().valueMmol)
        assertThat(predNo.getValue(60).valueMmol).isGreaterThan(glucose.last().valueMmol)
    }

    @Test
    fun t5_growthExplainedByAnnouncedCarbs() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val noUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        val now = 5_000_000_000L
        val glucose = listOf(6.0, 6.08, 6.18, 6.3, 6.45, 6.62, 6.80, 6.95).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "55")
            )
        )

        val pred = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val d = engine.lastDiagnosticsForTest()!!
        val predNoUam = noUam.predict(glucose, events).associateBy { it.horizonMinutes }

        d.resolvedMealPressureStep.indices.forEach { index ->
            assertThat(d.resolvedMealPressureStep[index]).isAtMost(
                maxOf(d.announcedCarbStep[index], d.rawUnifiedUamStep[index]) + 1e-9
            )
        }
        val fullAnnounced60 = d.announcedCarbStep.take(13).sum()
        assertThat(pred.getValue(60).valueMmol)
            .isLessThan(predNoUam.getValue(60).valueMmol + fullAnnounced60)
    }

    @Test
    fun t5b_suspectedUnifiedUamDoesNotCreateLongTail() = runBlocking {
        val engine = HybridPredictionEngine(
            enableEnhancedPredictionV3 = true,
            enableUam = true,
            enableUamVirtualMealFit = false
        ).withTestUamSensitivity()
        val now = 5_500_000_000L
        val glucose = listOf(6.40, 6.42, 6.44, 6.46, 6.48, 6.50, 6.50, 6.62).series(now)

        val forecasts = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val d = engine.lastDiagnosticsForTest()!!

        assertThat(d.uci0).isGreaterThan(0.0)
        assertThat(d.unifiedUamState).isEqualTo("SUSPECTED")
        assertThat(d.uamStep.all { it == 0.0 }).isTrue()
        assertThat(d.uamTailGuardMultiplier).isEqualTo(1.0)
        assertThat(forecasts.getValue(60).valueMmol - forecasts.getValue(30).valueMmol).isLessThan(0.8)
    }

    @Test
    fun announcedCarbImpactSuppressesSuspectedRawUnifiedOverlap() = runBlocking {
        val engine = HybridPredictionEngine(
            enableEnhancedPredictionV3 = true,
            enableUam = true,
            enableUamVirtualMealFit = false
        ).withTestUamSensitivity()
        val now = 5_750_000_000L
        val glucose = listOf(6.40, 6.42, 6.44, 6.46, 6.48, 6.50, 6.50, 6.62).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "30")
            )
        )

        engine.predict(glucose, events)
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(diagnostics.unifiedUamState).isEqualTo("INACTIVE")
        assertThat(diagnostics.uamActive).isFalse()
        assertThat(diagnostics.rawUnifiedUamStep.all { it == 0.0 }).isTrue()
        assertThat(diagnostics.announcedCarbStep.any { it > 0.0 }).isTrue()
        assertThat(diagnostics.uamStep.all { it == 0.0 }).isTrue()
        assertThat(diagnostics.doubleCountPrevented).isFalse()
    }

    @Test
    fun t6_fallingWithInsulin() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        val now = 6_000_000_000L
        val glucose = listOf(9.4, 9.2, 9.0, 8.75, 8.5, 8.25, 8.0, 7.8).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.2")
            )
        )

        val pred = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val d = engine.lastDiagnosticsForTest()!!

        assertThat(d.uci0).isEqualTo(0.0)
        assertThat(pred.getValue(5).valueMmol).isLessThan(glucose.last().valueMmol)
        assertThat(pred.getValue(30).valueMmol).isLessThan(pred.getValue(5).valueMmol)
        assertThat(pred.getValue(60).valueMmol).isLessThan(pred.getValue(30).valueMmol)
    }

    @Test
    fun t7_clamps() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        val now = 7_000_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.25, 6.35, 6.45, 6.55, 11.2).series(now)
        val events = listOf(
            TherapyEvent(glucose.last().ts - 10 * 60_000L, "carbs", mapOf("grams" to "200")),
            TherapyEvent(glucose.last().ts - 5 * 60_000L, "correction_bolus", mapOf("units" to "10"))
        )

        val forecasts = engine.predict(glucose, events)
        val d = engine.lastDiagnosticsForTest()!!

        assertThat(d.uci0).isAtMost(d.uciMax + 1e-9)
        d.therapyCumClamped.forEach { v ->
            assertThat(v).isAtLeast(-6.0)
            assertThat(v).isAtMost(6.0)
        }
        val trendAbsLimit = 0.55 * 12 + 0.7
        assertThat(d.trendCum60Clamped).isAtMost(trendAbsLimit)
        assertThat(d.trendCum60Clamped).isAtLeast(-trendAbsLimit)
        assertThat(d.arPhi).isAtLeast(0.0)
        assertThat(d.arPhi).isAtMost(0.97)
        assertThat(d.arSigmaE).isAtLeast(0.05)
        assertThat(d.arSigmaE).isAtMost(0.60)

        forecasts.forEach { f ->
            assertThat(f.valueMmol).isAtLeast(2.2)
            assertThat(f.valueMmol).isAtMost(22.0)
            assertThat(f.ciLow).isAtLeast(2.2)
            assertThat(f.ciHigh).isAtMost(22.0)
        }
    }

    @Test
    fun t7b_minuteLevelCgmIsCanonicalizedIntoResponsiveFiveMinuteTrend() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val start = 7_500_000_000L
        val glucose = (0 until 21).map { minute ->
            GlucosePoint(
                ts = start + minute * 60_000L,
                valueMmol = 6.0 + minute * 0.09,
                source = "test",
                quality = DataQuality.OK
            )
        }

        val forecasts = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val diagnostics = engine.lastDiagnosticsForTest()!!

        assertThat(diagnostics.rocPer5Used).isGreaterThan(0.25)
        assertThat(forecasts.getValue(30).valueMmol).isGreaterThan(glucose.last().valueMmol)
    }

    @Test
    fun t7c_futureTherapyEventsAreIgnored() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val now = 7_600_000_000L
        val glucose = listOf(7.2, 7.2, 7.2, 7.2, 7.2, 7.2, 7.2, 7.2).series(now)
        val futureEvents = listOf(
            TherapyEvent(
                ts = glucose.last().ts + 10 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "45")
            ),
            TherapyEvent(
                ts = glucose.last().ts + 15 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.0")
            )
        )

        val noEvents = engine.predict(glucose, emptyList())
        val withFuture = engine.predict(glucose, futureEvents)

        assertThat(withFuture).isEqualTo(noEvents)
    }

    @Test
    fun t8_announcedCarbsOnFlatProfilePushesForecastUpByHorizon() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val now = 8_000_000_000L
        val glucose = listOf(7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 15 * 60_000L,
                type = "carbs",
                payload = mapOf("carbs" to "40")
            )
        )

        val forecasts = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val f5 = forecasts.getValue(5).valueMmol
        val f30 = forecasts.getValue(30).valueMmol
        val f60 = forecasts.getValue(60).valueMmol

        assertThat(f5).isAtLeast(7.0)
        assertThat(f30).isGreaterThan(f5)
        assertThat(f60).isAtLeast(f30)
    }

    @Test
    fun t9_announcedInsulinOnFlatProfilePushesForecastDownByHorizon() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val now = 9_000_000_000L
        val glucose = listOf(9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.5")
            )
        )

        val forecasts = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val f5 = forecasts.getValue(5).valueMmol
        val f30 = forecasts.getValue(30).valueMmol
        val f60 = forecasts.getValue(60).valueMmol

        assertThat(f5).isAtMost(9.21)
        assertThat(f30).isLessThan(f5)
        assertThat(f60).isAtMost(f30)
    }

    @Test
    fun t10_knownInputMovesAnnouncedCarbRiseOutOfResidualTrend() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val now = 9_500_000_000L
        val glucose = listOf(6.0, 6.15, 6.32, 6.48, 6.63, 6.78, 6.91, 7.02).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 30 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "42")
            )
        )

        engine.predict(glucose, events)
        val diagnostics = engine.lastDiagnosticsForTest()!!

        assertThat(diagnostics.knownInputTherapyStep1).isGreaterThan(0.0)
        assertThat(diagnostics.knownInputUamStep1).isAtLeast(0.0)
        assertThat(abs(diagnostics.residualRoc0)).isLessThan(diagnostics.knownInputTherapyStep1 + 0.25)
        assertThat(abs(diagnostics.trendStep.getOrElse(1) { 0.0 })).isLessThan(0.35)
    }

    @Test
    fun t9b_inferredInsulinImpactIsCalibratedLowerThanExplicit() = runBlocking {
        val explicitEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val inferredEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val now = 9_100_000_000L
        val glucose = listOf(8.8, 8.8, 8.8, 8.8, 8.8, 8.8, 8.8, 8.8).series(now)

        val explicit = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.0")
            )
        )
        val inferred = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf(
                    "units" to "2.0",
                    "inferred" to "true",
                    "method" to "iob_jump"
                )
            )
        )

        val explicitPred = explicitEngine.predict(glucose, explicit).associateBy { it.horizonMinutes }
        val inferredPred = inferredEngine.predict(glucose, inferred).associateBy { it.horizonMinutes }

        assertThat(inferredPred.getValue(30).valueMmol).isGreaterThan(explicitPred.getValue(30).valueMmol)
        assertThat(inferredPred.getValue(60).valueMmol).isGreaterThan(explicitPred.getValue(60).valueMmol)
    }

    @Test
    fun t10_announcedCarbsAndInsulinProduceNonFlatHorizons() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val now = 10_000_000_000L
        val glucose = listOf(8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "25")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            )
        )

        val forecasts = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val values = listOf(
            forecasts.getValue(5).valueMmol,
            forecasts.getValue(30).valueMmol,
            forecasts.getValue(60).valueMmol
        )
        val uniqueRounded = values.map { String.format("%.2f", it) }.toSet()

        assertThat(uniqueRounded.size).isGreaterThan(1)
    }

    @Test
    fun t10b_externalSensitivityBlendWeight_scalesPredictionImpact() = runBlocking {
        val now = 10_500_000_000L
        val glucose = listOf(8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.0")
            )
        )
        val baseEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val softBlendEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val fullBlendEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        softBlendEngine.setSensitivityOverride(
            isfMmolPerUnit = 5.0,
            crGramPerUnit = 10.0,
            confidence = 0.9,
            blendWeight = 0.45
        )
        fullBlendEngine.setSensitivityOverride(
            isfMmolPerUnit = 5.0,
            crGramPerUnit = 10.0,
            confidence = 0.9,
            blendWeight = 1.0
        )

        val basePred = baseEngine.predict(glucose, events).associateBy { it.horizonMinutes }
        val softPred = softBlendEngine.predict(glucose, events).associateBy { it.horizonMinutes }
        val fullPred = fullBlendEngine.predict(glucose, events).associateBy { it.horizonMinutes }

        assertThat(softPred.getValue(30).valueMmol).isLessThan(basePred.getValue(30).valueMmol)
        assertThat(fullPred.getValue(30).valueMmol).isLessThan(softPred.getValue(30).valueMmol)
        assertThat(softPred.getValue(60).valueMmol).isLessThan(basePred.getValue(60).valueMmol)
        assertThat(fullPred.getValue(60).valueMmol).isLessThan(softPred.getValue(60).valueMmol)
    }

    @Test
    fun sparseHistoricalCorrectionDoesNotChangeProductionForecastSensitivity() = runBlocking {
        val now = 10_650_000_000L
        val historicalCorrectionTs = now - 12 * 60 * 60_000L
        val glucose = listOf(
            GlucosePoint(historicalCorrectionTs - 10 * 60_000L, 10.0, "test"),
            GlucosePoint(historicalCorrectionTs + 90 * 60_000L, 4.0, "test")
        ) + listOf(8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6)
            .series(now - 35 * 60_000L)
        val activeCorrection = TherapyEvent(
            ts = now - 10 * 60_000L,
            type = "correction_bolus",
            payload = mapOf("units" to "2.0")
        )
        val sparseHistoricalCorrection = TherapyEvent(
            ts = historicalCorrectionTs,
            type = "correction_bolus",
            payload = mapOf("units" to "1.0")
        )
        val baseline = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val sparseHistory = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        val baselineForecasts = baseline.predict(glucose, listOf(activeCorrection))
        val sparseForecasts = sparseHistory.predict(
            glucose,
            listOf(sparseHistoricalCorrection, activeCorrection)
        )

        assertThat(sparseForecasts).isEqualTo(baselineForecasts)
    }

    @Test
    fun externalIsfOnlyOverrideChangesCorrectionForecast() = runBlocking {
        val now = 10_750_000_000L
        val glucose = listOf(8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6).series(now)
        val correction = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "correction_bolus",
            payload = mapOf("units" to "2.0")
        )
        val baseEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val isfOnlyEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        isfOnlyEngine.setSensitivityOverride(
            isfMmolPerUnit = 5.0,
            crGramPerUnit = null,
            confidence = 1.0,
            minConfidenceRequired = 1.0,
            blendWeight = 1.0
        )

        val baseForecast = baseEngine.predict(glucose, listOf(correction)).associateBy { it.horizonMinutes }
        val isfOnlyForecast = isfOnlyEngine.predict(glucose, listOf(correction)).associateBy { it.horizonMinutes }

        assertThat(isfOnlyForecast.getValue(30).valueMmol)
            .isLessThan(baseForecast.getValue(30).valueMmol)
    }

    @Test
    fun externalCrOnlyOverrideChangesCarbForecast() = runBlocking {
        val now = 10_875_000_000L
        val glucose = listOf(6.4, 6.4, 6.4, 6.4, 6.4, 6.4, 6.4, 6.4).series(now)
        val carbs = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30")
        )
        val baseEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val crOnlyEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        crOnlyEngine.setSensitivityOverride(
            isfMmolPerUnit = null,
            crGramPerUnit = 20.0,
            confidence = 1.0,
            minConfidenceRequired = 1.0,
            blendWeight = 1.0
        )

        val baseForecast = baseEngine.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }
        val crOnlyForecast = crOnlyEngine.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }

        assertThat(crOnlyForecast.getValue(30).valueMmol)
            .isLessThan(baseForecast.getValue(30).valueMmol)
    }

    @Test
    fun independentOverridesKeepIsfActiveWhenCrConfidenceGateRejects() = runBlocking {
        val now = 10_900_000_000L
        val glucose = listOf(8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6, 8.6).series(now)
        val correction = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "correction_bolus",
            payload = mapOf("units" to "2.0")
        )
        val carbs = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30")
        )
        val base = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val isfOnly = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val independent = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        val activeIsf = SensitivityMetricOverride(
            value = 5.0,
            confidence = 1.0,
            minConfidenceRequired = 1.0,
            blendWeight = 1.0,
            source = "aaps"
        )
        isfOnly.setSensitivityOverrides(isf = activeIsf, cr = null)
        independent.setSensitivityOverrides(
            isf = activeIsf,
            cr = SensitivityMetricOverride(
                value = 20.0,
                confidence = 0.2,
                minConfidenceRequired = 0.8,
                blendWeight = 1.0,
                source = "evidence"
            )
        )

        val baseCorrection = base.predict(glucose, listOf(correction)).associateBy { it.horizonMinutes }
        val independentCorrection = independent.predict(glucose, listOf(correction)).associateBy { it.horizonMinutes }
        val isfOnlyCarbs = isfOnly.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }
        val independentCarbs = independent.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }

        assertThat(independentCorrection.getValue(30).valueMmol)
            .isLessThan(baseCorrection.getValue(30).valueMmol)
        assertThat(independentCarbs.getValue(30).valueMmol)
            .isWithin(1e-9)
            .of(isfOnlyCarbs.getValue(30).valueMmol)
    }

    @Test
    fun independentOverridesKeepCrActiveWhenIsfConfidenceGateRejects() = runBlocking {
        val now = 10_925_000_000L
        val glucose = listOf(6.4, 6.4, 6.4, 6.4, 6.4, 6.4, 6.4, 6.4).series(now)
        val correction = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "correction_bolus",
            payload = mapOf("units" to "2.0")
        )
        val carbs = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30")
        )
        val base = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val crOnly = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val independent = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        val activeCr = SensitivityMetricOverride(
            value = 20.0,
            confidence = 1.0,
            minConfidenceRequired = 1.0,
            blendWeight = 1.0,
            source = "evidence"
        )
        crOnly.setSensitivityOverrides(isf = null, cr = activeCr)
        independent.setSensitivityOverrides(
            isf = SensitivityMetricOverride(
                value = 5.0,
                confidence = 0.2,
                minConfidenceRequired = 0.8,
                blendWeight = 1.0,
                source = "aaps"
            ),
            cr = activeCr
        )

        val baseCarbs = base.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }
        val crOnlyCorrection = crOnly.predict(glucose, listOf(correction)).associateBy { it.horizonMinutes }
        val independentCorrection = independent.predict(glucose, listOf(correction)).associateBy { it.horizonMinutes }
        val crOnlyCarbs = crOnly.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }
        val independentCarbs = independent.predict(glucose, listOf(carbs)).associateBy { it.horizonMinutes }

        assertThat(independentCarbs.getValue(30).valueMmol)
            .isLessThan(baseCarbs.getValue(30).valueMmol)
        assertThat(independentCorrection.getValue(30).valueMmol)
            .isWithin(1e-9)
            .of(crOnlyCorrection.getValue(30).valueMmol)
        assertThat(independentCarbs.getValue(30).valueMmol)
            .isWithin(1e-9)
            .of(crOnlyCarbs.getValue(30).valueMmol)
    }

    @Test
    fun independentOverridesBoundEachMetricBeforeBlending() = runBlocking {
        val now = 10_950_000_000L
        val glucose = listOf(8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.0")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "30")
            )
        )
        val bounded = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val outOfRange = HybridPredictionEngine(enableEnhancedPredictionV3 = false)

        bounded.setSensitivityOverrides(
            isf = SensitivityMetricOverride(18.0, 1.0, 1.0, 1.0, "aaps"),
            cr = SensitivityMetricOverride(60.0, 1.0, 1.0, 1.0, "evidence")
        )
        outOfRange.setSensitivityOverrides(
            isf = SensitivityMetricOverride(100.0, 1.0, 1.0, 1.0, "aaps"),
            cr = SensitivityMetricOverride(100.0, 1.0, 1.0, 1.0, "evidence")
        )

        val boundedForecast = bounded.predict(glucose, events).associateBy { it.horizonMinutes }
        val outOfRangeForecast = outOfRange.predict(glucose, events).associateBy { it.horizonMinutes }

        listOf(5, 30, 60).forEach { horizon ->
            assertThat(outOfRangeForecast.getValue(horizon).valueMmol)
                .isWithin(1e-9)
                .of(boundedForecast.getValue(horizon).valueMmol)
        }
    }

    @Test
    fun t13_fastCarbsPushEarlierThanProteinSlow() = runBlocking {
        val fastEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val slowEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val now = 12_000_000_000L
        val glucose = listOf(6.8, 6.8, 6.8, 6.8, 6.8, 6.8, 6.8, 6.8).series(now)

        val fastEvent = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30", "food" to "honey and banana")
        )
        val slowEvent = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30", "food" to "chicken breast")
        )

        val fast = fastEngine.predict(glucose, listOf(fastEvent)).associateBy { it.horizonMinutes }
        val slow = slowEngine.predict(glucose, listOf(slowEvent)).associateBy { it.horizonMinutes }

        assertThat(fast.getValue(30).valueMmol).isGreaterThan(slow.getValue(30).valueMmol)
        assertThat(slow.getValue(60).valueMmol).isAtLeast(slow.getValue(5).valueMmol)
    }

    @Test
    fun t11_defaultInsulinProfileIsNovorapid() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        assertThat(engine.currentInsulinProfileForTest()).isEqualTo(InsulinActionProfileId.NOVORAPID)
    }

    @Test
    fun t12_lyumjevActsFasterThanNovorapidOnEarlyHorizons() = runBlocking {
        val now = 11_000_000_000L
        val glucose = listOf(9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 5 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.4")
            )
        )

        val novorapid = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        novorapid.setInsulinProfile(InsulinActionProfileId.NOVORAPID)
        val novPred = novorapid.predict(glucose, events).associateBy { it.horizonMinutes }

        val lyumjev = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        lyumjev.setInsulinProfile(InsulinActionProfileId.LYUMJEV)
        val lyuPred = lyumjev.predict(glucose, events).associateBy { it.horizonMinutes }

        assertThat(lyuPred.getValue(5).valueMmol).isLessThan(novPred.getValue(5).valueMmol)
        assertThat(lyuPred.getValue(30).valueMmol).isLessThan(novPred.getValue(30).valueMmol)
        assertThat(lyuPred.getValue(60).valueMmol).isLessThan(novPred.getValue(60).valueMmol)
    }

    @Test
    fun t12a_defaultDiaTracksSelectedInsulinProfile() = runBlocking {
        val novorapid = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        novorapid.setInsulinProfile(InsulinActionProfileId.NOVORAPID)

        val apidra = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        apidra.setInsulinProfile(InsulinActionProfileId.APIDRA)

        assertThat(novorapid.currentInsulinDurationHoursForTest()).isWithin(1e-9).of(5.0)
        assertThat(apidra.currentInsulinDurationHoursForTest()).isWithin(1e-9).of(280.0 / 60.0)
        assertThat(apidra.currentInsulinDurationHoursForTest())
            .isLessThan(novorapid.currentInsulinDurationHoursForTest())
    }

    @Test
    fun t12b_diaOverrideChangesInsulinImpact() = runBlocking {
        val now = 11_500_000_000L
        val glucose = listOf(9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.0")
            )
        )

        val shortDia = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        shortDia.setInsulinProfile(InsulinActionProfileId.NOVORAPID)
        shortDia.setInsulinDurationHours(3.0)
        val shortPred = shortDia.predict(glucose, events).associateBy { it.horizonMinutes }

        val defaultDia = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        defaultDia.setInsulinProfile(InsulinActionProfileId.NOVORAPID)
        val defaultPred = defaultDia.predict(glucose, events).associateBy { it.horizonMinutes }

        val longDia = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        longDia.setInsulinProfile(InsulinActionProfileId.NOVORAPID)
        longDia.setInsulinDurationHours(7.0)
        val longPred = longDia.predict(glucose, events).associateBy { it.horizonMinutes }

        assertThat(shortPred.getValue(60).valueMmol).isLessThan(defaultPred.getValue(60).valueMmol)
        assertThat(defaultPred.getValue(60).valueMmol).isLessThan(longPred.getValue(60).valueMmol)
    }

    @Test
    fun t13_runtimeUamHintIsDiagnosticOnlyForEnhancedV3() = runBlocking {
        val now = 11_800_000_000L
        val glucose = listOf(6.4, 6.41, 6.42, 6.42, 6.41, 6.40, 6.39, 6.38).series(now)
        val withHint = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val withoutHint = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        withHint.setUamRuntimeHint(
            ingestionTs = glucose.last().ts - 25 * 60_000L,
            carbsGrams = 30.0,
            confidence = 0.72,
            source = "uam_inference"
        )

        val forecastsWithHint = withHint.predict(glucose, emptyList())
        val diagnostics = withHint.lastDiagnosticsForTest()!!
        val forecastsWithoutHint = withoutHint.predict(glucose, emptyList())

        assertThat(diagnostics.usingVirtualMeal).isFalse()
        assertThat(diagnostics.legacyVirtualMealUsed).isFalse()
        assertThat(diagnostics.runtimeHintCarbs).isWithin(0.01).of(30.0)
        assertThat(diagnostics.runtimeHintConfidence).isWithin(0.001).of(0.72)
        assertThat(diagnostics.runtimeHintSource).isEqualTo("uam_inference")
        assertThat(forecastsWithHint).isEqualTo(forecastsWithoutHint)
    }

    @Test
    fun enhancedV3UsesOneResolvedMealPressureSeries() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val start = 12_000_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val therapy = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "30")
            )
        )

        val forecasts = engine.predict(glucose, therapy)
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(forecasts).isNotEmpty()
        assertThat(diagnostics.unifiedUamState).isNotEmpty()
        assertThat(diagnostics.unifiedUamSource).isEqualTo("unified_uam")
        assertThat(diagnostics.resolvedMealPressureStep).hasSize(13)
        assertThat(diagnostics.legacyVirtualMealUsed).isFalse()
        assertThat(diagnostics.doubleCountPrevented).isTrue()
    }

    @Test
    fun announcedCarbsAndUnifiedUamAreBoundedInsteadOfAddedAsFullContours() = runBlocking {
        val start = 12_500_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val announcedCarbs = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "30")
            )
        )
        val withAnnounced = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val withoutAnnounced = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        val forecastWith = withAnnounced.predict(glucose, announcedCarbs).associateBy { it.horizonMinutes }
        val diagnosticsWith = checkNotNull(withAnnounced.lastDiagnosticsForTest())
        val forecastWithout = withoutAnnounced.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val diagnosticsWithout = checkNotNull(withoutAnnounced.lastDiagnosticsForTest())

        assertThat(diagnosticsWith.unifiedUamState).isEqualTo(diagnosticsWithout.unifiedUamState)
        assertThat(diagnosticsWith.announcedMealPressureWeight).isGreaterThan(0.0)
        assertThat(diagnosticsWith.uamMealPressureWeight).isGreaterThan(0.0)
        diagnosticsWith.resolvedMealPressureStep.indices.forEach { index ->
            val greaterCandidate = maxOf(
                diagnosticsWith.announcedCarbStep[index],
                diagnosticsWith.rawUnifiedUamStep[index]
            )
            assertThat(diagnosticsWith.resolvedMealPressureStep[index]).isAtMost(greaterCandidate + 1e-9)
        }
        val fullAnnounced60 = diagnosticsWith.announcedCarbStep.take(13).sum()
        assertThat(forecastWith.getValue(60).valueMmol)
            .isLessThan(forecastWithout.getValue(60).valueMmol + fullAnnounced60)
    }

    @Test
    fun disabledUnifiedUamKeepsAnnouncedCarbsAndInsulinWithoutUamContribution() = runBlocking {
        val now = 13_000_000_000L
        val glucose = listOf(7.0, 7.08, 7.18, 7.30, 7.45, 7.62, 7.80, 8.0).series(now)
        val therapy = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "35")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.5")
            )
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val noTherapyEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        val forecasts = engine.predict(glucose, therapy)
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())
        val noTherapyForecasts = noTherapyEngine.predict(glucose, emptyList())

        assertThat(diagnostics.unifiedUamState).isEqualTo("DISABLED")
        assertThat(diagnostics.unifiedUamConfidence).isEqualTo(0.0)
        assertThat(diagnostics.uamStep.all { it == 0.0 }).isTrue()
        assertThat(diagnostics.rawUnifiedUamStep.all { it == 0.0 }).isTrue()
        assertThat(diagnostics.uamMealPressureWeight).isEqualTo(0.0)
        assertThat(diagnostics.resolvedMealPressureStep).isEqualTo(diagnostics.announcedCarbStep)
        assertThat(diagnostics.resolvedTherapyStep).isEqualTo(diagnostics.therapyStep)
        assertThat(diagnostics.insulinStep.any { it < 0.0 }).isTrue()
        assertThat(diagnostics.announcedCarbStep.any { it > 0.0 }).isTrue()
        diagnostics.glucosePath.indices.drop(1).forEach { index ->
            val expected = diagnostics.trendStep[index] + diagnostics.therapyStep[index]
            val actual = diagnostics.glucosePath[index] - diagnostics.glucosePath[index - 1]
            assertThat(actual).isWithin(1e-9).of(expected)
        }
        assertThat(forecasts).isNotEqualTo(noTherapyForecasts)
    }

    @Test
    fun resolvedMealPressureGetterDefensivelyCopiesItsSteps() {
        val resolved = MealPressureResolver.resolve(
            MealPressureInput(
                announcedCarbSteps = doubleArrayOf(0.0, 0.3),
                uamSteps = doubleArrayOf(0.0, 0.4),
                residualCobNowGrams = 10.0,
                announcedCarbCoverage = 1.0,
                uamConfidence = 0.8
            )
        )

        val firstRead = resolved.steps
        firstRead[1] = 99.0

        assertThat(resolved.steps[1]).isLessThan(1.0)
    }

    @Test
    fun bolusAtNowDoesNotChangePreviousIntervalUnifiedUam() = runBlocking {
        val start = 13_500_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val bolusAtNow = TherapyEvent(
            ts = glucose.last().ts,
            type = "correction_bolus",
            payload = mapOf("units" to "4.0")
        )
        val noBolus = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val withBolus = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        noBolus.predict(glucose, emptyList())
        withBolus.predict(glucose, listOf(bolusAtNow))
        val noBolusDiagnostics = checkNotNull(noBolus.lastDiagnosticsForTest())
        val withBolusDiagnostics = checkNotNull(withBolus.lastDiagnosticsForTest())

        assertThat(withBolusDiagnostics.unifiedUamState).isEqualTo(noBolusDiagnostics.unifiedUamState)
        assertThat(withBolusDiagnostics.uci0).isWithin(1e-12).of(noBolusDiagnostics.uci0)
        assertThat(withBolusDiagnostics.unifiedUamConfidence)
            .isWithin(1e-12)
            .of(noBolusDiagnostics.unifiedUamConfidence)
        assertThat(withBolusDiagnostics.knownInputInsulinStep1).isEqualTo(0.0)
        assertThat(withBolusDiagnostics.insulinStep.drop(1).any { it < 0.0 }).isTrue()
    }

    @Test
    fun historicalKnownInputNeverBackfillsUamFromCurrentConfidence() = runBlocking {
        val start = 14_000_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val withUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val withoutUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        withUam.predict(glucose, emptyList())
        withoutUam.predict(glucose, emptyList())
        val withDiagnostics = checkNotNull(withUam.lastDiagnosticsForTest())
        val withoutDiagnostics = checkNotNull(withoutUam.lastDiagnosticsForTest())

        assertThat(withDiagnostics.unifiedUamState).isEqualTo("ACTIVE")
        assertThat(withDiagnostics.unifiedUamConfidence).isGreaterThan(0.0)
        assertThat(withDiagnostics.knownInputUamStep1).isEqualTo(0.0)
        assertThat(withDiagnostics.knownInputTherapyStep1)
            .isWithin(1e-12)
            .of(withoutDiagnostics.knownInputTherapyStep1)
    }

    @Test
    fun opposingInsulinAndCarbsUseOneCombinedClampForHistoricalAndFutureTherapy() = runBlocking {
        val now = 14_500_000_000L
        val glucose = listOf(9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 30 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "60")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 30 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "20.0")
            )
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        engine.predict(glucose, events)
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        var cumulativeRaw = 0.0
        var previousClamped = 0.0
        var sawOpposingStep = false
        var sawSaturation = false
        diagnostics.therapyStep.indices.drop(1).forEach { index ->
            sawOpposingStep = sawOpposingStep ||
                (diagnostics.insulinStep[index] < 0.0 && diagnostics.announcedCarbStep[index] > 0.0)
            cumulativeRaw += diagnostics.insulinStep[index] + diagnostics.announcedCarbStep[index]
            sawSaturation = sawSaturation || abs(cumulativeRaw) > 6.0
            val clamped = cumulativeRaw.coerceIn(-6.0, 6.0)
            assertThat(diagnostics.therapyStep[index]).isWithin(1e-9).of(clamped - previousClamped)
            previousClamped = clamped
        }
        val expectedHistorical = (
            diagnostics.knownInputInsulinStep1 + diagnostics.knownInputAnnouncedCarbStep1
            ).coerceIn(-6.0, 6.0)
        assertThat(diagnostics.knownInputTherapyStep1).isWithin(1e-9).of(expectedHistorical)
        assertThat(sawOpposingStep).isTrue()
        assertThat(sawSaturation).isTrue()
    }

    @Test
    fun unknownRuntimeQualityKeepsUnifiedUamForecastOnlyAndProvisional() = runBlocking {
        val start = 15_000_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        engine.predict(glucose, emptyList())
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(diagnostics.rawUnifiedUamStep.any { it > 0.0 }).isTrue()
        assertThat(diagnostics.resolvedMealPressureStep.any { it > 0.0 }).isTrue()
        assertThat(diagnostics.unifiedUamSensorTrust).isEqualTo(0.0)
        assertThat(diagnostics.unifiedUamTherapyCoverage).isEqualTo(0.0)
        assertThat(diagnostics.announcedCarbCoverage).isEqualTo(0.0)
        assertThat(diagnostics.unifiedUamQualityProvisional).isTrue()
        assertThat(diagnostics.unifiedUamActiveForControl).isFalse()
        assertThat(diagnostics.unifiedUamLowerBoundCarbsGrams).isNull()
    }

    @Test
    fun validatedRuntimeQualityEnablesControlAndExposesTheSameEstimatorSnapshot() = runBlocking {
        val start = 15_250_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        engine.setUamRuntimeQualityContext(
            sensorTrust = 0.92,
            therapyCoverage = 0.88,
            announcedCarbCoverage = 1.0,
            sensorBlocked = false
        )

        engine.predict(glucose, emptyList())
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(diagnostics.unifiedUamState).isEqualTo("ACTIVE")
        assertThat(diagnostics.unifiedUamActiveForForecast).isTrue()
        assertThat(diagnostics.unifiedUamActiveForControl).isTrue()
        assertThat(diagnostics.unifiedUamImpactMmol5).isGreaterThan(0.0)
        assertThat(diagnostics.unifiedUamSignedResidualMmol5).isGreaterThan(0.0)
        assertThat(diagnostics.unifiedUamShortAverageDeltaMmol5).isGreaterThan(0.0)
        assertThat(diagnostics.unifiedUamEquivalentCarbsGrams).isNotNull()
        assertThat(diagnostics.unifiedUamLowerBoundCarbsGrams).isNotNull()
        assertThat(diagnostics.unifiedUamOnsetTs).isNotNull()
        assertThat(diagnostics.unifiedUamFirstDetectionTs).isNotNull()
        assertThat(diagnostics.unifiedUamActiveSinceTs).isNotNull()
        assertThat(diagnostics.unifiedUamSupportStableBuckets).isAtLeast(2)
        assertThat(diagnostics.unifiedUamLowerBoundStableBuckets).isAtLeast(2)
        assertThat(diagnostics.unifiedUamSensorTrust).isWithin(1e-12).of(0.92)
        assertThat(diagnostics.unifiedUamTherapyCoverage).isWithin(1e-12).of(0.88)
        assertThat(diagnostics.unifiedUamReasons).contains("support_confirmed")
        assertThat(diagnostics.unifiedUamAlgorithmVersion).isNotEmpty()
        assertThat(diagnostics.unifiedUamQualityProvisional).isFalse()
    }

    @Test
    fun invalidRuntimeQualityContextFailsClosedInsteadOfReusingPriorQuality() = runBlocking {
        val start = 15_300_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        engine.setUamRuntimeQualityContext(0.95, 0.95, 1.0, sensorBlocked = false)
        engine.setUamRuntimeQualityContext(Double.NaN, 2.0, -1.0, sensorBlocked = false)

        engine.predict(glucose, emptyList())
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(diagnostics.unifiedUamSensorTrust).isEqualTo(0.0)
        assertThat(diagnostics.unifiedUamTherapyCoverage).isEqualTo(0.0)
        assertThat(diagnostics.announcedCarbCoverage).isEqualTo(0.0)
        assertThat(diagnostics.unifiedUamActiveForControl).isFalse()
        assertThat(diagnostics.unifiedUamLowerBoundCarbsGrams).isNull()
    }

    @Test
    fun diagnosticsSeparateLegacyResolvedAndRawUnifiedContoursAtEveryIndex() = runBlocking {
        val start = 15_500_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).series(start)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "30")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            )
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        engine.predict(glucose, events)
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(diagnostics.resolvedMealPressureSource).isNotEmpty()
        assertThat(diagnostics.glucosePath).hasSize(13)
        assertThat(diagnostics.therapyStep).hasSize(13)
        assertThat(diagnostics.resolvedTherapyStep).hasSize(13)
        assertThat(diagnostics.resolvedMealPressureStep).hasSize(13)
        assertThat(diagnostics.uamStep).hasSize(13)
        assertThat(diagnostics.resolvedUamAttributionStep).hasSize(13)
        assertThat(diagnostics.rawUnifiedUamStep).hasSize(13)
        listOf(
            diagnostics.therapyStep,
            diagnostics.resolvedTherapyStep,
            diagnostics.resolvedMealPressureStep,
            diagnostics.uamStep,
            diagnostics.resolvedUamAttributionStep,
            diagnostics.rawUnifiedUamStep
        ).forEach { steps -> assertThat(steps[0]).isEqualTo(0.0) }

        var overlapCount = 0
        diagnostics.glucosePath.indices.forEach { index ->
            val expectedAttribution = diagnostics.resolvedTherapyStep[index] - diagnostics.therapyStep[index]
            assertThat(diagnostics.uamStep[index]).isWithin(1e-9).of(expectedAttribution)
            assertThat(diagnostics.resolvedUamAttributionStep[index]).isWithin(1e-9).of(expectedAttribution)
            assertThat(diagnostics.therapyStep[index] + diagnostics.uamStep[index])
                .isWithin(1e-9)
                .of(diagnostics.resolvedTherapyStep[index])

            if (index > 0) {
                val expectedPathDelta = diagnostics.trendStep[index] + diagnostics.resolvedTherapyStep[index]
                val actualPathDelta = diagnostics.glucosePath[index] - diagnostics.glucosePath[index - 1]
                assertThat(actualPathDelta).isWithin(1e-9).of(expectedPathDelta)
            }
            if (diagnostics.announcedCarbStep[index] > 0.0 && diagnostics.rawUnifiedUamStep[index] > 0.0) {
                overlapCount += 1
                assertThat(diagnostics.resolvedMealPressureStep[index]).isAtMost(
                    maxOf(diagnostics.announcedCarbStep[index], diagnostics.rawUnifiedUamStep[index]) + 1e-9
                )
                assertThat(diagnostics.resolvedMealPressureStep[index]).isLessThan(
                    diagnostics.announcedCarbStep[index] + diagnostics.rawUnifiedUamStep[index]
                )
            }
        }
        assertThat(overlapCount).isGreaterThan(0)
        assertThat(diagnostics.doubleCountPrevented).isTrue()
        assertThat(diagnostics.therapyStep.drop(1).take(12).sum() + diagnostics.uamStep.drop(1).take(12).sum())
            .isWithin(1e-9)
            .of(diagnostics.resolvedTherapyStep.drop(1).take(12).sum())
        assertThat(diagnostics.resolvedTherapyStep.last()).isEqualTo(diagnostics.resolvedTherapyStep[12])
    }

    @Test
    fun announcedCarbPressurePreventsExtraUamAfterTherapySaturatesAtPositiveClamp() = runBlocking {
        val start = 15_750_000_000L
        val glucose = listOf(6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0).series(start)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "40", "meal" to "first")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 34 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "40", "meal" to "second")
            )
        )
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        engine.predict(glucose, events)
        val diagnostics = checkNotNull(engine.lastDiagnosticsForTest())

        assertThat(diagnostics.unifiedUamState).isEqualTo("ACTIVE")
        assertThat(diagnostics.uamActive).isTrue()
        assertThat(diagnostics.therapyStep.drop(1).take(12).sum()).isWithin(1e-9).of(6.0)
        assertThat(diagnostics.resolvedTherapyStep.drop(1).take(12).sum()).isWithin(1e-9).of(6.0)
        assertThat(diagnostics.uamStep.any { it < -1e-9 }).isTrue()

        val saturatedIndices = diagnostics.therapyStep.indices.drop(1).filter { index ->
            diagnostics.announcedCarbStep[index] > 0.0 &&
                kotlin.math.abs(diagnostics.therapyStep.take(index + 1).sum() - 6.0) <= 1e-9 &&
                kotlin.math.abs(diagnostics.resolvedTherapyStep.take(index + 1).sum() - 6.0) <= 1e-9 &&
                kotlin.math.abs(diagnostics.therapyStep[index]) <= 1e-9 &&
                kotlin.math.abs(diagnostics.resolvedTherapyStep[index]) <= 1e-9
        }

        assertThat(saturatedIndices).isNotEmpty()
        saturatedIndices.forEach { index ->
            assertThat(diagnostics.uamStep[index]).isWithin(1e-9).of(0.0)
            assertThat(diagnostics.resolvedUamAttributionStep[index]).isWithin(1e-9).of(0.0)
            assertThat(diagnostics.therapyStep[index] + diagnostics.uamStep[index])
                .isWithin(1e-9)
                .of(diagnostics.resolvedTherapyStep[index])
        }
        assertThat(diagnostics.therapyStep.drop(1).take(12).sum() + diagnostics.uamStep.drop(1).take(12).sum())
            .isWithin(1e-9)
            .of(diagnostics.resolvedTherapyStep.drop(1).take(12).sum())
    }

    @Test
    fun invalidResolverSourcePreservesAnnouncedPressureAndDisablesUamWeight() {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()
        val snapshot = engine.resolveMealPressureForTest(
            MealPressureInput(
                announcedCarbSteps = doubleArrayOf(0.0, 0.30, 0.20),
                uamSteps = doubleArrayOf(0.0, Double.NaN, 0.40),
                residualCobNowGrams = 10.0,
                announcedCarbCoverage = 0.0,
                uamConfidence = 0.8
            )
        )

        assertThat(snapshot.source).isEqualTo("INVALID_INPUT")
        assertThat(snapshot.steps.asList()).containsExactly(0.0, 0.30, 0.20).inOrder()
        assertThat(snapshot.uamWeight).isEqualTo(0.0)
    }

    @Test
    fun unrelatedUamRemainsInResolvedPressureAfterAnnouncedCarbAttribution() {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).withTestUamSensitivity()

        val snapshot = engine.resolveMealPressureForTest(
            MealPressureInput(
                announcedCarbSteps = doubleArrayOf(0.0, 0.30, 0.0),
                uamSteps = doubleArrayOf(0.0, 0.0, 0.40),
                residualCobNowGrams = 10.0,
                announcedCarbCoverage = 0.5,
                uamConfidence = 0.8
            )
        )

        assertThat(snapshot.source).isEqualTo("INCOMPLETE_CARB_HISTORY")
        assertThat(snapshot.steps[0]).isEqualTo(0.0)
        assertThat(snapshot.steps[1]).isEqualTo(0.30)
        assertThat(snapshot.steps[2]).isWithin(1e-9).of(0.16)
        assertThat(snapshot.steps[2]).isGreaterThan(0.0)
    }

    private fun List<Double>.series(startTs: Long): List<GlucosePoint> {
        return mapIndexed { idx, value ->
            GlucosePoint(
                ts = startTs + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }
    }

    private fun mealContext(
        identity: String,
        revision: String,
        profile: MealAbsorptionProfile,
        durationMinutes: Int
    ): MealAbsorptionContext = MealAbsorptionContext(
        enabled = true,
        perMealOverrides = mapOf(
            identity to MealAbsorptionSelection(profile, durationMinutes, revision)
        )
    )

    private fun HybridPredictionEngine.withTestUamSensitivity() = apply {
        setUamSensitivityRuntimeContext(
            io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = SensitivityRuntimeConsumer.UAM
            )
        )
    }

    private fun canonicalCarbEvent(
        ts: Long,
        grams: Int,
        identity: Long,
        revision: String,
        extraJson: String = ""
    ): TherapyEvent = TherapyEventEntity(
        id = "canonical-carb-$identity-$revision",
        timestamp = ts,
        type = "carbs",
        payloadJson = "{\"grams\":$grams,\"aapsCarbId\":$identity,\"aapsRevisionId\":\"$revision\"$extraJson}"
    ).toDomain(Gson())

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTES = 5 * 60_000L
    }
}
