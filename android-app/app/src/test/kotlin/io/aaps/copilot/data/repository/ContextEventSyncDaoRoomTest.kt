package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ContextEventSyncEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ContextEventSyncDaoRoomTest {

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
    fun queryReturnsOnlyOldestUnresolvedRevisionPerEventAndRotatesRetriedHeads() = runBlocking {
        val dao = db.contextEventSyncDao()
        dao.insert(row("a-r1", "event-a", revision = 1L, attemptedAt = 10L))
        dao.insert(row("a-r2", "event-a", revision = 2L, attemptedAt = 1L))
        dao.insert(row("b-r1", "event-b", revision = 1L, attemptedAt = 20L))
        dao.insert(row("c-r1", "event-c", revision = 1L, attemptedAt = 30L))

        val first = dao.orderedBatchByStatus(ContextEventSyncCoordinator.STATUS_PENDING, limit = 2)

        assertThat(first.map { it.syncId }).containsExactly("a-r1", "b-r1").inOrder()
        dao.update(first.first().copy(attemptedAt = 100L))

        val second = dao.orderedBatchByStatus(ContextEventSyncCoordinator.STATUS_PENDING, limit = 2)

        assertThat(second.map { it.syncId }).containsExactly("b-r1", "c-r1").inOrder()
        Unit
    }

    @Test
    fun failedPredecessorBlocksLaterRevisionUntilItIsExplicitlyResolved() = runBlocking {
        val dao = db.contextEventSyncDao()
        dao.insert(
            row("a-r1", "event-a", revision = 1L, attemptedAt = 10L).copy(
                status = ContextEventSyncCoordinator.STATUS_FAILED,
                completedAt = 11L
            )
        )
        dao.insert(row("a-r2", "event-a", revision = 2L, attemptedAt = 20L))
        dao.insert(row("b-r1", "event-b", revision = 1L, attemptedAt = 30L))

        val selected = dao.orderedBatchByStatus(ContextEventSyncCoordinator.STATUS_PENDING, limit = 8)

        assertThat(selected.map { it.syncId }).containsExactly("b-r1")
        Unit
    }

    @Test
    fun firstUnresolvedUsesTheSameOperationIndependentPolicyAsReconciliation() = runBlocking {
        val dao = db.contextEventSyncDao()
        dao.insert(
            row("completed", "event-a", revision = 1L, attemptedAt = 1L).copy(
                status = ContextEventSyncCoordinator.STATUS_COMPLETED,
                completedAt = 2L
            )
        )
        dao.insert(
            row("failed", "event-a", revision = 2L, attemptedAt = 3L).copy(
                status = ContextEventSyncCoordinator.STATUS_FAILED,
                completedAt = 4L
            )
        )
        dao.insert(row("pending", "event-a", revision = 3L, attemptedAt = 5L))

        assertThat(dao.firstUnresolvedForEvent("event-a")?.syncId).isEqualTo("failed")
        Unit
    }

    @Test
    fun housekeepingDeletesOnlyCompletedAndRetainsFailedPredecessor() = runBlocking {
        val dao = db.contextEventSyncDao()
        dao.insert(
            row("completed", "event-a", revision = 1L, attemptedAt = 1L).copy(
                status = ContextEventSyncCoordinator.STATUS_COMPLETED,
                completedAt = 2L
            )
        )
        dao.insert(
            row("failed", "event-b", revision = 1L, attemptedAt = 1L).copy(
                status = ContextEventSyncCoordinator.STATUS_FAILED,
                completedAt = 2L
            )
        )

        assertThat(dao.deleteCompletedOlderThan(olderThan = 3L)).isEqualTo(1)
        assertThat(dao.latestForEvent("event-a")).isNull()
        assertThat(dao.firstUnresolvedForEvent("event-b")?.syncId).isEqualTo("failed")
        Unit
    }

    private fun row(
        syncId: String,
        eventId: String,
        revision: Long,
        attemptedAt: Long
    ) = ContextEventSyncEntity(
        syncId = syncId,
        eventId = eventId,
        revision = revision,
        operation = if (revision == 1L) "CREATE" else "UPDATE",
        requestHash = "digest.payload",
        status = ContextEventSyncCoordinator.STATUS_PENDING,
        attemptedAt = attemptedAt,
        completedAt = null,
        sanitizedError = null
    )
}
