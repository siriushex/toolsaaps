package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.TargetManagerDao
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.data.local.entity.TargetManagerStateEntity
import io.aaps.copilot.domain.target.AcceptedTargetState
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetCommandCandidate
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetManager
import io.aaps.copilot.domain.target.TargetManagerDecision
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetProposal
import io.aaps.copilot.domain.target.TargetProposalFactory
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.profile.ActivityTargetProposalFactory
import io.aaps.copilot.util.ordinaryExceptionOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface TargetCommandDispatcher {
    suspend fun dispatch(command: TargetCommandCandidate): Boolean
}

internal enum class TargetCommandPreflightFailure(
    val reasonCode: String,
    val outcome: TargetDecisionOutcome
) {
    MANAGER_NOT_ACTIVE("manager_not_active", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    KILL_SWITCH("kill_switch", TargetDecisionOutcome.BLOCK_KILL_SWITCH),
    THERAPY_ACTIONS_NOT_ARMED("therapy_actions_not_armed", TargetDecisionOutcome.BLOCK_DELIVERY_TRUST),
    CURRENT_SAFETY_BOUNDS_CHANGED("current_safety_bounds_changed", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    ACCEPTED_FORECAST_LOOKUP_FAILED(
        "accepted_forecast_lookup_failed",
        TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY
    ),
    CURRENT_GLUCOSE_LOOKUP_FAILED("current_glucose_lookup_failed", TargetDecisionOutcome.BLOCK_STALE_DATA),
    CURRENT_GLUCOSE_FRESHNESS_INVALID(
        "current_glucose_freshness_invalid",
        TargetDecisionOutcome.BLOCK_STALE_DATA
    ),
    CANDIDATE_STALE("candidate_stale", TargetDecisionOutcome.BLOCK_STALE_DATA),
    CANDIDATE_SENSITIVITY_CYCLE_MISSING(
        "candidate_sensitivity_cycle_missing",
        TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY
    ),
    ACCEPTED_FORECAST_MISSING_OR_STALE(
        "accepted_forecast_missing_or_stale",
        TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY
    ),
    ACCEPTED_FORECAST_CYCLE_CHANGED(
        "accepted_forecast_cycle_changed",
        TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY
    ),
    CURRENT_GLUCOSE_MISSING("current_glucose_missing", TargetDecisionOutcome.BLOCK_STALE_DATA),
    CURRENT_GLUCOSE_STALE("current_glucose_stale", TargetDecisionOutcome.BLOCK_STALE_DATA),
    CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE(
        "current_glucose_newer_than_candidate",
        TargetDecisionOutcome.BLOCK_STALE_DATA
    ),
    BASE_PROVENANCE_MISSING("base_provenance_missing", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    SCHEDULE_REVISION_CHANGED("schedule_revision_changed", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    SCHEDULE_RESOLUTION_FAILED("schedule_resolution_failed", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    SCHEDULE_INTERVAL_CHANGED("schedule_interval_changed", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    ADJUSTMENT_RUN_LOOKUP_FAILED("adjustment_run_lookup_failed", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    UNEXPECTED_ADJUSTMENT_RUN("unexpected_adjustment_run", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    ADJUSTMENT_RUN_CHANGED("adjustment_run_changed", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS),
    ACTIVE_TARGET_LOOKUP_FAILED("active_target_lookup_failed", TargetDecisionOutcome.BLOCK_MANUAL_TARGET),
    LOCAL_SAFETY_CHRONOLOGY_UNRESOLVED(
        "local_safety_chronology_unresolved",
        TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS
    ),
    TARGET_OBSERVATION_MISSING("target_observation_missing", TargetDecisionOutcome.BLOCK_MANUAL_TARGET),
    TARGET_OBSERVATION_INVALID("target_observation_invalid", TargetDecisionOutcome.BLOCK_MANUAL_TARGET),
    ACTIVE_TARGET_OBSERVATION_CHANGED(
        "active_target_observation_changed",
        TargetDecisionOutcome.BLOCK_MANUAL_TARGET
    ),
    COPILOT_PRIORITY_POLICY_CHANGED(
        "copilot_priority_policy_changed",
        TargetDecisionOutcome.BLOCK_MANUAL_TARGET
    ),
    COPILOT_PRIORITY_REVISION_CHANGED(
        "copilot_priority_revision_changed",
        TargetDecisionOutcome.BLOCK_MANUAL_TARGET
    ),
    TARGET_OBSERVATION_EXPIRED("target_observation_expired", TargetDecisionOutcome.BLOCK_MANUAL_TARGET),
    LEGACY_TARGET_ACTIVE("legacy_target_active", TargetDecisionOutcome.BLOCK_LEGACY_TARGET_DRAIN),
    MANUAL_OR_FOREIGN_TARGET_ACTIVE(
        "manual_or_foreign_target_active",
        TargetDecisionOutcome.BLOCK_MANUAL_TARGET
    ),
    MANUAL_COMMAND_LOOKUP_FAILED("manual_command_lookup_failed", TargetDecisionOutcome.BLOCK_MANUAL_TARGET),
    MANUAL_TARGET_ACTIVE_OR_PENDING(
        "manual_target_active_or_pending",
        TargetDecisionOutcome.BLOCK_MANUAL_TARGET
    ),
    TARGET_MANAGER_SETTINGS_RECHECK_FAILED(
        "target_manager_settings_recheck_failed",
        TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS
    ),
    TARGET_MANAGER_SETTINGS_CHANGED(
        "target_manager_settings_changed",
        TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS
    ),
    TARGET_AUTHORITY_READ_TIMEOUT("target_authority_read_timeout", TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS);

    companion object {
        const val MARKER = "dispatch_preflight_blocked"

        private val byReasonCode = entries.associateBy(TargetCommandPreflightFailure::reasonCode)

        fun fromReason(reasonCode: String): TargetCommandPreflightFailure? = byReasonCode[reasonCode]

        fun requireReason(reasonCode: String): TargetCommandPreflightFailure =
            requireNotNull(fromReason(reasonCode)) { "Unsupported Target Manager preflight reason" }

        fun fromMarkedReasonCodes(reasonCodes: List<String>): TargetCommandPreflightFailure? {
            val markerIndex = reasonCodes.indexOfLast { it == MARKER }
            if (markerIndex < 0) return null
            return reasonCodes.getOrNull(markerIndex + 1)?.let(::fromReason)
        }
    }
}

internal class TargetCommandPreflightBlockedException(
    val failure: TargetCommandPreflightFailure
) : Exception(failure.reasonCode)

fun interface TargetDeliveryStatusProvider {
    suspend fun status(idempotencyKey: String): String?
}

class TargetManagerRepository(
    private val dao: TargetManagerDao,
    private val gson: Gson,
    private val dispatcher: TargetCommandDispatcher,
    private val manager: TargetManager = TargetManager(),
    private val proposalFactory: TargetProposalFactory = TargetProposalFactory(),
    private val activityProposalFactory: ActivityTargetProposalFactory = ActivityTargetProposalFactory(),
    private val deliveryStatusProvider: TargetDeliveryStatusProvider? = null
) {
    suspend fun evaluateAndDispatch(input: TargetManagerInput): TargetManagerDecision = evaluate(
        input = input,
        dispatchAllowed = true
    )

    suspend fun evaluateReadOnly(input: TargetManagerInput): TargetManagerDecision = evaluate(
        input = input,
        dispatchAllowed = false
    )

    private suspend fun evaluate(
        input: TargetManagerInput,
        dispatchAllowed: Boolean
    ): TargetManagerDecision = PROCESS_MUTEX.withLock {
        if (dispatchAllowed) recoverConfirmedAbsentKnownMismatchQuarantine(input)
        val reconciliation = if (dispatchAllowed) reconcilePendingLocked(input) else PendingReconciliation()
        reconciliation.blocked?.let { return@withLock it }
        val pendingRetry = reconciliation.retry

        val loaded = try {
            loadRuntime(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            if (!dispatchAllowed) {
                return@withLock readOnlyFailClosed(
                    input = input,
                    runtime = input.runtimeState,
                    reason = "read_only_state_lookup_failed"
                )
            }
            throw failure
        }
        if (loaded.quarantineReason != null) {
            if (!dispatchAllowed) {
                return@withLock readOnlyFailClosed(
                    input = input,
                    runtime = loaded.runtime,
                    reason = "runtime_state_quarantined:${loaded.quarantineReason}"
                )
            }
            if (loaded.persistedState?.reconciliationStatus?.startsWith(STATUS_QUARANTINED) == true) {
                return@withLock quarantinedRuntimeDecision(
                    input,
                    loaded.runtime,
                    "runtime_state_quarantined:${loaded.quarantineReason}"
                )
            }
            return@withLock failClosed(
                input = input,
                runtime = loaded.runtime,
                reason = "runtime_state_quarantined:${loaded.quarantineReason}",
                reconciliationStatus = STATUS_QUARANTINED,
                persistedState = loaded.persistedState
            )
        }
        val persistedRuntime = loaded.runtime

        val virtualShadowTarget = persistedRuntime.acceptedTarget
            ?.takeIf {
                input.mode == TargetManagerMode.SHADOW &&
                    it.lastCommandStatus == STATUS_SHADOW &&
                    it.expiresAt > input.nowTs
            }
            ?.let { accepted ->
                ActiveAapsTarget(
                    targetMmol = accepted.targetMmol,
                    startedAt = accepted.acceptedAt,
                    expiresAt = accepted.expiresAt,
                    source = "target_manager_shadow",
                    ownership = ActiveTargetOwnership.TARGET_MANAGER,
                    idempotencyKey = accepted.lastCommandId
                )
            }
        val sensorRelease = proposalFactory.sensorSafetyRelease(
            activeTarget = if (input.mode == TargetManagerMode.SHADOW) {
                virtualShadowTarget
            } else {
                input.activeAapsTarget
            },
            baseTargetMmol = input.safety.baseTargetMmol,
            sensorBlocked = input.safety.sensorTrust in setOf(
                SensorTrustState.RESTRICTED,
                SensorTrustState.BLOCKED
            ),
            sensorReason = input.safety.sensorTrust.name.lowercase(),
            generatedAt = input.nowTs,
            inputFingerprint = "sensor:${input.glucoseTimestamp}:${input.safety.sensorTrust.name}:" +
                "${persistedRuntime.acceptedTarget?.revision ?: 0L}"
        )
        val keepalive = persistedRuntime.acceptedTarget
            ?.takeIf { accepted ->
                when {
                    accepted.ownerRuleId == TargetProposalFactory.PLANNED_ACTIVITY_RETURN_SOURCE -> false
                    accepted.intent == TargetIntent.PLANNED_ACTIVITY_ADAPTATION -> false
                    else -> true
                }
            }
            ?.let { accepted ->
                proposalFactory.keepalive(
                    accepted = accepted,
                    nowTs = input.nowTs,
                    sensorTrust = input.safety.sensorTrust,
                    mode = input.mode,
                    inputFingerprint = "keepalive:${accepted.revision}:${accepted.expiresAt}"
                )
            }
        val freshPlannedActivityProposal = input.proposals.singleOrNull { proposal ->
            proposal.sourceRuleId == ActivityTargetProposalFactory.SOURCE_RULE_ID &&
                proposal.intent == TargetIntent.PLANNED_ACTIVITY_ADAPTATION &&
                proposal.generatedAt == input.nowTs &&
                proposal.activityProposal != null
        }
        val plannedActivityKeepalive = persistedRuntime.acceptedTarget
            ?.takeIf { accepted ->
                accepted.intent == TargetIntent.PLANNED_ACTIVITY_ADAPTATION &&
                    input.activitySafety.keepaliveAllowed
            }
            ?.let { accepted ->
                activityProposalFactory.renewal(
                    accepted = accepted,
                    freshProposal = freshPlannedActivityProposal,
                    nowTs = input.nowTs
                )
            }
        val plannedActivityReturn = proposalFactory.plannedActivityReturnToBase(
            accepted = persistedRuntime.acceptedTarget,
            input = input
        )
        val proposals = if (plannedActivityKeepalive != null && freshPlannedActivityProposal != null) {
            input.proposals.filterNot { it === freshPlannedActivityProposal }
        } else {
            input.proposals
        }
        val effectiveInput = input.copy(
            runtimeState = persistedRuntime,
            activeAapsTarget = input.activeAapsTarget,
            proposals = proposals + listOfNotNull(
                sensorRelease,
                plannedActivityReturn,
                plannedActivityKeepalive,
                keepalive
            )
        )
        val decision = try {
            manager.decide(effectiveInput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ArithmeticException) {
            if (!dispatchAllowed) {
                return@withLock readOnlyFailClosed(input, persistedRuntime, "checked_arithmetic_failed")
            }
            return@withLock failClosed(
                input,
                persistedRuntime,
                "checked_arithmetic_failed",
                "manager_error"
            )
        } catch (_: IllegalArgumentException) {
            if (!dispatchAllowed) {
                return@withLock readOnlyFailClosed(input, persistedRuntime, "manager_validation_failed")
            }
            return@withLock failClosed(
                input,
                persistedRuntime,
                "manager_validation_failed",
                "manager_error"
            )
        } catch (_: IllegalStateException) {
            if (!dispatchAllowed) {
                return@withLock readOnlyFailClosed(input, persistedRuntime, "manager_state_failed")
            }
            return@withLock failClosed(
                input,
                persistedRuntime,
                "manager_state_failed",
                "manager_error"
            )
        }
        val semanticFingerprint = decision.semanticFingerprint
            ?: gateFingerprint(effectiveInput, decision.outcome)
        val resolvedDecision = decision.copy(semanticFingerprint = semanticFingerprint)
        if (pendingRetry != null) {
            return@withLock handlePendingRetry(
                input = input,
                retry = pendingRetry,
                currentDecision = resolvedDecision,
                priorRuntime = persistedRuntime
            )
        }
        val entity = resolvedDecision.toEntity(effectiveInput, semanticFingerprint)
            ?: if (!dispatchAllowed) {
                return@withLock readOnlyFailClosed(
                    input,
                    persistedRuntime,
                    "journal_payload_oversized"
                )
            } else {
                return@withLock failClosed(
                    input,
                    persistedRuntime,
                    "journal_payload_oversized",
                    "journal_rejected"
                )
            }

        if (!dispatchAllowed) {
            val diagnosticFingerprint = diagnosticFingerprint(
                mode = input.mode,
                semanticFingerprint = semanticFingerprint,
                timestamp = input.nowTs
            )
            return@withLock persistReadOnlyDiagnostic(
                input = input,
                runtime = persistedRuntime,
                decision = resolvedDecision,
                entity = entity.copy(
                    id = "${input.mode.name}:$diagnosticFingerprint",
                    semanticFingerprint = diagnosticFingerprint,
                    commandJson = null,
                    deliveryStatus = STATUS_READ_ONLY
                ),
                liveSemanticFingerprint = semanticFingerprint
            )
        }

        val command = resolvedDecision.command
        if (command == null) {
            val state = stateEntity(
                runtime = resolvedDecision.nextRuntimeState,
                updatedAt = input.nowTs,
                reconciliationStatus = if (input.mode == TargetManagerMode.SHADOW) STATUS_SHADOW else "evaluated"
            ) ?: return@withLock failClosed(
                input,
                persistedRuntime,
                "runtime_payload_oversized",
                "journal_rejected"
            )
            val inserted = if (input.mode == TargetManagerMode.OFF) {
                dao.insertDecision(entity)
            } else {
                dao.insertDecisionAndState(entity, state)
            }
            if (inserted == -1L) {
                return@withLock semanticDuplicate(resolvedDecision, semanticFingerprint, persistedRuntime)
            }
            return@withLock resolvedDecision
        }

        val pendingState = checkNotNull(resolvedDecision.nextRuntimeState.acceptedTarget)
        val envelope = PendingCommandEnvelope(command, pendingState)
        val commandJson = boundedJson(envelope)
            ?: return@withLock failClosed(
                input,
                persistedRuntime,
                "journal_payload_oversized",
                "journal_rejected"
            )
        val pendingEntity = entity.copy(commandJson = commandJson, deliveryStatus = STATUS_PENDING)
        validatePendingEnvelope(envelope, pendingEntity, input.mode, persistedRuntime)?.let { reason ->
            return@withLock failClosed(input, persistedRuntime, reason, "journal_rejected")
        }
        if (dao.insertPendingDecision(pendingEntity) == -1L) {
            val existing = dao.decisionByFingerprint(input.mode.name, semanticFingerprint)
                ?: return@withLock failClosed(
                    input,
                    persistedRuntime,
                    "decision_uniqueness_conflict",
                    "journal_conflict"
                )
            return@withLock reconcileExistingDecision(
                input,
                existing,
                persistedRuntime,
                resolvedDecision
            )
        }

        return@withLock dispatchAndFinalize(
            input = input,
            entity = pendingEntity,
            envelope = envelope,
            priorRuntime = persistedRuntime,
            baseDecision = resolvedDecision
        )
    }

    suspend fun pruneDecisions(nowTs: Long): Int = PROCESS_MUTEX.withLock {
        dao.pruneReadOnlyDiagnostics(
            nowTs = nowTs,
            retentionMs = DECISION_RETENTION_MS,
            maxRows = READ_ONLY_DIAGNOSTIC_MAX_ROWS
        )
    }

    private suspend fun reconcilePendingLocked(input: TargetManagerInput): PendingReconciliation {
        for (pending in dao.pendingDecisions()) {
            val mode = ordinaryExceptionOrNull { TargetManagerMode.valueOf(pending.mode) }
                ?: return PendingReconciliation(
                    blocked = quarantinePending(input, pending, "pending_mode_invalid", input.mode)
                )
            val loaded = loadRuntimeForMode(mode)
            if (loaded.quarantineReason != null) {
                return PendingReconciliation(
                    blocked = unresolvedPending(input, "pending_runtime_quarantined")
                )
            }
            val priorRuntime = loaded.runtime
            val envelope = parseEnvelope(pending.commandJson)
                ?: return PendingReconciliation(
                    blocked = quarantinePending(input, pending, "pending_envelope_invalid", mode)
                )
            val envelopeReason = validatePendingEnvelope(envelope, pending, mode, priorRuntime)
            if (envelopeReason != null) {
                return PendingReconciliation(
                    blocked = quarantinePending(input, pending, envelopeReason, mode)
                )
            }
            val status = deliveryStatus(envelope.command.idempotencyKey)
                ?: return PendingReconciliation(
                    blocked = unresolvedPending(input, "pending_delivery_unresolved")
                )
            when (status) {
                STATUS_SENT -> {
                    val confirmed = envelope.acceptedTarget.copy(lastCommandStatus = STATUS_SENT)
                    val runtime = priorRuntime.copy(
                        acceptedTarget = confirmed,
                        lastDecisionFingerprint = pending.semanticFingerprint,
                        reconciliationStatus = "reconciled_sent"
                    )
                    val state = stateEntity(runtime, input.nowTs, "reconciled_sent")
                        ?: return PendingReconciliation(
                            blocked = quarantinePending(input, pending, "pending_state_oversized", mode)
                        )
                    if (dao.updateDecisionAndState(pending.copy(deliveryStatus = STATUS_SENT), state) != 1) {
                        return PendingReconciliation(
                            blocked = persistenceFailure(
                                input,
                                pending,
                                mode,
                                priorRuntime,
                                "pending_sent_persistence_failed"
                            )
                        )
                    }
                }
                STATUS_ABSENT -> {
                    if (!pendingMayBeRetried(pending, input.nowTs)) {
                        return PendingReconciliation(
                            blocked = unresolvedPending(input, "pending_delivery_unresolved")
                        )
                    }
                    return PendingReconciliation(
                        retry = PendingRetry(pending, envelope, mode, priorRuntime)
                    )
                }
                STATUS_PENDING,
                STATUS_UNKNOWN -> return PendingReconciliation(
                    blocked = unresolvedPending(input, "pending_delivery_unresolved")
                )
                else -> return PendingReconciliation(
                    blocked = unresolvedPending(input, "pending_delivery_status_invalid")
                )
            }
        }
        return PendingReconciliation()
    }

    private suspend fun recoverConfirmedAbsentKnownMismatchQuarantine(input: TargetManagerInput) {
        if (input.mode != TargetManagerMode.ACTIVE) return
        val state = dao.state(input.mode.name) ?: return
        if (state.reconciliationStatus != QUARANTINE_KNOWN_MISMATCH) return
        val decision = dao.latestQuarantinedDecision(input.mode.name) ?: return
        val accepted = state.acceptedTargetJson?.let { raw ->
            if (utf8Size(raw) > MAX_JSON_BYTES) return
            targetManagerJsonOrNull { gson.fromJson(raw, AcceptedTargetState::class.java) } ?: return
        }
        if (accepted?.let { validateAccepted(it, input.mode) } != null) return
        val priorRuntime = TargetManagerRuntimeState(
            mode = input.mode,
            acceptedTarget = accepted,
            lastDecisionFingerprint = state.lastDecisionFingerprint,
            lastSafetyBypassFingerprint = state.lastSafetyBypassFingerprint,
            reconciliationStatus = state.reconciliationStatus
        )
        val envelope = parseEnvelope(decision.commandJson) ?: return
        if (validatePendingEnvelope(envelope, decision, input.mode, priorRuntime) != null) return

        when (deliveryStatus(envelope.command.idempotencyKey)) {
            STATUS_SENT -> {
                val confirmed = envelope.acceptedTarget.copy(lastCommandStatus = STATUS_SENT)
                val runtime = priorRuntime.copy(
                    acceptedTarget = confirmed,
                    lastDecisionFingerprint = decision.semanticFingerprint,
                    reconciliationStatus = STATUS_RECOVERED_SENT
                )
                val recoveredState = stateEntity(runtime, input.nowTs, STATUS_RECOVERED_SENT) ?: return
                dao.updateDecisionAndState(
                    decision.copy(deliveryStatus = STATUS_SENT),
                    recoveredState
                )
            }
            STATUS_ABSENT -> {
                val runtime = priorRuntime.copy(reconciliationStatus = STATUS_RECOVERED_ABSENT)
                val recoveredState = stateEntity(runtime, input.nowTs, STATUS_RECOVERED_ABSENT) ?: return
                dao.updateDecisionAndState(
                    decision.copy(deliveryStatus = STATUS_SUPERSEDED),
                    recoveredState
                )
            }
        }
    }

    private suspend fun handlePendingRetry(
        input: TargetManagerInput,
        retry: PendingRetry,
        currentDecision: TargetManagerDecision,
        priorRuntime: TargetManagerRuntimeState
    ): TargetManagerDecision {
        val currentCommand = currentDecision.command
        val matchesCurrentDecision = retry.mode == input.mode &&
            currentDecision.semanticFingerprint == retry.entity.semanticFingerprint &&
            currentCommand != null &&
            commandsExactlyMatch(currentCommand, retry.envelope.command)
        if (!matchesCurrentDecision) {
            val supersededRuntime = retry.priorRuntime.copy(reconciliationStatus = "pending_superseded")
            val state = stateEntity(supersededRuntime, input.nowTs, "pending_superseded")
                ?: return quarantinePending(
                    input,
                    retry.entity,
                    "pending_supersede_state_oversized",
                    retry.mode
                )
            if (dao.updateDecisionAndState(
                    retry.entity.copy(deliveryStatus = STATUS_FAILED),
                    state
                ) != 1
            ) {
                return persistenceFailure(
                    input,
                    retry.entity,
                    retry.mode,
                    retry.priorRuntime,
                    "pending_supersede_persistence_failed"
                )
            }
            return currentDecision.copy(
                command = null,
                reasonCodes = currentDecision.reasonCodes + "pending_retry_rejected_current_context",
                nextRuntimeState = priorRuntime.copy(reconciliationStatus = "pending_superseded")
            )
        }
        val refreshedAccepted = currentDecision.nextRuntimeState.acceptedTarget
            ?: return failClosed(
                input,
                priorRuntime,
                "pending_retry_accepted_target_missing",
                "journal_rejected"
            )
        val refreshedEnvelope = PendingCommandEnvelope(currentCommand, refreshedAccepted)
        val refreshedJson = boundedJson(refreshedEnvelope)
            ?: return failClosed(
                input,
                priorRuntime,
                "pending_retry_payload_oversized",
                "journal_rejected"
            )
        val refreshedEntity = retry.entity.copy(
            timestamp = input.nowTs,
            commandJson = refreshedJson,
            deliveryStatus = STATUS_PENDING
        )
        validatePendingEnvelope(refreshedEnvelope, refreshedEntity, retry.mode, priorRuntime)?.let { reason ->
            return quarantinePending(input, retry.entity, reason, retry.mode)
        }
        if (dao.updatePendingDecision(refreshedEntity) != 1) {
            return persistenceFailure(
                input,
                retry.entity,
                retry.mode,
                priorRuntime,
                "pending_retry_refresh_persistence_failed"
            )
        }
        return dispatchAndFinalize(
            input = input,
            entity = refreshedEntity,
            envelope = refreshedEnvelope,
            priorRuntime = retry.priorRuntime,
            baseDecision = currentDecision
        )
    }

    private suspend fun reconcileExistingDecision(
        input: TargetManagerInput,
        existing: TargetManagerDecisionEntity,
        priorRuntime: TargetManagerRuntimeState,
        currentDecision: TargetManagerDecision
    ): TargetManagerDecision {
        if (existing.deliveryStatus !in setOf(STATUS_PENDING, STATUS_FAILED)) {
            return semanticDuplicate(restoredDecision(existing, priorRuntime), existing.semanticFingerprint, priorRuntime)
        }
        val mode = ordinaryExceptionOrNull { TargetManagerMode.valueOf(existing.mode) }
            ?: return quarantinePending(input, existing, "existing_mode_invalid", input.mode)
        val envelope = parseEnvelope(existing.commandJson)
            ?: return quarantinePending(input, existing, "existing_envelope_invalid", mode)
        validatePendingEnvelope(envelope, existing, mode, priorRuntime)?.let { reason ->
            return quarantinePending(input, existing, reason, mode)
        }
        val currentCommand = currentDecision.command
        if (currentDecision.semanticFingerprint != existing.semanticFingerprint) {
            return quarantinePending(input, existing, "existing_envelope_current_mismatch", mode)
        }
        if (currentCommand == null) {
            return currentDecision.copy(
                command = null,
                reasonCodes = currentDecision.reasonCodes + "existing_retry_rejected_current_context",
                nextRuntimeState = priorRuntime
            )
        }
        if (!commandsExactlyMatch(currentCommand, envelope.command)) {
            return quarantinePending(input, existing, "existing_envelope_current_mismatch", mode)
        }
        return when (deliveryStatus(envelope.command.idempotencyKey)) {
            STATUS_SENT -> {
                val confirmed = envelope.acceptedTarget.copy(lastCommandStatus = STATUS_SENT)
                val runtime = priorRuntime.copy(
                    acceptedTarget = confirmed,
                    lastDecisionFingerprint = existing.semanticFingerprint,
                    reconciliationStatus = "reconciled_sent"
                )
                val state = stateEntity(runtime, input.nowTs, "reconciled_sent")
                    ?: return failClosed(input, priorRuntime, "runtime_payload_oversized", "journal_rejected")
                if (dao.updateDecisionAndState(existing.copy(deliveryStatus = STATUS_SENT), state) != 1) {
                    return persistenceFailure(
                        input,
                        existing,
                        mode,
                        priorRuntime,
                        "existing_sent_persistence_failed"
                    )
                }
                restoredDecision(existing.copy(deliveryStatus = STATUS_SENT), runtime).copy(
                    reasonCodes = listOf("delivery_reconciled_sent")
                )
            }
            STATUS_ABSENT -> {
                if (existing.deliveryStatus == STATUS_PENDING && !pendingMayBeRetried(existing, input.nowTs)) {
                    return unresolvedPending(input, "pending_delivery_unresolved")
                }
                val refreshedAccepted = currentDecision.nextRuntimeState.acceptedTarget
                    ?: return failClosed(
                        input,
                        priorRuntime,
                        "existing_retry_accepted_target_missing",
                        "journal_rejected"
                    )
                val refreshedEnvelope = PendingCommandEnvelope(currentCommand, refreshedAccepted)
                val refreshedJson = boundedJson(refreshedEnvelope)
                    ?: return failClosed(
                        input,
                        priorRuntime,
                        "existing_retry_payload_oversized",
                        "journal_rejected"
                    )
                val pending = existing.copy(
                    timestamp = input.nowTs,
                    commandJson = refreshedJson,
                    reasonCodesJson = if (persistedPreflightFailure(existing) != null) {
                        boundedJson(currentDecision.reasonCodes) ?: existing.reasonCodesJson
                    } else existing.reasonCodesJson,
                    deliveryStatus = STATUS_PENDING
                )
                validatePendingEnvelope(refreshedEnvelope, pending, mode, priorRuntime)?.let { reason ->
                    return quarantinePending(input, existing, reason, mode)
                }
                if (dao.updatePendingDecision(pending) != 1) {
                    return persistenceFailure(
                        input,
                        existing,
                        mode,
                        priorRuntime,
                        "existing_retry_persistence_failed"
                    )
                }
                dispatchAndFinalize(
                    input,
                    pending,
                    refreshedEnvelope,
                    priorRuntime,
                    currentDecision
                )
            }
            else -> unresolvedPending(input, "pending_delivery_unresolved")
        }
    }

    private suspend fun dispatchAndFinalize(
        input: TargetManagerInput,
        entity: TargetManagerDecisionEntity,
        envelope: PendingCommandEnvelope,
        priorRuntime: TargetManagerRuntimeState,
        baseDecision: TargetManagerDecision
    ): TargetManagerDecision {
        var preflightFailure: TargetCommandPreflightFailure? = null
        val delivered = try {
            dispatcher.dispatch(envelope.command)
        } catch (blocked: TargetCommandPreflightBlockedException) {
            preflightFailure = blocked.failure
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return baseDecision.copy(
                outcome = TargetDecisionOutcome.DELIVERY_FAILED,
                command = null,
                reasonCodes = baseDecision.reasonCodes + "delivery_status_unknown",
                nextRuntimeState = priorRuntime.copy(reconciliationStatus = "pending_unresolved")
            )
        }
        if (delivered) {
            val confirmedAccepted = envelope.acceptedTarget.copy(lastCommandStatus = STATUS_SENT)
            val confirmedRuntime = priorRuntime.copy(
                acceptedTarget = confirmedAccepted,
                lastDecisionFingerprint = entity.semanticFingerprint,
                reconciliationStatus = "confirmed"
            )
            val state = stateEntity(confirmedRuntime, input.nowTs, "confirmed")
                ?: return failClosed(input, priorRuntime, "runtime_payload_oversized", "journal_rejected")
            if (dao.updateDecisionAndState(entity.copy(deliveryStatus = STATUS_SENT), state) != 1) {
                return persistenceFailure(
                    input,
                    entity,
                    input.mode,
                    priorRuntime,
                    "delivery_sent_persistence_failed"
                )
            }
            return baseDecision.copy(
                command = null,
                nextRuntimeState = confirmedRuntime
            )
        }

        val failedRuntime = priorRuntime.copy(reconciliationStatus = "delivery_failed")
        val state = stateEntity(failedRuntime, input.nowTs, "delivery_failed")
            ?: return failClosed(input, priorRuntime, "runtime_payload_oversized", "journal_rejected")
        // Known pre-POST refusal shares the original failed/absent retry journal, with typed diagnostics only.
        val reasonCodes = preflightFailure?.let { failure ->
            baseDecision.reasonCodes + listOf(TargetCommandPreflightFailure.MARKER, failure.reasonCode)
        } ?: baseDecision.reasonCodes
        val failedEntity = entity.copy(
            deliveryStatus = STATUS_FAILED,
            reasonCodesJson = if (preflightFailure != null) {
                boundedJson(reasonCodes) ?: return persistenceFailure(
                    input, entity, input.mode, priorRuntime, "dispatch_preflight_reason_persistence_failed"
                )
            } else entity.reasonCodesJson
        )
        if (dao.updateDecisionAndState(failedEntity, state) != 1) {
            return persistenceFailure(
                input,
                entity,
                input.mode,
                priorRuntime,
                "delivery_failed_persistence_failed"
            )
        }
        return baseDecision.copy(
            outcome = TargetDecisionOutcome.DELIVERY_FAILED,
            command = null,
            reasonCodes = reasonCodes + "delivery_failed",
            nextRuntimeState = failedRuntime
        )
    }

    private suspend fun loadRuntime(input: TargetManagerInput): LoadedRuntime {
        if (input.mode == TargetManagerMode.OFF) {
            return LoadedRuntime(TargetManagerRuntimeState(TargetManagerMode.OFF), null, null)
        }
        return loadRuntimeForMode(input.mode)
    }

    private suspend fun loadRuntimeForMode(mode: TargetManagerMode): LoadedRuntime {
        val entity = dao.state(mode.name)
            ?: return LoadedRuntime(TargetManagerRuntimeState(mode), null, null)
        if (
            entity.mode != mode.name ||
            entity.reconciliationStatus.isBlank() ||
            utf8Size(entity.reconciliationStatus) > MAX_DIAGNOSTIC_BYTES ||
            entity.lastDecisionFingerprint?.let(::utf8Size)?.let { it > MAX_JSON_BYTES } == true ||
            entity.lastSafetyBypassFingerprint?.let(::utf8Size)?.let { it > MAX_JSON_BYTES } == true
        ) {
            return LoadedRuntime(TargetManagerRuntimeState(mode), "state_metadata_invalid", entity)
        }
        if (entity.reconciliationStatus.startsWith(STATUS_QUARANTINED)) {
            val persistedReason = entity.reconciliationStatus
                .removePrefix("$STATUS_QUARANTINED:")
                .removePrefix("runtime_state_quarantined:")
                .ifBlank { "persisted_quarantine" }
            return LoadedRuntime(
                TargetManagerRuntimeState(mode, reconciliationStatus = entity.reconciliationStatus),
                persistedReason,
                entity
            )
        }
        val acceptedJson = entity.acceptedTargetJson
        val accepted = if (acceptedJson == null) {
            null
        } else {
            if (utf8Size(acceptedJson) > MAX_JSON_BYTES) {
                return LoadedRuntime(TargetManagerRuntimeState(mode), "accepted_json_oversized", entity)
            }
            targetManagerJsonOrNull { gson.fromJson(acceptedJson, AcceptedTargetState::class.java) }
                ?: return LoadedRuntime(TargetManagerRuntimeState(mode), "accepted_json_invalid", entity)
        }
        val invalid = accepted?.let { validateAccepted(it, mode) }
        if (invalid != null) return LoadedRuntime(TargetManagerRuntimeState(mode), invalid, entity)
        return LoadedRuntime(
            TargetManagerRuntimeState(
                mode = mode,
                acceptedTarget = accepted,
                lastDecisionFingerprint = entity.lastDecisionFingerprint,
                lastSafetyBypassFingerprint = entity.lastSafetyBypassFingerprint,
                reconciliationStatus = entity.reconciliationStatus
            ),
            null,
            entity
        )
    }

    private fun validateAccepted(
        accepted: AcceptedTargetState,
        mode: TargetManagerMode,
        allowedStatuses: Set<String>? = null
    ): String? {
        return try {
            if (accepted.revision !in 0 until Long.MAX_VALUE) return "accepted_revision_invalid"
            if (!accepted.targetMmol.isFinite() || accepted.targetMmol <= 0.0) return "accepted_target_invalid"
            if (accepted.durationMinutes !in MIN_PERSISTED_DURATION_MINUTES..MAX_PERSISTED_DURATION_MINUTES) {
                return "accepted_duration_invalid"
            }
            if (accepted.acceptedAt < 0L || accepted.expiresAt <= accepted.acceptedAt) {
                return "accepted_timestamp_invalid"
            }
            val expectedExpiry = ordinaryExceptionOrNull {
                Math.addExact(
                    accepted.acceptedAt,
                    Math.multiplyExact(accepted.durationMinutes.toLong(), MINUTE_MS)
                )
            } ?: return "accepted_timestamp_overflow"
            if (expectedExpiry != accepted.expiresAt) return "accepted_duration_mismatch"
            accepted.intent.name
            if (
                accepted.ownerRuleId.isBlank() ||
                accepted.lastInputFingerprint.isBlank() ||
                accepted.lastCommandId.isNullOrBlank()
            ) return "accepted_ownership_invalid"
            if (
                utf8Size(accepted.ownerRuleId) > MAX_DIAGNOSTIC_BYTES ||
                utf8Size(accepted.lastInputFingerprint) > MAX_JSON_BYTES ||
                utf8Size(accepted.lastCommandId) > MAX_JSON_BYTES
            ) return "accepted_metadata_oversized"
            val validStatuses = allowedStatuses ?: when (mode) {
                TargetManagerMode.ACTIVE -> setOf(STATUS_SENT)
                TargetManagerMode.SHADOW -> setOf(STATUS_SHADOW)
                TargetManagerMode.OFF -> return "accepted_off_mode_invalid"
            }
            if (accepted.lastCommandStatus !in validStatuses) return "accepted_status_invalid"
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "accepted_shape_invalid"
        }
    }

    private fun validatePendingEnvelope(
        envelope: PendingCommandEnvelope,
        entity: TargetManagerDecisionEntity,
        mode: TargetManagerMode,
        priorRuntime: TargetManagerRuntimeState
    ): String? {
        return try {
            if (mode != TargetManagerMode.ACTIVE) return "pending_mode_not_active"
            if (entity.mode != mode.name) return "pending_entity_mode_mismatch"
            if (entity.id != "${mode.name}:${entity.semanticFingerprint}") {
                return "pending_entity_id_mismatch"
            }
            if (entity.outcome !in setOf(
                    TargetDecisionOutcome.SEND.name,
                    TargetDecisionOutcome.RENEW_SAME_TARGET.name
                )
            ) return "pending_entity_outcome_invalid"
            validateAccepted(
                accepted = envelope.acceptedTarget,
                mode = mode,
                allowedStatuses = setOf(STATUS_PENDING)
            )?.let { return it }
            if (envelope.acceptedTarget.acceptedAt != entity.timestamp) {
                return "pending_timestamp_mismatch"
            }
            val expectedRevision = Math.addExact(
                priorRuntime.acceptedTarget?.revision ?: 0L,
                1L
            )
            if (envelope.acceptedTarget.revision != expectedRevision) {
                return "pending_revision_mismatch"
            }
            val command = envelope.command
            command.intent.name
            val expectedCommandId = "$TARGET_MANAGER_IDEMPOTENCY_PREFIX${entity.semanticFingerprint}"
            if (
                !command.targetMmol.isFinite() ||
                command.targetMmol <= 0.0 ||
                command.durationMinutes !in MIN_PERSISTED_DURATION_MINUTES..MAX_PERSISTED_DURATION_MINUTES ||
                command.ownerRuleId.isBlank() ||
                command.reason.isBlank() ||
                command.idempotencyKey != expectedCommandId ||
                command.semanticFingerprint.isBlank() ||
                command.generatedAt <= 0L ||
                command.generatedAt > entity.timestamp
            ) return "pending_command_invalid"
            val provenance = command.baseProvenance ?: return "pending_base_provenance_missing"
            if (
                provenance.scheduleRevision < 0L ||
                provenance.intervalId?.isBlank() == true ||
                provenance.adjustmentRunId?.isBlank() == true
            ) return "pending_base_provenance_invalid"
            if (
                command.targetMmol.toRawBits() != envelope.acceptedTarget.targetMmol.toRawBits() ||
                command.durationMinutes != envelope.acceptedTarget.durationMinutes ||
                command.ownerRuleId != envelope.acceptedTarget.ownerRuleId ||
                command.intent != envelope.acceptedTarget.intent ||
                command.idempotencyKey != envelope.acceptedTarget.lastCommandId ||
                command.semanticFingerprint != entity.semanticFingerprint
            ) return "pending_envelope_mismatch"
            if (
                utf8Size(command.ownerRuleId) > MAX_DIAGNOSTIC_BYTES ||
                utf8Size(command.reason) > MAX_JSON_BYTES ||
                utf8Size(command.idempotencyKey) > MAX_JSON_BYTES ||
                utf8Size(command.semanticFingerprint) > MAX_JSON_BYTES
            ) return "pending_command_oversized"
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "pending_envelope_invalid"
        }
    }

    private fun commandsExactlyMatch(
        left: TargetCommandCandidate,
        right: TargetCommandCandidate
    ): Boolean = try {
        left.targetMmol.toRawBits() == right.targetMmol.toRawBits() &&
            left.durationMinutes == right.durationMinutes &&
            left.ownerRuleId == right.ownerRuleId &&
            left.intent == right.intent &&
            left.reason == right.reason &&
            left.idempotencyKey == right.idempotencyKey &&
            left.semanticFingerprint == right.semanticFingerprint &&
            left.baseProvenance == right.baseProvenance &&
            left.targetObservation == right.targetObservation &&
            left.sensitivityCycleId == right.sensitivityCycleId
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun persistReadOnlyDiagnostic(
        input: TargetManagerInput,
        runtime: TargetManagerRuntimeState,
        decision: TargetManagerDecision,
        entity: TargetManagerDecisionEntity,
        liveSemanticFingerprint: String,
        suppressDiagnosticDuplicate: Boolean = true
    ): TargetManagerDecision {
        val inserted = try {
            dao.insertReadOnlyDiagnosticAndPrune(
                decision = entity,
                retentionMs = DECISION_RETENTION_MS,
                maxRows = READ_ONLY_DIAGNOSTIC_MAX_ROWS
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return readOnlyPersistenceFailure(input, runtime)
        }
        if (inserted == -1L && suppressDiagnosticDuplicate) {
            return semanticDuplicate(decision, liveSemanticFingerprint, runtime)
        }
        return decision.copy(nextRuntimeState = runtime)
    }

    private suspend fun readOnlyFailClosed(
        input: TargetManagerInput,
        runtime: TargetManagerRuntimeState,
        reason: String
    ): TargetManagerDecision {
        val safeReason = reason.take(MAX_DIAGNOSTIC_BYTES)
        val liveFingerprint = gateFingerprint(
            input,
            TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
            safeReason
        )
        val diagnosticFingerprint = diagnosticFingerprint(
            mode = input.mode,
            semanticFingerprint = liveFingerprint,
            timestamp = input.nowTs
        )
        val decision = TargetManagerDecision(
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
            winner = null,
            command = null,
            semanticFingerprint = liveFingerprint,
            cadenceOutcome = null,
            cadenceReason = null,
            reasonCodes = listOf(safeReason),
            rejectedProposalReasons = emptyMap(),
            nextRuntimeState = runtime
        )
        val entity = TargetManagerDecisionEntity(
            id = "${input.mode.name}:$diagnosticFingerprint",
            timestamp = input.nowTs,
            mode = input.mode.name,
            semanticFingerprint = diagnosticFingerprint,
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS.name,
            winnerJson = null,
            commandJson = null,
            cadenceOutcome = null,
            cadenceReason = null,
            lastSentTargetMmol = input.lastAutomaticSent?.targetMmol,
            lastSentTimestamp = input.lastAutomaticSent?.timestamp,
            deliveryStatus = STATUS_READ_ONLY,
            reasonCodesJson = checkNotNull(boundedJson(listOf(safeReason))),
            rejectedProposalReasonsJson = "{}"
        )
        return persistReadOnlyDiagnostic(
            input = input,
            runtime = runtime,
            decision = decision,
            entity = entity,
            liveSemanticFingerprint = liveFingerprint,
            suppressDiagnosticDuplicate = false
        )
    }

    private fun readOnlyPersistenceFailure(
        input: TargetManagerInput,
        runtime: TargetManagerRuntimeState
    ): TargetManagerDecision {
        val reason = "read_only_diagnostic_persistence_failed"
        return TargetManagerDecision(
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
            winner = null,
            command = null,
            semanticFingerprint = gateFingerprint(
                input,
                TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
                reason
            ),
            cadenceOutcome = null,
            cadenceReason = null,
            reasonCodes = listOf(reason),
            rejectedProposalReasons = emptyMap(),
            nextRuntimeState = runtime
        )
    }

    private suspend fun failClosed(
        input: TargetManagerInput,
        runtime: TargetManagerRuntimeState,
        reason: String,
        reconciliationStatus: String,
        persistedState: TargetManagerStateEntity? = null
    ): TargetManagerDecision {
        val safeReason = reason.take(MAX_DIAGNOSTIC_BYTES)
        val fingerprint = gateFingerprint(input, TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS, safeReason)
        val durableStatus = if (reconciliationStatus.startsWith(STATUS_QUARANTINED)) {
            "$STATUS_QUARANTINED:$safeReason".take(MAX_DIAGNOSTIC_BYTES)
        } else {
            reconciliationStatus.take(MAX_DIAGNOSTIC_BYTES)
        }
        val nextRuntime = runtime.copy(reconciliationStatus = durableStatus)
        val state = if (durableStatus.startsWith(STATUS_QUARANTINED) && persistedState != null) {
            persistedState.copy(updatedAt = input.nowTs, reconciliationStatus = durableStatus)
        } else {
            stateEntity(nextRuntime, input.nowTs, durableStatus)
        }
            ?: TargetManagerStateEntity(
                mode = input.mode.name,
                updatedAt = input.nowTs,
                acceptedTargetJson = null,
                lastDecisionFingerprint = null,
                lastSafetyBypassFingerprint = null,
                reconciliationStatus = STATUS_QUARANTINED
            )
        val entity = TargetManagerDecisionEntity(
            id = "${input.mode.name}:$fingerprint",
            timestamp = input.nowTs,
            mode = input.mode.name,
            semanticFingerprint = fingerprint,
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS.name,
            winnerJson = null,
            commandJson = null,
            cadenceOutcome = null,
            cadenceReason = null,
            lastSentTargetMmol = input.lastAutomaticSent?.targetMmol,
            lastSentTimestamp = input.lastAutomaticSent?.timestamp,
            deliveryStatus = STATUS_BLOCKED,
            reasonCodesJson = checkNotNull(boundedJson(listOf(safeReason))),
            rejectedProposalReasonsJson = "{}"
        )
        if (input.mode == TargetManagerMode.OFF) {
            dao.insertDecision(entity)
        } else {
            val inserted = dao.insertDecisionAndState(entity, state)
            if (inserted == -1L && durableStatus.startsWith(STATUS_QUARANTINED)) {
                dao.upsertState(state)
            }
        }
        return TargetManagerDecision(
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
            winner = null,
            command = null,
            semanticFingerprint = fingerprint,
            cadenceOutcome = null,
            cadenceReason = null,
            reasonCodes = listOf(safeReason),
            rejectedProposalReasons = emptyMap(),
            nextRuntimeState = nextRuntime
        )
    }

    private fun quarantinedRuntimeDecision(
        input: TargetManagerInput,
        runtime: TargetManagerRuntimeState,
        reason: String
    ): TargetManagerDecision {
        val safeReason = reason.take(MAX_DIAGNOSTIC_BYTES)
        return TargetManagerDecision(
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
            winner = null,
            command = null,
            semanticFingerprint = gateFingerprint(
                input,
                TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
                safeReason
            ),
            cadenceOutcome = null,
            cadenceReason = null,
            reasonCodes = listOf(safeReason),
            rejectedProposalReasons = emptyMap(),
            nextRuntimeState = runtime.copy(
                reconciliationStatus = runtime.reconciliationStatus
                    .take(MAX_DIAGNOSTIC_BYTES)
            )
        )
    }

    private suspend fun quarantinePending(
        input: TargetManagerInput,
        pending: TargetManagerDecisionEntity,
        reason: String,
        stateMode: TargetManagerMode
    ): TargetManagerDecision {
        val safeReason = reason.take(MAX_DIAGNOSTIC_BYTES - STATUS_QUARANTINED.length - 1)
        val durableStatus = "$STATUS_QUARANTINED:$safeReason"
        val existingState = dao.state(stateMode.name)
        val state = existingState?.copy(
            updatedAt = input.nowTs,
            reconciliationStatus = durableStatus
        ) ?: TargetManagerStateEntity(
            mode = stateMode.name,
            updatedAt = input.nowTs,
            acceptedTargetJson = null,
            lastDecisionFingerprint = null,
            lastSafetyBypassFingerprint = null,
            reconciliationStatus = durableStatus
        )
        val updated = dao.updateDecisionAndState(
            pending.copy(deliveryStatus = STATUS_QUARANTINED),
            state
        )
        if (updated != 1) dao.upsertState(state)
        return TargetManagerDecision(
            outcome = TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS,
            winner = null,
            command = null,
            semanticFingerprint = pending.semanticFingerprint,
            cadenceOutcome = null,
            cadenceReason = null,
            reasonCodes = listOf("pending_delivery_quarantined:$safeReason"),
            rejectedProposalReasons = emptyMap(),
            nextRuntimeState = TargetManagerRuntimeState(
                mode = input.mode,
                reconciliationStatus = durableStatus
            )
        )
    }

    private suspend fun persistenceFailure(
        input: TargetManagerInput,
        entity: TargetManagerDecisionEntity,
        mode: TargetManagerMode,
        priorRuntime: TargetManagerRuntimeState,
        reason: String
    ): TargetManagerDecision {
        val quarantined = quarantinePending(input, entity, reason, mode)
        return quarantined.copy(
            reasonCodes = listOf(reason.take(MAX_DIAGNOSTIC_BYTES)),
            nextRuntimeState = priorRuntime.copy(
                reconciliationStatus = "$STATUS_QUARANTINED:${reason.take(400)}"
                    .take(MAX_DIAGNOSTIC_BYTES)
            )
        )
    }

    private fun unresolvedPending(input: TargetManagerInput, reason: String) = TargetManagerDecision(
        outcome = TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE,
        winner = null,
        command = null,
        semanticFingerprint = null,
        cadenceOutcome = null,
        cadenceReason = null,
        reasonCodes = listOf(reason.take(MAX_DIAGNOSTIC_BYTES)),
        rejectedProposalReasons = emptyMap(),
        nextRuntimeState = TargetManagerRuntimeState(input.mode, reconciliationStatus = "pending_unresolved")
    )

    private fun semanticDuplicate(
        decision: TargetManagerDecision,
        semanticFingerprint: String,
        runtime: TargetManagerRuntimeState
    ) = decision.copy(
        outcome = TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE,
        command = null,
        semanticFingerprint = semanticFingerprint,
        reasonCodes = decision.reasonCodes + "persisted_semantic_duplicate",
        nextRuntimeState = runtime
    )

    private fun restoredDecision(
        entity: TargetManagerDecisionEntity,
        runtime: TargetManagerRuntimeState
    ) = TargetManagerDecision(
        outcome = ordinaryExceptionOrNull { TargetDecisionOutcome.valueOf(entity.outcome) }
            ?: TargetDecisionOutcome.SEND,
        winner = null,
        command = null,
        semanticFingerprint = entity.semanticFingerprint,
        cadenceOutcome = null,
        cadenceReason = entity.cadenceReason,
        reasonCodes = listOf("delivery_reconciliation"),
        rejectedProposalReasons = emptyMap(),
        nextRuntimeState = runtime
    )

    private fun TargetManagerDecision.toEntity(
        input: TargetManagerInput,
        fingerprint: String
    ): TargetManagerDecisionEntity? {
        val winnerJson = winner?.let(::boundedWinnerJson)
        if (winner != null && winnerJson == null) return null
        val reasonJson = boundedJson(reasonCodes) ?: return null
        val rejectedJson = boundedJson(rejectedProposalReasons) ?: return null
        return TargetManagerDecisionEntity(
            id = "${input.mode.name}:$fingerprint",
            timestamp = input.nowTs,
            mode = input.mode.name,
            semanticFingerprint = fingerprint,
            outcome = outcome.name,
            winnerJson = winnerJson,
            commandJson = null,
            cadenceOutcome = cadenceOutcome?.name,
            cadenceReason = cadenceReason?.take(MAX_DIAGNOSTIC_BYTES),
            lastSentTargetMmol = input.lastAutomaticSent?.targetMmol,
            lastSentTimestamp = input.lastAutomaticSent?.timestamp,
            deliveryStatus = when {
                input.mode == TargetManagerMode.SHADOW -> STATUS_SHADOW
                command != null -> STATUS_PENDING
                else -> STATUS_NOT_REQUESTED
            },
            reasonCodesJson = reasonJson,
            rejectedProposalReasonsJson = rejectedJson
        )
    }

    private fun boundedWinnerJson(proposal: TargetProposal): String? = boundedJson(proposal)

    private fun stateEntity(
        runtime: TargetManagerRuntimeState,
        updatedAt: Long,
        reconciliationStatus: String
    ): TargetManagerStateEntity? {
        val acceptedJson = runtime.acceptedTarget?.let { boundedJson(it) ?: return null }
        if (utf8Size(reconciliationStatus) > MAX_DIAGNOSTIC_BYTES) return null
        return TargetManagerStateEntity(
            mode = runtime.mode.name,
            updatedAt = updatedAt,
            acceptedTargetJson = acceptedJson,
            lastDecisionFingerprint = runtime.lastDecisionFingerprint,
            lastSafetyBypassFingerprint = runtime.lastSafetyBypassFingerprint,
            reconciliationStatus = reconciliationStatus
        )
    }

    private fun parseEnvelope(raw: String?): PendingCommandEnvelope? {
        if (raw == null || utf8Size(raw) > MAX_JSON_BYTES) return null
        return targetManagerJsonOrNull { gson.fromJson(raw, PendingCommandEnvelope::class.java) }
    }

    private fun persistedPreflightFailure(
        entity: TargetManagerDecisionEntity
    ): TargetCommandPreflightFailure? {
        if (entity.deliveryStatus != STATUS_FAILED || utf8Size(entity.reasonCodesJson) > MAX_JSON_BYTES) {
            return null
        }
        return targetManagerJsonOrNull {
            val reasons = gson.fromJson(entity.reasonCodesJson, Array<String>::class.java).toList()
            TargetCommandPreflightFailure.fromMarkedReasonCodes(reasons)
        }
    }

    private suspend fun deliveryStatus(idempotencyKey: String): String? {
        val provider = deliveryStatusProvider ?: return null
        return try {
            provider.status(idempotencyKey)?.trim()?.lowercase()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun pendingMayBeRetried(
        pending: TargetManagerDecisionEntity,
        nowTs: Long
    ): Boolean {
        val age = ordinaryExceptionOrNull { Math.subtractExact(nowTs, pending.timestamp) } ?: return false
        return age >= PENDING_RETRY_GRACE_MS
    }

    private fun boundedJson(value: Any): String? {
        val json = targetManagerJsonOrNull { gson.toJson(value) } ?: return null
        return json.takeIf { utf8Size(it) <= MAX_JSON_BYTES }
    }

    private inline fun <T> targetManagerJsonOrNull(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Error) {
        val wrapped = failure.cause
        if (failure is AssertionError && wrapped is CancellationException) throw wrapped
        if (failure is AssertionError && wrapped is Error) throw wrapped
        throw failure
    } catch (failure: Exception) {
        when (val wrapped = failure.cause) {
            is CancellationException -> throw wrapped
            is Error -> throw wrapped
        }
        null
    }

    private fun gateFingerprint(
        input: TargetManagerInput,
        outcome: TargetDecisionOutcome,
        reason: String = "gate"
    ): String = digest(
        listOf(
            input.mode.name,
            input.glucoseTimestamp.toString(),
            input.therapyWatermark.toString(),
            outcome.name,
            reason
        ).joinToString("|")
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun diagnosticFingerprint(
        mode: TargetManagerMode,
        semanticFingerprint: String,
        timestamp: Long
    ): String = digest("read_only|${mode.name}|$semanticFingerprint|$timestamp")

    private fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private data class LoadedRuntime(
        val runtime: TargetManagerRuntimeState,
        val quarantineReason: String?,
        val persistedState: TargetManagerStateEntity?
    )

    private data class PendingCommandEnvelope(
        val command: TargetCommandCandidate,
        val acceptedTarget: AcceptedTargetState
    )

    private data class PendingRetry(
        val entity: TargetManagerDecisionEntity,
        val envelope: PendingCommandEnvelope,
        val mode: TargetManagerMode,
        val priorRuntime: TargetManagerRuntimeState
    )

    private data class PendingReconciliation(
        val blocked: TargetManagerDecision? = null,
        val retry: PendingRetry? = null
    )

    companion object {
        const val MAX_JSON_BYTES = 64 * 1024
        const val DECISION_RETENTION_MS = 45L * 24L * 60L * 60L * 1_000L
        const val READ_ONLY_DIAGNOSTIC_MAX_ROWS = 256

        private const val MAX_DIAGNOSTIC_BYTES = 512
        private const val STATUS_READ_ONLY = "read_only"
        private const val MIN_PERSISTED_DURATION_MINUTES = 1
        private const val MAX_PERSISTED_DURATION_MINUTES = 24 * 60
        private const val PENDING_RETRY_GRACE_MS = 2 * 60_000L
        private const val MINUTE_MS = 60_000L
        private const val STATUS_PENDING = "pending"
        private const val STATUS_SENT = "sent"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_ABSENT = "absent"
        private const val STATUS_UNKNOWN = "unknown"
        private const val STATUS_SHADOW = "shadow"
        private const val STATUS_BLOCKED = "blocked"
        private const val STATUS_NOT_REQUESTED = "not_requested"
        private const val STATUS_QUARANTINED = "quarantined"
        private const val STATUS_SUPERSEDED = "superseded"
        private const val STATUS_RECOVERED_SENT = "recovered_quarantine_sent"
        private const val STATUS_RECOVERED_ABSENT = "recovered_quarantine_absent"
        private const val QUARANTINE_KNOWN_MISMATCH =
            "$STATUS_QUARANTINED:existing_envelope_current_mismatch"
        private const val TARGET_MANAGER_IDEMPOTENCY_PREFIX = "TargetManager.v1:"

        private val PROCESS_MUTEX = Mutex()
    }
}
