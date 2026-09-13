package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class OverviewCobTelemetryTest {
    @Test fun primaryQueryIncludesCanonicalAndRawCob() {
        assertThat(buildPrimaryTelemetryKeysForUi()).containsAtLeast("cob_grams", "raw_cob")
    }
    private val now = 1_788_591_000_000L
    private fun row(value: Double, ts: Long = now, key: String = "cob_grams") = TelemetrySampleEntity(
        id = key, timestamp = ts, source = "aaps_broadcast", key = key,
        valueDouble = value, valueText = null, unit = "g", quality = "OK"
    )

    @Test fun importedCobIsUnclippedAndSeparateFromEffectiveCob() {
        val effective = row(1.7, now + 1L, "cob_effective_grams").copy(source = "copilot_runtime_cob_iob")
        assertThat(resolveExternalCobForUi(listOf(row(75.0), row(0.0, key = "raw_cob"), effective), now, 600_000L))
            .isEqualTo(75.0)
        assertThat(resolveExternalCobForUi(listOf(effective), now, 600_000L)).isNull()
        assertThat(resolveExternalCobForUi(listOf(row(0.0)), now, 600_000L)).isEqualTo(0.0)
    }

    @Test fun unavailableStaleFutureAndInvalidValuesAreNotPresentedAsCurrent() {
        listOf(row(20.0, now - 600_001L), row(20.0, now + 1L), row(Double.NaN), row(-1.0), row(401.0))
            .forEach { assertThat(resolveExternalCobForUi(listOf(it), now, 600_000L)).isNull() }
        assertThat(resolveExternalCobForUi(listOf(row(20.0, now - 600_000L)), now, 600_000L)).isEqualTo(20.0)
    }
}
