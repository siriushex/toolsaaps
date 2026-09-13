package io.aaps.copilot.data.repository

import kotlin.math.ceil

object GlucoseAlertAudioVolumePolicy {

    fun shouldEnsureAudible(
        notifyKind: GlucoseAlertNotifyKind,
        currentGlucoseMmol: Double?,
        currentGlucoseFresh: Boolean
    ): Boolean {
        return notifyKind == GlucoseAlertNotifyKind.LOW_NOW &&
            currentGlucoseFresh &&
            currentGlucoseMmol?.let { it < LOW_NOW_AUDIBLE_THRESHOLD_MMOL } == true
    }

    fun minimumAudibleVolume(maxVolume: Int): Int {
        if (maxVolume <= 0) return 0
        return ceil(maxVolume * MINIMUM_AUDIBLE_FRACTION).toInt().coerceIn(1, maxVolume)
    }

    private const val LOW_NOW_AUDIBLE_THRESHOLD_MMOL = 4.0
    private const val MINIMUM_AUDIBLE_FRACTION = 0.70
}
