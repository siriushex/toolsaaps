package io.aaps.copilot.data.repository

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAiHistoryClockSkewTest {
    @Test
    fun signedHistoryResponseAllowsSmallTransportClockDifferenceWithoutChangingEventTime() {
        val record = ServerAiHistoryRecord(
            "b836370b-fc6c-4bd2-9cd4-cf21db566f57", 1, NOW - 1000, NOW + 200,
            ServerAiHistoryKind.GLUCOSE, ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Glucose(6.8)
        )
        val page = buildJsonObject {
            put("snapshotSequence", 1); put("nextAfterSequence", 1)
            put("hasMore", false); put("provenance", "DEVICE_DECLARED")
            put("records", JsonArray(listOf(buildJsonObject {
                put("sequence", 1); put("receivedAtMs", NOW + 250)
                put("record", record.toWire(NOW + 250))
            })))
        }

        val result = HistoryCodec.page(page, ServerAiHistoryQuery(NOW - 10_000, NOW + 1), NOW)

        assertEquals(NOW - 1000, result.records.single().record.eventAtMs)
        assertEquals(NOW + 200, result.records.single().record.observedAtMs)
        assertEquals(NOW + 250, result.records.single().receivedAtMs)
    }

    @Test
    fun archiveResponseAcceptsBoundedClockSkewButRejectsDistantFuture() {
        fun page(generated: Long) = buildJsonObject {
            put("revision", "server-chat-archive-v1"); put("origin", "SERVER_ARCHIVE")
            put("snapshotScope", "MEMBERSHIP_ONLY"); put("generatedAtMs", generated)
            put("records", JsonArray(emptyList())); put("hasMore", false); put("nextCursor", JsonNull)
        }
        val query = ServerAiConversationQuery(NOW - 10_000, NOW + 1)

        assertEquals(NOW + 250, HistoryCodec.conversationPage(page(NOW + 250), query, NOW).generatedAtMs)
        assertTrue(runCatching { HistoryCodec.conversationPage(page(NOW + 60_001), query, NOW) }.isFailure)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
