package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.dao.SyncStateDao
import io.aaps.copilot.data.local.entity.SyncStateEntity
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AapsCarbHistorySyncRepositoryTest {

    @Test
    fun firstSyncRequestsExactlyThirtyDaysFromZeroCursorAndCompletesRevision() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 11L))
        )
        val imported = mutableListOf<AapsCarbHistoryPage>()
        val repository = repository(state, loader) { imported += it }

        repository.sync()

        assertThat(loader.calls).containsExactly(
            LoadCall(
                fromTs = NOW - THIRTY_DAYS_MS,
                throughTs = NOW,
                afterTimestamp = 0L,
                afterId = 0L,
                limit = 200
            )
        )
        assertThat(imported).hasSize(1)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(11L)
    }

    @Test
    fun multiPageSyncUsesExactContinuationCursorAndImportsBeforeProgress() = runTest {
        val state = RecordingSyncStateDao()
        val first = page(
            revisionId = 22L,
            nextTimestamp = ROW_TS,
            nextId = 7L,
            hasMore = true
        )
        val second = page(
            revisionId = 22L,
            nextTimestamp = ROW_TS + 60_000L,
            nextId = 8L
        )
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(first),
            AapsCarbHistoryResult.Page(second)
        )
        val cursorAtImport = mutableListOf<Pair<Long?, Long?>>()
        val repository = repository(state, loader) {
            cursorAtImport += state.value(CURSOR_TS_SOURCE) to state.value(CURSOR_ID_SOURCE)
        }

        repository.sync()

        assertThat(loader.calls.map { it.afterTimestamp to it.afterId })
            .containsExactly(0L to 0L, ROW_TS to 7L)
            .inOrder()
        assertThat(cursorAtImport)
            .containsExactly(null to null, ROW_TS to 7L)
            .inOrder()
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(22L)
    }

    @Test
    fun higherContinuationRevisionSupersedesScanAndBecomesObservedFloor() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 140L,
                    nextTimestamp = ROW_TS,
                    nextId = 140L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Page(page(revisionId = 141L))
        )
        val importedRevisions = mutableListOf<Long>()
        val repository = repository(state, loader) { page ->
            importedRevisions += page.revisionId
        }

        val result = repository.syncForRevision(140L)

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 140L,
                observedRevisionId = 141L
            )
        )
        assertThat(importedRevisions).containsExactly(140L)
        assertThat(loader.calls).hasSize(2)
        assertThat(state.value(CURSOR_TS_SOURCE) ?: 0L).isEqualTo(0L)
        assertThat(state.value(CURSOR_ID_SOURCE) ?: 0L).isEqualTo(0L)
        assertThat(state.value(REVISION_SOURCE)).isNull()
        assertThat(state.value(OBSERVED_REVISION_FLOOR_SOURCE)).isEqualTo(141L)

        val restartedLoader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 140L))
        )
        val restartedRepository = repository(state, restartedLoader)

        val restartedStaleResult = restartedRepository.syncForRevision(140L)

        assertThat(restartedStaleResult).isEqualTo(result)
        assertThat(restartedLoader.calls).isEmpty()
    }

    @Test
    fun cursorAndFinalRevisionStateArePersistedAsAtomicBatches() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 23L,
                    nextTimestamp = ROW_TS,
                    nextId = 9L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Page(page(revisionId = 23L))
        )
        val repository = repository(state, loader)

        repository.sync()

        assertThat(state.atomicBatches).containsExactly(
            listOf(
                OBSERVED_REVISION_FLOOR_SOURCE to 23L
            ),
            listOf(
                CURSOR_TS_SOURCE to ROW_TS,
                CURSOR_ID_SOURCE to 9L
            ),
            listOf(
                REVISION_SOURCE to 23L,
                CURSOR_TS_SOURCE to 0L,
                CURSOR_ID_SOURCE to 0L
            )
        ).inOrder()
    }

    @Test
    fun requestFailureRetriesFromLastSuccessfulExactCursor() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 33L,
                    nextTimestamp = ROW_TS,
                    nextId = 31L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Error,
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 33L,
                    nextTimestamp = ROW_TS + 60_000L,
                    nextId = 32L
                )
            )
        )
        val repository = repository(state, loader)

        repository.sync()
        assertThat(state.value(CURSOR_TS_SOURCE)).isEqualTo(ROW_TS)
        assertThat(state.value(CURSOR_ID_SOURCE)).isEqualTo(31L)
        assertThat(state.value(REVISION_SOURCE)).isNull()

        repository.sync()

        assertThat(loader.calls.map { it.afterTimestamp to it.afterId })
            .containsExactly(0L to 0L, ROW_TS to 31L, ROW_TS to 31L)
            .inOrder()
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(33L)
    }

    @Test
    fun hardPageCapStopsWithoutCommittingRevision() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 44L,
                    nextTimestamp = ROW_TS,
                    nextId = 1L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 44L,
                    nextTimestamp = ROW_TS + 60_000L,
                    nextId = 2L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Page(page(revisionId = 44L))
        )
        val repository = repository(state, loader, hardPageCap = 2)

        repository.sync()

        assertThat(loader.calls).hasSize(2)
        assertThat(state.value(CURSOR_TS_SOURCE)).isEqualTo(ROW_TS + 60_000L)
        assertThat(state.value(CURSOR_ID_SOURCE)).isEqualTo(2L)
        assertThat(state.value(REVISION_SOURCE)).isNull()
    }

    @Test
    fun importFailureDoesNotAdvanceCursorOrRevisionAndRepeatsPage() = runTest {
        val state = RecordingSyncStateDao()
        val first = page(
            revisionId = 55L,
            nextTimestamp = ROW_TS,
            nextId = 41L,
            hasMore = true
        )
        val second = page(
            revisionId = 55L,
            nextTimestamp = ROW_TS + 60_000L,
            nextId = 42L
        )
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(first),
            AapsCarbHistoryResult.Page(second),
            AapsCarbHistoryResult.Page(second)
        )
        var failSecondOnce = true
        val importedCursors = mutableListOf<Pair<Long, Long>>()
        val repository = repository(state, loader) { page ->
            importedCursors += page.nextTimestamp to page.nextId
            if (page.nextId == 42L && failSecondOnce) {
                failSecondOnce = false
                error("import failed")
            }
        }

        repository.sync()
        assertThat(state.value(CURSOR_TS_SOURCE)).isEqualTo(ROW_TS)
        assertThat(state.value(CURSOR_ID_SOURCE)).isEqualTo(41L)
        assertThat(state.value(REVISION_SOURCE)).isNull()

        repository.sync()

        assertThat(importedCursors)
            .containsExactly(
                ROW_TS to 41L,
                (ROW_TS + 60_000L) to 42L,
                (ROW_TS + 60_000L) to 42L
            )
            .inOrder()
        assertThat(loader.calls.takeLast(2).map { it.afterTimestamp to it.afterId })
            .containsExactly(ROW_TS to 41L, ROW_TS to 41L)
            .inOrder()
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(55L)
    }

    @Test
    fun changedRevisionResetsOldScanCursorAndRescansFromZero() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 60L,
                    nextTimestamp = ROW_TS,
                    nextId = 50L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Busy,
            AapsCarbHistoryResult.Page(page(revisionId = 61L))
        )
        val repository = repository(state, loader)

        repository.syncForRevision(60L)
        repository.observeRevision(61L)
        repository.syncForRevision(61L)

        assertThat(loader.calls.map { it.afterTimestamp to it.afterId })
            .containsExactly(0L to 0L, ROW_TS to 50L, 0L to 0L)
            .inOrder()
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(61L)
    }

    @Test
    fun finalRevisionIsAbsentDuringEveryImportAndStoredOnlyAfterFinalPage() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 70L,
                    nextTimestamp = ROW_TS,
                    nextId = 61L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Page(page(revisionId = 70L))
        )
        val revisionDuringImports = mutableListOf<Long?>()
        val repository = repository(state, loader) {
            revisionDuringImports += state.value(REVISION_SOURCE)
        }

        repository.sync()

        assertThat(revisionDuringImports).containsExactly(null, null).inOrder()
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(70L)
    }

    @Test
    fun repeatedUnchangedRevisionDoesNotRequestOrImportAgain() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 80L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        repository.syncForRevision(80L)
        repository.syncForRevision(80L)

        assertThat(loader.calls).hasSize(1)
        assertThat(imports).isEqualTo(1)
    }

    @Test
    fun supersededImportExceptionRollsBackWithoutCursorOrFinalRevision() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 90L,
                    nextTimestamp = ROW_TS,
                    nextId = 71L,
                    hasMore = true
                )
            )
        )
        val committedRows = mutableListOf<AapsCarbHistoryRow>()
        val repository = repository(
            state = state,
            loader = loader,
            guardedImporter = { page, commitGuard ->
                val before = committedRows.toList()
                try {
                    committedRows += page.rows
                    assertThat(commitGuard()).isTrue()
                    throw AapsCarbImportSupersededException()
                } catch (error: Throwable) {
                    committedRows.clear()
                    committedRows += before
                    throw error
                }
            }
        )

        val result = repository.syncForRevision(90L)

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 90L,
                observedRevisionId = 90L
            )
        )
        assertThat(committedRows).isEmpty()
        assertThat(state.value(CURSOR_TS_SOURCE) ?: 0L).isEqualTo(0L)
        assertThat(state.value(CURSOR_ID_SOURCE) ?: 0L).isEqualTo(0L)
        assertThat(state.value(REVISION_SOURCE)).isNull()
    }

    @Test
    fun revisionCannotObserveBetweenCommitGuardAndTransactionReturn() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 90L)),
            AapsCarbHistoryResult.Page(page(revisionId = 91L))
        )
        val guardReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val committedRevisions = mutableListOf<Long>()
        var oldTransactionReturned = false
        val repository = repository(
            state = state,
            loader = loader,
            guardedImporter = { page, commitGuard ->
                assertThat(commitGuard()).isTrue()
                if (page.revisionId == 90L) {
                    guardReached.complete(Unit)
                    releaseCommit.await()
                }
                committedRevisions += page.revisionId
                if (page.revisionId == 90L) {
                    oldTransactionReturned = true
                }
            }
        )

        val oldScan = async { repository.syncForRevision(90L) }
        guardReached.await()
        val newerScan = async { repository.syncForRevision(91L) }
        runCurrent()

        val floorWhileOldTransactionIsActive = state.value(OBSERVED_REVISION_FLOOR_SOURCE)
        val newerCompletedBeforeOldCommit = newerScan.isCompleted
        releaseCommit.complete(Unit)
        val oldResult = oldScan.await()
        val newerResult = newerScan.await()

        assertThat(floorWhileOldTransactionIsActive).isEqualTo(90L)
        assertThat(newerCompletedBeforeOldCommit).isFalse()
        assertThat(oldTransactionReturned).isTrue()
        assertThat(oldResult).isEqualTo(
            AapsCarbHistorySyncResult.Completed(
                revisionId = 90L,
                pages = 1,
                rows = 1
            )
        )
        assertThat(newerResult).isEqualTo(
            AapsCarbHistorySyncResult.Completed(
                revisionId = 91L,
                pages = 1,
                rows = 1
            )
        )
        assertThat(committedRevisions).containsExactly(90L, 91L).inOrder()
        assertThat(state.value(OBSERVED_REVISION_FLOOR_SOURCE)).isEqualTo(91L)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(91L)
    }

    @Test
    fun newerRevisionCannotLinearizeBetweenFinalCheckAndCompletionPublication() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 150L)),
            AapsCarbHistoryResult.Page(page(revisionId = 151L))
        )
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val importedRevisions = mutableListOf<Long>()
        val repository = repository(
            state = state,
            loader = loader,
            afterPostImportCheckBeforePublication = {
                if (importedRevisions.lastOrNull() == 150L) {
                    publicationReached.complete(Unit)
                    releasePublication.await()
                }
            },
            importer = { importedRevisions += it.revisionId }
        )

        val oldScan = async { repository.syncForRevision(150L) }
        publicationReached.await()
        val newerScan = async { repository.syncForRevision(151L) }
        runCurrent()

        val floorBeforeOldPublication = state.value(OBSERVED_REVISION_FLOOR_SOURCE)
        releasePublication.complete(Unit)
        val oldResult = oldScan.await()
        val newerResult = newerScan.await()

        assertThat(floorBeforeOldPublication).isEqualTo(150L)
        assertThat(oldResult).isEqualTo(
            AapsCarbHistorySyncResult.Completed(150L, pages = 1, rows = 1)
        )
        assertThat(newerResult).isEqualTo(
            AapsCarbHistorySyncResult.Completed(151L, pages = 1, rows = 1)
        )
        assertThat(importedRevisions).containsExactly(150L, 151L).inOrder()
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(151L)
    }

    @Test
    fun newerRevisionCannotLinearizeBetweenContinuationCheckAndCursorPublication() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(
                page(
                    revisionId = 160L,
                    nextTimestamp = ROW_TS,
                    nextId = 160L,
                    hasMore = true
                )
            ),
            AapsCarbHistoryResult.Busy,
            AapsCarbHistoryResult.Page(page(revisionId = 161L))
        )
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        var publicationHooks = 0
        val repository = repository(
            state = state,
            loader = loader,
            afterPostImportCheckBeforePublication = {
                publicationHooks += 1
                if (publicationHooks == 1) {
                    publicationReached.complete(Unit)
                    releasePublication.await()
                }
            }
        )

        val oldScan = async { repository.syncForRevision(160L) }
        publicationReached.await()
        val newerScan = async { repository.syncForRevision(161L) }
        runCurrent()

        val floorBeforeCursorPublication = state.value(OBSERVED_REVISION_FLOOR_SOURCE)
        releasePublication.complete(Unit)
        oldScan.await()
        val newerResult = newerScan.await()

        assertThat(floorBeforeCursorPublication).isEqualTo(160L)
        assertThat(newerResult).isEqualTo(
            AapsCarbHistorySyncResult.Completed(161L, pages = 1, rows = 1)
        )
        assertThat(state.atomicBatches).contains(
            listOf(CURSOR_TS_SOURCE to ROW_TS, CURSOR_ID_SOURCE to 160L)
        )
        val floor161Batch = state.atomicBatches.indexOf(
            listOf(OBSERVED_REVISION_FLOOR_SOURCE to 161L)
        )
        val cursor160Batch = state.atomicBatches.indexOf(
            listOf(CURSOR_TS_SOURCE to ROW_TS, CURSOR_ID_SOURCE to 160L)
        )
        assertThat(cursor160Batch).isAtLeast(0)
        assertThat(floor161Batch).isGreaterThan(cursor160Batch)
    }

    @Test
    fun explicitRequestObservesHigherPageRevisionBeforeSuperseding() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 141L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        val result = repository.syncForRevision(140L)

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 140L,
                observedRevisionId = 141L
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(imports).isEqualTo(0)
        assertThat(state.value(REVISION_SOURCE)).isNull()
        assertThat(state.value(OBSERVED_REVISION_FLOOR_SOURCE)).isEqualTo(141L)

        val restartedLoader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 140L))
        )
        val restartedRepository = repository(state, restartedLoader)

        val restartedStaleResult = restartedRepository.syncForRevision(140L)

        assertThat(restartedStaleResult).isEqualTo(result)
        assertThat(restartedLoader.calls).isEmpty()
    }

    @Test
    fun staleQueuedRevisionCannotReplaceNewerObservedRevision() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 101L))
        )
        val repository = repository(state, loader)
        repository.observeRevision(101L)

        repository.syncForRevision(100L)
        repository.syncForRevision(101L)

        assertThat(loader.calls).hasSize(1)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(101L)
    }

    @Test
    fun staleLowerStatusIsIgnoredWithoutRequestingAnOlderScan() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 121L))
        )
        val repository = repository(state, loader)
        repository.observeRevision(121L)
        repository.observeRevision(120L)

        val staleResult = repository.syncForRevision(120L)

        assertThat(staleResult).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 120L,
                observedRevisionId = 121L
            )
        )
        assertThat(loader.calls).isEmpty()

        repository.syncForRevision(121L)

        assertThat(loader.calls).hasSize(1)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(121L)
    }

    @Test
    fun staleLowerStatusDuringImportDoesNotSupersedeNewerScan() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 131L))
        )
        lateinit var repository: AapsCarbHistorySyncRepository
        var staleObservation: Deferred<Unit>? = null
        repository = repository(
            state = state,
            loader = loader,
            guardedImporter = { _, commitGuard ->
                staleObservation = async { repository.observeRevision(130L) }
                runCurrent()
                assertThat(commitGuard()).isTrue()
            }
        )

        val result = repository.syncForRevision(131L)
        staleObservation?.await()

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Completed(
                revisionId = 131L,
                pages = 1,
                rows = 1
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(131L)
    }

    @Test
    fun persistedCompletedRevisionSupersedesStaleExplicitRevisionAfterRestart() = runTest {
        val state = RecordingSyncStateDao().apply {
            seed(REVISION_SOURCE, 121L)
        }
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 120L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        val result = repository.syncForRevision(120L)

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 120L,
                observedRevisionId = 121L
            )
        )
        assertThat(loader.calls).isEmpty()
        assertThat(imports).isEqualTo(0)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(121L)
    }

    @Test
    fun persistedCompletedRevisionRejectsLowerUnrequestedPageAfterRestart() = runTest {
        val state = RecordingSyncStateDao().apply {
            seed(REVISION_SOURCE, 121L)
        }
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 120L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        val result = repository.sync()

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = null,
                observedRevisionId = 121L
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(imports).isEqualTo(0)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(121L)
    }

    @Test
    fun persistedCompletedRevisionDoesNotBlockTrulyNewerPageAfterRestart() = runTest {
        val state = RecordingSyncStateDao().apply {
            seed(REVISION_SOURCE, 121L)
        }
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 122L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        val result = repository.sync()

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Completed(
                revisionId = 122L,
                pages = 1,
                rows = 1
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(imports).isEqualTo(1)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(122L)
    }

    @Test
    fun persistedObservedFloorRejectsStaleExplicitRequestAndLowerPageAfterRestart() = runTest {
        val state = RecordingSyncStateDao().apply {
            seed(OBSERVED_REVISION_FLOOR_SOURCE, 141L)
        }
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 140L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        val explicitResult = repository.syncForRevision(140L)
        val pageResult = repository.sync()

        assertThat(explicitResult).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 140L,
                observedRevisionId = 141L
            )
        )
        assertThat(pageResult).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = null,
                observedRevisionId = 141L
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(imports).isEqualTo(0)
        assertThat(state.value(REVISION_SOURCE)).isNull()
        assertThat(state.value(OBSERVED_REVISION_FLOOR_SOURCE)).isEqualTo(141L)
    }

    @Test
    fun persistedObservedFloorIsNotCompletedAndAllowsMatchingRevisionImport() = runTest {
        val state = RecordingSyncStateDao().apply {
            seed(OBSERVED_REVISION_FLOOR_SOURCE, 141L)
        }
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 141L))
        )
        var imports = 0
        val repository = repository(state, loader) { imports += 1 }

        val result = repository.syncForRevision(141L)

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Completed(
                revisionId = 141L,
                pages = 1,
                rows = 1
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(imports).isEqualTo(1)
        assertThat(state.value(REVISION_SOURCE)).isEqualTo(141L)
        assertThat(state.value(OBSERVED_REVISION_FLOOR_SOURCE)).isEqualTo(141L)
    }

    @Test
    fun invalidNegativeRevisionDoesNotPersistObservedFloor() = runTest {
        val state = RecordingSyncStateDao()
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 1L))
        )
        val repository = repository(state, loader)

        val result = repository.syncForRevision(-1L)

        assertThat(result).isEqualTo(
            AapsCarbHistorySyncResult.Failed(
                reason = "invalid_revision",
                pages = 0,
                rows = 0
            )
        )
        assertThat(loader.calls).isEmpty()
        assertThat(state.value(OBSERVED_REVISION_FLOOR_SOURCE)).isNull()
        assertThat(state.atomicBatches).isEmpty()
    }

    @Test
    fun syncStateFailureFailsClosedBeforeRequestingClinicalData() = runTest {
        val state = RecordingSyncStateDao(failReads = true)
        val loader = RecordingLoader(
            AapsCarbHistoryResult.Page(page(revisionId = 110L))
        )
        val repository = repository(state, loader)

        val result = repository.sync()

        assertThat(result).isInstanceOf(AapsCarbHistorySyncResult.Failed::class.java)
        assertThat((result as AapsCarbHistorySyncResult.Failed).reason)
            .isEqualTo("sync_state_failed")
        assertThat(loader.calls).isEmpty()
    }

    @Test
    fun concurrentTriggersAreSerializedByOneMutex() = runTest {
        val state = RecordingSyncStateDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val loader = RecordingLoader { callIndex ->
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
            if (callIndex == 0) {
                entered.complete(Unit)
                release.await()
            }
            inFlight.decrementAndGet()
            AapsCarbHistoryResult.Page(page(revisionId = callIndex.toLong() + 100L))
        }
        val repository = repository(state, loader)

        val first = async { repository.syncForRevision(100L) }
        entered.await()
        repository.observeRevision(101L)
        val second = async { repository.syncForRevision(101L) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()

        assertThat(maxInFlight.get()).isEqualTo(1)
    }

    @Test
    fun concurrentLowerRequestCannotRegressPersistedObservedFloor() = runTest {
        val state = RecordingSyncStateDao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = RecordingLoader { callIndex ->
            if (callIndex == 0) {
                entered.complete(Unit)
                release.await()
            }
            AapsCarbHistoryResult.Busy
        }
        val repository = repository(state, loader)

        val newer = async { repository.syncForRevision(142L) }
        entered.await()
        val stale = async { repository.syncForRevision(141L) }
        runCurrent()

        val floorWhileNewerScanIsBlocked = state.value(OBSERVED_REVISION_FLOOR_SOURCE)
        release.complete(Unit)
        newer.await()
        val staleResult = stale.await()

        assertThat(floorWhileNewerScanIsBlocked).isEqualTo(142L)
        assertThat(staleResult).isEqualTo(
            AapsCarbHistorySyncResult.Superseded(
                requestedRevisionId = 141L,
                observedRevisionId = 142L
            )
        )
        assertThat(loader.calls).hasSize(1)
        assertThat(state.atomicBatches.flatten())
            .containsExactly(OBSERVED_REVISION_FLOOR_SOURCE to 142L)
    }

    private fun repository(
        state: RecordingSyncStateDao,
        loader: RecordingLoader,
        hardPageCap: Int = 64,
        guardedImporter: AapsCarbHistoryPageImporter? = null,
        afterPostImportCheckBeforePublication: suspend () -> Unit = {},
        importer: suspend (AapsCarbHistoryPage) -> Unit = {}
    ) = AapsCarbHistorySyncRepository(
        syncStateDao = state.proxy,
        loadPage = loader::load,
        importPage = guardedImporter ?: { page, commitGuard ->
            importer(page)
            if (!commitGuard()) {
                throw AapsCarbImportSupersededException()
            }
        },
        afterPostImportCheckBeforePublication = afterPostImportCheckBeforePublication,
        auditLogger = null,
        clock = { NOW },
        pageSize = 200,
        hardPageCap = hardPageCap
    )

    private class RecordingLoader(
        private vararg val results: AapsCarbHistoryResult
    ) {
        private var handler: (suspend (Int) -> AapsCarbHistoryResult)? = null
        val calls = mutableListOf<LoadCall>()

        constructor(handler: suspend (Int) -> AapsCarbHistoryResult) : this() {
            this.handler = handler
        }

        suspend fun load(
            fromTs: Long,
            throughTs: Long,
            afterTimestamp: Long,
            afterId: Long,
            limit: Int
        ): AapsCarbHistoryResult {
            calls += LoadCall(fromTs, throughTs, afterTimestamp, afterId, limit)
            return handler?.invoke(calls.lastIndex)
                ?: results.getOrElse(calls.lastIndex) {
                    error("No result for load call ${calls.size}")
                }
        }
    }

    private class RecordingSyncStateDao(
        private val failReads: Boolean = false
    ) {
        private val values = linkedMapOf<String, SyncStateEntity>()
        val atomicBatches = mutableListOf<List<Pair<String, Long>>>()

        val proxy: SyncStateDao = Proxy.newProxyInstance(
            SyncStateDao::class.java.classLoader,
            arrayOf(SyncStateDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "bySource" -> {
                    if (failReads) error("sync state unavailable")
                    values[args[0] as String]
                }
                "observeAll" -> flowOf(values.values.toList())
                "upsert" -> {
                    val state = args[0] as SyncStateEntity
                    values[state.source] = state
                    Unit
                }
                "upsertAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    val states = args[0] as List<SyncStateEntity>
                    atomicBatches += states.map { it.source to it.lastSyncedTimestamp }
                    states.forEach { state -> values[state.source] = state }
                    Unit
                }
                "toString" -> "RecordingSyncStateDao"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args[0] === this
                else -> error("Unexpected SyncStateDao call: ${method.name}")
            }
        } as SyncStateDao

        fun value(source: String): Long? = values[source]?.lastSyncedTimestamp

        fun seed(source: String, value: Long) {
            values[source] = SyncStateEntity(
                source = source,
                lastSyncedTimestamp = value
            )
        }
    }

    private data class LoadCall(
        val fromTs: Long,
        val throughTs: Long,
        val afterTimestamp: Long,
        val afterId: Long,
        val limit: Int
    )

    private fun page(
        revisionId: Long,
        nextTimestamp: Long = ROW_TS,
        nextId: Long = revisionId,
        hasMore: Boolean = false
    ) = AapsCarbHistoryPage(
        rows = listOf(
            AapsCarbHistoryRow(
                id = nextId.coerceAtLeast(1L),
                version = 1,
                dateCreated = nextTimestamp,
                isValid = true,
                referenceId = null,
                timestamp = nextTimestamp,
                duration = 0L,
                amount = 12.0,
                notes = null,
                notesSha256 = null,
                notesTruncated = false,
                nightscoutId = null,
                nightscoutIdSha256 = null,
                nightscoutIdTruncated = false
            )
        ),
        nextTimestamp = nextTimestamp,
        nextId = nextId,
        hasMore = hasMore,
        generatedAt = NOW,
        revisionId = revisionId
    )

    private companion object {
        const val NOW = 1_785_628_800_000L
        const val ROW_TS = NOW - 60_000L
        const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1_000L
        const val CURSOR_TS_SOURCE = "aaps_carb_history_cursor_ts"
        const val CURSOR_ID_SOURCE = "aaps_carb_history_cursor_id"
        const val REVISION_SOURCE = "aaps_carb_history_revision_id"
        const val OBSERVED_REVISION_FLOOR_SOURCE = "aaps_carb_history_observed_revision_floor"
    }
}
