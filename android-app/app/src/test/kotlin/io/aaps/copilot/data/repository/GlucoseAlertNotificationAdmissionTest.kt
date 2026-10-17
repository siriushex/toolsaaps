package io.aaps.copilot.data.repository

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GlucoseAlertNotificationAdmissionTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var db: CopilotDatabase
    private lateinit var notifier: GlucoseAlertNotifier

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        notifier = GlucoseAlertNotifier(context, GlucoseAlertAudioController(context),
            EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db)))
    }

    @After fun close() { db.close() }

    @Test fun blockedSelectedChannelIsNotReportedPostedOrRecreated() = runBlocking {
        manager.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, "Blocked watch",
            NotificationManager.IMPORTANCE_NONE))

        val result = deliver()

        assertThat(result.notificationPermissionGranted).isTrue()
        assertThat(result.notificationPosted).isFalse()
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.FAILED)
        assertThat(manager.activeNotifications).isEmpty()
        assertThat(manager.getNotificationChannel(WATCH_CHANNEL).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_NONE)
        val event = db.alertEventDao().latestUnresolvedGlucoseEpisode()!!
        assertThat(db.alertDeliveryReceiptDao().byEpisodeAndKind(event.episodeId, "INITIAL")!!.result)
            .isEqualTo(AlertReceiptResult.FAILED.name)
    }

    @Test fun enabledSelectedChannelPostsOneSilentWatchAndDuplicateDoesNotReplay() = runBlocking {
        val first = deliver()
        val duplicate = deliver(11_000L)

        assertThat(first.notificationPosted).isTrue()
        assertThat(first.audioPlayed).isFalse()
        assertThat(first.vibrationSucceeded).isFalse()
        assertThat(first.degradedReason).isNull()
        assertThat(duplicate.notificationPosted).isFalse()
        assertThat(manager.activeNotifications).hasLength(1)
        assertThat(manager.activeNotifications.single().notification.channelId).isEqualTo(WATCH_CHANNEL)
    }

    @Test fun deniedPermissionCannotBeReportedPosted() = runBlocking {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = deliver()

        assertThat(result.notificationPermissionGranted).isFalse()
        assertThat(result.notificationPosted).isFalse()
        assertThat(manager.activeNotifications).isEmpty()
    }

    @Test fun blockedDifferentChannelDoesNotSuppressAllowedWatch() = runBlocking {
        manager.createNotificationChannel(NotificationChannel("copilot_glucose_alert_soft", "Blocked soft",
            NotificationManager.IMPORTANCE_NONE))

        assertThat(deliver().notificationPosted).isTrue()
        assertThat(manager.getNotificationChannel("copilot_glucose_alert_soft").importance)
            .isEqualTo(NotificationManager.IMPORTANCE_NONE)
    }

    @Test fun blockedChannelGroupIsNotReportedPostedOrUnblocked() = runBlocking {
        val group = NotificationChannelGroup("risk_group", "Risk group")
        ReflectionHelpers.setField(group, "mBlocked", true)
        manager.createNotificationChannelGroup(group)
        manager.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, "Watch in group",
            NotificationManager.IMPORTANCE_LOW).apply { this.group = group.id })
        assertThat(manager.getNotificationChannelGroup(group.id).isBlocked).isTrue()

        assertThat(deliver().notificationPosted).isFalse()
        assertThat(manager.activeNotifications).isEmpty()
        assertThat(manager.getNotificationChannelGroup(group.id).isBlocked).isTrue()
    }

    @Test fun globallyDisabledNotificationsAreNotPosted() = runBlocking {
        shadowOf(manager).setNotificationsEnabled(false)
        assertThat(deliver().notificationPosted).isFalse()
        assertThat(manager.activeNotifications).isEmpty()
    }

    private suspend fun deliver(now: Long = 10_000L): GlucoseAlertDeliveryResult {
        val defaults = AppSettingsStore(object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("read-only defaults")
        }).settings.first()
        val decision = GlucoseAlertDecision(
            state = GlucoseAlertState.WATCH_60, direction = GlucoseAlertDirection.LOW,
            notifyKind = GlucoseAlertNotifyKind.WATCH_60, nextState = GlucoseAlertRuntimeState(),
            lowThreshold = 4.4, highThreshold = 10.0, urgentLowThreshold = 4.0,
            pred5 = 5.0, pred30 = 4.6, pred60 = 4.2, ciLow30 = 4.4, ciHigh30 = 5.0,
            currentGlucoseMmol = 5.0, currentGlucoseFresh = true, predictedMinutesToLow = 45,
            trendDelta5Mmol = -0.1, softActive = true, strongActive = false,
            repeatSuppressedByTrend = false, disableReason = null)
        return notifier.deliver(decision, 5.0, defaults, nowTs = now)
    }

    private companion object {
        const val WATCH_CHANNEL = "copilot_glucose_alert_watch"
    }
}
