package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import kotlin.math.abs

class GlucoseAlertEngine {

    fun evaluate(
        input: GlucoseAlertInput,
        persisted: GlucoseAlertRuntimeState
    ): GlucoseAlertDecision {
        val alertsEnabled = input.settings.softAlertEnabled
        val currentFresh = input.currentGlucoseAgeMinutes?.let { it <= CURRENT_GLUCOSE_FRESH_MAX_MINUTES } == true
        val lowThreshold = input.settings.softAlertLowMmol
        val highThreshold = input.settings.softAlertHighMmol
        val configuredUrgentLowThreshold = input.settings.urgentLowMmol
        val urgentLowThreshold = maxOf(configuredUrgentLowThreshold, LOW_NOW_SAFETY_THRESHOLD_MMOL)

        val strongLow = alertsEnabled &&
            currentFresh &&
            input.currentGlucoseMmol != null &&
            (
                input.currentGlucoseMmol <= configuredUrgentLowThreshold ||
                    input.currentGlucoseMmol < LOW_NOW_SAFETY_THRESHOLD_MMOL
                )
        val disableReason = when {
            !alertsEnabled -> "glucose_alerts_disabled"
            input.staleData -> "stale_data"
            input.sensorBlocked -> "sensor_blocked"
            input.sensorSuspectFalseLow -> "suspect_false_low"
            else -> null
        }

        val centralMinutesToLow = estimateMinutesToLow(
            input = input,
            lowThreshold = lowThreshold,
            useConfidenceBand = false
        )
        val confidenceMinutesToLow = estimateMinutesToLow(
            input = input,
            lowThreshold = lowThreshold,
            useConfidenceBand = input.settings.softAlertUseConfidenceBand
        )
        val predictedMinutesToLow = when {
            centralMinutesToLow != null && centralMinutesToLow <= 5 -> centralMinutesToLow
            else -> confidenceMinutesToLow ?: centralMinutesToLow
        }
        val predictiveDisabled = !disableReason.isNullOrBlank()
        val highRisk = !predictiveDisabled && evaluateHighRisk(input, highThreshold)

        val rawCandidateStage = when {
            strongLow -> GlucoseAlertState.LOW_NOW
            predictiveDisabled -> GlucoseAlertState.NONE
            predictedMinutesToLow == null -> {
                if (highRisk) GlucoseAlertState.SOFT_HIGH_RISK else GlucoseAlertState.NONE
            }
            centralMinutesToLow != null && centralMinutesToLow <= 5 -> GlucoseAlertState.CRITICAL_5
            predictedMinutesToLow <= 30 -> GlucoseAlertState.WARNING_30
            predictedMinutesToLow <= 60 -> GlucoseAlertState.WATCH_60
            highRisk -> GlucoseAlertState.SOFT_HIGH_RISK
            else -> GlucoseAlertState.NONE
        }
        val stageDisableReason = rawCandidateStage.disableReasonIfDisabled(input.settings)
        val candidateStage = if (stageDisableReason == null) rawCandidateStage else GlucoseAlertState.NONE

        val candidateDirection = when (candidateStage) {
            GlucoseAlertState.WATCH_60,
            GlucoseAlertState.WARNING_30,
            GlucoseAlertState.CRITICAL_5,
            GlucoseAlertState.LOW_NOW -> GlucoseAlertDirection.LOW
            GlucoseAlertState.SOFT_HIGH_RISK -> GlucoseAlertDirection.HIGH
            GlucoseAlertState.NONE -> null
        }

        val stageCompare = compareSeverity(candidateStage, persisted.activeAlertState)
        val requiresConfirmation = candidateStage.requiresSoftConfirmation()
        val samePending = candidateStage != GlucoseAlertState.NONE &&
            persisted.pendingRiskKey == riskKey(candidateStage, candidateDirection, lowThreshold, highThreshold)
        val pendingCount = when {
            candidateStage == GlucoseAlertState.NONE -> 0
            requiresConfirmation && samePending -> persisted.pendingRiskCount + 1
            requiresConfirmation -> 1
            else -> 0
        }
        val immediateEscalation = stageCompare > 0 && (
            persisted.activeAlertState != GlucoseAlertState.NONE || candidateStage.isStrongEscalation()
        )
        val confirmationSatisfied = !requiresConfirmation ||
            immediateEscalation ||
            pendingCount >= SOFT_CONFIRMATION_REQUIRED

        val safeNow = candidateStage == GlucoseAlertState.NONE || compareSeverity(candidateStage, persisted.activeAlertState) < 0
        val safeSinceTs = when {
            !safeNow -> 0L
            persisted.safeSinceTs > 0L -> persisted.safeSinceTs
            else -> input.nowTs
        }
        val safeSamplesCount = when {
            !safeNow -> 0
            persisted.activeAlertState == GlucoseAlertState.NONE -> 0
            else -> persisted.safeSamplesCount + 1
        }
        val safeDurationMs = if (safeSinceTs > 0L) (input.nowTs - safeSinceTs).coerceAtLeast(0L) else 0L
        val corroboratedHighReversal = candidateStage == GlucoseAlertState.SOFT_HIGH_RISK &&
            persisted.activeDirection == GlucoseAlertDirection.LOW && confirmationSatisfied &&
            currentFresh && (input.currentGlucoseAgeMinutes ?: -1L) >= 0L &&
            listOf(input.currentGlucoseMmol, input.pred30, input.ciLow30, input.ciHigh30)
                .all { it != null && it.isFinite() && it >= highThreshold } &&
            input.ciLow30!! <= input.pred30!! && input.pred30 <= input.ciHigh30!!
        // A confirmed current high with an entirely high forecast band is a new
        // opposite risk, not an ordinary return to safety after a low episode.
        val hysteresisSatisfied = safeDurationMs >= SAFE_DURATION_HYSTERESIS_MS || corroboratedHighReversal

        var resolvedStage = persisted.activeAlertState
        var resolvedDirection = persisted.activeDirection
        val repeatSuppressedByTrend = false
        var notifyKind = GlucoseAlertNotifyKind.NONE
        var nextLastSoftAlertAtTs = persisted.lastSoftAlertAtTs
        var nextLastStrongAlertAtTs = persisted.lastStrongAlertAtTs
        var nextStrongAlertSequence = persisted.strongAlertSequence
        var nextStageChangeTs = persisted.lastStageChangeTs
        val muted = persisted.mutedUntilTs > input.nowTs

        if (persisted.activeAlertState == GlucoseAlertState.NONE) {
            if (candidateStage != GlucoseAlertState.NONE && confirmationSatisfied) {
                resolvedStage = candidateStage
                resolvedDirection = candidateDirection
                nextStageChangeTs = input.nowTs
                notifyKind = candidateStage.toNotifyKind()
            }
        } else {
            when {
                stageCompare > 0 && confirmationSatisfied -> {
                    resolvedStage = candidateStage
                    resolvedDirection = candidateDirection
                    nextStageChangeTs = input.nowTs
                    notifyKind = candidateStage.toNotifyKind()
                }

                candidateStage == persisted.activeAlertState -> {
                    resolvedStage = candidateStage
                    resolvedDirection = candidateDirection
                }

                safeNow && hysteresisSatisfied -> {
                    if (candidateStage != GlucoseAlertState.NONE && confirmationSatisfied) {
                        resolvedStage = candidateStage
                        resolvedDirection = candidateDirection
                        nextStageChangeTs = input.nowTs
                        notifyKind = candidateStage.toNotifyKind()
                    } else {
                        resolvedStage = GlucoseAlertState.NONE
                        resolvedDirection = null
                        nextStageChangeTs = input.nowTs
                        notifyKind = GlucoseAlertNotifyKind.CLEAR
                    }
                }
            }
        }

        if (notifyKind.isSoft()) {
            nextLastSoftAlertAtTs = input.nowTs
        } else if (notifyKind.isStrong()) {
            nextLastStrongAlertAtTs = input.nowTs
            nextStrongAlertSequence = persisted.strongAlertSequence + 1
        } else if (resolvedStage == GlucoseAlertState.NONE) {
            nextStrongAlertSequence = 0
        }

        val nextState = GlucoseAlertRuntimeState(
            lastSoftAlertAtTs = nextLastSoftAlertAtTs,
            lastStrongAlertAtTs = nextLastStrongAlertAtTs,
            activeAlertState = resolvedStage,
            activeDirection = resolvedDirection,
            lastNotifiedRiskKey = when (notifyKind) {
                GlucoseAlertNotifyKind.NONE,
                GlucoseAlertNotifyKind.CLEAR -> persisted.lastNotifiedRiskKey
                else -> riskKey(resolvedStage, resolvedDirection, lowThreshold, highThreshold)
            },
            pendingRiskKey = when {
                candidateStage == GlucoseAlertState.NONE || confirmationSatisfied -> null
                else -> riskKey(candidateStage, candidateDirection, lowThreshold, highThreshold)
            },
            pendingRiskCount = when {
                candidateStage == GlucoseAlertState.NONE || confirmationSatisfied -> 0
                else -> pendingCount
            },
            safeSamplesCount = if (resolvedStage == GlucoseAlertState.NONE) 0 else safeSamplesCount,
            safeSinceTs = if (resolvedStage == GlucoseAlertState.NONE) 0L else safeSinceTs,
            lastStageChangeTs = nextStageChangeTs,
            mutedUntilTs = if (muted) persisted.mutedUntilTs else 0L,
            activeEpisodeId = "",
            strongAlertSequence = nextStrongAlertSequence
        )
        val episodeStage = if (candidateStage == GlucoseAlertState.NONE) {
            GlucoseAlertState.NONE
        } else {
            resolvedStage
        }
        val episodeDirection = if (episodeStage == GlucoseAlertState.NONE) null else resolvedDirection

        return GlucoseAlertDecision(
            state = resolvedStage,
            direction = resolvedDirection,
            notifyKind = notifyKind,
            nextState = nextState,
            lowThreshold = lowThreshold,
            highThreshold = highThreshold,
            urgentLowThreshold = urgentLowThreshold,
            pred5 = input.pred5,
            pred30 = input.pred30,
            pred60 = input.pred60,
            ciLow30 = input.ciLow30,
            ciHigh30 = input.ciHigh30,
            currentGlucoseMmol = input.currentGlucoseMmol,
            currentGlucoseFresh = currentFresh,
            predictedMinutesToLow = predictedMinutesToLow,
            trendDelta5Mmol = input.trendDelta5Mmol,
            softActive = resolvedStage in setOf(
                GlucoseAlertState.WATCH_60,
                GlucoseAlertState.WARNING_30,
                GlucoseAlertState.SOFT_HIGH_RISK
            ),
            strongActive = resolvedStage in setOf(
                GlucoseAlertState.CRITICAL_5,
                GlucoseAlertState.LOW_NOW
            ),
            repeatSuppressedByTrend = repeatSuppressedByTrend,
            disableReason = when {
                muted && resolvedStage != GlucoseAlertState.NONE -> "glucose_alert_muted"
                resolvedStage == GlucoseAlertState.NONE && !disableReason.isNullOrBlank() -> disableReason
                resolvedStage == GlucoseAlertState.NONE && !stageDisableReason.isNullOrBlank() -> stageDisableReason
                resolvedStage == GlucoseAlertState.NONE && candidateStage == GlucoseAlertState.NONE && input.pred30 == null && !strongLow -> "missing_forecast30"
                else -> null
            },
            episodeId = "",
            strongAlertSequence = nextStrongAlertSequence,
            episodeStage = episodeStage,
            episodeDirection = episodeDirection
        )
    }

