package io.aaps.copilot.ui

import io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult
import io.aaps.copilot.ui.foundation.screens.PlannedActivityEventUi
import kotlinx.coroutines.CancellationException

internal object PlannedActivityInvalidationPolicy {
    fun saveChangesDurableState(
        current: List<PlannedActivityEventUi>,
        requested: PlannedActivityEventUi
    ): Boolean = current.firstOrNull { it.eventId == requested.eventId } != requested

    fun deleteChangesDurableState(
        current: List<PlannedActivityEventUi>,
        eventId: String
    ): Boolean = current.any { it.eventId == eventId }
}

internal suspend fun persistPlannedActivityClinicalInput(
    persistence: suspend () -> PlannedActivityScheduleSaveResult,
    onClinicalInputPersisted: suspend () -> Unit
): PlannedActivityScheduleSaveResult {
    val result = persistence()
    if (result is PlannedActivityScheduleSaveResult.Saved) {
        try {
            onClinicalInputPersisted()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            // Persistence already succeeded; the coordinator reports and recovers invalidation failures.
        }
    }
    return result
}
