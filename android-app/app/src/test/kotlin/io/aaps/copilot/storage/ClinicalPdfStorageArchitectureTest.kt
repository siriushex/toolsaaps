package io.aaps.copilot.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class ClinicalPdfStorageArchitectureTest {

    @Test
    fun storageImplementationLivesOutsideReportAndDoesNotPersistRawPayloadOrLogPaths() {
        val root = sourceRoot()
        val storage = File(root, "io/aaps/copilot/storage/ClinicalPdfStorageRepository.kt")
            .readText()
        val reportFiles = File(root, "io/aaps/copilot/report").walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }

        assertThat(storage).contains("noBackupFilesDir")
        assertThat(storage).contains("clinical-pdf")
        assertThat(storage).doesNotContain("ClinicalReportPayload")
        assertThat(storage).doesNotContain("ClinicalReportDataset")
        assertThat(storage).doesNotContain("Log.")
        assertThat(reportFiles).doesNotContain("noBackupFilesDir")
        assertThat(reportFiles).doesNotContain("cacheDir")
    }

    @Test
    fun manifestFileProviderIsPrivateGrantingAndPathsExposeOnlyShareSubdirectory() {
        val project = projectRoot()
        val manifest = parse(File(project, "src/main/AndroidManifest.xml"))
        val application = directChildren(manifest.documentElement, "application").single()
        val provider = directChildren(application, "provider").single {
            it.androidAttribute("name") == "androidx.core.content.FileProvider" &&
                it.androidAttribute("authorities") == "${'$'}{applicationId}.clinical-pdf"
        }
        val paths = parse(File(project, "src/main/res/xml/clinical_pdf_file_paths.xml"))
        val pathEntries = directChildElements(paths.documentElement)

        assertThat(provider.androidAttribute("exported")).isEqualTo("false")
        assertThat(provider.androidAttribute("grantUriPermissions")).isEqualTo("true")
        assertThat(pathEntries).hasSize(1)
        assertThat(pathEntries.single().tagName).isEqualTo("cache-path")
        assertThat(pathEntries.single().getAttribute("name")).isEqualTo("clinical-pdf-share")
        assertThat(pathEntries.single().getAttribute("path")).isEqualTo("clinical-pdf-share/")
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun directChildren(parent: Element, tagName: String): List<Element> =
        directChildElements(parent).filter { it.tagName == tagName }

    private fun directChildElements(parent: Element): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(::add)
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun sourceRoot(): File = File(projectRoot(), "src/main/kotlin")

    private fun projectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            if (File(current, "src/main/AndroidManifest.xml").isFile) return current
            if (File(current, "app/src/main/AndroidManifest.xml").isFile) {
                return File(current, "app")
            }
            current = current.parentFile ?: error("Android app root not found")
        }
        error("Android app root not found")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
