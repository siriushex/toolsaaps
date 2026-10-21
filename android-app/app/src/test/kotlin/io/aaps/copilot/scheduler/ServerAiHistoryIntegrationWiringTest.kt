package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ServerAiHistoryIntegrationWiringTest {
    @Test fun oneDurableScheduleOwnsBackgroundUploadsAfterSessionRestore() {
        val container = source("service/AppContainer.kt")
        assertThat(Regex("serverAiConnectionManager\\.load\\(\\)").findAll(container).count()).isEqualTo(1)
        assertThat(container).doesNotContain("maintainRoomAiHistory(")
        val scheduling = container.substringAfter("// Restore access before")
            .substringBefore("clinicalReportRepository.initialize()")
        assertThat(scheduling).contains("serverAiConnectionInitialization.await()")
        assertThat(scheduling).contains("settings.aiHistoryUpload")
        assertThat(scheduling).contains("ServerAiHistoryUploadSchedule.apply(")
    }

    @Test fun scheduledSlicesRetainProfileAndTimingEvidenceWithoutModelOrTherapyRequests() {
        val container = source("service/AppContainer.kt")
        val sync = container.substringAfter("internal suspend fun synchronizeServerAiHistory()")
            .substringBefore("\n    init {")
        assertThat(sync).contains("historyUploadMutex.lock()")
        assertThat(sync).contains("historyUploadMutex.unlock()")
        assertThat(sync).contains("serverAiRoomHistorySync.sync(maxPages = 64)")
        assertThat(sync).contains("aiInsulinContextSync.sync(")
        assertThat(sync).contains("settingsStore.settings.first().insulinProfileId")
        assertThat(sync).contains(".timingHistory(zone)")
        assertThat(sync).doesNotContain("meal_timing_history_status=UNAVAILABLE")
        assertThat(sync).doesNotContain("submitChat")
        assertThat(sync).doesNotContain("setTempTarget")
        val worker = source("scheduler/ServerAiHistoryUploadWorker.kt")
        assertThat(worker).contains("uploadHistorySlices(container::synchronizeServerAiHistory)")
    }

    @Test fun durableUploadIntegrationDoesNotRemoveSilentCountdownOrDailyArchive() {
        val container = source("service/AppContainer.kt")
        assertThat(container).contains("insulinOnsetCountdown.collectLatest { notifications.follow(it) }")
        assertThat(container).contains("serverAiHistoryClient::readDailyResults")
        assertThat(source("security/ServerAiConnectionManager.kt")).contains("internal suspend fun exchangeMealPhoto(")
    }

    private fun source(path: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }),
        "src/main/kotlin/io/aaps/copilot/$path"
    ).readText()
}
