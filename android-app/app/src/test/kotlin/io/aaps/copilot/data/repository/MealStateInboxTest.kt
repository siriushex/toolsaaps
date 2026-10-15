package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.meal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MealStateInboxTest {
    private val input = MealInput("manual:meal:receipt", 1000, MealCarbRange(20.0, 20.0))
    private val receipt = MealAapsConfirmation(input.id, MealRecordRevision("42", 2, 1200, 20.0, false))

    private suspend fun withDatabase(queries: MutableList<String>? = null, block: suspend (CopilotDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val builder = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
        if (queries != null) builder.setQueryCallback({ sql, _ -> queries.add(sql) }, java.util.concurrent.Executor { it.run() })
        val db = builder.build()
        try { block(db) } finally { db.close() }
    }

    @Test fun acknowledgementBeforeInputWaitsAndLatestTombstoneSurvivesOldReplay() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt.copy(inputId = null,
                record = receipt.record.copy(revision = 3, deleted = true)))))
            assertEquals(0, inbox.drainBatch().applied)
            inbox.persist(MealIngestionEvent.Input(input))
            assertEquals(1, inbox.drainBatch().applied)
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            assertEquals(0, inbox.drainBatch().applied)
            val stored = MealStateRepository(db).get(input.id)!!
            assertEquals(input, stored.identity.input)
            assertTrue(stored.identity.aapsRecord!!.deleted)
            assertEquals(3L, stored.identity.aapsRecord!!.revision)
        }
    }

    @Test fun unchangedPageUsesOneReceiptReadAndNoWrites() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        withDatabase(queries) { db ->
            val inbox = MealStateInbox(db)
            val event = MealIngestionEvent.Confirmed((1..100).map {
                receipt.copy(inputId = null, record = receipt.record.copy(canonicalId = it.toString()))
            })
            inbox.persist(event)
            queries.clear()
            inbox.persist(event)
            val receiptQueries = synchronized(queries) { queries.toList() }.filter { it.contains("meal_state_receipts") }
            assertEquals(1, receiptQueries.count { it.startsWith("SELECT", ignoreCase = true) })
            assertFalse(receiptQueries.any { it.startsWith("UPDATE", ignoreCase = true) || it.startsWith("INSERT", ignoreCase = true) })
        }
    }

    @Test fun newerUnlinkedRecordCanReceiveIdentityFromOlderAcknowledgement() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt.copy(inputId = null,
                record = receipt.record.copy(revision = 4, grams = 30.0)))))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            inbox.persist(MealIngestionEvent.Input(input))
            assertEquals(1, inbox.drainBatch().applied)
            assertEquals(30.0, MealStateRepository(db).get(input.id)!!.identity.aapsRecord!!.grams, 0.0)
        }
    }

    @Test fun conflictingRecordsAreQuarantinedAndDoNotBlockOtherMeals() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Input(input))
            val other = input.copy(id = "manual:meal:other")
            inbox.persist(MealIngestionEvent.Input(other))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt,
                receipt.copy(record = receipt.record.copy(canonicalId = "43")),
                receipt.copy(inputId = other.id, record = receipt.record.copy(canonicalId = "44")))))
            assertEquals(1, inbox.drainBatch().applied)
            assertEquals(2, db.mealReceiptDao().conflictCount())
            assertNull(MealStateRepository(db).get(input.id)!!.identity.aapsRecord)
            assertEquals("44", MealStateRepository(db).get(other.id)!!.identity.aapsRecord!!.canonicalId)
        }
    }

    @Test fun outerTransactionRollbackDoesNotLeaveReceipt() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            try {
                db.withTransaction {
                    inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
                    error("rollback source import")
                }
            } catch (_: IllegalStateException) { }
            inbox.persist(MealIngestionEvent.Input(input))
            assertEquals(0, inbox.drainBatch().applied)
            assertNull(db.mealReceiptDao().get("42"))
        }
    }

    @Test fun equalRevisionConflictIsStickyAndRepeatedImportDoesNotRewriteReceipt() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Input(input))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_duplicate_write BEFORE UPDATE ON meal_state_receipts " +
                "BEGIN SELECT RAISE(ABORT, 'duplicate receipt write'); END")
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER no_duplicate_write")
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt.copy(record = receipt.record.copy(grams = 30.0)))))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt.copy(record = receipt.record.copy(revision = 3)))))
            assertEquals(0, inbox.drainBatch().applied)
            assertEquals(1, db.mealReceiptDao().conflictCount())
        }
    }

    @Test fun conflictWithExistingIdentityIsDurablyQuarantined() = runBlocking {
        withDatabase { db ->
            val state = MealStateRepository(db)
            state.recordInput(input)
            state.reconcile(input.id, receipt.record.copy(canonicalId = "older-link"))
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            inbox.drainBatch()
            assertTrue(db.mealReceiptDao().get("42")!!.conflicted)
            assertEquals(0, inbox.drainBatch().applied)
        }
    }

    @Test fun realImporterCommitsTherapyAndReceiptTogetherAndOnlyThenSignals() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            var signals = 0
            val importer = AapsCarbHistoryImporter(db.therapyDao(),
                AapsCarbImportTransactionRunner { block -> db.withTransaction { block() } },
                persistMealPage = { inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt))) },
                onCommittedPage = { signals++ })
            val row = AapsCarbHistoryRow(42, 2, 1000, true, null, 1200, 0, 20.0,
                "copilot:manual:meal:receipt", null, false, null, null, false)
            val page = AapsCarbHistoryPage(listOf(row), 0, 0, false, 2000, 2)
            try { importer.importPage(page) { false }; fail("Must reject superseded transaction") }
            catch (_: AapsCarbImportSupersededException) { }
            assertEquals(0, signals)
            assertNull(db.mealReceiptDao().get("42"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM therapy_events").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            importer.importPage(page)
            assertEquals(1, signals)
            assertNotNull(db.mealReceiptDao().get("42"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM therapy_events").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun failedAppliedMarkerRollsBackStateAndCanBeRetried() = runBlocking {
        withDatabase { db ->
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Input(input))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_receipt BEFORE UPDATE ON meal_state_receipts " +
                "WHEN NEW.appliedRevision IS NOT NULL BEGIN SELECT RAISE(ABORT, 'test storage failure'); END")
            try { inbox.drainBatch(); fail("Must propagate storage failure") } catch (_: android.database.sqlite.SQLiteException) { }
            assertNull(MealStateRepository(db).get(input.id)!!.identity.aapsRecord)
            assertNull(db.mealReceiptDao().get("42")!!.appliedRevision)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_receipt")
            assertEquals(1, inbox.drainBatch().applied)
        }
    }

    @Test fun pendingReceiptSurvivesDatabaseReopenWithoutAnyInMemorySignal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-inbox-restart.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, CopilotDatabase::class.java, name).build()
            try {
                val inbox = MealStateInbox(first)
                inbox.persist(MealIngestionEvent.Input(input))
                inbox.persist(MealIngestionEvent.Confirmed(listOf(receipt)))
            } finally { first.close() }
            val second = Room.databaseBuilder(context, CopilotDatabase::class.java, name).build()
            try {
                assertEquals(1, MealStateInbox(second).drainBatch().applied)
                assertEquals(receipt.record, MealStateRepository(second).get(input.id)!!.identity.aapsRecord)
                assertEquals(0, MealStateInbox(second).drainBatch().applied)
            } finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }
}
