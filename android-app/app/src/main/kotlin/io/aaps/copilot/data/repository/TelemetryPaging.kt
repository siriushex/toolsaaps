package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.TelemetryDao
import io.aaps.copilot.data.local.dao.TelemetrySampleLite
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity

internal data class TelemetryPagedScanStats(
    val callerTag: String,
    val keyCount: Int,
    val pageCount: Int,
    val rowCount: Int
)

internal suspend fun scanTelemetryByKeysPaged(
    telemetryDao: TelemetryDao,
    since: Long,
    through: Long = Long.MAX_VALUE,
    keys: Collection<String>,
    callerTag: String,
    auditLogger: AuditLogger? = null,
    pageSize: Int = DEFAULT_TELEMETRY_PAGE_SIZE,
    onPage: suspend (List<TelemetrySampleLite>) -> Unit
): TelemetryPagedScanStats {
    val normalizedKeys = keys
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
    if (normalizedKeys.isEmpty()) {
        return TelemetryPagedScanStats(
            callerTag = callerTag,
            keyCount = 0,
            pageCount = 0,
            rowCount = 0
        )
    }
    val stats = scanTelemetryPages(
        since = since,
        pageSize = pageSize.coerceIn(100, 5_000),
        fetchPage = { afterTimestamp, afterId, limit ->
            telemetryDao.betweenByKeysPage(
                since = since,
                through = through,
                keys = normalizedKeys,
                afterTimestamp = afterTimestamp,
                afterId = afterId,
                limit = limit
            )
        },
        getTimestamp = { it.timestamp },
        getId = { it.id },
        onPage = onPage
    )
    val result = TelemetryPagedScanStats(
        callerTag = callerTag,
        keyCount = normalizedKeys.size,
        pageCount = stats.pageCount,
        rowCount = stats.rowCount
    )
    if (result.rowCount >= TELEMETRY_LARGE_SCAN_ROW_THRESHOLD || result.pageCount >= TELEMETRY_LARGE_SCAN_PAGE_THRESHOLD) {
        auditLogger?.infoThrottled(
            throttleKey = "telemetry_paged_scan_large:$callerTag",
            intervalMs = TELEMETRY_LARGE_SCAN_LOG_INTERVAL_MS,
            message = "telemetry_paged_scan_large",
            metadata = mapOf(
                "caller" to callerTag,
                "since" to since,
                "through" to through,
                "keyCount" to result.keyCount,
                "pageCount" to result.pageCount,
                "rowCount" to result.rowCount,
                "pageSize" to pageSize
            )
        )
    }
    return result
}

internal suspend fun collectTelemetryEntitiesByKeysPaged(
    telemetryDao: TelemetryDao,
    since: Long,
    keys: Collection<String>,
    callerTag: String,
    auditLogger: AuditLogger? = null,
    pageSize: Int = DEFAULT_TELEMETRY_PAGE_SIZE
): Pair<List<TelemetrySampleEntity>, TelemetryPagedScanStats> {
    val rows = mutableListOf<TelemetrySampleEntity>()
    val stats = scanTelemetryByKeysPaged(
        telemetryDao = telemetryDao,
        since = since,
        keys = keys,
        callerTag = callerTag,
        auditLogger = auditLogger,
        pageSize = pageSize
    ) { page ->
        rows += page.map { it.toEntity() }
    }
    return rows to stats
}

internal suspend fun <T> scanTelemetryPages(
    since: Long,
    pageSize: Int,
    fetchPage: suspend (afterTimestamp: Long, afterId: String, pageSize: Int) -> List<T>,
    getTimestamp: (T) -> Long,
    getId: (T) -> String,
    onPage: suspend (List<T>) -> Unit
): PageScanStats {
    var afterTimestamp = if (since == Long.MIN_VALUE) Long.MIN_VALUE else since - 1L
    var afterId = ""
    var pageCount = 0
    var rowCount = 0
    while (true) {
        val page = fetchPage(afterTimestamp, afterId, pageSize)
        if (page.isEmpty()) break
        pageCount += 1
        rowCount += page.size
        onPage(page)
        val last = page.last()
        afterTimestamp = getTimestamp(last)
        afterId = getId(last)
        if (page.size < pageSize) break
    }
    return PageScanStats(pageCount = pageCount, rowCount = rowCount)
}

internal fun TelemetrySampleLite.toEntity(): TelemetrySampleEntity =
    TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = valueDouble,
        valueText = valueText,
        unit = unit,
        quality = quality
    )

internal data class PageScanStats(
    val pageCount: Int,
    val rowCount: Int
)

private const val DEFAULT_TELEMETRY_PAGE_SIZE = 1_000
private const val TELEMETRY_LARGE_SCAN_ROW_THRESHOLD = 5_000
private const val TELEMETRY_LARGE_SCAN_PAGE_THRESHOLD = 6
private const val TELEMETRY_LARGE_SCAN_LOG_INTERVAL_MS = 60L * 60L * 1000L
