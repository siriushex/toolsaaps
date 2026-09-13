package io.aaps.copilot.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult
import io.aaps.copilot.domain.profile.PlannedActivityType
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnergyProfileRepositoryRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CopilotDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun concurrentRefreshesUseGeneratedTransactionToPromoteAndDeleteExactlyOnce() = runBlocking {
        val command = ActionCommand(
            id = "command-room-concurrent",
            type = "carbs",
            params = mapOf("carbsGrams" to "20.0"),
            safetySnapshot = SafetySnapshot(false, true, null, 0),
            idempotencyKey = "room-concurrent"
        )
        val therapy = TherapyEvent(
            ts = 1_000L,
            type = "carbs",
            payload = mapOf(
                "notes" to "copilot:${command.idempotencyKey}",
                "aapsCarbAmount" to "20.0",
                "aapsCarbIsValid" to "true",
                "aapsCarbClassification" to "AAPS_REAL",
                "aapsCarbSynthetic" to "false",
                "aapsCarbSuperseded" to "false"
            ),
            componentTrust = TherapyEventComponentTrust(
                canonicalCarbId = 901L,
                canonicalCarbRevision = "revision-a"
            )
        )
        val stagingRepository = EnergyProfileRepository(db.energyProfileDao(), clock = { 1_000L })
        stagingRepository.stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TABLE pending_intent_delete_audit (id INTEGER PRIMARY KEY AUTOINCREMENT)"
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER audit_pending_meal_profile_intent_delete " +
                "AFTER DELETE ON pending_meal_profile_intents " +
                "BEGIN INSERT INTO pending_intent_delete_audit (id) VALUES (NULL); END"
        )

        coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    EnergyProfileRepository(db.energyProfileDao(), clock = { 2_000L })
                        .refreshPendingSelections(listOf(therapy))
                },
                async(Dispatchers.Default) {
                    EnergyProfileRepository(db.energyProfileDao(), clock = { 2_000L })
                        .refreshPendingSelections(listOf(therapy))
                }
            ).awaitAll()
        }

        assertNotNull(db.energyProfileDao().matchingMealOverrideForIdentity("901", "revision-a"))
        assertNull(db.energyProfileDao().pendingMealProfileIntent(command.idempotencyKey))
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM pending_intent_delete_audit")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
        )
    }

    @Test
    fun replacementInsertAbortRollsBackAndPreservesExistingSchedules() = runBlocking {
        val repository = EnergyProfileRepository(db.energyProfileDao(), clock = { 1_000L })
        val existing = listOf(
            schedule("existing-morning", LocalDateTime.of(2026, 8, 8, 8, 0)),
            schedule("existing-evening", LocalDateTime.of(2026, 8, 8, 18, 0))
        )
        assertEquals(
            PlannedActivityScheduleSaveResult.Saved,
            repository.savePlannedActivitySchedule(existing)
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_planned_activity_event_replacement " +
                "BEFORE INSERT ON planned_activity_events " +
                "BEGIN SELECT RAISE(ABORT, 'forced planned activity replacement failure'); END"
        )

        val failure = try {
            runCatching {
                repository.savePlannedActivitySchedule(
                    listOf(schedule("replacement", LocalDateTime.of(2026, 8, 9, 8, 0)))
                )
            }.exceptionOrNull()
        } finally {
            db.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS abort_planned_activity_event_replacement"
            )
        }

        assertNotNull(failure)
        assertTrue(failure is SQLiteException)
        assertEquals(existing.map { it.eventId }, db.energyProfileDao().allEvents().map { it.eventId })
    }

    @Test
    fun successfulReplacementLeavesOnlyNewSchedules() = runBlocking {
        val repository = EnergyProfileRepository(db.energyProfileDao(), clock = { 1_000L })
        assertEquals(
            PlannedActivityScheduleSaveResult.Saved,
            repository.savePlannedActivitySchedule(
                listOf(
                    schedule("existing-morning", LocalDateTime.of(2026, 8, 8, 8, 0)),
                    schedule("existing-evening", LocalDateTime.of(2026, 8, 8, 18, 0))
                )
            )
        )
        val replacements = listOf(
            schedule("replacement-walk", LocalDateTime.of(2026, 8, 9, 8, 0)),
            schedule("replacement-strength", LocalDateTime.of(2026, 8, 9, 18, 0))
        )

        assertEquals(
            PlannedActivityScheduleSaveResult.Saved,
            repository.savePlannedActivitySchedule(replacements)
        )

        assertEquals(
            replacements.map { it.eventId },
            db.energyProfileDao().allEvents().map { it.eventId }
        )
    }

    @Test
    fun latestPhysicalActivityRatioUsesOnlyAllowedSourcesAndBoundedNewestSample() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("outside-before", 499L, "local_sensor", 1.20),
                telemetry("outside-after", 1_001L, "health_connect", 1.25),
                telemetry("aaps-autosens", 1_000L, "aaps_broadcast", 1.50),
                telemetry("nightscout", 999L, "nightscout", 1.45),
                telemetry("local-same-ts", 900L, "local_sensor", 1.15),
                telemetry("health-same-ts", 900L, "health_connect", 1.30),
                telemetry("untrusted-newest", 950L, "health_connect", 1.40, quality = "STALE")
            )
        )

        val result = db.telemetryDao().latestPhysicalActivityRatioSince(since = 500L, through = 1_000L)

        assertNotNull(result)
        assertEquals("health-same-ts", result?.id)
        assertEquals("health_connect", result?.source)
        assertEquals(900L, result?.timestamp)
    }

    @Test
    fun enabledEventsExcludesDisabledSchedulesInRealRoomQuery() = runBlocking {
        db.energyProfileDao().upsertEvent(event("active", enabled = true))
        db.energyProfileDao().upsertEvent(event("disabled", enabled = false))

        assertEquals(listOf("active"), db.energyProfileDao().enabledEvents().map { it.eventId })
    }

    private fun schedule(eventId: String, localStart: LocalDateTime) = PlannedActivitySchedule(
        eventId = eventId,
        enabled = true,
        title = eventId,
        type = PlannedActivityType.WALKING,
        intensity = PlannedActivityIntensity.MEDIUM,
        localStart = localStart,
        durationMinutes = 60,
        timezoneId = "UTC",
        recurrenceDays = emptySet(),
        recurrenceEndEpochDay = null,
        revision = 1L,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L
    )

    private fun telemetry(
        id: String,
        timestamp: Long,
        source: String,
        value: Double,
        quality: String = "trusted"
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = "activity_ratio",
        valueDouble = value,
        valueText = null,
        unit = "ratio",
        quality = quality
    )

    private fun event(eventId: String, enabled: Boolean) = PlannedActivityEventEntity(
        eventId = eventId,
        enabled = enabled,
        title = eventId,
        activityType = PlannedActivityType.WALKING.name,
        intensity = PlannedActivityIntensity.LIGHT.name,
        localStartIso = "2026-08-08T09:00:00",
        durationMinutes = 30,
        timezoneId = "UTC",
        recurrenceDaysMask = 0,
        recurrenceEndEpochDay = null,
        revision = 1L,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L
    )
}
