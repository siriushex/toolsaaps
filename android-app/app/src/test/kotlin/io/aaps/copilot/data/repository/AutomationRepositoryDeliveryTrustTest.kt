package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.target.DeliveryTrustState
import io.aaps.copilot.domain.target.SensorTrustState
import org.junit.Test

class AutomationRepositoryDeliveryTrustTest {

    @Test
    fun minuteCadenceRetainsEnoughHistoryForDeliveryAssessment() {
        val result = AutomationRepository.resolveLiveDeliveryTrustStatic(
            nowTs = NOW,
            canonicalGlucose = (0..25).map { minute ->
                GlucosePoint(
                    ts = NOW - (25 - minute) * 60_000L,
                    valueMmol = 5.0 + minute * 0.11,
                    source = "synthetic_minute_sensor",
                    quality = DataQuality.OK
                )
            },
            therapyEvents = emptyList(),
            sensorTrust = SensorTrustState.TRUSTED,
            latestTelemetry = completeTelemetry()
        )
        assertThat(result).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun slightlyShortMinuteCadenceRetainsACompleteDeliveryObservationSpan() {
        val result = AutomationRepository.resolveLiveDeliveryTrustStatic(
            nowTs = NOW,
            canonicalGlucose = (0..25).map { age ->
                GlucosePoint(
                    ts = NOW - 59_999L * age,
                    valueMmol = 8.0 - 0.11 * age,
                    source = "synthetic_jittered_sensor",
                    quality = DataQuality.OK
                )
            },
            therapyEvents = emptyList(),
            sensorTrust = SensorTrustState.TRUSTED,
            latestTelemetry = completeTelemetry()
        )
        assertThat(result).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun completeLiveEvidenceCanReachSuspectedNonresponse() {
        val result = AutomationRepository.resolveLiveDeliveryTrustStatic(
            nowTs = NOW,
            canonicalGlucose = risingGlucose(),
            therapyEvents = emptyList(),
            sensorTrust = SensorTrustState.TRUSTED,
            latestTelemetry = completeTelemetry()
        )

        assertThat(result).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
    }

    @Test
    fun recentAnnouncedCarbsKeepDeliveryStateNormal() {
        val result = AutomationRepository.resolveLiveDeliveryTrustStatic(
            nowTs = NOW,
            canonicalGlucose = risingGlucose(),
            therapyEvents = listOf(
                TherapyEvent(
                    ts = NOW - 30L * 60_000L,
                    type = "carbs",
                    payload = mapOf("grams" to "15")
                )
            ),
            sensorTrust = SensorTrustState.TRUSTED,
            latestTelemetry = completeTelemetry()
        )

        assertThat(result).isEqualTo(DeliveryTrustState.NORMAL)
    }

    @Test
    fun missingSetAgeOrMalformedCarbEvidenceFailsClosedAsUnknown() {
        val missingSetAge = AutomationRepository.resolveLiveDeliveryTrustStatic(
            nowTs = NOW,
            canonicalGlucose = risingGlucose(),
            therapyEvents = emptyList(),
            sensorTrust = SensorTrustState.TRUSTED,
            latestTelemetry = completeTelemetry() - "isf_factor_set_age_hours"
        )
        val malformedCarbs = AutomationRepository.resolveLiveDeliveryTrustStatic(
            nowTs = NOW,
            canonicalGlucose = risingGlucose(),
            therapyEvents = listOf(
                TherapyEvent(
                    ts = NOW - 30L * 60_000L,
                    type = "carbs",
                    payload = mapOf("grams" to "not-a-number")
                )
            ),
            sensorTrust = SensorTrustState.TRUSTED,
            latestTelemetry = completeTelemetry()
        )

        assertThat(missingSetAge).isEqualTo(DeliveryTrustState.UNKNOWN)
        assertThat(malformedCarbs).isEqualTo(DeliveryTrustState.UNKNOWN)
    }

    private fun completeTelemetry(): Map<String, Double?> = mapOf(
        "iob_units" to 1.5,
        "cob_effective_grams" to 0.0,
        "uam_runtime_control_flag" to 0.0,
        "isf_factor_set_age_hours" to 96.0
    )

    private fun risingGlucose(): List<GlucosePoint> = listOf(5.0, 5.5, 6.0, 6.6)
        .mapIndexed { index, value ->
            GlucosePoint(
                ts = NOW - (3 - index) * 5L * 60_000L,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
