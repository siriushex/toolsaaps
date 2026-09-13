package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.domain.profile.ActivityTargetProposal
import io.aaps.copilot.domain.profile.ActivityTargetProposalDirection
import io.aaps.copilot.domain.profile.ActivitySafePredictedFall
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetManagerTest {

    private val manager = TargetManager()

    @Test
    fun killSwitchBlocksEveryAutomaticProposal() {
        val decision = manager.decide(input(killSwitch = true))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_KILL_SWITCH)
        assertThat(decision.command).isNull()
    }

    @Test
    fun everyAutomaticDecreaseRequiresTheCycleSafetyIob() {
        val decrease = proposal(target = 4.8)

        val blocked = manager.decide(
            input(proposals = listOf(decrease), safetyIobUnits = null)
        )
        val allowed = manager.decide(
            input(proposals = listOf(decrease), safetyIobUnits = 0.0)
        )

        assertThat(blocked.command).isNull()
        assertThat(blocked.rejectedProposalReasons[decrease.sourceRuleId])
            .isEqualTo("safety_iob_blocks_target_decrease")
        assertThat(allowed.command?.targetMmol).isEqualTo(4.8)
    }

    @Test
    fun belowBaseRetargetRemainsBlockedWithoutQualifiedIobEvenWhenItRaisesActiveTarget() {
        listOf<Double?>(null, Double.NaN).forEach { unqualifiedIob ->
            val retarget = proposal(target = 4.2)

            val decision = manager.decide(
                input(
                    proposals = listOf(retarget),
                    activeTarget = activeTarget(4.0),
                    baseTarget = 5.5,
                    safetyIobUnits = unqualifiedIob
                )
            )

            assertThat(decision.command).isNull()
            assertThat(decision.rejectedProposalReasons[retarget.sourceRuleId])
                .isEqualTo("safety_iob_blocks_target_decrease")
        }
    }

    @Test
    fun belowBaseRetargetIsAllowedWithQualifiedIob() {
        val retarget = proposal(target = 4.2)

        val decision = manager.decide(
            input(
                proposals = listOf(retarget),
                activeTarget = activeTarget(4.0),
                baseTarget = 5.5,
                safetyIobUnits = 0.8
            )
        )

        assertThat(decision.command?.targetMmol).isEqualTo(4.2)
    }

    @Test
    fun atOrAboveBaseRetargetRemainsAllowedWithoutQualifiedIob() {
        val protectiveRetarget = proposal(target = 5.5)

        val decision = manager.decide(
            input(
                proposals = listOf(protectiveRetarget),
                activeTarget = activeTarget(4.0),
                baseTarget = 5.5,
                safetyIobUnits = null
            )
        )

        assertThat(decision.command?.targetMmol).isEqualTo(5.5)
    }

    @Test
    fun plannedActivityLowerUsesTheSameCycleSafetyIobAsNormalControl() {
        val activity = activityLowerProposal()
        val proposal = activityTarget(activity)

        val blocked = manager.decide(
            input(
                proposals = listOf(proposal),
                safetyIobUnits = null,
                activitySafety = activitySafety(activity)
            )
        )
        val allowed = manager.decide(
            input(
                proposals = listOf(proposal),
                safetyIobUnits = 1.0,
                activitySafety = activitySafety(activity)
            )
        )

        assertThat(blocked.command).isNull()
        assertThat(blocked.rejectedProposalReasons[proposal.sourceRuleId])
            .isEqualTo("safety_iob_blocks_target_decrease")
        assertThat(allowed.command?.targetMmol).isEqualTo(activity.targetMmol)
    }

    @Test
    fun plannedActivityLowerRejectsNegativeOrFlatTrend() {
        listOf(-0.1, 0.0).forEach { trend ->
            val activity = activityLowerProposal()
            val decision = manager.decide(
                input(
                    proposals = listOf(activityTarget(activity)),
                    activitySafety = activitySafety(activity, observedDelta5Mmol = trend)
                )
            )

            assertThat(decision.command).isNull()
            assertThat(decision.rejectedProposalReasons["planned_activity"])
                .isEqualTo("activity_lower_trend_or_rise_not_concordant")
        }
    }

    @Test
    fun plannedActivityRaiseRequiresMatchingSafePredictedFallAndRejectsUnsafeCi() {
        val activity = activityRaiseProposal()
        val noIndication = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = activityRaiseSafety(activity).copy(safePredictedFall = null)
            )
        )
        val unsafeCi = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = activityRaiseSafety(activity).copy(
                    forecasts = mapOf(
                        5 to ActivityForecastSafety(5, 6.8, 4.3, 7.2),
                        30 to activityForecast(30, 6.5),
                        60 to activityForecast(60, 6.2)
                    )
                )
            )
        )

        assertThat(noIndication.command).isNull()
        assertThat(noIndication.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_raise_safe_predicted_fall_missing")
        assertThat(unsafeCi.command).isNull()
        assertThat(unsafeCi.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_forecast_or_ci_unreliable")
    }

    @Test
    fun acceptedPlannedActivityRetainsScheduleIdentityButDropsPastForecastEvidence() {
        val activity = activityRaiseProposal()

        val decision = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = activityRaiseSafety(activity)
            )
        )

        val accepted = checkNotNull(decision.nextRuntimeState.acceptedTarget?.activityProposal)
        assertThat(accepted.occurrenceId).isEqualTo(activity.occurrenceId)
        assertThat(accepted.evidenceHash).isEqualTo(activity.evidenceHash)
        assertThat(accepted.protectionEvidenceHash).isNull()
    }

    @Test
    fun plannedActivityLowerRejectsNonConcordantRise() {
        val activity = activityLowerProposal()
        val decision = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = activitySafety(
                    activity,
                    forecasts = mapOf(
                        5 to activityForecast(5, 7.2),
                        30 to activityForecast(30, 7.1),
                        60 to activityForecast(60, 7.5)
                    )
                )
            )
        )

        assertThat(decision.command).isNull()
        assertThat(decision.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_lower_trend_or_rise_not_concordant")
    }

    @Test
    fun plannedActivityLowerRejectsPersonalEvidenceMismatch() {
        val activity = activityLowerProposal()
        val decision = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = activitySafety(activity).copy(personalEvidenceHash = "different-personal-proof")
            )
        )

        assertThat(decision.command).isNull()
        assertThat(decision.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_lower_candidate_personal_or_replay_invalid")
    }

    @Test
    fun plannedActivityLowerRejectsMismatchedNumericCandidateDespiteMatchingFingerprint() {
        val activity = activityLowerProposal()
        val decision = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = activitySafety(activity).copy(
                    sameCycleAdaptiveCandidateMmol = 4.7
                )
            )
        )

        assertThat(decision.command).isNull()
        assertThat(decision.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_lower_candidate_value_mismatch")
    }

    @Test
    fun plannedActivityLowerRejectsUnsafeCiOrExcessIob() {
        val activity = activityLowerProposal()
        val unsafeCi = activitySafety(
            activity,
            forecasts = mapOf(
                5 to activityForecast(5, 7.2),
                30 to ActivityForecastSafety(30, 7.5, 4.3, 7.9),
                60 to activityForecast(60, 7.8)
            )
        )
        val excessIob = activitySafety(activity)

        val ciDecision = manager.decide(input(proposals = listOf(activityTarget(activity)), activitySafety = unsafeCi))
        val iobDecision = manager.decide(
            input(
                proposals = listOf(activityTarget(activity)),
                activitySafety = excessIob,
                safetyIobUnits = 3.1
            )
        )

        assertThat(ciDecision.command).isNull()
        assertThat(ciDecision.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_forecast_or_ci_unreliable")
        assertThat(iobDecision.command).isNull()
        assertThat(iobDecision.rejectedProposalReasons["planned_activity"])
            .isEqualTo("activity_therapy_risk")
    }

    @Test
    fun materialRetargetWithinFiveMinutesIsNotBlockedByManagerHold() {
        val decision = manager.decide(
            input(
                proposals = listOf(proposal(target = 5.65)),
                lastSent = lastSent(target = 5.5, minutesAgo = 5)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.command?.targetMmol).isWithin(1e-6).of(5.65)
        assertThat(decision.cadenceOutcome)
            .isEqualTo(TempTargetCadenceOutcome.ALLOW_MATERIAL_CHANGE)
    }

    @Test
    fun urgentProtectiveRaiseWithinFiveMinutesIsNotBlockedByManagerHold() {
        val decision = manager.decide(
            input(
                proposals = listOf(proposal(target = 5.55, intent = TargetIntent.HYPO_PROTECTION)),
                lastSent = lastSent(target = 5.5, minutesAgo = 5)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.cadenceOutcome)
            .isEqualTo(TempTargetCadenceOutcome.ALLOW_URGENT_HYPO_RAISE)
    }

    @Test
    fun sameTargetWithinThirtyMinutesIsBlockedByCadencePolicy() {
        val decision = manager.decide(
            input(lastSent = lastSent(target = 5.5, minutesAgo = 5))
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_CADENCE)
        assertThat(decision.command).isNull()
    }

    @Test
    fun newSensorSafetyFingerprintWithinSameGlucoseBucketCanSend() {
        val previous = manager.decide(input(proposals = listOf(proposal(target = 5.2, fingerprint = "sensor-a"))))
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        source = "sensor_quality_rollback",
                        target = 5.5,
                        intent = TargetIntent.SENSOR_SAFETY_RELEASE,
                        priority = 1_000,
                        fingerprint = "sensor-b"
                    )
                ),
                runtimeState = previous.nextRuntimeState,
                lastSent = lastSent(target = 5.2, minutesAgo = 1)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
    }

    @Test
    fun identicalFingerprintWithinSameGlucoseBucketIsSuppressed() {
        val first = manager.decide(input())
        val second = manager.decide(input(runtimeState = first.nextRuntimeState))

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE)
        assertThat(second.command).isNull()
    }

    @Test
    fun sameBaseProvenanceSuppressesDuplicate() {
        val provenance = provenance(scheduleRevision = 7, intervalId = "night", adjustmentRunId = "run-9")
        val first = manager.decide(input(mode = TargetManagerMode.SHADOW, baseProvenance = provenance))
        val second = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                runtimeState = first.nextRuntimeState,
                baseProvenance = provenance
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE)
        assertThat(second.semanticFingerprint).isEqualTo(first.semanticFingerprint)
    }

    @Test
    fun scheduleRevisionCreatesNewSemanticEvent() {
        assertProvenanceChangeCreatesNewSemanticEvent(
            from = provenance(scheduleRevision = 7),
            to = provenance(scheduleRevision = 8)
        )
    }

    @Test
    fun intervalIdCreatesNewSemanticEvent() {
        assertProvenanceChangeCreatesNewSemanticEvent(
            from = provenance(intervalId = "night"),
            to = provenance(intervalId = "morning")
        )
    }

    @Test
    fun adjustmentRunIdCreatesNewSemanticEvent() {
        assertProvenanceChangeCreatesNewSemanticEvent(
            from = provenance(adjustmentRunId = "run-8"),
            to = provenance(adjustmentRunId = "run-9")
        )
    }

    @Test
    fun changingObservedActiveTimingCreatesNewSemanticEvent() {
        val active = activeTarget(6.0, ActiveTargetOwnership.LEGACY_COPILOT)
        val first = manager.decide(input(mode = TargetManagerMode.SHADOW, activeTarget = active))
        val second = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                activeTarget = active.copy(
                    startedAt = active.startedAt - MINUTE_MS,
                    expiresAt = active.expiresAt + MINUTE_MS
                ),
                runtimeState = first.nextRuntimeState
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(second.semanticFingerprint).isNotEqualTo(first.semanticFingerprint)
    }

    @Test
    fun activeTargetValueChangeWithSameIdempotencyKeyCreatesNewSemanticEvent() {
        val active = activeTarget(6.0, ActiveTargetOwnership.LEGACY_COPILOT)
        val first = manager.decide(input(mode = TargetManagerMode.SHADOW, activeTarget = active))
        val second = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                activeTarget = active.copy(targetMmol = 6.1),
                runtimeState = first.nextRuntimeState
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(second.semanticFingerprint).isNotEqualTo(first.semanticFingerprint)
    }

    @Test
    fun activeTargetSourceChangeWithSameIdempotencyKeyCreatesNewSemanticEvent() {
        val active = activeTarget(6.0, ActiveTargetOwnership.LEGACY_COPILOT)
        val first = manager.decide(input(mode = TargetManagerMode.SHADOW, activeTarget = active))
        val second = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                activeTarget = active.copy(source = "other-source"),
                runtimeState = first.nextRuntimeState
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(second.semanticFingerprint).isNotEqualTo(first.semanticFingerprint)
    }

    @Test
    fun winnerTargetFingerprintUsesExactDoublePayload() {
        val first = manager.decide(
            input(proposals = listOf(proposal(target = 5.501, fingerprint = "same-input")))
        )
        val second = manager.decide(
            input(
                proposals = listOf(proposal(target = 5.504, fingerprint = "same-input")),
                runtimeState = first.nextRuntimeState
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(second.semanticFingerprint).isNotEqualTo(first.semanticFingerprint)
        assertThat(second.command?.idempotencyKey).isNotEqualTo(first.command?.idempotencyKey)
    }

    @Test
    fun activeTargetFingerprintUsesExactDoublePayload() {
        val active = activeTarget(5.501)
        val first = manager.decide(
            input(
                proposals = listOf(proposal(target = 5.6, fingerprint = "same-input")),
                activeTarget = active
            )
        )
        val second = manager.decide(
            input(
                proposals = listOf(proposal(target = 5.6, fingerprint = "same-input")),
                runtimeState = first.nextRuntimeState,
                activeTarget = active.copy(targetMmol = 5.504)
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(second.semanticFingerprint).isNotEqualTo(first.semanticFingerprint)
        assertThat(second.command?.idempotencyKey).isNotEqualTo(first.command?.idempotencyKey)
    }

    @Test
    fun shadowLegacyTargetUsesVirtualAcceptedTargetOrBaseAsAnchor() {
        val baseAnchored = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                proposals = listOf(proposal(target = 5.5)),
                activeTarget = activeTarget(8.0, ActiveTargetOwnership.LEGACY_COPILOT),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(baseAnchored.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)

        val virtualAccepted = acceptedTarget(
            target = 6.0,
            lastCommandStatus = "shadow"
        )
        val virtualAnchored = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                proposals = listOf(proposal(target = 5.5)),
                runtimeState = TargetManagerRuntimeState(
                    mode = TargetManagerMode.SHADOW,
                    acceptedTarget = virtualAccepted
                ),
                activeTarget = activeTarget(4.2, ActiveTargetOwnership.LEGACY_COPILOT),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(virtualAnchored.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_DELIVERY_TRUST)
        assertThat(virtualAnchored.rejectedProposalReasons["adaptive"])
            .isEqualTo("delivery_nonresponse_blocks_target_decrease")
    }

    @Test
    fun activeLegacyTargetStillBlocksAutomaticCommand() {
        val decision = manager.decide(
            input(activeTarget = activeTarget(6.0, ActiveTargetOwnership.LEGACY_COPILOT))
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_LEGACY_TARGET_DRAIN)
        assertThat(decision.command).isNull()
    }

    @Test
    fun copilotPriorityPermitsResolvedExternalOwnersWithoutRelabelingOrForcingMinimum() {
        listOf(
            ActiveTargetOwnership.MANUAL_OR_FOREIGN,
            ActiveTargetOwnership.LEGACY_COPILOT,
            ActiveTargetOwnership.UNKNOWN
        ).forEach { ownership ->
            val external = activeTarget(7.0, ownership).copy(
                source = "aaps_automation",
                idempotencyKey = null
            )

            val decision = manager.decide(
                input(
                    proposals = listOf(proposal(target = 6.0)),
                    activeTarget = external,
                    copilotPriorityEnabled = true,
                    priorityRevision = 7L
                )
            )

            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
            assertThat(decision.command?.targetMmol).isEqualTo(6.0)
            assertThat(decision.command?.targetObservation?.activeAapsTarget).isEqualTo(external)
            assertThat(decision.command?.targetObservation?.copilotPriorityEnabled).isTrue()
            assertThat(decision.command?.targetObservation?.priorityRevision).isEqualTo(7L)
            assertThat(decision.command?.sensitivityCycleId).isEqualTo("test-sensitivity-cycle")
            assertThat(decision.command?.targetObservation?.activeAapsTarget?.ownership)
                .isEqualTo(ownership)
            assertThat(decision.command?.targetObservation?.activeAapsTarget?.source)
                .isEqualTo("aaps_automation")
        }
    }

    @Test
    fun priorityTakeoverDecreaseRequiresReliableSixtyMinuteForecast() {
        val priority = input(
            proposals = listOf(proposal(target = 6.0)),
            activeTarget = activeTarget(7.0, ActiveTargetOwnership.MANUAL_OR_FOREIGN),
            copilotPriorityEnabled = true
        )
        val missingLongHorizon = priority.copy(reliability = priority.reliability - 60)
        val decision = manager.decide(missingLongHorizon)
        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY)
        assertThat(decision.command).isNull()

        val ordinary = missingLongHorizon.copy(
            copilotPriorityEnabled = false,
            activeAapsTarget = activeTarget(7.0, ActiveTargetOwnership.TARGET_MANAGER)
        )
        assertThat(manager.decide(ordinary).outcome).isEqualTo(TargetDecisionOutcome.SEND)
    }

    @Test
    fun copilotPriorityDefaultsFalseAndKeepsExistingOwnershipBlocks() {
        val foreign = manager.decide(
            input(activeTarget = activeTarget(7.0, ActiveTargetOwnership.MANUAL_OR_FOREIGN))
        )
        val unknown = manager.decide(
            input(activeTarget = activeTarget(7.0, ActiveTargetOwnership.UNKNOWN))
        )
        val legacy = manager.decide(
            input(activeTarget = activeTarget(7.0, ActiveTargetOwnership.LEGACY_COPILOT))
        )

        assertThat(foreign.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(unknown.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(legacy.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_LEGACY_TARGET_DRAIN)
        assertThat(foreign.command).isNull()
        assertThat(unknown.command).isNull()
        assertThat(legacy.command).isNull()
    }

    @Test
    fun copilotPriorityAllowsResolvedUnknownButRejectsUnresolvedOrMalformedEvidence() {
        val resolvedUnknown = activeTarget(7.0, ActiveTargetOwnership.UNKNOWN).copy(
            source = "therapy_history",
            idempotencyKey = null,
            evidenceResolved = true
        )
        val unresolved = resolvedUnknown.copy(evidenceResolved = false)
        val malformed = resolvedUnknown.copy(targetMmol = Double.NaN)

        val allowed = manager.decide(
            input(activeTarget = resolvedUnknown, copilotPriorityEnabled = true)
        )
        val unresolvedDecision = manager.decide(
            input(activeTarget = unresolved, copilotPriorityEnabled = true)
        )
        val malformedDecision = manager.decide(
            input(activeTarget = malformed, copilotPriorityEnabled = true)
        )

        assertThat(allowed.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(unresolvedDecision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(unresolvedDecision.reasonCodes).containsExactly("active_target_evidence_unresolved")
        assertThat(malformedDecision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(malformedDecision.reasonCodes).containsExactly("active_target_evidence_invalid")
    }

    @Test
    fun gsonMissingOrNullActiveTargetOwnershipFailsClosedRegardlessOfPriority() {
        listOf(
            gsonActiveTargetJson(ownershipJson = null, sourceJson = "\"aaps_automation\""),
            gsonActiveTargetJson(ownershipJson = "null", sourceJson = "\"aaps_automation\"")
        ).forEach { json ->
            listOf(false, true).forEach { priorityEnabled ->
                val active = Gson().fromJson(json, ActiveAapsTarget::class.java)

                val decision = manager.decide(
                    input(
                        proposals = listOf(proposal(target = 6.0)),
                        activeTarget = active,
                        safetyIobUnits = null,
                        copilotPriorityEnabled = priorityEnabled
                    )
                )

                assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
                assertThat(decision.reasonCodes).containsExactly("active_target_evidence_invalid")
                assertThat(decision.command).isNull()
            }
        }
    }

    @Test
    fun gsonMissingOrNullActiveTargetSourceFailsClosedRegardlessOfPriority() {
        listOf(
            gsonActiveTargetJson(ownershipJson = "\"UNKNOWN\"", sourceJson = null),
            gsonActiveTargetJson(ownershipJson = "\"UNKNOWN\"", sourceJson = "null")
        ).forEach { json ->
            listOf(true, false).forEach { priorityEnabled ->
                val active = Gson().fromJson(json, ActiveAapsTarget::class.java)

                val decision = manager.decide(
                    input(
                        proposals = listOf(proposal(target = 6.0)),
                        activeTarget = active,
                        copilotPriorityEnabled = priorityEnabled
                    )
                )

                assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
                assertThat(decision.reasonCodes).containsExactly("active_target_evidence_invalid")
                assertThat(decision.command).isNull()
            }
        }
    }

    @Test
    fun takeoverDecreaseAboveBaseStillRequiresQualifiedIob() {
        val external = activeTarget(7.0, ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        val takeover = proposal(target = 6.0)

        listOf<Double?>(null, Double.NaN, -0.1, 30.1).forEach { unqualifiedIob ->
            val decision = manager.decide(
                input(
                    proposals = listOf(takeover),
                    activeTarget = external,
                    baseTarget = 5.5,
                    safetyIobUnits = unqualifiedIob,
                    copilotPriorityEnabled = true
                )
            )

            assertThat(decision.command).isNull()
            assertThat(decision.rejectedProposalReasons[takeover.sourceRuleId])
                .isEqualTo("safety_iob_blocks_target_decrease")
        }
    }

    @Test
    fun copilotPriorityDoesNotBypassLowTailReliabilitySensorChronologyKillOrCadence() {
        val external = activeTarget(7.0, ActiveTargetOwnership.MANUAL_OR_FOREIGN)
        val takeover = proposal(target = 6.0)
        fun priorityInput() = input(
            proposals = listOf(takeover),
            activeTarget = external,
            copilotPriorityEnabled = true
        )

        val lowTail = manager.decide(
            priorityInput().copy(
                safety = priorityInput().safety.copy(minimumPredictedOrCiMmol = 4.3)
            )
        )
        val unreliable = manager.decide(
            priorityInput().copy(
                reliability = priorityInput().reliability +
                    (30 to reliability(30).copy(state = HorizonReliabilityState.DEGRADED))
            )
        )
        val sensorWarn = manager.decide(
            priorityInput().copy(safety = priorityInput().safety.copy(sensorTrust = SensorTrustState.WARN))
        )
        val chronology = manager.decide(
            priorityInput().copy(safety = priorityInput().safety.copy(localChronologyResolved = false))
        )
        val kill = manager.decide(
            priorityInput().copy(safety = priorityInput().safety.copy(killSwitch = true))
        )
        val cadence = manager.decide(
            priorityInput().copy(lastAutomaticSent = lastSent(target = 6.0, minutesAgo = 1))
        )

        assertThat(lowTail.rejectedProposalReasons[takeover.sourceRuleId])
            .isEqualTo("low_risk_blocks_target_decrease")
        assertThat(unreliable.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY)
        assertThat(sensorWarn.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SENSOR_TRUST)
        assertThat(chronology.reasonCodes).containsExactly("local_safety_chronology_unresolved")
        assertThat(kill.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_KILL_SWITCH)
        assertThat(cadence.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_CADENCE)
    }

    @Test
    fun commandCarriesObservedAbsencePolicyAndRevisionAndAllChangeFingerprint() {
        val defaultPolicy = manager.decide(input())
        val priorityPolicy = manager.decide(
            input(copilotPriorityEnabled = true, priorityRevision = 1L)
        )
        val revisedPolicy = manager.decide(
            input(copilotPriorityEnabled = true, priorityRevision = 2L)
        )

        assertThat(defaultPolicy.command?.targetObservation)
            .isEqualTo(
                TargetCommandObservation(
                    activeAapsTarget = null,
                    copilotPriorityEnabled = false,
                    priorityRevision = 0L
                )
            )
        assertThat(priorityPolicy.command?.targetObservation)
            .isEqualTo(
                TargetCommandObservation(
                    activeAapsTarget = null,
                    copilotPriorityEnabled = true,
                    priorityRevision = 1L
                )
            )
        assertThat(priorityPolicy.semanticFingerprint).isNotEqualTo(defaultPolicy.semanticFingerprint)
        assertThat(revisedPolicy.semanticFingerprint).isNotEqualTo(priorityPolicy.semanticFingerprint)
    }

    @Test
    fun expiredExternalTargetIsObservedAsAbsent() {
        val expired = activeTarget(7.0, ActiveTargetOwnership.MANUAL_OR_FOREIGN).copy(expiresAt = NOW)

        val decision = manager.decide(
            input(activeTarget = expired, copilotPriorityEnabled = true)
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.command?.targetObservation)
            .isEqualTo(
                TargetCommandObservation(
                    activeAapsTarget = null,
                    copilotPriorityEnabled = true,
                    priorityRevision = 0L
                )
            )
    }

    @Test
    fun recentExternalRewriteBlocksAcrossRestartAndOnlyAuthenticatedEchoPasses() {
        val accepted = acceptedTarget(target = 6.0).copy(
            acceptedAt = NOW - 2 * MINUTE_MS,
            expiresAt = NOW + 28 * MINUTE_MS,
            lastCommandId = "TargetManager.v1:accepted"
        )
        val runtime = TargetManagerRuntimeState(
            mode = TargetManagerMode.ACTIVE,
            acceptedTarget = accepted
        )
        val independentRewrite = activeTarget(
            7.0,
            ActiveTargetOwnership.MANUAL_OR_FOREIGN
        ).copy(
            startedAt = accepted.acceptedAt + MINUTE_MS,
            expiresAt = accepted.expiresAt + MINUTE_MS,
            source = "aaps_automation",
            idempotencyKey = null
        )
        val sameValueAndWindowWithoutProvenance = independentRewrite.copy(
            targetMmol = accepted.targetMmol,
            startedAt = accepted.acceptedAt,
            expiresAt = accepted.expiresAt
        )
        val authenticatedEcho = sameValueAndWindowWithoutProvenance.copy(
            idempotencyKey = accepted.lastCommandId
        )

        val beforeRestart = manager.decide(
            input(
                runtimeState = runtime,
                activeTarget = independentRewrite,
                copilotPriorityEnabled = true
            )
        )
        val afterRestart = TargetManager().decide(
            input(
                runtimeState = runtime,
                activeTarget = independentRewrite,
                copilotPriorityEnabled = true
            )
        )
        val unverifiedEchoDecisions = listOf(
            sameValueAndWindowWithoutProvenance,
            sameValueAndWindowWithoutProvenance.copy(idempotencyKey = "other-command")
        ).map { unverifiedEcho ->
            TargetManager().decide(
                input(
                    proposals = listOf(proposal(target = 6.2)),
                    runtimeState = runtime,
                    activeTarget = unverifiedEcho,
                    copilotPriorityEnabled = true
                )
            )
        }
        val authenticatedEchoDecision = TargetManager().decide(
            input(
                proposals = listOf(proposal(target = 6.2)),
                runtimeState = runtime,
                activeTarget = authenticatedEcho,
                copilotPriorityEnabled = true
            )
        )

        assertThat(beforeRestart.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(beforeRestart.reasonCodes).containsExactly("external_target_writer_conflict")
        assertThat(afterRestart.reasonCodes).containsExactly("external_target_writer_conflict")
        unverifiedEchoDecisions.forEach { decision ->
            assertThat(decision.reasonCodes).containsExactly("external_target_writer_conflict")
        }
        assertThat(authenticatedEchoDecision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
    }

    @Test
    fun externalRewriteOutsideConflictWindowCanBeEvaluatedAgain() {
        val accepted = acceptedTarget(target = 6.0).copy(
            acceptedAt = NOW - 11 * MINUTE_MS,
            expiresAt = NOW + 19 * MINUTE_MS,
            lastCommandId = "TargetManager.v1:accepted"
        )
        val external = activeTarget(7.0, ActiveTargetOwnership.UNKNOWN).copy(
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 29 * MINUTE_MS,
            idempotencyKey = null
        )

        val decision = manager.decide(
            input(
                runtimeState = TargetManagerRuntimeState(
                    mode = TargetManagerMode.ACTIVE,
                    acceptedTarget = accepted
                ),
                activeTarget = external,
                copilotPriorityEnabled = true
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
    }

    @Test
    fun lowPriorityProtectiveIntentBeatsHighPriorityNormalIntent() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        source = "normal",
                        target = 5.8,
                        intent = TargetIntent.NORMAL_CONTROL,
                        priority = 100_000
                    ),
                    proposal(
                        source = "activity",
                        target = 5.6,
                        intent = TargetIntent.ACTIVITY_PROTECTION,
                        priority = -100_000
                    )
                )
            )
        )

        assertThat(decision.winner?.intent).isEqualTo(TargetIntent.ACTIVITY_PROTECTION)
        assertThat(decision.winner?.sourceRuleId).isEqualTo("activity")
    }

    @Test
    fun intentSafetyRankPrecedesPriorityForEveryAdjacentPair() {
        val intentsInSafetyOrder = listOf(
            TargetIntent.SENSOR_SAFETY_RELEASE,
            TargetIntent.HYPO_PROTECTION,
            TargetIntent.ACTIVITY_PROTECTION,
            TargetIntent.POST_HYPO_PROTECTION,
            TargetIntent.RECOVERY_TO_BASE,
            TargetIntent.NORMAL_CONTROL
        )

        intentsInSafetyOrder.zipWithNext().forEachIndexed { index, (higherRank, lowerRank) ->
            val decision = manager.decide(
                input(
                    proposals = listOf(
                        proposal(
                            source = "lower-$index",
                            target = 5.8,
                            intent = lowerRank,
                            priority = 10_000
                        ),
                        proposal(
                            source = "higher-$index",
                            target = 5.7,
                            intent = higherRank,
                            priority = -10_000
                        )
                    )
                )
            )

            assertThat(decision.winner?.intent).isEqualTo(higherRank)
        }
    }

    @Test
    fun completeComparatorTailIsStableAcrossEveryInputPermutation() {
        val targetWinner = proposal(
            source = "same",
            target = 5.9,
            fingerprint = "same",
            durationMinutes = 60,
            generatedAt = NOW - 2,
            reasonCodes = listOf("z")
        )
        assertWinnerAcrossPermutations(
            proposals = listOf(
                proposal(source = "same", target = 5.7, fingerprint = "same"),
                targetWinner,
                proposal(source = "same", target = 5.8, fingerprint = "same")
            ),
            expected = targetWinner
        )

        val durationWinner = proposal(
            source = "same",
            target = 5.8,
            fingerprint = "same",
            durationMinutes = 15
        )
        assertWinnerAcrossPermutations(
            proposals = listOf(
                proposal(source = "same", target = 5.8, fingerprint = "same", durationMinutes = 60),
                durationWinner,
                proposal(source = "same", target = 5.8, fingerprint = "same", durationMinutes = 30)
            ),
            expected = durationWinner
        )

        val generatedAtWinner = proposal(
            source = "same",
            target = 5.8,
            fingerprint = "same",
            generatedAt = NOW + 1
        )
        assertWinnerAcrossPermutations(
            proposals = listOf(
                proposal(source = "same", target = 5.8, fingerprint = "same", generatedAt = NOW - 1),
                generatedAtWinner,
                proposal(source = "same", target = 5.8, fingerprint = "same", generatedAt = NOW)
            ),
            expected = generatedAtWinner
        )

        val reasonWinner = proposal(
            source = "same",
            target = 5.8,
            fingerprint = "same",
            reasonCodes = listOf("a", "z")
        )
        assertWinnerAcrossPermutations(
            proposals = listOf(
                proposal(
                    source = "same",
                    target = 5.8,
                    fingerprint = "same",
                    reasonCodes = listOf("b")
                ),
                reasonWinner,
                proposal(
                    source = "same",
                    target = 5.8,
                    fingerprint = "same",
                    reasonCodes = listOf("a", "zz")
                )
            ),
            expected = reasonWinner
        )
    }

    @Test
    fun higherPriorityRollbackWinsOverNormalAdaptiveProposal() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(source = "adaptive", target = 5.2, priority = 100),
                    proposal(
                        source = "sensor_quality_rollback",
                        target = 5.5,
                        intent = TargetIntent.SENSOR_SAFETY_RELEASE,
                        priority = 1_000
                    )
                )
            )
        )

        assertThat(decision.winner?.sourceRuleId).isEqualTo("sensor_quality_rollback")
        assertThat(decision.command?.targetMmol).isWithin(1e-6).of(5.5)
    }

    @Test
    fun oneCycleWithThreeProposalsDispatchesOnlyOneWinner() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(source = "adaptive", target = 5.2, priority = 100),
                    proposal(source = "pattern", target = 5.8, priority = 200),
                    proposal(source = "post_hypo", target = 6.2, priority = 300)
                )
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.winner?.sourceRuleId).isEqualTo("post_hypo")
        assertThat(decision.command).isNotNull()
    }

    @Test
    fun suspectedNonresponseBlocksDecreaseBelowActiveAnchor() {
        assertDeliveryDecreaseBlocked(
            runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE),
            activeTarget = activeTarget(6.5),
            baseTarget = 5.5,
            proposedTarget = 6.4
        )
    }

    @Test
    fun suspectedNonresponseBlocksDecreaseBelowAcceptedAnchor() {
        assertDeliveryDecreaseBlocked(
            runtimeState = TargetManagerRuntimeState(
                mode = TargetManagerMode.ACTIVE,
                acceptedTarget = acceptedTarget(6.2)
            ),
            activeTarget = null,
            baseTarget = 5.5,
            proposedTarget = 6.1
        )
    }

    @Test
    fun suspectedNonresponseBlocksDecreaseBelowBaseAnchor() {
        assertDeliveryDecreaseBlocked(
            runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE),
            activeTarget = null,
            baseTarget = 5.5,
            proposedTarget = 5.4
        )
    }

    @Test
    fun suspectedNonresponseBlocksSmallestRepresentableDecrease() {
        assertDeliveryDecreaseBlocked(
            runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE),
            activeTarget = null,
            baseTarget = 5.5,
            proposedTarget = Math.nextDown(5.5)
        )
    }

    @Test
    fun suspectedNonresponseDoesNotBlockEqualOrHigherProposal() {
        listOf(5.5, 5.6).forEach { target ->
            val decision = manager.decide(
                input(
                    proposals = listOf(proposal(source = "candidate-$target", target = target)),
                    deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
                )
            )

            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
            assertThat(decision.command?.targetMmol).isWithin(1e-6).of(target)
            assertThat(decision.rejectedProposalReasons).doesNotContainKey("candidate-$target")
        }
    }

    @Test
    fun suspectedNonresponseAloneDoesNotCreateProposal() {
        val decision = manager.decide(
            input(
                proposals = emptyList(),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
        assertThat(decision.winner).isNull()
        assertThat(decision.command).isNull()
    }

    @Test
    fun loweringRequiresFiniteCurrentGlucoseAndPrediction() {
        val invalidValues = listOf<Double?>(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        )

        invalidValues.forEachIndexed { index, currentGlucose ->
            assertInvalidGlucoseContextBlocksDecrease(
                source = "current-$index",
                currentGlucoseMmol = currentGlucose,
                minimumPredictedOrCiMmol = 6.5
            )
        }
        invalidValues.forEachIndexed { index, prediction ->
            assertInvalidGlucoseContextBlocksDecrease(
                source = "prediction-$index",
                currentGlucoseMmol = 7.0,
                minimumPredictedOrCiMmol = prediction
            )
        }
    }

    @Test
    fun protectiveNonDecreasingProposalAllowsMissingGlucoseContext() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        source = "hypo-protection",
                        target = 5.6,
                        intent = TargetIntent.HYPO_PROTECTION
                    )
                ),
                currentGlucoseMmol = null,
                minimumPredictedOrCiMmol = null
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.command?.targetMmol).isWithin(1e-9).of(5.6)
    }

    @Test
    fun invalidSafetyContextBlocksEveryCommandWithExplicitReason() {
        val invalidCases = listOf(
            "invalid_target_bounds" to input(minTargetMmol = Double.NaN),
            "invalid_target_bounds" to input(maxTargetMmol = Double.POSITIVE_INFINITY),
            "invalid_target_bounds" to input(minTargetMmol = 8.0, maxTargetMmol = 7.0),
            "invalid_duration_bounds" to input(minDurationMinutes = 0),
            "invalid_duration_bounds" to input(minDurationMinutes = 60, maxDurationMinutes = 30),
            "invalid_base_target" to input(baseTarget = Double.NaN),
            "invalid_base_target" to input(baseTarget = 11.0),
            "invalid_low_risk_threshold" to input(lowRiskThresholdMmol = Double.NaN),
            "invalid_low_risk_threshold" to input(lowRiskThresholdMmol = Double.POSITIVE_INFINITY),
            "invalid_control_anchor" to input(activeTarget = activeTarget(Double.NaN))
        )

        invalidCases.forEach { (expectedReason, managerInput) ->
            val decision = manager.decide(managerInput)

            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
            assertThat(decision.reasonCodes).containsExactly(expectedReason)
            assertThat(decision.command).isNull()
        }
    }

    @Test
    fun dominantDeliveryOutcomeReportsMatchingReason() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(source = "invalid", target = 20.0),
                    proposal(source = "delivery-test", target = 5.4)
                ),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_DELIVERY_TRUST)
        assertThat(decision.reasonCodes)
            .containsExactly("delivery_nonresponse_blocks_target_decrease")
    }

    @Test
    fun dominantBlockedReasonIsStableAcrossProposalPermutations() {
        val invalidKeepalive = proposal(
            source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = 5.5,
            fingerprint = "invalid-keepalive"
        )
        val safetyBounds = proposal(
            source = "invalid-bounds",
            target = 20.0,
            fingerprint = "invalid-bounds"
        )

        val safetyResults = permutations(listOf(invalidKeepalive, safetyBounds)).map { proposals ->
            manager.decide(input(proposals = proposals))
        }
        assertThat(safetyResults.map { it.outcome to it.reasonCodes }.distinct()).hasSize(1)
        assertThat(safetyResults.first().outcome)
            .isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
        assertThat(safetyResults.first().reasonCodes).containsExactly("invalid_keepalive")

        val forecast = proposal(
            source = "forecast",
            target = 5.4,
            fingerprint = "forecast"
        )
        val forecastResults = permutations(listOf(invalidKeepalive, safetyBounds, forecast)).map { proposals ->
            val managerInput = input(proposals = proposals)
            manager.decide(managerInput.copy(reliability = emptyMap()))
        }
        assertThat(forecastResults.map { it.outcome to it.reasonCodes }.distinct()).hasSize(1)
        assertThat(forecastResults.first().outcome)
            .isEqualTo(TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY)
        assertThat(forecastResults.first().reasonCodes)
            .containsExactly("forecast_reliability_blocks_target_decrease")
    }

    @Test
    fun duplicateSourceIdsPreserveEveryRejectedProposalReason() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(source = "duplicate", target = 20.0, fingerprint = "same"),
                    proposal(source = "duplicate", target = 5.4, fingerprint = "same")
                ),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(decision.rejectedProposalReasons).hasSize(2)
        assertThat(decision.rejectedProposalReasons).doesNotContainKey("duplicate")
        assertThat(decision.rejectedProposalReasons.values)
            .containsExactly("safety_bounds", "delivery_nonresponse_blocks_target_decrease")
    }

    @Test
    fun rejectedReasonsAreStableForRepeatedSourceAndFingerprintAcrossPermutations() {
        val rejectedProposals = listOf(
            proposal(
                source = "duplicate",
                target = 20.0,
                fingerprint = "same",
                reasonCodes = listOf("outside-bounds")
            ),
            proposal(
                source = "duplicate",
                target = 5.4,
                fingerprint = "same",
                reasonCodes = listOf("delivery-decrease")
            )
        )

        val reasonMaps = permutations(rejectedProposals).map { proposals ->
            manager.decide(
                input(
                    proposals = proposals,
                    deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
                )
            ).rejectedProposalReasons
        }

        assertThat(reasonMaps.distinct()).hasSize(1)
        assertThat(reasonMaps.first().values)
            .containsExactly("safety_bounds", "delivery_nonresponse_blocks_target_decrease")
    }

    @Test
    fun generatedRejectedKeyCannotCollideWithRealUniqueSource() {
        val duplicateProposals = listOf(
            proposal(source = "duplicate", target = 20.0, fingerprint = "same"),
            proposal(source = "duplicate", target = 5.4, fingerprint = "same")
        )
        val generatedKey = manager.decide(
            input(
                proposals = duplicateProposals,
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        ).rejectedProposalReasons.keys.sorted().first()
        val collidingUniqueSource = proposal(
            source = generatedKey,
            target = 20.0,
            fingerprint = "unique-collision"
        )

        val reasonMaps = permutations(duplicateProposals + collidingUniqueSource).map { proposals ->
            manager.decide(
                input(
                    proposals = proposals,
                    deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
                )
            ).rejectedProposalReasons
        }

        assertThat(reasonMaps.distinct()).hasSize(1)
        assertThat(reasonMaps.first()).hasSize(3)
        assertThat(reasonMaps.first()).containsKey(generatedKey)
        assertThat(reasonMaps.first().values)
            .containsExactly(
                "safety_bounds",
                "delivery_nonresponse_blocks_target_decrease",
                "safety_bounds"
            )
    }

    @Test
    fun forgedKeepaliveIsRejectedWithoutRenewalOrOwnerInheritance() {
        val accepted = acceptedTarget(
            target = 6.0,
            revision = 7,
            ownerRuleId = "original-owner",
            lastCommandStatus = "sent",
            expiresAt = NOW + 4 * MINUTE_MS
        )
        val exactRenewal = proposal(
            source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = accepted.targetMmol,
            intent = accepted.intent,
            durationMinutes = accepted.durationMinutes,
            fingerprint = "renewal"
        )
        val activeRuntime = TargetManagerRuntimeState(
            mode = TargetManagerMode.ACTIVE,
            acceptedTarget = accepted
        )
        val invalidInputs = listOf(
            input(proposals = listOf(exactRenewal)),
            input(
                proposals = listOf(exactRenewal.copy(targetMmol = 6.1)),
                runtimeState = activeRuntime
            ),
            input(
                proposals = listOf(exactRenewal.copy(durationMinutes = 60)),
                runtimeState = activeRuntime
            ),
            input(
                proposals = listOf(exactRenewal.copy(intent = TargetIntent.HYPO_PROTECTION)),
                runtimeState = activeRuntime
            ),
            input(
                proposals = listOf(exactRenewal),
                runtimeState = activeRuntime.copy(
                    acceptedTarget = accepted.copy(lastCommandStatus = "pending")
                )
            ),
            input(
                mode = TargetManagerMode.SHADOW,
                proposals = listOf(exactRenewal),
                runtimeState = TargetManagerRuntimeState(
                    mode = TargetManagerMode.SHADOW,
                    acceptedTarget = accepted
                )
            ),
            input(
                proposals = listOf(exactRenewal),
                runtimeState = activeRuntime,
                sensorTrust = SensorTrustState.WARN
            ),
            input(
                proposals = listOf(exactRenewal),
                runtimeState = activeRuntime.copy(
                    acceptedTarget = accepted.copy(expiresAt = NOW + 5 * MINUTE_MS + 1)
                )
            ),
            input(
                proposals = listOf(exactRenewal),
                runtimeState = activeRuntime.copy(
                    acceptedTarget = accepted.copy(expiresAt = NOW - 5 * MINUTE_MS - 1)
                )
            ),
            input(
                nowTs = Long.MIN_VALUE,
                proposals = listOf(exactRenewal),
                runtimeState = activeRuntime.copy(
                    acceptedTarget = accepted.copy(expiresAt = Long.MAX_VALUE)
                )
            ),
            input(
                nowTs = Long.MAX_VALUE,
                proposals = listOf(exactRenewal),
                runtimeState = activeRuntime.copy(
                    acceptedTarget = accepted.copy(expiresAt = Long.MIN_VALUE)
                )
            )
        )

        invalidInputs.forEach { managerInput ->
            val decision = manager.decide(managerInput)

            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
            assertThat(decision.reasonCodes).containsExactly("invalid_keepalive")
            assertThat(decision.rejectedProposalReasons[TargetManager.RENEWAL_SOURCE_RULE_ID])
                .isEqualTo("invalid_keepalive")
            assertThat(decision.command).isNull()
            assertThat(decision.outcome).isNotEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        }
    }

    @Test
    fun loweredKeepaliveCannotRenewAfterCurrentSafetyIobDisappears() {
        val accepted = acceptedTarget(
            target = 4.8,
            lastCommandStatus = "sent",
            expiresAt = NOW + 4 * MINUTE_MS
        )
        val renewal = proposal(
            source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = accepted.targetMmol,
            intent = accepted.intent,
            durationMinutes = accepted.durationMinutes,
            fingerprint = "lowered-renewal"
        )
        val runtime = TargetManagerRuntimeState(
            mode = TargetManagerMode.ACTIVE,
            acceptedTarget = accepted
        )

        val blocked = manager.decide(
            input(
                proposals = listOf(renewal),
                runtimeState = runtime,
                activeTarget = activeTarget(4.8),
                baseTarget = 5.5,
                safetyIobUnits = null
            )
        )
        val allowed = manager.decide(
            input(
                proposals = listOf(renewal),
                runtimeState = runtime,
                activeTarget = activeTarget(4.8),
                baseTarget = 5.5,
                safetyIobUnits = 0.8
            )
        )

        assertThat(blocked.command).isNull()
        assertThat(blocked.rejectedProposalReasons[TargetManager.RENEWAL_SOURCE_RULE_ID])
            .isEqualTo("invalid_keepalive")
        assertThat(allowed.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
    }

    @Test
    fun protectiveKeepaliveAtOrAboveBaseDoesNotRequireSafetyIob() {
        val accepted = acceptedTarget(
            target = 6.0,
            lastCommandStatus = "sent",
            expiresAt = NOW + 4 * MINUTE_MS
        )
        val renewal = proposal(
            source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = accepted.targetMmol,
            intent = accepted.intent,
            durationMinutes = accepted.durationMinutes,
            fingerprint = "protective-renewal"
        )

        val decision = manager.decide(
            input(
                proposals = listOf(renewal),
                runtimeState = TargetManagerRuntimeState(
                    mode = TargetManagerMode.ACTIVE,
                    acceptedTarget = accepted
                ),
                activeTarget = activeTarget(6.0),
                baseTarget = 5.5,
                safetyIobUnits = null
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        assertThat(decision.command?.targetMmol).isEqualTo(6.0)
    }

    @Test
    fun expiryAndGraceRenewalIncrementPersistedRevision() {
        listOf(NOW, NOW - 5 * MINUTE_MS).forEachIndexed { index, expiresAt ->
            val accepted = acceptedTarget(
                target = 6.0,
                revision = 7,
                ownerRuleId = "original-owner",
                lastCommandStatus = "sent",
                expiresAt = expiresAt
            )
            val renewal = proposal(
                source = TargetManager.RENEWAL_SOURCE_RULE_ID,
                target = accepted.targetMmol,
                intent = accepted.intent,
                durationMinutes = accepted.durationMinutes,
                fingerprint = "renewal-$index"
            )
            val decision = manager.decide(
                input(
                    proposals = listOf(renewal),
                    runtimeState = TargetManagerRuntimeState(
                        mode = TargetManagerMode.ACTIVE,
                        acceptedTarget = accepted
                    )
                )
            )

            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
            assertThat(decision.nextRuntimeState.acceptedTarget?.revision).isEqualTo(8)
            assertThat(decision.nextRuntimeState.acceptedTarget?.ownerRuleId)
                .isEqualTo("original-owner")
        }
    }

    @Test
    fun expiredAcceptedTargetIsRevisionHistoryButNotControlAnchor() {
        val accepted = acceptedTarget(
            target = 8.0,
            revision = 41,
            expiresAt = NOW
        )
        val decision = manager.decide(
            input(
                proposals = listOf(proposal(target = 5.5)),
                runtimeState = TargetManagerRuntimeState(
                    mode = TargetManagerMode.ACTIVE,
                    acceptedTarget = accepted
                ),
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.command?.targetMmol).isWithin(1e-9).of(5.5)
        assertThat(decision.nextRuntimeState.acceptedTarget?.revision).isEqualTo(42)
    }

    @Test
    fun blockedSensorDoesNotRollbackProtectiveHighTargetToBase() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        source = "sensor_quality_rollback",
                        target = 5.5,
                        intent = TargetIntent.SENSOR_SAFETY_RELEASE,
                        priority = 1_000
                    )
                ),
                sensorTrust = SensorTrustState.BLOCKED,
                activeTarget = activeTarget(8.0)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SENSOR_TRUST)
        assertThat(decision.command).isNull()
    }

    @Test
    fun blockedSensorCanReleaseLowTargetUpToBase() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        source = "sensor_quality_rollback",
                        target = 5.5,
                        intent = TargetIntent.SENSOR_SAFETY_RELEASE,
                        priority = 1_000
                    )
                ),
                sensorTrust = SensorTrustState.BLOCKED,
                activeTarget = activeTarget(4.2)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.command?.targetMmol).isWithin(1e-6).of(5.5)
    }

    @Test
    fun blockedSensorCanReleaseFiniteBelowMinAnchorIntoValidRange() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        source = "sensor_quality_rollback",
                        target = 5.5,
                        intent = TargetIntent.SENSOR_SAFETY_RELEASE,
                        priority = 1_000
                    )
                ),
                sensorTrust = SensorTrustState.BLOCKED,
                activeTarget = activeTarget(3.9)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.command?.targetMmol).isWithin(1e-9).of(5.5)
    }

    @Test
    fun finiteBelowMinAnchorAllowsOnlyStrictRaiseIntoValidRange() {
        val raised = manager.decide(
            input(
                proposals = listOf(proposal(source = "raise", target = 4.0)),
                activeTarget = activeTarget(3.9)
            )
        )
        val notRaised = manager.decide(
            input(
                proposals = listOf(proposal(source = "flat", target = 3.9)),
                activeTarget = activeTarget(3.9)
            )
        )

        assertThat(raised.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(raised.command?.targetMmol).isWithin(1e-9).of(4.0)
        assertThat(notRaised.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
        assertThat(notRaised.command).isNull()
    }

    @Test
    fun aboveMaxAndNonFiniteAnchorsRemainFailClosed() {
        listOf(10.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { anchor ->
            val decision = manager.decide(
                input(
                    proposals = listOf(proposal(target = 10.0)),
                    activeTarget = activeTarget(anchor)
                )
            )

            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
            assertThat(decision.reasonCodes).containsExactly("invalid_control_anchor")
            assertThat(decision.command).isNull()
        }
    }

    @Test
    fun trustedProtectiveIntentCannotNumericallyLowerAnchor() {
        val decision = manager.decide(
            input(
                proposals = listOf(
                    proposal(
                        target = 6.4,
                        intent = TargetIntent.HYPO_PROTECTION,
                        fingerprint = "protective-lower"
                    )
                ),
                sensorTrust = SensorTrustState.TRUSTED,
                activeTarget = activeTarget(7.0)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_PROTECTIVE_DIRECTION)
        assertThat(decision.command).isNull()
    }

    @Test
    fun manualAapsTargetBlocksAutomaticOverwrite() {
        val decision = manager.decide(
            input(activeTarget = activeTarget(6.0, ActiveTargetOwnership.MANUAL_OR_FOREIGN))
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(decision.command).isNull()
    }

    @Test
    fun shadowReturnsWouldSendWithoutCommand() {
        val decision = manager.decide(input(mode = TargetManagerMode.SHADOW))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(decision.command).isNull()
        assertThat(decision.winner).isNotNull()
        assertThat(decision.nextRuntimeState.acceptedTarget?.targetMmol).isWithin(1e-6).of(5.5)
        assertThat(decision.nextRuntimeState.acceptedTarget?.lastCommandStatus).isEqualTo("shadow")
    }

    @Test
    fun maximumAcceptedRevisionFailsClosed() {
        val runtime = TargetManagerRuntimeState(
            mode = TargetManagerMode.ACTIVE,
            acceptedTarget = acceptedTarget(target = 5.5, revision = Long.MAX_VALUE)
        )

        assertThrows(ArithmeticException::class.java) {
            manager.decide(
                input(
                    proposals = listOf(proposal(target = 5.6)),
                    runtimeState = runtime
                )
            )
        }
    }

    @Test
    fun negativeAcceptedRevisionIsRejected() {
        val runtime = TargetManagerRuntimeState(
            mode = TargetManagerMode.ACTIVE,
            acceptedTarget = acceptedTarget(target = 5.5, revision = -1)
        )

        assertThrows(IllegalArgumentException::class.java) {
            manager.decide(
                input(
                    proposals = listOf(proposal(target = 5.6)),
                    runtimeState = runtime
                )
            )
        }
    }

    @Test
    fun acceptedExpiryOverflowFailsClosed() {
        assertThrows(ArithmeticException::class.java) {
            manager.decide(
                input(
                    nowTs = Long.MAX_VALUE - 1,
                    proposals = listOf(proposal(target = 5.6))
                )
            )
        }
    }

    @Test
    fun expiredHypoReasonCannotRenewOldHighTargetWhenLowerProposalIsUnreliable() {
        val accepted = acceptedTarget(target = 10.0, ownerRuleId = "adaptive",
            expiresAt = NOW + 4 * MINUTE_MS).copy(intent = TargetIntent.HYPO_PROTECTION)
        val renewal = proposal(source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = 10.0, intent = TargetIntent.HYPO_PROTECTION, priority = -10_000)
        val context = input(
            proposals = listOf(proposal(target = 4.9), renewal),
            runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE, acceptedTarget = accepted),
            activeTarget = activeTarget(10.0), baseTarget = 5.9,
            currentGlucoseMmol = 8.7, minimumPredictedOrCiMmol = 5.7
        )
        val decision = manager.decide(context.copy(reliability = context.reliability.mapValues {
            it.value.copy(state = HorizonReliabilityState.DEGRADED)
        }))
        assertThat(decision.command).isNull()
        assertThat(decision.rejectedProposalReasons["adaptive"])
            .isEqualTo("forecast_reliability_blocks_target_decrease")
        assertThat(decision.rejectedProposalReasons[TargetManager.RENEWAL_SOURCE_RULE_ID])
            .isEqualTo("invalid_keepalive")
    }

    @Test
    fun hypoRenewalStillProtectsCurrentLowOrLowCiWithoutFreshOwnerProposal() {
        val accepted = acceptedTarget(target = 10.0, ownerRuleId = "adaptive",
            expiresAt = NOW + 4 * MINUTE_MS).copy(intent = TargetIntent.HYPO_PROTECTION)
        val renewal = proposal(source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = 10.0, intent = TargetIntent.HYPO_PROTECTION)
        for ((glucose, ci) in listOf(3.8 to 6.0, 7.0 to 3.8)) {
            val decision = manager.decide(input(
                proposals = listOf(renewal),
                runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE, acceptedTarget = accepted),
                activeTarget = activeTarget(10.0), currentGlucoseMmol = glucose,
                minimumPredictedOrCiMmol = ci
            ))
            assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        }
    }

    @Test
    fun staleHypoOwnerProposalCannotAuthorizeRenewal() {
        val accepted = acceptedTarget(target = 10.0, ownerRuleId = "adaptive",
            expiresAt = NOW + 4 * MINUTE_MS).copy(intent = TargetIntent.HYPO_PROTECTION)
        val renewal = proposal(source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = 10.0, intent = TargetIntent.HYPO_PROTECTION, priority = -10_000)
        val owner = proposal(target = 10.0, intent = TargetIntent.HYPO_PROTECTION,
            generatedAt = NOW - MINUTE_MS)
        val decision = manager.decide(input(
            proposals = listOf(renewal, owner),
            runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE, acceptedTarget = accepted),
            activeTarget = activeTarget(10.0)
        ))
        assertThat(decision.rejectedProposalReasons[TargetManager.RENEWAL_SOURCE_RULE_ID])
            .isEqualTo("invalid_keepalive")
    }

    @Test
    fun freshMatchingHypoOwnerProposalRemainsEligible() {
        val accepted = acceptedTarget(target = 10.0, ownerRuleId = "adaptive",
            expiresAt = NOW + 4 * MINUTE_MS).copy(intent = TargetIntent.HYPO_PROTECTION)
        val renewal = proposal(source = TargetManager.RENEWAL_SOURCE_RULE_ID,
            target = 10.0, intent = TargetIntent.HYPO_PROTECTION, priority = -10_000)
        val owner = proposal(target = 10.0, intent = TargetIntent.HYPO_PROTECTION)
        val decision = manager.decide(input(
            proposals = listOf(renewal, owner),
            runtimeState = TargetManagerRuntimeState(mode = TargetManagerMode.ACTIVE, acceptedTarget = accepted),
            activeTarget = activeTarget(10.0)
        ))
        assertThat(decision.winner).isEqualTo(owner)
        assertThat(decision.rejectedProposalReasons).doesNotContainKey(TargetManager.RENEWAL_SOURCE_RULE_ID)
    }

    private fun assertProvenanceChangeCreatesNewSemanticEvent(
        from: TargetBaseProvenance,
        to: TargetBaseProvenance
    ) {
        val first = manager.decide(input(mode = TargetManagerMode.SHADOW, baseProvenance = from))
        val second = manager.decide(
            input(
                mode = TargetManagerMode.SHADOW,
                runtimeState = first.nextRuntimeState,
                baseProvenance = to
            )
        )

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(second.semanticFingerprint).isNotEqualTo(first.semanticFingerprint)
    }

    private fun assertDeliveryDecreaseBlocked(
        runtimeState: TargetManagerRuntimeState,
        activeTarget: ActiveAapsTarget?,
        baseTarget: Double,
        proposedTarget: Double
    ) {
        val decision = manager.decide(
            input(
                proposals = listOf(proposal(source = "delivery-test", target = proposedTarget)),
                runtimeState = runtimeState,
                activeTarget = activeTarget,
                deliveryTrust = DeliveryTrustState.SUSPECTED_NONRESPONSE,
                baseTarget = baseTarget
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_DELIVERY_TRUST)
        assertThat(decision.command).isNull()
        assertThat(decision.rejectedProposalReasons["delivery-test"])
            .isEqualTo("delivery_nonresponse_blocks_target_decrease")
    }

    private fun assertInvalidGlucoseContextBlocksDecrease(
        source: String,
        currentGlucoseMmol: Double?,
        minimumPredictedOrCiMmol: Double?
    ) {
        val decision = manager.decide(
            input(
                proposals = listOf(proposal(source = source, target = 5.4)),
                currentGlucoseMmol = currentGlucoseMmol,
                minimumPredictedOrCiMmol = minimumPredictedOrCiMmol
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY)
        assertThat(decision.reasonCodes)
            .containsExactly("invalid_glucose_context_blocks_target_decrease")
        assertThat(decision.rejectedProposalReasons[source])
            .isEqualTo("invalid_glucose_context_blocks_target_decrease")
        assertThat(decision.command).isNull()
    }

    private fun assertWinnerAcrossPermutations(
        proposals: List<TargetProposal>,
        expected: TargetProposal
    ) {
        permutations(proposals).forEach { permutation ->
            val decision = manager.decide(input(proposals = permutation))

            assertThat(decision.winner).isEqualTo(expected)
        }
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = when (values.size) {
        0, 1 -> listOf(values)
        else -> values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidateIndex, _ -> candidateIndex != index })
                .map { remainder -> listOf(value) + remainder }
        }
    }

    private fun input(
        nowTs: Long = NOW,
        mode: TargetManagerMode = TargetManagerMode.ACTIVE,
        proposals: List<TargetProposal> = listOf(proposal()),
        runtimeState: TargetManagerRuntimeState = TargetManagerRuntimeState(mode = mode),
        lastSent: LastSentTempTarget? = null,
        killSwitch: Boolean = false,
        sensorTrust: SensorTrustState = SensorTrustState.TRUSTED,
        deliveryTrust: DeliveryTrustState = DeliveryTrustState.NORMAL,
        activeTarget: ActiveAapsTarget? = null,
        baseTarget: Double = 5.5,
        currentGlucoseMmol: Double? = 7.0,
        minimumPredictedOrCiMmol: Double? = 6.5,
        lowRiskThresholdMmol: Double = 4.4,
        minTargetMmol: Double = 4.0,
        maxTargetMmol: Double = 10.0,
        minDurationMinutes: Int = 15,
        maxDurationMinutes: Int = 120,
        baseProvenance: TargetBaseProvenance = provenance(),
        activitySafety: ActivityTargetSafetyContext = ActivityTargetSafetyContext(),
        safetyIobUnits: Double? = 1.0,
        copilotPriorityEnabled: Boolean = false,
        priorityRevision: Long = 0L
    ) = TargetManagerInput(
        nowTs = nowTs,
        glucoseTimestamp = nowTs - MINUTE_MS,
        therapyWatermark = nowTs - 2 * MINUTE_MS,
        mode = mode,
        proposals = proposals,
        runtimeState = runtimeState,
        activeAapsTarget = activeTarget,
        safety = TargetManagerSafetyContext(
            killSwitch = killSwitch,
            dataFresh = true,
            sensorTrust = sensorTrust,
            deliveryTrust = deliveryTrust,
            currentGlucoseMmol = currentGlucoseMmol,
            minimumPredictedOrCiMmol = minimumPredictedOrCiMmol,
            lowRiskThresholdMmol = lowRiskThresholdMmol,
            minTargetMmol = minTargetMmol,
            maxTargetMmol = maxTargetMmol,
            minDurationMinutes = minDurationMinutes,
            maxDurationMinutes = maxDurationMinutes,
            baseTargetMmol = baseTarget,
            safetyIobUnits = safetyIobUnits
        ),
        reliability = mapOf(
            5 to reliability(5),
            30 to reliability(30),
            60 to reliability(60)
        ),
        lastAutomaticSent = lastSent,
        baseProvenance = baseProvenance,
        activitySafety = activitySafety,
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext(),
        copilotPriorityEnabled = copilotPriorityEnabled,
        priorityRevision = priorityRevision
    )

    private fun proposal(
        source: String = "adaptive",
        target: Double = 5.5,
        intent: TargetIntent = TargetIntent.NORMAL_CONTROL,
        priority: Int = 100,
        fingerprint: String = "$source-input",
        confidence: Double = 0.9,
        durationMinutes: Int = 30,
        generatedAt: Long = NOW,
        reasonCodes: List<String> = listOf(source)
    ) = TargetProposal(
        sourceRuleId = source,
        intent = intent,
        targetMmol = target,
        durationMinutes = durationMinutes,
        priority = priority,
        confidence = confidence,
        reasonCodes = reasonCodes,
        generatedAt = generatedAt,
        inputFingerprint = fingerprint
    )

    private fun activityTarget(activity: ActivityTargetProposal) = proposal(
        source = "planned_activity",
        target = checkNotNull(activity.targetMmol),
        intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
        priority = 350,
        fingerprint = "planned-activity"
    ).copy(activityProposal = activity)

    private fun activityLowerProposal() = ActivityTargetProposal(
        occurrenceId = "activity-1",
        occurrenceRevision = 4L,
        intensity = PlannedActivityIntensity.MEDIUM,
        direction = ActivityTargetProposalDirection.LOWER,
        targetMmol = 4.8,
        validFromMs = NOW - MINUTE_MS,
        validUntilMs = NOW + 20 * MINUTE_MS,
        evidenceHash = "schedule-proof",
        sameCycleCandidateFingerprint = "candidate-proof",
        personalEvidenceHash = "personal-proof",
        replayHash = "replay-proof",
        blockers = emptySet()
    )

    private fun activityRaiseProposal() = ActivityTargetProposal(
        occurrenceId = "activity-1",
        occurrenceRevision = 4L,
        intensity = PlannedActivityIntensity.LIGHT,
        direction = ActivityTargetProposalDirection.RAISE,
        targetMmol = 7.7,
        validFromMs = NOW - MINUTE_MS,
        validUntilMs = NOW + 20 * MINUTE_MS,
        evidenceHash = "schedule-proof",
        protectionEvidenceHash = safePredictedFall().evidenceHash,
        personalEvidenceHash = null,
        replayHash = null,
        blockers = emptySet()
    )

    private fun activitySafety(
        activity: ActivityTargetProposal,
        observedDelta5Mmol: Double = 0.2,
        forecasts: Map<Int, ActivityForecastSafety> = mapOf(
            5 to activityForecast(5, 7.2),
            30 to activityForecast(30, 7.5),
            60 to activityForecast(60, 7.8)
        )
    ) = ActivityTargetSafetyContext(
        moduleEnabled = true,
        occurrenceId = activity.occurrenceId,
        occurrenceRevision = activity.occurrenceRevision,
        validFromMs = activity.validFromMs,
        validUntilMs = activity.validUntilMs,
        evidenceHash = activity.evidenceHash,
        sameCycleAdaptiveCandidateMmol = activity.targetMmol,
        sameCycleCandidateFingerprint = activity.sameCycleCandidateFingerprint,
        personalEvidenceHash = activity.personalEvidenceHash,
        replayHash = activity.replayHash,
        observedDelta5Mmol = observedDelta5Mmol,
        cobGrams = 0.0,
        uamActive = false,
        forecasts = forecasts
    )

    private fun activityRaiseSafety(activity: ActivityTargetProposal) = ActivityTargetSafetyContext(
        moduleEnabled = true,
        occurrenceId = activity.occurrenceId,
        occurrenceRevision = activity.occurrenceRevision,
        validFromMs = activity.validFromMs,
        validUntilMs = activity.validUntilMs,
        evidenceHash = activity.evidenceHash,
        observedDelta5Mmol = -0.1,
        cobGrams = 0.0,
        uamActive = false,
        forecasts = mapOf(
            5 to activityForecast(5, 6.8),
            30 to activityForecast(30, 6.5),
            60 to activityForecast(60, 6.2)
        ),
        safePredictedFall = safePredictedFall()
    )

    private fun activityForecast(horizon: Int, value: Double) = ActivityForecastSafety(
        horizonMinutes = horizon,
        valueMmol = value,
        ciLowMmol = value - 0.4,
        ciHighMmol = value + 0.4
    )

    private fun safePredictedFall() = ActivitySafePredictedFall(
        evidenceHash = "control-proof",
        currentGlucoseMmol = 7.0,
        observedDelta5Mmol = -0.1,
        pred5Mmol = 6.8,
        pred30Mmol = 6.5,
        pred60Mmol = 6.2,
        ciLow5Mmol = 6.4,
        ciLow30Mmol = 6.1,
        ciLow60Mmol = 5.8,
        lowRiskThresholdMmol = 4.4
    )

    private fun provenance(
        scheduleRevision: Long = 1,
        intervalId: String? = "default",
        adjustmentRunId: String? = null
    ) = TargetBaseProvenance(
        scheduleRevision = scheduleRevision,
        intervalId = intervalId,
        adjustmentRunId = adjustmentRunId
    )

    private fun activeTarget(
        target: Double,
        ownership: ActiveTargetOwnership = ActiveTargetOwnership.TARGET_MANAGER
    ) = ActiveAapsTarget(
        targetMmol = target,
        startedAt = NOW - 10 * MINUTE_MS,
        expiresAt = NOW + 20 * MINUTE_MS,
        source = "copilot",
        ownership = ownership,
        idempotencyKey = "active"
    )

    private fun gsonActiveTargetJson(
        ownershipJson: String?,
        sourceJson: String?
    ): String = buildString {
        append("{\"targetMmol\":7.0,")
        append("\"startedAt\":${NOW - 10 * MINUTE_MS},")
        append("\"expiresAt\":${NOW + 20 * MINUTE_MS},")
        sourceJson?.let { append("\"source\":$it,") }
        ownershipJson?.let { append("\"ownership\":$it,") }
        append("\"idempotencyKey\":null,")
        append("\"evidenceResolved\":true}")
    }

    private fun acceptedTarget(
        target: Double,
        revision: Long = 3,
        ownerRuleId: String = "adaptive",
        lastCommandStatus: String? = "sent",
        durationMinutes: Int = 30,
        intent: TargetIntent = TargetIntent.NORMAL_CONTROL,
        expiresAt: Long = NOW + 20 * MINUTE_MS
    ) = AcceptedTargetState(
        revision = revision,
        targetMmol = target,
        durationMinutes = durationMinutes,
        ownerRuleId = ownerRuleId,
        intent = intent,
        acceptedAt = NOW - 10 * MINUTE_MS,
        expiresAt = expiresAt,
        lastInputFingerprint = "accepted",
        lastCommandId = "accepted-command",
        lastCommandStatus = lastCommandStatus
    )

    private fun lastSent(target: Double, minutesAgo: Int) = LastSentTempTarget(
        timestamp = NOW - minutesAgo * MINUTE_MS,
        targetMmol = target,
        idempotencyKey = "last"
    )

    private fun reliability(horizon: Int) = HorizonReliability(
        horizonMinutes = horizon,
        state = HorizonReliabilityState.RELIABLE,
        sampleCount = 100,
        maeMmol = 0.5,
        biasMmol = 0.0,
        ciCoverage = 0.9,
        weightMultiplier = 1.0,
        evaluatedAt = NOW
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val MINUTE_MS = 60_000L
    }
}
