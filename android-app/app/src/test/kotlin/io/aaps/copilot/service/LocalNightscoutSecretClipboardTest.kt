package io.aaps.copilot.service

import android.content.ClipDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalNightscoutSecretClipboardTest {

    @Test
    fun copiedSecretIsMarkedSensitiveAndAutomaticallyCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val secret = "transient-local-secret".toCharArray()

        LocalNightscoutSecretClipboard.copy(context, secret, clearAfterMs = 5_000L)

        assertThat(clipboard.primaryClipDescription?.extras?.getBoolean(
            ClipDescription.EXTRA_IS_SENSITIVE,
            false
        )).isTrue()
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString())
            .isEqualTo("transient-local-secret")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001L))

        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()).isEmpty()
        secret.fill('\u0000')
    }

    @Test
    fun startupReconciliationClearsOnlyExpiredStillOwnedClip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        LocalNightscoutSecretClipboard.clearLeaseForTest(context)
        val secret = "restart-secret".toCharArray()

        LocalNightscoutSecretClipboard.copy(
            context = context,
            secret = secret,
            clearAfterMs = 60_000L,
            nowMs = { 1_000L }
        )
        LocalNightscoutSecretClipboard.reconcile(context, nowMs = 61_001L)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()).isEmpty()

        LocalNightscoutSecretClipboard.copy(
            context = context,
            secret = secret,
            clearAfterMs = 60_000L,
            nowMs = { 100_000L }
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("other-app", "preserve-me"))
        LocalNightscoutSecretClipboard.reconcile(context, nowMs = 200_000L)
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("preserve-me")

        secret.fill('\u0000')
        LocalNightscoutSecretClipboard.clearLeaseForTest(context)
    }

    @Test
    fun applicationStartupReconcilesOwnedClipboardLease() {
        val source = java.io.File(
            requireNotNull(System.getProperty("user.dir")),
            "src/main/kotlin/io/aaps/copilot/CopilotApp.kt"
        ).readText()

        assertThat(source).contains("LocalNightscoutSecretClipboard.reconcile(this)")
    }
}
