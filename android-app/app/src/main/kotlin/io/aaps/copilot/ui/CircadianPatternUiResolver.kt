package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.ui.foundation.screens.CircadianCurvePointUi
import io.aaps.copilot.ui.foundation.screens.CircadianDeltaPointUi
import io.aaps.copilot.ui.foundation.screens.CircadianReplayDiagnosticUi
import io.aaps.copilot.ui.foundation.screens.CircadianPatternSectionUi
import io.aaps.copilot.ui.foundation.screens.CircadianPatternWindowSeriesUi
import io.aaps.copilot.ui.foundation.screens.CircadianRiskWindowUi
import kotlin.math.max

object CircadianPatternUiResolver {

    fun buildSections(
        slotStats: List<CircadianSlotStatEntity>,
        transitionStats: List<CircadianTransitionStatEntity>,
        snapshots: List<CircadianPatternSnapshotEntity>,
        replayStats: List<CircadianReplaySlotStatEntity>,
        nowTs: Long
    ): List<CircadianPatternSectionUi> {
        if (snapshots.isEmpty()) return emptyList()
        val currentSlotIndex = slotIndex(nowTs)
        return snapshots
            .sortedWith(compareBy<CircadianPatternSnapshotEntity> { dayTypeRank(it.dayType) }.thenBy { it.dayType })
            .map { snapshot ->
                val source = snapshot.segmentSource
                val windows = WINDOW_DAYS.mapNotNull { windowDays ->
                    val slotRows = slotStats
                        .filter { it.dayType == source && it.windowDays == windowDays }
                        .sortedBy { it.slotIndex }
                    if (slotRows.isEmpty()) return@mapNotNull null
                    val transitionBySlot = transitionStats
                        .filter { it.dayType == source && it.windowDays == windowDays }
                        .groupBy { it.slotIndex }
                    val pointRows = slotRows.map { row ->
                        CircadianCurvePointUi(
                            slotIndex = row.slotIndex,
                            medianBg = row.medianBg,
                            p10 = row.p10,
                            p25 = row.p25,
                            p75 = row.p75,
                            p90 = row.p90,
                            lowRate = row.pLow,
                            highRate = row.pHigh,
                            recommendedTargetMmol = recommendedTargetForSlot(row)
                        )
                    }
                    val deltaRows = slotRows.map { row ->
                        val slotTransitions = transitionBySlot[row.slotIndex].orEmpty()
                        val h30 = slotTransitions.firstOrNull { it.horizonMinutes == 30 }
                        val h60 = slotTransitions.firstOrNull { it.horizonMinutes == 60 }
                        CircadianDeltaPointUi(
                            slotIndex = row.slotIndex,
                            delta30 = h30?.deltaMedian,
                            delta60 = h60?.deltaMedian,
                            confidence30 = h30?.confidence,
                            confidence60 = h60?.confidence
                        )
                    }
                    CircadianPatternWindowSeriesUi(
                        windowDays = windowDays,
                        coverageDays = slotRows.maxOfOrNull { it.activeDays } ?: 0,
                        sampleCount = slotRows.sumOf { it.sampleCount },
                        confidence = slotRows.map { it.confidence }.average().coerceIn(0.0, 1.0),
                        qualityScore = slotRows.map { it.qualityScore }.average().coerceIn(0.0, 1.0),
                        points = pointRows,
                        deltaPoints = deltaRows,
                        topRiskWindows = buildTopRiskWindows(slotRows),
                        replayDiagnostics = buildReplayDiagnostics(
                            replayStats = replayStats,
                            requestedDayType = snapshot.dayType,
                            windowDays = windowDays,
                            slotIndex = currentSlotIndex
                        )
                    )
                }
                CircadianPatternSectionUi(
                    requestedDayType = snapshot.dayType,
                    segmentSource = snapshot.segmentSource,
                    stableWindowDays = snapshot.stableWindowDays,
                    recencyWindowDays = snapshot.recencyWindowDays,
                    recencyWeight = snapshot.recencyWeight,
                    coverageDays = snapshot.coverageDays,
                    sampleCount = snapshot.sampleCount,
                    segmentFallback = snapshot.segmentFallback,
                    fallbackReason = snapshot.fallbackReason,
                    confidence = snapshot.confidence,
                    qualityScore = snapshot.qualityScore,
                    windows = windows
                )
            }
    }

