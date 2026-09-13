package io.aaps.copilot.instrumentation

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class UiTestIsolationArchitectureTest {

    @Test
    fun androidTestsTargetDedicatedUiTestPackage() {
        val buildScript = sourceFile("build.gradle.kts").readText()
        val uiTestBuildType = blockBody(buildScript, """create("uiTest")""")

        assertThat(buildScript)
            .contains("""applicationId = "io.aaps.predictivecopilot"""")
        assertThat(buildScript).contains("""testBuildType = "uiTest"""")
        assertThat(uiTestBuildType).contains("""initWith(getByName("debug"))""")
        assertThat(uiTestBuildType).contains("""applicationIdSuffix = ".uitest"""")
        assertThat(buildScript)
            .contains("""add("uiTestImplementation", "androidx.compose.ui:ui-test-manifest")""")
        assertThat(buildScript)
            .doesNotContain("""debugImplementation("androidx.compose.ui:ui-test-manifest")""")
        assertThat(buildScript)
            .doesNotContain("""add("uiTestImplementation", "androidx.compose.ui:ui-tooling")""")
    }

    @Test
    fun runnerFailsClosedAndAlwaysCreatesInertApplication() {
        val runner = sourceFile(
            "src/androidTest/kotlin/io/aaps/copilot/CopilotTestRunner.kt"
        ).readText()
        val inertApplicationFile = sourceFile(
            "src/uiTest/kotlin/io/aaps/copilot/UiTestApplication.kt"
        )

        assertThat(runner).contains("io.aaps.predictivecopilot.uitest")
        assertThat(runner).contains("require(context.packageName == UI_TEST_TARGET_PACKAGE)")
        assertThat(runner).contains("UiTestApplication::class.java.name")
        assertThat(runner).doesNotContain("super.newApplication(cl, className, context)")

        assertThat(inertApplicationFile.isFile).isTrue()
        val inertApplication = inertApplicationFile.readText()
        assertThat(inertApplication).contains("class UiTestApplication : Application()")
        assertThat(inertApplication).doesNotContain("onCreate")
        assertThat(inertApplication).doesNotContain("AppContainer")
        assertThat(inertApplication).doesNotContain("Configuration.Provider")
        assertThat(inertApplication).doesNotContain("WorkManager")
    }

    @Test
    fun uiTestManifestRemovesEveryProductionPermissionAndComponent() {
        val mainManifest = parseManifest("src/main/AndroidManifest.xml")
        val uiTestManifestFile = sourceFile("src/uiTest/AndroidManifest.xml")

        assertThat(uiTestManifestFile.isFile).isTrue()
        val uiTestManifest = parseManifest(uiTestManifestFile)
        val removedPermissions = directChildren(uiTestManifest.documentElement, "uses-permission")
            .filter { it.toolsNode() == "remove" }
            .map { it.androidName() }
            .toSet()
        val productionPermissions = directChildren(mainManifest.documentElement, "uses-permission")
            .map { it.androidName() }
            .toSet()

        assertThat(removedPermissions).containsAtLeastElementsIn(productionPermissions)
        assertThat(removedPermissions).containsAtLeast(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK"
        )

        val uiTestApplication = directChildren(
            uiTestManifest.documentElement,
            "application"
        ).single()
        assertThat(uiTestApplication.androidName()).isEqualTo(".UiTestApplication")
        assertThat(
            uiTestApplication.getAttributeNS(TOOLS_NAMESPACE, "replace")
                .split(',')
                .map(String::trim)
        ).contains("android:name")

        val productionApplication = directChildren(
            mainManifest.documentElement,
            "application"
        ).single()
        val productionComponents = COMPONENT_TAGS.flatMap { tag ->
            directChildren(productionApplication, tag).map { it.androidName() }
        }.toSet()
        val removedComponents = COMPONENT_TAGS.flatMap { tag ->
            directChildren(uiTestApplication, tag)
                .filter { it.toolsNode() == "remove" }
                .map { it.androidName() }
        }.toSet()

        assertThat(removedComponents)
            .containsAtLeastElementsIn(productionComponents + DEPENDENCY_COMPONENTS)
        assertThat(
            directChildren(uiTestManifest.documentElement, "queries")
                .map { it.toolsNode() }
        ).contains("remove")
    }

    private fun parseManifest(relativePath: String) = parseManifest(sourceFile(relativePath))

    private fun parseManifest(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)

    private fun directChildren(parent: Element, tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == tagName) {
                result += child
            }
        }
        return result
    }

    private fun Element.androidName(): String =
        getAttributeNS(ANDROID_NAMESPACE, "name")

    private fun Element.toolsNode(): String =
        getAttributeNS(TOOLS_NAMESPACE, "node")

    private fun blockBody(source: String, declaration: String): String {
        val declarationIndex = source.indexOf(declaration)
        if (declarationIndex < 0) return ""
        val openingBrace = source.indexOf('{', declarationIndex + declaration.length)
        if (openingBrace < 0) return ""

        var depth = 1
        var index = openingBrace + 1
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            index += 1
        }
        return if (depth == 0) source.substring(openingBrace + 1, index - 1) else ""
    }

    private fun sourceFile(relativePath: String): File =
        File(requireNotNull(System.getProperty("user.dir")), relativePath)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
        val COMPONENT_TAGS = listOf("activity", "service", "receiver", "provider")
        val DEPENDENCY_COMPONENTS = setOf(
            "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService",
            "androidx.work.impl.background.systemjob.SystemJobService",
            "androidx.work.impl.foreground.SystemForegroundService",
            "androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver",
            "androidx.work.impl.background.systemalarm.RescheduleReceiver",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver",
            "androidx.room.MultiInstanceInvalidationService",
            "androidx.profileinstaller.ProfileInstallReceiver"
        )
    }
}
