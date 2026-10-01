package io.aaps.copilot.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import io.aaps.copilot.domain.target.EatingSoonPolicy
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.domain.target.TargetIntent
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.security.TherapyActionTransportGate
import io.aaps.copilot.security.TherapyActionsNotArmedException
import io.aaps.copilot.service.ApiFactory
import io.aaps.copilot.service.isOwnedLocalNightscoutEndpoint
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NightscoutActionRepository(
    private val context: Context,
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val carbsSendThrottle: CarbsSendThrottle,
    private val tempTargetSendThrottle: TempTargetSendThrottle,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) : AapsCarbGateway {

    suspend fun normalizeLegacyBlockedCommands(lookbackMs: Long = 7L * 24 * 60 * 60 * 1000): Int {
        val since = System.currentTimeMillis() - lookbackMs
        val failedLogs = db.auditLogDao().recentByMessage(
            message = "action_delivery_failed",
            sinceTs = since,
            limit = 500
        )
        if (failedLogs.isEmpty()) return 0
        val metadataType = object : TypeToken<Map<String, String>>() {}.type
        val blockedIds = failedLogs.mapNotNull { row ->
            val meta = runCatching { gson.fromJson<Map<String, String>>(row.metadataJson, metadataType) }.getOrNull()
                ?: return@mapNotNull null
            val reason = meta["reason"].orEmpty()
            val commandId = meta["commandId"].orEmpty()
            if (commandId.isBlank()) return@mapNotNull null
            if (reason == "temp_target_rate_limit_30m" || reason == "carbs_rate_limit_30m") {
                commandId
            } else {
                null
            }
        }.distinct()
        if (blockedIds.isEmpty()) return 0
        val updated = db.actionCommandDao().updateStatusByIds(
            ids = blockedIds,
            currentStatus = STATUS_FAILED,
            newStatus = STATUS_BLOCKED
        )
        if (updated > 0) {
            auditLogger.info(
                "action_status_normalized",
                mapOf(
                    "normalizedTo" to STATUS_BLOCKED,
                    "updated" to updated,
                    "reason" to "legacy_rate_limit_failed"
                )
            )
        }
        return updated
    }

    suspend fun submitTempTarget(
        command: ActionCommand,
        deliveryGuard: (suspend () -> String?)? = null
    ): Boolean = TEMP_TARGET_WRITE_MUTEX.withLock {
        val registration = registerPendingCommand(command)
        when (registration.kind) {
            PendingCommandRegistrationKind.EXISTING_SENT -> return@withLock true
            PendingCommandRegistrationKind.EXISTING_UNCERTAIN_OR_TERMINAL -> return@withLock false
            PendingCommandRegistrationKind.RECONCILIATION_RETRY -> return@withLock false
            PendingCommandRegistrationKind.FIRST_RESERVED -> Unit
        }
        val registeredCommand = registration.command
        val settings = settingsStore.settings.first()
        if (
            blockIfTherapyActionsNotArmed(
                command = registeredCommand,
                settings = settings,
                preservePendingMeal = false
            )
        ) return@withLock false
        automaticTargetOwnershipBlockReasonStatic(
            mode = settings.targetManagerMode,
            idempotencyKey = registeredCommand.idempotencyKey
        )?.let { reason ->
            blockLegacyAutomaticCommand(registeredCommand, reason)
            return@withLock false
        }
        deliverFirstReservedTempTarget(registeredCommand, deliveryGuard)
    }

    suspend fun submitOrRetryTempTarget(
        command: ActionCommand,
        deliveryGuard: (suspend () -> String?)? = null
    ): Boolean {
        val retryOwner = CompletableDeferred<Unit>()
        val activeOwner = TEMP_TARGET_RETRY_OWNERS.putIfAbsent(command.idempotencyKey, retryOwner)
        if (activeOwner != null) {
            activeOwner.await()
            return TEMP_TARGET_WRITE_MUTEX.withLock {
                db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)?.status == STATUS_SENT
            }
        }
        try {
            return TEMP_TARGET_WRITE_MUTEX.withLock {
                val existing = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
                if (existing?.status == STATUS_SENT) return@withLock true
                if (existing != null && existing.status !in setOf(STATUS_PENDING, STATUS_FAILED)) {
                    return@withLock false
                }
                val settings = settingsStore.settings.first()
                if (
                    blockIfTherapyActionsNotArmed(
                        command = command,
                        settings = settings,
                        preserveExistingCommand = existing != null
                    )
                ) return@withLock false
                automaticTargetOwnershipBlockReasonStatic(
                    mode = settings.targetManagerMode,
                    idempotencyKey = command.idempotencyKey
                )?.let { reason ->
                    blockLegacyAutomaticCommand(
                        command = command,
                        reason = reason,
                        preserveExistingCommand = existing != null
                    )
                    return@withLock false
                }
                deliveryGuard?.invoke()?.let { reason ->
                    auditLogger.warn(
                        "target_manager_dispatch_preflight_blocked",
                        mapOf("reason" to reason, "idempotencyKey" to command.idempotencyKey)
                    )
                    return@withLock false
                }
                val registration = registerPendingCommand(
                    command = command,
                    allowReconciliationRetry = true
                )
                when (registration.kind) {
                    PendingCommandRegistrationKind.EXISTING_SENT -> return@withLock true
                    PendingCommandRegistrationKind.EXISTING_UNCERTAIN_OR_TERMINAL -> return@withLock false
                    PendingCommandRegistrationKind.FIRST_RESERVED,
                    PendingCommandRegistrationKind.RECONCILIATION_RETRY -> Unit
                }
                deliverRegisteredTempTarget(
                    command = registration.command,
                    deliveryGuard = deliveryGuard,
                    isFirstReservation = registration.kind == PendingCommandRegistrationKind.FIRST_RESERVED
                )
            }
        } finally {
            retryOwner.complete(Unit)
            TEMP_TARGET_RETRY_OWNERS.remove(command.idempotencyKey, retryOwner)
        }
    }

    suspend fun reconcileTempTargetDelivery(idempotencyKey: String): TempTargetDeliveryReconciliation {
        val local = db.actionCommandDao().byIdempotencyKey(idempotencyKey)
        if (local?.status == STATUS_SENT) return TempTargetDeliveryReconciliation.SENT

        val settings = settingsStore.settings.first()
        val targets = nightscoutWriteTargets(settings)
        if (targets.isEmpty()) return TempTargetDeliveryReconciliation.UNKNOWN
        val expectedNote = "copilot:$idempotencyKey"
        var everyTargetChecked = true
        for (targetUrl in targets) {
            val treatments = try {
                apiFactory.nightscoutApi(targetUrl, settings).getTreatments(
                    mapOf(
                        "find[notes]" to expectedNote,
                        "count" to "10"
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Throwable) {
                everyTargetChecked = false
                emptyList()
            }
            if (treatments.any { it.notes == expectedNote }) {
                return TempTargetDeliveryReconciliation.SENT
            }
        }
        return if (everyTargetChecked) {
            TempTargetDeliveryReconciliation.CONFIRMED_ABSENT
        } else {
            TempTargetDeliveryReconciliation.UNKNOWN
        }
    }

    private suspend fun deliverRegisteredTempTarget(
        command: ActionCommand,
        deliveryGuard: (suspend () -> String?)? = null,
        isFirstReservation: Boolean = true
    ): Boolean {
        val target = command.params["targetMmol"]?.toDoubleOrNull()
        val duration = command.params["durationMinutes"]?.toIntOrNull()
        val reason = command.params["reason"].orEmpty().ifBlank { "copilot_temp_target" }
        if (target == null || duration == null) {
            markFailed(command, "invalid_action_payload")
            return false
        }

        val nowMs = System.currentTimeMillis()
        val throttle = tempTargetSendThrottle.evaluate(
            nowMs = nowMs,
            idempotencyKey = command.idempotencyKey,
            targetMmol = target,
            actionReason = reason,
            targetIntent = command.params["targetIntent"]
                ?.let { raw -> runCatching { TargetIntent.valueOf(raw) }.getOrNull() }
        )
        if (!throttle.allowed) {
            markBlocked(command, "temp_target_rate_limit_30m")
            auditLogger.warn(
                "temp_target_send_blocked_rate_limit",
                mapOf(
                    "reason" to throttle.reason,
                    "waitMinutes" to throttle.waitMinutes,
                    "lastSentTs" to (throttle.lastSentTs ?: 0L),
                    "lastTargetMmol" to (throttle.lastTargetMmol ?: -1.0),
                    "requestedTargetMmol" to target,
                    "idempotencyKey" to command.idempotencyKey
                )
            )
            return false
        }
        val settings = settingsStore.settings.first()
        if (
            blockIfTherapyActionsNotArmed(
                command = command,
                settings = settings,
                preservePendingMeal = false
            )
        ) {
            return false
        }
        automaticTargetOwnershipBlockReasonStatic(
            mode = settings.targetManagerMode,
            idempotencyKey = command.idempotencyKey
        )?.let { reason ->
            blockLegacyAutomaticCommand(command, reason)
            return false
        }
        deliveryGuard?.invoke()?.let { reason ->
            val durableReason = "managed_preflight:$reason"
            if (isFirstAllowlistedEatingSoonRefusal(command, isFirstReservation, durableReason)) {
                markBlocked(command, durableReason)
            } else {
                markFailed(command, durableReason)
            }
            auditLogger.warn(
                "target_manager_delivery_preflight_blocked",
                mapOf("reason" to reason, "idempotencyKey" to command.idempotencyKey)
            )
            return false
        }
        val nowIso = Instant.now().toString()
        val targetMgdl = UnitConverter.mmolToMgdl(target).toDouble()
        val request = NightscoutTreatmentRequest(
            createdAt = nowIso,
            eventType = "Temporary Target",
            duration = duration,
            targetTop = targetMgdl,
            targetBottom = targetMgdl,
            reason = reason.take(256),
            notes = "copilot:${command.idempotencyKey}"
        )
        val failurePolicy = tempTargetFailurePolicyStatic(command.idempotencyKey)
        val requiresReconciliation = failurePolicy == TempTargetFailurePolicy.RECONCILE_UNKNOWN

        val nightscoutTargets = nightscoutWriteTargets(settings)
        var lastError = if (nightscoutTargets.isEmpty()) "missing_nightscout_url" else "nightscout_post_failed"
        if (
            nightscoutTargets.isEmpty() &&
            isFirstAllowlistedEatingSoonRefusal(command, isFirstReservation, lastError)
        ) {
            markBlocked(command, lastError)
            return false
        }
        for (targetUrl in nightscoutTargets) {
            val nsSuccess = try {
                if (isLocalNightscoutTarget(targetUrl, settings)) {
                    val localDeliverySettings = settingsStore.settings.first()
                    if (
                        blockIfTherapyActionsNotArmed(
                            command = command,
                            settings = localDeliverySettings,
                            preservePendingMeal = false
                        )
                    ) {
                        return false
                    }
                    // Route classification and its credentials must share one settings snapshot.
                    val nsApi = apiFactory.nightscoutApi(targetUrl, settings)
                    nsApi.postTreatment(request)
                } else {
                    val nsApi = apiFactory.nightscoutApi(targetUrl, settings)
                    TherapyActionTransportGate.withArmedLease(
                        verifyPersistedState = {
                            settingsStore.settings.first().therapyActionsArmed
                        }
                    ) {
                        nsApi.postTreatment(request)
                    }
                }
                true
            } catch (_: TherapyActionsNotArmedException) {
                markBlocked(command, "therapy_actions_not_armed")
                return false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Throwable) {
                lastError = error.message ?: "nightscout_unknown_error"
                if (requiresReconciliation) {
                    auditLogger.warn(
                        "target_manager_delivery_unknown",
                        mapOf(
                            "idempotencyKey" to command.idempotencyKey,
                            "failureType" to error.javaClass.simpleName
                        )
                    )
                    throw TempTargetDeliveryUnknownException(
                        message = "Guarded temporary target delivery requires reconciliation",
                        cause = error
                    )
                }
                false
            }

            if (nsSuccess) {
                val channel = if (isLocalNightscoutTarget(targetUrl, settings)) {
                    "nightscout_local"
                } else {
                    "nightscout"
                }
                markSent(command, channel = channel)
                auditLogger.info(
                    "temp_target_sent",
                    mapOf(
                        "targetMmol" to target,
                        "duration" to duration,
                        "reason" to reason,
                        "channel" to channel,
                        "targetUrl" to targetUrl
                    )
                )
                return true
            }
        }

        if (settings.localCommandFallbackEnabled && !requiresReconciliation) {
            val relayResult = try {
                TherapyActionTransportGate.withArmedLease(
                    verifyPersistedState = {
                        settingsStore.settings.first().therapyActionsArmed
                    }
                ) {
                    sendLocalTreatmentFallbackChain(
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
                }
            } catch (_: TherapyActionsNotArmedException) {
                markBlocked(command, "therapy_actions_not_armed")
                return false
            }
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

    private suspend fun deliverFirstReservedTempTarget(
        command: ActionCommand,
        deliveryGuard: (suspend () -> String?)?
    ): Boolean {
        return deliverRegisteredTempTarget(command, deliveryGuard)
    }

    private suspend fun blockLegacyAutomaticCommand(
        command: ActionCommand,
        reason: String,
        preserveExistingCommand: Boolean = false
    ) {
        val existing = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
        val blockedCommand = existing?.let { command.copy(id = it.id) } ?: command
        if (existing?.status != STATUS_SENT && !preserveExistingCommand) {
            markBlocked(blockedCommand, reason)
        } else {
            auditLogger.warn(
                "action_delivery_blocked",
                mapOf(
                    "reason" to reason,
                    "commandId" to blockedCommand.id,
                    "type" to blockedCommand.type,
                    "preservedStatus" to existing?.status.orEmpty()
                )
            )
        }
    }

    private suspend fun blockIfTherapyActionsNotArmed(
        command: ActionCommand,
        settings: AppSettings,
        preserveExistingCommand: Boolean = false,
        preservePendingMeal: Boolean = true
    ): Boolean {
        val reason = therapyActionBootstrapBlockReasonStatic(settings.therapyActionsArmed)
            ?: return false
        val existing = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
        val blockedCommand = existing?.let { command.copy(id = it.id) } ?: command
        // Disarming cannot prove that an earlier uncertain meal POST did not commit.
        val preserveUnknownMeal = preservePendingMeal &&
            command.idempotencyKey.startsWith("manual:meal:") &&
            existing?.status == STATUS_PENDING
        if (existing?.status != STATUS_SENT && !preserveExistingCommand && !preserveUnknownMeal) {
            markBlocked(blockedCommand, reason)
        }
        auditLogger.warn(
            "action_delivery_blocked",
            mapOf(
                "reason" to reason,
                "commandId" to blockedCommand.id,
                "type" to blockedCommand.type,
                "preservedStatus" to existing?.status.orEmpty()
            )
        )
        return true
    }

    suspend fun submitManualMealTarget(
        command: ActionCommand,
        deliveryGuard: suspend () -> String?
    ): EatingSoonResult {
        if (command.type != "temp_target" || !command.idempotencyKey.startsWith("manual:meal:") ||
            !command.idempotencyKey.endsWith(":eating-soon")) {
            return EatingSoonResult(MealDeliveryStatus.BLOCKED, "invalid_meal_identity")
        }
        var blockReason: String? = null
        val sent = try {
            submitTempTarget(command) { deliveryGuard().also { blockReason = it } }
        } catch (_: TempTargetDeliveryUnknownException) {
            false
        }
        if (sent) return EatingSoonResult(MealDeliveryStatus.SENT)
        val record = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
        return manualMealTargetResult(record, blockReason)
    }

    private fun manualMealTargetResult(record: ActionCommandEntity?, blockReason: String? = null): EatingSoonResult {
        return when (record?.status) {
            STATUS_SENT -> EatingSoonResult(MealDeliveryStatus.SENT)
            STATUS_BLOCKED, STATUS_FAILED -> EatingSoonResult(
                MealDeliveryStatus.BLOCKED,
                canonicalEatingSoonFailureCode(blockReason) ?: storedEatingSoonFailureCode(record) ?: "target_not_sent"
            )
            else -> EatingSoonResult(MealDeliveryStatus.UNKNOWN, "delivery_unconfirmed")
        }
    }

    suspend fun manualCarbBlockReason(command: ActionCommand): String? {
        if (command.type != "carbs" || !command.idempotencyKey.startsWith("manual:meal:")) return null
        val record = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey) ?: return null
        if (!sameManualCarbPayload(record, command)) return "submission_changed"
        if (record.status != STATUS_BLOCKED) return null
        return runCatching {
            gson.fromJson(record.payloadJson, com.google.gson.JsonObject::class.java)
                .get("carbBlockReason")?.asString
        }.getOrNull()?.takeIf { it in setOf("carbs_rate_limit_30m", "therapy_actions_not_armed") }
            ?: "carbs_blocked"
    }

    suspend fun submitCarbs(command: ActionCommand): Boolean {
        val guardedMeal = command.idempotencyKey.startsWith("manual:meal:")
        val settings = settingsStore.settings.first()
        if (guardedMeal) {
            val prior = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
            if (prior != null && !sameManualCarbPayload(prior, command)) return false
            if (prior?.status == STATUS_SENT) return true
        }
        if (blockIfTherapyActionsNotArmed(command, settings)) return false
        when (registerPendingCommand(command).kind) {
            PendingCommandRegistrationKind.FIRST_RESERVED -> Unit
            PendingCommandRegistrationKind.EXISTING_SENT -> return true
            PendingCommandRegistrationKind.EXISTING_UNCERTAIN_OR_TERMINAL,
            PendingCommandRegistrationKind.RECONCILIATION_RETRY -> return false
        }

        val carbs = command.params["carbsGrams"]?.toDoubleOrNull()
            ?: command.params["carbs"]?.toDoubleOrNull()
            ?: command.params["grams"]?.toDoubleOrNull()
        if (carbs == null || !carbs.isFinite() || carbs <= 0.0) {
            markFailed(command, "invalid_carbs_payload")
            return false
        }
        val safetyCapGrams = io.aaps.copilot.domain.nutrition.MealCarbLimits.submissionMaximum(
            command.idempotencyKey, settings.carbComputationMaxGrams
        )
        if (carbs > safetyCapGrams) {
            markFailed(command, "carbs_above_safety_cap")
            auditLogger.warn(
                "carbs_send_blocked_safety_cap",
                mapOf(
                    "requestedCarbsGrams" to carbs,
                    "safetyCapGrams" to safetyCapGrams,
                    "idempotencyKey" to command.idempotencyKey
                )
            )
            return false
        }
        val reason = command.params["reason"].orEmpty().ifBlank { "copilot_carbs" }
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.now().toString()
        val throttle = carbsSendThrottle.evaluate(nowMs)
        if (!throttle.allowed) {
            markBlocked(command, "carbs_rate_limit_30m")
            auditLogger.warn(
                "carbs_send_blocked_rate_limit",
                mapOf(
                    "waitMinutes" to throttle.waitMinutes,
                    "lastSentTs" to (throttle.lastSentTs ?: 0L),
                    "idempotencyKey" to command.idempotencyKey
                )
            )
            return false
        }

        val request = NightscoutTreatmentRequest(
            createdAt = nowIso,
            eventType = "Carb Correction",
            carbs = carbs,
            reason = reason,
            notes = "copilot:${command.idempotencyKey}"
        )

        var deliverySettings = settingsStore.settings.first()
        if (blockIfTherapyActionsNotArmed(command, deliverySettings)) return false
        val nightscoutTargets = nightscoutWriteTargets(deliverySettings)
        var lastError = if (nightscoutTargets.isEmpty()) "missing_nightscout_url" else "nightscout_post_failed"
        for (targetUrl in nightscoutTargets) {
            deliverySettings = settingsStore.settings.first()
            if (blockIfTherapyActionsNotArmed(command, deliverySettings)) return false
            val nsSuccess = runCatching {
                val nsApi = apiFactory.nightscoutApi(targetUrl, deliverySettings)
                if (isLocalNightscoutTarget(targetUrl, deliverySettings)) {
                    nsApi.postTreatment(request)
                } else {
                    TherapyActionTransportGate.withArmedLease(
                        verifyPersistedState = {
                            settingsStore.settings.first().therapyActionsArmed
                        }
                    ) {
                        nsApi.postTreatment(request)
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException || error is Error) throw error
                if (guardedMeal) {
                    if (error !is Exception) throw error
                    if (error is TherapyActionsNotArmedException) {
                        markBlocked(command, "therapy_actions_not_armed")
                    } else {
                        // A committed POST can lose its response. Keep PENDING; never send this meal again.
                        auditLogger.warn("manual_meal_delivery_unknown", mapOf(
                            "idempotencyKey" to command.idempotencyKey,
                            "failureType" to error.javaClass.simpleName
                        ))
                    }
                    return false
                }
                lastError = error.message ?: "nightscout_unknown_error"
            }.isSuccess
            if (nsSuccess) {
                val channel = if (isLocalNightscoutTarget(targetUrl, deliverySettings)) {
                    "nightscout_local"
                } else {
                    "nightscout"
                }
                markSent(command, channel = channel)
                auditLogger.info(
                    "carbs_sent",
                    mapOf(
                        "carbsGrams" to carbs,
                        "reason" to reason,
                        "channel" to channel,
                        "targetUrl" to targetUrl
                    )
                )
                return true
            }
        }

        deliverySettings = settingsStore.settings.first()
        if (blockIfTherapyActionsNotArmed(command, deliverySettings)) return false
        if (deliverySettings.localCommandFallbackEnabled && !guardedMeal) {
            val relayResult = try {
                TherapyActionTransportGate.withArmedLease(
                    verifyPersistedState = {
                        settingsStore.settings.first().therapyActionsArmed
                    }
                ) {
                    sendLocalTreatmentFallbackChain(
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
                        settings = deliverySettings
                    )
                }
            } catch (_: TherapyActionsNotArmedException) {
                markBlocked(command, "therapy_actions_not_armed")
                return false
            }
            if (relayResult.delivered) {
                markSent(command, channel = relayResult.channel ?: "local_broadcast_fallback")
                auditLogger.warn(
                    "carbs_sent_local_fallback",
                    mapOf(
                        "carbsGrams" to carbs,
                        "channel" to relayResult.channel,
                        "package" to deliverySettings.localCommandPackage,
                        "action" to deliverySettings.localCommandAction,
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

    override suspend fun postCarbEntry(
        tsMs: Long,
        grams: Double,
        note: String
    ): Result<String> {
        val settings = settingsStore.settings.first()
        return postUamCarbEntryStatic(
            settings = settings,
            apiFactory = apiFactory,
            tsMs = tsMs,
            grams = grams,
            note = note,
            deliveryGuard = {
                therapyActionBootstrapBlockReasonStatic(
                    settingsStore.settings.first().therapyActionsArmed
                )
            }
        )
    }

    override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
        return fetchUamCarbEntriesStatic(
            settings = settingsStore.settings.first(),
            apiFactory = apiFactory,
            sinceTsMs = sinceTsMs
        )
    }

    suspend fun countSentActionsLast6h(nowTs: Long): Int {
        val window = LocalTargetSafetyTimeWindow.at(nowTs)
        return db.actionCommandDao().countByStatusBetweenExcludingTwoPrefixes(
            status = STATUS_SENT,
            since = window.actionSinceInclusive,
            through = window.nowTs,
            excludedPrefix1 = "$MANUAL_IDEMPOTENCY_PREFIX%",
            excludedPrefix2 = "$KEEPALIVE_IDEMPOTENCY_PREFIX%"
        )
    }

    private suspend fun registerPendingCommand(
        command: ActionCommand,
        allowReconciliationRetry: Boolean = false
    ): PendingCommandRegistration {
        val now = System.currentTimeMillis()
        val payloadJson = gson.toJson(command.params)
        val safetyJson = gson.toJson(command.safetySnapshot)
        val registration = db.withTransaction {
            val existing = db.actionCommandDao().byIdempotencyKey(command.idempotencyKey)
            when {
                existing == null -> {
                    db.actionCommandDao().upsert(
                        ActionCommandEntity(
                            id = command.id,
                            timestamp = now,
                            type = command.type,
                            payloadJson = payloadJson,
                            safetyJson = safetyJson,
                            idempotencyKey = command.idempotencyKey,
                            status = STATUS_PENDING
                        )
                    )
                    PendingCommandRegistration(
                        kind = PendingCommandRegistrationKind.FIRST_RESERVED,
                        command = command
                    )
                }
                command.type == "carbs" && command.idempotencyKey.startsWith("manual:meal:") &&
                    !sameManualCarbPayload(existing, command) -> PendingCommandRegistration(
                    kind = PendingCommandRegistrationKind.EXISTING_UNCERTAIN_OR_TERMINAL,
                    command = command.copy(id = existing.id)
                )
                existing.status == STATUS_SENT -> PendingCommandRegistration(
                    kind = PendingCommandRegistrationKind.EXISTING_SENT,
                    command = command.copy(id = existing.id)
                )
                allowReconciliationRetry && existing.status in setOf(STATUS_PENDING, STATUS_FAILED) -> {
                    val retryCommand = command.copy(id = existing.id)
                    db.actionCommandDao().upsert(
                        existing.copy(
                            timestamp = now,
                            payloadJson = payloadJson,
                            safetyJson = safetyJson,
                            status = STATUS_PENDING
                        )
                    )
                    PendingCommandRegistration(
                        kind = PendingCommandRegistrationKind.RECONCILIATION_RETRY,
                        command = retryCommand
                    )
                }
                else -> PendingCommandRegistration(
                    kind = PendingCommandRegistrationKind.EXISTING_UNCERTAIN_OR_TERMINAL,
                    command = command.copy(id = existing.id)
                )
            }
        }
        when (registration.kind) {
            PendingCommandRegistrationKind.EXISTING_SENT,
            PendingCommandRegistrationKind.EXISTING_UNCERTAIN_OR_TERMINAL -> {
                auditLogger.info("action_deduplicated", mapOf("idempotencyKey" to command.idempotencyKey))
            }
            PendingCommandRegistrationKind.RECONCILIATION_RETRY -> {
                auditLogger.info(
                    "action_delivery_retry",
                    mapOf(
                        "idempotencyKey" to registration.command.idempotencyKey,
                        "commandId" to registration.command.id
                    )
                )
            }
            PendingCommandRegistrationKind.FIRST_RESERVED -> Unit
        }
        return registration
    }

    private fun sameManualCarbPayload(record: ActionCommandEntity, command: ActionCommand): Boolean =
        record.type == command.type && runCatching {
            val stored = gson.fromJson(record.payloadJson, com.google.gson.JsonObject::class.java)
            stored.remove("carbBlockReason")
            stored.remove("deliveryChannel")
            stored == gson.toJsonTree(command.params)
        }.getOrDefault(false)

    private fun isFirstAllowlistedEatingSoonRefusal(
        command: ActionCommand,
        isFirstReservation: Boolean,
        reason: String
    ): Boolean = isFirstReservation &&
        isExactEatingSoonCommand(command) &&
        canonicalEatingSoonFailureCode(reason) != null

    private fun isExactEatingSoonCommand(command: ActionCommand): Boolean =
        command.type == "temp_target" &&
            command.idempotencyKey.startsWith("manual:meal:") &&
            command.idempotencyKey.endsWith(":eating-soon") &&
            command.params["targetMmol"]?.toDoubleOrNull() == EatingSoonPolicy.TARGET_MMOL &&
            command.params["durationMinutes"]?.toIntOrNull() == EatingSoonPolicy.DURATION_MINUTES &&
            command.params["reason"] == "Eating Soon"

    private data class PendingCommandRegistration(
        val kind: PendingCommandRegistrationKind,
        val command: ActionCommand
    )

    private enum class PendingCommandRegistrationKind {
        FIRST_RESERVED,
        EXISTING_SENT,
        EXISTING_UNCERTAIN_OR_TERMINAL,
        RECONCILIATION_RETRY
    }

    private suspend fun markFailed(command: ActionCommand, reason: String) {
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = command.id,
                timestamp = System.currentTimeMillis(),
                type = command.type,
                payloadJson = failurePayloadJson(command, reason),
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

    private suspend fun markBlocked(command: ActionCommand, reason: String) {
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = command.id,
                timestamp = System.currentTimeMillis(),
                type = command.type,
                payloadJson = failurePayloadJson(command, reason),
                safetyJson = gson.toJson(command.safetySnapshot),
                idempotencyKey = command.idempotencyKey,
                status = STATUS_BLOCKED
            )
        )
        auditLogger.warn(
            "action_delivery_blocked",
            mapOf("reason" to reason, "commandId" to command.id, "type" to command.type)
        )
    }

    private fun failurePayloadJson(command: ActionCommand, reason: String): String {
        if (command.type == "carbs" && command.idempotencyKey.startsWith("manual:meal:")) {
            val code = reason.takeIf { it in setOf("carbs_rate_limit_30m", "therapy_actions_not_armed") }
                ?: "carbs_blocked"
            return gson.toJson(command.params + ("carbBlockReason" to code))
        }
        if (command.type != "temp_target" || !command.idempotencyKey.startsWith("manual:meal:") ||
            !command.idempotencyKey.endsWith(":eating-soon")) return gson.toJson(command.params)
        return gson.toJson(command.params + (EATING_SOON_FAILURE_CODE to
            (canonicalEatingSoonFailureCode(reason) ?: "target_not_sent")))
    }

    private fun storedEatingSoonFailureCode(record: ActionCommandEntity): String? {
        val payload = try {
            gson.fromJson(record.payloadJson, JsonObject::class.java)
        } catch (_: JsonParseException) {
            null
        }
        val value = payload?.get(EATING_SOON_FAILURE_CODE)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return canonicalEatingSoonFailureCode(value)
    }

    private fun canonicalEatingSoonFailureCode(reason: String?): String? {
        if (reason == null || reason.length > 96) return null
        val code = reason.removePrefix("managed_preflight:")
        if (code == "therapy_actions_not_armed") return "actions_disarmed"
        if (code in EATING_SOON_FAILURE_CODES) return code
        for (minutes in listOf(5, 30, 60)) {
            if (code in listOf("forecast_${minutes}m_missing", "forecast_${minutes}m_invalid",
                    "forecast_${minutes}m_too_low", "forecast_${minutes}m_ci_low")) return code
        }
        return null
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

    private fun nightscoutWriteTargets(settings: AppSettings): List<String> =
        nightscoutWriteTargetsStatic(settings)

    private fun isLocalNightscoutTarget(url: String, settings: AppSettings): Boolean =
        isOwnedLocalNightscoutEndpoint(url, settings)

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

    companion object {
        private const val EATING_SOON_FAILURE_CODE = "eatingSoonFailureCode"
        private val EATING_SOON_FAILURE_CODES = setOf(
            "kill_switch_active", "actions_disarmed", "target_bounds_invalid", "target_out_of_bounds",
            "chronology_unresolved", "sensor_untrusted", "glucose_timestamp_missing", "glucose_timestamp_invalid",
            "glucose_timestamp_future", "glucose_stale", "glucose_missing", "glucose_invalid", "glucose_too_low",
            "forecast_timestamp_missing", "forecast_timestamp_invalid", "forecast_timestamp_future", "forecast_stale",
            "accepted_forecast_unavailable", "settings_changed", "safety_read_expired", "safety_lookup_failed",
            "target_authority_read_timeout", "invalid_action_payload", "missing_nightscout_url", "target_not_sent"
        )
        private val TEMP_TARGET_WRITE_MUTEX = Mutex()
        private val TEMP_TARGET_RETRY_OWNERS = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        const val MANUAL_IDEMPOTENCY_PREFIX = "manual:"
        const val KEEPALIVE_IDEMPOTENCY_PREFIX = "adaptive_keepalive:"
        const val TARGET_MANAGER_IDEMPOTENCY_PREFIX = "TargetManager.v1:"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_FAILED = "FAILED"
        const val DEFAULT_LOCAL_TREATMENT_ACTION = "info.nightscout.client.NEW_TREATMENT"
        const val ACTION_LOCAL_TREATMENTS = "info.nightscout.androidaps.action.LOCAL_TREATMENTS"
        const val ACTION_NS_EMULATOR = "com.eveningoutpost.dexdrip.NS_EMULATOR"
        const val AAPS_PACKAGE_LEGACY = "info.nightscout.androidaps"
        const val AAPS_PACKAGE_MODERN = "app.aaps"

        internal fun automaticTargetOwnershipBlockReasonStatic(
            mode: TargetManagerMode,
            idempotencyKey: String
        ): String? {
            if (mode != TargetManagerMode.ACTIVE) return null
            if (idempotencyKey.startsWith(TARGET_MANAGER_IDEMPOTENCY_PREFIX)) return null
            if (idempotencyKey.startsWith(MANUAL_IDEMPOTENCY_PREFIX)) return null
            return "legacy_automatic_writer_blocked_active_manager"
        }

        internal fun therapyActionBootstrapBlockReasonStatic(
            therapyActionsArmed: Boolean
        ): String? = if (therapyActionsArmed) null else "therapy_actions_not_armed"

        internal fun tempTargetFailurePolicyStatic(
            idempotencyKey: String
        ): TempTargetFailurePolicy = if (
            idempotencyKey.startsWith(TARGET_MANAGER_IDEMPOTENCY_PREFIX) ||
            (idempotencyKey.startsWith("manual:meal:") && idempotencyKey.endsWith(":eating-soon"))
        ) {
            TempTargetFailurePolicy.RECONCILE_UNKNOWN
        } else {
            TempTargetFailurePolicy.LEGACY_FALLBACK_ALLOWED
        }

        internal suspend fun postUamCarbEntryStatic(
            settings: AppSettings,
            apiFactory: ApiFactory,
            tsMs: Long,
            grams: Double,
            note: String,
            deliveryGuard: (suspend () -> String?)? = null
        ): Result<String> {
            therapyActionBootstrapBlockReasonStatic(settings.therapyActionsArmed)?.let { reason ->
                return Result.failure(IllegalStateException(reason))
            }
            if (tsMs <= 0L) return Result.failure(IllegalArgumentException("invalid_uam_treatment_timestamp"))
            if (!grams.isFinite() || grams < 0.1) {
                return Result.failure(IllegalArgumentException("invalid_uam_carbs"))
            }
            val tag = UamTagCodec.parseUamTag(note)
            if (tag?.ver != 2) return Result.failure(IllegalArgumentException("invalid_uam_v2_tag"))

            val targets = nightscoutWriteTargetsStatic(settings)
            if (targets.isEmpty()) return Result.failure(IllegalStateException("missing_nightscout_url"))
            val request = NightscoutTreatmentRequest(
                createdAt = Instant.ofEpochMilli(tsMs).toString(),
                date = tsMs,
                mills = tsMs,
                eventType = "Carb Correction",
                carbs = grams,
                notes = note,
                reason = "uam_engine"
            )
            deliveryGuard?.invoke()?.let { reason ->
                return Result.failure(IllegalStateException(reason))
            }

            val response = try {
                if (isLocalNightscoutTargetStatic(targets.first(), settings)) {
                    apiFactory.nightscoutApi(targets.first(), settings).postTreatment(request)
                } else {
                    TherapyActionTransportGate.withArmedLease(
                        verifyPersistedState = {
                            deliveryGuard?.invoke() == null
                        }
                    ) {
                        apiFactory.nightscoutApi(targets.first(), settings).postTreatment(request)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Throwable) {
                return Result.failure(error)
            }
            val remoteId = response.id?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("nightscout_post_missing_remote_id"))
            return Result.success(remoteId)
        }

        internal suspend fun fetchUamCarbEntriesStatic(
            settings: AppSettings,
            apiFactory: ApiFactory,
            sinceTsMs: Long
        ): Result<List<AapsCarbEntry>> {
            if (sinceTsMs <= 0L) {
                return Result.failure(IllegalArgumentException("invalid_uam_fetch_timestamp"))
            }
            val targets = nightscoutWriteTargetsStatic(settings)
            if (targets.isEmpty()) return Result.failure(IllegalStateException("missing_nightscout_url"))
            val sinceIso = Instant.ofEpochMilli(sinceTsMs).toString()
            var lastError: Throwable? = null
            for (target in targets) {
                val rows = try {
                    apiFactory.nightscoutApi(target, settings).getTreatments(
                        mapOf(
                            "count" to "2000",
                            "find[created_at][\$gte]" to sinceIso
                        )
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: Error) {
                    throw fatal
                } catch (error: Throwable) {
                    lastError = error
                    continue
                }
                return Result.success(
                    rows.mapNotNull { treatment ->
                        val grams = treatment.carbs ?: return@mapNotNull null
                        if (!grams.isFinite() || grams <= 0.0) return@mapNotNull null
                        val tsMs = parseUamTreatmentTimestamp(
                            createdAt = treatment.createdAt,
                            mills = treatment.mills,
                            date = treatment.date
                        ) ?: return@mapNotNull null
                        AapsCarbEntry(
                            remoteId = treatment.id,
                            tsMs = tsMs,
                            grams = grams,
                            note = treatment.notes
                        )
                    }
                )
            }
            return Result.failure(lastError ?: IllegalStateException("nightscout_fetch_failed"))
        }

        private fun nightscoutWriteTargetsStatic(settings: AppSettings): List<String> {
            val targets = LinkedHashSet<String>()
            val resolved = normalizeTargetUrlStatic(settings.resolvedNightscoutUrl())
            if (resolved.isNotEmpty()) targets += resolved
            if (settings.localNightscoutEnabled) {
                targets += normalizeTargetUrlStatic("https://127.0.0.1:${settings.localNightscoutPort}")
            }
            return targets.toList()
        }

        private fun isLocalNightscoutTargetStatic(
            url: String,
            settings: AppSettings
        ): Boolean = isOwnedLocalNightscoutEndpoint(url, settings)

        private fun normalizeTargetUrlStatic(url: String): String = url.trim().trimEnd('/')

        private fun parseUamTreatmentTimestamp(
            createdAt: String?,
            mills: Long?,
            date: Long?
        ): Long? {
            val parsedCreatedAt = runCatching {
                createdAt?.let { Instant.parse(it).toEpochMilli() }
            }.getOrNull()
            if (parsedCreatedAt != null && parsedCreatedAt > 0L) return parsedCreatedAt
            val raw = mills?.takeIf { it > 0L } ?: date?.takeIf { it > 0L } ?: return null
            return if (raw < 1_000_000_000_000L) raw * 1000L else raw
        }
    }

    enum class TempTargetFailurePolicy {
        RECONCILE_UNKNOWN,
        LEGACY_FALLBACK_ALLOWED
    }

    enum class TempTargetDeliveryReconciliation {
        SENT,
        CONFIRMED_ABSENT,
        UNKNOWN
    }

    class TempTargetDeliveryUnknownException(
        message: String,
        cause: Throwable
    ) : IllegalStateException(message, cause)

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
