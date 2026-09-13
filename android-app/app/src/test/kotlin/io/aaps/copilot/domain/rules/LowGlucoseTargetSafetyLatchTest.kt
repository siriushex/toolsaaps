package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LowGlucoseTargetSafetyLatchTest {

    @Test
    fun preservesRaisedTargetUntilTwoConsecutiveSafeCycles() {
        val latch = LowGlucoseTargetSafetyLatch()

        val risk = latch.update(
            currentGlucoseMmol = 5.38,
            forecastMinimumMmol = 3.8,
            activeSafetyTargetMmol = 6.6,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = true
        )
        assertThat(risk.latched).isTrue()
        assertThat(risk.safeCycles).isEqualTo(0)
        assertThat(latch.protectTarget(4.2, risk)).isEqualTo(6.6)

        val firstSafe = latch.update(
            currentGlucoseMmol = 5.1,
            forecastMinimumMmol = 4.1,
            activeSafetyTargetMmol = 6.6,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        assertThat(firstSafe.latched).isTrue()
        assertThat(firstSafe.safeCycles).isEqualTo(1)
        assertThat(latch.protectTarget(4.2, firstSafe)).isEqualTo(6.6)

        val secondSafe = latch.update(
            currentGlucoseMmol = 5.2,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = 6.6,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        assertThat(secondSafe.latched).isFalse()
        assertThat(secondSafe.safeCycles).isEqualTo(0)
        assertThat(latch.protectTarget(4.2, secondSafe)).isEqualTo(4.2)
    }

    @Test
    fun proposedSafetyTargetRaisesProtectedFloor() {
        val latch = LowGlucoseTargetSafetyLatch()

        val state = latch.update(
            currentGlucoseMmol = 4.5,
            forecastMinimumMmol = 3.5,
            activeSafetyTargetMmol = 5.0,
            proposedSafetyTargetMmol = 6.6,
            seedFromActiveSafety = true
        )

        assertThat(state.protectedTargetMmol).isEqualTo(6.6)
        assertThat(latch.protectTarget(4.0, state)).isEqualTo(6.6)
        assertThat(latch.protectTarget(10.0, state)).isEqualTo(10.0)
    }

    @Test
    fun restartSeedRetainsRecentSafetyTargetForRecoveryCycle() {
        val restartedLatch = LowGlucoseTargetSafetyLatch()

        val state = restartedLatch.update(
            currentGlucoseMmol = 4.8,
            forecastMinimumMmol = 4.1,
            activeSafetyTargetMmol = 6.6,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = true
        )

        assertThat(state.latched).isTrue()
        assertThat(state.safeCycles).isEqualTo(1)
        assertThat(restartedLatch.protectTarget(4.2, state)).isEqualTo(6.6)
    }

    @Test
    fun diagnosticPreviewDoesNotAdvanceTwoSafeCycleReleaseLatch() {
        val diagnosticPath = LowGlucoseTargetSafetyLatch()
        val controlPath = LowGlucoseTargetSafetyLatch()
        listOf(diagnosticPath, controlPath).forEach { latch ->
            latch.update(
                currentGlucoseMmol = 5.3,
                forecastMinimumMmol = 3.8,
                activeSafetyTargetMmol = 6.6,
                proposedSafetyTargetMmol = null,
                seedFromActiveSafety = true
            )
        }

        val diagnostic = diagnosticPath.preview(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        val firstNormalAfterDiagnostic = diagnosticPath.update(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        val firstControl = controlPath.update(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        val secondNormalAfterDiagnostic = diagnosticPath.update(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        val secondControl = controlPath.update(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )

        assertThat(diagnostic.safeCycles).isEqualTo(1)
        assertThat(firstNormalAfterDiagnostic).isEqualTo(firstControl)
        assertThat(firstNormalAfterDiagnostic.latched).isTrue()
        assertThat(secondNormalAfterDiagnostic).isEqualTo(secondControl)
        assertThat(secondNormalAfterDiagnostic.latched).isFalse()
    }

    @Test
    fun diagnosticPreviewDoesNotCommitFreshLatchInitializationOrRisk() {
        val diagnosticPath = LowGlucoseTargetSafetyLatch()
        val controlPath = LowGlucoseTargetSafetyLatch()

        val diagnostic = diagnosticPath.preview(
            currentGlucoseMmol = 3.8,
            forecastMinimumMmol = 3.7,
            activeSafetyTargetMmol = 6.6,
            proposedSafetyTargetMmol = 6.8,
            seedFromActiveSafety = true
        )
        val normalAfterDiagnostic = diagnosticPath.update(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )
        val control = controlPath.update(
            currentGlucoseMmol = 5.3,
            forecastMinimumMmol = 4.2,
            activeSafetyTargetMmol = null,
            proposedSafetyTargetMmol = null,
            seedFromActiveSafety = false
        )

        assertThat(diagnostic.riskNow).isTrue()
        assertThat(diagnostic.latched).isTrue()
        assertThat(diagnostic.protectedTargetMmol).isEqualTo(6.8)
        assertThat(normalAfterDiagnostic).isEqualTo(control)
        assertThat(normalAfterDiagnostic.latched).isFalse()
        assertThat(normalAfterDiagnostic.safeCycles).isEqualTo(0)
        assertThat(normalAfterDiagnostic.protectedTargetMmol).isNull()
    }
}
