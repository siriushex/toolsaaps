package io.aaps.copilot.domain.model

import java.util.IdentityHashMap
import java.util.Locale

internal enum class TherapyCarbComponentKind {
    NONE,
    REAL,
    UAM_SYNTHETIC,
    CORRECTION,
    INVALID_OR_SUPERSEDED
}

internal object TherapyComponentPolicy {
    val INSULIN_UNITS_RANGE = 0.0..100.0
    val CARBS_GRAMS_RANGE = 0.0..500.0

    fun isValidCanonicalRow(
        insulinU: Double?,
        carbsG: Double?,
        syntheticUam: Boolean,
        hasInsulinEvidence: Boolean,
        hasContext: Boolean
    ): Boolean {
        if (insulinU != null && (!insulinU.isFinite() || insulinU !in INSULIN_UNITS_RANGE)) {
            return false
        }
        if (carbsG != null && (!carbsG.isFinite() || carbsG !in CARBS_GRAMS_RANGE)) return false
        if (insulinU == null && carbsG == null && !hasContext) return false
        if (syntheticUam && carbsG == null) return false
        if (hasInsulinEvidence && insulinU == null) return false
        if (hasContext && (insulinU != null || carbsG != null || syntheticUam || hasInsulinEvidence)) {
            return false
        }
        return true
    }
}

internal data class TherapyComponentSemantics(
    val canonicalCarbId: Long?,
    val canonicalCarbAuthoritative: Boolean,
    val wholeEventValid: Boolean,
    val insulinU: Double?,
    val carbsG: Double?,
    val carbKind: TherapyCarbComponentKind,
    val learningCarbsSuppressed: Boolean
) {
    val learningCarbsG: Double?
        get() = carbsG.takeIf {
            carbKind == TherapyCarbComponentKind.REAL && !learningCarbsSuppressed
        }

    val canonicalMealInsulinCanBeCorrection: Boolean
        get() = canonicalCarbAuthoritative && when (carbKind) {
            TherapyCarbComponentKind.CORRECTION,
            TherapyCarbComponentKind.INVALID_OR_SUPERSEDED,
            TherapyCarbComponentKind.NONE -> true
            TherapyCarbComponentKind.REAL,
            TherapyCarbComponentKind.UAM_SYNTHETIC -> false
        }

    val keepEvent: Boolean
        get() = insulinU != null || carbsG != null
}

internal class TherapyComponentResolutionSession(
    private val resolver: (TherapyEvent) -> TherapyComponentSemantics =
        ::resolveTherapyComponents
) {
    private val resolved = IdentityHashMap<TherapyEvent, TherapyComponentSemantics>()

    fun resolve(event: TherapyEvent): TherapyComponentSemantics =
        resolved[event] ?: resolver(event).also { resolved[event] = it }

    fun remember(event: TherapyEvent, components: TherapyComponentSemantics) {
        resolved[event] = components
    }
}

internal fun resolveTherapyComponents(event: TherapyEvent): TherapyComponentSemantics =
    resolveTherapyComponents(
        type = event.type,
        payload = event.payload,
        componentTrust = event.componentTrust
    )

internal fun resolveTherapyComponents(
    type: String,
    payload: Map<String, String>
): TherapyComponentSemantics = resolveTherapyComponents(
    type = type,
    payload = payload,
    componentTrust = TherapyEventComponentTrust.NONE
)

