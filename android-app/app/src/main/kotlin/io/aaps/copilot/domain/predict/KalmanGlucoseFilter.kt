package io.aaps.copilot.domain.predict

import kotlin.math.sqrt

data class KalmanPoint(
    val tsMs: Long,
    val gMmol: Double,
    val rocPerMin: Double
)

class KalmanGlucoseFilter {
    private var initialized = false
    private var lastTs = 0L

    private var g = 0.0
    private var v = 0.0

    private var p00 = 0.25
    private var p01 = 0.0
    private var p10 = 0.0
    private var p11 = 0.04

    fun update(zMmol: Double, tsMs: Long): KalmanPoint {
        if (!initialized) {
            reset(zMmol, tsMs)
            return KalmanPoint(tsMs = tsMs, gMmol = zMmol, rocPerMin = 0.0)
        }

        val dt = (tsMs - lastTs) / 60_000.0
        if (dt <= 0.0) {
            return KalmanPoint(tsMs = tsMs, gMmol = g, rocPerMin = v)
        }
        if (dt > 20.0) {
            reset(zMmol, tsMs)
            return KalmanPoint(tsMs = tsMs, gMmol = zMmol, rocPerMin = 0.0)
        }

        val sigmaA = 0.02
        val sigmaZ = 0.18
        val qScale = sigmaA * sigmaA
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        val q00 = qScale * (dt4 / 4.0)
        val q01 = qScale * (dt3 / 2.0)
        val q11 = qScale * dt2

        val gPred = g + v * dt
        val vPred = v
        val p00Pred = p00 + dt * (p10 + p01) + dt2 * p11 + q00
        val p01Pred = p01 + dt * p11 + q01
        val p10Pred = p10 + dt * p11 + q01
        val p11Pred = p11 + q11

        val r = sigmaZ * sigmaZ
        val y = zMmol - gPred
        val s = (p00Pred + r).coerceAtLeast(1e-9)
        val limit = 3.0 * sqrt(s)
        val yUsed = y.coerceIn(-limit, limit)

        val k0 = p00Pred / s
        val k1 = p10Pred / s

        g = gPred + k0 * yUsed
        v = vPred + k1 * yUsed

        val np00 = ((1.0 - k0) * p00Pred).coerceAtLeast(1e-6)
        val np01 = (1.0 - k0) * p01Pred
        val np10 = p10Pred - k1 * p00Pred
        val np11 = (p11Pred - k1 * p01Pred).coerceAtLeast(1e-6)
        val sym = (np01 + np10) / 2.0

        p00 = np00
        p01 = sym
        p10 = sym
        p11 = np11

        lastTs = tsMs
        return KalmanPoint(
            tsMs = tsMs,
            gMmol = g.coerceIn(2.2, 22.0),
            rocPerMin = v.coerceIn(-0.24, 0.24)
        )
    }

    private fun reset(zMmol: Double, tsMs: Long) {
        initialized = true
        lastTs = tsMs
        g = zMmol
        v = 0.0
        p00 = 0.25
        p01 = 0.0
        p10 = 0.0
        p11 = 0.04
    }
}
