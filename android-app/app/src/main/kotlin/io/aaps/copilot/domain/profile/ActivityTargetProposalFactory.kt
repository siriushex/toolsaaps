package io.aaps.copilot.domain.profile

import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.target.AcceptedTargetState
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManager
import io.aaps.copilot.domain.target.TargetProposal
import java.time.Instant

enum class ActivityTargetProposalDirection { RAISE, LOWER, HOLD }

enum class ActivityTargetBlocker {
    MODULE_DISABLED,
    OUTSIDE_ACTIVITY_WINDOW,
    MISSING_SAME_CYCLE_CANDIDATE,
    MISSING_PERSONAL_EVIDENCE,
    MISSING_REPLAY_EVIDENCE,
    MISSING_SAFE_PREDICTED_FALL,
    CANDIDATE_NOT_BELOW_BASE,
    INVALID_TARGET_BOUNDS
}

/** Stable evidence is deliberately explicit: schedule data alone cannot lower a target. */
data class ActivityPersonalRiseEvidence(val evidenceHash: String)

/** Replay proof must attest that the candidate did not increase low-glucose risk. */
data class ActivityReplayTargetEvidence(val replayHash: String)

/**
 * Same-cycle, control-forecast-only evidence that a planned activity needs a protective raise.
 * It deliberately rejects flat and rising trajectories as well as any low-risk interval.
 */
data class ActivitySafePredictedFall(
    val evidenceHash: String,
    val currentGlucoseMmol: Double,
    val observedDelta5Mmol: Double,
    val pred5Mmol: Double,
    val pred30Mmol: Double,
    val pred60Mmol: Double,
    val ciLow5Mmol: Double,
    val ciLow30Mmol: Double,
    val ciLow60Mmol: Double,
    val lowRiskThresholdMmol: Double
) {
    fun isSafePredictedFall(): Boolean =
        evidenceHash.isNotBlank() &&
            listOf(
                currentGlucoseMmol,
                observedDelta5Mmol,
                pred5Mmol,
                pred30Mmol,
                pred60Mmol,
                ciLow5Mmol,
                ciLow30Mmol,
                ciLow60Mmol,
                lowRiskThresholdMmol
            ).all(Double::isFinite) &&
            lowRiskThresholdMmol > 0.0 &&
            currentGlucoseMmol > lowRiskThresholdMmol &&
            ciLow5Mmol > lowRiskThresholdMmol &&
            ciLow30Mmol > lowRiskThresholdMmol &&
            ciLow60Mmol > lowRiskThresholdMmol &&
            observedDelta5Mmol < 0.0 &&
            pred5Mmol < currentGlucoseMmol &&
            pred30Mmol < pred5Mmol &&
            pred60Mmol < pred30Mmol
}

data class ActivityTargetProposal(
    val occurrenceId: String,
    val occurrenceRevision: Long,
    val intensity: PlannedActivityIntensity,
    val direction: ActivityTargetProposalDirection,
    val targetMmol: Double?,
    val validFromMs: Long,
    val validUntilMs: Long,
    val evidenceHash: String,
    val protectionEvidenceHash: String? = null,
    val sameCycleCandidateFingerprint: String? = null,
    val personalEvidenceHash: String?,
    val replayHash: String?,
    val blockers: Set<ActivityTargetBlocker>,
    val requiresUserConfirmation: Boolean = false
)

data class ActivityTargetProposalInput(
    val now: Instant,
    val occurrence: PlannedActivityOccurrence?,
    val moduleEnabled: Boolean,
    val baseTargetMmol: Double,
    val minTargetMmol: Double,
    val maxTargetMmol: Double,
    val sameCycleAdaptiveCandidateMmol: Double? = null,
    val sameCycleAdaptiveCandidateFingerprint: String? = null,
    val safePredictedFall: ActivitySafePredictedFall? = null,
    val personalRiseEvidence: ActivityPersonalRiseEvidence? = null,
    val replayEvidence: ActivityReplayTargetEvidence? = null
)

