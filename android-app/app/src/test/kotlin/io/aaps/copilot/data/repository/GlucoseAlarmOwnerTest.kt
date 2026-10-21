package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class GlucoseAlarmOwnerTest {
    private lateinit var db: CopilotDatabase
    private val dispatcher = newSingleThreadContext("alert-owner-test")
    @Volatile private var elapsed = 1_000L
    private fun clock() = LocalAlarmSourceClock(100_000 + elapsed, elapsed, 2)
    private val feed = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE, ::clock)
    private val settings = MutableStateFlow(runBlocking {
        AppSettingsStore(object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
        }).settings.first().copy(localAlarmRuntimeEnabled = true)
    })
    private val mute = MutableStateFlow(0L)
    private val volume = object : AlarmVolumePort {
        @Volatile var index = 2
        override fun read() = AlarmVolumeSnapshot(index, 10, false)
        override fun set(index: Int) { this.index = index }
    }
    private val audio = Audio()
    private val platform = Platform()
    private fun owner() = GlucoseAlarmOwner(db, settings, feed.source, mute, feed::current,
        ::clock, volume, audio, platform, dispatcher)
    private fun publish(level: LocalAlarmLevel = LocalAlarmLevel.LOW_NOW): CheckedLocalAlarmSource {
        val ticket = feed.begin(LocalAlarmSourceObservation(level, clock().wallMs, clock().wallMs,
            clock().wallMs + 600_000, "synthetic-risk"))!!
        assertTrue(feed.publish(ticket, "event-one", 1))
        return feed.current()!!
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(8_000) { while (!condition()) delay(5) }
    private suspend fun running(block: suspend CoroutineScope.(GlucoseAlarmOwner) -> Unit) = coroutineScope {
        val owner = owner(); val job = launch { owner.run() }
        try { until { owner.state.value.phase == GlucoseAlarmOwnerPhase.WAITING }; block(owner) }
        finally { job.cancelAndJoin() }
    }
    private class Audio : LocalAlarmAudioPort {
        val starts = CopyOnWriteArrayList<LocalAlarmCycle>()
        val stops = CopyOnWriteArrayList<LocalAlarmCycle>()
        @Volatile var lease: LocalAlarmAudioOwner? = null
        @Volatile var plays = true
        @Volatile var prepares = true
        @Volatile var cleanupFails = false
        override suspend fun prepareLocalAlarm(owner: LocalAlarmAudioOwner): Boolean { lease = owner; return prepares }
        override fun ownsLocalAlarm(cycle: LocalAlarmCycle) = lease?.let { it.cycle == cycle && it.retained() } == true
        override suspend fun playLocalAlarm(cycle: LocalAlarmCycle, stepIndex: Int, settings: AppSettings,
            admitted: (LocalAlarmCycle) -> Boolean): GlucoseAlertAudioPlaybackResult {
            check(admitted(cycle)); starts += cycle
            return GlucoseAlertAudioPlaybackResult(played = plays, failureReason = if (plays) null else "synthetic_failure")
        }
        override fun stopLocalAlarm(cycle: LocalAlarmCycle) {
            stops += cycle
            if (cleanupFails) error("synthetic_cleanup_failure")
            lease?.takeIf { it.cycle == cycle }?.retire()
        }
    }
    private class Platform : GlucoseAlarmPlatform {
        @Volatile var capable = GlucoseAlarmCapabilities(true, true, true, true)
        @Volatile var scheduled: LocalAlarmWake? = null
        @Volatile var held: LocalAlarmCycle? = null
        @Volatile var vibration: LocalAlarmCycle? = null
        @Volatile var declinesWake = false
        override fun capabilities() = capable
        override fun publish(state: GlucoseAlarmOwnerState) = LocalAlarmNotificationResult.POSTED
        override fun schedule(wake: LocalAlarmWake?): Boolean {
            scheduled = wake.takeUnless { declinesWake }; return wake == null || !declinesWake
        }
        override fun acquireWake(cycle: LocalAlarmCycle): Boolean { check(held == null); held = cycle; return true }
        override fun releaseWake(cycle: LocalAlarmCycle) { if (held == cycle) held = null }
        override fun vibrate(cycle: LocalAlarmCycle): LocalAlarmVibrationResult { vibration = cycle; return LocalAlarmVibrationResult.REQUESTED }
        override fun stopVibration(cycle: LocalAlarmCycle) { if (vibration == cycle) vibration = null }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close(); dispatcher.close() }

    @Test fun emissionsAndRestartNeverAuthorizePlaybackWithoutAcceptedOffer() = runBlocking {
        running { owner ->
            publish(); delay(50)
            assertTrue(audio.starts.isEmpty()); assertNull(platform.scheduled)
            assertNull(owner.state.value.acknowledgement)
        }
    }

    @Test fun revokedConsentBeforeRunnerCreationExitsWithoutWaitingForAnotherSettingsEmission() = runBlocking {
        settings.value = settings.value.copy(localAlarmRuntimeEnabled = false)
        val owner = owner()
        withTimeout(3_000) { owner.run() }
        assertEquals(GlucoseAlarmOwnerPhase.OFF, owner.state.value.phase)
        assertTrue(audio.starts.isEmpty()); assertNull(platform.held); assertNull(platform.scheduled)
    }

    @Test fun initialUsesOnePlayerAndExactAcknowledgementStopsAndPausesOnlyItsCycle() = runBlocking {
        running { owner ->
            val result = owner.startInitial(publish(), settings.value)!!
            assertTrue(result.audio.played); assertTrue(result.vibration)
            until { platform.scheduled != null }
            assertEquals(1, audio.starts.size)
            val state = owner.state.value
            assertFalse(owner.acknowledge("old-session", state.acknowledgement!!))
            assertFalse(owner.acknowledge(state.sessionId!!, state.acknowledgement.copy(ordinal = 8)))
            assertTrue(owner.acknowledge(state.sessionId, state.acknowledgement))
            assertFalse(owner.acknowledge(state.sessionId, state.acknowledgement))
            until { platform.held == null }
            assertNull(platform.vibration); assertEquals(2, volume.index)
            assertEquals(1, audio.starts.size)
        }
        assertNull(platform.scheduled); assertNull(platform.held)
    }

    @Test fun worseningInvalidatesPriorActionAndStartsNewHigherPriorityCycle() = runBlocking {
        running { owner ->
            assertTrue(owner.startInitial(publish(LocalAlarmLevel.CRITICAL_5), settings.value)!!.audio.played)
            val old = owner.state.value
            owner.acceptedWithoutInitial(publish(LocalAlarmLevel.LOW_NOW))
            until { audio.starts.any { it.level == LocalAlarmLevel.LOW_NOW } }
            assertFalse(owner.acknowledge(old.sessionId!!, old.acknowledgement!!))
            assertEquals(LocalAlarmLevel.LOW_NOW, audio.starts.last().level)
        }
    }

    @Test fun seenSurvivesFreshCgmPointsAndAcceptedOffersForTheSameLow() = runBlocking {
        settings.value = settings.value.copy(strongLowRepeatMinutes = 1)
        running { owner ->
            owner.startInitial(publish(), settings.value)
            val action = owner.state.value
            assertTrue(owner.acknowledge(action.sessionId!!, action.acknowledgement!!))
            for (minute in 1..9) {
                elapsed = 1_000L + minute * 60_000L
                owner.acceptedWithoutInitial(publish())
                owner.signal()
                delay(30)
                assertEquals(1, audio.starts.size)
                assertEquals(GlucoseAlarmOwnerPhase.PAUSED, owner.state.value.phase)
            }
            elapsed = 601_000
            owner.acceptedWithoutInitial(publish()); owner.signal()
            until { audio.starts.size > 1 }
        }
    }

    @Test fun sourceExpiryStopsAudioCancelsAlarmAndRemovesAction() = runBlocking {
        running { owner ->
            owner.startInitial(publish(), settings.value)
            elapsed += 601_000; owner.signal()
            until { platform.held == null && platform.scheduled == null && owner.state.value.acknowledgement == null }
            assertNull(platform.vibration)
        }
    }

    @Test fun globalOffRemainsStrongerThanCurrentCriticalAndItsButton() = runBlocking {
        running { owner ->
            owner.startInitial(publish(), settings.value)
            val prior = owner.state.value
            RoomEpisodeAlertReceiptStore(db).writeMuteUntil(clock().wallMs + 1_800_000, clock().wallMs)
            mute.value = clock().wallMs + 1_800_000
            owner.signal()
            until { platform.held == null && platform.scheduled == null }
            assertFalse(owner.acknowledge(prior.sessionId!!, prior.acknowledgement!!))
            assertNull(owner.startInitial(feed.current()!!, settings.value))
            assertEquals(1, audio.starts.size)
        }
    }

    @Test fun nativeStartProofDefersLocalCycleAndSchedulesOnlyItsCriticalRepeat() = runBlocking {
        running { owner ->
            val source = publish()
            owner.nativeInitial(source, true)
            owner.acceptedWithoutInitial(source)
            until { platform.scheduled != null }
            assertEquals(121_000L, platform.scheduled!!.dueElapsedMs)
            assertTrue(audio.starts.isEmpty())
            elapsed = 121_000; owner.signal()
            until { audio.starts.isNotEmpty() }
            assertEquals(1, audio.starts.size)
        }
    }

    @Test fun cleanPreparationDeclineAllowsNativeFallbackWithoutDuplicateAudio() = runBlocking {
        audio.prepares = false
        running { owner -> assertNull(owner.startInitial(publish(), settings.value)) }
        assertTrue(audio.starts.isEmpty())
        assertNull(platform.held)
    }

    @Test fun failedCleanupReturnsExplicitFailureInsteadOfNativeFallback() = runBlocking {
        audio.plays = false; audio.cleanupFails = true
        running { owner ->
            val result = owner.startInitial(publish(), settings.value)
            assertNotNull(result); assertFalse(result!!.audio.played)
        }
    }

    @Test fun deniedNotificationCapabilityDeclinesBeforeAudioAndReportsLimited() = runBlocking {
        running { owner ->
            platform.capable = platform.capable.copy(notifications = false)
            assertNull(owner.startInitial(publish(), settings.value))
            until { owner.state.value.phase == GlucoseAlarmOwnerPhase.LIMITED }
            assertTrue(audio.starts.isEmpty())
        }
    }

    @Test fun declinedSystemAlarmDoesNotPretendDurableWakeExists() = runBlocking {
        running { owner ->
            platform.declinesWake = true
            assertTrue(owner.startInitial(publish(), settings.value)!!.audio.played)
            until { owner.state.value.phase == GlucoseAlarmOwnerPhase.LIMITED }
            assertNull(owner.state.value.nextAlarmElapsedMs)
        }
    }

    @Test fun disabledConsentStopsOwnedHardwareAndKeepsNativeSettingsUnchanged() = runBlocking {
        running { owner ->
            owner.startInitial(publish(), settings.value)
            settings.value = settings.value.copy(localAlarmRuntimeEnabled = false)
            until { owner.state.value.phase == GlucoseAlarmOwnerPhase.OFF && platform.held == null }
            assertNull(platform.scheduled); assertNull(platform.vibration)
        }
    }

    @Test fun disablingClinicalLevelWithdrawsActionAndDoesNotClaimAnActiveSignal() = runBlocking {
        running { owner ->
            owner.startInitial(publish(), settings.value)
            settings.value = settings.value.copy(lowNowAlertEnabled = false)
            until { platform.held == null && owner.state.value.acknowledgement == null }
            assertEquals(GlucoseAlarmOwnerPhase.WAITING, owner.state.value.phase)
            assertNull(platform.scheduled)
            assertEquals(1, audio.starts.size)
        }
    }
}
