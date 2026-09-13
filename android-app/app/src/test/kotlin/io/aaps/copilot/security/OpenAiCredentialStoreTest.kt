package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiCredentialStoreTest {

    @Test
    fun migrationStagesVerifiesPromotesVerifiesThenClearsLegacy() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("  sk-test  ")
        val secure = FakeSecretStorage(state)
        val store = OpenAiCredentialStore(secure, legacy)

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(state.active).isEqualTo("sk-test")
        assertThat(state.pending).isNull()
        assertThat(secure.operations).containsExactly(
            "rollback_promotion",
            "read_active",
            "stage:sk-test",
            "read_pending",
            "commit_pending",
            "read_active",
            "discard_rollback"
        ).inOrder()
        assertThat(legacy.value).isNull()
        assertThat(store.readSecret()).isEqualTo("sk-test")
    }

    @Test
    fun migrationSourceMutationBeforeCleanupPreservesNewerLegacyValue() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a").apply {
            beforeClear = { value = "synthetic-legacy-b" }
        }
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        val result = store.migrateLegacyKey()

        assertThat(result.name).isEqualTo("SOURCE_CHANGED")
        assertThat(state.active).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
    }

    @Test
    fun alreadySecureCredentialDoesNotEraseDifferentLegacyValue() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-b")
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        val result = store.migrateLegacyKey()

        assertThat(result.name).isEqualTo("SOURCE_CHANGED")
        assertThat(state.active).isEqualTo("synthetic-secure-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun exactLegacyCompareAndClearRunsOnlyAfterVerifiedActive() = runTest {
        val state = FakeStorageState()
        val secure = FakeSecretStorage(state)
        var activeObservedAtClear: String? = null
        var activeReadsObservedAtClear = 0
        val legacy = FakeLegacyOpenAiKeySource("  synthetic-legacy-a  ").apply {
            beforeClear = {
                activeObservedAtClear = state.active
                activeReadsObservedAtClear = secure.operations.count { it == "read_active" }
            }
        }
        val store = OpenAiCredentialStore(secure, legacy)

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.MIGRATED)

        assertThat(legacy.clearExpectedValues).containsExactly("synthetic-legacy-a")
        assertThat(activeObservedAtClear).isEqualTo("synthetic-legacy-a")
        assertThat(activeReadsObservedAtClear).isEqualTo(2)
        assertThat(legacy.value).isNull()
    }

    @Test
    fun pendingIsNeverConfiguredOrReturnedAsActive() = runTest {
        val state = FakeStorageState(pending = "sk-pending")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state),
            FakeLegacyOpenAiKeySource(null)
        )

        assertThat(store.readSecret()).isNull()
        assertThat(store.readConfigured()).isFalse()
        assertThat(state.pending).isEqualTo("sk-pending")
    }

    @Test
    fun processRestartAfterPendingVerificationMismatchStillCannotReadPending() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("sk-test")
        val firstProcess = OpenAiCredentialStore(
            FakeSecretStorage(state, pendingReadOverride = "different"),
            legacy
        )

        assertThat(firstProcess.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(state.active).isNull()
        assertThat(state.pending).isEqualTo("sk-test")
        assertThat(legacy.value).isEqualTo("sk-test")

        val restartedProcess = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        assertThat(restartedProcess.readSecret()).isNull()
        assertThat(restartedProcess.readConfigured()).isFalse()
        assertThat(state.pending).isEqualTo("sk-test")
    }

    @Test
    fun retryOverwritesPendingAndCompletesMigration() = runTest {
        val state = FakeStorageState(pending = "stale")
        val legacy = FakeLegacyOpenAiKeySource("sk-test")
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(state.active).isEqualTo("sk-test")
        assertThat(state.pending).isNull()
        assertThat(legacy.value).isNull()
    }

    @Test
    fun failedStageKeepsLegacyAndLeavesNoActiveSecret() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("sk-test")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state, failStage = true),
            legacy
        )

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(state.active).isNull()
        assertThat(legacy.value).isEqualTo("sk-test")
        assertThat(store.readConfigured()).isFalse()
    }

    @Test
    fun corruptActiveFailsClosedWithoutPlaintextFallback() = runTest {
        val state = FakeStorageState(active = "sk-active")
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state, activeReadFailure = IllegalStateException("corrupt")),
            legacy
        )

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(legacy.readCount).isEqualTo(0)
        assertThat(legacy.value).isEqualTo("sk-legacy")
        val failure = runCatching { store.readSecret() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("Secure credential read failed")
        assertThat(failure?.cause).isNull()
    }

    @Test
    fun failedReplaceVerificationPreservesPreviousActiveAcrossRestart() = runTest {
        val state = FakeStorageState(active = "sk-old")
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy")
        val firstProcess = OpenAiCredentialStore(
            FakeSecretStorage(state, pendingReadOverride = "wrong"),
            legacy
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { firstProcess.replace("sk-new") }
        }

        assertThat(firstProcess.readSecret()).isEqualTo("sk-old")
        assertThat(state.active).isEqualTo("sk-old")
        assertThat(state.pending).isEqualTo("sk-new")
        assertThat(legacy.value).isEqualTo("sk-legacy")

        val restartedProcess = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restartedProcess.readSecret()).isEqualTo("sk-old")
    }

    @Test
    fun successfulReplacePromotesPendingAndClearsLegacy() = runTest {
        val state = FakeStorageState(active = "sk-old")
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy")
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        store.replace("  sk-new  ")

        assertThat(state.active).isEqualTo("sk-new")
        assertThat(state.pending).isNull()
        assertThat(store.readSecret()).isEqualTo("sk-new")
        assertThat(legacy.value).isNull()
    }

    @Test
    fun commitFailurePreservesPreviousActiveAndLegacy() = runTest {
        val state = FakeStorageState(active = "sk-old")
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state, failCommitBeforePromote = true),
            legacy
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.replace("sk-new") }
        }

        assertThat(state.active).isEqualTo("sk-old")
        assertThat(state.pending).isEqualTo("sk-new")
        assertThat(store.readSecret()).isEqualTo("sk-old")
        assertThat(legacy.value).isEqualTo("sk-legacy")
    }

    @Test
    fun commitThatDoesNotPromoteKeepsPreviousActiveReadable() = runTest {
        val state = FakeStorageState(active = "sk-old")
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state, commitWithoutPromote = true),
            legacy
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.replace("sk-new") }
        }

        assertThat(state.active).isEqualTo("sk-old")
        assertThat(state.pending).isEqualTo("sk-new")
        assertThat(store.readSecret()).isEqualTo("sk-old")
        assertThat(legacy.value).isEqualTo("sk-legacy")
    }

    @Test
    fun promotedMismatchRollsBackPreviousActiveAcrossRestart() = runTest {
        val state = FakeStorageState(
            active = "sk-old",
            legacyCleanupIntent = "synthetic-prior-cleanup"
        )
        val store = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                activeReadOverrideAfterCommit = "mismatched-promoted-value"
            ),
            FakeLegacyOpenAiKeySource(null)
        )

        val failure = runCatching { store.replace("sk-new") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(state.active).isEqualTo("sk-old")
        assertThat(state.pending).isNull()
        assertThat(state.legacyCleanupIntent).isEqualTo("synthetic-prior-cleanup")

        val restarted = OpenAiCredentialStore(
            FakeSecretStorage(state),
            FakeLegacyOpenAiKeySource(null)
        )
        assertThat(restarted.readSecret()).isEqualTo("sk-old")
        assertThat(state.legacyCleanupIntent).isEqualTo("synthetic-prior-cleanup")
    }

    @Test
    fun promotedDecryptFailureRollsBackToNoActiveAcrossRestart() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                activeReadFailureAfterCommit = IllegalStateException(
                    "promoted decrypt failed with sk-legacy"
                )
            ),
            legacy
        )

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(state.active).isNull()
        assertThat(state.pending).isNull()
        assertThat(legacy.value).isEqualTo("sk-legacy")

        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restarted.readConfigured()).isFalse()
        assertThat(restarted.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(state.active).isEqualTo("sk-legacy")
    }

    @Test
    fun restartRecoversInterruptedPromotionBeforeReadingActive() = runTest {
        val state = FakeStorageState(
            active = "unverified-promoted",
            rollbackPresent = true,
            rollbackActive = "verified-previous"
        )
        val restarted = OpenAiCredentialStore(
            FakeSecretStorage(state),
            FakeLegacyOpenAiKeySource(null)
        )

        assertThat(restarted.readSecret()).isEqualTo("verified-previous")
        assertThat(state.active).isEqualTo("verified-previous")
        assertThat(state.rollbackPresent).isFalse()
    }

    @Test
    fun migrationCommitFailureLeavesPendingUnconfiguredAndRetrySucceeds() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("sk-test")
        val firstProcess = OpenAiCredentialStore(
            FakeSecretStorage(state, failCommitBeforePromote = true),
            legacy
        )

        assertThat(firstProcess.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(state.active).isNull()
        assertThat(state.pending).isEqualTo("sk-test")
        assertThat(legacy.value).isEqualTo("sk-test")

        val restartedProcess = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restartedProcess.readConfigured()).isFalse()
        assertThat(restartedProcess.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(state.active).isEqualTo("sk-test")
        assertThat(state.pending).isNull()
        assertThat(legacy.value).isNull()
    }

    @Test
    fun legacyClearExceptionAfterPromotionReturnsCleanupUncertainAndKeepsActive() = runTest {
        val state = FakeStorageState(active = "sk-old")
        val legacy = FakeLegacyOpenAiKeySource("sk-legacy", failClear = true)
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        val result = store.delegate.replace("sk-new")

        assertThat(result.name).isEqualTo("REPLACED_WITH_LEGACY_CLEANUP_UNCERTAIN")
        assertThat(state.active).isEqualTo("sk-new")
        assertThat(state.pending).isNull()
        assertThat(store.readSecret()).isEqualTo("sk-new")
        assertThat(legacy.value).isEqualTo("sk-legacy")
    }

    @Test
    fun cleanupUncertainAfterLegacyClearFailureRetriesAndRemovesPlaintext() = runTest {
        val state = FakeStorageState()
        val legacy = FakeLegacyOpenAiKeySource("sk-test", failClear = true)
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        assertThat(store.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.CLEANUP_UNCERTAIN)
        assertThat(state.active).isEqualTo("sk-test")
        assertThat(store.readSecret()).isEqualTo("sk-test")
        assertThat(legacy.value).isEqualTo("sk-test")

        legacy.failClear = false

        assertThat(store.migrateLegacyKey()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(store.readSecret()).isEqualTo("sk-test")
    }

    @Test
    fun alreadySecureWithoutLegacyReturnsConfiguredAndClearsStalePending() = runTest {
        val state = FakeStorageState(active = "sk-secure", pending = "stale")
        val secure = FakeSecretStorage(state)
        val store = OpenAiCredentialStore(secure, FakeLegacyOpenAiKeySource(null))

        assertThat(store.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.ALREADY_SECURE)
        assertThat(store.readSecret()).isEqualTo("sk-secure")
        assertThat(state.pending).isNull()
        assertThat(secure.operations).contains("clear_pending")
    }

    @Test
    fun blankLegacyIsNotMigratedAndStalePendingIsCleared() = runTest {
        val state = FakeStorageState(pending = "stale")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state),
            FakeLegacyOpenAiKeySource("   ")
        )

        assertThat(store.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(state.active).isNull()
        assertThat(state.pending).isNull()
    }

    @Test
    fun replaceRejectsBlankSecretWithoutChangingRecords() = runTest {
        val state = FakeStorageState(active = "sk-old", pending = "pending")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state),
            FakeLegacyOpenAiKeySource(null)
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.replace(" \t ") }
        }

        assertThat(state.active).isEqualTo("sk-old")
        assertThat(state.pending).isEqualTo("pending")
    }

    @Test
    fun clearExactLegacyAndSecureCredentialRemovesBoth() = runTest {
        val state = FakeStorageState(active = "synthetic-credential-a", pending = "stale")
        var deletionMarkerAtLegacyCompare: Boolean? = null
        var deletionIntentAtLegacyCompare: String? = null
        val legacy = FakeLegacyOpenAiKeySource("  synthetic-credential-a  ").apply {
            beforeClear = {
                deletionMarkerAtLegacyCompare = state.deletionPending
                deletionIntentAtLegacyCompare = state.pendingDeletionLegacyCleanupIntent
            }
        }
        val secure = FakeSecretStorage(state)
        val store = OpenAiCredentialStore(secure, legacy)

        store.clear()

        assertThat(state.active).isNull()
        assertThat(state.pending).isNull()
        assertThat(secure.clearCount).isEqualTo(1)
        assertThat(legacy.value).isNull()
        assertThat(legacy.clearExpectedValues).containsExactly("synthetic-credential-a")
        assertThat(deletionMarkerAtLegacyCompare).isTrue()
        assertThat(deletionIntentAtLegacyCompare).isEqualTo("synthetic-credential-a")
        assertThat(store.readConfigured()).isFalse()
        assertThat(state.deletionPending).isTrue()
        assertThat(state.pendingDeletionLegacyCleanupIntent).isNull()
    }

    @Test
    fun clearWithoutLegacyDeletesSecureCredential() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource(null)
        val store = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        store.clear()

        assertThat(state.active).isNull()
        assertThat(state.deletionPending).isTrue()
        assertThat(legacy.readCount).isEqualTo(1)
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun clearSourceMutationBeforeCompareKeepsDurableMarkerAndSecureActive() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a").apply {
            beforeClear = { value = "synthetic-legacy-b" }
        }
        val secure = FakeSecretStorage(state)
        val store = OpenAiCredentialStore(secure, legacy)

        val failure = runCatching { store.clear() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("Secure credential clear failed")
        assertThat(state.deletionPending).isTrue()
        assertThat(state.pendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-a")
        assertThat(state.active).isEqualTo("synthetic-secure-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(secure.clearCount).isEqualTo(0)
        assertThat(runCatching { store.readSecret() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun restartResumesDeletionAfterMarkerCommitInterruption() = runTest {
        listOf<Throwable>(
            IllegalStateException("ordinary"),
            CancellationException("cancelled"),
            AssertionError("fatal")
        ).forEach { interruption ->
            val state = FakeStorageState(active = "secure-active")
            val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
            val firstProcess = OpenAiCredentialStore(
                FakeSecretStorage(
                    state = state,
                    markDeletionPendingFailureAfterCommit = interruption
                ),
                legacy
            )

            val failure = runCatching { firstProcess.clear() }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(state.deletionPending).isTrue()
            assertThat(state.pendingDeletionLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")
            assertThat(state.active).isEqualTo("secure-active")
            assertThat(legacy.value).isEqualTo("synthetic-legacy-a")

            val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
            restarted.clear()

            assertThat(state.active).isNull()
            assertThat(legacy.value).isNull()
            assertThat(state.deletionPending).isTrue()
            assertThat(state.pendingDeletionLegacyCleanupIntent).isNull()
        }
    }

    @Test
    fun restartResumesDeletionAfterLegacyRemovalBeforeSecureClear() = runTest {
        listOf<Throwable>(
            IllegalStateException("ordinary"),
            CancellationException("cancelled"),
            AssertionError("fatal")
        ).forEach { interruption ->
            val state = FakeStorageState(active = "secure-active")
            val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
            val firstProcess = OpenAiCredentialStore(
                FakeSecretStorage(state = state, clearFailure = interruption),
                legacy
            )

            val failure = runCatching { firstProcess.clear() }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(state.deletionPending).isTrue()
            assertThat(state.pendingDeletionLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")
            assertThat(state.active).isEqualTo("secure-active")
            assertThat(legacy.value).isNull()

            val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
            restarted.clear()

            assertThat(state.active).isNull()
            assertThat(state.pendingDeletionLegacyCleanupIntent).isNull()
            assertThat(state.deletionPending).isTrue()
        }
    }

    @Test
    fun restartWithChangedLegacyKeepsSourceSecureActiveAndDeletionIntent() = runTest {
        val state = FakeStorageState(active = "secure-active")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
        val firstProcess = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                markDeletionPendingFailureAfterCommit = CancellationException("cancelled")
            ),
            legacy
        )

        assertThat(runCatching { firstProcess.clear() }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
        legacy.value = "synthetic-legacy-c"

        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        val recoveryFailure = runCatching { restarted.clear() }.exceptionOrNull()

        assertThat(recoveryFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(state.active).isEqualTo("secure-active")
        assertThat(state.deletionPending).isTrue()
        assertThat(state.pendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-c")
        assertThat(legacy.clearExpectedValues).isEmpty()
    }

    @Test
    fun noLegacyDeletionIntentNeverClaimsLaterLegacyValue() = runTest {
        val state = FakeStorageState(active = "secure-active")
        val legacy = FakeLegacyOpenAiKeySource(null)
        val firstProcess = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                markDeletionPendingFailureAfterCommit = AssertionError("fatal")
            ),
            legacy
        )

        assertThat(runCatching { firstProcess.clear() }.exceptionOrNull())
            .isInstanceOf(AssertionError::class.java)
        legacy.value = "synthetic-legacy-b"
        val readsBeforeRecovery = legacy.readCount

        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        restarted.clear()

        assertThat(state.active).isNull()
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(legacy.readCount).isEqualTo(readsBeforeRecovery)
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun legacyWrittenAfterOriginalRemovalKeepsRestartRecoveryFailClosed() = runTest {
        val state = FakeStorageState(active = "secure-active")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
        val firstProcess = OpenAiCredentialStore(
            FakeSecretStorage(state, failClear = true),
            legacy
        )

        val failure = runCatching { firstProcess.clear() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(state.deletionPending).isTrue()
        assertThat(state.active).isEqualTo("secure-active")
        assertThat(legacy.value).isNull()

        legacy.value = "synthetic-legacy-b"
        val restarted = OpenAiCredentialProvider(
            OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        )
        val restartedFailure = runCatching { restarted.requireCredential() }.exceptionOrNull()

        assertThat(restartedFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(restartedFailure?.message).isEqualTo("OpenAI credential migration failed")
        assertThat(state.deletionPending).isTrue()
        assertThat(state.active).isEqualTo("secure-active")
        assertThat(state.pendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")

        val readsAfterFirstRestart = legacy.readCount
        val clearsAfterFirstRestart = legacy.clearCount

        assertThat(runCatching { restarted.requireCredential() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(state.deletionPending).isTrue()
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(legacy.readCount).isEqualTo(readsAfterFirstRestart)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterFirstRestart)

        assertThat(restarted.ensureMigrated()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(legacy.readCount).isEqualTo(readsAfterFirstRestart + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterFirstRestart)
    }

    @Test
    fun pendingDeletionRecoveryNeverReadsOrClearsLegacy() = runTest {
        val state = FakeStorageState(
            active = "synthetic-secure-a",
            deletionPending = true
        )
        val legacy = FakeLegacyOpenAiKeySource(
            initialValue = "synthetic-legacy-b",
            failClear = true
        )
        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        assertThat(restarted.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(restarted.readConfigured()).isFalse()

        assertThat(state.deletionPending).isTrue()
        assertThat(state.active).isNull()
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(legacy.readCount).isEqualTo(0)
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun explicitReplaceAfterTombstoneAtomicallyActivatesCredentialAndRemovesTombstone() = runTest {
        val state = FakeStorageState(
            deletionPending = true,
            keyAvailable = false,
            durableKeyAvailable = false
        )
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-b")
        val secure = FakeSecretStorage(state)
        val store = OpenAiCredentialStore(secure, legacy)

        val result = store.delegate.replace("synthetic-secure-c")

        assertThat(result).isEqualTo(CredentialReplacementResult.REPLACED)
        assertThat(secure.deletionPendingAtCommitStart).isTrue()
        assertThat(secure.activeAndDeletionPendingAfterCommit)
            .isEqualTo("synthetic-secure-c" to false)
        assertThat(state.active).isEqualTo("synthetic-secure-c")
        assertThat(state.deletionPending).isFalse()
        assertThat(legacy.value).isNull()
        assertThat(state.keyAvailable).isTrue()
        assertThat(state.durableKeyAvailable).isTrue()
        assertThat(secure.operations.indexOf("recreate_key")).isAtLeast(0)
        assertThat(secure.operations.indexOf("persist_pending")).isAtLeast(0)
        assertThat(secure.operations.indexOf("recreate_key"))
            .isLessThan(secure.operations.indexOf("persist_pending"))

        state.crashProcess()
        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        assertThat(restarted.readSecret()).isEqualTo("synthetic-secure-c")
        assertThat(state.deletionPending).isFalse()
        assertThat(state.pending).isNull()
        assertThat(state.keyAvailable).isTrue()
    }

    @Test
    fun stagePendingRecreatesAliasDurablyBeforePreferenceCommitFailure() = runTest {
        fakeCommitFailures().forEach { failure ->
            val state = FakeStorageState(
                keyAvailable = false,
                durableKeyAvailable = false
            )
            val storage = FakeSecretStorage(
                state = state,
                preferenceCommitFailureSite = FakePreferenceCommitSite.STAGE_PENDING,
                preferenceCommitFailure = failure
            )

            assertThat(
                runCatching {
                    storage.stagePending("synthetic-secure-b", "synthetic-legacy-a")
                }.exceptionOrNull()
            ).isNotNull()
            assertThat(state.keyAvailable).isTrue()
            assertThat(state.durableKeyAvailable).isTrue()
            assertThat(state.pending).isEqualTo("synthetic-secure-b")
            assertThat(state.durablePending).isNull()
            assertThat(storage.operations.indexOf("recreate_key")).isAtLeast(0)
            assertThat(storage.operations).doesNotContain("persist_pending")

            state.crashProcess()
            val restarted = FakeSecretStorage(state)

            assertThat(restarted.readPending()).isNull()
            assertThat(state.keyAvailable).isTrue()
        }
    }

    @Test
    fun aliasLossAfterPendingVerificationBlocksPromotionAndPreservesDurableTombstone() = runTest {
        val state = FakeStorageState(
            active = "synthetic-secure-a",
            legacyCleanupIntent = "synthetic-legacy-active",
            deletionPending = true,
            pendingDeletionLegacyCleanupIntent = "synthetic-legacy-deletion"
        )
        val storage = FakeSecretStorage(state)
        storage.stagePending("synthetic-secure-b", "synthetic-legacy-pending")
        assertThat(storage.readPending()).isEqualTo("synthetic-secure-b")
        assertThat(storage.readPendingLegacyCleanupIntent())
            .isEqualTo("synthetic-legacy-pending")

        state.keyAvailable = false
        state.durableKeyAvailable = false

        assertThat(runCatching { storage.commitPending() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(state.active).isEqualTo("synthetic-secure-a")
        assertThat(state.pending).isEqualTo("synthetic-secure-b")
        assertThat(state.legacyCleanupIntent).isEqualTo("synthetic-legacy-active")
        assertThat(state.pendingLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-pending")
        assertThat(state.rollbackPresent).isFalse()
        assertThat(state.deletionPending).isTrue()
        assertThat(state.pendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-deletion")
        assertThat(state.durableActive).isEqualTo("synthetic-secure-a")
        assertThat(state.durablePending).isEqualTo("synthetic-secure-b")
        assertThat(state.durablePendingLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-pending")
        assertThat(state.durableLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-active")
        assertThat(state.durableRollbackPresent).isFalse()
        assertThat(state.durableDeletionPending).isTrue()
        assertThat(state.durablePendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-deletion")
        assertThat(state.durableKeyAvailable).isFalse()

        state.crashProcess()
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-deletion")
        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)

        assertThat(runCatching { restarted.readSecret() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(state.active).isEqualTo("synthetic-secure-a")
        assertThat(state.pending).isEqualTo("synthetic-secure-b")
        assertThat(state.pendingLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-pending")
        assertThat(state.legacyCleanupIntent).isEqualTo("synthetic-legacy-active")
        assertThat(state.rollbackPresent).isFalse()
        assertThat(state.deletionPending).isTrue()
        assertThat(state.pendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-deletion")
        assertThat(legacy.readCount).isEqualTo(0)
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun failedMarkerCommitCannotAuthorizeRecoveryLegacyMutationBeforeCrash() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state = state, failDeletionMarkerCommit = true),
            legacy
        )

        assertThat(runCatching { store.clear() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(state.deletionPending).isTrue()
        assertThat(state.durableDeletionPending).isFalse()

        val sameProcessRecovery = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(
            runCatching { sameProcessRecovery.probeConfiguredAfterInterruption() }
                .exceptionOrNull()
        )
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.clearCount).isEqualTo(0)
        assertThat(state.durableActive).isEqualTo("synthetic-secure-a")

        state.crashProcess()
        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restarted.readSecret()).isEqualTo("synthetic-secure-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
    }

    @Test
    fun sameNamespaceReplacementCannotMutateAfterConcurrentDeletionQuarantine() = runTest {
        listOf<Throwable?>(
            null,
            CancellationException("cancel deletion marker commit"),
            AssertionError("fatal deletion marker commit")
        ).forEach { persistenceFailure ->
            val events = mutableListOf<String>()
            val state = FakeStorageState(active = "synthetic-secure-a")
            val legacy = FakeLegacyOpenAiKeySource(
                initialValue = "synthetic-legacy-a",
                sharedOperations = events
            )
            val replacementPaused = CompletableDeferred<Unit>()
            val resumeReplacement = CompletableDeferred<Unit>()
            val replacementStore = OpenAiCredentialStore(
                FakeSecretStorage(
                    state = state,
                    beforeStage = {
                        events += "replacement:paused"
                        replacementPaused.complete(Unit)
                        resumeReplacement.await()
                    },
                    sharedOperations = events,
                    operationLabel = "replacement"
                ),
                legacy
            )
            val deletingStore = OpenAiCredentialStore(
                FakeSecretStorage(
                    state = state,
                    failDeletionMarkerCommit = persistenceFailure == null,
                    deletionMarkerCommitFailure = persistenceFailure,
                    sharedOperations = events,
                    operationLabel = "deletion"
                ),
                legacy
            )
            val replacementProvider = OpenAiCredentialProvider(replacementStore)
            val deletingProvider = OpenAiCredentialProvider(deletingStore)

            val replacement = async {
                runCatching { replacementProvider.replace("synthetic-secure-b") }
            }
            replacementPaused.await()
            val deletion = async { runCatching { deletingProvider.delete() } }
            runCurrent()
            resumeReplacement.complete(Unit)

            assertThat(replacement.await().exceptionOrNull()).isNull()
            val deletionFailure = deletion.await().exceptionOrNull()
            if (persistenceFailure == null) {
                assertThat(deletionFailure).isInstanceOf(IllegalStateException::class.java)
            } else {
                assertThat(deletionFailure).isSameInstanceAs(persistenceFailure)
            }
            val quarantineIndex = events.indexOf("deletion:quarantine")
            assertThat(quarantineIndex).isAtLeast(0)
            listOf(
                "replacement:stage",
                "replacement:commit",
                "legacy:clear"
            ).forEach { mutation ->
                val mutationIndex = events.indexOf(mutation)
                assertThat(mutationIndex).isAtLeast(0)
                assertThat(mutationIndex).isLessThan(quarantineIndex)
            }
            assertThat(state.active).isEqualTo("synthetic-secure-b")
            assertThat(legacy.value).isNull()
        }
    }

    @Test
    fun cancellationWhileWaitingForSameNamespaceTransactionDoesNotEnterStorage() = runTest {
        val events = mutableListOf<String>()
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource(null)
        val replacementPaused = CompletableDeferred<Unit>()
        val resumeReplacement = CompletableDeferred<Unit>()
        val replacementStore = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                beforeStage = {
                    replacementPaused.complete(Unit)
                    resumeReplacement.await()
                },
                sharedOperations = events,
                operationLabel = "replacement"
            ),
            legacy
        )
        val deletingStore = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                sharedOperations = events,
                operationLabel = "deletion"
            ),
            legacy
        )

        val replacement = async { replacementStore.replace("synthetic-secure-b") }
        replacementPaused.await()
        val deletion = launch { deletingStore.clear() }
        runCurrent()

        assertThat(events).doesNotContain("deletion:mark")
        deletion.cancelAndJoin()
        resumeReplacement.complete(Unit)
        replacement.await()

        assertThat(deletion.isCancelled).isTrue()
        assertThat(events).doesNotContain("deletion:mark")
        assertThat(state.active).isEqualTo("synthetic-secure-b")
    }

    @Test
    fun cancellationInsideSameNamespaceTransactionReleasesWaitingDeletion() = runTest {
        val events = mutableListOf<String>()
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource(null)
        val replacementPaused = CompletableDeferred<Unit>()
        val replacementStore = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                beforeStage = {
                    replacementPaused.complete(Unit)
                    awaitCancellation()
                },
                sharedOperations = events,
                operationLabel = "replacement"
            ),
            legacy
        )
        val deletingStore = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                sharedOperations = events,
                operationLabel = "deletion"
            ),
            legacy
        )

        val replacement = launch { replacementStore.replace("synthetic-secure-b") }
        replacementPaused.await()
        val deletion = async { deletingStore.clear() }
        runCurrent()

        assertThat(events).doesNotContain("deletion:mark")
        replacement.cancelAndJoin()
        deletion.await()

        assertThat(replacement.isCancelled).isTrue()
        assertThat(events).contains("deletion:mark")
        assertThat(state.active).isNull()
    }

    @Test
    fun independentProviderNamespacesDoNotShareTransactionFence() = runTest {
        val openAiPaused = CompletableDeferred<Unit>()
        val resumeOpenAi = CompletableDeferred<Unit>()
        val openAiStore = ClinicalAiCredentialStore(
            providerId = ClinicalAiProviderId.OPENAI,
            secureStorage = FakeSecretStorage(
                state = FakeStorageState(active = "synthetic-openai-a"),
                beforeStage = {
                    openAiPaused.complete(Unit)
                    resumeOpenAi.await()
                }
            ),
            legacyCredentialSource = FakeLegacyOpenAiKeySource(null)
        )
        val anthropicState = FakeStorageState(active = "synthetic-anthropic-a")
        val anthropicStore = ClinicalAiCredentialStore(
            providerId = ClinicalAiProviderId.ANTHROPIC,
            secureStorage = FakeSecretStorage(anthropicState),
            legacyCredentialSource = EmptyLegacyCredentialSource
        )

        val openAiReplacement = async { openAiStore.replace("synthetic-openai-b") }
        openAiPaused.await()
        val anthropicReplacement = async {
            anthropicStore.replace("synthetic-anthropic-b")
        }
        runCurrent()

        assertThat(anthropicReplacement.isCompleted).isTrue()
        assertThat(anthropicReplacement.await())
            .isEqualTo(CredentialReplacementResult.REPLACED)
        assertThat(anthropicState.active).isEqualTo("synthetic-anthropic-b")

        resumeOpenAi.complete(Unit)
        assertThat(openAiReplacement.await())
            .isEqualTo(CredentialReplacementResult.REPLACED)
    }

    @Test
    fun failedMarkerCommitThenImmediateCrashLeavesCommittedStateUntouched() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state = state, failDeletionMarkerCommit = true),
            legacy
        )

        assertThat(runCatching { store.clear() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        state.crashProcess()

        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restarted.readSecret()).isEqualTo("synthetic-secure-a")
        assertThat(state.deletionPending).isFalse()
        assertThat(state.keyAvailable).isTrue()
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun preferencesCommitFailureKeepsKeyForEncryptedRestartRecovery() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(state = state, failSecureClearPreferencesCommit = true),
            legacy
        )

        assertThat(runCatching { store.clear() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(legacy.value).isNull()
        assertThat(state.durableDeletionPending).isTrue()
        assertThat(state.durablePendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-a")
        assertThat(state.keyAvailable).isTrue()

        state.crashProcess()
        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        restarted.clear()

        assertThat(state.active).isNull()
        assertThat(state.pendingDeletionLegacyCleanupIntent).isNull()
        assertThat(state.keyAvailable).isFalse()
    }

    @Test
    fun secureClearCommitsEncryptedRecordRemovalBeforeAliasDeletion() = runTest {
        listOf<Throwable>(
            CancellationException("cancel after preferences commit"),
            AssertionError("fatal after preferences commit")
        ).forEach { interruption ->
            val state = FakeStorageState(active = "synthetic-secure-a")
            val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
            val store = OpenAiCredentialStore(
                FakeSecretStorage(
                    state = state,
                    clearFailureAfterPreferencesCommit = interruption
                ),
                legacy
            )

            assertThat(runCatching { store.clear() }.exceptionOrNull()).isSameInstanceAs(interruption)
            assertThat(state.keyAvailableAtLastPreferencesCommit).isTrue()
            assertThat(state.durableDeletionPending).isTrue()
            assertThat(state.durablePendingDeletionLegacyCleanupIntent).isNull()

            legacy.value = "synthetic-legacy-b"
            state.crashProcess()
            val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
            restarted.clear()

            assertThat(state.active).isNull()
            assertThat(state.keyAvailable).isFalse()
            assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        }
    }

    @Test
    fun crashAfterAliasDeletionHasNoEncryptedDeletionRecordLeftToDecrypt() = runTest {
        val state = FakeStorageState(active = "synthetic-secure-a")
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
        val fatal = AssertionError("fatal after alias deletion")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                clearFailureAfterAliasDeletion = fatal
            ),
            legacy
        )

        assertThat(runCatching { store.clear() }.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(state.keyAvailable).isFalse()
        assertThat(state.durablePendingDeletionLegacyCleanupIntent).isNull()

        state.crashProcess()
        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restarted.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(state.active).isNull()
    }

    @Test
    fun failedExplicitReplaceVerificationRestoresDeletionTombstone() = runTest {
        val state = FakeStorageState(deletionPending = true)
        val legacy = FakeLegacyOpenAiKeySource("synthetic-legacy-b")
        val store = OpenAiCredentialStore(
            FakeSecretStorage(
                state = state,
                activeReadOverrideAfterCommit = "synthetic-mismatch"
            ),
            legacy
        )

        val failure = runCatching { store.replace("synthetic-secure-c") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(state.active).isNull()
        assertThat(state.deletionPending).isTrue()
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")

        val restarted = OpenAiCredentialStore(FakeSecretStorage(state), legacy)
        assertThat(restarted.migrateLegacyKey())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
    }

    @Test
    fun promotionCommitFalseOrThrowQuarantinesProcessOnlyStateUntilRestart() = runTest {
        fakeCommitFailures().forEach { failure ->
            val stageState = FakeStorageState(active = "synthetic-secure-a")
            val failingStage = FakeSecretStorage(
                state = stageState,
                preferenceCommitFailureSite = FakePreferenceCommitSite.STAGE_PENDING,
                preferenceCommitFailure = failure
            )
            assertThat(
                runCatching {
                    failingStage.stagePending("synthetic-secure-b", "synthetic-legacy-a")
                }.exceptionOrNull()
            ).isNotNull()
            assertThat(stageState.pending).isEqualTo("synthetic-secure-b")
            assertThat(stageState.durablePending).isNull()
            stageState.crashProcess()
            assertThat(stageState.pending).isNull()

            val state = FakeStorageState(
                active = "synthetic-secure-a",
                legacyCleanupIntent = "synthetic-legacy-a",
                deletionPending = true,
                pendingDeletionLegacyCleanupIntent = "synthetic-legacy-a"
            )
            val storage = FakeSecretStorage(
                state = state,
                preferenceCommitFailureSite = FakePreferenceCommitSite.COMMIT_PENDING,
                preferenceCommitFailure = failure
            )
            storage.stagePending("synthetic-secure-b", "synthetic-legacy-a")

            assertThat(runCatching { storage.commitPending() }.exceptionOrNull()).isNotNull()

            assertThat(state.active).isEqualTo("synthetic-secure-b")
            assertThat(state.rollbackPresent).isTrue()
            assertThat(state.rollbackActive).isEqualTo("synthetic-secure-a")
            assertThat(state.deletionPending).isFalse()
            assertThat(state.durableActive).isEqualTo("synthetic-secure-a")
            assertThat(state.durablePending).isEqualTo("synthetic-secure-b")
            assertThat(state.durableRollbackPresent).isFalse()
            assertThat(state.durableDeletionPending).isTrue()

            val sameProcess = FakeSecretStorage(state)
            assertThat(runCatching { sameProcess.isDeletionPending() }.exceptionOrNull())
                .isInstanceOf(IllegalStateException::class.java)
            val sameProcessLegacy = FakeLegacyOpenAiKeySource("synthetic-legacy-a")
            val sameProcessStore = OpenAiCredentialStore(
                FakeSecretStorage(state),
                sameProcessLegacy
            )
            assertThat(
                runCatching { sameProcessStore.probeConfiguredAfterInterruption() }
                    .exceptionOrNull()
            ).isInstanceOf(IllegalStateException::class.java)
            assertThat(sameProcessLegacy.readCount).isEqualTo(0)
            assertThat(sameProcessLegacy.clearCount).isEqualTo(0)

            state.crashProcess()

            assertThat(state.active).isEqualTo("synthetic-secure-a")
            assertThat(state.pending).isEqualTo("synthetic-secure-b")
            assertThat(state.rollbackPresent).isFalse()
            assertThat(state.deletionPending).isTrue()
            assertThat(state.pendingDeletionLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")
        }
    }

    @Test
    fun rollbackCommitFalseOrThrowRestoresOnlyProcessMemoryUntilRestartRetry() = runTest {
        fakeCommitFailures().forEach { failure ->
            val state = FakeStorageState(
                active = "synthetic-secure-b",
                legacyCleanupIntent = "synthetic-legacy-a",
                rollbackPresent = true,
                rollbackActive = "synthetic-secure-a",
                rollbackDeletionPending = true,
                rollbackDeletionLegacyCleanupIntent = "synthetic-legacy-a"
            )
            val storage = FakeSecretStorage(
                state = state,
                preferenceCommitFailureSite = FakePreferenceCommitSite.ROLLBACK_PROMOTION,
                preferenceCommitFailure = failure
            )

            assertThat(runCatching { storage.rollbackPromotion() }.exceptionOrNull()).isNotNull()

            assertThat(state.active).isEqualTo("synthetic-secure-a")
            assertThat(state.rollbackPresent).isFalse()
            assertThat(state.deletionPending).isTrue()
            assertThat(state.durableActive).isEqualTo("synthetic-secure-b")
            assertThat(state.durableRollbackPresent).isTrue()
            assertThat(state.durableDeletionPending).isFalse()

            state.crashProcess()
            assertThat(state.active).isEqualTo("synthetic-secure-b")
            assertThat(state.rollbackPresent).isTrue()
            assertThat(state.deletionPending).isFalse()

            FakeSecretStorage(state).rollbackPromotion()
            assertThat(state.active).isEqualTo("synthetic-secure-a")
            assertThat(state.durableActive).isEqualTo("synthetic-secure-a")
            assertThat(state.rollbackPresent).isFalse()
            assertThat(state.durableRollbackPresent).isFalse()
            assertThat(state.deletionPending).isTrue()
            assertThat(state.durableDeletionPending).isTrue()
        }
    }

    @Test
    fun cleanupCommitFailuresRestorePendingRollbackAndCleanupIntentFromDisk() = runTest {
        fakeCommitFailures().forEach { failure ->
            val rollbackState = FakeStorageState(
                active = "synthetic-secure-b",
                rollbackPresent = true,
                rollbackActive = "synthetic-secure-a"
            )
            val rollbackStorage = FakeSecretStorage(
                state = rollbackState,
                preferenceCommitFailureSite = FakePreferenceCommitSite.DISCARD_ROLLBACK,
                preferenceCommitFailure = failure
            )
            assertThat(runCatching { rollbackStorage.discardRollback() }.exceptionOrNull())
                .isNotNull()
            assertThat(rollbackState.rollbackPresent).isFalse()
            assertThat(rollbackState.durableRollbackPresent).isTrue()
            rollbackState.crashProcess()
            assertThat(rollbackState.rollbackPresent).isTrue()

            val pendingState = FakeStorageState(
                active = "synthetic-secure-a",
                pending = "synthetic-secure-b",
                pendingLegacyCleanupIntent = "synthetic-legacy-a"
            )
            val pendingStorage = FakeSecretStorage(
                state = pendingState,
                preferenceCommitFailureSite = FakePreferenceCommitSite.CLEAR_PENDING,
                preferenceCommitFailure = failure
            )
            assertThat(runCatching { pendingStorage.clearPending() }.exceptionOrNull()).isNotNull()
            assertThat(pendingState.pending).isNull()
            assertThat(pendingState.durablePending).isEqualTo("synthetic-secure-b")
            pendingState.crashProcess()
            assertThat(pendingState.pending).isEqualTo("synthetic-secure-b")
            assertThat(pendingState.pendingLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")

            val cleanupState = FakeStorageState(
                active = "synthetic-secure-b",
                legacyCleanupIntent = "synthetic-legacy-a"
            )
            val cleanupStorage = FakeSecretStorage(
                state = cleanupState,
                preferenceCommitFailureSite =
                    FakePreferenceCommitSite.CLEAR_LEGACY_CLEANUP_INTENT,
                preferenceCommitFailure = failure
            )
            assertThat(runCatching { cleanupStorage.clearLegacyCleanupIntent() }.exceptionOrNull())
                .isNotNull()
            assertThat(cleanupState.legacyCleanupIntent).isNull()
            assertThat(cleanupState.durableLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")
            cleanupState.crashProcess()
            assertThat(cleanupState.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")
        }
    }

    @Test
    fun failedTombstoneOrSecureClearCommitPreservesDurableAliasAndRestartTruth() = runTest {
        fakeCommitFailures().forEach { failure ->
            val markerState = FakeStorageState(active = "synthetic-secure-a")
            val markerStorage = FakeSecretStorage(
                state = markerState,
                preferenceCommitFailureSite = FakePreferenceCommitSite.MARK_DELETION_PENDING,
                preferenceCommitFailure = failure
            )
            assertThat(
                runCatching {
                    markerStorage.markDeletionPending("synthetic-legacy-a")
                }.exceptionOrNull()
            ).isNotNull()
            assertThat(markerState.deletionPending).isTrue()
            assertThat(markerState.durableDeletionPending).isFalse()
            assertThat(markerState.keyAvailable).isTrue()
            assertThat(markerState.durableKeyAvailable).isTrue()
            markerState.crashProcess()
            assertThat(markerState.active).isEqualTo("synthetic-secure-a")
            assertThat(markerState.deletionPending).isFalse()
            assertThat(markerState.keyAvailable).isTrue()

            val clearState = FakeStorageState(
                active = "synthetic-secure-a",
                deletionPending = true,
                pendingDeletionLegacyCleanupIntent = "synthetic-legacy-a"
            )
            val clearStorage = FakeSecretStorage(
                state = clearState,
                preferenceCommitFailureSite = FakePreferenceCommitSite.CLEAR_ALL,
                preferenceCommitFailure = failure
            )
            assertThat(runCatching { clearStorage.clear() }.exceptionOrNull()).isNotNull()
            assertThat(clearState.active).isNull()
            assertThat(clearState.pendingDeletionLegacyCleanupIntent).isNull()
            assertThat(clearState.durableActive).isEqualTo("synthetic-secure-a")
            assertThat(clearState.durablePendingDeletionLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")
            assertThat(clearState.keyAvailable).isTrue()
            assertThat(clearState.durableKeyAvailable).isTrue()
            clearState.crashProcess()
            assertThat(clearState.active).isEqualTo("synthetic-secure-a")
            assertThat(clearState.pendingDeletionLegacyCleanupIntent)
                .isEqualTo("synthetic-legacy-a")
            assertThat(clearState.keyAvailable).isTrue()
        }
    }

    @Test
    fun missingKeystoreAliasMakesEveryExposedEncryptedRecordUnreadable() = runTest {
        val state = FakeStorageState(
            active = "synthetic-secure-a",
            pending = "synthetic-secure-b",
            pendingLegacyCleanupIntent = "synthetic-legacy-pending",
            legacyCleanupIntent = "synthetic-legacy-active",
            deletionPending = true,
            pendingDeletionLegacyCleanupIntent = "synthetic-legacy-deletion"
        )
        val storage = FakeSecretStorage(state)
        state.keyAvailable = false
        state.durableKeyAvailable = false

        assertThat(runCatching { storage.read() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { storage.readPending() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(
            runCatching { storage.readPendingLegacyCleanupIntent() }.exceptionOrNull()
        ).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { storage.readLegacyCleanupIntent() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(
            runCatching {
                storage.readPendingDeletionLegacyCleanupIntent()
            }.exceptionOrNull()
        ).isInstanceOf(IllegalStateException::class.java)

        state.crashProcess()
        val restarted = FakeSecretStorage(state)
        assertThat(runCatching { restarted.read() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { restarted.readPending() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(
            runCatching { restarted.readPendingLegacyCleanupIntent() }.exceptionOrNull()
        ).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { restarted.readLegacyCleanupIntent() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(
            runCatching {
                restarted.readPendingDeletionLegacyCleanupIntent()
            }.exceptionOrNull()
        ).isInstanceOf(IllegalStateException::class.java)
    }

    private fun fakeCommitFailures(): List<Throwable?> = listOf(
        null,
        IllegalStateException("synthetic preference commit throw")
    )

    private enum class FakePreferenceCommitSite {
        STAGE_PENDING,
        COMMIT_PENDING,
        ROLLBACK_PROMOTION,
        DISCARD_ROLLBACK,
        CLEAR_PENDING,
        CLEAR_LEGACY_CLEANUP_INTENT,
        MARK_DELETION_PENDING,
        CLEAR_ALL
    }

    private data class FakeStorageState(
        var active: String? = null,
        var pending: String? = null,
        var pendingLegacyCleanupIntent: String? = null,
        var legacyCleanupIntent: String? = null,
        var rollbackPresent: Boolean = false,
        var rollbackActive: String? = null,
        var rollbackLegacyCleanupIntent: String? = null,
        var rollbackDeletionPending: Boolean = false,
        var rollbackDeletionLegacyCleanupIntent: String? = null,
        var deletionPending: Boolean = false,
        var pendingDeletionLegacyCleanupIntent: String? = null,
        var keyAvailable: Boolean = true,
        var durableActive: String? = active,
        var durablePending: String? = pending,
        var durablePendingLegacyCleanupIntent: String? = pendingLegacyCleanupIntent,
        var durableLegacyCleanupIntent: String? = legacyCleanupIntent,
        var durableRollbackPresent: Boolean = rollbackPresent,
        var durableRollbackActive: String? = rollbackActive,
        var durableRollbackLegacyCleanupIntent: String? = rollbackLegacyCleanupIntent,
        var durableRollbackDeletionPending: Boolean = rollbackDeletionPending,
        var durableRollbackDeletionLegacyCleanupIntent: String? =
            rollbackDeletionLegacyCleanupIntent,
        var durableDeletionPending: Boolean = deletionPending,
        var durablePendingDeletionLegacyCleanupIntent: String? =
            pendingDeletionLegacyCleanupIntent,
        var durableKeyAvailable: Boolean = keyAvailable,
        var processDeletionStateQuarantined: Boolean = false,
        var keyAvailableAtLastPreferencesCommit: Boolean? = null
    ) {
        fun crashProcess() {
            active = durableActive
            pending = durablePending
            pendingLegacyCleanupIntent = durablePendingLegacyCleanupIntent
            legacyCleanupIntent = durableLegacyCleanupIntent
            rollbackPresent = durableRollbackPresent
            rollbackActive = durableRollbackActive
            rollbackLegacyCleanupIntent = durableRollbackLegacyCleanupIntent
            rollbackDeletionPending = durableRollbackDeletionPending
            rollbackDeletionLegacyCleanupIntent =
                durableRollbackDeletionLegacyCleanupIntent
            deletionPending = durableDeletionPending
            pendingDeletionLegacyCleanupIntent =
                durablePendingDeletionLegacyCleanupIntent
            keyAvailable = durableKeyAvailable
            processDeletionStateQuarantined = false
        }

        fun persistPreferences() {
            durableActive = active
            durablePending = pending
            durablePendingLegacyCleanupIntent = pendingLegacyCleanupIntent
            durableLegacyCleanupIntent = legacyCleanupIntent
            durableRollbackPresent = rollbackPresent
            durableRollbackActive = rollbackActive
            durableRollbackLegacyCleanupIntent = rollbackLegacyCleanupIntent
            durableRollbackDeletionPending = rollbackDeletionPending
            durableRollbackDeletionLegacyCleanupIntent =
                rollbackDeletionLegacyCleanupIntent
            durableDeletionPending = deletionPending
            durablePendingDeletionLegacyCleanupIntent =
                pendingDeletionLegacyCleanupIntent
        }
    }

    private class FakeSecretStorage(
        private val state: FakeStorageState,
        private val failStage: Boolean = false,
        private val pendingReadOverride: String? = null,
        private val activeReadFailure: Throwable? = null,
        private val failCommitBeforePromote: Boolean = false,
        private val commitWithoutPromote: Boolean = false,
        private val activeReadOverrideAfterCommit: String? = null,
        private val activeReadFailureAfterCommit: Throwable? = null,
        private val failClear: Boolean = false,
        private val clearFailure: Throwable? = null,
        private val markDeletionPendingFailureAfterCommit: Throwable? = null,
        private val failDeletionMarkerCommit: Boolean = false,
        private val failSecureClearPreferencesCommit: Boolean = false,
        private val clearFailureAfterPreferencesCommit: Throwable? = null,
        private val clearFailureAfterAliasDeletion: Throwable? = null,
        private val beforeStage: suspend () -> Unit = {},
        private val sharedOperations: MutableList<String>? = null,
        private val operationLabel: String = "storage",
        private val deletionMarkerCommitFailure: Throwable? = null,
        private val preferenceCommitFailureSite: FakePreferenceCommitSite? = null,
        private val preferenceCommitFailure: Throwable? = null
    ) : SecretStorage {
        val operations = mutableListOf<String>()
        var clearCount = 0
        var deletionPendingAtCommitStart: Boolean? = null
        var activeAndDeletionPendingAfterCommit: Pair<String?, Boolean>? = null
        private var promotionCommitted = false

        override suspend fun stagePending(value: String, legacyCleanupExpected: String?) {
            beforeStage()
            if (failStage) error("stage failed")
            val recreatedKey = !state.keyAvailable
            if (recreatedKey) {
                state.keyAvailable = true
                state.durableKeyAvailable = true
                operations += "recreate_key"
            }
            operations += "stage:$value"
            sharedOperations?.add("$operationLabel:stage")
            state.pending = value
            state.pendingLegacyCleanupIntent = legacyCleanupExpected
            commitPreferences(FakePreferenceCommitSite.STAGE_PENDING)
            if (recreatedKey) {
                operations += "persist_pending"
            }
        }

        override suspend fun readPending(): String? {
            operations += "read_pending"
            return readEncryptedRecord(
                pendingReadOverride ?: state.pending,
                "pending"
            )
        }

        override suspend fun readPendingLegacyCleanupIntent(): String? =
            readEncryptedRecord(state.pendingLegacyCleanupIntent, "pending cleanup")

        override suspend fun commitPending() {
            operations += "commit_pending"
            sharedOperations?.add("$operationLabel:commit")
            if (failCommitBeforePromote) error("commit failed")
            check(!state.processDeletionStateQuarantined)
            val pending = requireNotNull(
                readEncryptedRecord(state.pending, "pending")
            )
            val pendingLegacyCleanup = readEncryptedRecord(
                state.pendingLegacyCleanupIntent,
                "pending cleanup"
            )
            state.rollbackPresent = true
            state.rollbackActive = state.active
            state.rollbackLegacyCleanupIntent = state.legacyCleanupIntent
            state.rollbackDeletionPending = state.deletionPending
            state.rollbackDeletionLegacyCleanupIntent =
                state.pendingDeletionLegacyCleanupIntent
            deletionPendingAtCommitStart = state.deletionPending
            state.deletionPending = false
            state.pendingDeletionLegacyCleanupIntent = null
            if (commitWithoutPromote) return
            state.active = pending
            state.pending = null
            state.legacyCleanupIntent = pendingLegacyCleanup
            state.pendingLegacyCleanupIntent = null
            commitPreferences(FakePreferenceCommitSite.COMMIT_PENDING)
            activeAndDeletionPendingAfterCommit = state.active to state.deletionPending
            promotionCommitted = true
        }

        override suspend fun rollbackPromotion() {
            operations += "rollback_promotion"
            if (!state.rollbackPresent) return
            check(!state.processDeletionStateQuarantined)
            state.active = state.rollbackActive
            state.legacyCleanupIntent = state.rollbackLegacyCleanupIntent
            state.deletionPending = state.rollbackDeletionPending
            state.pendingDeletionLegacyCleanupIntent =
                state.rollbackDeletionLegacyCleanupIntent
            state.rollbackPresent = false
            state.rollbackActive = null
            state.rollbackLegacyCleanupIntent = null
            state.rollbackDeletionPending = false
            state.rollbackDeletionLegacyCleanupIntent = null
            commitPreferences(FakePreferenceCommitSite.ROLLBACK_PROMOTION)
            promotionCommitted = false
        }

        override suspend fun discardRollback() {
            operations += "discard_rollback"
            check(!state.processDeletionStateQuarantined)
            state.rollbackPresent = false
            state.rollbackActive = null
            state.rollbackLegacyCleanupIntent = null
            state.rollbackDeletionPending = false
            state.rollbackDeletionLegacyCleanupIntent = null
            commitPreferences(FakePreferenceCommitSite.DISCARD_ROLLBACK)
            promotionCommitted = false
        }

        override suspend fun clearPending() {
            operations += "clear_pending"
            check(!state.processDeletionStateQuarantined)
            state.pending = null
            state.pendingLegacyCleanupIntent = null
            commitPreferences(FakePreferenceCommitSite.CLEAR_PENDING)
        }

        override suspend fun readLegacyCleanupIntent(): String? =
            readEncryptedRecord(state.legacyCleanupIntent, "cleanup")

        override suspend fun clearLegacyCleanupIntent() {
            check(!state.processDeletionStateQuarantined)
            state.legacyCleanupIntent = null
            commitPreferences(FakePreferenceCommitSite.CLEAR_LEGACY_CLEANUP_INTENT)
        }

        override suspend fun read(): String? {
            operations += "read_active"
            val active = readEncryptedRecord(state.active, "active")
            if (promotionCommitted) {
                promotionCommitted = false
                activeReadFailureAfterCommit?.let { throw it }
                activeReadOverrideAfterCommit?.let { return it }
            }
            activeReadFailure?.let { throw it }
            return active
        }

        override suspend fun markDeletionPending(legacyCleanupExpected: String?) {
            check(!state.processDeletionStateQuarantined)
            sharedOperations?.add("$operationLabel:mark")
            state.deletionPending = true
            state.pendingDeletionLegacyCleanupIntent = legacyCleanupExpected
            if (failDeletionMarkerCommit || deletionMarkerCommitFailure != null) {
                failPreferenceCommit(
                    deletionMarkerCommitFailure,
                    "deletion marker commit failed"
                )
            }
            commitPreferences(FakePreferenceCommitSite.MARK_DELETION_PENDING)
            markDeletionPendingFailureAfterCommit?.let { throw it }
        }

        override suspend fun isDeletionPending(): Boolean {
            check(!state.processDeletionStateQuarantined)
            return state.deletionPending
        }

        override suspend fun readPendingDeletionLegacyCleanupIntent(): String? {
            check(!state.processDeletionStateQuarantined)
            return readEncryptedRecord(
                state.pendingDeletionLegacyCleanupIntent,
                "deletion intent"
            )
        }

        override suspend fun clear() {
            operations += "clear_all"
            clearCount += 1
            check(!state.processDeletionStateQuarantined)
            if (failClear) error("clear failed")
            clearFailure?.let { throw it }
            state.active = null
            state.pending = null
            state.pendingLegacyCleanupIntent = null
            state.legacyCleanupIntent = null
            state.rollbackPresent = false
            state.rollbackActive = null
            state.rollbackLegacyCleanupIntent = null
            state.rollbackDeletionPending = false
            state.rollbackDeletionLegacyCleanupIntent = null
            state.pendingDeletionLegacyCleanupIntent = null
            state.deletionPending = true
            state.keyAvailableAtLastPreferencesCommit = state.keyAvailable
            if (failSecureClearPreferencesCommit) {
                failPreferenceCommit(
                    failure = null,
                    failureMessage = "secure clear preferences commit failed"
                )
            }
            commitPreferences(FakePreferenceCommitSite.CLEAR_ALL)
            clearFailureAfterPreferencesCommit?.let { throw it }
            state.keyAvailable = false
            state.durableKeyAvailable = false
            clearFailureAfterAliasDeletion?.let { throw it }
            promotionCommitted = false
        }

        private fun commitPreferences(site: FakePreferenceCommitSite) {
            check(!state.processDeletionStateQuarantined)
            if (preferenceCommitFailureSite == site) {
                failPreferenceCommit(
                    preferenceCommitFailure,
                    "preference commit failed"
                )
            }
            state.persistPreferences()
        }

        private fun readEncryptedRecord(value: String?, recordName: String): String? {
            if (value != null && !state.keyAvailable) {
                error("encrypted $recordName key unavailable")
            }
            return value
        }

        private fun failPreferenceCommit(
            failure: Throwable?,
            failureMessage: String
        ): Nothing {
            state.processDeletionStateQuarantined = true
            sharedOperations?.add("$operationLabel:quarantine")
            failure?.let { throw it }
            error(failureMessage)
        }
    }

    private class FakeLegacyOpenAiKeySource(
        initialValue: String?,
        var failClear: Boolean = false,
        private val sharedOperations: MutableList<String>? = null
    ) : LegacyOpenAiKeySource {
        var value: String? = initialValue
        var readCount = 0
        var clearCount = 0
        var beforeClear: (() -> Unit)? = null
        val clearExpectedValues = mutableListOf<String?>()

        override suspend fun read(): String? {
            readCount += 1
            return value
        }

        override suspend fun clearIfMatches(expected: String): Boolean {
            clearCount += 1
            sharedOperations?.add("legacy:clear")
            clearExpectedValues += expected
            beforeClear?.invoke()
            if (failClear) error("clear failed")
            if (value?.trim() != expected) return false
            value = null
            return true
        }
    }
}