/**
 * Pure planned-activity proposal construction. This class cannot submit therapy commands.
 * Production does not currently have canonical replay evidence, so it can only emit RAISE.
 */
class ActivityTargetProposalFactory {

    fun create(input: ActivityTargetProposalInput): ActivityTargetProposal {
        val occurrence = input.occurrence
        if (occurrence == null) return hold(input, null, setOf(ActivityTargetBlocker.OUTSIDE_ACTIVITY_WINDOW))
        val validFrom = occurrence.evaluationStarts.toEpochMilli()
        val validUntil = occurrence.end.plus(ACTIVITY_GRACE).toEpochMilli()
        val evidenceHash = scheduleEvidenceHash(occurrence)
        if (!input.moduleEnabled) return hold(input, occurrence, setOf(ActivityTargetBlocker.MODULE_DISABLED), evidenceHash)
        if (input.now.toEpochMilli() !in validFrom until validUntil) {
            return hold(input, occurrence, setOf(ActivityTargetBlocker.OUTSIDE_ACTIVITY_WINDOW), evidenceHash)
        }
        if (!input.baseTargetMmol.isFinite() || !input.minTargetMmol.isFinite() ||
            !input.maxTargetMmol.isFinite() || input.minTargetMmol > input.maxTargetMmol
        ) {
            return hold(input, occurrence, setOf(ActivityTargetBlocker.INVALID_TARGET_BOUNDS), evidenceHash)
        }

        val candidate = input.sameCycleAdaptiveCandidateMmol
        if (candidate != null && candidate.isFinite() && candidate < input.baseTargetMmol) {
            val blockers = linkedSetOf<ActivityTargetBlocker>()
            if (input.personalRiseEvidence?.evidenceHash.isNullOrBlank()) blockers += ActivityTargetBlocker.MISSING_PERSONAL_EVIDENCE
            if (input.replayEvidence?.replayHash.isNullOrBlank()) blockers += ActivityTargetBlocker.MISSING_REPLAY_EVIDENCE
            if (input.sameCycleAdaptiveCandidateFingerprint.isNullOrBlank()) blockers += ActivityTargetBlocker.MISSING_SAME_CYCLE_CANDIDATE
            if (candidate !in input.minTargetMmol..input.maxTargetMmol) blockers += ActivityTargetBlocker.INVALID_TARGET_BOUNDS
            if (blockers.isNotEmpty()) {
                // A controller decrease does not cancel independent pre-activity protection.
                return raise(input, occurrence, validFrom, validUntil, evidenceHash)
            }
            return proposal(
                occurrence = occurrence,
                direction = ActivityTargetProposalDirection.LOWER,
                targetMmol = candidate,
                validFrom = validFrom,
                validUntil = validUntil,
                evidenceHash = evidenceHash,
                protectionEvidenceHash = null,
                sameCycleCandidateFingerprint = input.sameCycleAdaptiveCandidateFingerprint,
                personalEvidenceHash = input.personalRiseEvidence!!.evidenceHash,
                replayHash = input.replayEvidence!!.replayHash
            )
        }
        if (candidate == null &&
            (!input.personalRiseEvidence?.evidenceHash.isNullOrBlank() ||
                !input.replayEvidence?.replayHash.isNullOrBlank())
        ) {
            // Explicit lower evidence without the same-cycle controller candidate must not invent a decrement.
            return hold(input, occurrence, setOf(ActivityTargetBlocker.MISSING_SAME_CYCLE_CANDIDATE), evidenceHash)
        }
        return raise(input, occurrence, validFrom, validUntil, evidenceHash)
    }

