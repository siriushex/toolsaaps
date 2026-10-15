package io.aaps.copilot.data.repository

import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionPhase
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** SERVER_REQUIRED also covers loading, saved, pending, disabled and failed connections. */
enum class ServerAiChatRouting { UNCONFIGURED, SERVER_REQUIRED }

enum class ServerAiChatError {
    ROUTING_UNAVAILABLE, DISABLED, UNSUPPORTED_MODALITY, INVALID_REQUEST, CONTEXT_SYNC,
    NETWORK, UNAUTHORIZED, REJECTED, INVALID_RESPONSE, UNKNOWN, TIMED_OUT, CANCELLED,
    FAILED, RESULT_UNAVAILABLE
}

enum class ServerAiChatCancellation { NOT_ATTEMPTED, CONFIRMED, ALREADY_TERMINAL, UNCONFIRMED }

class ServerAiChatFailure(
    val reason: ServerAiChatError,
    val requestId: String? = null,
    val jobId: String? = null,
    val jobError: ServerAiJobError? = null,
    val remoteCancellation: ServerAiChatCancellation = ServerAiChatCancellation.NOT_ATTEMPTED,
    val outcomeUnknown: Boolean = false
) : IOException(
    when (reason) {
        ServerAiChatError.ROUTING_UNAVAILABLE -> "Server AI chat is not connected. Check the app's server AI setup."
        ServerAiChatError.DISABLED -> "Server AI inference is unavailable. Check the server connection and readiness."
        ServerAiChatError.UNSUPPORTED_MODALITY ->
            "Server AI supports text chat only. Remove attachments and use typed text; audio and speech are unavailable."
        ServerAiChatError.INVALID_REQUEST -> "The question is invalid or exceeds server limits. Edit it explicitly before sending again."
        ServerAiChatError.CONTEXT_SYNC -> "Chat context could not be synchronized. No model job was submitted. Check the server connection."
        ServerAiChatError.NETWORK -> "Server AI could not be reached. Check the connection and server conversation history before retrying."
        ServerAiChatError.UNAUTHORIZED -> "Server AI access is unavailable. Check the server subscription and connection."
        ServerAiChatError.REJECTED -> "The server rejected the AI job. Check server readiness before retrying."
        ServerAiChatError.INVALID_RESPONSE -> "Server AI returned an inconsistent response. Check server conversation history before retrying."
        ServerAiChatError.UNKNOWN -> "The server AI job outcome is UNKNOWN. Check server conversation history; do not resubmit automatically."
        ServerAiChatError.TIMED_OUT -> "The server AI wait limit was reached. Check server conversation history before retrying."
        ServerAiChatError.CANCELLED -> "The server AI job was cancelled."
        ServerAiChatError.FAILED -> "The server AI job failed. No answer is available."
        ServerAiChatError.RESULT_UNAVAILABLE -> "The server AI answer is unavailable or expired. Check server conversation history."
    }
) {
    override fun toString() = "ServerAiChatFailure(reason=$reason, requestId=$requestId, jobId=$jobId, " +
        "jobError=$jobError, remoteCancellation=$remoteCancellation, outcomeUnknown=$outcomeUnknown)"
}

internal interface ServerAiChatJobs {
    suspend fun capabilities(): ServerAiCapabilities
    fun prepareChat(text: String, deadlineMs: Long): ServerAiChatSubmission
    suspend fun submit(submission: ServerAiChatSubmission): ServerAiJobSnapshot
    suspend fun status(jobId: String): ServerAiJobSnapshot
    suspend fun cancel(jobId: String): ServerAiJobSnapshot
}

internal data class ServerAiChatPolicy(
    val jobTimeoutMs: Long = 840_000,
    val overallTimeoutMs: Long = 900_000,
    val cancellationTimeoutMs: Long = 5_000,
    val initialPollDelayMs: Long = 250,
    val maxPollDelayMs: Long = 4_000,
    val maxPolls: Int = 256
) {
    init {
        require(jobTimeoutMs in 1..900_000 && overallTimeoutMs in 1..900_000)
        require(cancellationTimeoutMs in 1..30_000)
        require(initialPollDelayMs in 1..maxPollDelayMs && maxPollDelayMs <= 30_000)
        require(maxPolls in 1..1_024)
    }
}

