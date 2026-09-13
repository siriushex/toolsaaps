package io.aaps.copilot.domain.eating

data class EatingEvidencePoint(
    val ts: Long,
    val carbsG: Double,
    val syntheticUam: Boolean
)

data class ProbableEatingWindow(
    val medianMinuteOfDay: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val iqrMinutes: Int,
    val supportDays: Int,
    val lookbackDays: Int,
    val episodeCount: Int,
    val enteredEpisodeCount: Int,
    val uamEpisodeCount: Int,
    val confidencePct: Double
)

data class EatingWindowAnalysis(
    val lookbackDays: Int,
    val windows: List<ProbableEatingWindow>,
    val excludedNightEpisodeCount: Int
)

data class EatingWindowDualHorizonSnapshot(
    val recent: EatingWindowAnalysis,
    val stable: EatingWindowAnalysis
)
