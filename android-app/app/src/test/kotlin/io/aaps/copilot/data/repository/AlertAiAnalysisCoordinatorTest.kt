package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import okio.ByteString.Companion.toByteString

class AlertAiAnalysisCoordinatorTest {
    @Test
    fun durableClaimPrecedesContextBuildAndDuplicateSkipsSecondBuild() = runTest {
        val dao = FakeDao()
        val contextEntered = CompletableDeferred<Unit>()
        val releaseContext = CompletableDeferred<Unit>()
        val contextBuilds = AtomicInteger()
        val gatewayCalls = AtomicInteger()
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource { "synthetic-secret" },
            networkGate = AlertAiNetworkGate { networkSnapshot(true) },
            contextSource = AlertAiAnalysisContextSource {
                if (contextBuilds.incrementAndGet() == 1) {
                    contextEntered.complete(Unit)
                    releaseContext.await()
                }
                context()
            },
            dao = dao,
            gatewayFactory = gatewayFactory(config, gatewayCalls),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )
        val first = async { coordinator.run(trigger("prebuild-dedup")) }
        contextEntered.await()

        try {
            assertThat(dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.RUNNING.name)
            val duplicate = async { coordinator.run(trigger("prebuild-dedup")) }
            assertThat(duplicate.await()).isEqualTo(AlertAiAnalysisOutcome.ALREADY_CLAIMED)
            assertThat(contextBuilds.get()).isEqualTo(1)
            assertThat(gatewayCalls.get()).isEqualTo(0)
        } finally {
            releaseContext.complete(Unit)
        }

