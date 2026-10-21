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
class ClinicalDisplayWorkRequestTest {
    @Test fun localOnlyRequestFollowsTheNextFiveIncludingDstAndNonIntegerOffsets() {
        listOf("2026-10-17T04:59:00+04:00[Asia/Tbilisi]", "2026-10-17T05:00:00+04:00[Asia/Tbilisi]",
            "2026-10-25T01:00:00+02:00[Europe/Berlin]", "2026-03-29T01:00:00+01:00[Europe/Berlin]",
            "2026-10-17T22:00:00+05:45[Asia/Kathmandu]").forEach { value ->
            val now = ZonedDateTime.parse(value)
            val request = WorkScheduler.clinicalDisplayRequest(now)
            assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
            assertEquals(NightlyAdviceSchedule.next(now).toInstant().toEpochMilli(), request.workSpec.nextScheduleTimeOverride)
            assertEquals(86_400_000L, request.workSpec.intervalDuration)
        }
    }
}
