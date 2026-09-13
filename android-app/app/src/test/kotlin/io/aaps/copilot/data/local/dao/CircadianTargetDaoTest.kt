package io.aaps.copilot.data.local.dao

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.CircadianTargetAdjustmentEntity
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class CircadianTargetDaoTest {

    @Test
    fun publishRunRejectsMoreThan72AdjustmentsBeforeAnyInsert() {
        val dao = RecordingCircadianTargetDao()
        val run = runEntity()
        val adjustments = (0..72).map { index ->
            adjustmentEntity(id = "adjustment-$index", run = run)
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.publishRun(run, adjustments) }
        }

        assertThat(dao.insertedRuns).isEmpty()
        assertThat(dao.insertedAdjustmentBatches).isEmpty()
    }

    @Test
    fun publishRunRejectsAdjustmentFromAnotherRunBeforeAnyInsert() {
        val dao = RecordingCircadianTargetDao()
        val run = runEntity()
        val mismatched = adjustmentEntity(id = "other-run", run = run).copy(runId = "run-other")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.publishRun(run, listOf(mismatched)) }
        }

        assertThat(dao.insertedRuns).isEmpty()
        assertThat(dao.insertedAdjustmentBatches).isEmpty()
    }

    @Test
    fun publishRunRejectsAdjustmentFromAnotherRevisionBeforeAnyInsert() {
        val dao = RecordingCircadianTargetDao()
        val run = runEntity()
        val mismatched = adjustmentEntity(id = "other-revision", run = run)
            .copy(scheduleRevision = run.scheduleRevision + 1L)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.publishRun(run, listOf(mismatched)) }
        }

        assertThat(dao.insertedRuns).isEmpty()
        assertThat(dao.insertedAdjustmentBatches).isEmpty()
    }

    @Test
    fun publishRunInsertsRunThenCompleteAdjustmentBatch() = runBlocking {
        val dao = RecordingCircadianTargetDao()
        val run = runEntity()
        val adjustments = listOf(
            adjustmentEntity(id = "adjustment-1", run = run),
            adjustmentEntity(id = "adjustment-2", run = run)
        )

        dao.publishRun(run, adjustments)

        assertThat(dao.operations).containsExactly("delete_failed", "run", "adjustments").inOrder()
        assertThat(dao.insertedRuns).containsExactly(run)
        assertThat(dao.insertedAdjustmentBatches.single()).containsExactlyElementsIn(adjustments).inOrder()
    }

    private fun runEntity(): CircadianTargetRunEntity = CircadianTargetRunEntity(
        runId = "run-1",
        scheduleRevision = 7L,
        localRunDate = "2026-07-20",
        startedAt = 1_000L,
        completedAt = 2_000L,
        lookbackStart = 100L,
        lookbackEnd = 1_900L,
        status = "ACTIVE",
        validDays = 7,
        trustedShare = 0.95,
        lowRiskPassed = true,
        reasonCodesJson = "[]"
    )

    private fun adjustmentEntity(
        id: String,
        run: CircadianTargetRunEntity
    ): CircadianTargetAdjustmentEntity = CircadianTargetAdjustmentEntity(
        id = id,
        runId = run.runId,
        scheduleRevision = run.scheduleRevision,
        dayType = "ALL",
        hour = 9,
        manualTargetMmol = 6.0,
        desiredDeltaMmol = -0.1,
        appliedDeltaMmol = -0.1,
        medianMmol = 7.0,
        p25 = 6.4,
        p75 = 7.6,
        trustedLowCount = 0,
        sampleCount = 40,
        activeDays = 7,
        qualityScore = 0.9,
        sensorTrustedShare = 0.95,
        generatedAt = 2_000L,
        validUntil = 3_000L,
        status = "ACTIVE",
        reasonCodesJson = "[]"
    )

    private class RecordingCircadianTargetDao : CircadianTargetDao {
        val operations = mutableListOf<String>()
        val insertedRuns = mutableListOf<CircadianTargetRunEntity>()
        val insertedAdjustmentBatches = mutableListOf<List<CircadianTargetAdjustmentEntity>>()
        override suspend fun insertRun(run: CircadianTargetRunEntity) {
            operations += "run"
            insertedRuns += run
        }

        override suspend fun insertAdjustments(adjustments: List<CircadianTargetAdjustmentEntity>) {
            operations += "adjustments"
            insertedAdjustmentBatches += adjustments
        }

        override suspend fun deleteFailedRunForDate(
            scheduleRevision: Long,
            localRunDate: String
        ): Int {
            operations += "delete_failed"
            return 0
        }

        override suspend fun insertFailedRun(run: CircadianTargetRunEntity) = Unit

        override suspend fun latestCompletedRun(
            scheduleRevision: Long
        ): CircadianTargetRunEntity? = null

        override suspend fun runForLocalDate(
            scheduleRevision: Long,
            localRunDate: String
        ): CircadianTargetRunEntity? = null

        override suspend fun adjustmentsByRunId(
            runId: String
        ): List<CircadianTargetAdjustmentEntity> = emptyList()

        override suspend fun currentAdjustment(
            scheduleRevision: Long,
            dayType: String,
            hour: Int
        ): CircadianTargetAdjustmentEntity? = null

        override suspend fun previousSuccessfulAdjustment(
            scheduleRevision: Long,
            dayType: String,
            hour: Int,
            beforeCompletedAtExclusive: Long
        ): CircadianTargetAdjustmentEntity? = null

        override suspend fun latestCompletedLocalRunDate(scheduleRevision: Long): String? = null

        override suspend fun deleteRunsCompletedBefore(cutoff: Long): Int = 0
    }
}
