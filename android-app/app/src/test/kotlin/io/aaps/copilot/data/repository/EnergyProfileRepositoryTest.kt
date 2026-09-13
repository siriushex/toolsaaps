package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.EnergyProfileDao
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.PendingMealProfileIntentEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult
import io.aaps.copilot.domain.profile.ScheduleValidation
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EnergyProfileRepositoryTest {

    @Test
    fun continuousSixHourCgmUsesSettledGlucoseInsteadOfLastSampleAsAbsorptionDuration() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val mealTs = LocalDateTime.of(2026, 8, 14, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val mealTimestamps = (0L..6L).map { day ->
            mealTs - day * 24L * 60L * 60L * 1000L
        }
        val glucoseCurve = listOf(
            -30L to 5.5,
            0L to 5.6,
            30L to 7.0,
            60L to 7.5,
            90L to 6.5,
            120L to 5.9,
            135L to 5.7,
            150L to 5.6,
            180L to 5.5,
            240L to 5.5,
            300L to 5.6,
            360L to 5.5
        )
        val repository = inferenceRepository(
            dao = dao,
            mealTs = mealTs,
            additionalMealTimestamps = mealTimestamps.drop(1),
            glucose = mealTimestamps.flatMap { timestamp ->
                glucoseCurve.map { (offsetMinutes, mmol) ->
                    glucose(timestamp + offsetMinutes * 60_000L, mmol)
                }
            }
        )

        val result = repository.refreshInference(inferenceNow(), ZoneOffset.UTC)

        val snapshot = (result as EnergyProfileInferenceRefreshResult.Stored).snapshot
        assertThat(snapshot.foodDurationMinutes).isEqualTo(120)
        assertThat(snapshot.foodProfile).isEqualTo(MealAbsorptionProfile.MIXED.name)
        assertThat(snapshot.foodDurationMinutes).isLessThan(360)
    }

    @Test
    fun mealWithoutSettledGlucoseSignalIsExcludedInsteadOfInventingLongDuration() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val mealTs = LocalDateTime.of(2026, 8, 14, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val repository = inferenceRepository(
            dao = dao,
            mealTs = mealTs,
            glucose = listOf(
                glucose(mealTs - 30 * 60_000L, 5.5),
                glucose(mealTs, 5.6),
                glucose(mealTs + 30 * 60_000L, 7.0),
                glucose(mealTs + 60 * 60_000L, 7.8),
                glucose(mealTs + 120 * 60_000L, 7.7),
                glucose(mealTs + 180 * 60_000L, 7.5),
                glucose(mealTs + 240 * 60_000L, 7.4),
                glucose(mealTs + 300 * 60_000L, 7.4),
                glucose(mealTs + 360 * 60_000L, 7.3)
            )
        )

        val result = repository.refreshInference(inferenceNow(), ZoneOffset.UTC)

        val snapshot = (result as EnergyProfileInferenceRefreshResult.Stored).snapshot
        assertThat(snapshot.foodDurationMinutes).isNull()
        assertThat(snapshot.foodProfile).isNull()
        assertThat(snapshot.qualityDays).isEqualTo(0)
    }

    @Test
    fun sameCompletedLocalDayAndSourceHashDoesNotInsertTwice() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val now = LocalDateTime.of(2026, 8, 15, 3, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val mealTs = LocalDateTime.of(2026, 8, 14, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val repository = EnergyProfileRepository(
            energyProfileDao = dao,
            inferenceDataSource = EnergyProfileInferenceDataSource(
                loadSettings = { EnergyProfileSettings() },
                loadTherapy = { _, _ -> listOf(TherapyEventEntity("meal-1", mealTs, "carbs", "{}")) },
                loadGlucose = { _, _ -> listOf(
                    GlucoseSampleEntity(timestamp = mealTs + 30 * 60_000L, mmol = 6.0, source = "aaps_broadcast", quality = "OK"),
                    GlucoseSampleEntity(timestamp = mealTs + 90 * 60_000L, mmol = 7.0, source = "aaps_broadcast", quality = "OK"),
                    GlucoseSampleEntity(timestamp = mealTs + 150 * 60_000L, mmol = 6.5, source = "aaps_broadcast", quality = "OK")
                ) },
                loadTelemetry = { _, _ -> emptyList<TelemetrySampleEntity>() },
                decodeTherapy = {
                    TherapyEvent(
                        ts = mealTs,
                        type = "carbs",
                        payload = mapOf(
                            "aapsCarbAmount" to "20",
                            "aapsCarbIsValid" to "true",
                            "aapsCarbClassification" to "AAPS_REAL",
                            "aapsCarbSynthetic" to "false",
                            "aapsCarbSuperseded" to "false"
                        )
                    )
                }
            )
        )

        val first = repository.refreshInference(now, ZoneOffset.UTC)
        val second = repository.refreshInference(now + 60_000L, ZoneOffset.UTC)

        assertThat(first).isInstanceOf(EnergyProfileInferenceRefreshResult.Stored::class.java)
        assertThat(second).isInstanceOf(EnergyProfileInferenceRefreshResult.Reused::class.java)
        assertThat(dao.snapshots).hasSize(1)
        Unit
    }

    private fun inferenceRepository(
        dao: RecordingEnergyProfileDao,
        mealTs: Long,
        glucose: List<GlucoseSampleEntity>,
        additionalMealTimestamps: List<Long> = emptyList()
    ): EnergyProfileRepository = EnergyProfileRepository(
        energyProfileDao = dao,
        inferenceDataSource = EnergyProfileInferenceDataSource(
            loadSettings = { EnergyProfileSettings() },
            loadTherapy = {
                _, _ -> (listOf(mealTs) + additionalMealTimestamps).mapIndexed { index, timestamp ->
                    TherapyEventEntity(
                        "meal-${index + 1}",
                        timestamp,
                        "carbs",
                        """{
                            "aapsCarbAmount":"20",
                            "aapsCarbIsValid":"true",
                            "aapsCarbClassification":"AAPS_REAL",
                            "aapsCarbSynthetic":"false",
                            "aapsCarbSuperseded":"false"
                        }""".trimIndent()
                    )
                }
            },
            loadGlucose = { _, _ -> glucose },
            loadTelemetry = { _, _ -> emptyList<TelemetrySampleEntity>() },
            decodeTherapy = { row ->
                TherapyEvent(
                    ts = row.timestamp,
                    type = "carbs",
                    payload = mapOf(
                        "aapsCarbAmount" to "20",
                        "aapsCarbIsValid" to "true",
                        "aapsCarbClassification" to "AAPS_REAL",
                        "aapsCarbSynthetic" to "false",
                        "aapsCarbSuperseded" to "false"
                    )
                )
            }
        )
    )

    private fun glucose(timestamp: Long, mmol: Double): GlucoseSampleEntity = GlucoseSampleEntity(
        timestamp = timestamp,
        mmol = mmol,
        source = "aaps_broadcast",
        quality = "OK"
    )

    private fun inferenceNow(): Long =
        LocalDateTime.of(2026, 8, 15, 3, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun savePlannedActivitySchedulePersistsTypedEventsWithTheirRevisionInOneReplace() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val repository = EnergyProfileRepository(dao)
        val event = plannedActivityEvent(revision = 7L)

        val result = repository.savePlannedActivitySchedule(listOf(event))

        assertThat(result).isEqualTo(PlannedActivityScheduleSaveResult.Saved)
        assertThat(dao.replacedEventBatches).containsExactly(
            listOf(
                PlannedActivityEventEntity(
                    eventId = "walk",
                    enabled = true,
                    title = "Morning walk",
                    activityType = "WALKING",
                    intensity = "LIGHT",
                    localStartIso = "2026-08-03T10:00",
                    durationMinutes = 60,
                    timezoneId = "Asia/Tbilisi",
                    recurrenceDaysMask = 1,
                    recurrenceEndEpochDay = null,
                    revision = 7L,
                    createdAtMs = 100L,
                    updatedAtMs = 200L
                )
            )
        )
        assertThat(dao.legacyScheduleWrites).isEqualTo(0)
        Unit
    }

    @Test
    fun savePlannedActivityScheduleRejectsEnabledOverlapWithoutReplacingPersistedEvents() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val repository = EnergyProfileRepository(dao)
        val first = plannedActivityEvent(eventId = "first")
        val overlapping = plannedActivityEvent(
            eventId = "second",
            localStart = LocalDateTime.of(2026, 8, 3, 10, 30)
        )

        val result = repository.savePlannedActivitySchedule(listOf(first, overlapping))

        assertThat(result).isInstanceOf(PlannedActivityScheduleSaveResult.Invalid::class.java)
        assertThat((result as PlannedActivityScheduleSaveResult.Invalid).validation)
            .isInstanceOf(ScheduleValidation.Overlap::class.java)
        assertThat(dao.replacedEventBatches).isEmpty()
        assertThat(dao.legacyScheduleWrites).isEqualTo(0)
        Unit
    }

    @Test
    fun submittedCarbActionStagesSelectionWithoutDirectCanonicalPromotion() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val repository = EnergyProfileRepository(dao, clock = { 123L })
        val command = copilotCarbCommand("manual:meal-direct-promotion")

        repository.stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.FAST, 45),
        )

        assertThat(dao.mealOverrides).isEmpty()
        assertThat(dao.pendingIntents).containsExactly(
            command.idempotencyKey,
            PendingMealProfileIntentEntity(
                idempotencyKey = command.idempotencyKey,
                copilotNote = "copilot:${command.idempotencyKey}",
                profile = "FAST",
                durationMinutes = 45,
                expectedCarbsGrams = 20.0,
                submittedAtMs = 123L,
                expiresAtMs = 123L + 24L * 60L * 60L * 1000L
            )
        )
        Unit
    }

    @Test
    fun changedTherapyRevisionInvalidatesMealOverride() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val repository = EnergyProfileRepository(dao)
        repository.stageSelectionAfterSubmittedCarbAction(
            command = copilotCarbCommand("manual:meal-revision"),
            selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120),
        )
        repository.refreshPendingSelections(
            listOf(trustedImportedEvent("902", "revision-a", copilotCarbCommand("manual:meal-revision")))
        )

        val resolved = repository.resolveMealOverride(
            therapyIdentity = "902",
            therapyRevision = "revision-b"
        )

        assertThat(resolved).isNull()
        assertThat(dao.mealOverrides).isEmpty()
        Unit
    }

    @Test
    fun mealAbsorptionReadDoesNotDeleteInvalidOverrideBeforeAcceptedMaintenance() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val repository = EnergyProfileRepository(dao)
        dao.mealOverrides["invalid-profile"] = MealProfileOverrideEntity(
            canonicalTherapyIdentity = "invalid-profile",
            therapyRevisionHash = "revision-a",
            profile = "UNKNOWN_PROFILE",
            durationMinutes = 120,
            source = "test",
            revision = 1L,
            updatedAtMs = 100L
        )

        val resolved = repository.resolveMealOverride(
            therapyIdentity = "invalid-profile",
            therapyRevision = "revision-a"
        )

        assertThat(resolved).isNull()
        assertThat(dao.mealOverrides).containsKey("invalid-profile")
    }

    @Test
    fun canonicalReferenceForCopilotAction_matchesTrustedImportedRecordByNote() {
        val command = copilotCarbCommand("manual:meal-1")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())

        val result = repository.canonicalReferenceForCopilotAction(
            command = command,
            therapy = listOf(trustedImportedEvent("901", "revision-a", command))
        )

        assertThat(result).isEqualTo(
            CanonicalCarbActionResult.Canonical("901", "revision-a")
        )
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsMultipleTrustedCandidates() {
        val command = copilotCarbCommand("manual:meal-2")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())

        val result = repository.canonicalReferenceForCopilotAction(
            command = command,
            therapy = listOf(
                trustedImportedEvent("902", "revision-a", command),
                trustedImportedEvent("903", "revision-a", command)
            )
        )

        assertThat(result).isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsWrongNote() {
        val command = copilotCarbCommand("manual:meal-2-wrong-note")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val wrongNote = trustedImportedEvent("902", "revision-a", command).copy(
            payload = mapOf("notes" to "copilot:another-action")
        )

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(wrongNote)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_acceptsExactActionTokenFromNoteAlias() {
        val command = copilotCarbCommand("manual:meal-note-alias")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val canonical = trustedImportedEvent("903", "revision-a", command)
            .withOnlyNoteAlias("copilot:${command.idempotencyKey}")

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(canonical)))
            .isEqualTo(CanonicalCarbActionResult.Canonical("903", "revision-a"))
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsConflictingNoteAliases() {
        val command = copilotCarbCommand("manual:meal-note-conflict")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val trusted = trustedImportedEvent("904", "revision-a", command)
        val conflict = trusted.copy(
            payload = trusted.payload + mapOf(
                "notes" to "copilot:${command.idempotencyKey}",
                "note" to "copilot:other-action"
            )
        )

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(conflict)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsCopiedTokenWithWrongCanonicalCarbs() {
        val command = copilotCarbCommand("manual:meal-wrong-carbs")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val copiedToken = trustedImportedEvent("905", "revision-a", command, canonicalCarbsGrams = 24.0)

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(copiedToken)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_acceptsCanonicalCarbsAtPositiveToleranceBoundary() {
        val command = copilotCarbCommand("manual:meal-carbs-positive-boundary")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val canonical = trustedImportedEvent("905", "revision-a", command, canonicalCarbsGrams = 20.1)

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(canonical)))
            .isEqualTo(CanonicalCarbActionResult.Canonical("905", "revision-a"))
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_acceptsCanonicalCarbsJustInsideToleranceBoundary() {
        val command = copilotCarbCommand("manual:meal-carbs-inside-boundary")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val canonical = trustedImportedEvent(
            "905",
            "revision-a",
            command,
            canonicalCarbsGrams = 20.0999999999
        )

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(canonical)))
            .isEqualTo(CanonicalCarbActionResult.Canonical("905", "revision-a"))
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsCanonicalCarbsJustBeyondToleranceBoundary() {
        val command = copilotCarbCommand("manual:meal-carbs-beyond-boundary")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val canonical = trustedImportedEvent(
            "905",
            "revision-a",
            command,
            canonicalCarbsGrams = 20.1000000001
        )

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(canonical)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsSameTokenAmbiguityBeforeTrustFiltering() {
        val command = copilotCarbCommand("manual:meal-ambiguous-token")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val trusted = trustedImportedEvent("906", "revision-a", command)
        val malformed = TherapyEvent(
            ts = trusted.ts,
            type = "carbs",
            payload = trusted.payload
        )

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(trusted, malformed)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsDuplicateCanonicalRecord() {
        val command = copilotCarbCommand("manual:meal-2-duplicate")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())
        val duplicate = trustedImportedEvent("902", "revision-a", command)

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(duplicate, duplicate)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun canonicalReferenceForCopilotAction_rejectsConflictedAndMissingRecords() {
        val command = copilotCarbCommand("manual:meal-3")
        val repository = EnergyProfileRepository(RecordingEnergyProfileDao())

        val conflicted = trustedImportedEvent("904", "revision-a", command).copy(
            componentTrust = TherapyEventComponentTrust(
                canonicalCarbId = 904L,
                canonicalCarbRevision = "revision-a",
                canonicalReferenceConflict = true
            )
        )
        val missing = TherapyEvent(
            ts = 1_000L,
            type = "carbs",
            payload = mapOf("notes" to "copilot:${command.idempotencyKey}")
        )

        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(conflicted)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        assertThat(repository.canonicalReferenceForCopilotAction(command, listOf(missing)))
            .isEqualTo(CanonicalCarbActionResult.NoCanonicalReference)
        Unit
    }

    @Test
    fun runtimeRefreshPersistsStagedSelectionOnlyAfterTrustedCanonicalConfirmation() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val repository = EnergyProfileRepository(dao, clock = { 123L })
        val command = copilotCarbCommand("manual:meal-4")
        repository.stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 270)
        )

        repository.refreshPendingSelections(listOf(TherapyEvent(1_000L, "carbs", emptyMap())))
        assertThat(dao.mealOverrides).isEmpty()

        repository.refreshPendingSelections(
            listOf(trustedImportedEvent("905", "revision-a", command))
        )

        assertThat(dao.mealOverrides).containsKey("905")
        Unit
    }

    @Test
    fun reconciledManualMealEnergyPersistsOnlyForTrustedCanonicalMeal() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val storedEnergy = mutableListOf<MealEnergyOverrideEntity>()
        val energyRepository = MealEnergyOverrideRepository(
            source = MealEnergyOverrideDataSource(
                therapyById = { error("canonical reconciliation must use its verified reference") },
                save = storedEnergy::add
            ),
            gson = Gson(),
            clock = { 123L }
        )
        val repository = EnergyProfileRepository(
            energyProfileDao = dao,
            clock = { 123L },
            mealEnergyOverrideRepository = energyRepository
        )
        val command = copilotCarbCommand("manual:meal-energy")

        repository.stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120),
            manualMealEnergyKcal = 540.0
        )
        repository.refreshPendingSelections(emptyList())

        assertThat(storedEnergy).isEmpty()
        assertThat(dao.pendingIntents.getValue(command.idempotencyKey).manualMealEnergyKcal)
            .isEqualTo(540.0)

        repository.refreshPendingSelections(
            listOf(trustedImportedEvent("910", "revision-910", command))
        )

        assertThat(storedEnergy).containsExactly(
            MealEnergyOverrideEntity(
                canonicalTherapyIdentity = "910",
                therapyRevisionHash = "revision-910",
                caloriesKcal = 540.0,
                updatedAtMs = 123L
            )
        )
        assertThat(dao.pendingIntents).isEmpty()
        Unit
    }

    @Test
    fun submittedActionPersistsDurablePendingIntentWithStableMatchFields() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val command = copilotCarbCommand("manual:meal-pending")
        EnergyProfileRepository(dao, clock = { 123L }).stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)
        )

        assertThat(dao.pendingIntents).containsExactly(
            command.idempotencyKey,
            PendingMealProfileIntentEntity(
                idempotencyKey = command.idempotencyKey,
                copilotNote = "copilot:${command.idempotencyKey}",
                profile = "MIXED",
                durationMinutes = 120,
                expectedCarbsGrams = 20.0,
                submittedAtMs = 123L,
                expiresAtMs = 123L + 24L * 60L * 60L * 1000L
            )
        )
        Unit
    }

    @Test
    fun runtimeRefreshPersistsSelectionStagedBeforeRepositoryRecreation() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val command = copilotCarbCommand("manual:meal-5")
        EnergyProfileRepository(dao).stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)
        )

        EnergyProfileRepository(dao).refreshPendingSelections(
            listOf(trustedImportedEvent("906", "revision-a", command))
        )

        assertThat(dao.mealOverrides).containsKey("906")
        assertThat(dao.pendingIntents).isEmpty()
        Unit
    }

    @Test
    fun conflictingCandidateRetainsPendingIntentUntilBoundedExpiry() = runBlocking {
        val dao = RecordingEnergyProfileDao()
        val command = copilotCarbCommand("manual:meal-expiry")
        EnergyProfileRepository(dao, clock = { 100L }).stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.FAST, 45)
        )

        EnergyProfileRepository(dao, clock = { 200L }).refreshPendingSelections(
            listOf(
                trustedImportedEvent("907", "revision-a", command),
                trustedImportedEvent("908", "revision-a", command)
            )
        )

        assertThat(dao.mealOverrides).isEmpty()
        assertThat(dao.pendingIntents).containsKey(command.idempotencyKey)

        EnergyProfileRepository(dao, clock = { 100L + 24L * 60L * 60L * 1000L })
            .refreshPendingSelections(emptyList())

        assertThat(dao.pendingIntents).isEmpty()
        Unit
    }

    @Test
    fun concurrentRuntimeRefreshPromotesDurableIntentExactlyOnce() = runBlocking {
        val dao = RecordingEnergyProfileDao(blockLegacyUpsertsUntilConcurrent = true)
        val repository = EnergyProfileRepository(dao, clock = { 123L })
        val command = copilotCarbCommand("manual:meal-concurrent")
        repository.stageSelectionAfterSubmittedCarbAction(
            command = command,
            selection = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 270)
        )

        coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    repository.refreshPendingSelections(listOf(trustedImportedEvent("909", "revision-a", command)))
                },
                async(Dispatchers.Default) {
                    repository.refreshPendingSelections(listOf(trustedImportedEvent("909", "revision-a", command)))
                }
            ).awaitAll()
        }

        assertThat(dao.successfulPromotions).isEqualTo(1)
        assertThat(dao.mealOverrides).containsKey("909")
        assertThat(dao.pendingIntents).isEmpty()
        Unit
    }

    private fun plannedActivityEvent(
        eventId: String = "walk",
        localStart: LocalDateTime = LocalDateTime.of(2026, 8, 3, 10, 0),
        revision: Long = 1L,
        recurrenceDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY)
    ) = PlannedActivitySchedule(
        eventId = eventId,
        enabled = true,
        title = "Morning walk",
        type = PlannedActivityType.WALKING,
        intensity = PlannedActivityIntensity.LIGHT,
        localStart = localStart,
        durationMinutes = 60,
        timezoneId = "Asia/Tbilisi",
        recurrenceDays = recurrenceDays,
        recurrenceEndEpochDay = null,
        revision = revision,
        createdAtMs = 100L,
        updatedAtMs = 200L
    )

    private fun copilotCarbCommand(idempotencyKey: String): ActionCommand = ActionCommand(
        id = "command-$idempotencyKey",
        type = "carbs",
        params = mapOf("carbsGrams" to "20.0"),
        safetySnapshot = SafetySnapshot(false, true, null, 0),
        idempotencyKey = idempotencyKey
    )

    private fun trustedImportedEvent(
        identity: String,
        revision: String,
        command: ActionCommand,
        canonicalCarbsGrams: Double = 20.0
    ): TherapyEvent = TherapyEvent(
        ts = 1_000L,
        type = "carbs",
        payload = mapOf(
            "notes" to "copilot:${command.idempotencyKey}",
            "aapsCarbAmount" to canonicalCarbsGrams.toString(),
            "aapsCarbIsValid" to "true",
            "aapsCarbClassification" to "AAPS_REAL",
            "aapsCarbSynthetic" to "false",
            "aapsCarbSuperseded" to "false"
        ),
        componentTrust = TherapyEventComponentTrust(
            canonicalCarbId = identity.toLong(),
            canonicalCarbRevision = revision
        )
    )

    private fun TherapyEvent.withOnlyNoteAlias(note: String): TherapyEvent = copy(
        payload = payload.toMutableMap().apply {
            remove("notes")
            put("note", note)
        }
    )

    private class RecordingEnergyProfileDao(
        private val blockLegacyUpsertsUntilConcurrent: Boolean = false
    ) : EnergyProfileDao {
        val mealOverrides = linkedMapOf<String, MealProfileOverrideEntity>()
        val pendingIntents = linkedMapOf<String, PendingMealProfileIntentEntity>()
        val snapshots = linkedMapOf<String, EnergyProfileSnapshotEntity>()
        val replacedEventBatches = mutableListOf<List<PlannedActivityEventEntity>>()
        var legacyScheduleWrites = 0
        var successfulPromotions = 0
        private var legacyUpsertArrivals = 0
        private val legacyUpsertGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        override fun observeEnabledEvents(): Flow<List<PlannedActivityEventEntity>> = emptyFlow()

        override fun observeAllEvents(): Flow<List<PlannedActivityEventEntity>> = emptyFlow()

        override suspend fun allEvents(): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun enabledEvents(): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun enabledEventsLimited(limit: Int): List<PlannedActivityEventEntity> =
            enabledEvents().take(limit)

        override suspend fun alertCauseEnabledCandidatesPage(
            afterLocalStartIso: String?,
            afterEventId: String?,
            limit: Int
        ): List<PlannedActivityEventEntity> = emptyList()

        override suspend fun upsertEvent(event: PlannedActivityEventEntity) {
            legacyScheduleWrites += 1
        }

        override suspend fun deleteEvent(eventId: String): Int {
            legacyScheduleWrites += 1
            return 0
        }

        override suspend fun deleteAllEvents(): Int {
            legacyScheduleWrites += 1
            return 0
        }

        override suspend fun insertEvents(events: List<PlannedActivityEventEntity>) {
            legacyScheduleWrites += 1
        }

        override suspend fun replaceEvents(events: List<PlannedActivityEventEntity>) {
            replacedEventBatches += events
        }

        override suspend fun matchingMealOverrideForIdentity(
            canonicalTherapyIdentity: String,
            expectedTherapyRevisionHash: String
        ): MealProfileOverrideEntity? = mealOverrides[canonicalTherapyIdentity]
            ?.takeIf { it.therapyRevisionHash == expectedTherapyRevisionHash }

        override suspend fun upsertMealOverride(override: MealProfileOverrideEntity) {
            if (blockLegacyUpsertsUntilConcurrent) {
                val release = synchronized(this) { ++legacyUpsertArrivals == 2 }
                if (release) legacyUpsertGate.complete(Unit)
                legacyUpsertGate.await()
            }
            synchronized(this) {
                mealOverrides[override.canonicalTherapyIdentity] = override
            }
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
            synchronized(this) {
                pendingIntents[intent.idempotencyKey] = intent
            }
        }

        override suspend fun activePendingMealProfileIntents(nowMs: Long): List<PendingMealProfileIntentEntity> =
            synchronized(this) {
                pendingIntents.values.filter { it.expiresAtMs > nowMs }
            }

        override suspend fun deleteExpiredPendingMealProfileIntents(nowMs: Long): Int = synchronized(this) {
            val expired = pendingIntents.values.filter { it.expiresAtMs <= nowMs }
            expired.forEach { pendingIntents.remove(it.idempotencyKey) }
            expired.size
        }

        override suspend fun pendingMealProfileIntent(
            idempotencyKey: String
        ): PendingMealProfileIntentEntity? = synchronized(this) {
            pendingIntents[idempotencyKey]
        }

        override suspend fun deletePendingMealProfileIntent(idempotencyKey: String): Int = synchronized(this) {
            if (pendingIntents.remove(idempotencyKey) != null) 1 else 0
        }

        override suspend fun promotePendingMealProfileIntent(
            expectedIntent: PendingMealProfileIntentEntity,
            override: MealProfileOverrideEntity,
            nowMs: Long
        ): Boolean = synchronized(this) {
            val current = pendingIntents[expectedIntent.idempotencyKey]
            if (current != expectedIntent || current.expiresAtMs <= nowMs) return@synchronized false
            mealOverrides[override.canonicalTherapyIdentity] = override
            pendingIntents.remove(expectedIntent.idempotencyKey)
            successfulPromotions += 1
            true
        }

        override fun observeLatestSnapshot(): Flow<EnergyProfileSnapshotEntity?> = emptyFlow()

        override suspend fun snapshot(snapshotId: String): EnergyProfileSnapshotEntity? = snapshots[snapshotId]

        override suspend fun upsertSnapshot(snapshot: EnergyProfileSnapshotEntity) {
            snapshots[snapshot.snapshotId] = snapshot
        }

        override suspend fun insertSnapshotAndTrim(snapshot: EnergyProfileSnapshotEntity) {
            upsertSnapshot(snapshot)
        }

        override suspend fun trimSnapshots(): Int = 0
    }
}
