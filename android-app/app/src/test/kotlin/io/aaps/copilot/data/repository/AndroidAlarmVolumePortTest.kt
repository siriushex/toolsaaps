package io.aaps.copilot.data.repository

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidAlarmVolumePortTest {
    private val audioManager = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test fun readsTheActualAlarmIndexMaximumAndFixedPolicy() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val snapshot = AndroidAlarmVolumePort(audioManager).read()
        assertThat(snapshot.index).isEqualTo(audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertThat(snapshot.maximum).isEqualTo(audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        assertThat(snapshot.fixed).isEqualTo(audioManager.isVolumeFixed)
    }

    @Test fun settingAlarmVolumePreservesOtherStreams() {
        val otherStreams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_VOICE_CALL)
        val before = otherStreams.associateWith { audioManager.getStreamVolume(it) }
        AndroidAlarmVolumePort(audioManager).set(3)
        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(3)
        assertThat(otherStreams.associateWith { audioManager.getStreamVolume(it) }).isEqualTo(before)
    }

    @Test fun adapterCannotRequestSoundVibrationUiRingerOrRouteChanges() {
        val source = File("src/main/kotlin/io/aaps/copilot/data/repository/AndroidAlarmVolumePort.kt").readText()
        assertThat(source).contains("setStreamVolume(AudioManager.STREAM_ALARM, index, 0)")
        listOf("FLAG_", "setRingerMode", "setSpeakerphoneOn", "setCommunicationDevice", "NotificationManager",
            "MediaPlayer", "Vibrator", "WakeLock").forEach { assertThat(source).doesNotContain(it) }
    }

    @Test fun volumeHelperOnlyHasInactiveExecutorAndNoRuntimeArming() {
        val root = File("src/main/kotlin/io/aaps/copilot")
        val owners = setOf("LocalAlarmVolumeLease.kt", "AndroidAlarmVolumePort.kt")
        val consumers = root.walkTopDown().filter { it.extension == "kt" && it.name !in owners }
            .filter { it.readText().contains(Regex("LocalAlarmVolumeLease|AndroidAlarmVolumePort")) }
            .map { it.relativeTo(root).path }.toList()
        assertThat(consumers).containsExactly("data/repository/LocalAlarmCycleExecutor.kt")
        val runners = root.walkTopDown().filter { it.extension == "kt" && it.name != "LocalAlarmCycleExecutor.kt" }
            .filter { it.readText().contains(Regex("LocalAlarmCycleExecutor\\s*\\(")) }.toList()
        assertThat(runners).isEmpty()
    }
}
