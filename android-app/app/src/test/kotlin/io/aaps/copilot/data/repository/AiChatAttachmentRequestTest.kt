package io.aaps.copilot.data.repository

import android.app.Application
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiChatAttachmentRequestTest {
    @Test fun personalRequestContainsRealPdfImageAndCompleteTextContent() = runTest {
        val dir = Files.createTempDirectory("chat-request-").toFile()
        try {
            val pdf = File(dir, "sample.pdf").apply { writeText("%PDF-1.7\nsynthetic transport fixture\n%%EOF") }
            val image = File(dir, "sample.png").apply { writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5n8AAAAASUVORK5CYII=")) }
            val text = File(dir, "sample.txt").apply { writeText("Complete synthetic attachment") }
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{\"output_text\":\"Synthetic reply\"}"))
                val repository = repository(server)
                assertEquals("Synthetic reply", repository.askWithAttachments("Review these", "Synthetic context",
                    attachments = listOf(request(pdf, "application/pdf"), request(image, "image/png", AiChatAttachmentRequest.Kind.IMAGE),
                        request(text, "text/plain"))))
                val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                val content = body.getAsJsonArray("input").last().asJsonObject.getAsJsonArray("content")
                val fileItem = content.first { it.asJsonObject.get("type").asString == "input_file" }.asJsonObject
                assertEquals("sample.pdf", fileItem.get("filename").asString)
                assertEquals("data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf.readBytes()), fileItem.get("file_data").asString)
                val imageItem = content.first { it.asJsonObject.get("type").asString == "input_image" }.asJsonObject
                assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(image.readBytes()), imageItem.get("image_url").asString)
                assertTrue(content.toString().contains("Complete synthetic attachment"))
                assertFalse(content.toString().contains("metadata only"))
                assertEquals(1, server.requestCount)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingBinaryFileFailsBeforeAnyNetworkRequest() = runTest {
        MockWebServer().use { server ->
            assertTrue(runCatching { repository(server).askWithAttachments("Review", "c",
                attachments = listOf(AiChatAttachmentRequest("sample.zip", "application/zip", "/missing/sample.zip", AiChatAttachmentRequest.Kind.FILE)))
            }.exceptionOrNull() is AiChatAttachmentFailure)
            assertEquals(0, server.requestCount)
        }
    }

    private fun request(file: File, mime: String, kind: AiChatAttachmentRequest.Kind = AiChatAttachmentRequest.Kind.FILE) =
        AiChatAttachmentRequest(file.name, mime, file.path, kind)
    private fun repository(server: MockWebServer) = AiChatRepository(
        baseUrlProvider = { server.url("/v1/").toString() }, credentialReader = { "sample-key" },
        auditLogger = AuditLogger(object : AuditLogDao {
            override suspend fun insert(entity: AuditLogEntity) = Unit
            override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = flowOf(emptyList())
            override suspend fun recentByMessage(message: String, sinceTs: Long, limit: Int) = emptyList<AuditLogEntity>()
            override suspend fun deleteOlderThan(olderThan: Long) = 0
            override suspend fun deleteOlderThanInfoMessages(olderThan: Long, messages: List<String>) = 0
        }, Gson())
    )
}
