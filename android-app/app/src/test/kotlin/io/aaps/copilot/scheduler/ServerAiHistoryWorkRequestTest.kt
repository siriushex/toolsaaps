package io.aaps.copilot.scheduler

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import io.aaps.copilot.config.AiHistoryUploadSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = Application::class)
class ServerAiHistoryWorkRequestTest {
    @Test fun periodicRequestsPersistEachSelectedIntervalAndRequireNetwork() {
        for (minutes in AiHistoryUploadSettings.INTERVALS) {
            val spec = ServerAiHistoryUploadSchedule.periodicRequest(AiHistoryUploadSettings(intervalMinutes = minutes)).workSpec
            assertEquals(minutes * 60_000L, spec.intervalDuration)
            assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
            assertEquals(30_000L, spec.backoffDelayDuration)
            assertFalse(spec.input.getBoolean(ServerAiHistoryUploadSchedule.MANUAL, false))
            assertEquals(ServerAiHistoryScheduleWorker::class.java.name, spec.workerClassName)
        }
    }

    @Test fun manualRequestIsOneShotAndExplicitWithoutAClinicalPayload() {
        val spec = ServerAiHistoryUploadSchedule.manualRequest().workSpec
        assertEquals(0L, spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(setOf(ServerAiHistoryUploadSchedule.MANUAL), spec.input.keyValueMap.keys)
        assertTrue(spec.input.getBoolean(ServerAiHistoryUploadSchedule.MANUAL, false))
        assertNotEquals(ServerAiHistoryUploadSchedule.PERIODIC, ServerAiHistoryUploadSchedule.MANUAL_WORK)
    }

    @Test fun successfulPartialUploadHasOneStableShortDelaySuccessor() {
        val parent = java.util.UUID.randomUUID()
        val first = ServerAiHistoryUploadSchedule.uploadRequest(false, parent)
        val retry = ServerAiHistoryUploadSchedule.uploadRequest(false, parent)
        assertEquals(first.id, retry.id)
        assertNotEquals(parent, first.id)
        assertEquals(5_000L, first.workSpec.initialDelay)
        assertEquals(0L, first.workSpec.intervalDuration)
        assertEquals(ServerAiHistoryUploadWorker::class.java.name, first.workSpec.workerClassName)
        assertFalse(first.workSpec.input.getBoolean(ServerAiHistoryUploadSchedule.MANUAL, true))
        assertNotEquals(first.id, ServerAiHistoryUploadSchedule.uploadRequest(false, first.id).id)
        assertTrue(ServerAiHistoryUploadSchedule.uploadRequest(true, parent).workSpec.input
            .getBoolean(ServerAiHistoryUploadSchedule.MANUAL, false))
    }
}
