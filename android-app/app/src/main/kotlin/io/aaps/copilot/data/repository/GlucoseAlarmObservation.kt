package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.LocalAlarmLevel

/** Measurement window, not episode or hardware authority. Only the committed producer can publish it. */
class GlucoseAlarmObservation private constructor(
    private val episodeStage: GlucoseAlertState,
    private val episodeDirection: GlucoseAlertDirection,
    internal val observation: LocalAlarmSourceObservation
) {
    internal fun matches(signal: EpisodeAlertSignal) = signal.stage == episodeStage &&
        signal.direction == episodeDirection && signal.nowTs == observation.evaluatedAtWallMs

    companion object {
        internal fun fromDecision(decision: GlucoseAlertDecision, evaluatedAt: Long,
            glucoseTimestamp: Long?): GlucoseAlarmObservation? {
            val timestamp = glucoseTimestamp ?: return null
            val until = GlucoseAlertEngine.currentGlucoseValidUntilTimestamp(timestamp) ?: return null
            val level = when (decision.currentAlarmStage) {
                GlucoseAlertState.WARNING_30 -> LocalAlarmLevel.WARNING_30
                GlucoseAlertState.SOFT_HIGH_RISK -> LocalAlarmLevel.SOFT_HIGH_RISK
                GlucoseAlertState.CRITICAL_5 -> LocalAlarmLevel.CRITICAL_5
                GlucoseAlertState.LOW_NOW -> LocalAlarmLevel.LOW_NOW
                else -> return null
            }
            val direction = if (level == LocalAlarmLevel.SOFT_HIGH_RISK) GlucoseAlertDirection.HIGH else GlucoseAlertDirection.LOW
            val value = decision.currentGlucoseMmol
            if (!decision.currentGlucoseFresh || value == null || !value.isFinite() || value <= 0 ||
                evaluatedAt < timestamp || evaluatedAt >= until || decision.episodeStage == GlucoseAlertState.NONE ||
                decision.episodeDirection != direction) return null
            return GlucoseAlarmObservation(decision.episodeStage, direction,
                LocalAlarmSourceObservation(level, evaluatedAt, timestamp, until, direction.name))
        }
    }
}
