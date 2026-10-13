package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.nutrition.LentaMealHistoryStatus
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LentaMealHistoryRepositoryTest {
    private val gson = Gson()
    private val cutoff = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
    private fun row(id: Int, revision: String = "r1", valid: Boolean = true) = TherapyEventEntity(
        "food-$id-$revision-$valid", cutoff - id * 86400000L, "carbs", gson.toJson(mapOf(
            "aapsCarbId" to id, "aapsRevisionId" to revision, "aapsCarbAmount" to 35.0,
            "aapsCarbIsValid" to valid, "aapsCarbClassification" to "AAPS_REAL",
            "aapsCarbSynthetic" to false, "aapsCarbSuperseded" to false)))
    private fun evidence(id: Int) = MealProfileOverrideEntity(id.toString(), "r1", "MIXED", 120,
        "COPILOT_UI", 1, row(id).timestamp + 1000, "MEDIUM", "USER_CORRECTED", 35.0,
        80.0, "USER")

    @Test fun loadsRealRoomHistoryReadOnlyWithOriginalGiProvenance() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.therapyDao().upsertAll(listOf(row(1), row(2)))
            db.energyProfileDao().upsertMealOverride(evidence(1))
            db.energyProfileDao().upsertMealOverride(evidence(2))
            val before = db.therapyDao().since(0)
            val result = LentaMealHistoryRepository(db, gson).load(cutoff, ZoneOffset.UTC)
            assertEquals(LentaMealHistoryStatus.READY, result.status)
            assertEquals(2, result.entries.size)
            assertEquals(80.0, result.entries.first().glycemicIndex!!.value, 0.0)
            assertEquals(before, db.therapyDao().since(0))
            assertEquals(evidence(1), db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1"))
        } finally { db.close() }
    }

    @Test fun excludesTombstonesConflictsMissingProvenanceFutureAndUnfinishedDay() {
        val rows = listOf(row(1), row(1, valid = false), row(2), row(2, "r2"), row(3), row(4),
            row(5).copy(timestamp = cutoff - 60000), row(6), row(7))
        val overrides = (1..7).map { evidence(it) }.map {
            when (it.canonicalTherapyIdentity) {
                "3" -> it.copy(portionProvenance = "ACCEPTED_SUGGESTION")
                "4" -> it.copy(updatedAtMs = cutoff + 1)
                "6" -> it.copy(confirmedCarbsGrams = 36.0)
                else -> it
            }
        }
        val result = LentaMealHistoryRepository.fromRows(cutoff, ZoneOffset.UTC, rows, overrides, gson)
        assertEquals(listOf("7"), result.entries.map { it.meal.canonicalId })
    }

    @Test fun duplicateImportsCountOnceInvalidGiDoesNotDiscardValidFood() {
        val result = LentaMealHistoryRepository.fromRows(cutoff, ZoneOffset.UTC,
            listOf(row(1), row(1).copy(id = "another-import")),
            listOf(evidence(1).copy(glycemicIndexValue = Double.NaN)), gson)
        assertEquals(1, result.entries.size)
        assertNull(result.entries.single().glycemicIndex)
    }

    @Test fun inputCapRejectsWholeSnapshotInsteadOfTrainingOnTruncatedSubset() {
        val result = LentaMealHistoryRepository.fromRows(cutoff, ZoneOffset.UTC,
            List(5001) { row(it + 1) }, listOf(evidence(1)), gson)
        assertEquals(LentaMealHistoryStatus.OVERFLOW, result.status)
        assertTrue(result.entries.isEmpty())
    }
}
