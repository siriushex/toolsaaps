package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.aaps.copilot.data.local.dao.ClinicalTargetManagerEvidenceProjection
import io.aaps.copilot.data.local.dao.TargetManagerDao
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import io.aaps.copilot.data.local.entity.TargetManagerStateEntity
import io.aaps.copilot.domain.target.AcceptedTargetState
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.ActivityForecastSafety
import io.aaps.copilot.domain.target.ActivityTargetSafetyContext
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.HorizonReliability
import io.aaps.copilot.domain.target.HorizonReliabilityState
import io.aaps.copilot.domain.target.LastSentTempTarget
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManagerInput
import io.aaps.copilot.domain.target.TargetManager
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetManagerRuntimeState
import io.aaps.copilot.domain.target.TargetManagerSafetyContext
import io.aaps.copilot.domain.target.TargetProposal
import io.aaps.copilot.domain.profile.ActivityTargetProposal
import io.aaps.copilot.domain.profile.ActivityTargetProposalDirection
import io.aaps.copilot.domain.profile.ActivitySafePredictedFall
import io.aaps.copilot.domain.profile.ActivityContextSource
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.ActivityTargetProposalFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TargetManagerRepositoryTest {

    @Test
    fun readOnlyEvaluationKeepsExactSensitivityDiagnosticsAndNeverDispatches() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val sensitivity = io.aaps.copilot.testSensitivityRuntimeSnapshot(
            cycleId = "source-change-target-cycle",
            settingsRevision = 91L
        )
        val input = input().copy(
            sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.TARGET_MANAGER,
                snapshot = sensitivity
            )
        )

        repository(dao) { dispatches.incrementAndGet(); true }.evaluateReadOnly(input)
        val rows = AutomationRepository.buildTargetManagerSensitivityTelemetryRowsStatic(
            nowTs = NOW,
            sensitivityRuntime = input.sensitivityRuntime
        ).associateBy { it.key }

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("read_only")
        assertThat(rows.getValue("target_manager_sensitivity_cycle_id").valueText)
            .isEqualTo(sensitivity.forecastCycleId)
        assertThat(rows.getValue("target_manager_sensitivity_settings_revision").valueDouble)
            .isEqualTo(sensitivity.settingsRevision.toDouble())
    }

    @Test
    fun readOnlyDiagnosticPersistsWithoutSuppressingFirstLiveDispatchAndSecondLiveDuplicate() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }
        val exactInput = input()

        val diagnostic = repository.evaluateReadOnly(exactInput)
        val firstLive = repository.evaluateAndDispatch(exactInput)
        val secondLive = repository.evaluateAndDispatch(exactInput)

        assertThat(diagnostic.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(firstLive.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(secondLive.outcome).isEqualTo(TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE)
        assertThat(dispatches.get()).isEqualTo(1)
        assertThat(dao.decisions.map { it.deliveryStatus }).containsExactly("read_only", "sent").inOrder()
        assertThat(dao.decisions.map { it.semanticFingerprint }).containsNoDuplicates()
        assertThat(dao.decisions.single { it.deliveryStatus == "sent" }.semanticFingerprint)
            .isEqualTo(firstLive.semanticFingerprint)
    }

    @Test
    fun readOnlyMalformedRuntimeLeavesAuthoritativeStateAndLiveJournalByteIdentical() = runBlocking {
        val dao = FakeTargetManagerDao()
        val malformed = TargetManagerStateEntity(
            mode = TargetManagerMode.ACTIVE.name,
            updatedAt = NOW - MINUTE_MS,
            acceptedTargetJson = "{malformed",
            lastDecisionFingerprint = "live-before-read-only",
            lastSafetyBypassFingerprint = "safety-before-read-only",
            reconciliationStatus = "confirmed"
        )
        dao.upsertState(malformed)
        val stateBefore = dao.state(TargetManagerMode.ACTIVE.name)
        val liveBefore = dao.decisions.filterNot { it.deliveryStatus == "read_only" }.toList()

        val diagnostic = repository(dao) { error("read-only must not dispatch") }
            .evaluateReadOnly(input())
        val repeated = repository(dao) { error("read-only must not dispatch") }
            .evaluateReadOnly(input())

        assertThat(diagnostic.reasonCodes.joinToString()).contains("accepted_json_invalid")
        assertThat(repeated.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
        assertThat(repeated.reasonCodes.joinToString()).contains("accepted_json_invalid")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isEqualTo(stateBefore)
        assertThat(dao.decisions.filterNot { it.deliveryStatus == "read_only" })
            .containsExactlyElementsIn(liveBefore)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("read_only")
    }

    @Test
    fun readOnlyOversizedJournalLeavesAuthoritativeStateAndLiveJournalByteIdentical() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initialState = TargetManagerStateEntity(
            mode = TargetManagerMode.ACTIVE.name,
            updatedAt = NOW - MINUTE_MS,
            acceptedTargetJson = null,
            lastDecisionFingerprint = "live-before-oversized",
            lastSafetyBypassFingerprint = null,
            reconciliationStatus = "confirmed"
        )
        dao.upsertState(initialState)
        val huge = input().copy(
            proposals = listOf(
                proposal(
                    reasonCodes = List(400) { "r$it-${"x".repeat(400)}" },
                    fingerprint = "read-only-huge"
                )
            )
        )
        val stateBefore = dao.state(TargetManagerMode.ACTIVE.name)

        val diagnostic = repository(dao) { error("read-only must not dispatch") }
            .evaluateReadOnly(huge)

        assertThat(diagnostic.reasonCodes).contains("journal_payload_oversized")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isEqualTo(stateBefore)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("read_only")
    }

    @Test
    fun readOnlyManagerExceptionLeavesAuthoritativeStateAndLaterNormalBehaviorUnchanged() = runBlocking {
        val readOnlyDao = FakeTargetManagerDao()
        val controlDao = FakeTargetManagerDao()
        val initialState = TargetManagerStateEntity(
            mode = TargetManagerMode.ACTIVE.name,
            updatedAt = NOW - MINUTE_MS,
            acceptedTargetJson = null,
            lastDecisionFingerprint = "before-manager-failure",
            lastSafetyBypassFingerprint = null,
            reconciliationStatus = "confirmed"
        )
        readOnlyDao.upsertState(initialState)
        controlDao.upsertState(initialState)

        val diagnostic = repository(readOnlyDao) { error("read-only must not dispatch") }
            .evaluateReadOnly(managerFailureInput(IllegalStateException("read-only manager failure")))
        val liveAfterDiagnostic = repository(readOnlyDao) { true }
            .evaluateAndDispatch(managerFailureInput(IllegalStateException("normal manager failure")))
        val controlLive = repository(controlDao) { true }
            .evaluateAndDispatch(managerFailureInput(IllegalStateException("normal manager failure")))

        assertThat(diagnostic.reasonCodes).contains("manager_state_failed")
        assertThat(readOnlyDao.state(TargetManagerMode.ACTIVE.name))
            .isEqualTo(controlDao.state(TargetManagerMode.ACTIVE.name))
        assertThat(liveAfterDiagnostic.outcome).isEqualTo(controlLive.outcome)
        assertThat(liveAfterDiagnostic.reasonCodes).isEqualTo(controlLive.reasonCodes)
        assertThat(readOnlyDao.decisions.filterNot { it.deliveryStatus == "read_only" })
            .containsExactlyElementsIn(controlDao.decisions)
        Unit
    }

    @Test
    fun readOnlyDiagnosticPersistenceFailureReturnsInMemoryBlockWithoutLiveMutation() = runBlocking {
        val dao = FakeTargetManagerDao().apply { failReadOnlyDiagnosticInsert = true }
        val state = TargetManagerStateEntity(
            mode = TargetManagerMode.ACTIVE.name,
            updatedAt = NOW - MINUTE_MS,
            acceptedTargetJson = null,
            lastDecisionFingerprint = "before-diagnostic-failure",
            lastSafetyBypassFingerprint = null,
            reconciliationStatus = "confirmed"
        )
        dao.upsertState(state)

        val decision = repository(dao) { error("read-only must not dispatch") }
            .evaluateReadOnly(input())

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_SAFETY_BOUNDS)
        assertThat(decision.reasonCodes).contains("read_only_diagnostic_persistence_failed")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isEqualTo(state)
        assertThat(dao.decisions).isEmpty()
    }

    @Test
    fun readOnlyDiagnosticRetentionPrunesByAgeWithoutTouchingLiveEvidence() = runBlocking {
        val dao = FakeTargetManagerDao()
        val repository = repository(dao) { true }
        val oldTs = NOW - TargetManagerRepository.DECISION_RETENTION_MS - MINUTE_MS

        repository.evaluateAndDispatch(input(fingerprint = "live-retained"))
        repository.evaluateReadOnly(diagnosticInput(oldTs, "old-diagnostic"))
        repository.evaluateReadOnly(diagnosticInput(NOW, "new-diagnostic"))

        assertThat(dao.decisions.filter { it.deliveryStatus == "read_only" }).hasSize(1)
        assertThat(dao.decisions.any { it.deliveryStatus == "sent" }).isTrue()
    }

    @Test
    fun readOnlyDiagnosticRetentionCapsRowsAcrossClockRollbackWithoutTouchingLiveEvidence() = runBlocking {
        val dao = FakeTargetManagerDao()
        val repository = repository(dao) { true }
        repository.evaluateAndDispatch(input(fingerprint = "live-before-rollback-retention"))

        repeat(READ_ONLY_DIAGNOSTIC_EXPECTED_CAP + 20) { index ->
            repository.evaluateReadOnly(
                diagnosticInput(
                    nowTs = NOW - index.toLong(),
                    fingerprint = "rollback-diagnostic-$index"
                )
            )
        }

        assertThat(dao.decisions.count { it.deliveryStatus == "read_only" })
            .isEqualTo(READ_ONLY_DIAGNOSTIC_EXPECTED_CAP)
        assertThat(dao.decisions.count { it.deliveryStatus == "sent" }).isEqualTo(1)
    }

    @Test
    fun acceptedPlannedActivityRaiseDispatchesExactlyOnceThroughRepository() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val sentTargets = mutableListOf<Double>()
        val repository = repository(dao) { command ->
            sentTargets += command.targetMmol
            dispatches.incrementAndGet()
            true
        }
        val activity = activityRaise()

        val decision = repository.evaluateAndDispatch(
            input(
                proposal = activityTarget(activity),
                activitySafety = activitySafety(activity)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.winner?.targetMmol).isEqualTo(7.7)
        assertThat(decision.winner?.durationMinutes).isEqualTo(30)
        assertThat(sentTargets).containsExactly(7.7)
        assertThat(dispatches.get()).isEqualTo(1)
    }

    @Test
    fun postDispatchPersistenceAndAuditFailuresDoNotRetryTherapy() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val persistenceAttempts = AtomicInteger()
        val warningAudits = AtomicInteger()
        val decisionAudits = AtomicInteger()
        val repository = repository(dao) {
            dispatches.incrementAndGet()
            true
        }

        val decision = requireNotNull(
            AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                nowTs = NOW,
                state = DeliveryTrustState.WATCH,
                evaluate = { repository.evaluateAndDispatch(input()) },
                persist = {
                    persistenceAttempts.incrementAndGet()
                    throw IllegalStateException("telemetry storage unavailable")
                },
                warn = { _, _ ->
                    warningAudits.incrementAndGet()
                    throw IllegalStateException("warning audit unavailable")
                },
                reportDecision = {
                    decisionAudits.incrementAndGet()
                    throw IllegalStateException("decision audit unavailable")
                }
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(dispatches.get()).isEqualTo(1)
        assertThat(persistenceAttempts.get()).isEqualTo(1)
        assertThat(warningAudits.get()).isEqualTo(1)
        assertThat(decisionAudits.get()).isEqualTo(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun managerDecisionCancellationEscapesSameInstanceWithoutFailClosedPersistence() = runBlocking {
        val cancellation = CancellationException("cancel manager validation")
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }

        val caught = captureFailure {
            repository.evaluateAndDispatch(managerFailureInput(cancellation))
        }

        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.decisions).isEmpty()
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isNull()
    }

    @Test
    fun managerDecisionCancellationSkipsAllPostEvaluationAutomationWork() = runBlocking {
        val cancellation = CancellationException("cancel manager validation")
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val telemetryPersistence = AtomicInteger()
        val warningAudits = AtomicInteger()
        val decisionAudits = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }

        val caught = captureFailure {
            AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                nowTs = NOW,
                state = DeliveryTrustState.WATCH,
                evaluate = { repository.evaluateAndDispatch(managerFailureInput(cancellation)) },
                persist = { telemetryPersistence.incrementAndGet() },
                warn = { _, _ -> warningAudits.incrementAndGet() },
                reportDecision = { decisionAudits.incrementAndGet() }
            )
        }

        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(dao.decisions).isEmpty()
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isNull()
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(telemetryPersistence.get()).isEqualTo(0)
        assertThat(warningAudits.get()).isEqualTo(0)
        assertThat(decisionAudits.get()).isEqualTo(0)
    }

    @Test
    fun directThrowableFromManagerEscapesOuterAutomationBoundaryWithoutSideEffects() = runBlocking {
        val failure = DirectTargetManagerThrowable()
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val telemetryPersistence = AtomicInteger()
        val persistenceWarningAudits = AtomicInteger()
        val decisionAudits = AtomicInteger()
        val evaluationFailureAudits = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }

        val caught = captureFailure {
            AutomationRepository.evaluateTargetManagerWithFailureBoundaryStatic(
                mode = TargetManagerMode.ACTIVE,
                evaluateAndReport = {
                    AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                        nowTs = NOW,
                        state = DeliveryTrustState.WATCH,
                        evaluate = { repository.evaluateAndDispatch(managerFailureInput(failure)) },
                        persist = { telemetryPersistence.incrementAndGet() },
                        warn = { _, _ -> persistenceWarningAudits.incrementAndGet() },
                        reportDecision = { decisionAudits.incrementAndGet() }
                    )
                },
                reportEvaluationFailure = { _, _ -> evaluationFailureAudits.incrementAndGet() }
            )
        }

        assertThat(caught).isSameInstanceAs(failure)
        assertThat(dao.decisions).isEmpty()
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isNull()
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(telemetryPersistence.get()).isEqualTo(0)
        assertThat(persistenceWarningAudits.get()).isEqualTo(0)
        assertThat(decisionAudits.get()).isEqualTo(0)
        assertThat(evaluationFailureAudits.get()).isEqualTo(0)
    }

    @Test
    fun outerAutomationBoundaryStillReportsAndContainsOrdinaryException() = runBlocking {
        val reports = mutableListOf<Pair<String, Map<String, Any?>>>()

        val result = AutomationRepository.evaluateTargetManagerWithFailureBoundaryStatic<Any>(
            mode = TargetManagerMode.SHADOW,
            evaluateAndReport = { throw IllegalArgumentException("ordinary evaluation failure") },
            reportEvaluationFailure = { event, details -> reports += event to details }
        )

        assertThat(result).isNull()
        assertThat(reports).containsExactly(
            "target_manager_evaluation_failed" to mapOf(
                "mode" to TargetManagerMode.SHADOW.name,
                "failureType" to "IllegalArgumentException",
                "legacyFallback" to false
            )
        )
        Unit
    }

    @Test
    fun ordinaryManagerIllegalStateStillUsesExistingFailClosedBehavior() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()

        val decision = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(managerFailureInput(IllegalStateException("ordinary manager failure")))

        assertThat(decision.reasonCodes).contains("manager_state_failed")
        assertThat(decision.nextRuntimeState.reconciliationStatus).isEqualTo("manager_error")
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("blocked")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .isEqualTo("manager_error")
    }

    @Test
    fun dispatcherCancellationAndEveryErrorEscapeBeforePostEvaluationWork() = runBlocking {
        cancellationAndEveryError().forEach { failure ->
            val dao = FakeTargetManagerDao()
            val persistenceAttempts = AtomicInteger()
            val warningAudits = AtomicInteger()
            val decisionAudits = AtomicInteger()
            val repository = repository(dao) { throw failure }

            val caught = captureFailure {
                AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                    nowTs = NOW,
                    state = DeliveryTrustState.WATCH,
                    evaluate = { repository.evaluateAndDispatch(input()) },
                    persist = { persistenceAttempts.incrementAndGet() },
                    warn = { _, _ -> warningAudits.incrementAndGet() },
                    reportDecision = { decisionAudits.incrementAndGet() }
                )
            }

            assertThat(caught).isSameInstanceAs(failure)
            assertThat(persistenceAttempts.get()).isEqualTo(0)
            assertThat(warningAudits.get()).isEqualTo(0)
            assertThat(decisionAudits.get()).isEqualTo(0)
            assertThat(dao.decisions.single().deliveryStatus).isEqualTo("pending")
        }
    }

    @Test
    fun statusProviderCancellationAndEveryErrorEscapeBeforePostEvaluationWork() = runBlocking {
        cancellationAndEveryError().forEach { failure ->
            val initial = agedInput()
            val dao = FakeTargetManagerDao()
            TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher { throw IllegalStateException("status unknown after write") }
            ).evaluateAndDispatch(initial)
            val persistenceAttempts = AtomicInteger()
            val warningAudits = AtomicInteger()
            val decisionAudits = AtomicInteger()
            val restarted = TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher { true },
                deliveryStatusProvider = TargetDeliveryStatusProvider { throw failure }
            )

            val caught = captureFailure {
                AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                    nowTs = NOW,
                    state = DeliveryTrustState.WATCH,
                    evaluate = { restarted.evaluateAndDispatch(initial.copy(nowTs = NOW)) },
                    persist = { persistenceAttempts.incrementAndGet() },
                    warn = { _, _ -> warningAudits.incrementAndGet() },
                    reportDecision = { decisionAudits.incrementAndGet() }
                )
            }

            assertThat(caught).isSameInstanceAs(failure)
            assertThat(persistenceAttempts.get()).isEqualTo(0)
            assertThat(warningAudits.get()).isEqualTo(0)
            assertThat(decisionAudits.get()).isEqualTo(0)
            assertThat(dao.decisions.single().deliveryStatus).isEqualTo("pending")
        }
    }

    @Test
    fun jsonBoundariesPropagateCancellationAndEveryError() = runBlocking {
        cancellationAndEveryError().forEach { failure ->
            val parseDao = FakeTargetManagerDao()
            parseDao.upsertState(
                stateEntity(TargetManagerMode.ACTIVE, accepted(target = 5.7), Gson())
            )
            val parseDispatches = AtomicInteger()
            val parsePostEvaluationCalls = AtomicInteger()
            val parseRepository = repository(
                dao = parseDao,
                gson = acceptedReaderFailureGson(failure)
            ) { parseDispatches.incrementAndGet(); true }
            val parseCaught = captureFailure {
                AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                    nowTs = NOW,
                    state = DeliveryTrustState.WATCH,
                    evaluate = { parseRepository.evaluateAndDispatch(input()) },
                    persist = { parsePostEvaluationCalls.incrementAndGet() },
                    warn = { _, _ -> parsePostEvaluationCalls.incrementAndGet() },
                    reportDecision = { parsePostEvaluationCalls.incrementAndGet() }
                )
            }

            assertThat(parseCaught).isSameInstanceAs(failure)
            assertThat(parseDispatches.get()).isEqualTo(0)
            assertThat(parsePostEvaluationCalls.get()).isEqualTo(0)

            val serializeDao = FakeTargetManagerDao()
            val serializeDispatches = AtomicInteger()
            val serializePostEvaluationCalls = AtomicInteger()
            val serializeRepository = repository(
                dao = serializeDao,
                gson = proposalWriterFailureGson(failure)
            ) { serializeDispatches.incrementAndGet(); true }
            val serializeCaught = captureFailure {
                AutomationRepository.evaluatePersistAndReportTargetManagerDeliveryTrustStatic(
                    nowTs = NOW,
                    state = DeliveryTrustState.WATCH,
                    evaluate = { serializeRepository.evaluateAndDispatch(input()) },
                    persist = { serializePostEvaluationCalls.incrementAndGet() },
                    warn = { _, _ -> serializePostEvaluationCalls.incrementAndGet() },
                    reportDecision = { serializePostEvaluationCalls.incrementAndGet() }
                )
            }

            assertThat(serializeCaught).isSameInstanceAs(failure)
            assertThat(serializeDispatches.get()).isEqualTo(0)
            assertThat(serializePostEvaluationCalls.get()).isEqualTo(0)
        }
    }

    @Test
    fun ordinaryJsonFailuresRetainExistingFailClosedBehavior() = runBlocking {
        val parseDao = FakeTargetManagerDao()
        parseDao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 5.7), Gson()))
        val parseDispatches = AtomicInteger()
        val parseDecision = repository(
            dao = parseDao,
            gson = acceptedReaderFailureGson(IllegalArgumentException("malformed accepted target"))
        ) { parseDispatches.incrementAndGet(); true }.evaluateAndDispatch(input())

        assertThat(parseDecision.reasonCodes.single())
            .contains("runtime_state_quarantined:accepted_json_invalid")
        assertThat(parseDispatches.get()).isEqualTo(0)

        val serializeDao = FakeTargetManagerDao()
        val serializeDispatches = AtomicInteger()
        val serializeDecision = repository(
            dao = serializeDao,
            gson = proposalWriterFailureGson(IllegalArgumentException("cannot serialize proposal"))
        ) { serializeDispatches.incrementAndGet(); true }.evaluateAndDispatch(input())

        assertThat(serializeDecision.reasonCodes).contains("journal_payload_oversized")
        assertThat(serializeDispatches.get()).isEqualTo(0)
    }

    @Test
    fun ordinaryAcceptedAndPendingValidationFailuresRemainQuarantined() = runBlocking {
        val acceptedDao = FakeTargetManagerDao()
        acceptedDao.upsertState(
            TargetManagerStateEntity(
                mode = TargetManagerMode.ACTIVE.name,
                updatedAt = NOW,
                acceptedTargetJson = Gson().toJson(accepted(target = 5.7))
                    .replace("\"intent\":\"NORMAL_CONTROL\"", "\"intent\":null"),
                lastDecisionFingerprint = null,
                lastSafetyBypassFingerprint = null,
                reconciliationStatus = "confirmed"
            )
        )
        val acceptedDispatches = AtomicInteger()

        val acceptedDecision = repository(acceptedDao) { acceptedDispatches.incrementAndGet(); true }
            .evaluateAndDispatch(input())

        assertThat(acceptedDecision.reasonCodes.single())
            .contains("runtime_state_quarantined:accepted_shape_invalid")
        assertThat(acceptedDispatches.get()).isEqualTo(0)

        val pendingDao = FakeTargetManagerDao()
        val initial = agedInput()
        repository(pendingDao) { false }.evaluateAndDispatch(initial)
        val pending = pendingDao.decisions.single()
        val envelope = JsonParser.parseString(pending.commandJson).asJsonObject
        envelope.add("command", JsonNull.INSTANCE)
        pendingDao.decisions[0] = pending.copy(
            commandJson = envelope.toString(),
            deliveryStatus = "pending"
        )
        val pendingDispatches = AtomicInteger()

        val pendingDecision = TargetManagerRepository(
            dao = pendingDao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { pendingDispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        ).evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(pendingDecision.reasonCodes)
            .contains("pending_delivery_quarantined:pending_envelope_invalid")
        assertThat(pendingDispatches.get()).isEqualTo(0)
    }

    @Test
    fun pendingCommandCannotReplaceItsObservedAuthorityOrForecastGeneration() = runBlocking {
        val mutations: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.remove("targetObservation") },
            { it.getAsJsonObject("targetObservation").addProperty("priorityRevision", 99L) },
            { it.getAsJsonObject("targetObservation").addProperty("copilotPriorityEnabled", true) },
            { it.remove("sensitivityCycleId") },
            { it.addProperty("sensitivityCycleId", "different-cycle") }
        )
        mutations.forEach { mutate ->
            val dao = FakeTargetManagerDao()
            val initial = agedInput()
            repository(dao) { false }.evaluateAndDispatch(initial)
            val pending = dao.decisions.single()
            val envelope = JsonParser.parseString(pending.commandJson).asJsonObject
            mutate(envelope.getAsJsonObject("command"))
            dao.decisions[0] = pending.copy(commandJson = envelope.toString(), deliveryStatus = "pending")
            val dispatches = AtomicInteger()

            TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
                deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
            ).evaluateAndDispatch(initial.copy(nowTs = NOW))

            assertThat(dispatches.get()).isEqualTo(0)
        }
    }

    @Test
    fun confirmedLegacyPendingCommandMayReconcileWithoutGainingDispatchAuthority() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        repository(dao) { false }.evaluateAndDispatch(initial)
        val pending = dao.decisions.single()
        val envelope = JsonParser.parseString(pending.commandJson).asJsonObject
        envelope.getAsJsonObject("command").remove("targetObservation")
        envelope.getAsJsonObject("command").remove("sensitivityCycleId")
        dao.decisions[0] = pending.copy(commandJson = envelope.toString(), deliveryStatus = "pending")
        val dispatches = AtomicInteger()

        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "sent" }
        ).evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.decisions.single { it.id == pending.id }.deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun pendingCommandComparisonMismatchRemainsFailClosedWithoutRedispatch() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        repository(dao) { false }.evaluateAndDispatch(initial)
        val pending = dao.decisions.single()
        val envelope = JsonParser.parseString(pending.commandJson).asJsonObject
        envelope.getAsJsonObject("command").addProperty("reason", "different-valid-reason")
        dao.decisions[0] = pending.copy(
            commandJson = envelope.toString(),
            deliveryStatus = "pending"
        )
        val dispatches = AtomicInteger()

        val decision = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        ).evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes).contains("pending_retry_rejected_current_context")
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("failed")
    }

    @Test
    fun readOnlyEvaluationPersistsDiagnosticWithoutDispatchOrRuntimeMutation() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }

        val decision = repository.evaluateReadOnly(input())

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("read_only")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isNull()
    }

    @Test
    fun plannedActivityRaiseWithoutSafePredictedFallNeverDispatches() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }
        val activity = activityRaise().copy(protectionEvidenceHash = null)

        val decision = repository.evaluateAndDispatch(
            input(
                proposal = activityTarget(activity),
                activitySafety = activitySafety(activity)
            )
        )

        assertThat(decision.command).isNull()
        assertThat(decision.rejectedProposalReasons[ActivityTargetProposalFactory.SOURCE_RULE_ID])
            .isEqualTo("activity_raise_safe_predicted_fall_missing")
        assertThat(dispatches.get()).isEqualTo(0)
        Unit
    }

    @Test
    fun plannedActivityKeepaliveIsSuppressedOutsideOccurrenceWindow() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        val activity = activityRaise().copy(validUntilMs = NOW)
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 7.7,
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "PlannedActivityTarget.v1",
                    intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
                    activityProposal = activity
                ),
                gson
            )
        )
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }

        val decision = repository.evaluateAndDispatch(
            input(
                proposals = emptyList(),
                activitySafety = activitySafety(activity).copy(keepaliveAllowed = false)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun plannedActivityKeepaliveRequiresFreshCurrentCycleEvidenceAndRenewsOnce() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        val acceptedActivity = activityRaise().copy(protectionEvidenceHash = "cycle-one-proof")
        val freshEvidence = safePredictedFall().copy(evidenceHash = "cycle-two-proof")
        val freshActivity = acceptedActivity.copy(protectionEvidenceHash = freshEvidence.evidenceHash)
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 7.7,
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "PlannedActivityTarget.v1",
                    intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
                    activityProposal = acceptedActivity
                ),
                gson
            )
        )
        val sentTargets = mutableListOf<Double>()
        val repository = repository(dao, gson) { command -> sentTargets += command.targetMmol; true }

        val decision = repository.evaluateAndDispatch(
            input(
                proposals = listOf(activityTarget(freshActivity)),
                activitySafety = activitySafety(freshActivity).copy(
                    keepaliveAllowed = true,
                    safePredictedFall = freshEvidence
                )
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        assertThat(decision.winner?.sourceRuleId).isEqualTo(TargetManager.RENEWAL_SOURCE_RULE_ID)
        assertThat(sentTargets).containsExactly(7.7)
        Unit
    }

    @Test
    fun plannedActivityKeepaliveRejectsStoredEvidenceWithoutFreshCurrentCycleProposal() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        val activity = activityRaise()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 7.7,
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "PlannedActivityTarget.v1",
                    intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
                    activityProposal = activity
                ),
                gson
            )
        )
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }

        val decision = repository.evaluateAndDispatch(
            input(
                proposals = emptyList(),
                activitySafety = activitySafety(activity).copy(keepaliveAllowed = true)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
        assertThat(dispatches.get()).isEqualTo(0)
        Unit
    }

    @Test
    fun plannedActivityShadowJournalsWithoutDispatch() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }
        val activity = activityRaise()

        val decision = repository.evaluateAndDispatch(
            input(
                mode = TargetManagerMode.SHADOW,
                proposal = activityTarget(activity),
                activitySafety = activitySafety(activity)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("shadow")
    }

    @Test
    fun cancelledScheduleWithMeasuredActivityRoutesReturnToBaseOnlyThroughRepository() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 7.7,
                    expiresAt = NOW + 20 * MINUTE_MS,
                    owner = "PlannedActivityTarget.v1",
                    intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION
                ),
                gson
            )
        )
        val sentTargets = mutableListOf<Double>()
        val repository = repository(dao, gson) { command -> sentTargets += command.targetMmol; true }

        val returnToBaseRequested = AutomationRepository.activityReturnToBaseRequestedStatic(
            plannedActivityTargetOccurrence = null,
            activityContextSource = ActivityContextSource.MEASURED
        )
        val decision = repository.evaluateAndDispatch(
            input(
                proposals = emptyList(),
                activitySafety = ActivityTargetSafetyContext(returnToBaseRequested = returnToBaseRequested)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(decision.winner?.sourceRuleId).isEqualTo("planned_activity_return_to_base")
        assertThat(sentTargets).containsExactly(5.5)
        Unit
    }

    @Test
    fun cancelledScheduleWithMeasuredActivityNeverOverwritesManualOrForeignTarget() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 7.7,
                    expiresAt = NOW + 20 * MINUTE_MS,
                    owner = "PlannedActivityTarget.v1",
                    intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION
                ),
                gson
            )
        )
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }
        val manual = ActiveAapsTarget(
            targetMmol = 6.2,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 20 * MINUTE_MS,
            source = "manual",
            ownership = ActiveTargetOwnership.MANUAL_OR_FOREIGN,
            idempotencyKey = null
        )

        val returnToBaseRequested = AutomationRepository.activityReturnToBaseRequestedStatic(
            plannedActivityTargetOccurrence = null,
            activityContextSource = ActivityContextSource.MEASURED
        )
        val decision = repository.evaluateAndDispatch(
            input(
                proposals = emptyList(),
                activeAapsTarget = manual,
                activitySafety = ActivityTargetSafetyContext(returnToBaseRequested = returnToBaseRequested)
            )
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(dispatches.get()).isEqualTo(0)
        Unit
    }

    @Test
    fun plannedActivityReturnToBaseIsOneShotAndNeverCreatesKeepalive() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 5.5,
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "planned_activity_return_to_base",
                    intent = TargetIntent.RECOVERY_TO_BASE
                ),
                gson
            )
        )
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }

        val decision = repository.evaluateAndDispatch(input(proposals = emptyList()))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
        assertThat(dispatches.get()).isEqualTo(0)
        Unit
    }

    @Test
    fun eightConcurrentEvaluationsDispatchExactlyOnceAfterDurablePending() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val pendingWasDurable = AtomicBoolean()
        val repository = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher {
                pendingWasDurable.set(dao.pendingDecisions().size == 1)
                dispatches.incrementAndGet()
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "pending" }
        )

        val results = (1..8).map { async { repository.evaluateAndDispatch(input()) } }.awaitAll()

        assertThat(dispatches.get()).isEqualTo(1)
        assertThat(pendingWasDurable.get()).isTrue()
        assertThat(results.count { it.outcome == TargetDecisionOutcome.SEND }).isEqualTo(1)
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun multipleRepositoryInstancesStillDispatchOneCommand() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repositories = (1..8).map {
            TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher {
                    dispatches.incrementAndGet()
                    delay(50)
                    true
                },
                deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
            )
        }

        repositories.map { repository -> async { repository.evaluateAndDispatch(input()) } }.awaitAll()

        assertThat(dispatches.get()).isEqualTo(1)
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun shadowUsesSeparateVirtualStateAndNeverDispatches() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val repository = repository(dao) { dispatches.incrementAndGet(); true }

        val decision = repository.evaluateAndDispatch(input(TargetManagerMode.SHADOW))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.state(TargetManagerMode.SHADOW.name)?.acceptedTargetJson).isNotNull()
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isNull()
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("shadow")
    }

    @Test
    fun failedDeliveryPreservesPriorAcceptedTargetAndFinalizesAtomically() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 6.0), gson))
        val repository = repository(dao, gson = gson) { false }

        val result = repository.evaluateAndDispatch(input(proposalTarget = 6.3, fingerprint = "new"))

        assertThat(result.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED)
        val stored = gson.fromJson(
            dao.state(TargetManagerMode.ACTIVE.name)?.acceptedTargetJson,
            AcceptedTargetState::class.java
        )
        assertThat(stored.targetMmol).isEqualTo(6.0)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("failed")
        assertThat(dao.atomicFinalizations.get()).isEqualTo(1)
    }

    @Test
    fun clearedPreflightSameFingerprintRetriesOnlyAfterConfirmedAbsent() = runBlocking {
        val dao = FakeTargetManagerDao()
        val keys = mutableListOf<String>()
        var guardRefuses = true
        var posts = 0
        var reconciliations = 0
        val repository = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { command ->
                keys += command.idempotencyKey
                if (guardRefuses) {
                    throw TargetCommandPreflightBlockedException(
                        TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING
                    )
                }
                posts++
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider {
                reconciliations++
                "absent"
            }
        )
        val request = input(proposalTarget = 6.3, fingerprint = "transient-manual-preflight")

        repository.evaluateAndDispatch(request)
        val refusedEnvelope = dao.decisions.single().commandJson
        guardRefuses = false
        val second = repository.evaluateAndDispatch(request)

        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.SEND)
        assertThat(reconciliations).isEqualTo(1)
        assertThat(posts).isEqualTo(1)
        assertThat(keys).hasSize(2)
        assertThat(keys.distinct()).hasSize(1)
        assertThat(refusedEnvelope).isNotNull()
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().commandJson).isEqualTo(refusedEnvelope)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
        assertThat(second.reasonCodes).doesNotContain(TargetCommandPreflightFailure.MARKER)
    }

    @Test
    fun repeatedPreflightRefusalPreservesFailedJournalAndRechecksWithoutPosting() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 6.0), gson))
        val dispatches = AtomicInteger()
        var posts = 0
        val repository = TargetManagerRepository(
            dao = dao,
            gson = gson,
            dispatcher = TargetCommandDispatcher {
                dispatches.incrementAndGet()
                io.aaps.copilot.service.dispatchManagedTargetAfterPreflightStatic(
                    preflight = { "manual_target_active_or_pending" },
                    onPreflightBlocked = {},
                    deliver = { posts++; true }
                )
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )
        val request = input(proposalTarget = 6.3, fingerprint = "manual-preflight")

        val first = repository.evaluateAndDispatch(request)
        val firstEnvelope = dao.decisions.single().commandJson
        val second = repository.evaluateAndDispatch(request)

        assertThat(first.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED)
        assertThat(first.reasonCodes).containsExactly(
            "eligible",
            TargetCommandPreflightFailure.MARKER,
            "manual_target_active_or_pending",
            "delivery_failed"
        ).inOrder()
        assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED)
        assertThat(second.reasonCodes).contains("manual_target_active_or_pending")
        assertThat(dispatches.get()).isEqualTo(2)
        assertThat(posts).isEqualTo(0)
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("failed")
        assertThat(dao.decisions.single().outcome).isEqualTo(TargetDecisionOutcome.SEND.name)
        assertThat(firstEnvelope).isNotNull()
        assertThat(dao.decisions.single().commandJson).isEqualTo(firstEnvelope)
        assertThat(dao.decisions.single().reasonCodesJson)
            .contains("manual_target_active_or_pending")
        assertThat(dao.atomicFinalizations.get()).isEqualTo(2)
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .isEqualTo("delivery_failed")
        val stored = gson.fromJson(
            dao.state(TargetManagerMode.ACTIVE.name)?.acceptedTargetJson,
            AcceptedTargetState::class.java
        )
        assertThat(stored.targetMmol).isEqualTo(6.0)
    }

    @Test
    fun newerGlucosePreflightRemainsDistinctFromFalseTransportFailure() = runBlocking {
        val blockedDao = FakeTargetManagerDao()
        val blocked = repository(blockedDao) {
            throw TargetCommandPreflightBlockedException(
                TargetCommandPreflightFailure.CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE
            )
        }.evaluateAndDispatch(input(fingerprint = "newer-glucose"))

        val failedDao = FakeTargetManagerDao()
        val failed = repository(failedDao) { false }
            .evaluateAndDispatch(input(fingerprint = "transport-false"))

        assertThat(blocked.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED)
        assertThat(blocked.reasonCodes).contains("current_glucose_newer_than_candidate")
        assertThat(blockedDao.decisions.single().deliveryStatus).isEqualTo("failed")
        assertThat(failed.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED)
        assertThat(failed.reasonCodes).contains("delivery_failed")
        assertThat(failed.reasonCodes).doesNotContain(TargetCommandPreflightFailure.MARKER)
        assertThat(failedDao.decisions.single().deliveryStatus).isEqualTo("failed")
    }

    @Test
    fun typedPreflightChangesOnlyDiagnosticReasonsComparedWithFalseJournal() = runBlocking {
        val gson = Gson()
        val request = input(proposalTarget = 6.3, fingerprint = "journal-comparison")
        val ordinaryDao = FakeTargetManagerDao()
        val typedDao = FakeTargetManagerDao()
        listOf(ordinaryDao, typedDao).forEach {
            it.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 6.0), gson))
        }
        val ordinary = repository(ordinaryDao, gson = gson) { false }.evaluateAndDispatch(request)
        val typed = repository(typedDao, gson = gson) {
            throw TargetCommandPreflightBlockedException(TargetCommandPreflightFailure.KILL_SWITCH)
        }.evaluateAndDispatch(request)

        val ordinaryEntity = ordinaryDao.decisions.single()
        assertThat(typedDao.decisions.single().copy(reasonCodesJson = ordinaryEntity.reasonCodesJson))
            .isEqualTo(ordinaryEntity)
        assertThat(typed.copy(reasonCodes = ordinary.reasonCodes)).isEqualTo(ordinary)
        assertThat(typedDao.state(TargetManagerMode.ACTIVE.name))
            .isEqualTo(ordinaryDao.state(TargetManagerMode.ACTIVE.name))
    }

    @Test
    fun typedPreflightDoesNotInventAbsentOrRedispatchUnknownSentOrCancelledReconciliation() = runBlocking {
        listOf("unknown", "sent", "cancelled").forEach { status ->
            val dao = FakeTargetManagerDao()
            var dispatches = 0
            var reconciliations = 0
            val cancellation = CancellationException("cancel reconciliation")
            val repository = TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher {
                    dispatches++
                    throw TargetCommandPreflightBlockedException(
                        TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING
                    )
                },
                deliveryStatusProvider = TargetDeliveryStatusProvider {
                    reconciliations++
                    if (status == "cancelled") throw cancellation
                    status
                }
            )
            val request = input(fingerprint = "preflight-reconcile-$status")
            repository.evaluateAndDispatch(request)
            val before = dao.decisions.single()
            val beforeState = dao.state(TargetManagerMode.ACTIVE.name)
            if (status == "cancelled") {
                assertThat(captureFailure { repository.evaluateAndDispatch(request) })
                    .isSameInstanceAs(cancellation)
            } else {
                val second = repository.evaluateAndDispatch(request)
                assertThat(second.reasonCodes).contains(
                    if (status == "sent") "delivery_reconciled_sent" else "pending_delivery_unresolved"
                )
                assertThat(second.reasonCodes).doesNotContain(TargetCommandPreflightFailure.MARKER)
            }
            assertThat(reconciliations).isEqualTo(1)
            assertThat(dispatches).isEqualTo(1)
            if (status != "sent") {
                assertThat(dao.decisions.single()).isEqualTo(before)
                assertThat(dao.state(TargetManagerMode.ACTIVE.name)).isEqualTo(beforeState)
                assertThat(dao.atomicFinalizations.get()).isEqualTo(1)
            } else {
                assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
            }
        }
    }

    @Test
    fun retryAfterTypedRefusalDoesNotMaskFalseUnknownOrCancelledTransport() = runBlocking {
        listOf("false", "unknown", "cancelled").forEach { result ->
            val dao = FakeTargetManagerDao()
            var dispatches = 0
            val cancellation = CancellationException("cancel transport")
            val repository = TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher {
                    if (++dispatches == 1) {
                        throw TargetCommandPreflightBlockedException(
                            TargetCommandPreflightFailure.MANUAL_TARGET_ACTIVE_OR_PENDING
                        )
                    }
                    when (result) {
                        "unknown" -> throw IllegalStateException("transport outcome unknown")
                        "cancelled" -> throw cancellation
                        else -> false
                    }
                },
                deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
            )
            val request = input(fingerprint = "preflight-then-$result")
            repository.evaluateAndDispatch(request)
            if (result == "cancelled") {
                assertThat(captureFailure { repository.evaluateAndDispatch(request) })
                    .isSameInstanceAs(cancellation)
            } else {
                val second = repository.evaluateAndDispatch(request)
                assertThat(second.outcome).isEqualTo(TargetDecisionOutcome.DELIVERY_FAILED)
                assertThat(second.reasonCodes).doesNotContain(TargetCommandPreflightFailure.MARKER)
                assertThat(second.reasonCodes).contains(
                    if (result == "false") "delivery_failed" else "delivery_status_unknown"
                )
            }
            assertThat(dispatches).isEqualTo(2)
            assertThat(dao.decisions.single().reasonCodesJson)
                .doesNotContain(TargetCommandPreflightFailure.MARKER)
            assertThat(dao.decisions.single().deliveryStatus)
                .isEqualTo(if (result == "false") "failed" else "pending")
        }
    }

    @Test
    fun explicitAbsentRetriesOnlyTheSamePersistedKey() = runBlocking {
        val dao = FakeTargetManagerDao()
        val keys = mutableListOf<String>()
        val attempts = AtomicInteger()
        val initial = agedInput()
        val repository = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { command ->
                keys += command.idempotencyKey
                attempts.incrementAndGet() >= 2
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )

        repository.evaluateAndDispatch(initial)
        repository.evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(keys).hasSize(2)
        assertThat(keys.distinct()).hasSize(1)
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun failedSameFingerprintRespectsCurrentCadenceWithoutQuarantine() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        repository(dao) { false }.evaluateAndDispatch(initial)
        val dispatches = AtomicInteger()

        val decision = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(
                initial.copy(
                    nowTs = NOW,
                    lastAutomaticSent = LastSentTempTarget(
                        timestamp = NOW - MINUTE_MS,
                        targetMmol = 5.7,
                        idempotencyKey = "other:recent"
                    )
                )
            )

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SUPPRESS_SEMANTIC_DUPLICATE)
        assertThat(decision.cadenceReason).isEqualTo("duplicate_target_within_window")
        assertThat(decision.nextRuntimeState.reconciliationStatus.startsWith("quarantined")).isFalse()
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("failed")
    }

    @Test
    fun unresolvedPendingBlocksEveryNewFingerprintBeforeManagerDecision() = runBlocking {
        val dao = FakeTargetManagerDao()
        val first = repository(dao) { false }
        first.evaluateAndDispatch(input())
        dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "pending")
        val dispatches = AtomicInteger()
        val restarted = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "unknown" }
        )

        val result = restarted.evaluateAndDispatch(input(fingerprint = "new-event"))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(result.reasonCodes).contains("pending_delivery_unresolved")
        assertThat(dao.decisions).hasSize(1)
    }

    @Test
    fun agedPendingExplicitlyAbsentRetriesOnlyAfterMatchingCurrentDecision() = runBlocking {
        val dao = FakeTargetManagerDao()
        val firstKeys = mutableListOf<String>()
        val initial = agedInput()
        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { command -> firstKeys += command.idempotencyKey; false }
        ).evaluateAndDispatch(initial)
        dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "pending")
        val retryKeys = mutableListOf<String>()
        val retryGeneratedAt = mutableListOf<Long>()
        val restarted = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { command ->
                retryKeys += command.idempotencyKey
                retryGeneratedAt += command.generatedAt
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )

        restarted.evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(retryKeys).containsExactly(firstKeys.single())
        assertThat(retryGeneratedAt).containsExactly(NOW)
        assertThat(dao.decisions).hasSize(1)
        assertThat(dao.decisions.single().timestamp).isEqualTo(NOW)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun agedPendingNeverBypassesCurrentSafetyOrSemanticEvent() = runBlocking {
        val initial = agedInput()
        val manual = ActiveAapsTarget(
            targetMmol = 5.7,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 30 * MINUTE_MS,
            source = "manual",
            ownership = ActiveTargetOwnership.MANUAL_OR_FOREIGN,
            idempotencyKey = "manual:current"
        )
        val blockedInputs = listOf(
            initial.copy(nowTs = NOW, safety = safety().copy(killSwitch = true)),
            initial.copy(nowTs = NOW, safety = safety().copy(dataFresh = false)),
            initial.copy(nowTs = NOW, activeAapsTarget = manual),
            initial.copy(nowTs = NOW, safety = safety(sensorTrust = SensorTrustState.BLOCKED)),
            initial.copy(nowTs = NOW, proposals = listOf(proposal(fingerprint = "new-event"))),
            input(TargetManagerMode.SHADOW).copy(
                nowTs = NOW,
                glucoseTimestamp = initial.glucoseTimestamp,
                therapyWatermark = initial.therapyWatermark
            ),
            input(TargetManagerMode.OFF).copy(
                nowTs = NOW,
                glucoseTimestamp = initial.glucoseTimestamp,
                therapyWatermark = initial.therapyWatermark
            )
        )

        blockedInputs.forEach { current ->
            val dao = FakeTargetManagerDao()
            TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher { false }
            ).evaluateAndDispatch(initial)
            dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "pending")
            val dispatches = AtomicInteger()

            TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
                deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
            ).evaluateAndDispatch(current)

            assertThat(dispatches.get()).isEqualTo(0)
        }
    }

    @Test
    fun concurrentRepositoryInstancesRetryAgedPendingExactlyOnce() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { false }
        ).evaluateAndDispatch(initial)
        dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "pending")
        val dispatches = AtomicInteger()
        val repositories = (1..8).map {
            TargetManagerRepository(
                dao = dao,
                gson = Gson(),
                dispatcher = TargetCommandDispatcher {
                    dispatches.incrementAndGet()
                    delay(50)
                    true
                },
                deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
            )
        }

        repositories.map { repository ->
            async { repository.evaluateAndDispatch(initial.copy(nowTs = NOW)) }
        }.awaitAll()

        assertThat(dispatches.get()).isEqualTo(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("sent")
    }

    @Test
    fun pendingProviderFailureBlocksWithoutDispatch() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        repository(dao) { false }.evaluateAndDispatch(initial)
        dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "pending")
        val dispatches = AtomicInteger()
        val restarted = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { error("provider unavailable") }
        )

        val decision = restarted.evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes).contains("pending_delivery_unresolved")
    }

    @Test
    fun sentPendingRestoresAcceptedStateBeforeEvaluatingNewEvent() = runBlocking {
        val dao = FakeTargetManagerDao()
        repository(dao) { false }.evaluateAndDispatch(input())
        dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "pending")
        val dispatches = AtomicInteger()
        val restarted = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "sent" }
        )

        restarted.evaluateAndDispatch(input(fingerprint = "new-event", proposalTarget = 6.0))

        val restored = Gson().fromJson(
            dao.state(TargetManagerMode.ACTIVE.name)?.acceptedTargetJson,
            AcceptedTargetState::class.java
        )
        assertThat(restored.lastCommandStatus).isEqualTo("sent")
        assertThat(dao.decisions.first().deliveryStatus).isEqualTo("sent")
        assertThat(dispatches.get()).isEqualTo(1)
    }

    @Test
    fun manualTargetWithSameValueIsNeverReclassifiedOrOverwritten() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 5.7), gson))
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }
        val manual = ActiveAapsTarget(
            targetMmol = 5.7,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 30 * MINUTE_MS,
            source = "manual",
            ownership = ActiveTargetOwnership.MANUAL_OR_FOREIGN,
            idempotencyKey = "manual:exact-same-value"
        )

        val decision = repository.evaluateAndDispatch(input().copy(activeAapsTarget = manual))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun unknownTargetWithSameValueIsNeverReclassifiedOrOverwritten() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 5.7), gson))
        val dispatches = AtomicInteger()
        val unknown = ActiveAapsTarget(
            targetMmol = 5.7,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 30 * MINUTE_MS,
            source = "unknown",
            ownership = ActiveTargetOwnership.UNKNOWN,
            idempotencyKey = null
        )

        val decision = repository(dao, gson) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(input().copy(activeAapsTarget = unknown))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET)
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun activeLegacyTargetWithSameValueRemainsLegacyDrain() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 5.7), gson))
        val dispatches = AtomicInteger()
        val legacy = ActiveAapsTarget(
            targetMmol = 5.7,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 30 * MINUTE_MS,
            source = "legacy",
            ownership = ActiveTargetOwnership.LEGACY_COPILOT,
            idempotencyKey = "legacy:key"
        )

        val decision = repository(dao, gson) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(input().copy(activeAapsTarget = legacy))

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_LEGACY_TARGET_DRAIN)
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun finiteAcceptedTargetBelowNewBoundsCanOnlyDispatchSafetyRaise() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, accepted(target = 3.9), gson))
        val sentTargets = mutableListOf<Double>()
        val active = ActiveAapsTarget(
            targetMmol = 3.9,
            startedAt = NOW - 10 * MINUTE_MS,
            expiresAt = NOW + 20 * MINUTE_MS,
            source = "target_manager",
            ownership = ActiveTargetOwnership.TARGET_MANAGER,
            idempotencyKey = "TargetManager.v1:old"
        )

        val decision = repository(dao, gson) { command -> sentTargets += command.targetMmol; true }
            .evaluateAndDispatch(
                input().copy(
                    proposals = emptyList(),
                    activeAapsTarget = active,
                    safety = safety(sensorTrust = SensorTrustState.BLOCKED)
                )
            )

        assertThat(decision.winner?.intent).isEqualTo(TargetIntent.SENSOR_SAFETY_RELEASE)
        assertThat(sentTargets).containsExactly(5.5)
        Unit
    }

    @Test
    fun shadowSensorReleaseUsesOnlyVirtualShadowAcceptedState() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.SHADOW,
                accepted(target = 4.6, status = "shadow", commandId = "shadow:old"),
                gson
            )
        )
        val realLegacy = ActiveAapsTarget(
            targetMmol = 4.2,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 20 * MINUTE_MS,
            source = "legacy",
            ownership = ActiveTargetOwnership.LEGACY_COPILOT,
            idempotencyKey = "legacy:key"
        )
        val repository = repository(dao) { error("shadow must not dispatch") }

        val decision = repository.evaluateAndDispatch(
            input(TargetManagerMode.SHADOW).copy(
                proposals = emptyList(),
                activeAapsTarget = realLegacy,
                safety = safety(sensorTrust = SensorTrustState.BLOCKED)
            )
        )

        assertThat(decision.winner?.intent).isEqualTo(TargetIntent.SENSOR_SAFETY_RELEASE)
        assertThat(decision.winner?.targetMmol).isEqualTo(5.5)
    }

    @Test
    fun shadowNeverBuildsSensorReleaseFromRealLegacyTargetAlone() = runBlocking {
        val dao = FakeTargetManagerDao()
        val legacy = ActiveAapsTarget(
            targetMmol = 4.2,
            startedAt = NOW - MINUTE_MS,
            expiresAt = NOW + 20 * MINUTE_MS,
            source = "legacy",
            ownership = ActiveTargetOwnership.LEGACY_COPILOT,
            idempotencyKey = "legacy:key"
        )

        val decision = repository(dao) { error("shadow must not dispatch") }
            .evaluateAndDispatch(
                input(TargetManagerMode.SHADOW).copy(
                    proposals = emptyList(),
                    activeAapsTarget = legacy,
                    safety = safety(sensorTrust = SensorTrustState.BLOCKED)
                )
            )

        assertThat(decision.winner).isNull()
        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
    }

    @Test
    fun proposalsSharingSourceAndFingerprintAreNotCollapsedBeforeArbitration() = runBlocking {
        val dao = FakeTargetManagerDao()
        val proposals = listOf(
            proposal(target = 5.7, fingerprint = "same"),
            proposal(target = 6.0, fingerprint = "same").copy(intent = TargetIntent.HYPO_PROTECTION)
        )

        val decision = repository(dao) { true }
            .evaluateAndDispatch(input().copy(proposals = proposals))

        assertThat(decision.winner?.intent).isEqualTo(TargetIntent.HYPO_PROTECTION)
        assertThat(decision.winner?.targetMmol).isEqualTo(6.0)
    }

    @Test
    fun keepaliveRequiresModeSpecificConfirmedStatusAndPreservesOwner() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.SHADOW,
                accepted(
                    target = 6.0,
                    status = "shadow",
                    commandId = "shadow:old",
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "original-owner"
                ),
                gson
            )
        )
        val repository = repository(dao) { error("shadow must not dispatch") }

        val decision = repository.evaluateAndDispatch(
            input(TargetManagerMode.SHADOW).copy(proposals = emptyList(), lastAutomaticSent = null)
        )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.SHADOW_WOULD_SEND)
        assertThat(decision.nextRuntimeState.acceptedTarget?.ownerRuleId).isEqualTo("original-owner")
    }

    @Test
    fun activeKeepaliveRequiresSentStatusAndPreservesOwner() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 6.0,
                    status = "sent",
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "original-owner"
                ),
                gson
            )
        )
        val dispatches = AtomicInteger()

        val decision = repository(dao, gson) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(
                input(safetyIobUnits = null).copy(proposals = emptyList(), lastAutomaticSent = null)
            )

        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        assertThat(decision.nextRuntimeState.acceptedTarget?.ownerRuleId).isEqualTo("original-owner")
        assertThat(dispatches.get()).isEqualTo(1)
    }

    @Test
    fun activeLoweredKeepaliveDoesNotDispatchAfterSafetyIobDisappears() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        dao.upsertState(
            stateEntity(
                TargetManagerMode.ACTIVE,
                accepted(
                    target = 4.8,
                    status = "sent",
                    expiresAt = NOW + 4 * MINUTE_MS,
                    owner = "original-owner"
                ),
                gson
            )
        )
        val dispatches = AtomicInteger()
        val activeTarget = ActiveAapsTarget(
            targetMmol = 4.8,
            startedAt = NOW - 10 * MINUTE_MS,
            expiresAt = NOW + 20 * MINUTE_MS,
            source = "copilot",
            ownership = ActiveTargetOwnership.TARGET_MANAGER,
            idempotencyKey = "active-low"
        )

        val decision = repository(dao, gson) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(
                input(
                    proposals = emptyList(),
                    activeAapsTarget = activeTarget,
                    safetyIobUnits = null
                )
            )

        assertThat(decision.command).isNull()
        assertThat(decision.outcome).isNotEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun corruptAndOversizedRuntimeAreQuarantinedWithoutDispatch() = runBlocking {
        val nullShapedJson = """
            {
              "revision":1,
              "targetMmol":5.5,
              "durationMinutes":30,
              "ownerRuleId":null,
              "intent":"NORMAL_CONTROL",
              "acceptedAt":1799998800000,
              "expiresAt":1800000600000,
              "lastInputFingerprint":"previous",
              "lastCommandId":"TargetManager.v1:old",
              "lastCommandStatus":"sent"
            }
        """.trimIndent()
        listOf(
            "{bad-json",
            nullShapedJson,
            "x".repeat(TargetManagerRepository.MAX_JSON_BYTES + 1)
        ).forEach { raw ->
            val dao = FakeTargetManagerDao()
            dao.upsertState(
                TargetManagerStateEntity(
                    mode = TargetManagerMode.ACTIVE.name,
                    updatedAt = NOW,
                    acceptedTargetJson = raw,
                    lastDecisionFingerprint = null,
                    lastSafetyBypassFingerprint = null,
                    reconciliationStatus = "confirmed"
                )
            )
            val dispatches = AtomicInteger()

            val decision = repository(dao) { dispatches.incrementAndGet(); true }
                .evaluateAndDispatch(input())
            val afterRestart = repository(dao) { dispatches.incrementAndGet(); true }
                .evaluateAndDispatch(input(fingerprint = "second-cycle"))

            assertThat(dispatches.get()).isEqualTo(0)
            assertThat(decision.reasonCodes.single()).contains("runtime_state_quarantined")
            assertThat(afterRestart.reasonCodes.single()).contains("runtime_state_quarantined")
            assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.acceptedTargetJson).isEqualTo(raw)
            assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
                .startsWith("quarantined")
            dao.decisions.forEach { stored ->
                assertThat(stored.reasonCodesJson.toByteArray().size)
                    .isAtMost(TargetManagerRepository.MAX_JSON_BYTES)
            }
        }
    }

    @Test
    fun legacyAcceptedTargetJsonWithoutActivityProposalLoadsWithoutQuarantine() = runBlocking {
        val legacyJson = """
            {
              "revision":4,
              "targetMmol":5.5,
              "durationMinutes":30,
              "ownerRuleId":"previous",
              "intent":"NORMAL_CONTROL",
              "acceptedAt":1799999400000,
              "expiresAt":1800001200000,
              "lastInputFingerprint":"previous",
              "lastCommandId":"TargetManager.v1:old",
              "lastCommandStatus":"sent"
            }
        """.trimIndent()
        val dao = FakeTargetManagerDao()
        dao.upsertState(
            TargetManagerStateEntity(
                mode = TargetManagerMode.ACTIVE.name,
                updatedAt = NOW,
                acceptedTargetJson = legacyJson,
                lastDecisionFingerprint = null,
                lastSafetyBypassFingerprint = null,
                reconciliationStatus = "confirmed"
            )
        )
        val dispatches = AtomicInteger()

        val decision = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(input().copy(proposals = emptyList(), lastAutomaticSent = null))

        assertThat(JsonParser.parseString(legacyJson).asJsonObject.has("activityProposal")).isEqualTo(false)
        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.NO_PROPOSAL)
        assertThat(decision.nextRuntimeState.acceptedTarget?.activityProposal).isNull()
        assertThat(decision.nextRuntimeState.reconciliationStatus).doesNotContain("quarantined")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .doesNotContain("quarantined")
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun corruptPendingEnvelopeLatchesQuarantineAcrossRestart() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { false }
        ).evaluateAndDispatch(initial)
        dao.decisions[0] = dao.decisions.single().copy(
            commandJson = "{bad-envelope",
            deliveryStatus = "pending"
        )
        val dispatches = AtomicInteger()

        repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(initial.copy(nowTs = NOW))
        val afterRestart = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(input(fingerprint = "after-restart"))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(afterRestart.reasonCodes.single()).contains("runtime_state_quarantined")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .startsWith("quarantined")
    }

    @Test
    fun confirmedAbsentKnownMismatchQuarantineRecoversWithoutReplayingOldCommand() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { false }
        ).evaluateAndDispatch(initial)
        val failed = dao.decisions.single()
        dao.decisions[0] = failed.copy(deliveryStatus = "quarantined")
        dao.upsertState(
            checkNotNull(dao.state(TargetManagerMode.ACTIVE.name)).copy(
                reconciliationStatus = "quarantined:existing_envelope_current_mismatch"
            )
        )
        val dispatched = mutableListOf<String>()
        val restarted = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { command ->
                dispatched += command.idempotencyKey
                true
            },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
        )

        val decision = restarted.evaluateAndDispatch(input(fingerprint = "new-safe-context"))

        assertThat(dispatched).hasSize(1)
        assertThat(dispatched.single())
            .isNotEqualTo("TargetManager.v1:${failed.semanticFingerprint}")
        assertThat(dao.decisions.first().deliveryStatus).isEqualTo("superseded")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .doesNotContain("quarantined")
        assertThat(decision.nextRuntimeState.reconciliationStatus).doesNotContain("quarantined")
    }

    @Test
    fun diagnosticTrafficCannotHideQuarantineRecoveryEvidence() = runBlocking {
        for (delivery in listOf("absent", "sent", "unknown")) {
            val dao = FakeTargetManagerDao()
            TargetManagerRepository(dao, Gson(), TargetCommandDispatcher { false })
                .evaluateAndDispatch(agedInput())
            val original = dao.decisions.single()
            dao.decisions[0] = original.copy(deliveryStatus = "quarantined")
            dao.upsertState(checkNotNull(dao.state("ACTIVE")).copy(
                reconciliationStatus = "quarantined:existing_envelope_current_mismatch"
            ))
            repeat(80) { index ->
                dao.insertDecision(original.copy(
                    id = "diagnostic-$index",
                    timestamp = NOW + index,
                    semanticFingerprint = "diagnostic-$index",
                    deliveryStatus = "read_only",
                    commandJson = null
                ))
            }
            val checked = mutableListOf<String>()
            val dispatched = mutableListOf<String>()
            val repository = TargetManagerRepository(
                dao, Gson(), TargetCommandDispatcher { command ->
                    dispatched += command.idempotencyKey
                    true
                },
                deliveryStatusProvider = TargetDeliveryStatusProvider { key ->
                    checked += key
                    delivery
                }
            )

            val decision = repository.evaluateAndDispatch(input(fingerprint = "fresh-after-noise"))

            assertThat(checked).containsExactly("TargetManager.v1:${original.semanticFingerprint}")
            assertThat(dispatched).doesNotContain("TargetManager.v1:${original.semanticFingerprint}")
            if (delivery == "unknown") {
                assertThat(dispatched).isEmpty()
                assertThat(decision.reasonCodes.single()).contains("runtime_state_quarantined")
            } else {
                assertThat(dao.state("ACTIVE")?.reconciliationStatus).doesNotContain("quarantined")
                assertThat(dao.decisions.first().deliveryStatus)
                    .isEqualTo(if (delivery == "sent") "sent" else "superseded")
            }
        }
    }

    @Test
    fun unknownKnownMismatchQuarantineRemainsFailClosed() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { false }
        ).evaluateAndDispatch(initial)
        dao.decisions[0] = dao.decisions.single().copy(deliveryStatus = "quarantined")
        dao.upsertState(
            checkNotNull(dao.state(TargetManagerMode.ACTIVE.name)).copy(
                reconciliationStatus = "quarantined:existing_envelope_current_mismatch"
            )
        )
        val dispatches = AtomicInteger()

        val decision = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { dispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "unknown" }
        ).evaluateAndDispatch(input(fingerprint = "new-safe-context"))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes.single()).contains("runtime_state_quarantined")
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("quarantined")
    }

    @Test
    fun selfConsistentForeignPendingKeyIsQuarantinedWithoutDispatch() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { false }
        ).evaluateAndDispatch(initial)
        val pending = dao.decisions.single()
        val envelope = JsonParser.parseString(pending.commandJson).asJsonObject
        envelope.getAsJsonObject("command").addProperty("idempotencyKey", "foreign:forged")
        envelope.getAsJsonObject("acceptedTarget").addProperty("lastCommandId", "foreign:forged")
        dao.decisions[0] = pending.copy(
            commandJson = envelope.toString(),
            deliveryStatus = "pending"
        )
        val dispatches = AtomicInteger()

        repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(initial.copy(nowTs = NOW))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .startsWith("quarantined")
    }

    @Test
    fun dispatcherExceptionKeepsPendingAndUnknownStatusBlocksNewEvent() = runBlocking {
        val dao = FakeTargetManagerDao()
        val initial = agedInput()
        val sideEffects = AtomicInteger()

        TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher {
                sideEffects.incrementAndGet()
                error("timeout after write")
            }
        ).evaluateAndDispatch(initial)

        assertThat(sideEffects.get()).isEqualTo(1)
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("pending")
        val retryDispatches = AtomicInteger()
        val decision = TargetManagerRepository(
            dao = dao,
            gson = Gson(),
            dispatcher = TargetCommandDispatcher { retryDispatches.incrementAndGet(); true },
            deliveryStatusProvider = TargetDeliveryStatusProvider { "unknown" }
        ).evaluateAndDispatch(input(fingerprint = "new-event"))

        assertThat(retryDispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes).contains("pending_delivery_unresolved")
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("pending")
    }

    @Test
    fun zeroRowFinalizationNeverReportsConfirmedAndLatchesQuarantine() = runBlocking {
        val dao = FakeTargetManagerDao().apply { forceAtomicUpdateResultZero = true }

        val decision = repository(dao) { true }.evaluateAndDispatch(input())

        assertThat(decision.reasonCodes.joinToString()).contains("persistence")
        assertThat(decision.nextRuntimeState.reconciliationStatus).startsWith("quarantined")
        assertThat(dao.state(TargetManagerMode.ACTIVE.name)?.reconciliationStatus)
            .startsWith("quarantined")
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("pending")
    }

    @Test
    fun oversizedProposalJournalFailsClosedWithoutNetwork() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()
        val huge = input().copy(
            proposals = listOf(
                proposal(
                    reasonCodes = List(400) { "r$it-${"x".repeat(400)}" },
                    fingerprint = "huge"
                )
            )
        )

        val decision = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(huge)

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes).contains("journal_payload_oversized")
        dao.decisions.single().let { row ->
            listOfNotNull(row.winnerJson, row.commandJson, row.reasonCodesJson, row.rejectedProposalReasonsJson)
                .forEach { assertThat(it.toByteArray().size).isAtMost(TargetManagerRepository.MAX_JSON_BYTES) }
        }
    }

    @Test
    fun pruneDecisionsUsesCheckedFortyFiveDayCutoff() = runBlocking {
        val dao = FakeTargetManagerDao()
        val repository = repository(dao) { true }

        repository.pruneDecisions(NOW)

        assertThat(dao.lastDeleteCutoff)
            .isEqualTo(NOW - TargetManagerRepository.DECISION_RETENTION_MS)
    }

    @Test
    fun managerCheckedArithmeticFailureProducesBoundedDiagnosticWithoutDispatch() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()

        val decision = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(input().copy(nowTs = Long.MAX_VALUE))

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes).contains("checked_arithmetic_failed")
        assertThat(dao.decisions.single().deliveryStatus).isEqualTo("blocked")
    }

    @Test
    fun invalidNewCommandTimestampFailsClosedBeforeNetwork() = runBlocking {
        val dao = FakeTargetManagerDao()
        val dispatches = AtomicInteger()

        val decision = repository(dao) { dispatches.incrementAndGet(); true }
            .evaluateAndDispatch(
                input().copy(
                    nowTs = -1L,
                    glucoseTimestamp = -2L,
                    therapyWatermark = -3L
                )
            )

        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(decision.reasonCodes).contains("accepted_timestamp_invalid")
    }

    private fun repository(
        dao: FakeTargetManagerDao,
        gson: Gson = Gson(),
        dispatch: suspend (io.aaps.copilot.domain.target.TargetCommandCandidate) -> Boolean
    ) = TargetManagerRepository(
        dao = dao,
        gson = gson,
        dispatcher = TargetCommandDispatcher(dispatch),
        deliveryStatusProvider = TargetDeliveryStatusProvider { "absent" }
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private fun cancellationAndEveryError(): List<Throwable> = listOf(
        CancellationException("cancelled"),
        AssertionError("assertion"),
        LinkageError("linkage"),
        TestTargetManagerVirtualMachineError(),
        ThreadDeath()
    )

    private fun acceptedReaderFailureGson(failure: Throwable): Gson = GsonBuilder()
        .registerTypeAdapter(
            AcceptedTargetState::class.java,
            object : TypeAdapter<AcceptedTargetState>() {
                override fun write(out: JsonWriter, value: AcceptedTargetState?) {
                    out.nullValue()
                }

                override fun read(`in`: JsonReader): AcceptedTargetState = throw failure
            }
        )
        .create()

    private fun proposalWriterFailureGson(failure: Throwable): Gson = GsonBuilder()
        .registerTypeAdapter(
            TargetProposal::class.java,
            object : TypeAdapter<TargetProposal>() {
                override fun write(out: JsonWriter, value: TargetProposal?) {
                    throw failure
                }

                override fun read(`in`: JsonReader): TargetProposal? {
                    `in`.skipValue()
                    return null
                }
            }
        )
        .create()

    @Test
    fun oldHypoKeepaliveDoesNotDispatchWhenCurrentAdaptiveDecreaseIsUnreliable() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        val oldTarget = accepted(target = 10.0, expiresAt = NOW + 4 * MINUTE_MS,
            owner = "adaptive", intent = TargetIntent.HYPO_PROTECTION)
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, oldTarget, gson))
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }
        val base = input(proposalTarget = 4.9)
        val context = base.copy(
            safety = base.safety.copy(baseTargetMmol = 5.9, currentGlucoseMmol = 8.7,
                minimumPredictedOrCiMmol = 5.7),
            reliability = base.reliability.mapValues { it.value.copy(state = HorizonReliabilityState.DEGRADED) }
        )
        repeat(2) {
            val decision = repository.evaluateAndDispatch(context)
            assertThat(decision.command).isNull()
            assertThat(decision.nextRuntimeState.acceptedTarget?.expiresAt).isEqualTo(oldTarget.expiresAt)
            assertThat(decision.rejectedProposalReasons[TargetManager.RENEWAL_SOURCE_RULE_ID])
                .isEqualTo("invalid_keepalive")
        }
        assertThat(dispatches.get()).isEqualTo(0)
    }

    @Test
    fun oldHypoKeepaliveStillDispatchesWhenCurrentLowNeedsProtection() = runBlocking {
        val dao = FakeTargetManagerDao()
        val gson = Gson()
        val oldTarget = accepted(target = 10.0, expiresAt = NOW + 4 * MINUTE_MS,
            owner = "adaptive", intent = TargetIntent.HYPO_PROTECTION)
        dao.upsertState(stateEntity(TargetManagerMode.ACTIVE, oldTarget, gson))
        val dispatches = AtomicInteger()
        val repository = repository(dao, gson) { dispatches.incrementAndGet(); true }
        val base = input(proposals = emptyList())
        val decision = repository.evaluateAndDispatch(base.copy(
            safety = base.safety.copy(currentGlucoseMmol = 3.8)
        ))
        assertThat(decision.outcome).isEqualTo(TargetDecisionOutcome.RENEW_SAME_TARGET)
        assertThat(dispatches.get()).isEqualTo(1)
        assertThat(decision.nextRuntimeState.acceptedTarget?.expiresAt).isGreaterThan(oldTarget.expiresAt)
    }

    private fun input(
        mode: TargetManagerMode = TargetManagerMode.ACTIVE,
        proposalTarget: Double = 5.7,
        fingerprint: String = "cycle",
        proposal: TargetProposal = proposal(target = proposalTarget, fingerprint = fingerprint),
        proposals: List<TargetProposal> = listOf(proposal),
        activitySafety: ActivityTargetSafetyContext = ActivityTargetSafetyContext(),
        activeAapsTarget: ActiveAapsTarget? = null,
        safetyIobUnits: Double? = 1.0
    ) = TargetManagerInput(
        nowTs = NOW,
        glucoseTimestamp = NOW - MINUTE_MS,
        therapyWatermark = NOW - 2 * MINUTE_MS,
        mode = mode,
        proposals = proposals,
        runtimeState = TargetManagerRuntimeState(mode),
        activeAapsTarget = activeAapsTarget,
        safety = safety().copy(safetyIobUnits = safetyIobUnits),
        reliability = mapOf(5 to reliability(5), 30 to reliability(30), 60 to reliability(60)),
        lastAutomaticSent = null,
        baseProvenance = TargetBaseProvenance(1L, "interval", null),
        activitySafety = activitySafety,
        sensitivityRuntime = io.aaps.copilot.testTargetSensitivityRuntimeContext()
    )

    private fun managerFailureInput(failure: Throwable): TargetManagerInput = input(
        proposalTarget = 5.0
    ).copy(
        reliability = object : Map<Int, HorizonReliability> by emptyMap() {
            override fun get(key: Int): HorizonReliability? = throw failure
        }
    )

    private fun agedInput(): TargetManagerInput = input().copy(
        nowTs = NOW - 3 * MINUTE_MS,
        glucoseTimestamp = NOW - 4 * MINUTE_MS,
        therapyWatermark = NOW - 5 * MINUTE_MS
    )

    private fun diagnosticInput(nowTs: Long, fingerprint: String): TargetManagerInput = input(
        fingerprint = fingerprint,
        proposal = proposal(fingerprint = fingerprint).copy(generatedAt = nowTs)
    ).copy(
        nowTs = nowTs,
        glucoseTimestamp = nowTs - MINUTE_MS,
        therapyWatermark = nowTs - 2 * MINUTE_MS
    )

    private class DirectTargetManagerThrowable : Throwable("direct target manager failure")

    private fun proposal(
        target: Double = 5.7,
        fingerprint: String = "cycle",
        reasonCodes: List<String> = listOf("control")
    ) = TargetProposal(
        sourceRuleId = "adaptive",
        intent = TargetIntent.NORMAL_CONTROL,
        targetMmol = target,
        durationMinutes = 30,
        priority = 100,
        confidence = 0.9,
        reasonCodes = reasonCodes,
        generatedAt = NOW,
        inputFingerprint = fingerprint
    )

    private fun activityTarget(activity: ActivityTargetProposal) = TargetProposal(
        sourceRuleId = ActivityTargetProposalFactory.SOURCE_RULE_ID,
        intent = TargetIntent.PLANNED_ACTIVITY_ADAPTATION,
        targetMmol = checkNotNull(activity.targetMmol),
        durationMinutes = 30,
        priority = 350,
        confidence = 0.75,
        reasonCodes = listOf("planned_activity"),
        generatedAt = NOW,
        inputFingerprint = "planned-activity",
        activityProposal = activity
    )

    private fun activityRaise() = ActivityTargetProposal(
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

    private fun activitySafety(activity: ActivityTargetProposal) = ActivityTargetSafetyContext(
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

    private fun safety(sensorTrust: SensorTrustState = SensorTrustState.TRUSTED) =
        TargetManagerSafetyContext(
            killSwitch = false,
            dataFresh = true,
            sensorTrust = sensorTrust,
            deliveryTrust = DeliveryTrustState.NORMAL,
            currentGlucoseMmol = 7.0,
            minimumPredictedOrCiMmol = 6.0,
            lowRiskThresholdMmol = 4.4,
            minTargetMmol = 4.0,
            maxTargetMmol = 10.0,
            minDurationMinutes = 15,
            maxDurationMinutes = 120,
            baseTargetMmol = 5.5,
            safetyIobUnits = 1.0
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

    private fun accepted(
        target: Double,
        status: String = "sent",
        commandId: String = "TargetManager.v1:old",
        expiresAt: Long = NOW + 20 * MINUTE_MS,
        owner: String = "previous",
        intent: TargetIntent = TargetIntent.NORMAL_CONTROL,
        activityProposal: ActivityTargetProposal? = null
    ) = AcceptedTargetState(
        revision = 4L,
        targetMmol = target,
        durationMinutes = 30,
        ownerRuleId = owner,
        intent = intent,
        acceptedAt = expiresAt - 30 * MINUTE_MS,
        expiresAt = expiresAt,
        lastInputFingerprint = "previous",
        lastCommandId = commandId,
        lastCommandStatus = status,
        activityProposal = activityProposal
    )

    private fun stateEntity(
        mode: TargetManagerMode,
        accepted: AcceptedTargetState,
        gson: Gson
    ) = TargetManagerStateEntity(
        mode = mode.name,
        updatedAt = NOW,
        acceptedTargetJson = gson.toJson(accepted),
        lastDecisionFingerprint = null,
        lastSafetyBypassFingerprint = null,
        reconciliationStatus = if (mode == TargetManagerMode.SHADOW) "shadow" else "confirmed"
    )

    private class FakeTargetManagerDao : TargetManagerDao {
        override suspend fun invalidateTargetManagerLiveStatus(): Int = 0

        private val states = mutableMapOf<String, TargetManagerStateEntity>()
        val decisions = mutableListOf<TargetManagerDecisionEntity>()
        val atomicFinalizations = AtomicInteger()
        var lastDeleteCutoff: Long? = null
        var forceAtomicUpdateResultZero: Boolean = false
        var failReadOnlyDiagnosticInsert: Boolean = false

        override suspend fun state(mode: String): TargetManagerStateEntity? = synchronized(this) { states[mode] }

        override suspend fun upsertState(state: TargetManagerStateEntity) {
            synchronized(this) { states[state.mode] = state }
        }

        override suspend fun insertDecision(decision: TargetManagerDecisionEntity): Long = synchronized(this) {
            if (failReadOnlyDiagnosticInsert && decision.deliveryStatus == "read_only") {
                throw IllegalStateException("diagnostic insert failed")
            }
            if (decisions.any { it.mode == decision.mode && it.semanticFingerprint == decision.semanticFingerprint }) {
                -1L
            } else {
                decisions += decision
                decisions.size.toLong()
            }
        }

        override suspend fun updateDecision(decision: TargetManagerDecisionEntity): Int = synchronized(this) {
            val index = decisions.indexOfFirst { it.id == decision.id }
            if (index < 0) 0 else {
                decisions[index] = decision
                1
            }
        }

        override suspend fun decisionByFingerprint(
            mode: String,
            semanticFingerprint: String
        ): TargetManagerDecisionEntity? = synchronized(this) {
            decisions.firstOrNull { it.mode == mode && it.semanticFingerprint == semanticFingerprint }
        }

        override suspend fun pendingDecisions(): List<TargetManagerDecisionEntity> = synchronized(this) {
            decisions.filter { it.deliveryStatus == "pending" }.sortedBy { it.timestamp }
        }

        override suspend fun latestDecisions(limit: Int): List<TargetManagerDecisionEntity> = synchronized(this) {
            decisions.sortedByDescending { it.timestamp }.take(limit)
        }

        override suspend fun latestQuarantinedDecision(mode: String): TargetManagerDecisionEntity? = synchronized(this) {
            decisions.filter { it.mode == mode && it.deliveryStatus == "quarantined" }
                .sortedWith(compareByDescending<TargetManagerDecisionEntity> { it.timestamp }.thenByDescending { it.id })
                .firstOrNull()
        }

        override suspend fun between(
            fromTs: Long,
            throughTs: Long
        ): List<TargetManagerDecisionEntity> = synchronized(this) {
            decisions.filter { it.timestamp in fromTs..throughTs }
        }

        override suspend fun clinicalEvidenceBetween(
            fromTs: Long,
            throughTs: Long,
            limit: Int
        ): List<ClinicalTargetManagerEvidenceProjection> = synchronized(this) {
            decisions.filter { it.timestamp in fromTs..throughTs }
                .sortedWith(compareBy<TargetManagerDecisionEntity> { it.timestamp }.thenBy { it.id })
                .take(limit)
                .map {
                    ClinicalTargetManagerEvidenceProjection(
                        it.id,
                        it.timestamp,
                        it.outcome,
                        it.winnerJson,
                        it.reasonCodesJson
                    )
                }
        }

        override suspend fun latestReadOnlyDiagnosticTimestamp(): Long? = synchronized(this) {
            decisions.filter { it.deliveryStatus == "read_only" }.maxOfOrNull { it.timestamp }
        }

        override suspend fun deleteReadOnlyDiagnosticsOlderThan(olderThan: Long): Int {
            lastDeleteCutoff = olderThan
            return synchronized(this) {
                val before = decisions.size
                decisions.removeAll {
                    it.deliveryStatus == "read_only" && it.timestamp < olderThan
                }
                before - decisions.size
            }
        }

        override suspend fun deleteReadOnlyDiagnosticsBeyondLimit(maxRows: Int): Int = synchronized(this) {
            val retainedIds = decisions.asSequence()
                .filter { it.deliveryStatus == "read_only" }
                .sortedWith(
                    compareByDescending<TargetManagerDecisionEntity> { it.timestamp }
                        .thenByDescending { it.id }
                )
                .take(maxRows)
                .mapTo(mutableSetOf()) { it.id }
            val before = decisions.size
            decisions.removeAll {
                it.deliveryStatus == "read_only" && it.id !in retainedIds
            }
            before - decisions.size
        }

        override suspend fun insertDecisionAndState(
            decision: TargetManagerDecisionEntity,
            state: TargetManagerStateEntity
        ): Long {
            val inserted = insertDecision(decision)
            if (inserted != -1L) upsertState(state)
            return inserted
        }

        override suspend fun updateDecisionAndState(
            decision: TargetManagerDecisionEntity,
            state: TargetManagerStateEntity
        ): Int {
            atomicFinalizations.incrementAndGet()
            if (forceAtomicUpdateResultZero) return 0
            val updated = updateDecision(decision)
            if (updated == 1) upsertState(state)
            return updated
        }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val MINUTE_MS = 60_000L
        private const val READ_ONLY_DIAGNOSTIC_EXPECTED_CAP = 256
    }
}

private class TestTargetManagerVirtualMachineError : VirtualMachineError()
