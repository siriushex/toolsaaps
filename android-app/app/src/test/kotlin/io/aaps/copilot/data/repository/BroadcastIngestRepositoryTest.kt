package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BroadcastIngestRepositoryTest {

    @Test
    fun highFrequencyTelemetryKeepsExistingReactiveThrottle() {
        var now = 1_000_000L
        val policy = BroadcastReactiveInvalidationPolicy(nowMs = { now })
        val telemetry = BroadcastIngestRepository.IngestResult(0, 0, 4, null)

        assertThat(policy.shouldInvalidate(telemetry, telemetryOnlyCoalescedAction = true)).isTrue()
        now += 60_000L
        assertThat(policy.shouldInvalidate(telemetry, telemetryOnlyCoalescedAction = true)).isFalse()
        now += 4 * 60_000L
        assertThat(policy.shouldInvalidate(telemetry, telemetryOnlyCoalescedAction = true)).isTrue()
    }

    @Test
    fun therapyAndRelevantGlucoseNotifyOnlyAfterPersistenceCompletes() = runTest {
        val order = mutableListOf<String>()
        val policy = BroadcastReactiveInvalidationPolicy(nowMs = { 1_000_000L })

        val result = persistBroadcastIngestAndComplete(
            persistence = {
                order += "persisted"
                BroadcastIngestRepository.IngestResult(
                    glucoseImported = 1,
                    therapyImported = 1,
                    telemetryImported = 0,
                    warning = null,
                    latestGlucoseMmol = 7.0
                )
            },
            shouldInvalidate = { policy.shouldInvalidate(it, telemetryOnlyCoalescedAction = false) },
            onClinicalInputPersisted = { order += "invalidated" }
        )

        assertThat(order).containsExactly("persisted", "invalidated").inOrder()
        assertThat(result.reactiveInvalidationRequested).isTrue()
    }

    @Test
    fun relevantGlucoseAndTherapyRetainExistingPolicyWithoutTelemetryBypass() {
        var now = 1_000_000L
        val policy = BroadcastReactiveInvalidationPolicy(nowMs = { now })
        val moderateGlucose = BroadcastIngestRepository.IngestResult(
            glucoseImported = 1,
            therapyImported = 0,
            telemetryImported = 4,
            warning = null,
            latestGlucoseMmol = 7.0
        )
        val criticalGlucose = moderateGlucose.copy(latestGlucoseMmol = 3.8)
        val therapy = moderateGlucose.copy(glucoseImported = 0, therapyImported = 1)

        assertThat(policy.shouldInvalidate(moderateGlucose, telemetryOnlyCoalescedAction = true)).isTrue()
        now += 60_000L
        assertThat(policy.shouldInvalidate(moderateGlucose, telemetryOnlyCoalescedAction = true)).isFalse()
        assertThat(policy.shouldInvalidate(criticalGlucose, telemetryOnlyCoalescedAction = true)).isTrue()
        assertThat(policy.shouldInvalidate(therapy, telemetryOnlyCoalescedAction = true)).isTrue()
    }

    @Test
    fun noDataAndPersistenceFailureDoNotNotify() = runTest {
        var callbacks = 0
        val noData = persistBroadcastIngestAndComplete(
            persistence = { BroadcastIngestRepository.IngestResult(0, 0, 0, "no_supported_payload") },
            shouldInvalidate = { false },
            onClinicalInputPersisted = { callbacks++ }
        )

        val failure = runCatching {
            persistBroadcastIngestAndComplete(
                persistence = { error("db_failed") },
                shouldInvalidate = { true },
                onClinicalInputPersisted = { callbacks++ }
            )
        }

        assertThat(noData.reactiveInvalidationRequested).isFalse()
        assertThat(failure.exceptionOrNull()).hasMessageThat().isEqualTo("db_failed")
        assertThat(callbacks).isEqualTo(0)
    }

    @Test
    fun statusIobMappingKeepsIngestBucketSeparateFromRelayCycleTimestamp() {
        val receivedAt = 1_700_000_299_999L
        val relayTimestamp = 1_700_000_295_000L

        val samples = BroadcastIngestRepository.mapHighFrequencyStatusTelemetry(
            source = "aaps_broadcast",
            extras = mapOf(
                "iob" to "-0.5",
                "netIob" to "-0.5",
                "bolusIob" to "0.4",
                "basalIob" to "-0.9",
                "insulinActivity" to "0.012",
                "iobTimestamp" to relayTimestamp.toString()
            ),
            receivedAtMs = receivedAt
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_units"]?.timestamp).isEqualTo(1_700_000_100_000L)
        assertThat(byKey["iob_relay_timestamp_ms"]?.valueDouble).isEqualTo(relayTimestamp.toDouble())
        assertThat(byKey["iob_basal_units"]?.valueDouble).isEqualTo(-0.9)
    }

    @Test
    fun revisionStatusSignalIsValidatedTelemetryAndNotTherapy() {
        val extras = mapOf("aapsCarbRevisionId" to "42")

        val signal = parseAapsCarbStatusSignal(
            action = "info.nightscout.androidaps.status",
            extras = extras
        )

        assertThat(signal.revisionId).isEqualTo(42L)
        assertThat(signal.hasExplicitTherapyPayload).isFalse()
    }

    @Test
    fun invalidOrNegativeRevisionStatusSignalIsRejected() {
        val values = listOf("-1", "1.5", "NaN", Long.MAX_VALUE.toString() + "0")

        values.forEach { value ->
            val signal = parseAapsCarbStatusSignal(
                action = "info.nightscout.androidaps.status",
                extras = mapOf("aapsCarbRevisionId" to value)
            )

            assertThat(signal.revisionId).isNull()
            assertThat(signal.hasExplicitTherapyPayload).isFalse()
        }
    }
}
