package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import org.junit.Test

class SyncRepositoryDeviceStatusRecoveryTest {

    @Test
    fun skipsDeviceStatusFetchWhenNightscoutPointsAtCopilotLoopback() {
        assertThat(
            SyncRepository.shouldFetchRemoteDeviceStatus(
                loopbackUrl = true,
                degradedMode = false
            )
        ).isFalse()
        assertThat(
            SyncRepository.shouldFetchRemoteDeviceStatus(
                loopbackUrl = false,
                degradedMode = false
            )
        ).isTrue()
    }

    @Test
    fun smallPositiveIobJumpBecomesRecoveredMicrobolus() {
        val event = SyncRepository.buildIobDerivedInsulinEventStatic(
            ts = 100_000L,
            prevIob = 0.01,
            currIob = 0.05,
            dtMin = 5.0
        )

        assertThat(event).isNotNull()
        assertThat(event!!.id).startsWith("iob-mb-rec-")
        assertThat(event.payload["classification"]).isEqualTo("RECOVERED_MICROBOLUS")
        assertThat(event.payload["method"]).isEqualTo("iob_micro_jump")
        assertThat(event.payload["source"]).isEqualTo("aaps_iob_microbolus")
        assertThat(event.payload["inferred"]).isNull()
        assertThat(event.payload["recovered"]).isEqualTo("true")
    }

    @Test
    fun largePositiveIobJumpStaysClassicInferredIob() {
        val event = SyncRepository.buildIobDerivedInsulinEventStatic(
            ts = 100_000L,
            prevIob = 0.1,
            currIob = 0.8,
            dtMin = 5.0
        )

        assertThat(event).isNotNull()
        assertThat(event!!.id).startsWith("iob-inf-")
        assertThat(event.payload["classification"]).isEqualTo("INFERRED_IOB")
        assertThat(event.payload["method"]).isEqualTo("iob_jump")
        assertThat(event.payload["source"]).isEqualTo("aaps_ns_iob")
        assertThat(event.payload["inferred"]).isEqualTo("true")
        assertThat(event.payload["recovered"]).isNull()
    }

    @Test
    fun upstreamDeviceStatusTelemetryWinsOverLocalMirror() {
        val rows = listOf(
            telemetry(1_000L, "insulin_units", 0.5, source = "local_nightscout_devicestatus"),
            telemetry(2_000L, "insulin_units", 0.7, source = "nightscout_devicestatus")
        )

        val selected = SyncRepository.selectDeviceStatusTherapyTelemetryRowsStatic(rows)

        assertThat(selected).hasSize(1)
        assertThat(selected.single().source).isEqualTo("nightscout_devicestatus")
        assertThat(selected.single().valueDouble).isEqualTo(0.7)
    }

    @Test
    fun localMirrorIsUsedOnlyWhenUpstreamRowsAreMissing() {
        val rows = listOf(
            telemetry(1_000L, "insulin_units", 0.5, source = "local_nightscout_devicestatus"),
            telemetry(2_000L, "carbs_grams", 8.0, source = "local_nightscout_devicestatus")
        )

        val selected = SyncRepository.selectDeviceStatusTherapyTelemetryRowsStatic(rows)

        assertThat(selected).hasSize(2)
        assertThat(selected.map { it.source }.distinct()).containsExactly("local_nightscout_devicestatus")
    }

