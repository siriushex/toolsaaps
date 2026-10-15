package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.DeliveryDiagnosticPolicy
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticReason

/** Presentation only; an OPEN row or notification receipt is not current delivery evidence. */
data class DeliveryDiagnosticUiEvidence(
    val episodeId: String = "",
    val recordedAt: Long? = null,
    val reason: DeliveryDiagnosticReason? = null,
    val foodConfounded: Boolean = false,
    val acceptedAt: Long? = null
) {
    fun isCurrent(nowTs: Long): Boolean = episodeId.isNotEmpty() && recordedAt != null && recordedAt in 1..nowTs &&
        acceptedAt != null && acceptedAt in 1..nowTs && nowTs - acceptedAt <= DeliveryDiagnosticPolicy.MAX_GAP_MS &&
        reason in setOf(DeliveryDiagnosticReason.UNEXPECTED_RISE, DeliveryDiagnosticReason.PERSISTENT_HIGH)
}