        assertThat(first.await()).isEqualTo(AlertAiAnalysisOutcome.COMPLETED)
        assertThat(gatewayCalls.get()).isEqualTo(1)
    }

    @Test
    fun concurrentDistinctEpisodeIsTerminallyRejectedWithoutQueueOrSecondBuild() = runTest {
        val dao = FakeDao()
        val contextEntered = CompletableDeferred<Unit>()
        val releaseContext = CompletableDeferred<Unit>()
        val contextBuilds = AtomicInteger()
        val gatewayCalls = AtomicInteger()
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource { "synthetic-secret" },
            networkGate = AlertAiNetworkGate { networkSnapshot(true) },
            contextSource = AlertAiAnalysisContextSource {
                contextBuilds.incrementAndGet()
                contextEntered.complete(Unit)
                releaseContext.await()
                context()
            },
            dao = dao,
            gatewayFactory = gatewayFactory(config, gatewayCalls),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )
        val first = async { coordinator.run(trigger("busy-first")) }
        contextEntered.await()

        try {
            val rejectedAttempt = async { coordinator.run(trigger("busy-second")) }
            val rejected = withTimeoutOrNull(1_000L) { rejectedAttempt.await() }

            assertThat(rejected).isEqualTo(AlertAiAnalysisOutcome.FAILED)
            assertThat(contextBuilds.get()).isEqualTo(1)
            assertThat(gatewayCalls.get()).isEqualTo(0)
            val rejectedRow = requireNotNull(dao.byEpisodeId("busy-second"))
            assertThat(rejectedRow.status).isEqualTo(AlertAiAnalysisStatus.FAILED.name)
            assertThat(rejectedRow.sanitizedError).isEqualTo("ADMISSION_BUSY")
        } finally {
            releaseContext.complete(Unit)
        }

        assertThat(first.await()).isEqualTo(AlertAiAnalysisOutcome.COMPLETED)
        assertThat(gatewayCalls.get()).isEqualTo(1)
    }

    @Test
    fun postInsertExecutionIdentityDriftTerminalizesOwnedClaimBeforeGateway() = runTest {
        val compatible = compatibleConfig("http://127.0.0.1:18080/v1")
        val cases = listOf(
            PostClaimRaceCase("disabled") { it.settings = it.settings.copy(enabled = false) },
            PostClaimRaceCase("model") {
                it.settings = it.settings.copy(
                    config = ClinicalAiProviderConfig(ClinicalAiProviderId.OPENAI, "gpt-race")
                )
            },
            PostClaimRaceCase("endpoint", initialConfig = compatible) {
                it.settings = it.settings.copy(
                    config = compatibleConfig("http://127.0.0.1:18081/v1")
                )
            },
            PostClaimRaceCase("credential-deleted") { it.credential = null },
            PostClaimRaceCase("credential-replaced") { it.credential = "replacement-secret" },
            PostClaimRaceCase("network-lost") { it.network = null },
            PostClaimRaceCase("network-switched") {
                it.network = alertAiTestNetworkLease(202L)
            },
            PostClaimRaceCase("settings-source-unavailable") { it.settingsFailure = true },
            PostClaimRaceCase("credential-source-unavailable") { it.credentialFailure = true },
            PostClaimRaceCase("network-source-unavailable") { it.networkFailure = true }
        )

        cases.forEach { case ->
            val state = PostClaimRaceState(
                settings = AlertAiAnalysisSettings(true, case.initialConfig)
            )
            val dao = FakeDao(afterInsert = { case.mutate(state) })
            val gatewayCalls = AtomicInteger()
            val contextBuilds = AtomicInteger()
            val coordinator = AlertAiAnalysisCoordinator(
                settingsSource = AlertAiAnalysisSettingsSource {
                    if (state.settingsFailure) error("settings unavailable")
                    state.settings
                },
                credentialSource = AlertAiCredentialSource {
                    if (state.credentialFailure) error("credential unavailable")
                    state.credential
                },
                networkGate = AlertAiNetworkGate {
                    if (state.networkFailure) error("network unavailable")
                    state.network
                },
                contextSource = AlertAiAnalysisContextSource {
                    contextBuilds.incrementAndGet()
                    context()
                },
                dao = dao,
                gatewayFactory = gatewayFactory(case.initialConfig, gatewayCalls),
                analysisIdFactory = { "analysis-$it" },
                clock = { 99_000L }
            )

            val outcome = coordinator.run(trigger("post-insert-${case.name}"))

            assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
            assertThat(contextBuilds.get()).isEqualTo(1)
            assertThat(gatewayCalls.get()).isEqualTo(0)
            val stored = dao.rows.single()
            assertThat(stored.status).isEqualTo(AlertAiAnalysisStatus.FAILED.name)
            assertThat(stored.requestHash).isEqualTo(context().sha256)
            assertThat(stored.sanitizedError).isEqualTo("EXECUTION_IDENTITY_CHANGED")
            assertThat(stored.toString()).doesNotContain("replacement-secret")
        }
    }

    @Test
    fun contextFailureAndCancellationAfterClaimNeverLeaveRunningOrphan() = runTest {
        val ordinaryDao = FakeDao()
        val ordinary = coordinatorWithContext(
            dao = ordinaryDao,
            contextSource = AlertAiAnalysisContextSource { error("private context failure") }
        )

        assertThat(ordinary.run(trigger("context-failed")))
            .isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
        assertThat(ordinaryDao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.FAILED.name)
        assertThat(ordinaryDao.rows.single().sanitizedError).isEqualTo("CONTEXT_UNAVAILABLE")
        assertThat(ordinaryDao.rows.single().toString()).doesNotContain("private context failure")

        val cancellation = CancellationException("private context cancellation")
        val cancelledDao = FakeDao()
        val cancelled = coordinatorWithContext(
            dao = cancelledDao,
            contextSource = AlertAiAnalysisContextSource { throw cancellation }
        )

        val thrown = runCatching { cancelled.run(trigger("context-cancelled")) }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancelledDao.rows.single().status)
            .isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
        assertThat(cancelledDao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CANCELLED.name)
        assertThat(cancelledDao.rows.single().toString())
            .doesNotContain("private context cancellation")
    }

    @Test
    fun cancellationReturnedWithValidContextPropagatesAndTerminalizesOwnedClaim() = runTest {
        val cancellation = CancellationException("cancelled while returning valid context")
        val dao = FakeDao()
        val gatewayCalls = AtomicInteger()
        val coordinator = coordinatorWithContext(
            dao = dao,
            contextSource = AlertAiAnalysisContextSource {
                currentCoroutineContext().cancel(cancellation)
                context()
            },
            gatewayCalls = gatewayCalls
        )

        val thrown = captureChildFailure {
            coordinator.run(trigger("context-returned-cancelled"))
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(gatewayCalls.get()).isEqualTo(0)
        assertThat(dao.rows).hasSize(1)
        assertThat(dao.rows.single().episodeId).isEqualTo("context-returned-cancelled")
        assertThat(dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
        assertThat(dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CANCELLED.name)
    }

    @Test
    fun cancellationAtFinalPreGatewayCheckpointPropagatesWithoutGatewayCall() = runTest {
        val cancellation = CancellationException("cancelled after final network revalidation")
        var networkReads = 0
        val fixture = fixture(
            networkReader = {
                networkReads += 1
                if (networkReads == 2) currentCoroutineContext().cancel(cancellation)
                networkSnapshot(true)
            }
        )

        val thrown = captureChildFailure {
            fixture.coordinator.run(trigger("pre-gateway-cancelled"))
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(networkReads).isEqualTo(2)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        assertThat(fixture.dao.rows).hasSize(1)
        assertThat(fixture.dao.rows.single().episodeId).isEqualTo("pre-gateway-cancelled")
        assertThat(fixture.dao.rows.single().requestHash).isEqualTo(context().sha256)
        assertThat(fixture.dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
        assertThat(fixture.dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CANCELLED.name)
    }

    @Test
    fun postClaimFailureCannotOverwriteRowThatIsNoLongerOwnedRunningClaim() = runTest {
        var settings = AlertAiAnalysisSettings(
            enabled = true,
            config = ClinicalAiProviderConfig.defaultOpenAi()
        )
        var replaced = false
        val dao = FakeDao(
            afterUpdate = { updated, rows ->
                if (!replaced && updated.status == AlertAiAnalysisStatus.RUNNING.name &&
                    updated.requestHash == context().sha256
                ) {
                    replaced = true
                    val index = rows.indexOfFirst { it.analysisId == updated.analysisId }
                    rows[index] = updated.copy(
                        status = AlertAiAnalysisStatus.COMPLETED.name,
                        resultJson = VALID_RESULT,
                        completedAt = 98_000L
                    )
                    settings = settings.copy(enabled = false)
                }
            }
        )
        val calls = AtomicInteger()
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource { settings },
            credentialSource = AlertAiCredentialSource { "synthetic-secret" },
            networkGate = AlertAiNetworkGate { networkSnapshot(true) },
            contextSource = AlertAiAnalysisContextSource { context() },
            dao = dao,
            gatewayFactory = gatewayFactory(settings.config, calls),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )

        val outcome = coordinator.run(trigger("ownership-lost"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(calls.get()).isEqualTo(0)
        assertThat(dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.COMPLETED.name)
        assertThat(dao.rows.single().resultJson).isEqualTo(VALID_RESULT)
        assertThat(dao.rows.single().sanitizedError).isNull()
    }

    @Test
    fun builderIssuedContextMustNotExposeDataClassCopy() {
        assertThat(
            AlertAiCanonicalContext::class.java.declaredMethods.none { it.name.startsWith("copy") }
        ).isTrue()
    }

    @Test
    fun validRehashedContextBoundToDifferentTriggerFieldsFailsBeforeClaimOrRequest() = runTest {
        val mismatched = listOf(
            alertContextFixture(now = ALERT_AI_CONTEXT_FIXTURE_NOW + 1L),
            alertContextFixture(stage = "HIGH_NOW"),
            alertContextFixture(direction = AlertCauseDirection.HIGH),
            alertContextFixture(
                localCauseSnapshot = AlertCauseSnapshotCodec.repairSnapshot(
                    AlertCauseCode.SENSOR_QUALITY
                )
            )
        )

        mismatched.forEachIndexed { index, context ->
            val fixture = fixture(contextValue = context)

            val outcome = fixture.coordinator.run(trigger("trigger-mismatch-$index"))

            assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
            assertThat(fixture.dao.rows.single().status)
                .isEqualTo(AlertAiAnalysisStatus.FAILED.name)
            assertThat(fixture.dao.rows.single().sanitizedError).isEqualTo("CONTEXT_UNAVAILABLE")
            assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        }
    }

    @Test
    fun sameValidatedNetworkIdentityBeforeAndAfterBuildAllowsOneRequest() = runTest {
        val fixture = fixture(
            networkSnapshots = ArrayDeque(
                listOf(
                    alertAiTestNetworkLease(101L),
                    alertAiTestNetworkLease(101L)
                )
            )
        )

        val outcome = fixture.coordinator.run(trigger("same-network"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.COMPLETED)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(1)
        assertThat(fixture.dao.rows).hasSize(1)
    }

    @Test
    fun validatedNetworkSwitchBeforeClaimFailsClosed() = runTest {
        val fixture = fixture(
            networkSnapshots = ArrayDeque(
                listOf(
                    alertAiTestNetworkLease(101L),
                    alertAiTestNetworkLease(202L)
                )
            )
        )

        val outcome = fixture.coordinator.run(trigger("network-switch"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        assertThat(fixture.dao.rows.single().sanitizedError)
            .isEqualTo("EXECUTION_IDENTITY_CHANGED")
    }

    @Test
    fun validatedNetworkBecomingUnavailableBeforeClaimFailsClosed() = runTest {
        val fixture = fixture(
            networkSnapshots = ArrayDeque(
                listOf(alertAiTestNetworkLease(101L), null)
            )
        )

        val outcome = fixture.coordinator.run(trigger("network-lost"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        assertThat(fixture.dao.rows.single().sanitizedError)
            .isEqualTo("EXECUTION_IDENTITY_CHANGED")
    }

    @Test
    fun networkSnapshotExceptionFailsClosedButCancellationPropagates() = runTest {
        val ordinary = fixture(networkFailure = IllegalStateException("network callback failed"))

        assertThat(ordinary.coordinator.run(trigger("network-exception")))
            .isEqualTo(AlertAiAnalysisOutcome.NETWORK_UNAVAILABLE)
        assertThat(ordinary.contextBuilds.get()).isEqualTo(0)
        assertThat(ordinary.dao.rows).isEmpty()

        val cancellation = CancellationException("network scope cancelled")
        val cancelled = fixture(networkFailure = cancellation)
        val thrown = runCatching {
            cancelled.coordinator.run(trigger("network-cancelled"))
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancelled.contextBuilds.get()).isEqualTo(0)
        assertThat(cancelled.dao.rows).isEmpty()
    }

    @Test
    fun postBuildNetworkExceptionFailsClosedButCancellationPropagates() = runTest {
        val ordinaryReads = AtomicInteger()
        val ordinary = fixture(
            networkReader = {
                if (ordinaryReads.getAndIncrement() == 0) {
                    alertAiTestNetworkLease(101L)
                } else {
                    throw IllegalStateException("post-build network callback failed")
                }
            }
        )

        assertThat(ordinary.coordinator.run(trigger("post-build-network-exception")))
            .isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(ordinary.contextBuilds.get()).isEqualTo(1)
        assertThat(ordinary.dao.rows.single().sanitizedError)
            .isEqualTo("EXECUTION_IDENTITY_CHANGED")

        val cancellation = CancellationException("post-build network scope cancelled")
        val cancellationReads = AtomicInteger()
        val cancelled = fixture(
            networkReader = {
                if (cancellationReads.getAndIncrement() == 0) {
                    alertAiTestNetworkLease(101L)
                } else {
                    throw cancellation
                }
            }
        )
        val thrown = runCatching {
            cancelled.coordinator.run(trigger("post-build-network-cancelled"))
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancelled.contextBuilds.get()).isEqualTo(1)
        assertThat(cancelled.dao.rows.single().status)
            .isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
    }

    @Test
    fun disabledInvalidCredentialAndNetworkGatesRunBeforeContextBuild() = runTest {
        val cases = listOf(
            Case(enabled = false, credential = "key", network = true, AlertAiAnalysisOutcome.DISABLED),
            Case(enabled = true, credential = null, network = true, AlertAiAnalysisOutcome.CREDENTIAL_UNAVAILABLE),
            Case(enabled = true, credential = "key", network = false, AlertAiAnalysisOutcome.NETWORK_UNAVAILABLE)
        )
        cases.forEachIndexed { index, case ->
            val fixture = fixture(
                enabled = case.enabled,
                credential = case.credential,
                network = case.network
            )

            val result = fixture.coordinator.run(trigger("gate-$index"))

            assertThat(result).isEqualTo(case.outcome)
            assertThat(fixture.contextBuilds.get()).isEqualTo(0)
            assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
            assertThat(fixture.dao.rows).isEmpty()
        }
    }

    @Test
    fun invalidEffectiveConfigFailsBeforeCredentialNetworkAndContextBuild() = runTest {
        val credentialCalls = AtomicInteger()
        val networkCalls = AtomicInteger()
        val contextBuilds = AtomicInteger()
        val dao = FakeDao()
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(
                    enabled = true,
                    config = ClinicalAiProviderConfig.defaultOpenAi()
                )
            },
            credentialSource = AlertAiCredentialSource {
                credentialCalls.incrementAndGet()
                "synthetic-secret"
            },
            networkGate = AlertAiNetworkGate {
                networkCalls.incrementAndGet()
                networkSnapshot(true)
            },
            contextSource = AlertAiAnalysisContextSource {
                contextBuilds.incrementAndGet()
                context()
            },
            dao = dao,
            gatewayFactory = ClinicalAiGatewayFactory(emptyMap()),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )

        val outcome = coordinator.run(trigger("invalid-config"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONFIG_UNAVAILABLE)
        assertThat(credentialCalls.get()).isEqualTo(0)
        assertThat(networkCalls.get()).isEqualTo(0)
        assertThat(contextBuilds.get()).isEqualTo(0)
        assertThat(dao.rows).isEmpty()
    }

    @Test
    fun concurrentDuplicateClaimMakesExactlyOneRequestAndStoresNoPayloadOrCredential() = runTest {
        val fixture = fixture()

        val outcomes = listOf(
            async { fixture.coordinator.run(trigger("same-episode")) },
            async { fixture.coordinator.run(trigger("same-episode")) }
        ).awaitAll()

        assertThat(outcomes).containsExactly(
            AlertAiAnalysisOutcome.COMPLETED,
            AlertAiAnalysisOutcome.ALREADY_CLAIMED
        )
        assertThat(fixture.gatewayCalls.get()).isEqualTo(1)
        assertThat(fixture.dao.rows).hasSize(1)
        val stored = fixture.dao.rows.single()
        assertThat(stored.status).isEqualTo(AlertAiAnalysisStatus.COMPLETED.name)
        assertThat(stored.resultJson).isEqualTo(VALID_RESULT)
        assertThat(stored.requestHash).isEqualTo(context().sha256)
        assertThat(stored.toString()).doesNotContain(context().canonicalJson)
        assertThat(stored.toString()).doesNotContain("synthetic-secret")
        assertThat(stored.sanitizedError).isNull()
    }

    @Test
    fun requestFailureAndCancellationBecomeTerminalWithoutRetry() = runTest {
        val failed = fixture(gatewayFailure = IllegalStateException("private payload"))
        val failedOutcome = failed.coordinator.run(trigger("failed"))

        assertThat(failedOutcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(failed.gatewayCalls.get()).isEqualTo(1)
        assertThat(failed.dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.FAILED.name)
        assertThat(failed.dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.REQUEST_FAILED.name)
        assertThat(failed.dao.rows.single().toString()).doesNotContain("private payload")

        val cancelled = fixture(gatewayFailure = CancellationException("private cancellation"))
        val cancellation = runCatching {
            cancelled.coordinator.run(trigger("cancelled"))
        }.exceptionOrNull()

        assertThat(cancellation).isInstanceOf(CancellationException::class.java)
        assertThat(cancelled.gatewayCalls.get()).isEqualTo(1)
        assertThat(cancelled.dao.rows.single().status)
            .isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
        assertThat(cancelled.dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CANCELLED.name)
        assertThat(cancelled.dao.rows.single().toString()).doesNotContain("private cancellation")
    }

    @Test
    fun cancellationAfterDurableInsertTerminalizesOwnClaimAndNeverRetries() = runTest {
        val original = CancellationException("original cancellation")
        val dao = FakeDao(cancelAfterInsert = original)
        val first = fixture(dao = dao)

        val failure = runCatching {
            first.coordinator.run(trigger("insert-cancelled"))
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        assertThat(first.gatewayCalls.get()).isEqualTo(0)
        assertThat(dao.rows).hasSize(1)
        assertThat(dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
        assertThat(dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CANCELLED.name)

        val restarted = fixture(dao = dao)
        val repeated = restarted.coordinator.run(trigger("insert-cancelled"))

        assertThat(repeated).isEqualTo(AlertAiAnalysisOutcome.ALREADY_CLAIMED)
        assertThat(restarted.gatewayCalls.get()).isEqualTo(0)
    }

    @Test
    fun cancellationObservedAfterOwnedInsertReturnTerminalizesOnlyOwnClaim() = runTest {
        val dao = FakeDao(
            cancelContextAfterInsert = CancellationException("post-insert cancellation")
        )
        val fixture = fixture(dao = dao)

        val failure = supervisorScope {
            val attempt = async { fixture.coordinator.run(trigger("post-insert-cancelled")) }
            runCatching { attempt.await() }.exceptionOrNull()
        }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        assertThat(dao.rows).hasSize(1)
        assertThat(dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.CANCELLED.name)
        assertThat(dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CANCELLED.name)
    }

    @Test
    fun hangingTerminalUpdateIsBoundedDoesNotMaskCancellationAndStillPreventsRetry() = runTest {
        val original = CancellationException("original request cancellation")
        val dao = FakeDao(hangOnUpdate = true)
        val first = fixture(dao = dao, gatewayFailure = original)

        val failure = runCatching {
            first.coordinator.run(trigger("terminal-timeout"))
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        assertThat(first.gatewayCalls.get()).isEqualTo(1)
        assertThat(dao.rows.single().status).isEqualTo(AlertAiAnalysisStatus.RUNNING.name)

        val restarted = fixture(dao = dao)
        assertThat(restarted.coordinator.run(trigger("terminal-timeout")))
            .isEqualTo(AlertAiAnalysisOutcome.ALREADY_CLAIMED)
        assertThat(restarted.gatewayCalls.get()).isEqualTo(0)
    }

    @Test
    fun processRestartNeverResumesExistingRunningFailedOrCompletedRows() = runTest {
        AlertAiAnalysisStatus.entries.forEach { status ->
            val dao = FakeDao().apply { rows += row("existing-${status.name}", status) }
            val fixture = fixture(dao = dao)

            val outcome = fixture.coordinator.run(trigger("existing-${status.name}"))

            assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.ALREADY_CLAIMED)
            assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        }
    }

    @Test
    fun selectedProviderAndModelAreExactAndFactoryNeverFallsBack() = runTest {
        val selected = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )
        val built = mutableListOf<ClinicalAiProviderConfig>()
        val fixture = fixture(
            config = selected,
            builders = mapOf(
                ClinicalAiProviderId.GEMINI to ClinicalAiGatewayBuilder { config ->
                    built += config
                    FakeGateway(config.providerId, config.modelId, AtomicInteger())
                },
                ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder {
                    throw AssertionError("fallback must not be built")
                }
            )
        )

        val outcome = fixture.coordinator.run(trigger("gemini"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.COMPLETED)
        assertThat(built).containsExactly(selected)
        assertThat(fixture.dao.rows.single().provider).isEqualTo("GEMINI")
        assertThat(fixture.dao.rows.single().model).isEqualTo("gemini-3.6-flash")
    }

    @Test
    fun responseIdentityMismatchIsTerminalAndDoesNotRetry() = runTest {
        val fixture = fixture(responseProviderId = ClinicalAiProviderId.GEMINI)

        val outcome = fixture.coordinator.run(trigger("identity-mismatch"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(1)
        assertThat(fixture.dao.rows.single().status)
            .isEqualTo(AlertAiAnalysisStatus.FAILED.name)
        assertThat(fixture.dao.rows.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.IDENTITY_MISMATCH.name)

        val repeated = fixture.coordinator.run(trigger("identity-mismatch"))
        assertThat(repeated).isEqualTo(AlertAiAnalysisOutcome.ALREADY_CLAIMED)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(1)
    }

    @Test
    fun malformedContextHashFailsBeforeClaimOrRequest() = runTest {
        val valid = context()
        val fixture = fixture(
            contextValue = AlertAiCanonicalContext(
                canonicalJson = valid.canonicalJson,
                canonicalBytes = valid.canonicalBytes,
                sha256 = "b".repeat(64),
                rowCount = valid.rowCount
            )
        )

        val outcome = fixture.coordinator.run(trigger("bad-context"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
        assertThat(fixture.contextBuilds.get()).isEqualTo(1)
        assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        assertThat(fixture.dao.rows.single().sanitizedError).isEqualTo("CONTEXT_UNAVAILABLE")
    }

    @Test
    fun settingsCredentialAndNetworkAreRevalidatedAfterContextBuild() = runTest {
        val changedConfig = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )
        val cases = listOf(
            RaceCase("disabled", AlertAiAnalysisOutcome.FAILED) { state ->
                state.settings = state.settings.copy(enabled = false)
            },
            RaceCase("config", AlertAiAnalysisOutcome.FAILED) { state ->
                state.settings = AlertAiAnalysisSettings(true, changedConfig)
            },
            RaceCase("credential-deleted", AlertAiAnalysisOutcome.FAILED) { state ->
                state.credential = null
            },
            RaceCase("credential-replaced", AlertAiAnalysisOutcome.FAILED) { state ->
                state.credential = "replacement-secret"
            },
            RaceCase("network", AlertAiAnalysisOutcome.FAILED) { state ->
                state.network = null
            }
        )

        cases.forEach { case ->
            val state = RaceState()
            val dao = FakeDao()
            val gatewayCalls = AtomicInteger()
            val coordinator = AlertAiAnalysisCoordinator(
                settingsSource = AlertAiAnalysisSettingsSource { state.settings },
                credentialSource = AlertAiCredentialSource { state.credential },
                networkGate = AlertAiNetworkGate { state.network },
                contextSource = AlertAiAnalysisContextSource {
                    case.mutate(state)
                    context()
                },
                dao = dao,
                gatewayFactory = ClinicalAiGatewayFactory(
                    mapOf(
                        ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { config ->
                            FakeGateway(config.providerId, config.modelId, gatewayCalls)
                        }
                    )
                ),
                analysisIdFactory = { "analysis-$it" },
                clock = { 99_000L }
            )

            val outcome = coordinator.run(trigger("race-${case.name}"))

            assertThat(outcome).isEqualTo(case.outcome)
            assertThat(dao.rows.single().sanitizedError)
                .isEqualTo("EXECUTION_IDENTITY_CHANGED")
            assertThat(gatewayCalls.get()).isEqualTo(0)
        }
    }

    @Test
    fun sameProviderAndModelWithChangedEndpointFailsExecutionIdentityRevalidation() = runTest {
        val initial = compatibleConfig("http://127.0.0.1:18080/v1")
        val changed = compatibleConfig("http://127.0.0.1:18081/v1")
        var settings = AlertAiAnalysisSettings(enabled = true, config = initial)
        val dao = FakeDao()
        val calls = AtomicInteger()
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource { settings },
            credentialSource = AlertAiCredentialSource { "synthetic-secret" },
            networkGate = AlertAiNetworkGate { networkSnapshot(true) },
            contextSource = AlertAiAnalysisContextSource {
                settings = AlertAiAnalysisSettings(enabled = true, config = changed)
                context()
            },
            dao = dao,
            gatewayFactory = ClinicalAiGatewayFactory(
                mapOf(
                    ClinicalAiProviderId.OPENAI_COMPATIBLE to ClinicalAiGatewayBuilder { selected ->
                        FakeGateway(selected.providerId, selected.modelId, calls)
                    }
                )
            ),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )

        val outcome = coordinator.run(trigger("endpoint-race"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.FAILED)
        assertThat(dao.rows.single().sanitizedError)
            .isEqualTo("EXECUTION_IDENTITY_CHANGED")
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun requestUsesFreshPostBuildCredentialInstance() = runTest {
        val firstCredential = String("synthetic-secret".toCharArray())
        val freshCredential = String("synthetic-secret".toCharArray())
        val credentialReads = AtomicInteger()
        var deliveredCredential: String? = null
        val calls = AtomicInteger()
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource {
                if (credentialReads.getAndIncrement() == 0) firstCredential else freshCredential
            },
            networkGate = AlertAiNetworkGate { networkSnapshot(true) },
            contextSource = AlertAiAnalysisContextSource { context() },
            dao = FakeDao(),
            gatewayFactory = ClinicalAiGatewayFactory(
                mapOf(
                    ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { selected ->
                        FakeGateway(
                            selected.providerId,
                            selected.modelId,
                            calls,
                            credentialObserver = { deliveredCredential = it }
                        )
                    }
                )
            ),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )

        val outcome = coordinator.run(trigger("fresh-credential"))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.COMPLETED)
        assertThat(credentialReads.get()).isEqualTo(2)
        assertThat(firstCredential).isNotSameInstanceAs(freshCredential)
        assertThat(deliveredCredential).isSameInstanceAs(freshCredential)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun rehashedUnissuedOrUnknownContextsFailBeforeClaimOrRequest() = runTest {
        val forgedContexts = listOf(
            forgedContext {
                getAsJsonArray("daily14d")[13].asJsonObject
                    .addProperty("insulinUnits", 2.0)
            },
            forgedContext { addProperty("episodeId", "private-episode") },
            forgedContext {
                getAsJsonObject("localCause").addProperty("localId", "private-local")
            },
            forgedContext {
                getAsJsonArray("events14d").first().asJsonObject
                    .addProperty("provenance", "private-provenance")
            },
            forgedContext { addProperty("unexpected", true) }
        )

        forgedContexts.forEachIndexed { index, forged ->
            val fixture = fixture(contextValue = forged)

            val outcome = fixture.coordinator.run(trigger("forged-$index"))

            assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
            assertThat(fixture.dao.rows.single().sanitizedError).isEqualTo("CONTEXT_UNAVAILABLE")
            assertThat(fixture.gatewayCalls.get()).isEqualTo(0)
        }
    }

    private fun fixture(
        enabled: Boolean = true,
        credential: String? = "synthetic-secret",
        network: Boolean = true,
        networkSnapshots: ArrayDeque<AlertAiValidatedNetworkLease?>? = null,
        networkFailure: Throwable? = null,
        networkReader: (suspend () -> AlertAiValidatedNetworkLease?)? = null,
        gatewayFailure: Throwable? = null,
        config: ClinicalAiProviderConfig = ClinicalAiProviderConfig.defaultOpenAi(),
        dao: FakeDao = FakeDao(),
        builders: Map<ClinicalAiProviderId, ClinicalAiGatewayBuilder>? = null,
        contextValue: AlertAiCanonicalContext = context(),
        responseProviderId: ClinicalAiProviderId = config.providerId,
        responseModelId: String = config.modelId
    ): Fixture {
        val contextBuilds = AtomicInteger()
        val gatewayCalls = AtomicInteger()
        val effectiveBuilders = builders ?: mapOf(
            config.providerId to ClinicalAiGatewayBuilder {
                FakeGateway(
                    providerId = it.providerId,
                    modelId = it.modelId,
                    calls = gatewayCalls,
                    failure = gatewayFailure,
                    responseProviderId = responseProviderId,
                    responseModelId = responseModelId
                )
            }
        )
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = enabled, config = config)
            },
            credentialSource = AlertAiCredentialSource {
                credential
            },
            networkGate = AlertAiNetworkGate {
                networkFailure?.let { throw it }
                when {
                    networkReader != null -> networkReader()
                    networkSnapshots != null -> networkSnapshots.removeFirst()
                    else -> networkSnapshot(network)
                }
            },
            contextSource = AlertAiAnalysisContextSource {
                contextBuilds.incrementAndGet()
                contextValue
            },
            dao = dao,
            gatewayFactory = ClinicalAiGatewayFactory(effectiveBuilders),
            analysisIdFactory = { episodeId -> "analysis-$episodeId" },
            clock = { 99_000L }
        )
        return Fixture(coordinator, dao, contextBuilds, gatewayCalls)
    }

    private fun coordinatorWithContext(
        dao: FakeDao,
        contextSource: AlertAiAnalysisContextSource,
        gatewayCalls: AtomicInteger = AtomicInteger()
    ): AlertAiAnalysisCoordinator {
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        return AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource { "synthetic-secret" },
            networkGate = AlertAiNetworkGate { networkSnapshot(true) },
            contextSource = contextSource,
            dao = dao,
            gatewayFactory = gatewayFactory(config, gatewayCalls),
            analysisIdFactory = { "analysis-$it" },
            clock = { 99_000L }
        )
    }

    private suspend fun captureChildFailure(block: suspend () -> Unit): Throwable? =
        supervisorScope {
            val observed = CompletableDeferred<Throwable?>()
            launch {
                try {
                    block()
                    observed.complete(null)
                } catch (failure: Throwable) {
                    observed.complete(failure)
                }
            }
            observed.await()
        }

    private fun gatewayFactory(
        config: ClinicalAiProviderConfig,
        calls: AtomicInteger
    ) = ClinicalAiGatewayFactory(
        mapOf(
            config.providerId to ClinicalAiGatewayBuilder { selected ->
                FakeGateway(selected.providerId, selected.modelId, calls)
            }
        )
    )

    private class FakeGateway(
        override val providerId: ClinicalAiProviderId,
        override val modelId: String,
        private val calls: AtomicInteger,
        private val failure: Throwable? = null,
        private val responseProviderId: ClinicalAiProviderId = providerId,
        private val responseModelId: String = modelId,
        private val credentialObserver: (String) -> Unit = {}
    ) : ClinicalAiGateway {
        override fun capabilities() = ClinicalAiCapabilities(262_144, 65_536, 1_024, true)
        override suspend fun testConnection(credential: String) = ClinicalAiConnectionTest(
            providerId, modelId, ClinicalAiConnectionStatus.SUCCESS
        )
        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credential: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult = throw UnsupportedOperationException()

        override suspend fun analyzeAlert(
            context: AlertAiCanonicalContext,
            credential: suspend () -> String,
            networkLease: AlertAiValidatedNetworkLease
        ): AlertAiGatewayResult {
            calls.incrementAndGet()
            credentialObserver(credential())
            failure?.let { throw it }
            return AlertAiGatewayResult(responseProviderId, responseModelId, VALID_RESULT)
        }
    }

    private class FakeDao(
        private val cancelAfterInsert: CancellationException? = null,
        private val cancelContextAfterInsert: CancellationException? = null,
        private val hangOnUpdate: Boolean = false,
        private val afterInsert: (AlertAiAnalysisEntity) -> Unit = {},
        private val afterUpdate:
            (AlertAiAnalysisEntity, MutableList<AlertAiAnalysisEntity>) -> Unit = { _, _ -> }
    ) : AlertAiAnalysisDao {
        val rows = mutableListOf<AlertAiAnalysisEntity>()
        private val flow = MutableStateFlow<AlertAiAnalysisEntity?>(null)

        override suspend fun insert(entity: AlertAiAnalysisEntity): Long {
            val result = synchronized(rows) {
                if (rows.any { it.episodeId == entity.episodeId }) return@synchronized -1L
                rows += entity
                flow.value = entity
                afterInsert(entity)
                cancelAfterInsert?.let { throw it }
                1L
            }
            if (result != -1L) {
                cancelContextAfterInsert?.let { cancellation ->
                    currentCoroutineContext().cancel(cancellation)
                }
            }
            return result
        }

        override suspend fun update(entity: AlertAiAnalysisEntity): Int {
            if (hangOnUpdate && entity.status != AlertAiAnalysisStatus.RUNNING.name) {
                awaitCancellation()
            }
            return synchronized(rows) {
                val index = rows.indexOfFirst { it.analysisId == entity.analysisId }
                if (index < 0) return@synchronized 0
                rows[index] = entity
                flow.value = entity
                afterUpdate(entity, rows)
                1
            }
        }

        override suspend fun transitionOwnedRunning(
            analysisId: String,
            episodeId: String,
            provider: String,
            model: String,
            expectedRequestHash: String,
            newRequestHash: String,
            newStatus: String,
            newResultJson: String?,
            newCompletedAt: Long?,
            newSanitizedError: String?
        ): Int {
            val current = synchronized(rows) {
                rows.firstOrNull {
                    it.analysisId == analysisId &&
                        it.episodeId == episodeId &&
                        it.provider == provider &&
                        it.model == model &&
                        it.requestHash == expectedRequestHash &&
                        it.status == AlertAiAnalysisStatus.RUNNING.name &&
                        it.resultJson == null &&
                        it.completedAt == null &&
                        it.sanitizedError == null
                }
            } ?: return 0
            return update(
                current.copy(
                    requestHash = newRequestHash,
                    status = newStatus,
                    resultJson = newResultJson,
                    completedAt = newCompletedAt,
                    sanitizedError = newSanitizedError
                )
            )
        }

        override suspend fun byEpisodeId(episodeId: String) =
            synchronized(rows) { rows.firstOrNull { it.episodeId == episodeId } }

        override fun observeByEpisodeId(episodeId: String): Flow<AlertAiAnalysisEntity?> = flow

        override suspend fun byStatus(status: String) =
            synchronized(rows) { rows.filter { it.status == status } }
    }

    private fun trigger(episodeId: String) = AlertAiAnalysisTrigger(
        episodeId = episodeId,
        requestedAt = ALERT_AI_CONTEXT_FIXTURE_NOW,
        stage = "LOW_PREDICTED_30",
        direction = AlertCauseDirection.LOW,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
    )

    private fun context(): AlertAiCanonicalContext = alertContextFixture()

    private fun networkSnapshot(available: Boolean): AlertAiValidatedNetworkLease? =
        alertAiTestNetworkLease(101L).takeIf { available }

    private fun compatibleConfig(endpoint: String) = ClinicalAiProviderConfig.normalized(
        providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
        modelId = "compatible-model",
        endpoint = endpoint,
        compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
    )

    private fun forgedContext(mutate: JsonObject.() -> Unit): AlertAiCanonicalContext {
        val root = JsonParser.parseString(context().canonicalJson).asJsonObject
        root.mutate()
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        return AlertAiCanonicalContext(
            canonicalJson = bytes.toString(Charsets.UTF_8),
            canonicalBytes = bytes.toByteString(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) },
            rowCount = context().rowCount
        )
    }

    private fun row(episodeId: String, status: AlertAiAnalysisStatus) = AlertAiAnalysisEntity(
        analysisId = "analysis-$episodeId",
        episodeId = episodeId,
        provider = "OPENAI",
        model = "gpt-5.6-terra",
        requestHash = context().sha256,
        status = status.name,
        resultJson = if (status == AlertAiAnalysisStatus.COMPLETED) VALID_RESULT else null,
        requestedAt = ALERT_AI_CONTEXT_FIXTURE_NOW,
        completedAt = if (status == AlertAiAnalysisStatus.RUNNING) null else 99_000L,
        sanitizedError = null
    )

    private data class Fixture(
        val coordinator: AlertAiAnalysisCoordinator,
        val dao: FakeDao,
        val contextBuilds: AtomicInteger,
        val gatewayCalls: AtomicInteger
    )

    private data class Case(
        val enabled: Boolean,
        val credential: String?,
        val network: Boolean,
        val outcome: AlertAiAnalysisOutcome
    )

    private data class RaceCase(
        val name: String,
        val outcome: AlertAiAnalysisOutcome,
        val mutate: (RaceState) -> Unit
    )

    private data class RaceState(
        var settings: AlertAiAnalysisSettings = AlertAiAnalysisSettings(
            enabled = true,
            config = ClinicalAiProviderConfig.defaultOpenAi()
        ),
        var credential: String? = "synthetic-secret",
        var network: AlertAiValidatedNetworkLease? = alertAiTestNetworkLease(101L)
    )

    private data class PostClaimRaceCase(
        val name: String,
        val initialConfig: ClinicalAiProviderConfig = ClinicalAiProviderConfig.defaultOpenAi(),
        val mutate: (PostClaimRaceState) -> Unit
    )

    private data class PostClaimRaceState(
        var settings: AlertAiAnalysisSettings,
        var credential: String? = "synthetic-secret",
        var network: AlertAiValidatedNetworkLease? = alertAiTestNetworkLease(101L),
        var settingsFailure: Boolean = false,
        var credentialFailure: Boolean = false,
        var networkFailure: Boolean = false
    )

    private companion object {
        const val VALID_RESULT =
            "{\"schemaVersion\":1,\"primaryCauseCode\":\"SENSOR_QUALITY\",\"confidence\":\"HIGH\",\"evidenceCodes\":[\"SENSOR_BLOCKED\"],\"adviceCode\":\"DATA_INCOMPLETE\"}"
    }
}
