package io.aaps.copilot.domain.alerts

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

class AlertCauseSnapshotFailureSemanticsTest {

    @Test
    fun malformedOrdinarySnapshotDataReturnsNull() {
        assertThat(AlertCauseSnapshotCodec.sanitize(AlertCauseSnapshot("{malformed"))).isNull()
    }

    @Test
    fun codecParserConvertsOnlyOrdinaryExceptionToNull() {
        val snapshot = AlertCauseSnapshotCodec.failureSnapshot()

        val sanitized = AlertCauseSnapshotCodec.sanitize(snapshot) {
            throw IllegalArgumentException("malformed parser input")
        }

        assertThat(sanitized).isNull()
    }

    @Test
    fun codecParserLetsCancellationAndFatalErrorsEscape() {
        val snapshot = AlertCauseSnapshotCodec.failureSnapshot()
        listOf(
            CancellationException("cancel parser"),
            SimulatedAlertCauseVmError(),
            ThreadDeath()
        ).forEach { failure ->
            assertSameFailureEscapes(failure) {
                AlertCauseSnapshotCodec.sanitize(snapshot) { throw failure }
            }
        }
    }

    @Test
    fun codecParserLetsNonFatalErrorsReachPreparationBoundary() {
        val snapshot = AlertCauseSnapshotCodec.failureSnapshot()
        listOf(
            AssertionError("assert parser"),
            LinkageError("link parser")
        ).forEach { failure ->
            assertSameFailureEscapes(failure) {
                AlertCauseSnapshotCodec.sanitize(snapshot) { throw failure }
            }
        }
    }

    private fun assertSameFailureEscapes(failure: Throwable, block: () -> Unit) {
        var escaped: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            escaped = caught
        }
        assertThat(escaped).isSameInstanceAs(failure)
    }
}

private class SimulatedAlertCauseVmError : VirtualMachineError()
