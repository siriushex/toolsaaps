package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
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
class MealStateIngestionRoomTest {
    private suspend fun withProcessor(block: suspend (MealStateRepository, MealStateIngestionProcessor) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).build()
        try { val repo = MealStateRepository(db); block(repo, MealStateIngestionProcessor(repo)) }
        finally { db.close() }
    }
    private val input = MealInput("manual:meal:input", 1000, MealCarbRange(20.0, 20.0))
    private val confirmed = MealAapsConfirmation(input.id, MealRecordRevision("42", 2, 1200, 20.0, false))

    @Test fun confirmedRowLinksOnlyExplicitInputAndLaterDeletionNeedsNoNote() = runBlocking {
        withProcessor { repo, processor ->
            processor.process(MealIngestionEvent.Input(input))
            processor.process(MealIngestionEvent.Confirmed(listOf(confirmed)))
            processor.process(MealIngestionEvent.Input(input.copy(recordedAtMs = 1500)))
            assertEquals(input, repo.get(input.id)!!.identity.input)
            assertEquals(confirmed.record, repo.get(input.id)!!.identity.aapsRecord)
            processor.process(MealIngestionEvent.Confirmed(listOf(confirmed.copy(inputId = null,
                record = confirmed.record.copy(revision = 3, deleted = true)))))
            assertTrue(repo.get(input.id)!!.identity.aapsRecord!!.deleted)
            processor.process(MealIngestionEvent.Confirmed(listOf(confirmed)))
            assertTrue(repo.get(input.id)!!.identity.aapsRecord!!.deleted)
        }
    }

    @Test fun nearbyUnlinkedCarbDoesNotBecomeConfirmation() = runBlocking {
        withProcessor { repo, processor ->
            processor.process(MealIngestionEvent.Input(input))
            processor.process(MealIngestionEvent.Confirmed(listOf(confirmed.copy(inputId = null))))
            assertNull(repo.get(input.id)!!.identity.aapsRecord)
        }
    }

    @Test fun ambiguousExplicitNoteRejectsBeforeBindingEitherRecord() = runBlocking {
        withProcessor { repo, processor ->
            processor.process(MealIngestionEvent.Input(input))
            try {
                processor.process(MealIngestionEvent.Confirmed(listOf(confirmed,
                    confirmed.copy(record = confirmed.record.copy(canonicalId = "43")))))
                fail("Ambiguous note must reject")
            } catch (_: IllegalArgumentException) { }
            assertNull(repo.get(input.id)!!.identity.aapsRecord)
        }
    }
}
