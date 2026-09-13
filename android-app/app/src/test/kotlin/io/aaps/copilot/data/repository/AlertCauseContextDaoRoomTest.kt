package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AlertCauseContextDaoRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun irrelevantRecurrencesBeforeActiveRowDoNotEvictRelevantOccurrence() = runBlocking {
        db.energyProfileDao().insertEvents(
            (0 until AutomationRepository.ALERT_CAUSE_MAX_PLANNED_EVENTS).map { index ->
                planned(
                    id = "irrelevant-${index.toString().padStart(3, '0')}",
                    localStartIso = "2029-01-01T10:00:00",
                    recurrenceDaysMask = TUESDAY_MASK
                )
            } + planned(
                id = "zzz-active",
                localStartIso = "2029-01-01T11:30:00",
                recurrenceDaysMask = MONDAY_MASK
            )
        )

        val loadedIds = mutableListOf<String>()
        val result = AutomationRepository.loadRelevantPlannedAlertEventsStatic(NOW) { afterStart, afterId, limit ->
            db.energyProfileDao().alertCauseEnabledCandidatesPage(afterStart, afterId, limit)
                .also { page -> loadedIds += page.map { it.eventId } }
        }

        assertThat(result).isInstanceOf(AlertCausePlannedLoadResult.Complete::class.java)
        val complete = result as AlertCausePlannedLoadResult.Complete
        assertThat(complete.events.map { it.localId })
            .containsExactly("planned:zzz-active:$ACTIVE_START_TS")
        assertThat(complete.pagesRead).isEqualTo(2)
        assertThat(loadedIds).hasSize(AutomationRepository.ALERT_CAUSE_MAX_PLANNED_EVENTS + 1)
        Unit
    }

    @Test
    fun sixtyFiveGenuinelyActiveRecurrencesReportRelevantOverflow() = runBlocking {
        db.energyProfileDao().insertEvents(
            (0..AutomationRepository.ALERT_CAUSE_MAX_PLANNED_EVENTS).map { index ->
                planned(
                    id = "recurring-${index.toString().padStart(3, '0')}",
                    localStartIso = "2029-01-01T11:30:00",
                    recurrenceDaysMask = MONDAY_MASK
                )
            }
        )

        val result = AutomationRepository.loadRelevantPlannedAlertEventsStatic(NOW) { afterStart, afterId, limit ->
            db.energyProfileDao().alertCauseEnabledCandidatesPage(afterStart, afterId, limit)
        }

        assertThat(result).isEqualTo(AlertCausePlannedLoadResult.RelevantOverflow)
    }

    @Test
    fun cursorPagingTerminatesAndBreaksLocalStartTiesByEventId() = runBlocking {
        val expectedIds = (0..AutomationRepository.ALERT_CAUSE_MAX_PLANNED_EVENTS).map { index ->
            "tie-${index.toString().padStart(3, '0')}"
        }
        db.energyProfileDao().insertEvents(
            expectedIds.reversed().map { id ->
                planned(
                    id = id,
                    localStartIso = "2029-01-01T10:00:00",
                    recurrenceDaysMask = TUESDAY_MASK
                )
            }
        )
        val loadedIds = mutableListOf<String>()

        val result = AutomationRepository.loadRelevantPlannedAlertEventsStatic(NOW) { afterStart, afterId, limit ->
            db.energyProfileDao().alertCauseEnabledCandidatesPage(afterStart, afterId, limit)
                .also { page -> loadedIds += page.map { it.eventId } }
        }

        assertThat(result).isInstanceOf(AlertCausePlannedLoadResult.Complete::class.java)
        assertThat((result as AlertCausePlannedLoadResult.Complete).pagesRead).isEqualTo(2)
        assertThat(loadedIds).containsExactlyElementsIn(expectedIds).inOrder()
        assertThat(loadedIds.distinct()).hasSize(expectedIds.size)
    }

    @Test
    fun contextQueryReturnsLimitPlusOneInDeterministicOrder() = runBlocking {
        db.physioContextTagDao().upsertAll(
            (0..AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS).map { index ->
                PhysioContextTagEntity(
                    id = "tag-${index.toString().padStart(3, '0')}",
                    tsStart = NOW - index,
                    tsEnd = NOW + 60_000L,
                    tagType = "STRESS",
                    severity = (index % 3).toDouble(),
                    source = "LOCAL",
                    note = ""
                )
            }
        )

        val contextRows = db.physioContextTagDao().activeAtLimited(
            ts = NOW,
            limit = AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS + 1
        )

        assertThat(contextRows).hasSize(AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS + 1)
        assertThat(contextRows.zipWithNext().all { (left, right) ->
            left.severity > right.severity ||
                left.severity == right.severity && left.tsStart >= right.tsStart
        }).isTrue()
        assertThat(
            AutomationRepository.hasAlertCauseOverflowStatic(
                contextRows,
                AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS
            )
        ).isTrue()
    }

    @Test
    fun closedContextVolumeCannotEvictTheOnlyActiveContextRow() = runBlocking {
        val active = PhysioContextTagEntity(
            id = "active-stress",
            tsStart = NOW - 60_000L,
            tsEnd = NOW + 60_000L,
            tagType = "STRESS",
            severity = 0.5,
            source = "LOCAL",
            note = "",
            status = "ACTIVE"
        )
        db.physioContextTagDao().upsertAll(
            listOf(active) + (0 until 100).map { index ->
                active.copy(
                    id = "closed-${index.toString().padStart(3, '0')}",
                    severity = 1.0,
                    status = "CLOSED"
                )
            }
        )

        val rows = db.physioContextTagDao().activeAtLimited(
            ts = NOW,
            limit = AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS + 1
        )

        assertThat(rows.map { it.id }).containsExactly("active-stress")
        Unit
    }

    @Test
    fun rowsEndingAtNowCannotEvictTheOnlyHalfOpenActiveContextRow() = runBlocking {
        val active = PhysioContextTagEntity(
            id = "active-stress",
            tsStart = NOW - 60_000L,
            tsEnd = NOW + 60_000L,
            tagType = "STRESS",
            severity = 0.5,
            source = "LOCAL",
            note = "",
            status = "ACTIVE"
        )
        db.physioContextTagDao().upsertAll(
            listOf(active) + (0..AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS).map { index ->
                active.copy(
                    id = "ended-${index.toString().padStart(3, '0')}",
                    tsStart = NOW - index,
                    tsEnd = NOW,
                    severity = 1.0
                )
            }
        )

        val rows = db.physioContextTagDao().activeAtLimited(
            ts = NOW,
            limit = AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS + 1
        )
        val unboundedRows = db.physioContextTagDao().activeAt(NOW)

        assertThat(rows.map { it.id }).containsExactly("active-stress")
        assertThat(unboundedRows.map { it.id }).containsExactly("active-stress")
        assertThat(
            AutomationRepository.hasAlertCauseOverflowStatic(
                rows,
                AutomationRepository.ALERT_CAUSE_MAX_CONTEXT_EVENTS
            )
        ).isFalse()
        Unit
    }

    private fun planned(
        id: String,
        localStartIso: String,
        recurrenceDaysMask: Int,
        recurrenceEndEpochDay: Long? = null
    ) = PlannedActivityEventEntity(
        eventId = id,
        enabled = true,
        title = "Activity",
        activityType = "WALKING",
        intensity = "MEDIUM",
        localStartIso = localStartIso,
        durationMinutes = 120,
        timezoneId = "UTC",
        recurrenceDaysMask = recurrenceDaysMask,
        recurrenceEndEpochDay = recurrenceEndEpochDay,
        revision = 1L,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    companion object {
        private const val MONDAY_MASK = 1
        private const val TUESDAY_MASK = 2
        private val NOW = java.time.Instant.parse("2030-01-07T12:00:00Z").toEpochMilli()
        private val ACTIVE_START_TS = java.time.LocalDateTime.parse("2030-01-07T11:30:00")
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }
}
