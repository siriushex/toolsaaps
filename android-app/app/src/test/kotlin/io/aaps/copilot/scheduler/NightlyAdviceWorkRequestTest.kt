package io.aaps.copilot.scheduler

import android.app.Application
import androidx.work.NetworkType
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NightlyAdviceWorkRequestTest {
    @Test fun actualRequestIsNetworkFreeAndAnchoredToTheNextLocalFive() {
        val now = ZonedDateTime.parse("2026-10-17T04:00:00+04:00[Asia/Tbilisi]")
        val request = WorkScheduler.nightlyAdviceRequest(now)
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(NightlyAdviceSchedule.next(now).toInstant().toEpochMilli(), request.workSpec.nextScheduleTimeOverride)
        assertEquals(86_400_000L, request.workSpec.intervalDuration)
    }
}
