package io.aaps.copilot.data.repository

import android.media.AudioManager

class AndroidAlarmVolumePort(private val audioManager: AudioManager) : AlarmVolumePort {
    override fun read() = AlarmVolumeSnapshot(
        index = audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        fixed = audioManager.isVolumeFixed
    )

    override fun set(index: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
    }
}
