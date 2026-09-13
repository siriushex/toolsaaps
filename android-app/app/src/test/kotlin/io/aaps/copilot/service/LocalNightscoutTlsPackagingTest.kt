package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class LocalNightscoutTlsPackagingTest {

    @Test
    fun mainSourcesContainNoPackagedCredentialContainer() {
        val assetContainers = sourceFile("src/main").resolve("assets")
            .walkTopDown()
            .filter(File::isFile)
            .map { it.extension.lowercase() }
            .filter { it in CREDENTIAL_CONTAINER_EXTENSIONS }
            .toList()
        val tlsSource = sourceFile(
            "src/main/kotlin/io/aaps/copilot/service/LocalNightscoutTls.kt"
        ).readText()

        assertThat(assetContainers).isEmpty()
        assertThat(tlsSource).doesNotContain("KEYSTORE_ASSET")
        assertThat(tlsSource).doesNotContain("context.assets.open")
        assertThat(tlsSource).doesNotContain("local_ns_keystore.p12")
    }

    @Test
    fun heldCertificateUsesTheMatchingOkHttpTlsDependency() {
        val build = sourceFile("build.gradle.kts").readText()

        assertThat(build)
            .contains("implementation(\"com.squareup.okhttp3:okhttp-tls:5.3.2\")")
    }

    private fun sourceFile(relativePath: String): File {
        val moduleDir = File(requireNotNull(System.getProperty("user.dir")))
        return File(moduleDir, relativePath).also { file ->
            check(file.exists()) { "Missing source file: ${file.path}" }
        }
    }

    private companion object {
        val CREDENTIAL_CONTAINER_EXTENSIONS = setOf("jks", "keystore", "p12", "pfx")
    }
}
