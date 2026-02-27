package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import kotlinx.coroutines.flow.first

class NightscoutActionRepository(
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    suspend fun submitTempTarget(command: ActionCommand): Boolean {
        val existing = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
        if (existing != null) {
            auditLogger.info("action_deduplicated", mapOf("idempotencyKey" to command.idempotencyKey))
            return existing.status == STATUS_SENT
        }

        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = command.id,
                timestamp = System.currentTimeMillis(),
                type = command.type,
                payloadJson = gson.toJson(command.params),
                safetyJson = gson.toJson(command.safetySnapshot),
                idempotencyKey = command.idempotencyKey,
                status = STATUS_PENDING
            )
        )

        val settings = settingsStore.settings.first()
        if (settings.nightscoutUrl.isBlank()) {
            markFailed(command, "missing_nightscout_url")
            return false
        }

        val target = command.params["targetMmol"]?.toDoubleOrNull()
        val duration = command.params["durationMinutes"]?.toIntOrNull()
        val reason = command.params["reason"].orEmpty()
        if (target == null || duration == null) {
            markFailed(command, "invalid_action_payload")
            return false
        }

        val targetMgdl = UnitConverter.mmolToMgdl(target).toDouble()
        val nsApi = apiFactory.nightscoutApi(settings)

        val request = NightscoutTreatmentRequest(
            createdAt = Instant.now().toString(),
            eventType = "Temporary Target",
            duration = duration,
            targetTop = targetMgdl,
            targetBottom = targetMgdl,
            reason = reason,
            notes = "copilot:${command.idempotencyKey}"
        )

        return runCatching {
            nsApi.postTreatment(request)
        }.onSuccess {
            db.actionCommandDao().upsert(
                ActionCommandEntity(
                    id = command.id,
                    timestamp = System.currentTimeMillis(),
                    type = command.type,
                    payloadJson = gson.toJson(command.params),
                    safetyJson = gson.toJson(command.safetySnapshot),
                    idempotencyKey = command.idempotencyKey,
                    status = STATUS_SENT
                )
            )
            auditLogger.info("temp_target_sent", mapOf("targetMmol" to target, "duration" to duration, "reason" to reason))
        }.onFailure { error ->
            markFailed(command, error.message ?: "unknown_error")
        }.isSuccess
    }

    suspend fun countSentActionsLast6h(): Int {
        val since = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        return db.actionCommandDao().countByStatusSince(STATUS_SENT, since)
    }

    private suspend fun markFailed(command: ActionCommand, reason: String) {
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = command.id,
                timestamp = System.currentTimeMillis(),
                type = command.type,
                payloadJson = gson.toJson(command.params),
                safetyJson = gson.toJson(command.safetySnapshot),
                idempotencyKey = command.idempotencyKey,
                status = STATUS_FAILED
            )
        )
        auditLogger.error("temp_target_failed", mapOf("reason" to reason, "commandId" to command.id))
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
