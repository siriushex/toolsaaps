package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalAiCredentialProviderTest {

    @Test
    fun credentialsAndStatusAreIsolatedByProvider() = runTest {
        val fixture = providerFixture()

        fixture.provider.replace(ClinicalAiProviderId.OPENAI, "openai-secret")
        fixture.provider.replace(ClinicalAiProviderId.ANTHROPIC, "anthropic-secret")
        fixture.provider.ensureMigrated(ClinicalAiProviderId.GEMINI)

        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI))
            .isEqualTo("openai-secret")
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.ANTHROPIC))
            .isEqualTo("anthropic-secret")
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI]?.configured)
            .isTrue()
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.ANTHROPIC]?.configured)
            .isTrue()
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.GEMINI]?.configured)
            .isFalse()
        assertThat(fixture.storages.getValue(ClinicalAiProviderId.GEMINI).active).isNull()
    }

    @Test
    fun legacyOpenAiSecretMigratesOnlyToOpenAiStore() = runTest {
        val legacy = MutableLegacyCredentialSource("legacy-openai")
        val fixture = providerFixture(openAiLegacy = legacy)

        fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI)
        fixture.provider.ensureMigrated(ClinicalAiProviderId.ANTHROPIC)

        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI))
            .isEqualTo("legacy-openai")
        assertThat(
            runCatching {
                fixture.provider.requireCredential(ClinicalAiProviderId.ANTHROPIC)
            }.exceptionOrNull()
        ).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.storages.getValue(ClinicalAiProviderId.ANTHROPIC).active).isNull()
        assertThat(legacy.value).isNull()
        assertThat(legacy.readCount).isEqualTo(1)
        assertThat(legacy.clearCount).isEqualTo(1)
    }

    @Test
    fun nonOpenAiProvidersNeverAccessSuppliedOpenAiLegacySource() = runTest {
        ClinicalAiProviderId.entries
            .filterNot { it == ClinicalAiProviderId.OPENAI }
            .forEach { providerId ->
                val marker = "legacy-marker-${providerId.name}"
                val legacy = MutableLegacyCredentialSource(marker)
                val storage = FakeSecretStorage()
                val provider = ClinicalAiCredentialProvider(
                    mapOf(
                        providerId to ClinicalAiCredentialStore(
                            providerId = providerId,
                            secureStorage = storage,
                            legacyCredentialSource = legacy
                        )
                    )
                )

                assertThat(provider.ensureMigrated(providerId))
                    .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
                assertThat(storage.active).isNull()
                assertThat(
                    runCatching { provider.requireCredential(providerId) }.exceptionOrNull()
                ).isInstanceOf(IllegalStateException::class.java)

                provider.replace(providerId, "provider-secret")
                assertThat(provider.requireCredential(providerId)).isEqualTo("provider-secret")
                provider.delete(providerId)

                assertThat(storage.active).isNull()
                assertThat(legacy.value).isEqualTo(marker)
                assertThat(legacy.readCount).isEqualTo(0)
                assertThat(legacy.clearCount).isEqualTo(0)
            }
    }

    @Test
    fun providerRejectsProviderStoreMismatchWithoutAccessingLegacySource() {
        val marker = "legacy-marker-provider-mismatch"
        val legacy = MutableLegacyCredentialSource(marker)
        val openAiStore = ClinicalAiCredentialStore(
            providerId = ClinicalAiProviderId.OPENAI,
            secureStorage = FakeSecretStorage(),
            legacyCredentialSource = legacy
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ClinicalAiCredentialProvider(
                mapOf(ClinicalAiProviderId.ANTHROPIC to openAiStore)
            )
        }

        assertThat(failure).hasMessageThat().doesNotContain(marker)
        assertThat(legacy.readCount).isEqualTo(0)
        assertThat(legacy.clearCount).isEqualTo(0)
    }

    @Test
    fun deleteClearsOnlySelectedProvider() = runTest {
        val fixture = providerFixture()
        fixture.provider.replace(ClinicalAiProviderId.OPENAI, "openai-secret")
        fixture.provider.replace(ClinicalAiProviderId.GEMINI, "gemini-secret")

        fixture.provider.delete(ClinicalAiProviderId.OPENAI)

        assertThat(fixture.storages.getValue(ClinicalAiProviderId.OPENAI).clearCount)
            .isEqualTo(1)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI]?.configured)
            .isFalse()
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI))
            .isEqualTo("gemini-secret")
        assertThat(fixture.storages.getValue(ClinicalAiProviderId.GEMINI).clearCount)
            .isEqualTo(0)
    }

    @Test
    fun pendingDeletionMarkerDoesNotAffectAnotherProvider() = runTest {
        val openAiStorage = FakeSecretStorage().apply {
            active = "openai-secret"
            clearFailure = IllegalStateException("simulated deletion crash")
        }
        val geminiStorage = FakeSecretStorage().apply {
            active = "gemini-secret"
        }
        val fixture = providerFixture(
            storageOverrides = mapOf(
                ClinicalAiProviderId.OPENAI to openAiStorage,
                ClinicalAiProviderId.GEMINI to geminiStorage
            )
        )

        val deleteFailure = captureThrowable {
            fixture.provider.delete(ClinicalAiProviderId.OPENAI)
        }

        assertThat(deleteFailure.message).isEqualTo("OPENAI credential deletion failed")
        assertThat(openAiStorage.deletionPending).isTrue()
        assertThat(geminiStorage.deletionPending).isFalse()
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI))
            .isEqualTo("gemini-secret")

        openAiStorage.clearFailure = null
        val readAttemptsAfterRecovery = openAiStorage.readAttempts
        val routineFailure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI)
        }

        assertThat(routineFailure.message).isEqualTo("OPENAI credential migration failed")
        assertThat(openAiStorage.readAttempts).isEqualTo(readAttemptsAfterRecovery)
        assertThat(openAiStorage.deletionPending).isTrue()
        assertThat(openAiStorage.active).isEqualTo("openai-secret")
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI))
            .isEqualTo("gemini-secret")

        assertThat(fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI))
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(openAiStorage.deletionPending).isTrue()
        assertThat(openAiStorage.active).isNull()
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI))
            .isEqualTo("gemini-secret")
    }

    @Test
    fun failedDeletionRecoveryRetriesOnlyOnExplicitEnsure() = runTest {
        val storage = FakeSecretStorage().apply {
            active = "synthetic-secure-a"
        }
        val fixture = providerFixture(
            storageOverrides = mapOf(ClinicalAiProviderId.OPENAI to storage)
        )
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI))
            .isEqualTo("synthetic-secure-a")
        storage.clearFailure = IllegalStateException("synthetic clear failure")

        val deleteFailure = captureThrowable {
            fixture.provider.delete(ClinicalAiProviderId.OPENAI)
        }

        assertThat(deleteFailure.message).isEqualTo("OPENAI credential deletion failed")
        assertThat(storage.active).isEqualTo("synthetic-secure-a")
        assertThat(storage.deletionPending).isTrue()
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI])
            .isEqualTo(
                ClinicalAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true
                )
            )

        storage.clearFailure = null

        assertThat(fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI))
            .isEqualTo(CredentialMigrationResult.NO_LEGACY_KEY)
        assertThat(storage.active).isNull()
        assertThat(storage.deletionPending).isTrue()
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI])
            .isEqualTo(ClinicalAiCredentialStatus(configured = false, busy = false))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stalledProviderDoesNotBlockAnotherProvider() = runTest {
        val stageStarted = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()
        val openAiStorage = FakeSecretStorage(
            beforeStage = {
                stageStarted.complete(Unit)
                releaseStage.await()
            }
        )
        val fixture = providerFixture(
            storageOverrides = mapOf(ClinicalAiProviderId.OPENAI to openAiStorage)
        )

        val openAiReplace = launch {
            fixture.provider.replace(ClinicalAiProviderId.OPENAI, "openai-secret")
        }
        stageStarted.await()
        val anthropicReplace = async {
            fixture.provider.replace(ClinicalAiProviderId.ANTHROPIC, "anthropic-secret")
        }
        runCurrent()

        assertThat(anthropicReplace.isCompleted).isTrue()
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.ANTHROPIC))
            .isEqualTo("anthropic-secret")

        releaseStage.complete(Unit)
        openAiReplace.join()
    }

    @Test
    fun cancellationRecoversOnlyAffectedStatusAndRethrowsOriginal() = runTest {
        val cancellation = CancellationException("cancel provider write")
        val fixture = providerFixture(
            storageOverrides = mapOf(
                ClinicalAiProviderId.OPENAI to FakeSecretStorage(stageFailure = cancellation)
            )
        )
        fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI)
        fixture.provider.replace(ClinicalAiProviderId.ANTHROPIC, "anthropic-secret")

        val thrown = captureThrowable {
            fixture.provider.replace(ClinicalAiProviderId.OPENAI, "marker-secret")
        }

        assertSame(cancellation, thrown)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI]?.busy).isFalse()
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.ANTHROPIC])
            .isEqualTo(
                ClinicalAiCredentialStatus(configured = true, busy = false)
            )
    }

    @Test
    fun timeoutInterruptsBlockingStorageAndReleasesProviderMutex() = runTest {
        val blockFirstStage = AtomicBoolean(true)
        val neverReleased = CountDownLatch(1)
        val storage = FakeSecretStorage(
            beforeStage = {
                if (blockFirstStage.compareAndSet(true, false)) {
                    runInterruptible(Dispatchers.IO) {
                        neverReleased.await()
                    }
                }
            }
        )
        val fixture = providerFixture(
            storageOverrides = mapOf(ClinicalAiProviderId.OPENAI to storage)
        )
        fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI)

        val timeout = captureThrowable {
            withTimeout(250L) {
                fixture.provider.replace(ClinicalAiProviderId.OPENAI, "first-secret")
            }
        }

        assertThat(timeout).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI]?.busy).isFalse()

        fixture.provider.replace(ClinicalAiProviderId.OPENAI, "second-secret")
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI))
            .isEqualTo("second-secret")
    }

    @Test
    fun oneShotSecureReadFailureRecoversAndNextReadSucceedsWithoutLeak() = runTest {
        val marker = "MARKER-READ-SECRET-71b2"
        val fixture = providerFixture()
        fixture.provider.replace(ClinicalAiProviderId.OPENAI, marker)
        fixture.storages.getValue(ClinicalAiProviderId.OPENAI).failNextReads(
            count = 1,
            failure = IllegalStateException("storage read failed with $marker")
        )

        val failure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI)
        }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure.message).isEqualTo("OPENAI credential read failed")
        assertThat(failure.cause).isNull()
        assertThat(failure.stackTraceToString()).doesNotContain(marker)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI])
            .isEqualTo(
                ClinicalAiCredentialStatus(
                    configured = true,
                    busy = false,
                    readFailed = true
                )
            )

        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI))
            .isEqualTo(marker)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI]?.readFailed)
            .isFalse()
    }

    @Test
    fun persistentSecureReadFailureStaysRetryableAndSanitized() = runTest {
        val marker = "MARKER-PERSISTENT-READ-3e90"
        val fixture = providerFixture()
        fixture.provider.replace(ClinicalAiProviderId.GEMINI, marker)
        fixture.storages.getValue(ClinicalAiProviderId.GEMINI).failNextReads(
            count = 10,
            failure = IllegalStateException("persistent storage failure $marker")
        )

        repeat(2) {
            val failure = captureThrowable {
                fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI)
            }

            assertThat(failure.message).isEqualTo("GEMINI credential read failed")
            assertThat(failure.cause).isNull()
            assertThat(failure.stackTraceToString()).doesNotContain(marker)
            assertThat(fixture.provider.status.value[ClinicalAiProviderId.GEMINI])
                .isEqualTo(
                    ClinicalAiCredentialStatus(
                        configured = false,
                        busy = false,
                        readFailed = true
                    )
                )
        }
    }

    @Test
    fun coldStartOneShotSecureReadFailureRetriesOnlyOnExplicitEnsure() = runTest {
        val marker = "MARKER-COLD-START-8f2c"
        val storage = FakeSecretStorage().apply {
            active = marker
            failNextReads(
                count = 1,
                failure = IllegalStateException("cold-start read failed with $marker")
            )
        }
        val fixture = providerFixture(
            storageOverrides = mapOf(ClinicalAiProviderId.OPENAI to storage)
        )

        val firstFailure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI)
        }

        assertThat(firstFailure.message).isEqualTo("OPENAI credential migration failed")
        assertThat(firstFailure.cause).isNull()
        assertThat(firstFailure.stackTraceToString()).doesNotContain(marker)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI])
            .isEqualTo(
                ClinicalAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true
                )
            )
        val readAttemptsAfterFailure = storage.readAttempts

        val routineFailure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI)
        }

        assertThat(routineFailure.message).isEqualTo("OPENAI credential migration failed")
        assertThat(storage.readAttempts).isEqualTo(readAttemptsAfterFailure)

        assertThat(fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI))
            .isEqualTo(CredentialMigrationResult.ALREADY_SECURE)
        assertThat(fixture.provider.requireCredential(ClinicalAiProviderId.OPENAI))
            .isEqualTo(marker)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.OPENAI])
            .isEqualTo(ClinicalAiCredentialStatus(configured = true, busy = false))
    }

    @Test
    fun coldStartPersistentSecureReadFailureRetriesOnlyExplicitlyAndIsSanitized() = runTest {
        val marker = "MARKER-COLD-PERSISTENT-91d4"
        val storage = FakeSecretStorage().apply {
            active = marker
            failNextReads(
                count = 10,
                failure = IllegalStateException("persistent cold-start failure $marker")
            )
        }
        val fixture = providerFixture(
            storageOverrides = mapOf(ClinicalAiProviderId.GEMINI to storage)
        )

        val firstFailure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI)
        }
        val readAttemptsAfterFailure = storage.readAttempts
        val routineFailure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI)
        }

        listOf(firstFailure, routineFailure).forEach { failure ->
            assertThat(failure.message).isEqualTo("GEMINI credential migration failed")
            assertThat(failure.cause).isNull()
            assertThat(failure.stackTraceToString()).doesNotContain(marker)
        }
        assertThat(storage.readAttempts).isEqualTo(readAttemptsAfterFailure)
        assertThat(fixture.provider.status.value[ClinicalAiProviderId.GEMINI])
            .isEqualTo(
                ClinicalAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true
                )
            )

        assertThat(fixture.provider.ensureMigrated(ClinicalAiProviderId.GEMINI))
            .isEqualTo(CredentialMigrationResult.FAILED)
        assertThat(storage.readAttempts).isEqualTo(readAttemptsAfterFailure + 1)
        val postRetryRoutineFailure = captureThrowable {
            fixture.provider.requireCredential(ClinicalAiProviderId.GEMINI)
        }

        assertThat(postRetryRoutineFailure.message)
            .isEqualTo("GEMINI credential migration failed")
        assertThat(postRetryRoutineFailure.cause).isNull()
        assertThat(postRetryRoutineFailure.stackTraceToString()).doesNotContain(marker)
        assertThat(storage.readAttempts).isEqualTo(readAttemptsAfterFailure + 1)
    }

    @Test
    fun statusExceptionsAndProviderRepresentationDoNotLeakSecret() = runTest {
        val marker = "MARKER-SECRET-7f19"
        val fixture = providerFixture(
            storageOverrides = mapOf(
                ClinicalAiProviderId.OPENAI to FakeSecretStorage(
                    stageFailure = IllegalStateException("storage failure $marker")
                )
            )
        )
        fixture.provider.ensureMigrated(ClinicalAiProviderId.OPENAI)

        val failure = captureThrowable {
            fixture.provider.replace(ClinicalAiProviderId.OPENAI, marker)
        }
        val loggable = listOf(
            fixture.provider.status.value.toString(),
            fixture.provider.toString(),
            failure.toString(),
            failure.stackTraceToString()
        ).joinToString("\n")

        assertThat(loggable).doesNotContain(marker)
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    private fun providerFixture(
        openAiLegacy: LegacyCredentialSource = EmptyLegacyCredentialSource,
        storageOverrides: Map<ClinicalAiProviderId, FakeSecretStorage> = emptyMap()
    ): ProviderFixture {
        val storages = ClinicalAiProviderId.entries.associateWith { providerId ->
            storageOverrides[providerId] ?: FakeSecretStorage()
        }
        val stores = storages.mapValues { (providerId, storage) ->
            ClinicalAiCredentialStore(
                providerId = providerId,
                secureStorage = storage,
                legacyCredentialSource = if (providerId == ClinicalAiProviderId.OPENAI) {
                    openAiLegacy
                } else {
                    EmptyLegacyCredentialSource
                }
            )
        }
        return ProviderFixture(
            provider = ClinicalAiCredentialProvider(stores),
            storages = storages
        )
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

    private data class ProviderFixture(
        val provider: ClinicalAiCredentialProvider,
        val storages: Map<ClinicalAiProviderId, FakeSecretStorage>
    )

    private class MutableLegacyCredentialSource(
        var value: String?
    ) : LegacyCredentialSource {
        var readCount = 0
        var clearCount = 0

        override suspend fun read(): String? {
            readCount += 1
            return value
        }

        override suspend fun clearIfMatches(expected: String): Boolean {
            clearCount += 1
            if (value?.trim() != expected) return false
            value = null
            return true
        }
    }

    private class FakeSecretStorage(
        private val beforeStage: suspend () -> Unit = {},
        private val stageFailure: Throwable? = null
    ) : SecretStorage {
        var active: String? = null
        var pending: String? = null
        private var pendingLegacyCleanupIntent: String? = null
        private var legacyCleanupIntent: String? = null
        var clearCount = 0
        var readAttempts = 0
        var deletionPending = false
        private var pendingDeletionLegacyCleanupIntent: String? = null
        var clearFailure: Throwable? = null
        private var remainingReadFailures = 0
        private var readFailure: Throwable? = null
        private var rollbackPresent = false
        private var rollbackActive: String? = null
        private var rollbackDeletionPending = false
        private var rollbackDeletionLegacyCleanupIntent: String? = null
        private var rollbackLegacyCleanupIntent: String? = null

        fun failNextReads(count: Int, failure: Throwable) {
            remainingReadFailures = count
            readFailure = failure
        }

        override suspend fun stagePending(value: String, legacyCleanupExpected: String?) {
            beforeStage()
            stageFailure?.let { throw it }
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
            readAttempts += 1
            if (remainingReadFailures > 0) {
                remainingReadFailures -= 1
                throw checkNotNull(readFailure)
            }
            return active
        }

        override suspend fun clear() {
            clearCount += 1
            clearFailure?.let { throw it }
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
        }
    }
}
