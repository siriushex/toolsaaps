package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class OpenAiCredentialBackupRulesTest {

    @Test
    fun encryptedDeletionIntentRemainsInsideExcludedCredentialPreferences() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()

        assertThat(source).contains(
            "const val DELETION_LEGACY_CLEANUP_PREFIX = \"deletion_legacy_cleanup\""
        )
        assertThat(source).doesNotContain("deletion_legacy_cleanup_preferences")
        assertThat(credentialPreferenceFiles())
            .contains("openai_credentials.xml")
    }

    @Test
    fun manifestAttachesBothBackupRuleResources() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0)
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        assertThat(application.attributes.getNamedItemNS(androidNamespace, "fullBackupContent").nodeValue)
            .isEqualTo("@xml/backup_rules")
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "dataExtractionRules").nodeValue)
            .isEqualTo("@xml/data_extraction_rules")
    }

    @Test
    fun fullBackupExcludesDedicatedCredentialPreferencesFile() {
        val root = parse("src/main/res/xml/backup_rules.xml")

        assertThat(root.documentElement.tagName).isEqualTo("full-backup-content")
        assertThat(excludesByDomain(root, "sharedpref"))
            .containsAtLeastElementsIn(credentialPreferenceFiles())
    }

    @Test
    fun cloudAndDeviceTransferExcludeDedicatedCredentialPreferencesFile() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")

        assertThat(root.documentElement.tagName).isEqualTo("data-extraction-rules")
        assertThat(excludesUnder(root, "cloud-backup"))
            .containsAtLeastElementsIn(credentialPreferenceFiles())
        assertThat(excludesUnder(root, "device-transfer"))
            .containsAtLeastElementsIn(credentialPreferenceFiles())
    }

    @Test
    fun plaintextLegacyDataStoreIsExcludedAsWholeProtobufFile() {
        val settingsSource = sourceFile(
            "src/main/kotlin/io/aaps/copilot/config/AppSettingsStore.kt"
        ).readText()
        assertThat(settingsSource)
            .contains("preferencesDataStoreFile(\"copilot_settings.preferences_pb\")")

        // Backup XML cannot exclude only the plaintext legacy key from a protobuf file.
        val fullBackup = parse("src/main/res/xml/backup_rules.xml")
        assertThat(excludesByDomain(fullBackup, "file"))
            .containsAtLeastElementsIn(settingsDataStoreFiles())

        val extraction = parse("src/main/res/xml/data_extraction_rules.xml")
        assertThat(excludesUnder(extraction, "cloud-backup", "file"))
            .containsAtLeastElementsIn(settingsDataStoreFiles())
        assertThat(excludesUnder(extraction, "device-transfer", "file"))
            .containsAtLeastElementsIn(settingsDataStoreFiles())
    }

    @Test
    fun roomDatabaseAndRollingMedicalCopiesAreExcludedFromEveryBackupTransport() {
        val fullBackup = parse("src/main/res/xml/backup_rules.xml")
        assertThat(excludesByDomain(fullBackup, "database")).contains(".")
        assertThat(excludesByDomain(fullBackup, "file"))
            .containsAtLeastElementsIn(rollingMedicalDatabaseFiles())

        val extraction = parse("src/main/res/xml/data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { transport ->
            assertThat(excludesUnder(extraction, transport, "database")).contains(".")
            assertThat(excludesUnder(extraction, transport, "file"))
                .containsAtLeastElementsIn(rollingMedicalDatabaseFiles())
        }
    }

    private fun credentialPreferenceFiles(): List<String> = listOf(
        "openai_credentials.xml",
        "clinical_ai_anthropic_credentials.xml",
        "clinical_ai_gemini_credentials.xml",
        "clinical_ai_compatible_credentials.xml"
    )

    private fun settingsDataStoreFiles(): List<String> = listOf(
        "datastore/copilot_settings.preferences_pb",
        "datastore/copilot_settings.preferences_pb.preferences_pb"
    )

    private fun rollingMedicalDatabaseFiles(): List<String> = listOf(
        "copilot_snapshot.db",
        "copilot_snapshot.db-wal",
        "copilot_snapshot.db-shm",
        "copilot-backup.db",
        "copilot-backup.db-wal",
        "copilot-backup.db-shm"
    )

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(sourceFile(path))

    private fun excludesUnder(
        document: org.w3c.dom.Document,
        parentTag: String,
        domain: String = "sharedpref"
    ): List<String> {
        val parents = document.getElementsByTagName(parentTag)
        val result = mutableListOf<String>()
        for (index in 0 until parents.length) {
            val excludes = parents.item(index).childNodes
            for (childIndex in 0 until excludes.length) {
                val node = excludes.item(childIndex)
                if (node.nodeName == "exclude" &&
                    node.attributes?.getNamedItem("domain")?.nodeValue == domain
                ) {
                    result += node.attributes.getNamedItem("path").nodeValue
                }
            }
        }
        return result
    }

    private fun excludesByDomain(document: org.w3c.dom.Document, domain: String): List<String> {
        val nodes = document.getElementsByTagName("exclude")
        val result = mutableListOf<String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.attributes?.getNamedItem("domain")?.nodeValue == domain) {
                result += node.attributes.getNamedItem("path").nodeValue
            }
        }
        return result
    }

    private fun sourceFile(relativePath: String): File {
        val moduleDir = File(requireNotNull(System.getProperty("user.dir")))
        return File(moduleDir, relativePath)
    }
}
