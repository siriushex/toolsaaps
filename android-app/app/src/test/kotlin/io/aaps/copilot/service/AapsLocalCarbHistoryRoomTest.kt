package io.aaps.copilot.service

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AapsLocalCarbHistoryRoomTest {
    private val at = 1_800_000_000_000L
    private val nonce = "sample-page-000001"

    @Test fun acceptsExactBytesProducedByLocalAapsSerializer() {
        val payload = requireNotNull(javaClass.getResource("/fixtures/local-carb-page-v1.json")).readText()
        val request = AapsCarbHistoryRequest(nonce, at - 30L * 86_400_000L, at, 0, 0, 32)
        val result = AapsLocalObservation.decodeCarbReply(request, Bundle().apply {
            putString("nonce", nonce); putLong("revisionId", 9); putString("payload", payload)
        }) as AapsCarbHistoryResult.Page
        val row = result.value.rows.single()
        assertEquals("Test\u2028meal", row.notes)
        assertFalse(row.isValid)
        assertEquals(7L, row.id)
    }

    @Test fun localPagesUpdateOneMealAndRetainItsInvalidationOnReplay() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val importer = AapsCarbHistoryImporter(db.therapyDao(), AapsCarbImportTransactionRunner { block ->
                db.withTransaction { block() }
            })
            val sync = AapsCarbHistorySyncRepository(db.syncStateDao(), { _, _, _, _, _ -> error("unexpected legacy transport") },
                { page, guard -> importer.importPage(page, guard) }, auditLogger = null, clock = { at })
            suspend fun ingest(revision: Long, grams: Double, valid: Boolean, responseNonce: String = nonce) =
                sync.syncFromLocalAaps { from, through, ts, id, limit ->
                    val request = AapsCarbHistoryRequest(nonce, from, through, ts, id, limit)
                    val row = """{"id":7,"version":${revision - 7},"dateCreated":${at - 5},"isValid":$valid,"referenceId":null,"timestamp":${at - 100},"duration":0,"amount":$grams,"notes":null,"notesSha256":null,"notesTruncated":false,"nightscoutId":null,"nightscoutIdSha256":null,"nightscoutIdTruncated":false}"""
                    val payload = """{"nonce":"$nonce","fromTs":$from,"throughTs":$through,"generatedAt":$at,"rows":[$row],"nextTimestamp":${at - 100},"nextId":7,"hasMore":false}"""
                    AapsLocalObservation.decodeCarbReply(request, Bundle().apply {
                        putString("nonce", responseNonce); putLong("revisionId", revision); putString("payload", payload)
                    })
                }
            suspend fun effective() = JsonParser.parseString(db.therapyDao().since(0).single().payloadJson).asJsonObject
            assertTrue(ingest(7, 20.0, true) is AapsCarbHistorySyncResult.Completed)
            assertEquals(20.0, effective()["carbs"].asDouble, 0.0)
            assertTrue(ingest(8, 12.0, true) is AapsCarbHistorySyncResult.Completed)
            assertEquals(12.0, effective()["carbs"].asDouble, 0.0)
            assertTrue(ingest(9, 12.0, false, "different-sample") is AapsCarbHistorySyncResult.Failed)
            assertTrue(effective()["aapsCarbIsValid"].asBoolean)
            assertTrue(ingest(9, 12.0, false) is AapsCarbHistorySyncResult.Completed)
            assertTrue(ingest(9, 12.0, false) is AapsCarbHistorySyncResult.UpToDate)
            assertFalse(effective()["aapsCarbIsValid"].asBoolean)
            assertEquals(0.0, effective()["carbs"].asDouble, 0.0)
            assertEquals(12.0, effective()["aapsCarbAmount"].asDouble, 0.0)
        } finally { db.close() }
    }
}
