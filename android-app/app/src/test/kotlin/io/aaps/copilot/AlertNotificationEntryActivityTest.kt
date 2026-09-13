package io.aaps.copilot

import android.app.Application
import android.content.Intent
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.data.repository.AlertsRepository
import io.aaps.copilot.data.repository.GlucoseAlertNotifier
import io.aaps.copilot.ui.foundation.AlertNavigationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AlertEntryTestApplication::class, sdk = [35])
class AlertNotificationEntryActivityTest {
    @Before
    fun resetApplicationState() {
        ApplicationProvider.application.resetForTest()
    }

    @After
    fun closeApplicationState() {
        ApplicationProvider.application.shutdownForTest()
    }

    @Test
    fun lifecycleQueuesCandidateBeforeOpeningCleanMainActivity() {
        val intent = Intent(
            ApplicationProvider.application,
            AlertNotificationEntryActivity::class.java
        ).setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS)
            .putExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID, "glucose-alert-0001")

        val activity = Robolectric.buildActivity(AlertNotificationEntryActivity::class.java, intent)
            .create().get()

        assertThat(ApplicationProvider.application.candidates).containsExactly("glucose-alert-0001")
        val launched = shadowOf(activity).nextStartedActivity
        assertThat(launched.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(launched.extras).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun malformedAndDuplicateCapabilitiesAreQueuedInArrivalOrderForCentralValidation() {
        listOf("../bad", "glucose-alert-0001", "glucose-alert-0001").forEach { candidate ->
            val intent = Intent(ApplicationProvider.application, AlertNotificationEntryActivity::class.java)
                .setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS)
                .putExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID, candidate)
            Robolectric.buildActivity(AlertNotificationEntryActivity::class.java, intent).create()
        }

        assertThat(ApplicationProvider.application.candidates)
            .containsExactly("../bad", "glucose-alert-0001", "glucose-alert-0001").inOrder()
    }

    @Test
    fun closedPipelineStillLaunchesMainWithoutEpisodeData() {
        ApplicationProvider.application.accepting = false
        val intent = Intent(ApplicationProvider.application, AlertNotificationEntryActivity::class.java)
            .setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS)
            .putExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID, "glucose-alert-closed")

        val activity = Robolectric.buildActivity(AlertNotificationEntryActivity::class.java, intent)
            .create().get()

        val launched = shadowOf(activity).nextStartedActivity
        assertThat(launched.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(launched.extras).isNull()
        assertThat(ApplicationProvider.application.candidates).contains("glucose-alert-closed")
    }

    @Test
    fun roomValidationEntryHostAndCleanMainLaunchPreserveFifoUntilAcknowledged() = runBlocking {
        val app = ApplicationProvider.application
        app.db.alertEventDao().insert(
            AlertEventEntity(
                episodeId = "glucose-alert-valid",
                eventType = "GLUCOSE_ALERT_LOW",
                stage = "WARNING_30",
                status = "OPEN",
                severity = "WARNING_30",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                resolvedAt = null,
                localSnapshotJson = "{}",
                causeCode = null,
                causeSummary = null,
                suppressionUntil = null,
                lastNotificationAt = null,
                revision = 1L
            )
        )

        val validActivity = launchEntry("glucose-alert-valid")
        val first = withTimeout(5_000L) {
            app.coordinator.pendingRequest.filterNotNull().first()
        }
        assertThat(first.episodeId).isEqualTo("glucose-alert-valid")
        assertCleanMainLaunch(validActivity)

        val unknownActivity = launchEntry("glucose-alert-unknown")
        val duplicateActivity = launchEntry("glucose-alert-valid")
        assertThat(app.coordinator.pendingRequest.value).isEqualTo(first)
        assertThat(app.coordinator.acknowledge(first.token)).isTrue()

        val second = withTimeout(5_000L) {
            app.coordinator.pendingRequest.filterNotNull().first { it.token != first.token }
        }
        assertThat(second.episodeId).isNull()
        assertCleanMainLaunch(unknownActivity)
        assertThat(app.coordinator.acknowledge(second.token)).isTrue()

        val duplicate = withTimeout(5_000L) {
            app.coordinator.pendingRequest.filterNotNull().first { it.token != second.token }
        }
        assertThat(duplicate.episodeId).isEqualTo("glucose-alert-valid")
        assertCleanMainLaunch(duplicateActivity)
        assertThat(app.coordinator.acknowledge(duplicate.token)).isTrue()
    }

    private fun launchEntry(candidate: String): AlertNotificationEntryActivity {
        val intent = Intent(ApplicationProvider.application, AlertNotificationEntryActivity::class.java)
            .setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS)
            .putExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID, candidate)
        return Robolectric.buildActivity(AlertNotificationEntryActivity::class.java, intent)
            .create().get()
    }

    private fun assertCleanMainLaunch(activity: AlertNotificationEntryActivity) {
        val launched = shadowOf(activity).nextStartedActivity
        assertThat(launched.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(launched.extras).isNull()
    }

}

class AlertEntryTestApplication : Application(), AlertNavigationHost {
    val candidates = mutableListOf<String?>()
    var accepting: Boolean = true
    lateinit var db: CopilotDatabase
    lateinit var coordinator: AlertNavigationCoordinator
    private var coordinatorJob: Job = SupervisorJob()

    override fun onCreate() {
        super.onCreate()
        db = Room.inMemoryDatabaseBuilder(this, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        resetForTest()
    }

    fun resetForTest() {
        coordinatorJob.cancel()
        coordinatorJob = SupervisorJob()
        db.clearAllTables()
        candidates.clear()
        accepting = true
        val repository = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
        coordinator = AlertNavigationCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.IO),
            validateEpisodeId = repository::validatedGlucoseEpisodeId
        )
    }

    fun shutdownForTest() {
        coordinatorJob.cancel()
        db.close()
    }

    override fun enqueueAlertNavigation(candidate: String?): Boolean {
        candidates += candidate
        return accepting && coordinator.enqueue(candidate)
    }
}

private object ApplicationProvider {
    val application: AlertEntryTestApplication
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()
}
