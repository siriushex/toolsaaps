package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class TherapyActionTransportBarrierTest {

    @Test
    fun disarmWaitsForInFlightLeaseAndBlocksNextTransport() = runTest {
        val barrier = TherapyActionTransportBarrier()
        barrier.updateState(requestedArmed = true) { true }
        val transportStarted = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()

        val transport = async {
            barrier.withArmedLease(verifyPersistedState = { true }) {
                transportStarted.complete(Unit)
                releaseTransport.await()
                "sent"
            }
        }
        transportStarted.await()
        val disarm = async {
            barrier.updateState(requestedArmed = false) { true }
        }
        yield()

        assertThat(disarm.isCompleted).isFalse()
        releaseTransport.complete(Unit)
        assertThat(transport.await()).isEqualTo("sent")
        assertThat(disarm.await()).isFalse()

        val blocked = runCatching {
            barrier.withArmedLease(verifyPersistedState = { false }) { "unexpected" }
        }
        assertThat(blocked.exceptionOrNull()).isInstanceOf(TherapyActionsNotArmedException::class.java)
    }
}
