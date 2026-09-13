package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.TargetBaseProvenance
import io.aaps.copilot.domain.target.TargetCommandCandidate
import io.aaps.copilot.domain.target.TargetCommandObservation
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetOwnershipPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ManagedTargetAuthorityProofTest {
    private lateinit var db: CopilotDatabase
    private val now = 1_800_000_000_000L
    private val schedule = BaseTargetSchedule(defaultTargetMmol = 5.5)
    private val observation = TargetOwnershipPolicy.capture(null, false, 0L)
    private val command = TargetCommandCandidate(
        targetMmol = 6.0, durationMinutes = 30, ownerRuleId = "test",
        intent = TargetIntent.NORMAL_CONTROL, reason = "eligible",
        idempotencyKey = "test-command", semanticFingerprint = "test-fingerprint",
        baseProvenance = TargetBaseProvenance(0L, null, null),
        generatedAt = now, targetObservation = observation, sensitivityCycleId = "cycle"
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun checkFinal(
        finish: Long = now,
        candidate: TargetCommandCandidate = command,
        target: TargetCommandObservation = observation,
        cycle: String? = "cycle",
        glucose: Long? = now - 60_000L,
        generation: Long? = now - 60_000L,
        monotonic: Long = 1_000L + finish - now
    ) = managedTargetFinalAuthorityFailureStatic(candidate, schedule, cycle, generation,
        glucose, target, ManagedTargetReadClock(now, 1_000L),
        ManagedTargetReadClock(finish, monotonic), 15 * 60_000L)

    @Test fun expiresCandidateAndForecastAtCompletionNotAtReadStart() {
        assertThat(checkFinal()).isNull()
        assertThat(checkFinal(finish = now + 120_001L)).isEqualTo("candidate_stale")
        assertThat(checkFinal(finish = now + 2_000L, generation = now - 899_000L))
            .isEqualTo("accepted_forecast_missing_or_stale")
        assertThat(checkFinal(monotonic = 999L)).isEqualTo("local_safety_chronology_unresolved")
    }

    @Test fun expiresObservedTargetAtCompletion() {
        val target = TargetOwnershipPolicy.capture(ActiveAapsTarget(
            targetMmol = 7.0, startedAt = now - 60_000L, expiresAt = now + 1_000L,
            source = "aaps", ownership = ActiveTargetOwnership.TARGET_MANAGER,
            idempotencyKey = "known", evidenceResolved = true), false, 0L)
        assertThat(checkFinal(finish = now + 2_000L,
            candidate = command.copy(targetObservation = target), target = target))
            .isEqualTo(TargetOwnershipPolicy.TARGET_OBSERVATION_EXPIRED)
    }

    @Test fun latestClinicalInputsAreReadAfterPreliminaryWork() = runBlocking {
        val preliminary = now - 60_000L
        db.glucoseDao().upsertAll(listOf(sample(preliminary)))
        db.glucoseDao().upsertAll(listOf(sample(now + 1L)))
        val failure = withManagedTargetAuthorityProof(db) {
            val latest = db.glucoseDao().latestValidDistinctAtOrBefore(now + 2L, 1).single().timestamp
            checkFinal(finish = now + 2L, glucose = latest)
        }
        assertThat(failure).isEqualTo("current_glucose_newer_than_candidate")
        assertThat(checkFinal(cycle = "new-cycle")).isEqualTo("accepted_forecast_cycle_changed")
    }

    @Test fun proofDoesNotMixRowsWithConcurrentClinicalWriter() = runBlocking {
        db.glucoseDao().upsertAll(listOf(sample(now - 60_000L)))
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val proof = async(Dispatchers.IO) {
            withManagedTargetAuthorityProof(db, timeoutMs = 5_000L) {
                val first = db.glucoseDao().latestValidDistinctAtOrBefore(now, 1).single().timestamp
                read.complete(Unit)
                release.await()
                val last = db.glucoseDao().latestValidDistinctAtOrBefore(now, 1).single().timestamp
                assertThat(last).isEqualTo(first)
                checkFinal(glucose = last)
            }
        }
        read.await()
        val writerStarted = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            writerStarted.complete(Unit)
            db.glucoseDao().upsertAll(listOf(sample(now - 1L)))
        }
        writerStarted.await()
        assertThat(withTimeoutOrNull(100L) { writer.await(); true }).isNull()
        release.complete(Unit)
        assertThat(proof.await()).isNull()
        writer.await()
        assertThat(db.glucoseDao().latestValidDistinctAtOrBefore(now, 1).single().timestamp)
            .isEqualTo(now - 1L)
    }

    @Test fun ownTimeoutRollsBackAndDoesNotLeaveBackgroundWork() = runBlocking {
        db.glucoseDao().latestValidDistinctAtOrBefore(now, 1)
        var entered = false
        val result = withManagedTargetAuthorityProof(db, timeoutMs = 200L) {
            db.glucoseDao().upsertAll(listOf(sample(now)))
            entered = true
            awaitCancellation()
        }
        assertThat(entered).isTrue()
        assertThat(result).isEqualTo("target_authority_read_timeout")
        assertThat(db.glucoseDao().latestValidDistinctAtOrBefore(now, 1)).isEmpty()
    }

    @Test fun cancellationAndErrorsAreNotConvertedToApproval() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val job = async {
            withManagedTargetAuthorityProof(db) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        job.cancel()
        try { job.await(); throw AssertionError("Cancellation was swallowed") }
        catch (_: CancellationException) { }
        val fatal = AssertionError("fatal")
        var caught: Throwable? = null
        try { withManagedTargetAuthorityProof(db) { throw fatal } }
        catch (failure: Throwable) { caught = failure }
        assertThat(caught).isInstanceOf(AssertionError::class.java)
        assertThat(generateSequence(caught) { it.cause }.any { it === fatal }).isTrue()
    }

    private fun sample(ts: Long) = GlucoseSampleEntity(timestamp = ts, mmol = 7.0,
        source = "test", quality = "OK")
}
