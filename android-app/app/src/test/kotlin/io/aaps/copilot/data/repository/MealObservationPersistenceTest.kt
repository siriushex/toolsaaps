package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.meal.*
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MealObservationPersistenceTest {
    private val input = MealInput("manual:meal:sample", 10_000_000, MealCarbRange(20.0, 20.0))
    private val record = MealRecordRevision("42", 1, input.recordedAtMs, 20.0, false)
    private val at = input.recordedAtMs + 300_000
    private fun belief() = MealStateEstimator().initialize(input, MealHypothesisKind.entries.map { kind ->
        MealScenario(kind.name, kind,
            if (kind == MealHypothesisKind.NOT_HAPPENING) null else MealStartInterval(input.recordedAtMs, input.recordedAtMs),
            input.carbs, listOf(MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 1.0)), 1.0, record.canonicalId)
    })
    private fun observation() = MealObservation("sample-1", at, 6.5, input.recordedAtMs,
        "runtime-stable", 0, MealHypothesisKind.entries.associate { it.name to MealExpectedObservation(
            if (it == MealHypothesisKind.JUST_STARTED) 6.5 else 5.0, 0.5) }, true)

    private suspend fun persistedRows(db: CopilotDatabase, id: String) = Triple(
        db.mealStateDao().get(id), db.mealStateDao().scenarios(id), db.mealStateDao().absorption(id))

    private suspend fun withDb(initialBelief: MealBelief = belief(), block: suspend (CopilotDatabase, MealStateRepository, Long) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java).build()
        try {
            val repo = MealStateRepository(db)
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Input(input))
            inbox.persist(MealIngestionEvent.Confirmed(listOf(MealAapsConfirmation(input.id, record))))
            inbox.drainBatch()
            assertTrue(repo.saveBelief(input.id, repo.get(input.id)!!.storageRevision, initialBelief))
            block(db, repo, repo.get(input.id)!!.storageRevision)
        } finally { db.close() }
    }

    @Test fun causalObservationUpdatesOnceAndKeepsOriginalInput() = runBlocking {
        withDb { _, repo, revision ->
            assertEquals(MealObservationWriteStatus.UPDATED, repo.observe(input.id, revision, observation(), at).status)
            val after = repo.get(input.id)!!
            assertEquals(input, after.identity.input)
            assertTrue(after.belief!!.stageProbabilities.getValue(MealHypothesisKind.JUST_STARTED) > 1.0 / 6)
            val repeat = repo.observe(input.id, after.storageRevision, observation().copy(basedOnRevision = 1), at)
            assertEquals(MealUpdateReason.DUPLICATE_OR_OLD, repeat.estimatorReason)
            assertEquals(after.storageRevision, repo.get(input.id)!!.storageRevision)
        }
    }

    @Test fun stagedCorrectionBlocksObservationAndRawBeliefSaveBeforeDrain() = runBlocking {
        withDb { db, repo, revision ->
            MealStateInbox(db).persist(MealIngestionEvent.Confirmed(listOf(
                MealAapsConfirmation(input.id, record.copy(revision = 2, grams = 30.0)))))
            assertEquals(MealObservationWriteStatus.PENDING_RECONCILIATION,
                repo.observe(input.id, revision, observation(), at).status)
            val updated = MealStateEstimator().observe(belief(), observation()).belief
            assertFalse(repo.saveBelief(input.id, revision, updated))
            assertEquals(revision, repo.get(input.id)!!.storageRevision)
        }
    }

    @Test fun quarantinedIdentityCannotAdvanceEitherWritePath() = runBlocking {
        withDb { db, repo, revision ->
            MealStateInbox(db).persist(MealIngestionEvent.Confirmed(listOf(
                MealAapsConfirmation(input.id, record.copy(grams = 30.0)))))
            assertEquals(MealObservationWriteStatus.QUARANTINED,
                repo.observe(input.id, revision, observation(), at).status)
            assertFalse(repo.saveBelief(input.id, revision, MealStateEstimator().observe(belief(), observation()).belief))
        }
    }

    @Test fun futureStaleUntrustedAndNonCausalEvidenceNeverMutatesState() = runBlocking {
        withDb { _, repo, revision ->
            assertEquals(MealObservationWriteStatus.INVALID_SAMPLE_TIME,
                repo.observe(input.id, revision, observation(), at - 1).status)
            assertEquals(MealObservationWriteStatus.INVALID_SAMPLE_TIME,
                repo.observe(input.id, revision, observation(), at + 300_001).status)
            assertEquals(MealUpdateReason.INVALID_EVIDENCE,
                repo.observe(input.id, revision, observation().copy(qualityTrusted = false), at).estimatorReason)
            assertEquals(MealUpdateReason.NON_CAUSAL,
                repo.observe(input.id, revision, observation().copy(predictedAtMs = at), at).estimatorReason)
            assertEquals(revision, repo.get(input.id)!!.storageRevision)
        }
    }

    @Test fun concurrentObservationsHaveOneWinner() = runBlocking {
        withDb { _, repo, revision ->
            val results = (1..2).map { async { repo.observe(input.id, revision, observation(), at) } }.awaitAll()
            assertEquals(1, results.count { it.status == MealObservationWriteStatus.UPDATED })
            assertEquals(1, results.count { it.status == MealObservationWriteStatus.STALE_STORAGE })
        }
    }

    @Test fun correctionDoesNotInventReplacementPrior() = runBlocking {
        withDb { _, repo, _ ->
            val state = repo.reconcile(input.id, record.copy(revision = 2, grams = 30.0))
            assertEquals(MealObservationWriteStatus.PENDING_RECONCILIATION,
                repo.observe(input.id, state.storageRevision, observation(), at).status)
            assertNull(repo.get(input.id)!!.belief)
        }
    }

    @Test fun appliedCorrectionRequiresNewExplicitPriorAndOldWriterCannotRestoreIt() = runBlocking {
        withDb { db, repo, revision ->
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Confirmed(listOf(
                MealAapsConfirmation(input.id, record.copy(revision = 2, grams = 30.0)))))
            inbox.drainBatch()
            val corrected = repo.get(input.id)!!
            val stored = persistedRows(db, input.id)
            assertEquals(30.0, corrected.identity.aapsRecord!!.grams, 0.0)
            assertEquals(input, corrected.identity.input)
            assertNull(corrected.belief)
            assertEquals(MealObservationWriteStatus.MISSING_BELIEF,
                repo.observe(input.id, corrected.storageRevision, observation(), at).status)
            assertFalse(repo.saveBelief(input.id, revision, belief()))
            assertEquals(stored, persistedRows(db, input.id))
        }
    }

    @Test fun missingUnconfirmedAndDeletedInputsCannotConsumeSamples() = runBlocking {
        withDb { db, repo, _ ->
            val pending = input.copy(id = "manual:meal:pending")
            val pendingState = repo.recordInput(pending)
            val pendingRows = persistedRows(db, pending.id)
            assertEquals(MealObservationWriteStatus.UNKNOWN_INPUT,
                repo.observe("manual:meal:unknown", 0, observation(), at).status)
            assertEquals(MealObservationWriteStatus.UNCONFIRMED,
                repo.observe(pending.id, pendingState.storageRevision, observation(), at).status)
            val inbox = MealStateInbox(db)
            inbox.persist(MealIngestionEvent.Confirmed(listOf(
                MealAapsConfirmation(input.id, record.copy(revision = 2, deleted = true)))))
            inbox.drainBatch()
            val deleted = repo.get(input.id)!!
            val deletedRows = persistedRows(db, input.id)
            assertEquals(MealObservationWriteStatus.DELETED,
                repo.observe(input.id, deleted.storageRevision, observation(), at).status)
            assertEquals(deletedRows, persistedRows(db, input.id))
            assertEquals(pendingRows, persistedRows(db, pending.id))
        }
    }

    @Test fun changedRuntimeOrStalePosteriorRevisionCannotAdvanceStoredProbabilities() = runBlocking {
        withDb { db, repo, revision ->
            assertEquals(MealObservationWriteStatus.UPDATED,
                repo.observe(input.id, revision, observation(), at).status)
            val accepted = repo.get(input.id)!!
            val stored = persistedRows(db, input.id)
            val nextAt = at + 300_000
            val next = observation().copy(sampleId = "sample-2", sampleAtMs = nextAt,
                predictedAtMs = at, basedOnRevision = 1)
            val changed = repo.observe(input.id, accepted.storageRevision,
                next.copy(runtimeIdentity = "runtime-new"), nextAt)
            assertEquals(MealObservationWriteStatus.ESTIMATOR_REJECTED, changed.status)
            assertEquals(MealUpdateReason.MODEL_CHANGED, changed.estimatorReason)
            val stale = repo.observe(input.id, accepted.storageRevision,
                next.copy(basedOnRevision = 0), nextAt)
            assertEquals(MealUpdateReason.STALE_REVISION, stale.estimatorReason)
            assertEquals(stored, persistedRows(db, input.id))
        }
    }

    @Test fun childWriteFailureRollsBackPosteriorAndAllowsSameSampleRetry() = runBlocking {
        withDb { db, repo, revision ->
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_belief BEFORE INSERT ON meal_state_absorption " +
                "BEGIN SELECT RAISE(ABORT, 'sample write failure'); END")
            try { repo.observe(input.id, revision, observation(), at); fail("Must propagate failed transaction") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(revision, repo.get(input.id)!!.storageRevision)
            assertNull(repo.get(input.id)!!.belief!!.lastSampleAtMs)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_belief")
            assertEquals(MealObservationWriteStatus.UPDATED, repo.observe(input.id, revision, observation(), at).status)
        }
    }

    @Test fun preparedEngineForecastFeedsRoomWithoutReusingNewSampleInPrediction() = runBlocking {
        val prior = MealStateEstimator().initialize(input, belief().scenarios.map { case ->
            val absent = case.kind in setOf(MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL)
            val start = if (case.kind == MealHypothesisKind.UPCOMING) input.recordedAtMs + 600_000 else input.recordedAtMs
            MealScenario(case.id, case.kind, if (absent) null else MealStartInterval(start, start),
                if (absent) MealCarbRange(0.0, 0.0) else input.carbs, case.absorption, case.probability, record.canonicalId)
        })
        withDb(prior) { _, repo, revision ->
            val history = (0..20).map { GlucosePoint(input.recordedAtMs - (20 - it) * 300_000L, 6.0, "sensor") }
            val therapy = listOf(TherapyEvent(input.recordedAtMs, "carbs", mapOf("carbs" to "20"),
                componentTrust = TherapyEventComponentTrust(42, "r1")))
            val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
            val context = MealSimulationContext.capture(engine, history, therapy,
                engine.predict(history, therapy), "cycle-1", 7, input.recordedAtMs)
            val errorModel = MealObservationErrorModel("test-errors-v1", "runtime-stable",
                input.recordedAtMs - 1, prior.scenarios.associate { it.id to 0.5 })
            val prepared = MealObservationForecast.prepare(context, prior, revision, "runtime-stable", errorModel) {
                input.recordedAtMs + 1
            }
            val sample = GlucosePoint(at, prepared.expected.getValue("JUST_STARTED").meanMmol, "sensor")
            val observation = prepared.observation("sample-1", sample, "runtime-stable", prior.revision, true, at)!!
            assertEquals(MealObservationWriteStatus.UPDATED,
                repo.observe(prepared.inputId, prepared.storageRevision, observation, at).status)
            val stored = repo.get(input.id)!!
            assertEquals(input, stored.identity.input)
            assertEquals(at, stored.belief!!.lastSampleAtMs)
            assertEquals(history, context.glucose)
            assertEquals(input.recordedAtMs, context.glucose.last().ts)
            assertTrue(stored.belief!!.stageProbabilities.getValue(MealHypothesisKind.JUST_STARTED) >
                stored.belief!!.stageProbabilities.getValue(MealHypothesisKind.UPCOMING))
        }
    }
}
