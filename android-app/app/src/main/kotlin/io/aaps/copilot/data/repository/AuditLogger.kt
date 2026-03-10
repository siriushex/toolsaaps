package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import java.util.concurrent.ConcurrentHashMap

class AuditLogger(
    private val auditLogDao: AuditLogDao,
    private val gson: Gson,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val throttledLogTimestamps = ConcurrentHashMap<String, Long>()

    suspend fun info(message: String, metadata: Map<String, Any?> = emptyMap()) {
        log("INFO", message, metadata)
    }

    suspend fun infoThrottled(
        throttleKey: String,
        intervalMs: Long,
        message: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        if (shouldLog(throttleKey = throttleKey, intervalMs = intervalMs)) {
            log("INFO", message, metadata)
        }
    }

    suspend fun warn(message: String, metadata: Map<String, Any?> = emptyMap()) {
        log("WARN", message, metadata)
    }

    suspend fun warnThrottled(
        throttleKey: String,
        intervalMs: Long,
        message: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        if (shouldLog(throttleKey = throttleKey, intervalMs = intervalMs)) {
            log("WARN", message, metadata)
        }
    }

    suspend fun error(message: String, metadata: Map<String, Any?> = emptyMap()) {
        log("ERROR", message, metadata)
    }

    private suspend fun log(level: String, message: String, metadata: Map<String, Any?>) {
        auditLogDao.insert(
            AuditLogEntity(
                timestamp = clock(),
                level = level,
                message = message,
                metadataJson = gson.toJson(metadata)
            )
        )
    }

    private fun shouldLog(throttleKey: String, intervalMs: Long): Boolean {
        if (intervalMs <= 0L) return true
        val now = clock()
        while (true) {
            val previous = throttledLogTimestamps[throttleKey]
            if (previous != null && (now - previous) < intervalMs) {
                return false
            }
            if (previous == null) {
                if (throttledLogTimestamps.putIfAbsent(throttleKey, now) == null) {
                    return true
                }
            } else if (throttledLogTimestamps.replace(throttleKey, previous, now)) {
                return true
            }
        }
    }
}
