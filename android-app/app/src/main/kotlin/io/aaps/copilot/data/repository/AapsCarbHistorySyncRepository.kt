package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.SyncStateDao
import io.aaps.copilot.data.local.entity.SyncStateEntity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal typealias AapsCarbHistoryPageLoader = suspend (
    fromTs: Long,
    throughTs: Long,
    afterTimestamp: Long,
    afterId: Long,
    limit: Int
) -> AapsCarbHistoryResult

internal typealias AapsCarbHistoryPageImporter =
    suspend (AapsCarbHistoryPage, commitGuard: () -> Boolean) -> Unit

private class AapsCarbPageImportException(cause: Throwable) : RuntimeException(cause)

private enum class PostImportPublication {
    COMPLETED,
    CONTINUE,
    INVALID_CONTINUATION
}

internal sealed interface AapsCarbHistorySyncResult {
    data class Completed(
        val revisionId: Long,
        val pages: Int,
        val rows: Int
    ) : AapsCarbHistorySyncResult

    data class UpToDate(val revisionId: Long) : AapsCarbHistorySyncResult

    data class Failed(
        val reason: String,
        val pages: Int,
        val rows: Int
    ) : AapsCarbHistorySyncResult

    data class Superseded(
        val requestedRevisionId: Long?,
        val observedRevisionId: Long?
    ) : AapsCarbHistorySyncResult

    data class PageCapReached(
        val revisionId: Long,
        val pages: Int,
        val rows: Int
    ) : AapsCarbHistorySyncResult
}

