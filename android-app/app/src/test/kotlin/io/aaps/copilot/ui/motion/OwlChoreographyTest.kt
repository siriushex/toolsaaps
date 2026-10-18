package io.aaps.copilot.ui.motion

import org.junit.Assert.*
import org.junit.Test

class OwlChoreographyTest {
    @Test fun everySceneStartsAndEndsInTheSameVisibleRestPose() {
        OwlGesture.entries.forEach { gesture ->
            assertEquals(gesture.name, OwlPose(),
                OwlMotionPolicy.sample(gesture, 0).copy(active = false))
            assertEquals(gesture.name, OwlPose(),
                OwlMotionPolicy.sample(gesture, gesture.durationMs))
        }
    }

    @Test fun gazeAndGreetingHaveVisibleLayeredActionNotOnlyOneTinyChannel() {
        val gaze = (0L..900L step 5).map { OwlMotionPolicy.sample(OwlGesture.GAZE, it) }
        assertTrue("forward gaze must be visible at header optical size", gaze.maxOf { it.gazeX } >= 8f)
        assertTrue("head must follow the eyes", gaze.maxOf { it.headAngle } >= 1f)
        val greeting = (0L..840L step 5).map { OwlMotionPolicy.sample(OwlGesture.GREET, it) }
        assertTrue("greeting needs gentle anticipation", greeting.minOf { it.bodyScale } < .995f)
        assertTrue("wing and head should move together", greeting.any { it.wingAngle > 2f && it.headAngle < -.5f })
    }

    @Test fun headerAndChatUseOneFiniteDiminishingAttentionScene() {
        assertEquals(7_200L, OwlGesture.WAITING.durationMs)
        assertEquals(OwlGesture.WAITING.durationMs, ChatWaitingMotion.durationMs)
        for (time in 0L..8_000L step 17) {
            assertEquals("shared pose at $time", OwlMotionPolicy.sample(OwlGesture.WAITING, time),
                ChatWaitingMotion.pose(time))
        }
        val maxima = (0..2).map { cycle ->
            (0L until 2_400L step 13).maxOf { OwlMotionPolicy.sample(OwlGesture.WAITING, cycle * 2_400L + it).gazeX }
        }
        assertTrue(maxima[0] > maxima[1] && maxima[1] > maxima[2])
        assertEquals(OwlPose(), ChatWaitingMotion.pose(Long.MAX_VALUE))
    }
}
