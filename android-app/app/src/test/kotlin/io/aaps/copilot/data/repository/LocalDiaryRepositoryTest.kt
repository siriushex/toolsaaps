package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalDiaryRepositoryTest {
    private lateinit var db: CopilotDatabase
    private lateinit var store: LocalDiaryRepository
    private var now = 1_800_000_000_000L
    private fun meal(id: String = "entry-a") = DiaryDraft(id, kind = DiaryKind.MEAL,
        occurredAtMs = now - 60_000, zoneId = "Asia/Tbilisi", amount = "42,5")

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java).build()
        store = LocalDiaryRepository(db) { now }
    }
    @After fun close() { db.close() }

    @Test fun decimalInputsAreExactAndInvalidAmountsAreNotCoerced() {
        assertEquals(42500L, parseDiaryAmount(" 42,5 "))
        assertEquals(1L, parseDiaryAmount("0.001"))
        assertEquals(1_000_000L, parseDiaryAmount("1000"))
        listOf("", "0", "-1", "NaN", "Infinity", "1e3", "1001", "1,2.3", "1.1234", ".5", "1 000").forEach {
            assertNull(it, parseDiaryAmount(it))
        }
    }

    @Test fun localMealAndInjectionNeverWriteClinicalTables() = runBlocking {
        assertTrue(store.save(meal()) is DiarySaveResult.Saved)
        assertTrue(store.save(meal("entry-b").copy(kind = DiaryKind.INJECTION, amount = "2.25",
            insulinName = "Example insulin", insulinKind = DiaryInsulinKind.LONG)) is DiarySaveResult.Saved)
        val rows = store.observe(false, 100).first()
        assertEquals(2, rows.size)
        assertEquals(2250L, rows.first { it.kind == "INJECTION" }.amountMilli)
        assertTrue(rows.all { it.source == "MANUAL_LOCAL" })
        val exporter = RoomAiHistorySource(db)
        repeat(exporter.streamCount) { stream ->
            assertTrue(exporter.read(stream, 0, 1, now, 64).observations.isEmpty())
        }
        listOf("therapy_events", "action_commands", "glucose_samples", "telemetry_samples", "meal_state_receipts").forEach { table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst()); assertEquals(table, 0, it.getInt(0))
            }
        }
    }

    @Test fun retriesAfterLostAcknowledgementDoNotCreateDuplicateOrChangeOriginalTime() = runBlocking {
        val draft = meal()
        val first = store.save(draft) as DiarySaveResult.Saved
        now += 10_000
        assertEquals(first, store.save(draft))
        assertEquals(1, store.history(draft.entryId).size)
        assertEquals(first.entry, store.observe(false, 100).first().single())
    }

    @Test fun correctionsRetainOriginalAndRejectStaleEditor() = runBlocking {
        val first = (store.save(meal()) as DiarySaveResult.Saved).entry
        val edit = first.toDiaryDraft().copy(amount = "30", note = "Corrected portion")
        val second = store.save(edit) as DiarySaveResult.Saved
        assertEquals(second, store.save(edit))
        assertEquals(DiarySaveResult.Rejected(DiaryError.CONFLICT), store.save(edit.copy(amount = "31")))
        assertEquals(listOf(2, 1), store.history(first.entryId).map { it.revision })
        assertEquals(first, store.history(first.entryId).last())
        assertEquals(30_000L, store.observe(false, 100).first().single().amountMilli)
    }

    @Test fun deleteAndRestoreAreRevisionsAndDeletedRowsNeverResurfaceFromOlderVersion() = runBlocking {
        val first = (store.save(meal()) as DiarySaveResult.Saved).entry
        val deleted = store.save(first.toDiaryDraft().copy(deleted = true)) as DiarySaveResult.Saved
        assertTrue(store.observe(false, 100).first().isEmpty())
        assertEquals(deleted.entry, store.observe(true, 100).first().single())
        val restored = store.save(deleted.entry.toDiaryDraft().copy(deleted = false)) as DiarySaveResult.Saved
        assertEquals(restored.entry, store.observe(false, 100).first().single())
        assertTrue(store.observe(true, 100).first().isEmpty())
        assertEquals(3, store.history(first.entryId).size)
    }

    @Test fun concurrentEditsHaveOneWinner() = runBlocking {
        val first = (store.save(meal()) as DiarySaveResult.Saved).entry
        val result = listOf("30", "35").map { amount ->
            async(Dispatchers.Default) { store.save(first.toDiaryDraft().copy(amount = amount)) }
        }.awaitAll()
        assertEquals(1, result.count { it is DiarySaveResult.Saved })
        assertEquals(1, result.count { it == DiarySaveResult.Rejected(DiaryError.CONFLICT) })
        assertEquals(2, store.history(first.entryId).size)
    }

    @Test fun invalidMetadataAndFuturePlannedEventsCannotBeRecorded() = runBlocking {
        val invalid = listOf(
            meal().copy(occurredAtMs = now + 1), meal().copy(occurredAtMs = 0),
            meal().copy(zoneId = "missing-zone"), meal().copy(entryId = "../entry"),
            meal().copy(expectedRevision = -1), meal().copy(expectedRevision = Int.MAX_VALUE),
            meal().copy(note = "a".repeat(501)), meal().copy(insulinName = "Not a meal"),
            meal().copy(kind = DiaryKind.INJECTION), meal().copy(deleted = true)
        )
        invalid.forEach { assertTrue(store.save(it) is DiarySaveResult.Rejected) }
        assertTrue(store.observe(false, 100).first().isEmpty())
    }

    @Test fun unreadableStoredMetadataFailsInsteadOfInventingClinicalMeaning() = runBlocking {
        val row = (store.save(meal()) as DiarySaveResult.Saved).entry
        db.localDiaryDao().insert(row.copy(revision = 2, kind = "UNKNOWN"))
        assertTrue(runCatching { store.observe(false, 100).first() }.isFailure)
        assertTrue(runCatching { store.history(row.entryId) }.isFailure)
    }

    @Test fun originalKindCannotBeChangedIntoInjection() = runBlocking {
        val row = (store.save(meal()) as DiarySaveResult.Saved).entry
        val changed = row.toDiaryDraft().copy(kind = DiaryKind.INJECTION, insulinName = "Example",
            insulinKind = DiaryInsulinKind.OTHER)
        assertEquals(DiarySaveResult.Rejected(DiaryError.CONFLICT), store.save(changed))
    }

    @Test fun orderingUsesOccurrenceNotEditTimeAndLimitIsBounded() = runBlocking {
        val older = (store.save(meal("older").copy(occurredAtMs = now - 120_000)) as DiarySaveResult.Saved).entry
        store.save(meal("newer"))
        store.save(older.toDiaryDraft().copy(note = "Edited later"))
        assertEquals(listOf("newer", "older"), store.observe(false, 10).first().map { it.entryId })
        assertEquals("newer", store.observe(false, 1).first().single().entryId)
    }
}
