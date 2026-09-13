package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class AiChatRepositoryCredentialTest {

    @Test
    fun ask_failsBeforeRequestWhenSecureCredentialIsUnavailable() = runTest {
        MockWebServer().use { server ->
            val auditDao = RecordingAuditLogDao()
            var credentialReads = 0
            val repository = AiChatRepository(
                baseUrlProvider = { server.url("/v1/").toString() },
                credentialReader = {
                    credentialReads += 1
                    error("OpenAI credential is not configured")
                },
                auditLogger = AuditLogger(auditDao, Gson()),
                httpClient = OkHttpClient()
            )

            val failure = runCatching {
                repository.ask(question = "status", contextSummary = "local")
            }.exceptionOrNull()

            assertThat(credentialReads).isEqualTo(1)
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(auditDao.rows).isEmpty()
        }
    }

    private class RecordingAuditLogDao : AuditLogDao {
        val rows = mutableListOf<AuditLogEntity>()

        override suspend fun insert(entity: AuditLogEntity) {
            rows += entity
        }

        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = flowOf(rows.take(limit))

        override suspend fun recentByMessage(
            message: String,
            sinceTs: Long,
            limit: Int
        ): List<AuditLogEntity> = rows.filter { it.message == message }.take(limit)

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteOlderThanInfoMessages(
            olderThan: Long,
            messages: List<String>
        ): Int = 0
    }
}
