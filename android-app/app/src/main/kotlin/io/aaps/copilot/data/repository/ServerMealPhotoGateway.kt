package io.aaps.copilot.data.repository

import io.aaps.copilot.security.ServerAiConnectionFailure
import io.aaps.copilot.security.ServerAiConnectionError
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class MealPhotoAvailability { READY, NEEDS_CONNECTION, UNSUPPORTED, UNAVAILABLE }

class MealPhotoGatewayFailure(val reason: MealPhotoAnalysisResult.Failure.Reason) : IOException("meal_photo_failed") {
    override fun toString() = "MealPhotoGatewayFailure(reason=$reason)"
}

internal interface MealPhotoJobs {
    suspend fun ready(): Boolean
    fun prepare(request: MealPhotoGatewayRequest, deadlineMs: Long): MealPhotoSubmission
    suspend fun submit(submission: MealPhotoSubmission): MealPhotoJobSnapshot
    suspend fun status(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot
    suspend fun cancel(submission: MealPhotoSubmission, jobId: String): MealPhotoJobSnapshot
}

internal class SignedMealPhotoJobs(private val connection: ServerAiConnectionManager) : MealPhotoJobs {
    override suspend fun ready() = ServerMealPhotoProtocol.ready(exchange("GET", "/api/ai/v1/capabilities"))
    override fun prepare(request: MealPhotoGatewayRequest, deadlineMs: Long) =
        ServerMealPhotoProtocol.prepare(request, connection.currentServerTimeMs(), deadlineMs)
    override suspend fun submit(submission: MealPhotoSubmission) = ServerMealPhotoProtocol.snapshot(
        exchange("POST", ServerMealPhotoProtocol.JOBS_PATH, submission), submission, null)
    override suspend fun status(submission: MealPhotoSubmission, jobId: String) = ServerMealPhotoProtocol.snapshot(
        exchange("GET", "${ServerMealPhotoProtocol.JOBS_PATH}/$jobId"), submission, jobId)
    override suspend fun cancel(submission: MealPhotoSubmission, jobId: String) = ServerMealPhotoProtocol.snapshot(
        exchange("DELETE", "${ServerMealPhotoProtocol.JOBS_PATH}/$jobId"), submission, jobId)