    private fun raise(
        input: ActivityTargetProposalInput,
        occurrence: PlannedActivityOccurrence,
        validFrom: Long,
        validUntil: Long,
        evidenceHash: String
    ): ActivityTargetProposal {
        val target = AdaptiveTargetControllerRule.plannedActivityTargetMmol(
            intensity = occurrence.intensity,
            minTargetMmol = input.minTargetMmol,
            maxTargetMmol = input.maxTargetMmol
        )
        if (target <= input.baseTargetMmol) {
            return hold(input, occurrence, setOf(ActivityTargetBlocker.CANDIDATE_NOT_BELOW_BASE), evidenceHash)
        }
        val protection = input.safePredictedFall
        if (protection?.isSafePredictedFall() != true) {
            return hold(input, occurrence, setOf(ActivityTargetBlocker.MISSING_SAFE_PREDICTED_FALL), evidenceHash)
        }
        return proposal(
            occurrence = occurrence,
            direction = ActivityTargetProposalDirection.RAISE,
            targetMmol = target,
            validFrom = validFrom,
            validUntil = validUntil,
            evidenceHash = evidenceHash,
            protectionEvidenceHash = protection.evidenceHash,
            sameCycleCandidateFingerprint = null,
            personalEvidenceHash = null,
            replayHash = null
        )
    }

    fun asTargetProposal(activity: ActivityTargetProposal, generatedAt: Long): TargetProposal? {
        val target = activity.targetMmol ?: return null
        if (activity.direction == ActivityTargetProposalDirection.HOLD || activity.blockers.isNotEmpty()) return null
        return TargetProposal(
            sourceRuleId = SOURCE_RULE_ID,
            intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
            targetMmol = target,
            durationMinutes = DURATION_MINUTES,
            priority = PRIORITY,
            confidence = if (activity.direction == ActivityTargetProposalDirection.RAISE) 0.75 else 0.90,
            reasonCodes = listOf(
                "planned_activity",
                "direction=${activity.direction.name.lowercase()}",
                "occurrence=${activity.occurrenceId}",
                "revision=${activity.occurrenceRevision}"
            ),
            generatedAt = generatedAt,
            inputFingerprint = listOf(
                activity.occurrenceId,
                activity.occurrenceRevision,
                activity.direction,
                activity.targetMmol,
                activity.validFromMs,
                activity.validUntilMs,
                activity.evidenceHash,
                activity.protectionEvidenceHash.orEmpty(),
                activity.sameCycleCandidateFingerprint.orEmpty(),
                activity.personalEvidenceHash.orEmpty(),
                activity.replayHash.orEmpty()
            ).joinToString(":"),
            activityProposal = activity
        )
    }

    /**
     * A renewal deliberately consumes a newly materialized same-cycle proposal instead of
     * replaying the accepted forecast evidence. Schedule identity is stable; forecast evidence is not.
     */
    fun renewal(
        accepted: AcceptedTargetState?,
        freshProposal: TargetProposal?,
        nowTs: Long
    ): TargetProposal? {
        if (accepted?.intent != TargetIntent.PLANNED_ACTIVITY_ADAPTATION ||
            accepted.ownerRuleId != SOURCE_RULE_ID
        ) return null
        val acceptedActivity = accepted.activityProposal ?: return null
        val fresh = freshProposal ?: return null
        val activity = fresh.activityProposal ?: return null
        val freshTarget = activity.targetMmol ?: return null
        if (fresh.sourceRuleId != SOURCE_RULE_ID ||
            fresh.intent != TargetIntent.PLANNED_ACTIVITY_ADAPTATION ||
            fresh.generatedAt != nowTs ||
            acceptedActivity.direction != ActivityTargetProposalDirection.RAISE ||
            activity.direction != ActivityTargetProposalDirection.RAISE ||
            activity.protectionEvidenceHash.isNullOrBlank() ||
            activity.occurrenceId != acceptedActivity.occurrenceId ||
            activity.occurrenceRevision != acceptedActivity.occurrenceRevision ||
            activity.intensity != acceptedActivity.intensity ||
            activity.validFromMs != acceptedActivity.validFromMs ||
            activity.validUntilMs != acceptedActivity.validUntilMs ||
            activity.evidenceHash != acceptedActivity.evidenceHash ||
            freshTarget.toRawBits() != accepted.targetMmol.toRawBits() ||
            fresh.targetMmol.toRawBits() != freshTarget.toRawBits()
        ) return null
        if (nowTs !in activity.validFromMs until activity.validUntilMs) return null
        return TargetProposal(
            sourceRuleId = TargetManager.RENEWAL_SOURCE_RULE_ID,
            intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
            targetMmol = accepted.targetMmol,
            durationMinutes = accepted.durationMinutes,
            priority = RENEWAL_PRIORITY,
            confidence = fresh.confidence,
            reasonCodes = listOf("planned_activity_keepalive", "owner=${accepted.ownerRuleId}"),
            generatedAt = nowTs,
            inputFingerprint = "planned-activity-keepalive:${accepted.revision}:${accepted.lastCommandId}:" +
                "${activity.occurrenceId}:${activity.occurrenceRevision}:${activity.protectionEvidenceHash}",
            activityProposal = activity
        )
    }

