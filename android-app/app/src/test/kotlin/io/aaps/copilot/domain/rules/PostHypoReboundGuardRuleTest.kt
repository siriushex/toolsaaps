package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.RuleState
import org.junit.Test

class PostHypoReboundGuardRuleTest {

    private val rule = PostHypoReboundGuardRule()

    @Test
    fun triggers_whenHypoAndTwoRisingIntervals() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(ts = now - 20 * 60_000, valueMmol = 3.0, source = "test", quality = DataQuality.OK),
            GlucosePoint(ts = now - 15 * 60_000, valueMmol = 3.3, source = "test", quality = DataQuality.OK),
            GlucosePoint(ts = now - 10 * 60_000, valueMmol = 3.6, source = "test", quality = DataQuality.OK),
            GlucosePoint(ts = now - 5 * 60_000, valueMmol = 3.9, source = "test", quality = DataQuality.OK)
        )

        val decision = rule.evaluate(
            RuleContext(
                nowTs = now,
                glucose = glucose,
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = false
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal?.targetMmol).isWithin(0.001).of(4.4)
        assertThat(decision.actionProposal?.durationMinutes).isEqualTo(60)
    }

    @Test
    fun blocked_whenDataStale() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            RuleContext(
                nowTs = now,
                glucose = emptyList(),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = false,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = false
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.BLOCKED)
    }
}
