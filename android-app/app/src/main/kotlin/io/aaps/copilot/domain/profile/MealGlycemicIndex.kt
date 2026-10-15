package io.aaps.copilot.domain.profile

import java.io.Serializable

enum class GlycemicIndexSource { USER, CATALOG }

data class MealGlycemicIndex private constructor(
    val value: Double,
    val source: GlycemicIndexSource,
    val reference: String?
) : Serializable {
    init {
        require(value.isFinite() && value in 0.0..200.0)
        require(source != GlycemicIndexSource.CATALOG || !reference.isNullOrBlank())
        require(reference == null || reference.length <= 256)
    }

    companion object {
        fun fromManualInput(raw: String): MealGlycemicIndex? {
            if (raw.length > 32) return null
            return fromStored(raw.trim().replace(',', '.').toDoubleOrNull(), "USER")
        }

        fun fromStored(value: Double?, source: String?, reference: String? = null): MealGlycemicIndex? {
            if (value == null || !value.isFinite() || value !in 0.0..200.0) return null
            val origin = runCatching { GlycemicIndexSource.valueOf(source.orEmpty()) }.getOrNull() ?: return null
            val ref = if (origin == GlycemicIndexSource.CATALOG) {
                reference?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
            } else null
            return MealGlycemicIndex(value, origin, ref)
        }
    }
}

data class MealGlycemicIndexOverride(val therapyRevision: String, val index: MealGlycemicIndex)

/** Display-only evidence; no global GI is assigned to other meals. */
class MealGlycemicIndexContext(overrides: Map<String, MealGlycemicIndexOverride> = emptyMap()) {
    private val overrides = overrides.toMap()

    fun indexFor(reference: MealTherapyReference): MealGlycemicIndex? {
        if (reference.trust != MealTherapyReferenceTrust.TRUSTED || reference.identity.isBlank()) return null
        val revision = reference.revision?.takeIf { it.isNotBlank() } ?: return null
        return overrides[reference.identity]?.takeIf { it.therapyRevision == revision }?.index
    }

    companion object {
        val EMPTY = MealGlycemicIndexContext()
    }
}
