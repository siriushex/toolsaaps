package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class ReceiverTherapyBoundaryTest {

    @Test
    fun exportedIngestReceiverDoesNotDeclareDiagnosticActions() {
        val manifest = parseManifest("src/main/AndroidManifest.xml")
        val receiver = manifest.getElementsByTagName("receiver")
            .asSequence()
            .map { it as Element }
            .single {
                it.getAttributeNS(ANDROID_NAMESPACE, "name") ==
                    ".receiver.LocalDataBroadcastReceiver"
            }
        val actions = receiver.getElementsByTagName("action")
            .asSequence()
            .map { it as Element }
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
            .toSet()

        assertThat(actions).doesNotContain("io.aaps.copilot.BROADCAST_TEST_INGEST")
        assertThat(actions).doesNotContain("io.aaps.copilot.BROADCAST_TEST_SEND_TEMP_TARGET")
        assertThat(actions).doesNotContain("io.aaps.copilot.BROADCAST_TEST_SEND_CARBS")
        assertThat(actions).containsExactly("info.nightscout.androidaps.status")
        assertThat(receiver.getAttributeNS(ANDROID_NAMESPACE, "permission"))
            .isEqualTo(DATA_RELAY_PERMISSION)
    }

    @Test
    fun exportedIngestReceiverHasNoDiagnosticTherapyWriter() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/receiver/LocalDataBroadcastReceiver.kt"
        ).readText()

        assertThat(source).doesNotContain("TEST_SEND_TEMP_TARGET_ACTION")
        assertThat(source).doesNotContain("TEST_SEND_CARBS_ACTION")
        assertThat(source).doesNotContain("handleTestTempTarget")
        assertThat(source).doesNotContain("handleTestCarbs")
    }

    @Test
    fun debugReceiverIsNotExported() {
        val manifest = parseManifest("src/main/AndroidManifest.xml")
        val receiver = manifest.getElementsByTagName("receiver")
            .asSequence()
            .map { it as Element }
            .single {
                it.getAttributeNS(ANDROID_NAMESPACE, "name") ==
                    ".receiver.DebugActionReceiver"
            }

        assertThat(receiver.getAttributeNS(ANDROID_NAMESPACE, "exported")).isEqualTo("false")
    }

    private fun parseManifest(path: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(sourceFile(path))

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> =
        (0 until length).asSequence().map(::item)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val DATA_RELAY_PERMISSION =
            "info.nightscout.androidaps.permission.RELAY_COPILOT_DATA"
    }
}
