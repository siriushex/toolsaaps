package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import org.junit.Test

class CalibrationModelAuthorityTest {

    @Test
    fun matchingCurrentSessionSelectsApplicableActiveModel() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val model = model(sessionKey = sessionKey)

        val selected = CalibrationModelAuthority.selectApplicableModel(
            model = model,
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(sensorAge("age", "aaps", NOW, 1.0))
        )

        assertThat(selected).isSameInstanceAs(model)
    }

    @Test
    fun rolloverMissingOrAmbiguousSessionEvidenceFailsClosed() {
        val oldSession = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 8.0))
        val model = model(sessionKey = oldSession)

        val rollover = CalibrationModelAuthority.selectApplicableModel(
            model = model,
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(sensorAge("new-age", "aaps", NOW, 1.0))
        )
        val missing = CalibrationModelAuthority.selectApplicableModel(
            model = model,
            nowTs = NOW,
            pointTs = NOW,
            telemetry = emptyList()
        )
        val ambiguous = CalibrationModelAuthority.selectApplicableModel(
            model = model,
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(
                sensorAge("age-a", "aaps", NOW, 8.0),
                sensorAge("age-b", "xdrip", NOW, 1.0)
            )
        )

        assertThat(rollover).isNull()
        assertThat(missing).isNull()
        assertThat(ambiguous).isNull()
    }

    @Test
    fun invalidCompetingSessionEvidenceFailsClosed() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val selected = CalibrationModelAuthority.selectApplicableModel(
            model = model(sessionKey = sessionKey),
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(
                sensorAge("valid", "aaps", NOW, 1.0),
                sensorAge("invalid", "xdrip", NOW, 1.0).copy(
                    valueDouble = null,
                    valueText = "unknown"
                )
            )
        )

        assertThat(selected).isNull()
    }

    @Test
    fun expiredAndZeroStrengthModelsFailClosed() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val evidence = listOf(sensorAge("age", "aaps", NOW, 1.0))
        val expired = model(
            sessionKey = sessionKey,
            createdAt = NOW - 1_000L,
            validFromTs = NOW - 1_000L,
            validToTs = NOW - 1L
        )
        val zeroStrength = model(
            sessionKey = sessionKey,
            createdAt = NOW - 72L * 60L * 60L * 1000L,
            validFromTs = NOW - 80L * 60L * 60L * 1000L,
            validToTs = NOW + 1L
        )

        assertThat(CalibrationModelAuthority.selectApplicableModel(expired, NOW, NOW, evidence)).isNull()
        assertThat(CalibrationModelAuthority.selectApplicableModel(zeroStrength, NOW, NOW, evidence)).isNull()
    }

    @Test
    fun glucoseApplicationFallsBackToRawOutsideModelApplicability() {
        val model = model(
            sessionKey = "session",
            createdAt = NOW,
            validFromTs = NOW,
            validToTs = NOW + 60_000L
        )

        val beforeValidity = CalibrationModelAuthority.applyToGlucose(
            pointTs = NOW - 1L,
            rawMmol = 5.0,
            model = model
        )
        val afterValidity = CalibrationModelAuthority.applyToGlucose(
            pointTs = NOW + 60_001L,
            rawMmol = 5.0,
            model = model
        )

        assertThat(beforeValidity).isEqualTo(5.0)
        assertThat(afterValidity).isEqualTo(5.0)
    }

    @Test
    fun futureGlucosePointFailsClosedEvenWithMatchingSessionAndModelWindow() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))

        val selected = CalibrationModelAuthority.selectApplicableModel(
            model = model(sessionKey = sessionKey, validToTs = NOW + 60_000L),
            nowTs = NOW,
            pointTs = NOW + 1L,
            telemetry = listOf(sensorAge("age", "aaps", NOW, 1.0))
        )

        assertThat(selected).isNull()
    }

    @Test
    fun modelCreatedAfterAuthoritativeNowFailsClosedButExactNowIsAllowed() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val evidence = listOf(sensorAge("age", "aaps", NOW, 1.0))
        val futureCreated = model(
            sessionKey = sessionKey,
            createdAt = NOW + 1L,
            validFromTs = NOW - 60_000L
        )
        val createdNow = futureCreated.copy(createdAt = NOW)

        assertThat(
            CalibrationModelAuthority.selectApplicableModel(
                futureCreated,
                NOW,
                NOW,
                evidence
            )
        ).isNull()
        assertThat(
            CalibrationModelAuthority.selectApplicableModel(
                createdNow,
                NOW,
                NOW,
                evidence
            )
        ).isEqualTo(createdNow)
    }

    @Test
    fun historicalPointBeforeModelCreationRemainsApplicableAfterCausalAcceptance() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val pointTs = NOW - 30L * 60L * 1000L
        val model = model(
            sessionKey = sessionKey,
            createdAt = NOW,
            validFromTs = pointTs - 1L
        )

        val selected = CalibrationModelAuthority.selectApplicableModel(
            model = model,
            nowTs = NOW,
            pointTs = pointTs,
            telemetry = listOf(sensorAge("age", "aaps", NOW, 1.0))
        )

        assertThat(selected).isEqualTo(model)
        assertThat(CalibrationModelAuthority.applyToGlucose(pointTs, 5.0, model))
            .isWithin(0.0001).of(5.8)
    }

    @Test
    fun malformedModelTimestampsFailCurrentAuthoritySelection() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val evidence = listOf(sensorAge("age", "aaps", NOW, 1.0))
        val negativeValidity = model(
            sessionKey = sessionKey,
            validFromTs = -1L,
            validToTs = NOW + 1L
        )
        val createdAfterValidity = model(
            sessionKey = sessionKey,
            createdAt = NOW,
            validFromTs = NOW - 1L,
            validToTs = NOW - 1L
        )

        assertThat(
            CalibrationModelAuthority.selectApplicableModel(
                negativeValidity,
                NOW,
                NOW,
                evidence
            )
        ).isNull()
        assertThat(
            CalibrationModelAuthority.selectApplicableModel(
                createdAfterValidity,
                NOW,
                NOW - 1L,
                evidence
            )
        ).isNull()
    }

    @Test
    fun modelFreeAuthorityStillRequiresCurrentCausalGlucose() {
        val evidence = listOf(sensorAge("age", "aaps", NOW, 1.0))
        assertThat(
            CalibrationModelAuthority.resolveAuthorityContext(
                nowTs = NOW,
                pointTs = null,
                telemetry = evidence
            ).contextValid
        ).isFalse()
        assertThat(
            CalibrationModelAuthority.resolveAuthorityContext(
                nowTs = NOW,
                pointTs = NOW + 1L,
                telemetry = evidence
            ).contextValid
        ).isFalse()
        assertThat(
            CalibrationModelAuthority.resolveAuthorityContext(
                nowTs = NOW,
                pointTs = NOW,
                telemetry = evidence
            ).contextValid
        ).isTrue()
    }

    @Test
    fun modelFreeAuthorityRequiresOneTrustworthyCurrentSensorSession() {
        val sameSession = CalibrationModelAuthority.resolveAuthorityContext(
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(sensorAge("age", "aaps", NOW, 1.0)),
            evidenceTruncated = false
        )
        val missing = CalibrationModelAuthority.resolveAuthorityContext(
            nowTs = NOW,
            pointTs = NOW,
            telemetry = emptyList(),
            evidenceTruncated = false
        )
        val conflicting = CalibrationModelAuthority.resolveAuthorityContext(
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(
                sensorAge("old", "aaps", NOW, 8.0),
                sensorAge("new", "xdrip", NOW, 1.0)
            ),
            evidenceTruncated = false
        )
        val truncated = CalibrationModelAuthority.resolveAuthorityContext(
            nowTs = NOW,
            pointTs = NOW,
            telemetry = listOf(sensorAge("age", "aaps", NOW, 1.0)),
            evidenceTruncated = true
        )

        assertThat(sameSession.contextValid).isTrue()
        assertThat(sameSession.currentSessionKey).isEqualTo(
            calibrationSensorSessionKeyFromAgeSample(NOW, 1.0)
        )
        assertThat(missing.contextValid).isFalse()
        assertThat(missing.currentSessionKey).isNull()
        assertThat(conflicting.contextValid).isFalse()
        assertThat(conflicting.currentSessionKey).isNull()
        assertThat(truncated.contextValid).isFalse()
        assertThat(truncated.currentSessionKey).isNull()
    }

    @Test
    fun freshConflictAtSensorEvidenceSentinelFailsClosed() {
        val sessionStartTs = NOW - 60L * 60L * 1000L
        val matchingRows = (0 until CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT).map { index ->
            val timestamp = NOW - index
            sensorAge(
                id = "matching-$index",
                source = "source-$index",
                timestamp = timestamp,
                ageHours = (timestamp - sessionStartTs).toDouble() / 3_600_000.0
            )
        }
        val conflictingSentinel = sensorAge(
            id = "conflicting-sentinel",
            source = "conflict",
            timestamp = NOW - CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT,
            ageHours = 0.1
        )
        val evidence = CalibrationModelAuthority.selectBoundedSessionEvidence(
            queriedTelemetry = matchingRows + conflictingSentinel,
            nowTs = NOW
        )
        val sessionKey = requireNotNull(
            calibrationSensorSessionKeyFromAgeSample(NOW, 1.0)
        )

        val selected = CalibrationModelAuthority.selectApplicableModel(
            model = model(sessionKey = sessionKey),
            nowTs = NOW,
            pointTs = NOW,
            telemetry = evidence.telemetry,
            evidenceTruncated = evidence.evidenceTruncated
        )

        assertThat(evidence.evidenceTruncated).isTrue()
        assertThat(selected).isNull()
    }

    @Test
    fun explicitRawAuthorityCanUseCausalGlucoseWithoutApplyingAnUnknownSensorModel() {
        val context = CalibrationModelAuthority.resolveAuthorityContext(
            nowTs = NOW,
            pointTs = NOW,
            telemetry = emptyList()
        )
        val raw = CalibrationAuthorityStateCodec.resolve(
            token = CalibrationAuthorityStateCodec.raw("raw-without-session", null),
            applicableModel = null,
            context = context
        )

        assertThat(raw.contextValid).isTrue()
        assertThat(raw.currentSessionKey).isNull()
        assertThat(raw.activeModel).isNull()
        assertThat(CalibrationAuthorityStateCodec.resolve(
            token = CalibrationAuthorityStateCodec.pending("manual-pending"),
            applicableModel = null,
            context = context
        ).contextValid).isFalse()
        assertThat(CalibrationAuthorityStateCodec.resolve(
            token = null,
            applicableModel = null,
            context = context
        ).contextValid).isFalse()
        val unrelatedModel = model("unverified-session")
        assertThat(CalibrationAuthorityStateCodec.resolve(
            token = CalibrationAuthorityStateCodec.active("active", unrelatedModel),
            applicableModel = unrelatedModel,
            context = context
        ).contextValid).isFalse()
        assertThat(CalibrationAuthorityStateCodec.resolve(
            token = CalibrationAuthorityStateCodec.raw("raw-without-session", null),
            applicableModel = unrelatedModel,
            context = context
        ).contextValid).isFalse()
    }

    @Test
    fun rawWithoutSessionDoesNotAuthorizeConflictingTruncatedOrNoncausalEvidence() {
        val contexts = listOf(
            CalibrationModelAuthority.resolveAuthorityContext(NOW, null, emptyList()),
            CalibrationModelAuthority.resolveAuthorityContext(NOW, NOW + 1L, emptyList()),
            CalibrationModelAuthority.resolveAuthorityContext(NOW, NOW, emptyList(), evidenceTruncated = true),
            CalibrationModelAuthority.resolveAuthorityContext(
                NOW, NOW,
                listOf(sensorAge("old", "aaps", NOW, 8.0), sensorAge("new", "xdrip", NOW, 1.0))
            )
        )
        contexts.forEach { context ->
            assertThat(CalibrationAuthorityStateCodec.resolve(
                token = CalibrationAuthorityStateCodec.raw("raw-without-session", null),
                applicableModel = null,
                context = context
            ).contextValid).isFalse()
        }
    }

    @Test
    fun durableAuthorityCodecIsBoundedVersionedAndExact() {
        val sessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
        val model = model(sessionKey)
        val active = CalibrationAuthorityStateCodec.active("active-nonce", model)
        val raw = CalibrationAuthorityStateCodec.raw("raw-nonce", sessionKey)
        val pending = CalibrationAuthorityStateCodec.pending("pending-nonce")

        assertThat(CalibrationAuthorityStateCodec.decode(active)?.state)
            .isEqualTo(DurableCalibrationAuthorityStatus.ACTIVE)
        assertThat(CalibrationAuthorityStateCodec.selectDurablyBoundModel(active, model))
            .isEqualTo(model)
        assertThat(CalibrationAuthorityStateCodec.selectDurablyBoundModel(
            active,
            model.copy(offsetMmol = model.offsetMmol + 0.1)
        )).isNull()
        assertThat(CalibrationAuthorityStateCodec.decode(raw)?.state)
            .isEqualTo(DurableCalibrationAuthorityStatus.RAW)
        assertThat(CalibrationAuthorityStateCodec.decode(pending)?.state)
            .isEqualTo(DurableCalibrationAuthorityStatus.PENDING)
        listOf(
            "legacy-opaque-token",
            "{\"version\":2,\"state\":\"RAW\",\"nonce\":\"n\"}",
            "{\"version\":1,\"state\":\"RAW\",\"nonce\":\"n\",\"sessionKey\":\"\"}",
            "x".repeat(1_025)
        ).forEach { invalid ->
            assertThat(CalibrationAuthorityStateCodec.decode(invalid)).isNull()
            assertThat(CalibrationAuthorityStateCodec.selectDurablyBoundModel(invalid, model)).isNull()
        }
    }

    private fun model(
        sessionKey: String,
        createdAt: Long = NOW - 60_000L,
        validFromTs: Long = NOW - 60_000L,
        validToTs: Long = NOW + 24L * 60L * 60L * 1000L
    ) = GlucoseCalibrationModel(
        id = "accepted-model",
        sensorSessionKey = sessionKey,
        createdAt = createdAt,
        validFromTs = validFromTs,
        validToTs = validToTs,
        modelType = GlucoseCalibrationModelType.OFFSET,
        gain = 1.0,
        offsetMmol = 0.8,
        confidence = 0.9,
        checkCount = 2,
        sensorAgeHours = 4.0,
        lagMinutesAtFit = 10.0,
        status = GlucoseCalibrationModelStatus.ACTIVE,
        diagnosticsJson = "{}"
    )

    private fun sensorAge(
        id: String,
        source: String,
        timestamp: Long,
        ageHours: Double
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = "sensor_age_hours",
        valueDouble = ageHours,
        valueText = null,
        unit = "h",
        quality = "OK"
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
