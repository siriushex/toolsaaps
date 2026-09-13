package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.SegmentProfileGuardRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import org.junit.Test

class SensorTrustP0ReplayTest {

    @Test
    fun unsafeSensorTrustRejectsCalibrationAllowsRollbackAndBlocksFallbackProposal() {
        val bloodTs = 1_000_000L
        val alignedTs = bloodTs + 10 * 60_000L
        val alignedRaw = GlucosePoint(alignedTs, 8.0, "synthetic")
        var queriedTrustTs: Long? = null
        val calibration = GlucoseCalibrationGuard.assess(
            sessionKey = "synthetic-session",
            bloodTs = bloodTs,
            bloodMmol = 8.0,
            lagMinutes = 10.0,
            rawGlucose = listOf(alignedRaw),
            telemetryAt = { queriedTs ->
                queriedTrustTs = queriedTs
                CalibrationTrustSnapshot(
                    sensorBlocked = true,
                    sensorSuspectFalseLow = true,
                    stale = false,
                    available = true
                )
            }
        )
        val rollbackAllowed = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 8.0,
            baseTargetMmol = 5.5,
            assessment = AutomationRepository.SensorQualityAssessment(
                score = 0.2,
                blocked = true,
                reason = "synthetic_rapid_delta",
                suspectFalseLow = false,
                delta5Mmol = 2.0,
                noiseStd5Mmol = 0.8,
                gapMinutes = 1.0
            )
        )

        val ruleDecision = RuleEngine(
            rules = listOf(SegmentProfileGuardRule()),
            safetyPolicy = SafetyPolicy()
        ).evaluate(
            context = RuleContext(
                nowTs = 2_000_000L,
                glucose = emptyList(),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = true,
                currentProfileEstimate = ProfileEstimate(
                    isfMmolPerUnit = 2.0,
                    crGramPerUnit = 10.0,
                    confidence = 0.8,
                    sampleCount = 20,
                    isfSampleCount = 10,
                    crSampleCount = 10,
                    lookbackDays = 14
                ),
                currentProfileSegment = ProfileSegmentEstimate(
                    dayType = DayType.WEEKDAY,
                    timeSlot = ProfileTimeSlot.NIGHT,
                    isfMmolPerUnit = 3.0,
                    crGramPerUnit = 10.0,
                    confidence = 0.8,
                    isfSampleCount = 10,
                    crSampleCount = 10,
                    lookbackDays = 14
                )
            ),
            config = SafetyPolicyConfig(killSwitch = false)
        ).single()

        assertThat(calibration.matchedRaw).isEqualTo(alignedRaw)
        assertThat(calibration.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(calibration.reason).isEqualTo("sensor_blocked_aligned")
        assertThat(queriedTrustTs).isEqualTo(alignedTs)
        assertThat(rollbackAllowed).isTrue()
        assertThat(ruleDecision.ruleId).isEqualTo("SegmentProfileGuard.v1")
        assertThat(ruleDecision.state).isEqualTo(RuleState.BLOCKED)
        assertThat(ruleDecision.actionProposal).isNull()
        assertThat(ruleDecision.reasons).contains("sensor_blocked")
    }
}
