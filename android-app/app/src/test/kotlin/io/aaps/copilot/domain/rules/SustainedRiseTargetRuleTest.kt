package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.target.TargetProposalFactory
import org.junit.Assert.*
import org.junit.Test

class SustainedRiseTargetRuleTest {
    private val now = 1_700_000_000_000L
    private fun context(values: List<Double> = listOf(8.6, 9.0, 9.4)) = RuleContext(
        nowTs = now,
        glucose = values.mapIndexed { i, value -> GlucosePoint(now - (values.lastIndex-i)*300_000L, value, "sensor") },
        therapyEvents = emptyList(),
        forecasts = listOf(5,30,60).map { Forecast(now+it*60_000L, it, 10.0, 7.0, 13.0, "local") },
        currentDayPattern = null, baseTargetMmol = 6.7, dataFresh = true,
        activeTempTargetMmol = 5.7, actionsLast6h = 0, currentGlucoseMmol = values.last(), safetyIobUnits = 3.6
    )

    @Test fun confirmedRiseUsesFourPointOneForThirtyMinutes() {
        val result = AdaptiveTargetControllerRule().evaluate(context())
        assertEquals(4.1, result.actionProposal!!.targetMmol, 1e-9)
        assertEquals(30, result.actionProposal!!.durationMinutes)
        assertTrue(result.actionProposal!!.reason.contains("sustained_rise_control"))
        val proposal = TargetProposalFactory().fromRuleDecision(result, 120, now, "rise-input")!!
        assertEquals("SustainedRiseTarget.v1", proposal.sourceRuleId)
    }

    @Test fun holdStillRequiresFreshProposalAndStoppedRiseRestoresCalculation() {
        val rule = AdaptiveTargetControllerRule()
        rule.evaluate(context())
        val hold = rule.evaluate(context().copy(activeTempTargetMmol = 4.1))
        assertEquals(4.1, hold.actionProposal!!.targetMmol, 1e-9)
        val stopped = rule.evaluate(context(listOf(9.0,9.4,9.4)).copy(activeTempTargetMmol = 4.1))
        assertTrue(stopped.actionProposal!!.targetMmol > 4.1)
        assertFalse(stopped.actionProposal!!.reason.contains("sustained_rise_control"))
    }

    @Test fun reachingCalculatedBaseMustReplaceAnActiveLowTarget() {
        val input = context(listOf(5.5,5.5,5.5)).copy(baseTargetMmol = 5.5, activeTempTargetMmol = 4.1,
            safetyIobUnits = 0.0, forecasts = listOf(5,30,60).map {
                Forecast(now+it*60_000L, it, 5.5, 5.3, 5.7, "local")
            })
        val result = AdaptiveTargetControllerRule().evaluate(input)
        assertEquals(RuleState.TRIGGERED, result.state)
        assertEquals(5.5, result.actionProposal!!.targetMmol, 1e-9)
    }

    @Test fun shortOrNonRisingHistoryCannotSelectTheNewMode() {
        for (values in listOf(listOf(9.0,9.4), listOf(8.9,9.0,8.9), listOf(8.6,8.6,9.4), listOf(8.1,8.3,8.5))) {
            assertFalse(AdaptiveTargetControllerRule().evaluate(context(values)).actionProposal?.reason
                ?.contains("sustained_rise_control") == true)
        }
    }

    @Test fun lowForecastStaleEvidenceAndMissingIobDoNotAuthorizeNewMode() {
        val initial = context()
        val unsafe = listOf(initial.copy(dataFresh = false), initial.copy(sensorBlocked = true),
            initial.copy(safetyIobUnits = null), initial.copy(adaptiveMinTargetMmol = 4.5),
            initial.copy(nowTs = now + 600_000L),
            initial.copy(forecasts = initial.forecasts.filter { it.horizonMinutes != 60 }),
            initial.copy(forecasts = initial.forecasts.map { if(it.horizonMinutes == 60) it.copy(ciLow = 3.9) else it }))
        unsafe.forEach {
            assertFalse(AdaptiveTargetControllerRule().evaluate(it).actionProposal?.reason
                ?.contains("sustained_rise_control") == true)
        }
    }

    @Test fun conflictingSamplesAndGapsCannotProveTenMinutesOfRise() {
        val initial = context()
        assertTrue(SustainedRiseTargetPolicy.qualifies(initial.copy(glucose = initial.glucose + initial.glucose.last())))
        val invalid = listOf(
            initial.copy(glucose = initial.glucose + initial.glucose.last().copy(valueMmol = 10.0)),
            initial.copy(glucose = initial.glucose.mapIndexed { i, point ->
                if (i == 1) point.copy(ts = now - 60_000L) else point
            }),
            initial.copy(glucose = initial.glucose + initial.glucose.last().copy(ts = now + 1)),
            initial.copy(glucose = initial.glucose.mapIndexed { i, point ->
                if (i == 1) point.copy(valueMmol = Double.NaN) else point
            })
        )
        invalid.forEach { assertFalse(SustainedRiseTargetPolicy.qualifies(it)) }
    }

    @Test fun ForecastsMustBelongToOneFreshCycleWithOrderedFiniteIntervals() {
        val initial = context()
        val invalid = listOf(
            initial.copy(forecasts = initial.forecasts.map { if (it.horizonMinutes == 60) it.copy(ts = it.ts - 1) else it }),
            initial.copy(forecasts = initial.forecasts.map { it.copy(ts = it.ts + 1) }),
            initial.copy(forecasts = initial.forecasts.map { it.copy(ciLow = 4.0) }),
            initial.copy(forecasts = initial.forecasts.map { it.copy(ciHigh = 9.0) }),
            initial.copy(forecasts = initial.forecasts.map { it.copy(ciHigh = Double.POSITIVE_INFINITY) }),
            initial.copy(actionChronologyResolved = false)
        )
        invalid.forEach { assertFalse(SustainedRiseTargetPolicy.qualifies(it)) }
    }
}
