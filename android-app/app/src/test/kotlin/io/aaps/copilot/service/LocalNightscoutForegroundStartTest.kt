package io.aaps.copilot.service

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class LocalNightscoutForegroundStartTest {
    @Before
    fun setUp() {
        TherapyActionRuntimeState.setArmed(true)
        PowerSaveRuntimeState.setUntil(0)
        AppVisibilityTracker.markForeground(true)
        LocalNightscoutRuntimeState.stopped(LocalNightscoutRuntimeReason.SERVICE_STOPPED)
    }

    @After
    fun tearDown() {
        TherapyActionRuntimeState.setArmed(false)
        PowerSaveRuntimeState.setUntil(0)
        AppVisibilityTracker.markForeground(false)
        LocalNightscoutRuntimeState.stopped(LocalNightscoutRuntimeReason.SERVICE_STOPPED)
    }

    @Test
    fun platformPromotionDenialStopsOnlyServiceBeforeStartingRuntime() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        val service = controller.get()
        val shadow = shadowOf(service)
        shadow.setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background"))
        try {
            val result = service.onStartCommand(Intent().setAction(LocalNightscoutForegroundService.ACTION_START), 0, 7)
            assertThat(result).isEqualTo(Service.START_NOT_STICKY)
            assertThat(shadow.isStoppedBySelf).isTrue()
            assertThat(shadow.lastForegroundNotification).isNull()
            assertThat(LocalNightscoutForegroundService.isMinuteLoopActive()).isFalse()
            assertThat(LocalNightscoutRuntimeState.value.reason).isEqualTo("FOREGROUND_START_NOT_ALLOWED")
        } finally {
            controller.destroy()
        }
        assertThat(LocalNightscoutRuntimeState.value.reason).isEqualTo("FOREGROUND_START_NOT_ALLOWED")
    }

    @Test
    fun missingCopilotApplicationDoesNotLeaveAnOrphanForegroundNotification() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        try {
            assertThat(controller.get().onStartCommand(Intent(), 0, 1)).isEqualTo(Service.START_NOT_STICKY)
            val shadow = shadowOf(controller.get())
            assertThat(shadow.isStoppedBySelf).isTrue()
            assertThat(shadow.isForegroundStopped).isTrue()
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun productionCleanupPassesDenialAndNormalReasonsToRuntimeStop() {
        val reasons = mutableListOf<LocalNightscoutRuntimeReason>()
        val cleanup: (LocalNightscoutRuntimeReason) -> Unit = { reason ->
            reasons += reason
            LocalNightscoutRuntimeState.stopped(reason)
        }
        stopLocalRuntimeAfterServiceExit(true, cleanup)
        assertThat(LocalNightscoutRuntimeState.value.reason).isEqualTo("FOREGROUND_START_NOT_ALLOWED")
        stopLocalRuntimeAfterServiceExit(false, cleanup)
        assertThat(reasons).containsExactly(
            LocalNightscoutRuntimeReason.FOREGROUND_START_NOT_ALLOWED,
            LocalNightscoutRuntimeReason.SERVICE_STOPPED
        ).inOrder()
    }

    @Test
    fun platformProgrammingFailureIsNotSwallowed() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("other failure"))
        try {
            val failure = runCatching { controller.get().onStartCommand(Intent(), 0, 1) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo("other failure")
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun controllerDoesNotFallbackToOrdinaryServiceAndCanRetryWhenOpened() {
        val context = CountingContext()
        context.denied = true
        LocalNightscoutServiceController.start(context)
        assertThat(context.foregroundCalls).isEqualTo(1)
        assertThat(context.ordinaryCalls).isEqualTo(0)
        assertThat(LocalNightscoutRuntimeState.value.reason).isEqualTo("FOREGROUND_START_NOT_ALLOWED")
        context.denied = false
        LocalNightscoutServiceController.start(context)
        assertThat(context.foregroundCalls).isEqualTo(2)
        assertThat(context.ordinaryCalls).isEqualTo(0)
    }

    @Test
    fun controllerRetainsVisibilityPowerSaveAndArmedGuards() {
        val context = CountingContext()
        AppVisibilityTracker.markForeground(false)
        LocalNightscoutServiceController.start(context)
        AppVisibilityTracker.markForeground(true)
        TherapyActionRuntimeState.setArmed(false)
        LocalNightscoutServiceController.start(context, allowBackground = true)
        TherapyActionRuntimeState.setArmed(true)
        PowerSaveRuntimeState.setUntil(System.currentTimeMillis() + 60_000)
        LocalNightscoutServiceController.start(context, allowBackground = true)
        assertThat(context.foregroundCalls).isEqualTo(0)
        assertThat(context.ordinaryCalls).isEqualTo(0)
    }

    private class CountingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var foregroundCalls = 0
        var ordinaryCalls = 0
        var denied = false

        override fun startForegroundService(service: Intent): ComponentName? {
            foregroundCalls++
            if (denied) throw ForegroundServiceStartNotAllowedException("background")
            return service.component
        }

        override fun startService(service: Intent): ComponentName? {
            ordinaryCalls++
            return service.component
        }
    }
}
