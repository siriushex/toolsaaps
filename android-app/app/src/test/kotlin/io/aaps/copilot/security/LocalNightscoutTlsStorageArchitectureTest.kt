package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class LocalNightscoutTlsStorageArchitectureTest {

    @Test
    fun tlsIdentityHasDedicatedNamespaceAndDeletionCoordinator() {
        val namespace = RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS
        val registry = DeletionDurabilityCoordinatorRegistry()

        assertThat(namespace).isEqualTo(
            SecretStorageNamespace(
                preferencesFile = "local_nightscout_tls_identity",
                keyAlias = "io.aaps.predictivecopilot.local_nightscout_tls.aes_gcm.v1"
            )
        )
        assertThat(registry.coordinatorFor(namespace))
            .isSameInstanceAs(registry.coordinatorFor(namespace.copy()))
        assertThat(registry.coordinatorFor(namespace))
            .isNotSameInstanceAs(
                registry.coordinatorFor(ClinicalAiSecretStorageNamespaces.OPENAI)
            )
    }

    @Test
    fun tlsPreferencesAreExcludedFromCloudBackupAndDeviceTransfer() {
        val fullBackup = parse("src/main/res/xml/backup_rules.xml")
        val extraction = parse("src/main/res/xml/data_extraction_rules.xml")

        assertThat(excludesByDomain(fullBackup, "sharedpref"))
            .contains("local_nightscout_tls_identity.xml")
        assertThat(excludesByDomain(fullBackup, "sharedpref"))
            .contains("local_nightscout_tls_identity_reset_tombstone.xml")
        assertThat(excludesUnder(extraction, "cloud-backup"))
            .contains("local_nightscout_tls_identity.xml")
        assertThat(excludesUnder(extraction, "cloud-backup"))
            .contains("local_nightscout_tls_identity_reset_tombstone.xml")
        assertThat(excludesUnder(extraction, "device-transfer"))
            .contains("local_nightscout_tls_identity.xml")
        assertThat(excludesUnder(extraction, "device-transfer"))
            .contains("local_nightscout_tls_identity_reset_tombstone.xml")
    }

    @Test
    fun tlsIdentityUsesOnlyAtomicKeystoreEncryptedRecordStorage() {
        val tlsSource = sourceFile(
            "src/main/kotlin/io/aaps/copilot/service/LocalNightscoutTlsIdentity.kt"
        ).readText()
        val byteStorageSource = sourceFile(
            "src/main/kotlin/io/aaps/copilot/service/LocalNightscoutEncryptedIdentityStorage.kt"
        ).readText()
        val genericStorageSource = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()

        assertThat(tlsSource).contains("fun readOrCreate(create: () -> ByteArray): ByteArray")
        assertThat(tlsSource).contains("encoded.fill(0)")
        assertThat(tlsSource).doesNotContain("Base64")
        assertThat(tlsSource).doesNotContain("val password: String")
        assertThat(tlsSource).doesNotContain("getSharedPreferences")
        assertThat(tlsSource).doesNotContain("DataStore")
        assertThat(tlsSource).doesNotContain("Room")
        assertThat(tlsSource).doesNotContain("FileOutputStream")
        assertThat(byteStorageSource).contains("Cipher.getInstance(TRANSFORMATION)")
        assertThat(byteStorageSource).contains("AES/GCM/NoPadding")
        assertThat(byteStorageSource).contains("plaintext: ByteArray")
        assertThat(byteStorageSource).contains("RESET_PENDING_KEY")
        assertThat(byteStorageSource).contains("RuntimeSecretDeletionDurabilityCoordinators")
        assertThat(byteStorageSource).contains("dependencies.namespace")
        assertThat(byteStorageSource).doesNotContain("String(cipher.doFinal")
        assertThat(genericStorageSource).doesNotContain("AtomicSecretRecordCoordinator")
        assertThat(genericStorageSource).doesNotContain("readOrCreateAtomic")
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(sourceFile(path))

    private fun excludesUnder(
        document: org.w3c.dom.Document,
        parentTag: String
    ): List<String> {
        val parents = document.getElementsByTagName(parentTag)
        val result = mutableListOf<String>()
        for (index in 0 until parents.length) {
            val children = parents.item(index).childNodes
            for (childIndex in 0 until children.length) {
                val node = children.item(childIndex)
                if (node.nodeName == "exclude" &&
                    node.attributes?.getNamedItem("domain")?.nodeValue == "sharedpref"
                ) {
                    result += node.attributes.getNamedItem("path").nodeValue
                }
            }
        }
        return result
    }

    private fun excludesByDomain(
        document: org.w3c.dom.Document,
        domain: String
    ): List<String> {
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
        return File(moduleDir, relativePath).also { file ->
            check(file.exists()) { "Missing source file: ${file.path}" }
        }
    }
}
