package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.RuleState
import org.junit.Test

class SegmentProfileGuardRuleTest {

    private val rule = SegmentProfileGuardRule()

    @Test
    fun triggers_whenSegmentShowsHigherSensitivity() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            RuleContext(
                nowTs = now,
                glucose = listOf(GlucosePoint(now, 5.8, "test", DataQuality.OK)),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                currentProfileEstimate = ProfileEstimate(
                    isfMmolPerUnit = 1.5,
                    crGramPerUnit = 10.0,
                    confidence = 0.8,
                    sampleCount = 50,
                    isfSampleCount = 25,
                    crSampleCount = 25,
                    lookbackDays = 365
                ),
                currentProfileSegment = ProfileSegmentEstimate(
                    dayType = DayType.WEEKDAY,
                    timeSlot = ProfileTimeSlot.MORNING,
                    isfMmolPerUnit = 2.0,
                    crGramPerUnit = 9.8,
                    confidence = 0.7,
                    isfSampleCount = 7,
                    crSampleCount = 5,
                    lookbackDays = 365
                )
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.actionProposal!!.targetMmol).isWithin(0.001).of(5.8)
        assertThat(decision.reasons).contains("segment_more_sensitive")
    }

    @Test
    fun noMatch_whenSegmentConfidenceLow() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            RuleContext(
                nowTs = now,
                glucose = listOf(GlucosePoint(now, 5.6, "test", DataQuality.OK)),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                currentProfileEstimate = ProfileEstimate(
                    isfMmolPerUnit = 1.5,
                    crGramPerUnit = 10.0,
                    confidence = 0.8,
                    sampleCount = 50,
                    isfSampleCount = 25,
                    crSampleCount = 25,
                    lookbackDays = 365
                ),
                currentProfileSegment = ProfileSegmentEstimate(
                    dayType = DayType.WEEKEND,
                    timeSlot = ProfileTimeSlot.NIGHT,
                    isfMmolPerUnit = 2.0,
                    crGramPerUnit = 8.0,
                    confidence = 0.2,
                    isfSampleCount = 2,
                    crSampleCount = 2,
                    lookbackDays = 365
                )
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.NO_MATCH)
        assertThat(decision.reasons).contains("segment_confidence_low")
    }
}
