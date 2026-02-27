package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import kotlin.math.abs

class SegmentProfileGuardRule : TargetRule {

    override val id: String = "SegmentProfileGuard.v1"

    override fun evaluate(context: RuleContext): RuleDecision {
        if (!context.dataFresh) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("stale_data"), null)
        }
        val profile = context.currentProfileEstimate ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("no_profile_estimate"), null)
        val segment = context.currentProfileSegment ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("no_profile_segment"), null)
        if (segment.confidence < MIN_SEGMENT_CONFIDENCE) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("segment_confidence_low"), null)
        }
        val segmentIsf = segment.isfMmolPerUnit ?: return RuleDecision(id, RuleState.NO_MATCH, listOf("segment_missing_isf"), null)
        if (profile.isfMmolPerUnit <= 0.0) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("invalid_profile_isf"), null)
        }

        val isfRatio = segmentIsf / profile.isfMmolPerUnit
        val segmentCr = segment.crGramPerUnit
        val crRatio = if (segmentCr != null && profile.crGramPerUnit > 0.0) {
            segmentCr / profile.crGramPerUnit
        } else {
            null
        }

        val (target, reason) = when {
            isfRatio >= MORE_SENSITIVE_ISF_RATIO -> context.baseTargetMmol + MORE_SENSITIVE_TARGET_STEP to "segment_more_sensitive"
            isfRatio <= LESS_SENSITIVE_ISF_RATIO && (crRatio == null || crRatio <= LESS_SENSITIVE_CR_RATIO) ->
                context.baseTargetMmol - LESS_SENSITIVE_TARGET_STEP to "segment_less_sensitive"
            else -> return RuleDecision(id, RuleState.NO_MATCH, listOf("segment_neutral"), null)
        }

        val boundedTarget = target.coerceIn(4.0, 10.0)
        if (abs(boundedTarget - context.baseTargetMmol) < 0.15) {
            return RuleDecision(id, RuleState.NO_MATCH, listOf("close_to_base_target"), null)
        }
        if (context.activeTempTargetMmol != null && abs(context.activeTempTargetMmol - boundedTarget) < 0.15) {
            return RuleDecision(id, RuleState.BLOCKED, listOf("same_temp_target_active"), null)
        }

        return RuleDecision(
            ruleId = id,
            state = RuleState.TRIGGERED,
            reasons = buildList {
                add(reason)
                add("day=${segment.dayType}")
                add("slot=${segment.timeSlot}")
                add("segmentConfidence=${"%.2f".format(segment.confidence)}")
                add("isfRatio=${"%.2f".format(isfRatio)}")
                crRatio?.let { add("crRatio=${"%.2f".format(it)}") }
            },
            actionProposal = ActionProposal(
                type = "temp_target",
                targetMmol = boundedTarget,
                durationMinutes = 60,
                reason = reason
            )
        )
    }

    private companion object {
        const val MIN_SEGMENT_CONFIDENCE = 0.35
        const val MORE_SENSITIVE_ISF_RATIO = 1.20
        const val LESS_SENSITIVE_ISF_RATIO = 0.85
        const val LESS_SENSITIVE_CR_RATIO = 0.90
        const val MORE_SENSITIVE_TARGET_STEP = 0.30
        const val LESS_SENSITIVE_TARGET_STEP = 0.20
    }
}
