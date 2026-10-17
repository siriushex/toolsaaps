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
        override suspend fun ready(): Boolean { reads++; return ready }
        override fun prepare(request: MealPhotoGatewayRequest, deadlineMs: Long) =
            ServerMealPhotoProtocol.prepare(request, now, deadlineMs)
        override suspend fun submit(submission: MealPhotoSubmission): MealPhotoJobSnapshot {
            sends++
            if (submitFailure) throw IOException("private transport detail")
            return queued(submission).let { if (badReceipt) it.copy(requestId = UUID.randomUUID().toString()) else it }
        }
        override suspend fun status(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot {
            polls++; return queued(submission)
        }
        override suspend fun cancel(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot {
            cancelled += jobId
            return queued(submission).copy(state = ServerAiJobState.CANCELLED, finishedMs = now + 1)
        }
        fun queued(submission: MealPhotoSubmission) = MealPhotoJobSnapshot(jobId, submission.requestId,
            ServerAiJobState.QUEUED, now, submission.deadlineMs, null, null, false, null, null)
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
        MealPhotoAnalysisRepository(gateway).analyze(request.requestId, request.bytes)
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
}
