package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.ui.foundation.screens.CredentialDialogEphemeralState
import java.io.File
import org.junit.Test

class OpenAiCredentialArchitectureTest {

    @Test
    fun routineSettingsSerializationCannotWriteLegacyOpenAiKey() {
        val root = sourceRoot()
        val routineSettings = File(
            root,
            "io/aaps/copilot/config/AppSettingsStore.kt"
        ).readText()
        val settingsProjection = routineSettings
            .substringAfter("private fun readSettings")
            .substringBefore("suspend fun update")
        val settingsUpdate = routineSettings
            .substringAfter("private suspend fun updateInternal")
            .substringBefore("internal suspend fun beginSensitivitySettingsMutation")
        val appSettingsModel = routineSettings.substringAfter("data class AppSettings(")
        val rawLegacyKeyOwners = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "openai_api_key" in it.readText() }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

        assertThat(settingsProjection).doesNotContain("KEY_LEGACY_OPENAI_CREDENTIAL")
        assertThat(settingsUpdate).doesNotContain("KEY_LEGACY_OPENAI_CREDENTIAL")
        assertThat(routineSettings).doesNotContain("next.openAiApiKey")
        assertThat(routineSettings).doesNotContain("openAiApiKey = prefs")
        assertThat(appSettingsModel).doesNotContain("openAiApiKey")
        assertThat(rawLegacyKeyOwners).containsExactly(
            "io/aaps/copilot/config/AppSettingsStore.kt"
        )
    }

    @Test
    fun legacyPlaintextCredentialIsNotProjectedOrSerialized() {
        val root = sourceRoot()
        val routineSettings = File(
            root,
            "io/aaps/copilot/config/AppSettingsStore.kt"
        ).readText()

        assertThat(routineSettings).doesNotContain("next.openAiApiKey")
        assertThat(routineSettings).doesNotContain("openAiApiKey = prefs")
    }

    @Test
    fun legacyMigrationApiCannotExposeDataStoreOrPreferences() {
        val root = sourceRoot()
        val routineSettings = File(
            root,
            "io/aaps/copilot/config/AppSettingsStore.kt"
        ).readText()
        val legacySource = File(
            root,
            "io/aaps/copilot/config/AppSettingsLegacyOpenAiKeySource.kt"
        ).readText()
        val narrowApiOwners = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                val source = it.readText()
                "readLegacyOpenAiCredential" in source ||
                    "clearLegacyOpenAiCredentialIfMatches" in source
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val unsafeInternalSettingsAccessor = Regex(
            """internal\s+(?:suspend\s+)?fun\s+\w+\([^)]*\)\s*:\s*(?:DataStore(?:<Preferences>)?|Preferences)"""
        )

        assertThat(routineSettings).doesNotContain("legacyCredentialDataStore")
        assertThat(unsafeInternalSettingsAccessor.containsMatchIn(routineSettings)).isFalse()
        assertThat(routineSettings).contains("readLegacyOpenAiCredential")
        assertThat(routineSettings).contains("clearLegacyOpenAiCredentialIfMatches")
        assertThat(legacySource).doesNotContain("DataStore<Preferences>")
        assertThat(legacySource).doesNotContain("androidx.datastore")
        assertThat(narrowApiOwners).containsExactly(
            "io/aaps/copilot/config/AppSettingsLegacyOpenAiKeySource.kt",
            "io/aaps/copilot/config/AppSettingsStore.kt"
        )
    }

    @Test
    fun secretStorageDurabilityOperationsCannotHaveDefaultImplementations() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/security/ClinicalAiCredentialStore.kt"
        ).readText()
        val contract = source.substringAfter("interface SecretStorage {")
            .substringBefore("interface LegacyCredentialSource")

        assertThat(contract.lineFor("rollbackPromotion"))
            .isEqualTo("suspend fun rollbackPromotion()")
        assertThat(contract.lineFor("discardRollback"))
            .isEqualTo("suspend fun discardRollback()")
        assertThat(contract.lineFor("markDeletionPending"))
            .isEqualTo(
                "suspend fun markDeletionPending(legacyCleanupExpected: String?)"
            )
        assertThat(contract.lineFor("isDeletionPending"))
            .isEqualTo("suspend fun isDeletionPending(): Boolean")
        assertThat(contract.lineFor("readPendingDeletionLegacyCleanupIntent"))
            .isEqualTo("suspend fun readPendingDeletionLegacyCleanupIntent(): String?")
        assertThat(contract.lineFor("readLegacyCleanupIntent"))
            .isEqualTo("suspend fun readLegacyCleanupIntent(): String?")
        assertThat(contract.lineFor("readPendingLegacyCleanupIntent"))
            .isEqualTo("suspend fun readPendingLegacyCleanupIntent(): String?")
        assertThat(contract.lineFor("clearLegacyCleanupIntent"))
            .isEqualTo("suspend fun clearLegacyCleanupIntent()")
        assertThat(contract).contains(
            "suspend fun stagePending(value: String, legacyCleanupExpected: String?)"
        )
    }

    @Test
    fun credentialStoreTransactionsAreFencedByProviderNamespace() {
        val source = File(
            sourceRoot(),
            "io/aaps/copilot/security/ClinicalAiCredentialStore.kt"
        ).readText()

        assertThat(source).contains("NAMESPACE_TRANSACTION_MUTEXES")
        assertThat(source).contains("NAMESPACE_TRANSACTION_MUTEXES.getValue(providerId)")
        assertThat(source).doesNotContain("private val mutex = Mutex()")
    }

    @Test
    fun credentialCleanupPendingStatusRemainsInternalProviderState() {
        val root = sourceRoot()
        val provider = File(
            root,
            "io/aaps/copilot/security/ClinicalAiCredentialProvider.kt"
        ).readText()
        val container = File(root, "io/aaps/copilot/service/AppContainer.kt").readText()
        val viewModel = File(root, "io/aaps/copilot/ui/MainViewModel.kt").readText()
        val screenModels = File(
            root,
            "io/aaps/copilot/ui/foundation/screens/ScreenModels.kt"
        ).readText()
        assertThat(provider).contains("val legacyCleanupPending: Boolean = false")
        assertThat(container).contains("legacyCleanupPending = value.legacyCleanupPending")
        assertThat(container).contains("legacyCleanupPending = openAi.legacyCleanupPending")
        assertThat(viewModel).contains(
            "legacyCleanupPending = state.legacyCleanupPending"
        )
        assertThat(viewModel).contains(
            "legacyCleanupPending = credentialStatus.legacyCleanupPending"
        )
        assertThat(screenModels).contains("val legacyCleanupPending: Boolean = false")
    }

    @Test
    fun credentialDialogEphemeralStateClearsDraftAndVisibility() {
        val state = CredentialDialogEphemeralState()
        state.open(ClinicalAiProviderId.OPENAI)
        state.updateDraft("synthetic-draft")

        assertThat(state.replaceDialogProvider).isEqualTo(ClinicalAiProviderId.OPENAI)
        assertThat(state.credentialDraft).isEqualTo("synthetic-draft")

        state.clear()

        assertThat(state.replaceDialogProvider).isNull()
        assertThat(state.credentialDraft).isEmpty()
    }

    @Test
    fun appContainer_ownsLegacyOpenAiAndPerProviderCredentialGraphs() {
        val root = sourceRoot()
        val container = File(root, "io/aaps/copilot/service/AppContainer.kt").readText()
        val repository = File(
            root,
            "io/aaps/copilot/data/repository/AiChatRepository.kt"
        ).readText()

        assertThat(container.windowedCount("KeystoreSecretStorage(")).isEqualTo(2)
        assertThat(container.windowedCount("OpenAiCredentialStore(")).isEqualTo(1)
        assertThat(container.windowedCount("OpenAiCredentialProvider(")).isEqualTo(1)
        assertThat(container.windowedCount("ClinicalAiCredentialProvider(")).isEqualTo(1)
        assertThat(container.windowedCount("ClinicalAiCredentialStore(")).isEqualTo(1)
        assertThat(container).contains("ClinicalAiSecretStorageNamespaces.OPENAI")
        assertThat(container).contains("ClinicalAiSecretStorageNamespaces.forProvider(providerId)")
        assertThat(container).contains("filterNot { it == ClinicalAiProviderId.OPENAI }")
        assertThat(container).contains(
            "ClinicalAiProviderId.OPENAI -> openAiCredentialProvider.requireCredential()"
        )
        assertThat(container).contains(
            "else -> clinicalAiCredentialProvider.requireCredential(providerId)"
        )
        assertThat(repository).doesNotContain("openAiApiKey")
        assertThat(repository.windowedCount("credentialReader()"))
            .isAtLeast(5)
        assertThat(repository.windowedCount("credentialProvider.requireCredential()"))
            .isEqualTo(1)
        assertThat(repository).doesNotContain("responseBody.take(")
        assertThat(repository).doesNotContain("body.take(")
    }

    @Test
    fun legacyOpenAiWrappersDelegateToClinicalAiCredentialComponents() {
        val root = sourceRoot()
        val store = File(
            root,
            "io/aaps/copilot/security/OpenAiCredentialStore.kt"
        ).readText()
        val provider = File(
            root,
            "io/aaps/copilot/security/OpenAiCredentialProvider.kt"
        ).readText()

        assertThat(store).contains("ClinicalAiCredentialStore")
        assertThat(provider).contains("ClinicalAiCredentialProvider")
    }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("android-app/app/src/main/kotlin")
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Unable to locate production Kotlin source root")
    }

    private fun String.windowedCount(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(needle, index)
            if (index < 0) return count
            count += 1
            index += needle.length
        }
    }

    private fun String.lineFor(operation: String): String =
        lineSequence()
            .map(String::trim)
            .single { it.startsWith("suspend fun $operation(") }
}
