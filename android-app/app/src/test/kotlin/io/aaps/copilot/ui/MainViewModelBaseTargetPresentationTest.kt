package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.target.EffectiveBaseTargetResolver
import io.aaps.copilot.domain.target.EffectiveTargetAdjustment
import io.aaps.copilot.domain.target.EffectiveTargetRuntimeGates
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetManagerMode
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class MainViewModelBaseTargetPresentationTest {

    @Test
    fun rejectedSourceChangeStaleCalculatedUamDoesNotBlockEffectivePresentationTarget() {
        val state = MainUiState().apply {
            uamRuntimeActive = false
            calculatedUamActive = true
        }

        val presentationUamActive = MainViewModel.resolveBaseTargetPresentationUamActiveStatic(state)
        val result = EffectiveBaseTargetResolver().resolve(
            nowTs = NOW,
            zoneId = ZONE,
            schedule = BaseTargetSchedule(
                revision = 7L,
                defaultTargetMmol = 5.6,
                autoEnabled = true,
                intervals = listOf(BaseTargetInterval("morning", 600, 720, 5.6))
            ),
            targetManagerMode = TargetManagerMode.ACTIVE,
            hardMinTargetMmol = 4.0,
            hardMaxTargetMmol = 10.0,
            gates = EffectiveTargetRuntimeGates(
                sensorTrust = SensorTrustState.TRUSTED,
                sensorAgeHours = 120.0,
                lowRiskLatched = false,
                forecast5CiLowMmol = 4.8,
                forecast30CiLowMmol = 4.7,
                safetyIobUnits = 1.0,
                effectiveCobGrams = 0.0,
                uamActive = presentationUamActive
            ),
            adjustments = listOf(
                EffectiveTargetAdjustment(
                    runId = "accepted-run",
                    scheduleRevision = 7L,
                    dayType = CircadianDayType.WEEKDAY,
                    hour = 10,
                    appliedDeltaMmol = -0.2,
                    generatedAt = NOW - 60_000L,
                    validUntil = NOW + 60_000L,
                    state = CircadianAutoState.ACTIVE
                )
            )
        )

        assertThat(presentationUamActive).isFalse()
        assertThat(result.effectiveTargetMmol).isWithin(0.0001).of(5.4)
        assertThat(result.reasonCodes).doesNotContain("uam_active")
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("UTC")
        val NOW: Long = LocalDateTime.of(2026, 8, 13, 10, 30)
            .atZone(ZONE)
            .toInstant()
            .toEpochMilli()
    }
}
