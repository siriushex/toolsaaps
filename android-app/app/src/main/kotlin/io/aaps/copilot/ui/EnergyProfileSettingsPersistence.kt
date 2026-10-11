package io.aaps.copilot.ui

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.profile.ProfileViolation
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

internal class EnergyProfileSettingsRejected(val violations: Set<ProfileViolation>) :
    IllegalArgumentException("Invalid energy profile settings")

internal class EnergyProfileSensitivityChangeRejected(cause: Exception) :
    IllegalStateException("Profile sensitivity recalculation failed", cause)

internal suspend fun persistEnergyProfileSettingsUpdate(
    readSettings: suspend () -> AppSettings,
    transform: (EnergyProfileSettings) -> EnergyProfileSettings,
    applyPhysiologicalSex: suspend (PhysiologicalSex) -> Unit,
    updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    today: () -> LocalDate = LocalDate::now
): EnergyProfileSettingsUpdate.Persist {
    fun validated(current: EnergyProfileSettings): EnergyProfileSettingsUpdate.Persist =
        when (val decision = evaluateEnergyProfileSettingsUpdate(current, transform(current), today())) {
            is EnergyProfileSettingsUpdate.Persist -> decision
            is EnergyProfileSettingsUpdate.Reject -> throw EnergyProfileSettingsRejected(decision.violations)
        }

    val initial = readSettings().energyProfile
    val requested = validated(initial)
    if (initial.physiologicalSex != requested.settings.physiologicalSex) {
        try {
            applyPhysiologicalSex(requested.settings.physiologicalSex)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw EnergyProfileSensitivityChangeRejected(failure)
        }
    }
    var persisted: EnergyProfileSettingsUpdate.Persist? = null
    // Revalidate and merge within the actual DataStore transaction, not the old form snapshot.
    updateSettings { current ->
        val decision = validated(current.energyProfile)
        require(decision.settings.physiologicalSex == current.energyProfile.physiologicalSex) {
            "physiological sex changed before profile persistence; retry with current settings"
        }
        persisted = decision
        current.copy(energyProfile = decision.settings)
    }
    return requireNotNull(persisted) { "profile writer did not apply the settings update" }
}
