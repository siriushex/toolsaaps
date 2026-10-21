package io.aaps.copilot.data.repository

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.ui.InsulinOnsetCountdown
import io.aaps.copilot.ui.insulinOnsetCountdown
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InsulinOnsetNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val now = 10_000_000L
    private val timer = InsulinOnsetCountdown("insulin-one", now - 900_000, now + 750_000, 27.5,
        mealAssociated = false, validUntilMs = now + 900_000)

    @Test fun retainedNotificationNeverAutoExpiresAndCannotLeaveANegativeChronometer() = kotlinx.coroutines.runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var time = now
        val waits = mutableListOf<Long>()
        val notifier = InsulinOnsetNotifier(context)
        notifier.follow(timer.copy(retained = true), { time }) { duration ->
            val notice = manager.activeNotifications.single().notification
            assertEquals(0L, notice.timeoutAfter)
            assertFalse(notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
            waits += duration
            time += duration
        }
        assertEquals(listOf(750_000L), waits)
        val completed = manager.activeNotifications.single().notification
        assertEquals(0L, completed.timeoutAfter)
        assertNull(completed.sound)
        assertNull(completed.vibrate)
        assertEquals(context.getString(io.aaps.copilot.R.string.onset_notification_elapsed),
            completed.extras.getCharSequence(Notification.EXTRA_TEXT))
        notifier.follow(null)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun invalidatedRetainedTimerPostsReviewWithoutCountdownOrScheduledWork() = kotlinx.coroutines.runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        InsulinOnsetNotifier(context).follow(timer.copy(retained = true, requiresReview = true), { now }) {
            fail("Review state must not schedule completion")
        }
        val notice = manager.activeNotifications.single().notification
        assertEquals(0L, notice.timeoutAfter)
        assertFalse(notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNull(notice.sound)
        assertNull(notice.vibrate)
        assertEquals(context.getString(io.aaps.copilot.R.string.onset_timer_review),
            notice.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun countdownHasNoSoundOrVibrationAndUsesSystemChronometer() {
        val notifier = InsulinOnsetNotifier(context)
        notifier.ensureChannel()
        val channel = manager.getNotificationChannel(InsulinOnsetNotifier.CHANNEL)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        val notice = notifier.notification(timer, now)
        assertNull(notice.sound)
        assertNull(notice.vibrate)
        assertEquals(0, notice.defaults)
        assertEquals(timer.estimatedAtMs, notice.`when`)
        assertTrue(notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notice.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notice.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull("quiet channel must allow a banner instead of the silent-notification group", notice.group)
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        assertTrue(notice.actions.isNullOrEmpty())
        assertEquals(750_000L, notice.timeoutAfter)
    }

    @Test fun expiryStopsChronometerAndDoesNotPrescribeEating() {
        val notice = InsulinOnsetNotifier(context).notification(timer, timer.estimatedAtMs)
        assertFalse(notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNull(notice.sound)
        assertFalse(notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("eat", true))
    }

    @Test fun clearingTheCountdownDoesNotCancelAGlucoseAlert() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = InsulinOnsetNotifier(context)
        notifier.show(timer, now)
        manager.notify(8410, Notification.Builder(context, InsulinOnsetNotifier.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).build())
        notifier.clear()
        assertEquals(listOf(8410), manager.activeNotifications.map { it.id })
    }

    @Test fun staleTimerAndBlockedChannelCannotPost() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = InsulinOnsetNotifier(context)
        assertFalse(notifier.show(timer, timer.validUntilMs + 1))
        manager.createNotificationChannel(android.app.NotificationChannel(InsulinOnsetNotifier.CHANNEL,
            "Disabled timer", NotificationManager.IMPORTANCE_NONE))
        assertFalse(notifier.show(timer, now))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun backgroundCountdownOnlyWorksAtDeadlineAndExpiryNotEverySecond() = kotlinx.coroutines.runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var time = now
        val waits = mutableListOf<Long>()
        InsulinOnsetNotifier(context).follow(timer, { time }) { duration ->
            val notice = manager.activeNotifications.single().notification
            assertEquals(waits.isEmpty(), notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            waits += duration
            time += duration
        }
        assertEquals(listOf(750_000L, 150_001L), waits)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun explicitValidityDeadlineClearsTimerWithoutClaimingOnset() = kotlinx.coroutines.runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var time = now
        var waits = 0
        InsulinOnsetNotifier(context).follow(timer.copy(validUntilMs = now + 60_000), { time }) { duration ->
            waits++
            assertEquals(60_001L, duration)
            time += duration
        }
        assertEquals(1, waits)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun acceptedEstimateKeepsQuietNotificationThroughOnsetAndCompletion() = kotlinx.coroutines.runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val estimate = AiInsulinContext("FIASP", 27.5, now - 14 * 60_000L, fittedProfileId = "FIASP")
        val event = TherapyEventEntity("normal-insulin", now, "meal_bolus", "{\"insulin\":2.2,\"carbs\":40}")
        val projected = insulinOnsetCountdown(listOf(event), estimate, now)!!
        var time = now
        val waits = mutableListOf<Long>()
        InsulinOnsetNotifier(context).follow(projected, { time }) { duration ->
            val notice = manager.activeNotifications.single().notification
            assertNull(notice.sound)
            assertNull(notice.vibrate)
            assertEquals(waits.isEmpty(), notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            waits += duration
            time += duration
        }
        assertEquals(listOf(1_650_000L, 900_001L), waits)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun userChangedChannelCannotMakeTheTimerAudible() {
        manager.createNotificationChannel(android.app.NotificationChannel(InsulinOnsetNotifier.CHANNEL,
            "Customized timer", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(android.net.Uri.parse("content://media/internal/audio/media/1"), null)
            enableVibration(true)
        })
        val notice = InsulinOnsetNotifier(context).notification(timer, now)
        assertNull(notice.sound)
        assertNull(notice.vibrate)
        assertNotNull("changed channel needs the explicit quiet fallback", notice.group)
    }
}
