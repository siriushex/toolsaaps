package io.aaps.copilot.util

import kotlin.math.round

object UnitConverter {

    private const val MMOL_TO_MGDL = 18.0182

    fun mmolToMgdl(valueMmol: Double): Int = round(valueMmol * MMOL_TO_MGDL).toInt()

    fun mgdlToMmol(valueMgdl: Double): Double = valueMgdl / MMOL_TO_MGDL
}
