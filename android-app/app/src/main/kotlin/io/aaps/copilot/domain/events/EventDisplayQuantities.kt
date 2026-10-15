package io.aaps.copilot.domain.events

import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.model.resolveTherapyComponents
import java.util.Locale

internal data class EventDisplayQuantities(val carbsGrams: Double?, val insulinUnits: Double?)

internal fun eventDisplayQuantities(
    type: String,
    payload: Map<String, String>,
    trust: TherapyEventComponentTrust = TherapyEventComponentTrust.NONE
): EventDisplayQuantities {
    val components = resolveTherapyComponents(type, payload, trust)
    fun normalized(key: String) = key.lowercase(Locale.US).filter(Char::isLetterOrDigit)
    val groups = payload.entries.groupBy { normalized(it.key) }
    fun conflicting(keys: Set<String>): Boolean {
        val entries = groups.filterKeys { it in keys }.values.flatten()
        val numbers = entries.map { it.value.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite) }
        return numbers.any { it == null } || numbers.distinct().size > 1
    }
    val semanticKeys = setOf("aapscarbamount", "aapscarbclassification", "aapscarbisvalid", "aapscarbsynthetic", "aapscarbsuperseded", "isvalid")
    val semanticConflict = groups.filterKeys { it in semanticKeys }.values.any { entries ->
        entries.map { it.value.trim().lowercase(Locale.US) }.distinct().size > 1
    }
    val carbKeys = if (components.canonicalCarbAuthoritative) setOf("aapscarbamount") else
        setOf("grams", "carbs", "enteredcarbs", "mealcarbs")
    return EventDisplayQuantities(
        components.carbsG.takeIf { components.carbKind == TherapyCarbComponentKind.REAL &&
            !semanticConflict && !conflicting(carbKeys) },
        components.insulinU.takeUnless { semanticConflict ||
            conflicting(setOf("units", "bolusunits", "insulinunits", "insulin", "enteredinsulin")) }
    )
}
