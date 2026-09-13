package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import org.junit.Test

class MealPressureResolverTest {

    @Test
    fun announcedCarbsAndUamAreBlendedNotSummed() {
        val resolved = MealPressureResolver.resolve(
            MealPressureInput(
                announcedCarbSteps = doubleArrayOf(0.0, 0.30, 0.25),
                uamSteps = doubleArrayOf(0.0, 0.40, 0.30),
                residualCobNowGrams = 25.0,
                announcedCarbCoverage = 1.0,
                uamConfidence = 0.80
            )
        )
        assertThat(resolved.steps[1]).isAtMost(0.40)
        assertThat(resolved.steps[1]).isLessThan(0.70)
        assertThat(resolved.announcedWeight + resolved.uamWeight).isEqualTo(1.0)
    }

    @Test
    fun noAnnouncedPressureUsesConfidenceScaledUam() {
        val resolved = resolve(
            announced = doubleArrayOf(0.0, 0.0, 0.0),
            uam = doubleArrayOf(0.0, 0.40, 0.30),
            residualCob = 0.0,
            coverage = 1.0,
            confidence = 0.75
        )

        assertThat(resolved.steps[0]).isEqualTo(0.0)
        assertThat(resolved.steps[1]).isWithin(1e-9).of(0.30)
        assertThat(resolved.steps[2]).isWithin(1e-9).of(0.225)
        assertThat(resolved.announcedWeight).isEqualTo(0.0)
        assertThat(resolved.uamWeight).isEqualTo(0.75)
        assertThat(resolved.source).isEqualTo("UAM_ONLY")
    }

    @Test
    fun completeHistoryUsesOneNormalizedWeightAndItsExactComplement() {
        val resolved = resolve(
            announced = doubleArrayOf(0.20, 0.40),
            uam = doubleArrayOf(0.60, 0.20),
            residualCob = 10.0,
            coverage = 1.0,
            confidence = 0.50
        )

        assertThat(resolved.announcedWeight).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(resolved.uamWeight).isEqualTo(1.0 - resolved.announcedWeight)
        assertThat(resolved.announcedWeight + resolved.uamWeight).isEqualTo(1.0)
        assertThat(resolved.steps[0]).isWithin(1e-9).of(1.0 / 3.0)
        assertThat(resolved.steps[1]).isWithin(1e-9).of(1.0 / 3.0)
        assertThat(resolved.source).isEqualTo("BLENDED")
    }

    @Test
    fun equalCandidatesRemainBoundedWithFractionalWeights() {
        val candidate = 0.70
        val resolved = resolve(
            announced = doubleArrayOf(candidate),
            uam = doubleArrayOf(candidate),
            coverage = 0.37,
            confidence = 0.91
        )

        assertThat(resolved.steps[0]).isEqualTo(candidate)
        assertThat(resolved.announcedWeight + resolved.uamWeight).isEqualTo(1.0)
    }

    @Test
    fun largeFiniteCandidatesNeverOverflow() {
        val announced = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE * 0.90, 1.0)
        val uam = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val resolved = resolve(
            announced = announced,
            uam = uam,
            coverage = 0.37,
            confidence = 0.91
        )

