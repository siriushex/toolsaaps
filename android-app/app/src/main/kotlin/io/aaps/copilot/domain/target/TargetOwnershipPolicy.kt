package io.aaps.copilot.domain.target

object TargetOwnershipPolicy {
    const val TARGET_OBSERVATION_MISSING = "target_observation_missing"
    const val TARGET_OBSERVATION_INVALID = "target_observation_invalid"
    const val ACTIVE_TARGET_OBSERVATION_CHANGED = "active_target_observation_changed"
    const val COPILOT_PRIORITY_POLICY_CHANGED = "copilot_priority_policy_changed"
    const val COPILOT_PRIORITY_REVISION_CHANGED = "copilot_priority_revision_changed"
    const val TARGET_OBSERVATION_EXPIRED = "target_observation_expired"
    const val EXTERNAL_TARGET_WRITER_CONFLICT = "external_target_writer_conflict"

    fun capture(
        activeAapsTarget: ActiveAapsTarget?,
        copilotPriorityEnabled: Boolean,
        priorityRevision: Long
    ): TargetCommandObservation = TargetCommandObservation(
        activeAapsTarget = activeAapsTarget,
        copilotPriorityEnabled = copilotPriorityEnabled,
        priorityRevision = priorityRevision
    )

    fun preflightFailure(
        candidate: TargetCommandCandidate,
        currentObservation: TargetCommandObservation,
        nowTs: Long
    ): String? {
        val expected = candidate.targetObservation ?: return TARGET_OBSERVATION_MISSING
        return try {
            when {
                expected.priorityRevision < 0L || currentObservation.priorityRevision < 0L ->
                    TARGET_OBSERVATION_INVALID
                !isValidObservation(expected, nowTs) || !isValidObservation(currentObservation, nowTs) ->
                    TARGET_OBSERVATION_INVALID
                expected.copilotPriorityEnabled != currentObservation.copilotPriorityEnabled ->
                    COPILOT_PRIORITY_POLICY_CHANGED
                expected.priorityRevision != currentObservation.priorityRevision ->
                    COPILOT_PRIORITY_REVISION_CHANGED
                !sameActiveTarget(expected.activeAapsTarget, currentObservation.activeAapsTarget) ->
                    ACTIVE_TARGET_OBSERVATION_CHANGED
                currentObservation.activeAapsTarget?.expiresAt?.let { it <= nowTs } == true ->
                    TARGET_OBSERVATION_EXPIRED
                else -> null
            }
        } catch (_: RuntimeException) {
            TARGET_OBSERVATION_INVALID
        }
    }

    internal fun evaluationFailure(
        input: TargetManagerInput,
        activeAapsTarget: ActiveAapsTarget?
    ): TargetOwnershipFailure? {
        val active = activeAapsTarget ?: return null
        if (!active.evidenceResolved) {
            return TargetOwnershipFailure(
                TargetDecisionOutcome.BLOCK_MANUAL_TARGET,
                "active_target_evidence_unresolved"
            )
        }
        if (!hasCompleteOwnershipShape(active)) {
            return TargetOwnershipFailure(
                TargetDecisionOutcome.BLOCK_MANUAL_TARGET,
                "active_target_evidence_invalid"
            )
        }
        if (active.ownership == ActiveTargetOwnership.TARGET_MANAGER) return null
        if (!isValidExternalTargetEvidence(input, active)) {
            return TargetOwnershipFailure(
                TargetDecisionOutcome.BLOCK_MANUAL_TARGET,
                "active_target_evidence_invalid"
            )
        }
        if (!input.copilotPriorityEnabled) {
            return when {
                active.ownership == ActiveTargetOwnership.LEGACY_COPILOT &&
                    input.mode == TargetManagerMode.ACTIVE -> TargetOwnershipFailure(
                    TargetDecisionOutcome.BLOCK_LEGACY_TARGET_DRAIN,
                    "legacy_copilot_target_drain"
                )
                active.ownership in setOf(
                    ActiveTargetOwnership.MANUAL_OR_FOREIGN,
                    ActiveTargetOwnership.UNKNOWN
                ) -> TargetOwnershipFailure(
                    TargetDecisionOutcome.BLOCK_MANUAL_TARGET,
                    "manual_or_foreign_target_active"
                )
                else -> null
            }
        }
        if (!EatingSoonPolicy.isActiveConfirmedTarget(active, input.nowTs) && hasRecentExternalWriterConflict(input, active)) {
            return TargetOwnershipFailure(
                TargetDecisionOutcome.BLOCK_MANUAL_TARGET,
                EXTERNAL_TARGET_WRITER_CONFLICT
            )
        }
        return null
    }

