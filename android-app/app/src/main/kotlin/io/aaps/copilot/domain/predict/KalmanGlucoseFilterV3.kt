package io.aaps.copilot.domain.predict

import kotlin.math.sqrt

internal data class KalmanSnapshotV3(
    val gMmol: Double,
    val vMmolPerMin: Double,
    val rocPer5Mmol: Double,
    val sigmaG: Double,
    val ewmaNis: Double,
    val sigmaZ: Double,
    val sigmaA: Double,
    val updatesCount: Int
)

internal class KalmanGlucoseFilterV3 {

    private var inited = false
    private var lastTs = 0L
    private var updatesCount = 0

    private var g = 0.0
    private var v = 0.0

    private var p00 = 0.25
    private var p01 = 0.0
    private var p10 = 0.0
    private var p11 = 0.04

    private var ewmaNis = 1.0
    private var sigmaZ = 0.18
    private var sigmaA = 0.02

    fun update(zMmol: Double, ts: Long, volNorm: Double): KalmanSnapshotV3 {
        if (!inited) {
            reset(zMmol, ts)
            return snapshot()
        }

        val dt = (ts - lastTs) / 60_000.0
        if (dt <= 0.0) {
            return snapshot()
        }
        if (dt > MAX_DT_RESET_MIN) {
            reset(zMmol, ts)
            return snapshot()
        }

        val sigmaATarget = SIGMA_A_BASE + SIGMA_A_VOL * volNorm + SIGMA_A_NIS * maxOf(0.0, ewmaNis - 1.0)
        sigmaA = (0.85 * sigmaA + 0.15 * sigmaATarget).coerceIn(0.001, 0.40)

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

        val y = zMmol - gPred

        val rBase = (0.10 + 0.35 * volNorm).let { it * it }
        val nisDenom = (p00Pred + sigmaZ * sigmaZ).coerceAtLeast(EPS)
        val nis = (y * y) / nisDenom
        ewmaNis = (1.0 - NIS_LAMBDA) * ewmaNis + NIS_LAMBDA * nis

        if (ewmaNis > NIS_HIGH) sigmaZ *= 1.25
        if (ewmaNis < NIS_LOW) sigmaZ *= 0.90
        sigmaZ = sigmaZ.coerceIn(SIGMA_Z_MIN, SIGMA_Z_MAX)

        val r = (sigmaZ * sigmaZ).coerceIn(rBase * 0.6, rBase * 8.0)

        val innovationStd = sqrt((p00Pred + r).coerceAtLeast(EPS))
        val limit = 3.0 * innovationStd
        val yUsed = y.coerceIn(-limit, limit)

        val s = (p00Pred + r).coerceAtLeast(EPS)
        val k0 = p00Pred / s
        val k1 = p10Pred / s

        g = gPred + k0 * yUsed
        v = vPred + k1 * yUsed

        var np00 = (1.0 - k0) * p00Pred
        var np01 = (1.0 - k0) * p01Pred
        var np10 = p10Pred - k1 * p00Pred
        var np11 = p11Pred - k1 * p01Pred

        val symOffDiag = (np01 + np10) / 2.0
        np01 = symOffDiag
        np10 = symOffDiag

        p00 = np00.coerceAtLeast(1e-6)
        p01 = np01
        p10 = np10
        p11 = np11.coerceAtLeast(1e-6)

        val rocPer5 = (v * 5.0).coerceIn(-1.2, 1.2)
        v = rocPer5 / 5.0

        lastTs = ts
        updatesCount += 1

        return snapshot()
    }

    fun snapshotOrNull(): KalmanSnapshotV3? = if (inited) snapshot() else null

    fun reset(zMmol: Double, ts: Long) {
        inited = true
        g = zMmol
        v = 0.0
        p00 = 0.25
        p01 = 0.0
        p10 = 0.0
        p11 = 0.04
        sigmaZ = 0.18
        sigmaA = 0.02
        ewmaNis = 1.0
        lastTs = ts
        updatesCount = 1
    }

    private fun snapshot(): KalmanSnapshotV3 {
        val sigmaG = sqrt(p00.coerceAtLeast(0.0))
        return KalmanSnapshotV3(
            gMmol = g,
            vMmolPerMin = v,
            rocPer5Mmol = (v * 5.0).coerceIn(-1.2, 1.2),
            sigmaG = sigmaG,
            ewmaNis = ewmaNis,
            sigmaZ = sigmaZ,
            sigmaA = sigmaA,
            updatesCount = updatesCount
        )
    }

    private companion object {
        const val NIS_LAMBDA = 0.10
        const val NIS_HIGH = 4.0
        const val NIS_LOW = 0.6

        const val SIGMA_Z_MIN = 0.08
        const val SIGMA_Z_MAX = 0.60

        const val SIGMA_A_BASE = 0.010
        const val SIGMA_A_VOL = 0.050
        const val SIGMA_A_NIS = 0.020

        const val MAX_DT_RESET_MIN = 20.0
        const val EPS = 1e-9
    }
}
