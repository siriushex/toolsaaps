package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
class MealPortionSuggestionRepositoryTest {
    private lateinit var db: CopilotDatabase
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val settings = MealPortionSettings()
    private val clock = MutableClock(now)
    private val gson = Gson()

    @Before fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun closeDatabase() = db.close()

    private fun event(day: Int, revision: String = "r1", valid: Boolean = true) = TherapyEventEntity(
        "canonical-$day-$revision-$valid", now.minusSeconds(day * 86400L).toEpochMilli(), "carbs",
        gson.toJson(mapOf("aapsCarbId" to day, "aapsRevisionId" to revision,
            "aapsCarbAmount" to 35.0, "aapsCarbIsValid" to valid,
            "aapsCarbClassification" to "AAPS_REAL", "aapsCarbSynthetic" to false,
            "aapsCarbSuperseded" to false))
    )
    private fun override(day: Int) = MealProfileOverrideEntity(day.toString(), "r1", "MIXED", 180,
        "COPILOT_UI", 1, now.minusSeconds(day * 86400L - 60).toEpochMilli(),
        "MEDIUM", "USER_CORRECTED", 35.0)
    private suspend fun history() {
        db.therapyDao().upsertAll((1..5).map { event(it) })
        (1..5).forEach { db.energyProfileDao().upsertMealOverride(override(it)) }
    }
    private fun repository() = MealPortionSuggestionRepository.fromDatabase(db, gson, clock)
    private suspend fun estimate(repository: MealPortionSuggestionRepository = repository()) =
        repository.candidate(settings, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED, ZoneOffset.UTC)

    @Test fun canonicalRoomHistoryUsesMatchingIndependentOverridesReadOnly() = runBlocking {
        history()
        val result = estimate()
        assertThat(result.fromHistory).isTrue()
        assertThat(result.grams).isEqualTo(30.0)
        assertThat(db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1"))
            .isEqualTo(override(1))
    }

    @Test fun staleRevisionAndUnknownOrAcceptedOriginsFallBackWithoutDeletingEvidence() = runBlocking {
        history()
        db.therapyDao().upsertAll(listOf(event(1, "r2").copy(id = event(1).id)))
        assertThat(estimate().fromHistory).isFalse()
        assertThat(db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1")).isNotNull()
        for (origin in listOf(null, "ACCEPTED_SUGGESTION", "SYNTHETIC_UAM", "RESCUE", "PREPARATORY")) {
            (1..5).forEach { db.energyProfileDao().upsertMealOverride(override(it).copy(portionProvenance = origin)) }
            assertThat(estimate().supportMeals).isEqualTo(0)
        }
    }

    @Test fun tombstoneAliasCannotReviveCanonicalMeal() = runBlocking {
        history()
        db.therapyDao().upsertAll(listOf(event(1, valid = false)))
        assertThat(estimate().supportMeals).isEqualTo(4)
        assertThat(estimate().fromHistory).isFalse()
    }

    @Test fun incompleteForeignOrChangedConfirmationCannotBecomeALabel() = runBlocking {
        history()
        val evidence = override(1)
        for (changed in listOf(evidence.copy(source = "REMOTE"), evidence.copy(portion = null),
            evidence.copy(confirmedCarbsGrams = 34.0), evidence.copy(profile = "unknown"),
            evidence.copy(revision = 2), evidence.copy(updatedAtMs = event(1).timestamp - 1))) {
            db.energyProfileDao().upsertMealOverride(changed)
            assertThat(estimate().supportMeals).isEqualTo(4)
            assertThat(db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1")).isEqualTo(changed)
        }
    }

    @Test fun exactAliasDeduplicatesButStringCanonicalIdIsNotTrusted() = runBlocking {
        history()
        db.therapyDao().upsertAll(listOf(event(1).copy(id = "transport-alias")))
        assertThat(estimate().supportMeals).isEqualTo(5)
        db.therapyDao().upsertAll(listOf(event(2).copy(
            payloadJson = event(2).payloadJson.replace("\"aapsCarbId\":2", "\"aapsCarbId\":\"2\""))))
        assertThat(estimate().supportMeals).isEqualTo(4)
    }

    @Test fun changedEvidenceInvalidatesCacheAndFutureConfirmationIsExcluded() = runBlocking {
        history()
        val repository = repository()
        val first = estimate(repository)
        org.junit.Assert.assertSame(first, estimate(repository))
        db.energyProfileDao().upsertMealOverride(override(1).copy(updatedAtMs = now.plusSeconds(60).toEpochMilli()))
        assertThat(estimate(repository).supportMeals).isEqualTo(4)
        clock.now = now.plusSeconds(61)
        assertThat(estimate(repository).supportMeals).isEqualTo(5)
    }

    @Test fun cacheSeparatesSettingsTimeZoneAndProfileAndDoesNotScheduleWork() = runBlocking {
        var reads = 0
        val observations = (1..5).map { MealPortionObservation(it.toString(), now.minusSeconds(it * 86400L),
            35.0, MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_CORRECTED, MealPortion.MEDIUM) }
        val repository = MealPortionSuggestionRepository(clock) { _, _ -> reads++; observations }
        assertThat(reads).isEqualTo(0)
        assertThat(estimate(repository).grams).isEqualTo(30.0)
        val changed = settings.copy(medium = settings.medium.copy(defaultGrams = 30.0))
        assertThat(repository.candidate(changed, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED,
            ZoneOffset.UTC).grams).isEqualTo(32.5)
        assertThat(repository.candidate(settings, MealPortion.MEDIUM, MealAbsorptionProfile.FAST,
            ZoneOffset.UTC).fromHistory).isFalse()
        repository.candidate(settings, MealPortion.MEDIUM, MealAbsorptionProfile.MIXED, ZoneId.of("Europe/Berlin"))
        assertThat(reads).isEqualTo(4)
    }

    @Test fun unboundedSnapshotFailsClosedToConfiguredPreset() = runBlocking {
        val rows = (1..5001).map { MealPortionObservation(it.toString(), now.minusSeconds(86400), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_CORRECTED, MealPortion.MEDIUM) }
        val repository = MealPortionSuggestionRepository(clock) { _, _ -> rows }
        assertThat(estimate(repository).grams).isEqualTo(25.0)
        assertThat(estimate(repository).supportMeals).isEqualTo(0)
    }

    @Test fun availabilityFilteringCannotReviveConflictedIdentityInCache() = runBlocking {
        val rows = (1..5).map { MealPortionObservation(it.toString(), now.minusSeconds(it * 86400L), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_CORRECTED, MealPortion.MEDIUM) }
        val conflicted = rows + rows.first().copy(deleted = true, availableAt = now.plusSeconds(60))
        val repository = MealPortionSuggestionRepository(clock) { _, _ -> conflicted }
        assertThat(estimate(repository).supportMeals).isEqualTo(4)
        assertThat(estimate(repository).fromHistory).isFalse()
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
