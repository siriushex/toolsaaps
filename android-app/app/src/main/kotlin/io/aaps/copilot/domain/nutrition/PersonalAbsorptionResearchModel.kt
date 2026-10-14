package io.aaps.copilot.domain.nutrition

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

internal fun personalAbsorptionComparisonCohort(sensorSession: String, calibration: String,
    insulinModel: String, insulinDurationMinutes: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("meal-comparison-v1", sensorSession, calibration, insulinModel, insulinDurationMinutes.toString()).forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun comparablePersonalAbsorptionEpisodes(request: PersonalAbsorptionRequest,
    source: PersonalAbsorptionSourceSnapshot): List<PersonalAbsorptionEpisode> {
    val zone = ZoneId.of(request.recordedZoneId)
    val end = Instant.ofEpochMilli(request.cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    val start = end.atZone(zone).minusDays(21).toInstant()
    return source.episodes.filter { episode ->
        val meal = episode.meal
        val minute = meal.timestamp.atZone(zone).toLocalTime().toSecondOfDay() / 60
        val distance = abs(minute - request.minuteOfDay)
        meal.timestamp >= start && meal.timestamp < end && episode.latestEvidenceAtMs <= request.cutoffMs &&
            episode.recordedZoneId == request.recordedZoneId && meal.profile == request.profile &&
            meal.portion == request.portion && min(distance, 1440 - distance) <= 120
    }
}

/** Immutable research artifact. A held-out response fit is not a validated glucose predictor. */
internal class PersonalAbsorptionResearchModel private constructor(
    val request: PersonalAbsorptionRequest,
    val comparisonCohort: String,
    val availableAtMs: Long,
    val evidenceDigest: String,
    val timingShape: PersonalAbsorptionShape,
    val minimumTrainingGrams: Double,
    val maximumTrainingGrams: Double,
    val giModel: PersonalGiModel?
) {
    val version: String get() = if (giModel == null) "personal-meal-timing-research-v1" else "personal-meal-gi-research-v1"
    val clinicalInfluenceAllowed: Boolean get() = false
    fun shapeFor(knownGi: Double?): PersonalAbsorptionShape? =
        if (giModel == null) timingShape else knownGi?.let(giModel::shapeFor)

    companion object {
        fun fit(request: PersonalAbsorptionRequest, source: PersonalAbsorptionSourceSnapshot,
            comparisonCohort: String, giEvidence: List<PersonalGiEvidence>, clockMs: () -> Long): PersonalAbsorptionResearchModel? {
            require(request.cutoffMs > 0 && request.minuteOfDay in 0..1439 &&
                request.cutoffMs == source.cutoffMs && request.recordedZoneId == source.recordedZoneId)
            if (source.status != PersonalAbsorptionSourceStatus.READY || source.episodes.size > 240 ||
                !comparisonCohort.matches(Regex("[a-f0-9]{64}")) || giEvidence.size > 5000) return null
            val episodes = comparablePersonalAbsorptionEpisodes(request, source).filter { it.comparisonCohort == comparisonCohort }
            val fit = PersonalAbsorptionEstimator().evaluatePrepared(request, PersonalAbsorptionCohort(episodes, emptyMap()))
            if (fit.status != PersonalAbsorptionStatus.EVALUATED) return null
            val shape = requireNotNull(fit.candidate)
            val training = splitPersonalAbsorptionDays(request, episodes).training
            val minimumGrams = training.minOf { it.meal.grams }
            val maximumGrams = training.maxOf { it.meal.grams }
            val gi = PersonalGiAnalyzer().analyze(request, source, giEvidence, comparisonCohort)
            val giModel = gi.model.takeIf { gi.status == PersonalGiStatus.ANALYZED &&
                gi.fitStatus == PersonalAbsorptionStatus.EVALUATED && !gi.coefficientAtSearchBoundary &&
                gi.giImprovesEveryHorizon && gi.horizons.all { horizon ->
                    horizon.personalWithGi!!.maeMmol < horizon.typeModel!!.maeMmol
                } }
            val available = clockMs()
            if (available < request.cutoffMs) return null
            val encoded = listOf("personal-meal-artifact-v1", comparisonCohort, fit.evidenceDigest,
                gi.evidenceDigest, shape.toString(), giModel.toString()).joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return PersonalAbsorptionResearchModel(request, comparisonCohort, available, digest, shape,
                minimumGrams, maximumGrams, giModel)
        }
    }
}
