package io.aaps.copilot.domain.eating

import java.time.ZoneId

object EatingWindowSnapshotPlanner {
    const val RECENT_LOOKBACK_DAYS = 7
    const val STABLE_LOOKBACK_DAYS = 14

    fun plan(
        evidence: List<EatingEvidencePoint>,
        generatedAt: Long,
        zoneId: ZoneId
    ): EatingWindowDualHorizonSnapshot = EatingWindowDualHorizonSnapshot(
        recent = EatingWindowAnalyzer.analyze(
            evidence = evidence,
            generatedAt = generatedAt,
            zoneId = zoneId,
            lookbackDays = RECENT_LOOKBACK_DAYS
        ),
        stable = EatingWindowAnalyzer.analyze(
            evidence = evidence,
            generatedAt = generatedAt,
            zoneId = zoneId,
            lookbackDays = STABLE_LOOKBACK_DAYS
        )
    )
}
