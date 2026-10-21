package io.aaps.copilot.service

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3TargetProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val client = "11111111-2222-4333-8444-555555555555"
    private val at = 1_800_000_000_000L
    private val target get() = AapsV3TargetSpec(90.0, 99.0, 30, "Eating Soon")
    private val preview = """{"bolusId":321,"lines":[{"role":"Normal","text":"synthetic target"}],"advisorApplies":false,"advisorLines":[]}"""

    private fun ack(counter: Long, phase: String = "Done", status: String = "Ok",
                    payload: String? = null, timestamp: Long = at + 1,
                    id: String = client, reason: String? = null): JsonObject {
        val canonical = "$id|$counter|$phase|$status|${reason ?: ""}|${payload ?: ""}|$timestamp"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = mac.doFinal(canonical.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return buildJsonObject {
            put("clientId", id); put("commandCounter", counter); put("phase", phase)
            put("status", status); put("payload", payload?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
            put("timestamp", timestamp); put("signature", signature)
        }
    }

    @Test fun prepareContainsExactlyOneTargetAndUsesTheNativeSignatureBytes() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 7, at, target)
            val body = request.json
            val payload = Json.parseToJsonElement(body["payload"]!!.jsonPrimitive.content).jsonObject
            assertEquals("batch_prepare", payload["type"]!!.jsonPrimitive.content)
            val action = payload["actions"]!!.jsonArray.single().jsonObject
            assertEquals(setOf("type", "reason", "lowMgdl", "highMgdl", "durationMinutes", "startOffsetMinutes"), action.keys)
            assertEquals("temp_target", action["type"]!!.jsonPrimitive.content)
            assertEquals(90.0, action["lowMgdl"]!!.jsonPrimitive.double, 0.0)
            assertEquals(0, action["startOffsetMinutes"]!!.jsonPrimitive.int)
            assertEquals(at + 8000, body["validUntil"]!!.jsonPrimitive.long)
            assertTrue(body["wantsAck"]!!.jsonPrimitive.boolean)
            val canonical = "$client|7|$at|${at + 8000}|true|batch_prepare|${body["payload"]!!.jsonPrimitive.content}"
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            assertTrue(MessageDigest.isEqual(mac.doFinal(canonical.toByteArray()),
                body["signature"]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
            assertFalse(request.toString().contains("signature"))
        }
    }

    @Test fun aSignedPrepareIsOnlyAPreviewAndCommitReferencesItsExactIdentifier() {
        AapsV3TargetProtocol(key).use { protocol ->
            val prepared = protocol.prepare(client, 7, at, target)
            val response = ack(7, payload = preview)
            assertEquals(AapsV3TargetAck.PREVIEW, protocol.classify(prepared, response, at + 2))
            val commit = protocol.commit(prepared, response, 8, at + 2)
            val payload = Json.parseToJsonElement(commit.json["payload"]!!.jsonPrimitive.content).jsonObject
            assertEquals(setOf("type", "bolusId", "asAdvisor", "correctionU"), payload.keys)
            assertEquals("bolus_commit", payload["type"]!!.jsonPrimitive.content)
            assertEquals(321, payload["bolusId"]!!.jsonPrimitive.int)
            assertFalse(payload["asAdvisor"]!!.jsonPrimitive.boolean)
            assertEquals(0.0, payload["correctionU"]!!.jsonPrimitive.double, 0.0)
            assertEquals(AapsV3TargetAck.APPLIED, protocol.classify(commit, ack(8, timestamp = at + 3), at + 4))
            assertEquals(AapsV3TargetAck.UNCONFIRMED, protocol.classify(commit, response, at + 4))
        }
    }

    @Test fun correlationNotesAreSignedAndCannotCarryControlCharactersOrOversizedText() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 1, at, target.copy(notes = "copilot:meal-1"))
            assertEquals(AapsV3TargetAck.PREVIEW, protocol.classify(request, ack(1, payload = preview), at + 2))
            val action = Json.parseToJsonElement(request.json.getValue("payload").jsonPrimitive.content)
                .jsonObject.getValue("actions").jsonArray.single().jsonObject
            assertEquals("copilot:meal-1", action.getValue("notes").jsonPrimitive.content)
            listOf("line\nbreak", "x".repeat(513)).forEach { note ->
                assertThrows(IllegalArgumentException::class.java) { protocol.prepare(client, 2, at, target.copy(notes = note)) }
            }
        }
    }

    @Test fun executingOrHttpReceiptCannotBeReportedAsApplication() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 1, at, target)
            assertEquals(AapsV3TargetAck.EXECUTING, protocol.classify(request, ack(1, "Executing", "Pending"), at + 2))
            assertEquals(AapsV3TargetAck.UNCONFIRMED, protocol.classify(request, buildJsonObject { put("response", 200) }, at + 2))
            assertThrows(IllegalArgumentException::class.java) {
                protocol.commit(request, ack(1, "Executing", "Pending"), 2, at + 2)
            }
        }
    }

    @Test fun forgedStaleOtherClientAndWrongCounterAcksNeverCommit() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 9, at, target)
            val valid = ack(9, payload = preview)
            val invalid = listOf(
                JsonObject(valid + ("signature" to JsonPrimitive("0".repeat(64)))),
                JsonObject(valid + ("payload" to JsonPrimitive(preview.replace("321", "322")))),
                ack(8, payload = preview), ack(9, id = "11111111-2222-4333-8444-666666666666", payload = preview),
                ack(9, payload = preview, timestamp = at - 5001),
                ack(9, payload = preview, timestamp = at + 13001),
                JsonObject(valid + ("commandCounter" to JsonPrimitive("9"))),
                JsonObject(valid + ("extra" to JsonPrimitive(true))),
            )
            invalid.forEach { candidate ->
                assertEquals(AapsV3TargetAck.UNCONFIRMED, protocol.classify(request, candidate, at + 2))
                assertThrows(IllegalArgumentException::class.java) { protocol.commit(request, candidate, 10, at + 2) }
            }
        }
    }

    @Test fun previewCannotCarryWizardAdvisorDoseOrMalformedIdentifier() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 1, at, target)
            val invalid = listOf(preview.replace("321", "0"), preview.replace("321", "\"321\""),
                preview.replace("false", "true"), preview.dropLast(1) + ",\"wizardDetail\":{}}",
                preview.dropLast(1) + ",\"insulin\":2}", "{}", "[]",
                """{"bolusId":321,"bolusId":322,"lines":[]}""")
            invalid.forEach { value ->
                assertEquals(AapsV3TargetAck.UNCONFIRMED, protocol.classify(request, ack(1, payload = value), at + 2))
            }
        }
    }

    @Test fun expiredPreviewAndNonIncreasingCountersNeverProduceCommit() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 4, at, target)
            val response = ack(4, payload = preview)
            assertThrows(IllegalArgumentException::class.java) { protocol.commit(request, response, 4, at + 2) }
            assertThrows(IllegalArgumentException::class.java) { protocol.commit(request, response, 3, at + 2) }
            assertThrows(IllegalArgumentException::class.java) { protocol.commit(request, response, 5, at + 8001) }
            assertThrows(IllegalArgumentException::class.java) { protocol.commit(request, response, 5, at - 1) }
        }
    }

    @Test fun definitiveFailureAndExpiryAreDistinctFromUnknownDelivery() {
        AapsV3TargetProtocol(key).use { protocol ->
            val request = protocol.prepare(client, 1, at, target)
            assertEquals(AapsV3TargetAck.REJECTED, protocol.classify(request, ack(1, status = "Failed", reason = "ControlDisabled"), at + 2))
            assertEquals(AapsV3TargetAck.EXPIRED, protocol.classify(request, ack(1, status = "Expired"), at + 2))
            assertEquals(AapsV3TargetAck.UNCONFIRMED, protocol.classify(request, ack(1, phase = "Delivery", status = "Failed"), at + 2))
        }
    }

    @Test fun lateSignedCommitAckCanReconcileButCannotStartAnotherCommit() {
        AapsV3TargetProtocol(key).use { protocol ->
            val prepare = protocol.prepare(client, 1, at, target)
            val commit = protocol.commit(prepare, ack(1, payload = preview), 2, at + 2)
            assertEquals(AapsV3TargetAck.APPLIED, protocol.classify(commit, ack(2, timestamp = at + 3), at + 60_000))
            assertThrows(IllegalArgumentException::class.java) { protocol.commit(commit, ack(2), 3, at + 4) }
        }
    }

    @Test fun cancellationIsExplicitAndInvalidTargetsAndIdentifiersAreRejected() {
        AapsV3TargetProtocol(key).use { protocol ->
            val cancel = protocol.prepare(client, 1, at, AapsV3TargetSpec(0.0, 0.0, 0, "Custom"))
            assertEquals(0, Json.parseToJsonElement(cancel.json["payload"]!!.jsonPrimitive.content).jsonObject["actions"]!!
                .jsonArray.single().jsonObject["durationMinutes"]!!.jsonPrimitive.int)
            listOf(AapsV3TargetSpec(Double.NaN, 90.0, 30, "Custom"),
                AapsV3TargetSpec(100.0, 90.0, 30, "Custom"), AapsV3TargetSpec(0.0, 90.0, 30, "Custom"),
                AapsV3TargetSpec(90.0, 90.0, -1, "Custom"), AapsV3TargetSpec(90.0, 90.0, 30, "unknown")).forEach {
                assertThrows(IllegalArgumentException::class.java) { protocol.prepare(client, 1, at, it) }
            }
            assertThrows(IllegalArgumentException::class.java) { protocol.prepare("client|escape", 1, at, target) }
            assertThrows(IllegalArgumentException::class.java) { protocol.prepare(client, 0, at, target) }
            assertThrows(IllegalArgumentException::class.java) { protocol.prepare(client, 1, Long.MAX_VALUE, target) }
        }
    }

    @Test fun protocolCopiesAndErasesItsKeyWithoutMutatingTheCaller() {
        val caller = key.copyOf()
        val protocol = AapsV3TargetProtocol(caller)
        caller.fill(0)
        val request = protocol.prepare(client, 1, at, target)
        assertEquals(AapsV3TargetAck.PREVIEW, protocol.classify(request, ack(1, payload = preview), at + 2))
        protocol.close()
        assertTrue(caller.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { protocol.prepare(client, 2, at, target) }
        assertThrows(IllegalArgumentException::class.java) { AapsV3TargetProtocol(ByteArray(1)) }
    }
}
