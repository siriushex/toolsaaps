package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverviewRawGlucosePresentationTest {

    @Test
    fun rawCompanionIsShownWhenCalibrationChangesPrimaryValue() {
        assertThat(
            shouldShowRawGlucoseCompanion(
                primaryGlucoseMmol = 6.2,
                rawGlucoseMmol = 4.2
            )
        ).isTrue()
    }

    @Test
    fun rawCompanionIsHiddenWhenValuesAreEffectivelyEqual() {
        assertThat(
            shouldShowRawGlucoseCompanion(
                primaryGlucoseMmol = 4.21,
                rawGlucoseMmol = 4.20
            )
        ).isFalse()
    }
}
