package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.TelemetryMetricMapper
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalNightscoutClinicalInputInvalidationTest {

    @Test
    fun identicalDeviceStatusHeartbeatIsSuppressedButIobCobAndGlucoseChangesAreImmediate() {
        val policy = LocalNightscoutInvalidationPolicy()

        assertThat(policy.shouldInvalidateDeviceStatus(0, listOf(sample("iob_units", 1.0)))).isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(0, listOf(sample("iob_units", 1.0, timestamp = 2L))))
            .isFalse()
        assertThat(policy.shouldInvalidateDeviceStatus(0, listOf(sample("iob_units", 1.2, timestamp = 3L))))
            .isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(0, listOf(sample("cob_grams", 12.0, timestamp = 4L))))
            .isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(1, emptyList())).isTrue()
    }

    @Test
    fun irrelevantDeviceStatusNoiseDoesNotInvalidate() {
        val policy = LocalNightscoutInvalidationPolicy()

        assertThat(policy.shouldInvalidateDeviceStatus(0, listOf(sample("raw_uploader_battery", 77.0))))
            .isFalse()
    }

    @Test
    fun componentIobChangesInvalidateWhenTotalIobIsUnchanged() {
        val componentKeys = listOf(
            "iob_net_units",
            "iob_bolus_units",
            "iob_basal_units",
            "insulin_activity",
            "iob_effective_positive_units"
        )

        componentKeys.forEach { componentKey ->
            val policy = LocalNightscoutInvalidationPolicy()
            val initial = listOf(
                sample("iob_units", 1.0),
                sample(componentKey, 0.2)
            )
            val changed = listOf(
                sample("iob_units", 1.0, timestamp = 2L),
                sample(componentKey, 0.3, timestamp = 2L)
            )
            val timestampOnly = listOf(
                sample("iob_units", 1.0, timestamp = 3L),
                sample(componentKey, 0.3, timestamp = 3L)
            )

            assertThat(policy.shouldInvalidateDeviceStatus(0, initial)).isTrue()
            assertThat(policy.shouldInvalidateDeviceStatus(0, changed)).isTrue()
            assertThat(policy.shouldInvalidateDeviceStatus(0, timestampOnly)).isFalse()
        }
    }

    @Test
    fun realNestedDeviceStatusUsesUntrustedFlattenedIobOnlyForRelevance() {
        val componentFields = listOf(
            "netIob",
            "bolusIob",
            "basalIob",
            "activity",
            "effectivePositiveIob",
            "confidence",
            "source",
            "fallbackReason"
        )

        componentFields.forEach { changedField ->
            val policy = LocalNightscoutInvalidationPolicy()
            val initial = prepareLocalNightscoutDeviceStatus(
                timestamp = 1L,
                source = "local_nightscout_devicestatus",
                payload = nestedDeviceStatus()
            )
            val changedIob = (nestedDeviceStatus()["openaps"] as Map<*, *>)
                .let { it["iob"] as Map<*, *> }
                .toMutableMap()
                .apply {
                    this[changedField] = when (changedField) {
                        "source" -> "fallback"
                        "fallbackReason" -> "component_missing"
                        else -> 0.75
                    }
                }
            val changedPayload = mapOf("openaps" to mapOf("iob" to changedIob))
            val changed = prepareLocalNightscoutDeviceStatus(
                timestamp = 2L,
                source = "local_nightscout_devicestatus",
                payload = changedPayload
            )
            val timestampOnly = prepareLocalNightscoutDeviceStatus(
                timestamp = 3L,
                source = "local_nightscout_devicestatus",
                payload = changedPayload
            )

            assertThat(initial.telemetryRows).containsExactlyElementsIn(
                TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
                    timestamp = 1L,
                    source = "local_nightscout_devicestatus",
                    flattened = initial.flattened
                )
            )
            assertThat(policy.shouldInvalidateDeviceStatus(0, initial.telemetryRows, initial.relevanceSignature))
                .isTrue()
            assertThat(policy.shouldInvalidateDeviceStatus(0, changed.telemetryRows, changed.relevanceSignature))
                .isTrue()
            assertThat(policy.shouldInvalidateDeviceStatus(0, timestampOnly.telemetryRows, timestampOnly.relevanceSignature))
                .isFalse()
        }
    }

    @Test
    fun realNestedTimestampAndUploaderNoiseDoNotCreateReactiveHeartbeat() {
        val policy = LocalNightscoutInvalidationPolicy()
        val initial = prepareLocalNightscoutDeviceStatus(
            timestamp = 1L,
            source = "local_nightscout_devicestatus",
            payload = nestedDeviceStatus() + mapOf("uploader" to mapOf("battery" to 90))
        )
        val repeated = prepareLocalNightscoutDeviceStatus(
            timestamp = 60_001L,
            source = "local_nightscout_devicestatus",
            payload = nestedDeviceStatus() + mapOf("uploader" to mapOf("battery" to 89))
        )

        assertThat(policy.shouldInvalidateDeviceStatus(0, initial.telemetryRows, initial.relevanceSignature))
            .isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(0, repeated.telemetryRows, repeated.relevanceSignature))
            .isFalse()
    }

    @Test
    fun acceptedRuntimeConfidenceAndSourceChangesInvalidateWithoutTimestampHeartbeatNoise() {
        val policy = LocalNightscoutInvalidationPolicy()
        val initial = listOf(
            sample("iob_units", 1.0),
            sample("iob_runtime_confidence", 0.8),
            sample("iob_runtime_source_code", 1.0)
        )
        val confidenceChanged = listOf(
            sample("iob_units", 1.0, timestamp = 2L),
            sample("iob_runtime_confidence", 0.9, timestamp = 2L),
            sample("iob_runtime_source_code", 1.0, timestamp = 2L)
        )
        val sourceChanged = listOf(
            sample("iob_units", 1.0, timestamp = 3L),
            sample("iob_runtime_confidence", 0.9, timestamp = 3L),
            sample("iob_runtime_source_code", 2.0, timestamp = 3L)
        )
        val timestampOnly = sourceChanged.map { it.copy(timestamp = 4L, id = "${it.key}-4") }

        assertThat(policy.shouldInvalidateDeviceStatus(0, initial)).isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(0, confidenceChanged)).isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(0, sourceChanged)).isTrue()
        assertThat(policy.shouldInvalidateDeviceStatus(0, timestampOnly)).isFalse()
    }

    @Test
    fun persistenceSuccessInvalidatesAfterCommitWhileNoopAndFailureDoNot() = runTest {
        val order = mutableListOf<String>()

        val success = persistLocalNightscoutClinicalInput(
            persistence = {
                order += "persisted"
                1
            },
            shouldInvalidate = { it > 0 },
            onClinicalInputPersisted = { order += "invalidated" }
        )
        val noop = persistLocalNightscoutClinicalInput(
            persistence = { 0 },
            shouldInvalidate = { it > 0 },
            onClinicalInputPersisted = { order += "unexpected" }
        )
        val failure = runCatching {
            persistLocalNightscoutClinicalInput(
                persistence = { error("db_failed") },
                shouldInvalidate = { true },
                onClinicalInputPersisted = { order += "unexpected" }
            )
        }

        assertThat(success).isEqualTo(1)
        assertThat(noop).isEqualTo(0)
        assertThat(order).containsExactly("persisted", "invalidated").inOrder()
        assertThat(failure.exceptionOrNull()).hasMessageThat().isEqualTo("db_failed")
    }

    @Test
    fun successfulEntryCallbacksHaveNoSecondLocalDebounce() = runTest {
        var callbacks = 0

        repeat(2) {
            val appliedCount: Int = persistLocalNightscoutClinicalInput(
                persistence = { 1 },
                shouldInvalidate = { persisted -> persisted > 0 },
                onClinicalInputPersisted = { callbacks++ }
            )
            assertThat(appliedCount).isEqualTo(1)
        }

        assertThat(callbacks).isEqualTo(2)
    }

    private fun sample(
        key: String,
        value: Double,
        timestamp: Long = 1L
    ) = TelemetrySampleEntity(
        id = "$key-$timestamp",
        timestamp = timestamp,
        source = "local_nightscout_devicestatus",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = null,
        quality = "OK"
    )

    private fun nestedDeviceStatus(): Map<String, Any?> = mapOf(
        "created_at" to "2026-08-28T10:00:00Z",
        "openaps" to mapOf(
            "iob" to mapOf(
                "iob" to 1.0,
                "netIob" to 0.2,
                "bolusIob" to 0.3,
                "basalIob" to -0.1,
                "activity" to 0.01,
                "effectivePositiveIob" to 0.3,
                "confidence" to 0.8,
                "source" to "aaps",
                "fallbackReason" to "none",
                "timestamp" to 1L
            )
        )
    )
}
