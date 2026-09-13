package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.LocalEventImpactAnalyzer
import io.aaps.copilot.domain.isfcr.IsfCrModelState
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import org.junit.Test

class IsfCrRepositoryRealtimeFilterTest {

    @Test
    fun `realtime snapshot is valid only for the base model used to build it`() {
        val bound = bindRealtimeSnapshotToInputGeneration(
            snapshot = realtimeSnapshot(isf = 2.03, cr = 9.5, suffix = "bound"),
            modelUpdatedAt = 1234L,
            profileTimestamp = 5678L
        )

        assertThat(realtimeSnapshotMatchesInputGeneration(bound, 1234L, 5678L)).isTrue()
        assertThat(realtimeSnapshotMatchesInputGeneration(bound, 1235L, 5678L)).isFalse()
        assertThat(realtimeSnapshotMatchesInputGeneration(bound, 1234L, 5679L)).isFalse()
        val missingGenerationFailure = runCatching {
            bindRealtimeSnapshotToInputGeneration(
                snapshot = realtimeSnapshot(isf = 2.03, cr = 9.5, suffix = "no-model"),
                modelUpdatedAt = null,
                profileTimestamp = null
            )
        }.exceptionOrNull()
        assertThat(missingGenerationFailure).isNotNull()
        assertThat(missingGenerationFailure).hasMessageThat().contains("input generation")
        assertThat(
            realtimeSnapshotMatchesInputGeneration(
                realtimeSnapshot(isf = 2.03, cr = 9.5, suffix = "zero-generation").copy(
                    factors = mapOf(
                        ISFCR_BASE_MODEL_REVISION_FACTOR to 0.0,
                        ISFCR_PROFILE_REVISION_FACTOR to 0.0
                    )
                ),
                null,
                null
            )
        ).isFalse()
        assertThat(
            realtimeSnapshotMatchesInputGeneration(
                realtimeSnapshot(isf = 2.03, cr = 9.5, suffix = "legacy"),
                1234L,
                5678L
            )
        ).isFalse()
    }

