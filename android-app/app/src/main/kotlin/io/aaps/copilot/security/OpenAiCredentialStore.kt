package io.aaps.copilot.security

import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.CancellationException

class OpenAiCredentialStore(
    secureStorage: SecretStorage,
    legacyKeySource: LegacyOpenAiKeySource
) {
    internal val delegate = ClinicalAiCredentialStore(
        providerId = ClinicalAiProviderId.OPENAI,
        secureStorage = secureStorage,
        legacyCredentialSource = legacyKeySource
    )

    suspend fun readConfigured(): Boolean = delegate.readConfigured()

    internal suspend fun readSecret(): String? = delegate.readSecret()

    internal suspend fun probeConfiguredAfterInterruption(): Boolean =
        delegate.probeConfiguredAfterInterruption().configured

    suspend fun replace(value: String) {
        try {
            delegate.replace(value)
        } catch (cleanupCancellation: CredentialReplacementCleanupCancellation) {
            throw cleanupCancellation.original
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    suspend fun clear() = delegate.clear()

    suspend fun migrateLegacyKey(): CredentialMigrationResult =
        delegate.migrateLegacyCredential()
}
