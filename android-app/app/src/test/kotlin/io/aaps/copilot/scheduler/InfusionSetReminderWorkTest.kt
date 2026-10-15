package io.aaps.copilot.scheduler

import android.app.Application
import androidx.work.NetworkType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InfusionSetReminderWorkTest {
    @Test fun oneTimeDeadlineHasNoClinicalOrNetworkConstraints() {
        val now = 1_800_000_000_000L
        val work = infusionReminderWork("infusion-$now", now + 3_600_000, now)
        assertEquals(3_600_000L, work.workSpec.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, work.workSpec.constraints.requiredNetworkType)
        assertFalse(work.workSpec.constraints.requiresBatteryNotLow())
        assertFalse(work.workSpec.constraints.requiresCharging())
        assertEquals("infusion-$now", work.workSpec.input.getString("generation"))
        assertEquals(0L, infusionReminderWork("infusion-$now", now - 1, now).workSpec.initialDelay)
    }
}
