package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_KEYS
import io.aaps.copilot.data.repository.ACCEPTED_SENSITIVITY_MARKER_KEYS
import io.aaps.copilot.data.repository.TargetManagerLiveStatus
import io.aaps.copilot.data.repository.TargetManagerLiveStatusCodec
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import org.junit.Test

class MainViewModelPrimaryTelemetryKeysTest {

    @Test
    fun primaryOverviewTelemetryIncludesGlucoseAlertRuntimeContract() {
        val keys = buildPrimaryTelemetryKeysForUi()

        assertThat(keys).containsAtLeast(
            "glucose_alert_state",
            "glucose_alert_direction",
            "glucose_alert_disable_reason",
            "glucose_alert_soft_active",
            "glucose_alert_strong_active"
        )
    }

    @Test
    fun primaryOverviewTelemetryIncludesCurrentSensorAgeForTargetSafety() {
        val keys = buildPrimaryTelemetryKeysForUi()

        assertThat(keys).containsAtLeast("sensor_age_hours", "sensor_lag_age_hours")
    }

    @Test
    fun primaryOverviewTelemetryIncludesAtomicIobSourceCode() {
        assertThat(buildPrimaryTelemetryKeysForUi()).contains("iob_runtime_source_code")
    }

    @Test
    fun primaryOverviewTelemetryIncludesExactCompleteAcceptedSensitivityMarkerSet() {
        val keys = buildPrimaryTelemetryKeysForUi()

        assertThat(keys.filter(ACCEPTED_SENSITIVITY_MARKER_KEYS::contains))
            .containsExactlyElementsIn(ACCEPTED_SENSITIVITY_MARKER_KEYS)
        assertThat(keys).contains(SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY)
    }

    @Test
    fun primaryOverviewTelemetryIncludesExactCompleteAcceptedCalibrationMarkerSet() {
        val keys = buildPrimaryTelemetryKeysForUi()

        assertThat(keys.filter(ACCEPTED_CALIBRATION_KEYS::contains))
            .containsExactlyElementsIn(ACCEPTED_CALIBRATION_KEYS)
    }

    @Test
    fun primaryOverviewTelemetryIncludesAndDecodesOnlyExactTargetManagerStatusRow() {
        val status = TargetManagerLiveStatus(
            schemaVersion = TargetManagerLiveStatusCodec.SCHEMA_VERSION,
            timestamp = 10_000L,
            mode = "ACTIVE",
            priorityEnabled = true,
            policyRevision = 4L,
            currentTargetMmol = 6.2,
            proposedTargetMmol = 5.9,
            outcome = "SEND",
            reason = "eligible"
        )
        val exact = TargetManagerLiveStatusCodec.toTelemetryRow(status)
        val keyCollision = exact.copy(
            id = "other",
            timestamp = exact.timestamp + 1L,
            source = "other"
        )

        assertThat(buildPrimaryTelemetryKeysForUi()).contains(TargetManagerLiveStatusCodec.KEY)
        assertThat(resolveTargetManagerLiveStatusForUi(listOf(keyCollision, exact))).isEqualTo(status)
        assertThat(resolveTargetManagerLiveStatusForUi(listOf(keyCollision))).isNull()
    }
}
