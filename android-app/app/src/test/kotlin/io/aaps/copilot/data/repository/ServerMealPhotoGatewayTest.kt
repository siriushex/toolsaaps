package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ServerMealPhotoGatewayTest {
    private val now = 1_800_000_000_000L
    private val request = MealPhotoGatewayRequest(UUID.randomUUID().toString(), byteArrayOf(-1, -40, -1, 0, -1, -39), "image/jpeg")

    private inner class Jobs : MealPhotoJobs {
        var ready = true
        var reads = 0
        var sends = 0
        var polls = 0
        val cancelled = mutableListOf<String>()
        val jobId = UUID.randomUUID().toString()
        var submitFailure = false
        var badReceipt = false
        var terminalState: ServerAiJobState? = null
        var terminalOnSubmit = false
        var resultExpires: Long? = null
        override suspend fun ready(): Boolean { reads++; return ready }
        override fun prepare(request: MealPhotoGatewayRequest, deadlineMs: Long) =
            ServerMealPhotoProtocol.prepare(request, now, deadlineMs)
        override suspend fun submit(submission: MealPhotoSubmission): MealPhotoJobSnapshot {
            sends++
            if (submitFailure) throw IOException("private transport detail")
            return (if (terminalOnSubmit) terminal(submission) else queued(submission))
                .let { if (badReceipt) it.copy(requestId = UUID.randomUUID().toString()) else it }
        }
        override suspend fun status(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot {
            polls++; return if (terminalState != null) terminal(submission) else queued(submission)
        }
        override suspend fun cancel(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot {
            cancelled += jobId
            return queued(submission).copy(state = ServerAiJobState.CANCELLED, finishedMs = now + 1)
        }
        fun queued(submission: MealPhotoSubmission) = MealPhotoJobSnapshot(jobId, submission.requestId,
            ServerAiJobState.QUEUED, now, submission.deadlineMs, null, null, false, null, null)
        fun terminal(submission: MealPhotoSubmission) = queued(submission).copy(
            state = checkNotNull(terminalState), startedMs = now + 1, finishedMs = now + 2,
            resultAvailable = resultExpires != null, estimateJson = resultExpires?.let { "{}" },
            resultExpiresMs = resultExpires)
    }

    @Test fun disconnectedStateNeverChecksProviderOrUploadsImage() = runTest {
        val jobs = Jobs()
        val gateway = ServerMealPhotoGateway(jobs, { false }, { now }, {})
        assertThat(gateway.availability()).isEqualTo(MealPhotoAvailability.NEEDS_CONNECTION)
        val result = MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
        assertThat(result).isEqualTo(MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED))
        assertThat(jobs.reads).isEqualTo(0)
        assertThat(jobs.sends).isEqualTo(0)
    }

    @Test fun textOnlyReadinessCannotSubmitPhoto() = runTest {
        val jobs = Jobs().apply { ready = false }
        val gateway = ServerMealPhotoGateway(jobs, { true }, { now }, {})
        val result = MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
        assertThat(result).isEqualTo(MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.UNAVAILABLE))
        assertThat(jobs.sends).isEqualTo(0)
    }

    @Test fun lostSubmissionAcknowledgementIsUnknownAndNeverReplayed() = runTest {
        val jobs = Jobs().apply { submitFailure = true }
        val gateway = ServerMealPhotoGateway(jobs, { true }, { now }, {})
        val result = MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
        assertThat(result).isEqualTo(MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.UNKNOWN_OUTCOME))
        assertThat(jobs.sends).isEqualTo(1)
        assertThat(jobs.polls).isEqualTo(0)
        assertThat(jobs.cancelled).isEmpty()
    }

    @Test fun wrongRequestReceiptCannotGrantCancellationAuthority() = runTest {
        val jobs = Jobs().apply { badReceipt = true }
        val gateway = ServerMealPhotoGateway(jobs, { true }, { now }, {})
        val result = MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
        assertThat(result).isEqualTo(MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.UNKNOWN_OUTCOME))
        assertThat(jobs.sends).isEqualTo(1)
        assertThat(jobs.cancelled).isEmpty()
    }

    @Test fun boundedPollingCancelsOnlyTheCorrelatedJob() = runTest {
        val jobs = Jobs()
        val gateway = ServerMealPhotoGateway(jobs, { true }, { now }, {})
        val result = MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
        assertThat(result).isEqualTo(MealPhotoAnalysisResult.Failure(MealPhotoAnalysisResult.Failure.Reason.TIMED_OUT))
        assertThat(jobs.sends).isEqualTo(1)
        assertThat(jobs.polls).isAtMost(64)
        assertThat(jobs.cancelled).containsExactly(jobs.jobId)
    }

    @Test fun terminalJobFailuresDoNotRevokePhotoServiceAvailability() = runTest {
        val expected = mapOf(ServerAiJobState.FAILED to "ANALYSIS_FAILED",
            ServerAiJobState.CANCELLED to "CANCELLED", ServerAiJobState.EXPIRED to "TIMED_OUT",
            ServerAiJobState.SUCCEEDED to "RESULT_UNAVAILABLE", ServerAiJobState.UNKNOWN to "UNKNOWN_OUTCOME")
        for ((state, reason) in expected) for (immediate in listOf(false, true)) {
            val jobs = Jobs().apply { terminalState = state; terminalOnSubmit = immediate }
            val gateway = ServerMealPhotoGateway(jobs, { true }, { now }, {})
            val result = MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
            assertThat((result as MealPhotoAnalysisResult.Failure).reason.name).isEqualTo(reason)
            assertThat(jobs.sends).isEqualTo(1)
            assertThat(jobs.polls).isEqualTo(if (immediate) 0 else 1)
            assertThat(jobs.cancelled).isEmpty()
            assertThat(gateway.availability()).isEqualTo(MealPhotoAvailability.READY)
        }
    }

    @Test fun retainedResultExpiresAtItsOriginalDeadlineWithoutAnotherSubmission() = runTest {
        val receivedAt = now + 100
        for (expires in listOf(receivedAt - 1, receivedAt, receivedAt + 1)) {
            val jobs = Jobs().apply {
                terminalState = ServerAiJobState.SUCCEEDED
                terminalOnSubmit = true
                resultExpires = expires
            }
            var clockReads = 0
            val gateway = ServerMealPhotoGateway(jobs, { true },
                { if (clockReads++ == 0) now else receivedAt }, {})
            if (expires > receivedAt) assertThat(gateway.analyze(request)).isEqualTo("{}")
            else {
                val failure = runCatching { gateway.analyze(request) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(MealPhotoGatewayFailure::class.java)
                assertThat((failure as MealPhotoGatewayFailure).reason)
                    .isEqualTo(MealPhotoAnalysisResult.Failure.Reason.RESULT_UNAVAILABLE)
            }
            assertThat(jobs.sends).isEqualTo(1)
            assertThat(jobs.polls).isEqualTo(0)
            assertThat(jobs.cancelled).isEmpty()
        }
    }
}
