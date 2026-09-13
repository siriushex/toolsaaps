package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BloodCheckValueValidatorTest {

    @Test
    fun mmol_acceptsOnlyFiniteSupportedRange() {
        assertThat(isSupportedBloodCheckValue("2.2", "mmol/L")).isTrue()
        assertThat(isSupportedBloodCheckValue("5,6", "mmol/L")).isTrue()
        assertThat(isSupportedBloodCheckValue("22.0", "mmol/L")).isTrue()

        listOf("", "NaN", "Infinity", "-Infinity", "5,6,7", "5,6.7", "2.19", "22.01").forEach { value ->
            assertThat(isSupportedBloodCheckValue(value, "mmol/L")).isFalse()
        }
    }

    @Test
    fun mgdl_acceptsOnlyFiniteSupportedRangeEquivalentToRepositoryBounds() {
        assertThat(isSupportedBloodCheckValue("39.7", "mg/dL")).isTrue()
        assertThat(isSupportedBloodCheckValue("396.4", "mg/dL")).isTrue()

        listOf("", "NaN", "Infinity", "-Infinity", "39.6", "396.5").forEach { value ->
            assertThat(isSupportedBloodCheckValue(value, "mg/dL")).isFalse()
        }
    }

    @Test
    fun unsupportedUnitsFailClosed() {
        assertThat(isSupportedBloodCheckValue("5.6", "unknown")).isFalse()
    }
}
