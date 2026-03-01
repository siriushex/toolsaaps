package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import org.junit.Test

class ForecastSnapshotResolverTest {

    @Test
    fun resolvesHorizonsFromLatestOriginCycle() {
        val baseTs = 1_800_000_000_000L
        val cycleAOrigin = baseTs
        val cycleBOrigin = baseTs + 60_000L

        val rows = listOf(
            ForecastEntity(
                id = 1,
                timestamp = cycleAOrigin + 5 * 60_000L,
                horizonMinutes = 5,
                valueMmol = 5.1,
                ciLow = 4.8,
                ciHigh = 5.4,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 2,
                timestamp = cycleAOrigin + 30 * 60_000L,
                horizonMinutes = 30,
                valueMmol = 5.6,
                ciLow = 4.9,
                ciHigh = 6.2,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 3,
                timestamp = cycleAOrigin + 60 * 60_000L,
                horizonMinutes = 60,
                valueMmol = 6.2,
                ciLow = 5.0,
                ciHigh = 7.2,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 4,
                timestamp = cycleBOrigin + 5 * 60_000L,
                horizonMinutes = 5,
                valueMmol = 5.3,
                ciLow = 4.9,
                ciHigh = 5.8,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 5,
                timestamp = cycleBOrigin + 30 * 60_000L,
                horizonMinutes = 30,
                valueMmol = 5.9,
                ciLow = 5.0,
                ciHigh = 6.7,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 6,
                timestamp = cycleBOrigin + 60 * 60_000L,
                horizonMinutes = 60,
                valueMmol = 6.5,
                ciLow = 5.3,
                ciHigh = 7.6,
                modelVersion = "m"
            )
        )

        val resolved = ForecastSnapshotResolver.resolveLatestByHorizon(rows)
        assertThat(resolved[5]?.id).isEqualTo(4L)
        assertThat(resolved[30]?.id).isEqualTo(5L)
        assertThat(resolved[60]?.id).isEqualTo(6L)
    }

    @Test
    fun keepsAvailableHorizonFromLatestCycleAndDoesNotBackfillOlderCycle() {
        val baseTs = 1_800_000_000_000L
        val cycleAOrigin = baseTs
        val cycleBOrigin = baseTs + 60_000L

        val rows = listOf(
            ForecastEntity(
                id = 10,
                timestamp = cycleAOrigin + 5 * 60_000L,
                horizonMinutes = 5,
                valueMmol = 5.0,
                ciLow = 4.7,
                ciHigh = 5.3,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 11,
                timestamp = cycleAOrigin + 30 * 60_000L,
                horizonMinutes = 30,
                valueMmol = 5.5,
                ciLow = 4.9,
                ciHigh = 6.1,
                modelVersion = "m"
            ),
            ForecastEntity(
                id = 12,
                timestamp = cycleBOrigin + 5 * 60_000L,
                horizonMinutes = 5,
                valueMmol = 5.2,
                ciLow = 4.8,
                ciHigh = 5.7,
                modelVersion = "m"
            )
        )

        val resolved = ForecastSnapshotResolver.resolveLatestByHorizon(rows)
        assertThat(resolved.keys).containsExactly(5)
        assertThat(resolved[5]?.id).isEqualTo(12L)
    }
}

