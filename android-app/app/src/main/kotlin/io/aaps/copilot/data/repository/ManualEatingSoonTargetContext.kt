package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.EatingSoonPolicy
import io.aaps.copilot.util.UnitConverter

internal fun isCanonicalEatingSoonCommand(command: ActionCommandEntity, gson: Gson): Boolean {
    if (command.type != "temp_target" || !EatingSoonPolicy.isRequestKey(command.idempotencyKey)) return false
    val payload = targetPayload(command, gson) ?: return false
    return EatingSoonPolicy.isCanonicalRequest(command.idempotencyKey,
        primitiveString(payload, "targetMmol")?.toDoubleOrNull(),
        primitiveString(payload, "durationMinutes")?.toIntOrNull(),
        primitiveString(payload, "reason"))
}

internal fun withConfirmedEatingSoonContext(
    active: ActiveAapsTarget?, command: ActionCommandEntity?, nowTs: Long, gson: Gson
): ActiveAapsTarget? {
    if (active == null) return null
    val confirmed = command != null && isCanonicalEatingSoonCommand(command, gson) &&
        isConfirmedObservedTargetCommand(active, command, nowTs, gson) &&
        EatingSoonPolicy.isActiveConfirmedTarget(active.copy(eatingSoonConfirmed = true), nowTs)
    return active.copy(eatingSoonConfirmed = confirmed)
}

internal fun isConfirmedObservedTargetCommand(
    active: ActiveAapsTarget, command: ActionCommandEntity, nowTs: Long, gson: Gson
): Boolean {
    if (!active.evidenceResolved || active.source.isBlank() || active.startedAt <= 0L ||
        active.startedAt > nowTs || active.expiresAt <= nowTs || command.type != "temp_target" ||
        command.status != NightscoutActionRepository.STATUS_SENT ||
        command.idempotencyKey != active.idempotencyKey ||
        command.timestamp !in active.startedAt..nowTs || command.timestamp >= active.expiresAt) return false
    val payload = targetPayload(command, gson) ?: return false
    val target = primitiveString(payload, "targetMmol")?.toDoubleOrNull() ?: return false
    val duration = primitiveString(payload, "durationMinutes")?.toLongOrNull()
        ?.takeIf { it in 15L..120L } ?: return false
    if (!UnitConverter.matchesTempTargetObservation(target, active.targetMmol)) return false
    return runCatching { Math.subtractExact(active.expiresAt, active.startedAt) }.getOrNull() == duration * 60_000L
}

internal class ConfirmedSupersedingTarget private constructor(val observed: ActiveAapsTarget) {
    companion object {
        fun fromObservation(
            active: ActiveAapsTarget?, command: ActionCommandEntity?, nowTs: Long, gson: Gson
        ): ConfirmedSupersedingTarget? {
            if (active == null || command == null ||
                !isConfirmedObservedTargetCommand(active, command, nowTs, gson)) return null
            val managed = active.ownership == ActiveTargetOwnership.TARGET_MANAGER &&
                active.idempotencyKey?.startsWith(NightscoutActionRepository.TARGET_MANAGER_IDEMPOTENCY_PREFIX) == true
            val manual = EatingSoonPolicy.isActiveConfirmedTarget(active, nowTs) &&
                isCanonicalEatingSoonCommand(command, gson)
            if (!managed && !manual) return null
            return ConfirmedSupersedingTarget(active)
        }
    }
}

internal fun isSupersededEatingSoonCommand(
    command: ActionCommandEntity, confirmedTarget: ConfirmedSupersedingTarget?, nowTs: Long, gson: Gson
): Boolean {
    val observed = confirmedTarget?.observed ?: return false
    return observed.startedAt in 1L..nowTs && observed.expiresAt > nowTs &&
        command.idempotencyKey != observed.idempotencyKey &&
        command.timestamp in 1L..observed.startedAt &&
        command.status == NightscoutActionRepository.STATUS_SENT && isCanonicalEatingSoonCommand(command, gson)
}

private fun targetPayload(command: ActionCommandEntity, gson: Gson): JsonObject? {
    if (command.payloadJson.length > 4096) return null
    return runCatching { gson.fromJson(command.payloadJson, JsonObject::class.java) }.getOrNull()
}

private fun primitiveString(payload: JsonObject, key: String): String? =
    payload.get(key)?.takeIf { it.isJsonPrimitive }?.asString
