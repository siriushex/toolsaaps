package io.aaps.copilot.ui

import io.aaps.copilot.data.repository.ClinicalReportFailureReason
import io.aaps.copilot.data.repository.ClinicalReportRunDisposition
import io.aaps.copilot.data.repository.ClinicalReportState
import io.aaps.copilot.config.ClinicalAiConfigIdentityPolicy
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

internal class ClinicalReportUiCommands(
    val state: StateFlow<ClinicalReportState>,
    private val clock: () -> Long,
    private val zoneId: () -> ZoneId,
    private val prepareLocal: suspend (Long, ZoneId) -> Unit,
    private val start:
        suspend (Long, ZoneId, Boolean, String?) -> ClinicalReportRunDisposition,
    private val cancelActive: suspend () -> Unit
) {
    suspend fun prepare() {
        prepareLocal(clock(), zoneId())
    }

    suspend fun send(expectedConfigIdentity: String): Boolean {
        ClinicalAiConfigIdentityPolicy.requireValid(expectedConfigIdentity)
        val current = state.value
        val localReady = current is ClinicalReportState.LocalReady
        val failedWithLocal = current is ClinicalReportState.Failed &&
            current.local != null &&
            current.reason != ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
        val cancelledWithLocal = current is ClinicalReportState.Cancelled &&
            current.local != null
        if (!localReady && !failedWithLocal && !cancelledWithLocal) return false
        start(clock(), zoneId(), false, expectedConfigIdentity)
        return true
    }

    suspend fun cancel() {
        cancelActive()
    }

    suspend fun retryUnknown(confirmed: Boolean): ClinicalReportRunDisposition? {
        val failed = state.value as? ClinicalReportState.Failed
        if (
            !confirmed ||
            failed?.reason != ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME ||
            failed.local == null
        ) {
            return null
        }
        return start(clock(), zoneId(), true, null)
    }
}
