package io.aaps.copilot.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R

private fun artworkPath(data: String): Path = PathParser().parsePathString(data).toPath()

private class OwlPaths {
    val body = artworkPath(OwlArtworkData.BODY)
    val face = artworkPath(OwlArtworkData.FACE)
    val breast = artworkPath(OwlArtworkData.BREAST)
    val beak = artworkPath(OwlArtworkData.BEAK)
    val wing = artworkPath(OwlArtworkData.WING)
    val layers = OwlArtworkData.WING_LAYERS.map(::artworkPath)
    val eyes = OwlArtworkData.EYES.map(::artworkPath)
    val upperLids = listOf(96f, 184f).map { x -> artworkPath("M${x-23} 153C${x-19} 135 ${x+10} 132 ${x+23} 146") }
    val closedLids = listOf(96f, 184f).map { x -> artworkPath("M${x-20} 151Q$x 161 ${x+20} 151") }
}

/** Fixed-size native artwork. Small marks never decode the feather bitmap. */
@Composable
fun CopilotOwl(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    band: OwlColorBand = OwlColorBand.BRAND,
    gesture: OwlGesture = OwlGesture.STILL,
    motionKey: String = gesture.name,
    animate: Boolean = true,
    description: String? = null
) {
    val paths = remember { OwlPaths() }
    val colors = remember(band) { OwlArtworkData.PALETTES.getValue(band).map(::Color) }
    val body = remember(colors) { Brush.linearGradient(*listOf(0f to colors[0], .36f to colors[1], .76f to colors[2], 1f to colors[3]).toTypedArray(), start = Offset(42f,20f), end = Offset(235f,265f)) }
    val wing = remember(colors) { Brush.linearGradient(listOf(colors[4],colors[5]), Offset(46f,155f),Offset(103f,254f)) }
    val iris = remember(colors) { Brush.radialGradient(listOf(colors[6],colors[7],colors[8]),Offset(0f,0f),21f) }
    val teal = remember { Brush.linearGradient(listOf(Color(0xFF227B87),Color(0xFF075366),Color(0xFF063746)),Offset(95f,105f),Offset(175f,265f)) }
    val texture = if (size >= 96.dp) ImageBitmap.imageResource(R.drawable.owl_feather_tile) else null
    val clock = rememberFiniteMotionClock(gesture.durationMs, motionKey, animate && gesture != OwlGesture.STILL)
    val motionEnabled = animate && LocalCopilotMotionEnabled.current
    val progress = if (gesture == OwlGesture.WAITING) Modifier.progressSemantics() else Modifier
    Canvas(modifier.size(size).then(progress).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val pose = OwlMotionPolicy.sample(gesture, clock.value, reduced = !motionEnabled)
        fun DrawScope.feather(path: Path) {
            if (texture != null) clipPath(path) {
                drawImage(texture, dstSize = IntSize(280,280), alpha = .36f, blendMode = BlendMode.Softlight)
            }
        }
        scale(this.size.width / 280f, this.size.height / 280f, Offset.Zero) {
            scale(pose.bodyScale,pose.bodyScale,Offset(140f,140f)) {
                drawPath(paths.body,body);feather(paths.body)
                clipPath(paths.body) {
                    fun DrawScope.foldedWing() {
                        drawPath(paths.wing,wing)
                        if (size >= 48.dp) paths.layers.forEach { layer ->
                            drawPath(layer,wing)
                            drawPath(layer,colors[9].copy(alpha=.24f),style=Stroke(1.4f))
                        }
                        feather(paths.wing)
                    }
                    rotate(pose.wingAngle,Offset(55f,160f)) { foldedWing() }
                    rotate(-pose.wingAngle,Offset(225f,160f)) {
                        translate(280f,0f) { scale(-1f,1f,Offset.Zero) { foldedWing() } }
                    }
                    drawPath(paths.breast,teal);feather(paths.breast)
                    translate(0f,pose.headY) {
                        rotate(pose.headAngle,Offset(140f,156f)) {
                            drawPath(paths.face,teal);feather(paths.face)
                            listOf(96f,184f).forEachIndexed { index,x ->
                                if (pose.blink < .94f) scale(1f,(1f-pose.blink).coerceAtLeast(.05f),Offset(x,151f)) {
                                    drawPath(paths.eyes[index],Color(0xFFF4F7F3))
                                    clipPath(paths.eyes[index]) {
                                        val target = OwlMotionPolicy.eyeTarget(pose,x)
                                        translate(x+target.first,151f+target.second) {
                                            drawCircle(iris,16.8f,Offset.Zero)
                                            if (band != OwlColorBand.BRAND) drawCircle(Color(0xFF0C1113),9.4f,Offset.Zero)
                                            drawCircle(Color.White.copy(alpha=.48f),1.6f,Offset(-3.2f,-3.8f))
                                        }
                                    }
                                    drawPath(paths.upperLids[index],Color(0xFF063D4A).copy(alpha=.78f),style=Stroke(if(size>=48.dp)3.6f else 2.1f,cap=StrokeCap.Round))
                                }
                                if (pose.blink > .3f) drawPath(paths.closedLids[index],Color(0xFF033645).copy(alpha=pose.blink),style=Stroke(4.2f,cap=StrokeCap.Round))
                            }
                            drawPath(paths.beak,Color(0xFFFFFDF8))
                        }
                    }
                }
                if (band != OwlColorBand.BRAND) drawPath(paths.body,colors[10],style=Stroke(1.4f))
            }
        }
    }
}

/** All reviewed glyph recipes share one finite clock and cached paths. */
@Composable
fun ClinicalGlyph(id: String, modifier: Modifier = Modifier, size: Dp = 24.dp,
    animate: Boolean = false, motionKey: String = id, tint: Color? = null, description: String? = null) {
    val art = remember(id) { ClinicalGlyphData.byId.getValue(id) }
    val paths = remember(art) { art.paths.map(::artworkPath) }
    val allowed = animate && !art.manual && LocalCopilotMotionEnabled.current
    val clock = rememberFiniteMotionClock(art.durationMs.toLong(),motionKey,allowed)
    val color = tint ?: Color(art.color)
    Canvas(modifier.size(size).then(if(description==null)Modifier else Modifier.semantics{contentDescription=description})) {
        val event = id.startsWith("event_")
        val viewport = if(event)32f else 24f
        scale(this.size.width/viewport,this.size.height/viewport,Offset.Zero) {
            translate(if(event)4f else 0f,if(event)4f else 0f) {
                paths.forEachIndexed { index,path ->
                    val pose = glyphPartPose(art,index,clock.value,allowed)
                    translate(pose.x,pose.y) { rotate(pose.angle,Offset(pose.pivotX,pose.pivotY)) {
                        drawPath(path,color,style=Stroke(pose.weight,cap=StrokeCap.Round,join=StrokeJoin.Round))
                        if(size>=48.dp) translate(-.12f,-.12f) {
                            drawPath(path,Color.White.copy(alpha=.32f),style=Stroke(.55f,cap=StrokeCap.Round,join=StrokeJoin.Round))
                        }
                        if(pose.trace>0f) drawPath(path,color.copy(alpha=pose.trace),style=Stroke(2.5f,cap=StrokeCap.Round,join=StrokeJoin.Round,
                            pathEffect=PathEffect.dashPathEffect(floatArrayOf(4f,20f),pose.offset)))
                    } }
                }
            }
        }
    }
}