class AapsCarbHistorySyncRepository internal constructor(
    private val syncStateDao: SyncStateDao,
    private val loadPage: AapsCarbHistoryPageLoader,
    private val importPage: AapsCarbHistoryPageImporter,
    private val afterPostImportCheckBeforePublication: suspend () -> Unit = {},
    private val auditLogger: AuditLogger?,
    private val clock: () -> Long = System::currentTimeMillis,
    pageSize: Int = DEFAULT_PAGE_SIZE,
    hardPageCap: Int = DEFAULT_HARD_PAGE_CAP
) {
    internal constructor(
        source: AapsCarbHistorySource,
        importer: AapsCarbHistoryImporter,
        syncStateDao: SyncStateDao,
        auditLogger: AuditLogger?,
        clock: () -> Long = System::currentTimeMillis,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        hardPageCap: Int = DEFAULT_HARD_PAGE_CAP
    ) : this(
        syncStateDao = syncStateDao,
        loadPage = source::load,
        importPage = { page, commitGuard ->
            importer.importPage(page, commitGuard)
        },
        auditLogger = auditLogger,
        clock = clock,
        pageSize = pageSize,
        hardPageCap = hardPageCap
    )

    private val mutex = Mutex()
    private val revisionCommitMutex = Mutex()
    private val observedRevisionFloorMutex = Mutex()
    private val latestObservedRevision = AtomicLong(NO_REVISION)
    private val pageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
    private val hardPageCap = hardPageCap.coerceIn(1, MAX_HARD_PAGE_CAP)

    // Guarded by mutex. A persisted cursor is reusable only while this process
    // can prove which revision scan owns it.
    private var activeRevisionId: Long? = null

    internal suspend fun observeRevision(revisionId: Long) {
        if (revisionId < 0L) return
        revisionCommitMutex.withLock {
            observeRevisionLocked(revisionId)
        }
    }

    internal suspend fun sync(): AapsCarbHistorySyncResult =
        runSerializedSync(null)

    internal suspend fun syncForRevision(revisionId: Long): AapsCarbHistorySyncResult {
        if (revisionId < 0L) {
            return AapsCarbHistorySyncResult.Failed(
                reason = "invalid_revision",
                pages = 0,
                rows = 0
            )
        }
        return runSerializedSync(revisionId)
    }

    private suspend fun runSerializedSync(
        requestedRevisionId: Long?
    ): AapsCarbHistorySyncResult {
        return try {
            requestedRevisionId?.let { persistObservedRevisionFloor(it) }
            syncInternal(requestedRevisionId)
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            auditWarn(
                message = "aaps_carb_history_sync_failed",
                metadata = mapOf(
                    "reason" to "sync_state_failed",
                    "requestedRevisionId" to requestedRevisionId
                )
            )
            AapsCarbHistorySyncResult.Failed(
                reason = "sync_state_failed",
                pages = 0,
                rows = 0
            )
        }
    }

    private suspend fun syncInternal(
        requestedRevisionId: Long?
    ): AapsCarbHistorySyncResult = mutex.withLock {
        val observedBeforeStart = readObservedRevisionFloor()
        if (
            requestedRevisionId != null &&
            observedBeforeStart != null &&
            observedBeforeStart > requestedRevisionId
        ) {
            return@withLock superseded(requestedRevisionId, observedBeforeStart)
        }

        val completedRevisionId = stateValue(REVISION_ID)
        val revisionFloor = higherRevision(
            completedRevisionId,
            observedBeforeStart
        )
        if (
            requestedRevisionId != null &&
            revisionFloor != null &&
            revisionFloor > requestedRevisionId
        ) {
            return@withLock superseded(requestedRevisionId, revisionFloor)
        }
        if (requestedRevisionId != null && completedRevisionId == requestedRevisionId) {
            return@withLock AapsCarbHistorySyncResult.UpToDate(requestedRevisionId)
        }

        var scanRevisionId = activeRevisionId
        val canResume = scanRevisionId != null &&
            completedRevisionId != scanRevisionId &&
            (requestedRevisionId == null || requestedRevisionId == scanRevisionId) &&
            (observedBeforeStart == null || observedBeforeStart == scanRevisionId)
        var cursorTimestamp: Long
        var cursorId: Long
        if (canResume) {
            cursorTimestamp = stateValue(CURSOR_TS) ?: 0L
            cursorId = stateValue(CURSOR_ID) ?: 0L
        } else {
            resetCursorIfNeeded()
            activeRevisionId = null
            scanRevisionId = null
            cursorTimestamp = 0L
            cursorId = 0L
        }

        val throughTs = clock().coerceAtLeast(0L)
        val fromTs = (throughTs - LOOKBACK_MS).coerceAtLeast(0L)
        var pages = 0
        var rows = 0

        while (pages < hardPageCap) {
            val result = try {
                loadPage(
                    fromTs,
                    throughTs,
                    cursorTimestamp,
                    cursorId,
                    pageSize
                )
            } catch (error: Throwable) {
                error.rethrowCancellationOrFatal()
                return@withLock fail(
                    reason = "request_exception",
                    pages = pages,
                    rows = rows
                )
            }

            val page = when (result) {
                is AapsCarbHistoryResult.Page -> result.value
                else -> {
                    val observed = observedRevision()
                    if (
                        scanRevisionId != null &&
                        observed != null &&
                        observed != scanRevisionId
                    ) {
                        resetCursorIfNeeded()
                        activeRevisionId = null
                        return@withLock superseded(scanRevisionId, observed)
                    }
                    return@withLock fail(
                        reason = result.failureReason(),
                        pages = pages,
                        rows = rows
                    )
                }
            }
            val observedFloorForPage = persistObservedRevisionFloor(page.revisionId)

            if (scanRevisionId == null) {
                val currentRevisionFloor = higherRevision(
                    completedRevisionId,
                    observedFloorForPage
                )
                if (
                    requestedRevisionId != null &&
                    page.revisionId != requestedRevisionId
                ) {
                    resetCursorIfNeeded()
                    activeRevisionId = null
                    return@withLock superseded(
                        requestedRevisionId,
                        observedFloorForPage
                    )
                }
                if (
                    currentRevisionFloor != null &&
                    page.revisionId < currentRevisionFloor
                ) {
                    resetCursorIfNeeded()
                    activeRevisionId = null
                    return@withLock superseded(
                        requestedRevisionId,
                        currentRevisionFloor
                    )
                }
                if (completedRevisionId == page.revisionId) {
                    resetCursorIfNeeded()
                    activeRevisionId = null
                    return@withLock AapsCarbHistorySyncResult.UpToDate(page.revisionId)
                }
                scanRevisionId = page.revisionId
                activeRevisionId = page.revisionId
            } else if (page.revisionId != scanRevisionId) {
                resetCursorIfNeeded()
                activeRevisionId = null
                return@withLock superseded(
                    scanRevisionId,
                    observedFloorForPage
                )
            }

            observedRevision()?.let { observed ->
                if (observed != scanRevisionId) {
                    resetCursorIfNeeded()
                    activeRevisionId = null
                    return@withLock superseded(scanRevisionId, observed)
                }
            }

            val importingRevisionId = scanRevisionId
                ?: return@withLock fail("missing_revision", pages, rows)
            val publication = try {
                revisionCommitMutex.withLock {
                    if (latestObservedRevision.get() != importingRevisionId) {
                        throw AapsCarbImportSupersededException()
                    }
                    try {
                        importPage(page) {
                            latestObservedRevision.get() == importingRevisionId
                        }
                    } catch (error: Throwable) {
                        error.rethrowCancellationOrFatal()
                        if (error is AapsCarbImportSupersededException) throw error
                        throw AapsCarbPageImportException(error)
                    }
                    pages += 1
                    rows += page.rows.size
                    if (latestObservedRevision.get() != importingRevisionId) {
                        throw AapsCarbImportSupersededException()
                    }
                    afterPostImportCheckBeforePublication()
                    if (latestObservedRevision.get() != importingRevisionId) {
                        throw AapsCarbImportSupersededException()
                    }
                    when {
                        !page.hasMore -> {
                            upsertStates(
                                REVISION_ID to importingRevisionId,
                                CURSOR_TS to 0L,
                                CURSOR_ID to 0L
                            )
                            PostImportPublication.COMPLETED
                        }
                        !isStrictlyAfter(
                            timestamp = page.nextTimestamp,
                            id = page.nextId,
                            cursorTimestamp = cursorTimestamp,
                            cursorId = cursorId
                        ) -> PostImportPublication.INVALID_CONTINUATION
                        else -> {
                            upsertStates(
                                CURSOR_TS to page.nextTimestamp,
                                CURSOR_ID to page.nextId
                            )
                            PostImportPublication.CONTINUE
                        }
                    }
                }
            } catch (_: AapsCarbImportSupersededException) {
                resetCursorIfNeeded()
                activeRevisionId = null
                return@withLock superseded(
                    importingRevisionId,
                    observedRevision()
                )
            } catch (_: AapsCarbPageImportException) {
                return@withLock fail(
                    reason = "import_failed",
                    pages = pages,
                    rows = rows
                )
            }
            when (publication) {
                PostImportPublication.COMPLETED -> {
                    activeRevisionId = null
                    auditInfo(
                        message = "aaps_carb_history_sync_completed",
                        metadata = mapOf(
                            "revisionId" to importingRevisionId,
                            "pages" to pages,
                            "rows" to rows
                        )
                    )
                    return@withLock AapsCarbHistorySyncResult.Completed(
                        revisionId = importingRevisionId,
                        pages = pages,
                        rows = rows
                    )
                }
                PostImportPublication.INVALID_CONTINUATION -> return@withLock fail(
                    reason = "invalid_continuation",
                    pages = pages,
                    rows = rows
                )
                PostImportPublication.CONTINUE -> {
                    cursorTimestamp = page.nextTimestamp
                    cursorId = page.nextId
                }
            }
        }

        val revisionId = scanRevisionId
            ?: return@withLock fail("missing_revision", pages, rows)
        auditWarn(
            message = "aaps_carb_history_sync_page_cap_reached",
            metadata = mapOf(
                "revisionId" to revisionId,
                "pages" to pages,
                "rows" to rows,
                "pageCap" to hardPageCap
            )
        )
        AapsCarbHistorySyncResult.PageCapReached(
            revisionId = revisionId,
            pages = pages,
            rows = rows
        )
    }

    private suspend fun fail(
        reason: String,
        pages: Int,
        rows: Int
    ): AapsCarbHistorySyncResult.Failed {
        auditWarn(
            message = "aaps_carb_history_sync_failed",
            metadata = mapOf(
                "reason" to reason,
                "pages" to pages,
                "rows" to rows,
                "activeRevisionId" to activeRevisionId
            )
        )
        return AapsCarbHistorySyncResult.Failed(reason, pages, rows)
    }

    private suspend fun superseded(
        requestedRevisionId: Long?,
        observedRevisionId: Long?
    ): AapsCarbHistorySyncResult.Superseded {
        auditWarn(
            message = "aaps_carb_history_sync_superseded",
            metadata = mapOf(
                "requestedRevisionId" to requestedRevisionId,
                "observedRevisionId" to observedRevisionId
            )
        )
        return AapsCarbHistorySyncResult.Superseded(
            requestedRevisionId = requestedRevisionId,
            observedRevisionId = observedRevisionId
        )
    }

    private suspend fun resetCursorIfNeeded() {
        val cursorTimestamp = stateValue(CURSOR_TS) ?: 0L
        val cursorId = stateValue(CURSOR_ID) ?: 0L
        if (cursorTimestamp != 0L || cursorId != 0L) {
            upsertStates(
                CURSOR_TS to 0L,
                CURSOR_ID to 0L
            )
        }
    }

    private suspend fun stateValue(source: String): Long? =
        syncStateDao.bySource(source)?.lastSyncedTimestamp

    private suspend fun upsertStates(vararg values: Pair<String, Long>) {
        syncStateDao.upsertAll(
            values.map { (source, value) ->
                SyncStateEntity(
                    source = source,
                    lastSyncedTimestamp = value
                )
            }
        )
    }

    private suspend fun readObservedRevisionFloor(): Long? =
        revisionCommitMutex.withLock {
            observedRevisionFloorMutex.withLock {
                val persisted = stateValue(OBSERVED_REVISION_FLOOR)
                persisted?.let(::observeRevisionLocked)
                higherRevision(persisted, observedRevision())
            }
        }

    private suspend fun persistObservedRevisionFloor(revisionId: Long): Long {
        return revisionCommitMutex.withLock {
            observeRevisionLocked(revisionId)
            observedRevisionFloorMutex.withLock {
                val persisted = stateValue(OBSERVED_REVISION_FLOOR)
                persisted?.let(::observeRevisionLocked)
                val effectiveFloor = observedRevision() ?: revisionId
                if (persisted == null || effectiveFloor > persisted) {
                    upsertStates(OBSERVED_REVISION_FLOOR to effectiveFloor)
                }
                effectiveFloor
            }
        }
    }

    private fun observeRevisionLocked(revisionId: Long) {
        val current = latestObservedRevision.get()
        if (revisionId > current) {
            latestObservedRevision.set(revisionId)
        }
    }

    private fun observedRevision(): Long? =
        latestObservedRevision.get().takeIf { it != NO_REVISION }

    private fun higherRevision(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private suspend fun auditInfo(
        message: String,
        metadata: Map<String, Any?>
    ) {
        val logger = auditLogger ?: return
        try {
            logger.info(message, metadata)
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
        }
    }

    private suspend fun auditWarn(
        message: String,
        metadata: Map<String, Any?>
    ) {
        val logger = auditLogger ?: return
        try {
            logger.warn(message, metadata)
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
        }
    }

    private fun AapsCarbHistoryResult.failureReason(): String = when (this) {
        AapsCarbHistoryResult.Busy -> "busy"
        AapsCarbHistoryResult.Timeout -> "timeout"
        AapsCarbHistoryResult.Error -> "remote_error"
        AapsCarbHistoryResult.Unavailable -> "unavailable"
        AapsCarbHistoryResult.Invalid -> "invalid_result"
        AapsCarbHistoryResult.TransportTimeout -> "transport_timeout"
        is AapsCarbHistoryResult.Page -> "unexpected_page"
    }

    private fun Throwable.rethrowCancellationOrFatal() {
        if (this is CancellationException || this is Error) throw this
    }

    private fun isStrictlyAfter(
        timestamp: Long,
        id: Long,
        cursorTimestamp: Long,
        cursorId: Long
    ): Boolean =
        timestamp > cursorTimestamp ||
            (timestamp == cursorTimestamp && id > cursorId)

    internal companion object {
        const val CURSOR_TS = "aaps_carb_history_cursor_ts"
        const val CURSOR_ID = "aaps_carb_history_cursor_id"
        const val REVISION_ID = "aaps_carb_history_revision_id"
        const val OBSERVED_REVISION_FLOOR = "aaps_carb_history_observed_revision_floor"

        private const val NO_REVISION = -1L
        private const val DEFAULT_PAGE_SIZE = 200
        private const val MAX_PAGE_SIZE = 200
        private const val DEFAULT_HARD_PAGE_CAP = 64
        private const val MAX_HARD_PAGE_CAP = 256
        private const val LOOKBACK_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
