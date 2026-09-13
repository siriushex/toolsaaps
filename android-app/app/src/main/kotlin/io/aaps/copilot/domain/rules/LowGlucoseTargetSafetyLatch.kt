package io.aaps.copilot.domain.rules

/** Keeps a raised safety target active while current or forecast glucose remains below the floor. */
class LowGlucoseTargetSafetyLatch(
    private val lowFloorMmol: Double = LOW_GLUCOSE_FLOOR_MMOL,
    private val safeCyclesToRelease: Int = SAFE_CYCLES_TO_RELEASE
) {
    data class State(
        val riskNow: Boolean,
        val latched: Boolean,
        val safeCycles: Int,
        val protectedTargetMmol: Double?
    )

    private var initialized = false
    private var latched = false
    private var safeCycles = 0
    private var protectedTargetMmol: Double? = null

    private data class Transition(
        val initialized: Boolean,
        val state: State
    )

    init {
        require(lowFloorMmol.isFinite()) { "lowFloorMmol must be finite" }
        require(safeCyclesToRelease > 0) { "safeCyclesToRelease must be positive" }
    }

    @Synchronized
    fun update(
        currentGlucoseMmol: Double?,
        forecastMinimumMmol: Double?,
        activeSafetyTargetMmol: Double?,
        proposedSafetyTargetMmol: Double?,
        seedFromActiveSafety: Boolean
    ): State {
        val transition = transition(
            currentGlucoseMmol = currentGlucoseMmol,
            forecastMinimumMmol = forecastMinimumMmol,
            activeSafetyTargetMmol = activeSafetyTargetMmol,
            proposedSafetyTargetMmol = proposedSafetyTargetMmol,
            seedFromActiveSafety = seedFromActiveSafety
        )
        initialized = transition.initialized
        latched = transition.state.latched
        safeCycles = transition.state.safeCycles
        protectedTargetMmol = transition.state.protectedTargetMmol
        return transition.state
    }

    @Synchronized
    fun preview(
        currentGlucoseMmol: Double?,
        forecastMinimumMmol: Double?,
        activeSafetyTargetMmol: Double?,
        proposedSafetyTargetMmol: Double?,
        seedFromActiveSafety: Boolean
    ): State = transition(
        currentGlucoseMmol = currentGlucoseMmol,
        forecastMinimumMmol = forecastMinimumMmol,
        activeSafetyTargetMmol = activeSafetyTargetMmol,
        proposedSafetyTargetMmol = proposedSafetyTargetMmol,
        seedFromActiveSafety = seedFromActiveSafety
    ).state

    private fun transition(
        currentGlucoseMmol: Double?,
        forecastMinimumMmol: Double?,
        activeSafetyTargetMmol: Double?,
        proposedSafetyTargetMmol: Double?,
        seedFromActiveSafety: Boolean
    ): Transition {
        val riskNow = isBelowFloor(currentGlucoseMmol) || isBelowFloor(forecastMinimumMmol)
        val activeSafetyTarget = activeSafetyTargetMmol.finiteOrNull()
        val proposedSafetyTarget = proposedSafetyTargetMmol.finiteOrNull()
        var nextInitialized = initialized
        var nextLatched = latched
        var nextSafeCycles = safeCycles
        var nextProtectedTargetMmol = protectedTargetMmol

        if (!nextInitialized) {
            nextInitialized = true
            if (seedFromActiveSafety && activeSafetyTarget != null) {
                nextLatched = true
                nextProtectedTargetMmol = activeSafetyTarget
            }
        }

        if (riskNow) {
            nextLatched = true
            nextSafeCycles = 0
            nextProtectedTargetMmol = maxTarget(
                nextProtectedTargetMmol,
                activeSafetyTarget,
                proposedSafetyTarget
            )
        } else if (nextLatched) {
            nextProtectedTargetMmol = maxTarget(nextProtectedTargetMmol, proposedSafetyTarget)
            nextSafeCycles += 1
            if (nextSafeCycles >= safeCyclesToRelease) {
                nextLatched = false
                nextSafeCycles = 0
                nextProtectedTargetMmol = null
            }
        }

        if (nextLatched && proposedSafetyTarget != null) {
            nextProtectedTargetMmol = maxTarget(nextProtectedTargetMmol, proposedSafetyTarget)
        }

        return Transition(
            initialized = nextInitialized,
            state = State(
                riskNow = riskNow,
                latched = nextLatched,
                safeCycles = nextSafeCycles,
                protectedTargetMmol = nextProtectedTargetMmol
            )
        )
    }

    fun protectTarget(proposedTargetMmol: Double, state: State): Double {
        val protectedTarget = state.protectedTargetMmol
        return if (state.latched && protectedTarget != null) {
            maxOf(proposedTargetMmol, protectedTarget)
        } else {
            proposedTargetMmol
        }
    }

    private fun isBelowFloor(value: Double?): Boolean =
        value != null && value.isFinite() && value < lowFloorMmol

    private fun Double?.finiteOrNull(): Double? = this?.takeIf(Double::isFinite)

    private fun maxTarget(vararg values: Double?): Double? =
        values.filterNotNull().maxOrNull()

    companion object {
        private const val LOW_GLUCOSE_FLOOR_MMOL = 4.0
        private const val SAFE_CYCLES_TO_RELEASE = 2
    }
}
