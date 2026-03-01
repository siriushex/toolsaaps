package io.aaps.copilot.domain.activity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActivityIntensityTest {

    @Test
    fun mapsPaceToExpectedRatioBands() {
        assertThat(ActivityIntensity.ratioFromPacePerMinute(0.0)).isWithin(0.001).of(0.95)
        assertThat(ActivityIntensity.ratioFromPacePerMinute(10.0)).isWithin(0.001).of(1.0)
        assertThat(ActivityIntensity.ratioFromPacePerMinute(25.0)).isWithin(0.001).of(1.1)
        assertThat(ActivityIntensity.ratioFromPacePerMinute(45.0)).isWithin(0.001).of(1.25)
        assertThat(ActivityIntensity.ratioFromPacePerMinute(90.0)).isWithin(0.001).of(1.5)
        assertThat(ActivityIntensity.ratioFromPacePerMinute(160.0)).isWithin(0.001).of(1.8)
    }

    @Test
    fun mapsRatioToReadableLabel() {
        assertThat(ActivityIntensity.labelFromRatio(0.95)).isEqualTo("rest")
        assertThat(ActivityIntensity.labelFromRatio(1.0)).isEqualTo("light")
        assertThat(ActivityIntensity.labelFromRatio(1.2)).isEqualTo("moderate")
        assertThat(ActivityIntensity.labelFromRatio(1.6)).isEqualTo("high")
    }
}

