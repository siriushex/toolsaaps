package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlin.math.abs
import kotlin.math.min

/** A known food attribute, never a GI inferred from the CGM outcome being scored. */
data class PersonalGiEvidence(
    val canonicalId: String, val therapyRevision: String,
    val value: Double, val source: String, val reference: String?, val availableAtMs: Long
)

enum class PersonalGiStatus {
    SOURCE_UNAVAILABLE, OVERFLOW, MIXED_CONTEXT, INSUFFICIENT_DATA,
    INSUFFICIENT_GI_VARIATION, ANALYZED
}

data class PersonalGiError(
    val maeMmol: Double, val biasMmol: Double, val maximumOverpredictionMmol: Double
)

data class PersonalGiHorizon(
    val minutes: Int, val meals: Int, val days: Int, val windows: Int,
    val typeModel: PersonalGiError?, val personalModel: PersonalGiError?,
    val personalWithGi: PersonalGiError?
)

/** Fixed declared-mass stress scenarios, not confidence bounds for actual food. */
data class PersonalGiMassStress(val declaredMassScale: Double, val horizons: List<PersonalGiHorizon>)

data class PersonalGiModel(
    val shape: PersonalAbsorptionShape, val centerGi: Double, val slopePer100Gi: Double,
    val minimumTrainingGi: Double, val maximumTrainingGi: Double
) {
    init {
        require(minimumTrainingGi.isFinite() && maximumTrainingGi.isFinite() &&
            minimumTrainingGi in 0.0..200.0 && maximumTrainingGi in minimumTrainingGi..200.0)
        require(centerGi.isFinite() && centerGi in minimumTrainingGi..maximumTrainingGi)
        require(slopePer100Gi.isFinite() && slopePer100Gi in -0.25..0.25)
    }
    fun shapeFor(gi: Double): PersonalAbsorptionShape? {
        if (!gi.isFinite() || gi !in minimumTrainingGi..maximumTrainingGi) return null
        val exponent = shape.exponent + slopePer100Gi * (gi - centerGi) / 100.0
        return if (exponent in 0.75..1.25) shape.copy(exponent = exponent) else null
    }
}

class PersonalGiAnalysis internal constructor(
    val status: PersonalGiStatus,
    val cutoffMs: Long,
    val trainingMeals: Int,
    val trainingDays: Int,
    val validationMeals: Int,
    val validationDays: Int,
    val model: PersonalGiModel?,
    val fitStatus: PersonalAbsorptionStatus?,
    val coefficientAtSearchBoundary: Boolean,
    val evidenceDigest: String,
    horizons: List<PersonalGiHorizon>,
    stress: List<PersonalGiMassStress>,
    rejections: Map<String, Int>,
    cohorts: Map<String, Int>
) {
    val horizons: List<PersonalGiHorizon> = Collections.unmodifiableList(horizons.toList())
    val massStress: List<PersonalGiMassStress> = Collections.unmodifiableList(stress.map {
        it.copy(horizons = Collections.unmodifiableList(it.horizons.toList()))
    })
    val rejections: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(rejections))
    val availableCohorts: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(cohorts))
    val giImprovesEveryHorizon: Boolean get() = horizons.size == 3 && horizons.all {
        it.personalWithGi != null && it.personalModel != null &&
            it.personalWithGi.maeMmol < it.personalModel.maeMmol
    }
    val clinicalInfluenceAllowed: Boolean get() = false
    val laboratoryGi: Double? get() = null
    val targetPercentage: Double? get() = null
    val prospectiveForecastValidated: Boolean get() = false
    val targetManagerBlockers: List<String> = Collections.unmodifiableList(listOf(
        "PROSPECTIVE_FORECAST_VALIDATION_REQUIRED",
        "INDEPENDENT_CLOSED_LOOP_SAFETY_REVIEW_REQUIRED",
        "CLINICAL_ACTIVATION_NOT_AUTHORIZED"
    ))
    val modelVersion: String get() = "personal_gi_conditional_analysis_v1"
}

/**
 * Offline GI ablation on qualified episodes. Scores insulin-corrected response
 * increments, not prospective glucose forecasts or outcomes of different targets.
 */
