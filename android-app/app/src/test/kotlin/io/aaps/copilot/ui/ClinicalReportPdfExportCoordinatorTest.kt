package io.aaps.copilot.ui

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.ClinicalPdfSourceLease
import io.aaps.copilot.data.repository.ClinicalPdfSourceLeaseResult
import io.aaps.copilot.data.repository.ClinicalPdfSourceCaptureResult
import io.aaps.copilot.data.repository.ClinicalPdfReportIdentity
import io.aaps.copilot.data.repository.ClinicalPdfReportPhase
import io.aaps.copilot.data.repository.ClinicalPdfReprepareResult
import io.aaps.copilot.report.ClinicalPdfContentBlock
import io.aaps.copilot.report.ClinicalPdfContentCursor
import io.aaps.copilot.report.ClinicalPdfContentIdentity
import io.aaps.copilot.report.ClinicalPdfContentRole
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.storage.ClinicalPdfCopyResult
import io.aaps.copilot.storage.ClinicalPdfReadyArtifact
import io.aaps.copilot.storage.ClinicalPdfSharePayload
import io.aaps.copilot.storage.ClinicalPdfShareResult
import io.aaps.copilot.storage.ClinicalPdfStageResult
import io.aaps.copilot.ui.foundation.screens.ClinicalPdfExportUiState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClinicalReportPdfExportCoordinatorTest {

    @Test
    fun reprepareCoordinatorCoalescesConcurrentRequestsAndPreparesOnExactClaim() = runTest {
        val claimStarted = CompletableDeferred<Unit>()
        val finishClaim = CompletableDeferred<Unit>()
        var claims = 0
        var prepares = 0
        val coordinator = ClinicalReportPdfReprepareCoordinator(
            currentReportIdentity = { LOCAL_IDENTITY },
            claimReprepare = { identity ->
                assertThat(identity).isEqualTo(LOCAL_IDENTITY)
                claims += 1
                claimStarted.complete(Unit)
                finishClaim.await()
                ClinicalPdfReprepareResult.PREPARE_REQUIRED
            },
            prepare = { prepares += 1 }
        )

        val first = launch { coordinator.reprepare() }
        claimStarted.await()
        val duplicate = launch { coordinator.reprepare() }
        duplicate.join()
        finishClaim.complete(Unit)
        first.join()

        assertThat(claims).isEqualTo(1)
        assertThat(prepares).isEqualTo(1)
    }

    @Test
    fun supersessionDuringHeavyStagingReleasesLateArtifactAndCannotPublishReady() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val stagingStarted = CompletableDeferred<Unit>()
        val finishStaging = CompletableDeferred<Unit>()
        val stale = artifact("stale-staging")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            identity = identity,
            stage = {
                stagingStarted.complete(Unit)
                withContext(NonCancellable) { finishStaging.await() }
                ClinicalPdfStageResult.Ready(stale)
            },
            release = released::add
        )

        coordinator.begin()
        stagingStarted.await()
        identity.current = UPLOADING_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)
        finishStaging.complete(Unit)
        advanceUntilIdle()

        assertThat(released).containsExactly(stale)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(coordinator.state.value.ticket).isNull()
    }

    @Test
    fun readyArtifactIsReleasedWhenSameRequestMovesToUploadingThenComplete() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val ready = artifact("local-only")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            identity = identity,
            stage = { ClinicalPdfStageResult.Ready(ready) },
            release = released::add
        )

        coordinator.begin()
        advanceUntilIdle()
        val staleTicket = coordinator.state.value.ticket!!

        identity.current = UPLOADING_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)
        identity.current = COMPLETE_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)

        assertThat(released).containsExactly(ready)
        assertThat(coordinator.claimReadyTicket(staleTicket.id)).isFalse()
        coordinator.resolvePicker(staleTicket.id, "stale-destination")
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)

        coordinator.begin()
        advanceUntilIdle()
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.READY)
        assertThat(coordinator.state.value.ticket!!.id).isGreaterThan(staleTicket.id)
    }

    @Test
    fun retryableArtifactIsReleasedWhenReportIdentityIsSuperseded() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val ready = artifact("retry-superseded")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            identity = identity,
            stage = { ClinicalPdfStageResult.Ready(ready) },
            release = released::add
        )
        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!
        assertThat(coordinator.claimReadyTicket(ticket.id)).isTrue()
        coordinator.resolvePicker(ticket.id, null)
        assertThat(released).isEmpty()

        identity.current = UPLOADING_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)

        assertThat(released).containsExactly(ready)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        coordinator.begin()
        assertThat(coordinator.state.value.ticket).isNull()
    }

    @Test
    fun supersessionDuringSafWriteCancelsOldArtifactAndIgnoresLateSuccess() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val copyStarted = CompletableDeferred<Unit>()
        val finishCopy = CompletableDeferred<Unit>()
        val ready = artifact("writing")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            identity = identity,
            stage = { ClinicalPdfStageResult.Ready(ready) },
            copy = { _, _ ->
                copyStarted.complete(Unit)
                withContext(NonCancellable) { finishCopy.await() }
                ClinicalPdfCopyResult.CopiedVerified
            },
            release = released::add
        )
        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!
        assertThat(coordinator.claimReadyTicket(ticket.id)).isTrue()
        coordinator.resolvePicker(ticket.id, "destination")
        copyStarted.await()

        identity.current = COMPLETE_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)
        finishCopy.complete(Unit)
        advanceUntilIdle()

        assertThat(released).containsExactly(ready)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        coordinator.resolvePicker(ticket.id, "stale")
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
    }

    @Test
    fun supersessionDuringShareDiscardsLateCopyAndNeverCallsChooser() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val shareStarted = CompletableDeferred<Unit>()
        val finishShare = CompletableDeferred<Unit>()
        val ready = artifact("sharing")
        val payload = ClinicalPdfSharePayload(
            uri = Uri.parse("content://clinical-pdf/stale-share.pdf"),
            mimeType = "application/pdf",
            flags = 1
        )
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val discarded = mutableListOf<ClinicalPdfSharePayload>()
        var chooserCalls = 0
        val coordinator = coordinator(
            identity = identity,
            stage = { ClinicalPdfStageResult.Ready(ready) },
            share = {
                shareStarted.complete(Unit)
                withContext(NonCancellable) { finishShare.await() }
                ClinicalPdfShareResult.Ready(payload)
            },
            discardShare = discarded::add,
            release = released::add
        )
        coordinator.begin()
        advanceUntilIdle()
        coordinator.beginShare {
            chooserCalls += 1
            true
        }
        shareStarted.await()

        identity.current = COMPLETE_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)
        finishShare.complete(Unit)
        advanceUntilIdle()

        assertThat(chooserCalls).isEqualTo(0)
        assertThat(discarded).containsExactly(payload)
        assertThat(released).containsExactly(ready)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
    }

    @Test
    fun currentIdentityIsRecheckedBeforeReadyAndBeforeArtifactClaim() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val staged = artifact("recheck")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val stageStarted = CompletableDeferred<Unit>()
        val finishStage = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            identity = identity,
            stage = {
                stageStarted.complete(Unit)
                finishStage.await()
                ClinicalPdfStageResult.Ready(staged)
            },
            release = released::add
        )

        coordinator.begin()
        stageStarted.await()
        identity.current = COMPLETE_IDENTITY
        finishStage.complete(Unit)
        advanceUntilIdle()

        assertThat(released).containsExactly(staged)
        assertThat(coordinator.state.value.ticket).isNull()

        identity.current = LOCAL_IDENTITY
        coordinator.onReportIdentityChanged(identity.current)
        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!
        identity.current = UPLOADING_IDENTITY

        assertThat(coordinator.claimReadyTicket(ticket.id)).isFalse()
        assertThat(released).hasSize(2)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
    }

    @Test
    fun acquisitionFailureAfterUnobservedSupersessionCannotLeavePreparingActive() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val acquisition = CompletableDeferred<ClinicalPdfSourceCaptureResult>()
        val coordinator = coordinator(identity = identity, acquire = { acquisition.await() })

        coordinator.begin()
        identity.current = UPLOADING_IDENTITY
        acquisition.complete(ClinicalPdfSourceCaptureResult.Failed)
        advanceUntilIdle()

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(coordinator.state.value.ticket).isNull()
    }

    @Test
    fun supersessionAtLeaseHandoffSkipsHeavySourceBuildAndStaging() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        var sourceBuilds = 0
        var stages = 0
        var leaseCloses = 0
        val lease = ClinicalPdfSourceLease(
            reportIdentity = LOCAL_IDENTITY,
            sourceBuilder = {
                sourceBuilds += 1
                ClinicalPdfSourceCaptureResult.Ready(SOURCE)
            },
            releaseLease = { leaseCloses += 1 }
        )
        val coordinator = coordinator(
            identity = identity,
            acquireLease = {
                identity.current = UPLOADING_IDENTITY
                ClinicalPdfSourceLeaseResult.Ready(lease)
            },
            stage = {
                stages += 1
                ClinicalPdfStageResult.Failed
            }
        )

        coordinator.begin()
        advanceUntilIdle()

        assertThat(sourceBuilds).isEqualTo(0)
        assertThat(stages).isEqualTo(0)
        assertThat(leaseCloses).isEqualTo(1)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
    }

    @Test
    fun tooLargeAfterUnobservedSupersessionCannotGateTheReplacementIdentity() = runTest {
        val identity = IdentityProbe(LOCAL_IDENTITY)
        val stageStarted = CompletableDeferred<Unit>()
        val finishStage = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            identity = identity,
            stage = {
                stageStarted.complete(Unit)
                finishStage.await()
                ClinicalPdfStageResult.TooLarge
            }
        )

        coordinator.begin()
        stageStarted.await()
        identity.current = COMPLETE_IDENTITY
        finishStage.complete(Unit)
        advanceUntilIdle()

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(coordinator.state.value.requiresReprepare).isFalse()
    }

    @Test
    fun beginPublishesPreparingBeforeAcquisitionAndReadyOnlyAfterStaging() = runTest {
        val acquisition = CompletableDeferred<ClinicalPdfSourceCaptureResult>()
        val artifact = artifact("ready")
        val coordinator = coordinator(
            acquire = { acquisition.await() },
            stage = { ClinicalPdfStageResult.Ready(artifact) }
        )

        coordinator.begin()

        assertThat(coordinator.state.value.phase)
            .isEqualTo(ClinicalPdfExportUiState.PREPARING)
        assertThat(coordinator.state.value.ticket).isNull()

        acquisition.complete(ClinicalPdfSourceCaptureResult.Ready(source()))
        advanceUntilIdle()

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.READY)
        assertThat(coordinator.state.value.ticket).isNotNull()
        assertThat(coordinator.state.value.ticket!!.suggestedFilename)
            .isEqualTo("aaps-copilot-clinical-report-2027-01-15.pdf")
    }

    @Test
    fun beginReturnsWithPreparingBeforeBlockingSourceBuildRunsOffCallerThread() = runTest {
        val buildStarted = CountDownLatch(1)
        val allowBuildToFinish = CountDownLatch(1)
        val leaseClosed = AtomicBoolean(false)
        val sourceExecutor = Executors.newSingleThreadExecutor()
        val sourceDispatcher = sourceExecutor.asCoroutineDispatcher()
        val lease = ClinicalPdfSourceLease(
            reportIdentity = LOCAL_IDENTITY,
            sourceBuilder = {
                buildStarted.countDown()
                check(allowBuildToFinish.await(5, TimeUnit.SECONDS))
                ClinicalPdfSourceCaptureResult.Ready(SOURCE)
            },
            releaseLease = { leaseClosed.set(true) }
        )
        val coordinator = coordinator(
            acquireLease = { ClinicalPdfSourceLeaseResult.Ready(lease) },
            sourceDispatcher = sourceDispatcher
        )

        coordinator.begin()

        assertThat(coordinator.state.value.phase)
            .isEqualTo(ClinicalPdfExportUiState.PREPARING)
        assertThat(buildStarted.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(coordinator.state.value.ticket).isNull()

        allowBuildToFinish.countDown()
        sourceExecutor.shutdown()
        assertThat(sourceExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        advanceUntilIdle()

        assertThat(leaseClosed.get()).isTrue()
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.READY)
        sourceDispatcher.close()
    }

    @Test
    fun duplicateBeginCoalescesAndReadyTicketCanBeClaimedExactlyOnce() = runTest {
        var captures = 0
        var stages = 0
        val coordinator = coordinator(
            acquire = {
                captures += 1
                ClinicalPdfSourceCaptureResult.Ready(source())
            },
            stage = {
                stages += 1
                ClinicalPdfStageResult.Ready(artifact("one"))
            }
        )

        coordinator.begin()
        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!

        assertThat(captures).isEqualTo(1)
        assertThat(stages).isEqualTo(1)
        assertThat(coordinator.claimReadyTicket(ticket.id)).isTrue()
        assertThat(coordinator.claimReadyTicket(ticket.id)).isFalse()
        assertThat(coordinator.claimReadyTicket(ticket.id + 1L)).isFalse()
    }

    @Test
    fun pickerSuccessCopiesReadyArtifactWithoutRenderingAgain() = runTest {
        val ready = artifact("copy")
        var stagedSource: ClinicalPdfContentSource? = null
        var copiedArtifact: ClinicalPdfReadyArtifact? = null
        var copiedDestination: Any? = null
        val coordinator = coordinator(
            stage = { source ->
                stagedSource = source
                ClinicalPdfStageResult.Ready(ready)
            },
            copy = { artifact, destination ->
                copiedArtifact = artifact
                copiedDestination = destination
                ClinicalPdfCopyResult.CopiedVerified
            }
        )

        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!
        assertThat(coordinator.claimReadyTicket(ticket.id)).isTrue()
        coordinator.resolvePicker(ticket.id, "content://destination")

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.WRITING)
        advanceUntilIdle()

        assertThat(stagedSource).isSameInstanceAs(SOURCE)
        assertThat(copiedArtifact).isSameInstanceAs(ready)
        assertThat(copiedDestination).isEqualTo("content://destination")
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.COMPLETE)
    }

    @Test
    fun staleAndRepeatedPickerCallbacksCannotAffectActiveExport() = runTest {
        var copies = 0
        val coordinator = coordinator(
            copy = { _, _ ->
                copies += 1
                ClinicalPdfCopyResult.CopiedVerified
            }
        )
        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!
        coordinator.claimReadyTicket(ticket.id)

        coordinator.resolvePicker(ticket.id + 1L, "stale")
        coordinator.resolvePicker(ticket.id, "current")
        coordinator.resolvePicker(ticket.id, "duplicate")
        advanceUntilIdle()

        assertThat(copies).isEqualTo(1)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.COMPLETE)
    }

    @Test
    fun pickerCancellationKeepsArtifactForOneRetryThenDeletesIt() = runTest {
        val ready = artifact("retry")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            stage = { ClinicalPdfStageResult.Ready(ready) },
            release = released::add
        )
        coordinator.begin()
        advanceUntilIdle()
        val first = coordinator.state.value.ticket!!
        coordinator.claimReadyTicket(first.id)

        coordinator.resolvePicker(first.id, null)

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(released).isEmpty()

        coordinator.begin()
        val retry = coordinator.state.value.ticket!!
        assertThat(retry.id).isGreaterThan(first.id)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.READY)
        coordinator.claimReadyTicket(retry.id)
        coordinator.resolvePicker(retry.id, null)

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(released).containsExactly(ready)
    }

    @Test
    fun shareClaimsArtifactOnceIgnoresStalePickerAndReleasesAfterOneChooser() = runTest {
        val ready = artifact("share")
        val payload = ClinicalPdfSharePayload(
            uri = Uri.parse("content://clinical-pdf/share.pdf"),
            mimeType = "application/pdf",
            flags = 1
        )
        var shareCalls = 0
        var chooserCalls = 0
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            stage = { ClinicalPdfStageResult.Ready(ready) },
            share = {
                shareCalls += 1
                ClinicalPdfShareResult.Ready(payload)
            },
            release = released::add
        )

        coordinator.begin()
        advanceUntilIdle()
        val stalePickerTicket = coordinator.state.value.ticket!!

        coordinator.beginShare {
            chooserCalls += 1
            true
        }
        coordinator.beginShare {
            chooserCalls += 1
            true
        }
        coordinator.resolvePicker(stalePickerTicket.id, "stale")

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.WRITING)
        advanceUntilIdle()

        assertThat(shareCalls).isEqualTo(1)
        assertThat(chooserCalls).isEqualTo(1)
        assertThat(released).containsExactly(ready)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.COMPLETE)
    }

    @Test
    fun shareFailureHasOneBoundedRetryAndRejectedChooserDiscardsTemp() = runTest {
        val ready = artifact("share-retry")
        val payload = ClinicalPdfSharePayload(
            uri = Uri.parse("content://clinical-pdf/rejected.pdf"),
            mimeType = "application/pdf",
            flags = 1
        )
        var shareCalls = 0
        val discarded = mutableListOf<ClinicalPdfSharePayload>()
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        val coordinator = coordinator(
            stage = { ClinicalPdfStageResult.Ready(ready) },
            share = {
                shareCalls += 1
                if (shareCalls == 1) ClinicalPdfShareResult.Failed
                else ClinicalPdfShareResult.Ready(payload)
            },
            discardShare = discarded::add,
            release = released::add
        )
        coordinator.begin()
        advanceUntilIdle()

        coordinator.beginShare { true }
        advanceUntilIdle()

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.FAILED)
        assertThat(coordinator.state.value.canShare).isTrue()
        assertThat(released).isEmpty()

        coordinator.beginShare { false }
        advanceUntilIdle()

        assertThat(shareCalls).isEqualTo(2)
        assertThat(discarded).containsExactly(payload)
        assertThat(released).containsExactly(ready)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.FAILED)
        assertThat(coordinator.state.value.canShare).isFalse()
    }

    @Test
    fun acquisitionRenderAndCopyFailuresMapToSanitizedTerminalStates() = runTest {
        val missing = coordinator(acquire = { ClinicalPdfSourceCaptureResult.Failed })
        missing.onReportIdentityChanged(LOCAL_IDENTITY)
        missing.begin()
        advanceUntilIdle()
        assertThat(missing.state.value.phase).isEqualTo(ClinicalPdfExportUiState.FAILED)
        assertThat(missing.state.value.requiresReprepare).isTrue()
        missing.onReportIdentityChanged(LOCAL_IDENTITY)
        assertThat(missing.state.value.phase).isEqualTo(ClinicalPdfExportUiState.FAILED)
        missing.onReportIdentityChanged(COMPLETE_IDENTITY)
        assertThat(missing.state.value.phase).isEqualTo(ClinicalPdfExportUiState.IDLE)
        assertThat(missing.state.value.requiresReprepare).isFalse()

        var tooLargeStages = 0
        val tooLarge = coordinator(stage = {
            tooLargeStages += 1
            ClinicalPdfStageResult.TooLarge
        })
        tooLarge.onReportIdentityChanged(LOCAL_IDENTITY)
        tooLarge.begin()
        advanceUntilIdle()
        assertThat(tooLarge.state.value.phase).isEqualTo(ClinicalPdfExportUiState.TOO_LARGE)
        assertThat(tooLarge.state.value.requiresReprepare).isTrue()
        tooLarge.begin()
        advanceUntilIdle()
        assertThat(tooLargeStages).isEqualTo(1)
        tooLarge.onReportIdentityChanged(LOCAL_IDENTITY)
        assertThat(tooLarge.state.value.phase).isEqualTo(ClinicalPdfExportUiState.TOO_LARGE)
        tooLarge.onReportIdentityChanged(COMPLETE_IDENTITY)
        assertThat(tooLarge.state.value.phase).isEqualTo(ClinicalPdfExportUiState.IDLE)
        tooLarge.begin()
        advanceUntilIdle()
        assertThat(tooLargeStages).isEqualTo(2)

        val copyFailure = coordinator(copy = { _, _ -> ClinicalPdfCopyResult.Failed })
        copyFailure.begin()
        advanceUntilIdle()
        val ticket = copyFailure.state.value.ticket!!
        copyFailure.claimReadyTicket(ticket.id)
        copyFailure.resolvePicker(ticket.id, "destination")
        advanceUntilIdle()
        assertThat(copyFailure.state.value.phase).isEqualTo(ClinicalPdfExportUiState.FAILED)
    }

    @Test
    fun cancellationDuringPreparationPublishesCancelledAndNoTicket() = runTest {
        val acquisition = CompletableDeferred<ClinicalPdfSourceCaptureResult>()
        val coordinator = coordinator(acquire = { acquisition.await() })
        coordinator.begin()

        coordinator.cancel()
        advanceUntilIdle()

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(coordinator.state.value.ticket).isNull()
    }

    @Test
    fun closeIdempotentlyReleasesReadyAndRetryArtifactsAndRejectsRelaunch() = runTest {
        val ready = artifact("close")
        val released = mutableListOf<ClinicalPdfReadyArtifact>()
        var captures = 0
        val coordinator = coordinator(
            acquire = {
                captures += 1
                ClinicalPdfSourceCaptureResult.Ready(SOURCE)
            },
            stage = { ClinicalPdfStageResult.Ready(ready) },
            release = released::add
        )
        coordinator.begin()
        advanceUntilIdle()
        val ticket = coordinator.state.value.ticket!!
        coordinator.claimReadyTicket(ticket.id)
        coordinator.resolvePicker(ticket.id, null)

        coordinator.close()
        coordinator.close()
        coordinator.begin()
        advanceUntilIdle()

        assertThat(released).containsExactly(ready)
        assertThat(captures).isEqualTo(1)
        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(coordinator.state.value.ticket).isNull()
    }

    @Test
    fun closeCancelsInFlightPreparationWithoutPublishingAfterTeardown() = runTest {
        val acquisition = CompletableDeferred<ClinicalPdfSourceCaptureResult>()
        val coordinator = coordinator(acquire = { acquisition.await() })
        coordinator.begin()

        coordinator.close()
        acquisition.complete(ClinicalPdfSourceCaptureResult.Ready(SOURCE))
        advanceUntilIdle()

        assertThat(coordinator.state.value.phase).isEqualTo(ClinicalPdfExportUiState.CANCELLED)
        assertThat(coordinator.state.value.ticket).isNull()
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        identity: IdentityProbe = IdentityProbe(LOCAL_IDENTITY),
        acquire: suspend () -> ClinicalPdfSourceCaptureResult = {
            ClinicalPdfSourceCaptureResult.Ready(SOURCE)
        },
        acquireLease: suspend () -> ClinicalPdfSourceLeaseResult = {
            when (val captured = acquire()) {
                is ClinicalPdfSourceCaptureResult.Ready -> ClinicalPdfSourceLeaseResult.Ready(
                    ClinicalPdfSourceLease(
                        reportIdentity = identity.current,
                        sourceBuilder = { captured },
                        releaseLease = {}
                    )
                )
                ClinicalPdfSourceCaptureResult.Failed -> ClinicalPdfSourceLeaseResult.Failed
            }
        },
        sourceDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        stage: suspend (ClinicalPdfContentSource) -> ClinicalPdfStageResult = {
            ClinicalPdfStageResult.Ready(artifact("default"))
        },
        copy: suspend (ClinicalPdfReadyArtifact, Any) -> ClinicalPdfCopyResult = { _, _ ->
            ClinicalPdfCopyResult.CopiedVerified
        },
        share: suspend (ClinicalPdfReadyArtifact) -> ClinicalPdfShareResult = {
            ClinicalPdfShareResult.Failed
        },
        discardShare: (ClinicalPdfSharePayload) -> Unit = {},
        release: (ClinicalPdfReadyArtifact) -> Unit = {}
    ) = ClinicalReportPdfExportCoordinator(
        scope = this,
        acquireSourceLease = acquireLease,
        sourceDispatcher = sourceDispatcher,
        stage = stage,
        copy = copy,
        share = share,
        discardShare = discardShare,
        currentReportIdentity = { identity.current },
        initialReportIdentity = identity.current,
        release = release
    )

    private data class IdentityProbe(var current: ClinicalPdfReportIdentity)

    private fun artifact(name: String) = ClinicalPdfReadyArtifact(
        file = File("$name.ready.pdf"),
        sizeBytes = 128L,
        sha256 = "b".repeat(64),
        pageCount = 1,
        createdAt = GENERATED_AT
    )

    private fun source(): ClinicalPdfContentSource = SOURCE

    private companion object {
        const val GENERATED_AT = 1_800_000_000_000L
        val LOCAL_IDENTITY = ClinicalPdfReportIdentity(
            ClinicalPdfReportPhase.LOCAL_READY,
            "1".repeat(64)
        )
        val UPLOADING_IDENTITY = ClinicalPdfReportIdentity(
            ClinicalPdfReportPhase.UPLOADING,
            "2".repeat(64)
        )
        val COMPLETE_IDENTITY = ClinicalPdfReportIdentity(
            ClinicalPdfReportPhase.COMPLETE,
            "3".repeat(64)
        )
        val SOURCE = object : ClinicalPdfContentSource {
            override val identity = ClinicalPdfContentIdentity(
                requestSha256 = "a".repeat(64),
                generatedAt = GENERATED_AT,
                zoneId = "Asia/Tbilisi",
                schemaVersion = 7
            )

            override fun open(): ClinicalPdfContentCursor = object : ClinicalPdfContentCursor {
                private var emitted = false
                override fun next(): ClinicalPdfContentBlock? = if (emitted) {
                    null
                } else {
                    emitted = true
                    ClinicalPdfContentBlock("title", ClinicalPdfContentRole.TITLE)
                }

                override fun close() = Unit
            }
        }
    }
}
