package io.aaps.copilot.ui.motion

enum class OwlGesture(val durationMs: Long) {
    STILL(0), LAUNCH(480), GAZE(900), LISTEN(960), GREET(840), NOD(720), WAITING(7_200), MENU(480)
}

data class OwlPose(
    val blink: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val headAngle: Float = 0f,
    val headY: Float = 0f,
    val wingAngle: Float = 0f,
    val bodyScale: Float = 1f,
    val active: Boolean = false,
    val bodyX: Float = 0f,
    val bodyY: Float = 0f,
    val bodyAngle: Float = 0f
)

/** Presentation only. A clock never indicates clinical or request success. */
object OwlMotionPolicy {
    fun sample(gesture: OwlGesture, elapsedMs: Long, visible: Boolean = true,
        reduced: Boolean = false, urgent: Boolean = false, powerSave: Boolean = false): OwlPose {
        require(elapsedMs >= 0)
        if (!visible || reduced || urgent || powerSave || gesture == OwlGesture.STILL) return OwlPose()
        if (elapsedMs >= gesture.durationMs) return OwlPose()
        val t = if (gesture == OwlGesture.WAITING) (elapsedMs % 2_400).toFloat() else elapsedMs.toFloat()
        if (t == 0f || (gesture == OwlGesture.WAITING && t >= 2_160f)) return OwlPose(active = true)
        fun wave(start: Float, end: Float): Float {
            if (t <= start || t >= end) return 0f
            return NativeMotion.accent((t - start) / (end - start))
        }
        val blink = when (gesture) {
            OwlGesture.LAUNCH -> wave(260f, 400f)
            OwlGesture.WAITING -> wave(1_800f, 2_000f)
            OwlGesture.GREET -> wave(450f, 610f)
            OwlGesture.NOD -> wave(410f, 550f)
            OwlGesture.MENU -> wave(250f, 390f)
            else -> wave(560f, 740f)
        }
        return when (gesture) {
            OwlGesture.LAUNCH -> OwlPose(blink = blink,
                bodyScale = 1f - .014f * wave(0f, 120f) + .008f * wave(80f, 460f),
                bodyX = 3.4f * wave(110f, 480f),
                bodyY = 1.8f * wave(0f, 120f) - 8f * wave(80f, 460f),
                bodyAngle = -1.2f * wave(80f, 440f),
                wingAngle = 5.8f * wave(70f, 400f), gazeY = -1.2f * wave(120f, 420f), active = true)
            OwlGesture.GAZE -> OwlPose(blink = blink,
                gazeX = 8.8f * wave(20f, 760f), gazeY = -.5f * wave(20f, 760f),
                headAngle = 1.6f * wave(120f, 780f), bodyAngle = .55f * wave(200f, 840f), active = true)
            OwlGesture.LISTEN -> OwlPose(blink = blink,
                headAngle = -3.5f * wave(40f, 820f), gazeX = -3f * wave(0f, 700f),
                bodyAngle = .6f * wave(180f, 900f), wingAngle = .8f * wave(220f, 860f), active = true)
            OwlGesture.GREET -> OwlPose(blink = blink,
                bodyScale = 1f - .014f * wave(0f, 180f) + .006f * wave(100f, 760f),
                bodyY = 1.2f * wave(0f, 180f) - 6f * wave(100f, 760f),
                bodyAngle = -.7f * wave(100f, 780f),
                wingAngle = 6f * wave(80f, 680f), headAngle = -1.4f * wave(180f, 760f),
                gazeX = 2.2f * wave(120f, 700f), active = true)
            OwlGesture.NOD -> OwlPose(blink = blink,
                headY = 3.6f * wave(60f, 600f), headAngle = -1.4f * wave(20f, 520f),
                gazeY = 1.2f * wave(80f, 540f), bodyY = .9f * wave(180f, 660f), active = true)
            OwlGesture.WAITING -> {
                // Eyes lead three staggered marks; each phrase settles before the next one.
                val energy = waitingEnergy(elapsedMs)
                val first = waitingAccent(0, elapsedMs)
                val second = waitingAccent(1, elapsedMs)
                val third = waitingAccent(2, elapsedMs)
                val gaze = (4f * first + 6.5f * second + 9f * third) / (first + second + third).coerceAtLeast(1f)
                OwlPose(blink = blink, gazeX = gaze * energy,
                    headAngle = -2.4f * energy * wave(360f, 1_920f),
                    headY = .7f * energy * wave(480f, 2_040f),
                    bodyAngle = .35f * energy * wave(600f, 2_160f), active = true)
            }
            OwlGesture.MENU -> OwlPose(blink = blink,
                gazeX = -6f * wave(0f, 460f), headAngle = -1.2f * wave(60f, 480f),
                bodyAngle = -.35f * wave(120f, 480f), active = true)
            OwlGesture.STILL -> OwlPose()
        }
    }

    internal fun waitingEnergy(elapsedMs: Long): Float {
        require(elapsedMs >= 0)
        return if (elapsedMs >= OwlGesture.WAITING.durationMs) 0f else 1f - .18f * (elapsedMs / 2_400)
    }

    internal fun waitingAccent(index: Int, elapsedMs: Long): Float {
        require(index in 0..2 && elapsedMs >= 0)
        if (elapsedMs >= OwlGesture.WAITING.durationMs) return 0f
        val phase = ((elapsedMs % 2_400) - (120 + index * 260)).toFloat() / 840f
        return NativeMotion.accent(phase.coerceIn(0f, 1f))
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
