package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class OverviewCobTelemetryTest {
    @Test fun primaryQueryIncludesCanonicalAndRawCob() {
        assertThat(buildPrimaryTelemetryKeysForUi()).containsAtLeast("cob_grams", "raw_cob", "cob_effective_grams")
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

    private fun effective(value: Double, ts: Long = now) = row(value, ts, "cob_effective_grams")
        .copy(source = "copilot_runtime_cob_iob")

    @Test fun primaryUsesExistingMixedEstimateEvenWhenExternalIsNewer() {
        val samples = listOf(effective(18.4, now - 1L), row(73.0), row(75.0, key = "raw_cob"))
        assertThat(selectEffectiveCobForUi(samples, now, 600_000L)?.valueDouble).isEqualTo(18.4)
        assertThat(selectEffectiveCobForUi(listOf(row(73.0)), now, 600_000L)).isNull()
        assertThat(selectEffectiveCobForUi(listOf(effective(0.0), row(73.0)), now, 600_000L)?.valueDouble).isEqualTo(0.0)
    }

    @Test fun latestInvalidRuntimeDoesNotReviveOlderEstimate() {
        val old = effective(18.4, now - 1L)
        listOf(effective(Double.NaN), effective(Double.POSITIVE_INFINITY), effective(-1.0), effective(81.0),
            effective(20.0).copy(valueDouble = null), effective(20.0).copy(unit = "U"),
            effective(20.0).copy(quality = "INVALID"), effective(20.0, now + 1L)).forEach { invalid ->
            assertThat(selectEffectiveCobForUi(listOf(old, invalid, row(73.0)), now, 600_000L)).isNull()
        }
    }

    @Test fun effectiveSourceAndInclusiveFreshnessAreStrict() {
        listOf(effective(20.0, 0L), effective(20.0, -1L), effective(20.0, now - 600_001L),
            effective(20.0).copy(source = "aaps_broadcast"), effective(20.0).copy(key = "cob_grams")).forEach {
            assertThat(selectEffectiveCobForUi(listOf(it), now, 600_000L)).isNull()
        }
        assertThat(selectEffectiveCobForUi(listOf(effective(80.0, now - 600_000L)), now, 600_000L)?.valueDouble)
            .isEqualTo(80.0)
    }

    @Test fun conflictingSameTimeEstimatesAreUnavailableRegardlessOfOrder() {
        val a = effective(18.4).copy(id = "a")
        val b = effective(19.0).copy(id = "b")
        listOf(listOf(a, b), listOf(b, a)).forEach {
            assertThat(selectEffectiveCobForUi(it, now, 600_000L)).isNull()
        }
        assertThat(selectEffectiveCobForUi(listOf(a, a.copy(id = "c")), now, 600_000L)).isEqualTo(a)
    }
}
