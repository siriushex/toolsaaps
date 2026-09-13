package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.dao.ActionCommandDao
import io.aaps.copilot.data.local.dao.AutomaticSentCommandEvidence
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionCommandUamExportReservationStoreTest {

    @Test
    fun twoStoreInstancesRacingForSameKeyOnlyReserveOnce() = runBlocking {
        val dao = FakeActionCommandDao()
        val first = store(dao)
        val second = store(dao)
        val start = CompletableDeferred<Unit>()

        val results = coroutineScope {
            listOf(first, second).map { candidate ->
                async(Dispatchers.Default) {
                    start.await()
                    candidate.reserve(KEY)
                }
            }.also { start.complete(Unit) }.map { it.await() }
        }

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(results.count { !it }).isEqualTo(1)
        assertThat(dao.rows).hasSize(1)
    }

    @Test
    fun newStoreInstanceBlocksExistingReservationAfterRestart() = runBlocking {
        val dao = FakeActionCommandDao()
        assertThat(store(dao).reserve(KEY)).isTrue()

        assertThat(store(dao).reserve(KEY)).isFalse()
        assertThat(dao.rows).hasSize(1)
    }

    @Test
    fun reservationUsesRealClockTimestampInsteadOfSentinel() = runBlocking {
        val dao = FakeActionCommandDao()

        assertThat(store(dao).reserve(KEY)).isTrue()

        val row = dao.requireReservation(KEY)
        assertThat(row.timestamp).isEqualTo(1_234_567L)
        assertThat(row.timestamp).isNotEqualTo(Long.MAX_VALUE)
        assertThat(row.payloadJson).contains("\"createdAtMs\":1234567")
        assertThat(row.payloadJson).contains("\"updatedAtMs\":1234567")
    }

    @Test
    fun terminalTransitionUsesUpdatedClockTimestampAndPreservesCreationTime() = runBlocking {
        val dao = FakeActionCommandDao()
        var nowMs = 1_234_567L
        val store = ActionCommandUamExportReservationStore(
            actionCommandDao = dao,
            clock = { nowMs }
        )
        assertThat(store.reserve(KEY)).isTrue()

        nowMs = 1_345_678L
        store.markSent(KEY, "remote-123")

        val row = dao.requireReservation(KEY)
        assertThat(row.timestamp).isEqualTo(1_345_678L)
        assertThat(row.payloadJson).contains("\"createdAtMs\":1234567")
        assertThat(row.payloadJson).contains("\"updatedAtMs\":1345678")
    }

    @Test
    fun housekeepingCannotRemoveDurableReservation() = runBlocking {
        val dao = FakeActionCommandDao()
        assertThat(store(dao).reserve(KEY)).isTrue()

        assertThat(dao.deleteOlderThan(1_234_568L)).isEqualTo(0)
        assertThat(store(dao).reserve(KEY)).isFalse()
    }

    @Test
    fun sentStatusAndRemoteIdSurviveNewStoreInstance() = runBlocking {
        val dao = FakeActionCommandDao()
        val first = store(dao)
        assertThat(first.reserve(KEY)).isTrue()
        first.markSent(KEY, "remote-123")

        assertThat(store(dao).reserve(KEY)).isFalse()
        val row = dao.requireReservation(KEY)
        assertThat(row.type).isEqualTo(ActionCommandUamExportReservationStore.ACTION_TYPE)
        assertThat(row.status).isEqualTo(ActionCommandUamExportReservationStore.STATUS_SENT)
        assertThat(row.payloadJson).contains("\"remoteId\":\"remote-123\"")
        assertThat(row.payloadJson).contains("\"createdAtMs\":1234567")
    }

    @Test
    fun pendingUnknownStatusAndDetailSurviveNewStoreInstance() = runBlocking {
        val dao = FakeActionCommandDao()
        val first = store(dao)
        assertThat(first.reserve(KEY)).isTrue()
        first.markPendingUnknown(KEY, "timeout after POST")

        assertThat(store(dao).reserve(KEY)).isFalse()
        val row = dao.requireReservation(KEY)
        assertThat(row.status).isEqualTo(ActionCommandUamExportReservationStore.STATUS_PENDING_UNKNOWN)
        assertThat(row.payloadJson).contains("\"detail\":\"timeout after POST\"")
    }

    @Test
    fun transitionsRequireExistingReservation() = runBlocking {
        val store = store(FakeActionCommandDao())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.markSent(KEY, "remote-123") }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.markPendingUnknown(KEY, "unknown") }
        }
        Unit
    }

    @Test
    fun releaseDeletesOnlyReservedRowAndAllowsFreshReservation() = runBlocking {
        val dao = FakeActionCommandDao()
        val store = store(dao)
        assertThat(store.reserve(KEY)).isTrue()

        store.release(KEY)

        assertThat(dao.rows).isEmpty()
        assertThat(store.reserve(KEY)).isTrue()
    }

    @Test
    fun releaseCannotClearTerminalReservations() = runBlocking {
        val sentDao = FakeActionCommandDao()
        val sent = store(sentDao)
        sent.reserve("sent")
        sent.markSent("sent", "remote-1")

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { sent.release("sent") }
        }
        assertThat(store(sentDao).reserve("sent")).isFalse()

        val unknownDao = FakeActionCommandDao()
        val unknown = store(unknownDao)
        unknown.reserve("unknown")
        unknown.markPendingUnknown("unknown", "network outcome unknown")

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { unknown.release("unknown") }
        }
        assertThat(store(unknownDao).reserve("unknown")).isFalse()
    }

    private fun store(dao: ActionCommandDao) = ActionCommandUamExportReservationStore(
        actionCommandDao = dao,
        clock = { 1_234_567L }
    )

    private class FakeActionCommandDao : ActionCommandDao {
        val rows = linkedMapOf<String, ActionCommandEntity>()

        override suspend fun upsert(command: ActionCommandEntity) {
            rows[command.id] = command
        }

        override suspend fun byIdempotencyKey(idempotencyKey: String): ActionCommandEntity? =
            rows.values.firstOrNull { it.idempotencyKey == idempotencyKey }

        override suspend fun deleteByIdempotencyKeyTypeAndStatus(
            idempotencyKey: String,
            type: String,
            status: String
        ): Int {
            val row = rows.values.firstOrNull {
                it.idempotencyKey == idempotencyKey &&
                    it.type == type &&
                    it.status == status
            } ?: return 0
            rows.remove(row.id)
            return 1
        }

        override suspend fun automaticSentTargetEvidenceBefore(
            beforeTimestamp: Long
        ): AutomaticSentCommandEvidence = AutomaticSentCommandEvidence(0, null, null)

        fun requireReservation(key: String): ActionCommandEntity = rows.values.single {
            it.idempotencyKey == ActionCommandUamExportReservationStore.IDEMPOTENCY_PREFIX + key
        }

        override suspend fun countByStatusSince(status: String, since: Long): Int = 0

        override suspend fun countByStatusSinceExcludingPrefix(
            status: String,
            since: Long,
            excludedPrefix: String
        ): Int = 0

        override suspend fun countByStatusBetweenExcludingTwoPrefixes(
            status: String,
            since: Long,
            through: Long,
            excludedPrefix1: String,
            excludedPrefix2: String
        ): Int = 0

        override suspend fun latestTimestampByTypeAndStatusAtOrBeforeExcludingPrefix(
            type: String,
            status: String,
            through: Long,
            excludedPrefix: String
        ): Long? = null

        override suspend fun latestByTypeAndStatusAtOrBeforeExcludingPrefix(
            type: String,
            status: String,
            through: Long,
            excludedPrefix: String
        ): ActionCommandEntity? = null

        override suspend fun latestTimestampByTypeAndStatus(type: String, status: String): Long? = null

        override suspend fun byTypeAndIdempotencyPrefixSince(
            type: String,
            idempotencyPrefix: String,
            since: Long
        ): List<ActionCommandEntity> = emptyList()

        override suspend fun latest(limit: Int): List<ActionCommandEntity> = rows.values.take(limit)

        override suspend fun updateStatusByIds(
            ids: List<String>,
            currentStatus: String,
            newStatus: String
        ): Int = 0

        override fun observeLatest(limit: Int): Flow<List<ActionCommandEntity>> = emptyFlow()

        override suspend fun deleteOlderThan(olderThan: Long): Int {
            val oldIds = rows.values.filter {
                it.timestamp < olderThan &&
                    it.type != ActionCommandUamExportReservationStore.ACTION_TYPE
            }.map { it.id }
            oldIds.forEach(rows::remove)
            return oldIds.size
        }
    }

    private companion object {
        const val KEY = "uam:v2:id64=ZXBpc29kZQ:seq=1"
    }
}
