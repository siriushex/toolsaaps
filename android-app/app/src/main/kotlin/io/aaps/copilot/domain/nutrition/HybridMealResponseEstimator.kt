package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

data class HybridMealResponseRequest(
    val cutoffMs: Long,
    val selectedProfile: MealAbsorptionProfile?,
    val portion: MealPortion,
    val recordedZoneId: String,
    val minuteOfDay: Int,
    val declaredGrams: Double? = null,
    val portionSettings: MealPortionSettings = MealPortionSettings(),
    val personalProfile: EnergyProfileSettings = EnergyProfileSettings()
)

enum class HybridMealCurveSource { TYPE_DEFAULT, PERSONAL_DIAGNOSTIC }
enum class HybridMealQuantitySource { DECLARED, PORTION_PRESET, HISTORY_SUGGESTION }
data class HybridMealCurvePoint(val ageMinutes: Int, val cumulativeFraction: Double,
    val modeledAbsorbedGrams: Double, val modeledRemainingGrams: Double)
/** Signed apparent response per declared mass and historical CSF; never GI. */
data class HybridMealResponsePoint(val ageMinutes: Int, val medianNormalizedDelta: Double,
    val p25: Double?, val p75: Double?, val supportMeals: Int)
data class HybridMealResponsePreview(
    val shape: PersonalAbsorptionShape,
    val curveSource: HybridMealCurveSource,
    val profileWasDefaulted: Boolean,
    val modeledGrams: Double,
    val quantitySource: HybridMealQuantitySource,
    val quantityPrior: MealQuantityPrior,
    val profileResolution: ProfileResolution,
    val curve: List<HybridMealCurvePoint>,
    val response: List<HybridMealResponsePoint>,
    val supportMeals: Int,
    val supportDays: Int,
    val unusableSummaryMeals: Int,
    val personalEvaluation: PersonalAbsorptionEvaluation,
    val evidenceDigest: String,
    val evidenceCutoffMs: Long,
    val modelVersion: String = HybridMealResponseEstimator.MODEL_VERSION
) {
    val clinicalInfluenceAllowed: Boolean get() = false
    val actualMassAccuracyValidated: Boolean get() = false
    val laboratoryGi: Double? get() = null
    val targetAdjustmentPercent: Double? get() = null
    val prospectiveForecastMae: Double? get() = null
}

/** Pure advisory preview, available without history. No live/runtime registration. */
class HybridMealResponseEstimator {
    fun preview(request: HybridMealResponseRequest,
        inputs: List<PersonalAbsorptionInput> = emptyList()): HybridMealResponsePreview {
        require(request.cutoffMs > 0 && request.minuteOfDay in 0..1439)
        require(request.portionSettings.isValid())
        require(request.declaredGrams == null ||
            (request.declaredGrams.isFinite() && request.declaredGrams in 1.0..500.0))
        val zone = ZoneId.of(request.recordedZoneId)
        val now = Instant.ofEpochMilli(request.cutoffMs)
        val type = request.selectedProfile ?: MealAbsorptionProfile.MIXED
        val personalRequest = PersonalAbsorptionRequest(request.cutoffMs, type, request.portion,
            request.recordedZoneId, request.minuteOfDay)
        val cohort = preparePersonalAbsorptionCohort(personalRequest, inputs)
        val evaluation = PersonalAbsorptionEstimator().evaluatePrepared(personalRequest, cohort)
        val completedBefore = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val prior = MealPortionEstimator(Clock.fixed(now, zone)).describePrior(
            if (cohort.overflow) emptyList() else inputs.map { it.meal }.filter { it.timestamp < completedBefore },
            request.portionSettings, request.portion, type, zone, request.minuteOfDay)
        val grams = request.declaredGrams ?: prior.estimate.grams
        val quantitySource = when {
            request.declaredGrams != null -> HybridMealQuantitySource.DECLARED
            prior.estimate.fromHistory -> HybridMealQuantitySource.HISTORY_SUGGESTION
            else -> HybridMealQuantitySource.PORTION_PRESET
        }
        val candidate = evaluation.candidate.takeIf {
            request.selectedProfile != null && evaluation.status == PersonalAbsorptionStatus.EVALUATED
        }
        val shape = candidate ?: PersonalAbsorptionShape(type, MealAbsorptionCurve(type, null).durationMinutes, 1.0)
        val curve = (0..shape.durationMinutes step 5).map { minute ->
            val fraction = shape.cumulative(minute.toDouble())
            val absorbed = grams * fraction
            HybridMealCurvePoint(minute, fraction, absorbed, grams - absorbed)
        }
        // Normalize only fully usable episodes. Missing points stay missing, not zero.
        val normalized = cohort.episodes.mapNotNull { episode ->
            val scale = episode.meal.grams * episode.csfMmolPerGram
            val steps = episode.steps.map { it.ageMinutes to it.residualMmol / scale }
            steps.takeIf { scale.isFinite() && scale > 0 && it.all { row -> row.second.isFinite() } }
                ?.let { episode to it }
        }
        val response = normalized.flatMap { it.second }.groupBy { it.first }.toSortedMap().map { (age, rows) ->
            val values = rows.map { it.second }.sorted()
            HybridMealResponsePoint(age, quantile(values, 0.5),
                if (values.size > 1) quantile(values, 0.25) else null,
                if (values.size > 1) quantile(values, 0.75) else null, values.size)
        }
        val stableEvaluation = evaluation.copy(rejections = evaluation.rejections.toSortedMap(compareBy { it.name }))
        val source = listOf(MODEL_VERSION, request, prior, stableEvaluation, response).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return HybridMealResponsePreview(shape,
            if (candidate == null) HybridMealCurveSource.TYPE_DEFAULT else HybridMealCurveSource.PERSONAL_DIAGNOSTIC,
            request.selectedProfile == null, grams, quantitySource, prior,
            EnergyProfilePolicy().resolve(request.personalProfile, now.atZone(zone).toLocalDate()),
            Collections.unmodifiableList(curve), Collections.unmodifiableList(response), normalized.size,
            normalized.map { it.first.meal.timestamp.atZone(zone).toLocalDate() }.distinct().size,
            cohort.episodes.size - normalized.size, evaluation, digest, request.cutoffMs)
    }

    private fun quantile(sorted: List<Double>, p: Double): Double {
        val position = (sorted.size - 1) * p
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        return sorted[lower] * (1.0 - (position - lower)) + sorted[upper] * (position - lower)
    }

    companion object { const val MODEL_VERSION = "hybrid_meal_response_advisory_v1" }
}
