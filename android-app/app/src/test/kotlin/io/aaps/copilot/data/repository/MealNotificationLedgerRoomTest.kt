package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.meal.MealDeliveryClock
import io.aaps.copilot.domain.meal.MealNotificationBlock
import io.aaps.copilot.domain.meal.MealNotificationEligibility
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class MealNotificationLedgerRoomTest {
    private val initial = MealDeliveryClock("boot-a", 10_000_000, 20_000_000, true)
    private val interval = MealNotificationEligibility.INTERVAL_MS

    @Test fun aliasWriteFailureRollsBackTheWholeReservation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).build()
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_alias BEFORE INSERT ON " +
                "meal_notification_claim_aliases BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END")
            try {
                MealNotificationLedger(db).reserve("a", setOf("a"), initial, 0)
                fail("Reservation should fail atomically")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(0, db.mealNotificationClaimDao().count())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_alias")
            assertNull(MealNotificationLedger(db).reserve("a", setOf("a"), initial, 0))
        } finally { db.close() }
    }

    @Test fun raceHasOneWinnerAndClaimSurvivesReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-notification-ledger-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name).build()
        var db = open()
        try {
            val ledger = MealNotificationLedger(db)
            val results = listOf("episode-a", "episode-b").map { episode -> async {
                ledger.reserve(episode, setOf(episode), initial, 0)
            } }.awaitAll()
            assertEquals(1, results.count { it == null })
            assertEquals(1, results.count { it == MealNotificationBlock.COOLDOWN })
            db.close()
            db = open()
            val reopened = MealNotificationLedger(db)
            assertEquals(MealNotificationBlock.COOLDOWN, reopened.reserve("third", setOf("third"),
                initial.copy(elapsedMs = initial.elapsedMs + interval - 1, wallMs = initial.wallMs + interval - 1), 0))
            assertNull(reopened.reserve("third", setOf("third"),
                initial.copy(elapsedMs = initial.elapsedMs + interval, wallMs = initial.wallMs + interval), 0))
            assertEquals(2, db.mealNotificationClaimDao().count())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun aliasesCannotBypassPerEpisodeLimitOrResetItAfterCorrection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).build()
        try {
            val ledger = MealNotificationLedger(db)
            assertNull(ledger.reserve("input-a", setOf("input-a", "canonical-a"), initial, 0))
            assertEquals(MealNotificationBlock.EPISODE_ALREADY_CLAIMED, ledger.reserve("corrected-a",
                setOf("corrected-a", "canonical-a"), initial.copy(elapsedMs = initial.elapsedMs + interval,
                    wallMs = initial.wallMs + interval), 0))
            assertEquals(1, db.mealNotificationClaimDao().count())
        } finally { db.close() }
    }

    @Test fun muteClockUncertaintyAndInvalidIdentityDoNotConsumeClaim() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).build()
        try {
            val ledger = MealNotificationLedger(db)
            assertEquals(MealNotificationBlock.MUTED, ledger.reserve("a", setOf("a"), initial, initial.wallMs + 1))
            assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN,
                ledger.reserve("a", setOf("a"), initial.copy(trusted = false), 0))
            assertEquals(MealNotificationBlock.INVALID_IDENTITY, ledger.reserve("a", emptySet(), initial, 0))
            assertEquals(0, db.mealNotificationClaimDao().count())
            assertNull(ledger.reserve("a", setOf("a"), initial, 0))
            assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN,
                ledger.reserve("b", setOf("b"), initial.copy(bootId = "boot-b", elapsedMs = 1), 0))
        } finally { db.close() }
    }
}
