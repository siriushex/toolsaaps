package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverviewClinicalStatusTest {

    @Test
    fun currentValueUsesDisplayedTargetRange() {
        assertThat(resolveCurrentGlucoseStatus(7.9, false, 3.9, 6.7))
            .isEqualTo(OverviewCurrentGlucoseStatus.ABOVE_TARGET)
        assertThat(resolveCurrentGlucoseStatus(3.8, false, 3.9, 6.7))
            .isEqualTo(OverviewCurrentGlucoseStatus.BELOW_TARGET)
        assertThat(resolveCurrentGlucoseStatus(6.7, false, 3.9, 6.7))
            .isEqualTo(OverviewCurrentGlucoseStatus.IN_TARGET)
    }

    @Test
    fun currentMissingAndStaleStatesFailExplicitly() {
        assertThat(resolveCurrentGlucoseStatus(null, true, 3.9, 6.7))
            .isEqualTo(OverviewCurrentGlucoseStatus.NO_DATA)
        assertThat(resolveCurrentGlucoseStatus(5.6, true, 3.9, 6.7))
            .isEqualTo(OverviewCurrentGlucoseStatus.STALE)
        assertThat(resolveCurrentGlucoseStatus(Double.NaN, false, 3.9, 6.7))
            .isEqualTo(OverviewCurrentGlucoseStatus.NO_DATA)
    }

    @Test
    fun forecastValueUsesDisplayedTargetRangeIndependently() {
        assertThat(resolveForecastGlucoseStatus(2.3, 3.9, 6.7))
            .isEqualTo(OverviewForecastGlucoseStatus.LOW)
        assertThat(resolveForecastGlucoseStatus(5.6, 3.9, 6.7))
            .isEqualTo(OverviewForecastGlucoseStatus.IN_RANGE)
        assertThat(resolveForecastGlucoseStatus(7.9, 3.9, 6.7))
            .isEqualTo(OverviewForecastGlucoseStatus.HIGH)
    }

    @Test
    fun invalidForecastInputsAreMissing() {
        assertThat(resolveForecastGlucoseStatus(null, 3.9, 6.7))
            .isEqualTo(OverviewForecastGlucoseStatus.MISSING)
        assertThat(resolveForecastGlucoseStatus(Double.POSITIVE_INFINITY, 3.9, 6.7))
            .isEqualTo(OverviewForecastGlucoseStatus.MISSING)
        assertThat(resolveForecastGlucoseStatus(5.6, 6.7, 3.9))
            .isEqualTo(OverviewForecastGlucoseStatus.MISSING)
    }

    @Test
    fun matrixRowHeight_isStableForEachResponsiveMode() {
        assertThat(overviewClinicalMatrixRowHeightDp(useTwoColumns = false, fontScale = 1.0f))
            .isEqualTo(72f)
        assertThat(overviewClinicalMatrixRowHeightDp(useTwoColumns = true, fontScale = 1.0f))
            .isEqualTo(78f)
        assertThat(overviewClinicalMatrixRowHeightDp(useTwoColumns = true, fontScale = 1.3f))
            .isWithin(0.001f)
            .of(92.4f)
    }

    @Test
    fun targetManagerOutcomeNeverClaimsAapsApplied() {
        assertThat(targetManagerOutcomeUiKind("DISPATCH_DISABLED"))
            .isEqualTo(TargetManagerOutcomeUiKind.BLOCKED)
        assertThat(targetManagerOutcomeUiKind("SEND"))
            .isEqualTo(TargetManagerOutcomeUiKind.SUBMITTED_PENDING_READBACK)
        assertThat(targetManagerOutcomeUiKind("RENEW_SAME_TARGET"))
            .isEqualTo(TargetManagerOutcomeUiKind.SUBMITTED_PENDING_READBACK)
        assertThat(targetManagerOutcomeUiKind("DELIVERY_FAILED"))
            .isEqualTo(TargetManagerOutcomeUiKind.DELIVERY_FAILED)
        assertThat(targetManagerOutcomeUiKind("BLOCK_FORECAST_RELIABILITY"))
            .isEqualTo(TargetManagerOutcomeUiKind.BLOCKED)
    }

    @Test
    fun targetManagerReasonUsesOnlyBoundedCategories() {
        assertThat(targetManagerReasonUiKind("external_target_writer_conflict").name)
            .isEqualTo("EXTERNAL_WRITER_CONFLICT")
        assertThat(targetManagerReasonUiKind("iob_unqualified"))
            .isEqualTo(TargetManagerReasonUiKind.IOB_UNQUALIFIED)
        assertThat(targetManagerReasonUiKind("external_target_retained"))
            .isEqualTo(TargetManagerReasonUiKind.EXTERNAL_TARGET)
        assertThat(targetManagerReasonUiKind("private-id=123"))
            .isEqualTo(TargetManagerReasonUiKind.NO_PROPOSAL)
    }

    @Test
    fun preflightReasonsAreNeutralAndDistinctFromDeliveryFailure() {
        assertThat(targetManagerReasonUiKind("eating_soon_target_active"))
            .isEqualTo(TargetManagerReasonUiKind.EATING_SOON_ACTIVE)
        assertThat(targetManagerOutcomeUiKind("BLOCK_MANUAL_TARGET"))
            .isEqualTo(TargetManagerOutcomeUiKind.BLOCKED)
        assertThat(targetManagerReasonUiKind("manual_target_active_or_pending"))
            .isEqualTo(TargetManagerReasonUiKind.MANUAL_TARGET_RETAINED)
        assertThat(targetManagerReasonUiKind("current_glucose_newer_than_candidate"))
            .isEqualTo(TargetManagerReasonUiKind.CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE)
        assertThat(targetManagerReasonUiKind("target_manager_settings_changed"))
            .isEqualTo(TargetManagerReasonUiKind.PREFLIGHT_BLOCKED)
        assertThat(targetManagerReasonUiKind("delivery_failed"))
            .isEqualTo(TargetManagerReasonUiKind.DELIVERY_FAILED)
    }
}
