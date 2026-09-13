package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.EnergyProfileInferenceRefreshResult
import io.aaps.copilot.data.repository.PersistedEatingWindowSnapshot
import io.aaps.copilot.domain.eating.EatingWindowAnalysis
import io.aaps.copilot.domain.eating.EatingWindowDualHorizonSnapshot
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

class EatingWindowRefreshScheduleTest {

    private val zone = ZoneOffset.ofHours(4)

    @Test
    fun schedulesSameNightWhenBeforeRefreshTime() {
        val now = ZonedDateTime.of(2026, 8, 2, 2, 0, 0, 0, zone)

        val delay = EatingWindowRefreshSchedule.initialDelayMillis(now)

        assertThat(delay).isEqualTo(75 * 60 * 1000L)
    }

    @Test
    fun schedulesFollowingNightWhenRefreshTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 8, 2, 3, 15, 0, 0, zone)

        val delay = EatingWindowRefreshSchedule.initialDelayMillis(now)

        assertThat(delay).isEqualTo(24 * 60 * 60 * 1000L)
    }

    @Test
    fun dailyMaintenanceRefreshesInferenceAfterExistingSnapshotWithoutSchedulingMoreWork() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val snapshot = PersistedEatingWindowSnapshot(
            localCompletedDate = "2026-08-01",
            generatedAt = 1L,
            sourceFingerprint = "fingerprint",
            snapshot = EatingWindowDualHorizonSnapshot(
                recent = EatingWindowAnalysis(7, emptyList(), 0),
                stable = EatingWindowAnalysis(14, emptyList(), 0)
            )
        )

        val returned = refreshDailyEatingMaintenance(
            refreshEatingWindow = {
                calls += "eating"
                snapshot
            },
            refreshEnergyProfile = {
                calls += "energy"
                EnergyProfileInferenceRefreshResult.Unavailable
            }
        )

        assertThat(returned).isEqualTo(snapshot)
        assertThat(calls).containsExactly("eating", "energy").inOrder()
    }
}
