package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamExportMode
import org.junit.Test

class GlucoseAlertEngineTest {

    private val engine = GlucoseAlertEngine()

    @Test
    fun watch60_usesConfidenceBandAndNeedsTwoCycles() {
        val settings = testSettings()
        val baseTs = 1_000_000L
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                pred5 = 5.5,
                pred30 = 4.8,
                pred60 = 4.0,
                ciLow30 = 4.6,
                ciHigh30 = 5.4
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 60_000L,
                pred5 = 5.4,
                pred30 = 4.7,
                pred60 = 3.9,
                ciLow30 = 4.5,
                ciHigh30 = 5.3
            ),
            persisted = first.nextState
        )

        assertThat(first.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(first.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(second.state).isEqualTo(GlucoseAlertState.WATCH_60)
        assertThat(second.notifyKind).isEqualTo(GlucoseAlertNotifyKind.WATCH_60)
    }

    @Test
    fun softHigh_fallsBackToPred30WhenCiMissing() {
        val settings = testSettings().copy(softAlertUseConfidenceBand = true)
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 0L,
                pred30 = 10.4,
                ciLow30 = null,
                ciHigh30 = null
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 60_000L,
                pred30 = 10.6,
                ciLow30 = null,
                ciHigh30 = null
            ),
            persisted = first.nextState
        )

        assertThat(second.state).isEqualTo(GlucoseAlertState.SOFT_HIGH_RISK)
        assertThat(second.direction).isEqualTo(GlucoseAlertDirection.HIGH)
    }

    @Test
    fun warning30DoesNotRepeatWithinOneEpisode() {
        val settings = testSettings()
        val baseTs = 1_000_000L
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                pred5 = 5.0,
                pred30 = 4.0,
                pred60 = 3.4,
                ciLow30 = 3.8,
                ciHigh30 = 4.9
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 60_000L,
                pred5 = 5.0,
                pred30 = 3.9,
                pred60 = 3.3,
                ciLow30 = 3.7,
                ciHigh30 = 4.8
            ),
            persisted = first.nextState
        )
        val third = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 4 * 60_000L,
                pred5 = 5.0,
                pred30 = 3.9,
                pred60 = 3.3,
                ciLow30 = 3.7,
                ciHigh30 = 4.8
            ),
            persisted = second.nextState
        )
        val fourth = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 6 * 60_000L,
                pred5 = 5.0,
                pred30 = 3.9,
                pred60 = 3.3,
                ciLow30 = 3.7,
                ciHigh30 = 4.8
            ),
            persisted = third.nextState
        )

        assertThat(second.notifyKind).isEqualTo(GlucoseAlertNotifyKind.WARNING_30)
        assertThat(third.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(fourth.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
    }

    @Test
    fun warning30StageRemainsActiveWithoutIntervalRepeat() {
        val settings = testSettings()
        val baseTs = 1_000_000L
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                pred5 = 5.0,
                pred30 = 3.9,
                pred60 = 3.4,
                ciLow30 = 3.7,
                ciHigh30 = 4.8,
                trendDelta5Mmol = -0.08
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 60_000L,
                pred5 = 5.0,
                pred30 = 3.8,
                pred60 = 3.3,
                ciLow30 = 3.6,
                ciHigh30 = 4.7,
                trendDelta5Mmol = -0.05
            ),
            persisted = first.nextState
        )
        val repeat = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 6 * 60_000L,
                pred5 = 5.0,
                pred30 = 3.8,
                pred60 = 3.3,
                ciLow30 = 3.6,
                ciHigh30 = 4.7,
                trendDelta5Mmol = 0.16
            ),
            persisted = second.nextState
        )

        assertThat(second.notifyKind).isEqualTo(GlucoseAlertNotifyKind.WARNING_30)
        assertThat(repeat.state).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(repeat.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(repeat.repeatSuppressedByTrend).isFalse()
    }

    @Test
    fun mutedWindowPreservesInternalRiskStateAndDoesNotCreateIntervalRepeat() {
        val settings = testSettings()
        val baseTs = 1_000_000L
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                pred5 = 5.0,
                pred30 = 3.9,
                pred60 = 3.4,
                ciLow30 = 3.7,
                ciHigh30 = 4.8,
                trendDelta5Mmol = -0.08
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val active = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 60_000L,
                pred5 = 5.0,
                pred30 = 3.8,
                pred60 = 3.3,
                ciLow30 = 3.6,
                ciHigh30 = 4.7,
                trendDelta5Mmol = -0.05
            ),
            persisted = first.nextState
        )
        val mutedUntil = baseTs + 31 * 60_000L
        val muted = active.nextState.copy(mutedUntilTs = mutedUntil)

        val suppressed = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 10 * 60_000L,
                pred5 = 5.0,
                pred30 = 3.8,
                pred60 = 3.3,
                ciLow30 = 3.6,
                ciHigh30 = 4.7,
                trendDelta5Mmol = -0.05
            ),
            persisted = muted
        )
        val afterExpiry = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 32 * 60_000L,
                pred5 = 5.0,
                pred30 = 3.8,
                pred60 = 3.3,
                ciLow30 = 3.6,
                ciHigh30 = 4.7,
                trendDelta5Mmol = -0.05
            ),
            persisted = suppressed.nextState
        )

        assertThat(active.notifyKind).isEqualTo(GlucoseAlertNotifyKind.WARNING_30)
        assertThat(suppressed.state).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(suppressed.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(suppressed.nextState.mutedUntilTs).isEqualTo(mutedUntil)
        assertThat(suppressed.disableReason).isEqualTo("glucose_alert_muted")
        assertThat(afterExpiry.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(afterExpiry.nextState.mutedUntilTs).isEqualTo(0L)
    }

    @Test
    fun critical5TriggersImmediatelyWithoutIntervalRepeat() {
        val settings = testSettings()
        val baseTs = 1_000_000L
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                currentGlucoseMmol = 5.6,
                currentAgeMinutes = 1L,
                pred5 = 4.1,
                pred30 = 3.4,
                pred60 = 3.2,
                ciLow30 = 3.3,
                ciHigh30 = 4.9
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 60_000L,
                currentGlucoseMmol = 5.5,
                currentAgeMinutes = 1L,
                pred5 = 4.0,
                pred30 = 3.3,
                pred60 = 3.1,
                ciLow30 = 3.2,
                ciHigh30 = 4.8
            ),
            persisted = first.nextState
        )
        val third = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs + 100_000L,
                currentGlucoseMmol = 5.5,
                currentAgeMinutes = 1L,
                pred5 = 4.0,
                pred30 = 3.3,
                pred60 = 3.1,
                ciLow30 = 3.2,
                ciHigh30 = 4.8
            ),
            persisted = second.nextState
        )

        assertThat(first.notifyKind).isEqualTo(GlucoseAlertNotifyKind.CRITICAL_5)
        assertThat(first.strongAlertSequence).isEqualTo(1)
        assertThat(second.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(third.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(third.strongAlertSequence).isEqualTo(1)
    }

    @Test
    fun engineKeepsCriticalRiskSignalDuringMuteForCoordinatorToSuppress() {
        val settings = testSettings()
        val now = 1_000_000L
        val critical = engine.evaluate(
            input(settings, now, currentGlucoseMmol = 5.6, currentAgeMinutes = 1L, pred5 = 4.1, pred30 = 3.4, pred60 = 3.2, ciLow30 = 3.3, ciHigh30 = 4.9),
            GlucoseAlertRuntimeState(mutedUntilTs = now + 60_000L)
        )
        val noncritical = engine.evaluate(
            input(settings, now, pred5 = 5.0, pred30 = 3.9, pred60 = 3.4, ciLow30 = 3.7, ciHigh30 = 4.8),
            GlucoseAlertRuntimeState(mutedUntilTs = now + 60_000L)
        )

        assertThat(critical.notifyKind).isEqualTo(GlucoseAlertNotifyKind.CRITICAL_5)
        assertThat(noncritical.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
    }

    @Test
    fun lowNowTriggersImmediatelyAndClearsAfterFifteenSafeMinutes() {
        val settings = testSettings()
        val active = engine.evaluate(
            input = input(settings = settings, nowTs = 0L, currentGlucoseMmol = 3.8, currentAgeMinutes = 1L),
            persisted = GlucoseAlertRuntimeState()
        )
        val stillLow = engine.evaluate(
            input = input(settings = settings, nowTs = 60_000L, currentGlucoseMmol = 4.1, currentAgeMinutes = 1L),
            persisted = active.nextState
        )
        val recovered = engine.evaluate(
            input = input(settings = settings, nowTs = 16 * 60_000L, currentGlucoseMmol = 4.3, currentAgeMinutes = 1L),
            persisted = stillLow.nextState
        )

        assertThat(active.notifyKind).isEqualTo(GlucoseAlertNotifyKind.LOW_NOW)
        assertThat(stillLow.state).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(recovered.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(recovered.strongAlertSequence).isEqualTo(0)
    }

    @Test
    fun freshCurrentBelowFourUsesSafetyThresholdEvenWhenConfiguredThresholdIsLower() {
        val settings = testSettings().copy(urgentLowMmol = 3.5)

        val decision = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 1_000_000L,
                currentGlucoseMmol = 3.9,
                currentAgeMinutes = 1L
            ),
            persisted = GlucoseAlertRuntimeState()
        )

        assertThat(decision.state).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(decision.notifyKind).isEqualTo(GlucoseAlertNotifyKind.LOW_NOW)
        assertThat(decision.urgentLowThreshold).isEqualTo(4.0)
    }

    @Test
    fun freshCurrentBelowFourRemainsAnInternalRiskSignalDuringMute() {
        val nowTs = 1_000_000L

        val decision = engine.evaluate(
            input = input(
                nowTs = nowTs,
                currentGlucoseMmol = 3.8,
                currentAgeMinutes = 1L
            ),
            persisted = GlucoseAlertRuntimeState(mutedUntilTs = nowTs + 30L * 60_000L)
        )

        assertThat(decision.state).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(decision.notifyKind).isEqualTo(GlucoseAlertNotifyKind.LOW_NOW)
        assertThat(decision.nextState.mutedUntilTs).isEqualTo(nowTs + 30L * 60_000L)
        assertThat(decision.disableReason).isEqualTo("glucose_alert_muted")
    }

    @Test
    fun confidenceBandAloneCannotCreateImmediateCriticalFiveAlert() {
        val settings = testSettings().copy(softAlertUseConfidenceBand = true)
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 0L,
                currentGlucoseMmol = 4.5,
                currentAgeMinutes = 1L,
                pred5 = null,
                pred30 = 5.2,
                pred60 = 5.4,
                ciLow30 = 3.0,
                ciHigh30 = 6.0,
                trendDelta5Mmol = -0.12
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 60_000L,
                currentGlucoseMmol = 4.6,
                currentAgeMinutes = 1L,
                pred5 = null,
                pred30 = 5.1,
                pred60 = 5.3,
                ciLow30 = 3.0,
                ciHigh30 = 5.9,
                trendDelta5Mmol = -0.10
            ),
            persisted = first.nextState
        )

        assertThat(first.notifyKind).isNotEqualTo(GlucoseAlertNotifyKind.CRITICAL_5)
        assertThat(second.state).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(second.notifyKind).isEqualTo(GlucoseAlertNotifyKind.WARNING_30)
    }

    @Test
    fun stale_disablesPredictiveAlertsButNotFreshLowNow() {
        val settings = testSettings()
        val baseTs = 1_000_000L
        val soft = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                pred5 = 4.3,
                pred30 = 4.5,
                pred60 = 3.7,
                ciLow30 = 4.1,
                ciHigh30 = 5.2,
                staleData = true
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val strong = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = baseTs,
                currentGlucoseMmol = 3.8,
                currentAgeMinutes = 1L,
                staleData = true
            ),
            persisted = GlucoseAlertRuntimeState()
        )

        assertThat(soft.disableReason).isEqualTo("stale_data")
        assertThat(soft.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(strong.state).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(strong.notifyKind).isEqualTo(GlucoseAlertNotifyKind.LOW_NOW)
    }

    @Test
    fun downgradeRequiresFifteenSafeMinutes() {
        val settings = testSettings()
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 0L,
                pred5 = 5.0,
                pred30 = 3.9,
                pred60 = 3.2,
                ciLow30 = 3.7,
                ciHigh30 = 4.8
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val active = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 60_000L,
                pred5 = 5.0,
                pred30 = 3.8,
                pred60 = 3.1,
                ciLow30 = 3.6,
                ciHigh30 = 4.7
            ),
            persisted = first.nextState
        )
        val safeFirst = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 120_000L,
                pred5 = 6.0,
                pred30 = 6.3,
                pred60 = 6.6,
                ciLow30 = 5.9,
                ciHigh30 = 6.7
            ),
            persisted = active.nextState
        )
        val safeSecond = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 120_000L + 15 * 60_000L,
                pred5 = 6.0,
                pred30 = 6.3,
                pred60 = 6.6,
                ciLow30 = 5.9,
                ciHigh30 = 6.7
            ),
            persisted = safeFirst.nextState
        )

        assertThat(active.state).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(safeFirst.state).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(safeFirst.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(safeFirst.episodeStage).isEqualTo(GlucoseAlertState.NONE)
        assertThat(safeFirst.episodeDirection).isNull()
        assertThat(safeSecond.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(safeSecond.notifyKind).isEqualTo(GlucoseAlertNotifyKind.CLEAR)
    }

    @Test
    fun forecastOnlyHighCandidateDoesNotFlipLowEpisodeBeforeResolvedDowngrade() {
        val settings = testSettings()
        val safeStartTs = 1_000_000L
        val activeLow = GlucoseAlertRuntimeState(
            activeAlertState = GlucoseAlertState.LOW_NOW,
            activeDirection = GlucoseAlertDirection.LOW,
            lastStageChangeTs = safeStartTs - 60_000L
        )
        fun highInput(nowTs: Long) = input(
            settings = settings,
            nowTs = nowTs,
            currentGlucoseMmol = 7.0,
            currentAgeMinutes = 1L,
            pred5 = 16.0,
            pred30 = 18.0,
            pred60 = 20.0,
            ciLow30 = 16.0,
            ciHigh30 = 20.0
        )

        val pending = engine.evaluate(highInput(safeStartTs), activeLow)
        val confirmedBeforeHysteresis = engine.evaluate(
            highInput(safeStartTs + 60_000L),
            pending.nextState
        )
        val pendingAtHysteresis = engine.evaluate(
            highInput(safeStartTs + 15 * 60_000L),
            confirmedBeforeHysteresis.nextState
        )
        val committed = engine.evaluate(
            highInput(safeStartTs + 16 * 60_000L),
            pendingAtHysteresis.nextState
        )

        assertThat(confirmedBeforeHysteresis.state).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(confirmedBeforeHysteresis.direction).isEqualTo(GlucoseAlertDirection.LOW)
        assertThat(confirmedBeforeHysteresis.nextState.activeAlertState)
            .isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(confirmedBeforeHysteresis.episodeStage).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(confirmedBeforeHysteresis.episodeDirection).isEqualTo(GlucoseAlertDirection.LOW)
        assertThat(committed.state).isEqualTo(GlucoseAlertState.SOFT_HIGH_RISK)
        assertThat(committed.direction).isEqualTo(GlucoseAlertDirection.HIGH)
        assertThat(committed.episodeStage).isEqualTo(GlucoseAlertState.SOFT_HIGH_RISK)
        assertThat(committed.episodeDirection).isEqualTo(GlucoseAlertDirection.HIGH)
    }

    @Test
    fun corroboratedCurrentHighReplacesOldLowOnceWithoutFifteenMinuteDelay() {
        val settings = testSettings()
        val start = 1_000_000L
        var state = GlucoseAlertRuntimeState(
            activeAlertState = GlucoseAlertState.LOW_NOW,
            activeDirection = GlucoseAlertDirection.LOW,
            lastStageChangeTs = start - 60_000L
        )
        val stages = (0..6).map { minute ->
            val decision = engine.evaluate(input(
                settings = settings,
                nowTs = start + minute * 60_000L,
                currentGlucoseMmol = 15.0,
                currentAgeMinutes = 1L,
                pred5 = 16.0,
                pred30 = 18.0,
                pred60 = 20.0,
                ciLow30 = 16.0,
                ciHigh30 = 20.0
            ), state)
            state = decision.nextState
            assertThat(decision.episodeStage).isEqualTo(decision.state)
            assertThat(decision.episodeDirection).isEqualTo(decision.direction)
            decision.state
        }
        assertThat(stages.first()).isEqualTo(GlucoseAlertState.LOW_NOW)
        assertThat(stages.drop(1)).containsExactlyElementsIn(List(6) { GlucoseAlertState.SOFT_HIGH_RISK })
    }

    @Test
    fun uncertainHighForecastDoesNotExpediteOldLowResolution() {
        val start = 1_000_000L
        val low = GlucoseAlertRuntimeState(activeAlertState = GlucoseAlertState.LOW_NOW, activeDirection = GlucoseAlertDirection.LOW)
        listOf<Double?>(null, 7.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { lowerCi ->
            val highInput = input(
                settings = testSettings(), nowTs = start, currentGlucoseMmol = 15.0,
                currentAgeMinutes = 1L, pred5 = 16.0, pred30 = 18.0, pred60 = 20.0,
                ciLow30 = lowerCi, ciHigh30 = 20.0
            )
            val pending = engine.evaluate(highInput, low)
            val second = engine.evaluate(highInput.copy(nowTs = start + 60_000L), pending.nextState)
            assertThat(second.state).isEqualTo(GlucoseAlertState.LOW_NOW)
        }
    }

    @Test
    fun corroboratedHighReversalRequiresKnownFreshNonnegativeSampleAge() {
        listOf<Long?>(null, -1L, 0L, 5L, 6L).forEach { age ->
            val decision = confirmedHighAfterLow(corroboratedHighInput().copy(currentGlucoseAgeMinutes = age))
            val expected = if (age == 0L || age == 5L) GlucoseAlertState.SOFT_HIGH_RISK else GlucoseAlertState.LOW_NOW
            assertThat(decision.state).isEqualTo(expected)
            assertThat(decision.episodeStage).isEqualTo(decision.state)
            assertThat(decision.episodeDirection).isEqualTo(decision.direction)
        }
    }

    @Test
    fun malformedCentralCurrentOrUpperBoundCannotExpediteHighReversal() {
        val high = corroboratedHighInput()
        val malformed = listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .flatMap { value ->
                listOf(
                    high.copy(currentGlucoseMmol = value),
                    high.copy(pred30 = value)
                )
            } + listOf(
                high.copy(ciHigh30 = null),
                high.copy(ciHigh30 = Double.POSITIVE_INFINITY),
                high.copy(ciLow30 = 21.0, ciHigh30 = 20.0),
                high.copy(pred30 = 15.0),
                high.copy(pred30 = 21.0)
            )
        malformed.forEach { candidate ->
            val decision = confirmedHighAfterLow(candidate)
            assertThat(decision.state).isEqualTo(GlucoseAlertState.LOW_NOW)
            assertThat(decision.direction).isEqualTo(GlucoseAlertDirection.LOW)
            assertThat(decision.episodeStage).isEqualTo(decision.state)
            assertThat(decision.episodeDirection).isEqualTo(decision.direction)
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY).forEach { upperCi ->
            val decision = confirmedHighAfterLow(high.copy(ciHigh30 = upperCi))
            assertThat(decision.state).isEqualTo(GlucoseAlertState.LOW_NOW)
            assertThat(decision.direction).isEqualTo(GlucoseAlertDirection.LOW)
            // No high candidate: retain the engine state but start Room SAFE_PENDING.
            assertThat(decision.episodeStage).isEqualTo(GlucoseAlertState.NONE)
            assertThat(decision.episodeDirection).isNull()
        }
    }

    @Test
    fun disabledOrUntrustedHighCannotUseFastReversal() {
        val high = corroboratedHighInput()
        listOf(
            high.copy(staleData = true),
            high.copy(sensorBlocked = true),
            high.copy(sensorSuspectFalseLow = true),
            high.copy(settings = high.settings.copy(softHighAlertEnabled = false))
        ).forEach { candidate ->
            val decision = confirmedHighAfterLow(candidate)
            assertThat(decision.state).isEqualTo(GlucoseAlertState.LOW_NOW)
            assertThat(decision.direction).isEqualTo(GlucoseAlertDirection.LOW)
            assertThat(decision.episodeStage).isEqualTo(GlucoseAlertState.NONE)
            assertThat(decision.episodeDirection).isNull()
        }
    }

    @Test
    fun corroboratedHighReversalPreservesThirtyAndSixtyMinuteMuteAuthority() {
        listOf(30L, 60L).forEach { minutes ->
            val high = corroboratedHighInput()
            val mutedUntil = high.nowTs + minutes * 60_000L
            val decision = confirmedHighAfterLow(high, mutedUntil)
            assertThat(decision.state).isEqualTo(GlucoseAlertState.SOFT_HIGH_RISK)
            assertThat(decision.direction).isEqualTo(GlucoseAlertDirection.HIGH)
            assertThat(decision.episodeStage).isEqualTo(decision.state)
            assertThat(decision.episodeDirection).isEqualTo(decision.direction)
            assertThat(decision.nextState.mutedUntilTs).isEqualTo(mutedUntil)
            assertThat(decision.disableReason).isEqualTo("glucose_alert_muted")
        }
    }

    private fun corroboratedHighInput() = input(
        nowTs = 1_000_000L, currentGlucoseMmol = 15.0, currentAgeMinutes = 1L,
        pred5 = 16.0, pred30 = 18.0, pred60 = 20.0, ciLow30 = 16.0, ciHigh30 = 20.0
    )

    private fun confirmedHighAfterLow(high: GlucoseAlertInput, mutedUntilTs: Long = 0L): GlucoseAlertDecision {
        val low = GlucoseAlertRuntimeState(
            activeAlertState = GlucoseAlertState.LOW_NOW,
            activeDirection = GlucoseAlertDirection.LOW,
            mutedUntilTs = mutedUntilTs
        )
        val pending = engine.evaluate(high, low)
        return engine.evaluate(high.copy(nowTs = high.nowTs + 60_000L), pending.nextState)
    }

    @Test
    fun watch60_disabled_suppresses_stage() {
        val settings = testSettings().copy(watch60AlertEnabled = false)
        val first = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 0L,
                pred5 = 5.5,
                pred30 = 4.8,
                pred60 = 4.0,
                ciLow30 = 4.6,
                ciHigh30 = 5.4
            ),
            persisted = GlucoseAlertRuntimeState()
        )
        val second = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 60_000L,
                pred5 = 5.4,
                pred30 = 4.7,
                pred60 = 3.9,
                ciLow30 = 4.5,
                ciHigh30 = 5.3
            ),
            persisted = first.nextState
        )

        assertThat(second.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(second.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(second.disableReason).isEqualTo("watch_60_disabled")
    }

    @Test
    fun critical5_disabled_suppresses_stage() {
        val settings = testSettings().copy(critical5AlertEnabled = false)
        val decision = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 0L,
                currentGlucoseMmol = 5.5,
                currentAgeMinutes = 1L,
                pred5 = 4.1,
                pred30 = 3.4,
                pred60 = 3.2,
                ciLow30 = 3.3,
                ciHigh30 = 4.9
            ),
            persisted = GlucoseAlertRuntimeState()
        )

        assertThat(decision.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(decision.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(decision.disableReason).isEqualTo("critical_5_disabled")
    }

    @Test
    fun lowNow_disabled_suppresses_current_low_alert() {
        val settings = testSettings().copy(lowNowAlertEnabled = false)
        val decision = engine.evaluate(
            input = input(
                settings = settings,
                nowTs = 0L,
                currentGlucoseMmol = 3.8,
                currentAgeMinutes = 1L
            ),
            persisted = GlucoseAlertRuntimeState()
        )

        assertThat(decision.state).isEqualTo(GlucoseAlertState.NONE)
        assertThat(decision.notifyKind).isEqualTo(GlucoseAlertNotifyKind.NONE)
        assertThat(decision.disableReason).isEqualTo("low_now_disabled")
    }

    private fun input(
        settings: AppSettings = testSettings(),
        nowTs: Long,
        currentGlucoseMmol: Double? = 6.0,
        currentAgeMinutes: Long? = 1L,
        pred5: Double? = null,
        pred30: Double? = null,
        pred60: Double? = null,
        ciLow30: Double? = null,
        ciHigh30: Double? = null,
        trendDelta5Mmol: Double? = null,
        staleData: Boolean = false,
        sensorBlocked: Boolean = false,
        sensorSuspectFalseLow: Boolean = false
    ) = GlucoseAlertInput(
        nowTs = nowTs,
        settings = settings,
        currentGlucoseMmol = currentGlucoseMmol,
        currentGlucoseAgeMinutes = currentAgeMinutes,
        pred5 = pred5,
        pred30 = pred30,
        pred60 = pred60,
        ciLow30 = ciLow30,
        ciHigh30 = ciHigh30,
        trendDelta5Mmol = trendDelta5Mmol,
        staleData = staleData,
        sensorBlocked = sensorBlocked,
        sensorSuspectFalseLow = sensorSuspectFalseLow
    )

    private fun testSettings(): AppSettings {
        return AppSettings(
            nightscoutUrl = "",
            apiSecret = "",
            cloudBaseUrl = "",
            killSwitch = false,
            rootExperimentalEnabled = false,
            localBroadcastIngestEnabled = true,
            strictBroadcastSenderValidation = false,
            localNightscoutEnabled = true,
            localNightscoutPort = 17582,
            localCommandFallbackEnabled = true,
            localCommandPackage = "info.nightscout.androidaps",
            localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
            insulinProfileId = InsulinActionProfileId.FIASP.name,
            enableUamInference = true,
            enableUamBoost = true,
            enableUamExportToAaps = false,
            uamExportMode = UamExportMode.OFF,
            dryRunExport = true,
            baseTargetMmol = 5.5,
            postHypoThresholdMmol = 3.0,
            postHypoDeltaThresholdMmol5m = 0.2,
            postHypoTargetMmol = 4.4,
            postHypoDurationMinutes = 60,
            postHypoLookbackMinutes = 90,
            rulePostHypoEnabled = true,
            rulePatternEnabled = true,
            ruleSegmentEnabled = true,
            adaptiveControllerEnabled = true,
            rulePostHypoPriority = 100,
            rulePatternPriority = 50,
            ruleSegmentPriority = 40,
            adaptiveControllerPriority = 120,
            rulePostHypoCooldownMinutes = 30,
            rulePatternCooldownMinutes = 30,
            ruleSegmentCooldownMinutes = 30,
            adaptiveControllerRetargetMinutes = 5,
            adaptiveControllerSafetyProfile = "BALANCED",
            adaptiveControllerStaleMaxMinutes = 15,
            adaptiveControllerMaxActions6h = 4,
            adaptiveControllerMaxStepMmol = 0.25,
            patternMinSamplesPerWindow = 40,
            patternMinActiveDaysPerWindow = 7,
            patternLowRateTrigger = 0.12,
            patternHighRateTrigger = 0.18,
            analyticsLookbackDays = 365,
            circadianPatternsEnabled = true,
            circadianStableLookbackDays = 14,
            circadianRecencyLookbackDays = 5,
            circadianUseWeekendSplit = true,
            circadianUseReplayResidualBias = true,
            circadianForecastWeight30 = 0.25,
            circadianForecastWeight60 = 0.35,
            softAlertEnabled = true,
            watch60AlertEnabled = true,
            warning30AlertEnabled = true,
            softHighAlertEnabled = true,
            critical5AlertEnabled = true,
            lowNowAlertEnabled = true,
            softAlertLowMmol = 4.4,
            softAlertHighMmol = 10.0,
            urgentLowMmol = 3.9,
            softAlertRepeatMinutes = 5,
            strongLowRepeatMinutes = 2,
            softAlertUseConfidenceBand = true,
            maxActionsIn6Hours = 3,
            staleDataMaxMinutes = 10,
            exportFolderUri = null
        )
    }
}
