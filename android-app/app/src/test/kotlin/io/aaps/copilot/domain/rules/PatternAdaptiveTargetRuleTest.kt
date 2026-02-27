package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.model.RuleState
import org.junit.Test

class PatternAdaptiveTargetRuleTest {

    private val rule = PatternAdaptiveTargetRule()

    @Test
    fun returnsNoMatch_whenWindowIsNotRisk() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            RuleContext(
                nowTs = now,
                glucose = listOf(GlucosePoint(now, 5.8, "test", DataQuality.OK)),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = PatternWindow(
                    dayType = DayType.WEEKDAY,
                    hour = 9,
                    sampleCount = 120,
                    activeDays = 20,
                    lowRate = 0.04,
                    highRate = 0.06,
                    recommendedTargetMmol = 5.5,
                    isRiskWindow = false
                ),
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = false
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.NO_MATCH)
        assertThat(decision.reasons).contains("no_risk_window")
    }

    @Test
    fun triggers_whenRiskWindowHasTargetShift() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            RuleContext(
                nowTs = now,
                glucose = listOf(GlucosePoint(now, 5.8, "test", DataQuality.OK)),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = PatternWindow(
                    dayType = DayType.WEEKEND,
                    hour = 2,
                    sampleCount = 84,
                    activeDays = 10,
                    lowRate = 0.24,
                    highRate = 0.05,
                    recommendedTargetMmol = 5.95,
                    isRiskWindow = true
                ),
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = false
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.actionProposal!!.targetMmol).isWithin(0.001).of(5.95)
        assertThat(decision.reasons).contains("samples=84")
    }
}
