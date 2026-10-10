package io.aaps.copilot.domain.target

import io.aaps.copilot.domain.profile.ActivityTargetProposalDirection
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.AdaptiveTempTargetController
import io.aaps.copilot.domain.rules.SustainedRiseTargetPolicy
import io.aaps.copilot.util.UnitConverter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

class TargetManager(
    private val cadencePolicy: TargetCadencePolicy = TargetCadencePolicy()
) {

    fun decide(input: TargetManagerInput): TargetManagerDecision {
        require(input.runtimeState.mode == input.mode) { "runtime mode must match input mode" }
        require(input.runtimeState.acceptedTarget?.revision?.let { it >= 0L } != false) {
            "accepted target revision must be nonnegative"
        }
        val runtime = input.runtimeState

        if (input.mode == TargetManagerMode.OFF) {
            return blocked(input, runtime, TargetDecisionOutcome.NO_PROPOSAL, "manager_off")
        }
        if (input.safety.killSwitch) {
            return blocked(input, runtime, TargetDecisionOutcome.BLOCK_KILL_SWITCH, "kill_switch")
        }
        if (!input.safety.dataFresh) {
            return blocked(input, runtime, TargetDecisionOutcome.BLOCK_STALE_DATA, "stale_data")
        }
        if (!input.safety.localChronologyResolved) {
            return blocked(
                input,
                runtime,
                TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
                "local_safety_chronology_unresolved"
            )
        }
        invalidSafetyContextReason(input.safety)?.let { reason ->
            return blocked(input, runtime, TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS, reason)
        }
        if (input.priorityRevision < 0L) {
            return blocked(
                input,
                runtime,
                TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
                "invalid_priority_revision"
            )
        }

        val active = input.activeAapsTarget?.takeIf { it.expiresAt > input.nowTs }
        TargetOwnershipPolicy.evaluationFailure(input, active)?.let { failure ->
            return blocked(
                input,
                runtime,
                failure.outcome,
                failure.reason
            )
        }

        val activeAnchor = active
            ?.takeUnless {
                input.mode == TargetManagerMode.SHADOW &&
                    it.ownership == ActiveTargetOwnership.LEGACY_COPILOT
            }
            ?.targetMmol
        val acceptedAnchor = runtime.acceptedTarget
            ?.takeIf { it.expiresAt > input.nowTs }
            ?.targetMmol
        val anchor = activeAnchor
            ?: acceptedAnchor
            ?: input.safety.baseTargetMmol
        if (!anchor.isFinite() || anchor > input.safety.maxTargetMmol) {
            return blocked(
                input,
                runtime,
                TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
                "invalid_control_anchor"
            )
        }
        val priorityTakeover = TargetOwnershipPolicy.isPriorityTakeover(input, active)
        val evaluations = input.proposals.map {
            evaluateCandidate(input, it, anchor, priorityTakeover)
        }
        val rejected = rejectedProposalReasons(evaluations)
        val winner = evaluations
            .filter(CandidateEvaluation::eligible)
            .map(CandidateEvaluation::proposal)
            .sortedWith(proposalComparator())
            .firstOrNull()
            ?: return blocked(
                input = input,
                runtime = runtime,
                outcome = dominantBlockedOutcome(evaluations),
                reason = dominantBlockedReason(evaluations),
                rejected = rejected
            )

        val semanticFingerprint = semanticFingerprint(input, active, winner)
        if (runtime.lastDecisionFingerprint == semanticFingerprint) {
            return blocked(
                input = input,
                runtime = runtime,
                outcome = TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE,
                reason = "semantic_event_already_evaluated",
                winner = winner,
                semanticFingerprint = semanticFingerprint,
                rejected = rejected
            )
        }

        val ordinaryCadence = cadencePolicy.decide(
            TargetCadenceRequest(
                nowTs = input.nowTs,
                targetMmol = winner.targetMmol,
                intent = winner.intent,
                manual = false,
                lastAutomaticSent = input.lastAutomaticSent
            )
        )
        val releaseReason = if (!ordinaryCadence.allowed && ordinaryCadence.reason == "duplicate_target_within_window") {
            when {
                isConfirmedSustainedRiseRelease(input, active, winner) -> "sustained_rise_upward_release"
                isForecastConfirmedTrendRelease(input, active, winner) -> "forecast_confirmed_trend_release"
                else -> null
            }
        } else null
        val cadence = releaseReason?.let {
            TargetCadenceDecision(true, TempTargetCadenceOutcome.ALLOW_EPISODE_RELEASE, it)
        } ?: ordinaryCadence
        val evaluatedRuntime = runtime.copy(lastDecisionFingerprint = semanticFingerprint)
        if (!cadence.allowed) {
            return blocked(
                input = input,
                runtime = evaluatedRuntime,
                outcome = TargetDecisionOutcome.BLOCK_CADENCE,
                reason = cadence.reason,
                winner = winner,
                semanticFingerprint = semanticFingerprint,
                cadence = cadence,
                rejected = rejected
            )
        }

        val renewal = winner.sourceRuleId == RENEWAL_SOURCE_RULE_ID
        val ownerRuleId = if (renewal) {
            checkNotNull(runtime.acceptedTarget) { "validated keepalive requires accepted target" }.ownerRuleId
        } else {
            winner.sourceRuleId
        }

        if (input.mode == TargetManagerMode.SHADOW) {
            val virtualAccepted = acceptedState(
                runtime = runtime,
                winner = winner,
                input = input,
                ownerRuleId = ownerRuleId,
                commandId = "shadow:$semanticFingerprint",
                commandStatus = "shadow"
            )
            return TargetManagerDecision(
                outcome = TargetDecisionOutcome.SHADOW_WOULD_SEND,
                winner = winner,
                command = null,
                semanticFingerprint = semanticFingerprint,
                cadenceOutcome = cadence.outcome,
                cadenceReason = cadence.reason,
                reasonCodes = listOf("shadow_would_send"),
                rejectedProposalReasons = rejected,
                nextRuntimeState = evaluatedRuntime.copy(acceptedTarget = virtualAccepted)
            )
        }

        val idempotencyKey = "TargetManager.v1:$semanticFingerprint"
        val accepted = acceptedState(
            runtime = runtime,
            winner = winner,
            input = input,
            ownerRuleId = ownerRuleId,
            commandId = idempotencyKey,
            commandStatus = "pending"
        )
        val command = TargetCommandCandidate(
            targetMmol = winner.targetMmol,
            durationMinutes = winner.durationMinutes,
            ownerRuleId = ownerRuleId,
            intent = winner.intent,
            reason = winner.reasonCodes.joinToString("|").ifBlank { winner.sourceRuleId },
            idempotencyKey = idempotencyKey,
            semanticFingerprint = semanticFingerprint,
            baseProvenance = input.baseProvenance,
            generatedAt = input.nowTs,
            targetObservation = TargetOwnershipPolicy.capture(
                activeAapsTarget = active,
                copilotPriorityEnabled = input.copilotPriorityEnabled,
                priorityRevision = input.priorityRevision
            ),
            sensitivityCycleId = input.sensitivityRuntime.snapshot.forecastCycleId
        )
        return TargetManagerDecision(
            outcome = if (renewal) TargetDecisionOutcome.RENEW_SAME_TARGET else TargetDecisionOutcome.SEND,
            winner = winner,
            command = command,
            semanticFingerprint = semanticFingerprint,
            cadenceOutcome = cadence.outcome,
            cadenceReason = cadence.reason,
            reasonCodes = listOf("eligible"),
            rejectedProposalReasons = rejected,
            nextRuntimeState = evaluatedRuntime.copy(acceptedTarget = accepted)
        )
    }

    private fun evaluateCandidate(
        input: TargetManagerInput,
        proposal: TargetProposal,
        anchor: Double,
        priorityTakeover: Boolean
    ): CandidateEvaluation {
        if (proposal.sourceRuleId == RENEWAL_SOURCE_RULE_ID && !isValidKeepalive(input, proposal)) {
            return CandidateEvaluation(proposal, false, "invalid_keepalive")
        }
        if (
            !proposal.targetMmol.isFinite() ||
            !proposal.confidence.isFinite() ||
            proposal.confidence !in 0.0..1.0 ||
            proposal.inputFingerprint.isBlank() ||
            proposal.sourceRuleId.isBlank() ||
            proposal.targetMmol !in input.safety.minTargetMmol..input.safety.maxTargetMmol ||
            proposal.durationMinutes !in input.safety.minDurationMinutes..input.safety.maxDurationMinutes
        ) {
            return CandidateEvaluation(proposal, false, "safety_bounds")
        }
        if (
            isBelowBaseWithoutQualifiedIob(input, proposal.targetMmol) ||
            priorityTakeover && proposal.targetMmol < anchor && !hasQualifiedSafetyIob(input)
        ) {
            return CandidateEvaluation(proposal, false, "safety_iob_blocks_target_decrease")
        }
        EatingSoonPolicy.replacementFailure(input.activeAapsTarget, input.nowTs, proposal.intent, proposal.targetMmol)
            ?.let { return CandidateEvaluation(proposal, false, it) }
        val sustainedRise = proposal.sourceRuleId == SustainedRiseTargetPolicy.SOURCE
        if (sustainedRise && (proposal.generatedAt != input.nowTs ||
                abs(proposal.targetMmol - SustainedRiseTargetPolicy.TARGET_MMOL) > EPSILON ||
                proposal.durationMinutes != SustainedRiseTargetPolicy.DURATION_MINUTES ||
                proposal.intent != TargetIntent.NORMAL_CONTROL ||
                input.safety.currentGlucoseMmol?.let { it.isFinite() && it > 8.5 } != true ||
                input.safety.deliveryTrust != DeliveryTrustState.NORMAL)) {
            return CandidateEvaluation(proposal, false, "invalid_sustained_rise_context")
        }
        // Continuing this low target is a fresh risk decision, not a neutral hold.
        val isDecrease = proposal.targetMmol < anchor || sustainedRise
        if (proposal.intent == TargetIntent.PLANNED_ACTIVITY_ADAPTATION) {
            validatePlannedActivityProposal(input, proposal)?.let { reason ->
                return CandidateEvaluation(proposal, false, reason)
            }
        }
        if (proposal.sourceRuleId == TargetProposalFactory.PLANNED_ACTIVITY_RETURN_SOURCE) {
            validatePlannedActivityReturn(input, proposal)?.let { reason ->
                return CandidateEvaluation(proposal, false, reason)
            }
        }

        val delta = proposal.targetMmol - anchor
        if (
            input.safety.deliveryTrust == DeliveryTrustState.SUSPECTED_NONRESPONSE &&
            isDecrease
        ) {
            return CandidateEvaluation(
                proposal,
                false,
                "delivery_nonresponse_blocks_target_decrease"
            )
        }

        when (input.safety.sensorTrust) {
            SensorTrustState.BLOCKED,
            SensorTrustState.RESTRICTED -> {
                val isSafeRelease = proposal.intent == TargetIntent.SENSOR_SAFETY_RELEASE &&
                    anchor < input.safety.baseTargetMmol - EPSILON &&
                    proposal.targetMmol > anchor + EPSILON &&
                    proposal.targetMmol <= input.safety.baseTargetMmol + EPSILON
                if (!isSafeRelease) {
                    return CandidateEvaluation(proposal, false, "sensor_trust_blocks_proposal")
                }
            }

            SensorTrustState.WARN -> if (isDecrease) {
                return CandidateEvaluation(proposal, false, "sensor_warn_blocks_target_decrease")
            }

            SensorTrustState.TRUSTED -> Unit
        }

        if (proposal.intent.isProtective() && isDecrease) {
            return CandidateEvaluation(proposal, false, "protective_target_cannot_lower_anchor")
        }

        if (isDecrease) {
            val currentGlucose = input.safety.currentGlucoseMmol
            val minimumPredictedOrCi = input.safety.minimumPredictedOrCiMmol
            if (
                currentGlucose == null ||
                !currentGlucose.isFinite() ||
                minimumPredictedOrCi == null ||
                !minimumPredictedOrCi.isFinite() ||
                !input.safety.lowRiskThresholdMmol.isFinite()
            ) {
                return CandidateEvaluation(
                    proposal,
                    false,
                    "invalid_glucose_context_blocks_target_decrease"
                )
            }
            val requiredHorizons = if (priorityTakeover) {
                PRIORITY_DECREASE_HORIZONS
            } else {
                REQUIRED_DECREASE_HORIZONS
            }
            val forecastReliable = requiredHorizons.all { horizon ->
                input.reliability[horizon]?.state == HorizonReliabilityState.RELIABLE
            }
            if (!forecastReliable) {
                return CandidateEvaluation(proposal, false, "forecast_reliability_blocks_target_decrease")
            }
            val lowGlucose = currentGlucose <= input.safety.lowRiskThresholdMmol ||
                minimumPredictedOrCi <= input.safety.lowRiskThresholdMmol
            if (lowGlucose) {
                return CandidateEvaluation(proposal, false, "low_risk_blocks_target_decrease")
            }
        }

        return CandidateEvaluation(proposal, true, null)
    }

    private fun validatePlannedActivityProposal(
        input: TargetManagerInput,
        proposal: TargetProposal
    ): String? {
        val activity = proposal.activityProposal ?: return "activity_proposal_missing"
        val safety = input.activitySafety
        if (!safety.moduleEnabled) return "activity_module_disabled"
        if (activity.direction == ActivityTargetProposalDirection.HOLD || activity.targetMmol == null || activity.blockers.isNotEmpty()) {
            return "activity_hold_or_blocked"
        }
        if (proposal.durationMinutes != ACTIVITY_DURATION_MINUTES) return "activity_duration_invalid"
        if (activity.occurrenceId.isBlank() || activity.occurrenceRevision < 0L ||
            activity.validFromMs >= activity.validUntilMs ||
            input.nowTs !in activity.validFromMs until activity.validUntilMs
        ) return "activity_occurrence_window_invalid"
        if (activity.occurrenceId != safety.occurrenceId ||
            activity.occurrenceRevision != safety.occurrenceRevision ||
            activity.validFromMs != safety.validFromMs ||
            activity.validUntilMs != safety.validUntilMs ||
            activity.evidenceHash.isBlank() || activity.evidenceHash != safety.evidenceHash
        ) return "activity_occurrence_evidence_mismatch"
        if (input.safety.sensorTrust != SensorTrustState.TRUSTED) return "activity_sensor_trust_invalid"
        val currentGlucose = input.safety.currentGlucoseMmol
        val observedDelta = safety.observedDelta5Mmol
        if (currentGlucose == null || !currentGlucose.isFinite() ||
            observedDelta == null || !observedDelta.isFinite()
        ) {
            return "activity_glucose_or_trend_missing"
        }
        val reliableForecasts = ACTIVITY_REQUIRED_HORIZONS.all { horizon ->
            input.reliability[horizon]?.state == HorizonReliabilityState.RELIABLE &&
                safety.forecasts[horizon]?.let { forecast ->
                    forecast.horizonMinutes == horizon && forecast.valueMmol.isFinite() &&
                        forecast.ciLowMmol.isFinite() && forecast.ciHighMmol.isFinite() &&
                        forecast.ciLowMmol <= forecast.ciHighMmol &&
                        forecast.ciLowMmol > input.safety.lowRiskThresholdMmol
                } == true
        }
        if (!reliableForecasts) return "activity_forecast_or_ci_unreliable"
        if (input.safety.safetyIobUnits?.takeIf(Double::isFinite)?.let { it <= ACTIVITY_MAX_IOB_UNITS } != true ||
            safety.cobGrams?.takeIf(Double::isFinite)?.let { it <= ACTIVITY_MAX_COB_GRAMS } != true ||
            safety.uamActive
        ) return "activity_therapy_risk"
        val target = activity.targetMmol
        if (target.toRawBits() != proposal.targetMmol.toRawBits()) return "activity_target_mismatch"
        return when (activity.direction) {
            ActivityTargetProposalDirection.RAISE -> {
                val mapped = AdaptiveTargetControllerRule.plannedActivityTargetMmol(
                    intensity = activity.intensity,
                    minTargetMmol = input.safety.minTargetMmol,
                    maxTargetMmol = input.safety.maxTargetMmol
                )
                if (target.toRawBits() != mapped.toRawBits() || target < input.safety.baseTargetMmol) {
                    "activity_raise_not_mapped_or_protective"
                } else if (
                    activity.protectionEvidenceHash.isNullOrBlank() ||
                    activity.protectionEvidenceHash != safety.safePredictedFall?.evidenceHash ||
                    !safePredictedFallMatchesControl(input, safety, currentGlucose, observedDelta)
                ) {
                    "activity_raise_safe_predicted_fall_missing"
                } else {
                    null
                }
            }

            ActivityTargetProposalDirection.LOWER -> {
                val forecast5 = checkNotNull(safety.forecasts[5])
                val forecast30 = checkNotNull(safety.forecasts[30])
                val forecast60 = checkNotNull(safety.forecasts[60])
                val concordantRise = observedDelta > 0.0 &&
                    forecast5.valueMmol > currentGlucose &&
                    forecast30.valueMmol > forecast5.valueMmol &&
                    forecast60.valueMmol > forecast30.valueMmol
                if (!concordantRise) return "activity_lower_trend_or_rise_not_concordant"
                val sameCycleCandidate = safety.sameCycleAdaptiveCandidateMmol
                if (sameCycleCandidate == null ||
                    !sameCycleCandidate.isFinite() ||
                    !sameSafetyDouble(sameCycleCandidate, target) ||
                    !sameSafetyDouble(sameCycleCandidate, proposal.targetMmol)
                ) {
                    return "activity_lower_candidate_value_mismatch"
                }
                if (target >= input.safety.baseTargetMmol ||
                    activity.sameCycleCandidateFingerprint.isNullOrBlank() ||
                    activity.sameCycleCandidateFingerprint != safety.sameCycleCandidateFingerprint ||
                    activity.personalEvidenceHash.isNullOrBlank() ||
                    activity.personalEvidenceHash != safety.personalEvidenceHash ||
                    activity.replayHash.isNullOrBlank() ||
                    activity.replayHash != safety.replayHash
                ) {
                    "activity_lower_candidate_personal_or_replay_invalid"
                } else {
                    null
                }
            }

            ActivityTargetProposalDirection.HOLD -> "activity_hold_or_blocked"
        }
    }

    private fun safePredictedFallMatchesControl(
        input: TargetManagerInput,
        safety: ActivityTargetSafetyContext,
        currentGlucose: Double,
        observedDelta: Double
    ): Boolean {
        val protection = safety.safePredictedFall ?: return false
        val forecast5 = safety.forecasts[5] ?: return false
        val forecast30 = safety.forecasts[30] ?: return false
        val forecast60 = safety.forecasts[60] ?: return false
        return protection.isSafePredictedFall() &&
            sameSafetyDouble(protection.lowRiskThresholdMmol, input.safety.lowRiskThresholdMmol) &&
            sameSafetyDouble(protection.currentGlucoseMmol, currentGlucose) &&
            sameSafetyDouble(protection.observedDelta5Mmol, observedDelta) &&
            sameSafetyDouble(protection.pred5Mmol, forecast5.valueMmol) &&
            sameSafetyDouble(protection.pred30Mmol, forecast30.valueMmol) &&
            sameSafetyDouble(protection.pred60Mmol, forecast60.valueMmol) &&
            sameSafetyDouble(protection.ciLow5Mmol, forecast5.ciLowMmol) &&
            sameSafetyDouble(protection.ciLow30Mmol, forecast30.ciLowMmol) &&
            sameSafetyDouble(protection.ciLow60Mmol, forecast60.ciLowMmol)
    }

    private fun sameSafetyDouble(left: Double, right: Double): Boolean =
        left.isFinite() && right.isFinite() && abs(left - right) <= ACTIVITY_EVIDENCE_EPSILON

    private fun validatePlannedActivityReturn(
        input: TargetManagerInput,
        proposal: TargetProposal
    ): String? {
        val accepted = input.runtimeState.acceptedTarget
        if (!input.activitySafety.returnToBaseRequested || input.activitySafety.keepaliveAllowed) {
            return "activity_return_not_requested"
        }
        if (accepted?.intent != TargetIntent.PLANNED_ACTIVITY_ADAPTATION ||
            accepted.ownerRuleId != TargetProposalFactory.PLANNED_ACTIVITY_SOURCE
        ) return "activity_return_ownership_invalid"
        if (proposal.intent != TargetIntent.RECOVERY_TO_BASE ||
            proposal.durationMinutes != ACTIVITY_DURATION_MINUTES ||
            proposal.targetMmol.toRawBits() != input.safety.baseTargetMmol.toRawBits()
        ) return "activity_return_payload_invalid"
        return null
    }

    private fun invalidSafetyContextReason(safety: TargetManagerSafetyContext): String? = when {
        !safety.minTargetMmol.isFinite() ||
            !safety.maxTargetMmol.isFinite() ||
            safety.minTargetMmol <= 0.0 ||
            safety.maxTargetMmol < safety.minTargetMmol -> "invalid_target_bounds"
        safety.minDurationMinutes <= 0 ||
            safety.maxDurationMinutes < safety.minDurationMinutes -> "invalid_duration_bounds"
        !safety.baseTargetMmol.isFinite() ||
            safety.baseTargetMmol !in safety.minTargetMmol..safety.maxTargetMmol -> "invalid_base_target"
        !safety.lowRiskThresholdMmol.isFinite() ||
            safety.lowRiskThresholdMmol <= 0.0 -> "invalid_low_risk_threshold"
        else -> null
    }

    private fun isValidKeepalive(
        input: TargetManagerInput,
        proposal: TargetProposal
    ): Boolean {
        val accepted = input.runtimeState.acceptedTarget ?: return false
        if (accepted.ownerRuleId == SustainedRiseTargetPolicy.SOURCE && input.proposals.none { candidate ->
                candidate.sourceRuleId == SustainedRiseTargetPolicy.SOURCE &&
                    candidate.generatedAt == input.nowTs &&
                    abs(candidate.targetMmol - accepted.targetMmol) < EPSILON &&
                    evaluateCandidate(input, candidate, accepted.targetMmol, priorityTakeover = false).eligible
            }) return false
        if (accepted.ownerRuleId == TargetProposalFactory.PLANNED_ACTIVITY_RETURN_SOURCE) return false
        if (accepted.intent == TargetIntent.HYPO_PROTECTION && !hasCurrentHypoProtection(input, accepted)) {
            return false
        }
        if (accepted.intent == TargetIntent.PLANNED_ACTIVITY_ADAPTATION &&
            !input.activitySafety.keepaliveAllowed
        ) return false
        if (input.safety.sensorTrust != SensorTrustState.TRUSTED) return false
        if (isBelowBaseWithoutQualifiedIob(input, proposal.targetMmol)) return false
        val requiredStatus = when (input.mode) {
            TargetManagerMode.ACTIVE -> "sent"
            TargetManagerMode.SHADOW -> "shadow"
            TargetManagerMode.OFF -> return false
        }
        if (accepted.lastCommandStatus != requiredStatus) return false
        val remainingMs = try {
            Math.subtractExact(accepted.expiresAt, input.nowTs)
        } catch (_: ArithmeticException) {
            return false
        }
        if (remainingMs !in -KEEPALIVE_GRACE_MS..KEEPALIVE_WINDOW_MS) return false
        if (accepted.intent == TargetIntent.PLANNED_ACTIVITY_ADAPTATION &&
            !isValidPlannedActivityRenewal(input, accepted, proposal)
        ) return false
        return accepted.targetMmol.toRawBits() == proposal.targetMmol.toRawBits() &&
            accepted.durationMinutes == proposal.durationMinutes &&
            accepted.intent == proposal.intent
    }

    private fun hasCurrentHypoProtection(input: TargetManagerInput, accepted: AcceptedTargetState): Boolean {
        val safety = input.safety
        val currentLow = safety.currentGlucoseMmol?.let {
            it.isFinite() && it <= safety.lowRiskThresholdMmol
        } == true
        val predictedLow = safety.minimumPredictedOrCiMmol?.let {
            it.isFinite() && it <= safety.lowRiskThresholdMmol
        } == true
        if (currentLow || predictedLow) return true

        // A completed low-risk episode must not sustain its old target through renewal alone.
        return input.proposals.any { candidate ->
            candidate.sourceRuleId != RENEWAL_SOURCE_RULE_ID &&
                candidate.sourceRuleId == accepted.ownerRuleId &&
                candidate.intent == TargetIntent.HYPO_PROTECTION &&
                candidate.generatedAt == input.nowTs &&
                candidate.targetMmol.toRawBits() == accepted.targetMmol.toRawBits() &&
                candidate.durationMinutes == accepted.durationMinutes &&
                evaluateCandidate(input, candidate, accepted.targetMmol, priorityTakeover = false).eligible
        }
    }

    private fun isConfirmedSustainedRiseRelease(
        input: TargetManagerInput,
        active: ActiveAapsTarget?,
        winner: TargetProposal
    ): Boolean {
        val accepted = input.runtimeState.acceptedTarget ?: return false
        val last = input.lastAutomaticSent ?: return false
        return accepted.ownerRuleId == SustainedRiseTargetPolicy.SOURCE &&
            accepted.lastCommandStatus == "sent" && accepted.lastCommandId != null &&
            active?.ownership == ActiveTargetOwnership.TARGET_MANAGER &&
            active.idempotencyKey == accepted.lastCommandId &&
            last.idempotencyKey == accepted.lastCommandId &&
            UnitConverter.matchesTempTargetObservation(accepted.targetMmol, active.targetMmol) &&
            abs(last.targetMmol - accepted.targetMmol) < EPSILON &&
            winner.sourceRuleId == AdaptiveTargetControllerRule.RULE_ID &&
            winner.generatedAt == input.nowTs && winner.targetMmol > accepted.targetMmol + EPSILON
    }

    private fun isForecastConfirmedTrendRelease(
        input: TargetManagerInput,
        active: ActiveAapsTarget?,
        winner: TargetProposal
    ): Boolean {
        val accepted = input.runtimeState.acceptedTarget ?: return false
        val last = input.lastAutomaticSent ?: return false
        val source = AdaptiveTargetControllerRule.RULE_ID
        if (accepted.ownerRuleId != source || accepted.intent != TargetIntent.NORMAL_CONTROL ||
            accepted.lastCommandStatus != "sent" || accepted.lastCommandId == null ||
            accepted.expiresAt <= input.nowTs || active?.ownership != ActiveTargetOwnership.TARGET_MANAGER ||
            !active.evidenceResolved || active.idempotencyKey != accepted.lastCommandId ||
            last.idempotencyKey != accepted.lastCommandId ||
            !UnitConverter.matchesTempTargetObservation(accepted.targetMmol, active.targetMmol) ||
            abs(last.targetMmol - accepted.targetMmol) >= EPSILON ||
            winner.sourceRuleId != source || winner.intent != TargetIntent.NORMAL_CONTROL ||
            winner.generatedAt != input.nowTs ||
            input.safety.sensorTrust != SensorTrustState.TRUSTED ||
            input.safety.deliveryTrust != DeliveryTrustState.NORMAL ||
            input.glucoseTimestamp <= last.timestamp || input.glucoseTimestamp <= 0L ||
            input.nowTs - input.glucoseTimestamp !in 0L..5 * 60_000L
        ) return false

        val anchor = accepted.targetMmol
        val base = input.safety.baseTargetMmol
        val delta = winner.targetMmol - anchor
        if (abs(delta) + EPSILON < AdaptiveTargetControllerRule.TARGET_STEP_MMOL ||
            UnitConverter.mmolToMgdl(winner.targetMmol) == UnitConverter.mmolToMgdl(anchor) ||
            delta * (base - anchor) <= 0.0 ||
            winner.targetMmol !in minOf(anchor, base)..maxOf(anchor, base)
        ) return false

        // This context also carries the canonical trend and accepted control forecasts outside activity mode.
        val trend = input.activitySafety.observedDelta5Mmol ?: return false
        val current = input.safety.currentGlucoseMmol ?: return false
        if (!trend.isFinite() || !current.isFinite() ||
            abs(trend) < AdaptiveTempTargetController.TREND_STOP_THRESHOLD_MMOL5 ||
            trend * delta >= 0.0
        ) return false
        val forecasts = listOf(5, 30, 60).map { horizon ->
            input.activitySafety.forecasts[horizon]?.takeIf {
                it.horizonMinutes == horizon && it.valueMmol.isFinite() &&
                    it.ciLowMmol.isFinite() && it.ciHighMmol.isFinite() &&
                    it.ciLowMmol <= it.valueMmol && it.valueMmol <= it.ciHighMmol
            } ?: return false
        }
        return (forecasts.first().valueMmol - current) * trend > 0.0
    }

    private fun isBelowBaseWithoutQualifiedIob(
        input: TargetManagerInput,
        targetMmol: Double
    ): Boolean = targetMmol < input.safety.baseTargetMmol - EPSILON &&
        !hasQualifiedSafetyIob(input)

    private fun hasQualifiedSafetyIob(input: TargetManagerInput): Boolean =
        input.safety.safetyIobUnits?.let { it.isFinite() && it in 0.0..30.0 } == true

    private fun isValidPlannedActivityRenewal(
        input: TargetManagerInput,
        accepted: AcceptedTargetState,
        proposal: TargetProposal
    ): Boolean {
        val acceptedActivity = accepted.activityProposal ?: return false
        val freshActivity = proposal.activityProposal ?: return false
        val freshTarget = freshActivity.targetMmol ?: return false
        return proposal.generatedAt == input.nowTs &&
            acceptedActivity.direction == ActivityTargetProposalDirection.RAISE &&
            freshActivity.direction == ActivityTargetProposalDirection.RAISE &&
            freshActivity.protectionEvidenceHash?.isNotBlank() == true &&
            freshActivity.occurrenceId == acceptedActivity.occurrenceId &&
            freshActivity.occurrenceRevision == acceptedActivity.occurrenceRevision &&
            freshActivity.intensity == acceptedActivity.intensity &&
            freshActivity.validFromMs == acceptedActivity.validFromMs &&
            freshActivity.validUntilMs == acceptedActivity.validUntilMs &&
            freshActivity.evidenceHash == acceptedActivity.evidenceHash &&
            freshTarget.toRawBits() == accepted.targetMmol.toRawBits() &&
            proposal.targetMmol.toRawBits() == freshTarget.toRawBits()
    }

    private fun proposalComparator() = compareByDescending<TargetProposal> { it.intent.safetyRank() }
        .thenByDescending { it.priority }
        .thenByDescending { it.confidence }
        .thenBy { it.sourceRuleId }
        .thenBy { it.inputFingerprint }
        .thenByDescending { it.targetMmol }
        .thenBy { it.durationMinutes }
        .thenByDescending { it.generatedAt }
        .thenComparator { left, right -> compareReasonCodes(left.reasonCodes, right.reasonCodes) }

    private fun compareReasonCodes(left: List<String>, right: List<String>): Int {
        val sharedSize = minOf(left.size, right.size)
        for (index in 0 until sharedSize) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun acceptedState(
        runtime: TargetManagerRuntimeState,
        winner: TargetProposal,
        input: TargetManagerInput,
        ownerRuleId: String,
        commandId: String,
        commandStatus: String
    ): AcceptedTargetState {
        val currentRevision = runtime.acceptedTarget?.revision ?: 0L
        require(currentRevision >= 0L) { "accepted target revision must be nonnegative" }
        val nextRevision = Math.addExact(currentRevision, 1L)
        val durationMs = Math.multiplyExact(winner.durationMinutes.toLong(), MINUTE_MS)
        val expiresAt = Math.addExact(input.nowTs, durationMs)
        return AcceptedTargetState(
            revision = nextRevision,
            targetMmol = winner.targetMmol,
            durationMinutes = winner.durationMinutes,
            ownerRuleId = ownerRuleId,
            intent = winner.intent,
            acceptedAt = input.nowTs,
            expiresAt = expiresAt,
            lastInputFingerprint = winner.inputFingerprint,
            lastCommandId = commandId,
            lastCommandStatus = commandStatus,
            // Schedule identity is kept for lifecycle ownership; forecast proof is cycle-scoped.
            activityProposal = winner.activityProposal?.copy(protectionEvidenceHash = null)
        )
    }

    private fun semanticFingerprint(
        input: TargetManagerInput,
        active: ActiveAapsTarget?,
        winner: TargetProposal
    ): String {
        val fields = listOf(
            input.glucoseTimestamp.toString(),
            input.therapyWatermark.toString(),
            input.safety.sensorTrust.name,
            input.safety.deliveryTrust.name,
            input.copilotPriorityEnabled.toString(),
            input.priorityRevision.toString(),
            input.sensitivityRuntime.snapshot.forecastCycleId,
            if (active == null) "active_target_absent" else "active_target_present",
            active?.ownership?.name,
            active?.idempotencyKey,
            active?.source,
            active?.targetMmol?.let(::canonicalDouble),
            active?.startedAt?.toString(),
            active?.expiresAt?.toString(),
            active?.evidenceResolved?.toString(),
            input.baseProvenance.scheduleRevision.toString(),
            input.baseProvenance.intervalId,
            input.baseProvenance.adjustmentRunId,
            winner.sourceRuleId,
            winner.intent.name,
            canonicalDouble(winner.targetMmol),
            winner.durationMinutes.toString(),
            winner.inputFingerprint
        )
        val contextFields = if (active?.eatingSoonConfirmed == true) listOf("eating_soon_confirmed_v1") else emptyList()
        return digestFields(*(fields + contextFields).toTypedArray())
    }

    private fun digestFields(vararg fields: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            if (field == null) {
                digest.update(NULL_FIELD_LENGTH)
            } else {
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest()
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
    }

    private fun canonicalDouble(value: Double): String = java.lang.Double.toHexString(value)

    private fun rejectedProposalReasons(
        evaluations: List<CandidateEvaluation>
    ): Map<String, String> {
        val rejected = evaluations
            .filterNot(CandidateEvaluation::eligible)
            .map { evaluation ->
                CanonicalRejectedEvaluation(
                    evaluation = evaluation,
                    canonical = canonicalEvaluation(evaluation)
                )
            }
            .sortedBy(CanonicalRejectedEvaluation::canonical)
        val sourceCounts = rejected
            .groupingBy { it.evaluation.proposal.sourceRuleId }
            .eachCount()
        val reservedSourceKeys = rejected
            .filter { sourceCounts[it.evaluation.proposal.sourceRuleId] == 1 }
            .mapTo(mutableSetOf()) { it.evaluation.proposal.sourceRuleId }
        val usedKeys = reservedSourceKeys.toMutableSet()
        val generatedKeys = mutableMapOf<Int, String>()
        rejected.forEachIndexed { index, rejectedEvaluation ->
            val proposal = rejectedEvaluation.evaluation.proposal
            if (sourceCounts[proposal.sourceRuleId] == 1) return@forEachIndexed
            val baseKey = generatedRejectedKey(rejectedEvaluation)
            var candidate = baseKey
            var suffix = 2
            while (!usedKeys.add(candidate)) {
                candidate = "$baseKey#$suffix"
                suffix += 1
            }
            generatedKeys[index] = candidate
        }
        return buildMap {
            rejected.forEachIndexed { index, rejectedEvaluation ->
                val evaluation = rejectedEvaluation.evaluation
                val proposal = evaluation.proposal
                val key = if (sourceCounts[proposal.sourceRuleId] == 1) {
                    proposal.sourceRuleId
                } else {
                    checkNotNull(generatedKeys[index])
                }
                put(key, checkNotNull(evaluation.rejectionReason))
            }
        }
    }

    private fun generatedRejectedKey(
        rejected: CanonicalRejectedEvaluation
    ): String {
        val source = rejected.evaluation.proposal.sourceRuleId
        return "$REJECTED_KEY_NAMESPACE${digestFields(source)}:${digestFields(rejected.canonical)}"
    }

    private fun canonicalEvaluation(evaluation: CandidateEvaluation): String {
        val proposal = evaluation.proposal
        return canonicalFields(
            proposal.sourceRuleId,
            proposal.intent.name,
            canonicalDouble(proposal.targetMmol),
            proposal.durationMinutes.toString(),
            proposal.priority.toString(),
            canonicalDouble(proposal.confidence),
            canonicalFields(*proposal.reasonCodes.toTypedArray()),
            proposal.generatedAt.toString(),
            proposal.inputFingerprint,
            checkNotNull(evaluation.rejectionReason)
        )
    }

    private fun canonicalFields(vararg fields: String): String = buildString {
        fields.forEach { field ->
            append(field.length)
            append(':')
            append(field)
        }
    }

    private fun dominantBlockedOutcome(evaluations: List<CandidateEvaluation>): TargetDecisionOutcome {
        if (evaluations.isEmpty()) return TargetDecisionOutcome.NO_PROPOSAL
        val reasons = evaluations.mapNotNull(CandidateEvaluation::rejectionReason)
        return dominantBlockedCategory(reasons).outcome
    }

    private fun dominantBlockedReason(evaluations: List<CandidateEvaluation>): String {
        val reasons = evaluations.mapNotNull(CandidateEvaluation::rejectionReason)
        if (reasons.isEmpty()) return "no_proposal"
        val category = dominantBlockedCategory(reasons)
        return reasons.filter { blockedReasonCategory(it) == category }.minOrNull() ?: "no_proposal"
    }

    private fun dominantBlockedCategory(reasons: List<String>): BlockedReasonCategory = reasons
        .map(::blockedReasonCategory)
        .minByOrNull { it.ordinal }
        ?: BlockedReasonCategory.SAFETY

    private fun blockedReasonCategory(reason: String): BlockedReasonCategory = when {
        reason == "eating_soon_target_active" -> BlockedReasonCategory.MANUAL
        reason.contains("delivery_nonresponse") -> BlockedReasonCategory.DELIVERY
        reason.contains("sensor") -> BlockedReasonCategory.SENSOR
        reason.contains("protective") -> BlockedReasonCategory.PROTECTIVE
        reason.contains("forecast") ||
            reason.contains("low_risk") ||
            reason.contains("glucose_context") -> BlockedReasonCategory.FORECAST
        else -> BlockedReasonCategory.SAFETY
    }

    private fun blocked(
        input: TargetManagerInput,
        runtime: TargetManagerRuntimeState,
        outcome: TargetDecisionOutcome,
        reason: String,
        winner: TargetProposal? = null,
        semanticFingerprint: String? = null,
        cadence: TargetCadenceDecision? = null,
        rejected: Map<String, String> = emptyMap()
    ) = TargetManagerDecision(
        outcome = outcome,
        winner = winner,
        command = null,
        semanticFingerprint = semanticFingerprint,
        cadenceOutcome = cadence?.outcome,
        cadenceReason = cadence?.reason,
        reasonCodes = listOf(reason),
        rejectedProposalReasons = rejected,
        nextRuntimeState = runtime
    )

    private fun TargetIntent.safetyRank(): Int = when (this) {
        TargetIntent.SENSOR_SAFETY_RELEASE -> 600
        TargetIntent.HYPO_PROTECTION -> 500
        TargetIntent.ACTIVITY_PROTECTION -> 400
        TargetIntent.PLANNED_ACTIVITY_ADAPTATION -> 350
        TargetIntent.POST_HYPO_PROTECTION -> 300
        TargetIntent.RECOVERY_TO_BASE -> 200
        TargetIntent.NORMAL_CONTROL -> 100
    }

    private data class CandidateEvaluation(
        val proposal: TargetProposal,
        val eligible: Boolean,
        val rejectionReason: String?
    )

    private data class CanonicalRejectedEvaluation(
        val evaluation: CandidateEvaluation,
        val canonical: String
    )

    private enum class BlockedReasonCategory(
        val outcome: TargetDecisionOutcome
    ) {
        DELIVERY(TargetDecisionOutcome.BLOCK_DELIVERY_TRUST),
        SENSOR(TargetDecisionOutcome.BLOCK_SENSOR_TRUST),
        PROTECTIVE(TargetDecisionOutcome.BLOCK_PROTECTIVE_DIRECTION),
        FORECAST(TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY),
        MANUAL(TargetDecisionOutcome.BLOCK_MANUAL_TARGET),
        SAFETY(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
    }

    companion object {
        const val RENEWAL_SOURCE_RULE_ID = "target_manager_keepalive"
        internal const val KEEPALIVE_WINDOW_MS = 5 * 60_000L
        internal const val KEEPALIVE_GRACE_MS = 5 * 60_000L

        private val REQUIRED_DECREASE_HORIZONS = setOf(5, 30)
        private val PRIORITY_DECREASE_HORIZONS = setOf(5, 30, 60)
        private val ACTIVITY_REQUIRED_HORIZONS = setOf(5, 30, 60)
        private const val ACTIVITY_DURATION_MINUTES = 30
        private const val ACTIVITY_MAX_IOB_UNITS = 3.0
        private const val ACTIVITY_MAX_COB_GRAMS = 5.0
        private const val ACTIVITY_EVIDENCE_EPSILON = 1e-9
        private val NULL_FIELD_LENGTH = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array()
        private const val REJECTED_KEY_NAMESPACE = "target-manager:rejected:"
        private const val MINUTE_MS = 60_000L
        private const val EPSILON = 1e-9
    }
}
