package io.aaps.copilot.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class AiChatContextSummaryTest {
    @Test
    fun selectedProfileAndFittedEvidenceAreNotLostOrCalledAMealDelay() {
        val summary = buildAiChatContextSummary(MainUiState().apply {
            insulinProfileId = "FIASP"
            insulinRealOnsetMinutes = 27.5
            insulinRealProfileOnsetMinutes = 26.0
            insulinRealProfileUpdatedTs = 1000L
            insulinRealProfileConfidence = 0.7
            insulinRealProfileSamples = 12
            insulinRealProfileStatus = "estimated_daily"
        }, 1234L)
        assertTrue(summary.contains("configured_insulin_profile_id=FIASP"))
        assertTrue(summary.contains("configured_insulin_profile_label=Fiasp"))
        assertTrue(summary.contains("insulin_profile_estimated_onset_minutes=26.00"))
        assertTrue(summary.contains("insulin_profile_updated_at_ms=1000"))
        assertTrue(summary.contains("insulin_profile_sample_count=12"))
        assertTrue(summary.contains("insulin_onset_is_not_meal_waiting_interval=true"))
        assertTrue(summary.contains("current_settings_do_not_identify_historical_bolus_insulin=true"))
    }

    @Test
    fun limitedAndMissingDataStayExplicitInsteadOfBlockingChatOrInventingNormality() {
        val summary = buildAiChatContextSummary(MainUiState(), 1234L)
        assertTrue(summary.contains("context_generated_at_ms=1234"))
        assertTrue(summary.contains("snapshot_is_complete_history=false"))
        assertTrue(summary.contains("analytics_ready=false"))
        assertTrue(summary.contains("glucose_current=--"))
        assertTrue(summary.contains("glucose_age_minutes=unknown"))
        assertTrue(summary.contains("sensor_blocked=unknown"))
        assertTrue(summary.contains("uam_calc_active=unknown"))
        assertTrue(summary.contains("daily_report_generated_ts=unknown"))
        assertTrue(summary.contains("forecast_accepted_generation_ms=unknown"))
        assertTrue(summary.toByteArray(StandardCharsets.UTF_8).size < 8_000)
    }

    @Test
    fun forecastsSensorAndCobKeepTheirOwnEvidenceInsteadOfSharingFreshGlucoseTime() {
        val summary = buildAiChatContextSummary(MainUiState().apply {
            forecastAcceptedGenerationTs = 100L
            latestExternalCobGrams = 48.0
            latestExternalCobTimestamp = 200L
            latestEffectiveCobGrams = 18.5
            latestEffectiveCobTimestamp = 300L
            sensorAgeHours = 300.0
            sensorAgeSource = "device_declared"
            sensorQualitySuspectFalseLow = true
        }, 1234L)
        assertTrue(summary.contains("forecast_accepted_generation_ms=100"))
        assertTrue(summary.contains("cob_external_grams=48.00 timestamp_ms=200"))
        assertTrue(summary.contains("cob_effective_grams=18.50 timestamp_ms=300"))
        assertTrue(summary.contains("sensor_age_hours=300.00 source=device_declared"))
        assertTrue(summary.contains("suspect_false_low=true"))
    }

    @Test
    fun positiveAndSignedNetIobAreDistinctAndOnsetIsNotDuration() {
        val state = MainUiState().apply {
            latestIobUnits = 3.3
            latestIobRealUnits = -2.2
            insulinRealOnsetMinutes = 25.0
            latestDataAgeMinutes = 17L
        }
        val summary = buildAiChatContextSummary(state, 1234L)
        assertTrue(summary.contains("iob_positive_units=3.30 iob_net_units=-2.20"))
        assertTrue(summary.contains("insulin_onset_minutes=25.00"))
        assertTrue(summary.contains("insulin_action_duration_hours=not_provided"))
        assertTrue(summary.contains("glucose_age_minutes=17"))
        assertFalse(summary.contains("dia_h="))
    }

    @Test
    fun nonFiniteValuesAreMissingRatherThanUsableMeasurements() {
        val summary = buildAiChatContextSummary(MainUiState().apply {
            latestGlucoseMmol = Double.NaN
            latestCobGrams = Double.POSITIVE_INFINITY
        }, 1234L)
        assertTrue(summary.contains("glucose_current=--"))
        assertTrue(summary.contains("cob_grams=--"))
        assertFalse(summary.contains("NaN"))
        assertFalse(summary.contains("Infinity"))
    }
}
