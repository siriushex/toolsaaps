package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.EnergyProfileDao
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.PendingMealProfileIntentEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.repository.EnergyProfileRepository
import io.aaps.copilot.data.repository.ManualMealSubmission
import io.aaps.copilot.data.repository.MealDeliveryStatus
import io.aaps.copilot.data.repository.MealEnergyOverrideDataSource
import io.aaps.copilot.data.repository.MealEnergyOverrideRepository
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainViewModelManualMealEnergyTest {

    @Test
    fun manualMealEnergyValidationAcceptsAnAbsentValueAndRejectsInvalidValues() {
        assertThat(MainViewModel.isManualMealEnergyInputValid(null)).isEqualTo(true)
        assertThat(MainViewModel.isManualMealEnergyInputValid(540.0)).isEqualTo(true)
        assertThat(MainViewModel.isManualMealEnergyInputValid(0.0)).isEqualTo(false)
        assertThat(MainViewModel.isManualMealEnergyInputValid(Double.NaN)).isEqualTo(false)
    }

    @Test
    fun failedSubmitCarbsDoesNotLeaveManualMealEnergyIntentOrCreateOverrideLater() = runTest {
        val dao = RecordingEnergyProfileDao()
        val storedOverrides = mutableListOf<MealEnergyOverrideEntity>()
        val energyProfileRepository = EnergyProfileRepository(
            energyProfileDao = dao,
            mealEnergyOverrideRepository = MealEnergyOverrideRepository(
                source = MealEnergyOverrideDataSource(
                    therapyById = { error("failed delivery must not query therapy") },
                    save = storedOverrides::add
                ),
                gson = Gson(),
                clock = { 123L }
            ),
            clock = { 123L }
        )
        var submitCalls = 0
        val submission = ManualMealSubmission(
            sendCarbs = {
                submitCalls += 1
                false
            },
            stageSelection = { command, selection, manualMealEnergyKcal ->
                energyProfileRepository.stageSelectionAfterSubmittedCarbAction(
                    command = command,
                    selection = selection,
                    manualMealEnergyKcal = manualMealEnergyKcal
                )
            },
            sendEatingSoon = { error("failed carbs must not request target") }
        )
        val command = ActionCommand(
            id = "manual-carb-command",
            type = "carbs",
            params = mapOf("carbsGrams" to "20.0", "reason" to "overview_cob"),
            safetySnapshot = SafetySnapshot(
                killSwitch = false,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0
            ),
            idempotencyKey = "manual:meal-energy-failure"
        )

        val delivered = submission.submit(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120),
            mealEnergyKcal = 540.0,
            eatingSoon = true
        )

        assertThat(delivered.carbs).isEqualTo(MealDeliveryStatus.UNKNOWN)
        assertThat(submitCalls).isEqualTo(1)
        assertThat(dao.pendingIntents).isEmpty()

        energyProfileRepository.refreshPendingSelections(listOf(canonicalMeal(command)))

        assertThat(storedOverrides).isEmpty()
    }

    private fun canonicalMeal(command: ActionCommand) = TherapyEvent(
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
            canonicalCarbId = 910L,
            canonicalCarbRevision = "revision-910"
        )
    )

    private class RecordingEnergyProfileDao : EnergyProfileDao {
        val pendingIntents = linkedMapOf<String, PendingMealProfileIntentEntity>()
        private val mealOverrides = linkedMapOf<String, MealProfileOverrideEntity>()

        override fun observeEnabledEvents(): Flow<List<PlannedActivityEventEntity>> = emptyFlow()

        override suspend fun allEvents(): List<PlannedActivityEventEntity> = emptyList()

        override fun observeAllEvents(): Flow<List<PlannedActivityEventEntity>> = emptyFlow()

        override suspend fun enabledEvents(): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun enabledEventsLimited(limit: Int): List<PlannedActivityEventEntity> =
            enabledEvents().take(limit)

        override suspend fun alertCauseEnabledCandidatesPage(
            afterLocalStartIso: String?,
            afterEventId: String?,
            limit: Int
        ): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun upsertEvent(event: PlannedActivityEventEntity) = Unit

        override suspend fun deleteEvent(eventId: String): Int = 0

        override suspend fun deleteAllEvents(): Int = 0

        override suspend fun insertEvents(events: List<PlannedActivityEventEntity>) = Unit

        override suspend fun matchingMealOverrideForIdentity(
            canonicalTherapyIdentity: String,
            expectedTherapyRevisionHash: String
        ): MealProfileOverrideEntity? = mealOverrides[canonicalTherapyIdentity]
            ?.takeIf { it.therapyRevisionHash == expectedTherapyRevisionHash }

        override suspend fun upsertMealOverride(override: MealProfileOverrideEntity) {
            mealOverrides[override.canonicalTherapyIdentity] = override
        }

        override suspend fun deleteMealOverride(canonicalTherapyIdentity: String): Int =
            if (mealOverrides.remove(canonicalTherapyIdentity) != null) 1 else 0

        override suspend fun deleteMealOverrideWithDifferentRevision(
            canonicalTherapyIdentity: String,
            expectedTherapyRevisionHash: String
        ): Int {
            val override = mealOverrides[canonicalTherapyIdentity]
                ?.takeIf { it.therapyRevisionHash != expectedTherapyRevisionHash }
                ?: return 0
            mealOverrides.remove(override.canonicalTherapyIdentity)
            return 1
        }

        override suspend fun upsertPendingMealProfileIntent(intent: PendingMealProfileIntentEntity) {
            pendingIntents[intent.idempotencyKey] = intent
        }

        override suspend fun activePendingMealProfileIntents(
            nowMs: Long
        ): List<PendingMealProfileIntentEntity> = pendingIntents.values.filter { it.expiresAtMs > nowMs }

        override suspend fun pendingMealProfileIntent(
            idempotencyKey: String
        ): PendingMealProfileIntentEntity? = pendingIntents[idempotencyKey]

        override suspend fun deletePendingMealProfileIntent(idempotencyKey: String): Int =
            if (pendingIntents.remove(idempotencyKey) != null) 1 else 0

        override suspend fun deleteExpiredPendingMealProfileIntents(nowMs: Long): Int {
            val expired = pendingIntents.values.filter { it.expiresAtMs <= nowMs }
            expired.forEach { pendingIntents.remove(it.idempotencyKey) }
            return expired.size
        }

        override suspend fun promotePendingMealProfileIntent(
            expectedIntent: PendingMealProfileIntentEntity,
            override: MealProfileOverrideEntity,
            nowMs: Long
        ): Boolean {
            val current = pendingIntents[expectedIntent.idempotencyKey]
            if (current != expectedIntent || current.expiresAtMs <= nowMs) return false
            mealOverrides[override.canonicalTherapyIdentity] = override
            pendingIntents.remove(expectedIntent.idempotencyKey)
            return true
        }

        override fun observeLatestSnapshot(): Flow<EnergyProfileSnapshotEntity?> = emptyFlow()

        override suspend fun snapshot(snapshotId: String): EnergyProfileSnapshotEntity? = null

        override suspend fun upsertSnapshot(snapshot: EnergyProfileSnapshotEntity) = Unit

        override suspend fun trimSnapshots(): Int = 0
    }
}
