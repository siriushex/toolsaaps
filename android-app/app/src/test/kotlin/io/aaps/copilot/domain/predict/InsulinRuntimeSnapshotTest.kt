package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InsulinRuntimeSnapshotTest {

    @Test
    fun completeComponentSnapshotPreservesSignedValuesAndUsesPositiveComponentsOnly() {
        val timestamp = 1_700_000_000_000L

        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            components = components(
                timestamp = timestamp,
                net = -0.5,
                bolus = 0.4,
                basal = -0.9,
                activity = 0.012
            )
        )

        val snapshot = result.snapshot
        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.source).isEqualTo(InsulinRuntimeSource.AAPS_COMPONENTS)
        assertThat(snapshot.timestamp).isEqualTo(timestamp)
        assertThat(snapshot.netIobUnits).isEqualTo(-0.5)
        assertThat(snapshot.bolusIobUnits).isEqualTo(0.4)
        assertThat(snapshot.basalIobUnits).isEqualTo(-0.9)
        assertThat(snapshot.insulinActivity).isEqualTo(0.012)
        assertThat(snapshot.effectivePositiveIobUnits).isEqualTo(0.4)
        assertThat(snapshot.confidence).isEqualTo(1.0)
        assertThat(snapshot.fallbackReason).isNull()
        assertThat(result.rejectionReason).isNull()
    }

    @Test
    fun mixedComponentTimestampsFallBackToSignedLegacyWithoutInventingComponents() {
        val timestamp = 1_700_000_000_000L
        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            components = components(timestamp, net = -0.5, bolus = 0.4, basal = -0.9, activity = 0.012)
                .copy(basalIob = TimedInsulinValue(-0.9, timestamp - 1_000L)),
            legacyIob = TimedInsulinValue(-0.5, timestamp)
        )

        val snapshot = result.snapshot
        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(snapshot.netIobUnits).isEqualTo(-0.5)
        assertThat(snapshot.bolusIobUnits).isNull()
        assertThat(snapshot.basalIobUnits).isNull()
        assertThat(snapshot.insulinActivity).isNull()
        assertThat(snapshot.effectivePositiveIobUnits).isEqualTo(0.0)
        assertThat(snapshot.confidence).isLessThan(1.0)
        assertThat(snapshot.fallbackReason).contains("component_timestamp_mismatch")
    }

    @Test
    fun staleComponentAndLegacyTelemetryIsRejected() {
        val now = 1_700_001_000_000L
        val staleTimestamp = now - InsulinRuntimeSnapshotResolver.MAX_AGE_MS - 1L

        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = now,
            components = components(staleTimestamp, net = 1.0, bolus = 1.2, basal = -0.2, activity = 0.02),
            legacyIob = TimedInsulinValue(1.0, staleTimestamp)
        )

        assertThat(result.snapshot).isNull()
        assertThat(result.rejectionReason).contains("timestamp_stale")
    }

    @Test
    fun nonFiniteAndOutOfBoundsComponentsSafelyFallBackToLegacy() {
        val timestamp = 1_700_000_000_000L
        val nonFinite = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            components = components(timestamp, net = Double.NaN, bolus = 1.0, basal = 0.0, activity = 0.01),
            legacyIob = TimedInsulinValue(-0.3, timestamp)
        )
        val outOfBounds = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            components = components(timestamp, net = 31.0, bolus = 31.0, basal = 0.0, activity = 0.01),
            legacyIob = TimedInsulinValue(-0.3, timestamp)
        )

        assertThat(nonFinite.snapshot?.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(nonFinite.snapshot?.fallbackReason).contains("component_non_finite")
        assertThat(outOfBounds.snapshot?.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(outOfBounds.snapshot?.fallbackReason).contains("component_out_of_bounds")
    }

    @Test
    fun absentComponentsUseBoundedLegacyOrLocalEstimateWithLowerConfidence() {
        val timestamp = 1_700_000_000_000L
        val legacy = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            legacyIob = TimedInsulinValue(-0.7, timestamp)
        ).snapshot
        val local = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            localEstimate = QualifiedLocalInsulinEstimate(
                value = 1.4,
                timestamp = timestamp,
                evidenceTimestamp = timestamp,
                therapyCoverage = 0.85,
                confidence = 0.60
            )
        ).snapshot
        val invalidLocal = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            localEstimate = QualifiedLocalInsulinEstimate(
                value = 31.0,
                timestamp = timestamp,
                evidenceTimestamp = timestamp,
                therapyCoverage = 0.85,
                confidence = 0.75
            )
        )

        assertThat(legacy?.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(legacy?.netIobUnits).isEqualTo(-0.7)
        assertThat(legacy?.effectivePositiveIobUnits).isEqualTo(0.0)
        assertThat(legacy?.fallbackReason).isEqualTo("components_absent")
        assertThat(local?.source).isEqualTo(InsulinRuntimeSource.LOCAL_ESTIMATE)
        assertThat(local?.netIobUnits).isEqualTo(1.4)
        assertThat(local?.effectivePositiveIobUnits).isEqualTo(1.4)
        assertThat(local?.confidence).isLessThan(legacy!!.confidence)
        assertThat(invalidLocal.snapshot).isNull()
        assertThat(invalidLocal.rejectionReason).contains("local_estimate_out_of_bounds")
    }

    @Test
    fun componentNetMustEqualSignedComponentSum() {
        val timestamp = 1_700_000_000_000L
        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            components = components(timestamp, net = 0.9, bolus = 0.4, basal = -0.9, activity = 0.012),
            legacyIob = TimedInsulinValue(0.9, timestamp)
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(result.snapshot?.fallbackReason).contains("component_net_mismatch")
    }

    @Test
    fun smallAapsRoundingDifferenceStillUsesAtomicComponents() {
        val timestamp = 1_700_000_000_000L
        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            components = components(timestamp, net = -0.48, bolus = 0.4, basal = -0.9, activity = 0.012),
            legacyIob = TimedInsulinValue(-0.48, timestamp)
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.AAPS_COMPONENTS)
        assertThat(result.snapshot?.effectivePositiveIobUnits).isEqualTo(0.4)
    }

    @Test
    fun rejectedPartialTelemetryUsesFreshLocalFallbackWithoutStaleComponents() {
        val timestamp = 1_700_000_000_000L
        val result = InsulinRuntimeSnapshotResolver.resolveTelemetry(
            nowTimestamp = timestamp + 30_000L,
            telemetry = mapOf(
                "iob_net_units" to -0.5,
                "iob_bolus_units" to null,
                "iob_basal_units" to null,
                "insulin_activity" to null,
                "iob_relay_timestamp_ms" to null,
                "iob_runtime_confidence" to null
            ),
            localEstimate = QualifiedLocalInsulinEstimate(
                value = 0.8,
                timestamp = timestamp,
                evidenceTimestamp = timestamp,
                therapyCoverage = 0.85,
                confidence = 0.75
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.LOCAL_ESTIMATE)
        assertThat(result.snapshot?.netIobUnits).isEqualTo(0.8)
        assertThat(result.snapshot?.bolusIobUnits).isNull()
        assertThat(result.snapshot?.fallbackReason).contains("component_incomplete")
    }

    @Test
    fun unsupportedLocalZeroRemainsDiagnosticAndPreservesZeroCoverage() {
        val timestamp = 1_700_000_000_000L

        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = timestamp + 30_000L,
            localEstimate = QualifiedLocalInsulinEstimate(
                value = 0.0,
                timestamp = timestamp + 30_000L,
                evidenceTimestamp = timestamp,
                therapyCoverage = 0.0,
                confidence = 0.0
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.LOCAL_ESTIMATE)
        assertThat(result.snapshot?.netIobUnits).isEqualTo(0.0)
        assertThat(result.snapshot?.therapyCoverage).isEqualTo(0.0)
        assertThat(result.snapshot?.confidence).isEqualTo(0.0)
    }

    @Test
    fun coveredLocalEstimatePreservesEvidenceTimestampCoverageAndConfidence() {
        val now = 1_700_000_120_000L
        val evidenceTimestamp = now - 4 * 60_000L

        val result = InsulinRuntimeSnapshotResolver.resolve(
            nowTimestamp = now,
            localEstimate = QualifiedLocalInsulinEstimate(
                value = 0.9,
                timestamp = now,
                evidenceTimestamp = evidenceTimestamp,
                therapyCoverage = 0.85,
                confidence = 0.75
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.LOCAL_ESTIMATE)
        assertThat(result.snapshot?.timestamp).isEqualTo(now)
        assertThat(result.snapshot?.evidenceTimestamp).isEqualTo(evidenceTimestamp)
        assertThat(result.snapshot?.therapyCoverage).isEqualTo(0.85)
        assertThat(result.snapshot?.confidence).isEqualTo(0.75)
    }

    @Test
    fun telemetrySnapshotPreservesOneSignedComponentCycle() {
        val timestamp = 1_700_000_000_000L

        val result = InsulinRuntimeSnapshotResolver.resolveTelemetry(
            nowTimestamp = timestamp + 30_000L,
            telemetry = mapOf(
                "iob_net_units" to -0.5,
                "iob_bolus_units" to 0.4,
                "iob_basal_units" to -0.9,
                "insulin_activity" to 0.012,
                "iob_relay_timestamp_ms" to timestamp.toDouble(),
                "iob_runtime_confidence" to 1.0
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.AAPS_COMPONENTS)
        assertThat(result.snapshot?.netIobUnits).isEqualTo(-0.5)
        assertThat(result.snapshot?.effectivePositiveIobUnits).isEqualTo(0.4)
        assertThat(result.snapshot?.timestamp).isEqualTo(timestamp)
    }

    @Test
    fun telemetryFallbackKeepsSignedLegacyInsteadOfClampingIt() {
        val timestamp = 1_700_000_000_000L

        val result = InsulinRuntimeSnapshotResolver.resolveTelemetry(
            nowTimestamp = timestamp + 30_000L,
            telemetry = mapOf(
                "iob_units" to -0.7,
                "iob_sample_ts" to timestamp.toDouble(),
                "iob_runtime_source_code" to InsulinRuntimeSnapshotResolver.sourceCode(
                    InsulinRuntimeSource.LEGACY_AAPS
                ),
                "iob_runtime_confidence" to 0.65
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(result.snapshot?.netIobUnits).isEqualTo(-0.7)
        assertThat(result.snapshot?.effectivePositiveIobUnits).isEqualTo(0.0)
    }

    @Test
    fun telemetryFallbackUsesExplicitExternalProvenanceRegardlessOfConfidence() {
        val timestamp = 1_700_000_000_000L

        val result = InsulinRuntimeSnapshotResolver.resolveTelemetry(
            nowTimestamp = timestamp + 30_000L,
            telemetry = mapOf(
                "iob_units" to 1.2,
                "iob_sample_ts" to timestamp.toDouble(),
                "iob_runtime_source_code" to InsulinRuntimeSnapshotResolver.sourceCode(
                    InsulinRuntimeSource.EXTERNAL_ESTIMATE
                ),
                "iob_runtime_confidence" to 0.99
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
        assertThat(result.snapshot?.confidence).isLessThan(0.65)
    }

    @Test
    fun telemetryFallbackUsesExplicitLocalProvenanceInsteadOfConfidenceInference() {
        val timestamp = 1_700_000_000_000L

        val result = InsulinRuntimeSnapshotResolver.resolveTelemetry(
            nowTimestamp = timestamp + 30_000L,
            telemetry = mapOf(
                "iob_units" to 0.8,
                "iob_sample_ts" to timestamp.toDouble(),
                "iob_runtime_source_code" to InsulinRuntimeSnapshotResolver.sourceCode(
                    InsulinRuntimeSource.LOCAL_ESTIMATE
                ),
                "iob_runtime_evidence_timestamp_ms" to timestamp.toDouble(),
                "iob_runtime_therapy_coverage" to 0.85,
                "iob_runtime_confidence" to 1.0
            )
        )

        assertThat(result.snapshot?.source).isEqualTo(InsulinRuntimeSource.LOCAL_ESTIMATE)
    }

    private fun components(
        timestamp: Long,
        net: Double,
        bolus: Double,
        basal: Double,
        activity: Double
    ) = InsulinComponentTelemetry(
        netIob = TimedInsulinValue(net, timestamp),
        bolusIob = TimedInsulinValue(bolus, timestamp),
        basalIob = TimedInsulinValue(basal, timestamp),
        insulinActivity = TimedInsulinValue(activity, timestamp)
    )
}
