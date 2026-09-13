package io.aaps.copilot.config

import io.aaps.copilot.security.LegacyOpenAiKeySource

class AppSettingsLegacyOpenAiKeySource(
    private val settingsStore: AppSettingsStore
) : LegacyOpenAiKeySource {
    override suspend fun read(): String? =
        settingsStore.readLegacyOpenAiCredential()

    override suspend fun clearIfMatches(expected: String): Boolean =
        settingsStore.clearLegacyOpenAiCredentialIfMatches(expected)
}
