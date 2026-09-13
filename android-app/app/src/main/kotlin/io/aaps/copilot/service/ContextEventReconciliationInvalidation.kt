package io.aaps.copilot.service

import io.aaps.copilot.data.repository.ContextEventReconciliationSummary
import io.aaps.copilot.scheduler.ClinicalInputInvalidationSource
import io.aaps.copilot.scheduler.notifyClinicalInputInvalidationAfterReconciliation

internal suspend fun reconcileContextEventsAndInvalidate(
    startup: Boolean,
    reconcile: suspend () -> ContextEventReconciliationSummary,
    invalidate: suspend (ClinicalInputInvalidationSource) -> Unit
): ContextEventReconciliationSummary {
    val summary = reconcile()
    notifyClinicalInputInvalidationAfterReconciliation(
        appliedCount = summary.appliedCount,
        startup = startup,
        invalidate = invalidate
    )
    return summary
}
