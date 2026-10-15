package io.aaps.copilot.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing

enum class OwlGesture(val durationMs: Long) {
    STILL(0), LAUNCH(480), GAZE(900), LISTEN(960), GREET(840), NOD(720), WAITING(2_400)
}

data class OwlPose(
    val blink: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val headAngle: Float = 0f,
    val headY: Float = 0f,
    val wingAngle: Float = 0f,
    val bodyScale: Float = 1f,
    val active: Boolean = false
)

/** Presentation only. A clock never indicates clinical or request success. */
object OwlMotionPolicy {
    fun sample(gesture: OwlGesture, elapsedMs: Long, visible: Boolean = true,
        reduced: Boolean = false, urgent: Boolean = false, powerSave: Boolean = false): OwlPose {
        require(elapsedMs >= 0)
        if (!visible || reduced || urgent || powerSave || gesture == OwlGesture.STILL) return OwlPose()
        if (elapsedMs >= gesture.durationMs) return OwlPose()
        val t = elapsedMs.toFloat()
        fun wave(start: Float, end: Float): Float {
            if (t <= start || t >= end) return 0f
            return NativeMotion.accent((t - start) / (end - start))
        }
        val blink = when (gesture) {
            OwlGesture.LAUNCH -> wave(180f, 320f)
            OwlGesture.WAITING -> wave(1_660f, 1_840f)
            OwlGesture.GREET -> wave(450f, 610f)
            OwlGesture.NOD -> wave(410f, 550f)
            else -> wave(560f, 740f)
        }
        return when (gesture) {
            OwlGesture.LAUNCH -> OwlPose(blink = blink, bodyScale = .985f + .015f * FastOutSlowInEasing.transform((t / 220f).coerceAtMost(1f)), active = true)
            OwlGesture.GAZE -> OwlPose(blink = blink, gazeX = 2.8f * wave(40f, 720f), gazeY = -.4f * wave(40f, 720f), active = true)
            OwlGesture.LISTEN -> OwlPose(blink = blink, headAngle = -3f * wave(0f, 800f), active = true)
            OwlGesture.GREET -> OwlPose(blink = blink, wingAngle = 4f * wave(0f, 700f), active = true)
            OwlGesture.NOD -> OwlPose(blink = blink, headY = 3f * wave(0f, 580f), active = true)
            OwlGesture.WAITING -> OwlPose(blink = blink, gazeX = 1.8f * wave(80f, 1_440f),
                headAngle = -.8f * wave(80f, 1_440f), active = true)
            OwlGesture.STILL -> OwlPose()
        }
    }
}

enum class OwlColorBand { BRAND, WHITE, YELLOW, GREEN, RED, PURPLE, UNKNOWN }

/** User-selected color bands are not clinical alert thresholds. */
fun owlColorBand(glucose: Double?, fresh: Boolean): OwlColorBand = when {
    !fresh || glucose == null || !glucose.isFinite() || glucose <= 0.0 -> OwlColorBand.UNKNOWN
    glucose < 2.8 -> OwlColorBand.WHITE
    glucose < 3.6 -> OwlColorBand.YELLOW
    glucose <= 9.0 -> OwlColorBand.GREEN
    glucose <= 13.0 -> OwlColorBand.RED
    else -> OwlColorBand.PURPLE
}
