package io.aaps.copilot.domain.alerts

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.target.DeliveryTrustState
import org.junit.Test

class AlertCauseAnalyzerTest {

    @Test
    fun missingEssentialEvidenceIsDataIncomplete() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                glucose = completeInput().glucose.copy(currentMmol = null),
                sensor = completeInput().sensor.copy(blocked = true)
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(analysis.factors).containsExactly(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(analysis.evidenceCodes).contains("CURRENT_EVIDENCE_MISSING")
    }

    @Test
    fun staleCycleIsDataIncomplete() {
        val input = completeInput().copy(cycleTimestamp = NOW - 16 * 60_000L)

        val analysis = AlertCauseAnalyzer.analyze(input)

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(analysis.evidenceCodes).contains("CURRENT_EVIDENCE_STALE")
    }

    @Test
    fun sensorQualityBeatsUamAndDelivery() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                sensor = completeInput().sensor.copy(score = 0.30, blocked = true),
                uam = qualifiedUam(),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
        assertThat(analysis.factors).containsAtLeast(
            AlertCauseCode.SENSOR_QUALITY,
            AlertCauseCode.MEAL_UAM,
            AlertCauseCode.DELIVERY_NONRESPONSE
        )
        assertThat(analysis.factors.first()).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
    }

    @Test
    fun qualifiedRisingUamBeatsDeliverySuspicion() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                uam = qualifiedUam(),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.MEAL_UAM)
        assertThat(analysis.factors).containsExactly(
            AlertCauseCode.MEAL_UAM,
            AlertCauseCode.DELIVERY_NONRESPONSE
        ).inOrder()
    }

    @Test
    fun mealUamRequiresExplicitActiveStateEvenWhenFlagsAndResidualAreQualified() {
        listOf(
            AlertUamState.INACTIVE,
            AlertUamState.SUSPECTED,
            AlertUamState.DECAYING,
            AlertUamState.BLOCKED
        ).forEach { state ->
            val analysis = AlertCauseAnalyzer.analyze(
                completeInput().copy(
                    uam = qualifiedUam().copy(state = state, active = true)
                )
            )

            assertThat(analysis.primary).isEqualTo(AlertCauseCode.UNKNOWN)
            assertThat(analysis.factors).doesNotContain(AlertCauseCode.MEAL_UAM)
        }
    }

    @Test
    fun deliveryCauseRequiresExactSuspectedNonresponseAndHighDirection() {
        val watch = AlertCauseAnalyzer.analyze(
            completeInput().copy(deliveryTrust = DeliveryTrustState.WATCH)
        )
        val unknown = AlertCauseAnalyzer.analyze(
            completeInput().copy(deliveryTrust = DeliveryTrustState.UNKNOWN)
        )
        val low = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )
        val suspected = AlertCauseAnalyzer.analyze(
            completeInput().copy(deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE)
        )

        assertThat(watch.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(unknown.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(low.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(suspected.primary).isEqualTo(AlertCauseCode.DELIVERY_NONRESPONSE)
        assertThat(suspected.shortAdvice.lowercase()).contains("possible")
        assertThat(suspected.shortAdvice.lowercase()).doesNotContain("failure")
    }

    @Test
    fun explicitLowInsulinActivityCanExplainHighOnly() {
        val lowActivity = completeInput().insulin!!.copy(
            effectivePositiveIobUnits = 0.10,
            insulinActivity = 0.005,
            confidence = 0.90
        )

        val high = AlertCauseAnalyzer.analyze(completeInput().copy(insulin = lowActivity))
        val low = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(insulin = lowActivity)
        )

        assertThat(high.primary).isEqualTo(AlertCauseCode.INSULIN_ACTIVITY_LOW)
        assertThat(low.primary).isEqualTo(AlertCauseCode.UNKNOWN)
    }

    @Test
    fun alignedActiveTargetDeltaProducesTargetResponse() {
        val high = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                target = completeInput().target!!.copy(autoDeltaMmol = 0.30, effectiveTargetMmol = 6.30)
            )
        )
        val low = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(
                target = completeInput().target!!.copy(autoDeltaMmol = -0.30, effectiveTargetMmol = 5.70)
            )
        )

        assertThat(high.primary).isEqualTo(AlertCauseCode.TARGET_RESPONSE)
        assertThat(low.primary).isEqualTo(AlertCauseCode.TARGET_RESPONSE)
    }

    @Test
    fun explicitAlignedCircadianDeltaProducesCircadianPattern() {
        val high = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                circadian = AlertCircadianEvidence(applied = true, delta30Mmol = 0.40, confidence = 0.80)
            )
        )
        val wrongDirection = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(
                circadian = AlertCircadianEvidence(applied = true, delta30Mmol = 0.40, confidence = 0.80)
            )
        )

        assertThat(high.primary).isEqualTo(AlertCauseCode.CIRCADIAN_PATTERN)
        assertThat(wrongDirection.primary).isEqualTo(AlertCauseCode.UNKNOWN)
    }

    @Test
    fun activeRelevantEventProducesEventContextUsingTypeOnly() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                activeEventTypes = setOf(AlertContextEventType.STRESS, AlertContextEventType.ILLNESS)
            )
        )
        val snapshot = AlertCauseSnapshotCodec.encode(completeInput().copy(
            activeEventTypes = setOf(AlertContextEventType.STRESS, AlertContextEventType.ILLNESS)
        ), analysis).canonicalJson

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.EVENT_CONTEXT)
        assertThat(analysis.evidenceCodes).containsExactly("EVENT_STRESS", "EVENT_ILLNESS").inOrder()
        assertThat(snapshot).contains("STRESS")
        assertThat(snapshot).doesNotContain("note")
        assertThat(snapshot).doesNotContain("title")
        assertThat(snapshot).doesNotContain("eventId")
    }

    @Test
    fun stressDoesNotExplainLowAndActivityDoesNotExplainHigh() {
        val stressLow = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(
                activeEventTypes = setOf(AlertContextEventType.STRESS)
            )
        )
        val activityHigh = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.HIGH).copy(
                activeEventTypes = setOf(AlertContextEventType.ACTIVITY)
            )
        )

        assertThat(stressLow.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(stressLow.evidenceCodes).doesNotContain("EVENT_STRESS")
        assertThat(activityHigh.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(activityHigh.evidenceCodes).doesNotContain("EVENT_ACTIVITY")
    }

    @Test
    fun directionCompatibleEventsRequireAndAcceptNumericCorroboration() {
        val activityLow = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(
                activeEventTypes = setOf(AlertContextEventType.ACTIVITY)
            )
        )
        val stressHigh = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.HIGH).copy(
                activeEventTypes = setOf(AlertContextEventType.STRESS)
            )
        )
        val uncorroboratedGlucose = completeInput().glucose.copy(
            forecast5Mmol = 11.0,
            forecast30Mmol = 11.0,
            forecast60Mmol = 11.0,
            lowerCi5Mmol = 10.8,
            lowerCi30Mmol = 10.8,
            lowerCi60Mmol = 10.8,
            trendDelta5Mmol = 0.0
        )
        val stressWithoutMovement = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                glucose = uncorroboratedGlucose,
                activeEventTypes = setOf(AlertContextEventType.STRESS)
            )
        )

        assertThat(activityLow.primary).isEqualTo(AlertCauseCode.EVENT_CONTEXT)
        assertThat(activityLow.evidenceCodes).contains("EVENT_ACTIVITY")
        assertThat(stressHigh.primary).isEqualTo(AlertCauseCode.EVENT_CONTEXT)
        assertThat(stressHigh.evidenceCodes).contains("EVENT_STRESS")
        assertThat(stressWithoutMovement.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(stressWithoutMovement.evidenceCodes).doesNotContain("EVENT_STRESS")
    }

    @Test
    fun sensorContextAloneNeverBecomesEventCause() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(activeEventTypes = setOf(AlertContextEventType.SENSOR))
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(analysis.evidenceCodes).doesNotContain("EVENT_SENSOR")
    }

    @Test
    fun explicitTargetAndCircadianEvidencePrecedeCompatibleEventContext() {
        val targetFirst = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                target = completeInput().target!!.copy(
                    autoDeltaMmol = 0.30,
                    effectiveTargetMmol = 6.30
                ),
                activeEventTypes = setOf(AlertContextEventType.STRESS)
            )
        )
        val circadianFirst = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                target = null,
                circadian = AlertCircadianEvidence(applied = true, delta30Mmol = 0.40, confidence = 0.90),
                activeEventTypes = setOf(AlertContextEventType.STRESS)
            )
        )

        assertThat(targetFirst.primary).isEqualTo(AlertCauseCode.TARGET_RESPONSE)
        assertThat(targetFirst.factors).containsExactly(
            AlertCauseCode.TARGET_RESPONSE,
            AlertCauseCode.EVENT_CONTEXT
        ).inOrder()
        assertThat(circadianFirst.primary).isEqualTo(AlertCauseCode.CIRCADIAN_PATTERN)
        assertThat(circadianFirst.factors).containsExactly(
            AlertCauseCode.CIRCADIAN_PATTERN,
            AlertCauseCode.EVENT_CONTEXT
        ).inOrder()
    }

    @Test
    fun failedOptionalEventContextFailsClosedWithoutInventingCause() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(eventContextAvailable = false)
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(analysis.evidenceCodes).contains("EVENT_CONTEXT_UNAVAILABLE")
    }

    @Test
    fun weakCompleteEvidenceIsUnknown() {
        val analysis = AlertCauseAnalyzer.analyze(completeInput())

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(analysis.factors).containsExactly(AlertCauseCode.UNKNOWN)
        assertThat(analysis.confidence).isEqualTo(CauseConfidence.LOW)
    }

    @Test
    fun lowDirectionRejectsMealAndPositiveCircadianExplanations() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput(direction = AlertCauseDirection.LOW).copy(
                uam = qualifiedUam(),
                circadian = AlertCircadianEvidence(applied = true, delta30Mmol = 0.50, confidence = 0.90)
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(analysis.factors).doesNotContain(AlertCauseCode.MEAL_UAM)
        assertThat(analysis.factors).doesNotContain(AlertCauseCode.CIRCADIAN_PATTERN)
    }

    @Test
    fun adviceComesFromFixedNonActionableConstants() {
        val analyses = listOf(
            AlertCauseAnalyzer.analyze(completeInput()),
            AlertCauseAnalyzer.analyze(completeInput().copy(uam = qualifiedUam())),
            AlertCauseAnalyzer.analyze(
                completeInput().copy(deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE)
            )
        )

        analyses.forEach { analysis ->
            assertThat(analysis.shortAdvice.length).isAtMost(160)
            val lower = analysis.shortAdvice.lowercase()
            assertThat(lower).doesNotContain("units")
            assertThat(lower).doesNotContain("grams")
            assertThat(lower).doesNotContain("take carbs")
            assertThat(lower).doesNotContain("set target")
            assertThat(lower).doesNotContain("calibrate")
            assertThat(lower).doesNotContain("diagnosis")
        }
    }

    @Test
    fun factorsAndEvidenceAreUniqueOrderedAllowlistedAndBounded() {
        val input = completeInput().copy(
            sensor = completeInput().sensor.copy(score = 0.20, blocked = true, suspectFalseLow = true),
            uam = qualifiedUam(),
            deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE,
            activeEventTypes = AlertContextEventType.entries.reversed().toSet(),
            insulin = completeInput().insulin!!.copy(
                effectivePositiveIobUnits = 0.0,
                insulinActivity = 0.0
            ),
            target = completeInput().target!!.copy(autoDeltaMmol = 0.30, effectiveTargetMmol = 6.30),
            circadian = AlertCircadianEvidence(true, 0.40, 0.90)
        )

        val first = AlertCauseAnalyzer.analyze(input)
        val second = AlertCauseAnalyzer.analyze(input.copy(activeEventTypes = input.activeEventTypes.reversed().toSet()))

        assertThat(second).isEqualTo(first)
        assertThat(first.factors.toSet()).hasSize(first.factors.size)
        assertThat(first.factors.size).isAtMost(AlertCauseAnalyzer.MAX_FACTORS)
        assertThat(first.evidenceCodes.toSet()).hasSize(first.evidenceCodes.size)
        assertThat(first.evidenceCodes.size).isAtMost(AlertCauseAnalyzer.MAX_EVIDENCE_CODES)
        assertThat(first.evidenceCodes).containsNoDuplicates()
        first.evidenceCodes.forEach { code ->
            assertThat(code.length).isAtMost(AlertCauseAnalyzer.MAX_EVIDENCE_CODE_LENGTH)
            assertThat(AlertCauseAnalyzer.ALLOWED_EVIDENCE_CODES).contains(code)
        }
    }

    @Test
    fun snapshotIsDeterministicFiniteBoundedAndPersistsOnlyCodedIdentityResult() {
        val input = completeInput().copy(
            glucose = completeInput().glucose.copy(currentMmol = Double.NaN),
            activeEventTypes = setOf(AlertContextEventType.ALCOHOL, AlertContextEventType.STRESS)
        )
        val analysis = AlertCauseAnalyzer.analyze(input)

        val first = AlertCauseSnapshotCodec.encode(input, analysis).canonicalJson
        val second = AlertCauseSnapshotCodec.encode(input, analysis).canonicalJson

        assertThat(second).isEqualTo(first)
        assertThat(first.length).isAtMost(AlertCauseSnapshotCodec.MAX_JSON_CHARS)
        assertThat(first).doesNotContain("NaN")
        assertThat(first).doesNotContain("Infinity")
        assertThat(first).doesNotContain("\"cycleId\"")
        assertThat(first).doesNotContain("\"sensitivityCycleId\"")
        assertThat(first).doesNotContain("cycle-42")
        assertThat(first).contains("\"identityStatus\":\"MATCHED\"")
        assertThat(first).contains("\"settingsRevision\":42")
        assertThat(first).contains("\"cycleTimestamp\":$NOW")
        assertThat(first).contains("\"snapshotTimestamp\":${NOW - 60_000L}")
        assertThat(first).contains("\"source\":\"AAPS_COMPONENTS\"")
        assertThat(first).contains("\"uam\"")
        assertThat(first).contains("\"state\":\"INACTIVE\"")
    }

    @Test
    fun snapshotSanitizerAllowlistDropsUnknownKeysIdsAndFreeTextRecursively() {
        val malicious = AlertCauseSnapshot(
            """{
                "version":1,
                "cause":"SENSOR_QUALITY",
                "confidence":"HIGH",
                "factors":["SENSOR_QUALITY"],
                "evidence":["SENSOR_BLOCKED"],
                "identityStatus":"MATCHED",
                "unknownRoot":"private root text",
                "eventId":"550e8400-e29b-41d4-a716-446655440000",
                "glucose":{
                    "currentMmol":3.8,
                    "forecast5Mmol":"free text",
                    "note":"private glucose note",
                    "eventId":"glucose-event-id"
                },
                "sensitivity":{
                    "cycleId":"550e8400-e29b-41d4-a716-446655440001",
                    "settingsRevision":42,
                    "timestamp":1900000000000,
                    "isfMmolPerUnit":2.4,
                    "private":"patient detail"
                },
                "uam":{
                    "timestamp":1899999700000,
                    "state":"ACTIVE",
                    "active":true,
                    "controlActive":true,
                    "confidence":0.9,
                    "signedResidualMmol5":0.4,
                    "sensitivityCycleId":"550e8400-e29b-41d4-a716-446655440002",
                    "sensitivitySettingsRevision":42,
                    "title":"private event title"
                }
            }""".trimIndent()
        )

        val sanitized = AlertCauseSnapshotCodec.sanitize(malicious)?.canonicalJson

        assertThat(sanitized).isNotNull()
        assertThat(sanitized).contains("\"cause\":\"SENSOR_QUALITY\"")
        assertThat(sanitized).contains("\"currentMmol\":3.8")
        assertThat(sanitized).contains("\"settingsRevision\":42")
        assertThat(sanitized).contains("\"sensitivitySettingsRevision\":42")
        assertThat(sanitized).doesNotContain("unknownRoot")
        assertThat(sanitized).doesNotContain("eventId")
        assertThat(sanitized).doesNotContain("cycleId")
        assertThat(sanitized).doesNotContain("sensitivityCycleId")
        assertThat(sanitized).doesNotContain("private")
        assertThat(sanitized).doesNotContain("free text")
        assertThat(sanitized).doesNotContain("title")
        assertThat(sanitized).doesNotContain("550e8400")
        assertThat(sanitized!!.length).isAtMost(AlertCauseSnapshotCodec.MAX_JSON_CHARS)
    }

    @Test
    fun snapshotEncoderAlsoSanitizesSyntheticAnalysisLists() {
        val synthetic = AlertCauseAnalysis(
            primary = AlertCauseCode.UNKNOWN,
            factors = listOf(AlertCauseCode.UNKNOWN, AlertCauseCode.UNKNOWN),
            confidence = CauseConfidence.LOW,
            evidenceCodes = listOf("private evidence", "EVENT_STRESS", "EVENT_STRESS"),
            shortAdvice = "private advice"
        )

        val snapshot = AlertCauseSnapshotCodec.encode(completeInput(), synthetic).canonicalJson

        assertThat(snapshot).contains("\"factors\":[\"UNKNOWN\"]")
        assertThat(snapshot).contains("\"evidence\":[\"EVENT_STRESS\"]")
        assertThat(snapshot).doesNotContain("private")
    }

    @Test
    fun targetSnapshotPersistsOnlySemanticProductionReasonCodes() {
        val input = completeInput().copy(
            target = completeInput().target!!.copy(
                reasonCodes = listOf(
                    "auto_adjustment_applied",
                    "valid_looking_private_reason",
                    "free text patient detail",
                    "owner=private-rule"
                )
            )
        )
        val analysis = AlertCauseAnalyzer.analyze(input)

        val snapshot = AlertCauseSnapshotCodec.encode(input, analysis).canonicalJson

        assertThat(snapshot).contains("auto_adjustment_applied")
        assertThat(snapshot).doesNotContain("valid_looking_private_reason")
        assertThat(snapshot).doesNotContain("free text patient detail")
        assertThat(snapshot).doesNotContain("owner=private-rule")
    }

    @Test
    fun mismatchedSensitivityAndUamIdentityIsDataIncomplete() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(uam = completeInput().uam!!.copy(sensitivityCycleId = "other-cycle"))
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(analysis.evidenceCodes).contains("CYCLE_IDENTITY_MISMATCH")
    }

    @Test
    fun acceptedSensitivityIdentityMayPrecedeWriterTimestampWithinSameFreshCycle() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                sensitivity = completeInput().sensitivity!!.copy(timestamp = NOW - 2_000L)
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.UNKNOWN)
        assertThat(analysis.evidenceCodes).doesNotContain("CYCLE_IDENTITY_MISMATCH")
    }

    @Test
    fun canonicalGlucoseAndUamSampleMayPrecedeAcceptedWriterCycle() {
        val sampleTimestamp = NOW - 5 * 60_000L
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                glucose = completeInput().glucose.copy(currentTimestamp = sampleTimestamp),
                uam = qualifiedUam().copy(timestamp = sampleTimestamp)
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.MEAL_UAM)
        assertThat(analysis.evidenceCodes).doesNotContain("CYCLE_IDENTITY_MISMATCH")
    }

    @Test
    fun staleCanonicalUamSampleIsDataIncomplete() {
        val analysis = AlertCauseAnalyzer.analyze(
            completeInput().copy(
                uam = qualifiedUam().copy(timestamp = NOW - 16 * 60_000L)
            )
        )

        assertThat(analysis.primary).isEqualTo(AlertCauseCode.DATA_INCOMPLETE)
        assertThat(analysis.evidenceCodes).contains("CYCLE_IDENTITY_MISMATCH")
    }

    private fun completeInput(direction: AlertCauseDirection = AlertCauseDirection.HIGH) = AlertCauseInput(
        nowTs = NOW,
        cycleTimestamp = NOW,
        direction = direction,
        glucose = AlertGlucoseEvidence(
            currentMmol = if (direction == AlertCauseDirection.HIGH) 11.0 else 3.8,
            currentTimestamp = NOW,
            forecast5Mmol = if (direction == AlertCauseDirection.HIGH) 11.2 else 3.7,
            forecast30Mmol = if (direction == AlertCauseDirection.HIGH) 11.6 else 3.4,
            forecast60Mmol = if (direction == AlertCauseDirection.HIGH) 11.8 else 3.5,
            lowerCi5Mmol = if (direction == AlertCauseDirection.HIGH) 10.6 else 3.2,
            lowerCi30Mmol = if (direction == AlertCauseDirection.HIGH) 10.8 else 2.9,
            lowerCi60Mmol = if (direction == AlertCauseDirection.HIGH) 10.7 else 3.0,
            trendDelta5Mmol = if (direction == AlertCauseDirection.HIGH) 0.15 else -0.15,
            dataFresh = true
        ),
        sensor = AlertSensorEvidence(
            score = 0.90,
            blocked = false,
            suspectFalseLow = false
        ),
        insulin = AlertInsulinEvidence(
            cycleTimestamp = NOW,
            snapshotTimestamp = NOW - 60_000L,
            evidenceTimestamp = NOW - 60_000L,
            source = AlertInsulinSource.AAPS_COMPONENTS,
            netIobUnits = 1.5,
            effectivePositiveIobUnits = 1.5,
            insulinActivity = 0.03,
            confidence = 1.0
        ),
        sensitivity = AlertSensitivityEvidence(
            cycleId = "cycle-42",
            settingsRevision = 42L,
            timestamp = NOW,
            isfMmolPerUnit = 2.4,
            crGramPerUnit = 9.0,
            isfSource = AlertSensitivitySource.AAPS,
            crSource = AlertSensitivitySource.EVIDENCE_BLEND,
            confidence = 0.80
        ),
        uam = AlertUamEvidence(
            timestamp = NOW,
            state = AlertUamState.INACTIVE,
            active = false,
            controlActive = false,
            confidence = 0.20,
            signedResidualMmol5 = 0.0,
            equivalentCarbsGrams = null,
            sensitivityCycleId = "cycle-42",
            sensitivitySettingsRevision = 42L
        ),
        deliveryTrust = DeliveryTrustState.NORMAL,
        target = AlertTargetEvidence(
            manualTargetMmol = 6.0,
            autoDeltaMmol = 0.0,
            effectiveTargetMmol = 6.0,
            state = AlertTargetState.ACTIVE,
            reasonCodes = listOf("eligible")
        ),
        circadian = AlertCircadianEvidence(applied = false, delta30Mmol = 0.0, confidence = 0.0),
        activeEventTypes = emptySet(),
        eventContextAvailable = true
    )

    private fun qualifiedUam() = completeInput().uam!!.copy(
        state = AlertUamState.ACTIVE,
        active = true,
        controlActive = true,
        confidence = 0.85,
        signedResidualMmol5 = 0.45,
        equivalentCarbsGrams = 18.0
    )

    companion object {
        private const val NOW = 2_000_000L
    }
}
