package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-w320dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AboutScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun idleOnlyChecksAndDoesNotDownloadOrInstall() {
        var checks = 0
        val activity = show(AppUpdateState(), onCheck = { checks++ })
        try {
            compose.onNodeWithText("Проверить обновление").performClick()
            assertEquals(1, checks)
            compose.onNodeWithText("Установить", substring = false).assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun availableReleaseOffersExplicitDownloadWithNotes() {
        var downloads = 0
        val activity = show(AppUpdateState(UpdatePhase.AVAILABLE, sampleRelease()), onDownload = { downloads++ })
        try {
            compose.onNodeWithText("Update notes").assertIsDisplayed()
            compose.onNodeWithText("Загрузить обновление").performClick()
            assertEquals(1, downloads)
            saveRaster("about-available.png", activity.get().window.decorView)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun downloadingOnlyShowsProgressAndCancellation() {
        var cancelled = 0
        val activity = show(AppUpdateState(UpdatePhase.DOWNLOADING, sampleRelease(), percent = 43),
            onCancel = { cancelled++ })
        try {
            compose.onNodeWithText("43%").assertIsDisplayed()
            compose.onNodeWithTag("updateAction").assertDoesNotExist()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(1, cancelled)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun readyInstallerIsReachableOnSmallScreenAndExplainsAlertInterruption() {
        var installs = 0
        val activity = show(AppUpdateState(UpdatePhase.READY, sampleRelease()), onInstall = { installs++ })
        try {
            compose.onNodeWithText("На время установки", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Установить", substring = false).performScrollTo().performClick()
            assertEquals(1, installs)
            saveRaster("about-ready.png", activity.get().window.decorView)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun invalidApkOffersOnlyRecheck() {
        val activity = show(AppUpdateState(UpdatePhase.ERROR, failure = UpdateFailure.INVALID_APK))
        try {
            compose.onNodeWithText("APK не прошёл", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Проверить обновление").assertIsDisplayed()
            compose.onNodeWithText("Установить", substring = false).assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun interruptedTransferOffersResumeWithRetainedProgress() {
        var downloads = 0
        val activity = show(AppUpdateState(UpdatePhase.ERROR, sampleRelease(), percent = 75,
            failure = UpdateFailure.NETWORK), onDownload = { downloads++ })
        try {
            compose.onNodeWithText("75%").assertIsDisplayed()
            compose.onNodeWithText("Продолжить загрузку").performScrollTo().performClick()
            assertEquals(1, downloads)
            compose.onNodeWithText("Установить", substring = false).assertDoesNotExist()
            saveRaster("about-resume.png", activity.get().window.decorView)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun storageFailureExplainsRecoveryAndOnlyRetriesExplicitly() {
        var checks = 0
        val activity = show(AppUpdateState(UpdatePhase.ERROR, failure = UpdateFailure.STORAGE),
            onCheck = { checks++ })
        try {
            compose.onNodeWithText("Не удалось сохранить APK.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Проверьте свободное место", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Установить", substring = false).assertDoesNotExist()
            assertEquals(0, checks)
            compose.onNodeWithText("Проверить обновление").performScrollTo().performClick()
            assertEquals(1, checks)
            saveRaster("about-storage-error.png", activity.get().window.decorView)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun installerOnlyGrantsReadAccessToTheVerifiedApkDirectory() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache").remove("${app.packageName}.app-update")
        val file = File(app.cacheDir, "app-updates/verified/sample.apk").apply { parentFile!!.mkdirs(); writeText("sample") }
        val intent = updateInstallerIntent(app, file)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertThrows(IllegalArgumentException::class.java) {
            updateInstallerIntent(app, File(app.cacheDir, "app-updates/unverified.part"))
        }
    }

    private fun show(state: AppUpdateState, onCheck: () -> Unit = { fail("Unexpected check") },
        onDownload: () -> Unit = { fail("Unexpected download") }, onCancel: () -> Unit = {},
        onInstall: () -> Unit = { fail("Unexpected install") }) =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().also { controller ->
            controller.get().setContent { AapsCopilotTheme(dynamicColor = false) {
                AboutContent(state, onCheck, onDownload, onCancel, onInstall)
            } }
            compose.waitForIdle()
        }

    private fun saveRaster(name: String, view: android.view.View) {
        (System.getProperty("update.screenshotDir") ?: "build/reports/app-update-native").let { path ->
            val file = File(path, name).apply { parentFile!!.mkdirs() }
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}
