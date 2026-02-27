package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity

class AuditLogger(
    private val auditLogDao: AuditLogDao,
    private val gson: Gson
) {

    suspend fun info(message: String, metadata: Map<String, Any?> = emptyMap()) {
        log("INFO", message, metadata)
    }

    suspend fun warn(message: String, metadata: Map<String, Any?> = emptyMap()) {
        log("WARN", message, metadata)
    }

    suspend fun error(message: String, metadata: Map<String, Any?> = emptyMap()) {
        log("ERROR", message, metadata)
    }

    private suspend fun log(level: String, message: String, metadata: Map<String, Any?>) {
        auditLogDao.insert(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                level = level,
                message = message,
                metadataJson = gson.toJson(metadata)
            )
        )
    }
}
