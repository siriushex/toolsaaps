package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class PersonalAbsorptionRequest(
    val cutoffMs: Long, val profile: MealAbsorptionProfile, val portion: MealPortion,
    val recordedZoneId: String, val minuteOfDay: Int
)
enum class PersonalAbsorptionStatus { INSUFFICIENT_DATA, OVERFLOW, AMBIGUOUS, NOT_IMPROVED, BOUNDED_CANDIDATE, EVALUATED }
data class PersonalAbsorptionEnvelope(val minimumDuration: Int, val maximumDuration: Int,
    val minimumExponent: Double, val maximumExponent: Double)
data class PersonalAbsorptionDayComparison(val date: LocalDate, val meals: Int,
    val baselineMaeMmol5: Double, val candidateMaeMmol5: Double)
data class PersonalAbsorptionEvaluation(
    val status: PersonalAbsorptionStatus,
    val trainingMeals: Int = 0, val trainingDays: Int = 0,
    val validationMeals: Int = 0, val validationDays: Int = 0,
    val candidate: PersonalAbsorptionShape? = null,
    val baselineMaeMmol5: Double? = null, val candidateMaeMmol5: Double? = null,
    val baselineBiasMmol5: Double? = null, val candidateBiasMmol5: Double? = null,
    val envelope: PersonalAbsorptionEnvelope? = null,
    val days: List<PersonalAbsorptionDayComparison> = emptyList(),
    val rejections: Map<PersonalAbsorptionRejection, Int> = emptyMap(),
    val evidenceDigest: String = "", val evidenceCutoffMs: Long = 0,
    val modelVersion: String = PersonalAbsorptionEstimator.MODEL_VERSION
) {
    val clinicalInfluenceAllowed: Boolean get() = false
    val actualMassAccuracyValidated: Boolean get() = false
    val prospectiveForecastMae: Double? get() = null
    val laboratoryGi: Double? get() = null
}

/** Retrospective response reconstruction conditional on declared mass/exposures. */
class PersonalAbsorptionEstimator {
    fun evaluate(request: PersonalAbsorptionRequest, inputs: List<PersonalAbsorptionInput>): PersonalAbsorptionEvaluation =
        evaluatePrepared(request, preparePersonalAbsorptionCohort(request, inputs))

