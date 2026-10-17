package io.aaps.copilot.data.repository

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServerAiHistoryCodecTest {
    private val now = 1_800_000_000_000L
    private val id = "2ec58479-e05b-4805-87f6-5bb06b04bb2d"
    private fun chat(text: String, context: ServerAiHistoricalContext? = null) = ServerAiHistoryRecord(
        id, 1, now, now, ServerAiHistoryKind.CHAT, ServerAiHistorySource.COPILOT, ServerAiHistoryQuality.UNKNOWN,
        data = ServerAiHistoryData.Chat(id, ServerAiHistoryRole.USER, text), context = context)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected_rejection") } catch (_: Exception) { }
    }
    private fun full(wire: JsonObject): JsonObject = JsonObject(wire + ("context" to (wire["context"] ?: JsonNull)))

    @Test fun exactUtf8RecordLimitIncludesNullContext() {
        val overhead = HistoryJson.bytes(full(chat("x").toWire(now))).size - 1
        val budget = 16_384 - overhead
        val text = "\u754c".repeat(budget / 3) + "a".repeat(budget % 3)
        val wire = chat(text).toWire(now)
        assertEquals(16_384, HistoryJson.bytes(full(wire)).size)
        rejected { chat(text + "a").toWire(now) }
    }

    @Test fun exactUtf8RecordLimitIncludesEveryHistoricalContextDefault() {
        val context = ServerAiHistoricalContext(glucoseBasis = ServerAiGlucoseBasis.UNKNOWN)
        val base = chat("x", context).toWire(now)
        val fullContext = JsonObject(setOf("zoneId", "sensorSessionId", "calibrationModelId", "glucoseBasis", "inputModelVersion")
            .associateWith { base.getValue("context").jsonObject[it] ?: JsonNull })
        val overhead = HistoryJson.bytes(JsonObject(base + ("context" to fullContext))).size - 1
        val budget = 16_384 - overhead
        val text = "\u754c".repeat(budget / 3) + "a".repeat(budget % 3)
        chat(text, context).toWire(now)
        rejected { chat(text + "a", context).toWire(now) }
    }

    @Test fun strictJsonRejectsNestedDuplicateCoercionAndTrailingData() {
        listOf("{\"a\":{\"x\":1,\"x\":2}}", "{\"a\":1} {}", "{\"a\":NaN}", "\uFEFF{}").forEach {
            rejected { HistoryJson.parse(it.toByteArray(), 131_072) }
        }
        rejected { HistoryJson.long(JsonPrimitive("1")) }
        rejected { HistoryJson.long(Json.parseToJsonElement("1.0")) }
        rejected { HistoryJson.long(Json.parseToJsonElement("1e0")) }
        rejected { HistoryJson.bool(JsonPrimitive("false")) }
        rejected { HistoryJson.parse("{\"a\":\"\\uD800\"}".toByteArray(), 131_072) }
    }

    @Test fun responseByteLimitsAreIndependentAndInclusive() {
        for (limit in listOf(65_536, 131_072)) {
            HistoryJson.parse(("{}" + " ".repeat(limit - 2)).toByteArray(), limit)
            rejected { HistoryJson.parse(("{}" + " ".repeat(limit - 1)).toByteArray(), limit) }
        }
    }

    @Test fun canonicalRecordIdentityTimeAndTombstones() {
        rejected { chat("synthetic").copy(recordId = id.uppercase()).toWire(now) }
        rejected { chat("synthetic").copy(revision = 0).toWire(now) }
        rejected { chat("synthetic").copy(eventAtMs = now - HistoryJson.retentionMs).toWire(now) }
        rejected { chat("synthetic").copy(observedAtMs = now + 1).toWire(now) }
        rejected { chat("synthetic").copy(deleted = true).toWire(now) }
        val tombstone = chat("synthetic").copy(deleted = true, data = null)
        assertEquals(JsonNull, tombstone.toWire(now)["data"])
    }

    @Test fun allStructuredPayloadsRoundTripWithActualBackendFields() {
        val payloads = listOf(
            ServerAiHistoryData.Glucose(5.5),
            ServerAiHistoryData.Therapy(ServerAiTherapyEvent.BOLUS, insulinUnits = 1.0),
            ServerAiHistoryData.Meal(20.0, ServerAiFoodType.MIXED, ServerAiQuantitySource.UNKNOWN),
            ServerAiHistoryData.Forecast(5, 5.0, "synthetic-v1"),
            ServerAiHistoryData.Sensitivity(netIobUnits = -0.5),
            ServerAiHistoryData.Activity(steps = 1),
            ServerAiHistoryData.Device(deliveryState = ServerAiDeliveryState.UNKNOWN),
            ServerAiHistoryData.Alert("synthetic-alert", ServerAiAlertSeverity.INFO, ServerAiAlertDelivery.UNKNOWN),
            ServerAiHistoryData.Diagnostic("synthetic-diagnostic", ServerAiDiagnosticStatus.UNKNOWN),
            ServerAiHistoryData.Chat(id, ServerAiHistoryRole.ASSISTANT, "synthetic")
        )
        payloads.forEachIndexed { index, data ->
            val record = chat("synthetic").copy(recordId = UUID(0, index + 1L).toString(), kind = data.kind, data = data)
            assertEquals(record, HistoryCodec.record(record.toWire(now), now))
        }
        assertFalse(payloads[2].toWire().containsKey("glycemicIndex"))
        assertFalse(payloads[2].toWire().containsKey("giSource"))
    }

    @Test fun mixedTherapyInventedGiAndEmptyContextAreRejected() {
        rejected { ServerAiHistoryData.Therapy(ServerAiTherapyEvent.BOLUS, carbsGrams = 2.0, insulinUnits = 1.0).toWire() }
        rejected { ServerAiHistoryData.Therapy(ServerAiTherapyEvent.DELIVERY_STATE).toWire() }
        rejected { ServerAiHistoryData.Meal(20.0, ServerAiFoodType.MIXED, ServerAiQuantitySource.UNKNOWN, 50.0).toWire() }
        rejected { ServerAiHistoryData.Device().toWire() }
        rejected { ServerAiHistoryData.Sensitivity().toWire() }
        rejected { ServerAiHistoryData.Glucose(Double.NaN).toWire() }
        rejected { ServerAiHistoricalContext().toWire() }
    }

    @Test fun recordReadRejectsUnknownNestedFieldsAndOriginPromotion() {
        val wire = chat("synthetic").toWire(now)
        val data = wire.getValue("data").jsonObject
        rejected { HistoryCodec.record(JsonObject(wire + ("data" to JsonObject(data + ("extra" to JsonPrimitive(1))))), now) }
        rejected { HistoryCodec.record(JsonObject(wire + ("data" to JsonObject(data + ("origin" to JsonPrimitive("SERVER_ARCHIVE"))))), now) }
        rejected { HistoryCodec.record(JsonObject(wire + ("owner" to JsonPrimitive(id))), now) }
    }

    @Test fun receiptsRejectWrongIdentityCountsSequenceAndAmbiguousFields() {
        fun ack(sequence: String = "43", count: String = "2", batch: String = id, extra: String = "") =
            HistoryJson.parse("""{"batchId":"$batch","sequence":$sequence,"changedRecords":$count$extra}""".toByteArray(), 65_536)
        HistoryCodec.receipt(ack(), id, 2, 41)
        rejected { HistoryCodec.receipt(ack(sequence = "42"), id, 2, 41) }
        rejected { HistoryCodec.receipt(ack(count = "3"), id, 2, 41) }
        rejected { HistoryCodec.receipt(ack(sequence = "\"43\""), id, 2, 41) }
        rejected { HistoryCodec.receipt(ack(extra = ",\"accepted\":true"), id, 2, 41) }
        rejected { HistoryCodec.receipt(ack(batch = UUID(0, 1).toString()), id, 2, 41) }
    }

    @Test fun historyPageRequiresStrictlyIncreasingSequencesAndHonestContinuation() {
        val query = ServerAiHistoryQuery(now - 10, now + 1)
        fun page(sequence: Int, next: Int, more: Boolean, duplicate: Boolean = false) = buildJsonObject {
            put("snapshotSequence", 5); put("nextAfterSequence", next); put("hasMore", more); put("provenance", "DEVICE_DECLARED")
            val row = buildJsonObject { put("sequence", sequence); put("receivedAtMs", now); put("record", chat("synthetic").toWire(now)) }
            put("records", JsonArray(if (duplicate) listOf(row, row) else listOf(row)))
        }
        HistoryCodec.page(page(3, 3, true), query, now)
        rejected { HistoryCodec.page(page(3, 5, true), query, now) }
        rejected { HistoryCodec.page(page(3, 3, false), query, now) }
        rejected { HistoryCodec.page(page(3, 5, false, duplicate = true), query, now) }
        rejected { HistoryCodec.query(query.copy(kinds = listOf(ServerAiHistoryKind.CHAT, ServerAiHistoryKind.CHAT)), now) }
    }

    @Test fun conversationRejectsFabricatedSuccessAndRequiresMembershipScope() {
        val query = ServerAiConversationQuery(now - 100, now + 1)
        val entry = buildJsonObject {
            put("jobId", id); put("requestId", UUID(0, 1).toString()); put("acceptedAtMs", now - 50); put("deadlineMs", now + 50)
            put("startedAtMs", JsonNull); put("finishedAtMs", JsonNull); put("state", "OUTCOME_NOT_RECORDED")
            put("question", "synthetic"); put("answer", JsonNull); put("model", "synthetic-model"); put("promptRevision", "synthetic-v1"); put("updatedAtMs", now - 50)
        }
        fun page(row: JsonObject, scope: String = "MEMBERSHIP_ONLY") = buildJsonObject {
            put("revision", "server-chat-archive-v1"); put("origin", "SERVER_ARCHIVE"); put("generatedAtMs", now)
            put("snapshotScope", scope); put("records", JsonArray(listOf(row))); put("hasMore", false); put("nextCursor", JsonNull)
        }
        assertNull(HistoryCodec.conversationPage(page(entry), query, now).records.single().answer)
        rejected { HistoryCodec.conversationPage(page(JsonObject(entry + ("state" to JsonPrimitive("SUCCEEDED")))), query, now) }
        rejected { HistoryCodec.conversationPage(page(entry, "FULL_STATE"), query, now) }
    }

    @Test fun serverTimesTolerateOnlyTheExistingProofClockWindow() {
        val query = ServerAiHistoryQuery(now - 100, now + 1)
        val record = chat("synthetic").copy(eventAtMs = now - 1, observedAtMs = now + 10).toWire(now + 20)
        fun page(received: Long) = buildJsonObject {
            put("snapshotSequence", 1); put("nextAfterSequence", 1); put("hasMore", false); put("provenance", "DEVICE_DECLARED")
            put("records", JsonArray(listOf(buildJsonObject { put("sequence", 1); put("receivedAtMs", received); put("record", record) })))
        }
        HistoryCodec.page(page(now + 20), query, now)
        rejected { HistoryCodec.page(page(now + 60_001), query, now) }
        val archiveQuery = ServerAiConversationQuery(now - 100, now + 1)
        fun archive(generated: Long) = buildJsonObject {
            put("revision", "server-chat-archive-v1"); put("origin", "SERVER_ARCHIVE"); put("generatedAtMs", generated)
            put("snapshotScope", "MEMBERSHIP_ONLY"); put("records", JsonArray(emptyList())); put("hasMore", false); put("nextCursor", JsonNull)
        }
        HistoryCodec.conversationPage(archive(now + 20), archiveQuery, now)
        rejected { HistoryCodec.conversationPage(archive(now + 60_001), archiveQuery, now) }
    }
}
