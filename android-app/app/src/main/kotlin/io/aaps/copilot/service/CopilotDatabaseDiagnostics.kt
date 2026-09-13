package io.aaps.copilot.service

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CopilotDatabaseDiagnostics {
    private const val TAG = "CopilotDbDiagnostics"

    private val roomTables = listOf(
        "glucose_samples",
        "therapy_events",
        "forecasts",
        "blood_glucose_checks",
        "glucose_calibration_models",
        "rule_executions",
        "action_commands",
        "sync_state",
        "audit_logs",
        "baseline_points",
        "pattern_windows",
        "circadian_slot_stats",
        "circadian_transition_stats",
        "circadian_pattern_snapshots",
        "circadian_replay_slot_stats",
        "profile_estimates",
        "profile_segment_estimates",
        "isf_cr_snapshots",
        "isf_cr_evidence",
        "isf_cr_model_state",
        "physio_context_tags",
        "telemetry_samples",
        "uam_inference_events"
    )

    data class TableSummary(
        val table: String,
        val count: Long?,
        val bytes: Long?,
        val minTimestamp: Long?,
        val maxTimestamp: Long?
    )

    data class TelemetryKeySummary(
        val key: String,
        val count: Long,
        val minTimestamp: Long?,
        val maxTimestamp: Long?
    )

    data class Summary(
        val pageBytes: Long,
        val freeBytes: Long,
        val dbstatAvailable: Boolean,
        val tables: List<TableSummary>,
        val topTelemetryKeys: List<TelemetryKeySummary>
    )

    suspend fun logSummaryForDebug(
        db: CopilotDatabase,
        auditLogger: AuditLogger
    ): Summary = withContext(Dispatchers.IO) {
        val sqlDb = db.openHelper.writableDatabase
        val pageSize = longPragma(sqlDb, "page_size")
        val pageCount = longPragma(sqlDb, "page_count")
        val freeListCount = longPragma(sqlDb, "freelist_count")
        val sizeByName = queryDbstatBytes(sqlDb)
        val tableSummaries = roomTables.map { table ->
            val columns = tableColumns(sqlDb, table)
            TableSummary(
                table = table,
                count = queryCount(sqlDb, table),
                bytes = sizeByName[table],
                minTimestamp = if ("timestamp" in columns) queryTimestampBound(sqlDb, table, min = true) else null,
                maxTimestamp = if ("timestamp" in columns) queryTimestampBound(sqlDb, table, min = false) else null
            )
        }.sortedWith(
            compareByDescending<TableSummary> { it.bytes ?: -1L }
                .thenByDescending { it.count ?: -1L }
        )
        val summary = Summary(
            pageBytes = pageSize * pageCount,
            freeBytes = pageSize * freeListCount,
            dbstatAvailable = sizeByName.isNotEmpty(),
            tables = tableSummaries,
            topTelemetryKeys = queryTopTelemetryKeys(sqlDb)
        )
        Log.i(
            TAG,
            "summary pageBytes=${summary.pageBytes} freeBytes=${summary.freeBytes} dbstatAvailable=${summary.dbstatAvailable}"
        )
        sizeByName.entries
            .sortedByDescending { it.value }
            .take(20)
            .forEach { entry ->
                Log.i(TAG, "dbstat name=${entry.key} bytes=${entry.value}")
            }
        tableSummaries.forEach { table ->
            Log.i(
                TAG,
                "table=${table.table} count=${table.count ?: -1} bytes=${table.bytes ?: -1} minTs=${table.minTimestamp ?: -1} maxTs=${table.maxTimestamp ?: -1}"
            )
        }
        summary.topTelemetryKeys.forEach { key ->
            Log.i(
                TAG,
                "telemetry_key=${key.key} count=${key.count} minTs=${key.minTimestamp ?: -1} maxTs=${key.maxTimestamp ?: -1}"
            )
        }
        auditLogger.info(
            "db_table_diagnostics_completed",
            mapOf(
                "pageBytes" to summary.pageBytes,
                "freeBytes" to summary.freeBytes,
                "dbstatAvailable" to summary.dbstatAvailable,
                "topTables" to tableSummaries.take(12).joinToString(separator = ";") {
                    "${it.table}:count=${it.count ?: -1},bytes=${it.bytes ?: -1},minTs=${it.minTimestamp ?: -1},maxTs=${it.maxTimestamp ?: -1}"
                },
                "topTelemetryKeys" to summary.topTelemetryKeys.take(20).joinToString(separator = ";") {
                    "${it.key}:count=${it.count},minTs=${it.minTimestamp ?: -1},maxTs=${it.maxTimestamp ?: -1}"
                }
            )
        )
        summary
    }

    private fun longPragma(db: SupportSQLiteDatabase, pragma: String): Long {
        return db.query("PRAGMA $pragma").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun queryDbstatBytes(db: SupportSQLiteDatabase): Map<String, Long> {
        return runCatching {
            buildMap {
                db.query("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name").use { cursor ->
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), cursor.getLong(1))
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "dbstat unavailable: ${it.message ?: it::class.java.simpleName}")
            emptyMap()
        }
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
        return runCatching {
            buildSet {
                db.query("PRAGMA table_info($table)").use { cursor ->
                    while (cursor.moveToNext()) {
                        add(cursor.getString(1))
                    }
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun queryCount(db: SupportSQLiteDatabase, table: String): Long? {
        return runCatching {
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrNull()
    }

    private fun queryTimestampBound(db: SupportSQLiteDatabase, table: String, min: Boolean): Long? {
        val aggregate = if (min) "MIN" else "MAX"
        return runCatching {
            db.query("SELECT $aggregate(timestamp) FROM $table").use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun queryTopTelemetryKeys(db: SupportSQLiteDatabase): List<TelemetryKeySummary> {
        return runCatching {
            buildList {
                db.query(
                    "SELECT key, COUNT(*) AS cnt, MIN(timestamp), MAX(timestamp) " +
                        "FROM telemetry_samples GROUP BY key ORDER BY cnt DESC LIMIT 40"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        add(
                            TelemetryKeySummary(
                                key = cursor.getString(0),
                                count = cursor.getLong(1),
                                minTimestamp = if (cursor.isNull(2)) null else cursor.getLong(2),
                                maxTimestamp = if (cursor.isNull(3)) null else cursor.getLong(3)
                            )
                        )
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "top telemetry key query failed: ${it.message ?: it::class.java.simpleName}")
            emptyList()
        }
    }
}
