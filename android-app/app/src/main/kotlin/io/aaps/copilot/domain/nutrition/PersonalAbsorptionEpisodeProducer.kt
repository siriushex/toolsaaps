package io.aaps.copilot.domain.nutrition

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

enum class PersonalAbsorptionSourceStatus { READY, UNAVAILABLE, OVERFLOW }
enum class PersonalAbsorptionGlucoseBasis { UNKNOWN, CONTEMPORANEOUS, RETROSPECTIVELY_RECALCULATED }
enum class PersonalAbsorptionSourceProblem {
    HISTORICAL_TIMEZONE, GLUCOSE_AVAILABILITY, GLUCOSE_BASIS, INDEPENDENT_INSULIN_INTERVALS,
    HISTORICAL_MODEL_CONTEXT, CONTEXT_REVIEW, SENSOR_IDENTITY, CALIBRATION_IDENTITY,
    PREPARATION_AVAILABILITY, UNBOUND_MEAL, MEAL_MISMATCH, CONFLICTING_MEAL,
    CONFLICTING_EVIDENCE, OUTSIDE_WINDOW, INCOMPLETE_EPISODE
}

/** Explicit research-source attestations, not inferred legacy metadata or clinical authority. */
data class PersonalAbsorptionEpisodeEvidence(
    val input: PersonalAbsorptionInput,
    val sensorSessionId: String?,
    val calibrationIdentity: String?,
    val contextReviewReference: String?,
    val preparedAtMs: Long,
    val glucoseBasis: PersonalAbsorptionGlucoseBasis = PersonalAbsorptionGlucoseBasis.UNKNOWN
)

class PersonalAbsorptionEpisodeOutcome internal constructor(
    val canonicalId: String,
    val therapyRevision: String?,
    val timestampMs: Long,
    val episode: PersonalAbsorptionEpisode?,
    sourceProblems: Set<PersonalAbsorptionSourceProblem>,
    preparationRejections: Set<PersonalAbsorptionRejection>
) {
    val sourceProblems: Set<PersonalAbsorptionSourceProblem> =
        Collections.unmodifiableSet(LinkedHashSet(sourceProblems))
    val preparationRejections: Set<PersonalAbsorptionRejection> =
        Collections.unmodifiableSet(LinkedHashSet(preparationRejections))
}

class PersonalAbsorptionSourceSnapshot internal constructor(
    val cutoffMs: Long,
    val recordedZoneId: String,
    val status: PersonalAbsorptionSourceStatus,
    outcomes: List<PersonalAbsorptionEpisodeOutcome>
) {
    val outcomes: List<PersonalAbsorptionEpisodeOutcome> = Collections.unmodifiableList(outcomes.toList())
    val episodes: List<PersonalAbsorptionEpisode> = Collections.unmodifiableList(outcomes.mapNotNull { it.episode })
    val clinicalInfluenceAllowed: Boolean get() = false
    val actualMassAccuracyValidated: Boolean get() = false
}

