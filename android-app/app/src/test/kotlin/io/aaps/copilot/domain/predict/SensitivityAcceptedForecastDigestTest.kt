package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitivityAcceptedForecastDigestTest {

    @Test
    fun canonicalDigestIsStableAcrossInputOrder() {
        val rows = forecastRows()

        val forward = SensitivityAcceptedForecastDigest.compute("cycle-7", 7L, rows, decomposition())
        val reverse = SensitivityAcceptedForecastDigest.compute("cycle-7", 7L, rows.reversed(), decomposition())

        assertThat(forward).isNotEmpty()
        assertThat(reverse).isEqualTo(forward)
    }

    @Test
    fun changedForecastValueCannotAuthenticateAgainstAcceptedDigest() {
        val accepted = SensitivityAcceptedForecastDigest.compute("cycle-7", 7L, forecastRows())
        val overwritten = forecastRows().map { row ->
            if (row.horizonMinutes == 30) row.copy(valueMmol = row.valueMmol + 0.1) else row
        }

        assertThat(SensitivityAcceptedForecastDigest.matches("cycle-7", 7L, overwritten, accepted)).isFalse()
    }

    @Test
    fun duplicateOrMissingRequiredHorizonIsRejected() {
        val rows = forecastRows()

        assertThat(SensitivityAcceptedForecastDigest.compute("cycle-7", 7L, rows.dropLast(1))).isNull()
        assertThat(SensitivityAcceptedForecastDigest.compute("cycle-7", 7L, rows + rows.first())).isNull()
    }

    @Test
    fun changedDecompositionCannotAuthenticateAgainstAcceptedDigest() {
        val accepted = SensitivityAcceptedForecastDigest.compute(
            "cycle-7",
            7L,
            forecastRows(),
            decomposition()
        )

        assertThat(
            SensitivityAcceptedForecastDigest.matches(
                "cycle-7",
                7L,
                forecastRows(),
                decomposition().copy(therapy60Mmol = -0.9),
                accepted
            )
        ).isFalse()
    }

    @Test
    fun decompositionCanonicalTextRoundTripsDeterministically() {
        val value = decomposition()
        val encoded = SensitivityAcceptedForecastDecompositionCodec.encode(value)

        assertThat(SensitivityAcceptedForecastDecompositionCodec.decode(encoded)).isEqualTo(value)
        assertThat(SensitivityAcceptedForecastDecompositionCodec.encode(value)).isEqualTo(encoded)
    }

    @Test
    fun validUnicodeIsByteExactAndIsNotSilentlyNormalized() {
        val nonAscii = decomposition().copy(modelVersion = "модель-β")
        val encoded = SensitivityAcceptedForecastDecompositionCodec.encode(nonAscii)
        val composed = SensitivityAcceptedForecastDigest.compute("cycle-café", 7L, forecastRows(), nonAscii)
        val decomposed = SensitivityAcceptedForecastDigest.compute("cycle-cafe\u0301", 7L, forecastRows(), nonAscii)

        assertThat(SensitivityAcceptedForecastDecompositionCodec.decode(encoded)).isEqualTo(nonAscii)
        assertThat(composed).isNotNull()
        assertThat(decomposed).isNotNull()
        assertThat(composed).isNotEqualTo(decomposed)
    }

    @Test
    fun malformedSurrogatesAreRejectedInsteadOfUtf8Replacement() {
        val malformedHigh = "bad-\uD800"
        val malformedLow = "bad-\uDC00"

        assertThat(SensitivityAcceptedForecastDigest.compute(malformedHigh, 7L, forecastRows())).isNull()
        assertThat(
            SensitivityAcceptedForecastDigest.compute(
                "cycle-7",
                7L,
                forecastRows().map { it.copy(modelVersion = malformedLow) }
            )
        ).isNull()
        assertThat(
            runCatching {
                SensitivityAcceptedForecastDecompositionCodec.encode(
                    decomposition().copy(modelVersion = malformedHigh)
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun decompositionDecoderRejectsInvalidHexAndMalformedUtf8() {
        val prefix = "v1|-|-|-|-|-|-|"

        assertThat(SensitivityAcceptedForecastDecompositionCodec.decode(prefix + "0g")).isNull()
        assertThat(SensitivityAcceptedForecastDecompositionCodec.decode(prefix + "c328")).isNull()
    }

    @Test
    fun signedZeroHasOneCanonicalDigestAndNonFiniteValuesFailClosed() {
        val positiveZeroRows = forecastRows().map {
            it.copy(valueMmol = 0.0, ciLow = 0.0, ciHigh = 0.0)
        }
        val negativeZeroRows = forecastRows().map {
            it.copy(valueMmol = -0.0, ciLow = -0.0, ciHigh = -0.0)
        }
        val positive = SensitivityAcceptedForecastDigest.compute(
            "cycle-zero",
            7L,
            positiveZeroRows,
            decomposition().copy(trend60Mmol = 0.0)
        )
        val negative = SensitivityAcceptedForecastDigest.compute(
            "cycle-zero",
            7L,
            negativeZeroRows,
            decomposition().copy(trend60Mmol = -0.0)
        )

        assertThat(negative).isEqualTo(positive)
        assertThat(
            SensitivityAcceptedForecastDigest.compute(
                "cycle-7",
                7L,
                forecastRows().map { it.copy(valueMmol = Double.NaN) }
            )
        ).isNull()
        assertThat(
            SensitivityAcceptedForecastDigest.compute(
                "cycle-7",
                7L,
                forecastRows(),
                decomposition().copy(kfSigmaGMmol = Double.POSITIVE_INFINITY)
            )
        ).isNull()
    }

    @Test
    fun canonicalTextBoundsAreEnforcedByUtf8ByteLength() {
        val modelAtLimit = "é".repeat(128)
        val modelOverLimit = modelAtLimit + "a"
        val cycleAtLimit = "c".repeat(512)
        val cycleOverLimit = cycleAtLimit + "c"

        assertThat(
            SensitivityAcceptedForecastDigest.compute(
                cycleAtLimit,
                7L,
                forecastRows().map { it.copy(modelVersion = modelAtLimit) },
                decomposition().copy(modelVersion = modelAtLimit)
            )
        ).isNotNull()
        assertThat(
            SensitivityAcceptedForecastDigest.compute(
                cycleOverLimit,
                7L,
                forecastRows()
            )
        ).isNull()
        assertThat(
            SensitivityAcceptedForecastDigest.compute(
                "cycle-7",
                7L,
                forecastRows().map { it.copy(modelVersion = modelOverLimit) }
            )
        ).isNull()
        assertThat(
            runCatching {
                SensitivityAcceptedForecastDecompositionCodec.encode(
                    decomposition().copy(modelVersion = modelOverLimit)
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun forecastRows() = listOf(5, 30, 60).map { horizon ->
        SensitivityAcceptedForecastRow(
            horizonMinutes = horizon,
            targetTimestamp = GENERATION_TS + horizon * 60_000L,
            valueMmol = 6.0 + horizon / 100.0,
            ciLow = 5.0 + horizon / 100.0,
            ciHigh = 7.0 + horizon / 100.0,
            modelVersion = "local-v3"
        )
    }

    private fun decomposition() = SensitivityAcceptedForecastDecomposition(
        trend60Mmol = 0.7,
        therapy60Mmol = -0.4,
        uam60Mmol = 0.2,
        residualRoc0Mmol5 = 0.05,
        sigmaEMmol5 = 0.11,
        kfSigmaGMmol = 0.09,
        modelVersion = "local-v3"
    )

    private companion object {
        const val GENERATION_TS = 1_786_435_200_000L
    }
}
