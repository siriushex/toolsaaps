package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class AiChatRepositoryServerRoutingTest {
    @Test
    fun serverTextChatReadsNeitherPersonalKeyNorPersonalEndpoint() = runTest {
        var syncedContext: String? = null
        var syncedHistory: List<AiChatTurn>? = null
        val jobs = Jobs()
        val gateway = gateway(jobs) { context, history ->
            syncedContext = context
            syncedHistory = history
        }
        val repository = repository(gateway)
        val history = listOf(AiChatTurn("assistant", "complete previous reply"))

        assertEquals("Synthetic answer", repository.ask("entire question", "entire context", history))

        assertEquals(ServerAiChatRouting.SERVER_REQUIRED, repository.chatRoutingState())
        assertEquals("entire context", syncedContext)
        assertEquals(history, syncedHistory)
        assertEquals("entire question", jobs.text)
        assertEquals(1, jobs.sends)
    }

    @Test
    fun allServerRoutingFailuresStayOnServerAndNeverReadPersonalKey() = runTest {
        for (reason in listOf(ServerAiJobError.NETWORK, ServerAiJobError.UNAUTHORIZED,
            ServerAiJobError.SERVICE_UNAVAILABLE, ServerAiJobError.NOT_FOUND, ServerAiJobError.INVALID_RESPONSE)) {
            val jobs = Jobs().also { it.failure = ServerAiJobFailure(reason) }
            val error = runCatching { repository(gateway(jobs)).ask("q", "c") }.exceptionOrNull()
            assertTrue(error is ServerAiChatFailure)
            assertEquals(0, jobs.sends)
        }
        val jobs = Jobs().also { it.enabled = false }
        assertEquals(ServerAiChatError.DISABLED, serverFailure { repository(gateway(jobs)).ask("q", "c") }.reason)
    }

    @Test
    fun configuredRouteWithoutGatewayFailsClosed() = runTest {
        val repository = repository(null, route = { ServerAiChatRouting.SERVER_REQUIRED })

        assertEquals(ServerAiChatError.ROUTING_UNAVAILABLE, serverFailure { repository.ask("q", "c") }.reason)
    }

    @Test
    fun personalOverrideCannotBypassConfiguredGateway() = runTest {
        val jobs = Jobs()
        val repository = repository(gateway(jobs), route = { ServerAiChatRouting.UNCONFIGURED })

        assertEquals("Synthetic answer", repository.ask("q", "c"))
        assertEquals(ServerAiChatRouting.SERVER_REQUIRED, repository.chatRoutingState())
        assertEquals(1, jobs.sends)
    }

    @Test
    fun serverModalitiesRejectBeforeReadingKeysEndpointsOrLocalFiles() = runTest {
        val jobs = Jobs()
        val repository = repository(gateway(jobs))
        val attachment = AiChatAttachmentRequest("synthetic.png", "image/png", "missing-chat-image.png", AiChatAttachmentRequest.Kind.IMAGE)

        val errors = listOf(
            serverFailure { repository.askWithAttachments("q", "c", attachments = listOf(attachment)) },
            serverFailure { repository.transcribeAudio(File("missing-chat-audio.m4a")) },
            serverFailure { repository.synthesizeSpeech("synthetic speech", File("missing-chat-output")) }
        )

        errors.forEach { error ->
            assertEquals(ServerAiChatError.UNSUPPORTED_MODALITY, error.reason)
            assertTrue(error.message!!.contains("typed text"))
        }
        assertEquals(0, jobs.capabilityReads)
        assertEquals(0, jobs.sends)
    }

    @Test
    fun emptyAttachmentListUsesServerTextChat() = runTest {
        val jobs = Jobs()

        assertEquals("Synthetic answer", repository(gateway(jobs)).askWithAttachments("q", "c"))
        assertEquals(1, jobs.sends)
    }

    @Test
    fun explicitUnconfiguredRouteRetainsLegacyCredentialBehaviorForChatAndModalities() = runTest {
        var credentialReads = 0
        val repository = AiChatRepository(
            baseUrlProvider = { "https://example.invalid/v1/" },
            credentialReader = {
                credentialReads++
                throw LegacyCredentialRead()
            },
            auditLogger = logger(),
            serverRoutingProvider = { ServerAiChatRouting.UNCONFIGURED }
        )
        val attachment = AiChatAttachmentRequest("synthetic.txt", "text/plain", "missing-chat-file.txt", AiChatAttachmentRequest.Kind.FILE)
        val failures = listOf(
            runCatching { repository.ask("q", "c") }.exceptionOrNull(),
            runCatching { repository.askWithAttachments("q", "c", attachments = listOf(attachment)) }.exceptionOrNull(),
            runCatching { repository.transcribeAudio(File("missing-chat-audio.m4a")) }.exceptionOrNull(),
            runCatching { repository.synthesizeSpeech("text", File("missing-chat-output")) }.exceptionOrNull()
        )
        failures.forEach { assertTrue(it is LegacyCredentialRead) }
        assertEquals(4, credentialReads)
    }

    private fun repository(
        gateway: ServerAiChatGateway?,
        route: () -> ServerAiChatRouting = { gateway?.routingState() ?: ServerAiChatRouting.UNCONFIGURED }
    ) = AiChatRepository(
        baseUrlProvider = { throw AssertionError("Server chat must not read the personal endpoint") },
        credentialReader = { throw AssertionError("Server chat must not read personal credentials") },
        auditLogger = logger(),
        httpClient = OkHttpClient.Builder().addInterceptor { throw AssertionError("No personal request is permitted") }.build(),
        serverGateway = gateway,
        serverRoutingProvider = route
    )

    private fun gateway(jobs: Jobs, sync: suspend (String, List<AiChatTurn>) -> Unit = { _, _ -> }) = ServerAiChatGateway(
        jobs, { ServerAiChatRouting.SERVER_REQUIRED }, sync, { NOW }
    )

    private suspend fun serverFailure(block: suspend () -> Unit): ServerAiChatFailure = try {
        block()
        throw AssertionError("Expected a server chat error")
    } catch (failure: ServerAiChatFailure) {
        failure
    }

    private class LegacyCredentialRead : Exception()

    private class Jobs : ServerAiChatJobs {
        var enabled = true
        var failure: ServerAiJobFailure? = null
        var text: String? = null
        var sends = 0
        var capabilityReads = 0
        override suspend fun capabilities(): ServerAiCapabilities {
            capabilityReads++
            failure?.let { throw it }
            return ServerAiCapabilities("server-codex-chat-r1a.1", enabled, listOf("CHAT"), listOf("TEXT"), listOf("TEXT"),
                4_096, 8_192, 8_192, 16_384, 65_536, 900_000)
        }

        override fun prepareChat(text: String, deadlineMs: Long): ServerAiChatSubmission {
            this.text = text
            return ServerAiChatSubmission(REQUEST, deadlineMs, text.toByteArray())
        }

        override suspend fun submit(submission: ServerAiChatSubmission): ServerAiJobSnapshot {
            sends++
            return ServerAiJobSnapshot(JOB, REQUEST, "CHAT", ServerAiJobState.SUCCEEDED,
                NOW, submission.deadlineMs, NOW, NOW, true, ServerAiJobResult("Synthetic answer"), NOW + 60_000)
        }

        override suspend fun status(jobId: String): ServerAiJobSnapshot = throw AssertionError("No polling expected")
        override suspend fun cancel(jobId: String): ServerAiJobSnapshot = throw AssertionError("No cancellation expected")
    }

    private fun logger() = AuditLogger(object : AuditLogDao {
        override suspend fun insert(entity: AuditLogEntity) = Unit
        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = flowOf(emptyList())
        override suspend fun recentByMessage(message: String, sinceTs: Long, limit: Int): List<AuditLogEntity> = emptyList()
        override suspend fun deleteOlderThan(olderThan: Long): Int = 0
        override suspend fun deleteOlderThanInfoMessages(olderThan: Long, messages: List<String>): Int = 0
    }, Gson())

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val REQUEST = "2ec58479-e05b-4805-87f6-5bb06b04bb2d"
        const val JOB = "f1e19b82-e0f2-4c50-b2bf-03c80c906f2c"
    }
}
