package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AuditLoggerTest {

    @Test
    fun throttledInfoSuppressesRepeatedWritesInsideInterval() {
        runBlocking {
            val dao = FakeAuditLogDao()
            var now = 1_000L
            val logger = AuditLogger(
                auditLogDao = dao,
                gson = Gson(),
                clock = { now }
            )

            logger.infoThrottled(
                throttleKey = "cycle",
                intervalMs = 5_000L,
                message = "automation_cycle_checkpoint",
                metadata = mapOf("stage" to "post_recent_data")
            )
            logger.infoThrottled(
                throttleKey = "cycle",
                intervalMs = 5_000L,
                message = "automation_cycle_checkpoint",
                metadata = mapOf("stage" to "post_recent_data")
            )

            now += 5_001L
            logger.infoThrottled(
                throttleKey = "cycle",
                intervalMs = 5_000L,
                message = "automation_cycle_checkpoint",
                metadata = mapOf("stage" to "post_recent_data")
            )

            assertThat(dao.rows).hasSize(2)
            assertThat(dao.rows.map { it.timestamp }).containsExactly(1_000L, 6_001L).inOrder()
        }
    }

    @Test
    fun warnThrottledUsesIndependentKeys() {
        runBlocking {
            val dao = FakeAuditLogDao()
            var now = 10_000L
            val logger = AuditLogger(
                auditLogDao = dao,
                gson = Gson(),
                clock = { now }
            )

            logger.warnThrottled(
                throttleKey = "broadcast:A",
                intervalMs = 60_000L,
                message = "broadcast_reactive_automation_skipped"
            )
            logger.warnThrottled(
                throttleKey = "broadcast:B",
                intervalMs = 60_000L,
                message = "broadcast_reactive_automation_skipped"
            )

            assertThat(dao.rows).hasSize(2)
            assertThat(dao.rows.map { it.level }.toSet()).containsExactly("WARN")
        }
    }

    private class FakeAuditLogDao : AuditLogDao {
        val rows = mutableListOf<AuditLogEntity>()

        override suspend fun insert(entity: AuditLogEntity) {
            rows += entity
        }

        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()

        override suspend fun recentByMessage(
            message: String,
            sinceTs: Long,
            limit: Int
        ): List<AuditLogEntity> = rows
            .filter { it.message == message && it.timestamp >= sinceTs }
            .sortedByDescending { it.timestamp }
            .take(limit)

        override suspend fun deleteOlderThan(olderThan: Long): Int {
            val before = rows.size
            rows.removeAll { it.timestamp < olderThan }
            return before - rows.size
        }
    }
}
