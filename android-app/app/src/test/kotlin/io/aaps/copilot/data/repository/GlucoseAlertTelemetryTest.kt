package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import org.junit.Test

class GlucoseAlertTelemetryTest {

    @Test
    fun recordsNotificationPermissionAndDeliveryOutcome() {
        val rows = AutomationRepository.buildGlucoseAlertTelemetryRowsStatic(
            nowTs = 1_000L,
            decision = GlucoseAlertDecision(
                state = GlucoseAlertState.LOW_NOW,
                direction = GlucoseAlertDirection.LOW,
                notifyKind = GlucoseAlertNotifyKind.LOW_NOW,
                nextState = GlucoseAlertRuntimeState(activeAlertState = GlucoseAlertState.LOW_NOW),
                lowThreshold = 4.0,
                highThreshold = 10.0,
                urgentLowThreshold = 4.0,
                pred5 = 3.7,
                pred30 = 3.5,
                pred60 = 3.4,
                ciLow30 = 3.2,
                ciHigh30 = 3.8,
                currentGlucoseMmol = 3.8,
                currentGlucoseFresh = true,
                predictedMinutesToLow = 0,
                trendDelta5Mmol = -0.1,
                softActive = false,
                strongActive = true,
                repeatSuppressedByTrend = false,
                disableReason = null
            ),
            persisted = GlucoseAlertRuntimeState(activeAlertState = GlucoseAlertState.LOW_NOW),
            delivery = GlucoseAlertDeliveryResult(
                notificationPermissionGranted = true,
                notificationPosted = true,
                vibrationSucceeded = true,
                audioPlayed = true
            ),
            insulinSnapshot = InsulinRuntimeSnapshot(
                timestamp = 900L,
                source = InsulinRuntimeSource.AAPS_COMPONENTS,
                netIobUnits = -0.5,
                bolusIobUnits = 0.4,
                basalIobUnits = -0.9,
                insulinActivity = 0.012,
                effectivePositiveIobUnits = 0.4,
                confidence = 1.0,
                fallbackReason = null
            ),
            sensitivitySnapshot = SensitivityRuntimeSnapshot(
                settingsRevision = 19L,
                forecastCycleId = "cycle-alert-19",
                timestamp = 950L,
                isf = sensitivityDecision(4.2, SensitivityResolvedSource.AAPS),
                cr = sensitivityDecision(11.0, SensitivityResolvedSource.COPILOT_NATIVE)
            )
        ).associateBy { it.key }

        assertThat(rows.getValue("glucose_alert_notification_permission_granted").valueDouble)
            .isEqualTo(1.0)
        assertThat(rows.getValue("glucose_alert_notification_posted").valueDouble)
            .isEqualTo(1.0)
        assertThat(rows.getValue("glucose_alert_vibration_attempted").valueDouble)
            .isEqualTo(1.0)
        assertThat(rows.getValue("glucose_alert_audio_played").valueDouble)
            .isEqualTo(1.0)
        assertThat(rows.getValue("glucose_alert_iob_net_units").valueDouble).isEqualTo(-0.5)
        assertThat(rows.getValue("glucose_alert_iob_effective_positive_units").valueDouble).isEqualTo(0.4)
        assertThat(rows.getValue("glucose_alert_iob_basal_units").valueDouble).isEqualTo(-0.9)
        assertThat(rows.getValue("glucose_alert_insulin_activity").valueDouble).isEqualTo(0.012)
        assertThat(rows.getValue("glucose_alert_sensitivity_cycle_id").valueText)
            .isEqualTo("cycle-alert-19")
        assertThat(rows.getValue("glucose_alert_sensitivity_settings_revision").valueDouble)
            .isEqualTo(19.0)
        assertThat(rows.getValue("glucose_alert_isf_effective").valueDouble).isEqualTo(4.2)
        assertThat(rows.getValue("glucose_alert_cr_effective").valueDouble).isEqualTo(11.0)
    }

    private fun sensitivityDecision(value: Double, source: SensitivityResolvedSource) =
        SensitivityMetricDecision(
            requested = if (source == SensitivityResolvedSource.AAPS) {
                SensitivitySourcePreference.AAPS
            } else {
                SensitivitySourcePreference.COPILOT
            },
            resolved = source,
            rawAaps = 4.2,
            rawEvidence = 4.8,
            rawCopilot = value,
            blended = null,
            effective = value,
            confidence = 1.0,
            fallbackReason = null
        )
}
