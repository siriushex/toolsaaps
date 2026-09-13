package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.ClinicalTargetManagerEvidenceProjection
import io.aaps.copilot.data.local.entity.TargetManagerDecisionEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ClinicalTargetManagerEvidenceSourceTest {

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
    fun daoProjectionSelectsOnlyMinimalEvidenceColumnsInDeterministicBoundedOrder() = runBlocking {
        val dao = db.targetManagerDao()
        listOf(
            decision("later", 3_000L),
            decision("same-b", 2_000L),
            decision("same-a", 2_000L),
            decision("early", 1_000L)
        ).forEach { dao.insertDecision(it) }

        val rows = dao.clinicalEvidenceBetween(
            fromTs = 1_000L,
            throughTs = 3_000L,
            limit = 3
        )

        assertThat(rows.map { it.id }).containsExactly("early", "same-a", "same-b").inOrder()
        assertThat(ClinicalTargetManagerEvidenceProjection::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") })
            .containsExactly("id", "timestamp", "outcome", "winnerJson", "reasonCodesJson")
        val source = File("src/main/kotlin/io/aaps/copilot/data/local/dao/TargetManagerDao.kt")
            .readText()
        val query = source.substringBefore("suspend fun clinicalEvidenceBetween")
            .substringAfterLast("@Query(")
        assertThat(query)
            .contains("SELECT id, timestamp, outcome, winnerJson, reasonCodesJson")
        assertThat(query).doesNotContain("SELECT *")
        assertThat(query).doesNotContain("commandJson")
        assertThat(query).doesNotContain("rejectedProposalReasonsJson")
    }

    @Test
    fun sourceExpandsCarryInAndEvaluationLeadAndMapsOnlyMinimalFields() = runTest {
        val preparedFrom = 40L * DAY_MS
        val through = preparedFrom + DAY_MS
        var capturedFrom = Long.MIN_VALUE
        var capturedThrough = Long.MIN_VALUE
        var capturedLimit = -1
        val projection = projection(1)

        val snapshot = ClinicalTargetManagerEvidenceSource.load(
            preparedFromTs = preparedFrom,
            throughTs = through
        ) { fromTs, throughTs, limit ->
            capturedFrom = fromTs
            capturedThrough = throughTs
            capturedLimit = limit
            listOf(projection)
        }

        assertThat(capturedFrom).isEqualTo(
            preparedFrom - ClinicalPlannedActivityPeriodPolicy.MAX_SUPPORTED_DURATION_MS -
                MAX_ACTIVITY_EVALUATION_LEAD_MS
        )
        assertThat(capturedThrough).isEqualTo(through)
        assertThat(capturedLimit).isEqualTo(ClinicalTargetManagerEvidenceSource.MAX_ROWS + 1)
        assertThat(snapshot.complete).isTrue()
        assertThat(snapshot.rows).containsExactly(
            ClinicalTargetManagerEvidence(
                id = projection.id,
                timestamp = projection.timestamp,
                outcome = projection.outcome,
                winnerJson = projection.winnerJson,
                reasonCodesJson = projection.reasonCodesJson
            )
        )
    }

    @Test
    fun sourceAcceptsExactCardinalityAndExplicitlyOmitsOverLimitEvidence() = runTest {
        val exact = List(ClinicalTargetManagerEvidenceSource.MAX_ROWS) { projection(it) }
        val over = List(ClinicalTargetManagerEvidenceSource.MAX_ROWS + 1) { projection(it) }

        val complete = ClinicalTargetManagerEvidenceSource.load(10L * DAY_MS, 11L * DAY_MS) {
            _, _, _ -> exact
        }
        val incomplete = ClinicalTargetManagerEvidenceSource.load(10L * DAY_MS, 11L * DAY_MS) {
            _, _, _ -> over
        }

        assertThat(complete.complete).isTrue()
        assertThat(complete.rows).hasSize(ClinicalTargetManagerEvidenceSource.MAX_ROWS)
        assertThat(incomplete.complete).isFalse()
        assertThat(incomplete.rows).isEmpty()
    }

    private fun decision(id: String, timestamp: Long): TargetManagerDecisionEntity =
        TargetManagerDecisionEntity(
            id = id,
            timestamp = timestamp,
            mode = "ACTIVE",
            semanticFingerprint = "fingerprint-$id",
            outcome = "BLOCK_SENSOR_TRUST",
            winnerJson =
                """{"activityProposal":{"occurrenceId":"event","occurrenceRevision":1}}""",
            commandJson = "heavy-command-$id",
            cadenceOutcome = "BLOCKED",
            cadenceReason = "test",
            lastSentTargetMmol = null,
            lastSentTimestamp = null,
            deliveryStatus = "not_requested",
            reasonCodesJson = "[\"reason-$id\"]",
            rejectedProposalReasonsJson = "heavy-rejected-$id"
        )

    private fun projection(index: Int) = ClinicalTargetManagerEvidenceProjection(
        id = "decision-${index.toString().padStart(5, '0')}",
        timestamp = index.toLong(),
        outcome = "BLOCK_SENSOR_TRUST",
        winnerJson = null,
        reasonCodesJson = "[]"
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        const val MAX_ACTIVITY_EVALUATION_LEAD_MS = 90L * 60L * 1_000L
    }
}
