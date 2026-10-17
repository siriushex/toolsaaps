package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerMealPhotoProtocolTest {
    private val now = 1_800_000_000_000L
    private val id = UUID.randomUUID().toString()
    private val jpeg = byteArrayOf(-1, -40, -1, 0, -1, -39)

    @Test fun canonicalPhotoPreparationCopiesOneImageAndPreservesRequestIdentity() {
        val submission = ServerMealPhotoProtocol.prepare(MealPhotoGatewayRequest(id, jpeg, "image/jpeg"), now, now + 60_000)
        val body = HistoryJson.parse(submission.body, 3 * 1024 * 1024)
        assertThat(body.keys).containsExactly("schemaVersion", "mimeType", "imageBase64")
        assertThat(submission.requestId).isEqualTo(id)
        assertThat(submission.deadlineMs).isEqualTo(now + 60_000)
        val copy = submission.body
        copy.fill(0)
        assertThat(submission.body).isNotEqualTo(copy)
        assertThat(submission.toString()).doesNotContain(HistoryJson.string(body.getValue("imageBase64")))
    }

    @Test fun rejectsNonJpegAndOversizedDeadlineBeforeTransport() {
        assertThrows(ServerAiJobFailure::class.java) {
            ServerMealPhotoProtocol.prepare(MealPhotoGatewayRequest(id, byteArrayOf(1), "image/jpeg"), now, now + 10)
        }
        assertThrows(ServerAiJobFailure::class.java) {
            ServerMealPhotoProtocol.prepare(MealPhotoGatewayRequest(id, jpeg, "image/jpeg"), now, now + 60_001)
        }
    }

    @Test fun photoReadinessIsPerRouteNotGlobalTextReadiness() {
        assertThat(ServerMealPhotoProtocol.ready(capability(true))).isTrue()
        assertThat(ServerMealPhotoProtocol.ready(capability(false))).isFalse()
        assertThat(ServerMealPhotoProtocol.ready(envelope(capability(false), textReady = true))).isFalse()
        assertThat(ServerMealPhotoProtocol.ready(envelope(capability(true), textReady = false))).isTrue()
    }

    @Test fun duplicateCapabilityFieldsAndChangedLimitsAreRejected() {
        val encoded = HistoryJson.decodeUtf8(capability(true))
        assertThrows(ServerAiJobFailure::class.java) {
            ServerMealPhotoProtocol.ready(encoded.replace("\"max_image_bytes\":1048576", "\"max_image_bytes\":1048576,\"max_image_bytes\":1").toByteArray())
        }
        assertThrows(ServerAiJobFailure::class.java) {
            ServerMealPhotoProtocol.ready(encoded.replace("1048576", "1048577").toByteArray())
        }
    }

    @Test fun receiptMustMatchPhotoIdentityAndDeadline() {
        val submission = ServerMealPhotoProtocol.prepare(MealPhotoGatewayRequest(id, jpeg, "image/jpeg"), now, now + 60_000)
        val receipt = receipt()
        val value = ServerMealPhotoProtocol.snapshot(receipt, submission, null)
        assertThat(value.state).isEqualTo(ServerAiJobState.QUEUED)
        assertThrows(ServerAiJobFailure::class.java) {
            ServerMealPhotoProtocol.snapshot(receipt().toString(Charsets.UTF_8).replace(id, UUID.randomUUID().toString()).toByteArray(), submission, null)
        }
        assertThrows(ServerAiJobFailure::class.java) {
            ServerMealPhotoProtocol.snapshot(receipt().toString(Charsets.UTF_8).replace("MEAL_PHOTO", "CHAT").toByteArray(), submission, null)
        }
    }

    private fun capability(ready: Boolean) = HistoryJson.bytes(buildJsonObject {
        put("revision", ServerMealPhotoProtocol.REVISION)
        put("inference_enabled", ready)
        put("task_kinds", JsonArray(listOf(JsonPrimitive("MEAL_PHOTO"))))
        put("input_modalities", JsonArray(listOf(JsonPrimitive("IMAGE"))))
        put("output_modalities", JsonArray(listOf(JsonPrimitive("MEAL_ESTIMATE"))))
        put("max_result_chars", 65_536); put("max_result_bytes", 65_536)
        put("max_response_bytes", 65_536); put("max_deadline_ms", 60_000)
        put("max_request_body_bytes", 3 * 1024 * 1024)
        put("max_image_bytes", 1_048_576); put("max_ingredients", 20)
    })

    private fun envelope(photo: ByteArray, textReady: Boolean) = HistoryJson.bytes(buildJsonObject {
        put("revision", ServerTaskCapabilities.ENVELOPE_REVISION)
        put("inference_enabled", true)
        put("task_kinds", JsonArray(listOf(JsonPrimitive("CHAT"), JsonPrimitive("MEAL_PHOTO"))))
        put("input_modalities", JsonArray(listOf(JsonPrimitive("TEXT"), JsonPrimitive("IMAGE"))))
        put("output_modalities", JsonArray(listOf(JsonPrimitive("TEXT"), JsonPrimitive("MEAL_ESTIMATE"))))
        put("routes", buildJsonObject {
            put("CHAT", buildJsonObject {
                put("task_kinds", JsonArray(listOf(JsonPrimitive("CHAT"))))
                put("input_modalities", JsonArray(listOf(JsonPrimitive("TEXT"))))
                put("output_modalities", JsonArray(listOf(JsonPrimitive("TEXT"))))
                put("inference_enabled", textReady)
            })
            put("MEAL_PHOTO", HistoryJson.parse(photo, 65_536))
        })
    })

    private fun receipt() = HistoryJson.bytes(buildJsonObject {
        put("job_id", UUID.randomUUID().toString()); put("request_id", id)
        put("kind", "MEAL_PHOTO"); put("state", "QUEUED")
        put("created_ms", now); put("deadline_ms", now + 60_000)
        put("started_ms", JsonNull); put("finished_ms", JsonNull)
        put("result_available", false)
    })
}