    private fun evaluateHighRisk(input: GlucoseAlertInput, highThreshold: Double): Boolean {
        val highProbe = if (input.settings.softAlertUseConfidenceBand) {
            input.ciHigh30 ?: input.pred30
        } else {
            input.pred30
        }
        return highProbe != null && highProbe > highThreshold
    }

    private fun estimateMinutesToLow(
        input: GlucoseAlertInput,
        lowThreshold: Double,
        useConfidenceBand: Boolean
    ): Int? {
        val points = buildList {
            input.currentGlucoseMmol?.let { add(0 to it) }
            input.pred5?.let { add(5 to it) }
            val low30 = if (useConfidenceBand) input.ciLow30 ?: input.pred30 else input.pred30
            low30?.let { add(30 to it) }
            input.pred60?.let { add(60 to it) }
        }.distinctBy { it.first }
            .sortedBy { it.first }
        if (points.size < 2) return null
        for (index in 1 until points.size) {
            val (timeA, valueA) = points[index - 1]
            val (timeB, valueB) = points[index]
            if (!valueA.isFinite() || !valueB.isFinite()) continue
            if (valueA <= lowThreshold) return timeA
            if (valueA > lowThreshold && valueB <= lowThreshold) {
                val span = (timeB - timeA).coerceAtLeast(1)
                val delta = valueB - valueA
                if (abs(delta) < 1e-6) return timeB
                val ratio = ((lowThreshold - valueA) / delta).coerceIn(0.0, 1.0)
                return (timeA + span * ratio).toInt()
            }
        }
        return null
    }

