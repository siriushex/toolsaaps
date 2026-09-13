package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.data.repository.TargetManagerLiveStatusCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewAcceptedTupleExpiryTest {

    @Test
    fun cobExpiresOnceWithoutForecastOrDatabaseEmissions() = runTest {
        val baseTs = 1_800_000_000_000L
        val emissions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            acceptedSensitivityTupleUiClock(
                generationTimestamps = MutableStateFlow(null),
                cobEvidence = MutableStateFlow(baseTs to 300_000L),
                clock = { baseTs + testScheduler.currentTime }
            ).toList(emissions)
        }
        advanceTimeBy(300_000L)
        runCurrent()
        assertThat(emissions).containsExactly(baseTs)
        advanceTimeBy(1L)
        runCurrent()
        assertThat(emissions).containsExactly(baseTs, baseTs + 300_001L).inOrder()
        advanceTimeBy(900_000L)
        runCurrent()
        assertThat(emissions).hasSize(2)
    }

    @Test
    fun emitsAtInclusiveBoundaryPlusOneWithoutAnyInputEmission() = runTest {
        val generationTs = 1_800_000_000_000L
        val generations = MutableStateFlow<Long?>(generationTs)
        val emissions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            acceptedSensitivityTupleUiClock(
                generationTimestamps = generations,
                clock = { generationTs + testScheduler.currentTime }
            ).take(2).toList(emissions)
        }

        runCurrent()
        assertThat(emissions).containsExactly(generationTs)

        advanceTimeBy(AcceptedSensitivityTupleFreshness.MAX_AGE_MS)
        runCurrent()
        assertThat(emissions).containsExactly(generationTs)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(emissions).containsExactly(
            generationTs,
            generationTs + AcceptedSensitivityTupleFreshness.MAX_AGE_MS + 1L
        ).inOrder()
    }

    @Test
    fun replacingAcceptedGenerationCancelsThePreviousExpiry() = runTest {
        val baseTs = 1_800_000_000_000L
        val generations = MutableStateFlow<Long?>(baseTs)
        val emissions = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            acceptedSensitivityTupleUiClock(
                generationTimestamps = generations,
                clock = { baseTs + testScheduler.currentTime }
            ).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(5L * 60_000L)
        generations.value = baseTs + 5L * 60_000L
        runCurrent()

        advanceTimeBy(10L * 60_000L + 1L)
        runCurrent()
        assertThat(emissions).containsExactly(baseTs, baseTs + 5L * 60_000L).inOrder()

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertThat(emissions).containsExactly(
            baseTs,
            baseTs + 5L * 60_000L,
            baseTs + 20L * 60_000L + 1L
        ).inOrder()
        job.cancel()
    }

    @Test
    fun expiryEmissionAdvancesToTheBoundaryWhenWallClockDoesNotAdvance() = runTest {
        val generationTs = 1_800_000_000_000L
        val emissions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            acceptedSensitivityTupleUiClock(
                generationTimestamps = MutableStateFlow(generationTs),
                clock = { generationTs }
            ).take(2).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(AcceptedSensitivityTupleFreshness.MAX_AGE_MS + 1L)
        runCurrent()

        assertThat(emissions).containsExactly(
            generationTs,
            generationTs + AcceptedSensitivityTupleFreshness.MAX_AGE_MS + 1L
        ).inOrder()
    }

    @Test
    fun targetManagerStatusExpiresOnceWithoutARecurringPoll() = runTest {
        val statusTs = 1_800_000_000_000L
        val emissions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            acceptedSensitivityTupleUiClock(
                generationTimestamps = MutableStateFlow(null),
                targetManagerStatusTimestamps = MutableStateFlow(statusTs),
                clock = { statusTs + testScheduler.currentTime }
            ).take(2).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(TargetManagerLiveStatusCodec.MAX_AGE_MS)
        runCurrent()
        assertThat(emissions).containsExactly(statusTs)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(emissions).containsExactly(
            statusTs,
            statusTs + TargetManagerLiveStatusCodec.MAX_AGE_MS + 1L
        ).inOrder()
    }
}
