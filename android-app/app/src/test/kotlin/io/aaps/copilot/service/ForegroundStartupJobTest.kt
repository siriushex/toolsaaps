package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundStartupJobTest {
    @Test
    fun deniedPermissionDoesNotPromptAgainOnResumeButServiceCanRetry() = runTest {
        var starts = 0
        var permissionPrompts = 0
        val job = ForegroundStartupJob(this, { true }, { it() })
        val startup: suspend () -> Unit = {
            starts++
            job.runPermissionsOnce { permissionPrompts++ }
        }
        job.enqueue(startup)
        advanceTimeBy(250)
        runCurrent()
        job.cancel()
        job.enqueue(startup)
        advanceTimeBy(250)
        runCurrent()
        assertThat(starts).isEqualTo(2)
        assertThat(permissionPrompts).isEqualTo(1)
    }

    @Test
    fun startedOnlyWaitsAndResumeCanRetry() = runTest {
        var resumed = false
        var starts = 0
        val job = ForegroundStartupJob(this, { resumed }, { it() })
        job.enqueue { starts++ }
        advanceTimeBy(250)
        runCurrent()
        assertThat(starts).isEqualTo(0)
        resumed = true
        job.enqueue { starts++ }
        advanceTimeBy(250)
        runCurrent()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun callbacksBeforeDrawAreCoalescedAndCancelledBeforeRapidResume() = runTest {
        val callbacks = mutableListOf<() -> Unit>()
        var starts = 0
        val job = ForegroundStartupJob(this, { true }, callbacks::add)
        job.enqueue { starts++ }
        job.enqueue { starts++ }
        assertThat(callbacks).hasSize(1)
        job.cancel()
        job.enqueue { starts++ }
        callbacks.forEach { it() }
        advanceTimeBy(250)
        runCurrent()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun pauseDuringPreparationCancelsOldAttemptAndResumeRetries() = runTest {
        val prepared = CompletableDeferred<Unit>()
        var starts = 0
        val job = ForegroundStartupJob(this, { true }, { it() })
        job.enqueue { prepared.await(); starts++ }
        advanceTimeBy(250)
        runCurrent()
        job.cancel()
        prepared.complete(Unit)
        runCurrent()
        assertThat(starts).isEqualTo(0)
        job.enqueue { starts++ }
        advanceTimeBy(250)
        runCurrent()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun repeatedEnqueueDuringDelayStartsOnlyOnce() = runTest {
        var starts = 0
        val job = ForegroundStartupJob(this, { true }, { it() })
        job.enqueue { starts++ }
        runCurrent()
        job.enqueue { starts++ }
        advanceTimeBy(250)
        runCurrent()
        assertThat(starts).isEqualTo(1)
    }
}
