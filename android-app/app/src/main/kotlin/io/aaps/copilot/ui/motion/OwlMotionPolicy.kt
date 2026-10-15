package io.aaps.copilot.ui.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class OwlGesture(val durationMs: Long) {
    STILL(0), LAUNCH(800), GAZE(1_600), LISTEN(1_600), GREET(1_400), NOD(1_000), WAITING(8_000)
}

data class OwlPose(
    val blink: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val headAngle: Float = 0f,
    val headY: Float = 0f,
    val wingAngle: Float = 0f,
    val bodyScale: Float = 1f,
    val orbitAngle: Float? = null,
    val active: Boolean = false
)

/** Presentation only. A clock never indicates clinical or request success. */
object OwlMotionPolicy {
    fun sample(gesture: OwlGesture, elapsedMs: Long, visible: Boolean = true,
        reduced: Boolean = false, urgent: Boolean = false, powerSave: Boolean = false): OwlPose {
        require(elapsedMs >= 0)
        if (!visible || reduced || urgent || powerSave || gesture == OwlGesture.STILL) return OwlPose()
        if (elapsedMs >= gesture.durationMs) return if (gesture == OwlGesture.WAITING)
            sample(gesture,gesture.durationMs-1).copy(active=false) else OwlPose()
        val t = elapsedMs.toFloat()
        fun wave(start: Float, end: Float): Float {
            if (t <= start || t >= end) return 0f
            return sin(PI * (t - start) / (end - start)).toFloat().let { it * it }
        }
        val blink = when (gesture) {
            OwlGesture.LAUNCH -> wave(270f, 460f)
            OwlGesture.WAITING -> wave(1_660f, 1_920f)
            OwlGesture.GREET -> wave(890f, 1_090f)
            OwlGesture.NOD -> wave(640f, 840f)
            else -> wave(1_020f, 1_260f)
        }
        return when (gesture) {
            OwlGesture.LAUNCH -> OwlPose(blink = blink, bodyScale = .97f + .03f * (t / 192f).coerceAtMost(1f), active = true)
            OwlGesture.GAZE -> OwlPose(blink = blink, gazeX = 3.6f * wave(80f, 1_000f), gazeY = -.6f * wave(80f, 1_000f), active = true)
            OwlGesture.LISTEN -> OwlPose(blink = blink, headAngle = -4f * wave(40f, 1_000f), active = true)
            OwlGesture.GREET -> OwlPose(blink = blink, wingAngle = 6f * wave(0f, 900f), active = true)
            OwlGesture.NOD -> OwlPose(blink = blink, headY = 4f * wave(0f, 800f), active = true)
            OwlGesture.WAITING -> {
                val angle = -90f + 360f * t / 2_800f
                val radians = angle * PI / 180
                OwlPose(blink = blink, gazeX = 4.2f * cos(radians).toFloat(),
                    gazeY = 4.2f * sin(radians).toFloat(), orbitAngle = angle, active = true)
            }
            OwlGesture.STILL -> OwlPose()
        }
    }

    fun eyeTarget(pose: OwlPose, eyeX: Float): Pair<Float, Float> {
        val angle = pose.orbitAngle ?: return pose.gazeX to pose.gazeY
        val radians = angle * PI / 180
        val dx = 104f * cos(radians).toFloat() - (eyeX - 140f) * 150f / 280f
        val dy = 104f * sin(radians).toFloat() - 11f * 150f / 280f
        val length = hypot(dx, dy)
        return dx / length * 4.2f to dy / length * 4.2f
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
