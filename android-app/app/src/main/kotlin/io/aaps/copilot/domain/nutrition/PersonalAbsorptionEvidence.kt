package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.util.Collections
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import java.time.ZoneId

enum class PersonalAbsorptionContextCheck { SENSOR, CALIBRATION, DELIVERY, ACTIVITY, ILLNESS, OTHER_FOOD }
enum class PersonalAbsorptionConfound {
    SENSOR_ERROR, CALIBRATION_CHANGE, DELIVERY_FAULT, UNEXPLAINED_INSULIN,
    ACTIVITY, ILLNESS, RESCUE, HYPOGLYCEMIA, OVERLAPPING_MEAL
}
enum class PersonalAbsorptionRejection {
    INVALID_MEAL, MISSING_CONTEXT, INVALID_CONTEXT, UNKNOWN_CONTEXT, FUTURE_EVIDENCE,
    UNKNOWN_AVAILABILITY, INSUFFICIENT_CGM, INVALID_INSULIN, INSUFFICIENT_INSULIN,
    CONFLICTING_IDENTITY, LATE_TRAINING_EVIDENCE, OUTSIDE_WINDOW, NOT_COMPARABLE, OVERFLOW,
    SENSOR_ERROR, CALIBRATION_CHANGE, DELIVERY_FAULT, UNEXPLAINED_INSULIN,
    ACTIVITY, ILLNESS, RESCUE, HYPOGLYCEMIA, OVERLAPPING_MEAL
}
enum class PersonalAbsorptionInsulinSource { UNKNOWN, INDEPENDENT_COMPONENTS, CONFIRMED_DELIVERY_MODEL }

data class PersonalAbsorptionGlucose(
    val timestampMs: Long, val mmol: Double, val quality: DataQuality,
    val availableAtMs: Long?
)
data class PersonalAbsorptionInsulinInterval(
    val startMs: Long, val endMs: Long, val activityUnits: Double,
    val source: PersonalAbsorptionInsulinSource, val availableAtMs: Long?
)
data class PersonalAbsorptionContext(
    val isfMmolPerUnit: Double, val carbRatioGramsPerUnit: Double,
    val insulinModelId: String, val insulinDurationMinutes: Int,
    val knownAtMs: Long, val insulinHistoryCovered: Boolean
)
data class PersonalAbsorptionInput(
    val meal: MealPortionObservation, val recordedZoneId: String,
    val glucose: List<PersonalAbsorptionGlucose>,
    val insulin: List<PersonalAbsorptionInsulinInterval>,
    val context: PersonalAbsorptionContext?,
    val reviewed: Set<PersonalAbsorptionContextCheck>,
    val confounds: Set<PersonalAbsorptionConfound>
)
data class PersonalAbsorptionShape(
    val profile: MealAbsorptionProfile, val durationMinutes: Int, val exponent: Double
) {
    init {
        require(durationMinutes in personalAbsorptionDurationRange(profile) && durationMinutes % 5 == 0)
        require(exponent.isFinite() && exponent in 0.75..1.25)
    }
    fun cumulative(ageMinutes: Double): Double =
        MealAbsorptionCurve(profile, durationMinutes).absorbedFraction(ageMinutes).pow(exponent)
    fun absorbedGrams(declaredGrams: Double, ageMinutes: Double): Double {
        require(declaredGrams.isFinite() && declaredGrams >= 0.0)
        return declaredGrams * cumulative(ageMinutes)
    }
}
internal fun personalAbsorptionDurationRange(profile: MealAbsorptionProfile): IntRange = when (profile) {
    MealAbsorptionProfile.FAST -> 30..60
    MealAbsorptionProfile.MIXED -> 60..180
    MealAbsorptionProfile.FAT_PROTEIN -> 180..360
}
data class PersonalAbsorptionStep(val ageMinutes: Int, val residualMmol: Double)
class PersonalAbsorptionEpisode internal constructor(
    val meal: MealPortionObservation, val recordedZoneId: String,
    steps: List<PersonalAbsorptionStep>, val csfMmolPerGram: Double,
    val latestEvidenceAtMs: Long, val sourceDigest: String
) {
    val steps: List<PersonalAbsorptionStep> = Collections.unmodifiableList(steps.toList())
}
data class PersonalAbsorptionPreparation(
    val episode: PersonalAbsorptionEpisode?, val rejections: Set<PersonalAbsorptionRejection>
)

