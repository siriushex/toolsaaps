package io.aaps.copilot.data.repository

import android.net.Uri
import androidx.annotation.RawRes
import io.aaps.copilot.R
import io.aaps.copilot.config.AppSettings

enum class GlucoseAlertAudioSlot {
    SOFT,
    CRITICAL_PRIMARY,
    CRITICAL_SECONDARY
}

data class GlucoseAlertAudioClipSpec(
    val slot: GlucoseAlertAudioSlot,
    @RawRes val resId: Int?,
    val sourceUri: Uri?,
    val label: String,
    val startMs: Int,
    val durationMs: Int,
    val valid: Boolean,
    val fallbackUsed: Boolean,
    val customSelected: Boolean
)

object GlucoseAlertAudioProfiles {
    private const val MIN_DURATION_MS = 15_000
    private const val MAX_DURATION_MS = 30_000

    private const val ELK_DURATION_MS = 123_839
    private const val NIGHT_DURATION_MS = 180_072
    private const val SNOW_DURATION_MS = 161_090

    private val RECOMMENDED = mapOf(
        GlucoseAlertAudioSlot.SOFT to RecommendedClip(
            resId = R.raw.elk_creek,
            label = "Elk Creek.mp3",
            totalDurationMs = ELK_DURATION_MS,
            startMs = 32_000,
            durationMs = 2_000
        ),
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY to RecommendedClip(
            resId = R.raw.night_waltz,
            label = "Night Waltz.mp3",
            totalDurationMs = NIGHT_DURATION_MS,
            startMs = 42_000,
            durationMs = 20_000
        ),
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY to RecommendedClip(
            resId = R.raw.snowbirds,
            label = "Snowbirds.mp3",
            totalDurationMs = SNOW_DURATION_MS,
            startMs = 36_000,
            durationMs = 20_000
        )
    )

    fun resolve(settings: AppSettings, slot: GlucoseAlertAudioSlot): GlucoseAlertAudioClipSpec {
        val recommended = RECOMMENDED.getValue(slot)
        val requestedStart = requestedStart(settings, slot)
        val requestedDuration = requestedDuration(settings, slot)
        val customUri = requestedUri(settings, slot)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val customLabel = requestedDisplayName(settings, slot)?.takeIf { it.isNotBlank() }

        if (customUri != null) {
            val customTimingValid = requestedStart >= 0 && requestedDuration in durationRange(slot)
            return if (customTimingValid) {
                GlucoseAlertAudioClipSpec(
                    slot = slot,
                    resId = null,
                    sourceUri = customUri,
                    label = customLabel ?: recommended.label,
                    startMs = requestedStart,
                    durationMs = requestedDuration,
                    valid = true,
                    fallbackUsed = false,
                    customSelected = true
                )
            } else {
                recommendedSpec(slot, valid = false, fallbackUsed = true)
            }
        }

        val requestedValid = isValid(
            slot = slot,
            startMs = requestedStart,
            durationMs = requestedDuration,
            totalDurationMs = recommended.totalDurationMs
        )
        return if (requestedValid) {
            GlucoseAlertAudioClipSpec(
                slot = slot,
                resId = recommended.resId,
                sourceUri = null,
                label = recommended.label,
                startMs = requestedStart,
                durationMs = requestedDuration,
                valid = true,
                fallbackUsed = false,
                customSelected = false
            )
        } else {
            recommendedSpec(slot, valid = false, fallbackUsed = true)
        }
    }

    fun recommendedSpec(slot: GlucoseAlertAudioSlot, valid: Boolean = true, fallbackUsed: Boolean = false): GlucoseAlertAudioClipSpec {
        val recommended = RECOMMENDED.getValue(slot)
        return GlucoseAlertAudioClipSpec(
            slot = slot,
            resId = recommended.resId,
            sourceUri = null,
            label = recommended.label,
            startMs = recommended.startMs,
            durationMs = recommended.durationMs,
            valid = valid,
            fallbackUsed = fallbackUsed,
            customSelected = false
        )
    }

    fun requestedUri(settings: AppSettings, slot: GlucoseAlertAudioSlot): String? = when (slot) {
        GlucoseAlertAudioSlot.SOFT -> settings.softAlertAudioUri
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY -> settings.criticalAlertAudio1Uri
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> settings.criticalAlertAudio2Uri
    }

    fun requestedDisplayName(settings: AppSettings, slot: GlucoseAlertAudioSlot): String? = when (slot) {
        GlucoseAlertAudioSlot.SOFT -> settings.softAlertAudioDisplayName
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY -> settings.criticalAlertAudio1DisplayName
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> settings.criticalAlertAudio2DisplayName
    }

    fun recommendedStartMs(slot: GlucoseAlertAudioSlot): Int = RECOMMENDED.getValue(slot).startMs

    fun recommendedDurationMs(slot: GlucoseAlertAudioSlot): Int = RECOMMENDED.getValue(slot).durationMs

    fun label(slot: GlucoseAlertAudioSlot): String = RECOMMENDED.getValue(slot).label

    fun isValid(settings: AppSettings, slot: GlucoseAlertAudioSlot): Boolean = resolve(settings, slot).valid

    fun isValid(slot: GlucoseAlertAudioSlot, startMs: Int, durationMs: Int): Boolean {
        val recommended = RECOMMENDED.getValue(slot)
        return isValid(slot, startMs, durationMs, recommended.totalDurationMs)
    }

    fun displayLabel(settings: AppSettings, slot: GlucoseAlertAudioSlot): String {
        return requestedDisplayName(settings, slot)?.takeIf { it.isNotBlank() } ?: label(slot)
    }

    private fun requestedStart(settings: AppSettings, slot: GlucoseAlertAudioSlot): Int = when (slot) {
        GlucoseAlertAudioSlot.SOFT -> settings.softAlertAudioStartMs
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY -> settings.criticalAlertAudio1StartMs
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> settings.criticalAlertAudio2StartMs
    }

    private fun requestedDuration(settings: AppSettings, slot: GlucoseAlertAudioSlot): Int = when (slot) {
        GlucoseAlertAudioSlot.SOFT -> settings.softAlertAudioDurationMs
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY -> settings.criticalAlertAudio1DurationMs
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> settings.criticalAlertAudio2DurationMs
    }

    private fun durationRange(slot: GlucoseAlertAudioSlot): IntRange =
        if (slot == GlucoseAlertAudioSlot.SOFT) 1_000..5_000 else MIN_DURATION_MS..MAX_DURATION_MS

    private fun isValid(slot: GlucoseAlertAudioSlot, startMs: Int, durationMs: Int, totalDurationMs: Int): Boolean {
        if (durationMs !in durationRange(slot)) return false
        if (startMs < 0) return false
        return startMs <= totalDurationMs - durationMs
    }

    private data class RecommendedClip(
        @RawRes val resId: Int,
        val label: String,
        val totalDurationMs: Int,
        val startMs: Int,
        val durationMs: Int
    )
}
