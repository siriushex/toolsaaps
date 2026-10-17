package io.aaps.copilot.data.repository

import io.aaps.copilot.data.repository.DailyReviewFixtures.change
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServerDailyReviewCodecTest {
    private val f = DailyReviewFixtures

    @Test fun closedListRequestAndFrozenMembership() {
        val query = DailyReviewQuery()
        assertEquals("{\"afterSequence\":0,\"snapshotSequence\":null}", HistoryJson.decodeUtf8(DailyReviewCodec.listQuery(query)))
        val result = DailyReviewCodec.page(f.page(), query, f.now)
        assertEquals(f.id(), result.records.single().jobId)
        assertEquals(1L, result.snapshotSequence)
        assertFalse(result.hasMore)
        assertNull(result.nextAfterSequence)
    }

    @Test fun selectedAuditPreservesHistoricalAndCurrentChecksWithoutClinicalPromotion() {
        val result = DailyReviewCodec.detail(f.detail(), f.id(), f.now)
        assertEquals(f.text, result.summary)
        assertEquals(f.stored - 1000, result.replyCheckedAtMs)
        assertEquals(f.now - 500, result.checkedAtMs)
        assertEquals(DailyReviewSourceState.SOURCE_MATCHED, result.sourceState)
        assertEquals(listOf("MEALS_NOT_ANALYZED", "INSULIN_NOT_ANALYZED"), result.missingInputCodes)
        assertFalse(result.toString().contains(f.text))
    }

    @Test fun changedAndUnavailableSourceRemainDifferentFromOriginalReply() {
        val changed = f.detail().change("currentState", JsonPrimitive("SOURCE_CHANGED"))
            .change("sourceCheck", f.source(status = "CHANGED"))
        assertEquals(DailyReviewSourceState.SOURCE_CHANGED, DailyReviewCodec.detail(changed, f.id(), f.now).sourceState)
        for (state in listOf("SOURCE_UNAVAILABLE", "SUPERSEDED")) {
            val wire = f.detail().change("currentState", JsonPrimitive(state)).change("sourceCheck", JsonNull)
            assertNull(DailyReviewCodec.detail(wire, f.id(), f.now).checkedAtMs)
        }
    }

    @Test fun invalidCursorsAndMembershipCannotBeAdopted() {
        for (query in listOf(DailyReviewQuery(-1), DailyReviewQuery(Long.MAX_VALUE), DailyReviewQuery(2, 1))) {
            assertThrows(Exception::class.java) { DailyReviewCodec.listQuery(query) }
        }
        val bad = listOf(f.page().change("snapshotSequence", JsonPrimitive(true)),
            f.page().change("queue", JsonPrimitive("unexpected")),
            f.page().change("hasMore", JsonPrimitive(true)),
            f.page(listOf(f.summary(), f.summary())), f.page(listOf(f.summary(2)), high = 1),
            f.page().change("membershipMeaning", JsonPrimitive("COMPLETE_HISTORY")))
        bad.forEach { assertThrows(Exception::class.java) { DailyReviewCodec.page(it, DailyReviewQuery(), f.now) } }
    }

    @Test fun wrongIdentityExpirySourceAndAdmissionAreRejected() {
        val review = f.detail().getValue("review").jsonObject
        val bad = listOf(f.detail().change("jobId", JsonPrimitive(f.id(2))),
            f.detail().change("expiresAtMs", JsonPrimitive(f.expires + 1)),
            f.detail().change("sourceCheck", f.source().change("factsDigest", JsonPrimitive("f".repeat(64)))),
            f.detail().change("sourceCheck", JsonNull),
            f.detail().change("review", review.change("publicationState", JsonPrimitive("ADMITTED"))),
            f.detail().change("review", review.change("scope", JsonPrimitive("FULL_MEDICAL"))),
            f.detail().change("review", review.change("summary", JsonPrimitive("Change target by twenty percent 20"))),
            f.detail().change("review", review.change("tools", JsonArray(emptyList()))))
        bad.forEach { assertThrows(Exception::class.java) { DailyReviewCodec.detail(it, f.id(), f.now) } }
        assertThrows(Exception::class.java) { DailyReviewCodec.detail(f.detail(), f.id(), f.expires) }
    }

    @Test fun actualSyntheticBackendProjectionWithFindingIsReadable() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("server-daily-review.json")!!.use { it.readBytes() }
        val wire = HistoryJson.parse(bytes, 131_072)
        val job = HistoryJson.string(wire.getValue("jobId"))
        val result = DailyReviewCodec.detail(wire, job, f.now)
        assertEquals(1, result.findings.size)
        assertEquals(DailyReviewSourceState.SOURCE_MATCHED, result.sourceState)
        assertEquals("Observed exposure warrants reviewing the recorded context.", result.findings.single())
    }
}
