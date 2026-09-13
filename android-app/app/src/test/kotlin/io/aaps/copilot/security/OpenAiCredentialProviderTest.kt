package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class OpenAiCredentialProviderTest {

    @Test
    fun firstCredentialRead_waitsForOneMigrationAndReturnsSecureValue() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource("sk-migrated")
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        assertThat(provider.requireCredential()).isEqualTo("sk-migrated")
        assertThat(provider.requireCredential()).isEqualTo("sk-migrated")

        assertThat(secure.stageCount).isEqualTo(1)
        assertThat(legacy.clearCount).isEqualTo(1)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false
            )
        )
        assertThat(provider.status.value.toString()).doesNotContain("sk-")
    }

    @Test
    fun failedMigration_keepsLegacyAndDisablesCredentialReads() = runTest {
        val secure = FakeSecretStorage(failStage = true)
        val legacy = FakeLegacySource("sk-legacy")
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val result = provider.ensureMigrated()
        val failure = runCatching { provider.requireCredential() }.exceptionOrNull()

        assertThat(result).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).doesNotContain("sk-legacy")
        assertThat(legacy.value).isEqualTo("sk-legacy")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
    }

    @Test
    fun transientFailedMigrationRetriesOnlyOnExplicitEnsure() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource("synthetic-legacy-a").apply {
            readFailure = IllegalStateException("synthetic transient legacy read failure")
        }
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
        val readsAfterFailure = legacy.readCount
        val clearsAfterFailure = legacy.clearCount
        val stagesAfterFailure = secure.stageCount
        legacy.readFailure = null

        val routineFailure = runCatching { provider.requireCredential() }.exceptionOrNull()

        assertThat(legacy.readCount).isEqualTo(readsAfterFailure)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterFailure)
        assertThat(secure.stageCount).isEqualTo(stagesAfterFailure)
        assertThat(routineFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(routineFailure?.message).isEqualTo("OpenAI credential migration failed")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.readCount).isEqualTo(readsAfterFailure + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterFailure + 1)
        assertThat(secure.stageCount).isEqualTo(stagesAfterFailure + 1)
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
    }

    @Test
    fun configuredReadFailureAfterMigrationRetriesOnlyOnExplicitEnsure() = runTest {
        val secure = FakeSecretStorage().apply {
            failReadAfter(
                successfulReads = 2,
                failure = IllegalStateException("synthetic configured read failure")
            )
        }
        val legacy = FakeLegacySource("synthetic-legacy-a")
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(secure.active).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.value).isNull()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
        val readsAfterFailure = legacy.readCount
        val clearsAfterFailure = legacy.clearCount
        val stagesAfterFailure = secure.stageCount

        val routineFailure = runCatching { provider.requireCredential() }.exceptionOrNull()

        assertThat(legacy.readCount).isEqualTo(readsAfterFailure)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterFailure)
        assertThat(secure.stageCount).isEqualTo(stagesAfterFailure)
        assertThat(routineFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(routineFailure?.message).isEqualTo("OpenAI credential migration failed")

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.ALREADY_SECURE)
        assertThat(legacy.readCount).isEqualTo(readsAfterFailure + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterFailure)
        assertThat(secure.stageCount).isEqualTo(stagesAfterFailure)
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
    }

    @Test
    fun sourceChangedMigrationKeepsVerifiedCredentialUsableAndCanBeReevaluated() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource("synthetic-legacy-a").apply {
            beforeClear = { value = "synthetic-legacy-b" }
        }
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val result = provider.ensureMigrated()
        val readsAfterConflict = legacy.readCount
        val clearsAfterConflict = legacy.clearCount

        assertThat(result.name).isEqualTo("SOURCE_CHANGED")
        assertThat(secure.active).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.readCount).isEqualTo(readsAfterConflict)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterConflict)

        legacy.beforeClear = null
        legacy.value = "synthetic-legacy-a"

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
    }

    @Test
    fun legacyReadFailureWithVerifiedActiveIsCleanupUncertainAndRetryable() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val writer = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        writer.ensureMigrated()
        writer.replace("synthetic-secure-a")
        legacy.value = "synthetic-secure-a"
        legacy.readFailure = IllegalStateException("synthetic legacy read failure")
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val result = provider.ensureMigrated()
        val readsAfterUncertainty = legacy.readCount
        val clearsAfterUncertainty = legacy.clearCount

        assertThat(result.name).isEqualTo("CLEANUP_UNCERTAIN")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-a")
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-a")
        assertThat(legacy.value).isEqualTo("synthetic-secure-a")
        assertThat(legacy.readCount).isEqualTo(readsAfterUncertainty)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterUncertainty)

        legacy.readFailure = null

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-a")
    }

    @Test
    fun compareClearFailureAfterPromotionIsCleanupUncertainAndRetryable() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource("synthetic-legacy-a").apply {
            clearFailure = IllegalStateException("synthetic compare-clear failure")
        }
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val result = provider.ensureMigrated()
        val readsAfterUncertainty = legacy.readCount
        val clearsAfterUncertainty = legacy.clearCount

        assertThat(result.name).isEqualTo("CLEANUP_UNCERTAIN")
        assertThat(secure.active).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.readCount).isEqualTo(readsAfterUncertainty)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterUncertainty)

        legacy.clearFailure = null

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
    }

    @Test
    fun replaceSourceMutationAfterPromotionKeepsNewCredentialUsableAndNewerLegacy() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        legacy.value = "synthetic-legacy-a"
        legacy.beforeClear = { legacy.value = "synthetic-legacy-b" }

        provider.replace("  synthetic-secure-c  ")

        assertThat(secure.active).isEqualTo("synthetic-secure-c")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-c")
    }

    @Test
    fun replaceAndDelete_refreshOnlyNonSecretStatus() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        provider.ensureMigrated()
        provider.replace("  sk-new  ")

        assertThat(provider.requireCredential()).isEqualTo("sk-new")
        assertThat(provider.status.value.configured).isTrue()
        assertThat(provider.status.value.toString()).doesNotContain("sk-new")

        provider.delete()

        assertThat(provider.status.value.configured).isFalse()
        assertThat(runCatching { provider.requireCredential() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun replaceCancellationAndFatal_clearBusyAndRethrowOriginal() = runTest {
        listOf<Throwable>(
            CancellationException("cancel replace"),
            AssertionError("fatal replace")
        ).forEach { failure ->
            val secure = FakeSecretStorage(stageFailure = failure)
            val provider = OpenAiCredentialProvider(
                OpenAiCredentialStore(secure, FakeLegacySource(null))
            )
            provider.ensureMigrated()

            val thrown = captureThrowable {
                provider.replace("sk-never-expose")
            }

            assertSame(failure, thrown)
            assertThat(provider.status.value.busy).isFalse()
            assertThat(provider.status.value.toString()).doesNotContain("sk-never-expose")
        }
    }

    @Test
    fun deleteRecoveryFailureRetriesOnlyOnExplicitEnsureAndRethrowsOriginal() = runTest {
        listOf<Throwable>(
            CancellationException("cancel delete"),
            AssertionError("fatal delete")
        ).forEach { failure ->
            val secure = FakeSecretStorage()
            val legacy = FakeLegacySource(null)
            val provider = OpenAiCredentialProvider(
                OpenAiCredentialStore(secure, legacy)
            )
            provider.ensureMigrated()
            provider.replace("sk-existing")
            secure.clearFailure = failure

            val thrown = captureThrowable {
                provider.delete()
            }

            assertSame(failure, thrown)
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true
                )
            )
            assertThat(provider.status.value.toString()).doesNotContain("sk-existing")
            val legacyReadsAfterRecovery = legacy.readCount
            val legacyClearsAfterRecovery = legacy.clearCount
            val stagesAfterRecovery = secure.stageCount

            secure.clearFailure = null
            val routineFailure = captureThrowable { provider.requireCredential() }

            assertThat(routineFailure.message).isEqualTo("OpenAI credential migration failed")
            assertThat(legacy.readCount).isEqualTo(legacyReadsAfterRecovery)
            assertThat(legacy.clearCount).isEqualTo(legacyClearsAfterRecovery)
            assertThat(secure.stageCount).isEqualTo(stagesAfterRecovery)
            assertThat(secure.active).isEqualTo("sk-existing")
            assertThat(secure.deletionPending).isTrue()
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true
                )
            )

            assertThat(provider.ensureMigrated())
                .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
            assertThat(legacy.readCount).isEqualTo(legacyReadsAfterRecovery)
            assertThat(legacy.clearCount).isEqualTo(legacyClearsAfterRecovery)
            assertThat(secure.stageCount).isEqualTo(stagesAfterRecovery)
            assertThat(secure.active).isNull()
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(configured = false, busy = false)
            )
        }
    }

    @Test
    fun migrationCancellationAndFatal_clearBusyAndRethrowOriginal() = runTest {
        listOf<Throwable>(
            CancellationException("cancel migration"),
            AssertionError("fatal migration")
        ).forEach { failure ->
            val secure = FakeSecretStorage(stageFailure = failure)
            val provider = OpenAiCredentialProvider(
                OpenAiCredentialStore(secure, FakeLegacySource("sk-legacy"))
            )

            val thrown = captureThrowable {
                provider.ensureMigrated()
            }

            assertSame(failure, thrown)
            assertThat(provider.status.value.busy).isFalse()
            assertThat(provider.status.value.toString()).doesNotContain("sk-legacy")
        }
    }

    @Test
    fun replaceCancellationAfterCommitRethrowsAfterConfiguredTerminalization() = runTest {
        val cancellation = CancellationException("cancel legacy cleanup after replace")
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        legacy.value = "sk-legacy"
        legacy.clearFailure = cancellation

        val thrown = captureThrowable {
            provider.replace("sk-committed")
        }

        assertSame(cancellation, thrown)
        assertThat(secure.active).isEqualTo("sk-committed")
        assertThat(legacy.value).isEqualTo("sk-legacy")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("sk-committed")
    }

    @Test
    fun replaceFatalAfterCommitRethrowsAndRefreshesConfiguredStatus() = runTest {
        val fatal = AssertionError("fatal legacy cleanup after replace")
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        legacy.value = "sk-legacy"
        legacy.clearFailure = fatal

        val thrown = captureThrowable {
            provider.replace("sk-committed")
        }

        assertSame(fatal, thrown)
        assertThat(secure.active).isEqualTo("sk-committed")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
    }

    @Test
    fun deleteCancellationAndFatalDuringLegacyClearRemainFailClosedUntilExplicitRetry() = runTest {
        listOf<Throwable>(
            CancellationException("cancel legacy clear after delete"),
            AssertionError("fatal legacy clear after delete")
        ).forEach { failure ->
            val secure = FakeSecretStorage()
            val legacy = FakeLegacySource(null)
            val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
            provider.ensureMigrated()
            provider.replace("sk-existing")
            legacy.value = "sk-legacy"
            legacy.clearFailure = failure

            val thrown = captureThrowable {
                provider.delete()
            }

            assertSame(failure, thrown)
            assertThat(secure.active).isEqualTo("sk-existing")
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true
                )
            )
            val readsAfterRecovery = legacy.readCount
            val clearsAfterRecovery = legacy.clearCount
            legacy.clearFailure = null

            assertThat(runCatching { provider.requireCredential() }.exceptionOrNull()?.message)
                .isEqualTo("OpenAI credential migration failed")
            assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
            assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)

            assertThat(provider.ensureMigrated())
                .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
            assertThat(secure.active).isNull()
            assertThat(legacy.value).isNull()
        }
    }

    @Test
    fun migrationCancellationAndFatalAfterPromotion_refreshConfiguredStatus() = runTest {
        listOf<Throwable>(
            CancellationException("cancel legacy clear after migration"),
            AssertionError("fatal legacy clear after migration")
        ).forEach { failure ->
            val secure = FakeSecretStorage()
            val legacy = FakeLegacySource("sk-legacy").apply {
                clearFailure = failure
            }
            val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

            val thrown = captureThrowable {
                provider.ensureMigrated()
            }

            assertSame(failure, thrown)
            assertThat(secure.active).isEqualTo("sk-legacy")
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(
                    configured = true,
                    busy = false,
                    legacyCleanupPending = true
                )
            )
        }
    }

    @Test
    fun replaceInterruptedPromotedRead_probesPersistedActiveState() = runTest {
        listOf<Throwable>(
            CancellationException("cancel promoted read"),
            AssertionError("fatal promoted read")
        ).forEach { failure ->
            val secure = FakeSecretStorage()
            val provider = OpenAiCredentialProvider(
                OpenAiCredentialStore(secure, FakeLegacySource(null))
            )
            provider.ensureMigrated()
            secure.failReadAfter(successfulReads = 1, failure = failure)

            val thrown = captureThrowable {
                provider.replace("sk-promoted")
            }

            assertSame(failure, thrown)
            assertThat(secure.active).isEqualTo("sk-promoted")
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(
                    configured = true,
                    busy = false
                )
            )
        }
    }

    @Test
    fun promotedReadCancellationRearmsSameProviderExactCleanupRetry() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        provider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        val cancellation = CancellationException("synthetic promoted read cancellation")
        secure.failReadAfter(successfulReads = 1, failure = cancellation)

        val thrown = captureThrowable {
            provider.replace("synthetic-secure-b")
        }

        assertSame(cancellation, thrown)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        val clearsBeforeExplicitRetry = legacy.clearCount
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.clearCount).isEqualTo(clearsBeforeExplicitRetry)

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = true, busy = false)
        )
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(provider.status.value.toString()).doesNotContain("synthetic-legacy-a")
    }

    @Test
    fun rollbackDiscardFatalRearmsSameProviderRetryWithoutDeletingChangedSource() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        provider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        val fatal = AssertionError("synthetic rollback discard fatal")
        secure.failNextDiscardRollback(fatal)

        val thrown = captureThrowable {
            provider.replace("synthetic-secure-b")
        }

        assertSame(fatal, thrown)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        legacy.value = "synthetic-legacy-c"
        val clearsBeforeExplicitRetry = legacy.clearCount
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(legacy.clearCount).isEqualTo(clearsBeforeExplicitRetry)

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.SOURCE_CHANGED)
        assertThat(legacy.value).isEqualTo("synthetic-legacy-c")
        assertThat(legacy.clearCount).isEqualTo(clearsBeforeExplicitRetry)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(provider.status.value.toString()).doesNotContain("synthetic-legacy-a")
    }

    @Test
    fun secureReadRecoveryRearmsSameProviderCleanupRetry() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        secure.active = "synthetic-secure-b"
        secure.legacyCleanupIntent = "synthetic-legacy-a"
        legacy.value = "synthetic-legacy-a"
        secure.failReadAfter(
            successfulReads = 0,
            failure = IllegalStateException("synthetic active read failure")
        )

        val thrown = captureThrowable {
            provider.requireCredential()
        }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                readFailed = true,
                legacyCleanupPending = true
            )
        )

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = true, busy = false)
        )
    }

    @Test
    fun secureReadRecoveryPreservesNonDurableSourceChangedRetry() = runTest {
        val secure = FakeSecretStorage().apply {
            active = "synthetic-secure-a"
        }
        val legacy = FakeLegacySource("synthetic-legacy-b")
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.SOURCE_CHANGED)
        assertThat(secure.legacyCleanupIntent).isNull()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        val readsAfterConflict = legacy.readCount
        val clearsAfterConflict = legacy.clearCount
        secure.failReadAfter(
            successfulReads = 0,
            failure = IllegalStateException("synthetic one-shot secure read failure")
        )

        val readFailure = captureThrowable { provider.requireCredential() }

        assertThat(readFailure.message).isEqualTo("OpenAI credential read failed")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                readFailed = true,
                legacyCleanupPending = true
            )
        )
        assertThat(legacy.readCount).isEqualTo(readsAfterConflict)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterConflict)

        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-a")
        assertThat(legacy.readCount).isEqualTo(readsAfterConflict)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterConflict)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.SOURCE_CHANGED)
        assertThat(legacy.readCount).isEqualTo(readsAfterConflict + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterConflict)
        assertThat(legacy.value).isEqualTo("synthetic-legacy-b")
        assertThat(provider.status.value.toString()).doesNotContain("synthetic-secure-a")
        assertThat(provider.status.value.toString()).doesNotContain("synthetic-legacy-b")
    }

    @Test
    fun migrationInterruptedPromotedRead_probesPersistedActiveState() = runTest {
        listOf<Throwable>(
            CancellationException("cancel migrated read"),
            AssertionError("fatal migrated read")
        ).forEach { failure ->
            val secure = FakeSecretStorage().apply {
                failReadAfter(successfulReads = 1, failure = failure)
            }
            val provider = OpenAiCredentialProvider(
                OpenAiCredentialStore(secure, FakeLegacySource("sk-legacy"))
            )

            val thrown = captureThrowable {
                provider.ensureMigrated()
            }

            assertSame(failure, thrown)
            assertThat(secure.active).isEqualTo("sk-legacy")
            assertThat(provider.status.value).isEqualTo(
                OpenAiCredentialStatus(
                    configured = true,
                    busy = false,
                    legacyCleanupPending = true
                )
            )
        }
    }

    @Test
    fun coldMigrationCancellationDefersLegacyCleanupUntilExplicitRetry() = runTest {
        assertColdMigrationInterruptionDefersLegacyCleanupUntilExplicitRetry(
            CancellationException("synthetic cold migration cancellation")
        )
    }

    @Test
    fun coldMigrationFatalDefersLegacyCleanupUntilExplicitRetry() = runTest {
        assertColdMigrationInterruptionDefersLegacyCleanupUntilExplicitRetry(
            AssertionError("synthetic cold migration fatal")
        )
    }

    @Test
    fun verifiedActiveLegacyReadCancellationDefersRetryUntilExplicitEnsure() = runTest {
        assertVerifiedActiveLegacyReadInterruptionDefersRetryUntilExplicitEnsure(
            CancellationException("synthetic verified-active legacy read cancellation")
        )
    }

    @Test
    fun verifiedActiveLegacyReadFatalDefersRetryUntilExplicitEnsure() = runTest {
        assertVerifiedActiveLegacyReadInterruptionDefersRetryUntilExplicitEnsure(
            AssertionError("synthetic verified-active legacy read fatal")
        )
    }

    @Test
    fun unconfiguredMigrationReadCancellationDefersRetryUntilExplicitEnsure() = runTest {
        assertUnconfiguredMigrationReadInterruptionDefersRetryUntilExplicitEnsure(
            CancellationException("synthetic unconfigured migration read cancellation")
        )
    }

    @Test
    fun unconfiguredMigrationReadFatalDefersRetryUntilExplicitEnsure() = runTest {
        assertUnconfiguredMigrationReadInterruptionDefersRetryUntilExplicitEnsure(
            AssertionError("synthetic unconfigured migration read fatal")
        )
    }

    @Test
    fun unconfiguredDeleteInterruptionCannotReimportLegacyDuringRoutineRead() = runTest {
        val cancellation = CancellationException("synthetic pre-tombstone delete cancellation")
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource("synthetic-legacy-a").apply {
            clearFailure = cancellation
        }
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val thrown = captureThrowable {
            provider.delete()
        }

        assertSame(cancellation, thrown)
        assertThat(secure.active).isNull()
        assertThat(secure.deletionPending).isTrue()
        assertThat(secure.pendingDeletionLegacyCleanupIntent)
            .isEqualTo("synthetic-legacy-a")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
        val readsAfterRecovery = legacy.readCount
        val clearsAfterRecovery = legacy.clearCount
        val migrationStagesAfterRecovery = secure.stageCount
        legacy.clearFailure = null

        val routineResult = runCatching { provider.requireCredential() }

        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)
        assertThat(secure.stageCount).isEqualTo(migrationStagesAfterRecovery)
        assertThat(routineResult.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(routineResult.exceptionOrNull()?.message)
            .isEqualTo("OpenAI credential migration failed")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery + 1)
        assertThat(secure.stageCount).isEqualTo(migrationStagesAfterRecovery)
        assertThat(legacy.value).isNull()
        assertThat(runCatching { provider.requireCredential() }.exceptionOrNull()?.message)
            .isEqualTo("OpenAI credential is not configured")
    }

    @Test
    fun corruptCredential_canBeResetThenReplaced() = runTest {
        val secure = FakeSecretStorage(readFailure = IllegalStateException("corrupt"))
        val provider = OpenAiCredentialProvider(
            OpenAiCredentialStore(secure, FakeLegacySource(null))
        )

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(provider.status.value.migrationFailed).isTrue()

        provider.delete()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = false, busy = false)
        )

        provider.replace("sk-recovered")
        assertThat(provider.requireCredential()).isEqualTo("sk-recovered")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false
            )
        )
    }

    @Test
    fun ordinaryReplaceCleanupExceptionDoesNotReportUpdateFailure() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        legacy.value = "sk-legacy"
        legacy.clearFailure = IllegalStateException("legacy clear failed with sk-secret")

        provider.replace("sk-committed")

        assertThat(secure.active).isEqualTo("sk-committed")
        assertThat(legacy.value).isEqualTo("sk-legacy")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        assertThat(provider.requireCredential()).isEqualTo("sk-committed")
        assertThat(provider.status.value.toString()).doesNotContain("sk-")
    }

    @Test
    fun replacementCleanupExceptionPersistsExactIntentAndRestartRetriesUnchangedLegacy() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val firstProvider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        firstProvider.ensureMigrated()
        firstProvider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        legacy.clearFailure = IllegalStateException("synthetic cleanup failure")

        firstProvider.replace("synthetic-secure-b")

        assertThat(firstProvider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(firstProvider.status.value.legacyCleanupPending).isTrue()
        assertThat(secure.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")

        legacy.clearFailure = null
        val restartedSecure = secure.restarted()
        val restarted = OpenAiCredentialProvider(
            OpenAiCredentialStore(restartedSecure, legacy)
        )

        assertThat(restarted.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(restartedSecure.legacyCleanupIntent).isNull()
        assertThat(restarted.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(restarted.status.value.legacyCleanupPending).isFalse()
    }

    @Test
    fun replacementCleanupCancellationPersistsExactIntentAcrossProviderRestart() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val firstProvider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        firstProvider.ensureMigrated()
        firstProvider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        val cancellation = CancellationException("synthetic cleanup cancellation")
        legacy.clearFailure = cancellation

        val thrown = captureThrowable {
            firstProvider.replace("synthetic-secure-b")
        }

        assertSame(cancellation, thrown)
        assertThat(firstProvider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(firstProvider.status.value.legacyCleanupPending).isTrue()
        assertThat(secure.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")

        legacy.clearFailure = null
        val restartedSecure = secure.restarted()
        val restarted = OpenAiCredentialProvider(
            OpenAiCredentialStore(restartedSecure, legacy)
        )

        assertThat(restarted.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.value).isNull()
        assertThat(restartedSecure.legacyCleanupIntent).isNull()
        assertThat(restarted.requireCredential()).isEqualTo("synthetic-secure-b")
    }

    @Test
    fun replacementCleanupRetryAcceptsCapturedLegacyAlreadyAbsent() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val firstProvider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        firstProvider.ensureMigrated()
        firstProvider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        legacy.clearFailure = IllegalStateException("synthetic cleanup failure")

        firstProvider.replace("synthetic-secure-b")
        assertThat(secure.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")
        legacy.clearFailure = null
        legacy.value = null

        val restartedSecure = secure.restarted()
        val restarted = OpenAiCredentialProvider(
            OpenAiCredentialStore(restartedSecure, legacy)
        )

        assertThat(restarted.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.ALREADY_SECURE)
        assertThat(restartedSecure.legacyCleanupIntent).isNull()
        assertThat(restarted.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(restarted.status.value.legacyCleanupPending).isFalse()
    }

    @Test
    fun replacementCleanupRetryNeverDeletesChangedLegacySource() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val firstProvider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        firstProvider.ensureMigrated()
        firstProvider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        legacy.clearFailure = IllegalStateException("synthetic cleanup failure")

        firstProvider.replace("synthetic-secure-b")
        assertThat(secure.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")
        legacy.clearFailure = null
        legacy.value = "synthetic-legacy-c"

        val restartedSecure = secure.restarted()
        val restarted = OpenAiCredentialProvider(
            OpenAiCredentialStore(restartedSecure, legacy)
        )

        assertThat(restarted.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.SOURCE_CHANGED)
        assertThat(legacy.value).isEqualTo("synthetic-legacy-c")
        assertThat(legacy.clearCount).isEqualTo(1)
        assertThat(restartedSecure.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")
        assertThat(restarted.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(restarted.status.value.legacyCleanupPending).isTrue()
    }

    @Test
    fun cleanupIntentWriteFailurePreventsReplacementCommit() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        provider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        secure.cleanupIntentStageFailure = IllegalStateException("synthetic intent write failure")

        val thrown = captureThrowable {
            provider.replace("synthetic-secure-b")
        }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown.message).isEqualTo("OpenAI credential update failed")
        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
        assertThat(secure.legacyCleanupIntent).isNull()
    }

    @Test
    fun cleanupIntentClearFailureRemainsPendingAfterLegacyRemovalAndRetries() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val firstProvider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        firstProvider.ensureMigrated()
        firstProvider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        secure.cleanupIntentClearFailure = IllegalStateException("synthetic intent clear failure")

        firstProvider.replace("synthetic-secure-b")

        assertThat(legacy.value).isNull()
        assertThat(secure.legacyCleanupIntent).isEqualTo("synthetic-legacy-a")
        assertThat(firstProvider.requireCredential()).isEqualTo("synthetic-secure-b")
        assertThat(firstProvider.status.value.legacyCleanupPending).isTrue()

        secure.cleanupIntentClearFailure = null
        val restartedSecure = secure.restarted()
        val restarted = OpenAiCredentialProvider(
            OpenAiCredentialStore(restartedSecure, legacy)
        )

        assertThat(restarted.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.ALREADY_SECURE)
        assertThat(restartedSecure.legacyCleanupIntent).isNull()
        assertThat(restarted.requireCredential()).isEqualTo("synthetic-secure-b")
    }

    @Test
    fun ordinaryDeleteFailureDuringLegacyClearRemainsFailClosedUntilExplicitRetry() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        provider.replace("sk-existing")
        legacy.value = "sk-legacy"
        legacy.clearFailure = IllegalStateException("legacy clear failed with sk-secret")

        val thrown = captureThrowable {
            provider.delete()
        }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown.message).isEqualTo("OpenAI credential deletion failed")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
        assertThat(provider.status.value.toString()).doesNotContain("sk-")
        val readsAfterRecovery = legacy.readCount
        val clearsAfterRecovery = legacy.clearCount
        legacy.clearFailure = null

        assertThat(runCatching { provider.requireCredential() }.exceptionOrNull()?.message)
            .isEqualTo("OpenAI credential migration failed")
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(secure.active).isNull()
        assertThat(legacy.value).isNull()
    }

    @Test
    fun failedDeletionMarkerCommitCannotAuthorizeProviderRecoveryMutation() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        provider.replace("synthetic-secure-a")
        legacy.value = "synthetic-legacy-a"
        secure.failNextDeletionMarkerCommit()

        val thrown = captureThrowable { provider.delete() }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown.message).isEqualTo("OpenAI credential deletion failed")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.clearCount).isEqualTo(0)
        assertThat(secure.durableActive).isEqualTo("synthetic-secure-a")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = false,
                busy = false,
                migrationFailed = true
            )
        )
        val readsAfterRecovery = legacy.readCount
        val clearsAfterRecovery = legacy.clearCount

        assertThat(runCatching { provider.requireCredential() }.exceptionOrNull()?.message)
            .isEqualTo("OpenAI credential migration failed")
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)

        secure.crashProcess()
        val restarted = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        assertThat(restarted.requireCredential()).isEqualTo("synthetic-secure-a")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")
    }

    @Test
    fun cancelledCleanupAfterVerifiedReplaceDoesNotProbeOrFailStatus() = runTest {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource(null)
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))
        provider.ensureMigrated()
        legacy.value = "sk-legacy"
        val cancellation = CancellationException("cancel after commit")
        legacy.clearFailure = cancellation
        secure.stallReadAfter(successfulReads = 2)

        val thrown = captureThrowable {
            provider.replace("sk-committed")
        }

        assertSame(cancellation, thrown)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        secure.clearReadStall()
        assertThat(provider.requireCredential()).isEqualTo("sk-committed")
    }

    private suspend fun captureThrowable(block: suspend () -> Unit): Throwable {
        var captured: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            captured = failure
        }
        return requireNotNull(captured) { "Expected operation to throw" }
    }

    private suspend fun assertColdMigrationInterruptionDefersLegacyCleanupUntilExplicitRetry(
        failure: Throwable
    ) {
        val secure = FakeSecretStorage().apply {
            failReadAfter(successfulReads = 1, failure = failure)
        }
        val legacy = FakeLegacySource("synthetic-legacy-a")
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val thrown = captureThrowable {
            provider.ensureMigrated()
        }

        assertSame(failure, thrown)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = true
            )
        )
        val readsAfterRecovery = legacy.readCount
        val clearsAfterRecovery = legacy.clearCount

        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery + 1)
        assertThat(legacy.value).isNull()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = true, busy = false)
        )
        assertThat(provider.status.value.toString()).doesNotContain("synthetic-legacy-a")
    }

    private suspend fun assertVerifiedActiveLegacyReadInterruptionDefersRetryUntilExplicitEnsure(
        failure: Throwable
    ) {
        val secure = FakeSecretStorage().apply {
            active = "synthetic-secure-a"
        }
        val legacy = FakeLegacySource(null).apply {
            readFailure = failure
        }
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val thrown = captureThrowable {
            provider.ensureMigrated()
        }

        assertSame(failure, thrown)
        assertThat(secure.legacyCleanupIntent).isNull()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = true, busy = false)
        )
        val readsAfterRecovery = legacy.readCount
        val clearsAfterRecovery = legacy.clearCount
        legacy.readFailure = null

        assertThat(provider.requireCredential()).isEqualTo("synthetic-secure-a")
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)

        assertThat(provider.ensureMigrated())
            .isEqualTo(CredentialMigrationResult.ALREADY_SECURE)
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = true, busy = false)
        )
        assertThat(provider.status.value.toString()).doesNotContain("synthetic-secure-a")
    }

    private suspend fun assertUnconfiguredMigrationReadInterruptionDefersRetryUntilExplicitEnsure(
        failure: Throwable
    ) {
        val secure = FakeSecretStorage()
        val legacy = FakeLegacySource("synthetic-legacy-a").apply {
            readFailure = failure
        }
        val provider = OpenAiCredentialProvider(OpenAiCredentialStore(secure, legacy))

        val thrown = captureThrowable {
            provider.ensureMigrated()
        }

        assertSame(failure, thrown)
        assertThat(secure.active).isNull()
        assertThat(secure.legacyCleanupIntent).isNull()
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = false, busy = false)
        )
        val readsAfterRecovery = legacy.readCount
        val clearsAfterRecovery = legacy.clearCount
        val migrationStagesAfterRecovery = secure.stageCount
        legacy.readFailure = null

        val routineResult = runCatching { provider.requireCredential() }

        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery)
        assertThat(secure.stageCount).isEqualTo(migrationStagesAfterRecovery)
        assertThat(routineResult.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(routineResult.exceptionOrNull()?.message)
            .isEqualTo("OpenAI credential is not configured")
        assertThat(legacy.value).isEqualTo("synthetic-legacy-a")

        assertThat(provider.ensureMigrated()).isEqualTo(CredentialMigrationResult.MIGRATED)
        assertThat(legacy.readCount).isEqualTo(readsAfterRecovery + 1)
        assertThat(legacy.clearCount).isEqualTo(clearsAfterRecovery + 1)
        assertThat(secure.stageCount).isEqualTo(migrationStagesAfterRecovery + 1)
        assertThat(legacy.value).isNull()
        assertThat(provider.requireCredential()).isEqualTo("synthetic-legacy-a")
        assertThat(provider.status.value).isEqualTo(
            OpenAiCredentialStatus(configured = true, busy = false)
        )
    }

    private class FakeLegacySource(
        var value: String?
    ) : LegacyOpenAiKeySource {
        var readCount = 0
        var clearCount = 0
        var readFailure: Throwable? = null
        var clearFailure: Throwable? = null
        var beforeClear: (() -> Unit)? = null

        override suspend fun read(): String? {
            readCount += 1
            readFailure?.let { throw it }
            return value
        }

        override suspend fun clearIfMatches(expected: String): Boolean {
            clearCount += 1
            beforeClear?.invoke()
            clearFailure?.let { throw it }
            if (value?.trim() != expected) return false
            value = null
            return true
        }
    }

    private class FakeSecretStorage(
        private val failStage: Boolean = false,
        private var stageFailure: Throwable? = null,
        private var readFailure: Throwable? = null
    ) : SecretStorage {
        var active: String? = null
        var pending: String? = null
        private var pendingLegacyCleanupIntent: String? = null
        var deletionPending = false
        var pendingDeletionLegacyCleanupIntent: String? = null
        var durableActive: String? = null
        private var durableDeletionPending = false
        private var durablePendingDeletionLegacyCleanupIntent: String? = null
        private var failDeletionMarkerCommit = false
        private var processDeletionStateQuarantined = false
        var stageCount = 0
        var clearFailure: Throwable? = null
        var legacyCleanupIntent: String? = null
        var cleanupIntentStageFailure: Throwable? = null
        var cleanupIntentClearFailure: Throwable? = null
        private var successfulReadsBeforeFailure: Int? = null
        private var scheduledReadFailure: Throwable? = null
        private var successfulReadsBeforeStall: Int? = null
        private var rollbackPresent = false
        private var rollbackActive: String? = null
        private var rollbackDeletionPending = false
        private var rollbackDeletionLegacyCleanupIntent: String? = null
        private var rollbackLegacyCleanupIntent: String? = null
        private var discardRollbackFailure: Throwable? = null

        fun failReadAfter(successfulReads: Int, failure: Throwable) {
            successfulReadsBeforeFailure = successfulReads
            scheduledReadFailure = failure
        }

        fun stallReadAfter(successfulReads: Int) {
            successfulReadsBeforeStall = successfulReads
        }

        fun clearReadStall() {
            successfulReadsBeforeStall = null
        }

        fun failNextDiscardRollback(failure: Throwable) {
            discardRollbackFailure = failure
        }

        fun failNextDeletionMarkerCommit() {
            failDeletionMarkerCommit = true
        }

        fun crashProcess() {
            active = durableActive
            deletionPending = durableDeletionPending
            pendingDeletionLegacyCleanupIntent =
                durablePendingDeletionLegacyCleanupIntent
            processDeletionStateQuarantined = false
        }

        fun restarted(): FakeSecretStorage = FakeSecretStorage().also { restarted ->
            restarted.active = active
            restarted.pending = pending
            restarted.pendingLegacyCleanupIntent = pendingLegacyCleanupIntent
            restarted.legacyCleanupIntent = legacyCleanupIntent
            restarted.deletionPending = deletionPending
            restarted.pendingDeletionLegacyCleanupIntent =
                pendingDeletionLegacyCleanupIntent
            restarted.rollbackPresent = rollbackPresent
            restarted.rollbackActive = rollbackActive
            restarted.rollbackDeletionPending = rollbackDeletionPending
            restarted.rollbackDeletionLegacyCleanupIntent =
                rollbackDeletionLegacyCleanupIntent
            restarted.rollbackLegacyCleanupIntent = rollbackLegacyCleanupIntent
        }

        override suspend fun stagePending(value: String, legacyCleanupExpected: String?) {
            stageCount += 1
            if (failStage) error("stage failed")
            stageFailure?.let { throw it }
            cleanupIntentStageFailure?.let { throw it }
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
            durableActive = active
            pending = null
            legacyCleanupIntent = pendingLegacyCleanupIntent
            pendingLegacyCleanupIntent = null
            deletionPending = false
            pendingDeletionLegacyCleanupIntent = null
        }

        override suspend fun rollbackPromotion() {
            if (!rollbackPresent) return
            active = rollbackActive
            durableActive = active
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
            discardRollbackFailure?.let { failure ->
                discardRollbackFailure = null
                throw failure
            }
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
            cleanupIntentClearFailure?.let { throw it }
            legacyCleanupIntent = null
        }

        override suspend fun markDeletionPending(legacyCleanupExpected: String?) {
            check(!processDeletionStateQuarantined)
            deletionPending = true
            pendingDeletionLegacyCleanupIntent = legacyCleanupExpected
            if (failDeletionMarkerCommit) {
                failDeletionMarkerCommit = false
                processDeletionStateQuarantined = true
                error("deletion marker commit failed")
            }
            durableDeletionPending = true
            durablePendingDeletionLegacyCleanupIntent = legacyCleanupExpected
        }

        override suspend fun isDeletionPending(): Boolean {
            check(!processDeletionStateQuarantined)
            return deletionPending
        }

        override suspend fun readPendingDeletionLegacyCleanupIntent(): String? {
            check(!processDeletionStateQuarantined)
            return pendingDeletionLegacyCleanupIntent
        }

        override suspend fun read(): String? {
            successfulReadsBeforeStall?.let { remaining ->
                if (remaining == 0) awaitCancellation()
                successfulReadsBeforeStall = remaining - 1
            }
            successfulReadsBeforeFailure?.let { remaining ->
                if (remaining == 0) {
                    successfulReadsBeforeFailure = null
                    val failure = checkNotNull(scheduledReadFailure)
                    scheduledReadFailure = null
                    throw failure
                }
                successfulReadsBeforeFailure = remaining - 1
            }
            readFailure?.let { throw it }
            return active
        }

        override suspend fun clear() {
            check(!processDeletionStateQuarantined)
            clearFailure?.let { throw it }
            active = null
            durableActive = null
            pending = null
            pendingLegacyCleanupIntent = null
            legacyCleanupIntent = null
            readFailure = null
            rollbackPresent = false
            rollbackActive = null
            rollbackDeletionPending = false
            rollbackDeletionLegacyCleanupIntent = null
            rollbackLegacyCleanupIntent = null
            pendingDeletionLegacyCleanupIntent = null
            durableDeletionPending = true
            durablePendingDeletionLegacyCleanupIntent = null
        }
    }
}
