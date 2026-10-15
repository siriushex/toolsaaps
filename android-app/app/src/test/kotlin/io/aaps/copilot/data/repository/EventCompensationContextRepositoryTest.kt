package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.*
import io.aaps.copilot.domain.events.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EventCompensationContextRepositoryTest {
    private val gson = Gson()
    private val start = 1_800_000_000_000L
    private val minute = 60_000L
    private fun row(revision: String = "r1") = TherapyEventEntity("meal-context-1", start, "carbs", gson.toJson(mapOf(
        "aapsCarbId" to 51, "aapsRevisionId" to revision, "aapsCarbAmount" to 35.0,
        "aapsCarbIsValid" to true, "aapsCarbClassification" to "AAPS_REAL", "aapsCarbSynthetic" to false)))
    private fun selected(row: TherapyEventEntity = row()) = EventTimelineRepository(gson)
        .prepareTherapyEvent(TherapySanitizer.toDomainEvents(listOf(row), gson).single())!!
    private fun override() = MealProfileOverrideEntity("51", "r1", "MIXED", 180,
        "COPILOT_UI", 1, start, glycemicIndexValue = 55.0, glycemicIndexSource = "USER")
    private fun sample(key: String, value: Double, ts: Long = start, unit: String? = "h", id: String = key) =
        TelemetrySampleEntity(id, ts, "aaps_broadcast", key, value, null, unit, "OK")
    private suspend fun withDb(block: suspend (CopilotDatabase, EventCompensationContextRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.therapyDao().upsertAll(listOf(row()))
            db.energyProfileDao().upsertMealOverride(override())
            db.glucoseDao().upsertAll((0..12).map {
                GlucoseSampleEntity(timestamp = start + it * 5 * minute, mmol = 5.0 + it / 4.0,
                    source = "aaps_broadcast", quality = "OK")
            })
            block(db, EventCompensationContextRepository(db, gson))
        } finally { db.close() }
    }

    @Test fun realRoomReadLinksGiToExactRevisionAndDoesNotWrite() = runBlocking { withDb { db, repository ->
        val before = db.therapyDao().since(0)
        db.telemetryDao().upsertAll(listOf(sample("sensor_age_hours", 200.0),
            sample("isf_value", 2.5, unit = "mmol/L/U"),
            sample("isf_value", 2.0, start - 60 * minute, "mmol/L/U", "isf-before")))
        val result = repository.load(selected(), start + 60 * minute)
        assertEquals(EventContextStatus.READY, result.status)
        assertEquals(55.0, result.glycemicIndex!!.value, 0.0)
        assertEquals(8.0, result.glucose!!.peak, 0.0)
        assertEquals(25.0, result.isf!!.changePercent, 0.0)
        assertEquals(200.0, result.sensorAge!!.hours, 0.0)
        assertEquals(before, db.therapyDao().since(0))
        assertEquals(override(), db.energyProfileDao().matchingMealOverrideForIdentity("51", "r1"))
        assertFalse(gson.toJson(selected()).contains("displayMealIdentity"))
        assertFalse(gson.toJson(selected()).contains("displayMealRevision"))
    } }

    @Test fun changedSelectionAndMismatchedGiAreNotSilentlyJoined() = runBlocking { withDb { db, repository ->
        val old = selected()
        db.therapyDao().upsertAll(listOf(row("r2")))
        assertEquals(EventContextStatus.SOURCE_CHANGED, repository.load(old, start + 60 * minute).status)
        val fresh = repository.load(selected(row("r2")), start + 60 * minute)
        assertEquals(EventContextStatus.READY, fresh.status)
        assertNull(fresh.glycemicIndex)
    } }

    @Test fun corruptGiAndFutureOverrideCannotBecomeKnownGi() = runBlocking { withDb { db, repository ->
        db.energyProfileDao().upsertMealOverride(override().copy(glycemicIndexSource = "MODEL"))
        assertNull(repository.load(selected(), start + 60 * minute).glycemicIndex)
        db.energyProfileDao().upsertMealOverride(override().copy(updatedAtMs = start + 61 * minute))
        assertNull(repository.load(selected(), start + 60 * minute).glycemicIndex)
    } }

    @Test fun sensorQualityWarningSuppressesGlucoseInterpretation() = runBlocking { withDb { db, repository ->
        db.telemetryDao().upsertAll(listOf(sample("sensor_quality_blocked", 1.0, start + 10 * minute, null)))
        val result = repository.load(selected(), start + 60 * minute)
        assertTrue(result.sensorConcern)
        assertNull(result.glucose)
        assertNotNull(result.glycemicIndex)
    } }

    @Test fun unknownUnitsAndStaleOrConflictingSensorAgeStayUnknown() = runBlocking { withDb { db, repository ->
        db.telemetryDao().upsertAll(listOf(sample("isf_value", 2.0, start - 60 * minute, null, "before"),
            sample("isf_value", 2.5, unit = null), sample("sensor_age_hours", 200.0, start - 20 * minute)))
        var result = repository.load(selected(), start + 60 * minute)
        assertNull(result.isf)
        assertNull(result.sensorAge)
        db.telemetryDao().upsertAll(listOf(sample("sensor_age_hours", 200.0), sample("sensor_age_hours", 300.0, id = "conflict")))
        result = repository.load(selected(), start + 60 * minute)
        assertNull(result.sensorAge)
    } }

    @Test fun overlappingMealAndSensorChangeAreExplicit() = runBlocking { withDb { db, repository ->
        db.therapyDao().upsertAll(listOf(row().copy(id = "meal-context-2", timestamp = start + 30 * minute),
            TherapyEventEntity("sensor-context-1", start + 40 * minute, "sensor_change", "{}")))
        val result = repository.load(selected(), start + 60 * minute)
        assertTrue(result.overlappingMeal)
        assertTrue(result.sensorConcern)
        assertNull(result.glucose)
    } }

    @Test fun boundedOverflowDoesNotPresentPartialHistoryAsComplete() = runBlocking { withDb { db, repository ->
        db.glucoseDao().upsertAll((1..601).map {
            GlucoseSampleEntity(timestamp = start + it, mmol = 6.0, source = "aaps_broadcast", quality = "OK")
        })
        val result = repository.load(selected(), start + 60 * minute)
        assertEquals(EventContextStatus.OVERFLOW, result.status)
        assertNull(result.glucose)
    } }

    @Test fun futureMealCannotUseCurrentHistory() = runBlocking { withDb { _, repository ->
        assertEquals(EventContextStatus.UNAVAILABLE, repository.load(selected(), start - 1).status)
    } }

    @Test fun futureTelemetryAndCurrentSensorAgeCannotDescribeAnOlderMeal() = runBlocking { withDb { db, repository ->
        db.telemetryDao().upsertAll(listOf(sample("sensor_age_hours", 300.0, start + minute),
            sample("isf_value", 45.0, start + minute, "mg/dL/U"),
            sample("isf_value", 36.0, start - 60 * minute, "mg/dL/U", "before")))
        val result = repository.load(selected(), start + 60 * minute)
        assertNull(result.sensorAge)
        assertNull(result.isf)
    } }

    @Test fun explicitMgdlIsfIsNormalizedButUnknownCgmSourceIsNotUsed() = runBlocking { withDb { db, repository ->
        db.telemetryDao().upsertAll(listOf(sample("isf_value", 45.0, unit = "mg/dL/U"),
            sample("isf_value", 36.0, start - 60 * minute, "mg/dL/U", "before")))
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(timestamp = start + minute,
            mmol = 7.0, source = "model_preview", quality = "OK")))
        val result = repository.load(selected(), start + 60 * minute)
        assertEquals(25.0, result.isf!!.changePercent, 0.0)
        assertNull(result.glucose)
    } }
}