    internal fun evaluatePrepared(request: PersonalAbsorptionRequest,
        cohort: PersonalAbsorptionCohort): PersonalAbsorptionEvaluation {
        val zone = ZoneId.of(request.recordedZoneId)
        if (cohort.overflow) return PersonalAbsorptionEvaluation(PersonalAbsorptionStatus.OVERFLOW,
            rejections = mapOf(PersonalAbsorptionRejection.OVERFLOW to 1), evidenceCutoffMs = request.cutoffMs)
        val rejects = cohort.rejections.toMutableMap()
        fun dropped(reason: PersonalAbsorptionRejection) { rejects[reason] = (rejects[reason] ?: 0) + 1 }
        val episodes = cohort.episodes
        fun date(e: PersonalAbsorptionEpisode) = e.meal.timestamp.atZone(zone).toLocalDate()
        val dates = episodes.map(::date).distinct().sorted()
        val testDays = max(2, ceil(dates.size / 3.0).toInt())
        val boundary = dates.getOrNull((dates.size - testDays).coerceAtLeast(0))
        val boundaryMs = boundary?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0
        val train = episodes.filter { boundary != null && date(it) < boundary }.filter {
            (it.latestEvidenceAtMs < boundaryMs).also { valid ->
                if (!valid) dropped(PersonalAbsorptionRejection.LATE_TRAINING_EVIDENCE)
            }
        }
        val test = episodes.filter { boundary != null && date(it) >= boundary }
        val digest = digest(request, episodes)
        val base = PersonalAbsorptionEvaluation(PersonalAbsorptionStatus.INSUFFICIENT_DATA,
            train.size, train.map(::date).distinct().size, test.size, test.map(::date).distinct().size,
            rejections = rejects.toMap(), evidenceDigest = digest, evidenceCutoffMs = request.cutoffMs)
        if (base.trainingMeals < 8 || base.trainingDays < 5 || base.validationMeals < 3 || base.validationDays < 2) return base
        val baselineDuration = MealAbsorptionCurve(request.profile, null).durationMinutes
        val baseline = PersonalAbsorptionShape(request.profile, baselineDuration, 1.0)
        val candidates = (personalAbsorptionDurationRange(request.profile) step 5).flatMap { duration ->
            (0..20).map { index -> PersonalAbsorptionShape(request.profile, duration, 0.75 + index * 0.025) }
        }
        val scored = candidates.map { it to metrics(train, it).loss }
        val best = scored.minWith(compareBy<Pair<PersonalAbsorptionShape, Double>> { it.second }
            .thenBy { abs(it.first.durationMinutes - baselineDuration) }.thenBy { abs(it.first.exponent - 1.0) }).first
        val bestLoss = scored.first { it.first == best }.second
        val near = scored.filter { it.second <= bestLoss + max(1e-6, bestLoss * 0.01) }.map { it.first }
        val envelope = PersonalAbsorptionEnvelope(near.minOf { it.durationMinutes }, near.maxOf { it.durationMinutes },
            near.minOf { it.exponent }, near.maxOf { it.exponent })
        val weight = train.size.toDouble() / (train.size + 8)
        val shrunk = PersonalAbsorptionShape(request.profile,
            ((baselineDuration + weight * (best.durationMinutes - baselineDuration)) / 5.0).roundToInt() * 5,
            1.0 + weight * (best.exponent - 1.0))
        val b = metrics(test, baseline)
        val c = metrics(test, shrunk)
        val ambiguous = envelope.maximumDuration - envelope.minimumDuration >= 30 ||
            envelope.maximumExponent - envelope.minimumExponent >= 0.20 - 1e-9
        val durationRange = personalAbsorptionDurationRange(request.profile)
        val status = when {
            ambiguous -> PersonalAbsorptionStatus.AMBIGUOUS
            b.mae <= 1e-9 || c.mae >= b.mae * 0.95 -> PersonalAbsorptionStatus.NOT_IMPROVED
            best.durationMinutes == durationRange.first || best.durationMinutes == durationRange.last -> PersonalAbsorptionStatus.BOUNDED_CANDIDATE
            else -> PersonalAbsorptionStatus.EVALUATED
        }
        return base.copy(status = status, candidate = shrunk, envelope = envelope,
            baselineMaeMmol5 = b.mae, candidateMaeMmol5 = c.mae,
            baselineBiasMmol5 = b.bias, candidateBiasMmol5 = c.bias,
            days = test.groupBy(::date).toSortedMap().map { (day, rows) ->
                PersonalAbsorptionDayComparison(day, rows.size, metrics(rows, baseline).mae, metrics(rows, shrunk).mae)
            })
    }

    private data class Metrics(val loss: Double, val mae: Double, val bias: Double)
    private fun metrics(episodes: List<PersonalAbsorptionEpisode>, shape: PersonalAbsorptionShape): Metrics {
        val perMeal = episodes.map { episode ->
            val errors = episode.steps.map { step ->
                val food = episode.meal.grams * episode.csfMmolPerGram *
                    (shape.cumulative(step.ageMinutes.toDouble()) - shape.cumulative(step.ageMinutes - 5.0))
                food - step.residualMmol
            }
            Metrics(errors.map { val a = abs(it); if (a <= 0.3) it * it / 2 else 0.3 * (a - 0.15) }.average(),
                errors.map(::abs).average(), errors.average())
        }
        return Metrics(perMeal.map { it.loss }.average(), perMeal.map { it.mae }.average(), perMeal.map { it.bias }.average())
    }
    private fun digest(request: PersonalAbsorptionRequest, rows: List<PersonalAbsorptionEpisode>): String {
        val source = buildString {
            append(MODEL_VERSION).append('|').append(request).append('\n')
            rows.forEach { row ->
                append(row.meal).append('|').append(row.recordedZoneId).append('|')
                    .append(row.csfMmolPerGram).append('|').append(row.latestEvidenceAtMs).append('|')
                    .append(row.sourceDigest).append('|')
                    .append(row.steps).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object { const val MODEL_VERSION = "personal_apparent_absorption_shadow_v1" }
}
