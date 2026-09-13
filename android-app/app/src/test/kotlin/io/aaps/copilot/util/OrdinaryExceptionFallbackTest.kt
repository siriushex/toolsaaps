package io.aaps.copilot.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

class OrdinaryExceptionFallbackTest {

    @Test
    fun ordinaryExceptionReturnsNull() {
        assertThat(ordinaryExceptionOrNull<String> { throw IllegalArgumentException("malformed") })
            .isNull()
    }

    @Test
    fun cancellationFatalAndNonFatalErrorsEscapeUnchanged() {
        listOf(
            CancellationException("cancel"),
            SimulatedUtilityVmError(),
            ThreadDeath(),
            AssertionError("assert"),
            LinkageError("link")
        ).forEach { failure ->
            var escaped: Throwable? = null
            try {
                ordinaryExceptionOrNull<Unit> { throw failure }
            } catch (caught: Throwable) {
                escaped = caught
            }
            assertThat(escaped).isSameInstanceAs(failure)
        }
    }
}

private class SimulatedUtilityVmError : VirtualMachineError()
