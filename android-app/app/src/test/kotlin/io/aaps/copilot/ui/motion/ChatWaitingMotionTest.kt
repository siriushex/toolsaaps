package io.aaps.copilot.ui.motion

import org.junit.Assert.*
import org.junit.Test

class ChatWaitingMotionTest {
    @Test fun attentionIsFiniteDeterministicAndAtRestAfterItsBudget() {
        assertEquals(ChatWaitingMotion.pose(480), ChatWaitingMotion.pose(480))
        assertTrue(ChatWaitingMotion.pose(480).active)
        for (time in 0L..7_200L step 40) {
            val pose = ChatWaitingMotion.pose(time)
            assertTrue(pose.gazeX in 0f..9f)
            assertTrue(pose.headAngle in -2.4f..0f)
            assertTrue(pose.blink in 0f..1f)
            repeat(3) { assertTrue(ChatWaitingMotion.dotAlpha(it, time) in .28f..1f) }
        }
        assertEquals(OwlPose(), ChatWaitingMotion.pose(7_200))
        assertEquals(OwlPose(), ChatWaitingMotion.pose(Long.MAX_VALUE))
        repeat(3) { assertEquals(.45f, ChatWaitingMotion.dotAlpha(it, Long.MAX_VALUE)) }
    }
    @Test fun successiveTypingMarksAreStaggeredNotSimultaneous() {
        val values = (0..2).map { ChatWaitingMotion.dotAlpha(it, 480) }
        assertTrue(values.toSet().size > 1)
        assertTrue(runCatching { ChatWaitingMotion.pose(-1) }.isFailure)
    }
}
