package io.aaps.copilot.data.repository

import io.aaps.copilot.scheduler.NightlyAdviceSchedule
import io.aaps.copilot.security.ServerAiConnectionError
import io.aaps.copilot.security.ServerAiConnectionFailure
import io.aaps.copilot.security.ServerAiHistoryPersistence
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

internal data class ServerDailyArchiveState(
    val records: List<DailyReviewSummary> = emptyList(), val fetchedAtMs: Long? = null,
    val refreshing: Boolean = false, val failure: ServerAiHistoryError? = null,
    val selectedJobId: String? = null, val detail: DailyReviewDetail? = null,
    val detailFetchedAtMs: Long? = null, val detailRefreshing: Boolean = false,
    val detailFailure: ServerAiHistoryError? = null
) { override fun toString() = "ServerDailyArchiveState(redacted)" }

/** Retained audit reads only. No model, export, alarm or therapy dependency. */
internal class ServerDailyReviewRepository(
    private val persistence: ServerAiHistoryPersistence,
    private val binding: suspend () -> String,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val readPage: suspend (DailyReviewQuery, String) -> DailyReviewPage,
    private val readDetail: suspend (String, String) -> DailyReviewDetail,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ServerDailyArchiveState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var device: String? = null
    private var loaded = false
    private var cache: Cache? = null
    private var attemptedAt: Long? = null

    private data class Cache(val device: String, val fetchedAt: Long, val rows: List<DailyReviewSummary>,
        val detail: DailyReviewDetail? = null, val detailAt: Long? = null, val denied: Boolean = false)

    suspend fun open() = operation {
        val current = restore()
        val now = now()
        val recentAttempt = attemptedAt?.let { now >= it && now - it < BACKOFF_MS } == true
        if (!fresh(cache?.fetchedAt, now) || cache?.denied == true) {
            if (!recentAttempt) refreshLocked(current)
        }
    }

    suspend fun refresh() = operation {
        val current = currentDevice()
        // An explicit complete read may repair only this cache, not source history.
        refreshLocked(current)
    }

    suspend fun select(jobId: String) = operation(detailOperation = true) {
        val current = restore()
        val saved = cache ?: throw ServerAiHistoryFailure(ServerAiHistoryError.NOT_FOUND)
        if (saved.rows.none { it.jobId == jobId }) throw ServerAiHistoryFailure(ServerAiHistoryError.NOT_FOUND)
        mutableState.update { it.copy(selectedJobId = jobId, detailFailure = null) }
        if (saved.detail?.jobId == jobId && fresh(saved.detailAt, now())) return@operation
        selectedLocked(current, jobId)
    }

    suspend fun refreshSelection() = operation(detailOperation = true) {
        val job = mutableState.value.selectedJobId ?: return@operation
        selectedLocked(restore(), job)
    }

    fun closeSelection() { mutableState.update { it.copy(selectedJobId = null, detailFailure = null) } }

    private suspend fun restore(): String {
        val current = currentDevice()
        if (!loaded) {
            val saved = storage { persistence.read()?.let { decode(it, current) } }
            loaded = true
            cache = saved
        }
        checkDevice(current)
        validateRetained(now())
        checkDevice(current)
        adopt(cache)
        return current
    }

    private suspend fun validateRetained(observed: Long) {
        val saved = cache ?: return
        if (saved.fetchedAt > observed || saved.detailAt?.let { it > observed } == true) {
            cache = null; loaded = false
            mutableState.value = ServerDailyArchiveState()
            throw ServerAiHistoryFailure(ServerAiHistoryError.STORAGE)
        }
        val rows = saved.rows.filter { it.expiresAtMs > observed }
        val detail = saved.detail?.takeIf { it.expiresAtMs > observed && rows.any { row -> row.jobId == it.jobId } }
        if (rows.size != saved.rows.size || detail != saved.detail) {
            val pruned = saved.copy(rows = rows, detail = detail, detailAt = saved.detailAt.takeIf { detail != null })
            // Hide expiry before I/O, including when physical cleanup cannot commit.
            cache = pruned
            adopt(pruned)
            storage { persistence.write(encode(pruned)) }
        }
    }

    private suspend fun currentDevice(): String {
        val current = binding().also { check(Regex("[0-9a-f]{64}").matches(it)) }
        if (current != device) {
            device = current; loaded = false; cache = null; attemptedAt = null
            mutableState.value = ServerDailyArchiveState()
        }
        return current
    }

    private suspend fun checkDevice(expected: String) {
        if (binding() != expected) throw ServerAiHistoryFailure(ServerAiHistoryError.DEVICE_MISMATCH)
    }

    private suspend fun refreshLocked(current: String) {
        val observed = now()
        validateRetained(observed)
        attemptedAt = observed
        mutableState.update { it.copy(refreshing = true, failure = null) }
        val rows = withTimeoutOrNull(READ_TIMEOUT_MS) {
            val result = mutableListOf<DailyReviewSummary>()
            var query = DailyReviewQuery()
            var bytes = 0
            for (pageNumber in 1..MAX_PAGES) {
                val page = readPage(query, current)
                checkDevice(current)
                check(page.records.size <= 20)
                check(page.snapshotSequence >= query.afterSequence)
                check(query.snapshotSequence == null || page.snapshotSequence == query.snapshotSequence)
                if (result.size + page.records.size > MAX_ROWS) throw ServerAiHistoryFailure(ServerAiHistoryError.TOO_LARGE)
                bytes += HistoryJson.bytes(JsonArray(page.records.map { it.wire })).size
                if (bytes > MAX_CACHE_BYTES) throw ServerAiHistoryFailure(ServerAiHistoryError.TOO_LARGE)
                result += page.records
                if (!page.hasMore) {
                    check(result.map { it.jobId }.distinct().size == result.size)
                    check(result.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
                    return@withTimeoutOrNull result.toList()
                }
                val next = page.nextAfterSequence ?: error("invalid_daily_cursor")
                check(next > query.afterSequence && next < page.snapshotSequence)
                query = DailyReviewQuery(next, page.snapshotSequence)
            }
            throw ServerAiHistoryFailure(ServerAiHistoryError.TOO_LARGE)
        } ?: throw ServerAiHistoryFailure(ServerAiHistoryError.NETWORK)
        val finished = now()
        check(finished >= observed)
        val retained = rows.filter { it.expiresAtMs > finished }
        val priorDetail = cache?.detail?.takeIf { detail -> retained.any { it.jobId == detail.jobId } && detail.expiresAtMs > finished }
        priorDetail?.let { matches(retained.single { row -> row.jobId == it.jobId }, it) }
        commit(Cache(current, finished, retained, priorDetail, cache?.detailAt.takeIf { priorDetail != null }))
        loaded = true
    }

    private suspend fun selectedLocked(current: String, job: String) {
        val saved = cache ?: throw ServerAiHistoryFailure(ServerAiHistoryError.NOT_FOUND)
        val summary = saved.rows.singleOrNull { it.jobId == job } ?: throw ServerAiHistoryFailure(ServerAiHistoryError.NOT_FOUND)
        mutableState.update { it.copy(detailRefreshing = true, detailFailure = null) }
        val result = withTimeoutOrNull(READ_TIMEOUT_MS) { readDetail(job, current) }
            ?: throw ServerAiHistoryFailure(ServerAiHistoryError.NETWORK)
        checkDevice(current)
        val finished = now()
        validateRetained(finished)
        if (summary.expiresAtMs <= finished) throw ServerAiHistoryFailure(ServerAiHistoryError.NOT_FOUND)
        matches(summary, result)
        val retained = cache ?: throw ServerAiHistoryFailure(ServerAiHistoryError.NOT_FOUND)
        commit(retained.copy(detail = result, detailAt = finished))
    }

    private fun matches(summary: DailyReviewSummary, detail: DailyReviewDetail) {
        check(summary.jobId == detail.jobId && summary.reportDate == detail.reportDate && summary.zoneId == detail.zoneId)
        check(summary.storedAtMs == detail.storedAtMs && summary.expiresAtMs == detail.expiresAtMs)
        check(summary.model == detail.model && summary.summary == detail.summary)
    }

    private suspend fun commit(value: Cache) {
        checkDevice(value.device)
        storage { persistence.write(encode(value)) }
        checkDevice(value.device)
        cache = value
        adopt(value)
    }

    private fun adopt(value: Cache?) {
        val rows = value?.rows.orEmpty().sortedWith(compareByDescending<DailyReviewSummary> { it.reportDate }
            .thenByDescending { it.storedAtMs }.thenByDescending { it.sequence })
        mutableState.update { previous -> previous.copy(records = rows,
            fetchedAtMs = value?.fetchedAt?.takeIf { it > 0 && !value.denied },
            detail = value?.detail, detailFetchedAtMs = value?.detailAt,
            selectedJobId = previous.selectedJobId?.takeIf { job -> rows.any { it.jobId == job } },
            failure = if (value?.denied == true) ServerAiHistoryError.UNAUTHORIZED else previous.failure) }
    }

    private fun encode(value: Cache): String {
        val data = buildJsonObject {
            put("version", 1); put("device", value.device); put("fetchedAtMs", value.fetchedAt); put("denied", value.denied)
            put("records", JsonArray(value.rows.map { it.wire }))
            put("detail", value.detail?.wire ?: JsonNull)
            put("detailFetchedAtMs", value.detailAt?.let(::JsonPrimitive) ?: JsonNull)
        }
        return HistoryJson.bytes(data).also {
            if (it.size > MAX_CACHE_BYTES) throw ServerAiHistoryFailure(ServerAiHistoryError.TOO_LARGE)
        }.let(HistoryJson::decodeUtf8)
    }

    private fun decode(raw: String, expected: String): Cache? {
        val value = HistoryJson.parse(HistoryJson.encodeUtf8(raw), MAX_CACHE_BYTES)
        HistoryJson.fields(value, setOf("version", "device", "fetchedAtMs", "denied", "records", "detail", "detailFetchedAtMs"))
        check(HistoryJson.long(value.getValue("version")) == 1L)
        val device = HistoryJson.string(value.getValue("device")).also { check(Regex("[0-9a-f]{64}").matches(it)) }
        if (device != expected) return null
        val fetched = HistoryJson.long(value.getValue("fetchedAtMs"))
        val denied = HistoryJson.bool(value.getValue("denied"))
        check(fetched <= now())
        if (!denied) HistoryJson.time(fetched)
        val rows = HistoryJson.array(value.getValue("records")).also { check(it.size <= MAX_ROWS) }
            .map { DailyReviewCodec.summary(HistoryJson.obj(it), fetched) }
        check(rows.map { it.jobId }.distinct().size == rows.size)
        check(rows.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        val at = value.getValue("detailFetchedAtMs").let { if (it == JsonNull) null else HistoryJson.long(it).also(HistoryJson::time) }
        check(at == null || at <= now())
        val detail = value.getValue("detail").let {
            if (it == JsonNull) { check(at == null); null }
            else {
                check(at != null)
                val objectValue = HistoryJson.obj(it)
                val job = HistoryJson.string(objectValue.getValue("jobId"))
                DailyReviewCodec.detail(objectValue, job, at).also { detail -> matches(rows.single { row -> row.jobId == job }, detail) }
            }
        }
        check(!denied || rows.isEmpty() && detail == null)
        return Cache(device, fetched, rows, detail, at, denied)
    }

    private suspend fun operation(detailOperation: Boolean = false, work: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        try {
            withContext(dispatcher) {
                try { work() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val reason = reason(failure)
                    if (reason in setOf(ServerAiHistoryError.UNAUTHORIZED, ServerAiHistoryError.FORBIDDEN, ServerAiHistoryError.DEVICE_MISMATCH)) {
                        cache = null; loaded = false
                        mutableState.value = ServerDailyArchiveState(failure = reason)
                        if (reason != ServerAiHistoryError.DEVICE_MISMATCH) device?.let { key ->
                            try { storage { persistence.write(encode(Cache(key, 0, emptyList(), denied = true))) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { mutableState.value = ServerDailyArchiveState(failure = ServerAiHistoryError.STORAGE) }
                        }
                    } else mutableState.update {
                        if (detailOperation) it.copy(detailFailure = reason) else it.copy(failure = reason)
                    }
                } finally { mutableState.update { it.copy(refreshing = false, detailRefreshing = false) } }
            }
        } finally { mutex.unlock() }
    }

    private suspend fun <T> storage(work: suspend () -> T): T = try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ServerAiHistoryFailure) { throw failure }
        catch (_: Exception) { throw ServerAiHistoryFailure(ServerAiHistoryError.STORAGE) }

    private fun reason(error: Exception): ServerAiHistoryError = when (error) {
        is ServerAiHistoryFailure -> error.reason
        is ServerAiConnectionFailure -> when (error.reason) {
            ServerAiConnectionError.UNAUTHORIZED -> ServerAiHistoryError.UNAUTHORIZED
            ServerAiConnectionError.DEVICE_KEY -> ServerAiHistoryError.DEVICE_MISMATCH
            ServerAiConnectionError.STORAGE -> ServerAiHistoryError.STORAGE
            else -> ServerAiHistoryError.INVALID_RESPONSE
        }
        is IOException -> ServerAiHistoryError.NETWORK
        else -> ServerAiHistoryError.INVALID_RESPONSE
    }
    private fun now() = clock().also(HistoryJson::time)
    private fun fresh(at: Long?, now: Long): Boolean = at != null && at <= now &&
        at >= NightlyAdviceSchedule.windowEnd(Instant.ofEpochMilli(now).atZone(zone())).toInstant().toEpochMilli()

    private companion object {
        const val MAX_ROWS = 512
        const val MAX_PAGES = 26
        const val MAX_CACHE_BYTES = 1_048_576
        const val READ_TIMEOUT_MS = 45_000L
        const val BACKOFF_MS = 300_000L
    }
}