internal fun resolveTherapyComponents(
    type: String,
    payload: Map<String, String>,
    componentTrust: TherapyEventComponentTrust
): TherapyComponentSemantics {
    val values = payload.entries.associate { (key, value) -> normalizeTherapyComponentKey(key) to value }
    fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { values[normalizeTherapyComponentKey(it)] }
    fun number(vararg keys: String): Double? = value(*keys)
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    fun boolean(vararg keys: String): Boolean? = when (value(*keys)?.trim()?.lowercase(Locale.US)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    val canonicalSemanticConflict = componentTrust.canonicalSemanticConflict
    val legacyValidityConflict = componentTrust.legacyValidityConflict
    val hasTopLevelValidity = values.containsKey(normalizeTherapyComponentKey("isValid"))
    val wholeEventValid = !legacyValidityConflict &&
        (!hasTopLevelValidity || boolean("isValid") == true)
    val canonicalCarbAuthoritative = canonicalSemanticConflict ||
        CANONICAL_CARB_COMPONENT_KEYS.any(values::containsKey)
    val normalizedType = type.trim().lowercase(Locale.US)
    val insulin = if (normalizedType in INSULIN_TYPES && (wholeEventValid || canonicalCarbAuthoritative)) {
        number("units", "bolusUnits", "insulinUnits", "insulin", "enteredInsulin")
            ?.takeIf { it in TherapyComponentPolicy.INSULIN_UNITS_RANGE }
    } else {
        null
    }

    val canonicalClassification = value("aapsCarbClassification")?.trim()?.uppercase(Locale.US)
    val canonicalAmount = number("aapsCarbAmount")
    val canonicalValid = boolean("aapsCarbIsValid") == true && boolean("aapsCarbSuperseded") != true
    val canonicalSynthetic = boolean("aapsCarbSynthetic") == true
    val legacyClassification = value("classification")?.trim()?.uppercase(Locale.US)
    val legacyCarbs = number("grams", "carbs", "enteredCarbs", "mealCarbs")
    val legacySynthetic = boolean("synthetic") == true ||
        legacyClassification == CLASSIFICATION_UAM ||
        value("source").equals("uam_engine", true) ||
        value("reason")?.contains("uam_engine", true) == true ||
        value("syntheticType")?.contains("uam", true) == true ||
        value("note")?.contains("UAM_ENGINE|", true) == true ||
        value("notes")?.contains("UAM_ENGINE|", true) == true

    val carbResolution = when {
        normalizedType !in CARB_TYPES -> null to TherapyCarbComponentKind.NONE
        canonicalCarbAuthoritative -> when {
            canonicalSemanticConflict -> null to TherapyCarbComponentKind.INVALID_OR_SUPERSEDED
            !canonicalValid -> null to TherapyCarbComponentKind.INVALID_OR_SUPERSEDED
            canonicalClassification == CLASSIFICATION_CORRECTION ||
                canonicalAmount?.let { it < 0.0 } == true ->
                null to TherapyCarbComponentKind.CORRECTION
            canonicalAmount == null || canonicalAmount == 0.0 ->
                null to TherapyCarbComponentKind.NONE
            canonicalClassification == CLASSIFICATION_UAM || canonicalSynthetic ->
                canonicalAmount.takeIf { it in TherapyComponentPolicy.CARBS_GRAMS_RANGE } to
                    TherapyCarbComponentKind.UAM_SYNTHETIC
            canonicalClassification == CLASSIFICATION_REAL ->
                canonicalAmount.takeIf { it in TherapyComponentPolicy.CARBS_GRAMS_RANGE } to
                    TherapyCarbComponentKind.REAL
            else -> null to TherapyCarbComponentKind.INVALID_OR_SUPERSEDED
        }
        !wholeEventValid -> null to TherapyCarbComponentKind.INVALID_OR_SUPERSEDED
        legacyClassification == CLASSIFICATION_CORRECTION ->
            null to TherapyCarbComponentKind.CORRECTION
        legacyCarbs == null || legacyCarbs <= 0.0 ||
            legacyCarbs !in TherapyComponentPolicy.CARBS_GRAMS_RANGE ->
            null to TherapyCarbComponentKind.NONE
        legacySynthetic -> legacyCarbs to TherapyCarbComponentKind.UAM_SYNTHETIC
        else -> legacyCarbs to TherapyCarbComponentKind.REAL
    }

    return TherapyComponentSemantics(
        canonicalCarbId = componentTrust.canonicalCarbId,
        canonicalCarbAuthoritative = canonicalCarbAuthoritative,
        wholeEventValid = wholeEventValid,
        insulinU = insulin,
        carbsG = carbResolution.first,
        carbKind = carbResolution.second,
        learningCarbsSuppressed = boolean("copilotLearningCarbsSuppressed") == true
    )
}

private fun normalizeTherapyComponentKey(value: String): String =
    value.lowercase(Locale.US).filter(Char::isLetterOrDigit)

private val INSULIN_TYPES = setOf("bolus", "insulin", "correction_bolus", "meal_bolus")
private val CARB_TYPES = setOf("meal_bolus", "meal", "carbs")
private val CANONICAL_CARB_COMPONENT_KEYS = setOf(
    "aapscarbamount",
    "aapscarbisvalid",
    "aapscarbclassification",
    "aapscarbsynthetic",
    "aapscarbsuperseded"
)
private const val CLASSIFICATION_REAL = "AAPS_REAL"
private const val CLASSIFICATION_UAM = "UAM_SYNTHETIC"
private const val CLASSIFICATION_CORRECTION = "AAPS_CORRECTION"
