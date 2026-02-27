package io.aaps.copilot.data.repository

import android.content.Context
import android.content.Intent
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
    private val context: Context,
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
        val target = command.params["targetMmol"]?.toDoubleOrNull()
        val duration = command.params["durationMinutes"]?.toIntOrNull()
        val reason = command.params["reason"].orEmpty()
        if (target == null || duration == null) {
            markFailed(command, "invalid_action_payload")
            return false
        }

        var lastError = "missing_nightscout_url"
        if (settings.nightscoutUrl.isNotBlank()) {
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

            val nsSuccess = runCatching {
                nsApi.postTreatment(request)
            }.onFailure { error ->
                lastError = error.message ?: "nightscout_unknown_error"
            }.isSuccess

            if (nsSuccess) {
                markSent(command, channel = "nightscout")
                auditLogger.info("temp_target_sent", mapOf("targetMmol" to target, "duration" to duration, "reason" to reason))
                return true
            }
        }

        if (settings.localCommandFallbackEnabled) {
            val relaySuccess = sendLocalTempTargetFallback(
                targetMmol = target,
                durationMinutes = duration,
                reason = reason,
                settings = settings,
                idempotencyKey = command.idempotencyKey
            )
            if (relaySuccess) {
                markSent(command, channel = "local_broadcast_fallback")
                auditLogger.warn(
                    "temp_target_sent_local_fallback",
                    mapOf(
                        "targetMmol" to target,
                        "duration" to duration,
                        "package" to settings.localCommandPackage,
                        "action" to settings.localCommandAction
                    )
                )
                return true
            }
            lastError = "$lastError|local_broadcast_fallback_failed"
        }

        markFailed(command, lastError)
        return false
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

    private suspend fun markSent(command: ActionCommand, channel: String) {
        val payload = command.params.toMutableMap().apply {
            put("deliveryChannel", channel)
        }
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = command.id,
                timestamp = System.currentTimeMillis(),
                type = command.type,
                payloadJson = gson.toJson(payload),
                safetyJson = gson.toJson(command.safetySnapshot),
                idempotencyKey = command.idempotencyKey,
                status = STATUS_SENT
            )
        )
    }

    private fun sendLocalTempTargetFallback(
        targetMmol: Double,
        durationMinutes: Int,
        reason: String,
        settings: io.aaps.copilot.config.AppSettings,
        idempotencyKey: String
    ): Boolean {
        val action = settings.localCommandAction.trim().ifBlank { DEFAULT_LOCAL_TREATMENT_ACTION }
        val packageName = settings.localCommandPackage.trim().ifBlank { null }
        val targetMgdl = UnitConverter.mmolToMgdl(targetMmol).toDouble()
        val nowIso = Instant.now().toString()
        val payload = mapOf(
            "created_at" to nowIso,
            "eventType" to "Temporary Target",
            "duration" to durationMinutes.toString(),
            "targetTop" to targetMgdl.toString(),
            "targetBottom" to targetMgdl.toString(),
            "targetTopMmol" to targetMmol.toString(),
            "targetBottomMmol" to targetMmol.toString(),
            "reason" to reason,
            "notes" to "copilot:$idempotencyKey",
            "idempotencyKey" to idempotencyKey
        )
        val payloadJson = gson.toJson(payload)

        return runCatching {
            if (packageName != null) {
                context.packageManager.getPackageInfo(packageName, 0)
            }
            val intent = Intent(action).apply {
                setPackage(packageName)
                putExtra("eventType", "Temporary Target")
                putExtra("created_at", nowIso)
                putExtra("duration", durationMinutes)
                putExtra("targetTop", targetMgdl)
                putExtra("targetBottom", targetMgdl)
                putExtra("targetTopMmol", targetMmol)
                putExtra("targetBottomMmol", targetMmol)
                putExtra("reason", reason)
                putExtra("notes", "copilot:$idempotencyKey")
                putExtra("idempotencyKey", idempotencyKey)
                putExtra("payload", payloadJson)
                putExtra("treatment", payloadJson)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        }.isSuccess
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val DEFAULT_LOCAL_TREATMENT_ACTION = "info.nightscout.client.NEW_TREATMENT"
    }
}