    private fun riskKey(
        stage: GlucoseAlertState,
        direction: GlucoseAlertDirection?,
        lowThreshold: Double,
        highThreshold: Double
    ): String {
        val threshold = when (direction) {
            GlucoseAlertDirection.LOW -> lowThreshold
            GlucoseAlertDirection.HIGH -> highThreshold
            null -> null
        }
        return listOf(stage.name, direction?.name.orEmpty(), threshold?.let { "%.1f".format(it) }.orEmpty()).joinToString(":")
    }

    private fun compareSeverity(candidate: GlucoseAlertState, current: GlucoseAlertState): Int {
        return candidate.severity - current.severity
    }

    companion object {
        private const val CURRENT_GLUCOSE_FRESH_MAX_MINUTES = 5L
        private const val LOW_NOW_SAFETY_THRESHOLD_MMOL = 4.0
        private const val SOFT_CONFIRMATION_REQUIRED = 2
        private const val SAFE_DURATION_HYSTERESIS_MS = 15 * 60_000L
    }
}

data class GlucoseAlertInput(
    val nowTs: Long,
    val settings: AppSettings,
    val currentGlucoseMmol: Double?,
    val currentGlucoseAgeMinutes: Long?,
    val pred5: Double?,
    val pred30: Double?,
    val pred60: Double?,
    val ciLow30: Double?,
    val ciHigh30: Double?,
    val trendDelta5Mmol: Double?,
    val staleData: Boolean,
    val sensorBlocked: Boolean,
    val sensorSuspectFalseLow: Boolean
)

