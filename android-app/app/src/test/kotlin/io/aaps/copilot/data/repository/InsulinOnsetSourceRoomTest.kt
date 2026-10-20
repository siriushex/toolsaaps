package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.ui.insulinOnsetCountdown
import kotlinx.coroutines.flow.first
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
class InsulinOnsetSourceRoomTest {
    private val now = 10_000_000L

    @Test fun insulinOnlyAnchorIsNotStarvedByCarbsTargetsOrMicroboluses() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val anchor = TherapyEventEntity("normal-insulin", now - 900_000, "correction_bolus", "{\"insulin\":2.2}")
            db.therapyDao().upsertAll(listOf(anchor) + (1..100).flatMap { index ->
                listOf(TherapyEventEntity("food-$index", now - index, "carbs", "{\"carbs\":1}"),
                    TherapyEventEntity("target-$index", now - index, "temp_target", "{}"),
                    TherapyEventEntity("micro-$index", now - index, "correction_bolus", "{\"insulin\":0.1,\"isSMB\":true}"))
            })
            val candidates = db.therapyDao().observeInsulinOnsetCandidates().first()
            assertEquals(101, candidates.size)
            val timer = insulinOnsetCountdown(candidates, AiInsulinContext("FIASP", 27.5, now, fittedProfileId = "FIASP"), now)!!
            assertEquals(anchor.id, timer.recordKey)
            assertEquals(750L, timer.remainingSeconds(now))
            assertFalse(timer.mealAssociated)
        } finally { db.close() }
    }

    @Test fun latestPerKeyKeepsOldProfileIdentityAndConflictingCurrentOnset() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.telemetryDao().upsertAll((1..200).map { index ->
                TelemetrySampleEntity("onset-$index", now - 200 + index, "copilot_runtime_cob_iob",
                    "insulin_real_onset_min", 27.5, null, "min", "OK")
            } + TelemetrySampleEntity("profile-one", now - 5_000_000, "copilot_real_insulin_profile",
                "insulin_profile_real_source_profile", null, "FIASP", null, "OK"))
            val rows = db.telemetryDao().observeInsulinOnsetContext().first()
            assertEquals(2, rows.size)
            assertEquals(true, AiInsulinContext.fromTelemetry("FIASP", rows, now).profileMatches)
            db.telemetryDao().upsertAll(listOf(TelemetrySampleEntity("conflict", now, "copilot_runtime_cob_iob",
                "insulin_real_onset_min", 35.0, null, "min", "OK")))
            val conflicting = db.telemetryDao().observeInsulinOnsetContext().first()
            assertEquals(3, conflicting.size)
            assertNull(AiInsulinContext.fromTelemetry("FIASP", conflicting, now).onsetMinutes)
        } finally { db.close() }
    }
}
