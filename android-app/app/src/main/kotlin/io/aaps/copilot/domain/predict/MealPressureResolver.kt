package io.aaps.copilot.domain.predict

import kotlin.math.max

internal data class MealPressureInput(
    val announcedCarbSteps: DoubleArray,
    val uamSteps: DoubleArray,
    val residualCobNowGrams: Double,
    val announcedCarbCoverage: Double,
    val uamConfidence: Double
)

internal data class ResolvedMealPressure(
    private var stepsStorage: DoubleArray,
    val announcedWeight: Double,
    val uamWeight: Double,
    val source: String
) {
    init {
        stepsStorage = stepsStorage.copyOf()
    }

    val steps: DoubleArray
        get() = stepsStorage.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedMealPressure) return false

        return stepsStorage.contentEquals(other.stepsStorage) &&
            announcedWeight.toBits() == other.announcedWeight.toBits() &&
            uamWeight.toBits() == other.uamWeight.toBits() &&
            source == other.source
    }

    override fun hashCode(): Int {
        var result = stepsStorage.contentHashCode()
        result = 31 * result + announcedWeight.hashCode()
        result = 31 * result + uamWeight.hashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}

internal object MealPressureResolver {
    fun resolve(input: MealPressureInput): ResolvedMealPressure {
        val announced = input.announcedCarbSteps
        val uam = input.uamSteps
        val outputSize = max(announced.size, uam.size)
        val announcedValid = announced.isNotEmpty() && announced.all { it.isValidPressureStep() }
        if (!announcedValid) {
            return invalidZero(outputSize)
        }
        val uamValid = uam.size == announced.size && uam.all { it.isValidPressureStep() }
        if (!uamValid) {
            return resolved(
                steps = announced,
                announcedWeight = 1.0,
                uamWeight = 0.0,
                source = SOURCE_INVALID_INPUT
            )
        }

        val residualCob = input.residualCobNowGrams.clampedNonNegative()
        val coverage = input.announcedCarbCoverage.clampedUnitInterval()
        val confidence = input.uamConfidence.clampedUnitInterval()
        val hasAnnounced = announced.any { it > 0.0 }
        val hasUam = uam.any { it > 0.0 }

        if (residualCob > 0.0 && coverage < 1.0) {
            val attenuatedUamWeight = if (hasUam) confidence * (1.0 - coverage) else 0.0
            return resolved(
                steps = DoubleArray(announced.size) { index ->
                    max(announced[index], uam[index].scaledAndBounded(attenuatedUamWeight))
                },
                announcedWeight = 1.0,
                uamWeight = attenuatedUamWeight,
                source = SOURCE_INCOMPLETE_CARB_HISTORY
            )
        }
        if (!hasAnnounced && !hasUam) {
            return resolved(
                steps = DoubleArray(announced.size),
                announcedWeight = 0.0,
                uamWeight = 0.0,
                source = SOURCE_NONE
            )
        }
        if (!hasAnnounced) {
            return resolved(
                steps = uam.scaledBy(confidence),
                announcedWeight = 0.0,
                uamWeight = confidence,
                source = SOURCE_UAM_ONLY
            )
        }
        if (!hasUam) {
            return resolved(
                steps = announced,
                announcedWeight = 1.0,
                uamWeight = 0.0,
                source = SOURCE_ANNOUNCED_ONLY
            )
        }

        val totalWeight = coverage + confidence
        if (totalWeight <= 0.0) {
            return resolved(
                steps = announced,
                announcedWeight = 1.0,
                uamWeight = 0.0,
                source = SOURCE_ANNOUNCED_ONLY
            )
        }

        val announcedWeight = coverage / totalWeight
        val uamWeight = 1.0 - announcedWeight
        return resolved(
            steps = DoubleArray(announced.size) { index ->
                boundedBlend(
                    announced = announced[index],
                    uam = uam[index],
                    announcedWeight = announcedWeight,
                    uamWeight = uamWeight
                )
            },
            announcedWeight = announcedWeight,
            uamWeight = uamWeight,
            source = SOURCE_BLENDED
        )
    }

    private fun resolved(
        steps: DoubleArray,
        announcedWeight: Double,
        uamWeight: Double,
        source: String
    ): ResolvedMealPressure = ResolvedMealPressure(
        stepsStorage = steps,
        announcedWeight = announcedWeight,
        uamWeight = uamWeight,
        source = source
    )

    private fun DoubleArray.scaledBy(weight: Double): DoubleArray =
        DoubleArray(size) { index -> this[index].scaledAndBounded(weight) }

    private fun Double.scaledAndBounded(weight: Double): Double {
        val scaled = this * weight
        return if (scaled.isFinite()) scaled.coerceIn(0.0, this) else this
    }

    private fun boundedBlend(
        announced: Double,
        uam: Double,
        announcedWeight: Double,
        uamWeight: Double
    ): Double {
        if (announced == uam) return announced
        if (announcedWeight <= 0.0) return uam
        if (uamWeight <= 0.0) return announced

        val upperBound = max(announced, uam)
        val blended = if (announced < uam) {
            announced + (uam - announced) * uamWeight
        } else {
            uam + (announced - uam) * announcedWeight
        }
        return if (blended.isFinite()) {
            blended.coerceIn(0.0, upperBound)
        } else {
            upperBound
        }
    }

    private fun invalidZero(size: Int): ResolvedMealPressure = resolved(
        steps = DoubleArray(size),
        announcedWeight = 0.0,
        uamWeight = 0.0,
        source = SOURCE_INVALID_INPUT
    )

    private fun Double.isValidPressureStep(): Boolean = isFinite() && this >= 0.0

    private fun Double.clampedNonNegative(): Double =
        finiteOrZero().coerceAtLeast(0.0)

    private fun Double.clampedUnitInterval(): Double = finiteOrZero().coerceIn(0.0, 1.0)

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

    private const val SOURCE_NONE = "NONE"
    private const val SOURCE_UAM_ONLY = "UAM_ONLY"
    private const val SOURCE_ANNOUNCED_ONLY = "ANNOUNCED_ONLY"
    private const val SOURCE_BLENDED = "BLENDED"
    private const val SOURCE_INCOMPLETE_CARB_HISTORY = "INCOMPLETE_CARB_HISTORY"
    private const val SOURCE_INVALID_INPUT = "INVALID_INPUT"
}
