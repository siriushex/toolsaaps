package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.MealEnergyOverrideDao
import io.aaps.copilot.data.local.dao.TherapyDao
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.profile.MealTherapyReferenceTrust
import io.aaps.copilot.domain.profile.toMealTherapyReference

internal data class MealEnergyOverrideDataSource(
    val therapyById: suspend (String) -> TherapyEventEntity?,
    val save: suspend (MealEnergyOverrideEntity) -> Unit
)

sealed interface ManualMealEnergySaveResult {
    data class Saved(
        val canonicalTherapyIdentity: String,
        val therapyRevisionHash: String
    ) : ManualMealEnergySaveResult

    data object INVALID_INPUT : ManualMealEnergySaveResult
    data object MEAL_NOT_FOUND : ManualMealEnergySaveResult
    data object UNTRUSTED_MEAL : ManualMealEnergySaveResult
}

/**
 * Stores only a user-entered energy total for one already imported, real AAPS meal.
 * It never creates therapy, changes a target, or affects automation.
 */
class MealEnergyOverrideRepository internal constructor(
    private val source: MealEnergyOverrideDataSource,
    private val gson: Gson,
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(
        therapyDao: TherapyDao,
        mealEnergyOverrideDao: MealEnergyOverrideDao,
        gson: Gson,
        clock: () -> Long = System::currentTimeMillis
    ) : this(
        source = MealEnergyOverrideDataSource(
            therapyById = therapyDao::byId,
            save = mealEnergyOverrideDao::upsert
        ),
        gson = gson,
        clock = clock
    )

    suspend fun saveForMealEvent(
        therapyEventId: String,
        caloriesKcal: Double
    ): ManualMealEnergySaveResult {
        val eventId = therapyEventId.trim()
        if (eventId.isEmpty() || !caloriesKcal.isFinite() || caloriesKcal !in MIN_KCAL..MAX_KCAL) {
            return ManualMealEnergySaveResult.INVALID_INPUT
        }
        val entity = source.therapyById(eventId) ?: return ManualMealEnergySaveResult.MEAL_NOT_FOUND
        if (TherapySanitizer.isLocalBroadcastArtifact(entity.id, entity.type)) {
            return ManualMealEnergySaveResult.UNTRUSTED_MEAL
        }
        val therapy = entity.toDomain(gson)
        val components = resolveTherapyComponents(therapy)
        val reference = therapy.toMealTherapyReference()
        if (
            components.carbKind != TherapyCarbComponentKind.REAL ||
            components.carbsG == null ||
            reference.trust != MealTherapyReferenceTrust.TRUSTED
        ) {
            return ManualMealEnergySaveResult.UNTRUSTED_MEAL
        }
        val revision = reference.revision ?: return ManualMealEnergySaveResult.UNTRUSTED_MEAL
        return saveForTrustedCanonicalMeal(
            canonicalTherapyIdentity = reference.identity,
            therapyRevisionHash = revision,
            caloriesKcal = caloriesKcal
        )
    }

    /**
     * Persists an exact value only after a caller has verified the AAPS canonical
     * identity and revision from the reconciled meal event.
     */
    internal suspend fun saveForTrustedCanonicalMeal(
        canonicalTherapyIdentity: String,
        therapyRevisionHash: String,
        caloriesKcal: Double
    ): ManualMealEnergySaveResult {
        val identity = canonicalTherapyIdentity.trim()
        val revision = therapyRevisionHash.trim()
        if (
            identity.isEmpty() || identity.length > MAX_REFERENCE_LENGTH ||
            revision.isEmpty() || revision.length > MAX_REFERENCE_LENGTH ||
            !isValidManualMealEnergyKcal(caloriesKcal)
        ) {
            return ManualMealEnergySaveResult.INVALID_INPUT
        }
        source.save(
            MealEnergyOverrideEntity(
                canonicalTherapyIdentity = identity,
                therapyRevisionHash = revision,
                caloriesKcal = caloriesKcal,
                updatedAtMs = clock()
            )
        )
        return ManualMealEnergySaveResult.Saved(identity, revision)
    }

    internal companion object {
        const val MIN_KCAL = 1.0
        const val MAX_KCAL = 10_000.0
        const val MAX_REFERENCE_LENGTH = 128

        fun isValidManualMealEnergyKcal(value: Double): Boolean =
            value.isFinite() && value in MIN_KCAL..MAX_KCAL
    }
}