class PersonalGiAnalyzer {
    fun analyze(request: PersonalAbsorptionRequest, source: PersonalAbsorptionSourceSnapshot,
        giEvidence: List<PersonalGiEvidence>, comparisonCohort: String? = null): PersonalGiAnalysis {
        require(request.cutoffMs > 0 && request.minuteOfDay in 0..1439)
        require(request.cutoffMs == source.cutoffMs && request.recordedZoneId == source.recordedZoneId)
        val zone = ZoneId.of(request.recordedZoneId)
        val rejected = sortedMapOf<String, Int>()
        fun reject(reason: String, count: Int = 1) { if (count > 0) rejected[reason] = (rejected[reason] ?: 0) + count }
        var cohorts = emptyMap<String, Int>()
        var training = emptyList<PersonalAbsorptionEpisode>()
        var validation = emptyList<PersonalAbsorptionEpisode>()
        fun days(rows: List<PersonalAbsorptionEpisode>) = rows.map { it.meal.timestamp.atZone(zone).toLocalDate() }.distinct().size
        var digest = ""
        fun result(status: PersonalGiStatus, model: PersonalGiModel? = null,
            fit: PersonalAbsorptionStatus? = null, bounded: Boolean = false,
            horizons: List<PersonalGiHorizon> = emptyList(), stress: List<PersonalGiMassStress> = emptyList()) =
            PersonalGiAnalysis(status, request.cutoffMs, training.size, days(training), validation.size,
                days(validation), model, fit, bounded, digest, horizons, stress, rejected, cohorts)
        if (source.status == PersonalAbsorptionSourceStatus.OVERFLOW || giEvidence.size > 5000 || source.episodes.size > 240)
            return result(PersonalGiStatus.OVERFLOW)
        if (giEvidence.any { it.canonicalId.length > 128 || it.therapyRevision.length > 256 ||
                it.source.length > 32 || (it.reference?.length ?: 0) > 256 }) return result(PersonalGiStatus.OVERFLOW)
        if (source.status != PersonalAbsorptionSourceStatus.READY) return result(PersonalGiStatus.SOURCE_UNAVAILABLE)
        source.outcomes.forEach { outcome ->
            outcome.sourceProblems.forEach { reject("SOURCE_${it.name}") }
            outcome.preparationRejections.forEach { reject("EPISODE_${it.name}") }
        }
        val end = Instant.ofEpochMilli(request.cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val start = end.atZone(zone).minusDays(21).toInstant()
        val comparable = source.episodes.filter { episode ->
            val meal = episode.meal
            val minute = meal.timestamp.atZone(zone).toLocalTime().toSecondOfDay() / 60
            val distance = abs(minute - request.minuteOfDay)
            val valid = meal.timestamp >= start && meal.timestamp < end &&
                episode.latestEvidenceAtMs <= request.cutoffMs && episode.recordedZoneId == request.recordedZoneId &&
                meal.profile == request.profile && meal.portion == request.portion && min(distance, 1440 - distance) <= 120
            if (!valid) reject("NOT_COMPARABLE")
            valid
        }
        cohorts = comparable.mapNotNull { it.comparisonCohort }.groupingBy { it }.eachCount().toSortedMap()
        val selectedCohort = comparisonCohort ?: cohorts.keys.singleOrNull()
        if (selectedCohort == null && cohorts.size > 1) return result(PersonalGiStatus.MIXED_CONTEXT)
        val selected = comparable.filter {
            (it.comparisonCohort != null && it.comparisonCohort == selectedCohort).also { valid ->
                if (!valid) reject("UNQUALIFIED_OR_DIFFERENT_COHORT")
            }
        }
        val suppliedGi = giEvidence.toList().groupBy { it.canonicalId }
        val indexes = sortedMapOf<String, PersonalGiEvidence>()
        // Select dates before removing absent GI, so missingness cannot move the holdout boundary.
        val originalSplit = splitPersonalAbsorptionDays(request, selected)
        val eligible = selected.mapNotNull { episode ->
            val versions = suppliedGi[episode.meal.canonicalId].orEmpty().distinct()
            val gi = versions.singleOrNull()
            val reason = when {
                versions.isEmpty() -> "MISSING_GI"
                gi == null -> "CONFLICTING_GI"
                gi.therapyRevision != episode.meal.therapyRevision -> "GI_REVISION_MISMATCH"
                MealGlycemicIndex.fromStored(gi.value, gi.source, gi.reference) == null -> "INVALID_GI"
                gi.availableAtMs <= 0 || gi.availableAtMs > request.cutoffMs -> "GI_AVAILABILITY"
                episode.meal.timestamp.toEpochMilli() < originalSplit.boundaryMs &&
                    maxOf(episode.latestEvidenceAtMs, gi.availableAtMs) >= originalSplit.boundaryMs -> "LATE_TRAINING_EVIDENCE"
                else -> null
            }
            if (reason != null) { reject(reason); null } else {
                indexes[episode.meal.canonicalId] = requireNotNull(gi)
                episode
            }
        }
        training = eligible.filter { it.meal.timestamp.toEpochMilli() < originalSplit.boundaryMs }
        validation = eligible.filter { it.meal.timestamp.toEpochMilli() >= originalSplit.boundaryMs }
        digest = digest(request, selectedCohort, selected, giEvidence)
        if (training.size < 8 || days(training) < 5 || validation.size < 3 || days(validation) < 2)
            return result(PersonalGiStatus.INSUFFICIENT_DATA)
        val trainGi = training.map { indexes.getValue(it.meal.canonicalId).value }
        if (trainGi.distinct().size < 2) return result(PersonalGiStatus.INSUFFICIENT_GI_VARIATION)
        // Reuse the timing fit, with the ORIGINAL split held fixed despite absent GI.
        val fit = PersonalAbsorptionEstimator().evaluatePreparedSplit(request, training, validation)
        val personal = fit.candidate ?: return result(PersonalGiStatus.INSUFFICIENT_DATA, fit = fit.status)
        val center = trainGi.average()
        val minimum = trainGi.min()
        val maximum = trainGi.max()
        fun model(slope: Double) = PersonalGiModel(personal, center, slope, minimum, maximum)
        val slopes = (-10..10).map { it * 0.025 }.filter { slope ->
            model(slope).shapeFor(minimum) != null && model(slope).shapeFor(maximum) != null
        }
        val best = slopes.minWith(compareBy<Double> { slope ->
            training.map { episode ->
                val shape = requireNotNull(model(slope).shapeFor(indexes.getValue(episode.meal.canonicalId).value))
                episode.steps.map { step ->
                    val error = predicted(episode, shape, step.ageMinutes, 5, 1.0) - step.residualMmol
                    val a = abs(error)
                    if (a <= 0.3) error * error / 2 else 0.3 * (a - 0.15)
                }.average()
            }.average()
        }.thenBy { abs(it) })
        val fitted = model(best * training.size.toDouble() / (training.size + 8))
        validation = validation.filter { episode ->
            (fitted.shapeFor(indexes.getValue(episode.meal.canonicalId).value) != null).also {
                if (!it) reject("GI_OUTSIDE_TRAINING_SUPPORT")
            }
        }
        if (validation.size < 3 || days(validation) < 2) return result(PersonalGiStatus.INSUFFICIENT_DATA, fit = fit.status)
        val baseline = PersonalAbsorptionShape(request.profile, MealAbsorptionCurve(request.profile, null).durationMinutes, 1.0)
        fun score(scale: Double) = listOf(5, 30, 60).map { horizon ->
            scoreHorizon(validation, indexes, zone, baseline, fitted, horizon, scale)
        }
        return result(PersonalGiStatus.ANALYZED, fitted, fit.status, abs(best) >= 0.25 - 1e-9,
            score(1.0), listOf(0.8, 1.2).map { PersonalGiMassStress(it, score(it)) })
    }

    private fun scoreHorizon(episodes: List<PersonalAbsorptionEpisode>, gi: Map<String, PersonalGiEvidence>,
        zone: ZoneId, baseline: PersonalAbsorptionShape, model: PersonalGiModel,
        horizon: Int, scale: Double): PersonalGiHorizon {
        data class MealErrors(val episode: PersonalAbsorptionEpisode, val errors: List<List<Double>>)
        val scored = episodes.mapNotNull { episode ->
            val steps = episode.steps.associateBy { it.ageMinutes }
            val ages = steps.keys.filter { end -> (end - horizon + 5..end step 5).all { it > 0 && it in steps } }.sorted()
            if (ages.isEmpty()) return@mapNotNull null
            val shapes = listOf(baseline, model.shape, requireNotNull(model.shapeFor(gi.getValue(episode.meal.canonicalId).value)))
            MealErrors(episode, shapes.map { shape -> ages.map { end ->
                predicted(episode, shape, end, horizon, scale) -
                    (end - horizon + 5..end step 5).sumOf { steps.getValue(it).residualMmol }
            } })
        }
        val byDay = scored.groupBy { it.episode.meal.timestamp.atZone(zone).toLocalDate() }
        fun error(index: Int): PersonalGiError? {
            if (scored.isEmpty()) return null
            val mae = byDay.values.map { rows -> rows.map { it.errors[index].map(::abs).average() }.average() }.average()
            val bias = byDay.values.map { rows -> rows.map { it.errors[index].average() }.average() }.average()
            val maximum = scored.maxOf { row -> row.errors[index].max().coerceAtLeast(0.0) }
            return PersonalGiError(mae, bias, maximum)
        }
        return PersonalGiHorizon(horizon, scored.size, byDay.size, scored.sumOf { it.errors[0].size }, error(0), error(1), error(2))
    }

    private fun predicted(episode: PersonalAbsorptionEpisode, shape: PersonalAbsorptionShape,
        age: Int, horizon: Int, scale: Double): Double = episode.meal.grams * scale * episode.csfMmolPerGram *
        (shape.cumulative(age.toDouble()) - shape.cumulative((age - horizon).toDouble()))

    private fun digest(request: PersonalAbsorptionRequest, cohort: String?, rows: List<PersonalAbsorptionEpisode>,
        gi: List<PersonalGiEvidence>): String {
        val value = buildString {
            append("personal_gi_conditional_analysis_v1|").append(request).append('|').append(cohort).append('\n')
            rows.sortedBy { it.meal.canonicalId }.forEach { append(it.sourceDigest).append('|').append(it.latestEvidenceAtMs).append('\n') }
            gi.distinct().sortedWith(compareBy<PersonalGiEvidence> { it.canonicalId }.thenBy { it.toString() })
                .forEach { append(it).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
