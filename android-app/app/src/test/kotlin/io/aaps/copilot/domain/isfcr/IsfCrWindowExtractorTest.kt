package io.aaps.copilot.domain.isfcr

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.data.repository.DeliveryDiagnosticTimelineSample
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.predict.TelemetrySignal
import io.aaps.copilot.domain.predict.UamMode
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.target.DeliveryTrustState
import org.junit.Assert.assertEquals
import org.junit.Test

class IsfCrWindowExtractorTest {

    @Test
    fun compensationEventReachesEvidenceContextAndWeightWithoutChangingValue() {
        val correctionTs = 1_700_000_000_000L
        val therapy = listOf(TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0")))
        val base = IsfCrWindowExtractor().extract(
            IsfCrHistoryBundle(buildCorrectionGlucose(correctionTs), therapy, emptyList(), emptyList()), IsfCrSettings(), 2.5
        ).evidence.first { it.sampleType == IsfCrSampleType.ISF }
        val contextual = IsfCrWindowExtractor().extract(
            IsfCrHistoryBundle(
                buildCorrectionGlucose(correctionTs), therapy, emptyList(), emptyList(),
                events = listOf(CompensationEvent("stress", correctionTs, correctionTs + 4 * 60 * 60_000L, CompensationEventType.STRESS))
            ), IsfCrSettings(), 2.5
        ).evidence.first { it.sampleType == IsfCrSampleType.ISF }
        assertThat(contextual.context["eventContext"]).contains("STRESS")
        assertThat(contextual.weight).isLessThan(base.weight)
        assertThat(contextual.value).isEqualTo(base.value)
    }

    @Test
    fun threeDayDeliveryIntervalStillBlocksIsfEvidenceOnDayTwo() {
        val day = 24L * 60L * 60L * 1_000L
        val eventStart = 40L * day
        val correctionTs = eventStart + 2L * day
        val events = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEvent(
                        eventStart,
                        "infusion_problem",
                        mapOf(
                            "eventId" to "three-day-delivery-gate",
                            "endTs" to (eventStart + 3L * day).toString()
                        )
                    )
                )
            ),
            nowTs = correctionTs
        )

        val extraction = IsfCrWindowExtractor().extract(
            IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = listOf(
                    TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0"))
                ),
                telemetry = emptyList(),
                tags = emptyList(),
                events = events
            ),
            IsfCrSettings(),
            2.5
        )

        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.ISF }).isTrue()
        assertThat(extraction.droppedReasonCounts["isf_low_quality"]).isEqualTo(1)
    }

    @Test
    fun recoveredDeliveryEpisodeStopsBlockingLaterIsfEvidence() {
        val correctionTs = 1_700_000_000_000L
        val events = EventTimelineRepository().deliveryDiagnosticEvents(
            listOf(
                DeliveryDiagnosticTimelineSample(
                    correctionTs - 100L * 60_000L,
                    "runtime",
                    DeliveryTrustState.SUSPECTED_NONRESPONSE
                ),
                DeliveryDiagnosticTimelineSample(
                    correctionTs - 60L * 60_000L,
                    "runtime",
                    DeliveryTrustState.SUSPECTED_NONRESPONSE
                ),
                DeliveryDiagnosticTimelineSample(
                    correctionTs - 30L * 60_000L,
                    "runtime",
                    DeliveryTrustState.NORMAL
                )
            )
        )

        val extraction = IsfCrWindowExtractor().extract(
            IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = listOf(TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0"))),
                telemetry = emptyList(),
                tags = emptyList(),
                events = events
            ),
            IsfCrSettings(),
            2.5
        )

        assertThat(extraction.evidence.any { it.sampleType == IsfCrSampleType.ISF }).isTrue()
        assertThat(extraction.droppedReasonCounts["isf_low_quality"]).isNull()
    }

    @Test
    fun overlappingHormonalEventsUseMaximumPenaltyInsteadOfProduct() {
        val correctionTs = 1_700_000_500_000L
        val therapy = listOf(TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0")))
        fun extract(events: List<CompensationEvent>) = IsfCrWindowExtractor().extract(
            IsfCrHistoryBundle(
                buildCorrectionGlucose(correctionTs),
                therapy,
                emptyList(),
                emptyList(),
                events = events
            ),
            IsfCrSettings(),
            2.5
        ).evidence.first { it.sampleType == IsfCrSampleType.ISF }
        val strongest = CompensationEvent(
            "strongest",
            correctionTs,
            correctionTs + 24L * 60L * 60_000L,
            CompensationEventType.HORMONAL,
            attributes = mapOf("factor" to "0.7")
        )
        val weaker = strongest.copy(
            localId = "weaker",
            type = CompensationEventType.MENSTRUAL_CYCLE,
            attributes = mapOf("factor" to "0.3")
        )

        assertThat(extract(listOf(strongest, weaker)).weight)
            .isWithin(0.000_001)
            .of(extract(listOf(strongest)).weight)
    }

    @Test
    fun extract_resolvesEachInputTherapyEventOnceAcrossLearningScans() {
        var resolverCalls = 0
        val correctionTs = 1_700_050_000_000L
        val therapy = listOf(
            TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(
                correctionTs + 6L * 60L * 60L * 1_000L,
                "meal_bolus",
                mapOf("grams" to "24", "bolusUnits" to "2.0")
            )
        )
        val extractor = IsfCrWindowExtractor(
            componentResolver = { type, payload ->
                resolverCalls += 1
                resolveTherapyComponents(type, payload)
            }
        )

        extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = therapy,
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(resolverCalls).isEqualTo(therapy.size)
    }

    @Test
    fun extractionKeyCanonicalizationPreservesLegacyAliasesWithoutRegex() {
        assertThat(canonicalizeIsfCrExtractionKey("enteredCarbs")).isEqualTo("entered_carbs")
        assertThat(canonicalizeIsfCrExtractionKey("Entered carbs (g)")).isEqualTo("entered_carbs_g")
        assertThat(canonicalizeIsfCrExtractionKey("__IOB--Units__")).isEqualTo("iob_units")
        assertThat(canonicalizeIsfCrExtractionKey("openAPS_IOB")).isEqualTo("open_aps_iob")
    }

    @Test
    fun conflictingPayloadAliasesKeepOriginalEntryOrder() {
        val payload = linkedMapOf(
            "enteredCarbs" to "20",
            "grams" to "10"
        )

        val selected = firstCanonicalIsfCrPayloadDouble(
            payload = payload,
            acceptedKeys = setOf("grams", "carbs", "entered_carbs", "meal_carbs")
        )

        assertThat(selected).isEqualTo(20.0)
    }

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
    fun extract_rejectsIsfSamplesWhenAnotherBolusOverlapsTheResponseWindow() {
        val correctionTs = 1_700_050_000_000L
        val extraction = IsfCrWindowExtractor().extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = listOf(
                    TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "0.5")),
                    TherapyEvent(
                        correctionTs + 30L * 60_000L,
                        "correction_bolus",
                        mapOf("units" to "0.5")
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.ISF }).isTrue()
        assertThat(extraction.droppedReasonCounts["isf_competing_insulin"]).isEqualTo(2)
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
    fun extract_canonicalUamAndInvalidCombinedCarbsAreExcludedButCombinedInsulinRemains() {
        val correctionTs = 1_700_125_000_000L
        val extraction = IsfCrWindowExtractor().extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(correctionTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = correctionTs,
                        type = "meal_bolus",
                        payload = mapOf(
                            "units" to "1.0",
                            "carbs" to "40",
                            "isValid" to "false",
                            "aapsCarbId" to "401",
                            "aapsCarbAmount" to "40",
                            "aapsCarbIsValid" to "false",
                            "aapsCarbClassification" to "AAPS_REAL",
                            "aapsCarbSynthetic" to "false",
                            "aapsCarbSuperseded" to "false"
                        )
                    ),
                    TherapyEvent(
                        ts = correctionTs + 10L * 60L * 1_000L,
                        type = "carbs",
                        payload = mapOf(
                            "carbs" to "18",
                            "synthetic" to "false",
                            "aapsCarbId" to "402",
                            "aapsCarbAmount" to "18",
                            "aapsCarbIsValid" to "true",
                            "aapsCarbClassification" to "UAM_SYNTHETIC",
                            "aapsCarbSynthetic" to "true",
                            "aapsCarbSuperseded" to "false"
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
        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
        assertThat(extraction.droppedReasonCounts["isf_carbs_around"] ?: 0).isEqualTo(0)
    }

    @Test
    fun extract_deduplicatedCanonicalCarbCannotBeRestoredWhileBothInsulinComponentsRemain() {
        val mealTs = 1_700_150_000_000L
        fun reconciledMeal(id: String, units: String) = TherapyEventEntity(
            id = id,
            timestamp = mealTs,
            type = "meal_bolus",
            payloadJson =
                """{"units":$units,"carbs":90,"aapsCarbId":451,"aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        ).toDomain(Gson())
        val extraction = IsfCrWindowExtractor().extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(reconciledMeal("survivor", "3.0"), reconciledMeal("duplicate", "1.0")),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.count { it.sampleType == IsfCrSampleType.CR }).isEqualTo(1)
        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.ISF }).isTrue()
        assertThat(extraction.droppedReasonCounts["isf_carbs_around"]).isNull()
    }

    @Test
    fun extract_deduplicatedRealMealDoesNotReclassifyItsInsulinAsCorrection() {
        val firstMealTs = 1_700_160_000_000L
        val duplicateMealTs = firstMealTs + 6L * 60L * 60L * 1_000L
        fun reconciledMeal(id: String, ts: Long, units: String, amount: String) = TherapyEventEntity(
            id = id,
            timestamp = ts,
            type = "meal_bolus",
            payloadJson =
                """{"units":$units,"aapsCarbId":452,"aapsCarbAmount":$amount,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        ).toDomain(Gson())
        val extraction = IsfCrWindowExtractor().extract(
            history = IsfCrHistoryBundle(
                glucose = (buildMealGlucose(firstMealTs) + buildCorrectionGlucose(duplicateMealTs))
                    .distinctBy(GlucosePoint::ts)
                    .sortedBy(GlucosePoint::ts),
                therapy = listOf(
                    reconciledMeal("survivor", firstMealTs, units = "3.0", amount = "30"),
                    reconciledMeal("duplicate", duplicateMealTs, units = "1.0", amount = "31")
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.count { it.sampleType == IsfCrSampleType.CR }).isEqualTo(1)
        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.ISF }).isTrue()
    }

    @Test
    fun extract_combinedCanonicalUamMealDoesNotBecomeCorrectionEvidence() {
        val mealTs = 1_700_190_000_000L
        val extraction = IsfCrWindowExtractor().extract(
            history = IsfCrHistoryBundle(
                glucose = buildCorrectionGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "meal_bolus",
                        payload = mapOf(
                            "units" to "1.0",
                            "aapsCarbAmount" to "18",
                            "aapsCarbIsValid" to "true",
                            "aapsCarbClassification" to "UAM_SYNTHETIC",
                            "aapsCarbSynthetic" to "true",
                            "aapsCarbSuperseded" to "false"
                        )
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence).isEmpty()
        assertThat(extraction.droppedCount).isEqualTo(0)
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
    fun extract_uamTaggedCarbsDoNotCreateCrEvidence() {
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
                            "notes" to UamTagCodec.buildTag(
                                eventId = "test",
                                seq = 1,
                                mode = UamMode.NORMAL,
                                version = 2
                            )
                        )
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    @Test
    fun extract_structuredSyntheticUamCarbsDoNotCreateCrEvidence() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_434_000_000L
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = listOf(
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf(
                            "grams" to "60",
                            "synthetic" to "true",
                            "syntheticType" to "uam",
                            "source" to "uam_engine"
                        )
                    )
                ),
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        assertThat(extraction.evidence.none { it.sampleType == IsfCrSampleType.CR }).isTrue()
    }

    @Test
    fun extract_adjacentSyntheticUamExportDoesNotChangeRealMealCrEvidence() {
        val extractor = IsfCrWindowExtractor()
        val mealTs = 1_700_436_000_000L
        val realMeal = TherapyEvent(
            ts = mealTs,
            type = "carbs",
            payload = mapOf("grams" to "40")
        )
        val mealBolus = TherapyEvent(
            ts = mealTs - 10L * 60L * 1_000L,
            type = "bolus",
            payload = mapOf("units" to "4.0")
        )
        val syntheticExport = TherapyEvent(
            ts = mealTs + 10L * 60L * 1_000L,
            type = "carbs",
            payload = mapOf(
                "grams" to "15",
                "source" to "uam_engine",
                "synthetic" to "true",
                "notes" to UamTagCodec.buildTag(
                    eventId = "adjacent",
                    seq = 1,
                    mode = UamMode.NORMAL,
                    version = 2
                )
            )
        )

        fun extractWith(therapy: List<TherapyEvent>) = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = buildMealGlucose(mealTs),
                therapy = therapy,
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.5
        )

        val baseline = extractWith(listOf(realMeal, mealBolus))
            .evidence
            .single { it.sampleType == IsfCrSampleType.CR }
        val withSynthetic = extractWith(listOf(realMeal, mealBolus, syntheticExport))
            .evidence
            .single { it.sampleType == IsfCrSampleType.CR }

        assertThat(withSynthetic).isEqualTo(baseline)
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
