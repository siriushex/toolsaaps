package io.aaps.copilot.data.repository

import io.aaps.copilot.security.ServerAiHistoryPersistence
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerDailyReviewRepositoryTest {
    private val f = DailyReviewFixtures
    private class Disk : ServerAiHistoryPersistence {
        var value: String? = null
        var failWrite = false
        var writes = 0
        var readHook: suspend () -> Unit = {}
        override suspend fun read(): String? {
            val result = value
            readHook()
            return result
        }
        override suspend fun write(value: String) { if (failWrite) error("synthetic private storage"); this.value = value; writes++ }
    }

    private inner class Fixture(val disk: Disk = Disk()) {
        var now = f.now
        var binding = f.device
        var count = 1
        var calls = 0
        var details = 0
        var error: ServerAiHistoryError? = null
        var pageHook: suspend (DailyReviewQuery) -> Unit = {}
        var detailHook: suspend () -> Unit = {}
        fun repository() = ServerDailyReviewRepository(disk, { binding }, { now }, { ZoneId.of("UTC") },
            readPage = { query, expected ->
                assertEquals(binding, expected)
                calls++
                pageHook(query)
                error?.let { throw ServerAiHistoryFailure(it) }
                val end = minOf(count.toLong(), query.afterSequence + 20)
                val rows = if (query.afterSequence >= end) emptyList() else
                    ((query.afterSequence + 1)..end).map { f.summary(it.toInt()) }
                DailyReviewCodec.page(f.page(rows, count.toLong(), end < count), query, now)
            },
            readDetail = { id, expected ->
                assertEquals(binding, expected)
                details++
                error?.let { throw ServerAiHistoryFailure(it) }
                val result = DailyReviewCodec.detail(f.detail(java.util.UUID.fromString(id).leastSignificantBits.toInt()), id, now)
                detailHook()
                result
            }, dispatcher = Dispatchers.Unconfined)
    }

    @Test fun completeIndexLoadsOnceAndSurvivesRestartWithoutAnotherRead() = runTest {
        val source = Fixture().apply { count = 25 }
        val first = source.repository()
        first.open()
        assertEquals(2, source.calls)
        assertEquals(25, first.state.value.records.size)
        assertEquals(f.id(25), first.state.value.records.first().jobId)
        assertEquals(f.now, first.state.value.fetchedAtMs)
        first.open()
        val restarted = source.repository()
        restarted.open()
        assertEquals(2, source.calls)
        assertEquals(first.state.value.records, restarted.state.value.records)
        assertEquals(1, source.disk.writes)
    }

    @Test fun failedContinuationCannotPartiallyReplaceLastCompleteIndex() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open()
        val before = source.disk.value
        source.count = 25
        source.pageHook = { if (it.afterSequence > 0) throw ServerAiHistoryFailure(ServerAiHistoryError.NETWORK) }
        repository.refresh()
        assertEquals(before, source.disk.value)
        assertEquals(1, repository.state.value.records.size)
        assertEquals(f.now, repository.state.value.fetchedAtMs)
        assertEquals(ServerAiHistoryError.NETWORK, repository.state.value.failure)
        assertFalse(repository.state.value.refreshing)
    }

    @Test fun unavailableRouteBacksOffAndDoesNotPretendConnectionIsMissing() = runTest {
        val source = Fixture().apply { error = ServerAiHistoryError.NOT_FOUND }
        val repository = source.repository()
        repository.open(); repository.open()
        assertEquals(1, source.calls)
        assertEquals(ServerAiHistoryError.NOT_FOUND, repository.state.value.failure)
        source.now += 300_001
        repository.open()
        assertEquals(2, source.calls)
        assertNull(repository.state.value.fetchedAtMs)
    }

    @Test fun nextLocalCutoffRechecksButNeverRelabelsOldCacheOnFailure() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open()
        source.now += 4 * 3_600_000L
        source.error = ServerAiHistoryError.NETWORK
        repository.open()
        assertEquals(2, source.calls)
        assertEquals(f.now, repository.state.value.fetchedAtMs)
        assertEquals(1, repository.state.value.records.size)
    }

    @Test fun selectedResultIsRetainedSeparatelyAndReopeningDoesNotReinfer() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open(); repository.select(f.id())
        assertEquals(1, source.details)
        assertEquals(f.now - 500, repository.state.value.detail?.checkedAtMs)
        repository.closeSelection(); repository.select(f.id())
        assertEquals(1, source.details)
        val restarted = source.repository()
        restarted.open(); restarted.select(f.id())
        assertEquals(1, source.details)
        assertEquals(f.text, restarted.state.value.detail?.summary)
        source.error = ServerAiHistoryError.NETWORK
        restarted.refreshSelection()
        assertEquals(f.text, restarted.state.value.detail?.summary)
        assertEquals(ServerAiHistoryError.NETWORK, restarted.state.value.detailFailure)
    }

    @Test fun changedDeviceAndRevocationHidePriorDeviceData() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open(); repository.select(f.id())
        source.binding = "e".repeat(64)
        source.error = ServerAiHistoryError.UNAUTHORIZED
        repository.open()
        assertTrue(repository.state.value.records.isEmpty())
        assertNull(repository.state.value.detail)
        assertNull(repository.state.value.fetchedAtMs)
    }

    @Test fun deviceChangeDuringPaginationCannotCommitForeignIndex() = runTest {
        val source = Fixture().apply { count = 25 }
        val repository = source.repository()
        source.pageHook = { source.binding = "e".repeat(64) }
        repository.open()
        assertNull(source.disk.value)
        assertTrue(repository.state.value.records.isEmpty())
        assertEquals(ServerAiHistoryError.DEVICE_MISMATCH, repository.state.value.failure)
    }

    @Test fun deviceChangeDuringDiskReadCannotDisplayCachedForeignContent() = runTest {
        val source = Fixture()
        source.repository().also { it.open(); it.select(f.id()) }
        val before = source.disk.value
        source.disk.readHook = { source.binding = "e".repeat(64) }
        val restarted = source.repository()
        restarted.open()
        assertTrue(restarted.state.value.records.isEmpty())
        assertNull(restarted.state.value.detail)
        assertNull(restarted.state.value.fetchedAtMs)
        assertEquals(ServerAiHistoryError.DEVICE_MISMATCH, restarted.state.value.failure)
        assertEquals(before, source.disk.value)
        assertEquals(1, source.calls)
    }

    @Test fun knownRevocationClearsCachedMedicalContentAcrossRestart() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open(); repository.select(f.id())
        source.error = ServerAiHistoryError.UNAUTHORIZED
        repository.refresh()
        assertTrue(repository.state.value.records.isEmpty())
        assertFalse(source.disk.value!!.contains(f.text))
        val restarted = source.repository()
        restarted.open()
        assertTrue(restarted.state.value.records.isEmpty())
        assertNull(restarted.state.value.detail)
    }

    @Test fun denialMarkerWriteFailureCannotEscapeOrRedisplayMedicalData() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open()
        source.disk.failWrite = true
        source.error = ServerAiHistoryError.UNAUTHORIZED
        repository.refresh()
        assertTrue(repository.state.value.records.isEmpty())
        assertEquals(ServerAiHistoryError.STORAGE, repository.state.value.failure)
    }

    @Test fun writeFailureAndClockReversalCannotCreateNewSuccess() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open()
        val original = source.disk.value
        source.disk.failWrite = true
        repository.refresh()
        assertEquals(original, source.disk.value)
        assertEquals(ServerAiHistoryError.STORAGE, repository.state.value.failure)
        source.disk.failWrite = false
        source.now--
        val restarted = source.repository()
        restarted.open()
        assertTrue(restarted.state.value.records.isEmpty())
        assertEquals(ServerAiHistoryError.STORAGE, restarted.state.value.failure)
    }

    @Test fun expiredEntriesAreNotDisplayedAndArePrunedWithoutExtendingExpiry() = runTest {
        val source = Fixture()
        source.repository().also { it.open(); it.select(f.id()) }
        source.now = f.expires
        source.error = ServerAiHistoryError.NETWORK
        val restarted = source.repository()
        restarted.open()
        assertTrue(restarted.state.value.records.isEmpty())
        assertNull(restarted.state.value.detail)
        assertFalse(source.disk.value!!.contains(f.text))
    }

    @Test fun clockReversalHidesInMemoryIndexAndLaterFetchedDetailWithoutOverwritingDisk() = runTest {
        for (afterDetail in listOf(false, true)) {
            val source = Fixture()
            val repository = source.repository()
            repository.open()
            if (afterDetail) {
                source.now += 1000
                repository.select(f.id())
            }
            val before = source.disk.value
            val calls = source.calls
            source.now--
            repository.open()
            assertTrue(repository.state.value.records.isEmpty())
            assertNull(repository.state.value.detail)
            assertNull(repository.state.value.fetchedAtMs)
            assertEquals(ServerAiHistoryError.STORAGE, repository.state.value.failure)
            assertEquals(before, source.disk.value)
            assertEquals(calls, source.calls)
        }
    }

    @Test fun explicitRefreshPrunesExpiredMemoryEvenWhenReadFails() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open(); repository.select(f.id())
        source.now = f.expires
        source.error = ServerAiHistoryError.NETWORK
        repository.refresh()
        assertTrue(repository.state.value.records.isEmpty())
        assertNull(repository.state.value.detail)
        assertNull(repository.state.value.selectedJobId)
        assertFalse(source.disk.value!!.contains(f.text))
        assertEquals(ServerAiHistoryError.NETWORK, repository.state.value.failure)
    }

    @Test fun expiredDiskRestoreNeverEmitsExpiredTextToCollectors() = runTest {
        val source = Fixture()
        source.repository().also { it.open(); it.select(f.id()) }
        source.now = f.expires
        source.error = ServerAiHistoryError.NETWORK
        val restarted = source.repository()
        val observed = mutableListOf<ServerDailyArchiveState>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            restarted.state.collect { observed += it }
        }
        restarted.open()
        observer.cancelAndJoin()
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it.records.isEmpty() && it.detail == null })
    }

    @Test fun expiryDuringSelectedReadCannotReintroduceExpiredRows() = runTest {
        val source = Fixture()
        val repository = source.repository()
        repository.open()
        source.detailHook = { source.now = f.expires }
        repository.select(f.id())
        assertTrue(repository.state.value.records.isEmpty())
        assertNull(repository.state.value.detail)
        assertNull(repository.state.value.selectedJobId)
        assertFalse(source.disk.value!!.contains(f.text))
        assertEquals(ServerAiHistoryError.NOT_FOUND, repository.state.value.detailFailure)
    }

    @Test fun cancellationAndConcurrentOpensDoNotWritePartialState() = runTest {
        val source = Fixture()
        val repository = source.repository()
        source.pageHook = { awaitCancellation() }
        val work = launch { repository.open() }
        runCurrent()
        repository.open()
        assertEquals(1, source.calls)
        work.cancelAndJoin()
        assertNull(source.disk.value)
        assertFalse(repository.state.value.refreshing)
        assertTrue(repository.state.value.records.isEmpty())
    }

    @Test fun rowBudgetRefusesWholeIndexAndMalformedCacheIsNotSilentlyOverwritten() = runTest {
        val source = Fixture().apply { count = 513 }
        source.repository().also {
            it.open()
            assertEquals(ServerAiHistoryError.TOO_LARGE, it.state.value.failure)
            assertTrue(it.state.value.records.isEmpty())
        }
        assertNull(source.disk.value)
        source.disk.value = "{\"version\":1,\"version\":1}"
        val calls = source.calls
        source.repository().also {
            it.open()
            assertEquals(ServerAiHistoryError.STORAGE, it.state.value.failure)
        }
        assertEquals(calls, source.calls)
    }
}
