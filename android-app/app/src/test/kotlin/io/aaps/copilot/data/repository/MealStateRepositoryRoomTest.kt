package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.meal.*
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
class MealStateRepositoryRoomTest {
    @Test fun duplicateSampleCannotAdvancePersistedPosterior() = runBlocking {
        withDb { _, repo ->
            repo.recordInput(input, belief())
            val observed = update(belief())
            assertTrue(repo.saveBelief(input.id, 0, observed))
            val repeated = MealBelief(input, observed.scenarios, 2, observed.lastSampleAtMs,
                observed.lastSampleId, observed.runtimeIdentity)
            assertFalse(repo.saveBelief(input.id, 1, repeated))
            assertEquals(1L, repo.get(input.id)!!.belief!!.revision)
        }
    }

    @Test fun pendingExactGramsDoNotRequireInventedPriorOrAapsConfirmation() = runBlocking {
        withDb { _, repo ->
            val exact = input.copy(carbs = MealCarbRange(20.0, 20.0))
            repo.recordInput(exact)
            val pending = repo.get(exact.id)!!
            assertEquals(exact, pending.identity.input)
            assertNull(pending.identity.aapsRecord)
            assertNull(pending.belief)
        }
    }
    @Test fun failedChildWriteRestoresPreviousPosteriorAndRevision() = runBlocking {
        withDb { db, repo ->
            repo.recordInput(input, belief())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_profile BEFORE INSERT ON " +
                "meal_state_absorption BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END")
            try {
                repo.saveBelief(input.id, 0, update(belief()))
                fail("Partial belief must not be committed")
            } catch (_: android.database.sqlite.SQLiteException) { }
            val restored = repo.get(input.id)!!
            assertEquals(0L, restored.storageRevision)
            assertEquals(0L, restored.belief!!.revision)
            assertEquals(belief().stageProbabilities, restored.belief.stageProbabilities)
        }
    }

    @Test fun incompletePersistedDistributionRejectsInsteadOfInventingPrior() = runBlocking {
        withDb { db, repo ->
            repo.recordInput(input, belief())
            db.openHelper.writableDatabase.execSQL("DELETE FROM meal_state_scenarios WHERE kind = 'NOT_HAPPENING'")
            try { repo.get(input.id); fail("Corrupt posterior must reject") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun invalidAndWrongCanonicalPosteriorCannotReplaceStoredState() = runBlocking {
        withDb { _, repo ->
            val original = repo.recordInput(input, belief())
            val malformed = MealBelief(input, belief().scenarios.dropLast(1), 1)
            for (bad in listOf(malformed, update(belief(canonicalId = "unconfirmed")) )) {
                try { repo.saveBelief(input.id, 0, bad); fail("Invalid posterior") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(original.storageRevision, repo.get(input.id)!!.storageRevision)
        }
    }

    @Test fun unknownModelVersionIsNotSilentlyTreatedAsCurrent() = runBlocking {
        withDb { db, repo ->
            repo.recordInput(input, belief())
            db.openHelper.writableDatabase.execSQL("UPDATE meal_states SET modelVersion = 'unsupported-model'")
            try { repo.get(input.id); fail("Unsupported model must reject") }
            catch (_: IllegalArgumentException) { }
        }
    }
    private val input = MealInput("input-a", 10_000_000, MealCarbRange(15.0, 40.0))
    private fun belief(source: MealInput = input, canonicalId: String? = null): MealBelief =
        MealStateEstimator().initialize(source, MealHypothesisKind.entries.map { kind ->
            MealScenario(kind.name, kind,
                if (kind == MealHypothesisKind.NOT_HAPPENING) null else MealStartInterval(9_000_000, 10_000_000),
                source.carbs, listOf(MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 1.0)),
                1.0, canonicalId)
        })
    private fun update(b: MealBelief): MealBelief = MealBelief(b.input, b.scenarios, b.revision + 1,
        10_300_000, "sample-1", "runtime-a")

    private suspend fun withDb(block: suspend (CopilotDatabase, MealStateRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).build()
        try { block(db, MealStateRepository(db)) } finally { db.close() }
    }

    @Test fun inputReplayDoesNotDuplicateOrOverwritePosterior() = runBlocking {
        withDb { _, repo ->
            val initial = repo.recordInput(input, belief())
            assertTrue(repo.saveBelief(input.id, initial.storageRevision, update(belief())))
            val replay = repo.recordInput(input, belief())
            assertEquals(1L, replay.belief!!.revision)
            assertEquals(input, replay.identity.input)
            assertNull(replay.identity.aapsRecord)
            try {
                repo.recordInput(input.copy(carbs = MealCarbRange(20.0, 20.0)))
                fail("Input identity must not be reused with different grams")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun correctionInvalidatesStaleCalculationWithoutRewritingInput() = runBlocking {
        withDb { _, repo ->
            repo.recordInput(input, belief())
            val record = MealRecordRevision("canonical-a", 1, 9_500_000, 20.0, false)
            val confirmed = repo.reconcile(input.id, record)
            assertNull(confirmed.belief)
            assertTrue(repo.saveBelief(input.id, confirmed.storageRevision, belief(canonicalId = record.canonicalId)))
            val loaded = repo.get(input.id)!!
            val corrected = repo.reconcile(input.id, record.copy(revision = 2, grams = 30.0))
            assertNull(corrected.belief)
            assertEquals(input, corrected.identity.input)
            assertEquals(30.0, corrected.identity.aapsRecord!!.grams, 0.0)
            assertFalse(repo.saveBelief(input.id, loaded.storageRevision, update(loaded.belief!!)))
            assertEquals(corrected.storageRevision, repo.reconcile(input.id, record).storageRevision)
            assertEquals(corrected.storageRevision, repo.reconcile(input.id, record.copy(revision = 2, grams = 30.0)).storageRevision)
        }
    }

    @Test fun tombstoneCannotBeRevivedByOldCallbackOrPrediction() = runBlocking {
        withDb { _, repo ->
            repo.recordInput(input)
            val record = MealRecordRevision("canonical-a", 2, input.recordedAtMs, 20.0, true)
            val deleted = repo.reconcile(input.id, record)
            assertFalse(repo.saveBelief(input.id, deleted.storageRevision, belief(canonicalId = record.canonicalId)))
            repo.reconcile(input.id, record.copy(revision = 1, deleted = false))
            assertTrue(repo.get(input.id)!!.identity.aapsRecord!!.deleted)
            assertNull(repo.get(input.id)!!.belief)
        }
    }

    @Test fun nearbyMealsRemainSeparateAndCanonicalIdentityCannotHaveTwoOwners() = runBlocking {
        withDb { _, repo ->
            repo.recordInput(input, belief())
            val other = input.copy(id = "input-b")
            repo.recordInput(other, belief(other))
            val record = MealRecordRevision("canonical-a", 1, input.recordedAtMs, 20.0, false)
            repo.reconcile(input.id, record)
            try { repo.reconcile(other.id, record); fail("Explicit canonical conflict must reject") }
            catch (_: IllegalArgumentException) { }
            assertNull(repo.get(other.id)!!.identity.aapsRecord)
            assertNotNull(repo.get(other.id)!!.belief)
            repo.reconcile(other.id, record.copy(canonicalId = "canonical-b"))
            assertEquals("canonical-b", repo.get(other.id)!!.identity.aapsRecord!!.canonicalId)
        }
    }

    @Test fun racingPosteriorUpdatesHaveOneWinner() = runBlocking {
        withDb { _, repo ->
            val before = repo.recordInput(input, belief())
            val results = (1..2).map { async {
                repo.saveBelief(input.id, before.storageRevision, update(belief()))
            } }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(1L, repo.get(input.id)!!.storageRevision)
        }
    }

    @Test fun conflictingSameRevisionDoesNotMutateState() = runBlocking {
        withDb { _, repo ->
            repo.recordInput(input)
            val record = MealRecordRevision("canonical-a", 1, input.recordedAtMs, 20.0, false)
            val original = repo.reconcile(input.id, record)
            try { repo.reconcile(input.id, record.copy(grams = 21.0)); fail("Conflicting revision") }
            catch (_: IllegalArgumentException) { }
            assertEquals(original.storageRevision, repo.get(input.id)!!.storageRevision)
            assertEquals(record, repo.get(input.id)!!.identity.aapsRecord)
        }
    }

    @Test fun posteriorSurvivesDatabaseReopeningExactly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-state-restart-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name).build()
        var db = open()
        try {
            val before = update(belief())
            val repo = MealStateRepository(db)
            repo.recordInput(input, belief())
            assertTrue(repo.saveBelief(input.id, 0, before))
            db.close(); db = open()
            val after = MealStateRepository(db).get(input.id)!!.belief!!
            assertEquals(before.input, after.input)
            assertEquals(before.stageProbabilities, after.stageProbabilities)
            assertEquals(before.revision, after.revision)
            assertEquals(before.runtimeIdentity, after.runtimeIdentity)
            assertEquals(before.lastSampleId, after.lastSampleId)
            assertEquals(before.lastSampleAtMs, after.lastSampleAtMs)
            assertEquals(before.scenarios.map { it.absorption }, after.scenarios.map { it.absorption })
            assertEquals(before.scenarios.map { it.inferredStart }, after.scenarios.map { it.inferredStart })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