    @Test
    fun `malformed base model revisions fail closed`() {
        val snapshot = realtimeSnapshot(isf = 2.03, cr = 9.5, suffix = "malformed")

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 12.5).forEach { invalid ->
            assertThat(
                realtimeSnapshotMatchesInputGeneration(
                    snapshot.copy(
                        factors = snapshot.factors + mapOf(
                            ISFCR_BASE_MODEL_REVISION_FACTOR to invalid,
                            ISFCR_PROFILE_REVISION_FACTOR to 34.0
                        )
                    ),
                    12L,
                    34L
                )
            ).isFalse()
        }
    }

    @Test
    fun `isf revision state merge preserves existing cr model`() {
        val existing = IsfCrModelState(
            updatedAt = 1L,
            hourlyIsf = List(24) { 6.9 },
            hourlyCr = List(24) { 9.5 },
            params = mapOf(
                "weekday_isf_0" to 6.9,
                "weekday_cr_0" to 9.5,
                "confidenceThreshold" to 0.55
            ),
            fitMetrics = mapOf(
                "isfEvidenceCount" to 450.0,
                "crEvidenceCount" to 30.0,
                "crCoverageHours" to 12.0
            )
        )
        val refitted = IsfCrModelState(
            updatedAt = 2L,
            hourlyIsf = List(24) { 2.03 },
            hourlyCr = List(24) { 20.0 },
            params = mapOf(
                "weekday_isf_0" to 2.03,
                "weekday_cr_0" to 20.0,
                "confidenceThreshold" to 0.60
            ),
            fitMetrics = mapOf(
                "isfEvidenceCount" to 0.0,
                "crEvidenceCount" to 1.0,
                "crCoverageHours" to 1.0
            )
        )

        val merged = mergeIsfRevisionModelState(refitted = refitted, existing = existing)

        assertThat(merged.hourlyIsf).containsExactlyElementsIn(refitted.hourlyIsf).inOrder()
        assertThat(merged.hourlyCr).containsExactlyElementsIn(existing.hourlyCr).inOrder()
        assertThat(merged.params.getValue("weekday_isf_0")).isEqualTo(2.03)
        assertThat(merged.params.getValue("weekday_cr_0")).isEqualTo(9.5)
        assertThat(merged.fitMetrics.getValue("isfEvidenceCount")).isEqualTo(0.0)
        assertThat(merged.fitMetrics.getValue("crEvidenceCount")).isEqualTo(30.0)
        assertThat(merged.fitMetrics.getValue("crCoverageHours")).isEqualTo(12.0)
    }

    @Test
    fun `isf revision snapshot merge preserves existing cr runtime`() {
        val existing = realtimeSnapshot(isf = 6.9, cr = 9.5, suffix = "existing")
        val refitted = realtimeSnapshot(isf = 2.03, cr = 20.0, suffix = "refitted")

        val merged = mergeIsfRevisionRealtimeSnapshot(refitted = refitted, existing = existing)

        assertThat(merged.isfEff).isEqualTo(refitted.isfEff)
        assertThat(merged.isfBase).isEqualTo(refitted.isfBase)
        assertThat(merged.crEff).isEqualTo(existing.crEff)
        assertThat(merged.crBase).isEqualTo(existing.crBase)
        assertThat(merged.ciCrLow).isEqualTo(existing.ciCrLow)
        assertThat(merged.ciCrHigh).isEqualTo(existing.ciCrHigh)
        assertThat(merged.crEvidenceCount).isEqualTo(existing.crEvidenceCount)
    }

    @Test
    fun `delivery window includes crossing episode and closes it at recovery`() {
        val hour = 60L * 60L * 1_000L
        val fromTs = 10L * hour
        val recoveryTs = fromTs + 10L * 60L * 1_000L

        val events = deliveryDiagnosticEventsForWindow(
            rows = listOf(
                deliveryValue(fromTs - 2L * hour, DeliveryTrustState.SUSPECTED_NONRESPONSE, "pump-a"),
                deliveryValue(recoveryTs, DeliveryTrustState.NORMAL, "pump-a")
            ),
            fromTs = fromTs,
            throughTs = fromTs + hour
        )

        assertThat(events).hasSize(1)
        assertThat(events.single().startTs).isEqualTo(fromTs - 2L * hour)
        assertThat(events.single().endTs).isEqualTo(recoveryTs)
    }

    @Test
    fun `delivery window excludes episode wholly before requested range`() {
        val hour = 60L * 60L * 1_000L
        val fromTs = 10L * hour

        val events = deliveryDiagnosticEventsForWindow(
            rows = listOf(
                deliveryValue(fromTs - 3L * hour, DeliveryTrustState.SUSPECTED_NONRESPONSE, "pump-a"),
                deliveryValue(fromTs - 2L * hour, DeliveryTrustState.NORMAL, "pump-a")
            ),
            fromTs = fromTs,
            throughTs = fromTs + hour
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `delivery window keeps recovery isolated by source`() {
        val hour = 60L * 60L * 1_000L
        val fromTs = 10L * hour

        val events = deliveryDiagnosticEventsForWindow(
            rows = listOf(
                deliveryValue(fromTs - 2L * hour, DeliveryTrustState.SUSPECTED_NONRESPONSE, "pump-a"),
                deliveryValue(fromTs + 10L * 60L * 1_000L, DeliveryTrustState.NORMAL, "pump-b")
            ),
            fromTs = fromTs,
            throughTs = fromTs + hour
        )

        assertThat(events).hasSize(1)
        assertThat(events.single().localId)
            .isEqualTo(stableDeliveryEventLocalId("pump-a", fromTs - 2L * hour))
        assertThat(events.single().endTs).isEqualTo(fromTs + 2L * hour)
    }

    @Test
    fun `delivery diagnostic lookback is overflow safe near zero`() {
        val fourHours = 4L * 60L * 60L * 1_000L

        assertThat(deliveryDiagnosticLookbackFrom(Long.MIN_VALUE)).isEqualTo(0L)
        assertThat(deliveryDiagnosticLookbackFrom(0L)).isEqualTo(0L)
        assertThat(deliveryDiagnosticLookbackFrom(1L)).isEqualTo(0L)
        assertThat(deliveryDiagnosticLookbackFrom(fourHours)).isEqualTo(0L)
        assertThat(deliveryDiagnosticLookbackFrom(fourHours + 1L)).isEqualTo(1L)
        assertThat(deliveryDiagnosticLookbackFrom(Long.MAX_VALUE))
            .isEqualTo(Long.MAX_VALUE - fourHours)
    }

    @Test
    fun `persisted suspected nonresponse projects into the isfcr infusion gate`() {
        val events = deliveryDiagnosticEventsFromTelemetry(
            listOf(
                deliveryRow(1L, DeliveryTrustState.NORMAL),
                deliveryRow(2L, DeliveryTrustState.SUSPECTED_NONRESPONSE)
            )
        )

        assertThat(events).hasSize(1)
        assertThat(events.single().type).isEqualTo(CompensationEventType.INFUSION_PUMP_INSULIN)
        assertThat(LocalEventImpactAnalyzer().impact(events.single()).infusionGateBlocked).isTrue()
    }

    @Test
    fun `versioned delivery telemetry wins over legacy at the same source timestamp`() {
        val events = deliveryDiagnosticEventsFromTelemetry(
            listOf(
                deliveryRow(1L, DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY, 2.0, "legacy"),
                deliveryRow(1L, DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY, 110.0, "stable")
            )
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `invalid versioned delivery telemetry fails closed without legacy fallback`() {
        val events = deliveryDiagnosticEventsFromTelemetry(
            listOf(
                deliveryRow(1L, DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY, 2.0, "legacy"),
                deliveryRow(1L, DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY, 120.5, "invalid-stable")
            )
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `legacy delivery ordinals are accepted only from the historical copilot source`() {
        val foreignLegacy = TelemetrySampleEntity(
            id = "foreign-legacy",
            timestamp = 1L,
            source = "aaps_import",
            key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
            valueDouble = 2.0,
            valueText = null,
            unit = null,
            quality = "OK"
        )

        assertThat(deliveryDiagnosticEventsFromTelemetry(listOf(foreignLegacy))).isEmpty()
    }

    @Test
    fun `conflicting duplicate versioned values are unavailable`() {
        val events = deliveryDiagnosticEventsFromTelemetry(
            listOf(
                deliveryRow(1L, DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY, 120.0, "suspect"),
                deliveryRow(1L, DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY, 100.0, "normal")
            )
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `realtime repository path keeps either valid component when its sibling is nested`() {
        val rows = listOf(
            therapyRow(
                id = "carb-only",
                payload =
                    """{"units":{"value":3},"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
            ),
            therapyRow(
                id = "insulin-only",
                payload =
                    """{"units":3,"carbs":90,"aapsCarbAmount":[24],"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
            ),
            therapyRow(
                id = "neither",
                payload = """{"units":{"value":3},"carbs":[24]}"""
            )
        )

        val components = sanitizeRealtimeIsfCrTherapyRows(rows, Gson())
            .map { resolveTherapyComponents(it.type, it.payload) }

        assertThat(components).hasSize(2)
        assertThat(components[0].insulinU).isNull()
        assertThat(components[0].carbsG).isEqualTo(24.0)
        assertThat(components[1].insulinU).isEqualTo(3.0)
        assertThat(components[1].carbsG).isNull()
    }

    @Test
    fun `realtime filter keeps only isfcr relevant therapy events`() {
        val tempTarget = TherapyEvent(
            ts = 1L,
            type = "temp_target",
            payload = mapOf("targetBottom" to "90")
        )
        val correctionBolus = TherapyEvent(
            ts = 2L,
            type = "Bolus",
            payload = mapOf("enteredInsulin" to "2.5")
        )
        val carbs = TherapyEvent(
            ts = 3L,
            type = "carbs",
            payload = mapOf("grams" to "24")
        )
        val setChange = TherapyEvent(
            ts = 4L,
            type = "infusion_set_change",
            payload = emptyMap()
        )

        assertThat(isRelevantRealtimeIsfCrTherapyEvent(tempTarget)).isFalse()
        assertThat(isRelevantRealtimeIsfCrTherapyEvent(correctionBolus)).isTrue()
        assertThat(isRelevantRealtimeIsfCrTherapyEvent(carbs)).isTrue()
        assertThat(isRelevantRealtimeIsfCrTherapyEvent(setChange)).isTrue()
    }

    @Test
    fun `realtime therapy scan widens when raw rows are saturated by temp targets`() {
        assertThat(
            shouldWidenRealtimeTherapyScan(
                rawRowCount = 720,
                relevantRowCount = 6,
                currentLimit = 720
            )
        ).isTrue()
        assertThat(
            shouldWidenRealtimeTherapyScan(
                rawRowCount = 300,
                relevantRowCount = 6,
                currentLimit = 720
            )
        ).isFalse()
        assertThat(
            shouldWidenRealtimeTherapyScan(
                rawRowCount = 720,
                relevantRowCount = 40,
                currentLimit = 720
            )
        ).isFalse()
    }

    private fun therapyRow(id: String, payload: String) = TherapyEventEntity(
        id = id,
        timestamp = 100_000L,
        type = "meal_bolus",
        payloadJson = payload
    )

    private fun realtimeSnapshot(isf: Double, cr: Double, suffix: String) = IsfCrRealtimeSnapshot(
        id = suffix,
        ts = 100L,
        isfEff = isf,
        crEff = cr,
        isfBase = isf,
        crBase = cr,
        ciIsfLow = isf - 0.5,
        ciIsfHigh = isf + 0.5,
        ciCrLow = cr - 0.5,
        ciCrHigh = cr + 0.5,
        confidence = 0.6,
        qualityScore = 0.8,
        factors = emptyMap(),
        mode = IsfCrRuntimeMode.ACTIVE,
        isfEvidenceCount = 20,
        crEvidenceCount = 30,
        reasons = emptyList()
    )

    private fun deliveryRow(timestamp: Long, state: DeliveryTrustState) = deliveryRow(
        timestamp = timestamp,
        key = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
        value = when (state) {
            DeliveryTrustState.NORMAL -> 0.0
            DeliveryTrustState.WATCH -> 1.0
            DeliveryTrustState.SUSPECTED_NONRESPONSE -> 2.0
            DeliveryTrustState.UNKNOWN -> 3.0
        },
        suffix = state.name
    )

    private fun deliveryRow(timestamp: Long, key: String, value: Double, suffix: String) = TelemetrySampleEntity(
        id = "delivery-$timestamp-$suffix",
        timestamp = timestamp,
        source = "copilot_runtime_isfcr",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = null,
        quality = "OK"
    )

    private fun deliveryValue(
        timestamp: Long,
        state: DeliveryTrustState,
        source: String
    ) = DeliveryTrustTelemetryValue(
        timestamp = timestamp,
        source = source,
        key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
        value = DeliveryTrustStateWireCodec.encode(state)
    )
}
