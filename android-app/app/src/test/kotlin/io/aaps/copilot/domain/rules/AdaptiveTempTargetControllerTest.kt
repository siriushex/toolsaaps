package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveTempTargetControllerTest {

    private val controller = AdaptiveTempTargetController()

    @Test
    fun testClampRange() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 20.0,
                pred30 = 22.0,
                pred60 = 25.0,
                ciLow5 = 10.0,
                ciHigh5 = 30.0,
                ciLow30 = 11.0,
                ciHigh30 = 31.0,
                ciLow60 = 12.0,
                ciHigh60 = 32.0,
                prevTarget = 8.9,
                prevI = 180.0
            )
        )

        assertThat(out.newTempTarget).isAtLeast(4.0)
        assertThat(out.newTempTarget).isAtMost(9.0)
    }

    @Test
    fun testSafetyForcesHigh() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 5.0,
                pred30 = 5.0,
                pred60 = 5.0,
                ciLow5 = 3.9,
                ciHigh5 = 6.0,
                ciLow30 = 4.8,
                ciHigh30 = 5.2,
                ciLow60 = 4.9,
                ciHigh60 = 5.3,
                prevTarget = null,
                prevI = 12.0
            )
        )

        assertThat(out.newTempTarget).isEqualTo(9.0)
        assertThat(out.updatedI).isEqualTo(12.0)
    }

    @Test
    fun testSafetyRaisesTarget() {
        val out = controller.evaluate(
            input(
                base = 5.8,
                pred5 = 5.5,
                pred30 = 5.6,
                pred60 = 5.7,
                ciLow5 = 5.0,
                ciHigh5 = 6.0,
                ciLow30 = 5.3,
                ciHigh30 = 5.9,
                ciLow60 = 5.4,
                ciHigh60 = 6.0,
                prevTarget = null,
                prevI = 4.0
            )
        )

        assertThat(out.newTempTarget).isGreaterThan(5.8)
        assertThat(out.updatedI).isEqualTo(4.0)
    }

    @Test
    fun testControlDeadband() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 5.55,
                pred30 = 5.50,
                pred60 = 5.45,
                ciLow5 = 5.35,
                ciHigh5 = 5.9,
                ciLow30 = 5.32,
                ciHigh30 = 5.9,
                ciLow60 = 5.31,
                ciHigh60 = 5.9,
                prevTarget = null,
                prevI = 10.0
            )
        )

        assertThat(out.newTempTarget).isEqualTo(5.5)
        assertThat(out.updatedI).isEqualTo(8.0)
    }

    @Test
    fun testControlHighGlucose() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 8.0,
                pred30 = 9.0,
                pred60 = 10.0,
                ciLow5 = 7.7,
                ciHigh5 = 8.3,
                ciLow30 = 8.5,
                ciHigh30 = 9.5,
                ciLow60 = 9.2,
                ciHigh60 = 10.8,
                prevTarget = null,
                prevI = 0.0
            )
        )

        assertThat(out.newTempTarget).isLessThan(5.5)
        assertThat(out.newTempTarget).isAtLeast(4.0)
    }

    @Test
    fun testControlLowGlucoseNoSafety() {
        val out = controller.evaluate(
            input(
                base = 5.8,
                pred5 = 5.4,
                pred30 = 5.2,
                pred60 = 5.0,
                ciLow5 = 5.5,
                ciHigh5 = 5.9,
                ciLow30 = 5.45,
                ciHigh30 = 5.8,
                ciLow60 = 5.41,
                ciHigh60 = 5.7,
                prevTarget = null,
                prevI = 0.0
            )
        )

        assertThat(out.newTempTarget).isGreaterThan(5.8)
        assertThat(out.newTempTarget).isAtMost(9.0)
    }

    @Test
    fun testCobSignificant_forcesEffectiveBaseTo42() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 5.6,
                pred30 = 5.6,
                pred60 = 5.6,
                ciLow5 = 5.2,
                ciHigh5 = 6.0,
                ciLow30 = 5.0,
                ciHigh30 = 6.2,
                ciLow60 = 4.8,
                ciHigh60 = 6.4,
                prevTarget = null,
                prevI = 0.0,
                cobGrams = 35.0
            )
        )

        assertThat(out.debugFields["Tb"]).isEqualTo(4.2)
        assertThat(out.newTempTarget).isAtMost(4.2)
    }

    @Test
    fun testIobInfluence_raisesTargetForHypoProtection() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 5.5,
                pred30 = 5.5,
                pred60 = 5.5,
                ciLow5 = 5.2,
                ciHigh5 = 5.8,
                ciLow30 = 5.2,
                ciHigh30 = 5.8,
                ciLow60 = 5.2,
                ciHigh60 = 5.8,
                prevTarget = null,
                prevI = 0.0,
                iobUnits = 3.0
            )
        )

        assertThat(out.debugFields["iobUnits"]).isEqualTo(3.0)
        assertThat(out.newTempTarget).isGreaterThan(5.5)
    }

    @Test
    fun testImmediateCorrectionFromHighPreviousTarget() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 9.5,
                pred30 = 10.0,
                pred60 = 10.5,
                ciLow5 = 9.0,
                ciHigh5 = 10.0,
                ciLow30 = 9.5,
                ciHigh30 = 10.5,
                ciLow60 = 10.0,
                ciHigh60 = 11.0,
                prevTarget = 9.0,
                prevI = 0.0
            )
        )

        assertThat(out.newTempTarget).isEqualTo(4.0)
    }

    @Test
    fun testRegressionFromDeviceLog_highGlucoseMustNotStickToNine() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 10.27,
                pred30 = 10.27,
                pred60 = 10.27,
                ciLow5 = 9.02,
                ciHigh5 = 11.52,
                ciLow30 = 9.02,
                ciHigh30 = 11.52,
                ciLow60 = 9.02,
                ciHigh60 = 11.52,
                prevTarget = 10.0,
                prevI = 55.3
            )
        )

        assertThat(out.newTempTarget).isEqualTo(4.0)
        assertThat(out.reason).isEqualTo("control_pi")
    }

    @Test
    fun testSafetySuppressedWhenTrajectoryClearlyHigh_evenIfLowerBoundDips() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 10.0,
                pred30 = 10.2,
                pred60 = 10.4,
                ciLow5 = 4.05,
                ciHigh5 = 11.8,
                ciLow30 = 4.10,
                ciHigh30 = 12.1,
                ciLow60 = 4.15,
                ciHigh60 = 12.4,
                prevTarget = 8.2,
                prevI = 12.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.newTempTarget).isLessThan(5.5)
        assertThat(out.newTempTarget).isAtLeast(4.0)
        assertThat(out.debugFields["safetySuppressedByHighTrajectory"]).isEqualTo(1.0)
    }

    @Test
    fun testHighGlucoseGuard_preventsTargetAboveBaseWhenTrajectoryHigh() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 9.8,
                pred30 = 10.0,
                pred60 = 10.2,
                ciLow5 = 5.8,
                ciHigh5 = 11.2,
                ciLow30 = 6.0,
                ciHigh30 = 11.5,
                ciLow60 = 6.1,
                ciHigh60 = 11.8,
                prevTarget = 8.0,
                prevI = 24.0,
                iobUnits = 6.0
            )
        )

        assertThat(out.newTempTarget).isAtMost(5.5)
        assertThat(out.debugFields["highGuardActive"]).isEqualTo(1.0)
    }

    @Test
    fun testVeryHighGlucoseGuard_forcesAdditionalPulldown() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 12.0,
                pred30 = 12.2,
                pred60 = 12.4,
                ciLow5 = 6.0,
                ciHigh5 = 13.0,
                ciLow30 = 6.2,
                ciHigh30 = 13.2,
                ciLow60 = 6.4,
                ciHigh60 = 13.4,
                prevTarget = 7.6,
                prevI = 8.0
            )
        )

        assertThat(out.debugFields["highGuardActive"]).isEqualTo(1.0)
        assertThat(out.newTempTarget).isAtMost(4.4)
    }

    @Test
    fun testForceHighRequiresNearTermLowOrVeryLowCtrlLow() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 5.2,
                pred30 = 5.1,
                pred60 = 4.9,
                ciLow5 = 4.7,
                ciHigh5 = 5.8,
                ciLow30 = 2.2,
                ciHigh30 = 8.3,
                ciLow60 = 2.3,
                ciHigh60 = 8.5,
                prevTarget = null,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isNotEqualTo("safety_force_high")
        assertThat(out.newTempTarget).isLessThan(9.0)
    }

    @Test
    fun testLowFarHorizonWithoutNearTermRisk_doesNotForceHigh() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 6.2,
                pred30 = 5.0,
                pred60 = 4.8,
                ciLow5 = 5.0,
                ciHigh5 = 6.8,
                ciLow30 = 2.0,
                ciHigh30 = 8.0,
                ciLow60 = 2.1,
                ciHigh60 = 8.2,
                prevTarget = null,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isNotEqualTo("safety_force_high")
        assertThat(out.newTempTarget).isLessThan(9.0)
    }

    private fun input(
        base: Double,
        pred5: Double,
        pred30: Double,
        pred60: Double,
        ciLow5: Double,
        ciHigh5: Double,
        ciLow30: Double,
        ciHigh30: Double,
        ciLow60: Double,
        ciHigh60: Double,
        prevTarget: Double?,
        prevI: Double,
        uamActive: Boolean = false,
        cobGrams: Double? = null,
        iobUnits: Double? = null
    ) = AdaptiveTempTargetController.Input(
        nowTs = System.currentTimeMillis(),
        baseTarget = base,
        pred5 = pred5,
        pred30 = pred30,
        pred60 = pred60,
        ciLow5 = ciLow5,
        ciHigh5 = ciHigh5,
        ciLow30 = ciLow30,
        ciHigh30 = ciHigh30,
        ciLow60 = ciLow60,
        ciHigh60 = ciHigh60,
        uamActive = uamActive,
        previousTempTarget = prevTarget,
        previousI = prevI,
        cobGrams = cobGrams,
        iobUnits = iobUnits
    )
}