    internal fun isPriorityTakeover(
        input: TargetManagerInput,
        activeAapsTarget: ActiveAapsTarget?
    ): Boolean = input.copilotPriorityEnabled &&
        activeAapsTarget?.ownership != null &&
        activeAapsTarget.ownership != ActiveTargetOwnership.TARGET_MANAGER

    private fun hasCompleteOwnershipShape(active: ActiveAapsTarget): Boolean {
        val source: String? = active.source
        val ownership: ActiveTargetOwnership? = active.ownership
        return !source.isNullOrBlank() && ownership != null
    }

    private fun isValidExternalTargetEvidence(
        input: TargetManagerInput,
        active: ActiveAapsTarget
    ): Boolean {
        return active.targetMmol.isFinite() &&
            active.targetMmol in input.safety.minTargetMmol..input.safety.maxTargetMmol &&
            active.startedAt > 0L &&
            active.startedAt <= input.nowTs &&
            active.expiresAt > input.nowTs &&
            active.startedAt < active.expiresAt &&
            active.idempotencyKey?.isBlank() != true
    }

    private fun hasRecentExternalWriterConflict(
        input: TargetManagerInput,
        active: ActiveAapsTarget
    ): Boolean {
        val accepted = input.runtimeState.acceptedTarget ?: return false
        if (accepted.lastCommandStatus != CONFIRMED_COMMAND_STATUS || accepted.lastCommandId.isNullOrBlank()) {
            return false
        }
        val elapsed = try {
            Math.subtractExact(input.nowTs, accepted.acceptedAt)
        } catch (_: ArithmeticException) {
            return true
        }
        if (elapsed !in 0L..EXTERNAL_WRITER_CONFLICT_WINDOW_MS) return false
        if (active.startedAt < accepted.acceptedAt) return false
        return !matchesAcceptedCommand(active, accepted)
    }

    private fun matchesAcceptedCommand(
        active: ActiveAapsTarget,
        accepted: AcceptedTargetState
    ): Boolean {
        if (active.targetMmol.toRawBits() != accepted.targetMmol.toRawBits()) return false
        return active.idempotencyKey?.takeIf(String::isNotBlank) == accepted.lastCommandId
    }

    private fun isValidObservation(
        observation: TargetCommandObservation,
        nowTs: Long
    ): Boolean {
        val active = observation.activeAapsTarget ?: return true
        return active.evidenceResolved &&
            active.targetMmol.isFinite() &&
            active.targetMmol > 0.0 &&
            active.startedAt > 0L &&
            active.startedAt <= nowTs &&
            active.expiresAt > active.startedAt &&
            active.source.isNotBlank() &&
            active.ownership.name.isNotBlank() &&
            active.idempotencyKey?.isBlank() != true &&
            (!active.eatingSoonConfirmed || EatingSoonPolicy.isActiveConfirmedTarget(active, nowTs))
    }

    private fun sameActiveTarget(
        left: ActiveAapsTarget?,
        right: ActiveAapsTarget?
    ): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.targetMmol.toRawBits() == right.targetMmol.toRawBits() &&
            left.startedAt == right.startedAt &&
            left.expiresAt == right.expiresAt &&
            left.source == right.source &&
            left.ownership == right.ownership &&
            left.idempotencyKey == right.idempotencyKey &&
            left.evidenceResolved == right.evidenceResolved &&
            left.eatingSoonConfirmed == right.eatingSoonConfirmed
    }

    internal data class TargetOwnershipFailure(
        val outcome: TargetDecisionOutcome,
        val reason: String
    )

    private const val CONFIRMED_COMMAND_STATUS = "sent"
    private const val EXTERNAL_WRITER_CONFLICT_WINDOW_MS = 10 * 60_000L
}
