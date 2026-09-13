package io.aaps.copilot.security

import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

typealias OpenAiCredentialStatus = ClinicalAiCredentialStatus

class OpenAiCredentialProvider(
    store: OpenAiCredentialStore
) {
    private val delegate = ClinicalAiCredentialProvider(
        mapOf(ClinicalAiProviderId.OPENAI to store.delegate)
    )

    val status: StateFlow<OpenAiCredentialStatus> =
        delegate.statusFor(ClinicalAiProviderId.OPENAI)

    suspend fun ensureMigrated(): CredentialMigrationResult =
        delegate.ensureMigrated(ClinicalAiProviderId.OPENAI)

    internal suspend fun requireCredential(): String = try {
        delegate.requireCredential(ClinicalAiProviderId.OPENAI)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        if (status.value.migrationFailed) {
            throw IllegalStateException("OpenAI credential migration failed")
        }
        if (status.value.readFailed) {
            throw IllegalStateException("OpenAI credential read failed")
        }
        throw IllegalStateException("OpenAI credential is not configured")
    }

    suspend fun replace(value: String) {
        try {
            delegate.replace(ClinicalAiProviderId.OPENAI, value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            throw IllegalStateException("OpenAI credential update failed")
        }
    }

    suspend fun delete() {
        try {
            delegate.delete(ClinicalAiProviderId.OPENAI)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            throw IllegalStateException("OpenAI credential deletion failed")
        }
    }
}
