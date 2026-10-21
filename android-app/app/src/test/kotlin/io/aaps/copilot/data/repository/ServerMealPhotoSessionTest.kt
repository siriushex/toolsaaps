package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.security.ServerAiConnectionIdentity
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionPersistence
import io.aaps.copilot.security.ServerAiConnectionPhase
import io.aaps.copilot.security.ServerAiConnectionResponse
import io.aaps.copilot.security.ServerAiConnectionTransport
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class ServerMealPhotoSessionTest {
    private val now = 1_800_000_000_000L

    @Test fun savedSessionChecksSignedPhotoCapabilityWithoutManualReconnect() = runTest {
        val fixture = Fixture()
        fixture.connection.load()
        assertThat(fixture.connection.state.value.phase).isEqualTo(ServerAiConnectionPhase.SAVED)

        assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.READY)
        assertThat(fixture.paths).containsExactly("/api/ai/v1/capabilities")
        assertThat(fixture.signatures).isEqualTo(1)
    }

    @Test fun unloadedSessionIsReadBeforeDecidingThatConnectionIsMissing() = runTest {
        val fixture = Fixture()
        assertThat(fixture.connection.state.value.revision).isEqualTo(0)

        assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.READY)
        assertThat(fixture.paths).containsExactly("/api/ai/v1/capabilities")
    }

    @Test fun savedExpiredAccessRefreshesBeforePhotoCapability() = runTest {
        val fixture = Fixture(accessExpires = now - 1)
        fixture.connection.load()

        assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.READY)
        assertThat(fixture.paths).containsExactly(
            "/api/ai/v1/session/refresh", "/api/ai/v1/capabilities"
        ).inOrder()
        assertThat(fixture.connection.state.value.phase).isEqualTo(ServerAiConnectionPhase.ACTIVE)
    }

    @Test fun textOnlyServerRemainsUnsupportedAfterRestoringSession() = runTest {
        val fixture = Fixture().apply { photoSupported = false }
        fixture.connection.load()

        assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.UNSUPPORTED)
        assertThat(fixture.paths).containsExactly("/api/ai/v1/capabilities")
        assertThat(fixture.photoPosts).isEqualTo(0)
    }

    @Test fun restoredSessionNetworkFailureIsNotReportedAsMissingConfiguration() = runTest {
        val fixture = Fixture().apply { networkFailure = true }
        fixture.connection.load()

        assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.UNAVAILABLE)
        assertThat(fixture.paths).containsExactly("/api/ai/v1/capabilities")
        assertThat(fixture.photoPosts).isEqualTo(0)
    }

    @Test fun emptyAndUnfinishedActivationCannotUploadOrCreateADeviceKey() = runTest {
        for (record in listOf(null, """{"pending":{"id":"${UUID.randomUUID()}","code":"0123456789ABCDEF"}}""")) {
            val fixture = Fixture().apply { store.value = record }
            fixture.connection.load()

            assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.NEEDS_CONNECTION)
            assertThat(fixture.paths).isEmpty()
            assertThat(fixture.signatures).isEqualTo(0)
        }
    }

    @Test fun serverAuthorizationRefusalRemainsMissingConnectionAndNeverUploads() = runTest {
        val fixture = Fixture().apply { capabilityStatus = 401 }
        fixture.connection.load()

        assertThat(fixture.gateway.availability()).isEqualTo(MealPhotoAvailability.NEEDS_CONNECTION)
        assertThat(fixture.paths).containsExactly("/api/ai/v1/capabilities")
        assertThat(fixture.photoPosts).isEqualTo(0)
    }

    @Test fun sessionRemovedAfterCapabilityCannotAuthorizeImageSubmission() = runBlocking {
        val fixture = Fixture().apply { clearAfterCapability = true }
        fixture.connection.load()
        val result = MealPhotoAnalysisRepository(fixture.gateway).analyze(
            UUID.randomUUID().toString(), byteArrayOf(-1, -40, -1, 0, -1, -39)
        )

        assertThat(result).isEqualTo(MealPhotoAnalysisResult.Failure(
            MealPhotoAnalysisResult.Failure.Reason.NOT_CONFIGURED
        ))
        assertThat(fixture.paths).containsExactly("/api/ai/v1/capabilities")
        assertThat(fixture.photoPosts).isEqualTo(0)
    }

    @Test fun savedSessionCompletesOneSignedPhotoAnalysisWithoutReconnect() = runBlocking {
        val fixture = Fixture().apply { allowPhotoResult = true }
        fixture.connection.load()

        val result = MealPhotoAnalysisRepository(fixture.gateway).analyze(
            UUID.randomUUID().toString(), byteArrayOf(-1, -40, -1, 0, -1, -39)
        )

        assertThat(result).isInstanceOf(MealPhotoAnalysisResult.Success::class.java)
        assertThat((result as MealPhotoAnalysisResult.Success).estimate.mealName).isEqualTo("Rice")
        assertThat(fixture.paths).containsExactly(
            "/api/ai/v1/capabilities", "/api/ai/v1/meal-photo/jobs"
        ).inOrder()
        assertThat(fixture.photoPosts).isEqualTo(1)
        assertThat(fixture.signatures).isEqualTo(2)
    }

    private class MemoryStore(var value: String?) : ServerAiConnectionPersistence {
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }

    private inner class Fixture(accessExpires: Long = now + 600_000) {
        val store = MemoryStore("""{"session":${tokens(accessExpires)}}""")
        val paths = mutableListOf<String>()
        var photoSupported = true
        var networkFailure = false
        var capabilityStatus = 200
        var clearAfterCapability = false
        var allowPhotoResult = false
        var signatures = 0
        var photoPosts = 0
        private val identity = object : ServerAiConnectionIdentity {
            override fun existingChain(): List<String> = error("No activation expected")
            override fun create(challenge: ByteArray): List<String> = error("No key creation expected")
            override fun sign(message: ByteArray): String { signatures++; return "synthetic-proof" }
            override fun fingerprint() = "a".repeat(64)
        }
        val connection = ServerAiConnectionManager(store, identity,
            ServerAiConnectionTransport { method, path, body, headers, limit ->
                paths += path
                assertThat(headers["X-Copilot-Signature"]).isEqualTo("synthetic-proof")
                if (networkFailure) throw IOException("synthetic unavailable transport")
                when (path) {
                    "/api/ai/v1/session/refresh" -> {
                        assertThat(method).isEqualTo("POST")
                        assertThat(body).isNotEmpty()
                        ServerAiConnectionResponse(200, tokens(now + 600_000).toByteArray())
                    }
                    "/api/ai/v1/capabilities" -> {
                        assertThat(method).isEqualTo("GET")
                        assertThat(body).isEmpty()
                        assertThat(limit).isEqualTo(65_536)
                        assertThat(headers["Authorization"]).startsWith("Bearer access.")
                        if (clearAfterCapability) store.value = null
                        ServerAiConnectionResponse(capabilityStatus, capability(photoSupported))
                    }
                    "/api/ai/v1/meal-photo/jobs" -> {
                        photoPosts++
                        check(allowPhotoResult) { "No image submission expected" }
                        assertThat(method).isEqualTo("POST")
                        assertThat(HistoryJson.parse(body, 3 * 1024 * 1024).keys)
                            .containsExactly("schemaVersion", "mimeType", "imageBase64")
                        ServerAiConnectionResponse(202, completedPhoto(headers))
                    }
                    else -> error("Unexpected route")
                }
            }, clock = { now })
        val gateway = ServerMealPhotoGateway(connection)
    }

    private fun completedPhoto(headers: Map<String, String>) = HistoryJson.bytes(buildJsonObject {
        put("job_id", UUID.randomUUID().toString())
        put("request_id", checkNotNull(headers["X-Copilot-Request-Id"]))
        put("kind", "MEAL_PHOTO"); put("state", "SUCCEEDED")
        put("created_ms", now); put("deadline_ms", checkNotNull(headers["X-Copilot-Deadline-Ms"]).toLong())
        put("started_ms", now); put("finished_ms", now); put("result_available", true)
        put("result_expires_ms", now + 90_000)
        put("result", HistoryJson.parse("""
            {"schemaVersion":1,"estimateId":"synthetic-estimate","mealName":"Rice",
             "ingredients":[{"id":"rice","name":"Rice","massGrams":{"min":80,"max":120},
               "preparationState":"COOKED","carbohydrateBasis":"TOTAL",
               "carbohydrates":{"min":28,"max":34},"protein":{"min":2,"max":4},
               "fat":{"min":0,"max":2},"fiber":null,"sugar":null,"polyols":null,"energy":null}],
             "suggestedProfile":"MIXED","suggestedDurationMinutes":120}
        """.trimIndent().toByteArray(), 65_536))
    })

    private fun tokens(accessExpires: Long) = """{"access_token":"access.${"a".repeat(43)}","refresh_token":"refresh.${"b".repeat(43)}","access_expires_ms":$accessExpires,"refresh_expires_ms":${now + 86_400_000},"subscription_expires_ms":${now + 86_400_000}}"""

    private fun capability(photo: Boolean): ByteArray = if (!photo) {
        """{"task_kinds":["CHAT"]}""".toByteArray()
    } else HistoryJson.bytes(buildJsonObject {
        put("revision", ServerMealPhotoProtocol.REVISION)
        put("inference_enabled", true)
        put("task_kinds", JsonArray(listOf(JsonPrimitive("MEAL_PHOTO"))))
        put("input_modalities", JsonArray(listOf(JsonPrimitive("IMAGE"))))
        put("output_modalities", JsonArray(listOf(JsonPrimitive("MEAL_ESTIMATE"))))
        put("max_result_chars", 65_536); put("max_result_bytes", 65_536)
        put("max_response_bytes", 65_536); put("max_deadline_ms", 60_000)
        put("max_request_body_bytes", 3 * 1024 * 1024)
        put("max_image_bytes", 1_048_576); put("max_ingredients", 20)
    })
}
