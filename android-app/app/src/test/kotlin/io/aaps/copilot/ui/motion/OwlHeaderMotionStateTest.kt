package io.aaps.copilot.ui.motion

import org.junit.Assert.*
import org.junit.Test

class OwlHeaderMotionStateTest {
    private fun start(enabled: Boolean = true) = OwlHeaderMotionState.start(
        "overview", OwlGesture.GREET, false, false, enabled
    )

    @Test fun routeVisitsAreFiniteAndRepeatedInputsDoNotReplay() {
        val first = start()
        assertEquals(OwlGesture.GREET, first.gesture)
        assertEquals(720L, first.durationMs)
        assertEquals(first, first.next("overview", OwlGesture.GREET, false, false, true))
        val forecast = first.next("forecast", OwlGesture.GAZE, false, false, true)
        assertEquals(OwlGesture.GAZE, forecast.gesture)
        assertEquals(1L, forecast.generation)
        assertEquals(720L, forecast.durationMs)
        assertEquals(forecast, forecast.next("forecast", OwlGesture.GAZE, false, false, true))
        val back = forecast.next("overview", OwlGesture.GREET, false, false, true)
        assertEquals(2L, back.generation)
    }

    @Test fun closingMenuNeverRestartsTheRouteGesture() {
        val opened = start().next("overview", OwlGesture.GREET, true, false, true)
        assertEquals(OwlGesture.MENU, opened.gesture)
        assertEquals(480L, opened.durationMs)
        val closed = opened.next("overview", OwlGesture.GREET, false, false, true)
        assertEquals(opened.generation, closed.generation)
        assertEquals(opened.gesture, closed.gesture)
        assertFalse(closed.menuExpanded)
    }

    @Test fun actualBusyEdgesOwnWaitingNotItsDecorativeClock() {
        val entered = start().next("ai", OwlGesture.LISTEN, false, false, true)
        assertEquals(OwlGesture.LISTEN, entered.gesture)
        val waiting = entered.next("ai", OwlGesture.LISTEN, false, true, true)
        assertEquals(OwlGesture.WAITING, waiting.gesture)
        assertEquals(2_400L, waiting.durationMs)
        assertEquals(waiting, waiting.next("ai", OwlGesture.LISTEN, false, true, true))
        assertTrue(waiting.waiting)
        val finished = waiting.next("ai", OwlGesture.LISTEN, false, false, true)
        assertEquals(OwlGesture.STILL, finished.gesture)
        assertFalse(finished.waiting)
    }

    @Test fun suppressionCannotReplayWhenMotionReturns() {
        val suppressed = start().next("overview", OwlGesture.GREET, false, false, false)
        assertFalse(suppressed.animate)
        val resumed = suppressed.next("overview", OwlGesture.GREET, false, false, true)
        assertFalse(resumed.animate)
        assertEquals(suppressed.generation, resumed.generation)
        val explicitVisit = resumed.next("analytics", OwlGesture.NOD, false, false, true)
        assertTrue(explicitVisit.animate)
        assertFalse(start(false).next("overview", OwlGesture.GREET, false, false, true).animate)
    }

    @Test fun rapidTransitionsRetainOnlyTheLatestPresentation() {
        val opened = start().next("overview", OwlGesture.GREET, true, false, true)
        val changed = opened.next("forecast", OwlGesture.GAZE, false, false, true)
        val waiting = changed.next("ai", OwlGesture.LISTEN, false, true, true)
        assertEquals(OwlGesture.WAITING, waiting.gesture)
        val stopped = waiting.next("ai", OwlGesture.LISTEN, false, false, true)
        assertEquals(OwlGesture.STILL, stopped.gesture)
        assertEquals(4L, stopped.generation)
    }

    @Test fun temporaryUnresolvedRouteDoesNotCreateAnotherVisit() {
        val first = start()
        assertEquals(first, first.next("", OwlGesture.STILL, false, false, true))
    }
}