/**
 * Advisory text only. Context synchronization must finish successfully before job preparation.
 * The required callback receives the full inputs and must throw on failed or incomplete transfer.
 * One invocation prepares and sends exactly one request; ambiguous sends are never retried.
 */
class ServerAiChatGateway internal constructor(
    private val jobs: ServerAiChatJobs,
    private val routingProvider: () -> ServerAiChatRouting,
    private val syncChatContext: suspend (contextSummary: String, history: List<AiChatTurn>) -> Unit,
    private val serverTimeMs: () -> Long,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val policy: ServerAiChatPolicy = ServerAiChatPolicy()
) {
    constructor(
        connection: ServerAiConnectionManager,
        syncChatContext: suspend (contextSummary: String, history: List<AiChatTurn>) -> Unit
    ) : this(
        jobs = SignedChatJobs(ServerAiJobClient(connection)),
        routingProvider = {
            val state = connection.state.value
            // An unloaded record cannot establish that the server is unconfigured.
            if (state.revision > 0 && state.phase == ServerAiConnectionPhase.DISCONNECTED &&
                !state.hasStoredSession && !state.canResume
            ) ServerAiChatRouting.UNCONFIGURED else ServerAiChatRouting.SERVER_REQUIRED
        },
        syncChatContext = syncChatContext,
        serverTimeMs = connection::currentServerTimeMs
    )

    fun routingState(): ServerAiChatRouting = routingProvider()

    suspend fun ask(question: String, contextSummary: String, history: List<AiChatTurn> = emptyList()): String {
        val attempt = Attempt()
        // Copy before the first suspension; neither the caller nor the sync callback can edit it.
        val frozenHistory = Collections.unmodifiableList(history.toList())
        try {
            return withTimeoutOrNull(policy.overallTimeoutMs) {
                requireServerRoute()
                val capabilities = jobs.capabilities()
                if (!capabilities.inferenceEnabled) throw ServerAiChatFailure(ServerAiChatError.DISABLED)
                if (capabilities.taskKinds != listOf("CHAT") || capabilities.inputModalities != listOf("TEXT") ||
                    capabilities.outputModalities != listOf("TEXT")
                ) throw ServerAiChatFailure(ServerAiChatError.UNSUPPORTED_MODALITY)
                validateQuestion(question, capabilities)
                try {
                    syncChatContext(contextSummary, frozenHistory)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not expose transport exceptions that might contain context or credentials.
                    throw ServerAiChatFailure(ServerAiChatError.CONTEXT_SYNC)
                }
                currentCoroutineContext().ensureActive()
                requireServerRoute()
                val now = serverTimeMs()
                val window = minOf(policy.jobTimeoutMs, capabilities.maxDeadlineMs)
                if (now <= 0 || window !in 1..900_000) throw ServerAiChatFailure(ServerAiChatError.INVALID_RESPONSE)
                val deadline = Math.addExact(now, window)
                attempt.submission = jobs.prepareChat(question, deadline).also {
                    if (it.deadlineMs != deadline || !canonicalUuid(it.requestId)) {
                        throw ServerAiChatFailure(ServerAiChatError.INVALID_RESPONSE)
                    }
                }
                attempt.startedAt = monotonicTimeMs()
                attempt.windowMs = window
                requireTimeRemaining(attempt)
                currentCoroutineContext().ensureActive()
                attempt.sendAttempted = true
                acceptSnapshot(attempt, jobs.submit(attempt.submission!!))
                awaitAnswer(attempt)
            } ?: throw ServerAiChatFailure(
                if (attempt.sendAttempted && attempt.snapshot == null) ServerAiChatError.UNKNOWN else ServerAiChatError.TIMED_OUT
            )
        } catch (cancelled: CancellationException) {
            val remote = cancelKnownJob(attempt)
            // Preserve structured cancellation and make the remote outcome inspectable by the caller.
            if (attempt.sendAttempted) cancelled.addSuppressed(failureDetails(
                if (attempt.snapshot == null) ServerAiChatError.UNKNOWN else ServerAiChatError.CANCELLED, attempt, remote
            ))
            throw cancelled
        } catch (failure: Exception) {
            val remote = cancelKnownJob(attempt)
            val reason = when (failure) {
                is ServerAiChatFailure -> failure.reason
                is ServerAiJobFailure -> when (failure.reason) {
                    ServerAiJobError.NETWORK, ServerAiJobError.INVALID_RESPONSE ->
                        if (attempt.sendAttempted && attempt.snapshot == null) ServerAiChatError.UNKNOWN
                        else if (failure.reason == ServerAiJobError.NETWORK) ServerAiChatError.NETWORK
                        else ServerAiChatError.INVALID_RESPONSE
                    ServerAiJobError.UNAUTHORIZED, ServerAiJobError.FORBIDDEN -> ServerAiChatError.UNAUTHORIZED
                    ServerAiJobError.SERVICE_UNAVAILABLE, ServerAiJobError.NOT_FOUND, ServerAiJobError.METHOD_NOT_ALLOWED -> ServerAiChatError.DISABLED
                    ServerAiJobError.INVALID_REQUEST, ServerAiJobError.REQUEST_TOO_LARGE -> ServerAiChatError.INVALID_REQUEST
                    else -> ServerAiChatError.REJECTED
                }
                is IOException -> if (attempt.sendAttempted && attempt.snapshot == null) ServerAiChatError.UNKNOWN else ServerAiChatError.NETWORK
                else -> if (attempt.sendAttempted && attempt.snapshot == null) ServerAiChatError.UNKNOWN else ServerAiChatError.INVALID_RESPONSE
            }
            throw failureDetails(reason, attempt, remote, (failure as? ServerAiJobFailure)?.reason)
        }
    }

    private suspend fun awaitAnswer(attempt: Attempt): String {
        var backoff = policy.initialPollDelayMs
        var polls = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val snapshot = attempt.snapshot!!
            when (snapshot.state) {
                ServerAiJobState.UNKNOWN -> throw ServerAiChatFailure(ServerAiChatError.UNKNOWN)
                ServerAiJobState.FAILED -> throw ServerAiChatFailure(ServerAiChatError.FAILED)
                ServerAiJobState.CANCELLED -> throw ServerAiChatFailure(ServerAiChatError.CANCELLED)
                ServerAiJobState.EXPIRED -> throw ServerAiChatFailure(ServerAiChatError.TIMED_OUT)
                ServerAiJobState.SUCCEEDED -> {
                    requireTimeRemaining(attempt)
                    if (!snapshot.resultAvailable || snapshot.result == null || snapshot.result.text.isBlank() ||
                        snapshot.resultExpiresMs == null || snapshot.resultExpiresMs <= serverTimeMs()
                    ) throw ServerAiChatFailure(ServerAiChatError.RESULT_UNAVAILABLE)
                    return snapshot.result.text
                }
                else -> Unit
            }
            val remaining = requireTimeRemaining(attempt)
            if (polls >= policy.maxPolls) throw ServerAiChatFailure(ServerAiChatError.TIMED_OUT)
            pause(minOf(backoff, remaining))
            currentCoroutineContext().ensureActive()
            requireTimeRemaining(attempt)
            acceptSnapshot(attempt, jobs.status(snapshot.jobId))
            polls++
            backoff = minOf(backoff * 2, policy.maxPollDelayMs)
        }
    }

    private fun requireServerRoute() {
        if (routingState() != ServerAiChatRouting.SERVER_REQUIRED) throw ServerAiChatFailure(ServerAiChatError.ROUTING_UNAVAILABLE)
    }

    private fun validateQuestion(question: String, capabilities: ServerAiCapabilities) {
        try {
            require(capabilities.maxTextChars in 1..4_096 && capabilities.maxTextBytes in 1..8_192)
            require(question.isNotBlank() && question.length <= capabilities.maxTextChars * 2)
            require(question.codePointCount(0, question.length) <= capabilities.maxTextChars)
            val bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(question))
            require(bytes.remaining() <= capabilities.maxTextBytes)
        } catch (_: Exception) {
            throw ServerAiChatFailure(ServerAiChatError.INVALID_REQUEST)
        }
    }

    private fun requireTimeRemaining(attempt: Attempt): Long {
        val now = serverTimeMs()
        val elapsed = Math.subtractExact(monotonicTimeMs(), attempt.startedAt)
        if (now <= 0 || elapsed < 0) throw ServerAiChatFailure(ServerAiChatError.INVALID_RESPONSE)
        val remaining = minOf(Math.subtractExact(attempt.submission!!.deadlineMs, now), attempt.windowMs - elapsed)
        if (remaining <= 0) throw ServerAiChatFailure(ServerAiChatError.TIMED_OUT)
        return remaining
    }

    private fun acceptSnapshot(attempt: Attempt, next: ServerAiJobSnapshot) {
        val submission = attempt.submission!!
        val previous = attempt.snapshot
        val consistent = canonicalUuid(next.jobId) && next.requestId == submission.requestId &&
            next.kind == "CHAT" && next.deadlineMs == submission.deadlineMs &&
            next.createdMs > 0 && next.createdMs < next.deadlineMs && next.deadlineMs - next.createdMs <= 900_000 &&
            (previous == null || (next.jobId == previous.jobId && next.createdMs == previous.createdMs &&
                (previous.startedMs == null || next.startedMs == previous.startedMs))) &&
            (next.startedMs == null || next.startedMs in next.createdMs until next.deadlineMs) &&
            (next.state != ServerAiJobState.SUCCEEDED || (next.finishedMs != null && next.startedMs != null &&
                next.finishedMs >= next.startedMs && next.finishedMs < next.deadlineMs))
        if (!consistent) throw ServerAiChatFailure(ServerAiChatError.INVALID_RESPONSE)
        attempt.snapshot = next
    }

    private suspend fun cancelKnownJob(attempt: Attempt): ServerAiChatCancellation {
        val known = attempt.snapshot ?: return if (attempt.sendAttempted) ServerAiChatCancellation.UNCONFIRMED
            else ServerAiChatCancellation.NOT_ATTEMPTED
        if (known.terminal) return ServerAiChatCancellation.NOT_ATTEMPTED
        return withContext(NonCancellable) {
            withTimeoutOrNull(policy.cancellationTimeoutMs) {
                try {
                    val started = monotonicTimeMs()
                    acceptSnapshot(attempt, jobs.cancel(known.jobId))
                    var polls = 0
                    while (true) {
                        when (attempt.snapshot!!.state) {
                            ServerAiJobState.CANCELLED -> return@withTimeoutOrNull ServerAiChatCancellation.CONFIRMED
                            ServerAiJobState.SUCCEEDED, ServerAiJobState.FAILED, ServerAiJobState.EXPIRED ->
                                return@withTimeoutOrNull ServerAiChatCancellation.ALREADY_TERMINAL
                            ServerAiJobState.UNKNOWN -> return@withTimeoutOrNull ServerAiChatCancellation.UNCONFIRMED
                            else -> Unit
                        }
                        val elapsed = Math.subtractExact(monotonicTimeMs(), started)
                        val remaining = policy.cancellationTimeoutMs - elapsed
                        if (elapsed < 0 || remaining <= 0 || polls++ >= policy.maxPolls) break
                        pause(minOf(policy.initialPollDelayMs, remaining))
                        if (monotonicTimeMs() - started >= policy.cancellationTimeoutMs) break
                        acceptSnapshot(attempt, jobs.status(known.jobId))
                    }
                    ServerAiChatCancellation.UNCONFIRMED
                } catch (_: Exception) {
                    ServerAiChatCancellation.UNCONFIRMED
                }
            } ?: ServerAiChatCancellation.UNCONFIRMED
        }
    }

    private fun failureDetails(
        reason: ServerAiChatError,
        attempt: Attempt,
        remote: ServerAiChatCancellation,
        jobError: ServerAiJobError? = null
    ) = ServerAiChatFailure(
        reason, attempt.submission?.requestId, attempt.snapshot?.jobId, jobError, remote,
        outcomeUnknown = attempt.sendAttempted && remote !in setOf(ServerAiChatCancellation.CONFIRMED, ServerAiChatCancellation.ALREADY_TERMINAL) &&
            (attempt.snapshot == null || attempt.snapshot?.terminal == false || attempt.snapshot?.state == ServerAiJobState.UNKNOWN)
    )

    private class Attempt {
        var submission: ServerAiChatSubmission? = null
        var snapshot: ServerAiJobSnapshot? = null
        var sendAttempted = false
        var startedAt = 0L
        var windowMs = 0L
    }

    private class SignedChatJobs(private val client: ServerAiJobClient) : ServerAiChatJobs {
        override suspend fun capabilities() = client.capabilities()
        override fun prepareChat(text: String, deadlineMs: Long) = client.prepareChat(text, deadlineMs)
        override suspend fun submit(submission: ServerAiChatSubmission) = client.submit(submission)
        override suspend fun status(jobId: String) = client.status(jobId)
        override suspend fun cancel(jobId: String) = client.cancel(jobId)
    }

    private companion object {
        fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
