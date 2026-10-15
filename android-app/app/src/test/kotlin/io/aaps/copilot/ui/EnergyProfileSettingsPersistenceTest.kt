package io.aaps.copilot.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.PhysiologicalSex
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnergyProfileSettingsPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    private val today = LocalDate.of(2026, 10, 11)
    private val adult = EnergyProfileSettings(birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
        heightCm = 175.0, weightKg = 70.0)

    @Test fun profileSurvivesClosingAndReopeningThePreferencesFile() = runTest {
        val file = File(directory.root, "profile.preferences_pb")
        val writerJob = SupervisorJob()
        val writer = AppSettingsStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + writerJob), produceFile = { file }))
        val before = writer.settings.first()
        val expected = adult.copy(foodProfileMode = FoodProfileMode.MANUAL,
            activityProfileMode = ActivityProfileMode.MANUAL, forecastActivityInfluenceEnabled = true,
            calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN, manualCalorieTargetKcal = 2100,
            shareProfileWithAi = false)
        try {
            persistEnergyProfileSettingsUpdate({ writer.settings.first() }, { expected },
                { error("No sensitivity change requested") }, { writer.update(it) }, { today })
            assertEquals(expected, writer.settings.first().energyProfile)
        } finally { writerJob.cancelAndJoin() }
        val readerJob = SupervisorJob()
        try {
            val reader = AppSettingsStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(coroutineContext + readerJob), produceFile = { file }))
            val reloaded = reader.settings.first()
            assertEquals(expected, reloaded.energyProfile)
            assertEquals(before.sensitivitySettingsRevision, reloaded.sensitivitySettingsRevision)
            assertEquals(before.killSwitch, reloaded.killSwitch)
        } finally { readerJob.cancelAndJoin() }
    }

    @Test fun profileEditMergesIntoTheLatestTransactionNotTheReadSnapshot() = runTest {
        var current = settings(backgroundScope)
        val concurrent = adult.copy(foodProfileMode = FoodProfileMode.MANUAL,
            forecastActivityInfluenceEnabled = true, shareProfileWithAi = false, manualCalorieTargetKcal = 2100)
        val result = persistEnergyProfileSettingsUpdate({ current }, { it.copy(heightCm = 176.5, weightKg = 71.2) },
            { error("No sensitivity change") }, { update ->
                current = current.copy(energyProfile = concurrent)
                current = update(current)
            }, { today })
        assertEquals(concurrent.copy(heightCm = 176.5, weightKg = 71.2), result.settings)
        assertEquals(result.settings, current.energyProfile)
    }

    @Test fun invalidProfileNeverStartsSensitivityOrStorageWrites() = runTest {
        var writes = 0
        val failure = runCatching {
            persistEnergyProfileSettingsUpdate({ settings(backgroundScope) },
                { it.copy(heightCm = 999.0, physiologicalSex = PhysiologicalSex.FEMALE) },
                { writes++ }, { writes++ }, { today })
        }.exceptionOrNull()
        assertTrue(failure is EnergyProfileSettingsRejected)
        assertEquals(0, writes)
    }

    @Test fun validationIsRepeatedAgainstConcurrentSettings() = runTest {
        var current = settings(backgroundScope)
        val failure = runCatching {
            persistEnergyProfileSettingsUpdate({ current }, { it.copy(weightKg = 71.0) }, {}, { update ->
                current = current.copy(energyProfile = current.energyProfile.copy(manualCalorieTargetKcal = 499))
                current = update(current)
            }, { today })
        }.exceptionOrNull()
        assertTrue(failure is EnergyProfileSettingsRejected)
        assertEquals(70.0, current.energyProfile.weightKg!!, 0.0)
    }

    @Test fun sensitivityChangeMustFinishBeforeProfilePersistence() = runTest {
        var current = settings(backgroundScope)
        val events = mutableListOf<String>()
        persistEnergyProfileSettingsUpdate({ current }, { it.copy(physiologicalSex = PhysiologicalSex.FEMALE, weightKg = 71.0) },
            { sex -> events += "sensitivity"; current = current.copy(energyProfile = current.energyProfile.copy(physiologicalSex = sex)) },
            { update -> events += "profile"; current = update(current) }, { today })
        assertEquals(listOf("sensitivity", "profile"), events)
        assertEquals(71.0, current.energyProfile.weightKg!!, 0.0)
    }

    @Test fun rejectedSensitivityCycleCannotReportSavedOrWriteTheProfile() = runTest {
        var writes = 0
        val failure = IOException("Fixture accepted cycle unavailable")
        val result = runCatching {
            persistEnergyProfileSettingsUpdate({ settings(backgroundScope) },
                { it.copy(physiologicalSex = PhysiologicalSex.FEMALE) }, { throw failure }, { writes++ }, { today })
        }
        assertTrue(result.exceptionOrNull() is EnergyProfileSensitivityChangeRejected)
        assertSame(failure, result.exceptionOrNull()?.cause)
        assertEquals(0, writes)
    }

    @Test fun unappliedSexOrConcurrentSexChangeCannotBypassTheLease() = runTest {
        var current = settings(backgroundScope)
        val failure = runCatching {
            persistEnergyProfileSettingsUpdate({ current }, { it.copy(physiologicalSex = PhysiologicalSex.MALE) },
                {}, { update -> current = update(current) }, { today })
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(adult, current.energyProfile)
    }

    @Test fun storageFailureAndCancellationAreNotSuccessfulSaves() = runTest {
        val current = settings(backgroundScope)
        for (failure in listOf(IOException("Fixture write failed"), CancellationException("Fixture canceled"))) {
            val result = runCatching {
                persistEnergyProfileSettingsUpdate({ current }, { it.copy(weightKg = 71.0) },
                    {}, { throw failure }, { today })
            }
            assertSame(failure, result.exceptionOrNull())
        }
    }

    @Test fun pediatricNormalizationRemainsInThePersistencePath() = runTest {
        var current = settings(backgroundScope).copy(energyProfile = adult.copy(calorieGoalMode = CalorieGoalMode.LOSS))
        val result = persistEnergyProfileSettingsUpdate({ current },
            { it.copy(birthDateEpochDay = LocalDate.of(2012, 1, 1).toEpochDay()) }, {},
            { current = it(current) }, { today })
        assertTrue(result.pediatricGoalReset)
        assertEquals(CalorieGoalMode.OFF, current.energyProfile.calorieGoalMode)
    }

    private suspend fun settings(scope: CoroutineScope): AppSettings = AppSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(directory.root, "defaults.preferences_pb") })
    ).settings.first().copy(energyProfile = adult)
}