/** Explicit diagnostic inputs only. Never reconstructs unknown insulin as zero. */
class PersonalAbsorptionEvidenceAdapter {
    fun prepare(input: PersonalAbsorptionInput, cutoffMs: Long): PersonalAbsorptionPreparation {
        fun reject(reason: PersonalAbsorptionRejection) = PersonalAbsorptionPreparation(null, setOf(reason))
        if (runCatching { ZoneId.of(input.recordedZoneId) }.isFailure)
            return reject(PersonalAbsorptionRejection.INVALID_CONTEXT)
        if (input.glucose.size > 10000 || input.insulin.size > 30000)
            return reject(PersonalAbsorptionRejection.OVERFLOW)
        val meal = input.meal
        val ts = meal.timestamp.toEpochMilli()
        if (ts <= 0 || meal.canonicalId.isBlank() || meal.canonicalId.length > 128 ||
            meal.therapyRevision.isNullOrBlank() || meal.therapyRevision.length > 256 || meal.deleted ||
            !meal.grams.isFinite() || meal.grams !in 1.0..500.0 ||
            meal.provenance !in setOf(MealPortionProvenance.USER_ENTERED, MealPortionProvenance.USER_CORRECTED))
            return reject(PersonalAbsorptionRejection.INVALID_MEAL)
        if (meal.availableAt < meal.timestamp || meal.availableAt.toEpochMilli() > cutoffMs)
            return reject(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
        if (input.confounds.isNotEmpty()) return PersonalAbsorptionPreparation(null,
            input.confounds.map { PersonalAbsorptionRejection.valueOf(it.name) }.toSet())
        if (!input.reviewed.containsAll(PersonalAbsorptionContextCheck.entries))
            return reject(PersonalAbsorptionRejection.UNKNOWN_CONTEXT)
        val context = input.context ?: return reject(PersonalAbsorptionRejection.MISSING_CONTEXT)
        val csf = context.isfMmolPerUnit / context.carbRatioGramsPerUnit
        if (!context.isfMmolPerUnit.isFinite() || context.isfMmolPerUnit <= 0 ||
            !context.carbRatioGramsPerUnit.isFinite() || context.carbRatioGramsPerUnit <= 0 ||
            !csf.isFinite() || csf <= 0 || csf > 10 || context.isfMmolPerUnit > 100 ||
            context.insulinModelId.isBlank() || context.insulinModelId.length > 128 ||
            context.insulinDurationMinutes !in 30..720 || context.knownAtMs !in 1..ts ||
            !context.insulinHistoryCovered)
            return reject(PersonalAbsorptionRejection.INVALID_CONTEXT)
        val maximum = personalAbsorptionDurationRange(meal.profile).last
        val start = ts - 45 * MINUTE_MS
        val end = ts + maximum * MINUTE_MS
        if (end > cutoffMs) return reject(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
        val glucose = input.glucose.filter { it.timestampMs in (start - 10 * MINUTE_MS)..(end + 10 * MINUTE_MS) }
            .distinct().sortedBy { it.timestampMs }
        if (glucose.any { !it.mmol.isFinite() || it.mmol !in 0.1..100.0 || it.quality != DataQuality.OK } ||
            glucose.zipWithNext().any { it.first.timestampMs == it.second.timestampMs })
            return reject(PersonalAbsorptionRejection.SENSOR_ERROR)
        if (glucose.any { it.availableAtMs == null })
            return reject(PersonalAbsorptionRejection.UNKNOWN_AVAILABILITY)
        if (glucose.any { it.availableAtMs!! < it.timestampMs || it.availableAtMs > cutoffMs })
            return reject(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
        if (glucose.size < 2 || glucose.zipWithNext().any { it.second.timestampMs - it.first.timestampMs > 10 * MINUTE_MS })
            return reject(PersonalAbsorptionRejection.INSUFFICIENT_CGM)
        val insulin = input.insulin.filter { it.endMs > start && it.startMs < end }
            .distinct().sortedWith(compareBy<PersonalAbsorptionInsulinInterval> { it.startMs }.thenBy { it.endMs })
        if (insulin.any { it.startMs <= 0 || it.endMs <= it.startMs || it.endMs - it.startMs > 10 * MINUTE_MS ||
                !it.activityUnits.isFinite() || abs(it.activityUnits) > 100 ||
                it.source !in setOf(PersonalAbsorptionInsulinSource.INDEPENDENT_COMPONENTS,
                    PersonalAbsorptionInsulinSource.CONFIRMED_DELIVERY_MODEL) } ||
            insulin.zipWithNext().any { it.first.endMs > it.second.startMs })
            return reject(PersonalAbsorptionRejection.INVALID_INSULIN)
        if (insulin.any { it.availableAtMs == null })
            return reject(PersonalAbsorptionRejection.UNKNOWN_AVAILABILITY)
        if (insulin.any { it.availableAtMs!! < it.endMs || it.availableAtMs > cutoffMs })
            return reject(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
        val pre = mutableListOf<Double>()
        val post = mutableListOf<PersonalAbsorptionStep>()
        for (age in -40..maximum step 5) {
            val a = ts + (age - 5) * MINUTE_MS
            val b = ts + age * MINUTE_MS
            val units = integrate(insulin, a, b) ?: return reject(PersonalAbsorptionRejection.INSUFFICIENT_INSULIN)
            val ga = interpolate(glucose, a) ?: continue
            val gb = interpolate(glucose, b) ?: continue
            val corrected = gb - ga + context.isfMmolPerUnit * units
            if (!corrected.isFinite()) return reject(PersonalAbsorptionRejection.INVALID_CONTEXT)
            if (age <= 0) pre += corrected else post += PersonalAbsorptionStep(age, corrected)
        }
        if (pre.size < ceil(9 * 0.90).toInt() || post.size < ceil(maximum / 5 * 0.90).toInt())
            return reject(PersonalAbsorptionRejection.INSUFFICIENT_CGM)
        val background = pre.sorted()[pre.size / 2]
        val residuals = post.map { it.copy(residualMmol = it.residualMmol - background) }
        val available = maxOf(meal.availableAt.toEpochMilli(), context.knownAtMs,
            glucose.maxOf { it.availableAtMs!! }, insulin.maxOf { it.availableAtMs!! })
        val source = listOf(meal.toString(), input.recordedZoneId, context.toString(), glucose.toString(), insulin.toString())
            .joinToString("\n")
        val sourceDigest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PersonalAbsorptionPreparation(PersonalAbsorptionEpisode(meal, input.recordedZoneId,
            residuals, csf, available, sourceDigest), emptySet())
    }

    private fun interpolate(points: List<PersonalAbsorptionGlucose>, ts: Long): Double? {
        val found = points.binarySearchBy(ts) { it.timestampMs }
        if (found >= 0) return points[found].mmol
        val next = -found - 1
        if (next <= 0 || next >= points.size) return null
        val a = points[next - 1]
        val b = points[next]
        val gap = b.timestampMs - a.timestampMs
        if (gap > 10 * MINUTE_MS) return null
        return a.mmol + (b.mmol - a.mmol) * (ts - a.timestampMs).toDouble() / gap
    }

    private fun integrate(points: List<PersonalAbsorptionInsulinInterval>, start: Long, end: Long): Double? {
        val found = points.binarySearchBy(start) { it.startMs }
        var index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        var cursor = start
        var result = 0.0
        while (index < points.size && cursor < end) {
            val p = points[index++]
            if (p.endMs <= cursor) continue
            if (p.startMs > cursor) return null
            val through = minOf(end, p.endMs)
            result += p.activityUnits * (through - cursor).toDouble() / (p.endMs - p.startMs)
            cursor = through
        }
        return result.takeIf { cursor == end && it.isFinite() }
    }
    private companion object { const val MINUTE_MS = 60000L }
}
