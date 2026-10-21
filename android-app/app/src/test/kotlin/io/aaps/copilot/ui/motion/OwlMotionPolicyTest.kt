package io.aaps.copilot.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class OwlMotionPolicyTest {
    @Test fun routeChangesNeverCrossfadeInteractiveScreenLayers() {
        assertEquals(EnterTransition.None, NativeMotion.routeEnter)
        assertEquals(ExitTransition.None, NativeMotion.routeExit)
    }
    @Test fun everyGestureIsBoundedAndFinite() {
        OwlGesture.entries.forEach { gesture ->
            assertTrue(gesture.durationMs in 0..8_000)
            for (time in 0L..9_000L step 7) {
                val p=OwlMotionPolicy.sample(gesture,time)
                assertTrue(listOf(p.blink,p.gazeX,p.gazeY,p.headAngle,p.headY,p.wingAngle,p.bodyScale,p.bodyX,p.bodyY,p.bodyAngle).all(Float::isFinite))
                assertTrue(p.blink in 0f..1f);assertTrue(abs(p.headAngle)<=4f)
                assertTrue(p.wingAngle in 0f..6f);assertTrue(p.headY in 0f..4f)
                assertTrue(hypot(p.gazeX,p.gazeY)<=9.01f)
                assertTrue(abs(p.bodyX)<=3.5f);assertTrue(p.bodyY in -8f..2f)
                assertTrue(abs(p.bodyAngle)<=1.3f);assertTrue(p.bodyScale in .98f..1.01f)
                if(time>=gesture.durationMs)assertFalse(p.active)
            }
        }
    }
    @Test fun reducedHiddenUrgentAndPowerSaveAlwaysUseStaticNeutral() {
        OwlGesture.entries.forEach { gesture ->
            for(t in 0L..8_000L step 37) {
                assertEquals(OwlPose(),OwlMotionPolicy.sample(gesture,t,reduced=true))
                assertEquals(OwlPose(),OwlMotionPolicy.sample(gesture,t,visible=false))
                assertEquals(OwlPose(),OwlMotionPolicy.sample(gesture,t,urgent=true))
                assertEquals(OwlPose(),OwlMotionPolicy.sample(gesture,t,powerSave=true))
            }
        }
    }
    @Test fun gesturesFinishNeutralAndWaitingFreezesWithoutImpliedSuccess() {
        OwlGesture.entries.filter { it!=OwlGesture.WAITING }.forEach {
            assertEquals(OwlPose(),OwlMotionPolicy.sample(it,it.durationMs))
        }
        val ended=OwlMotionPolicy.sample(OwlGesture.WAITING,8_000)
        assertEquals(ended,OwlMotionPolicy.sample(OwlGesture.WAITING,86_400_000))
        assertEquals(OwlPose(),ended)
    }
    @Test fun singleVisitBlinkAndThreeFiniteWaitingBlinks() {
        OwlGesture.entries.filter { it!=OwlGesture.STILL }.forEach { gesture ->
            var spans=0;var closed=false
            for(t in 0..gesture.durationMs) { val next=OwlMotionPolicy.sample(gesture,t).blink>.94f
                if(next&&!closed)spans++;closed=next }
            assertEquals(gesture.name,if(gesture==OwlGesture.WAITING)3 else 1,spans)
        }
    }
    @Test fun everyWaitingPhraseHasARestAndDiminishingEnergy() {
        val maxima = (0L..2L).map { phrase ->
            val offset = phrase*2_400
            val end = OwlMotionPolicy.sample(OwlGesture.WAITING, offset+2_300).copy(active=false)
            assertEquals(OwlPose(),end)
            (0L..2_399L step 13).maxOf { OwlMotionPolicy.sample(OwlGesture.WAITING,offset+it).gazeX }
        }
        assertTrue(maxima[0]>maxima[1] && maxima[1]>maxima[2])
        assertEquals(OwlPose(),OwlMotionPolicy.sample(OwlGesture.WAITING,7_200))
    }
    @Test fun bodyAndFaceChannelsNeverJumpBetweenAdjacentMilliseconds() {
        OwlGesture.entries.forEach { gesture ->
            fun channels(p:OwlPose)=listOf(p.gazeX,p.gazeY,p.headAngle,p.headY,p.wingAngle,p.bodyX,p.bodyY,p.bodyAngle)
            var previous=channels(OwlMotionPolicy.sample(gesture,0))
            for(t in 1..gesture.durationMs) {
                val next=channels(OwlMotionPolicy.sample(gesture,t))
                next.zip(previous).forEach { (a,b)->assertTrue("${gesture.name} at $t",abs(a-b)<.25f) }
                previous=next
            }
        }
    }
    @Test fun accentsArriveQuicklyAndSettleWithoutOvershoot() {
        assertEquals(0f,NativeMotion.accent(0f))
        assertEquals(1f,NativeMotion.accent(.32f),.0001f)
        assertEquals(0f,NativeMotion.accent(1f))
        var previous=0f
        for(i in 0..320) {
            val next=NativeMotion.accent(i/1_000f)
            assertTrue(next in previous..1f);previous=next
        }
        for(i in 321..1_000) {
            val next=NativeMotion.accent(i/1_000f)
            assertTrue(next in 0f..previous);previous=next
        }
    }
    @Test fun exactVisualBandsNeverInventFreshness() {
        listOf(2.799 to OwlColorBand.WHITE,2.8 to OwlColorBand.YELLOW,3.599 to OwlColorBand.YELLOW,
            3.6 to OwlColorBand.GREEN,9.0 to OwlColorBand.GREEN,9.001 to OwlColorBand.RED,
            13.0 to OwlColorBand.RED,13.001 to OwlColorBand.PURPLE).forEach { (g,b)->assertEquals(b,owlColorBand(g,true)) }
        listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0,0.0).forEach { assertEquals(OwlColorBand.UNKNOWN,owlColorBand(it,true)) }
        assertEquals(OwlColorBand.UNKNOWN,owlColorBand(6.2,false))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidTimeIsRefused() { OwlMotionPolicy.sample(OwlGesture.GAZE,-1) }
    @Test fun allFortyRecipesAndTwelveEventIconsArePresentAndBounded() {
        assertEquals(52,ClinicalGlyphData.byId.size)
        ClinicalGlyphData.byId.forEach { (id,art) ->
            assertTrue(id,art.paths.isNotEmpty());assertTrue(id,art.durationMs in 260..760)
            art.paths.indices.forEach { index ->
                assertEquals(GlyphPartPose(),glyphPartPose(art,index,0,true))
                assertEquals(GlyphPartPose(),glyphPartPose(art,index,10_000,true))
                for(t in 0L..800L step 11) {
                    assertEquals(GlyphPartPose(),glyphPartPose(art,index,t,false))
                    val p=glyphPartPose(art,index,t,true)
                    assertTrue(abs(p.x)<=.75f);assertTrue(abs(p.y)<=2.5f);assertTrue(abs(p.angle)<=15f)
                    assertTrue(p.weight in 1.8f..2.5f);assertTrue(p.trace in 0f.. .601f)
                    if(art.manual)assertEquals(GlyphPartPose(),p)
                }
            }
        }
    }
    @Test fun everyOrdinaryGlyphHasRealFiniteMotionButUrgentGlyphsStayStill() {
        ClinicalGlyphData.byId.forEach { (id,art) ->
            val changes=(1 until art.durationMs step 7).any { t -> art.paths.indices.any { glyphPartPose(art,it,t.toLong(),true)!=GlyphPartPose() } }
            assertEquals(id,!art.manual,changes)
        }
        assertTrue(ClinicalGlyphData.byId.getValue("low-now").manual)
        assertTrue(ClinicalGlyphData.byId.getValue("low-imminent").manual)
    }
}