    private fun buildTopRiskWindows(rows: List<CircadianSlotStatEntity>): List<CircadianRiskWindowUi> {
        return (0 until 24).mapNotNull { hour ->
            val hourRows = rows.filter { it.slotIndex / 4 == hour }
            if (hourRows.isEmpty()) return@mapNotNull null
            val lowRate = hourRows.map { it.pLow }.average()
            val highRate = hourRows.map { it.pHigh }.average()
            val riskStrength = max(lowRate - LOW_TRIGGER, highRate - HIGH_TRIGGER)
            if (riskStrength <= 0.0) return@mapNotNull null
            CircadianRiskWindowUi(
                hour = hour,
                lowRate = lowRate,
                highRate = highRate,
                recommendedTargetMmol = recommendedTargetForRates(lowRate, highRate)
            )
        }
            .sortedByDescending { max(it.lowRate - LOW_TRIGGER, it.highRate - HIGH_TRIGGER) }
            .take(4)
    }

    private fun buildReplayDiagnostics(
        replayStats: List<CircadianReplaySlotStatEntity>,
        requestedDayType: String,
        windowDays: Int,
        slotIndex: Int
    ): List<CircadianReplayDiagnosticUi> {
        return listOf(30, 60).mapNotNull { horizon ->
            val requested = replayStats.firstOrNull {
                it.dayType == requestedDayType &&
                    it.windowDays == windowDays &&
                    it.slotIndex == slotIndex &&
                    it.horizonMinutes == horizon
            }
            val fallback = replayStats.firstOrNull {
                it.dayType == "ALL" &&
                    it.windowDays == windowDays &&
                    it.slotIndex == slotIndex &&
                    it.horizonMinutes == horizon
            }
            val row = requested ?: fallback ?: return@mapNotNull null
            CircadianReplayDiagnosticUi(
                horizonMinutes = horizon,
                bucketStatus = classifyReplayBucket(
                    sampleCount = row.sampleCount,
                    coverageDays = row.coverageDays,
                    requiredCoverageDays = when (requestedDayType.uppercase()) {
                        "WEEKDAY" -> 3
                        "WEEKEND" -> 2
                        else -> 4
                    },
                    maeImprovementMmol = row.maeImprovementMmol,
                    winRate = row.winRate
                ),
                winRate = row.winRate,
                maeBaseline = row.maeBaseline,
                maeCircadian = row.maeCircadian,
                sampleCount = row.sampleCount,
                fallbackToAll = requested == null && fallback != null
            )
        }
    }

    private fun classifyReplayBucket(
        sampleCount: Int,
        coverageDays: Int,
        requiredCoverageDays: Int,
        maeImprovementMmol: Double,
        winRate: Double
    ): String {
        return when {
            sampleCount < 8 || coverageDays < requiredCoverageDays -> "INSUFFICIENT"
            maeImprovementMmol <= -0.05 && winRate >= 0.55 -> "HELPFUL"
            maeImprovementMmol > 0.05 || winRate < 0.45 -> "HARMFUL"
            else -> "NEUTRAL"
        }
    }

    private fun recommendedTargetForSlot(row: CircadianSlotStatEntity): Double {
        return recommendedTargetForRates(
            lowRate = row.pLow,
            highRate = row.pHigh
        )
    }

    private fun recommendedTargetForRates(
        lowRate: Double,
        highRate: Double
    ): Double {
        val base = 5.5
        return when {
            lowRate >= 0.30 -> 6.10
            lowRate >= 0.20 -> 5.90
            lowRate >= LOW_TRIGGER -> 5.75
            highRate >= 0.35 && lowRate < LOW_TRIGGER * 0.5 -> 5.05
            highRate >= 0.25 && lowRate < LOW_TRIGGER * 0.5 -> 5.20
            highRate >= HIGH_TRIGGER && lowRate < LOW_TRIGGER * 0.6 -> 5.30
            else -> base
        }
    }

    private fun dayTypeRank(dayType: String): Int {
        return when (dayType.uppercase()) {
            "WEEKDAY" -> 0
            "WEEKEND" -> 1
            else -> 2
        }
    }

    private fun slotIndex(ts: Long): Int {
        val zoned = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
        return ((zoned.hour * 60 + zoned.minute) / 15).coerceIn(0, 95)
    }

    private val WINDOW_DAYS = listOf(5, 7, 10, 14)
    private const val LOW_TRIGGER = 0.12
    private const val HIGH_TRIGGER = 0.18
}
