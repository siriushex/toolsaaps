package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.CopilotMigrations
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalNightscoutV3StoreTest {
    private lateinit var context: Context
    private lateinit var db: CopilotDatabase
    private lateinit var name: String
    private var time = 1_800_000_000_000L
    private val meals = LocalV3Collection.TREATMENTS
    private fun store(limits: LocalV3StoreLimits = LocalV3StoreLimits()) = LocalNightscoutV3Store(db, { time }, limits)
    private fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
        .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
    private fun input(id: String = "meal-1", at: Long = 1000, body: String = "{\"carbs\":20}") =
        LocalV3DocumentInput(meals, id, "paired-feed", at, body)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "wire-store-${UUID.randomUUID()}.db"
        db = open()
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(name) }

    @Test fun normalizedCreateRetryDoesNotChangeRevisionOrClinicalData() = runBlocking {
        val storage = store()
        val first = storage.create(input(body = "{\"note\":\"test\",\"carbs\":20}"))
        time += 100
        val retry = storage.create(input(body = "{ \"carbs\":20, \"note\":\"test\" }"))
        assertTrue(first.changed)
        assertFalse(retry.changed)
        assertEquals(first.document, retry.document)
        assertEquals(1, db.localNightscoutV3Dao().count())
        assertEquals(first.document.modifiedRevision, db.localNightscoutV3Dao().clock()!!.revision)
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
        rejected(LocalV3StoreError.CONFLICT) { storage.create(input(body = "{\"carbs\":21}")) }
    }

    @Test fun compareAndSetUpdatesPreserveIdentityAndRejectStaleChanges() = runBlocking {
        val storage = store()
        val original = storage.create(input()).document
        val next = input(at = 900, body = "{\"carbs\":18}")
        val updated = storage.update(next, original.modifiedRevision)
        assertTrue(updated.changed)
        assertEquals(original.createdRevision, updated.document.createdRevision)
        assertEquals(900L, updated.document.eventAtMs)
        assertFalse(storage.update(next, original.modifiedRevision).changed)
        rejected(LocalV3StoreError.CONFLICT) { storage.update(input(), original.modifiedRevision) }
        rejected(LocalV3StoreError.CONFLICT) {
            storage.update(next.copy(source = "different-feed"), updated.document.modifiedRevision)
        }
        rejected(LocalV3StoreError.NOT_FOUND) { storage.update(input("absent"), original.modifiedRevision) }
        assertEquals(updated.document, storage.get(meals, "meal-1"))
    }

    @Test fun tombstoneRetainsPayloadCannotBeRecreatedAndSurvivesRestart() = runBlocking {
        val storage = store()
        val created = storage.create(input()).document
        val deleted = storage.delete(meals, "meal-1", created.modifiedRevision).document
        assertTrue(deleted.deleted)
        assertEquals(created.payloadJson, deleted.payloadJson)
        assertEquals(created.eventAtMs, deleted.eventAtMs)
        assertFalse(storage.delete(meals, "meal-1", created.modifiedRevision).changed)
        assertTrue(storage.chronological(meals, 0, 10).documents.isEmpty())
        db.close(); db = open()
        val reopened = store()
        assertEquals(deleted, reopened.history(meals, created.modifiedRevision, 10).documents.single())
        rejected(LocalV3StoreError.CONFLICT) { reopened.create(input()) }
        rejected(LocalV3StoreError.CONFLICT) { reopened.update(input(), deleted.modifiedRevision) }
        assertEquals(deleted.modifiedRevision, reopened.lastModified()[meals])
    }

    @Test fun deletionRequiresMatchingRevisionAndNeverCreatesUnknownIdentity() = runBlocking {
        val storage = store()
        val first = storage.create(input()).document
        val second = storage.update(input(body = "{}"), first.modifiedRevision).document
        rejected(LocalV3StoreError.CONFLICT) { storage.delete(meals, first.identifier, first.modifiedRevision) }
        rejected(LocalV3StoreError.NOT_FOUND) { storage.delete(meals, "absent", first.modifiedRevision) }
        assertEquals(second, storage.get(meals, first.identifier))
    }

    @Test fun readOnlyCannotBeEditedDeletedOrUnlocked() = runBlocking {
        val storage = store()
        val locked = input().copy(readOnly = true)
        val row = storage.create(locked).document
        assertFalse(storage.create(locked).changed)
        assertFalse(storage.update(locked, row.modifiedRevision).changed)
        rejected(LocalV3StoreError.READ_ONLY) { storage.update(input(), row.modifiedRevision) }
        rejected(LocalV3StoreError.READ_ONLY) { storage.update(locked.copy(payloadJson = "{}"), row.modifiedRevision) }
        rejected(LocalV3StoreError.READ_ONLY) { storage.delete(meals, row.identifier, row.modifiedRevision) }
    }

    @Test fun revisionsPersistThroughRestartAndClockRollbackAcrossCollections() = runBlocking {
        val first = store().create(input()).document
        db.close(); db = open()
        time -= 86_400_000
        val second = store().create(input().copy(collection = LocalV3Collection.ENTRIES)).document
        assertEquals(first.modifiedRevision + 1, second.modifiedRevision)
        assertEquals(1000L, second.eventAtMs)
        val revisions = store().lastModified()
        assertEquals(first.modifiedRevision, revisions[meals])
        assertEquals(second.modifiedRevision, revisions[LocalV3Collection.ENTRIES])
        assertEquals(0L, revisions[LocalV3Collection.FOOD])
    }

    @Test fun historyPageCursorDoesNotSkipUnreturnedRecords() = runBlocking {
        val storage = store()
        val rows = (1..5).map { storage.create(input("row-$it", at = it.toLong())).document }
        val first = storage.history(meals, 0, 2)
        assertEquals(rows.take(2), first.documents)
        assertEquals(rows[1].modifiedRevision, first.nextCursor)
        assertEquals(rows.last().modifiedRevision, first.collectionRevision)
        assertTrue(first.hasMore)
        val second = storage.history(meals, first.nextCursor, 2)
        val third = storage.history(meals, second.nextCursor, 2)
        assertEquals(rows, first.documents + second.documents + third.documents)
        assertFalse(third.hasMore)
        val empty = storage.history(meals, third.nextCursor, 2)
        assertEquals(third.nextCursor, empty.nextCursor)
        assertTrue(empty.documents.isEmpty())
    }

    @Test fun changedOldEventAndDeletionAreVisibleByRevisionNotClinicalTime() = runBlocking {
        val storage = store()
        val one = storage.create(input("one", 10)).document
        val two = storage.create(input("two", 20)).document
        val originalHead = storage.lastModified().getValue(meals)
        val edited = storage.update(input("one", 5, "{\"carbs\":18}"), one.modifiedRevision).document
        val deleted = storage.delete(meals, "two", two.modifiedRevision).document
        assertEquals(listOf(edited, deleted), storage.history(meals, originalHead, 10).documents)
        assertTrue(storage.chronological(meals, 20, 10).documents.isEmpty())
        assertEquals(5L, edited.eventAtMs)
    }

    @Test fun mutationBetweenPagesIsNotLostWhenPreviouslyUnsentRowMovesForward() = runBlocking {
        val storage = store()
        val one = storage.create(input("one")).document
        val two = storage.create(input("two")).document
        val three = storage.create(input("three")).document
        val first = storage.history(meals, 0, 1)
        assertEquals(listOf(one), first.documents)
        val edited = storage.update(input("two", body = "{}"), two.modifiedRevision).document
        assertEquals(listOf(three, edited), storage.history(meals, first.nextCursor, 10).documents)
    }

    @Test fun chronologicalPageCompletesEqualTimeBoundaryWithoutChangingTimes() = runBlocking {
        val storage = store()
        storage.create(input("early", 9))
        listOf("c", "a", "b").forEach { storage.create(input(it, 10)) }
        storage.create(input("late", 11))
        val first = storage.chronological(meals, 0, 2)
        assertEquals(listOf("early", "a", "b", "c"), first.documents.map { it.identifier })
        assertEquals(listOf(9L, 10L, 10L, 10L), first.documents.map { it.eventAtMs })
        assertEquals(10, first.nextCursor)
        assertTrue(first.hasMore)
        val last = storage.chronological(meals, first.nextCursor, 2)
        assertEquals(listOf("late"), last.documents.map { it.identifier })
        assertFalse(last.hasMore)
    }

    @Test fun oversizedTieFailsInsteadOfIssuingPartialPageCursor() = runBlocking {
        val storage = store(LocalV3StoreLimits(tieRows = 2))
        (1..3).forEach { storage.create(input("same-$it")) }
        rejected(LocalV3StoreError.PAGE_CAPACITY) { storage.chronological(meals, 0, 1) }
        assertEquals(3, storage.history(meals, 0, 10).documents.size)
    }

    @Test fun pagePayloadBudgetIsCheckedBeforeMaterializingDocuments() = runBlocking {
        val storage = store(LocalV3StoreLimits(pageBytes = 20))
        (1..3).forEach { storage.create(input("row-$it", it.toLong())) }
        rejected(LocalV3StoreError.PAGE_CAPACITY) { storage.history(meals, 0, 3) }
        rejected(LocalV3StoreError.PAGE_CAPACITY) { storage.chronological(meals, 0, 3) }
        assertEquals(1, storage.history(meals, 0, 1).documents.size)
    }

    @Test fun capacityRefusalPreservesExistingRowsClockAndTombstones() = runBlocking {
        val storage = store(LocalV3StoreLimits(documents = 1))
        val first = storage.create(input()).document
        val deleted = storage.delete(meals, first.identifier, first.modifiedRevision).document
        val before = db.localNightscoutV3Dao().clock()
        rejected(LocalV3StoreError.CAPACITY) { storage.create(input("another")) }
        assertEquals(before, db.localNightscoutV3Dao().clock())
        assertEquals(deleted, storage.get(meals, first.identifier))
        assertEquals(1, db.localNightscoutV3Dao().count())
    }

    @Test fun payloadBudgetReleasesOnlyReplacedBytesNotDeletionIdentity() = runBlocking {
        val storage = store(LocalV3StoreLimits(payloadBytes = 14))
        val first = storage.create(input()).document
        val before = db.localNightscoutV3Dao().clock()
        rejected(LocalV3StoreError.CAPACITY) { storage.create(input("second")) }
        assertEquals(before, db.localNightscoutV3Dao().clock())
        storage.update(input(body = "{}"), first.modifiedRevision)
        storage.create(input("second", body = "{}"))
        assertEquals(4, db.localNightscoutV3Dao().clock()!!.payloadBytes)
        assertEquals(2, db.localNightscoutV3Dao().clock()!!.documentCount)
    }

    @Test fun failedClockWriteRollsBackDocumentAndDoesNotConsumeRevision() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_clock BEFORE INSERT ON local_ns_v3_clock BEGIN SELECT RAISE(ABORT, 'test_write_rejected'); END")
        try { store().create(input()); fail("expected storage error") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(0, db.localNightscoutV3Dao().count())
        assertNull(db.localNightscoutV3Dao().clock())
        sql.execSQL("DROP TRIGGER reject_clock")
        assertEquals(time, store().create(input()).document.modifiedRevision)
    }

    @Test fun cancellationRollsBackAnEnclosingProjectionTransaction() = runBlocking {
        try {
            db.withTransaction { store().create(input()); throw CancellationException("test cancellation") }
        } catch (_: CancellationException) { }
        assertEquals(0, db.localNightscoutV3Dao().count())
        assertNull(db.localNightscoutV3Dao().clock())
    }

    @Test fun concurrentStoreInstancesAllocateUniqueRevisionsAndDeduplicate() = runBlocking {
        val results = coroutineScope {
            (1..32).map { async(Dispatchers.Default) { store().create(input("row-$it")) } }.awaitAll()
        }
        assertEquals((time until time + 32).toList(), results.map { it.document.modifiedRevision }.sorted())
        val duplicate = coroutineScope {
            (1..16).map { async(Dispatchers.Default) { store().create(input("shared")) } }.awaitAll()
        }
        assertEquals(1, duplicate.count { it.changed })
        assertEquals(1, duplicate.map { it.document.modifiedRevision }.distinct().size)
        assertEquals(33, db.localNightscoutV3Dao().count())
    }

    @Test fun secondDatabaseConnectionUsesPersistedClockNotAnInMemoryCounter() = runBlocking {
        val first = store().create(input()).document
        val secondDb = open()
        try {
            val second = LocalNightscoutV3Store(secondDb, { time - 100 }).create(input("second")).document
            val third = store().create(input("third")).document
            assertEquals(first.modifiedRevision + 1, second.modifiedRevision)
            assertEquals(second.modifiedRevision + 1, third.modifiedRevision)
        } finally { secondDb.close() }
    }

    @Test fun invalidMetadataAndJsonNeverReachStorageOrLeakIntoErrors() = runBlocking {
        val bad = listOf(
            input().copy(identifier = "../unsafe"), input().copy(source = ""), input().copy(eventAtMs = null),
            input().copy(eventAtMs = 0), input().copy(eventAtMs = LocalNightscoutV3Store.MAX_TIME + 1),
            input(body = "[]"), input(body = "{\"x\":1,\"x\":2}"), input(body = "{\"x\":NaN}"),
            input(body = "{} {}"), input(body = "{\"srvModified\":1}"), input(body = "{\"isValid\":true}"),
            input(body = "{\"secret\":\"" + "x".repeat(65_536) + "\"}"),
            input(body = "{\"x\":\"" + "\u044f".repeat(40_000) + "\"}"),
            input(body = "{\"x\":\"\\uD800\"}"),
            input(body = "{\"x\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")
        )
        for (value in bad) rejected(LocalV3StoreError.INVALID) { store().create(value) }
        assertEquals(0, db.localNightscoutV3Dao().count())
        assertNull(db.localNightscoutV3Dao().clock())
        assertEquals("LocalV3DocumentInput(redacted)", input().toString())
    }

    @Test fun invalidPageBoundsAreNotSilentlyCoerced() = runBlocking {
        for ((after, limit) in listOf(-1L to 10, 0L to 0, 0L to 1001, Long.MAX_VALUE to 10)) {
            rejected(LocalV3StoreError.INVALID) { store().history(meals, after, limit) }
            rejected(LocalV3StoreError.INVALID) { store().chronological(meals, after, limit) }
        }
    }

    @Test fun invalidClockOrExhaustedRevisionRefusesBeforeWrite() = runBlocking {
        time = 0
        rejected(LocalV3StoreError.CLOCK) { store().create(input()) }
        time = Long.MAX_VALUE
        rejected(LocalV3StoreError.CLOCK) { store().create(input()) }
        assertEquals(0, db.localNightscoutV3Dao().count())
        time = LocalNightscoutV3Store.MAX_TIME
        store().create(input())
        rejected(LocalV3StoreError.STATE) { store().create(input("next")) }
        assertEquals(1, db.localNightscoutV3Dao().count())
    }

    @Test fun missingPersistentClockWithExistingRecordsIsNotReset() = runBlocking {
        store().create(input())
        db.openHelper.writableDatabase.execSQL("DELETE FROM local_ns_v3_clock")
        rejected(LocalV3StoreError.STATE) { store().create(input("second")) }
        assertEquals(1, db.localNightscoutV3Dao().count())
    }

    @Test fun collectionIsolationPreservesOpaqueSettingsWithoutTreatingThemAsCommands() = runBlocking {
        val storage = store()
        val settings = input().copy(collection = LocalV3Collection.SETTINGS, eventAtMs = null,
            payloadJson = "{\"action\":\"temp_target\",\"nested\":{\"x\":null}}")
        storage.create(settings)
        storage.create(input())
        assertEquals(1, storage.history(meals, 0, 10).documents.size)
        assertEquals(1, storage.history(LocalV3Collection.SETTINGS, 0, 10).documents.size)
        assertTrue(storage.chronological(LocalV3Collection.SETTINGS, 0, 10).documents.isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertEquals("LocalNightscoutV3DocumentEntity(redacted)", storage.get(meals, "meal-1").toString())
    }

    private suspend fun rejected(expected: LocalV3StoreError, block: suspend () -> Any?) {
        try { block(); fail("expected $expected") } catch (failure: LocalV3StoreException) {
            assertEquals(expected, failure.reason)
            assertEquals("local_v3_${expected.name.lowercase()}", failure.message)
        }
    }
}
