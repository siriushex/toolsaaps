package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.DeliveryTrustTelemetryValue
import io.aaps.copilot.data.repository.deliveryDiagnosticEventsForWindow
import io.aaps.copilot.data.repository.stableDeliveryEventLocalId
import io.aaps.copilot.service.clinicalEventTherapyLookbackFrom
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import java.io.File
import org.junit.Test

class EventTimelineSourceWiringTest {
    @Test fun overviewAndReportUsePersistedDeliveryDiagnosticsAlongsideCompleteSources() {
        val viewModel = source("io/aaps/copilot/ui/MainViewModel.kt")
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val automation = source("io/aaps/copilot/data/repository/AutomationRepository.kt")
        val isfCrRepository = source("io/aaps/copilot/data/repository/IsfCrRepository.kt")

        assertThat(automation).contains("DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY")
        assertThat(automation).contains("DeliveryTrustStateWireCodec.encode(deliveryTrust)")
        assertThat(automation).doesNotContain("deliveryTrust.ordinal")
        listOf(viewModel).forEach { production ->
            assertThat(production).contains("DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY")
            assertThat(production).contains("DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY")
            assertThat(production).contains("deliveryDiagnosticLookbackFrom")
            assertThat(production).contains("deliveryDiagnosticEventsForWindow")
            assertThat(production).contains("deliveryDiagnostics =")
            assertThat(production).contains("actualActivityEvents")
            assertThat(production).contains("calibrationEvents")
            assertThat(production).contains("plannedActivityEvents")
        }
        assertThat(container).contains("DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS")
        assertThat(container).contains("deliveryDiagnosticEventsForWindow")
        assertThat(container).contains("deliveryDiagnostics =")
        assertThat(container).contains("actualActivityEvents")
        assertThat(container).contains("calibrationEvents")
        assertThat(container).contains("plannedActivityEvents")
        assertThat(isfCrRepository).contains("DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY")
        assertThat(isfCrRepository).contains("DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY")
        assertThat(isfCrRepository).contains("deliveryDiagnosticEventsFromTelemetry")
        assertThat(isfCrRepository).contains("deliveryDiagnostics =")
    }

    @Test fun overviewUsesSubscriptionScopedBoundaryClockInsteadOfFixedCadenceOrAnchor() {
        val viewModel = source("io/aaps/copilot/ui/MainViewModel.kt")
        assertThat(viewModel).contains("boundaryDrivenEventTimeline(")
        assertThat(viewModel).contains("nextEventTimelineBoundaryTs")
        assertThat(viewModel).contains("flatMapLatest")
        assertThat(viewModel).doesNotContain("EVENT_TIMELINE_CLOCK_INTERVAL_MS")
        assertThat(viewModel).doesNotContain("while (true)")
        assertThat(viewModel).doesNotContain("telemetryObserveAnchorTs - EVENT_TIMELINE_PAST_WINDOW_MS")
    }

    @Test fun reportTherapyLookbackCoversFullSevenDayEventEnvelopeOverflowSafely() {
        val day = 24L * 60L * 60L * 1_000L

        assertThat(clinicalEventTherapyLookbackFrom(10L * day)).isEqualTo(3L * day)
        assertThat(clinicalEventTherapyLookbackFrom(7L * day)).isEqualTo(0L)
        assertThat(clinicalEventTherapyLookbackFrom(1L)).isEqualTo(0L)

        val container = source("io/aaps/copilot/service/AppContainer.kt")
        assertThat(container).contains("clinicalEventTherapyLookbackFrom(fromTs)")
    }

    @Test fun boundedDeliveryReadersUseSharedLookbackAndOverlapProjection() {
        val viewModel = source("io/aaps/copilot/ui/MainViewModel.kt")
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val isfCrRepository = source("io/aaps/copilot/data/repository/IsfCrRepository.kt")

        assertThat(viewModel).contains("observeDeliveryTrustInWindow(")
        assertThat(viewModel).contains("fromTs = deliveryDiagnosticLookbackFrom(window.fromTs)")
        assertThat(viewModel).contains("deliveryTelemetryRows = telemetryRows.deliveryRows")
        assertThat(viewModel).doesNotContain("EVENT_TIMELINE_ACTIVITY_LIMIT,\n                    keys = listOf(\n                        \"activity_ratio\",\n                        DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY")
        assertThat(viewModel).contains("deliveryDiagnosticEventsForWindow(")
        assertThat(viewModel).contains("observePhysicalActivityMetric5MinuteBuckets(")
        assertThat(viewModel).contains(
            "keys = listOf(PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY)"
        )
        assertThat(container).contains("deliveryDiagnosticLookbackFrom(fromTs)")
        assertThat(container).contains("deliveryDiagnosticEventsForWindow(")
        assertThat(Regex("deliveryDiagnosticLookbackFrom\\(").findAll(isfCrRepository).count())
            .isAtLeast(2)
        assertThat(Regex("deliveryDiagnosticEventsForWindow\\(").findAll(isfCrRepository).count())
            .isAtLeast(2)
    }

    @Test fun overviewSharedDeliveryProjectionKeepsCrossingAndDropsWhollyPreWindowEpisodes() {
        val hour = 60L * 60L * 1_000L
        val fromTs = 10L * hour

        val events = deliveryDiagnosticEventsForWindow(
            rows = listOf(
                deliveryValue(fromTs - 3L * hour, DeliveryTrustState.SUSPECTED_NONRESPONSE, "old"),
                deliveryValue(fromTs - 2L * hour, DeliveryTrustState.NORMAL, "old"),
                deliveryValue(fromTs - 2L * hour, DeliveryTrustState.SUSPECTED_NONRESPONSE, "crossing"),
                deliveryValue(fromTs + 10L * 60L * 1_000L, DeliveryTrustState.NORMAL, "crossing")
            ),
            fromTs = fromTs,
            throughTs = fromTs + hour
        )

        assertThat(events.map { it.localId })
            .containsExactly(stableDeliveryEventLocalId("crossing", fromTs - 2L * hour))
        assertThat(events.single().endTs).isEqualTo(fromTs + 10L * 60L * 1_000L)
    }

    private fun source(relative: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }) {
            "Unable to locate app module from ${System.getProperty("user.dir")}"
        },
        "src/main/kotlin/$relative"
    ).readText()

    private fun deliveryValue(
        timestamp: Long,
        state: DeliveryTrustState,
        source: String
    ) = DeliveryTrustTelemetryValue(
        timestamp = timestamp,
        source = source,
        key = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
        value = DeliveryTrustStateWireCodec.encode(state).toDouble()
    )
}
