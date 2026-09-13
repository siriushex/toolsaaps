package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState

class TargetProposalFactory {

    fun fromRuleDecision(
        decision: RuleDecision,
        priority: Int,
        generatedAt: Long,
        inputFingerprint: String
    ): TargetProposal? {
        val action = decision.actionProposal ?: return null
        if (decision.state != RuleState.TRIGGERED || !action.type.equals("temp_target", ignoreCase = true)) {
            return null
        }
        val intent = inferIntent(decision.ruleId, action.reason, decision.reasons)
        return TargetProposal(
            sourceRuleId = decision.ruleId,
            intent = intent,
            targetMmol = action.targetMmol,
            durationMinutes = action.durationMinutes,
            priority = priority,
            confidence = 1.0,
            reasonCodes = decision.reasons + action.reason,
            generatedAt = generatedAt,
            inputFingerprint = inputFingerprint
        )
    }

    fun sensorSafetyRelease(
        activeTarget: ActiveAapsTarget?,
        baseTargetMmol: Double,
        sensorBlocked: Boolean,
        sensorReason: String,
        generatedAt: Long,
        inputFingerprint: String
    ): TargetProposal? {
        if (!sensorBlocked || activeTarget == null) return null
        if (activeTarget.ownership != ActiveTargetOwnership.TARGET_MANAGER) return null
        if (activeTarget.targetMmol >= baseTargetMmol - EPSILON) return null
        if (baseTargetMmol - activeTarget.targetMmol < SENSOR_RELEASE_MIN_DELTA_MMOL - EPSILON) return null
        return TargetProposal(
            sourceRuleId = SENSOR_SAFETY_SOURCE,
            intent = TargetIntent.SENSOR_SAFETY_RELEASE,
            targetMmol = baseTargetMmol,
            durationMinutes = SENSOR_RELEASE_DURATION_MINUTES,
            priority = SENSOR_RELEASE_PRIORITY,
            confidence = 1.0,
            reasonCodes = listOf("sensor_quality_rollback", sensorReason),
            generatedAt = generatedAt,
            inputFingerprint = inputFingerprint
        )
    }

    fun keepalive(
        accepted: AcceptedTargetState?,
        nowTs: Long,
        sensorTrust: SensorTrustState,
        mode: TargetManagerMode,
        inputFingerprint: String
    ): TargetProposal? {
        if (accepted == null || sensorTrust != SensorTrustState.TRUSTED) return null
        val requiredStatus = when (mode) {
            TargetManagerMode.ACTIVE -> "sent"
            TargetManagerMode.SHADOW -> "shadow"
            TargetManagerMode.OFF -> return null
        }
        if (accepted.lastCommandStatus != requiredStatus) return null
        val remainingMs = try {
            Math.subtractExact(accepted.expiresAt, nowTs)
        } catch (_: ArithmeticException) {
            return null
        }
        if (remainingMs !in -TargetManager.KEEPALIVE_GRACE_MS..TargetManager.KEEPALIVE_WINDOW_MS) {
            return null
        }
        return TargetProposal(
            sourceRuleId = TargetManager.RENEWAL_SOURCE_RULE_ID,
            intent = accepted.intent,
            targetMmol = accepted.targetMmol,
            durationMinutes = accepted.durationMinutes,
            priority = KEEPALIVE_PRIORITY,
            confidence = 1.0,
            reasonCodes = listOf("target_manager_keepalive", "owner=${accepted.ownerRuleId}"),
            generatedAt = nowTs,
            inputFingerprint = inputFingerprint
        )
    }

    /**
     * The repository owns this lifecycle proposal because only it can prove the accepted
     * target was a prior planned-activity command. It never acts on foreign ownership.
     */
    fun plannedActivityReturnToBase(
        accepted: AcceptedTargetState?,
        input: TargetManagerInput
    ): TargetProposal? {
        if (!input.activitySafety.returnToBaseRequested || input.activitySafety.keepaliveAllowed) return null
        if (accepted?.intent != TargetIntent.PLANNED_ACTIVITY_ADAPTATION ||
            accepted.ownerRuleId != PLANNED_ACTIVITY_SOURCE
        ) return null
        return TargetProposal(
            sourceRuleId = PLANNED_ACTIVITY_RETURN_SOURCE,
            intent = TargetIntent.RECOVERY_TO_BASE,
            targetMmol = input.safety.baseTargetMmol,
            durationMinutes = PLANNED_ACTIVITY_RETURN_DURATION_MINUTES,
            priority = PLANNED_ACTIVITY_RETURN_PRIORITY,
            confidence = 1.0,
            reasonCodes = listOf("planned_activity_return_to_base", "owner=${accepted.ownerRuleId}"),
            generatedAt = input.nowTs,
            inputFingerprint = "planned-activity-return:${accepted.revision}:${accepted.lastCommandId}:" +
                "${input.activitySafety.occurrenceId.orEmpty()}:${input.activitySafety.occurrenceRevision ?: -1L}"
        )
    }

    private fun inferIntent(
        ruleId: String,
        actionReason: String,
        reasons: List<String>
    ): TargetIntent {
        val evidence = (listOf(ruleId, actionReason) + reasons).joinToString("|").lowercase()
        return when {
            "hypo_preemptive" in evidence || "safety_hypo" in evidence || "force_high" in evidence ->
                TargetIntent.HYPO_PROTECTION
            "recovery_to_base" in evidence -> TargetIntent.RECOVERY_TO_BASE
            "activity" in evidence || "exercise" in evidence || "steps_" in evidence ->
                TargetIntent.ACTIVITY_PROTECTION
            "posthypo" in evidence || "post_hypo" in evidence ->
                TargetIntent.POST_HYPO_PROTECTION
            else -> TargetIntent.NORMAL_CONTROL
        }
    }

    companion object {
        const val SENSOR_SAFETY_SOURCE = "sensor_quality_rollback"
        const val PLANNED_ACTIVITY_SOURCE = "PlannedActivityTarget.v1"
        const val PLANNED_ACTIVITY_RETURN_SOURCE = "planned_activity_return_to_base"

        private const val SENSOR_RELEASE_PRIORITY = 10_000
        private const val KEEPALIVE_PRIORITY = -10_000
        private const val PLANNED_ACTIVITY_RETURN_PRIORITY = 349
        private const val SENSOR_RELEASE_DURATION_MINUTES = 30
        private const val PLANNED_ACTIVITY_RETURN_DURATION_MINUTES = 30
        private const val SENSOR_RELEASE_MIN_DELTA_MMOL = 0.20
        private const val EPSILON = 1e-9
    }
}
