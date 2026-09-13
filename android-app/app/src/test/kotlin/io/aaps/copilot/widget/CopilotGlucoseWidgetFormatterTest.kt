package io.aaps.copilot.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CopilotGlucoseWidgetFormatterTest {

    @Test
    fun format_showsCalibratedSnapshotValues() {
        val text = CopilotGlucoseWidgetFormatter.format(
            CopilotGlucoseWidgetSnapshot(
                currentGlucoseMmol = 6.84,
                rawGlucoseMmol = 6.4,
                predicted30Mmol = 7.21,
                iobUnits = 1.234,
                cobGrams = 18.6,
                trendDeltaMmol = 0.32,
                sampleAgeMinutes = 4,
                forecastAgeMinutes = 3,
                updatedAtMs = 1_700_000_000_000L,
                calibrationApplied = true
            )
        )

        assertThat(text.current).isEqualTo("6.8")
        assertThat(text.predicted30).isEqualTo("7.2")
        assertThat(text.iob).isEqualTo("1.23")
        assertThat(text.cob).isEqualTo("19")
        assertThat(text.trend).isEqualTo("↑ +0.3")
        assertThat(text.age).isEqualTo("4m ago")
        assertThat(text.status).isEqualTo("Calibrated")
    }

    @Test
    fun format_marksStaleWhenSampleAgeIsHigh() {
        val text = CopilotGlucoseWidgetFormatter.format(
            CopilotGlucoseWidgetSnapshot(
                currentGlucoseMmol = 5.4,
                rawGlucoseMmol = 5.4,
                predicted30Mmol = null,
                iobUnits = null,
                cobGrams = null,
                trendDeltaMmol = -0.18,
                sampleAgeMinutes = 24,
                forecastAgeMinutes = null,
                updatedAtMs = 1_700_000_000_000L,
                calibrationApplied = false
            )
        )

        assertThat(text.status).isEqualTo("Stale")
        assertThat(text.trend).isEqualTo("↘ -0.2")
    }
}
