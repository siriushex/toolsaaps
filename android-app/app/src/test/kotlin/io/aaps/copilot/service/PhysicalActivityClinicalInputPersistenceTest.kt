package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhysicalActivityClinicalInputPersistenceTest {

    @Test
    fun localCollectorCommitsBeforeInvalidationAndSuppressesUnchangedNoop() = runTest {
        val order = mutableListOf<String>()

        val persisted = persistLocalActivityClinicalInput(
            shouldPersist = true,
            shouldInvalidate = true,
            persist = { order += "local_commit"; true },
            onClinicalInputPersisted = { order += "local_invalidate" }
        )
        val noop = persistLocalActivityClinicalInput(
            shouldPersist = false,
            shouldInvalidate = true,
            persist = { order += "unexpected_commit"; true },
            onClinicalInputPersisted = { order += "unexpected_invalidate" }
        )

        assertThat(persisted).isTrue()
        assertThat(noop).isFalse()
        assertThat(order).containsExactly("local_commit", "local_invalidate").inOrder()
    }

    @Test
    fun localCollectorFailureAndCancellationDoNotInvalidate() = runTest {
        var callbacks = 0
        val failed = runCatching {
            persistLocalActivityClinicalInput(
                shouldPersist = true,
                shouldInvalidate = true,
                persist = { error("local_db_failed") },
                onClinicalInputPersisted = { callbacks++ }
            )
        }
        val cancelled = runCatching {
            persistLocalActivityClinicalInput(
                shouldPersist = true,
                shouldInvalidate = true,
                persist = { throw CancellationException("local_cancelled") },
                onClinicalInputPersisted = { callbacks++ }
            )
        }

        assertThat(failed.exceptionOrNull()).hasMessageThat().isEqualTo("local_db_failed")
        assertThat(cancelled.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(callbacks).isEqualTo(0)
    }

    @Test
    fun healthConnectCommitsBeforeInvalidationAndSuppressesUnchangedNoop() = runTest {
        val order = mutableListOf<String>()

        val persisted = persistHealthConnectActivityClinicalInput(
            shouldPersist = true,
            persist = { order += "health_commit"; true },
            onClinicalInputPersisted = { order += "health_invalidate" }
        )
        val noop = persistHealthConnectActivityClinicalInput(
            shouldPersist = false,
            persist = { order += "unexpected_commit"; true },
            onClinicalInputPersisted = { order += "unexpected_invalidate" }
        )

        assertThat(persisted).isTrue()
        assertThat(noop).isFalse()
        assertThat(order).containsExactly("health_commit", "health_invalidate").inOrder()
    }

    @Test
    fun healthConnectFailureAndCancellationDoNotInvalidate() = runTest {
        var callbacks = 0
        val failed = runCatching {
            persistHealthConnectActivityClinicalInput(
                shouldPersist = true,
                persist = { error("health_db_failed") },
                onClinicalInputPersisted = { callbacks++ }
            )
        }
        val cancelled = runCatching {
            persistHealthConnectActivityClinicalInput(
                shouldPersist = true,
                persist = { throw CancellationException("health_cancelled") },
                onClinicalInputPersisted = { callbacks++ }
            )
        }

        assertThat(failed.exceptionOrNull()).hasMessageThat().isEqualTo("health_db_failed")
        assertThat(cancelled.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(callbacks).isEqualTo(0)
    }
}
