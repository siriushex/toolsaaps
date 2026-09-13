package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.ui.foundation.screens.MetricRuntimeAvailabilityUi
import io.aaps.copilot.ui.foundation.screens.MetricRuntimeSourceUi
import io.aaps.copilot.ui.foundation.screens.OverviewUiState
import io.aaps.copilot.ui.foundation.screens.ScreenLoadState
import io.aaps.copilot.ui.foundation.screens.withSensitivityApplyPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SensitivitySourceApplyCoordinatorTest {

    @Test
    fun concurrentIsfAndCrRequestsSerializeAndApplyingNeverCompletesBetweenThem() = runTest {
        val coordinator = SensitivitySourceApplyCoordinator()
        val firstGate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val observedApplying = mutableListOf<Boolean>()
        val collector = launch { coordinator.state.collect { observedApplying += it.applying } }

        val isf = async {
            coordinator.run("ISF", "AAPS") {
                order += "isf-start"
                firstGate.await()
                order += "isf-end"
                snapshot(2L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.COPILOT)
            }
        }
        runCurrent()
        val cr = async {
            coordinator.run("CR", "EVIDENCE") {
                order += "cr-start"
                order += "cr-end"
                snapshot(3L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.EVIDENCE)
            }
        }
        runCurrent()
        assertThat(coordinator.state.value.applying).isTrue()
        firstGate.complete(Unit)
        isf.await()
        cr.await()
        runCurrent()
        collector.cancelAndJoin()

        assertThat(order).containsExactly("isf-start", "isf-end", "cr-start", "cr-end").inOrder()
        val firstApplying = observedApplying.indexOf(true)
        assertThat(observedApplying.drop(firstApplying).dropLast(1)).doesNotContain(false)
        assertThat(coordinator.state.value.applying).isTrue()
        coordinator.presentationStateForOverview(
            overview(snapshot(3L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.EVIDENCE))
        )
        assertThat(coordinator.state.value.applying).isFalse()
    }

    @Test
    fun failedLastOperationPublishesExplicitErrorAfterQueueSettles() = runTest {
        val coordinator = SensitivitySourceApplyCoordinator()

        val error = runCatching {
            coordinator.run("CR", "COPILOT") { error("cycle rejected") }
        }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("cycle rejected")
        assertThat(coordinator.state.value.applying).isFalse()
        assertThat(coordinator.state.value.error).contains("cycle rejected")
        assertThat(coordinator.state.value.pendingMetric).isEqualTo("CR")
        assertThat(coordinator.state.value.pendingValue).isEqualTo("COPILOT")
        assertThat(
            sensitivitySourceSelection(
                acceptedSource = "EVIDENCE",
                metric = "CR",
                apply = coordinator.state.value
            )
        ).isEqualTo("COPILOT")
    }

    @Test
    fun unrelatedMetricKeepsItsLastAcceptedSelectionDuringForwardRecovery() = runTest {
        val coordinator = SensitivitySourceApplyCoordinator()
        runCatching {
            coordinator.run("ISF", "AAPS") { error("forecast unavailable") }
        }

        assertThat(
            sensitivitySourceSelection(
                acceptedSource = "COPILOT",
                metric = "CR",
                apply = coordinator.state.value
            )
        ).isEqualTo("COPILOT")
    }

    @Test
    fun processRestartShowsAuthoritativeSelectionWhileAcceptedRuntimeIsUnavailable() {
        assertThat(
            sensitivitySourceSelection(
                acceptedSource = "UNAVAILABLE",
                authoritativeSource = "AAPS",
                metric = "ISF",
                apply = SensitivitySourceApplyUiState()
            )
        ).isEqualTo("AAPS")
    }

    @Test
    fun cancellingQueuedRequestDoesNotClearActiveApplyingState() = runTest {
        val coordinator = SensitivitySourceApplyCoordinator()
        val firstGate = CompletableDeferred<Unit>()
        val active = launch {
            coordinator.run("ISF", "AAPS") {
                firstGate.await()
                snapshot(2L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.COPILOT)
            }
        }
        runCurrent()
        val queued = launch {
            coordinator.run("CR", "EVIDENCE") {
                snapshot(3L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.EVIDENCE)
            }
        }
        runCurrent()

        queued.cancelAndJoin()
        assertThat(coordinator.state.value.applying).isTrue()
        firstGate.complete(Unit)
        active.join()

        assertThat(coordinator.state.value.applying).isTrue()
        coordinator.presentationStateForOverview(
            overview(snapshot(2L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.COPILOT))
        )
        assertThat(coordinator.state.value.applying).isFalse()
    }

    @Test
    fun activeErrorAndQueuedCancellationCannotStrandApplyingState() = runTest {
        val releaseReached = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val coordinator = SensitivitySourceApplyCoordinator(
            beforeAttemptRelease = {
                releaseReached.complete(Unit)
                allowRelease.await()
            }
        )
        val failActive = CompletableDeferred<Unit>()
        val active = async {
            runCatching {
                coordinator.run("ISF", "AAPS") {
                    failActive.await()
                    error("active rejected")
                }
            }
        }
        runCurrent()
        val queued = launch {
            coordinator.run("CR", "EVIDENCE") {
                snapshot(3L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.EVIDENCE)
            }
        }
        runCurrent()

        failActive.complete(Unit)
        releaseReached.await()
        queued.cancelAndJoin()
        allowRelease.complete(Unit)
        active.await()
        runCurrent()

        assertThat(coordinator.state.value.applying).isFalse()
        assertThat(coordinator.state.value.error).contains("active rejected")
        assertThat(coordinator.state.value.expectedSettingsRevision).isNull()
    }

    @Test
    fun activeCancellationAndQueuedCancellationCannotStrandApplyingState() = runTest {
        val releaseReached = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val coordinator = SensitivitySourceApplyCoordinator(
            beforeAttemptRelease = {
                releaseReached.complete(Unit)
                allowRelease.await()
            }
        )
        val activeStarted = CompletableDeferred<Unit>()
        val keepActive = CompletableDeferred<Unit>()
        val active = launch {
            coordinator.run("ISF", "AAPS") {
                activeStarted.complete(Unit)
                keepActive.await()
                snapshot(2L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.COPILOT)
            }
        }
        activeStarted.await()
        val queued = launch {
            coordinator.run("CR", "EVIDENCE") {
                snapshot(3L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.EVIDENCE)
            }
        }
        runCurrent()

        active.cancel()
        releaseReached.await()
        queued.cancelAndJoin()
        allowRelease.complete(Unit)
        active.join()
        runCurrent()

        assertThat(coordinator.state.value.applying).isFalse()
        assertThat(coordinator.state.value.error).isNull()
        assertThat(coordinator.state.value.expectedSettingsRevision).isNull()
    }

    @Test
    fun operationCompletionKeepsPreviousTupleMaskedUntilOverviewObservesExpectedIdentity() = runTest {
        val coordinator = SensitivitySourceApplyCoordinator()
        val previous = snapshot(10L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.COPILOT)
        val accepted = snapshot(11L, SensitivitySourcePreference.EVIDENCE, SensitivitySourcePreference.COPILOT)
        val overviewStates = MutableStateFlow(overview(previous))
        val rendered = mutableListOf<OverviewUiState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(overviewStates, coordinator.state) { overview, apply ->
                val presentation = coordinator.presentationStateForOverview(overview, apply)
                overview.withSensitivityApplyPresentation(
                    applying = presentation.applying,
                    pendingMetric = presentation.pendingMetric,
                    pendingValue = presentation.pendingValue,
                    error = presentation.error
                )
            }.collect(rendered::add)
        }
        runCurrent()

        coordinator.run("ISF", "EVIDENCE") { accepted }
        runCurrent()

        assertThat(coordinator.state.value.applying).isTrue()
        assertThat(coordinator.state.value.expectedSettingsRevision).isEqualTo(accepted.settingsRevision)
        assertThat(coordinator.state.value.expectedIsfSource).isEqualTo("EVIDENCE")
        assertThat(coordinator.state.value.expectedCrSource).isEqualTo("COPILOT")
        assertThat(rendered.last().currentIsfMmolPerUnit).isNull()
        assertThat(rendered.last().currentCrGramsPerUnit).isNull()

        overviewStates.value = overview(previous)
        runCurrent()
        assertThat(rendered.last().currentIsfMmolPerUnit).isNull()

        overviewStates.value = overview(accepted)
        runCurrent()

        assertThat(rendered.last().currentIsfMmolPerUnit).isEqualTo(accepted.isf.effective)
        assertThat(rendered.last().currentCrGramsPerUnit).isEqualTo(accepted.cr.effective)
        assertThat(coordinator.state.value.applying).isFalse()
        val postCompletion = rendered.drop(1)
        assertThat(postCompletion.none { it.currentIsfMmolPerUnit == previous.isf.effective }).isTrue()
        collector.cancel()
    }

    private fun snapshot(
        revision: Long,
        isfSource: SensitivitySourcePreference,
        crSource: SensitivitySourcePreference
    ): SensitivityRuntimeSnapshot = SensitivityRuntimeSnapshot(
        settingsRevision = revision,
        forecastCycleId = "cycle-$revision",
        timestamp = 1_800_000_000_000L + revision,
        isf = decision(isfSource, effective = revision.toDouble()),
        cr = decision(crSource, effective = revision.toDouble() + 1.0)
    )

    private fun decision(
        requested: SensitivitySourcePreference,
        effective: Double
    ) = SensitivityMetricDecision(
        requested = requested,
        resolved = when (requested) {
            SensitivitySourcePreference.AAPS -> SensitivityResolvedSource.AAPS
            SensitivitySourcePreference.EVIDENCE -> SensitivityResolvedSource.EVIDENCE_BLEND
            SensitivitySourcePreference.COPILOT -> SensitivityResolvedSource.COPILOT_NATIVE
        },
        rawAaps = effective,
        rawEvidence = effective,
        rawCopilot = effective,
        blended = effective,
        effective = effective,
        confidence = 1.0,
        fallbackReason = null
    )

    private fun overview(snapshot: SensitivityRuntimeSnapshot) = OverviewUiState(
        loadState = ScreenLoadState.READY,
        isStale = false,
        currentIsfMmolPerUnit = snapshot.isf.effective,
        currentCrGramsPerUnit = snapshot.cr.effective,
        isfRuntime = MetricRuntimeSourceUi(
            requested = snapshot.isf.requested.name,
            availability = MetricRuntimeAvailabilityUi.AVAILABLE,
            acceptedSettingsRevision = snapshot.settingsRevision
        ),
        crRuntime = MetricRuntimeSourceUi(
            requested = snapshot.cr.requested.name,
            availability = MetricRuntimeAvailabilityUi.AVAILABLE,
            acceptedSettingsRevision = snapshot.settingsRevision
        )
    )
}
