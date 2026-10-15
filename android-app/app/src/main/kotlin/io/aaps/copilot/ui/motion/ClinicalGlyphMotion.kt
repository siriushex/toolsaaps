package io.aaps.copilot.ui.motion

internal data class GlyphPartPose(val x: Float = 0f, val y: Float = 0f, val angle: Float = 0f,
    val weight: Float = 1.8f, val trace: Float = 0f, val offset: Float = 24f,
    val pivotX: Float = 12f, val pivotY: Float = 12f)

internal fun glyphPartPose(art: GlyphArtwork, index: Int, elapsed: Long, enabled: Boolean): GlyphPartPose {
    if (!enabled || art.manual || elapsed <= 0L || elapsed >= art.durationMs) return GlyphPartPose()
    var pose = GlyphPartPose()
    art.moves.forEach { move ->
        val order = move.indices.indexOf(index)
        if (order < 0) return@forEach
        val delay = move.delay + order * move.stagger
        val progress = ((elapsed.toFloat() / art.durationMs - delay) / (1f - delay).coerceAtLeast(.25f)).coerceIn(0f, 1f)
        val wave = NativeMotion.accent(progress)
        pose = when (move.effect) {
            "shift" -> pose.copy(x = pose.x + move.x * wave, y = pose.y + move.y * wave)
            "rotate" -> pose.copy(angle = pose.angle + move.angle * wave, pivotX = move.pivotX, pivotY = move.pivotY)
            "weight" -> pose.copy(weight = pose.weight + move.amount * wave)
            "trace" -> pose.copy(trace = .6f * wave, offset = 24f - 48f * progress)
            else -> error("Unknown reviewed glyph effect")
        }
    }
    return pose
}
