package io.aaps.copilot.data.repository

import io.aaps.copilot.security.ServerAiConnectionIdentity
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionPersistence
import io.aaps.copilot.security.ServerAiConnectionTransport
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAiChatGatewayTest {
    @Test
    fun routingIsClosedUntilConnectionLoadsAndPendingOrErrorStateCannotUsePersonalMode() = runTest {
        val identity = object : ServerAiConnectionIdentity {
            override fun existingChain(): List<String>? = error("No identity read expected")
            override fun create(challenge: ByteArray): List<String> = error("No key creation expected")
            override fun sign(message: ByteArray): String = error("No signing expected")
            override fun fingerprint(): String = error("No fingerprint expected")
        }
        for ((stored, expected) in listOf(
            null to ServerAiChatRouting.UNCONFIGURED,
            """{"pending":{"id":"$REQUEST","code":"0123456789ABCDEF"}}""" to ServerAiChatRouting.SERVER_REQUIRED
        )) {
            val connection = ServerAiConnectionManager(
                object : ServerAiConnectionPersistence {
                    override suspend fun read() = stored
                    override suspend fun write(value: String) = error("No persistence write expected")
                },
                identity,
                ServerAiConnectionTransport { _, _, _, _, _ -> error("No network expected") },
                clock = { NOW }
            )
            val gateway = ServerAiChatGateway(connection, syncChatContext = { _, _ -> error("No sync expected") })
            assertEquals(ServerAiChatRouting.SERVER_REQUIRED, gateway.routingState())
            connection.load()
            assertEquals(expected, gateway.routingState())
            if (stored == null) {
                connection.activate("invalid")
                assertEquals(ServerAiChatRouting.SERVER_REQUIRED, gateway.routingState())
            }
        }
    }

    @Test
    fun syncsCompleteContextBeforeOneImmutableSubmissionAndPollsWithBackoff() = runTest {
        val fixture = Fixture(testScheduler)
        val question = "  complete question\n"
        val history = mutableListOf(AiChatTurn("user", "previous question"))
        fixture.jobs.onSubmit = {
            history.clear()
            fixture.jobs.snapshot(ServerAiJobState.QUEUED)
        }
        var polls = 0
        fixture.jobs.onStatus = {
            if (++polls < 4) fixture.jobs.snapshot(ServerAiJobState.RUNNING)
            else fixture.jobs.snapshot(ServerAiJobState.SUCCEEDED)
        }

        assertEquals("Synthetic answer", fixture.gateway.ask(question, "full context", history))

        assertEquals(listOf("capabilities", "sync", "prepare", "submit", "status", "status", "status", "status"), fixture.events)
        assertEquals("full context", fixture.syncedContext)
        assertEquals(listOf(AiChatTurn("user", "previous question")), fixture.syncedHistory)
        assertEquals(listOf(question), fixture.jobs.preparedTexts)
        assertEquals(1, fixture.jobs.submissions.size)
        assertSame(fixture.jobs.prepared, fixture.jobs.submissions.single())
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L), fixture.delays)
    }

    @Test
    fun disabledCapabilitiesDoNotSyncPrepareOrSubmit() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.capability = fixture.jobs.capability.copy(inferenceEnabled = false)

        assertEquals(ServerAiChatError.DISABLED, failure { fixture.gateway.ask("question", "context") }.reason)
        assertEquals(listOf("capabilities"), fixture.events)
    }

    @Test
    fun capabilityNetworkFailureIsExplicitAndDoesNotSubmit() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.capabilityFailure = ServerAiJobFailure(ServerAiJobError.NETWORK)

        val error = failure { fixture.gateway.ask("question", "context") }

        assertEquals(ServerAiChatError.NETWORK, error.reason)
        assertEquals(ServerAiJobError.NETWORK, error.jobError)
        assertTrue(fixture.jobs.submissions.isEmpty())
    }

    @Test
    fun unsupportedCapabilitiesAndOversizeQuestionsFailWithoutTruncation() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.capability = fixture.jobs.capability.copy(inputModalities = listOf("IMAGE"))
        assertEquals(ServerAiChatError.UNSUPPORTED_MODALITY, failure { fixture.gateway.ask("q", "context") }.reason)
        fixture.jobs.capability = capabilities()
        for (question in listOf("x".repeat(4_097), "\u20ac".repeat(2_731), "\uD800", "")) {
            assertEquals(ServerAiChatError.INVALID_REQUEST, failure { fixture.gateway.ask(question, "context") }.reason)
        }
        assertFalse(fixture.events.contains("sync"))
        assertTrue(fixture.jobs.preparedTexts.isEmpty())
    }

    @Test
    fun contextSyncFailurePreventsModelSubmission() = runTest {
        val fixture = Fixture(testScheduler, sync = { _, _ -> throw IOException("synthetic sync failure") })

        val error = failure { fixture.gateway.ask("question", "complete context", listOf(AiChatTurn("assistant", "full reply"))) }

        assertEquals(ServerAiChatError.CONTEXT_SYNC, error.reason)
        assertFalse(fixture.events.contains("prepare"))
        assertTrue(fixture.jobs.submissions.isEmpty())
        assertFalse(error.toString().contains("synthetic sync failure"))
    }

    @Test
    fun preparesDeadlineAfterSlowSyncUsingServerClock() = runTest {
        val fixture = Fixture(testScheduler, sync = { _, _ -> kotlinx.coroutines.delay(10_000) })

        fixture.gateway.ask("question", "context")

        assertEquals(NOW + 850_000, fixture.jobs.prepared!!.deadlineMs)
    }

    @Test
    fun longAnalysisCanCompleteBeyondTwoMinutesWithoutAnotherSubmission() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onStatus = {
            fixture.jobs.snapshot(
                if (testScheduler.currentTime >= 130_000) ServerAiJobState.SUCCEEDED
                else ServerAiJobState.RUNNING
            )
        }

        assertEquals("Synthetic answer", fixture.gateway.ask("question", "context"))
        assertTrue(testScheduler.currentTime >= 130_000)
        assertEquals(1, fixture.jobs.submissions.size)
    }

    @Test
    fun ambiguousSubmitIsUnknownAndNeverResubmits() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.onSubmit = { throw ServerAiJobFailure(ServerAiJobError.NETWORK) }

        val error = failure { fixture.gateway.ask("question", "context") }

        assertEquals(ServerAiChatError.UNKNOWN, error.reason)
        assertEquals(ServerAiJobError.NETWORK, error.jobError)
        assertEquals(REQUEST, error.requestId)
        assertNull(error.jobId)
        assertEquals(1, fixture.jobs.preparedTexts.size)
        assertEquals(1, fixture.jobs.submissions.size)
        assertFalse(fixture.events.contains("status"))
        assertFalse(fixture.events.contains("cancel"))
    }

    @Test
    fun overallTimeoutDuringAmbiguousSubmitIsUnknownWithoutAnotherSend() = runTest {
        val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(overallTimeoutMs = 500))
        fixture.jobs.onSubmit = { awaitCancellation() }

        val error = failure { fixture.gateway.ask("q", "c") }

        assertEquals(ServerAiChatError.UNKNOWN, error.reason)
        assertTrue(error.outcomeUnknown)
        assertEquals(REQUEST, error.requestId)
        assertEquals(1, fixture.jobs.submissions.size)
        assertTrue(fixture.jobs.cancelledIds.isEmpty())
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test
    fun overallTimeoutDuringSyncHasNoModelSideEffects() = runTest {
        val fixture = Fixture(testScheduler, sync = { _, _ -> awaitCancellation() }, policy = ServerAiChatPolicy(overallTimeoutMs = 500))

        val error = failure { fixture.gateway.ask("q", "c") }

        assertEquals(ServerAiChatError.TIMED_OUT, error.reason)
        assertFalse(error.outcomeUnknown)
        assertTrue(fixture.jobs.preparedTexts.isEmpty())
        assertTrue(fixture.jobs.submissions.isEmpty())
    }

    @Test
    fun pollingNetworkFailureRetainsKnownIdentityAndDoesNotResubmit() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onStatus = { throw ServerAiJobFailure(ServerAiJobError.NETWORK) }

        val error = failure { fixture.gateway.ask("q", "c") }

        assertEquals(ServerAiChatError.NETWORK, error.reason)
        assertEquals(REQUEST, error.requestId)
        assertEquals(JOB, error.jobId)
        assertEquals(ServerAiChatCancellation.CONFIRMED, error.remoteCancellation)
        assertFalse(error.outcomeUnknown)
        assertEquals(1, fixture.jobs.submissions.size)
    }

    @Test
    fun everyReceiptMustKeepRequestJobKindCreationStartAndDeadlineCorrelation() = runTest {
        val mutations: List<(ServerAiJobSnapshot) -> ServerAiJobSnapshot> = listOf(
            { it.copy(requestId = OTHER) },
            { it.copy(jobId = OTHER) },
            { it.copy(kind = "OTHER") },
            { it.copy(deadlineMs = it.deadlineMs + 1) },
            { it.copy(createdMs = it.createdMs - 1) },
            { it.copy(startedMs = it.startedMs!! + 1) }
        )
        for (mutate in mutations) {
            val fixture = Fixture(testScheduler)
            fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
            fixture.jobs.onStatus = { mutate(fixture.jobs.snapshot(ServerAiJobState.SUCCEEDED)) }

            val error = failure { fixture.gateway.ask("question", "context") }

            assertEquals(ServerAiChatError.INVALID_RESPONSE, error.reason)
            assertEquals(JOB, error.jobId)
            assertEquals(1, fixture.jobs.submissions.size)
            assertEquals(JOB, fixture.jobs.cancelledIds.single())
        }
    }

    @Test
    fun badInitialReceiptNeverBecomesCancellationAuthority() = runTest {
        val fixture = Fixture(testScheduler)
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.SUCCEEDED).copy(requestId = OTHER) }

        assertEquals(ServerAiChatError.INVALID_RESPONSE, failure { fixture.gateway.ask("q", "c") }.reason)
        assertTrue(fixture.jobs.cancelledIds.isEmpty())
    }

    @Test
    fun rejectsStateRegressionAndStaleOrLateSuccess() = runTest {
        val regression = Fixture(testScheduler)
        regression.jobs.onSubmit = { regression.jobs.snapshot(ServerAiJobState.RUNNING) }
        regression.jobs.onStatus = { regression.jobs.snapshot(ServerAiJobState.QUEUED) }
        assertEquals(ServerAiChatError.INVALID_RESPONSE, failure { regression.gateway.ask("q", "c") }.reason)

        val stale = Fixture(testScheduler)
        stale.jobs.onSubmit = { stale.jobs.snapshot(ServerAiJobState.SUCCEEDED).copy(resultExpiresMs = NOW) }
        assertEquals(ServerAiChatError.RESULT_UNAVAILABLE, failure { stale.gateway.ask("q", "c") }.reason)

        val late = Fixture(testScheduler)
        late.jobs.onSubmit = {
            val value = late.jobs.snapshot(ServerAiJobState.SUCCEEDED)
            value.copy(finishedMs = value.deadlineMs)
        }
        assertEquals(ServerAiChatError.INVALID_RESPONSE, failure { late.gateway.ask("q", "c") }.reason)
    }

    @Test
    fun terminalStatesAndMissingResultNeverFabricateAnswers() = runTest {
        val cases = mapOf(
            ServerAiJobState.UNKNOWN to ServerAiChatError.UNKNOWN,
            ServerAiJobState.FAILED to ServerAiChatError.FAILED,
            ServerAiJobState.CANCELLED to ServerAiChatError.CANCELLED,
            ServerAiJobState.EXPIRED to ServerAiChatError.TIMED_OUT
        )
        for ((state, expected) in cases) {
            val fixture = Fixture(testScheduler)
            fixture.jobs.onSubmit = { fixture.jobs.snapshot(state) }
            assertEquals(expected, failure { fixture.gateway.ask("q", "c") }.reason)
        }
        val fixture = Fixture(testScheduler)
        fixture.jobs.onSubmit = {
            fixture.jobs.snapshot(ServerAiJobState.SUCCEEDED).copy(resultAvailable = false, result = null, resultExpiresMs = null)
        }
        assertEquals(ServerAiChatError.RESULT_UNAVAILABLE, failure { fixture.gateway.ask("q", "c") }.reason)
    }

    @Test
    fun deadlineStopsPollingAndRequestsConfirmedRemoteCancellation() = runTest {
        val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(jobTimeoutMs = 600))
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onStatus = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }

        val error = failure { fixture.gateway.ask("q", "c") }

        assertEquals(ServerAiChatError.TIMED_OUT, error.reason)
        assertEquals(ServerAiChatCancellation.CONFIRMED, error.remoteCancellation)
        assertEquals(listOf(250L, 350L), fixture.delays)
        assertEquals(listOf(JOB), fixture.jobs.cancelledIds)
        assertEquals(1, fixture.events.count { it == "status" })
    }

    @Test
    fun backwardServerClockCannotExtendMonotonicDeadline() = runTest {
        val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(jobTimeoutMs = 600))
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onStatus = {
            fixture.serverOffset -= 100_000
            fixture.jobs.snapshot(ServerAiJobState.RUNNING)
        }

        assertEquals(ServerAiChatError.TIMED_OUT, failure { fixture.gateway.ask("q", "c") }.reason)
        assertTrue(testScheduler.currentTime < 5_000)
    }

    @Test
    fun pollingBudgetIsBoundedEvenWhenInjectedClockAndDelayDoNotAdvance() = runTest {
        val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(maxPolls = 3), advanceDelay = false)
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onStatus = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }

        assertEquals(ServerAiChatError.TIMED_OUT, failure { fixture.gateway.ask("q", "c") }.reason)
        assertEquals(3, fixture.events.count { it == "status" })
    }

    @Test
    fun coroutineCancellationCancelsKnownJobAndWaitsForPositiveConfirmation() = runTest {
        val reached = CompletableDeferred<Unit>()
        val fixture = Fixture(testScheduler)
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onStatus = {
            reached.complete(Unit)
            awaitCancellation()
        }
        fixture.jobs.onCancel = {
            fixture.jobs.onStatus = { fixture.jobs.snapshot(ServerAiJobState.CANCELLED) }
            fixture.jobs.snapshot(ServerAiJobState.CANCEL_REQUESTED)
        }
        val task = launch { fixture.gateway.ask("q", "c") }
        reached.await()

        task.cancelAndJoin()

        assertTrue(task.isCancelled)
        assertEquals(listOf(JOB), fixture.jobs.cancelledIds)
        assertEquals(listOf("cancel", "status"), fixture.events.filter { it == "cancel" || it == "status" }.takeLast(2))
        assertEquals(1, fixture.jobs.submissions.size)
    }

    @Test
    fun cancellationDuringSyncOrAmbiguousSendDoesNotResubmit() = runTest {
        for (duringSync in listOf(true, false)) {
            val reached = CompletableDeferred<Unit>()
            val fixture = Fixture(testScheduler, sync = { _, _ ->
                if (duringSync) {
                    reached.complete(Unit)
                    awaitCancellation()
                }
            })
            fixture.jobs.onSubmit = {
                reached.complete(Unit)
                awaitCancellation()
            }
            val task = launch { fixture.gateway.ask("q", "c") }
            reached.await()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertEquals(if (duringSync) 0 else 1, fixture.jobs.submissions.size)
            assertTrue(fixture.jobs.cancelledIds.isEmpty())
        }
    }

    @Test
    fun cancellationWithoutPositiveStopIsExplicitAndBounded() = runTest {
        val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(jobTimeoutMs = 100, cancellationTimeoutMs = 500))
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onCancel = { fixture.jobs.snapshot(ServerAiJobState.CANCEL_REQUESTED) }
        fixture.jobs.onStatus = { fixture.jobs.snapshot(ServerAiJobState.UNKNOWN) }

        val error = failure { fixture.gateway.ask("q", "c") }

        assertEquals(ServerAiChatError.TIMED_OUT, error.reason)
        assertEquals(ServerAiChatCancellation.UNCONFIRMED, error.remoteCancellation)
        assertTrue(testScheduler.currentTime <= 600)
    }

    @Test
    fun cancellationAcknowledgementMustMatchOriginalJobAndRequest() = runTest {
        for (changeRequest in listOf(true, false)) {
            val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(jobTimeoutMs = 100))
            fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
            fixture.jobs.onCancel = {
                val cancelled = fixture.jobs.snapshot(ServerAiJobState.CANCELLED)
                if (changeRequest) cancelled.copy(requestId = OTHER) else cancelled.copy(deadlineMs = cancelled.deadlineMs + 1)
            }

            val error = failure { fixture.gateway.ask("q", "c") }

            assertEquals(ServerAiChatCancellation.UNCONFIRMED, error.remoteCancellation)
            assertTrue(error.outcomeUnknown)
            assertEquals(JOB, error.jobId)
        }
    }

    @Test
    fun unresponsiveRemoteCancellationCannotHoldCoroutineForever() = runTest {
        val fixture = Fixture(testScheduler, policy = ServerAiChatPolicy(jobTimeoutMs = 100, cancellationTimeoutMs = 500))
        fixture.jobs.onSubmit = { fixture.jobs.snapshot(ServerAiJobState.RUNNING) }
        fixture.jobs.onCancel = { awaitCancellation() }

        val error = failure { fixture.gateway.ask("q", "c") }

        assertEquals(ServerAiChatCancellation.UNCONFIRMED, error.remoteCancellation)
        assertTrue(error.outcomeUnknown)
        assertEquals(600L, testScheduler.currentTime)
    }

    private suspend fun failure(block: suspend () -> Unit): ServerAiChatFailure {
        return try {
            block()
            throw AssertionError("Expected server chat failure")
        } catch (failure: ServerAiChatFailure) {
            failure
        }
    }

    private class Fixture(
        scheduler: TestCoroutineScheduler,
        sync: suspend (String, List<AiChatTurn>) -> Unit = { _, _ -> },
        policy: ServerAiChatPolicy = ServerAiChatPolicy(),
        advanceDelay: Boolean = true
    ) {
        val events = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val jobs = FakeJobs(events) { NOW + scheduler.currentTime }
        var serverOffset = 0L
        var syncedContext: String? = null
        var syncedHistory: List<AiChatTurn>? = null
        val gateway = ServerAiChatGateway(
            jobs = jobs,
            routingProvider = { ServerAiChatRouting.SERVER_REQUIRED },
            syncChatContext = { context, history ->
                events += "sync"
                syncedContext = context
                syncedHistory = history
                sync(context, history)
            },
            serverTimeMs = { NOW + scheduler.currentTime + serverOffset },
            monotonicTimeMs = { scheduler.currentTime },
            pause = { ms ->
                delays += ms
                if (advanceDelay) kotlinx.coroutines.delay(ms)
            },
            policy = policy
        )
    }

    private class FakeJobs(val events: MutableList<String>, val now: () -> Long) : ServerAiChatJobs {
        var capability = ServerAiChatGatewayTest.capabilities()
        var capabilityFailure: Exception? = null
        var prepared: ServerAiChatSubmission? = null
        val preparedTexts = mutableListOf<String>()
        val submissions = mutableListOf<ServerAiChatSubmission>()
        val cancelledIds = mutableListOf<String>()
        var onSubmit: suspend (ServerAiChatSubmission) -> ServerAiJobSnapshot = { snapshot(ServerAiJobState.SUCCEEDED) }
        var onStatus: suspend (String) -> ServerAiJobSnapshot = { snapshot(ServerAiJobState.SUCCEEDED) }
        var onCancel: suspend (String) -> ServerAiJobSnapshot = { snapshot(ServerAiJobState.CANCELLED) }

        override suspend fun capabilities(): ServerAiCapabilities {
            events += "capabilities"
            capabilityFailure?.let { throw it }
            return capability
        }

        override fun prepareChat(text: String, deadlineMs: Long): ServerAiChatSubmission {
            events += "prepare"
            preparedTexts += text
            return ServerAiChatSubmission(REQUEST, deadlineMs, text.toByteArray()).also { prepared = it }
        }

        override suspend fun submit(submission: ServerAiChatSubmission): ServerAiJobSnapshot {
            events += "submit"
            submissions += submission
            return onSubmit(submission)
        }

        override suspend fun status(jobId: String): ServerAiJobSnapshot {
            events += "status"
            assertEquals(JOB, jobId)
            return onStatus(jobId)
        }

        override suspend fun cancel(jobId: String): ServerAiJobSnapshot {
            events += "cancel"
            cancelledIds += jobId
            return onCancel(jobId)
        }

        fun snapshot(state: ServerAiJobState): ServerAiJobSnapshot {
            val terminal = state.terminal
            val succeeded = state == ServerAiJobState.SUCCEEDED
            return ServerAiJobSnapshot(
                JOB, REQUEST, "CHAT", state, NOW, prepared!!.deadlineMs,
                if (state == ServerAiJobState.QUEUED) null else NOW,
                if (terminal) now() else null,
                succeeded, if (succeeded) ServerAiJobResult("Synthetic answer") else null,
                if (succeeded) now() + 100_000 else null
            )
        }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val REQUEST = "2ec58479-e05b-4805-87f6-5bb06b04bb2d"
        private const val JOB = "f1e19b82-e0f2-4c50-b2bf-03c80c906f2c"
        private const val OTHER = "a19403e5-696c-4c5d-9b52-b8efafc7fe37"

        private fun capabilities() = ServerAiCapabilities(
            "server-codex-chat-r1a.1", true, listOf("CHAT"), listOf("TEXT"), listOf("TEXT"),
            4_096, 8_192, 8_192, 16_384, 65_536, 900_000
        )
    }
}
