package io.aaps.copilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.ComboJournalDatabase
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AapsV3ComboCatalogRetentionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: ComboJournalDatabase
    private lateinit var store: AapsV3ComboJournalStore
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val other = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeef"
    private val snapshot = "00000000-0000-4000-8000-000000000001"
    private val session = 1_800_000_000_000L
    private var now = session + 1000

    @Before fun setup() {
        val directory = temporary.newFolder("retained")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        db = ComboJournalDatabase.create(context)
        store = AapsV3ComboJournalStore(db)
    }
    @After fun teardown() { db.close() }

    private fun catalog(count: Int = 2) = AapsV3ComboInventory.create(snapshot, "a".repeat(64),
        (1..count).map { AapsV3ComboInventoryEntry("10000000-0000-4000-8000-${it.toString().padStart(12, '0')}",
            1000, "b".repeat(64), true, false, false, false) })
    private fun binding(catalog: AapsV3ComboInventory, after: String? = null, counter: Long = 3, limit: Int = 1) =
        AapsV3ComboInventoryBinding(client, master, java.util.UUID.randomUUID().toString(), counter, now, now + 30_000,
            AapsV3ComboReadSpec.Inventory(if (after == null) null else catalog.snapshotId, after, limit))
    private fun packet(catalog: AapsV3ComboInventory, binding: AapsV3ComboInventoryBinding = binding(catalog),
                       roster: Long = 1, startedAt: Long = session, admittedAt: Long = now) =
        ComboResponseHandoff(roster, ComboResponseContent.Inventory(binding, catalog.page(binding.query)), startedAt, admittedAt)
    private suspend fun refused(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected redacted catalog failure", failure is AapsV3ComboInventoryFailure)
        assertNull(failure!!.cause)
    }
    private suspend fun blockedStore(block: suspend (CompletableDeferred<Unit>) -> Unit) = coroutineScope {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { db.withTransaction { entered.complete(Unit); release.await() } }
        entered.await()
        try { block(release) } finally { release.complete(Unit); lock.join() }
    }

    @Test fun partialCatalogDoesNotReadPrivateStorageAndFullCatalogIsOneShot() = runBlocking {
        val catalog = catalog()
        AapsV3ComboCatalogRetention(store).use { receiver ->
            blockedStore {
                assertNull(withTimeout(10_000) { receiver.accept(packet(catalog)) { now } })
            }
            now++
            val last = packet(catalog, binding(catalog, catalog.reads.first().readId, 4))
            val audit = receiver.accept(last) { now }!!
            assertEquals(catalog.sha256, audit.inventorySha256)
            assertEquals(catalog.reads.map { it.readId }, audit.missingReadIds)
            assertEquals(0, audit.matchedReadCount)
            assertFalse(audit.declaredInventoryRetained)
            assertFalse(audit.pumpBindingVerified); assertFalse(audit.deliveryCompletenessVerified)
            assertTrue(runCatching { (last.content as ComboResponseContent.Inventory).page() }.isFailure)
            assertTrue(runCatching { (audit.missingReadIds as MutableList).clear() }.isFailure)
            refused { receiver.accept(packet(catalog)) { now } }
            assertEquals(0L, db.journalDao().totals().reads)
        }
    }

    @Test fun emptyCatalogAndSourceDamageNeverBecomeClinicalCoverage() = runBlocking {
        val catalog = AapsV3ComboInventory.create(snapshot, "a".repeat(64), emptyList(), 2, true)
        AapsV3ComboCatalogRetention(store).use { receiver ->
            val audit = receiver.accept(packet(catalog)) { now }!!
            assertTrue(audit.declaredInventoryRetained)
            assertEquals(0, audit.expectedReadCount)
            assertTrue(AapsV3ComboHistoryHazard.UNSCOPED_CORRUPTION in audit.hazards)
            assertTrue(AapsV3ComboHistoryHazard.STORAGE_FAILED in audit.hazards)
            assertFalse(audit.deliveryCompletenessVerified); assertFalse(audit.pumpBindingVerified)
            assertFalse(receiver.toString().contains(master))
        }
    }

    @Test fun changedRosterSessionClientOrMasterTerminatesTheCatalog() = runBlocking {
        val catalog = catalog()
        for (change in 0..3) AapsV3ComboCatalogRetention(store).use { receiver ->
            assertNull(receiver.accept(packet(catalog)) { now })
            val second = binding(catalog, catalog.reads.first().readId, 4)
            val next = packet(catalog, when (change) {
                2 -> second.copy(clientId = other)
                3 -> second.copy(masterInstallId = other)
                else -> second
            }, roster = if (change == 0) 2 else 1, startedAt = if (change == 1) session + 1 else session)
            refused { receiver.accept(next) { now } }
            assertTrue(runCatching { (next.content as ComboResponseContent.Inventory).page() }.isFailure)
            refused { receiver.accept(packet(catalog)) { now } }
        }
    }

    @Test fun continuationWithoutFirstPageAndOmittedMiddlePageAreRejected() = runBlocking {
        val catalog = catalog(3)
        AapsV3ComboCatalogRetention(store).use { receiver ->
            refused { receiver.accept(packet(catalog, binding(catalog, catalog.reads.first().readId, 4))) { now } }
        }
        AapsV3ComboCatalogRetention(store).use { receiver ->
            assertNull(receiver.accept(packet(catalog)) { now })
            refused { receiver.accept(packet(catalog, binding(catalog, catalog.reads[1].readId, 5))) { now } }
        }
    }

    @Test fun nonIncreasingCounterAndBackwardsIssueTimeAreRejected() = runBlocking {
        val catalog = catalog()
        for (backwardsTime in listOf(false, true)) AapsV3ComboCatalogRetention(store).use { receiver ->
            assertNull(receiver.accept(packet(catalog)) { now })
            val next = binding(catalog, catalog.reads.first().readId, if (backwardsTime) 4 else 3)
            val actual = if (backwardsTime) next.copy(issuedAt = now - 1, validUntil = now + 29_999) else next
            refused { receiver.accept(packet(catalog, actual)) { now } }
        }
    }

    @Test fun receiptAndObservationClockRewindAreRejected() = runBlocking {
        val catalog = catalog()
        val first = binding(catalog).copy(issuedAt = session + 100, validUntil = session + 30_100)
        for (backwardsObservation in listOf(false, true)) AapsV3ComboCatalogRetention(store).use { receiver ->
            assertNull(receiver.accept(packet(catalog, first, admittedAt = session + 200)) { now })
            val next = binding(catalog, catalog.reads.first().readId, 4)
                .copy(issuedAt = session + 101, validUntil = session + 30_101)
            val incoming = packet(catalog, next, admittedAt = if (backwardsObservation) now else now - 1)
            refused { receiver.accept(incoming) { if (backwardsObservation) now - 1 else now } }
        }
    }

    @Test fun invalidHandoffObservationOrSessionAndExpiredInputCannotBeUsed() = runBlocking {
        val catalog = catalog(0)
        for (invalid in 0..4) AapsV3ComboCatalogRetention(store).use { receiver ->
            val handoff = packet(catalog, roster = if (invalid == 0) 0 else 1,
                startedAt = if (invalid == 1) now + 1 else session,
                admittedAt = when (invalid) { 2 -> now - 1; 3 -> now + 1; else -> now })
            refused { receiver.accept(handoff) { if (invalid == 4) now + 30_000 else now } }
        }
    }

    @Test fun changedSnapshotOrCatalogDigestCannotProduceAnAudit() = runBlocking {
        val original = catalog()
        for (snapshotChanged in listOf(false, true)) AapsV3ComboCatalogRetention(store).use { receiver ->
            assertNull(receiver.accept(packet(original)) { now })
            val changed = AapsV3ComboInventory.create(if (snapshotChanged) other else snapshot, "a".repeat(64),
                original.reads, writerFailed = !snapshotChanged)
            refused { receiver.accept(packet(changed, binding(changed, changed.reads.first().readId, 4))) { now } }
        }
    }

    @Test fun wrongContentKindAndClosedConsumerAreTerminal() = runBlocking {
        val chunkBinding = AapsV3ComboChunkBinding(client, master, snapshot, snapshot, snapshot, "a".repeat(64),
            "b".repeat(64), 3, now, now + 30_000, 1, 0)
        val content = ComboResponseContent.Chunk(chunkBinding, byteArrayOf(1))
        AapsV3ComboCatalogRetention(store).use { receiver ->
            refused { receiver.accept(ComboResponseHandoff(1, content, session, now)) { now } }
            assertTrue(runCatching { content.copyBytes() }.isFailure)
        }
        val closed = AapsV3ComboCatalogRetention(store)
        closed.close()
        refused { closed.accept(packet(catalog(0))) { now } }
    }

    @Test fun busyInputIsClosedWithoutInvalidatingTheRunningAudit() = runBlocking {
        val receiver = AapsV3ComboCatalogRetention(store)
        try { blockedStore { release ->
            val active = async(start = CoroutineStart.UNDISPATCHED) { receiver.accept(packet(catalog(0))) { now } }
            val busy = packet(catalog(0))
            refused { receiver.accept(busy) { now } }
            assertTrue(runCatching { (busy.content as ComboResponseContent.Inventory).page() }.isFailure)
            release.complete(Unit)
            assertTrue(active.await()!!.declaredInventoryRetained)
        } } finally { receiver.close() }
    }

    @Test fun closeDuringPrivateReadSuppressesTheResult() = runBlocking {
        val receiver = AapsV3ComboCatalogRetention(store)
        blockedStore { release ->
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { receiver.accept(packet(catalog(0))) { now } }
            }
            receiver.close(); release.complete(Unit)
            assertTrue(active.await().exceptionOrNull() is AapsV3ComboInventoryFailure)
        }
        assertEquals(0L, db.journalDao().totals().reads)
    }

    @Test fun cancellationDuringPrivateReadClosesContentAndPropagates() = runBlocking {
        val handoff = packet(catalog(0))
        val receiver = AapsV3ComboCatalogRetention(store)
        try { blockedStore {
            val active = async(start = CoroutineStart.UNDISPATCHED) { receiver.accept(handoff) { now } }
            active.cancelAndJoin()
            assertTrue(active.isCancelled)
            assertTrue(runCatching { (handoff.content as ComboResponseContent.Inventory).page() }.isFailure)
            refused { receiver.accept(packet(catalog(0))) { now } }
        } } finally { receiver.close() }
    }

    @Test fun cancellationDuringPartialPageCannotBeResumedFromAnotherCoroutine() = runBlocking {
        val catalog = catalog()
        val receiver = AapsV3ComboCatalogRetention(store)
        val first = packet(catalog)
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            receiver.accept(first) { job.cancel(); now }
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(runCatching { (first.content as ComboResponseContent.Inventory).page() }.isFailure)
        try {
            refused { receiver.accept(packet(catalog, binding(catalog, catalog.reads.first().readId, 4))) { now } }
        } finally { receiver.close() }
    }

    @Test fun expiryAndClockRewindDuringPrivateReadSuppressSuccess() = runBlocking {
        for (late in listOf(now - 1, now + 30_000)) AapsV3ComboCatalogRetention(store).use { receiver ->
            blockedStore { release ->
                val initial = now
                var clock = initial
                val active = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { receiver.accept(packet(catalog(0))) { clock } }
                }
                clock = late; release.complete(Unit)
                assertTrue(active.await().exceptionOrNull() is AapsV3ComboInventoryFailure)
            }
        }
    }

    @Test fun externalAndInheritedTransactionsCannotReleaseAnAudit() = runBlocking {
        db.withTransaction {
            for (inherited in listOf(false, true)) AapsV3ComboCatalogRetention(store).use { receiver ->
                val result = if (inherited) withContext(Dispatchers.IO) {
                    runCatching { receiver.accept(packet(catalog(0))) { now } }
                } else runCatching { receiver.accept(packet(catalog(0))) { now } }
                assertEquals(ComboRetentionError.STORAGE, (result.exceptionOrNull() as ComboRetentionFailure).reason)
            }
        }
    }

    @Test fun storageFailureCannotYieldAPartialAuditOrBeReused() = runBlocking {
        val receiver = AapsV3ComboCatalogRetention(store)
        db.journalDao().totals()
        db.close()
        val result = runCatching { receiver.accept(packet(catalog(0))) { now } }
        assertEquals(ComboRetentionError.STORAGE, (result.exceptionOrNull() as ComboRetentionFailure).reason)
        refused { receiver.accept(packet(catalog(0))) { now } }
    }

    @Test fun completeMaximumCatalogStaysBoundedAndPreservesAllMissingIds() = runBlocking {
        val catalog = catalog(AapsV3ComboInventory.MAX_READS)
        AapsV3ComboCatalogRetention(store).use { receiver ->
            var after: String? = null
            var counter = 3L
            repeat(128) { index ->
                val request = binding(catalog, after, counter++, 32)
                val result = receiver.accept(packet(catalog, request)) { now }
                assertEquals(index == 127, result != null)
                if (result != null) assertEquals(catalog.reads.map { it.readId }, result.missingReadIds)
                after = catalog.page(request.query).reads.last().readId
                now++
            }
        }
        assertEquals(0L, db.journalDao().totals().reads)
    }
}
