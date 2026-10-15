package io.aaps.copilot.util

import kotlin.math.round
import kotlin.math.abs

object UnitConverter {

    private const val MMOL_TO_MGDL = 18.0182

    fun mmolToMgdl(valueMmol: Double): Int = round(valueMmol * MMOL_TO_MGDL).toInt()

    fun mgdlToMmol(valueMgdl: Double): Double = valueMgdl / MMOL_TO_MGDL

    fun matchesTempTargetObservation(requestedMmol: Double, observedMmol: Double): Boolean {
        if (!requestedMmol.isFinite() || !observedMmol.isFinite() ||
            requestedMmol <= 0.0 || observedMmol <= 0.0
        ) return false
        val wireRoundTrip = mgdlToMmol(mmolToMgdl(requestedMmol).toDouble())
        return abs(observedMmol - requestedMmol) < 1e-9 ||
            abs(observedMmol - wireRoundTrip) < 1e-9
    }
}
