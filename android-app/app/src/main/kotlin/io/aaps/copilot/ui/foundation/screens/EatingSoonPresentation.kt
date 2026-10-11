package io.aaps.copilot.ui.foundation.screens

import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.NightscoutActionRepository
import io.aaps.copilot.data.repository.isCanonicalEatingSoonCommand
import io.aaps.copilot.data.repository.isConfirmedObservedTargetCommand
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.EatingSoonPolicy

private val presentationGson = Gson()

internal fun eatingSoonPresentation(
    receipt: ActionCommandEntity?, observed: ActiveAapsTarget?, nowTs: Long, gson: Gson = presentationGson
): EatingSoonUiState {
    if (receipt == null || !isCanonicalEatingSoonCommand(receipt, gson)) return EatingSoonUiState()
    if (receipt.timestamp <= 0L || receipt.timestamp > nowTs) return EatingSoonUiState(
        EatingSoonUiStatus.UNKNOWN, receipt.idempotencyKey, receipt.timestamp
    )
    if (receipt.status == NightscoutActionRepository.STATUS_SENT &&
        nowTs - receipt.timestamp >= EatingSoonPolicy.DURATION_MINUTES * 60_000L) {
        // This permits a new explicit intent, not a replay or a claim about historical delivery.
        return EatingSoonUiState()
    }
    val status = when {
        observed != null && EatingSoonPolicy.isActiveConfirmedTarget(observed, nowTs) &&
            isConfirmedObservedTargetCommand(observed, receipt, nowTs, gson) -> EatingSoonUiStatus.CONFIRMED
        receipt.status == NightscoutActionRepository.STATUS_BLOCKED ||
            receipt.status == NightscoutActionRepository.STATUS_FAILED -> EatingSoonUiStatus.BLOCKED
        receipt.status == NightscoutActionRepository.STATUS_SENT -> EatingSoonUiStatus.AWAITING_AAPS
        // A durable pending receipt after restart cannot prove an in-flight send.
        else -> EatingSoonUiStatus.UNKNOWN
    }
    return EatingSoonUiState(status, receipt.idempotencyKey, receipt.timestamp)
}

internal fun mergeEatingSoonPresentation(durable: EatingSoonUiState, local: EatingSoonUiState?, nowTs: Long): EatingSoonUiState {
    if (local == null) return durable
    if (durable.status == EatingSoonUiStatus.IDLE &&
        local.status in setOf(EatingSoonUiStatus.AWAITING_AAPS, EatingSoonUiStatus.CONFIRMED) &&
        local.timestamp?.let { it > 0L && nowTs >= it && nowTs - it >= EatingSoonPolicy.DURATION_MINUTES * 60_000L } == true) return durable
    if (local.requestKey == durable.requestKey) {
        return if (local.status == EatingSoonUiStatus.QUEUED && durable.status == EatingSoonUiStatus.UNKNOWN) local
        else durable.copy(reason = local.reason)
    }
    return if ((local.timestamp ?: Long.MIN_VALUE) > (durable.timestamp ?: Long.MIN_VALUE)) local else durable
}
