package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiChatRepositoryCredentialInstrumentationTest {

    @Test
    fun requestUsesSecureCredentialOnlyInAuthorizationHeader() = runBlocking {
        val captured = AtomicReference<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                captured.set(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"choices":[{"message":{"content":"ok"}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val auditDao = RecordingAuditLogDao()
        val repository = AiChatRepository(
            baseUrlProvider = { "https://api.openai.com/v1/" },
            credentialReader = { "sk-instrumented-secret" },
            auditLogger = AuditLogger(auditDao, Gson()),
            httpClient = client
        )

        val response = repository.ask(question = "status", contextSummary = "local")
        val request = requireNotNull(captured.get())

        assertEquals("ok", response)
        assertEquals("Bearer sk-instrumented-secret", request.header("Authorization"))
        val requestBody = request.body?.let { body ->
            okio.Buffer().use { buffer ->
                body.writeTo(buffer)
                buffer.readUtf8()
            }
        }.orEmpty()
        assertFalse(requestBody.contains("sk-instrumented-secret"))
        assertFalse(auditDao.rows.joinToString().contains("sk-instrumented-secret"))
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
