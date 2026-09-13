package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiConfigInvalidReason
import io.aaps.copilot.config.ClinicalAiConfigState
import io.aaps.copilot.config.ClinicalAiModelCatalog
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.config.executionIdentity
import io.aaps.copilot.service.ClinicalAiProviderCredentialState
import io.aaps.copilot.ui.foundation.screens.ClinicalAiConnectionTestPhaseUi
import io.aaps.copilot.ui.foundation.screens.ClinicalAiConnectionTestUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalReportDisclosureUi
import io.aaps.copilot.ui.foundation.screens.ClinicalReportRetryFeedbackUi
import io.aaps.copilot.ui.foundation.screens.ClinicalReportUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalAiSettingsValidationErrorUi
import io.aaps.copilot.ui.foundation.screens.ClinicalAiSettingsUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainViewModelClinicalAiSettingsTest {

    @Test
    fun automaticCauseAnalysisSelectionIsPartOfClinicalAiUiState() {
        val state = ClinicalAiSettingsUiState(
            effectiveConfig = ClinicalAiProviderConfig.defaultOpenAi(),
            automaticCauseAnalysisEnabled = false
        )

        assertThat(state.automaticCauseAnalysisEnabled).isFalse()
        assertThat(state.labels.automaticCauseAnalysis).isEqualTo("Automatic cause analysis")
    }

    @Test
    fun nativeProviderSelectionKeepsCatalogModelOrUsesRecommendedDefault() {
        val compatibleAnthropicModel = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.OPENAI,
            modelId = ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.ANTHROPIC).id
        )

        assertThat(
            selectClinicalAiNativeConfig(
                providerId = ClinicalAiProviderId.ANTHROPIC,
                currentConfig = compatibleAnthropicModel
            ).modelId
        ).isEqualTo(compatibleAnthropicModel.modelId)

        assertThat(
            selectClinicalAiNativeConfig(
                providerId = ClinicalAiProviderId.GEMINI,
                currentConfig = compatibleAnthropicModel
            ).modelId
        ).isEqualTo(
            ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.GEMINI).id
        )
    }

    @Test
    fun compatibleDraftNormalizesOnlyAfterModelAndEndpointValidate() {
        val draft = ClinicalAiSettingsDraft(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            customModelSelected = true,
            modelDraft = "local-model",
            endpointDraft = "https://models.example/v1/",
            protocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
        )

        val resolved = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.UnconfiguredDefault(
                ClinicalAiProviderConfig.defaultOpenAi()
            ),
            draft = draft
        )

        assertThat(resolved.persistableConfig).isEqualTo(
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local-model",
                endpoint = "https://models.example/v1/",
                compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
            )
        )
        assertThat(resolved.validationError).isNull()
        assertThat(resolved.selectedProvider).isEqualTo(
            ClinicalAiProviderId.OPENAI_COMPATIBLE
        )
        assertThat(resolved.customModelSelected).isTrue()
    }

    @Test
    fun invalidDraftAndStoredReasonsMapToSanitizedUiErrors() {
        val invalidEndpoint = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig.defaultOpenAi()
            ),
            draft = ClinicalAiSettingsDraft(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                customModelSelected = true,
                modelDraft = "local-model",
                endpointDraft = "http://remote.example/v1",
                protocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )
        assertThat(invalidEndpoint.persistableConfig).isNull()
        assertThat(invalidEndpoint.validationError).isEqualTo(
            ClinicalAiSettingsValidationErrorUi.INVALID_ENDPOINT
        )

        val invalidStored = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Invalid(
                ClinicalAiConfigInvalidReason.UNKNOWN_PROTOCOL
            ),
            draft = null
        )
        assertThat(invalidStored.persistableConfig).isNull()
        assertThat(invalidStored.validationError).isEqualTo(
            ClinicalAiSettingsValidationErrorUi.INVALID_CONFIGURATION
        )
    }

    @Test
    fun draftInputIsBoundedBeforeEnteringViewModelState() {
        val bounded = ClinicalAiSettingsDraft.compatible(
            modelDraft = "m".repeat(1_000),
            endpointDraft = "https://example.test/" + "p".repeat(10_000),
            protocol = OpenAiCompatibleProtocol.RESPONSES
        )

        assertThat(bounded.modelDraft.length).isAtMost(256)
        assertThat(bounded.endpointDraft.length).isAtMost(2_048)
    }

    @Test
    fun remotePreflightDistinguishesReadyInvalidAndPendingWithoutDispatch() = runTest {
        val persisted = ClinicalAiProviderConfig.defaultOpenAi()
        var draft: ClinicalAiSettingsDraft? = null
        var pending = false
        var saveFailed = false
        val preflight = ClinicalAiRemoteSendPreflight(
            persistedState = { ClinicalAiConfigState.Valid(persisted) },
            draftState = { draft },
            persistencePending = { pending },
            persistenceFailed = { saveFailed }
        )

        assertThat(preflight.check()).isEqualTo(ClinicalAiRemotePreflightResult.READY)

        draft = ClinicalAiSettingsDraft.compatible(
            modelDraft = "local-model",
            endpointDraft = "http://remote.example/v1",
            protocol = OpenAiCompatibleProtocol.RESPONSES
        )
        assertThat(preflight.check()).isEqualTo(
            ClinicalAiRemotePreflightResult.INVALID_CONFIGURATION
        )

        draft = null
        pending = true
        assertThat(preflight.check()).isEqualTo(
            ClinicalAiRemotePreflightResult.PERSISTENCE_PENDING
        )

        pending = false
        saveFailed = true
        assertThat(preflight.check()).isEqualTo(
            ClinicalAiRemotePreflightResult.PERSISTENCE_FAILED
        )
    }

    @Test
    fun disclosureSendGuardDispatchesMatchingPersistedSnapshotExactlyOnce() = runTest {
        val persistedConfig = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "private-model",
            endpoint = "https://gateway.example.com/private/path",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        )
        val confirmed = ClinicalReportDisclosureUi(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            model = "private-model",
            endpointHost = "gateway.example.com",
            configIdentity = persistedConfig.executionIdentity()
        )
        var sends = 0
        var mismatches = 0
        val guard = ClinicalReportDisclosureSendGuard(
            persistedState = {
                ClinicalAiConfigState.Valid(persistedConfig)
            },
            onMismatch = { mismatches += 1 }
        )

        val dispatched = guard.sendIfMatches(confirmed) { sends += 1 }

        assertThat(dispatched).isTrue()
        assertThat(sends).isEqualTo(1)
        assertThat(mismatches).isEqualTo(0)
    }

    @Test
    fun disclosureSendGuardRejectsProviderModelAndEndpointHostChanges() = runTest {
        val disclosedConfig = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "private-model",
            endpoint = "https://gateway.example.com/v1",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        )
        val confirmed = ClinicalReportDisclosureUi(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            model = "private-model",
            endpointHost = "gateway.example.com",
            configIdentity = disclosedConfig.executionIdentity()
        )
        val persistedStates = listOf(
            ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig(
                    providerId = ClinicalAiProviderId.GEMINI,
                    modelId = "private-model"
                )
            ),
            ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig.normalized(
                    providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                    modelId = "changed-model",
                    endpoint = "https://gateway.example.com/v1",
                    compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
                )
            ),
            ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig.normalized(
                    providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                    modelId = "private-model",
                    endpoint = "https://changed.example.com/v1",
                    compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
                )
            ),
            ClinicalAiConfigState.Invalid(
                ClinicalAiConfigInvalidReason.INVALID_ENDPOINT
            )
        )
        var sends = 0
        var mismatches = 0

        persistedStates.forEach { persisted ->
            val guard = ClinicalReportDisclosureSendGuard(
                persistedState = { persisted },
                onMismatch = { mismatches += 1 }
            )

            assertThat(guard.sendIfMatches(confirmed) { sends += 1 }).isFalse()
        }

        assertThat(sends).isEqualTo(0)
        assertThat(mismatches).isEqualTo(persistedStates.size)
    }

    @Test
    fun persistenceHelperWritesOnlyLocallyValidResolution() = runTest {
        val writes = mutableListOf<ClinicalAiProviderConfig>()
        val validConfig = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )
        val valid = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig.defaultOpenAi()
            ),
            draft = ClinicalAiSettingsDraft.bounded(
                providerId = validConfig.providerId,
                customModelSelected = false,
                modelDraft = validConfig.modelId,
                endpointDraft = "",
                protocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )
        val invalid = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Valid(validConfig),
            draft = ClinicalAiSettingsDraft.compatible(
                modelDraft = "",
                endpointDraft = "https://models.example/v1",
                protocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )

        assertThat(
            persistClinicalAiSettingsResolution(valid) { writes += it }
        ).isTrue()
        assertThat(
            persistClinicalAiSettingsResolution(invalid) { writes += it }
        ).isFalse()
        assertThat(writes).containsExactly(validConfig)
    }

    @Test
    fun unusableClinicalConfigDisablesBothRemoteActionsAndDismissesRetry() {
        val state = ClinicalReportUiState(
            canSend = true,
            canRequestGuardedRetry = true,
            showRetryConfirmation = true,
            retryFeedback = ClinicalReportRetryFeedbackUi.PENDING
        )

        val blocked = state.withClinicalAiRemoteActionsEnabled(false)

        assertThat(blocked.canSend).isFalse()
        assertThat(blocked.canRequestGuardedRetry).isFalse()
        assertThat(blocked.showRetryConfirmation).isFalse()
        assertThat(blocked.retryFeedback).isNull()
        assertThat(blocked.pointsToSettings).isTrue()
    }

    @Test
    fun persistenceFailureRetainsLatestDraftAndExposesSaveError() = runTest {
        val draft = ClinicalAiSettingsDraft.compatible(
            modelDraft = "local-model",
            endpointDraft = "https://clinical.example/v1",
            protocol = OpenAiCompatibleProtocol.RESPONSES
        )
        val started = ClinicalAiPersistenceState().begin(
            draft = draft,
            persistable = true
        )
        val config = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig.defaultOpenAi()
            ),
            draft = draft
        ).persistableConfig!!
        var observedPersistedState = false
        val result = ClinicalAiSettingsPersistenceCoordinator().persist(
            version = started.version,
            config = config,
            currentVersion = { started.version },
            write = { error("simulated settings write failure") },
            persistedState = {
                observedPersistedState = true
                ClinicalAiConfigState.Valid(config)
            }
        )

        val failed = started.complete(
            version = started.version,
            result = result
        )

        assertThat(result).isEqualTo(ClinicalAiPersistenceResult.FAILED)
        assertThat(observedPersistedState).isFalse()
        assertThat(failed.draft).isEqualTo(draft)
        assertThat(failed.pending).isFalse()
        assertThat(failed.saveFailed).isTrue()

        val retrying = failed.begin(draft = draft, persistable = true)
        assertThat(retrying.saveFailed).isTrue()
        val confirmed = retrying.complete(
            version = retrying.version,
            result = ClinicalAiPersistenceResult.CONFIRMED
        )
        assertThat(confirmed.draft).isNull()
        assertThat(confirmed.saveFailed).isFalse()
    }

    @Test
    fun delayedPersistenceCompletesOnlyAfterEquivalentStateIsObserved() = runTest {
        val config = ClinicalAiProviderConfig(
            ClinicalAiProviderId.GEMINI,
            "gemini-delayed"
        )
        val observationRelease = CompletableDeferred<Unit>()
        var stored = ClinicalAiProviderConfig.defaultOpenAi()
        val coordinator = ClinicalAiSettingsPersistenceCoordinator()

        val result = async {
            coordinator.persist(
                version = 1L,
                config = config,
                currentVersion = { 1L },
                write = { stored = it },
                persistedState = {
                    observationRelease.await()
                    ClinicalAiConfigState.Valid(stored)
                }
            )
        }

        assertThat(result.isCompleted).isFalse()
        observationRelease.complete(Unit)
        assertThat(result.await()).isEqualTo(ClinicalAiPersistenceResult.CONFIRMED)
    }

    @Test
    fun latestPersistenceVersionWinsWhenOlderWriteIsDelayed() = runTest {
        val first = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-delayed"
        )
        val latest = ClinicalAiProviderConfig(
            ClinicalAiProviderId.GEMINI,
            "gemini-latest"
        )
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var currentVersion = 1L
        var stored = ClinicalAiProviderConfig.defaultOpenAi()
        val writes = mutableListOf<ClinicalAiProviderConfig>()
        val coordinator = ClinicalAiSettingsPersistenceCoordinator()

        val oldResult = async {
            coordinator.persist(
                version = 1L,
                config = first,
                currentVersion = { currentVersion },
                write = {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    stored = it
                    writes += it
                },
                persistedState = { ClinicalAiConfigState.Valid(stored) }
            )
        }
        firstEntered.await()
        currentVersion = 2L
        val latestResult = async {
            coordinator.persist(
                version = 2L,
                config = latest,
                currentVersion = { currentVersion },
                write = {
                    stored = it
                    writes += it
                },
                persistedState = { ClinicalAiConfigState.Valid(stored) }
            )
        }

        releaseFirst.complete(Unit)

        assertThat(oldResult.await()).isEqualTo(ClinicalAiPersistenceResult.STALE)
        assertThat(latestResult.await()).isEqualTo(
            ClinicalAiPersistenceResult.CONFIRMED
        )
        assertThat(stored).isEqualTo(latest)
        assertThat(writes).containsExactly(first, latest).inOrder()
    }

    @Test
    fun providerCredentialFailuresMapToSanitizedResetState() {
        val migration = mapClinicalAiCredentialUiState(
            ClinicalAiProviderCredentialState(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
        val read = mapClinicalAiCredentialUiState(
            ClinicalAiProviderCredentialState(
                configured = true,
                busy = false,
                readFailed = true
            )
        )
        val cleanupPending = mapClinicalAiCredentialUiState(
            ClinicalAiProviderCredentialState(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )

        assertThat(migration.migrationError).isTrue()
        assertThat(migration.readError).isFalse()
        assertThat(migration.resetRequired).isTrue()
        assertThat(read.migrationError).isFalse()
        assertThat(read.readError).isTrue()
        assertThat(read.resetRequired).isTrue()
        assertThat(cleanupPending.configured).isTrue()
        assertThat(cleanupPending.legacyCleanupPending).isTrue()
        assertThat(cleanupPending.resetRequired).isFalse()
    }

    @Test
    fun delayedOldConnectionResultCannotOverwriteNewerResult() = runTest {
        val oldConfig = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "claude-old"
        )
        val newConfig = ClinicalAiProviderConfig(
            ClinicalAiProviderId.GEMINI,
            "gemini-new"
        )
        val guard = ClinicalAiConnectionTestGenerationGuard()
        var currentConfig: ClinicalAiProviderConfig? = oldConfig
        val oldToken = guard.begin(oldConfig)
        val oldRelease = CompletableDeferred<Unit>()
        val published = mutableListOf<ClinicalAiConnectionTestUiState>()
        val oldResult = ClinicalAiConnectionTestUiState(
            phase = ClinicalAiConnectionTestPhaseUi.FAILURE,
            providerId = oldConfig.providerId,
            modelId = oldConfig.modelId
        )
        val newResult = ClinicalAiConnectionTestUiState(
            phase = ClinicalAiConnectionTestPhaseUi.SUCCESS,
            providerId = newConfig.providerId,
            modelId = newConfig.modelId
        )
        val oldCompletion = async {
            oldRelease.await()
            guard.publishIfCurrent(
                token = oldToken,
                currentConfig = currentConfig,
                value = oldResult,
                publish = published::add
            )
        }

        currentConfig = newConfig
        val newToken = guard.begin(newConfig)
        assertThat(
            guard.publishIfCurrent(
                token = newToken,
                currentConfig = currentConfig,
                value = newResult,
                publish = published::add
            )
        ).isTrue()
        oldRelease.complete(Unit)

        assertThat(oldCompletion.await()).isFalse()
        assertThat(published).containsExactly(newResult)
    }

    @Test
    fun configMutationInvalidatesInFlightConnectionResult() {
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val guard = ClinicalAiConnectionTestGenerationGuard()
        val token = guard.begin(config)
        guard.invalidate()
        val published = mutableListOf<ClinicalAiConnectionTestUiState>()

        val accepted = guard.publishIfCurrent(
            token = token,
            currentConfig = config,
            value = ClinicalAiConnectionTestUiState(
                phase = ClinicalAiConnectionTestPhaseUi.SUCCESS,
                providerId = config.providerId,
                modelId = config.modelId
            ),
            publish = published::add
        )

        assertThat(accepted).isFalse()
        assertThat(published).isEmpty()
    }

    @Test
    fun retryResetGateClearsOnEachDisableTransitionAfterReEnable() {
        val gate = ClinicalAiRetryStateResetGate()

        assertThat(gate.shouldClear(configurationUsable = false)).isTrue()
        assertThat(gate.shouldClear(configurationUsable = false)).isFalse()
        assertThat(gate.shouldClear(configurationUsable = true)).isFalse()
        assertThat(gate.shouldClear(configurationUsable = false)).isTrue()
    }
}
