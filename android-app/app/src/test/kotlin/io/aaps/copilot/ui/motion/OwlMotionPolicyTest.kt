package io.aaps.copilot.ui.motion

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class OwlMotionPolicyTest {
    @Test fun everyGestureIsBoundedAndFinite() {
        OwlGesture.entries.forEach { gesture ->
            assertTrue(gesture.durationMs in 0..8_000)
            for (time in 0L..9_000L step 7) {
                val p=OwlMotionPolicy.sample(gesture,time)
                assertTrue(listOf(p.blink,p.gazeX,p.gazeY,p.headAngle,p.headY,p.wingAngle,p.bodyScale).all(Float::isFinite))
                assertTrue(p.blink in 0f..1f);assertTrue(abs(p.headAngle)<=4f)
                assertTrue(p.wingAngle in 0f..6f);assertTrue(p.headY in 0f..4f)
                assertTrue(hypot(p.gazeX,p.gazeY)<=4.201f)
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
        assertNotNull(ended.orbitAngle);assertFalse(ended.active)
    }
    @Test fun exactlyOneBlinkAndNoPermanentFacialOrWingMotion() {
        OwlGesture.entries.filter { it!=OwlGesture.STILL }.forEach { gesture ->
            var spans=0;var closed=false
            for(t in 0..gesture.durationMs) { val next=OwlMotionPolicy.sample(gesture,t).blink>.94f
                if(next&&!closed)spans++;closed=next }
            assertEquals(gesture.name,1,spans)
        }
    }
    @Test fun bothPupilsPointFromTheirOwnCentersAtTheSameOrbitTarget() {
        for(t in 0L..7_999L step 31) {
            val p=OwlMotionPolicy.sample(OwlGesture.WAITING,t)
            listOf(96f,184f).forEach { x ->
                val target=OwlMotionPolicy.eyeTarget(p,x);assertEquals(4.2f,hypot(target.first,target.second),.001f)
            }
        }
        val p=OwlMotionPolicy.sample(OwlGesture.WAITING,0)
        assertTrue(OwlMotionPolicy.eyeTarget(p,96f).first>0f)
        assertTrue(OwlMotionPolicy.eyeTarget(p,184f).first<0f)
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
