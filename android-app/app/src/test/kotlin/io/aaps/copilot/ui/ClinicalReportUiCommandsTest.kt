package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.ClinicalDataQuality
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalReportFailureReason
import io.aaps.copilot.data.repository.ClinicalReportRunDisposition
import io.aaps.copilot.data.repository.ClinicalReportState
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClinicalReportUiCommandsTest {

    @Test
    fun openingAndPrepareNeverStartRemoteRequest() = runTest {
        val fixture = Fixture()

        fixture.commands.state.value
        fixture.commands.state.value
        fixture.commands.prepare()

        assertThat(fixture.prepareCalls.get()).isEqualTo(1)
        assertThat(fixture.startCalls.get()).isEqualTo(0)
    }

    @Test
    fun explicitSendStartsExactlyOneNonForcedRequest() = runTest {
        val fixture = Fixture()
        fixture.state.value = ClinicalReportState.LocalReady(
            requestId = "internal-request",
            local = localReport()
        )

        val identity = "a".repeat(64)
        assertThat(fixture.commands.send(identity)).isTrue()

        assertThat(fixture.startCalls.get()).isEqualTo(1)
        assertThat(fixture.forceValues).containsExactly(false)
        assertThat(fixture.expectedConfigIdentities).containsExactly(identity)
        assertThat(fixture.prepareCalls.get()).isEqualTo(0)
    }

    @Test
    fun sendAndForceRetryDoNotBuildOrUploadWithoutVisibleLocalSummary() = runTest {
        val fixture = Fixture()

        assertThat(fixture.commands.send("a".repeat(64))).isFalse()
        fixture.state.value = ClinicalReportState.Failed(
            requestId = "unknown-without-local",
            local = null,
            reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
        )
        assertThat(fixture.commands.retryUnknown(confirmed = true)).isNull()

        assertThat(fixture.startCalls.get()).isEqualTo(0)
        assertThat(fixture.prepareCalls.get()).isEqualTo(0)
    }

    @Test
    fun cancelDispatchesExactlyOnce() = runTest {
        val fixture = Fixture()

        fixture.commands.cancel()

        assertThat(fixture.cancelCalls.get()).isEqualTo(1)
        assertThat(fixture.startCalls.get()).isEqualTo(0)
    }

    @Test
    fun forceRetryRequiresUnknownOutcomeAndExplicitConfirmation() = runTest {
        val fixture = Fixture()

        assertThat(fixture.commands.retryUnknown(confirmed = true)).isNull()
        fixture.state.value = ClinicalReportState.Failed(
            requestId = "internal-request",
            local = localReport(),
            reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
        )
        assertThat(fixture.commands.retryUnknown(confirmed = false)).isNull()
        assertThat(fixture.startCalls.get()).isEqualTo(0)

        assertThat(fixture.commands.retryUnknown(confirmed = true))
            .isEqualTo(ClinicalReportRunDisposition.REMOTE_STARTED)
        assertThat(fixture.startCalls.get()).isEqualTo(1)
        assertThat(fixture.forceValues).containsExactly(true)
        assertThat(fixture.expectedConfigIdentities).containsExactly(null)
    }

    @Test
    fun forceRetryReturnsInFlightDispositionInsteadOfReportingRemoteStarted() = runTest {
        val fixture = Fixture(
            startDisposition = ClinicalReportRunDisposition.REMOTE_IN_FLIGHT
        )
        fixture.state.value = ClinicalReportState.Failed(
            requestId = "internal-request",
            local = localReport(),
            reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
        )

        val disposition = fixture.commands.retryUnknown(confirmed = true)

        assertThat(disposition)
            .isEqualTo(ClinicalReportRunDisposition.REMOTE_IN_FLIGHT)
        assertThat(fixture.startCalls.get()).isEqualTo(1)
    }

    private fun localReport(): ClinicalLocalReport {
        fun summary(days: Int) = ClinicalPeriodSummary(
            days = days,
            fromTs = 1L,
            throughTs = 2L,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 10.0,
            timeBelow4Pct = 0.0,
            timeInRangePct = 100.0,
            timeAboveRangePct = 0.0,
            totalInsulinU = 1.0,
            totalCarbsG = 1.0,
            meanTargetMmol = 6.0,
            weekdayPattern = emptyList(),
            weekendPattern = emptyList(),
            quality = ClinicalDataQuality(
                expectedBuckets = 1,
                coveredBuckets = 1,
                missingBuckets = 0,
                maxGapMinutes = 5
            )
        )
        return ClinicalLocalReport(
            summary7d = summary(7),
            summary30d = summary(30),
            requestHash = "internal",
            generatedAt = 2L,
            zoneId = "UTC"
        )
    }

    private class Fixture(
        private val startDisposition: ClinicalReportRunDisposition =
            ClinicalReportRunDisposition.REMOTE_STARTED
    ) {
        val state = MutableStateFlow<ClinicalReportState>(ClinicalReportState.Idle)
        val prepareCalls = AtomicInteger()
        val startCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val forceValues = mutableListOf<Boolean>()
        val expectedConfigIdentities = mutableListOf<String?>()

        val commands = ClinicalReportUiCommands(
            state = state,
            clock = { 1_800_000_000_000L },
            zoneId = { ZoneId.of("UTC") },
            prepareLocal = { _, _ ->
                prepareCalls.incrementAndGet()
            },
            start = { _, _, force, expectedConfigIdentity ->
                startCalls.incrementAndGet()
                forceValues += force
                expectedConfigIdentities += expectedConfigIdentity
                startDisposition
            },
            cancelActive = {
                cancelCalls.incrementAndGet()
            }
        )
    }
}
