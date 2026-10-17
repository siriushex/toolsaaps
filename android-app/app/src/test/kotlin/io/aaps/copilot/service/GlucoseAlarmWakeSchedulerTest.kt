package io.aaps.copilot.service

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.repository.LocalAlarmSourceClock
import io.aaps.copilot.domain.alerts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GlucoseAlarmWakeSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var now = LocalAlarmSourceClock(100_000, 1_000, 2)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun wake(level: LocalAlarmLevel = LocalAlarmLevel.LOW_NOW, due: Long = 121_000) =
        LocalAlarmWake(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "event-one"), 1, 1, level, 2, due, 601_000)
    private fun scheduler() = GlucoseAlarmWakeScheduler(context) { now }

    @Test fun oneGenuineAlarmClockIsStableAndCancellationRemovesIt() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = scheduler()
        assertTrue(scheduler.schedule(wake()))
        val first = alarms.nextAlarmClock!!
        assertEquals(220_000, first.triggerTime)
        assertTrue(scheduler.schedule(wake()))
        assertEquals(first, alarms.nextAlarmClock)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        scheduler.schedule(null)
        assertNull(alarms.nextAlarmClock)
    }

    @Test fun permissionDenialAndNoncriticalOrExpiredWakeDoNotCreateAlarm() {
        val scheduler = scheduler()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.schedule(wake()))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertFalse(scheduler.schedule(wake(LocalAlarmLevel.WARNING_30)))
        assertFalse(scheduler.schedule(wake(due = 601_000)))
        assertNull(alarms.nextAlarmClock)
    }

    @Test fun replacedAndReplayedWakeTokensCannotConsumeCurrentPlan() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = scheduler(); scheduler.schedule(wake())
        val prefs = context.getSharedPreferences("glucose_alarm_wake_v1", Context.MODE_PRIVATE)
        val old = prefs.getString("token", null)
        scheduler.schedule(wake(due = 181_000))
        val current = prefs.getString("token", null)
        fun intent(token: String?) = Intent(GlucoseAlarmWakeScheduler.ACTION_WAKE)
            .putExtra(GlucoseAlarmWakeScheduler.EXTRA_TOKEN, token)
        now = now.copy(elapsedMs = 181_000, wallMs = 280_000)
        assertFalse(scheduler.consume(intent(old)))
        assertTrue(scheduler.consume(intent(current)))
        assertFalse(scheduler.consume(intent(current)))
    }

    @Test fun clockBootEarlyLateAndMissingWindowAreRejected() {
        val valid = LocalAlarmSourceClock(220_000, 121_000, 2)
        fun accepts(n: LocalAlarmSourceClock) = GlucoseAlarmWakeScheduler.validWakeWindow(n, 2, 1_000, 100_000, 121_000, 601_000)
        assertTrue(accepts(valid))
        assertFalse(accepts(valid.copy(bootCount = 3)))
        assertFalse(accepts(valid.copy(wallMs = 226_000)))
        assertFalse(accepts(valid.copy(elapsedMs = 120_999, wallMs = 219_999)))
        assertFalse(accepts(valid.copy(elapsedMs = 601_000, wallMs = 700_000)))
        assertFalse(GlucoseAlarmWakeScheduler.validWakeWindow(valid, -1, -1, -1, -1, -1))
    }
}
