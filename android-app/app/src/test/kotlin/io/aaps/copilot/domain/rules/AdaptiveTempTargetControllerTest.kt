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
    fun testSafetyForcesHigh_usesConfiguredUpperBound10() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                pred5 = 5.0,
                pred30 = 5.0,
                pred60 = 5.0,
                ciLow5 = 3.8,
                ciHigh5 = 6.0,
                ciLow30 = 4.7,
                ciHigh30 = 5.3,
                ciLow60 = 4.8,
                ciHigh60 = 5.4,
                prevTarget = null,
                prevI = 5.0,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isEqualTo("safety_force_high")
        assertThat(out.newTempTarget).isEqualTo(10.0)
        assertThat(out.debugFields["targetMax"]).isEqualTo(10.0)
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
    fun loweringRequiresSafetyQualifiedCycleIobButProtectiveRaiseStillWorks() {
        val high = input(
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
            prevI = 0.0,
            safetyIobUnits = null
        )
        val blockedLower = controller.evaluate(high)
        val qualifiedLower = controller.evaluate(high.copy(safetyIobUnits = 0.0))
        val protectiveRaise = controller.evaluate(
            high.copy(
                pred5 = 4.2,
                pred30 = 4.0,
                pred60 = 3.8,
                ciLow5 = 3.8,
                ciLow30 = 3.6,
                ciLow60 = 3.4,
                safetyIobUnits = null
            )
        )

        assertThat(blockedLower.newTempTarget).isAtLeast(5.5)
        assertThat(blockedLower.reason).contains("safety_iob")
        assertThat(qualifiedLower.newTempTarget).isLessThan(5.5)
        assertThat(protectiveRaise.newTempTarget).isGreaterThan(5.5)
    }

    @Test
    fun coherentThirtyAndSixtyMinuteRiseLowersBeforeCurrentGlucoseIsHigh() {
        val risingForecast = input(
            base = 6.0,
            currentGlucose = 6.0,
            observedDelta5 = 0.0,
            pred5 = 6.1,
            pred30 = 7.4,
            pred60 = 8.4,
            ciLow5 = 5.8,
            ciHigh5 = 6.4,
            ciLow30 = 7.0,
            ciHigh30 = 7.8,
            ciLow60 = 7.9,
            ciHigh60 = 8.9,
            prevTarget = 6.0,
            prevI = 0.0,
            safetyIobUnits = 0.4
        )

        val out = controller.evaluate(risingForecast)
        val flatLongHorizon = controller.evaluate(
            risingForecast.copy(
                pred30 = 6.1,
                pred60 = 6.1,
                ciLow30 = 5.8,
                ciHigh30 = 6.4,
                ciLow60 = 5.8,
                ciHigh60 = 6.4
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["currentGlucose"]).isEqualTo(6.0)
        assertThat(out.debugFields["fastRiseSignal"]).isGreaterThan(0.0)
        assertThat(out.newTempTarget).isLessThan(6.0)
        assertThat(out.newTempTarget).isLessThan(flatLongHorizon.newTempTarget)
    }

    @Test
    fun unsafeThirtyAndSixtyMinuteCiPreventsPredictiveLowering() {
        val out = controller.evaluate(
            input(
                base = 6.0,
                currentGlucose = 6.0,
                observedDelta5 = 0.0,
                pred5 = 6.1,
                pred30 = 7.4,
                pred60 = 8.4,
                ciLow5 = 5.8,
                ciHigh5 = 6.4,
                ciLow30 = 3.8,
                ciHigh30 = 11.0,
                ciLow60 = 3.4,
                ciHigh60 = 13.4,
                prevTarget = 6.0,
                prevI = 0.0,
                safetyIobUnits = 0.4
            )
        )

        assertThat(out.reason).isEqualTo("safety_keep_existing_target")
        assertThat(out.debugFields["Pmin"]).isWithin(0.01).of(3.8)
        assertThat(out.newTempTarget).isEqualTo(6.0)
    }

    @Test
    fun highIobPreventsPredictiveLoweringDespiteCoherentRise() {
        val out = controller.evaluate(
            input(
                base = 6.0,
                currentGlucose = 6.0,
                observedDelta5 = 0.0,
                pred5 = 6.1,
                pred30 = 7.4,
                pred60 = 8.4,
                ciLow5 = 5.8,
                ciHigh5 = 6.4,
                ciLow30 = 7.0,
                ciHigh30 = 7.8,
                ciLow60 = 7.9,
                ciHigh60 = 8.9,
                prevTarget = 6.0,
                prevI = 0.0,
                safetyIobUnits = 4.0
            )
        )

        assertThat(out.debugFields["iobUnits"]).isEqualTo(4.0)
        assertThat(out.debugFields["Tb"]).isGreaterThan(6.0)
        assertThat(out.newTempTarget).isAtLeast(6.0)
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
    fun testLowBoundRisk_withCurrentTargetBelowFive_raisesToFive() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 10.0,
                pred5 = 9.6,
                pred30 = 9.4,
                pred60 = 9.2,
                ciLow5 = 6.2,
                ciHigh5 = 10.4,
                ciLow30 = 4.4,
                ciHigh30 = 10.8,
                ciLow60 = 3.4,
                ciHigh60 = 11.0,
                prevTarget = 4.3,
                prevI = 12.0
            )
        )

        assertThat(out.reason).isEqualTo("safety_raise_target_to_five")
        assertThat(out.newTempTarget).isEqualTo(5.0)
        assertThat(out.debugFields["Pmin"]).isEqualTo(3.4)
    }

    @Test
    fun testLowBoundRisk_withFallingTrajectory_escalatesExistingProtectiveTarget() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 6.8,
                pred5 = 6.2,
                pred30 = 5.8,
                pred60 = 5.4,
                ciLow5 = 4.7,
                ciHigh5 = 7.2,
                ciLow30 = 3.9,
                ciHigh30 = 6.8,
                ciLow60 = 3.5,
                ciHigh60 = 6.1,
                prevTarget = 6.6,
                prevI = 4.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_preemptive_guard")
        assertThat(out.newTempTarget).isGreaterThan(6.6)
        assertThat(out.debugFields["Pmin"]).isEqualTo(3.5)
    }

    @Test
    fun testLowBoundRiskBelowFour_preservesRaisedTarget() {
        val out = controller.evaluate(
            input(
                base = 4.2,
                currentGlucose = 5.38,
                observedDelta5 = 0.28,
                pred5 = 5.8,
                pred30 = 6.7,
                pred60 = 6.9,
                ciLow5 = 4.3,
                ciHigh5 = 7.0,
                ciLow30 = 3.8,
                ciHigh30 = 8.0,
                ciLow60 = 4.1,
                ciHigh60 = 8.4,
                prevTarget = 6.6,
                prevI = 0.0,
                safetyIobUnits = 1.9
            )
        )

        assertThat(out.reason).isEqualTo("safety_keep_existing_target")
        assertThat(out.newTempTarget).isEqualTo(6.6)
        assertThat(out.debugFields["Pmin"]).isEqualTo(3.8)
    }

    @Test
    fun controllerCobPressureRemainsBoundedWithoutForcingBaseToFourPointTwo() {
        val out = controller.evaluate(
            input(
                base = 6.1,
                pred5 = 6.1,
                pred30 = 6.1,
                pred60 = 6.1,
                ciLow5 = 6.0,
                ciHigh5 = 6.2,
                ciLow30 = 6.0,
                ciHigh30 = 6.2,
                ciLow60 = 6.0,
                ciHigh60 = 6.2,
                prevTarget = null,
                prevI = 0.0,
                cobGrams = 35.0
            )
        )

        assertThat(out.debugFields["Tb"]).isEqualTo(6.1)
        assertThat(out.debugFields["cobBias"]).isAtMost(1.2)
        assertThat(out.newTempTarget).isAtLeast(4.0)
        assertThat(out.newTempTarget).isAtMost(9.0)
    }

    @Test
    fun cobAboveTwentyDoesNotReplaceManualOrScheduledBase() {
        val out = controller.evaluate(
            input(
                base = 5.8,
                pred5 = 5.8,
                pred30 = 5.8,
                pred60 = 5.8,
                ciLow5 = 5.2,
                ciHigh5 = 6.4,
                ciLow30 = 5.1,
                ciHigh30 = 6.5,
                ciLow60 = 5.0,
                ciHigh60 = 6.6,
                prevTarget = null,
                prevI = 0.0,
                cobGrams = 20.01
            )
        )

        assertThat(out.debugFields["Tb"]).isEqualTo(5.8)
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
                safetyIobUnits = 3.0
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

        assertThat(out.newTempTarget).isEqualTo(4.5)
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

        assertThat(out.newTempTarget).isEqualTo(4.5)
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
                safetyIobUnits = 6.0
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
        assertThat(out.newTempTarget).isEqualTo(4.5)
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
                currentGlucose = 6.0,
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

    @Test
    fun testHighCurrentGlucoseSuppressesForceHighOnNoisyCi() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 8.7,
                pred5 = 6.6,
                pred30 = 6.4,
                pred60 = 6.1,
                ciLow5 = 4.15,
                ciHigh5 = 8.0,
                ciLow30 = 2.3,
                ciHigh30 = 8.4,
                ciLow60 = 2.2,
                ciHigh60 = 8.6,
                prevTarget = null,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isNotEqualTo("safety_force_high")
        assertThat(out.debugFields["safetySuppressedByCurrentHigh"]).isEqualTo(1.0)
        assertThat(out.newTempTarget).isAtMost(5.5)
    }

    @Test
    fun testRapidRiseBias_makesControllerReactEarlierToNearTermUpswing() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.1,
                pred5 = 10.8,
                pred30 = 8.2,
                pred60 = 6.7,
                ciLow5 = 10.1,
                ciHigh5 = 11.4,
                ciLow30 = 7.6,
                ciHigh30 = 8.8,
                ciLow60 = 6.2,
                ciHigh60 = 7.2,
                prevTarget = 6.0,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["fastRiseSignal"]).isGreaterThan(0.0)
        assertThat(out.debugFields["leadOvershoot"]).isGreaterThan(0.0)
        assertThat(out.debugFields["rapidRiseBias"]).isGreaterThan(0.0)
        assertThat(out.newTempTarget).isAtMost(4.3)
    }

    @Test
    fun testNearTermSpike_pullsTargetDownBeforeLongHorizonsCatchUp() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 6.9,
                observedDelta5 = 0.42,
                pred5 = 8.7,
                pred30 = 6.6,
                pred60 = 6.0,
                ciLow5 = 8.0,
                ciHigh5 = 9.3,
                ciLow30 = 6.0,
                ciHigh30 = 7.2,
                ciLow60 = 5.6,
                ciHigh60 = 6.5,
                prevTarget = 5.7,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["fastRiseSignal"]).isGreaterThan(0.0)
        assertThat(out.newTempTarget).isLessThan(4.9)
    }

    @Test
    fun testObservedTrendShock_reactsEvenWhenLongHorizonsLag() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.0,
                observedDelta5 = 0.46,
                pred5 = 7.35,
                pred30 = 6.0,
                pred60 = 5.8,
                ciLow5 = 6.9,
                ciHigh5 = 7.7,
                ciLow30 = 5.6,
                ciHigh30 = 6.4,
                ciLow60 = 5.4,
                ciHigh60 = 6.2,
                prevTarget = 5.5,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["observedDelta5"]).isWithin(0.01).of(0.46)
        assertThat(out.debugFields["trendShockBias"]).isGreaterThan(0.0)
        assertThat(out.newTempTarget).isLessThan(5.1)
    }

    @Test
    fun testFastRiseReducesDeadbandAndPreventsNeutralHold() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.0,
                observedDelta5 = 0.34,
                pred5 = 8.1,
                pred30 = 5.9,
                pred60 = 5.7,
                ciLow5 = 7.6,
                ciHigh5 = 8.6,
                ciLow30 = 5.5,
                ciHigh30 = 6.3,
                ciLow60 = 5.3,
                ciHigh60 = 6.1,
                prevTarget = 5.5,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["effectiveDeadband"]).isLessThan(AdaptiveTempTargetController.M_DEAD)
        assertThat(out.newTempTarget).isLessThan(5.5)
    }

    @Test
    fun testStoppedTrend_relaxesLowTempTargetTowardBase() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.2,
                observedDelta5 = 0.03,
                pred5 = 6.0,
                pred30 = 5.8,
                pred60 = 5.6,
                ciLow5 = 5.7,
                ciHigh5 = 6.3,
                ciLow30 = 5.5,
                ciHigh30 = 6.1,
                ciLow60 = 5.3,
                ciHigh60 = 5.9,
                prevTarget = 4.2,
                prevI = 150.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["targetRelaxed"]).isGreaterThan(out.debugFields["targetGuarded"])
        assertThat(out.newTempTarget).isGreaterThan(4.2)
        assertThat(out.newTempTarget).isLessThan(5.5)
    }

    @Test
    fun testStoppedTrend_relaxesHighTempTargetTowardBase() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 5.5,
                observedDelta5 = -0.02,
                pred5 = 5.4,
                pred30 = 5.45,
                pred60 = 5.5,
                ciLow5 = 5.3,
                ciHigh5 = 5.6,
                ciLow30 = 5.35,
                ciHigh30 = 5.65,
                ciLow60 = 5.4,
                ciHigh60 = 5.7,
                prevTarget = 7.4,
                prevI = -120.0
            )
        )

        assertThat(out.reason).isAnyOf("control_pi", "control_deadband")
        assertThat(out.newTempTarget).isLessThan(7.4)
        assertThat(out.newTempTarget).isAtLeast(5.5)
    }

    @Test
    fun testSevereNearTermLowOverridesHighCurrentSuppression() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 8.7,
                pred5 = 6.6,
                pred30 = 6.4,
                pred60 = 6.1,
                ciLow5 = 3.4,
                ciHigh5 = 8.0,
                ciLow30 = 2.3,
                ciHigh30 = 8.4,
                ciLow60 = 2.2,
                ciHigh60 = 8.6,
                prevTarget = null,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("safety_force_high")
    }

    @Test
    fun testPreemptiveGuardRaisesTargetBeforeNearTermLowMaterializes() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 5.4,
                observedDelta5 = -0.18,
                pred5 = 5.1,
                pred30 = 4.6,
                pred60 = 4.3,
                ciLow5 = 4.7,
                ciHigh5 = 5.4,
                ciLow30 = 3.75,
                ciHigh30 = 4.9,
                ciLow60 = 3.65,
                ciHigh60 = 4.8,
                prevTarget = 5.2,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_preemptive_guard")
        assertThat(out.newTempTarget).isGreaterThan(5.8)
        assertThat(out.debugFields["midTermLow"]).isWithin(0.01).of(3.65)
    }

    @Test
    fun coherentThirtyAndSixtyMinuteFallRaisesBeforeCurrentGlucoseFalls() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 6.4,
                observedDelta5 = 0.05,
                pred5 = 6.3,
                pred30 = 5.0,
                pred60 = 4.4,
                ciLow5 = 5.9,
                ciHigh5 = 6.7,
                ciLow30 = 4.35,
                ciHigh30 = 5.65,
                ciLow60 = 3.7,
                ciHigh60 = 5.1,
                prevTarget = 5.0,
                prevI = 0.0,
                safetyIobUnits = 1.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_preemptive_guard")
        assertThat(out.debugFields["currentGlucose"]).isEqualTo(6.4)
        assertThat(out.debugFields["forecastDrop5To30"]).isGreaterThan(1.0)
        assertThat(out.debugFields["forecastDrop30To60"]).isGreaterThan(0.5)
        assertThat(out.newTempTarget).isGreaterThan(5.5)
        assertThat(out.newTempTarget).isGreaterThan(5.0)
    }

    @Test
    fun testPreemptiveForceHighRaisesAboveExistingTargetWhenThirtySixtyRiskAllowsBelow36() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 5.2,
                observedDelta5 = -0.22,
                pred5 = 4.9,
                pred30 = 4.2,
                pred60 = 4.0,
                ciLow5 = 4.4,
                ciHigh5 = 5.2,
                ciLow30 = 3.5,
                ciHigh30 = 4.8,
                ciLow60 = 3.4,
                ciHigh60 = 4.7,
                prevTarget = 5.4,
                prevI = 0.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_preemptive_force_high")
        assertThat(out.newTempTarget).isGreaterThan(6.4)
        assertThat(out.newTempTarget).isGreaterThan(5.4)
    }

    @Test
    fun deviceRegressionRapidCoherentFallUsesSixtyMinuteRiskBeforeThirtyMinuteCiCrosses() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.20,
                observedDelta5 = -0.42,
                pred5 = 6.90,
                pred30 = 6.32,
                pred60 = 5.95,
                ciLow5 = 6.31,
                ciHigh5 = 7.49,
                ciLow30 = 4.42,
                ciHigh30 = 8.22,
                ciLow60 = 3.12,
                ciHigh60 = 8.78,
                prevTarget = 4.77,
                prevI = 9.81,
                safetyIobUnits = 0.41,
                rapidFallPriorConfirmedCycles = 1,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_preemptive_guard")
        assertThat(out.newTempTarget).isAtLeast(7.0)
        assertThat(out.debugFields["rapidFallFarTermLowConfirmed"]).isEqualTo(1.0)
        assertThat(out.debugFields["effectiveCiLow60"]).isWithin(0.01).of(3.12)
    }

    @Test
    fun worseningRapidFallEscalatesExistingProtectiveTargetToNewComputedLevel() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.20,
                observedDelta5 = -0.42,
                pred5 = 6.90,
                pred30 = 6.32,
                pred60 = 5.95,
                ciLow5 = 6.31,
                ciHigh5 = 7.49,
                ciLow30 = 4.42,
                ciHigh30 = 8.22,
                ciLow60 = 3.12,
                ciHigh60 = 8.78,
                prevTarget = 6.0,
                prevI = 9.81,
                safetyIobUnits = 0.41,
                rapidFallPriorConfirmedCycles = 1,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_preemptive_guard")
        assertThat(out.newTempTarget).isAtLeast(7.0)
        assertThat(out.newTempTarget).isGreaterThan(6.0)
    }

    @Test
    fun singleRapidFallCycleWithBroadCiDoesNotRaiseTargetPreemptively() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.20,
                observedDelta5 = -0.42,
                pred5 = 6.90,
                pred30 = 6.32,
                pred60 = 5.95,
                ciLow5 = 6.31,
                ciHigh5 = 7.49,
                ciLow30 = 4.42,
                ciHigh30 = 8.22,
                ciLow60 = 3.12,
                ciHigh60 = 8.78,
                prevTarget = 4.77,
                prevI = 9.81,
                safetyIobUnits = 0.41,
                rapidFallPriorConfirmedCycles = 0,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.newTempTarget).isLessThan(5.5)
        assertThat(out.debugFields["rapidFallFarTermLowCandidate"]).isEqualTo(1.0)
        assertThat(out.debugFields["rapidFallFarTermLowConfirmed"]).isEqualTo(0.0)
    }

    @Test
    fun broadSixtyMinuteCiWithoutCoherentFallDoesNotRaiseTargetPreemptively() {
        val out = controller.evaluate(
            input(
                base = 5.5,
                currentGlucose = 7.47,
                observedDelta5 = -0.42,
                pred5 = 7.43,
                pred30 = 7.52,
                pred60 = 6.76,
                ciLow5 = 6.81,
                ciHigh5 = 8.05,
                ciLow30 = 5.13,
                ciHigh30 = 9.64,
                ciLow60 = 3.96,
                ciHigh60 = 9.56,
                prevTarget = 4.50,
                prevI = 7.50,
                safetyIobUnits = 0.41,
                rapidFallPriorConfirmedCycles = 1,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.newTempTarget).isLessThan(5.5)
        assertThat(out.debugFields["farTermLowConfirmed"]).isEqualTo(0.0)
    }

    @Test
    fun ci60OnlyCollapseDoesNotReverseHighGlucoseControlToMaximumTarget() {
        val out = controller.evaluate(
            input(
                base = 6.5,
                currentGlucose = 11.46,
                observedDelta5 = -0.12,
                pred5 = 10.69,
                pred30 = 8.30,
                pred60 = 5.75,
                ciLow5 = 9.20,
                ciHigh5 = 12.10,
                ciLow30 = 5.80,
                ciHigh30 = 10.80,
                ciLow60 = 2.50,
                ciHigh60 = 9.00,
                prevTarget = 5.6,
                prevI = 200.0
            )
        )

        assertThat(out.reason).isNotEqualTo("hypo_preemptive_force_high")
        assertThat(out.newTempTarget).isAtMost(6.5)
        assertThat(out.debugFields["farTermLowConfirmed"]).isEqualTo(0.0)
    }

    @Test
    fun risingIobWithCoherentForecastReversalRestoresTargetBeforeObservedFall() {
        val out = controller.evaluate(
            input(
                base = 6.6,
                currentGlucose = 8.20,
                observedDelta5 = 0.55,
                pred5 = 8.35,
                pred30 = 8.20,
                pred60 = 7.20,
                ciLow5 = 7.70,
                ciHigh5 = 9.00,
                ciLow30 = 5.69,
                ciHigh30 = 10.70,
                ciLow60 = 4.15,
                ciHigh60 = 10.25,
                prevTarget = 5.0,
                prevI = 10.33,
                uamActive = true,
                cobGrams = 16.21,
                safetyIobUnits = 1.94,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isEqualTo("hypo_forecast_reversal_guard")
        assertThat(out.newTempTarget).isAtLeast(6.6)
        assertThat(out.newTempTarget).isLessThan(8.0)
        assertThat(out.debugFields["iobForecastReversalCandidate"]).isEqualTo(1.0)
    }

    @Test
    fun coherentForecastReversalWithoutIobDoesNotRaiseHighGlucoseTarget() {
        val out = controller.evaluate(
            input(
                base = 6.6,
                currentGlucose = 8.20,
                observedDelta5 = 0.55,
                pred5 = 8.35,
                pred30 = 8.20,
                pred60 = 7.20,
                ciLow5 = 7.70,
                ciHigh5 = 9.00,
                ciLow30 = 5.69,
                ciHigh30 = 10.70,
                ciLow60 = 4.15,
                ciHigh60 = 10.25,
                prevTarget = 5.0,
                prevI = 10.33,
                uamActive = true,
                cobGrams = 16.21,
                safetyIobUnits = 0.40,
                targetMax = 10.0
            )
        )

        assertThat(out.reason).isNotEqualTo("hypo_forecast_reversal_guard")
        assertThat(out.debugFields["iobForecastReversalCandidate"]).isEqualTo(0.0)
    }

    @Test
    fun oppositeDirectionIntegralCannotKeepPullingTargetDown() {
        val out = controller.evaluate(
            input(
                base = 6.5,
                currentGlucose = 6.5,
                observedDelta5 = 0.0,
                pred5 = 6.5,
                pred30 = 6.5,
                pred60 = 6.5,
                ciLow5 = 6.4,
                ciHigh5 = 6.6,
                ciLow30 = 6.4,
                ciHigh30 = 6.6,
                ciLow60 = 6.4,
                ciHigh60 = 6.6,
                prevTarget = 4.5,
                prevI = 200.0,
                safetyIobUnits = 1.4
            )
        )

        assertThat(out.reason).isEqualTo("control_pi")
        assertThat(out.debugFields["integralDirectionReset"]).isEqualTo(1.0)
        assertThat(out.updatedI).isAtLeast(-15.0)
        assertThat(out.updatedI).isAtMost(15.0)
        assertThat(out.newTempTarget).isGreaterThan(6.5)
    }

    @Test
    fun steadyHighWithoutConfirmedRiseDoesNotJumpToFour() {
        val out = controller.evaluate(
            input(
                base = 6.5,
                currentGlucose = 10.0,
                observedDelta5 = 0.04,
                pred5 = 10.1,
                pred30 = 10.0,
                pred60 = 9.6,
                ciLow5 = 9.4,
                ciHigh5 = 10.8,
                ciLow30 = 8.7,
                ciHigh30 = 11.2,
                ciLow60 = 8.0,
                ciHigh60 = 11.2,
                prevTarget = 6.5,
                prevI = 0.0,
                safetyIobUnits = 1.0
            )
        )

        assertThat(out.debugFields["aggressiveRiseEligible"]).isEqualTo(0.0)
        assertThat(out.newTempTarget).isAtLeast(5.5)
    }

    @Test
    fun confirmedStrongRiseCanUseFourPointZeroTarget() {
        val out = controller.evaluate(
            input(
                base = 6.5,
                currentGlucose = 8.0,
                observedDelta5 = 0.42,
                pred5 = 12.0,
                pred30 = 14.0,
                pred60 = 15.0,
                ciLow5 = 10.8,
                ciHigh5 = 13.2,
                ciLow30 = 11.5,
                ciHigh30 = 16.5,
                ciLow60 = 11.0,
                ciHigh60 = 17.0,
                prevTarget = 5.5,
                prevI = 0.0,
                safetyIobUnits = 0.8
            )
        )

        assertThat(out.debugFields["aggressiveRiseEligible"]).isEqualTo(1.0)
        assertThat(out.newTempTarget).isEqualTo(4.0)
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
        currentGlucose: Double? = null,
        observedDelta5: Double? = null,
        uamActive: Boolean = false,
        cobGrams: Double? = null,
        safetyIobUnits: Double? = 0.0,
        rapidFallPriorConfirmedCycles: Int = 0,
        targetMin: Double = AdaptiveTempTargetController.TMIN,
        targetMax: Double = AdaptiveTempTargetController.TMAX
    ) = AdaptiveTempTargetController.Input(
        nowTs = System.currentTimeMillis(),
        baseTarget = base,
        targetMinMmol = targetMin,
        targetMaxMmol = targetMax,
        currentGlucoseMmol = currentGlucose,
        observedDelta5Mmol = observedDelta5,
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
        safetyIobUnits = safetyIobUnits,
        rapidFallPriorConfirmedCycles = rapidFallPriorConfirmedCycles
    )
}