    @Test
    fun repeatedInsulinTelemetryCreatesSingleCorrectionBolus() {
        val rows = listOf(
            telemetry(1_000L, "insulin_units", 0.534),
            telemetry(2_000L, "insulin_units", 0.534),
            telemetry(3_000L, "insulin_units", 0.534)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(1)
        val event = recovered.single()
        assertThat(event.type).isEqualTo("correction_bolus")
        assertThat(event.timestamp).isEqualTo(1_000L)
        assertThat(event.id).contains("correction_bolus")
        assertThat(event.payloadJson).contains("nightscout_devicestatus_recovered")
        assertThat(event.payloadJson).doesNotContain("\"inferred\":\"true\"")
    }

    @Test
    fun repeatedMealTelemetryCreatesSingleMealBolusAndDoseChangeCreatesNewCluster() {
        val rows = listOf(
            telemetry(10_000L, "insulin_units", 1.2),
            telemetry(10_000L, "carbs_grams", 18.0),
            telemetry(12_000L, "insulin_units", 1.2),
            telemetry(12_000L, "carbs_grams", 18.0),
            telemetry(30_000L, "insulin_units", 2.0),
            telemetry(30_000L, "carbs_grams", 24.0),
            telemetry(32_000L, "insulin_units", 2.0),
            telemetry(32_000L, "carbs_grams", 24.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(2)
        assertThat(recovered.map { it.type }).containsExactly("meal_bolus", "meal_bolus")
        assertThat(recovered.map { it.timestamp }).containsExactly(10_000L, 30_000L)
    }

    @Test
    fun longRepeatedPlateauUsesBoundedWindowInsteadOfStretchingCluster() {
        val rows = listOf(
            telemetry(0L, "insulin_units", 0.534),
            telemetry(0L, "carbs_grams", 6.0),
            telemetry(5 * 60_000L, "insulin_units", 0.534),
            telemetry(5 * 60_000L, "carbs_grams", 6.0),
            telemetry(10 * 60_000L, "insulin_units", 0.534),
            telemetry(10 * 60_000L, "carbs_grams", 6.0),
            telemetry(45 * 60_000L, "insulin_units", 0.534),
            telemetry(45 * 60_000L, "carbs_grams", 6.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(1)
        val payload = recovered.single().payloadJson
        assertThat(payload).contains("\"clusterStartTs\":\"0\"")
        assertThat(payload).contains("\"clusterEndTs\":\"300000\"")
        assertThat(payload).contains("\"boundedWindowMs\":\"300000\"")
    }

    @Test
    fun sameDoseDoesNotRecoverRepeatedlyWithinShortGapWithoutRearm() {
        val rows = listOf(
            telemetry(0L, "insulin_units", 0.8),
            telemetry(0L, "carbs_grams", 12.0),
            telemetry(5 * 60_000L, "insulin_units", 0.8),
            telemetry(5 * 60_000L, "carbs_grams", 12.0),
            telemetry(60 * 60_000L, "insulin_units", 0.8),
            telemetry(60 * 60_000L, "carbs_grams", 12.0),
            telemetry(65 * 60_000L, "insulin_units", 0.8),
            telemetry(65 * 60_000L, "carbs_grams", 12.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(1)
        assertThat(recovered.single().timestamp).isEqualTo(0L)
    }

    @Test
    fun sameDoseCanRecoverAgainAfterMediumRearmGap() {
        val rows = listOf(
            telemetry(0L, "insulin_units", 0.8),
            telemetry(0L, "carbs_grams", 12.0),
            telemetry(5 * 60_000L, "insulin_units", 0.8),
            telemetry(5 * 60_000L, "carbs_grams", 12.0),
            telemetry(2 * 60 * 60_000L, "insulin_units", 0.8),
            telemetry(2 * 60 * 60_000L, "carbs_grams", 12.0),
            telemetry(2 * 60 * 60_000L + 5 * 60_000L, "insulin_units", 0.8),
            telemetry(2 * 60 * 60_000L + 5 * 60_000L, "carbs_grams", 12.0),
            telemetry(5 * 60 * 60_000L, "insulin_units", 0.8),
            telemetry(5 * 60 * 60_000L, "carbs_grams", 12.0),
            telemetry(5 * 60 * 60_000L + 5 * 60_000L, "insulin_units", 0.8),
            telemetry(5 * 60 * 60_000L + 5 * 60_000L, "carbs_grams", 12.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(3)
        assertThat(recovered.map { it.timestamp }).containsExactly(
            0L,
            2 * 60 * 60_000L,
            5 * 60 * 60_000L
        )
    }

    @Test
    fun sameDoseCanRecoverAgainAfterLongRearmGap() {
        val rows = listOf(
            telemetry(0L, "insulin_units", 0.8),
            telemetry(0L, "carbs_grams", 12.0),
            telemetry(5 * 60_000L, "insulin_units", 0.8),
            telemetry(5 * 60_000L, "carbs_grams", 12.0),
            telemetry(13 * 60 * 60_000L, "insulin_units", 0.8),
            telemetry(13 * 60 * 60_000L, "carbs_grams", 12.0),
            telemetry(13 * 60 * 60_000L + 5 * 60_000L, "insulin_units", 0.8),
            telemetry(13 * 60 * 60_000L + 5 * 60_000L, "carbs_grams", 12.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(2)
        assertThat(recovered.map { it.timestamp }).containsExactly(0L, 13 * 60 * 60_000L)
    }

    @Test
    fun sameDoseCanRecoverAgainAfterDifferentSignatureRearmsIt() {
        val rows = listOf(
            telemetry(0L, "insulin_units", 0.8),
            telemetry(0L, "carbs_grams", 12.0),
            telemetry(5 * 60_000L, "insulin_units", 0.8),
            telemetry(5 * 60_000L, "carbs_grams", 12.0),
            telemetry(60 * 60_000L, "insulin_units", 1.4),
            telemetry(60 * 60_000L, "carbs_grams", 20.0),
            telemetry(65 * 60_000L, "insulin_units", 1.4),
            telemetry(65 * 60_000L, "carbs_grams", 20.0),
            telemetry(90 * 60_000L, "insulin_units", 0.8),
            telemetry(90 * 60_000L, "carbs_grams", 12.0),
            telemetry(95 * 60_000L, "insulin_units", 0.8),
            telemetry(95 * 60_000L, "carbs_grams", 12.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).hasSize(3)
        assertThat(recovered.map { it.timestamp }).containsExactly(
            0L,
            60 * 60_000L,
            90 * 60_000L
        )
    }

    @Test
    fun singleTelemetrySampleDoesNotCreateRecoveredTherapy() {
        val rows = listOf(
            telemetry(100_000L, "carbs_grams", 12.0)
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList()
        )

        assertThat(recovered).isEmpty()
    }

    @Test
    fun existingRealTherapyNearClusterSuppressesRecoveredDuplicate() {
        val rows = listOf(
            telemetry(200_000L, "insulin_units", 0.8),
            telemetry(202_000L, "insulin_units", 0.8)
        )
        val existing = listOf(
            TherapyEventEntity(
                id = "real-bolus-1",
                timestamp = 199_000L,
                type = "correction_bolus",
                payloadJson = "{" +
                    "\"source\":\"nightscout_treatment\"," +
                    "\"insulin\":\"0.800\"" +
                    "}"
            )
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = existing
        )

        assertThat(recovered).isEmpty()
    }

    @Test
    fun existingRecoveredTherapyWithSameSignatureSuppressesRepeatedRecoveryWithinRearmWindow() {
        val rows = listOf(
            telemetry(60 * 60_000L, "insulin_units", 0.534),
            telemetry(60 * 60_000L, "carbs_grams", 6.0),
            telemetry(62 * 60_000L, "insulin_units", 0.534),
            telemetry(62 * 60_000L, "carbs_grams", 6.0)
        )
        val existingRecovered = listOf(
            TherapyEventEntity(
                id = "ns-ds-rec-0-meal_bolus-53-60",
                timestamp = 0L,
                type = "meal_bolus",
                payloadJson = "{" +
                    "\"eventType\":\"Meal Bolus\"," +
                    "\"insulin\":\"0.534\"," +
                    "\"enteredInsulin\":\"0.534\"," +
                    "\"carbs\":\"6.0\"," +
                    "\"enteredCarbs\":\"6.0\"," +
                    "\"source\":\"nightscout_devicestatus_recovered\"" +
                    "}"
            )
        )

        val recovered = SyncRepository.buildRecoveredTherapyEventsFromDeviceStatusTelemetryStatic(
            telemetryRows = rows,
            existingTherapyRows = emptyList(),
            existingRecoveredRows = existingRecovered
        )

        assertThat(recovered).isEmpty()
    }

    private fun telemetry(
        ts: Long,
        key: String,
        value: Double,
        source: String = "nightscout_devicestatus"
    ): TelemetrySampleEntity {
        return TelemetrySampleEntity(
            id = "tm-$ts-$key",
            timestamp = ts,
            source = source,
            key = key,
            valueDouble = value,
            valueText = null,
            unit = null,
            quality = "OK"
        )
    }
}
