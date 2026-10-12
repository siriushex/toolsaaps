package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PersonalAbsorptionRepositoryTest {
    private lateinit var db: CopilotDatabase
    private val gson = Gson()
    private val now = Instant.parse("2026-07-20T12:00:00Z")
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()
    private fun row(id: Int, revision: String = "r1", valid: Boolean = true) = TherapyEventEntity(
        "real-$id-$revision-$valid", now.minusSeconds(id * 86400L).toEpochMilli(), "carbs",
        gson.toJson(mapOf("aapsCarbId" to id, "aapsRevisionId" to revision,
            "aapsCarbAmount" to 35.0, "aapsCarbIsValid" to valid,
            "aapsCarbClassification" to "AAPS_REAL", "aapsCarbSynthetic" to false,
            "aapsCarbSuperseded" to false)))
    private fun override(id: Int) = MealProfileOverrideEntity(id.toString(), "r1", "MIXED", 120,
        "COPILOT_UI", 1, row(id).timestamp + 1000, "MEDIUM", "USER_CORRECTED", 35.0)
    private suspend fun populate() {
        db.therapyDao().upsertAll((1..5).map { row(it) })
        (1..5).forEach { db.energyProfileDao().upsertMealOverride(override(it)) }
    }
    private fun repository() = PersonalAbsorptionRepository(db, gson, Clock.fixed(now, ZoneOffset.UTC))

    @Test fun auditFindsCanonicalMealsButDoesNotInventMissingLearningContext() = runBlocking {
        populate()
        val before = db.therapyDao().since(0)
        val result = repository().audit(ZoneOffset.UTC)
        assertThat(result.independentDeclaredMeals).isEqualTo(5)
        assertThat(result.eligibleTimingMeals).isEqualTo(0)
        assertThat(result.missingInputs).contains("independent_insulin_intervals")
        assertThat(result.missingInputs).contains("historical_timezone")
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(db.therapyDao().since(0)).isEqualTo(before)
        assertThat(db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1")).isEqualTo(override(1))
    }

    @Test fun tombstonesAndStaleRevisionCannotBeDeclaredGroundTruth() = runBlocking {
        populate()
        db.therapyDao().upsertAll(listOf(row(1, valid = false), row(2, revision = "r2")))
        assertThat(repository().audit(ZoneOffset.UTC).independentDeclaredMeals).isEqualTo(3)
    }

    @Test fun acceptedModelSuggestionDoesNotBecomeIndependentLabel() = runBlocking {
        populate()
        (1..5).forEach { db.energyProfileDao().upsertMealOverride(override(it).copy(portionProvenance = "ACCEPTED_SUGGESTION")) }
        assertThat(repository().audit(ZoneOffset.UTC).independentDeclaredMeals).isEqualTo(0)
    }

    @Test fun overrideOverflowDoesNotProduceAnApparentlyCompletePartialAudit() {
        val result = PersonalAbsorptionRepository.auditRows(listOf(row(1)), List(5001) { override(1) }, gson,
            now.minusSeconds(21 * 86400), now, now)
        assertThat(result.inputOverflow).isTrue()
        assertThat(result.independentDeclaredMeals).isEqualTo(0)
    }
}