        assertFiniteAndBounded(resolved, announced, uam)
        assertThat(resolved.steps[0]).isEqualTo(Double.MAX_VALUE)
    }

    @Test
    fun everyValidResolutionStepIsFiniteNonNegativeAndAtMostTheGreaterCandidate() {
        val cases = listOf(
            ValidCase(doubleArrayOf(0.0, 0.30), doubleArrayOf(0.0, 0.40), 0.0, 1.0, 0.80),
            ValidCase(doubleArrayOf(0.50, 0.10), doubleArrayOf(0.20, 0.90), 0.0, 0.20, 0.90),
            ValidCase(doubleArrayOf(0.20, 0.60), doubleArrayOf(0.80, 0.10), 20.0, 0.35, 0.85),
            ValidCase(
                doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE * 0.50),
                doubleArrayOf(Double.MAX_VALUE * 0.75, Double.MAX_VALUE),
                0.0,
                0.63,
                0.47
            ),
            ValidCase(doubleArrayOf(0.30, 0.20), doubleArrayOf(0.90, 0.80), 0.0, 0.0, 0.0)
        )

        cases.forEach { case ->
            val resolved = resolve(
                announced = case.announced,
                uam = case.uam,
                residualCob = case.residualCob,
                coverage = case.coverage,
                confidence = case.confidence
            )

            assertFiniteAndBounded(resolved, case.announced, case.uam)
        }
    }

    @Test
    fun zeroNormalizedSupportPreservesAnnouncedPressure() {
        val resolved = resolve(
            announced = doubleArrayOf(0.30, 0.20),
            uam = doubleArrayOf(0.90, 0.80),
            residualCob = 0.0,
            coverage = 0.0,
            confidence = 0.0
        )

        assertThat(resolved.steps.asList()).containsExactly(0.30, 0.20).inOrder()
        assertThat(resolved.announcedWeight).isEqualTo(1.0)
        assertThat(resolved.uamWeight).isEqualTo(0.0)
        assertThat(resolved.source).isEqualTo("ANNOUNCED_ONLY")
    }

    @Test
    fun incompleteCarbHistoryRetainsAnnouncedAndOnlyUsesAttenuatedUamAlternative() {
        val resolved = resolve(
            announced = doubleArrayOf(0.10, 0.30, 0.20),
            uam = doubleArrayOf(0.40, 0.20, 0.80),
            residualCob = 25.0,
            coverage = 0.25,
            confidence = 0.80
        )

        assertThat(resolved.steps[0]).isWithin(1e-9).of(0.24)
        assertThat(resolved.steps[1]).isWithin(1e-9).of(0.30)
        assertThat(resolved.steps[2]).isWithin(1e-9).of(0.48)
        assertThat(resolved.announcedWeight).isEqualTo(1.0)
        assertThat(resolved.uamWeight).isWithin(1e-9).of(0.60)
        assertThat(resolved.source).isEqualTo("INCOMPLETE_CARB_HISTORY")
    }

    @Test
    fun incompleteExternalCobAttenuatesUamBeforeNoAnnouncedFallback() {
        val announced = doubleArrayOf(0.0, 0.0)
        val uam = doubleArrayOf(0.40, 0.80)

        listOf(0.0, 0.25, Math.nextDown(1.0)).forEach { coverage ->
            val resolved = resolve(
                announced = announced,
                uam = uam,
                residualCob = 25.0,
                coverage = coverage,
                confidence = 0.80
            )
            val expectedWeight = 0.80 * (1.0 - coverage)

            assertThat(resolved.steps[0]).isEqualTo(uam[0] * expectedWeight)
            assertThat(resolved.steps[1]).isEqualTo(uam[1] * expectedWeight)
            assertThat(resolved.announcedWeight).isEqualTo(1.0)
            assertThat(resolved.uamWeight).isEqualTo(expectedWeight)
            assertThat(resolved.source).isEqualTo("INCOMPLETE_CARB_HISTORY")
            assertFiniteAndBounded(resolved, announced, uam)
        }
    }

    @Test
    fun incompleteHistorySourceIsRetainedWhenUamCandidateIsZero() {
        val resolved = resolve(
            announced = doubleArrayOf(0.0, 0.30, 0.20),
            uam = doubleArrayOf(0.0, 0.0, 0.0),
            residualCob = 20.0,
            coverage = 0.20,
            confidence = 0.90
        )

        assertThat(resolved.steps.asList()).containsExactly(0.0, 0.30, 0.20).inOrder()
        assertThat(resolved.announcedWeight).isEqualTo(1.0)
        assertThat(resolved.uamWeight).isEqualTo(0.0)
        assertThat(resolved.source).isEqualTo("INCOMPLETE_CARB_HISTORY")
    }

    @Test
    fun nonFiniteScalarsAreUntrustedFiniteZeroValues() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { untrusted ->
            val coverageResult = resolve(
                announced = doubleArrayOf(0.20),
                uam = doubleArrayOf(0.60),
                residualCob = 0.0,
                coverage = untrusted,
                confidence = 0.50
            )
            val confidenceResult = resolve(
                announced = doubleArrayOf(0.20),
                uam = doubleArrayOf(0.60),
                residualCob = 0.0,
                coverage = 1.0,
                confidence = untrusted
            )
            val residualResult = resolve(
                announced = doubleArrayOf(0.20),
                uam = doubleArrayOf(0.60),
                residualCob = untrusted,
                coverage = 0.50,
                confidence = 1.0
            )

            assertThat(coverageResult.steps[0]).isEqualTo(0.60)
            assertThat(coverageResult.announcedWeight).isEqualTo(0.0)
            assertThat(coverageResult.uamWeight).isEqualTo(1.0)
            assertThat(confidenceResult.steps[0]).isEqualTo(0.20)
            assertThat(confidenceResult.announcedWeight).isEqualTo(1.0)
            assertThat(confidenceResult.uamWeight).isEqualTo(0.0)
            assertThat(residualResult.source).isEqualTo("BLENDED")
            assertThat(residualResult.announcedWeight + residualResult.uamWeight).isEqualTo(1.0)
            assertFiniteAndBounded(residualResult, doubleArrayOf(0.20), doubleArrayOf(0.60))
        }
    }

    @Test
    fun finiteScalarsAreClampedToTheirBounds() {
        val resolved = resolve(
            announced = doubleArrayOf(0.20, 0.40),
            uam = doubleArrayOf(0.60, 0.20),
            residualCob = -10.0,
            coverage = 2.0,
            confidence = 2.0
        )

        assertThat(resolved.steps[0]).isWithin(1e-9).of(0.40)
        assertThat(resolved.steps[1]).isWithin(1e-9).of(0.30)
        assertThat(resolved.announcedWeight).isEqualTo(0.50)
        assertThat(resolved.uamWeight).isEqualTo(0.50)
        assertThat(resolved.source).isEqualTo("BLENDED")
    }

    @Test
    fun invalidUamPreservesValidAnnouncedPressureWithoutThrowing() {
        val announced = doubleArrayOf(0.10, 0.30)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01).forEach { invalidStep ->
            val resolved = resolve(
                announced = announced,
                uam = doubleArrayOf(0.0, invalidStep)
            )

            assertThat(resolved.steps.asList()).containsExactly(0.10, 0.30).inOrder()
            assertThat(resolved.announcedWeight).isEqualTo(1.0)
            assertThat(resolved.uamWeight).isEqualTo(0.0)
            assertThat(resolved.source).isEqualTo("INVALID_INPUT")
            assertFiniteAndBounded(resolved, announced, doubleArrayOf(0.0, 0.0))
        }
    }

    @Test
    fun invalidAnnouncedPressureReturnsFiniteZeroHorizonWithoutThrowing() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01).forEach { invalidStep ->
            val resolved = resolve(
                announced = doubleArrayOf(0.0, invalidStep),
                uam = doubleArrayOf(0.10, 0.20)
            )

            assertThat(resolved.steps.asList()).containsExactly(0.0, 0.0).inOrder()
            assertThat(resolved.announcedWeight).isEqualTo(0.0)
            assertThat(resolved.uamWeight).isEqualTo(0.0)
            assertThat(resolved.source).isEqualTo("INVALID_INPUT")
            assertThat(resolved.steps.all { it.isFinite() }).isTrue()
        }
    }

    @Test
    fun emptyShorterAndLongerUamPreserveValidAnnouncedContourDefensively() {
        listOf(
            doubleArrayOf(),
            doubleArrayOf(0.40, 0.20),
            doubleArrayOf(0.40, 0.20, 0.10, 0.05)
        ).forEach { uam ->
            val announced = doubleArrayOf(0.10, 0.30, 0.20)
            val expected = announced.copyOf()
            val first = resolve(announced = announced, uam = uam)
            val second = resolve(announced = expected.copyOf(), uam = uam.copyOf())
            val expectedHash = first.hashCode()

            announced.fill(99.0)
            uam.fill(99.0)
            first.steps.fill(99.0)

            assertThat(first.steps.asList()).containsExactlyElementsIn(expected.asList()).inOrder()
            assertThat(first.steps.all { it.isFinite() }).isTrue()
            assertThat(first.announcedWeight).isEqualTo(1.0)
            assertThat(first.uamWeight).isEqualTo(0.0)
            assertThat(first.source).isEqualTo("INVALID_INPUT")
            assertThat(first).isEqualTo(second)
            assertThat(first.hashCode()).isEqualTo(expectedHash)
            assertThat(first.hashCode()).isEqualTo(second.hashCode())
        }
    }

    @Test
    fun emptyAnnouncedInputReturnsDeterministicFiniteZeroHorizon() {
        val uam = doubleArrayOf(0.10, 0.20)
        val first = resolve(announced = doubleArrayOf(), uam = uam)
        val second = resolve(announced = doubleArrayOf(), uam = uam.copyOf())

        uam.fill(99.0)
        first.steps.fill(99.0)

        assertThat(first.steps.asList()).containsExactly(0.0, 0.0).inOrder()
        assertThat(first.steps.all { it.isFinite() }).isTrue()
        assertThat(first.announcedWeight).isEqualTo(0.0)
        assertThat(first.uamWeight).isEqualTo(0.0)
        assertThat(first.source).isEqualTo("INVALID_INPUT")
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    @Test
    fun zeroInputsReturnDeterministicIsolatedOutput() {
        val announced = doubleArrayOf(0.0, 0.0)
        val uam = doubleArrayOf(0.0, 0.0)
        val first = resolve(announced = announced, uam = uam)
        val second = resolve(announced = announced, uam = uam)

        announced[0] = 1.0
        uam[1] = 1.0
        val exposed = first.steps
        exposed[0] = 1.0

        assertThat(first.steps.asList()).containsExactly(0.0, 0.0).inOrder()
        assertThat(second.steps.asList()).containsExactly(0.0, 0.0).inOrder()
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
        assertThat(first.source).isEqualTo("NONE")
    }

    @Test
    fun nonzeroAnnouncedOnlyOutputIsDefensivelyIsolatedAndContentComparable() {
        assertMutationIsolationAndValueSemantics(
            announced = doubleArrayOf(0.10, 0.30),
            uam = doubleArrayOf(0.0, 0.0),
            expected = doubleArrayOf(0.10, 0.30)
        )
    }

    @Test
    fun blendedOutputIsDefensivelyIsolatedAndContentComparable() {
        assertMutationIsolationAndValueSemantics(
            announced = doubleArrayOf(0.20, 0.40),
            uam = doubleArrayOf(0.60, 0.20),
            expected = doubleArrayOf(0.40, 0.30),
            coverage = 1.0,
            confidence = 1.0
        )
    }

    private fun assertMutationIsolationAndValueSemantics(
        announced: DoubleArray,
        uam: DoubleArray,
        expected: DoubleArray,
        coverage: Double = 1.0,
        confidence: Double = 1.0
    ) {
        val announcedCopy = announced.copyOf()
        val uamCopy = uam.copyOf()
        val first = resolve(announced, uam, coverage = coverage, confidence = confidence)
        val second = resolve(announcedCopy, uamCopy, coverage = coverage, confidence = confidence)
        val firstHash = first.hashCode()

        announced.fill(99.0)
        uam.fill(99.0)
        first.steps.fill(99.0)

        expected.indices.forEach { index ->
            assertThat(first.steps[index]).isWithin(1e-12).of(expected[index])
        }
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(firstHash)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    private fun assertFiniteAndBounded(
        resolved: ResolvedMealPressure,
        announced: DoubleArray,
        uam: DoubleArray
    ) {
        assertThat(resolved.steps.size).isEqualTo(announced.size)
        resolved.steps.indices.forEach { index ->
            val step = resolved.steps[index]
            assertThat(step.isFinite()).isTrue()
            assertThat(step).isAtLeast(0.0)
            assertThat(step).isAtMost(max(announced[index], uam[index]))
        }
    }

    private fun resolve(
        announced: DoubleArray,
        uam: DoubleArray,
        residualCob: Double = 0.0,
        coverage: Double = 1.0,
        confidence: Double = 1.0
    ): ResolvedMealPressure = MealPressureResolver.resolve(
        MealPressureInput(
            announcedCarbSteps = announced,
            uamSteps = uam,
            residualCobNowGrams = residualCob,
            announcedCarbCoverage = coverage,
            uamConfidence = confidence
        )
    )

    private data class ValidCase(
        val announced: DoubleArray,
        val uam: DoubleArray,
        val residualCob: Double,
        val coverage: Double,
        val confidence: Double
    )
}
