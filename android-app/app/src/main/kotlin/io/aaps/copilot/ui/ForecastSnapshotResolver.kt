package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.ForecastEntity

object ForecastSnapshotResolver {
    private const val MINUTE_MS = 60_000L

    fun resolveLatestByHorizon(forecasts: List<ForecastEntity>): Map<Int, ForecastEntity> {
        if (forecasts.isEmpty()) return emptyMap()

        val byOriginBucket = forecasts.groupBy { forecast ->
            (forecast.timestamp - forecast.horizonMinutes * MINUTE_MS) / MINUTE_MS
        }
        val latestOriginBucket = byOriginBucket.keys.maxOrNull()
        val latestCycleRows = latestOriginBucket?.let { byOriginBucket[it] }.orEmpty()

        val fromLatestCycle = latestCycleRows
            .groupBy { it.horizonMinutes }
            .mapValues { (_, rows) ->
                rows.maxWithOrNull(compareBy<ForecastEntity> { it.timestamp }.thenBy { it.id })!!
            }

        if (fromLatestCycle.isNotEmpty()) return fromLatestCycle

        return forecasts
            .groupBy { it.horizonMinutes }
            .mapValues { (_, rows) ->
                rows.maxWithOrNull(compareBy<ForecastEntity> { it.timestamp }.thenBy { it.id })!!
            }
    }
}

