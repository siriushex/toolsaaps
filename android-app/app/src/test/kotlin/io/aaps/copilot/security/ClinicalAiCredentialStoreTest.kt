package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClinicalAiCredentialStoreTest {

    @Test
    fun migrationUsesPendingThenActiveBeforeClearingLegacy() = runTest {
        val storage = RecordingSecretStorage()
        val legacy = RecordingLegacyCredentialSource("  provider-secret  ")
        val store = ClinicalAiCredentialStore(
            providerId = ClinicalAiProviderId.OPENAI,
            secureStorage = storage,
            legacyCredentialSource = legacy
        )

        assertThat(store.migrateLegacyCredential())
            .isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(storage.active).isEqualTo("provider-secret")
        assertThat(storage.pending).isNull()
        assertThat(storage.operations).containsExactly(
            "rollback_promotion",
            "read_active",
            "stage",
            "read_pending",
            "commit_pending",
            "read_active",
            "discard_rollback"
        ).inOrder()
        assertThat(legacy.value).isNull()
    }

    @Test
    fun emptyLegacySourceDoesNotCreateOrCopyCredential() = runTest {
        val storage = RecordingSecretStorage()
        val store = ClinicalAiCredentialStore(
            providerId = ClinicalAiProviderId.OPENAI,
            secureStorage = storage,
            legacyCredentialSource = EmptyLegacyCredentialSource
        )

        assertThat(store.migrateLegacyCredential())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(store.readSecret()).isNull()
        assertThat(storage.active).isNull()
        assertThat(storage.operations).doesNotContain("stage")
    }

    @Test
    fun failedPendingVerificationPreservesPreviousActive() = runTest {
        val storage = RecordingSecretStorage(
            active = "old-secret",
            pendingReadOverride = "different"
        )
        val store = ClinicalAiCredentialStore(
            providerId = ClinicalAiProviderId.OPENAI,
            secureStorage = storage,
            legacyCredentialSource = EmptyLegacyCredentialSource
        )

        val failure = runCatching { store.replace("new-secret") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(storage.active).isEqualTo("old-secret")
        assertThat(store.readSecret()).isEqualTo("old-secret")
    }

    private class RecordingSecretStorage(
        var active: String? = null,
        private val pendingReadOverride: String? = null
    ) : SecretStorage {
        var pending: String? = null
        private var pendingLegacyCleanupIntent: String? = null
        private var legacyCleanupIntent: String? = null
        val operations = mutableListOf<String>()
        private var rollbackPresent = false
        private var rollbackActive: String? = null
        private var rollbackDeletionPending = false
        private var rollbackDeletionLegacyCleanupIntent: String? = null
        private var rollbackLegacyCleanupIntent: String? = null
        private var deletionPending = false
        private var pendingDeletionLegacyCleanupIntent: String? = null

        override suspend fun stagePending(value: String, legacyCleanupExpected: String?) {
            operations += "stage"
            pending = value
            pendingLegacyCleanupIntent = legacyCleanupExpected
        }

        override suspend fun readPending(): String? {
            operations += "read_pending"
            return pendingReadOverride ?: pending
        }

        override suspend fun readPendingLegacyCleanupIntent(): String? =
            pendingLegacyCleanupIntent

        override suspend fun commitPending() {
            operations += "commit_pending"
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
            operations += "rollback_promotion"
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
            operations += "discard_rollback"
            rollbackPresent = false
            rollbackActive = null
            rollbackDeletionPending = false
            rollbackDeletionLegacyCleanupIntent = null
            rollbackLegacyCleanupIntent = null
        }

        override suspend fun clearPending() {
            operations += "clear_pending"
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
            operations += "read_active"
            return active
        }

        override suspend fun clear() {
            operations += "clear"
            active = null
            pending = null
            pendingLegacyCleanupIntent = null
            legacyCleanupIntent = null
            rollbackPresent = false
            rollbackActive = null
            rollbackDeletionPending = false
            rollbackDeletionLegacyCleanupIntent = null
            pendingDeletionLegacyCleanupIntent = null
            deletionPending = true
        }
    }

    private class RecordingLegacyCredentialSource(
        var value: String?
    ) : LegacyCredentialSource {
        override suspend fun read(): String? = value

        override suspend fun clearIfMatches(expected: String): Boolean {
            if (value?.trim() != expected) return false
            value = null
            return true
        }
    }
}
