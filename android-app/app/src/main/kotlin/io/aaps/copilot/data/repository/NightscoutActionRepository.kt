package io.aaps.copilot.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.util.LinkedHashSet
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
        registerPendingCommand(command)?.let { return it }

        val settings = settingsStore.settings.first()
        val target = command.params["targetMmol"]?.toDoubleOrNull()
        val duration = command.params["durationMinutes"]?.toIntOrNull()
        val reason = command.params["reason"].orEmpty().ifBlank { "copilot_temp_target" }
        if (target == null || duration == null) {
            markFailed(command, "invalid_action_payload")
            return false
        }

        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.now().toString()
        val nightscoutUrl = settings.resolvedNightscoutUrl()
        var lastError = "missing_nightscout_url"
        if (nightscoutUrl.isNotBlank()) {
            val targetMgdl = UnitConverter.mmolToMgdl(target).toDouble()
            val nsApi = apiFactory.nightscoutApi(nightscoutUrl, settings.apiSecret)

            val request = NightscoutTreatmentRequest(
                createdAt = nowIso,
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
            val relayResult = sendLocalTreatmentFallbackChain(
                payload = LocalTreatmentPayload(
                    eventType = "Temporary Target",
                    basePayload = mapOf(
                        "created_at" to nowIso,
                        "duration" to duration,
                        "targetTop" to UnitConverter.mmolToMgdl(target).toDouble(),
                        "targetBottom" to UnitConverter.mmolToMgdl(target).toDouble(),
                        "targetTopMmol" to target,
                        "targetBottomMmol" to target,
                        "reason" to reason,
                        "notes" to "copilot:${command.idempotencyKey}"
                    ),
                    treatmentPayload = mapOf(
                        "eventType" to "Temporary Target",
                        "created_at" to nowIso,
                        "mills" to nowMs,
                        "date" to nowMs,
                        "duration" to duration,
                        "targetTop" to target,
                        "targetBottom" to target,
                        "units" to "mmol",
                        "reason" to reason,
                        "notes" to "copilot:${command.idempotencyKey}",
                        "_id" to buildTreatmentId("tt", command.idempotencyKey)
                    ),
                    idempotencyKey = command.idempotencyKey
                ),
                settings = settings
            )
            if (relayResult.delivered) {
                markSent(command, channel = relayResult.channel ?: "local_broadcast_fallback")
                auditLogger.warn(
                    "temp_target_sent_local_fallback",
                    mapOf(
                        "targetMmol" to target,
                        "duration" to duration,
                        "channel" to relayResult.channel,
                        "package" to settings.localCommandPackage,
                        "action" to settings.localCommandAction,
                        "attempts" to relayResult.attemptsSummary
                    )
                )
                return true
            }
            lastError = "$lastError|${relayResult.failureCode}"
        }

        markFailed(command, lastError)
        return false
    }

    suspend fun submitCarbs(command: ActionCommand): Boolean {
        registerPendingCommand(command)?.let { return it }

        val carbs = command.params["carbsGrams"]?.toDoubleOrNull()
            ?: command.params["carbs"]?.toDoubleOrNull()
            ?: command.params["grams"]?.toDoubleOrNull()
        if (carbs == null || carbs <= 0.0) {
            markFailed(command, "invalid_carbs_payload")
            return false
        }
        val reason = command.params["reason"].orEmpty().ifBlank { "copilot_carbs" }
        val settings = settingsStore.settings.first()
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.now().toString()

        val nightscoutUrl = settings.resolvedNightscoutUrl()
        var lastError = "missing_nightscout_url"
        if (nightscoutUrl.isNotBlank()) {
            val nsApi = apiFactory.nightscoutApi(nightscoutUrl, settings.apiSecret)
            val request = NightscoutTreatmentRequest(
                createdAt = nowIso,
                eventType = "Carb Correction",
                carbs = carbs,
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
                auditLogger.info("carbs_sent", mapOf("carbsGrams" to carbs, "reason" to reason))
                return true
            }
        }

        if (settings.localCommandFallbackEnabled) {
            val relayResult = sendLocalTreatmentFallbackChain(
                payload = LocalTreatmentPayload(
                    eventType = "Carb Correction",
                    basePayload = mapOf(
                        "created_at" to nowIso,
                        "carbs" to carbs,
                        "grams" to carbs,
                        "reason" to reason,
                        "notes" to "copilot:${command.idempotencyKey}"
                    ),
                    treatmentPayload = mapOf(
                        "eventType" to "Carb Correction",
                        "created_at" to nowIso,
                        "mills" to nowMs,
                        "date" to nowMs,
                        "carbs" to carbs,
                        "reason" to reason,
                        "notes" to "copilot:${command.idempotencyKey}",
                        "_id" to buildTreatmentId("carbs", command.idempotencyKey)
                    ),
                    idempotencyKey = command.idempotencyKey
                ),
                settings = settings
            )
            if (relayResult.delivered) {
                markSent(command, channel = relayResult.channel ?: "local_broadcast_fallback")
                auditLogger.warn(
                    "carbs_sent_local_fallback",
                    mapOf(
                        "carbsGrams" to carbs,
                        "channel" to relayResult.channel,
                        "package" to settings.localCommandPackage,
                        "action" to settings.localCommandAction,
                        "attempts" to relayResult.attemptsSummary
                    )
                )
                return true
            }
            lastError = "$lastError|${relayResult.failureCode}"
        }

        markFailed(command, lastError)
        return false
    }

    suspend fun countSentActionsLast6h(): Int {
        val since = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        return db.actionCommandDao().countByStatusSince(STATUS_SENT, since)
    }

    private suspend fun registerPendingCommand(command: ActionCommand): Boolean? {
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
        return null
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
        auditLogger.error(
            "action_delivery_failed",
            mapOf("reason" to reason, "commandId" to command.id, "type" to command.type)
        )
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

    private fun sendLocalTreatmentFallbackChain(
        payload: LocalTreatmentPayload,
        settings: AppSettings
    ): LocalFallbackResult {
        val treatmentJson = gson.toJson(payload.treatmentPayload)
        val treatmentsJson = gson.toJson(listOf(payload.treatmentPayload))
        val apiSecret = settings.apiSecret.trim().ifBlank { null }
        val enrichedPayload = payload.basePayload.toMutableMap().apply {
            put("idempotencyKey", payload.idempotencyKey)
        }
        val payloadJson = gson.toJson(enrichedPayload)

        val configuredPackage = settings.localCommandPackage.trim().ifBlank { null }
        val aapsPackage = firstInstalledPackage(
            listOfNotNull(
                configuredPackage,
                AAPS_PACKAGE_LEGACY,
                AAPS_PACKAGE_MODERN
            ).distinct()
        )
        val customAction = settings.localCommandAction.trim().ifBlank { DEFAULT_LOCAL_TREATMENT_ACTION }
        val channels = buildBroadcastChannels(
            aapsPackage = aapsPackage,
            customPackage = configuredPackage,
            customAction = customAction
        )

        val attempts = mutableListOf<String>()
        for (channel in channels) {
            val sent = runCatching {
                val intent = Intent(channel.action).apply {
                    setPackage(channel.packageName)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("eventType", payload.eventType)
                    putExtra("collection", "treatments")
                    putExtra("idempotencyKey", payload.idempotencyKey)
                    putExtra("payload", payloadJson)
                    putExtra("treatment", treatmentJson)
                    putExtra("treatments", treatmentsJson)
                    putExtra("data", treatmentsJson)
                    enrichedPayload.forEach { (key, value) ->
                        putTypedExtra(key, value)
                    }
                    payload.treatmentPayload.forEach { (key, value) ->
                        putTypedExtra(key, value)
                    }
                    if (apiSecret != null) {
                        putExtra("token", apiSecret)
                        putExtra("apiSecret", apiSecret)
                        putExtra("secret", apiSecret)
                    }
                }
                if (resolveReceiverCount(intent) == 0) {
                    false
                } else {
                    context.sendBroadcast(intent)
                    true
                }
            }.getOrElse { false }
            attempts += "${channel.name}=${if (sent) "sent" else "skip"}"
            if (sent) {
                return LocalFallbackResult(
                    delivered = true,
                    channel = channel.name,
                    attemptsSummary = attempts.joinToString(";")
                )
            }
        }

        val failureCode = if (attempts.any { it.endsWith("=skip") }) {
            "local_broadcast_no_receiver"
        } else {
            "local_broadcast_fallback_failed"
        }
        return LocalFallbackResult(
            delivered = false,
            failureCode = failureCode,
            attemptsSummary = attempts.joinToString(";")
        )
    }

    private fun buildBroadcastChannels(
        aapsPackage: String?,
        customPackage: String?,
        customAction: String
    ): List<BroadcastChannel> {
        val rawChannels = listOf(
            BroadcastChannel(
                name = "ns_emulator_treatments",
                action = ACTION_NS_EMULATOR,
                packageName = aapsPackage
            ),
            BroadcastChannel(
                name = "local_treatments",
                action = ACTION_LOCAL_TREATMENTS,
                packageName = aapsPackage
            ),
            BroadcastChannel(
                name = "custom_fallback",
                action = customAction,
                packageName = customPackage
            )
        )
        val dedupe = LinkedHashSet<String>()
        return rawChannels.filter { channel ->
            val key = "${channel.action}|${channel.packageName.orEmpty()}"
            dedupe.add(key)
        }
    }

    private fun firstInstalledPackage(candidates: List<String>): String? =
        candidates.firstOrNull(::isPackageInstalled)

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun resolveReceiverCount(intent: Intent): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryBroadcastReceivers(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            ).size
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(intent, 0).size
        }
    }.getOrDefault(0)

    private fun buildTreatmentId(prefix: String, idempotencyKey: String): String {
        val suffix = idempotencyKey.replace(Regex("[^a-zA-Z0-9_-]"), "").take(48)
        return "copilot-$prefix-$suffix"
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val DEFAULT_LOCAL_TREATMENT_ACTION = "info.nightscout.client.NEW_TREATMENT"
        const val ACTION_LOCAL_TREATMENTS = "info.nightscout.androidaps.action.LOCAL_TREATMENTS"
        const val ACTION_NS_EMULATOR = "com.eveningoutpost.dexdrip.NS_EMULATOR"
        const val AAPS_PACKAGE_LEGACY = "info.nightscout.androidaps"
        const val AAPS_PACKAGE_MODERN = "app.aaps"
    }

    private fun Intent.putTypedExtra(key: String, value: Any) {
        when (value) {
            is String -> putExtra(key, value)
            is Int -> putExtra(key, value)
            is Long -> putExtra(key, value)
            is Double -> putExtra(key, value)
            is Float -> putExtra(key, value)
            is Boolean -> putExtra(key, value)
            else -> putExtra(key, value.toString())
        }
    }

    private data class LocalTreatmentPayload(
        val eventType: String,
        val basePayload: Map<String, Any>,
        val treatmentPayload: Map<String, Any>,
        val idempotencyKey: String
    )

    private data class BroadcastChannel(
        val name: String,
        val action: String,
        val packageName: String?
    )

    private data class LocalFallbackResult(
        val delivered: Boolean,
        val channel: String? = null,
        val failureCode: String = "local_broadcast_fallback_failed",
        val attemptsSummary: String = ""
    )
}