    private fun hold(
        input: ActivityTargetProposalInput,
        occurrence: PlannedActivityOccurrence?,
        blockers: Set<ActivityTargetBlocker>,
        evidenceHash: String = occurrence?.let(::scheduleEvidenceHash).orEmpty()
    ): ActivityTargetProposal = ActivityTargetProposal(
        occurrenceId = occurrence?.eventId.orEmpty(),
        occurrenceRevision = occurrence?.revision ?: -1L,
        intensity = occurrence?.intensity ?: PlannedActivityIntensity.LIGHT,
        direction = ActivityTargetProposalDirection.HOLD,
        targetMmol = null,
        validFromMs = occurrence?.evaluationStarts?.toEpochMilli() ?: input.now.toEpochMilli(),
        validUntilMs = occurrence?.end?.plus(ACTIVITY_GRACE)?.toEpochMilli() ?: input.now.toEpochMilli(),
        evidenceHash = evidenceHash,
        protectionEvidenceHash = null,
        sameCycleCandidateFingerprint = null,
        personalEvidenceHash = null,
        replayHash = null,
        blockers = blockers
    )

    private fun proposal(
        occurrence: PlannedActivityOccurrence,
        direction: ActivityTargetProposalDirection,
        targetMmol: Double,
        validFrom: Long,
        validUntil: Long,
        evidenceHash: String,
        protectionEvidenceHash: String?,
        sameCycleCandidateFingerprint: String?,
        personalEvidenceHash: String?,
        replayHash: String?
    ) = ActivityTargetProposal(
        occurrenceId = occurrence.eventId,
        occurrenceRevision = occurrence.revision,
        intensity = occurrence.intensity,
        direction = direction,
        targetMmol = targetMmol,
        validFromMs = validFrom,
        validUntilMs = validUntil,
        evidenceHash = evidenceHash,
        protectionEvidenceHash = protectionEvidenceHash,
        sameCycleCandidateFingerprint = sameCycleCandidateFingerprint,
        personalEvidenceHash = personalEvidenceHash,
        replayHash = replayHash,
        blockers = emptySet()
    )

    private fun scheduleEvidenceHash(occurrence: PlannedActivityOccurrence): String = listOf(
        occurrence.eventId,
        occurrence.revision,
        occurrence.intensity.name,
        occurrence.start.toEpochMilli(),
        occurrence.end.toEpochMilli(),
        occurrence.evaluationStarts.toEpochMilli()
    ).joinToString(":")

    companion object {
        const val SOURCE_RULE_ID = "PlannedActivityTarget.v1"
        const val DURATION_MINUTES = 30
        const val PRIORITY = 350
        private const val RENEWAL_PRIORITY = -10_000
        val ACTIVITY_GRACE: java.time.Duration = java.time.Duration.ofMinutes(15)
    }
}
