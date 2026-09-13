package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.data.repository.ClinicalAiGatewayFactory
import io.aaps.copilot.security.ClinicalAiCredentialProvider
import io.aaps.copilot.security.ClinicalAiCredentialStatus
import io.aaps.copilot.security.ClinicalAiCredentialStore
import io.aaps.copilot.security.EmptyLegacyCredentialSource
import io.aaps.copilot.security.LegacyOpenAiKeySource
import io.aaps.copilot.security.OpenAiCredentialProvider
import io.aaps.copilot.security.OpenAiCredentialStore
import io.aaps.copilot.security.SecretStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClinicalAiProviderManagerTest {

    @Test
    fun statusMappingPreservesMigrationAndReadFailuresForEveryProvider() {
        val statuses = ClinicalAiProviderId.entries.associateWith { providerId ->
            ClinicalAiCredentialStatus(
                configured = providerId == ClinicalAiProviderId.GEMINI,
                busy = false,
                migrationFailed = providerId == ClinicalAiProviderId.OPENAI ||
                    providerId == ClinicalAiProviderId.ANTHROPIC,
                readFailed = providerId == ClinicalAiProviderId.GEMINI ||
                    providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE,
                legacyCleanupPending = providerId == ClinicalAiProviderId.OPENAI ||
                    providerId == ClinicalAiProviderId.GEMINI
            )
        }

        val mapped = mapClinicalAiProviderCredentialStates(
            openAi = statuses.getValue(ClinicalAiProviderId.OPENAI),
            providers = statuses - ClinicalAiProviderId.OPENAI
        )

        ClinicalAiProviderId.entries.forEach { providerId ->
            val source = statuses.getValue(providerId)
            assertThat(mapped.getValue(providerId)).isEqualTo(
                ClinicalAiProviderCredentialState(
                    configured = source.configured,
                    busy = source.busy,
                    migrationFailed = source.migrationFailed,
                    readFailed = source.readFailed,
                    legacyCleanupPending = source.legacyCleanupPending
                )
            )
        }
    }

    @Test
    fun managerReplaceAndDeleteResetFailureStateForEveryProvider() = runTest {
        val storages = ClinicalAiProviderId.entries.associateWith {
            FailingReadSecretStorage()
        }
        val openAiProvider = OpenAiCredentialProvider(
            OpenAiCredentialStore(
                secureStorage = storages.getValue(ClinicalAiProviderId.OPENAI),
                legacyKeySource = EmptyLegacyOpenAiKeySource
            )
        )
        val nonOpenAiProvider = ClinicalAiCredentialProvider(
            ClinicalAiProviderId.entries
                .filterNot { it == ClinicalAiProviderId.OPENAI }
                .associateWith { providerId ->
                    ClinicalAiCredentialStore(
                        providerId = providerId,
                        secureStorage = storages.getValue(providerId),
                        legacyCredentialSource = EmptyLegacyCredentialSource
                    )
                }
        )
        val manager = ClinicalAiProviderManager(
            openAiCredentialProvider = openAiProvider,
            clinicalAiCredentialProvider = nonOpenAiProvider,
            gatewayFactory = ClinicalAiGatewayFactory()
        )

        openAiProvider.ensureMigrated()
        ClinicalAiProviderId.entries
            .filterNot { it == ClinicalAiProviderId.OPENAI }
            .forEach { nonOpenAiProvider.ensureMigrated(it) }
        val failed = manager.status.first { states ->
            ClinicalAiProviderId.entries.all {
                states[it]?.migrationFailed == true
            }
        }
        assertThat(failed.values).hasSize(ClinicalAiProviderId.entries.size)

        storages.values.forEach { it.failReads = false }
        ClinicalAiProviderId.entries.forEach { manager.replace(it, "test-credential") }
        val replaced = manager.status.first { states ->
            ClinicalAiProviderId.entries.all { providerId ->
                states[providerId]?.let {
                    !it.busy && it.configured &&
                        !it.migrationFailed && !it.readFailed
                } == true
            }
        }
        assertThat(replaced.keys).containsExactlyElementsIn(ClinicalAiProviderId.entries)

        ClinicalAiProviderId.entries.forEach { manager.delete(it) }
        val reset = manager.status.first { states ->
            ClinicalAiProviderId.entries.all { providerId ->
                states[providerId]?.let {
                    !it.busy && !it.configured &&
                        !it.migrationFailed && !it.readFailed
                } == true
            }
        }

        assertThat(reset.keys).containsExactlyElementsIn(ClinicalAiProviderId.entries)
    }

    private class FailingReadSecretStorage : SecretStorage {
        var failReads = true
        private var active: String? = null
        private var pending: String? = null
        private var pendingLegacyCleanupIntent: String? = null
        private var legacyCleanupIntent: String? = null
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
            if (failReads) error("sanitized storage failure")
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
            pendingDeletionLegacyCleanupIntent = null
            deletionPending = true
        }
    }

    private object EmptyLegacyOpenAiKeySource : LegacyOpenAiKeySource {
        override suspend fun read(): String? = null
        override suspend fun clearIfMatches(expected: String): Boolean = false
    }
}
