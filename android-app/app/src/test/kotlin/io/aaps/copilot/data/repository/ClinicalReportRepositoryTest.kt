package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiConfigInvalidReason
import io.aaps.copilot.config.ClinicalAiConfigState
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.config.executionIdentity
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.dao.ClinicalReportDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import io.aaps.copilot.security.ClinicalAiCredentialProvider
import io.aaps.copilot.security.ClinicalAiCredentialStore
import io.aaps.copilot.security.EmptyLegacyCredentialSource
import io.aaps.copilot.security.LegacyOpenAiKeySource
import io.aaps.copilot.security.OpenAiCredentialProvider
import io.aaps.copilot.security.OpenAiCredentialStore
import io.aaps.copilot.security.SecretStorage
import io.aaps.copilot.service.ClinicalAiProviderManager
import io.aaps.copilot.storage.ClinicalPdfCopyResult
import io.aaps.copilot.storage.ClinicalPdfReadyArtifact
import io.aaps.copilot.storage.ClinicalPdfShareResult
import io.aaps.copilot.storage.ClinicalPdfStageResult
import io.aaps.copilot.ui.ClinicalReportPdfExportCoordinator
import io.aaps.copilot.ui.foundation.screens.ClinicalPdfExportUiState
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalReportRepositoryTest {

    @Test
    fun nextRemoteRunUsesCurrentProviderBuilderModelAndOnlyMatchingCredential() = runBlocking {
        val openAi = ClinicalAiProviderConfig(
            ClinicalAiProviderId.OPENAI,
            "gpt-provider-test"
        )
        val anthropic = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-provider-test"
        )
        val gemini = ClinicalAiProviderConfig(
            ClinicalAiProviderId.GEMINI,
            "gemini-provider-test"
        )
        val compatible = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "compatible-provider-test",
            endpoint = "https://example.test/v1",
            compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
        )
        val configSource = MutableClinicalAiConfigSource(
            ClinicalAiConfigState.Valid(openAi)
        )
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource()
        val dao = FakeClinicalReportDao()
        val monotonicClock = MutableClock(NOW)
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials,
            monotonicClock = monotonicClock
        )

        listOf(openAi, anthropic, gemini, compatible).forEachIndexed { index, config ->
            configSource.state = if (index == 0) {
                ClinicalAiConfigState.UnconfiguredDefault(config)
            } else {
                ClinicalAiConfigState.Valid(config)
            }
            monotonicClock.set(
                NOW + index * (ClinicalReportRepository.REMOTE_COOLDOWN_MS + 1)
            )
            val run = repository.start(
                nowTs = NOW + index,
                zoneId = UTC,
                force = index > 0
            )
            withTimeout(5_000) { run.job.join() }

            val complete = repository.state.value as ClinicalReportState.Complete
            assertThat(complete.metadata.providerId).isEqualTo(config.providerId)
            assertThat(complete.metadata.requestedProviderId).isEqualTo(config.providerId)
            assertThat(complete.metadata.model).isEqualTo(config.modelId)
            assertThat(complete.metadata.requestedModel).isEqualTo(config.modelId)
            dao.history
                .filter { it.requestId == run.requestId }
                .forEach {
                    assertThat(it.provider).isEqualTo(config.providerId.name)
                    assertThat(it.model).isEqualTo(config.modelId)
                }
        }

        assertThat(configSource.reads.get()).isEqualTo(4)
        assertThat(gatewayFixture.builtConfigs).containsExactly(
            openAi,
            anthropic,
            gemini,
            compatible
        ).inOrder()
        assertThat(gatewayFixture.analyzedConfigs).containsExactly(
            openAi,
            anthropic,
            gemini,
            compatible
        ).inOrder()
        assertThat(credentials.requestedProviders).containsExactly(
            ClinicalAiProviderId.OPENAI,
            ClinicalAiProviderId.ANTHROPIC,
            ClinicalAiProviderId.GEMINI,
            ClinicalAiProviderId.OPENAI_COMPATIBLE
        ).inOrder()
        assertThat(gatewayFixture.observedCredentials).containsExactly(
            "credential-OPENAI",
            "credential-ANTHROPIC",
            "credential-GEMINI",
            "credential-OPENAI_COMPATIBLE"
        ).inOrder()
    }

    @Test
    fun inFlightRunKeepsProviderAndModelSnapshotWhenSettingsChange() = runBlocking {
        val openAi = ClinicalAiProviderConfig(
            ClinicalAiProviderId.OPENAI,
            "gpt-in-flight"
        )
        val anthropic = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-next-run"
        )
        val configSource = MutableClinicalAiConfigSource(
            ClinicalAiConfigState.Valid(openAi)
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gatewayFactory = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { config ->
                    RecordingClinicalAiGateway(
                        config = config,
                        onAnalyze = {
                            entered.complete(Unit)
                            release.await()
                        }
                    )
                },
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder { config ->
                    RecordingClinicalAiGateway(config)
                }
            )
        )
        val credentials = RecordingClinicalAiCredentialSource()
        val dao = FakeClinicalReportDao()
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFactory,
            credentialSource = credentials
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { entered.await() }
        configSource.state = ClinicalAiConfigState.Valid(anthropic)
        release.complete(Unit)
        withTimeout(5_000) { run.job.join() }

        val complete = repository.state.value as ClinicalReportState.Complete
        assertThat(complete.metadata.providerId).isEqualTo(ClinicalAiProviderId.OPENAI)
        assertThat(complete.metadata.model).isEqualTo(openAi.modelId)
        assertThat(credentials.requestedProviders)
            .containsExactly(ClinicalAiProviderId.OPENAI)
        dao.history.filter { it.requestId == run.requestId }.forEach {
            assertThat(it.provider).isEqualTo(ClinicalAiProviderId.OPENAI.name)
            assertThat(it.model).isEqualTo(openAi.modelId)
        }
        assertThat(configSource.reads.get()).isEqualTo(1)
    }

    @Test
    fun invalidConfigFailsClosedBeforeFactoryCredentialOrNetwork() = runBlocking {
        val configSource = MutableClinicalAiConfigSource(
            ClinicalAiConfigState.Invalid(
                ClinicalAiConfigInvalidReason.PARTIAL_CONFIGURATION
            )
        )
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource()
        val repository = providerRepository(
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val failed = repository.state.value as ClinicalReportState.Failed
        assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.INVALID_INPUT)
        assertThat(configSource.reads.get()).isEqualTo(1)
        assertThat(gatewayFixture.builtConfigs).isEmpty()
        assertThat(gatewayFixture.analyzedConfigs).isEmpty()
        assertThat(credentials.requestedProviders).isEmpty()
    }

    @Test
    fun gatewayFactoryIdentityMismatchFailsClosedWithSelectedProvenance() = runBlocking {
        val config = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-identity"
        )
        val dao = FakeClinicalReportDao()
        val credentials = RecordingClinicalAiCredentialSource()
        val gatewayFactory = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder {
                    RecordingClinicalAiGateway(
                        config = ClinicalAiProviderConfig(
                            ClinicalAiProviderId.GEMINI,
                            config.modelId
                        )
                    )
                }
            )
        )
        val repository = providerRepository(
            dao = dao,
            configSource = MutableClinicalAiConfigSource(ClinicalAiConfigState.Valid(config)),
            gatewayFactory = gatewayFactory,
            credentialSource = credentials
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val failed = repository.state.value as ClinicalReportState.Failed
        assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE)
        assertThat(credentials.requestedProviders).isEmpty()
        val persisted = checkNotNull(dao.byId(run.requestId))
        assertThat(persisted.provider).isEqualTo(config.providerId.name)
        assertThat(persisted.model).isEqualTo(config.modelId)
        assertThat(persisted.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE.name)
    }

    @Test
    fun responseMetadataMismatchFailsClosedWithoutRelabelingRun() = runBlocking {
        val config = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-response-identity"
        )
        val dao = FakeClinicalReportDao()
        val credentials = RecordingClinicalAiCredentialSource()
        val gatewayFactory = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder {
                    RecordingClinicalAiGateway(
                        config = config,
                        responseProviderId = ClinicalAiProviderId.GEMINI
                    )
                }
            )
        )
        val repository = providerRepository(
            dao = dao,
            configSource = MutableClinicalAiConfigSource(ClinicalAiConfigState.Valid(config)),
            gatewayFactory = gatewayFactory,
            credentialSource = credentials
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val failed = repository.state.value as ClinicalReportState.Failed
        assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE)
        assertThat(credentials.requestedProviders)
            .containsExactly(ClinicalAiProviderId.ANTHROPIC)
        val persisted = checkNotNull(dao.byId(run.requestId))
        assertThat(persisted.provider).isEqualTo(config.providerId.name)
        assertThat(persisted.model).isEqualTo(config.modelId)
        assertThat(persisted.responseJson).isNull()
    }

    @Test
    fun credentialFailureIsSanitizedAndMappedWithoutNetwork() = runBlocking {
        val config = ClinicalAiProviderConfig(
            ClinicalAiProviderId.GEMINI,
            "gemini-credential-error"
        )
        val dao = FakeClinicalReportDao()
        val auditDao = RecordingAuditLogDao()
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource(
            failure = IllegalStateException("credential failure contains $SECRET")
        )
        val repository = providerRepository(
            dao = dao,
            configSource = MutableClinicalAiConfigSource(ClinicalAiConfigState.Valid(config)),
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials,
            auditLogDao = auditDao
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val failed = repository.state.value as ClinicalReportState.Failed
        assertThat(failed.reason)
            .isEqualTo(ClinicalReportFailureReason.CREDENTIAL_UNAVAILABLE)
        assertThat(credentials.requestedProviders)
            .containsExactly(ClinicalAiProviderId.GEMINI)
        assertThat(gatewayFixture.networkCalls.get()).isEqualTo(0)
        val persisted = checkNotNull(dao.byId(run.requestId))
        assertThat(persisted.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.CREDENTIAL_UNAVAILABLE.name)
        assertThat(
            auditDao.records.joinToString { "${it.message}:${it.metadataJson}" }
        ).doesNotContain(SECRET)
    }

    @Test
    fun localPrepareAndInitializationDoNotReadConfigBuildGatewayOrReadCredential() = runBlocking {
        val configSource = MutableClinicalAiConfigSource(
            ClinicalAiConfigState.Valid(ClinicalAiProviderConfig.defaultOpenAi())
        )
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource()
        val repository = providerRepository(
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials
        )

        repository.initialize()
        repository.recoverInterrupted()
        repository.prepareLocal(NOW, UTC)
        yield()

        assertThat(configSource.reads.get()).isEqualTo(0)
        assertThat(gatewayFixture.builtConfigs).isEmpty()
        assertThat(gatewayFixture.analyzedConfigs).isEmpty()
        assertThat(credentials.requestedProviders).isEmpty()
    }

    @Test
    fun preparedLocalRowAdoptsSelectedProviderWhenRemoteRunStarts() = runBlocking {
        val selected = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-prepared"
        )
        val configSource = MutableClinicalAiConfigSource(
            ClinicalAiConfigState.Valid(ClinicalAiProviderConfig.defaultOpenAi())
        )
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource()
        val dao = FakeClinicalReportDao()
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials
        )

        val prepared = repository.prepareLocal(NOW, UTC)
        assertThat(configSource.reads.get()).isEqualTo(0)
        configSource.state = ClinicalAiConfigState.Valid(selected)

        val remote = repository.start(NOW, UTC)
        withTimeout(5_000) { remote.job.join() }

        assertThat(remote.requestId).isEqualTo(prepared.requestId)
        assertThat(configSource.reads.get()).isEqualTo(1)
        val remoteUpdates = dao.updateArguments
            .filter { it.requestId == remote.requestId }
            .filter {
                it.status in setOf(
                    ClinicalReportStatus.LOCAL_READY.name,
                    ClinicalReportStatus.UPLOADING.name,
                    ClinicalReportStatus.COMPLETE.name
                )
            }
            .takeLast(3)
        assertThat(remoteUpdates.map(ClinicalReportEntity::status)).containsExactly(
            ClinicalReportStatus.LOCAL_READY.name,
            ClinicalReportStatus.UPLOADING.name,
            ClinicalReportStatus.COMPLETE.name
        ).inOrder()
        remoteUpdates.forEach {
            assertThat(it.provider).isEqualTo(selected.providerId.name)
            assertThat(it.model).isEqualTo(selected.modelId)
        }
        assertThat(credentials.requestedProviders)
            .containsExactly(ClinicalAiProviderId.ANTHROPIC)
        Unit
    }

    @Test
    fun mapReduceReadsRotatingCredentialForEveryHttpRequestWithoutPersistingIt() = runBlocking {
        MockWebServer().use { server ->
            val source = dataset(NOW, days = 3, rowsPerDay = 300)
            val budget = ClinicalOpenAiClient.requestBytesForTest(
                source,
                ClinicalOpenAiClient.DEFAULT_MODEL
            ) - 1
            val client = testClient(server, requestByteBudget = budget)
            val plan = client.buildUploadPlan(source)
            assertThat(plan.chunks.size).isGreaterThan(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse = if (
                    request.body.readUtf8().contains(ClinicalOpenAiClient.CHUNK_SCHEMA_NAME)
                ) {
                    chunkSuccessResponse()
                } else {
                    successResponse()
                }
            }
            val credentialReads = AtomicInteger()
            val credentials = CopyOnWriteArrayList<String>()
            val credentialSource = ClinicalAiCredentialSource {
                "rotating-secret-${credentialReads.incrementAndGet()}".also {
                    credentials += it
                }
            }
            val config = ClinicalAiProviderConfig(
                ClinicalAiProviderId.OPENAI,
                ClinicalOpenAiClient.DEFAULT_MODEL
            )
            val dao = FakeClinicalReportDao()
            val auditDao = RecordingAuditLogDao()
            val repository = providerRepository(
                dao = dao,
                configSource = MutableClinicalAiConfigSource(
                    ClinicalAiConfigState.Valid(config)
                ),
                gatewayFactory = ClinicalAiGatewayFactory(
                    mapOf(
                        ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { client }
                    )
                ),
                credentialSource = credentialSource,
                datasetFactory = ClinicalReportDatasetFactory { _, _ -> payload(source) },
                auditLogDao = auditDao
            )

            val run = repository.start(NOW, UTC)
            withTimeout(10_000) { run.job.join() }

            assertThat(repository.state.value)
                .isInstanceOf(ClinicalReportState.Complete::class.java)
            val requestCount = server.requestCount
            assertThat(credentialReads.get()).isEqualTo(requestCount)
            val requests = List(requestCount) { server.takeRequest() }
            assertThat(requests.map { it.getHeader("Authorization") }).containsExactlyElementsIn(
                credentials.map { "Bearer $it" }
            ).inOrder()
            val persistedAndAudited = buildString {
                append(dao.history.joinToString())
                append(auditDao.records.joinToString())
            }
            credentials.forEach { secret ->
                assertThat(persistedAndAudited).doesNotContain(secret)
            }
        }
    }

    @Test
    fun realGatewayForbiddenResponseCannotPersistCompleteOrReachPdfMapping() = runBlocking {
        MockWebServer().use { server ->
            val forbiddenReport = reportObject().apply {
                add(
                    "providerExtension",
                    JsonObject().apply {
                        add(
                            "nested",
                            JsonObject().apply {
                                addProperty("bolusUnits", 2.0)
                            }
                        )
                    }
                )
            }
            server.enqueue(successResponse(forbiddenReport.toString()))
            val dao = FakeClinicalReportDao()
            val auditDao = RecordingAuditLogDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(server),
                auditLogDao = auditDao
            )
            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE)
            val persisted = checkNotNull(dao.byId(run.requestId))
            assertThat(persisted.status).isEqualTo(ClinicalReportStatus.FAILED.name)
            assertThat(persisted.sanitizedError)
                .isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE.name)
            assertThat(persisted.responseJson).isNull()
            assertThat(persisted.renderedText).isNull()
            assertThat(dao.history.map(ClinicalReportEntity::status))
                .doesNotContain(ClinicalReportStatus.COMPLETE.name)
            assertThat(auditDao.records.map(AuditLogEntity::message))
                .doesNotContain("clinical_report_complete")
            assertThat(repository.acquireClinicalPdfSourceLease())
                .isEqualTo(ClinicalPdfSourceLeaseResult.Failed)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun mapReduceCredentialDeletionPreventsTheNextHttpRequestAndLeaksNoSecret() = runBlocking {
        MockWebServer().use { server ->
            val source = dataset(NOW, days = 3, rowsPerDay = 300)
            val budget = ClinicalOpenAiClient.requestBytesForTest(
                source,
                ClinicalOpenAiClient.DEFAULT_MODEL
            ) - 1
            val client = testClient(server, requestByteBudget = budget)
            val plan = client.buildUploadPlan(source)
            assertThat(plan.chunks.size).isGreaterThan(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse = if (
                    request.body.readUtf8().contains(ClinicalOpenAiClient.CHUNK_SCHEMA_NAME)
                ) {
                    chunkSuccessResponse()
                } else {
                    successResponse()
                }
            }
            val deletedSecret = "deleted-rotating-secret"
            val credentialReads = AtomicInteger()
            val credentialSource = ClinicalAiCredentialSource {
                if (credentialReads.getAndIncrement() == 0) {
                    deletedSecret
                } else {
                    throw IllegalStateException("credential deleted: $deletedSecret")
                }
            }
            val config = ClinicalAiProviderConfig(
                ClinicalAiProviderId.OPENAI,
                ClinicalOpenAiClient.DEFAULT_MODEL
            )
            val dao = FakeClinicalReportDao()
            val auditDao = RecordingAuditLogDao()
            val repository = providerRepository(
                dao = dao,
                configSource = MutableClinicalAiConfigSource(
                    ClinicalAiConfigState.Valid(config)
                ),
                gatewayFactory = ClinicalAiGatewayFactory(
                    mapOf(
                        ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { client }
                    )
                ),
                credentialSource = credentialSource,
                datasetFactory = ClinicalReportDatasetFactory { _, _ -> payload(source) },
                auditLogDao = auditDao
            )

            val run = repository.start(NOW, UTC)
            withTimeout(10_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.PARTIAL_CHUNK)
            assertThat(credentialReads.get()).isEqualTo(2)
            assertThat(server.requestCount).isEqualTo(1)
            val persistedAndAudited = buildString {
                append(dao.history.joinToString())
                append(auditDao.records.joinToString())
            }
            assertThat(persistedAndAudited).doesNotContain(deletedSecret)
        }
    }

    @Test
    fun cancellationDuringConfigReadLeavesNoReservationOrRemoteSideEffect() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val configSource = ClinicalAiConfigSource {
            entered.complete(Unit)
            try {
                release.await()
                ClinicalAiConfigState.Valid(ClinicalAiProviderConfig.defaultOpenAi())
            } catch (cancelled: CancellationException) {
                cancellationObserved.complete(Unit)
                throw cancelled
            }
        }
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource()
        val dao = FakeClinicalReportDao()
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials
        )

        val start = async(Dispatchers.Default) { repository.start(NOW, UTC) }
        withTimeout(5_000) { entered.await() }
        start.cancel()
        delay(100)
        val observedBeforeRelease = cancellationObserved.isCompleted
        release.complete(Unit)
        runCatching { start.await() }

        assertThat(observedBeforeRelease).isTrue()
        assertThat(dao.insertInitialCalls.get()).isEqualTo(0)
        assertThat(dao.updateIfStatusInCalls.get()).isEqualTo(0)
        assertThat(dao.markInterruptedCalls.get()).isEqualTo(0)
        assertThat(repository.state.value).isEqualTo(ClinicalReportState.Idle)
        assertThat(gatewayFixture.builtConfigs).isEmpty()
        assertThat(credentials.requestedProviders).isEmpty()
    }

    @Test
    fun providerManagerExposesSafeStatusMutationAndSanitizedConnectionTest() = runBlocking {
        val openAiStorage = FakeSecretStorage()
        val openAiProvider = OpenAiCredentialProvider(
            OpenAiCredentialStore(openAiStorage, EmptyLegacyKeySource)
        )
        val anthropicStorage = FakeSecretStorage()
        val multiProvider = ClinicalAiCredentialProvider(
            mapOf(
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiCredentialStore(
                    providerId = ClinicalAiProviderId.ANTHROPIC,
                    secureStorage = anthropicStorage,
                    legacyCredentialSource = EmptyLegacyCredentialSource
                )
            )
        )
        val config = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-manager-test"
        )
        val testedCredentials = CopyOnWriteArrayList<String>()
        val gatewayFactory = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder {
                    object : ClinicalAiGateway by RecordingClinicalAiGateway(config) {
                        override suspend fun testConnection(
                            credential: String
                        ): ClinicalAiConnectionTest {
                            testedCredentials += credential
                            return ClinicalAiConnectionTest(
                                providerId = config.providerId,
                                modelId = config.modelId,
                                status = ClinicalAiConnectionStatus.SUCCESS,
                                latencyMs = 12L
                            )
                        }
                    }
                }
            )
        )
        val manager = ClinicalAiProviderManager(
            openAiCredentialProvider = openAiProvider,
            clinicalAiCredentialProvider = multiProvider,
            gatewayFactory = gatewayFactory
        )

        manager.replace(ClinicalAiProviderId.OPENAI, "managed-openai-secret")
        manager.replace(ClinicalAiProviderId.ANTHROPIC, "managed-anthropic-secret")
        val status = manager.status.first {
            it[ClinicalAiProviderId.OPENAI]?.busy == false &&
                it[ClinicalAiProviderId.ANTHROPIC]?.busy == false
        }
        assertThat(status[ClinicalAiProviderId.OPENAI]?.configured).isTrue()
        assertThat(status[ClinicalAiProviderId.ANTHROPIC]?.configured).isTrue()

        val connected = manager.testConnection(config)

        assertThat(connected.status).isEqualTo(ClinicalAiConnectionStatus.SUCCESS)
        assertThat(connected.providerId).isEqualTo(ClinicalAiProviderId.ANTHROPIC)
        assertThat(connected.modelId).isEqualTo(config.modelId)
        assertThat(testedCredentials).containsExactly("managed-anthropic-secret")

        manager.delete(ClinicalAiProviderId.ANTHROPIC)
        val missing = manager.testConnection(config)
        assertThat(missing.status)
            .isEqualTo(ClinicalAiConnectionStatus.CREDENTIAL_UNAVAILABLE)
        assertThat(testedCredentials).hasSize(1)
    }

    @Test
    fun providerManagerStatusSettlesForEveryProviderAfterStartupMigration() = runBlocking {
        val openAiProvider = OpenAiCredentialProvider(
            OpenAiCredentialStore(FakeSecretStorage(), EmptyLegacyKeySource)
        )
        val nonOpenAiProviders = ClinicalAiProviderId.values()
            .filterNot { it == ClinicalAiProviderId.OPENAI }
        val stores = nonOpenAiProviders.associateWith { providerId ->
            ClinicalAiCredentialStore(
                providerId = providerId,
                secureStorage = FakeSecretStorage(
                    if (providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE) {
                        "preconfigured-compatible-secret"
                    } else {
                        null
                    }
                ),
                legacyCredentialSource = EmptyLegacyCredentialSource
            )
        }
        val multiProvider = ClinicalAiCredentialProvider(stores)
        val manager = ClinicalAiProviderManager(
            openAiCredentialProvider = openAiProvider,
            clinicalAiCredentialProvider = multiProvider,
            gatewayFactory = ClinicalAiGatewayFactory()
        )

        openAiProvider.ensureMigrated()
        nonOpenAiProviders.forEach { providerId ->
            multiProvider.ensureMigrated(providerId)
        }
        val status = manager.status.first { states ->
            ClinicalAiProviderId.values().all { states[it]?.busy == false }
        }

        assertThat(status.keys)
            .containsExactlyElementsIn(ClinicalAiProviderId.values().asList())
        assertThat(status[ClinicalAiProviderId.OPENAI]?.configured).isFalse()
        assertThat(status[ClinicalAiProviderId.ANTHROPIC]?.configured).isFalse()
        assertThat(status[ClinicalAiProviderId.GEMINI]?.configured).isFalse()
        assertThat(status[ClinicalAiProviderId.OPENAI_COMPATIBLE]?.configured).isTrue()
    }

    @Test
    fun prepareLocalWorksUnconfiguredWithoutCredentialOrNetwork() = runBlocking {
        val dao = FakeClinicalReportDao()
        val storage = FakeSecretStorage()
        val builds = AtomicInteger()
        val repository = repository(
            dao = dao,
            storage = storage,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            }
        )

        val run = repository.prepareLocal(NOW, UTC)

        assertThat(run.requestId).isEqualTo("request-1")
        assertThat(run.disposition).isEqualTo(ClinicalReportRunDisposition.LOCAL_STARTED)
        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.LocalReady::class.java)
        assertThat(builds.get()).isEqualTo(1)
        assertThat(storage.readCount.get()).isEqualTo(0)
        assertThat(dao.byId(run.requestId)?.status).isEqualTo(ClinicalReportStatus.LOCAL_READY.name)
        assertNoOutboundPayload(checkNotNull(dao.byId(run.requestId)))
    }

    @Test
    fun startPublishesAndPersistsLocalReadyBeforeCredentialRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val dao = FakeClinicalReportDao()
            val storage = FakeSecretStorage(SECRET)
            val observedStates = CopyOnWriteArrayList<String>()
            lateinit var repository: ClinicalReportRepository
            storage.onRead = {
                observedStates += "credential"
                assertThat(dao.history.map(ClinicalReportEntity::status))
                    .contains(ClinicalReportStatus.LOCAL_READY.name)
            }
            repository = repository(
                dao = dao,
                storage = storage,
                client = testClient(server)
            )
            val collection = launch(Dispatchers.Unconfined) {
                repository.state.collect { observedStates += it::class.java.simpleName }
            }

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }
            collection.cancel()

            assertThat(observedStates.indexOf("LocalReady")).isAtLeast(0)
            assertThat(observedStates.indexOf("credential"))
                .isGreaterThan(observedStates.indexOf("LocalReady"))
            assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Complete::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun fixedClientProviderPersistsThroughPreparedReuseAndSuccessfulLifecycle() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val dao = FakeClinicalReportDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(server)
            )

            val prepared = repository.prepareLocal(NOW, UTC)
            val remote = repository.start(NOW, UTC)
            withTimeout(5_000) { remote.job.join() }

            assertThat(remote.requestId).isEqualTo(prepared.requestId)
            assertThat(
                dao.history
                    .filter { it.requestId == remote.requestId }
                    .map(ClinicalReportEntity::status)
            ).containsAtLeast(
                ClinicalReportStatus.BUILDING.name,
                ClinicalReportStatus.LOCAL_READY.name,
                ClinicalReportStatus.UPLOADING.name,
                ClinicalReportStatus.COMPLETE.name
            )
            assertThat(dao.updateArguments.map(ClinicalReportEntity::status)).containsAtLeast(
                ClinicalReportStatus.LOCAL_READY.name,
                ClinicalReportStatus.UPLOADING.name,
                ClinicalReportStatus.COMPLETE.name
            )
            dao.history
                .filter { it.requestId == remote.requestId }
                .forEach { assertThat(it.provider).isEqualTo(ClinicalAiProviderId.OPENAI.name) }
            dao.updateArguments
                .filter { it.requestId == remote.requestId }
                .forEach { assertThat(it.provider).isEqualTo(ClinicalAiProviderId.OPENAI.name) }
        }
    }

    @Test
    fun fixedClientProviderPersistsThroughFailureAndRecoveryUpdates() = runBlocking {
        MockWebServer().use { server ->
            val failedDao = FakeClinicalReportDao()
            val failedRepository = repository(
                dao = failedDao,
                storage = FakeSecretStorage(),
                client = testClient(server)
            )

            val failedRun = failedRepository.start(NOW, UTC)
            withTimeout(5_000) { failedRun.job.join() }

            val failureUpdates = failedDao.updateArguments.filter {
                it.requestId == failedRun.requestId
            }
            assertThat(failureUpdates.map(ClinicalReportEntity::status)).containsAtLeast(
                ClinicalReportStatus.LOCAL_READY.name,
                ClinicalReportStatus.UPLOADING.name,
                ClinicalReportStatus.FAILED.name
            )
            failureUpdates.forEach {
                assertThat(it.provider).isEqualTo(ClinicalAiProviderId.OPENAI.name)
            }

            val recoveryDao = FakeClinicalReportDao()
            recoveryDao.upsert(
                entity(
                    id = "stale-upload",
                    status = ClinicalReportStatus.UPLOADING,
                    createdAt = NOW
                ).copy(
                    provider = ClinicalAiProviderId.ANTHROPIC.name,
                    model = "historical-claude"
                )
            )
            val recoveryRepository = repository(dao = recoveryDao)

            recoveryRepository.recoverInterrupted()

            val recovered = checkNotNull(recoveryDao.byId("stale-upload"))
            assertThat(recovered.status).isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
            assertThat(recovered.provider).isEqualTo(ClinicalAiProviderId.ANTHROPIC.name)
            assertThat(recovered.model).isEqualTo("historical-claude")
        }
    }

    @Test
    fun mismatchedCompletionProviderPersistsInvalidResponseWithoutUnknownOutcomeGuard() =
        runBlocking {
        val dao = FakeClinicalReportDao()
        val client = object : ClinicalOpenAiClient() {
            override suspend fun analyze(
                payload: ClinicalReportPayload,
                credentialProvider: suspend () -> String,
                progress: ClinicalOpenAiProgressCallback
            ): ClinicalOpenAiResult = ClinicalOpenAiResult(
                report = advisoryReport(),
                metadata = ClinicalOpenAiMetadata(
                    model = ClinicalOpenAiClient.DEFAULT_MODEL,
                    requestedModel = ClinicalOpenAiClient.DEFAULT_MODEL,
                    systemFingerprint = null,
                    schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
                    schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
                    datasetSchemaVersion = payload.dataset.schemaVersion,
                    requestHash = payload.sha256,
                    chunkCount = 1,
                    usedSynthesis = false,
                    providerId = ClinicalAiProviderId.ANTHROPIC,
                    requestedProviderId = ClinicalAiProviderId.ANTHROPIC
                )
            )
        }
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val failed = repository.state.value as ClinicalReportState.Failed
        assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE)
        val persisted = checkNotNull(dao.byId(run.requestId))
        assertThat(persisted.status).isEqualTo(ClinicalReportStatus.FAILED.name)
        assertThat(persisted.provider).isEqualTo(ClinicalAiProviderId.OPENAI.name)
        assertThat(persisted.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE.name)
        assertThat(persisted.responseJson).isNull()
        assertThat(persisted.model).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
        assertThat(dao.history.map(ClinicalReportEntity::status))
            .doesNotContain(ClinicalReportStatus.COMPLETE.name)
        val blocked = repository.start(NOW, UTC)
        assertThat(blocked.requestId).isEqualTo(run.requestId)
        assertThat(blocked.disposition).isEqualTo(ClinicalReportRunDisposition.COOLDOWN)
    }

    @Test
    fun missingCredentialFailsButRetainsLocalSummary() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeClinicalReportDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(),
                client = testClient(server)
            )

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.CREDENTIAL_UNAVAILABLE)
            assertThat(failed.local).isNotNull()
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(dao.byId(run.requestId)?.localSummaryJson).isNotEqualTo("{}")
        }
    }

    @Test
    fun datasetTooLargeMapsToDistinctRepositoryFailureReason() = runBlocking {
        val client = object : ClinicalOpenAiClient() {
            override suspend fun analyze(
                payload: ClinicalReportPayload,
                credentialProvider: suspend () -> String,
                progress: ClinicalOpenAiProgressCallback
            ): ClinicalOpenAiResult {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
        }
        val repository = repository(
            storage = FakeSecretStorage(SECRET),
            client = client
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val failed = repository.state.value as ClinicalReportState.Failed
        assertThat(failed.reason)
            .isEqualTo(ClinicalReportFailureReason.DATASET_TOO_LARGE)
        assertThat(failed.local).isNotNull()
    }

    @Test
    fun offlineFailureRetainsLocalSummaryWithoutAutomaticRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val dao = FakeClinicalReportDao()
            val clock = MutableClock(NOW)
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(server),
                clock = clock
            )

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.local).isNotNull()
            assertThat(failed.reason)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(dao.byId(run.requestId)?.status).isEqualTo(ClinicalReportStatus.FAILED.name)

            clock.set(NOW + ClinicalReportRepository.REMOTE_COOLDOWN_MS + 1)
            val blocked = repository.start(
                NOW + ClinicalReportRepository.REMOTE_COOLDOWN_MS + 1,
                UTC
            )

            assertThat(blocked.requestId).isEqualTo(run.requestId)
            assertThat(blocked.disposition)
                .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun concurrentStartsShareRequestIdJobDatasetBuildAndNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse().setBodyDelay(400, TimeUnit.MILLISECONDS))
            val builds = AtomicInteger()
            val repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = testClient(server),
                datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                    builds.incrementAndGet()
                    payload(dataset(nowTs))
                }
            )

            val first = repository.start(NOW, UTC)
            val second = repository.start(NOW + 1, UTC)

            assertThat(second.requestId).isEqualTo(first.requestId)
            assertThat(second.job).isSameInstanceAs(first.job)
            assertThat(second.disposition)
                .isEqualTo(ClinicalReportRunDisposition.REMOTE_IN_FLIGHT)
            withTimeout(5_000) { first.job.join() }
            assertThat(builds.get()).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun delayedConfigConcurrentStartsReturnTheSameClaimAfterOwnerCompletes() = runBlocking {
        val ownerConfigEntered = CompletableDeferred<Unit>()
        val ownerConfigRelease = CompletableDeferred<Unit>()
        val waiterConfigRelease = CompletableDeferred<Unit>()
        val configReads = AtomicInteger()
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val configSource = ClinicalAiConfigSource {
            when (configReads.getAndIncrement()) {
                0 -> {
                    ownerConfigEntered.complete(Unit)
                    ownerConfigRelease.await()
                }
                1 -> waiterConfigRelease.await()
                else -> error("Unexpected config read")
            }
            ClinicalAiConfigState.Valid(config)
        }
        val gatewayFixture = RecordingGatewayFixture()
        val dao = FakeClinicalReportDao()
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = RecordingClinicalAiCredentialSource()
        )

        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            repository.start(NOW, UTC)
        }
        ownerConfigEntered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            repository.start(NOW + 1L, UTC)
        }

        ownerConfigRelease.complete(Unit)
        val ownerRun = withTimeout(5_000) { owner.await() }
        withTimeout(5_000) { ownerRun.job.join() }
        waiterConfigRelease.complete(Unit)
        val waiterRun = withTimeout(5_000) { waiter.await() }

        assertThat(configReads.get()).isEqualTo(1)
        assertThat(waiterRun.requestId).isEqualTo(ownerRun.requestId)
        assertThat(waiterRun.job).isSameInstanceAs(ownerRun.job)
        assertThat(waiterRun.disposition).isEqualTo(ownerRun.disposition)
        assertThat(dao.insertInitialCalls.get()).isEqualTo(1)
        assertThat(gatewayFixture.networkCalls.get()).isEqualTo(1)
    }

    @Test
    fun changedExecutionIdentityRejectsBeforeReservationAndRetainsPreparedLocal() =
        runBlocking {
            val disclosed = ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local-model",
                endpoint = "https://models.example/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            )
            val changed = ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local-model",
                endpoint = "https://models.example/v2",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            )
            val configSource = MutableClinicalAiConfigSource(
                ClinicalAiConfigState.Valid(changed)
            )
            val gatewayFixture = RecordingGatewayFixture()
            val credentials = RecordingClinicalAiCredentialSource()
            val dao = FakeClinicalReportDao()
            val repository = providerRepository(
                dao = dao,
                configSource = configSource,
                gatewayFactory = gatewayFixture.factory,
                credentialSource = credentials
            )
            val prepared = repository.prepareLocal(NOW, UTC)
            val localReadyBefore = repository.state.value
            val insertCallsBefore = dao.insertInitialCalls.get()
            val updatesBefore = dao.updateIfStatusInCalls.get()
            val historyBefore = dao.history.toList()

            val failure = runCatching {
                repository.start(
                    nowTs = NOW,
                    zoneId = UTC,
                    expectedConfigIdentity = disclosed.executionIdentity()
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalReportConfigurationChangedException::class.java)
            assertThat(repository.state.value).isEqualTo(localReadyBefore)
            assertThat((localReadyBefore as ClinicalReportState.LocalReady).requestId)
                .isEqualTo(prepared.requestId)
            assertThat(dao.insertInitialCalls.get()).isEqualTo(insertCallsBefore)
            assertThat(dao.updateIfStatusInCalls.get()).isEqualTo(updatesBefore)
            assertThat(dao.history).containsExactlyElementsIn(historyBefore).inOrder()
            assertThat(gatewayFixture.builtConfigs).isEmpty()
            assertThat(gatewayFixture.networkCalls.get()).isEqualTo(0)
            assertThat(credentials.requestedProviders).isEmpty()
        }

    @Test
    fun staleExecutionIdentityRejectsBeforeAnyRecoveryDaoActivityAndClearsPending() =
        runBlocking {
            val disclosed = ClinicalAiProviderConfig.defaultOpenAi()
            val changed = ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI,
                modelId = "changed-model"
            )
            val configSource = MutableClinicalAiConfigSource(
                ClinicalAiConfigState.Valid(changed)
            )
            val gatewayFixture = RecordingGatewayFixture()
            val dao = FakeClinicalReportDao().apply {
                upsert(
                    entity(
                        id = "persisted-unknown-guard",
                        status = ClinicalReportStatus.INTERRUPTED,
                        createdAt = NOW
                    ).copy(
                        sanitizedError =
                            ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
                    )
                )
                upsert(
                    entity(
                        id = "expired-complete",
                        status = ClinicalReportStatus.COMPLETE,
                        createdAt = 0L
                    )
                )
            }
            val repository = providerRepository(
                dao = dao,
                configSource = configSource,
                gatewayFactory = gatewayFixture.factory,
                credentialSource = RecordingClinicalAiCredentialSource()
            )
            val expectedIdentity = disclosed.executionIdentity()

            val failure = runCatching {
                repository.start(
                    nowTs = NOW,
                    zoneId = UTC,
                    expectedConfigIdentity = expectedIdentity
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalReportConfigurationChangedException::class.java)
            assertThat(dao.recoveryDaoCalls).isEmpty()
            assertThat(repository.state.value).isEqualTo(ClinicalReportState.Idle)
            assertThat(dao.byId("persisted-unknown-guard")).isNotNull()
            assertThat(dao.byId("expired-complete")).isNotNull()
            assertThat(dao.insertInitialCalls.get()).isEqualTo(0)
            assertThat(dao.updateIfStatusInCalls.get()).isEqualTo(0)
            assertThat(gatewayFixture.builtConfigs).isEmpty()
            assertThat(gatewayFixture.networkCalls.get()).isEqualTo(0)

            configSource.state = ClinicalAiConfigState.Valid(disclosed)
            val recovered = repository.start(
                nowTs = NOW + 1L,
                zoneId = UTC,
                expectedConfigIdentity = expectedIdentity
            )

            assertThat(recovered.disposition)
                .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
            assertThat(dao.recoveryDaoCalls).isNotEmpty()
        }

    @Test
    fun mismatchedPendingWaitersAreReleasedAndMatchingRetryStartsExactlyOnce() =
        runBlocking {
            val expectedConfig = ClinicalAiProviderConfig.defaultOpenAi()
            val changedConfig = ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI,
                modelId = "changed-model"
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val configSource = MutableClinicalAiConfigSource(
                ClinicalAiConfigState.Valid(changedConfig)
            ).apply {
                beforeRead = {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val gatewayFixture = RecordingGatewayFixture()
            val dao = FakeClinicalReportDao()
            val repository = providerRepository(
                dao = dao,
                configSource = configSource,
                gatewayFactory = gatewayFixture.factory,
                credentialSource = RecordingClinicalAiCredentialSource()
            )
            val identity = expectedConfig.executionIdentity()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    repository.start(NOW, UTC, expectedConfigIdentity = identity)
                }.exceptionOrNull()
            }
            entered.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    repository.start(NOW + 1L, UTC, expectedConfigIdentity = identity)
                }.exceptionOrNull()
            }

            release.complete(Unit)

            assertThat(withTimeout(5_000) { owner.await() })
                .isInstanceOf(ClinicalReportConfigurationChangedException::class.java)
            assertThat(withTimeout(5_000) { waiter.await() })
                .isInstanceOf(ClinicalReportConfigurationChangedException::class.java)
            assertThat(dao.insertInitialCalls.get()).isEqualTo(0)
            assertThat(gatewayFixture.networkCalls.get()).isEqualTo(0)
            assertThat(repository.state.value).isEqualTo(ClinicalReportState.Idle)

            configSource.beforeRead = {}
            configSource.state = ClinicalAiConfigState.Valid(expectedConfig)
            val retry = repository.start(
                NOW + 2L,
                UTC,
                expectedConfigIdentity = identity
            )
            withTimeout(5_000) { retry.job.join() }

            assertThat(dao.insertInitialCalls.get()).isEqualTo(1)
            assertThat(gatewayFixture.networkCalls.get()).isEqualTo(1)
        }

    @Test
    fun pendingStartWithDifferentExpectedIdentityCannotJoinOwner() = runBlocking {
        val ownerConfig = ClinicalAiProviderConfig.defaultOpenAi()
        val otherConfig = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.ANTHROPIC,
            modelId = "claude-sonnet-5"
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val configSource = MutableClinicalAiConfigSource(
            ClinicalAiConfigState.Valid(ownerConfig)
        ).apply {
            beforeRead = {
                entered.complete(Unit)
                release.await()
            }
        }
        val gatewayFixture = RecordingGatewayFixture()
        val dao = FakeClinicalReportDao()
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = RecordingClinicalAiCredentialSource()
        )
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            repository.start(
                NOW,
                UTC,
                expectedConfigIdentity = ownerConfig.executionIdentity()
            )
        }
        entered.await()

        val mismatch = runCatching {
            withTimeout(1_000) {
                repository.start(
                    NOW + 1L,
                    UTC,
                    expectedConfigIdentity = otherConfig.executionIdentity()
                )
            }
        }.exceptionOrNull()

        assertThat(mismatch)
            .isInstanceOf(ClinicalReportConfigurationChangedException::class.java)
        assertThat(dao.insertInitialCalls.get()).isEqualTo(0)
        release.complete(Unit)
        val run = withTimeout(5_000) { owner.await() }
        withTimeout(5_000) { run.job.join() }
        assertThat(dao.insertInitialCalls.get()).isEqualTo(1)
        assertThat(gatewayFixture.networkCalls.get()).isEqualTo(1)
    }

    @Test
    fun delayedConfigConcurrentForcedStartsReturnTheSameClaimAfterOwnerCompletes() =
        runBlocking {
            val ownerConfigEntered = CompletableDeferred<Unit>()
            val ownerConfigRelease = CompletableDeferred<Unit>()
            val waiterConfigRelease = CompletableDeferred<Unit>()
            val configReads = AtomicInteger()
            val config = ClinicalAiProviderConfig.defaultOpenAi()
            val configSource = ClinicalAiConfigSource {
                when (configReads.getAndIncrement()) {
                    0 -> Unit
                    1 -> {
                        ownerConfigEntered.complete(Unit)
                        ownerConfigRelease.await()
                    }
                    2 -> waiterConfigRelease.await()
                    else -> error("Unexpected config read")
                }
                ClinicalAiConfigState.Valid(config)
            }
            val gatewayFixture = RecordingGatewayFixture()
            val dao = FakeClinicalReportDao()
            val repository = providerRepository(
                dao = dao,
                configSource = configSource,
                gatewayFactory = gatewayFixture.factory,
                credentialSource = RecordingClinicalAiCredentialSource()
            )
            val seed = repository.start(NOW, UTC)
            withTimeout(5_000) { seed.job.join() }

            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                repository.start(NOW + 1L, UTC, force = true)
            }
            ownerConfigEntered.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                repository.start(NOW + 2L, UTC, force = true)
            }

            ownerConfigRelease.complete(Unit)
            val ownerRun = withTimeout(5_000) { owner.await() }
            withTimeout(5_000) { ownerRun.job.join() }
            waiterConfigRelease.complete(Unit)
            val waiterRun = withTimeout(5_000) { waiter.await() }

            assertThat(configReads.get()).isEqualTo(2)
            assertThat(waiterRun.requestId).isEqualTo(ownerRun.requestId)
            assertThat(waiterRun.job).isSameInstanceAs(ownerRun.job)
            assertThat(waiterRun.disposition).isEqualTo(ownerRun.disposition)
            assertThat(dao.insertInitialCalls.get()).isEqualTo(2)
            assertThat(gatewayFixture.networkCalls.get()).isEqualTo(2)
        }

    @Test
    fun cancelledPendingStartReleasesWaitersAndAllowsCleanRetry() = runBlocking {
        val configEntered = CompletableDeferred<Unit>()
        val configReads = AtomicInteger()
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val configSource = ClinicalAiConfigSource {
            if (configReads.getAndIncrement() == 0) {
                configEntered.complete(Unit)
                awaitCancellation()
            }
            ClinicalAiConfigState.Valid(config)
        }
        val gatewayFixture = RecordingGatewayFixture()
        val credentials = RecordingClinicalAiCredentialSource()
        val dao = FakeClinicalReportDao()
        val repository = providerRepository(
            dao = dao,
            configSource = configSource,
            gatewayFactory = gatewayFixture.factory,
            credentialSource = credentials
        )

        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            repository.start(NOW, UTC)
        }
        configEntered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            repository.start(NOW + 1L, UTC)
        }
        owner.cancel()
        val ownerFailure = runCatching { owner.await() }.exceptionOrNull()
        val waiterFailure = runCatching { waiter.await() }.exceptionOrNull()

        assertThat(ownerFailure).isInstanceOf(CancellationException::class.java)
        assertThat(waiterFailure).isInstanceOf(CancellationException::class.java)
        assertThat(dao.insertInitialCalls.get()).isEqualTo(0)
        assertThat(repository.state.value).isEqualTo(ClinicalReportState.Idle)
        assertThat(gatewayFixture.builtConfigs).isEmpty()
        assertThat(credentials.requestedProviders).isEmpty()

        val retry = repository.start(NOW + 2L, UTC)
        withTimeout(5_000) { retry.job.join() }

        assertThat(configReads.get()).isEqualTo(2)
        assertThat(dao.insertInitialCalls.get()).isEqualTo(1)
        assertThat(gatewayFixture.networkCalls.get()).isEqualTo(1)
    }

    @Test
    fun simultaneousStartsFromManyEntrantsShareOneRequestBuildAndClientCall() = runBlocking {
        val entrants = 32
        val releaseEntrants = CompletableDeferred<Unit>()
        val builds = AtomicInteger()
        val generatedIds = AtomicInteger()
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            client = client,
            storage = FakeSecretStorage(SECRET),
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            requestIdGenerator = RequestIdGenerator {
                generatedIds.incrementAndGet()
                "request-1"
            }
        )
        val deferred = (0 until entrants).map {
            async(Dispatchers.Default) {
                releaseEntrants.await()
                repository.start(NOW, UTC)
            }
        }

        releaseEntrants.complete(Unit)
        val runs = deferred.awaitAll()
        client.firstCallEntered.await()

        assertThat(runs.map(ClinicalReportRun::requestId).distinct()).containsExactly("request-1")
        assertThat(runs.map(ClinicalReportRun::job).distinct()).hasSize(1)
        assertThat(builds.get()).isEqualTo(1)
        assertThat(generatedIds.get()).isEqualTo(1)
        assertThat(client.calls.get()).isEqualTo(1)
        client.releaseFirstCall.complete(Unit)
        withTimeout(5_000) { runs.first().job.join() }
    }

    @Test
    fun cancelledGenerationCannotCompleteOrPublishStaleProgressIntoNewerGeneration() = runBlocking {
        val client = ControlledClinicalOpenAiClient(blockSecondCall = true)
        val ids = ArrayDeque(listOf("request-old", "request-new"))
        val repository = repository(
            client = client,
            storage = FakeSecretStorage(SECRET),
            requestIdProvider = { ids.removeFirst() }
        )

        val old = repository.start(NOW, UTC)
        client.firstCallEntered.await()
        repository.cancelActive()
        withTimeout(5_000) { old.job.join() }

        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Cancelled::class.java)
        assertThat(repository.state.value.requestId()).isEqualTo("request-old")

        val newer = repository.start(NOW + 1, UTC, force = true)
        client.secondCallEntered.await()
        client.callbacks.first().onProgress(
            ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.COMPLETED, 99, 1)
        )

        assertThat(repository.state.value.requestId()).isEqualTo("request-new")
        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Uploading::class.java)
        client.releaseSecondCall.complete(Unit)
        withTimeout(5_000) { newer.job.join() }
        client.callbacks.first().onProgress(
            ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.COMPLETED, 99, 1)
        )

        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Complete::class.java)
        assertThat(repository.state.value.requestId()).isEqualTo("request-new")
    }

    @Test
    fun cancellationCancelsCallPersistsCancelledAndNeverCompletes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val dao = FakeClinicalReportDao()
            val auditDao = RecordingAuditLogDao()
            val monotonicClock = MutableClock(0L)
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                auditLogDao = auditDao,
                monotonicClock = monotonicClock,
                client = testClient(
                    server,
                    timeouts = ClinicalOpenAiTimeouts(
                        connectMillis = 5_000,
                        writeMillis = 5_000,
                        readMillis = 30_000,
                        callMillis = 30_000
                    )
                )
            )

            val run = repository.start(NOW, UTC)
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            val stageLimitMs = 24L * 60L * 60L * 1_000L
            monotonicClock.set(stageLimitMs + 60_000L)
            repository.cancelActive()
            withTimeout(5_000) { run.job.join() }

            val cancelled = repository.state.value as ClinicalReportState.Cancelled
            assertThat(cancelled.local).isNotNull()
            assertThat(cancelled.local?.summary7d?.days).isEqualTo(7)
            assertThat(cancelled.local?.summary30d?.days).isEqualTo(30)
            val persisted = checkNotNull(dao.byId(run.requestId))
            assertThat(persisted.status)
                .isEqualTo(ClinicalReportStatus.CANCELLED.name)
            assertThat(persisted.responseJson).isNull()
            assertThat(persisted.renderedText).isNull()
            assertThat(dao.history.map(ClinicalReportEntity::status))
                .doesNotContain(ClinicalReportStatus.COMPLETE.name)
            assertThat(auditDao.records.map(AuditLogEntity::message))
                .doesNotContain("clinical_report_complete")
            val audit = JsonParser.parseString(
                auditDao.records.single {
                    it.message == "clinical_report_cancelled"
                }.metadataJson
            ).asJsonObject
            assertThat(audit.get("totalRequestBytes").asLong).isEqualTo(request.bodySize)
            assertThat(audit.get("maxRequestBytes").asLong).isEqualTo(request.bodySize)
            assertThat(audit.get("totalResponseBytes").asLong).isEqualTo(0L)
            assertThat(audit.get("durationMs").asLong).isAtLeast(0L)
            assertThat(audit.keySet()).containsAtLeast(
                "preparingDurationMs",
                "analyzingDurationMs",
                "reducingDurationMs",
                "synthesizingDurationMs",
                "validatingDurationMs"
            )
            assertThat(stageDurationValues(audit)).containsExactly(
                0L,
                0L,
                0L,
                stageLimitMs,
                0L
            ).inOrder()
            assertStageAuditIsBoundedAndSanitized(auditDao)
        }
    }

    @Test
    fun serverFailureAuditRetainsExactPartialTelemetryAndNeverRetriesOrCompletes() =
        runBlocking {
            MockWebServer().use { server ->
                val privateBody = "private-server-error-ошибка"
                val monotonicClock = MutableClock(1_000L)
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(
                        request: okhttp3.mockwebserver.RecordedRequest
                    ): MockResponse {
                        monotonicClock.set(1_400L)
                        return MockResponse().setResponseCode(500).setBody(privateBody)
                    }
                }
                val dao = FakeClinicalReportDao()
                val auditDao = RecordingAuditLogDao()
                val repository = repository(
                    dao = dao,
                    storage = FakeSecretStorage(SECRET),
                    client = testClient(server),
                    monotonicClock = monotonicClock,
                    auditLogDao = auditDao
                )

                val run = repository.start(NOW, UTC)
                withTimeout(5_000) { run.job.join() }
                val request = server.takeRequest()

                val failed = repository.state.value as ClinicalReportState.Failed
                assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.SERVER)
                assertThat(server.requestCount).isEqualTo(1)
                assertThat(auditDao.records.map(AuditLogEntity::message))
                    .doesNotContain("clinical_report_complete")
                val audit = JsonParser.parseString(
                    auditDao.records.single {
                        it.message == "clinical_report_failed"
                    }.metadataJson
                ).asJsonObject
                assertThat(audit.get("totalRequestBytes").asLong).isEqualTo(request.bodySize)
                assertThat(audit.get("maxRequestBytes").asLong).isEqualTo(request.bodySize)
                val responseBytes = privateBody.toByteArray(Charsets.UTF_8).size.toLong()
                assertThat(audit.get("totalResponseBytes").asLong).isEqualTo(responseBytes)
                assertThat(audit.get("maxResponseBytes").asLong).isEqualTo(responseBytes)
                assertThat(audit.get("durationMs").asLong).isAtLeast(0L)
                assertThat(stageDurationValues(audit)).containsExactly(
                    0L,
                    0L,
                    0L,
                    400L,
                    0L
                ).inOrder()
                assertThat(auditDao.records.joinToString()).doesNotContain(privateBody)
                val persisted = checkNotNull(dao.byId(run.requestId))
                assertThat(persisted.coverageJson).doesNotContain("RequestBytes")
                assertThat(persisted.coverageJson).doesNotContain("ResponseBytes")
                assertThat(persisted.responseJson).isNull()
                assertStageAuditIsBoundedAndSanitized(auditDao)
            }
        }

    @Test
    fun startDuringCancellationTerminalizationKeepsOriginalRunOwnership() = runBlocking {
        val client = CancellationBarrierClinicalOpenAiClient()
        val dao = FakeClinicalReportDao()
        val ids = ArrayDeque(listOf("request-original", "request-duplicate"))
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            requestIdProvider = { ids.removeFirst() }
        )
        val original = repository.start(NOW, UTC)
        client.callEntered.await()

        val cancellation = async(Dispatchers.Default) { repository.cancelActive() }
        client.cancellationObserved.await()
        val racing = repository.start(NOW + 1, UTC)

        assertThat(racing.requestId).isEqualTo(original.requestId)
        assertThat(racing.job).isSameInstanceAs(original.job)
        assertThat(racing.disposition)
            .isEqualTo(ClinicalReportRunDisposition.REMOTE_IN_FLIGHT)
        assertThat(client.calls.get()).isEqualTo(1)
        client.allowCancellationToFinish.complete(Unit)
        cancellation.await()

        assertThat(dao.byId(original.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.CANCELLED.name)
        assertThat(dao.byId("request-duplicate")).isNull()
    }

    @Test
    fun cancellationWinsWhenClientConvertsCancellationToOrdinaryFailure() = runBlocking {
        val client = CancellationToFailureClinicalOpenAiClient()
        val dao = FakeClinicalReportDao()
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client
        )
        val run = repository.start(NOW, UTC)
        client.callEntered.await()

        repository.cancelActive()
        withTimeout(5_000) { run.job.join() }

        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Cancelled::class.java)
        assertThat(dao.byId(run.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.CANCELLED.name)
        assertThat(dao.history.map(ClinicalReportEntity::status))
            .doesNotContain(ClinicalReportStatus.FAILED.name)
    }

    @Test
    fun cancellationDuringBlockingCompleteAuditCannotRegressPersistedComplete() = runBlocking {
        val auditDao = BlockingAuditLogDao("clinical_report_complete")
        val dao = FakeClinicalReportDao()
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            auditLogDao = auditDao
        )
        val run = repository.start(NOW, UTC)
        client.firstCallEntered.await()
        client.releaseFirstCall.complete(Unit)
        auditDao.blockedInsertEntered.await()

        assertThat(dao.byId(run.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.COMPLETE.name)
        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Complete::class.java)

        val cancellation = async(Dispatchers.Default) { repository.cancelActive() }
        withTimeout(2_000) {
            while (run.job.isActive) kotlinx.coroutines.yield()
        }
        auditDao.allowBlockedInsert.complete(Unit)
        cancellation.await()
        withTimeout(5_000) { run.job.join() }

        assertThat(dao.byId(run.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.COMPLETE.name)
        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Complete::class.java)
        assertThat(dao.history.map(ClinicalReportEntity::status))
            .doesNotContain(ClinicalReportStatus.CANCELLED.name)
    }

    @Test
    fun partialChunkFailureIsSanitizedAndRetainsLocalSummary() = runBlocking {
        MockWebServer().use { server ->
            val source = dataset(NOW, days = 3, rowsPerDay = 300)
            val budget = ClinicalOpenAiClient.requestBytesForTest(
                source,
                ClinicalOpenAiClient.DEFAULT_MODEL
            ) - 1
            val client = testClient(server, requestByteBudget = budget)
            val plan = client.buildUploadPlan(source)
            assertThat(plan.requiresSynthesis).isTrue()
            server.enqueue(chunkSuccessResponse())
            server.enqueue(MockResponse().setResponseCode(500).setBody("private-server-error"))

            val dao = FakeClinicalReportDao()
            val auditDao = RecordingAuditLogDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = client,
                datasetFactory = ClinicalReportDatasetFactory { _, _ -> payload(source) },
                auditLogDao = auditDao
            )

            val run = repository.start(NOW, UTC)
            withTimeout(10_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.PARTIAL_CHUNK)
            assertThat(failed.local).isNotNull()
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(dao.byId(run.requestId)?.sanitizedError)
                .isEqualTo(ClinicalReportFailureReason.PARTIAL_CHUNK.name)
            assertThat(dao.byId(run.requestId).toString()).doesNotContain("private-server-error")
            assertThat(auditDao.records.map(AuditLogEntity::message))
                .doesNotContain("clinical_report_complete")
        }
    }

    @Test
    fun restartHydratesPersistedUnknownChunkDisconnectAndRequiresExplicitRetry() = runBlocking {
        MockWebServer().use { server ->
            val source = dataset(NOW, days = 3, rowsPerDay = 300)
            val budget = ClinicalOpenAiClient.requestBytesForTest(
                source,
                ClinicalOpenAiClient.DEFAULT_MODEL
            ) - 1
            server.enqueue(chunkSuccessResponse())
            server.enqueue(
                MockResponse()
                    .setBody("private-partial-response")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
            val dao = FakeClinicalReportDao()
            val first = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(server, requestByteBudget = budget),
                datasetFactory = ClinicalReportDatasetFactory { _, _ -> payload(source) }
            )

            val failedRun = first.start(NOW, UTC)
            withTimeout(10_000) { failedRun.job.join() }

            val failed = first.state.value as ClinicalReportState.Failed
            assertThat(failed.reason)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
            assertThat(dao.byId(failedRun.requestId)?.sanitizedError)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
            assertThat(server.requestCount).isEqualTo(2)

            val retryClient = ControlledClinicalOpenAiClient(blockSecondCall = false)
            val builds = AtomicInteger()
            val restarted = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = retryClient,
                datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                    builds.incrementAndGet()
                    payload(dataset(nowTs))
                },
                requestIdGenerator = RequestIdGenerator { "request-retry" }
            )

            restarted.recoverInterrupted()

            val recovered = restarted.state.value as ClinicalReportState.Failed
            assertThat(recovered.requestId).isEqualTo(failedRun.requestId)
            assertThat(recovered.reason)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
            assertThat(recovered.local).isNotNull()
            val blocked = restarted.start(NOW + DAY_MS, UTC)
            assertThat(blocked.requestId).isEqualTo(failedRun.requestId)
            assertThat(blocked.disposition)
                .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
            assertThat(builds.get()).isEqualTo(0)
            assertThat(retryClient.calls.get()).isEqualTo(0)

            val retry = restarted.start(NOW + DAY_MS, UTC, force = true)
            retryClient.firstCallEntered.await()
            assertThat(dao.byId(failedRun.requestId)).isNull()
            retryClient.releaseFirstCall.complete(Unit)
            withTimeout(5_000) { retry.job.join() }

            assertThat(retry.requestId).isEqualTo("request-retry")
            assertThat(builds.get()).isEqualTo(1)
            assertThat(retryClient.calls.get()).isEqualTo(1)

            val afterAcknowledgedRetry = repository(dao = dao)
            afterAcknowledgedRetry.recoverInterrupted()

            assertThat(afterAcknowledgedRetry.state.value).isEqualTo(ClinicalReportState.Idle)
            assertThat(dao.byId(failedRun.requestId)).isNull()
        }
    }

    @Test
    fun interruptedUnknownGuardSurvivesThreeConsecutiveRestarts() = runBlocking {
        val dao = FakeClinicalReportDao()
        val seed = repository(dao = dao)
        val prepared = seed.prepareLocal(NOW, UTC)
        dao.replaceStatus(prepared.requestId, ClinicalReportStatus.UPLOADING)

        repeat(3) { restartIndex ->
            val restarted = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = ControlledClinicalOpenAiClient(blockSecondCall = false)
            )

            restarted.recoverInterrupted()

            val recovered = restarted.state.value as ClinicalReportState.Failed
            assertThat(recovered.requestId).isEqualTo(prepared.requestId)
            assertThat(recovered.reason)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
            val blocked = restarted.start(NOW + restartIndex + 1L, UTC)
            assertThat(blocked.requestId).isEqualTo(prepared.requestId)
            assertThat(blocked.disposition)
                .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
            assertThat(dao.byId(prepared.requestId)?.status)
                .isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
        }
    }

    @Test
    fun recoveredAnthropicUnknownGuardPreservesProviderThroughOpenAiLocalRefresh() = runBlocking {
        val dao = FakeClinicalReportDao()
        val unknown = entity(
            id = "unknown-request",
            status = ClinicalReportStatus.INTERRUPTED,
            createdAt = NOW
        ).copy(
            requestedFromTs = 0L,
            requestedThroughTs = 0L,
            requestHash = "",
            coverageJson = "{}",
            localSummaryJson = "{}",
            provider = ClinicalAiProviderId.ANTHROPIC.name,
            completedAt = NOW,
            sanitizedError = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
        )
        dao.upsert(unknown)
        val builds = AtomicInteger()
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            requestIdGenerator = RequestIdGenerator { "confirmed-retry" }
        )
        repository.recoverInterrupted()

        val recovered = repository.state.value as ClinicalReportState.Failed
        assertThat(recovered.reason)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
        assertThat(recovered.local).isNull()
        val observedStates = CopyOnWriteArrayList<ClinicalReportState>()
        val collection = launch(Dispatchers.Unconfined) {
            repository.state.collect(observedStates::add)
        }

        val localRefresh = repository.prepareLocal(NOW + DAY_MS, UTC)
        collection.cancel()

        assertThat(localRefresh.requestId).isEqualTo(unknown.requestId)
        assertThat(localRefresh.disposition)
            .isEqualTo(ClinicalReportRunDisposition.LOCAL_STARTED)
        val stillGuarded = repository.state.value as ClinicalReportState.Failed
        assertThat(stillGuarded.requestId).isEqualTo(unknown.requestId)
        assertThat(stillGuarded.reason)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
        assertThat(stillGuarded.local?.generatedAt).isEqualTo(NOW + DAY_MS)
        val persistedGuard = checkNotNull(dao.byId(unknown.requestId))
        assertThat(persistedGuard.status).isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
        assertThat(persistedGuard.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
        assertThat(persistedGuard.provider).isEqualTo(ClinicalAiProviderId.ANTHROPIC.name)
        assertThat(persistedGuard.localSummaryJson).isNotEqualTo("{}")
        assertThat(builds.get()).isEqualTo(1)
        assertThat(client.calls.get()).isEqualTo(0)
        assertThat(observedStates).isNotEmpty()
        assertThat(observedStates.all { it is ClinicalReportState.Failed }).isTrue()

        val blocked = repository.start(NOW + DAY_MS, UTC)
        assertThat(blocked.requestId).isEqualTo(unknown.requestId)
        assertThat(blocked.disposition)
            .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
        assertThat(client.calls.get()).isEqualTo(0)

        val retry = repository.start(NOW + DAY_MS, UTC, force = true)
        client.firstCallEntered.await()
        client.releaseFirstCall.complete(Unit)
        withTimeout(5_000) { retry.job.join() }

        assertThat(retry.requestId).isEqualTo("confirmed-retry")
        assertThat(builds.get()).isEqualTo(1)
        assertThat(client.calls.get()).isEqualTo(1)
        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.Complete::class.java)
    }

    @Test
    fun forcedUnknownRetryWaitsForOwnedLocalRefreshThenAcknowledgesAndStartsRemoteOnce() =
        runBlocking {
            val dao = FakeClinicalReportDao()
            dao.upsert(
                entity(
                    id = "unknown-request",
                    status = ClinicalReportStatus.INTERRUPTED,
                    createdAt = NOW
                ).copy(
                    requestHash = "",
                    coverageJson = "{}",
                    localSummaryJson = "{}",
                    completedAt = NOW,
                    sanitizedError =
                        ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
                )
            )
            val datasetEntered = CompletableDeferred<Unit>()
            val releaseDataset = CompletableDeferred<Unit>()
            val builds = AtomicInteger()
            val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = client,
                datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                    builds.incrementAndGet()
                    datasetEntered.complete(Unit)
                    releaseDataset.await()
                    payload(dataset(nowTs))
                },
                requestIdGenerator = RequestIdGenerator { "forced-remote" }
            )
            repository.recoverInterrupted()

            val localPreparation = async {
                repository.prepareLocal(NOW + DAY_MS, UTC)
            }
            datasetEntered.await()
            val forcedRetry = async {
                repository.start(NOW + DAY_MS, UTC, force = true)
            }
            yield()

            assertThat(forcedRetry.isCompleted).isFalse()
            assertThat(client.calls.get()).isEqualTo(0)
            assertThat(dao.acknowledgeCalls.get()).isEqualTo(0)

            releaseDataset.complete(Unit)
            localPreparation.await()
            val remoteRun = forcedRetry.await()
            client.firstCallEntered.await()

            assertThat(remoteRun.disposition)
                .isEqualTo(ClinicalReportRunDisposition.REMOTE_STARTED)
            assertThat(remoteRun.requestId).isEqualTo("forced-remote")
            assertThat(builds.get()).isEqualTo(1)
            assertThat(client.calls.get()).isEqualTo(1)
            assertThat(dao.acknowledgeCalls.get()).isEqualTo(1)
            assertThat(dao.byId("unknown-request")).isNull()

            client.releaseFirstCall.complete(Unit)
            withTimeout(5_000) { remoteRun.job.join() }
        }

    @Test
    fun expiredUnknownGuardIsRedactedToMinimalFieldsAndStillBlocksAcrossRestarts() =
        runBlocking {
            val dao = FakeClinicalReportDao()
            val terminalAt = NOW - ClinicalReportRepository.RETENTION_MS - 1L
            val requestId = "expired-unknown"
            dao.upsert(
                entity(
                    id = requestId,
                    status = ClinicalReportStatus.UPLOADING,
                    createdAt = terminalAt
                ).copy(
                    requestedFromTs = terminalAt - 30L * DAY_MS,
                    requestedThroughTs = terminalAt,
                    requestHash = "private-medical-hash",
                    coverageJson = """{"private":"coverage"}""",
                    localSummaryJson = """{"private":"summary"}""",
                    responseJson = """{"private":"response"}""",
                    renderedText = "private rendered medical report",
                    model = "private-model",
                    completedAt = null,
                    sanitizedError = null
                )
            )

            repeat(2) {
                val restarted = repository(
                    dao = dao,
                    clock = MutableClock(NOW)
                )
                restarted.recoverInterrupted()

                val recovered = restarted.state.value as ClinicalReportState.Failed
                assertThat(recovered.requestId).isEqualTo(requestId)
                assertThat(recovered.local).isNull()
                assertThat(recovered.reason)
                    .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
                val blocked = restarted.start(NOW, UTC)
                assertThat(blocked.requestId).isEqualTo(requestId)
                assertThat(blocked.disposition)
                    .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
            }

            val redacted = checkNotNull(dao.byId(requestId))
            assertThat(redacted.status).isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
            assertThat(redacted.sanitizedError)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
            assertThat(redacted.createdAt).isEqualTo(terminalAt)
            assertThat(redacted.completedAt).isEqualTo(NOW)
            assertThat(redacted.requestedFromTs).isEqualTo(0L)
            assertThat(redacted.requestedThroughTs).isEqualTo(0L)
            assertThat(redacted.requestHash).isEmpty()
            assertThat(redacted.coverageJson).isEqualTo("{}")
            assertThat(redacted.localSummaryJson).isEqualTo("{}")
            assertThat(redacted.responseJson).isNull()
            assertThat(redacted.renderedText).isNull()
            assertThat(redacted.model).isNull()
            assertThat(redacted.toString()).doesNotContain("private")
        }

    @Test
    fun prepareLocalNewerCancelledRowAndClockAnomaliesDoNotClearUnknownGuard() = runBlocking {
        val dao = FakeClinicalReportDao()
        val unknown = entity(
            id = "unknown-request",
            status = ClinicalReportStatus.FAILED,
            createdAt = NOW
        ).copy(
            completedAt = NOW,
            sanitizedError = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
        )
        dao.upsert(unknown)
        val firstRestart = repository(
            dao = dao,
            clock = MutableClock(NOW + DAY_MS)
        )
        firstRestart.recoverInterrupted()

        val prepared = firstRestart.prepareLocal(NOW + DAY_MS, UTC)
        assertThat(prepared.requestId).isEqualTo(unknown.requestId)
        val refreshedGuard = checkNotNull(dao.byId(prepared.requestId))
        assertThat(refreshedGuard.status).isEqualTo(ClinicalReportStatus.FAILED.name)
        assertThat(refreshedGuard.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
        assertThat(refreshedGuard.localSummaryJson).isNotEqualTo("{}")
        dao.upsert(
            entity(
                id = "newer-cancelled",
                status = ClinicalReportStatus.CANCELLED,
                createdAt = NOW + 2L * DAY_MS
            )
        )

        val restarted = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = ControlledClinicalOpenAiClient(blockSecondCall = false),
            clock = MutableClock(NOW - DAY_MS)
        )
        restarted.recoverInterrupted()

        val recovered = restarted.state.value as ClinicalReportState.Failed
        assertThat(recovered.requestId).isEqualTo(unknown.requestId)
        assertThat(recovered.reason)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
        val blocked = restarted.start(NOW - DAY_MS, UTC)
        assertThat(blocked.requestId).isEqualTo(unknown.requestId)
        assertThat(blocked.disposition)
            .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)

        val forwardClockRestart = repository(
            dao = dao,
            clock = MutableClock(
                NOW + ClinicalReportRepository.RETENTION_MS + DAY_MS
            )
        )
        forwardClockRestart.recoverInterrupted()

        val forwardBlocked = forwardClockRestart.start(NOW + DAY_MS, UTC)
        assertThat(forwardBlocked.requestId).isEqualTo(unknown.requestId)
        assertThat(forwardBlocked.disposition)
            .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
    }

    @Test
    fun failedForceReservationLeavesUnknownGuardUnacknowledgedAcrossRestart() = runBlocking {
        val dao = FakeClinicalReportDao()
        dao.upsert(
            entity(
                id = "duplicate-id",
                status = ClinicalReportStatus.COMPLETE,
                createdAt = NOW - DAY_MS
            )
        )
        val unknown = entity(
            id = "unknown-request",
            status = ClinicalReportStatus.FAILED,
            createdAt = NOW
        ).copy(
            completedAt = NOW,
            sanitizedError = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
        )
        dao.upsert(unknown)
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            requestIdGenerator = RequestIdGenerator { "duplicate-id" }
        )
        repository.recoverInterrupted()

        val failure = runCatching {
            repository.start(NOW + 1L, UTC, force = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(dao.byId(unknown.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.FAILED.name)
        assertThat(dao.byId(unknown.requestId)?.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
        assertThat(client.calls.get()).isEqualTo(0)

        val restarted = repository(dao = dao)
        restarted.recoverInterrupted()

        val blocked = restarted.start(NOW + 2L, UTC)
        assertThat(blocked.requestId).isEqualTo(unknown.requestId)
        assertThat(blocked.disposition)
            .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
    }

    @Test
    fun failedAcknowledgementAfterReservationKeepsUnknownGuardAcrossRestart() = runBlocking {
        val dao = FakeClinicalReportDao().apply {
            failNextAcknowledgement = true
        }
        val unknown = entity(
            id = "unknown-request",
            status = ClinicalReportStatus.FAILED,
            createdAt = NOW
        ).copy(
            completedAt = NOW,
            sanitizedError = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name
        )
        dao.upsert(unknown)
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            requestIdGenerator = RequestIdGenerator { "new-reservation" }
        )
        repository.recoverInterrupted()

        val failure = runCatching {
            repository.start(NOW + 1L, UTC, force = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(dao.byId(unknown.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.FAILED.name)
        assertThat(dao.byId("new-reservation")?.status)
            .isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
        assertThat(dao.byId("new-reservation")?.sanitizedError)
            .isEqualTo(
                ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST.name
            )
        assertThat(client.calls.get()).isEqualTo(0)

        val restarted = repository(dao = dao)
        restarted.recoverInterrupted()

        val blocked = restarted.start(NOW + 2L, UTC)
        assertThat(blocked.requestId).isEqualTo(unknown.requestId)
        assertThat(blocked.disposition)
            .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
    }

    @Test
    fun multiChunkTimeoutIsUnknownRemoteOutcome() = runBlocking {
        MockWebServer().use { server ->
            val source = dataset(NOW, days = 3, rowsPerDay = 300)
            val budget = ClinicalOpenAiClient.requestBytesForTest(
                source,
                ClinicalOpenAiClient.DEFAULT_MODEL
            ) - 1
            server.enqueue(chunkSuccessResponse())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val dao = FakeClinicalReportDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(
                    server,
                    requestByteBudget = budget,
                    timeouts = ClinicalOpenAiTimeouts(
                        connectMillis = 500,
                        writeMillis = 500,
                        readMillis = 250,
                        callMillis = 250
                    )
                ),
                datasetFactory = ClinicalReportDatasetFactory { _, _ -> payload(source) }
            )

            val run = repository.start(NOW, UTC)
            withTimeout(10_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.reason)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
            assertThat(dao.byId(run.requestId)?.sanitizedError)
                .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun invalidResponseIsSanitizedAndRetainsLocalSummary() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    JsonObject().apply {
                        addProperty("status", "completed")
                        addProperty("model", ClinicalOpenAiClient.DEFAULT_MODEL)
                        addProperty("output_text", """{"unexpected":"private-medical-text"}""")
                    }.toString()
                )
            )
            val dao = FakeClinicalReportDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(server)
            )

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            val failed = repository.state.value as ClinicalReportState.Failed
            assertThat(failed.reason).isEqualTo(ClinicalReportFailureReason.INVALID_RESPONSE)
            assertThat(failed.local).isNotNull()
            assertThat(dao.byId(run.requestId).toString()).doesNotContain("private-medical-text")
        }
    }

    @Test
    fun freshPreparedDatasetIsReusedByRequestIdHashAndTtl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val builds = AtomicInteger()
            val repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = testClient(server),
                datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                    builds.incrementAndGet()
                    payload(dataset(nowTs))
                }
            )

            val prepared = repository.prepareLocal(NOW, UTC)
            val started = repository.start(NOW + 30_000L, UTC)
            withTimeout(5_000) { started.job.join() }

            assertThat(started.requestId).isEqualTo(prepared.requestId)
            assertThat(builds.get()).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun backgroundReleaseDropsPayloadCacheButKeepsLocalSummary() = runBlocking {
        val builds = AtomicInteger()
        val repository = repository(
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            }
        )

        val first = repository.prepareLocal(NOW, UTC)
        val visibleState = repository.state.value

        repository.releasePreparedPayloadWhenIdle()
        val second = repository.prepareLocal(NOW + 30_000L, UTC)

        assertThat(visibleState).isInstanceOf(ClinicalReportState.LocalReady::class.java)
        assertThat(repository.state.value).isInstanceOf(ClinicalReportState.LocalReady::class.java)
        assertThat(second.requestId).isNotEqualTo(first.requestId)
        assertThat(builds.get()).isEqualTo(2)
    }

    @Test
    fun preparedDatasetReusesAtTtlBoundaryThenRebuildsWithNewHashAfterExpiry() = runBlocking {
        val builds = AtomicInteger()
        val dao = FakeClinicalReportDao()
        val repository = repository(
            dao = dao,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, zoneId ->
                builds.incrementAndGet()
                payload(dataset(nowTs).copy(zoneId = zoneId.id))
            }
        )

        val first = repository.prepareLocal(NOW, UTC)
        val firstHash = (repository.state.value as ClinicalReportState.LocalReady)
            .local.requestHash
        val boundary = repository.prepareLocal(
            NOW + ClinicalReportRepository.PREPARED_DATASET_TTL_MS,
            UTC
        )
        val expired = repository.prepareLocal(
            NOW + ClinicalReportRepository.PREPARED_DATASET_TTL_MS + 1,
            UTC
        )
        val rebuilt = repository.state.value as ClinicalReportState.LocalReady

        assertThat(boundary.requestId).isEqualTo(first.requestId)
        assertThat(expired.requestId).isNotEqualTo(first.requestId)
        assertThat(builds.get()).isEqualTo(2)
        assertThat(rebuilt.local.requestHash).isNotEqualTo(firstHash)
        assertThat(rebuilt.local.generatedAt)
            .isEqualTo(NOW + ClinicalReportRepository.PREPARED_DATASET_TTL_MS + 1)
        assertThat(dao.byId(expired.requestId)?.requestHash)
            .isEqualTo(rebuilt.local.requestHash)
    }

    @Test
    fun preparedDatasetZoneMismatchRebuildsAndPublishesNewZoneHash() = runBlocking {
        val builds = AtomicInteger()
        val tbilisi = ZoneId.of("Asia/Tbilisi")
        val dao = FakeClinicalReportDao()
        val repository = repository(
            dao = dao,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, zoneId ->
                builds.incrementAndGet()
                payload(dataset(nowTs).copy(zoneId = zoneId.id))
            }
        )

        val first = repository.prepareLocal(NOW, UTC)
        val firstHash = (repository.state.value as ClinicalReportState.LocalReady)
            .local.requestHash
        val changed = repository.prepareLocal(NOW, tbilisi)
        val rebuilt = repository.state.value as ClinicalReportState.LocalReady

        assertThat(changed.requestId).isNotEqualTo(first.requestId)
        assertThat(builds.get()).isEqualTo(2)
        assertThat(rebuilt.local.zoneId).isEqualTo(tbilisi.id)
        assertThat(rebuilt.local.requestHash).isNotEqualTo(firstHash)
        assertThat(dao.byId(changed.requestId)?.requestHash)
            .isEqualTo(rebuilt.local.requestHash)
    }

    @Test
    fun blankGeneratedRequestIdFailsBeforeBuildCredentialOrNetwork() {
        val builds = AtomicInteger()
        val storage = FakeSecretStorage(SECRET)
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            storage = storage,
            client = client,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            requestIdGenerator = RequestIdGenerator { "   " }
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.start(NOW, UTC) }
        }
        assertThat(builds.get()).isEqualTo(0)
        assertThat(storage.readCount.get()).isEqualTo(0)
        assertThat(client.calls.get()).isEqualTo(0)
        assertThat(repository.state.value).isEqualTo(ClinicalReportState.Idle)
    }

    @Test
    fun persistedRequestIdCollisionFailsWithoutOverwritingOrStartingRemoteWork() = runBlocking {
        val dao = FakeClinicalReportDao()
        dao.upsert(entity("duplicate-id", ClinicalReportStatus.COMPLETE, NOW - DAY_MS))
        val builds = AtomicInteger()
        val generatedIds = AtomicInteger()
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            dao = dao,
            client = client,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            requestIdGenerator = RequestIdGenerator {
                generatedIds.incrementAndGet()
                "duplicate-id"
            }
        )

        val failure = runCatching {
            repository.start(NOW, UTC)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(builds.get()).isEqualTo(0)
        assertThat(client.calls.get()).isEqualTo(0)
        assertThat(generatedIds.get())
            .isEqualTo(ClinicalReportRepository.REQUEST_ID_RESERVATION_ATTEMPTS)
        assertThat(dao.insertInitialCalls.get())
            .isEqualTo(ClinicalReportRepository.REQUEST_ID_RESERVATION_ATTEMPTS)
        assertThat(dao.byId("duplicate-id")?.status)
            .isEqualTo(ClinicalReportStatus.COMPLETE.name)
    }

    @Test
    fun atomicInitialInsertRetriesCollisionThenStartsWithReservedId() = runBlocking {
        val dao = FakeClinicalReportDao()
        dao.upsert(entity("duplicate-id", ClinicalReportStatus.COMPLETE, NOW - DAY_MS))
        val generatedIds = AtomicInteger()
        val builds = AtomicInteger()
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val repository = repository(
            dao = dao,
            client = client,
            storage = FakeSecretStorage(SECRET),
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                assertThat(dao.byId("reserved-id")?.status)
                    .isEqualTo(ClinicalReportStatus.BUILDING.name)
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            requestIdGenerator = RequestIdGenerator {
                if (generatedIds.incrementAndGet() == 1) "duplicate-id" else "reserved-id"
            }
        )

        val run = repository.start(NOW, UTC)
        client.firstCallEntered.await()
        client.releaseFirstCall.complete(Unit)
        withTimeout(5_000) { run.job.join() }

        assertThat(run.requestId).isEqualTo("reserved-id")
        assertThat(generatedIds.get()).isEqualTo(2)
        assertThat(dao.insertInitialCalls.get()).isEqualTo(2)
        assertThat(builds.get()).isEqualTo(1)
        assertThat(dao.byId("duplicate-id")?.status)
            .isEqualTo(ClinicalReportStatus.COMPLETE.name)
        assertThat(dao.byId("reserved-id")?.status)
            .isEqualTo(ClinicalReportStatus.COMPLETE.name)
    }

    @Test
    fun committedInsertCancellationIsReconciledBeforeReservationOwnershipIsReleased() =
        runBlocking {
            val dao = FakeClinicalReportDao().apply {
                commitThenCancelNextInsert = true
            }
            val ids = ArrayDeque(listOf("cancelled-reservation", "next-request"))
            val builds = AtomicInteger()
            val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = client,
                datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                    builds.incrementAndGet()
                    payload(dataset(nowTs))
                },
                requestIdProvider = { ids.removeFirst() }
            )

            val cancellation = runCatching {
                repository.start(NOW, UTC)
            }.exceptionOrNull()

            assertThat(cancellation).isInstanceOf(CancellationException::class.java)
            assertThat(dao.byId("cancelled-reservation")?.status)
                .isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
            assertThat(dao.byId("cancelled-reservation")?.sanitizedError)
                .isEqualTo(
                    ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST.name
                )
            assertThat(dao.latest(10).map(ClinicalReportEntity::status))
                .doesNotContain(ClinicalReportStatus.BUILDING.name)
            assertThat(builds.get()).isEqualTo(0)
            assertThat(client.calls.get()).isEqualTo(0)

            val next = repository.start(NOW + 1L, UTC)
            client.firstCallEntered.await()
            client.releaseFirstCall.complete(Unit)
            withTimeout(5_000) { next.job.join() }

            assertThat(next.requestId).isEqualTo("next-request")
            assertThat(builds.get()).isEqualTo(1)
            assertThat(client.calls.get()).isEqualTo(1)
            assertThat(dao.byId("next-request")?.status)
                .isEqualTo(ClinicalReportStatus.COMPLETE.name)
        }

    @Test
    fun cooldownBlocksDuplicateButForceAfterTerminalStartsNewRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            server.enqueue(successResponse())
            val clock = MutableClock(NOW)
            val ids = ArrayDeque(listOf("request-1", "request-2"))
            val repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = testClient(server),
                clock = clock,
                requestIdProvider = { ids.removeFirst() }
            )

            val first = repository.start(NOW, UTC)
            withTimeout(5_000) { first.job.join() }
            val blocked = repository.start(NOW + 1, UTC)

            assertThat(blocked.requestId).isEqualTo(first.requestId)
            assertThat(blocked.disposition).isEqualTo(ClinicalReportRunDisposition.COOLDOWN)
            assertThat(server.requestCount).isEqualTo(1)

            val forced = repository.start(NOW + 1, UTC, force = true)
            withTimeout(5_000) { forced.job.join() }

            assertThat(forced.requestId).isEqualTo("request-2")
            assertThat(forced.disposition)
                .isEqualTo(ClinicalReportRunDisposition.REMOTE_STARTED)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun inProcessCooldownUsesMonotonicTimeWhenWallClockMovesBackward() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            server.enqueue(successResponse())
            val wallClock = MutableClock(NOW)
            val monotonicClock = MutableClock(10_000L)
            val repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = testClient(server),
                clock = wallClock,
                monotonicClock = monotonicClock
            )

            val first = repository.start(NOW, UTC)
            withTimeout(5_000) { first.job.join() }
            wallClock.set(NOW - DAY_MS)
            monotonicClock.set(
                10_000L + ClinicalReportRepository.REMOTE_COOLDOWN_MS - 1L
            )

            val blocked = repository.start(NOW + 1L, UTC)

            assertThat(blocked.requestId).isEqualTo(first.requestId)
            assertThat(blocked.disposition).isEqualTo(ClinicalReportRunDisposition.COOLDOWN)
            assertThat(server.requestCount).isEqualTo(1)

            monotonicClock.set(
                10_000L + ClinicalReportRepository.REMOTE_COOLDOWN_MS + 1L
            )
            val second = repository.start(NOW + 2L, UTC)
            withTimeout(5_000) { second.job.join() }

            assertThat(second.requestId).isNotEqualTo(first.requestId)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun forceRetryBeforeTerminalStatusIsRejectedWithoutBuildOrNetwork() {
        val builds = AtomicInteger()
        val repository = repository(
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.start(NOW, UTC, force = true) }
        }
        assertThat(builds.get()).isEqualTo(0)
        assertThat(repository.state.value).isEqualTo(ClinicalReportState.Idle)
    }

    @Test
    fun restartMarksStaleWorkInterruptedAndRunsRetentionCleanup() = runBlocking {
        val clock = MutableClock(NOW)
        val dao = FakeClinicalReportDao()
        dao.upsert(entity("building", ClinicalReportStatus.BUILDING, NOW - DAY_MS))
        dao.upsert(entity("uploading", ClinicalReportStatus.UPLOADING, NOW - DAY_MS / 2))
        dao.upsert(
            entity(
                "expired",
                ClinicalReportStatus.COMPLETE,
                NOW - ClinicalReportRepository.RETENTION_MS - 1
            )
        )
        val repository = repository(dao = dao, clock = clock)

        repository.recoverInterrupted()

        assertThat(dao.byId("expired")).isNull()
        assertThat(dao.byId("building")?.status)
            .isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
        assertThat(dao.byId("uploading")?.status)
            .isEqualTo(ClinicalReportStatus.INTERRUPTED.name)
        assertThat(dao.byId("building")?.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST.name)
        assertThat(dao.byId("uploading")?.sanitizedError)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name)
    }

    @Test
    fun restartHydratesNewestUploadingLocalSummaryAndRequiresExplicitRetry() = runBlocking {
        val dao = FakeClinicalReportDao()
        val seed = repository(dao = dao)
        val prepared = seed.prepareLocal(NOW - 1_000L, UTC)
        dao.replaceStatus(prepared.requestId, ClinicalReportStatus.UPLOADING)
        dao.upsert(entity("older-building", ClinicalReportStatus.BUILDING, NOW - DAY_MS))
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val builds = AtomicInteger()
        val restarted = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            requestIdGenerator = RequestIdGenerator { "request-retry" }
        )

        restarted.recoverInterrupted()

        val recovered = restarted.state.value as ClinicalReportState.Failed
        assertThat(recovered.requestId).isEqualTo(prepared.requestId)
        assertThat(recovered.reason)
            .isEqualTo(ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME)
        assertThat(recovered.local).isNotNull()
        assertThat(recovered.local?.requestHash)
            .isEqualTo(dao.byId(prepared.requestId)?.requestHash)
        val blocked = restarted.start(NOW + DAY_MS, UTC)
        assertThat(blocked.requestId).isEqualTo(prepared.requestId)
        assertThat(blocked.disposition)
            .isEqualTo(ClinicalReportRunDisposition.RETRY_REQUIRED)
        assertThat(builds.get()).isEqualTo(0)
        assertThat(client.calls.get()).isEqualTo(0)

        val retry = restarted.start(NOW + DAY_MS, UTC, force = true)
        client.firstCallEntered.await()
        client.releaseFirstCall.complete(Unit)
        withTimeout(5_000) { retry.job.join() }

        assertThat(retry.requestId).isEqualTo("request-retry")
        assertThat(client.calls.get()).isEqualTo(1)
        assertThat(restarted.state.value).isInstanceOf(ClinicalReportState.Complete::class.java)
    }

    @Test
    fun buildingRecoveryHydratesPreRequestFailureWithoutRemoteRetryGuard() = runBlocking {
        val dao = FakeClinicalReportDao()
        dao.upsert(entity("building-only", ClinicalReportStatus.BUILDING, NOW - 1_000L))
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        val restarted = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            requestIdGenerator = RequestIdGenerator { "after-building" }
        )

        restarted.recoverInterrupted()

        val recovered = restarted.state.value as ClinicalReportState.Failed
        assertThat(recovered.requestId).isEqualTo("building-only")
        assertThat(recovered.local).isNull()
        assertThat(recovered.reason)
            .isEqualTo(ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST)
        val next = restarted.start(NOW, UTC)
        client.firstCallEntered.await()
        client.releaseFirstCall.complete(Unit)
        withTimeout(5_000) { next.job.join() }

        assertThat(next.requestId).isEqualTo("after-building")
        assertThat(next.disposition)
            .isEqualTo(ClinicalReportRunDisposition.REMOTE_STARTED)
        assertThat(client.calls.get()).isEqualTo(1)
    }

    @Test
    fun recoveryConditionalUpdateDoesNotOverwriteConcurrentCompleteOrCancel() = runBlocking {
        val dao = FakeClinicalReportDao()
        dao.upsert(entity("building", ClinicalReportStatus.BUILDING, NOW - DAY_MS))
        dao.upsert(entity("uploading", ClinicalReportStatus.UPLOADING, NOW - DAY_MS / 2))
        dao.afterLatestSnapshot = {
            dao.replaceStatus("building", ClinicalReportStatus.COMPLETE)
            dao.replaceStatus("uploading", ClinicalReportStatus.CANCELLED)
        }
        val repository = repository(dao = dao)

        repository.recoverInterrupted()

        assertThat(dao.byId("building")?.status).isEqualTo(ClinicalReportStatus.COMPLETE.name)
        assertThat(dao.byId("uploading")?.status).isEqualTo(ClinicalReportStatus.CANCELLED.name)
        assertThat(dao.markInterruptedCalls.get()).isEqualTo(2)
    }

    @Test
    fun firstStartWaitsForInProgressRecoveryBeforeWritingOrCallingClient() = runBlocking {
        val dao = FakeClinicalReportDao()
        val recoveryRead = CompletableDeferred<Unit>()
        val allowRecovery = CompletableDeferred<Unit>()
        val builds = AtomicInteger()
        val client = ControlledClinicalOpenAiClient(blockSecondCall = false)
        dao.afterLatestSnapshot = {
            recoveryRead.complete(Unit)
            allowRecovery.await()
        }
        val repository = repository(
            dao = dao,
            client = client,
            storage = FakeSecretStorage(SECRET),
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            }
        )

        val recovery = async(Dispatchers.Default) { repository.recoverInterrupted() }
        recoveryRead.await()
        val start = async(Dispatchers.Default) { repository.start(NOW, UTC) }
        delay(100)

        assertThat(builds.get()).isEqualTo(0)
        assertThat(client.calls.get()).isEqualTo(0)
        allowRecovery.complete(Unit)
        recovery.await()
        val run = start.await()
        client.firstCallEntered.await()
        client.releaseFirstCall.complete(Unit)
        withTimeout(5_000) { run.job.join() }

        assertThat(builds.get()).isEqualTo(1)
        assertThat(dao.byId(run.requestId)?.status)
            .isEqualTo(ClinicalReportStatus.COMPLETE.name)
    }

    @Test
    fun successfulPersistenceContainsOnlyStructuredResultAndMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val dao = FakeClinicalReportDao()
            val repository = repository(
                dao = dao,
                storage = FakeSecretStorage(SECRET),
                client = testClient(server)
            )

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            val entity = checkNotNull(dao.byId(run.requestId))
            assertThat(entity.status).isEqualTo(ClinicalReportStatus.COMPLETE.name)
            assertThat(entity.model).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(entity.schemaVersion).isEqualTo(ClinicalOpenAiClient.SCHEMA_VERSION)
            assertThat(entity.renderedText).isNull()
            assertThat(entity.sanitizedError).isNull()
            val response = JsonParser.parseString(checkNotNull(entity.responseJson)).asJsonObject
            assertThat(response.keySet()).containsExactly(
                "summary7dStatus",
                "summary30dStatus",
                "dataQuality",
                "patterns",
                "safetyObservations",
                "recommendations",
                "careTeamQuestions"
            )
            assertNoOutboundPayload(entity)
        }
    }

    @Test
    fun completeAuditAndCoveragePersistOnlyBoundedExecutionMetadata() = runBlocking {
        val dao = FakeClinicalReportDao()
        val auditDao = RecordingAuditLogDao()
        val baseSource = dataset(NOW)
        val markerTs = baseSource.glucose30d.first().ts
        val source = baseSource.copy(
            detail24h = baseSource.detail24h.copy(
                glucose = baseSource.detail24h.glucose.map {
                    if (it.ts == markerTs) it.copy(mmol = 13.789) else it
                },
                calibratedGlucose = baseSource.detail24h.calibratedGlucose.map {
                    if (it.ts == markerTs) it.copy(mmol = 13.789) else it
                }
            ),
            glucose7d = baseSource.glucose7d.map {
                if (it.ts == markerTs) it.copy(mmol = 13.789) else it
            },
            glucose30d = baseSource.glucose30d.map {
                if (it.ts == markerTs) it.copy(mmol = 13.789) else it
            }
        )
        val sourcePayload = payload(source)
        val metadata = ClinicalOpenAiMetadata(
            model = ClinicalOpenAiClient.DEFAULT_MODEL,
            requestedModel = ClinicalOpenAiClient.DEFAULT_MODEL,
            systemFingerprint = "fp_test",
            schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
            schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
            datasetSchemaVersion = source.schemaVersion,
            requestHash = sourcePayload.sha256,
            chunkCount = 9,
            usedSynthesis = true,
            reductionLevels = 3,
            coverageLedgerHash = "a".repeat(64),
            maxRequestBytes = 45_000L,
            maxResponseBytes = 12_000L,
            totalRequestBytes = 180_000L,
            totalResponseBytes = 48_000L,
            durationMs = 1_234L,
            sourceRows = ClinicalSourceRowCounts(
                glucose = 577,
                insulin = 41,
                carbs = 12,
                targets = 96
            )
        )
        val client = object : ClinicalOpenAiClient() {
            override suspend fun analyze(
                payload: ClinicalReportPayload,
                credentialProvider: suspend () -> String,
                progress: ClinicalOpenAiProgressCallback
            ): ClinicalOpenAiResult = ClinicalOpenAiResult(
                report = advisoryReport(),
                metadata = metadata
            )
        }
        val repository = repository(
            dao = dao,
            storage = FakeSecretStorage(SECRET),
            client = client,
            datasetFactory = ClinicalReportDatasetFactory { _, _ -> sourcePayload },
            auditLogDao = auditDao
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val completeAudit = auditDao.records.single {
            it.message == "clinical_report_complete"
        }
        val auditJson = JsonParser.parseString(completeAudit.metadataJson).asJsonObject
        assertThat(auditJson.keySet()).containsAtLeast(
            "requestHash",
            "coverageLedgerHash",
            "leafCount",
            "reductionLevels",
            "maxRequestBytes",
            "maxResponseBytes",
            "totalRequestBytes",
            "totalResponseBytes",
            "durationMs"
        )
        auditJson.entrySet().forEach { (_, value) ->
            assertThat(value.isJsonPrimitive || value.isJsonNull).isTrue()
        }
        val serializedAudit = auditDao.records.joinToString {
            "${it.message}:${it.metadataJson}"
        }
        listOf(
            "\"glucoseSeries\"",
            "\"d24\"",
            "\"g7\"",
            "\"g30\"",
            "\"leafHashes\"",
            "\"canonicalJson\"",
            "\"rootDigest\"",
            SECRET,
            "13.789"
        ).forEach { forbidden ->
            assertThat(serializedAudit).doesNotContain(forbidden)
        }

        val entity = checkNotNull(dao.byId(run.requestId))
        val coverage = JsonParser.parseString(entity.coverageJson).asJsonObject
        assertThat(coverage.keySet()).containsExactly(
            "summary7d",
            "summary30d",
            "ledgerHash",
            "leafCount",
            "reductionLevels",
            "sourceRows"
        )
        assertThat(coverage.get("ledgerHash").asString).isEqualTo("a".repeat(64))
        assertThat(coverage.get("leafCount").asInt).isEqualTo(9)
        assertThat(coverage.get("reductionLevels").asInt).isEqualTo(3)
        assertThat(coverage.getAsJsonObject("sourceRows").keySet()).containsExactly(
            "glucose",
            "insulin",
            "carbs",
            "targets"
        )
        val persistedSerialized = entity.coverageJson + entity.localSummaryJson
        listOf(
            "\"leafHashes\"",
            "\"canonicalJson\"",
            "\"rootDigest\"",
            "\"d24\"",
            "\"g7\"",
            "\"g30\""
        ).forEach { forbidden ->
            assertThat(persistedSerialized).doesNotContain(forbidden)
        }
    }

    @Test
    fun completionAuditAccumulatesExactStageDurationsWithoutSameStageDoubleCount() =
        runBlocking {
            val auditDao = RecordingAuditLogDao()
            val monotonicClock = MutableClock(1_000L)
            val client = scriptedProgressClient(
                monotonicClock = monotonicClock,
                scripts = listOf(
                    listOf(
                        1_100L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.PREPARING,
                            0,
                            1
                        ),
                        1_150L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.PREPARING,
                            0,
                            1
                        ),
                        1_300L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                            1,
                            3
                        ),
                        1_400L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                            2,
                            3
                        ),
                        1_700L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.REDUCING,
                            1,
                            2,
                            level = 1
                        ),
                        1_800L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.REDUCING,
                            1,
                            1,
                            level = 2
                        ),
                        2_200L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.SYNTHESIZING,
                            1,
                            1,
                            level = 2
                        ),
                        2_600L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.VALIDATING,
                            1,
                            1,
                            level = 2
                        ),
                        3_100L to ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.COMPLETED,
                            1,
                            1,
                            level = 2
                        )
                    )
                )
            )
            val repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = client,
                monotonicClock = monotonicClock,
                auditLogDao = auditDao
            )

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            val audit = completionAuditJson(auditDao, index = 0)
            assertThat(audit.keySet()).containsAtLeast(
                "preparingDurationMs",
                "analyzingDurationMs",
                "reducingDurationMs",
                "synthesizingDurationMs",
                "validatingDurationMs"
            )
            assertThat(audit.get("preparingDurationMs").asLong).isEqualTo(300L)
            assertThat(audit.get("analyzingDurationMs").asLong).isEqualTo(400L)
            assertThat(audit.get("reducingDurationMs").asLong).isEqualTo(500L)
            assertThat(audit.get("synthesizingDurationMs").asLong).isEqualTo(400L)
            assertThat(audit.get("validatingDurationMs").asLong).isEqualTo(500L)
            assertStageAuditIsBoundedAndSanitized(auditDao)
        }

    @Test
    fun directPathAuditSeparatesPreparingSynthesisAndValidationDurations() = runBlocking {
        val auditDao = RecordingAuditLogDao()
        val monotonicClock = MutableClock(1_000L)
        val client = scriptedProgressClient(
            monotonicClock = monotonicClock,
            scripts = listOf(
                listOf(
                    1_100L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.PREPARING,
                        0,
                        1
                    ),
                    1_300L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.SYNTHESIZING,
                        0,
                        1
                    ),
                    1_700L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.VALIDATING,
                        0,
                        1
                    ),
                    2_100L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.COMPLETED,
                        1,
                        1
                    )
                )
            )
        )
        val repository = repository(
            storage = FakeSecretStorage(SECRET),
            client = client,
            monotonicClock = monotonicClock,
            auditLogDao = auditDao
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        assertThat(stageDurationValues(completionAuditJson(auditDao, index = 0)))
            .containsExactly(
                300L,
                0L,
                0L,
                400L,
                400L
            ).inOrder()
    }

    @Test
    fun stageTimingRebasesBackwardClockAndNeverPublishesNegativeDurations() = runBlocking {
        val auditDao = RecordingAuditLogDao()
        val monotonicClock = MutableClock(1_000L)
        val client = scriptedProgressClient(
            monotonicClock = monotonicClock,
            scripts = listOf(
                listOf(
                    900L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.PREPARING,
                        0,
                        1
                    ),
                    950L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                        1,
                        1
                    ),
                    900L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.REDUCING,
                        1,
                        1,
                        level = 1
                    ),
                    1_000L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.SYNTHESIZING,
                        1,
                        1,
                        level = 1
                    ),
                    1_100L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.VALIDATING,
                        1,
                        1,
                        level = 1
                    ),
                    1_200L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.COMPLETED,
                        1,
                        1,
                        level = 1
                    )
                )
            )
        )
        val repository = repository(
            storage = FakeSecretStorage(SECRET),
            client = client,
            monotonicClock = monotonicClock,
            auditLogDao = auditDao
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val audit = completionAuditJson(auditDao, index = 0)
        assertThat(audit.get("preparingDurationMs").asLong).isEqualTo(50L)
        assertThat(audit.get("analyzingDurationMs").asLong).isEqualTo(0L)
        assertThat(audit.get("reducingDurationMs").asLong).isEqualTo(100L)
        assertThat(audit.get("synthesizingDurationMs").asLong).isEqualTo(100L)
        assertThat(audit.get("validatingDurationMs").asLong).isEqualTo(100L)
        stageDurationValues(audit).forEach { duration ->
            assertThat(duration).isAtLeast(0L)
        }
    }

    @Test
    fun stageTimingSaturatesHugeOverflowingJumpAtFixedPerReportBound() = runBlocking {
        val auditDao = RecordingAuditLogDao()
        val monotonicClock = MutableClock(Long.MIN_VALUE + 10L)
        val client = scriptedProgressClient(
            monotonicClock = monotonicClock,
            scripts = listOf(
                listOf(
                    Long.MAX_VALUE to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                        1,
                        1
                    ),
                    Long.MAX_VALUE to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.COMPLETED,
                        1,
                        1
                    )
                )
            )
        )
        val repository = repository(
            storage = FakeSecretStorage(SECRET),
            client = client,
            monotonicClock = monotonicClock,
            auditLogDao = auditDao
        )

        val run = repository.start(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val audit = completionAuditJson(auditDao, index = 0)
        val fixedBound = 24L * 60L * 60L * 1_000L
        assertThat(audit.get("preparingDurationMs").asLong).isEqualTo(fixedBound)
        assertThat(audit.get("analyzingDurationMs").asLong).isEqualTo(0L)
        assertThat(stageDurationValues(audit).sum()).isEqualTo(fixedBound)
        stageDurationValues(audit).forEach { duration ->
            assertThat(duration).isIn(0L..fixedBound)
        }
    }

    @Test
    fun stageTimingIsIsolatedBetweenConsecutiveActiveRuns() = runBlocking {
        val auditDao = RecordingAuditLogDao()
        val monotonicClock = MutableClock(0L)
        val client = scriptedProgressClient(
            monotonicClock = monotonicClock,
            scripts = listOf(
                listOf(
                    100L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                        1,
                        1
                    ),
                    200L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.VALIDATING,
                        1,
                        1
                    ),
                    300L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.COMPLETED,
                        1,
                        1
                    )
                ),
                listOf(
                    350L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                        1,
                        1
                    ),
                    400L to ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.COMPLETED,
                        1,
                        1
                    )
                )
            )
        )
        val repository = repository(
            storage = FakeSecretStorage(SECRET),
            client = client,
            monotonicClock = monotonicClock,
            auditLogDao = auditDao
        )

        val first = repository.start(NOW, UTC)
        withTimeout(5_000) { first.job.join() }
        val second = repository.start(NOW + 1L, UTC, force = true)
        withTimeout(5_000) { second.job.join() }

        val firstAudit = completionAuditJson(auditDao, index = 0)
        val secondAudit = completionAuditJson(auditDao, index = 1)
        assertThat(stageDurationValues(firstAudit)).containsExactly(
            100L,
            100L,
            0L,
            0L,
            100L
        ).inOrder()
        assertThat(stageDurationValues(secondAudit)).containsExactly(
            50L,
            50L,
            0L,
            0L,
            0L
        ).inOrder()
    }

    @Test
    fun clientProgressMapsToBoundedUploadingState() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(successResponse().setBodyDelay(300, TimeUnit.MILLISECONDS))
            val repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = testClient(server)
            )
            val states = CopyOnWriteArrayList<ClinicalReportState>()
            val collection = launch(Dispatchers.Unconfined) {
                repository.state.collect { states += it }
            }

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }
            collection.cancel()

            val progress = states.filterIsInstance<ClinicalReportState.Uploading>()
            assertThat(progress).isNotEmpty()
            assertThat(progress.all {
                it.completed in 0..it.total && it.total >= 1
            }).isTrue()
        }
    }

    @Test
    fun repositoryPublishesDistinctProgressStagesAndLevelsWithoutPartialComplete() =
        runBlocking {
            val observed = CopyOnWriteArrayList<ClinicalReportState>()
            lateinit var repository: ClinicalReportRepository
            fun result(payload: ClinicalReportPayload) = ClinicalOpenAiResult(
                report = ClinicalAdvisoryReport(
                    summary7dStatus = ClinicalSummaryStatus.STABLE,
                    summary30dStatus = ClinicalSummaryStatus.STABLE,
                    dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
                    patterns = emptyList(),
                    safetyObservations = listOf(
                        ClinicalSafetyObservation.NONE_IDENTIFIED
                    ),
                    recommendations = emptyList(),
                    careTeamQuestions = emptyList()
                ),
                metadata = ClinicalOpenAiMetadata(
                    model = ClinicalOpenAiClient.DEFAULT_MODEL,
                    requestedModel = ClinicalOpenAiClient.DEFAULT_MODEL,
                    systemFingerprint = null,
                    schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
                    schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
                    datasetSchemaVersion = payload.dataset.schemaVersion,
                    requestHash = payload.sha256,
                    chunkCount = 3,
                    usedSynthesis = true,
                    reductionLevels = 2
                )
            )
            val events = listOf(
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.PREPARING, 0, 1),
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.ANALYZING_CHUNK, 1, 3),
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.REDUCING, 1, 2, level = 1),
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.REDUCING, 1, 1, level = 2),
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.SYNTHESIZING, 1, 1, level = 2),
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.VALIDATING, 1, 1, level = 2)
            )
            val client = object : ClinicalOpenAiClient() {
                override suspend fun analyze(
                    payload: ClinicalReportPayload,
                    credentialProvider: suspend () -> String,
                    progress: ClinicalOpenAiProgressCallback
                ): ClinicalOpenAiResult {
                    events.forEach { event ->
                        progress.onProgress(event)
                        observed += repository.state.value
                    }
                    return result(payload)
                }
            }
            repository = repository(
                storage = FakeSecretStorage(SECRET),
                client = client
            )

            val run = repository.start(NOW, UTC)
            withTimeout(5_000) { run.job.join() }

            assertThat(observed.filterIsInstance<ClinicalReportState.Complete>())
                .isEmpty()
            val uploading = observed.filterIsInstance<ClinicalReportState.Uploading>()
            assertThat(uploading.map { it.stage to it.level }).containsExactly(
                ClinicalOpenAiProgressStage.PREPARING to 0,
                ClinicalOpenAiProgressStage.ANALYZING_CHUNK to 0,
                ClinicalOpenAiProgressStage.REDUCING to 1,
                ClinicalOpenAiProgressStage.REDUCING to 2,
                ClinicalOpenAiProgressStage.SYNTHESIZING to 2,
                ClinicalOpenAiProgressStage.VALIDATING to 2
            ).inOrder()
            assertThat(uploading.all {
                it.completed in 0..it.total && it.total >= 1
            }).isTrue()
            assertThat(
                uploading.filter {
                    it.stage == ClinicalOpenAiProgressStage.SYNTHESIZING ||
                        it.stage == ClinicalOpenAiProgressStage.VALIDATING
                }.all { it.completed == 0 && it.total == 1 }
            ).isTrue()
            assertThat(repository.state.value)
                .isInstanceOf(ClinicalReportState.Complete::class.java)
        }

    @Test
    fun cancellingDatasetBuildPersistsIncompleteWithoutCredentialOrNetwork() = runBlocking {
        MockWebServer().use { server ->
            val entered = MutableStateFlow(false)
            val dao = FakeClinicalReportDao()
            val storage = FakeSecretStorage(SECRET)
            val repository = repository(
                dao = dao,
                storage = storage,
                client = testClient(server),
                datasetFactory = ClinicalReportDatasetFactory { _, _ ->
                    entered.value = true
                    awaitCancellation()
                }
            )

            val run = repository.start(NOW, UTC)
            withTimeout(2_000) {
                while (!entered.value) kotlinx.coroutines.yield()
            }
            repository.cancelActive()
            withTimeout(5_000) { run.job.join() }

            val cancelled = repository.state.value as ClinicalReportState.Cancelled
            assertThat(cancelled.local).isNull()
            assertThat(storage.readCount.get()).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(dao.byId(run.requestId)?.status)
                .isEqualTo(ClinicalReportStatus.CANCELLED.name)
        }
    }

    @Test
    fun repositorySourceHasNoActionAutomationTargetCalibrationOrSettingsDependency() {
        val source = sourceFile("ClinicalReportRepository.kt").readText()
        listOf(
            "ActionRepository",
            "AutomationRepository",
            "TargetManagerRepository",
            "GlucoseCalibrationRepository",
            "NightscoutActionRepository",
            "AppSettings"
        ).forEach { forbidden ->
            assertThat(source).doesNotContain(forbidden)
        }
        val publicConstructorStart = source.indexOf("    constructor(")
        val publicDeclaration = source.substring(
            publicConstructorStart,
            source.indexOf("    ) : this(", publicConstructorStart)
        )
        assertThat(publicDeclaration).doesNotContain("RequestIdGenerator")
        assertThat(publicDeclaration).doesNotContain("requestIdProvider")
    }

    @Test
    fun recoveryUsesBoundedStatusQueriesAndPersistedUnknownAcknowledgement() {
        val repositorySource = sourceFile("ClinicalReportRepository.kt").readText()
        val daoSource = sourceFile(
            name = "ClinicalReportDao.kt",
            packagePath = "io/aaps/copilot/data/local/dao"
        ).readText()

        assertThat(repositorySource).contains("reportDao.activeForRecovery(")
        assertThat(repositorySource).doesNotContain("latest(Int.MAX_VALUE)")
        assertThat(daoSource).contains("suspend fun activeForRecovery(")
        assertThat(daoSource).contains("WHERE status IN (:statuses)")
        assertThat(daoSource).contains("LIMIT :limit")
        assertThat(repositorySource).contains("reportDao.latestUnknownOutcomeGuard(")
        assertThat(daoSource).contains("suspend fun latestUnknownOutcomeGuard(")
        assertThat(daoSource).contains("WHERE status IN (:statuses)")
        assertThat(daoSource).contains("AND sanitizedError = :sanitizedError")
        assertThat(daoSource).contains("LIMIT 1")
        assertThat(daoSource).contains("suspend fun acknowledgeUnknownOutcomeIfStatusIn(")
        assertThat(daoSource).contains("SET status = :acknowledgedStatus")
        assertThat(daoSource).contains("suspend fun redactUnknownOutcomeGuardsOlderThan(")
        assertThat(daoSource).contains("requestHash = ''")
        assertThat(daoSource).contains("localSummaryJson = '{}'")
        assertThat(daoSource).contains("suspend fun acknowledgeAndDeleteUnknownOutcome(")
        assertThat(daoSource).contains("suspend fun deleteAcknowledgedUnknownOutcome(")
    }

    @Test
    fun lifecyclePersistenceUsesStatusCasAfterAtomicInitialInsert() {
        val repositorySource = sourceFile("ClinicalReportRepository.kt").readText()
        val daoSource = sourceFile(
            name = "ClinicalReportDao.kt",
            packagePath = "io/aaps/copilot/data/local/dao"
        ).readText()

        assertThat(repositorySource).contains("reportDao.insertInitial(")
        assertThat(repositorySource).contains("reportDao.updateIfStatusIn(")
        assertThat(repositorySource).doesNotContain("reportDao.upsert(")
        assertThat(repositorySource).doesNotContain("reportDao.byId(requestId)")
        assertThat(daoSource).contains("OnConflictStrategy.IGNORE")
        assertThat(daoSource).contains("suspend fun insertInitial(")
        assertThat(daoSource).contains("suspend fun updateIfStatusIn(")
        assertThat(daoSource).contains("AND status IN (:expectedStatuses)")
    }

    @Test
    fun capturesExactPreparedPdfSourceBeforeLifecycleRelease() = runBlocking {
        val repository = repository()
        val run = repository.prepareLocal(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val local = (repository.state.value as ClinicalReportState.LocalReady).local
        assertThat(ClinicalPdfContentSourceFactory.create(payload(dataset(NOW)), local, null))
            .isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)
        val acquired = repository.acquireClinicalPdfSourceLease()
        assertThat(acquired).isInstanceOf(ClinicalPdfSourceLeaseResult.Ready::class.java)
        val lease = (acquired as ClinicalPdfSourceLeaseResult.Ready).lease
        val release = async { repository.releasePreparedPayloadWhenIdle() }
        yield()

        assertThat(release.isCompleted).isFalse()
        val captured = lease.buildSource()
        assertThat(captured).isInstanceOf(ClinicalPdfSourceCaptureResult.Ready::class.java)
        val source = (captured as ClinicalPdfSourceCaptureResult.Ready).source
        assertThat(source.identity.requestSha256)
            .isEqualTo((repository.state.value as ClinicalReportState.LocalReady).local.requestHash)
        assertThat(source.open().use { it.next() }).isNotNull()
        lease.close()
        release.await()
        assertThat(repository.acquireClinicalPdfSourceLease())
            .isEqualTo(ClinicalPdfSourceLeaseResult.Failed)
    }

    @Test
    fun concurrentCaptureOwnsSourceBeforeOnStopReleaseCanClearPreparedPayload() = runBlocking {
        val repository = repository()
        val run = repository.prepareLocal(NOW, UTC)
        withTimeout(5_000) { run.job.join() }

        val acquired = repository.acquireClinicalPdfSourceLease()
        assertThat(acquired).isInstanceOf(ClinicalPdfSourceLeaseResult.Ready::class.java)
        val lease = (acquired as ClinicalPdfSourceLeaseResult.Ready).lease
        val buildMayProceed = CompletableDeferred<Unit>()
        val capture = async(Dispatchers.Default) {
            buildMayProceed.await()
            lease.buildSource()
        }
        val release = async { repository.releasePreparedPayloadWhenIdle() }
        yield()

        assertThat(release.isCompleted).isFalse()
        buildMayProceed.complete(Unit)
        val captured = capture.await()
        assertThat(release.isCompleted).isFalse()
        lease.close()
        release.await()

        assertThat(captured).isInstanceOf(ClinicalPdfSourceCaptureResult.Ready::class.java)
        val source = (captured as ClinicalPdfSourceCaptureResult.Ready).source
        assertThat(source.open().use { it.next() }).isNotNull()
    }

    @Test
    fun exportBeginClaimsLeaseBeforeReturningWhileConcurrentOnStopReleaseWaits() = runBlocking {
        val repository = repository()
        val run = repository.prepareLocal(NOW, UTC)
        withTimeout(5_000) { run.job.join() }
        lateinit var release: kotlinx.coroutines.Job
        // Assert lease ownership before staging can reach its terminal result.
        val finishStaging = CompletableDeferred<Unit>()
        val coordinator = ClinicalReportPdfExportCoordinator(
            scope = this,
            acquireSourceLease = {
                val acquired = repository.acquireClinicalPdfSourceLease()
                release = launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.releasePreparedPayloadWhenIdle()
                }
                assertThat(release.isCompleted).isFalse()
                acquired
            },
            sourceDispatcher = Dispatchers.Default,
            stage = {
                finishStaging.await()
                ClinicalPdfStageResult.Failed
            },
            copy = { _, _ -> ClinicalPdfCopyResult.Failed },
            share = { ClinicalPdfShareResult.Failed },
            discardShare = {},
            currentReportIdentity = {
                ClinicalPdfReportIdentity.from(repository.state.value)
            },
            initialReportIdentity = ClinicalPdfReportIdentity.from(repository.state.value),
            release = {}
        )

        coordinator.begin()

        assertThat(coordinator.state.value.phase)
            .isEqualTo(ClinicalPdfExportUiState.PREPARING)
        assertThat(release.isCompleted).isFalse()
        finishStaging.complete(Unit)
        withTimeout(5_000) {
            coordinator.state.first { it.phase == ClinicalPdfExportUiState.FAILED }
        }
        withTimeout(5_000) { release.join() }
        assertThat(repository.acquireClinicalPdfSourceLease())
            .isEqualTo(ClinicalPdfSourceLeaseResult.Failed)
        coordinator.close()
    }

    @Test
    fun pdfReprepareClaimRequiresMatchingIdentityAndNoActiveLease() = runBlocking {
        val repository = repository()
        val first = repository.prepareLocal(NOW, UTC)
        withTimeout(5_000) { first.job.join() }
        val acquired = repository.acquireClinicalPdfSourceLease()
            as ClinicalPdfSourceLeaseResult.Ready
        val currentIdentity = ClinicalPdfReportIdentity.from(repository.state.value)
        val wrongIdentity = ClinicalPdfReportIdentity(
            currentIdentity.phase,
            "f".repeat(64)
        )

        assertThat(repository.claimClinicalPdfReprepare(wrongIdentity))
            .isEqualTo(ClinicalPdfReprepareResult.REJECTED)
        assertThat(repository.claimClinicalPdfReprepare(currentIdentity))
            .isEqualTo(ClinicalPdfReprepareResult.REJECTED)

        acquired.lease.close()
        assertThat(repository.claimClinicalPdfReprepare(currentIdentity))
            .isEqualTo(ClinicalPdfReprepareResult.PREPARE_REQUIRED)

        val second = repository.prepareLocal(NOW + 1L, UTC)
        withTimeout(5_000) { second.job.join() }
        assertThat(second.requestId).isNotEqualTo(first.requestId)
    }

    @Test
    fun pdfReprepareClaimRejectsReplacementPreparedOwnerWithoutDiscardingIt() = runBlocking {
        val builds = AtomicInteger()
        val replacementCreated = AtomicBoolean()
        val repository = repository(
            storage = FakeSecretStorage(SECRET),
            client = scriptedProgressClient(MutableClock(NOW), listOf(emptyList())),
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            },
            preparedLocalTransformForTest = ClinicalPdfPreparedLocalTransformForTest {
                it.copy().also { replacement ->
                    replacementCreated.set(replacement !== it)
                }
            }
        )
        val localRun = repository.prepareLocal(NOW, UTC)
        withTimeout(5_000) { localRun.job.join() }
        val currentIdentity = ClinicalPdfReportIdentity.from(repository.state.value)

        assertThat(currentIdentity.isExportable).isTrue()
        assertThat(replacementCreated.get()).isTrue()
        assertThat(repository.claimClinicalPdfReprepare(currentIdentity))
            .isEqualTo(ClinicalPdfReprepareResult.REJECTED)

        val remoteRun = repository.start(NOW, UTC)
        withTimeout(5_000) { remoteRun.job.join() }
        val acquired = repository.acquireClinicalPdfSourceLease()

        assertThat(acquired).isInstanceOf(ClinicalPdfSourceLeaseResult.Ready::class.java)
        val lease = (acquired as ClinicalPdfSourceLeaseResult.Ready).lease
        try {
            assertThat(lease.buildSource())
                .isInstanceOf(ClinicalPdfSourceCaptureResult.Ready::class.java)
        } finally {
            lease.close()
        }
        assertThat(builds.get()).isEqualTo(1)
    }

    @Test
    fun pdfReprepareClaimRejectsExactIdentityWhileLocalReadyJobIsActive() = runBlocking {
        val auditDao = BlockingAuditLogDao("clinical_report_local_ready")
        val repository = repository(auditLogDao = auditDao)
        val preparation = async(Dispatchers.Default) {
            repository.prepareLocal(NOW, UTC)
        }
        try {
            withTimeout(5_000) { auditDao.blockedInsertEntered.await() }
            val currentIdentity = ClinicalPdfReportIdentity.from(repository.state.value)

            assertThat(repository.state.value)
                .isInstanceOf(ClinicalReportState.LocalReady::class.java)
            assertThat(preparation.isCompleted).isFalse()
            assertThat(currentIdentity.isExportable).isTrue()
            assertThat(repository.claimClinicalPdfReprepare(currentIdentity))
                .isEqualTo(ClinicalPdfReprepareResult.REJECTED)
        } finally {
            auditDao.allowBlockedInsert.complete(Unit)
        }
        val run = withTimeout(5_000) { preparation.await() }
        withTimeout(5_000) { run.job.join() }
        val acquired = repository.acquireClinicalPdfSourceLease()

        assertThat(acquired).isInstanceOf(ClinicalPdfSourceLeaseResult.Ready::class.java)
        val lease = (acquired as ClinicalPdfSourceLeaseResult.Ready).lease
        try {
            assertThat(lease.buildSource())
                .isInstanceOf(ClinicalPdfSourceCaptureResult.Ready::class.java)
        } finally {
            lease.close()
        }
    }

    @Test
    fun lifecycleClearedPayloadCanReprepareAndExportWithoutDiscardingNewPayload() = runBlocking {
        val builds = AtomicInteger()
        val repository = repository(
            datasetFactory = ClinicalReportDatasetFactory { nowTs, _ ->
                builds.incrementAndGet()
                payload(dataset(nowTs))
            }
        )
        val first = repository.prepareLocal(NOW, UTC)
        withTimeout(5_000) { first.job.join() }
        val firstIdentity = ClinicalPdfReportIdentity.from(repository.state.value)
        repository.releasePreparedPayloadWhenIdle()
        val artifactFile = File.createTempFile("clinical-reprepare-", ".ready.pdf")
        val artifact = ClinicalPdfReadyArtifact(
            file = artifactFile,
            sizeBytes = 1L,
            sha256 = "a".repeat(64),
            pageCount = 1,
            createdAt = NOW
        )
        var stagedSources = 0
        val coordinator = ClinicalReportPdfExportCoordinator(
            scope = this,
            acquireSourceLease = repository::acquireClinicalPdfSourceLease,
            sourceDispatcher = Dispatchers.Unconfined,
            stage = { source ->
                source.open().use { cursor -> assertThat(cursor.next()).isNotNull() }
                stagedSources += 1
                ClinicalPdfStageResult.Ready(artifact)
            },
            copy = { _, _ -> ClinicalPdfCopyResult.Failed },
            share = { ClinicalPdfShareResult.Failed },
            discardShare = {},
            currentReportIdentity = {
                ClinicalPdfReportIdentity.from(repository.state.value)
            },
            initialReportIdentity = firstIdentity,
            release = { it.file.delete() }
        )

        try {
            coordinator.begin()
            withTimeout(5_000) {
                coordinator.state.first { it.phase == ClinicalPdfExportUiState.FAILED }
            }
            assertThat(coordinator.state.value.requiresReprepare).isTrue()

            assertThat(repository.claimClinicalPdfReprepare(firstIdentity))
                .isEqualTo(ClinicalPdfReprepareResult.PREPARE_REQUIRED)
            val second = repository.prepareLocal(NOW + 1L, UTC)
            withTimeout(5_000) { second.job.join() }
            val secondIdentity = ClinicalPdfReportIdentity.from(repository.state.value)

            assertThat(second.requestId).isNotEqualTo(first.requestId)
            assertThat(repository.claimClinicalPdfReprepare(firstIdentity))
                .isEqualTo(ClinicalPdfReprepareResult.REJECTED)
            coordinator.onReportIdentityChanged(secondIdentity)
            coordinator.begin()
            withTimeout(5_000) {
                coordinator.state.first { it.phase == ClinicalPdfExportUiState.READY }
            }

            assertThat(stagedSources).isEqualTo(1)
            assertThat(builds.get()).isEqualTo(2)
        } finally {
            coordinator.close()
            artifactFile.delete()
        }
    }

    @Test
    fun appContainerWiresClinicalRepositoryAfterSecureDependenciesAndRecoversInterruptedRows() {
        val source = sourceFile(
            name = "AppContainer.kt",
            packagePath = "io/aaps/copilot/service"
        ).readText()
        val repositoryIndex = source.indexOf("val clinicalReportRepository")

        assertThat(repositoryIndex).isGreaterThan(source.indexOf("val auditLogger"))
        assertThat(repositoryIndex).isGreaterThan(source.indexOf("val openAiCredentialProvider"))
        assertThat(source).contains(
            "ClinicalReportDatasetBuilder(\n        db,\n        glucoseCalibrationRepository"
        )
        assertThat(source).contains("ClinicalAiGatewayFactory(")
        assertThat(source).contains("ClinicalAiProviderId.OPENAI to")
        assertThat(source).contains("ClinicalAiProviderId.ANTHROPIC to")
        assertThat(source).contains("ClinicalAiProviderId.GEMINI to")
        assertThat(source).contains("ClinicalAiProviderId.OPENAI_COMPATIBLE to")
        assertThat(source).contains("ClinicalAiSecretStorageNamespaces.forProvider(providerId)")
        assertThat(source).contains("legacyKeySource = AppSettingsLegacyOpenAiKeySource(settingsStore)")
        assertThat(source).contains("legacyCredentialSource = EmptyLegacyCredentialSource")
        assertThat(source).contains(
            "configSource = ClinicalAiConfigSource {\n" +
                "            settingsStore.settings.first().clinicalAiConfigState\n" +
                "        }"
        )
        assertThat(source).contains("gatewayFactory = clinicalAiGatewayFactory")
        assertThat(source).contains("credentialSource = clinicalAiCredentialSource")
        assertThat(source).contains(
            "val clinicalAiProviderManager = ClinicalAiProviderManager("
        )
        assertThat(source).contains(
            "private val clinicalAiCredentialProvider = ClinicalAiCredentialProvider("
        )
        assertThat(source).contains(
            "private val clinicalAiGatewayFactory = ClinicalAiGatewayFactory("
        )
        assertThat(source).doesNotContain("\n    val clinicalAiCredentialProvider =")
        assertThat(source).doesNotContain("\n    val clinicalAiGatewayFactory =")
        assertThat(source).contains("reportDao = db.clinicalReportDao()")
        assertThat(source).contains("clinicalReportRepository.initialize()")
        assertThat(source).contains(
            "appScope.launch {\n" +
                "            openAiCredentialProvider.ensureMigrated()\n" +
                "            ClinicalAiProviderId.values()\n" +
                "                .filterNot { it == ClinicalAiProviderId.OPENAI }\n" +
                "                .forEach { providerId ->\n" +
                "                    clinicalAiCredentialProvider.ensureMigrated(providerId)\n" +
                "                }\n" +
                "        }"
        )
        assertThat(source).doesNotContain(
            "appScope.launch {\n            clinicalReportRepository.recoverInterrupted()"
        )
    }

    private fun repository(
        dao: FakeClinicalReportDao = FakeClinicalReportDao(),
        storage: FakeSecretStorage = FakeSecretStorage(),
        client: ClinicalOpenAiClient = ClinicalOpenAiClient(),
        datasetFactory: ClinicalReportDatasetFactory =
            ClinicalReportDatasetFactory { nowTs, _ -> payload(dataset(nowTs)) },
        clock: MutableClock = MutableClock(NOW),
        monotonicClock: MutableClock = MutableClock(NOW),
        requestIdProvider: () -> String = sequentialIds(),
        requestIdGenerator: RequestIdGenerator = RequestIdGenerator(requestIdProvider),
        auditLogDao: AuditLogDao = FakeAuditLogDao(),
        preparedLocalTransformForTest: ClinicalPdfPreparedLocalTransformForTest =
            ClinicalPdfPreparedLocalTransformForTest { it }
    ): ClinicalReportRepository {
        val credentialProvider = OpenAiCredentialProvider(
            OpenAiCredentialStore(storage, EmptyLegacyKeySource)
        )
        return ClinicalReportRepository.forTest(
            datasetFactory = datasetFactory,
            credentialProvider = credentialProvider,
            client = client,
            reportDao = dao,
            auditLogger = AuditLogger(auditLogDao, com.google.gson.Gson(), clock::now),
            clock = clock::now,
            monotonicClock = monotonicClock::now,
            dispatcher = Dispatchers.IO,
            requestIdGenerator = requestIdGenerator,
            preparedLocalTransformForTest = preparedLocalTransformForTest
        )
    }

    private fun providerRepository(
        dao: FakeClinicalReportDao = FakeClinicalReportDao(),
        configSource: ClinicalAiConfigSource,
        gatewayFactory: ClinicalAiGatewayFactory,
        credentialSource: ClinicalAiCredentialSource,
        datasetFactory: ClinicalReportDatasetFactory =
            ClinicalReportDatasetFactory { nowTs, _ -> payload(dataset(nowTs)) },
        clock: MutableClock = MutableClock(NOW),
        monotonicClock: MutableClock = MutableClock(NOW),
        requestIdGenerator: RequestIdGenerator = RequestIdGenerator(sequentialIds()),
        auditLogDao: AuditLogDao = FakeAuditLogDao()
    ): ClinicalReportRepository = ClinicalReportRepository.forTest(
        datasetFactory = datasetFactory,
        configSource = configSource,
        gatewayFactory = gatewayFactory,
        credentialSource = credentialSource,
        reportDao = dao,
        auditLogger = AuditLogger(auditLogDao, com.google.gson.Gson(), clock::now),
        clock = clock::now,
        monotonicClock = monotonicClock::now,
        dispatcher = Dispatchers.IO,
        requestIdGenerator = requestIdGenerator
    )

    private fun testClient(
        server: MockWebServer,
        requestByteBudget: Int = 256 * 1_024,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ): ClinicalOpenAiClient = ClinicalOpenAiClient.forTest(
        endpoint = server.url("/v1/responses").toString(),
        requestByteBudget = requestByteBudget,
        maxResponseBytes = 64 * 1_024,
        timeouts = timeouts
    )

    private fun dataset(
        generatedAt: Long,
        days: Int = 2,
        rowsPerDay: Int = 3
    ): ClinicalReportDataset {
        val start = generatedAt - days * DAY_MS
        val rowStep = DAY_MS / rowsPerDay
        val glucose = (0 until days * rowsPerDay).map { index ->
            ClinicalGlucosePoint(start + index * rowStep, 5.5 + (index % 5) * 0.1)
        }
        val therapy = glucose.mapIndexed { index, point ->
            ClinicalTherapyPoint(
                point.ts,
                insulinU = if (index % 2 == 0) 1.0 else null,
                carbsG = if (index % 2 == 1) 10.0 else null
            )
        }
        val targets = glucose.map {
            ClinicalTargetPoint(it.ts, 5.5, 6.5, durationMs = 30 * 60_000L)
        }
        val detail = glucose.filter { it.ts >= generatedAt - DAY_MS }
        val hourly = (0..23).map { ClinicalHourlyMetric(it, 1, 6.0, 6.0) }
        fun summary(periodDays: Int): ClinicalPeriodSummary {
            val expected = periodDays * 288
            return ClinicalPeriodSummary(
            days = periodDays,
            fromTs = generatedAt - periodDays * DAY_MS,
            throughTs = generatedAt,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 10.0,
            timeBelow4Pct = 1.0,
            timeInRangePct = 90.0,
            timeAboveRangePct = 9.0,
            totalInsulinU = 10.0,
            totalCarbsG = 100.0,
            meanTargetMmol = 6.0,
            weekdayPattern = hourly,
            weekendPattern = hourly,
            quality = ClinicalDataQuality(
                expectedBuckets = expected,
                coveredBuckets = expected,
                missingBuckets = 0,
                maxGapMinutes = 5
            )
        )
        }
        return ClinicalReportDataset(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = generatedAt,
            zoneId = UTC.id,
            detail24h = ClinicalDetailWindow(
                generatedAt - DAY_MS,
                generatedAt,
                detail,
                detail,
                therapy.filter { it.ts >= generatedAt - DAY_MS },
                targets.filter { it.ts >= generatedAt - DAY_MS },
                detail.map {
                    ClinicalForecastPoint(it.ts, 30, it.mmol + 0.2, it.mmol - 0.3, it.mmol + 0.7)
                },
                detail.map { ClinicalTelemetryPoint(it.ts, "iob_units", 1.0, "OK") }
            ),
            glucose7d = glucose,
            therapy7d = therapy,
            targets7d = targets,
            glucose30d = glucose,
            therapy30d = therapy,
            targets30d = targets,
            summary7d = summary(7),
            summary30d = summary(30),
            summary24h = summary(1)
        )
    }

    private fun payload(dataset: ClinicalReportDataset): ClinicalReportPayload {
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        return ClinicalReportPayload(
            dataset,
            compact,
            ClinicalReportDatasetBuilder.sha256(compact)
        )
    }

    private fun successResponse(
        output: String = reportObject().toString()
    ): MockResponse = MockResponse().setBody(
        JsonObject().apply {
            addProperty("status", "completed")
            addProperty("model", ClinicalOpenAiClient.DEFAULT_MODEL)
            addProperty("system_fingerprint", "fp_test")
            addProperty("output_text", output)
        }.toString()
    )

    private fun chunkSuccessResponse(): MockResponse = MockResponse().setBody(
        JsonObject().apply {
            addProperty("status", "completed")
            addProperty("model", ClinicalOpenAiClient.DEFAULT_MODEL)
            addProperty("system_fingerprint", "fp_test")
            add(
                "output_text",
                com.google.gson.JsonPrimitive(
                    """
                    {
                      "dataQuality":["COMPLETE"],
                      "findings":[{
                        "topic":"GLUCOSE_STABILITY",
                        "period":"LAST_24_HOURS",
                        "direction":"STABLE",
                        "confidence":"MEDIUM",
                        "timeBand":"OVERNIGHT",
                        "evidenceMetric":"TIME_IN_RANGE_PCT",
                        "evidenceValue":90.0
                      }]
                    }
                    """.trimIndent()
                )
            )
        }.toString()
    )

    private fun reportObject(): JsonObject = JsonParser.parseString(
        """
        {
          "summary7dStatus":"STABLE",
          "summary30dStatus":"MIXED",
          "dataQuality":["COMPLETE"],
          "patterns":[{
            "topic":"GLUCOSE_STABILITY",
            "period":"LAST_7_DAYS",
            "direction":"STABLE",
            "confidence":"MEDIUM",
            "timeBand":"OVERNIGHT",
            "evidenceMetric":"TIME_IN_RANGE_PCT",
            "evidenceValue":90.0
          }],
          "safetyObservations":["NONE_IDENTIFIED"],
          "recommendations":[{
            "careTeamDiscussionTopic":"SENSOR_RELIABILITY",
            "priority":"MEDIUM",
            "evidenceFindingIndices":[0],
            "period":"LAST_7_DAYS"
          }],
          "careTeamQuestions":["SENSOR_RELIABILITY_CONTEXT"]
        }
        """.trimIndent()
    ).asJsonObject

    private fun advisoryReport() = ClinicalAdvisoryReport(
        summary7dStatus = ClinicalSummaryStatus.STABLE,
        summary30dStatus = ClinicalSummaryStatus.MIXED,
        dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
        patterns = emptyList(),
        safetyObservations = listOf(ClinicalSafetyObservation.NONE_IDENTIFIED),
        recommendations = emptyList(),
        careTeamQuestions = emptyList()
    )

    private fun scriptedProgressClient(
        monotonicClock: MutableClock,
        scripts: List<List<Pair<Long, ClinicalOpenAiProgress>>>
    ): ClinicalOpenAiClient = object : ClinicalOpenAiClient() {
        private val calls = AtomicInteger()

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credentialProvider: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            scripts[calls.getAndIncrement()].forEach { (timestamp, event) ->
                monotonicClock.set(timestamp)
                progress.onProgress(event)
            }
            return ClinicalOpenAiResult(
                report = advisoryReport(),
                metadata = ClinicalOpenAiMetadata(
                    model = ClinicalOpenAiClient.DEFAULT_MODEL,
                    requestedModel = ClinicalOpenAiClient.DEFAULT_MODEL,
                    systemFingerprint = null,
                    schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
                    schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
                    datasetSchemaVersion = payload.dataset.schemaVersion,
                    requestHash = payload.sha256,
                    chunkCount = 1,
                    usedSynthesis = false,
                    durationMs = 1L
                )
            )
        }
    }

    private fun completionAuditJson(
        auditDao: RecordingAuditLogDao,
        index: Int
    ): JsonObject = JsonParser.parseString(
        auditDao.records.filter { it.message == "clinical_report_complete" }[index].metadataJson
    ).asJsonObject

    private fun stageDurationValues(audit: JsonObject): List<Long> = listOf(
        audit.get("preparingDurationMs").asLong,
        audit.get("analyzingDurationMs").asLong,
        audit.get("reducingDurationMs").asLong,
        audit.get("synthesizingDurationMs").asLong,
        audit.get("validatingDurationMs").asLong
    )

    private fun assertStageAuditIsBoundedAndSanitized(auditDao: RecordingAuditLogDao) {
        val serialized = auditDao.records.joinToString {
            "${it.message}:${it.metadataJson}"
        }
        listOf(
            "\"glucoseSeries\"",
            "\"d24\"",
            "\"g7\"",
            "\"g30\"",
            "\"canonicalJson\"",
            "\"rootDigest\"",
            SECRET,
            "13.789"
        ).forEach { forbidden ->
            assertThat(serialized).doesNotContain(forbidden)
        }
    }

    private fun entity(
        id: String,
        status: ClinicalReportStatus,
        createdAt: Long
    ) = ClinicalReportEntity(
        requestId = id,
        status = status.name,
        requestedFromTs = NOW - 30 * DAY_MS,
        requestedThroughTs = NOW,
        requestHash = "hash",
        coverageJson = """{"coverage":true}""",
        localSummaryJson = """{"summary":true}""",
        responseJson = null,
        renderedText = null,
        model = null,
        provider = ClinicalAiProviderId.OPENAI.name,
        schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
        createdAt = createdAt,
        completedAt = null,
        sanitizedError = null
    )

    private fun assertNoOutboundPayload(entity: ClinicalReportEntity) {
        val persisted = listOfNotNull(
            entity.coverageJson,
            entity.localSummaryJson,
            entity.responseJson,
            entity.renderedText,
            entity.sanitizedError
        ).joinToString()
        assertThat(persisted).doesNotContain("\"d24\"")
        assertThat(persisted).doesNotContain("\"g7\"")
        assertThat(persisted).doesNotContain("\"g30\"")
        assertThat(persisted).doesNotContain("\"compactJson\"")
    }

    private fun sourceFile(
        name: String,
        packagePath: String = "io/aaps/copilot/data/repository"
    ): File {
        val candidates = listOf(
            File("src/main/kotlin/$packagePath/$name"),
            File("app/src/main/kotlin/$packagePath/$name"),
            File("android-app/app/src/main/kotlin/$packagePath/$name")
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate $name")
    }

    private fun sequentialIds(): () -> String {
        val next = AtomicInteger()
        return { "request-${next.incrementAndGet()}" }
    }

    private class MutableClock(@Volatile private var value: Long) {
        fun now(): Long = value
        fun set(newValue: Long) {
            value = newValue
        }
    }

    private class MutableClinicalAiConfigSource(
        @Volatile var state: ClinicalAiConfigState
    ) : ClinicalAiConfigSource {
        val reads = AtomicInteger()
        @Volatile
        var beforeRead: suspend () -> Unit = {}

        override suspend fun currentState(): ClinicalAiConfigState {
            reads.incrementAndGet()
            beforeRead()
            return state
        }
    }

    private class RecordingClinicalAiCredentialSource(
        private val failure: Exception? = null
    ) : ClinicalAiCredentialSource {
        val requestedProviders = CopyOnWriteArrayList<ClinicalAiProviderId>()

        override suspend fun requireCredential(providerId: ClinicalAiProviderId): String {
            requestedProviders += providerId
            failure?.let { throw it }
            return "credential-${providerId.name}"
        }
    }

    private class RecordingGatewayFixture {
        val builtConfigs = CopyOnWriteArrayList<ClinicalAiProviderConfig>()
        val analyzedConfigs = CopyOnWriteArrayList<ClinicalAiProviderConfig>()
        val observedCredentials = CopyOnWriteArrayList<String>()
        val networkCalls = AtomicInteger()
        val factory = ClinicalAiGatewayFactory(
            ClinicalAiProviderId.values().associateWith {
                ClinicalAiGatewayBuilder { config ->
                    builtConfigs += config
                    RecordingClinicalAiGateway(
                        config = config,
                        onCredential = { observedCredentials += it },
                        onNetwork = { networkCalls.incrementAndGet() },
                        onAnalyze = { analyzedConfigs += config }
                    )
                }
            }
        )
    }

    private class RecordingClinicalAiGateway(
        private val config: ClinicalAiProviderConfig,
        private val responseProviderId: ClinicalAiProviderId = config.providerId,
        private val onCredential: (String) -> Unit = {},
        private val onNetwork: () -> Unit = {},
        private val onAnalyze: suspend () -> Unit = {}
    ) : ClinicalAiGateway {
        override val providerId: ClinicalAiProviderId = config.providerId
        override val modelId: String = config.modelId

        override fun capabilities() = ClinicalAiCapabilities(
            maxRequestBytes = 256 * 1_024,
            maxResponseBytes = 64 * 1_024,
            maxOutputTokens = 4_096,
            supportsStrictStructuredOutput = true
        )

        override suspend fun testConnection(credential: String) = ClinicalAiConnectionTest(
            providerId = providerId,
            modelId = modelId,
            status = ClinicalAiConnectionStatus.SUCCESS
        )

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credential: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            val selectedCredential = credential()
            onCredential(selectedCredential)
            onNetwork()
            onAnalyze()
            return ClinicalOpenAiResult(
                report = ClinicalAdvisoryReport(
                    summary7dStatus = ClinicalSummaryStatus.STABLE,
                    summary30dStatus = ClinicalSummaryStatus.STABLE,
                    dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
                    patterns = emptyList(),
                    safetyObservations = listOf(
                        ClinicalSafetyObservation.NONE_IDENTIFIED
                    ),
                    recommendations = emptyList(),
                    careTeamQuestions = emptyList()
                ),
                metadata = ClinicalOpenAiMetadata(
                    model = modelId,
                    requestedModel = modelId,
                    systemFingerprint = null,
                    schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
                    schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
                    datasetSchemaVersion = payload.dataset.schemaVersion,
                    requestHash = payload.sha256,
                    chunkCount = 1,
                    usedSynthesis = false,
                    providerId = responseProviderId,
                    requestedProviderId = responseProviderId
                )
            )
        }

        override suspend fun analyzeAlert(
            context: AlertAiCanonicalContext,
            credential: suspend () -> String,
            networkLease: AlertAiValidatedNetworkLease
        ): AlertAiGatewayResult = throw UnsupportedOperationException()
    }

    private class ControlledClinicalOpenAiClient(
        private val blockSecondCall: Boolean
    ) : ClinicalOpenAiClient() {
        val calls = AtomicInteger()
        val callbacks = CopyOnWriteArrayList<ClinicalOpenAiProgressCallback>()
        val firstCallEntered = CompletableDeferred<Unit>()
        val secondCallEntered = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()
        val releaseSecondCall = CompletableDeferred<Unit>()

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credentialProvider: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            callbacks += progress
            return when (calls.incrementAndGet()) {
                1 -> {
                    firstCallEntered.complete(Unit)
                    try {
                        releaseFirstCall.await()
                    } catch (_: CancellationException) {
                        return result(payload)
                    }
                    result(payload)
                }

                2 -> {
                    secondCallEntered.complete(Unit)
                    if (blockSecondCall) releaseSecondCall.await()
                    result(payload)
                }

                else -> error("Unexpected clinical client call")
            }
        }

        private fun result(payload: ClinicalReportPayload) = ClinicalOpenAiResult(
            report = ClinicalAdvisoryReport(
                summary7dStatus = ClinicalSummaryStatus.STABLE,
                summary30dStatus = ClinicalSummaryStatus.STABLE,
                dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
                patterns = emptyList(),
                safetyObservations = listOf(ClinicalSafetyObservation.NONE_IDENTIFIED),
                recommendations = emptyList(),
                careTeamQuestions = emptyList()
            ),
            metadata = ClinicalOpenAiMetadata(
                model = ClinicalOpenAiClient.DEFAULT_MODEL,
                requestedModel = ClinicalOpenAiClient.DEFAULT_MODEL,
                systemFingerprint = null,
                schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
                schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
                datasetSchemaVersion = payload.dataset.schemaVersion,
                requestHash = payload.sha256,
                chunkCount = 1,
                usedSynthesis = false
            )
        )
    }

    private class CancellationBarrierClinicalOpenAiClient : ClinicalOpenAiClient() {
        val calls = AtomicInteger()
        val callEntered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val allowCancellationToFinish = CompletableDeferred<Unit>()

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credentialProvider: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            calls.incrementAndGet()
            callEntered.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    cancellationObserved.complete(Unit)
                    allowCancellationToFinish.await()
                }
                throw cancelled
            }
        }
    }

    private class CancellationToFailureClinicalOpenAiClient : ClinicalOpenAiClient() {
        val callEntered = CompletableDeferred<Unit>()

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credentialProvider: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            callEntered.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                throw IllegalStateException("client converted cancellation")
            }
        }
    }

    private class FakeClinicalReportDao : ClinicalReportDao {
        private val state = MutableStateFlow<List<ClinicalReportEntity>>(emptyList())
        val history = CopyOnWriteArrayList<ClinicalReportEntity>()
        val updateArguments = CopyOnWriteArrayList<ClinicalReportEntity>()
        val insertInitialCalls = AtomicInteger()
        val updateIfStatusInCalls = AtomicInteger()
        val markInterruptedCalls = AtomicInteger()
        val acknowledgeCalls = AtomicInteger()
        val recoveryDaoCalls = CopyOnWriteArrayList<String>()
        var afterLatestSnapshot: suspend () -> Unit = {}
        var commitThenCancelNextInsert = false
        var failNextAcknowledgement = false

        override suspend fun insertInitial(entity: ClinicalReportEntity): Long {
            insertInitialCalls.incrementAndGet()
            if (byId(entity.requestId) != null) return -1L
            upsert(entity)
            if (commitThenCancelNextInsert) {
                commitThenCancelNextInsert = false
                throw CancellationException("insert committed before cancellation")
            }
            return 1L
        }

        suspend fun upsert(entity: ClinicalReportEntity) {
            history += entity
            state.value = (state.value.filterNot { it.requestId == entity.requestId } + entity)
                .sortedWith(compareByDescending<ClinicalReportEntity> { it.createdAt }
                    .thenByDescending { it.requestId })
        }

        override suspend fun updateIfStatusIn(
            entity: ClinicalReportEntity,
            expectedStatuses: List<String>
        ): Int {
            updateIfStatusInCalls.incrementAndGet()
            updateArguments += entity
            val current = byId(entity.requestId) ?: return 0
            if (current.status !in expectedStatuses) return 0
            upsert(entity)
            return 1
        }

        override suspend fun updateRowIfStatusIn(
            requestId: String,
            status: String,
            requestedFromTs: Long,
            requestedThroughTs: Long,
            requestHash: String,
            coverageJson: String,
            localSummaryJson: String,
            responseJson: String?,
            renderedText: String?,
            model: String?,
            provider: String,
            schemaVersion: Int,
            createdAt: Long,
            completedAt: Long?,
            sanitizedError: String?,
            expectedStatuses: List<String>
        ): Int = updateIfStatusIn(
            entity = ClinicalReportEntity(
                requestId = requestId,
                status = status,
                requestedFromTs = requestedFromTs,
                requestedThroughTs = requestedThroughTs,
                requestHash = requestHash,
                coverageJson = coverageJson,
                localSummaryJson = localSummaryJson,
                responseJson = responseJson,
                renderedText = renderedText,
                model = model,
                provider = provider,
                schemaVersion = schemaVersion,
                createdAt = createdAt,
                completedAt = completedAt,
                sanitizedError = sanitizedError
            ),
            expectedStatuses = expectedStatuses
        )

        override suspend fun latest(limit: Int): List<ClinicalReportEntity> {
            val snapshot = state.value.take(limit)
            afterLatestSnapshot()
            return snapshot
        }

        override suspend fun activeForRecovery(
            statuses: List<String>,
            limit: Int
        ): List<ClinicalReportEntity> {
            recoveryDaoCalls += "activeForRecovery"
            val snapshot = state.value.filter { it.status in statuses }.take(limit)
            afterLatestSnapshot()
            return snapshot
        }

        override suspend fun latestUnknownOutcomeGuard(
            statuses: List<String>,
            sanitizedError: String
        ): ClinicalReportEntity? {
            recoveryDaoCalls += "latestUnknownOutcomeGuard"
            return state.value.firstOrNull {
                it.status in statuses && it.sanitizedError == sanitizedError
            }
        }

        override suspend fun refreshUnknownOutcomeLocalIfStatusIn(
            requestId: String,
            expectedStatuses: List<String>,
            sanitizedError: String,
            requestedFromTs: Long,
            requestedThroughTs: Long,
            requestHash: String,
            coverageJson: String,
            localSummaryJson: String,
            schemaVersion: Int
        ): Int {
            val current = byId(requestId) ?: return 0
            if (
                current.status !in expectedStatuses ||
                current.sanitizedError != sanitizedError
            ) {
                return 0
            }
            upsert(
                current.copy(
                    requestedFromTs = requestedFromTs,
                    requestedThroughTs = requestedThroughTs,
                    requestHash = requestHash,
                    coverageJson = coverageJson,
                    localSummaryJson = localSummaryJson,
                    schemaVersion = schemaVersion
                )
            )
            return 1
        }

        override suspend fun acknowledgeUnknownOutcomeIfStatusIn(
            requestId: String,
            expectedStatuses: List<String>,
            sanitizedError: String,
            acknowledgedStatus: String
        ): Int {
            acknowledgeCalls.incrementAndGet()
            if (failNextAcknowledgement) {
                failNextAcknowledgement = false
                return 0
            }
            val current = byId(requestId) ?: return 0
            if (
                current.status !in expectedStatuses ||
                current.sanitizedError != sanitizedError
            ) {
                return 0
            }
            upsert(current.copy(status = acknowledgedStatus))
            return 1
        }

        override suspend fun deleteAcknowledgedUnknownOutcome(
            requestId: String,
            acknowledgedStatus: String,
            sanitizedError: String
        ): Int {
            val current = byId(requestId) ?: return 0
            if (
                current.status != acknowledgedStatus ||
                current.sanitizedError != sanitizedError
            ) {
                return 0
            }
            state.value = state.value.filterNot { it.requestId == requestId }
            return 1
        }

        override suspend fun byId(requestId: String): ClinicalReportEntity? =
            state.value.firstOrNull { it.requestId == requestId }

        override fun observeLatest(limit: Int): Flow<List<ClinicalReportEntity>> = state

        override suspend fun redactUnknownOutcomeGuardsOlderThan(
            olderThan: Long,
            protectedStatuses: List<String>,
            protectedSanitizedError: String
        ): Int {
            recoveryDaoCalls += "redactUnknownOutcomeGuardsOlderThan"
            val candidates = state.value.filter {
                it.createdAt < olderThan &&
                    it.status in protectedStatuses &&
                    it.sanitizedError == protectedSanitizedError
            }
            candidates.forEach { entity ->
                upsert(
                    entity.copy(
                        requestedFromTs = 0L,
                        requestedThroughTs = 0L,
                        requestHash = "",
                        coverageJson = "{}",
                        localSummaryJson = "{}",
                        responseJson = null,
                        renderedText = null,
                        model = null
                    )
                )
            }
            return candidates.size
        }

        override suspend fun deleteOlderThan(
            olderThan: Long,
            protectedStatuses: List<String>,
            protectedSanitizedError: String
        ): Int {
            recoveryDaoCalls += "deleteOlderThan"
            val before = state.value
            state.value = before.filter {
                it.createdAt >= olderThan ||
                    (
                        it.status in protectedStatuses &&
                            it.sanitizedError == protectedSanitizedError
                    )
            }
            return before.size - state.value.size
        }

        override suspend fun markInterruptedIfStatusIn(
            requestId: String,
            expectedStatuses: List<String>,
            interruptedStatus: String,
            completedAt: Long,
            sanitizedError: String
        ): Int {
            recoveryDaoCalls += "markInterruptedIfStatusIn"
            markInterruptedCalls.incrementAndGet()
            val current = byId(requestId) ?: return 0
            if (current.status !in expectedStatuses) return 0
            upsert(
                current.copy(
                    status = interruptedStatus,
                    completedAt = completedAt,
                    sanitizedError = sanitizedError
                )
            )
            return 1
        }

        suspend fun replaceStatus(requestId: String, status: ClinicalReportStatus) {
            val current = checkNotNull(byId(requestId))
            upsert(
                current.copy(
                    status = status.name,
                    completedAt = NOW,
                    sanitizedError = null
                )
            )
        }
    }

    private class FakeAuditLogDao : AuditLogDao {
        override suspend fun insert(entity: AuditLogEntity) = Unit
        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()
        override suspend fun recentByMessage(
            message: String,
            sinceTs: Long,
            limit: Int
        ): List<AuditLogEntity> = emptyList()

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0
        override suspend fun deleteOlderThanInfoMessages(
            olderThan: Long,
            messages: List<String>
        ): Int = 0
    }

    private class RecordingAuditLogDao : AuditLogDao {
        val records = CopyOnWriteArrayList<AuditLogEntity>()

        override suspend fun insert(entity: AuditLogEntity) {
            records += entity
        }

        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()

        override suspend fun recentByMessage(
            message: String,
            sinceTs: Long,
            limit: Int
        ): List<AuditLogEntity> = records
            .filter { it.message == message && it.timestamp >= sinceTs }
            .take(limit)

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteOlderThanInfoMessages(
            olderThan: Long,
            messages: List<String>
        ): Int = 0
    }

    private class BlockingAuditLogDao(
        private val blockedMessage: String
    ) : AuditLogDao {
        val blockedInsertEntered = CompletableDeferred<Unit>()
        val allowBlockedInsert = CompletableDeferred<Unit>()

        override suspend fun insert(entity: AuditLogEntity) {
            if (entity.message == blockedMessage) {
                blockedInsertEntered.complete(Unit)
                allowBlockedInsert.await()
            }
        }

        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()

        override suspend fun recentByMessage(
            message: String,
            sinceTs: Long,
            limit: Int
        ): List<AuditLogEntity> = emptyList()

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteOlderThanInfoMessages(
            olderThan: Long,
            messages: List<String>
        ): Int = 0
    }

    private class FakeSecretStorage(initial: String? = null) : SecretStorage {
        private var active = initial
        private var pending: String? = null
        private var pendingLegacyCleanupIntent: String? = null
        private var legacyCleanupIntent: String? = null
        val readCount = AtomicInteger()
        var onRead: () -> Unit = {}
        private var deletionPending = false
        private var pendingDeletionLegacyCleanupIntent: String? = null
        private var rollbackPresent = false
        private var rollbackActive: String? = null
        private var rollbackDeletionPending = false
        private var rollbackDeletionLegacyCleanupIntent: String? = null
        private var rollbackLegacyCleanupIntent: String? = null

        override suspend fun stagePending(value: String, legacyCleanupExpected: String?) {
            pending = value
            pendingLegacyCleanupIntent = legacyCleanupExpected
        }

        override suspend fun readPending(): String? = pending

        override suspend fun readPendingLegacyCleanupIntent(): String? =
            pendingLegacyCleanupIntent

        override suspend fun commitPending() {
            rollbackPresent = true
            rollbackActive = active
            rollbackDeletionPending = deletionPending
            rollbackDeletionLegacyCleanupIntent = pendingDeletionLegacyCleanupIntent
            rollbackLegacyCleanupIntent = legacyCleanupIntent
            active = pending
            pending = null
            legacyCleanupIntent = pendingLegacyCleanupIntent
            pendingLegacyCleanupIntent = null
            deletionPending = false
            pendingDeletionLegacyCleanupIntent = null
        }

        override suspend fun rollbackPromotion() {
            if (!rollbackPresent) return
            active = rollbackActive
            deletionPending = rollbackDeletionPending
            pendingDeletionLegacyCleanupIntent = rollbackDeletionLegacyCleanupIntent
            legacyCleanupIntent = rollbackLegacyCleanupIntent
            rollbackPresent = false
            rollbackActive = null
            rollbackDeletionPending = false
            rollbackDeletionLegacyCleanupIntent = null
            rollbackLegacyCleanupIntent = null
        }

        override suspend fun discardRollback() {
            rollbackPresent = false
            rollbackActive = null
            rollbackDeletionPending = false
            rollbackDeletionLegacyCleanupIntent = null
            rollbackLegacyCleanupIntent = null
        }

        override suspend fun clearPending() {
            pending = null
            pendingLegacyCleanupIntent = null
        }

        override suspend fun readLegacyCleanupIntent(): String? = legacyCleanupIntent

        override suspend fun clearLegacyCleanupIntent() {
            legacyCleanupIntent = null
        }

        override suspend fun markDeletionPending(legacyCleanupExpected: String?) {
            deletionPending = true
            pendingDeletionLegacyCleanupIntent = legacyCleanupExpected
        }

        override suspend fun isDeletionPending(): Boolean = deletionPending

        override suspend fun readPendingDeletionLegacyCleanupIntent(): String? =
            pendingDeletionLegacyCleanupIntent

        override suspend fun read(): String? {
            readCount.incrementAndGet()
            onRead()
            return active
        }

        override suspend fun clear() {
            active = null
            pending = null
            pendingLegacyCleanupIntent = null
            legacyCleanupIntent = null
            rollbackPresent = false
            rollbackActive = null
            rollbackDeletionPending = false
            rollbackDeletionLegacyCleanupIntent = null
            rollbackLegacyCleanupIntent = null
            deletionPending = true
            pendingDeletionLegacyCleanupIntent = null
        }
    }

    private object EmptyLegacyKeySource : LegacyOpenAiKeySource {
        override suspend fun read(): String? = null
        override suspend fun clearIfMatches(expected: String): Boolean = false
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val NOW: Long = ZonedDateTime.of(2026, 7, 26, 12, 0, 0, 0, UTC)
            .toInstant()
            .toEpochMilli()
        const val SECRET = "test-only-secret"
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}

private fun ClinicalReportState.requestId(): String = when (this) {
    ClinicalReportState.Idle -> ""
    is ClinicalReportState.Building -> requestId
    is ClinicalReportState.LocalReady -> requestId
    is ClinicalReportState.Uploading -> requestId
    is ClinicalReportState.Complete -> requestId
    is ClinicalReportState.Failed -> requestId
    is ClinicalReportState.Cancelled -> requestId
}
