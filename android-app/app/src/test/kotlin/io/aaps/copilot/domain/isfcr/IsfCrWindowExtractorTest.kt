package io.aaps.copilot.domain.isfcr

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import org.junit.Assert.assertEquals
import org.junit.Test

class IsfCrWindowExtractorTest {

    @Test
    fun extract_isfSampleCanBeInferredFromImplicitIobCorrectionWithoutTherapyEvents() {
        val extractor = IsfCrWindowExtractor()
        val correctionTs = 1_700_050_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = emptyList(),
                telemetry = listOf(
                    TelemetrySignal(ts = correctionTs - 10L * 60L * 1_000L, key = "iob_units", valueDouble = 0.2),
                    TelemetrySignal(ts = correctionTs - 5L * 60L * 1_000L, key = "iob_units", valueDouble = 0.3),
                    TelemetrySignal(ts = correctionTs, key = "iob_units", valueDouble = 1.1),
                    TelemetrySignal(ts = correctionTs + 5L * 60L * 1_000L, key = "iob_units", valueDouble = 1.0)
                ),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val isfSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.ISF }
        assertThat(isfSample).isNotNull()
        assertThat(isfSample!!.value).isAtLeast(0.2)
    }

    @Test
    fun extract_appliesWearAgePenaltyToEvidenceWeight() {
        val extractor = IsfCrWindowExtractor()
        val correctionTs = 1_700_000_000_000L
        val glucose = buildCorrectionGlucose(correctionTs)

        val freshExtraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = glucose,
                therapy = listOf(
                    TherapyEvent(
                        ts = correctionTs,
                        type = "correction_bolus",
                        payload = mapOf("units" to "1.0")
                    ),
                    TherapyEvent(
                        ts = correctionTs - 12L * 60L * 60L * 1_000L,
                        type = "infusion_set_change",
                        payload = emptyMap()
                    ),
                    TherapyEvent(
                        ts = correctionTs - 24L * 60L * 60L * 1_000L,
                        type = "sensor_change",
                        payload = emptyMap()
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val agedExtraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = glucose,
                therapy = listOf(
                    TherapyEvent(
                        ts = correctionTs,
                        type = "correction_bolus",
                        payload = mapOf("units" to "1.0")
                    ),
                    TherapyEvent(
                        ts = correctionTs - 140L * 60L * 60L * 1_000L,
                        type = "infusion_set_change",
                        payload = emptyMap()
                    ),
                    TherapyEvent(
                        ts = correctionTs - 250L * 60L * 60L * 1_000L,
                        type = "sensor_change",
                        payload = emptyMap()
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val fresh = freshExtraction.evidence.first { it.sampleType == IsfCrSampleType.ISF }
        val aged = agedExtraction.evidence.first { it.sampleType == IsfCrSampleType.ISF }

        assertThat(aged.weight).isLessThan(fresh.weight)
        assertThat(aged.context["setAgeWeight"]?.toDouble() ?: 1.0)
            .isLessThan(fresh.context["setAgeWeight"]?.toDouble() ?: 1.0)
        assertThat(aged.context["sensorAgeWeight"]?.toDouble() ?: 1.0)
            .isLessThan(fresh.context["sensorAgeWeight"]?.toDouble() ?: 1.0)
    }

    @Test
    fun extract_withoutWearMarkersKeepsWeightEqualToQuality() {
        val extractor = IsfCrWindowExtractor()
        val correctionTs = 1_700_100_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = correctionTs,
                        type = "correction_bolus",
                        payload = mapOf("units" to "1.0")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val sample = extraction.evidence.first { it.sampleType == IsfCrSampleType.ISF }
        assertEquals(sample.qualityScore, sample.weight, 1e-6)
    }

    @Test
    fun extract_syntheticUamCarbsDoNotInvalidateIsfCorrectionWindow() {
        val extractor = IsfCrWindowExtractor()
        val correctionTs = 1_700_120_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = correctionTs,
                        type = "correction_bolus",
                        payload = mapOf("units" to "1.0")
                    ),
                    TherapyEvent(
                        ts = correctionTs + 10L * 60L * 1_000L,
                        type = "carbs",
                        payload = mapOf(
                            "grams" to "18",
                            "source" to "uam_engine",
                            "synthetic" to "true",
                            "note" to "UAM_ENGINE|id=test|seq=1|ver=1|mode=NORMAL|"
                        )
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.any { it.sampleType == IsfCrSampleType.ISF }).isTrue()
        assertThat(extraction.droppedReasonCounts["isf_carbs_around"] ?: 0).isEqualTo(0)
    }

    @Test
    fun extract_crSampleDroppedWhenSensorBlockedTelemetryHigh() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_200_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "42")
                    ),
                    TherapyEvent(
                        ts = mealTs - 10L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "4.2")
                    )
                ),
                telemetry = (0..20).map { idx ->
                    TelemetrySignal(
                        ts = mealTs + idx * 10L * 60L * 1_000L,
                        key = "sensor_quality_blocked",
                        valueDouble = 1.0
                    )
                },
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(extraction.droppedReasonCounts["cr_sensor_blocked"]).isEqualTo(1)
    }

    @Test
    fun extract_crSensorBlockedThresholdCanBeRelaxedFromSettings() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_250_000_000L
        val telemetry = (0 until 10).map { idx ->
            TelemetrySignal(
                ts = mealTs + idx * 20L * 60L * 1_000L,
                key = "sensor_quality_blocked",
                valueDouble = if (idx < 4) 1.0 else 0.0
            )
        }
        val history = IsfCrHistoryBundle(
            glucose = buildMealGlucose(mealTs),
            therapy = listOf(
                TherapyEvent(
                    ts = mealTs,
                    type = "carbs",
                    payload = mapOf("grams" to "38")
                ),
                TherapyEvent(
                    ts = mealTs - 10L * 60L * 1_000L,
                    type = "bolus",
                    payload = mapOf("units" to "3.8")
                )
            ),
            telemetry = telemetry,
            tags = emptyList()
        )

        val blockedByDefault = extractor.extract(
            history = history,
            settings = IsfCrSettings(),
            isfReference = 2.5
        )
        val relaxedGate = extractor.extract(
            history = history,
            settings = IsfCrSettings(crSensorBlockedRateThreshold = 0.50),
            isfReference = 2.5
        )

        assertThat(blockedByDefault.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(blockedByDefault.droppedReasonCounts["cr_sensor_blocked"]).isEqualTo(1)
        assertThat(relaxedGate.evidence.any { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    @Test
    fun extract_crSampleDroppedWhenUamAmbiguityTelemetryHigh() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_300_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "36")
                    ),
                    TherapyEvent(
                        ts = mealTs + 5L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "3.6")
                    )
                ),
                telemetry = (0..12).map { idx ->
                    TelemetrySignal(
                        ts = mealTs + idx * 15L * 60L * 1_000L,
                        key = if (idx % 2 == 0) "uam_value" else "uam_calculated_flag",
                        valueDouble = 1.0
                    )
                },
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(extraction.droppedReasonCounts["cr_uam_ambiguity"]).isEqualTo(1)
    }

    @Test
    fun extract_crUamAmbiguityThresholdCanBeRelaxedFromSettings() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_350_000_000L
        val telemetry = (0 until 20).map { idx ->
            TelemetrySignal(
                ts = mealTs + idx * 10L * 60L * 1_000L,
                key = "uam_value",
                valueDouble = if (idx < 13) 1.0 else 0.0
            )
        }
        val history = IsfCrHistoryBundle(
            glucose = buildMealGlucose(mealTs),
            therapy = listOf(
                TherapyEvent(
                    ts = mealTs,
                    type = "carbs",
                    payload = mapOf("grams" to "44")
                ),
                TherapyEvent(
                    ts = mealTs + 10L * 60L * 1_000L,
                    type = "bolus",
                    payload = mapOf("units" to "4.4")
                )
            ),
            telemetry = telemetry,
            tags = emptyList()
        )

        val blockedByDefault = extractor.extract(
            history = history,
            settings = IsfCrSettings(),
            isfReference = 2.5
        )
        val relaxedGate = extractor.extract(
            history = history,
            settings = IsfCrSettings(crUamAmbiguityRateThreshold = 0.70),
            isfReference = 2.5
        )

        assertThat(blockedByDefault.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(blockedByDefault.droppedReasonCounts["cr_uam_ambiguity"]).isEqualTo(1)
        assertThat(relaxedGate.evidence.any { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    @Test
    fun extract_crBolusWindowIsAsymmetric_negative20ToPositive30() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_400_000_000L
        val outsideBefore = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "40")
                    ),
                    TherapyEvent(
                        ts = mealTs - 25L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "4.0")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )
        assertThat(outsideBefore.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(outsideBefore.droppedReasonCounts["cr_no_bolus_nearby"]).isEqualTo(1)

        val onLowerBoundary = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "40")
                    ),
                    TherapyEvent(
                        ts = mealTs - 20L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "4.0")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )
        assertThat(onLowerBoundary.evidence.any { it.sampleType == IsfCrSampleType.CR }).isTrue()

        val onUpperBoundary = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "40")
                    ),
                    TherapyEvent(
                        ts = mealTs + 30L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "4.0")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )
        assertThat(onUpperBoundary.evidence.any { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    @Test
    fun extract_crSampleUsesImplicitIobBolusWhenTherapyBolusMissing() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_425_000_000L
        val telemetry = listOf(
            TelemetrySignal(ts = mealTs - 20L * 60L * 1_000L, key = "iob_units", valueDouble = 0.4),
            TelemetrySignal(ts = mealTs - 5L * 60L * 1_000L, key = "iob_units", valueDouble = 0.5),
            TelemetrySignal(ts = mealTs + 5L * 60L * 1_000L, key = "iob_units", valueDouble = 2.1),
            TelemetrySignal(ts = mealTs + 20L * 60L * 1_000L, key = "iob_units", valueDouble = 1.9)
        )
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "36")
                    )
                ),
                telemetry = telemetry,
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val crSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.CR }
        assertThat(crSample).isNotNull()
        assertThat(crSample?.context?.get("mealBolusSource")).isEqualTo("implicit_iob")
        assertThat(crSample?.context?.get("mealBolusUnits")?.toDoubleOrNull()).isAtLeast(0.15)
        assertThat(crSample!!.weight).isLessThan(crSample.qualityScore)
    }

    @Test
    fun extract_crSampleAllowsCarbsOnlyFitWithSufficientIobContext() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_430_000_000L
        val telemetry = (0..8).map { idx ->
            TelemetrySignal(
                ts = mealTs - 20L * 60L * 1_000L + idx * 8L * 60L * 1_000L,
                key = "iob_units",
                valueDouble = 0.35
            )
        }
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "32")
                    )
                ),
                telemetry = telemetry,
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val crSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.CR }
        assertThat(crSample).isNotNull()
        assertThat(crSample?.context?.get("mealBolusSource")).isEqualTo("carbs_only_iob_context")
        assertThat(crSample?.context?.get("iobContextPoints")?.toIntOrNull()).isAtLeast(6)
        assertThat(crSample!!.weight).isLessThan(crSample.qualityScore)
    }

    @Test
    fun extract_crSampleAllowsUamEngineMealWithoutBolusOrIobContext() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_433_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf(
                            "grams" to "16",
                            "reason" to "uam_engine",
                            "notes" to "UAM_ENGINE|id=test|seq=1|ver=1|mode=BOOST|"
                        )
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val crSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.CR }
        assertThat(crSample).isNotNull()
        assertThat(crSample?.context?.get("mealBolusSource")).isEqualTo("carbs_only_uam_tag")
        assertThat(crSample?.context?.get("mealFromUamEngine")).isEqualTo("1")
        assertThat(extraction.droppedReasonCounts["cr_no_bolus_nearby"]).isNull()
    }

    @Test
    fun extract_deduplicatesDuplicateUamMealsInSameFiveMinuteBucket() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_434_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "60", "reason" to "uam_engine")
                    ),
                    TherapyEvent(
                        ts = mealTs + 60_000L,
                        type = "carbs",
                        payload = mapOf("grams" to "60", "reason" to "uam_engine")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val crEvidence = extraction.evidence.filter { it.sampleType == IsfCrSampleType.CR }
        assertThat(crEvidence).hasSize(1)
    }

    @Test
    fun extract_crSampleForUamTagCanPassHighUamAmbiguityWithPenalty() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_436_000_000L
        val telemetry = (0..8).map { idx ->
            TelemetrySignal(
                ts = mealTs + idx * 5L * 60L * 1_000L,
                key = "uam_value",
                valueDouble = 1.0
            )
        }

        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf(
                            "grams" to "20",
                            "reason" to "uam_engine",
                            "notes" to "UAM_ENGINE|id=test|seq=1|ver=1|mode=BOOST|"
                        )
                    )
                ),
                telemetry = telemetry,
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val crSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.CR }
        assertThat(crSample).isNotNull()
        assertThat(extraction.droppedReasonCounts["cr_uam_ambiguity"]).isNull()
        assertThat(crSample?.context?.get("mealBolusSource")).isEqualTo("carbs_only_uam_tag")
        assertThat(crSample!!.weight).isLessThan(0.3)
    }

    @Test
    fun extract_crSampleSupportsOneMinuteCgmIntervals() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_438_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucoseEveryMinute(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf("grams" to "30")
                    ),
                    TherapyEvent(
                        ts = mealTs - 10L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "3.0")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.droppedReasonCounts["cr_sparse_intervals"]).isNull()
        assertThat(extraction.evidence.any { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    @Test
    fun extract_crSampleAlignsMealTimestampWhenRiseStartsEarlier() {
        val extractor = IsfCrWindowExtractor()
        val mealTsLogged = 1_700_442_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucoseWithEarlyRise(mealTsLogged),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTsLogged,
                        type = "carbs",
                        payload = mapOf("grams" to "34")
                    ),
                    TherapyEvent(
                        ts = mealTsLogged - 5L * 60L * 1_000L,
                        type = "bolus",
                        payload = mapOf("units" to "3.4")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val crSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.CR }
        assertThat(crSample).isNotNull()
        val shift = crSample!!.context["mealAlignmentShiftMin"]?.toDoubleOrNull() ?: 0.0
        assertThat(crSample.context["mealAligned"]).isEqualTo("1")
        assertThat(shift).isLessThan(-5.0)
        assertThat(crSample.ts).isLessThan(mealTsLogged)
    }

    @Test
    fun extract_isfSampleOutlierIsAdjustedTowardReference() {
        val extractor = IsfCrWindowExtractor()
        val correctionTs = 1_700_445_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucoseOutlier(correctionTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = correctionTs,
                        type = "correction_bolus",
                        payload = mapOf("units" to "1.0")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val isfSample = extraction.evidence.firstOrNull { it.sampleType == IsfCrSampleType.ISF }
        assertThat(isfSample).isNotNull()
        assertThat(isfSample!!.context["isfOutlier"]).isEqualTo("1")
        val rawIsf = isfSample.context["rawIsf"]?.toDoubleOrNull() ?: 0.0
        assertThat(rawIsf).isGreaterThan(5.0)
        assertThat(isfSample.value).isLessThan(rawIsf)
        assertThat(isfSample.value).isAtMost(5.0)
    }

    @Test
    fun extract_crGrossGapThresholdCanBeRelaxedFromSettings() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_450_000_000L
        val history = IsfCrHistoryBundle(
            glucose = buildMealGlucoseWithGrossGap(mealTs),
            therapy = listOf(
                TherapyEvent(
                    ts = mealTs,
                    type = "carbs",
                    payload = mapOf("grams" to "40")
                ),
                TherapyEvent(
                    ts = mealTs - 10L * 60L * 1_000L,
                    type = "bolus",
                    payload = mapOf("units" to "4.0")
                )
            ),
            telemetry = emptyList(),
            tags = emptyList()
        )

        val blockedByDefault = extractor.extract(
            history = history,
            settings = IsfCrSettings(),
            isfReference = 2.5
        )
        val relaxedGate = extractor.extract(
            history = history,
            settings = IsfCrSettings(crGrossGapMinutes = 45.0),
            isfReference = 2.5
        )

        assertThat(blockedByDefault.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(blockedByDefault.droppedReasonCounts["cr_gross_gap"]).isEqualTo(1)
        assertThat(relaxedGate.evidence.any { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    private fun buildCorrectionGlucose(correctionTs: Long): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        var ts = correctionTs - 20L * 60L * 1_000L
        while (ts <= correctionTs + 240L * 60L * 1_000L) {
            val minute = ((ts - correctionTs) / 60_000.0)
            val value = when {
                minute <= 0.0 -> 9.8
                minute <= 120.0 -> 9.8 - (minute / 120.0) * 2.8
                else -> 7.0 + ((minute - 120.0) / 120.0) * 0.4
            }
            points += GlucosePoint(
                ts = ts,
                valueMmol = value,
                source = "cgm",
                quality = DataQuality.OK
            )
            ts += 5L * 60L * 1_000L
        }
        return points
    }

    private fun buildMealGlucose(mealTs: Long): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        var ts = mealTs
        while (ts <= mealTs + 240L * 60L * 1_000L) {
            val minute = ((ts - mealTs) / 60_000.0)
            val value = when {
                minute <= 45.0 -> 6.0 + (minute / 45.0) * 2.4
                minute <= 120.0 -> 8.4 - ((minute - 45.0) / 75.0) * 1.5
                else -> 6.9 - ((minute - 120.0) / 120.0) * 0.4
            }
            points += GlucosePoint(
                ts = ts,
                valueMmol = value,
                source = "cgm",
                quality = DataQuality.OK
            )
            ts += 5L * 60L * 1_000L
        }
        return points
    }

    private fun buildMealGlucoseWithGrossGap(mealTs: Long): List<GlucosePoint> {
        return buildMealGlucose(mealTs).filterNot { point ->
            point.ts in (mealTs + 60L * 60L * 1_000L)..(mealTs + 95L * 60L * 1_000L)
        }
    }

    private fun buildMealGlucoseWithEarlyRise(mealTs: Long): List<GlucosePoint> {
        val actualMealTs = mealTs - 20L * 60L * 1_000L
        val points = mutableListOf<GlucosePoint>()
        var ts = actualMealTs - 30L * 60L * 1_000L
        while (ts <= mealTs + 240L * 60L * 1_000L) {
            val minuteFromActual = ((ts - actualMealTs) / 60_000.0)
            val value = when {
                minuteFromActual <= 0.0 -> 5.9
                minuteFromActual <= 55.0 -> 5.9 + (minuteFromActual / 55.0) * 2.3
                minuteFromActual <= 120.0 -> 8.2 - ((minuteFromActual - 55.0) / 65.0) * 1.2
                else -> 7.0 - ((minuteFromActual - 120.0) / 120.0) * 0.4
            }
            points += GlucosePoint(
                ts = ts,
                valueMmol = value,
                source = "cgm",
                quality = DataQuality.OK
            )
            ts += 5L * 60L * 1_000L
        }
        return points
    }

    private fun buildMealGlucoseEveryMinute(mealTs: Long): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        var ts = mealTs
        while (ts <= mealTs + 120L * 60L * 1_000L) {
            val minute = ((ts - mealTs) / 60_000.0)
            val value = when {
                minute <= 35.0 -> 6.1 + (minute / 35.0) * 2.2
                minute <= 90.0 -> 8.3 - ((minute - 35.0) / 55.0) * 1.3
                else -> 7.0 - ((minute - 90.0) / 30.0) * 0.4
            }
            points += GlucosePoint(
                ts = ts,
                valueMmol = value,
                source = "cgm",
                quality = DataQuality.OK
            )
            ts += 60_000L
        }
        return points
    }

    private fun buildCorrectionGlucoseOutlier(correctionTs: Long): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        var ts = correctionTs - 20L * 60L * 1_000L
        while (ts <= correctionTs + 240L * 60L * 1_000L) {
            val minute = ((ts - correctionTs) / 60_000.0)
            val value = when {
                minute <= 0.0 -> 10.6
                minute <= 120.0 -> 10.6 - (minute / 120.0) * 7.2
                else -> 3.4 + ((minute - 120.0) / 120.0) * 0.6
            }
            points += GlucosePoint(
                ts = ts,
                valueMmol = value,
                source = "cgm",
                quality = DataQuality.OK
            )
            ts += 5L * 60L * 1_000L
        }
        return points
    }
}
