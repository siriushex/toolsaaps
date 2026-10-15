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

    @Test fun missingEnabledProfileRequestsOneImmediateCalculation() = kotlinx.coroutines.runBlocking {
        var enqueued = 0
        assertThat(ensureInitialEnergyProfileRefresh(true, false, { false }, { enqueued++ })).isTrue()
        assertThat(enqueued).isEqualTo(1)
        assertThat(ensureInitialEnergyProfileRefresh(true, false, { true }, { enqueued++ })).isFalse()
        assertThat(enqueued).isEqualTo(1)
    }

    @Test fun disabledProfileAndPowerSaveDoNotReadHistoryOrScheduleWork() = kotlinx.coroutines.runBlocking {
        for ((enabled, powerSave) in listOf(false to false, false to true, true to true)) {
            assertThat(ensureInitialEnergyProfileRefresh(enabled, powerSave,
                { error("unexpected snapshot read") }, { error("unexpected work") })).isFalse()
        }
    }

    @Test fun snapshotReadFailureAndCancellationCannotScheduleWork() = kotlinx.coroutines.runBlocking {
        for (failure in listOf(IllegalStateException("read failed"),
            kotlinx.coroutines.CancellationException("cancelled"))) {
            val result = runCatching {
                ensureInitialEnergyProfileRefresh(true, false, { throw failure }, { error("unexpected work") })
            }
            org.junit.Assert.assertSame(failure, result.exceptionOrNull())
        }
    }

    @Test fun enqueueFailureIsNotReportedAsSuccessfulCalculation() = kotlinx.coroutines.runBlocking {
        val failure = IllegalStateException("enqueue failed")
        val result = runCatching { ensureInitialEnergyProfileRefresh(true, false, { false }, { throw failure }) }
        org.junit.Assert.assertSame(failure, result.exceptionOrNull())
    }

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