data class GlucoseAlertDecision(
    val state: GlucoseAlertState,
    val direction: GlucoseAlertDirection?,
    val notifyKind: GlucoseAlertNotifyKind,
    val nextState: GlucoseAlertRuntimeState,
    val lowThreshold: Double,
    val highThreshold: Double,
    val urgentLowThreshold: Double,
    val pred5: Double?,
    val pred30: Double?,
    val pred60: Double?,
    val ciLow30: Double?,
    val ciHigh30: Double?,
    val currentGlucoseMmol: Double?,
    val currentGlucoseFresh: Boolean,
    val predictedMinutesToLow: Int?,
    val trendDelta5Mmol: Double?,
    val softActive: Boolean,
    val strongActive: Boolean,
    val repeatSuppressedByTrend: Boolean,
    val disableReason: String?,
    val episodeId: String = "",
    val strongAlertSequence: Int = 0,
    val episodeStage: GlucoseAlertState = state,
    val episodeDirection: GlucoseAlertDirection? = direction
)

enum class GlucoseAlertNotifyKind {
    NONE,
    WATCH_60,
    WARNING_30,
    SOFT_HIGH,
    CRITICAL_5,
    LOW_NOW,
    CLEAR;

    fun isSoft(): Boolean = this in setOf(WATCH_60, WARNING_30, SOFT_HIGH)
    fun isStrong(): Boolean = this in setOf(CRITICAL_5, LOW_NOW)
}

private val GlucoseAlertState.severity: Int
    get() = when (this) {
        GlucoseAlertState.NONE -> 0
        GlucoseAlertState.SOFT_HIGH_RISK -> 1
        GlucoseAlertState.WATCH_60 -> 2
        GlucoseAlertState.WARNING_30 -> 3
        GlucoseAlertState.CRITICAL_5 -> 4
        GlucoseAlertState.LOW_NOW -> 5
    }

private fun GlucoseAlertState.requiresSoftConfirmation(): Boolean = this in setOf(
    GlucoseAlertState.WATCH_60,
    GlucoseAlertState.WARNING_30,
    GlucoseAlertState.SOFT_HIGH_RISK
)

private fun GlucoseAlertState.isStrongEscalation(): Boolean = this in setOf(
    GlucoseAlertState.CRITICAL_5,
    GlucoseAlertState.LOW_NOW
)

private fun GlucoseAlertState.toNotifyKind(): GlucoseAlertNotifyKind = when (this) {
    GlucoseAlertState.NONE -> GlucoseAlertNotifyKind.NONE
    GlucoseAlertState.WATCH_60 -> GlucoseAlertNotifyKind.WATCH_60
    GlucoseAlertState.WARNING_30 -> GlucoseAlertNotifyKind.WARNING_30
    GlucoseAlertState.SOFT_HIGH_RISK -> GlucoseAlertNotifyKind.SOFT_HIGH
    GlucoseAlertState.CRITICAL_5 -> GlucoseAlertNotifyKind.CRITICAL_5
    GlucoseAlertState.LOW_NOW -> GlucoseAlertNotifyKind.LOW_NOW
}

private fun GlucoseAlertState.disableReasonIfDisabled(settings: AppSettings): String? = when (this) {
    GlucoseAlertState.NONE -> null
    GlucoseAlertState.WATCH_60 -> if (settings.watch60AlertEnabled) null else "watch_60_disabled"
    GlucoseAlertState.WARNING_30 -> if (settings.warning30AlertEnabled) null else "warning_30_disabled"
    GlucoseAlertState.SOFT_HIGH_RISK -> if (settings.softHighAlertEnabled) null else "soft_high_disabled"
    GlucoseAlertState.CRITICAL_5 -> if (settings.critical5AlertEnabled) null else "critical_5_disabled"
    GlucoseAlertState.LOW_NOW -> if (settings.lowNowAlertEnabled) null else "low_now_disabled"
}
