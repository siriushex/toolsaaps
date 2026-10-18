package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEpisodeFixtures
import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.nutrition.MealPortionObservation
import io.aaps.copilot.domain.nutrition.MealPortionProvenance
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionSourceProblem
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionSourceStatus
import io.aaps.copilot.domain.nutrition.PersonalGiStatus
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
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
    private var onCanonicalRead: (() -> Unit)? = null
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries()
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT", ignoreCase = true) && sql.contains("therapy_events")) {
                    val callback = onCanonicalRead
                    onCanonicalRead = null
                    callback?.invoke()
                }
            }, Executor { it.run() }).build()
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
    private fun prepared(id: Int = 1) = PersonalAbsorptionEpisodeFixtures.evidence(
        MealPortionObservation(id.toString(), Instant.ofEpochMilli(row(id).timestamp), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_CORRECTED, MealPortion.MEDIUM,
            availableAt = Instant.ofEpochMilli(override(id).updatedAtMs), therapyRevision = "r1"), ZoneOffset.UTC.id)

    @Test fun giAnalysisJoinsSameRevisionRoomEvidenceWithoutWrites() = runBlocking {
        db.therapyDao().upsertAll((1..18).map { row(it) })
        (1..18).forEach { id -> db.energyProfileDao().upsertMealOverride(override(id).copy(
            glycemicIndexValue = if (id % 2 == 0) 25.0 else 75.0, glycemicIndexSource = "USER")) }
        val before = db.therapyDao().since(0)
        val result = repository().analyzeGi(ZoneOffset.UTC, MealAbsorptionProfile.MIXED,
            MealPortion.MEDIUM, 720, (1..18).map { prepared(it) })
        assertThat(result.status).isEqualTo(PersonalGiStatus.ANALYZED)
        assertThat(result.model!!.minimumTrainingGi).isEqualTo(25.0)
        assertThat(result.model!!.maximumTrainingGi).isEqualTo(75.0)
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(db.therapyDao().since(0)).isEqualTo(before)
        assertThat(db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1")!!.glycemicIndexValue).isEqualTo(75.0)
    }

    @Test fun giAnalysisDoesNotGuessMissingInsulinOrGiFromLegacyRows() = runBlocking {
        populate()
        val result = repository().analyzeGi(ZoneOffset.UTC, MealAbsorptionProfile.MIXED, MealPortion.MEDIUM, 720)
        assertThat(result.status).isEqualTo(PersonalGiStatus.INSUFFICIENT_DATA)
        assertThat(result.rejections["SOURCE_INDEPENDENT_INSULIN_INTERVALS"]).isEqualTo(5)
        assertThat(result.model).isNull()
    }

    @Test fun sourceReportsLegacyGapsEvenWhenCgmRowsExistWithoutProvenance() = runBlocking {
        populate()
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(timestamp = row(1).timestamp,
            mmol = 6.0, source = "aaps_broadcast", quality = "OK")))
        val before = db.therapyDao().since(0)
        val result = repository().produce(ZoneOffset.UTC)
        assertThat(result.status).isEqualTo(PersonalAbsorptionSourceStatus.READY)
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes).hasSize(5)
        assertThat(result.outcomes.first().sourceProblems).contains(PersonalAbsorptionSourceProblem.GLUCOSE_AVAILABILITY)
        assertThat(result.outcomes.first().sourceProblems).contains(PersonalAbsorptionSourceProblem.INDEPENDENT_INSULIN_INTERVALS)
        assertThat(result.clinicalInfluenceAllowed).isFalse()
        assertThat(db.therapyDao().since(0)).isEqualTo(before)
        assertThat(db.glucoseDao().since(0)).hasSize(1)
    }

    @Test fun timingHistoryReportsWindowSupportWithoutTherapyWritesOrInventedEvidence() = runBlocking {
        populate()
        val before = db.therapyDao().since(0)
        val legacy = repository().timingHistory(ZoneOffset.UTC)
        assertThat(legacy.windows.map { it.candidateMeals }).containsExactly(3, 5, 5).inOrder()
        assertThat(legacy.windows.all { it.qualifiedAbsorptionEpisodes == 0 }).isTrue()
        val qualified = repository().timingHistory(ZoneOffset.UTC, (1..5).map { prepared(it) })
        assertThat(qualified.windows.map { it.qualifiedDays }).containsExactly(3, 5, 5).inOrder()
        assertThat(qualified.recommendedWaitMinutes).isNull()
        assertThat(qualified.notificationAllowed).isFalse()
        assertThat(db.therapyDao().since(0)).isEqualTo(before)
    }

    @Test fun sourceQualifiesExplicitPreparedEvidenceOnlyAgainstActualRoomLabels() = runBlocking {
        populate()
        val before = db.therapyDao().since(0)
        val result = repository().produce(ZoneOffset.UTC, listOf(prepared()))
        assertThat(result.episodes).hasSize(1)
        assertThat(result.episodes.single().meal.canonicalId).isEqualTo("1")
        assertThat(result.episodes.single().latestEvidenceAtMs).isEqualTo(prepared().preparedAtMs)
        assertThat(result.outcomes).hasSize(5)
        assertThat(db.therapyDao().since(0)).isEqualTo(before)
        assertThat(db.energyProfileDao().matchingMealOverrideForIdentity("1", "r1")).isEqualTo(override(1))
    }

    @Test fun preparedPacketIsFrozenBeforeTheCanonicalRoomReadSuspends() = runBlocking {
        populate()
        val frame = prepared()
        val points = frame.input.glucose.toMutableList()
        val packets = mutableListOf(frame.copy(input = frame.input.copy(glucose = points)))
        onCanonicalRead = { points.clear(); packets.clear() }
        val result = repository().produce(ZoneOffset.UTC, packets)
        assertThat(points).isEmpty()
        assertThat(packets).isEmpty()
        assertThat(result.episodes).hasSize(1)
        assertThat(result.episodes.single().latestEvidenceAtMs).isEqualTo(frame.preparedAtMs)
    }

    @Test fun preparedEvidenceCannotRestoreDeletedOrSupersededRoomCarbs() = runBlocking {
        populate()
        db.therapyDao().upsertAll(listOf(row(1, valid = false), row(2, revision = "r2")))
        val result = repository().produce(ZoneOffset.UTC, listOf(prepared(1), prepared(2)))
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.filter { it.canonicalId in setOf("1", "2") }
            .all { PersonalAbsorptionSourceProblem.UNBOUND_MEAL in it.sourceProblems }).isTrue()
    }

    @Test fun acceptedSuggestionAndWrongDeclaredMassDoNotSupplyCanonicalGroundTruth() = runBlocking {
        populate()
        db.energyProfileDao().upsertMealOverride(override(1).copy(portionProvenance = "ACCEPTED_SUGGESTION"))
        db.energyProfileDao().upsertMealOverride(override(2).copy(confirmedCarbsGrams = 36.0))
        val result = repository().produce(ZoneOffset.UTC, listOf(prepared(1), prepared(2)))
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes.filter { it.canonicalId in setOf("1", "2") }
            .all { PersonalAbsorptionSourceProblem.UNBOUND_MEAL in it.sourceProblems }).isTrue()
    }

    @Test fun oversizedPreparedSourceRejectsBeforeTouchingRoom() = runBlocking {
        val frame = prepared()
        db.close()
        val result = repository().produce(ZoneOffset.UTC, List(241) { frame })
        assertThat(result.status).isEqualTo(PersonalAbsorptionSourceStatus.OVERFLOW)
        assertThat(result.episodes).isEmpty()
    }

    @Test fun roomQueryOverflowDoesNotProduceATruncatedTrainingSet() = runBlocking {
        db.therapyDao().upsertAll((1..5001).map { row(it).copy(timestamp = row(1).timestamp) })
        val result = repository().produce(ZoneOffset.UTC)
        assertThat(result.status).isEqualTo(PersonalAbsorptionSourceStatus.OVERFLOW)
        assertThat(result.episodes).isEmpty()
        assertThat(result.outcomes).isEmpty()
    }

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
