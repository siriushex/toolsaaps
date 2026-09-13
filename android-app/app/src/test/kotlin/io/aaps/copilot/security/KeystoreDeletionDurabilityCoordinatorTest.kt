package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class KeystoreDeletionDurabilityCoordinatorTest {

    @Test
    fun stalledOpenAiDeleteDoesNotBlockOrDelayCancelledAnthropicDelete() = runBlocking {
        val registry = DeletionDurabilityCoordinatorRegistry()
        val openAiCoordinator = registry.coordinatorFor(
            ClinicalAiSecretStorageNamespaces.OPENAI
        )
        val anthropicCoordinator = registry.coordinatorFor(
            ClinicalAiSecretStorageNamespaces.ANTHROPIC
        )
        val openAiEntered = CompletableDeferred<Unit>()
        val releaseOpenAi = CountDownLatch(1)
        val anthropicEntered = CompletableDeferred<Unit>()
        val releaseAnthropic = CountDownLatch(1)

        val openAiDelete = async {
            runInterruptible(Dispatchers.IO) {
                openAiCoordinator.withGuard {
                    openAiEntered.complete(Unit)
                    releaseOpenAi.await()
                }
            }
        }

        try {
            openAiEntered.await()
            val anthropicDelete = async {
                runInterruptible(Dispatchers.IO) {
                    anthropicCoordinator.withGuard {
                        anthropicEntered.complete(Unit)
                        releaseAnthropic.await()
                    }
                }
            }

            withTimeout(TEST_TIMEOUT_MS) { anthropicEntered.await() }
            withTimeout(TEST_TIMEOUT_MS) { anthropicDelete.cancelAndJoin() }

            assertThat(anthropicDelete.isCancelled).isTrue()
            assertThat(openAiDelete.isCompleted).isFalse()
        } finally {
            releaseAnthropic.countDown()
            releaseOpenAi.countDown()
            openAiDelete.await()
        }
    }

    @Test
    fun sameNamespaceDeleteIsSerializedAndWaitingCancellationIsPrompt() = runBlocking {
        val registry = DeletionDurabilityCoordinatorRegistry()
        val namespaceCopy = ClinicalAiSecretStorageNamespaces.OPENAI.copy()
        val holderCoordinator = registry.coordinatorFor(
            ClinicalAiSecretStorageNamespaces.OPENAI
        )
        val waiterCoordinator = registry.coordinatorFor(namespaceCopy)
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CountDownLatch(1)
        val waiterEntered = CompletableDeferred<Unit>()

        assertThat(waiterCoordinator).isSameInstanceAs(holderCoordinator)

        val holder = async {
            runInterruptible(Dispatchers.IO) {
                holderCoordinator.withGuard {
                    holderEntered.complete(Unit)
                    releaseHolder.await()
                }
            }
        }

        try {
            holderEntered.await()
            val waiter = async {
                runInterruptible(Dispatchers.IO) {
                    waiterCoordinator.withGuard {
                        waiterEntered.complete(Unit)
                    }
                }
            }
            withTimeout(TEST_TIMEOUT_MS) {
                while (!holderCoordinator.hasQueuedThreadsForTest()) {
                    yield()
                }
            }

            withTimeout(TEST_TIMEOUT_MS) { waiter.cancelAndJoin() }

            assertThat(waiter.isCancelled).isTrue()
            assertThat(waiterEntered.isCompleted).isFalse()
            assertThat(holder.isCompleted).isFalse()
        } finally {
            releaseHolder.countDown()
            holder.await()
        }
    }

    @Test
    fun quarantineIsSharedOnlyWithinEqualNamespace() = runBlocking {
        val registry = DeletionDurabilityCoordinatorRegistry()
        val openAi = registry.coordinatorFor(ClinicalAiSecretStorageNamespaces.OPENAI)
        val sameOpenAi = registry.coordinatorFor(
            ClinicalAiSecretStorageNamespaces.OPENAI.copy()
        )
        val anthropic = registry.coordinatorFor(
            ClinicalAiSecretStorageNamespaces.ANTHROPIC
        )

        openAi.withGuard { openAi.quarantine() }

        assertThat(runCatching { sameOpenAi.withGuard {} }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { anthropic.withGuard {} }.exceptionOrNull()).isNull()
    }

    @Test
    fun telegramQuarantineDoesNotBlockServerAiOrLocalTransport() {
        val registry = DeletionDurabilityCoordinatorRegistry()
        val telegram = registry.coordinatorFor(RuntimeSecretStorageNamespaces.TELEGRAM)
        val sameTelegram = registry.coordinatorFor(RuntimeSecretStorageNamespaces.TELEGRAM.copy())
        val serverAi = registry.coordinatorFor(RuntimeSecretStorageNamespaces.SERVER_AI_CONNECTION)
        val localTransport = registry.coordinatorFor(RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS)

        telegram.withGuard { telegram.quarantine() }

        assertThat(runCatching { sameTelegram.withGuard {} }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { serverAi.withGuard {} }.exceptionOrNull()).isNull()
        assertThat(runCatching { localTransport.withGuard {} }.exceptionOrNull()).isNull()
        val namespaces = listOf(RuntimeSecretStorageNamespaces.TELEGRAM,
            RuntimeSecretStorageNamespaces.SERVER_AI_CONNECTION, RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS)
        assertThat(namespaces.map { it.preferencesFile }).containsNoDuplicates()
        assertThat(namespaces.map { it.keyAlias }).containsNoDuplicates()
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 5_000L
    }
}