    private suspend fun exchange(method: String, path: String, submission: MealPhotoSubmission? = null): ByteArray {
        val response = try {
            connection.exchangeMealPhoto(method, path, submission?.body ?: ByteArray(0),
                submission?.requestId, submission?.deadlineMs)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ServerAiConnectionFailure) {
            throw ServerAiJobFailure(when (failure.reason) {
                ServerAiConnectionError.NETWORK -> ServerAiJobError.NETWORK
                ServerAiConnectionError.INVALID_RESPONSE -> ServerAiJobError.INVALID_RESPONSE
                else -> ServerAiJobError.UNAUTHORIZED
            })
        } catch (_: IOException) { throw ServerAiJobFailure(ServerAiJobError.NETWORK) }
        if (response.statusCode != if (method == "POST") 202 else 200) {
            throw ServerAiJobFailure(when (response.statusCode) {
                400 -> ServerAiJobError.INVALID_REQUEST
                401, 403 -> ServerAiJobError.UNAUTHORIZED
                404, 405, 503 -> ServerAiJobError.SERVICE_UNAVAILABLE
                409 -> ServerAiJobError.REQUEST_CONFLICT
                413 -> ServerAiJobError.REQUEST_TOO_LARGE
                429 -> ServerAiJobError.RATE_LIMITED
                else -> ServerAiJobError.INVALID_RESPONSE
            })
        }
        if (response.body.size > ServerMealPhotoProtocol.RESPONSE_LIMIT) {
            throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
        }
        return response.body
    }
}

/** Image-only server path: one immutable submission, no history upload, key fallback or replay. */
class ServerMealPhotoGateway internal constructor(
    private val jobs: MealPhotoJobs,
    private val canCheckConnection: () -> Boolean,
    private val serverTime: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) : MealPhotoGateway {
    constructor(connection: ServerAiConnectionManager) : this(
        SignedMealPhotoJobs(connection),
        {
            val state = connection.state.value
            // Restored or unloaded credentials must reach the signed client's checks.
            !(state.revision > 0 && state.phase == ServerAiConnectionPhase.DISCONNECTED &&
                !state.hasStoredSession && !state.canResume)
        },
        connection::currentServerTimeMs
    )

    suspend fun availability(): MealPhotoAvailability {
        if (!canCheckConnection()) return MealPhotoAvailability.NEEDS_CONNECTION
        return try {
            if (jobs.ready()) MealPhotoAvailability.READY else MealPhotoAvailability.UNSUPPORTED
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ServerAiJobFailure) {
            when (failure.reason) {
                ServerAiJobError.UNAUTHORIZED, ServerAiJobError.FORBIDDEN -> MealPhotoAvailability.NEEDS_CONNECTION
                else -> MealPhotoAvailability.UNAVAILABLE
            }
        } catch (_: Exception) { MealPhotoAvailability.UNAVAILABLE }
    }

    override suspend fun analyze(request: MealPhotoGatewayRequest): String {
        var submission: MealPhotoSubmission? = null
        var snapshot: MealPhotoJobSnapshot? = null
        var sent = false
        var cancelling = false
        fun accept(prepared: MealPhotoSubmission, candidate: MealPhotoJobSnapshot) {
            val before = snapshot
            if (candidate.requestId != prepared.requestId || candidate.deadlineMs != prepared.deadlineMs ||
                before?.let {
                    candidate.jobId != it.jobId || candidate.createdMs != it.createdMs ||
                        (it.startedMs != null && candidate.startedMs != it.startedMs) ||
                        (it.state != ServerAiJobState.QUEUED && candidate.state == ServerAiJobState.QUEUED) ||
                        (it.state == ServerAiJobState.CANCEL_REQUESTED && candidate.state == ServerAiJobState.RUNNING)
                } == true) throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
            snapshot = candidate
        }
        suspend fun cancelKnown() {
            val prepared = submission ?: return
            val known = snapshot ?: return
            if (known.state.terminal || cancelling) return
            cancelling = true
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) { runCatching { jobs.cancel(prepared, known.jobId) } }
            }
        }
        try {
            return withTimeoutOrNull(70_000) {
                when (availability()) {
                    MealPhotoAvailability.NEEDS_CONNECTION -> fail(MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED)
                    MealPhotoAvailability.UNSUPPORTED, MealPhotoAvailability.UNAVAILABLE -> fail(MealPhotoAnalysisResult.Failure.Reason.UNAVAILABLE)
                    MealPhotoAvailability.READY -> Unit
                }
                if (!canCheckConnection()) fail(MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED)
                val prepared = jobs.prepare(request, Math.addExact(serverTime(), ServerMealPhotoProtocol.MAX_DEADLINE_MS))
                submission = prepared
                if (prepared.requestId != request.requestId) throw ServerAiJobFailure(ServerAiJobError.INVALID_REQUEST)
                sent = true
                accept(prepared, jobs.submit(prepared))
                repeat(64) { poll ->
                    val current = checkNotNull(snapshot)
                    when (current.state) {
                        ServerAiJobState.SUCCEEDED -> {
                            if (!current.resultAvailable || current.estimateJson == null ||
                                current.resultExpiresMs?.let { it > serverTime() } != true) {
                                fail(MealPhotoAnalysisResult.Failure.Reason.UNAVAILABLE)
                            }
                            return@withTimeoutOrNull current.estimateJson
                        }
                        ServerAiJobState.UNKNOWN -> fail(MealPhotoAnalysisResult.Failure.Reason.UNKNOWN_OUTCOME)
                        ServerAiJobState.FAILED, ServerAiJobState.CANCELLED, ServerAiJobState.EXPIRED ->
                            fail(MealPhotoAnalysisResult.Failure.Reason.UNAVAILABLE)
                        else -> Unit
                    }
                    if (serverTime() >= prepared.deadlineMs) fail(MealPhotoAnalysisResult.Failure.Reason.TIMED_OUT)
                    pause(minOf(250L * (poll + 1), 2_000L))
                    accept(prepared, jobs.status(prepared, current.jobId))
                }
                fail(MealPhotoAnalysisResult.Failure.Reason.TIMED_OUT)
            } ?: fail(if (sent && snapshot == null) MealPhotoAnalysisResult.Failure.Reason.UNKNOWN_OUTCOME
                else MealPhotoAnalysisResult.Failure.Reason.TIMED_OUT)
        } catch (cancelled: CancellationException) {
            cancelKnown()
            throw cancelled
        } catch (failure: Exception) {
            cancelKnown()
            if (failure is MealPhotoGatewayFailure) throw failure
            val reason = (failure as? ServerAiJobFailure)?.reason
            fail(if (sent && snapshot == null && (reason == null || reason == ServerAiJobError.NETWORK ||
                    reason == ServerAiJobError.INVALID_RESPONSE)) MealPhotoAnalysisResult.Failure.Reason.UNKNOWN_OUTCOME
                else when (reason) {
                    ServerAiJobError.UNAUTHORIZED, ServerAiJobError.FORBIDDEN -> MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED
                    ServerAiJobError.SERVICE_UNAVAILABLE -> MealPhotoAnalysisResult.Failure.Reason.UNAVAILABLE
                    ServerAiJobError.INVALID_REQUEST, ServerAiJobError.REQUEST_TOO_LARGE -> MealPhotoAnalysisResult.Failure.Reason.INVALID_REQUEST
                    ServerAiJobError.INVALID_RESPONSE -> MealPhotoAnalysisResult.Failure.Reason.INVALID_RESPONSE
                    else -> MealPhotoAnalysisResult.Failure.Reason.NETWORK
                })
        }
    }

    private fun fail(reason: MealPhotoAnalysisResult.Failure.Reason): Nothing = throw MealPhotoGatewayFailure(reason)
}
