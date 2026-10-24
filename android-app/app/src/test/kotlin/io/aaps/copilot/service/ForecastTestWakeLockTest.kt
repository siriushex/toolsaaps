package io.aaps.copilot.service

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ForecastTestWakeLockTest {
    private val power = ApplicationProvider.getApplicationContext<Context>().getSystemService(PowerManager::class.java)
    private val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forecast-test-lease")
    private val lease = ForecastTestWakeLock(lock, SystemClock::elapsedRealtime)

    @Test fun longSessionRenewsBoundedLeaseAndOneReleaseClearsAllAcquisitions() {
        try {
            assertThat(lock.isHeld).isFalse()
            lease.renew()
            repeat(361) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
                assertThat(lock.isHeld).isTrue()
                lease.renew()
            }
        } finally { lease.release() }
        assertThat(lock.isHeld).isFalse()
        lease.renew()
        assertThat(lock.isHeld).isFalse()
    }

    @Test fun noAdmissionRenewalLetsWakeLockExpireAfterTwoMinutes() {
        lease.renew()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ForecastTestWakeLock.LEASE_MS + 1))
        assertThat(lock.isHeld).isFalse()
        lease.release()
        lease.release()
    }

    @Test fun watchdogChecksDoNotContinuouslyExtendTheTimeout() {
        lease.renew()
        repeat(59) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            lease.renew()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(62))
        assertThat(lock.isHeld).isFalse()
        lease.release()
    }
}