/** Completed conditional episodes; source qualification cannot authorize a target or dose. */
class PersonalAbsorptionEpisodeProducer {
    fun produce(
        cutoffMs: Long,
        recordedZoneId: String,
        canonicalMeals: List<MealPortionObservation>,
        evidence: List<PersonalAbsorptionEpisodeEvidence>,
        sourceStatus: PersonalAbsorptionSourceStatus = PersonalAbsorptionSourceStatus.READY
    ): PersonalAbsorptionSourceSnapshot {
        require(cutoffMs > 0 && recordedZoneId.length <= 128)
        val zone = ZoneId.of(recordedZoneId)
        val overflow = exceedsLimits(canonicalMeals, evidence)
        if (overflow || sourceStatus != PersonalAbsorptionSourceStatus.READY) {
            return PersonalAbsorptionSourceSnapshot(cutoffMs, zone.id,
                if (overflow) PersonalAbsorptionSourceStatus.OVERFLOW else sourceStatus, emptyList())
        }
        val end = Instant.ofEpochMilli(cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val start = end.atZone(zone).minusDays(21).toInstant()
        val labels = canonicalMeals.toList().groupBy { it.canonicalId }
        val frames = freezeEvidence(evidence).groupBy { it.input.meal.canonicalId }
        val overlap = overlappingLabels(labels.values.mapNotNull { it.distinct().singleOrNull() }
            .filter { validLabel(it) && it.timestamp >= start && it.timestamp < end &&
                it.availableAt.toEpochMilli() <= cutoffMs })
        val adapter = PersonalAbsorptionEvidenceAdapter()
        val outcomes = (labels.keys + frames.keys).map { id ->
            val labelVersions = labels[id].orEmpty().distinct()
            val frameVersions = frames[id].orEmpty().distinct()
            val label = labelVersions.singleOrNull()
            val frame = frameVersions.singleOrNull()
            val representative = label ?: labelVersions.minByOrNull { it.timestamp }
                ?: frameVersions.minBy { it.input.meal.timestamp }.input.meal
            val problems = linkedSetOf<PersonalAbsorptionSourceProblem>()
            val rejections = linkedSetOf<PersonalAbsorptionRejection>()
            if (labelVersions.isEmpty()) problems += PersonalAbsorptionSourceProblem.UNBOUND_MEAL
            if (labelVersions.size > 1) problems += PersonalAbsorptionSourceProblem.CONFLICTING_MEAL
            if (frameVersions.size > 1) problems += PersonalAbsorptionSourceProblem.CONFLICTING_EVIDENCE
            if (frameVersions.isEmpty()) problems += MISSING_SOURCE
            if (label != null) {
                if (!validLabel(label)) rejections += PersonalAbsorptionRejection.INVALID_MEAL
                if (label.timestamp < start || label.timestamp >= end)
                    problems += PersonalAbsorptionSourceProblem.OUTSIDE_WINDOW
                if (label.availableAt.toEpochMilli() > cutoffMs) rejections += PersonalAbsorptionRejection.FUTURE_EVIDENCE
                if (episodeEnd(label)?.let { it <= end.toEpochMilli() } != true)
                    problems += PersonalAbsorptionSourceProblem.INCOMPLETE_EPISODE
                if (id in overlap) rejections += PersonalAbsorptionRejection.OVERLAPPING_MEAL
            }
            if (frame != null) {
                if (frame.input.meal != label && label != null) problems += PersonalAbsorptionSourceProblem.MEAL_MISMATCH
                problems += missingSource(frame, cutoffMs, zone.id)
            }
            val prepared = if (problems.isEmpty() && rejections.isEmpty() && frame != null) {
                adapter.prepare(frame.input, cutoffMs)
            } else null
            prepared?.rejections?.let(rejections::addAll)
            val episode = prepared?.episode?.let { original ->
                // Review/provenance available later cannot be used in an earlier held-out fit.
                PersonalAbsorptionEpisode(original.meal, original.recordedZoneId, original.steps,
                    original.csfMmolPerGram, maxOf(original.latestEvidenceAtMs, frame!!.preparedAtMs),
                    sourceDigest(original.sourceDigest, frame), comparisonCohort(frame))
            }
            PersonalAbsorptionEpisodeOutcome(id, representative.therapyRevision,
                representative.timestamp.toEpochMilli(), episode, problems, rejections)
        }.sortedWith(compareBy<PersonalAbsorptionEpisodeOutcome> { it.timestampMs }.thenBy { it.canonicalId })
        return PersonalAbsorptionSourceSnapshot(cutoffMs, zone.id, PersonalAbsorptionSourceStatus.READY, outcomes)
    }

    private fun missingSource(frame: PersonalAbsorptionEpisodeEvidence, cutoffMs: Long, zone: String): Set<PersonalAbsorptionSourceProblem> {
        val input = frame.input
        val problems = linkedSetOf<PersonalAbsorptionSourceProblem>()
        if (input.recordedZoneId != zone || runCatching { ZoneId.of(input.recordedZoneId) }.isFailure)
            problems += PersonalAbsorptionSourceProblem.HISTORICAL_TIMEZONE
        if (input.glucose.isEmpty() || input.glucose.any { it.availableAtMs == null })
            problems += PersonalAbsorptionSourceProblem.GLUCOSE_AVAILABILITY
        if (frame.glucoseBasis != PersonalAbsorptionGlucoseBasis.CONTEMPORANEOUS)
            problems += PersonalAbsorptionSourceProblem.GLUCOSE_BASIS
        if (input.insulin.isEmpty()) problems += PersonalAbsorptionSourceProblem.INDEPENDENT_INSULIN_INTERVALS
        if (input.context == null) problems += PersonalAbsorptionSourceProblem.HISTORICAL_MODEL_CONTEXT
        if (!validReference(frame.sensorSessionId)) problems += PersonalAbsorptionSourceProblem.SENSOR_IDENTITY
        if (!validReference(frame.calibrationIdentity)) problems += PersonalAbsorptionSourceProblem.CALIBRATION_IDENTITY
        if (!validReference(frame.contextReviewReference) || !input.reviewed.containsAll(PersonalAbsorptionContextCheck.entries))
            problems += PersonalAbsorptionSourceProblem.CONTEXT_REVIEW
        val latestInput = maxOf(input.meal.availableAt.toEpochMilli(), input.context?.knownAtMs ?: 0L,
            input.glucose.maxOfOrNull { it.availableAtMs ?: 0L } ?: 0L,
            input.insulin.maxOfOrNull { it.availableAtMs ?: 0L } ?: 0L)
        if (frame.preparedAtMs !in 1..cutoffMs || frame.preparedAtMs < latestInput)
            problems += PersonalAbsorptionSourceProblem.PREPARATION_AVAILABILITY
        return problems
    }

    private fun overlappingLabels(meals: List<MealPortionObservation>): Set<String> {
        val ordered = meals.sortedWith(compareBy<MealPortionObservation> { it.timestamp }.thenBy { it.canonicalId })
        val result = mutableSetOf<String>()
        var previousEnd = Long.MIN_VALUE
        ordered.forEachIndexed { index, meal ->
            val end = episodeEnd(meal) ?: return@forEachIndexed
            if (previousEnd > meal.timestamp.toEpochMilli() ||
                ordered.getOrNull(index + 1)?.timestamp?.toEpochMilli()?.let { it < end } == true)
                result += meal.canonicalId
            previousEnd = maxOf(previousEnd, end)
        }
        return result
    }

    private fun validLabel(meal: MealPortionObservation): Boolean =
        meal.timestamp.toEpochMilli() > 0 && !meal.deleted && meal.canonicalId.isNotBlank() &&
            meal.canonicalId.length <= 128 && !meal.therapyRevision.isNullOrBlank() &&
            meal.therapyRevision.length <= 256 && meal.grams.isFinite() && meal.grams in 1.0..500.0 &&
            meal.availableAt >= meal.timestamp && meal.provenance in
            setOf(MealPortionProvenance.USER_ENTERED, MealPortionProvenance.USER_CORRECTED)

    private fun episodeEnd(meal: MealPortionObservation): Long? = runCatching {
        Math.addExact(meal.timestamp.toEpochMilli(), personalAbsorptionDurationRange(meal.profile).last * 60_000L)
    }.getOrNull()

    private fun validReference(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= 256 && value.none { it.isISOControl() }

    private fun sourceDigest(original: String, frame: PersonalAbsorptionEpisodeEvidence): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("qualified-episode-v1", original, frame.sensorSessionId!!, frame.calibrationIdentity!!,
            frame.contextReviewReference!!, frame.preparedAtMs.toString(), frame.glucoseBasis.name).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun comparisonCohort(frame: PersonalAbsorptionEpisodeEvidence): String {
        val context = requireNotNull(frame.input.context)
        return personalAbsorptionComparisonCohort(frame.sensorSessionId!!, frame.calibrationIdentity!!,
            context.insulinModelId, context.insulinDurationMinutes)
    }

    companion object {
        internal fun freezeEvidence(evidence: List<PersonalAbsorptionEpisodeEvidence>): List<PersonalAbsorptionEpisodeEvidence> =
            evidence.toList().map { frame -> frame.copy(input = frame.input.copy(
                glucose = frame.input.glucose.toList(), insulin = frame.input.insulin.toList(),
                reviewed = frame.input.reviewed.toSet(), confounds = frame.input.confounds.toSet())) }

        internal fun exceedsLimits(meals: List<MealPortionObservation>, evidence: List<PersonalAbsorptionEpisodeEvidence>): Boolean =
            meals.size > 5000 || evidence.size > 240 ||
                evidence.sumOf { it.input.glucose.size.toLong() } > 20_000 ||
                evidence.sumOf { it.input.insulin.size.toLong() } > 60_000

        private val MISSING_SOURCE = setOf(PersonalAbsorptionSourceProblem.HISTORICAL_TIMEZONE,
            PersonalAbsorptionSourceProblem.GLUCOSE_AVAILABILITY, PersonalAbsorptionSourceProblem.GLUCOSE_BASIS,
            PersonalAbsorptionSourceProblem.INDEPENDENT_INSULIN_INTERVALS,
            PersonalAbsorptionSourceProblem.HISTORICAL_MODEL_CONTEXT, PersonalAbsorptionSourceProblem.CONTEXT_REVIEW,
            PersonalAbsorptionSourceProblem.SENSOR_IDENTITY, PersonalAbsorptionSourceProblem.CALIBRATION_IDENTITY,
            PersonalAbsorptionSourceProblem.PREPARATION_AVAILABILITY)
    }
}
