package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UamExportPolicyTest {

    @Test
    fun minimumSendQuantumIsOneDecigram() {
        assertThat(UamExportPolicy.MIN_SEND_QUANTUM_G).isEqualTo(0.1)
    }

    @Test
    fun blocksUntilActiveForTenMinutesAndAllowsExactBoundary() {
        assertBlocked(
            defaultInput(activeSinceTs = NOW_TS - 9 * MINUTE_MS - 59_000L),
            "active_under_10m"
        )

        assertSend(
            defaultInput(activeSinceTs = NOW_TS - 10 * MINUTE_MS),
            grams = 15.0,
            seq = 1
        )
    }

    @Test
    fun secondFifteenGramWriteIsAllowedAtExactTenMinuteBoundary() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(entry(minutesAgo = 10, grams = 15.0, seq = 1))
            ),
            grams = 15.0,
            seq = 2
        )
    }

    @Test
    fun explicitAutoExportCapLimitsAConfirmedUamIncrement() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                maximumIncrementGrams = 10.0
            ),
            grams = 10.0,
            seq = 1
        )
    }

    @Test
    fun rejectsAutoExportCapOutsideTheSafeRange() {
        assertBlocked(
            defaultInput(maximumIncrementGrams = 0.09),
            "invalid_maximum_increment"
        )
        assertBlocked(
            defaultInput(maximumIncrementGrams = 15.01),
            "invalid_maximum_increment"
        )
    }

    @Test
    fun blocksExportWhenCurrentGlucoseIsBelowFour() {
        assertBlocked(defaultInput(currentGlucoseMmol = 3.99), "current_glucose_below_4")
        assertSend(defaultInput(currentGlucoseMmol = 4.0), grams = 15.0, seq = 1)
    }

    @Test
    fun blocksExportWhenForecastMinimumIsBelowFour() {
        assertBlocked(defaultInput(forecastMinimumMmol = 3.99), "forecast_below_4")
        assertSend(defaultInput(forecastMinimumMmol = 4.0), grams = 15.0, seq = 1)
    }

    @Test
    fun globalRollingCapacityIsExhaustedAfterThirtyGrams() {
        assertBlocked(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(minutesAgo = 20, grams = 15.0, seq = 1, episodeId = "episode-1"),
                    entry(minutesAgo = 10, grams = 15.0, seq = 2, episodeId = "episode-1")
                )
            ),
            "global_rolling_30m_capacity_exhausted"
        )
    }

    @Test
    fun requiresActiveTimestampAndRejectsFutureActiveTimestamp() {
        assertBlocked(defaultInput(activeSinceTs = null), "active_time_missing")
        assertBlocked(defaultInput(activeSinceTs = -1L), "invalid_active_timestamp")
        assertBlocked(defaultInput(activeSinceTs = NOW_TS + 1L), "active_time_in_future")
    }

    @Test
    fun initialSendRequiresConfidenceAtLeastPointFiveFive() {
        assertBlocked(defaultInput(confidence = 0.549999), "confidence_below_initial_min")
        assertSend(defaultInput(confidence = 0.55), grams = 15.0, seq = 1)
    }

    @Test
    fun deliveredEpisodeKeepsPointFiveFiveConfidenceFloor() {
        val ledger = listOf(entry(minutesAgo = 10, grams = 5.0, seq = 1))

        assertBlocked(
            defaultInput(
                confidence = 0.549999,
                supportedLowerBoundGrams = 15.0,
                remoteLedger = ledger
            ),
            "confidence_below_continuation_min"
        )
        assertSend(
            defaultInput(
                confidence = 0.55,
                supportedLowerBoundGrams = 15.0,
                remoteLedger = ledger
            ),
            grams = 10.0,
            seq = 2
        )
    }

    @Test
    fun minimumWriteIntervalIsInclusiveAtTenMinutes() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(entry(tsMs = NOW_TS - 9 * MINUTE_MS - 59_000L, grams = 5.0, seq = 1))
            ),
            "write_interval_under_10m"
        )

        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(entry(minutesAgo = 10, grams = 15.0, seq = 1))
            ),
            grams = 15.0,
            seq = 2
        )
    }

    @Test
    fun blocksWhenRollingThirtyMinuteCapacityIsExhausted() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 50.0,
                remoteLedger = listOf(
                    entry(minutesAgo = 20, grams = 15.0, seq = 1),
                    entry(minutesAgo = 10, grams = 15.0, seq = 2)
                )
            ),
            "rolling_30m_capacity_exhausted"
        )
    }

    @Test
    fun rollingWindowExcludesEntryExactlyThirtyMinutesOld() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 45.0,
                remoteLedger = listOf(entry(minutesAgo = 30, grams = 15.0, seq = 1))
            ),
            grams = 15.0,
            seq = 2
        )
    }

    @Test
    fun rollingWindowIncludesTwentyNineMinutesFiftyNinePointNineNineNineSeconds() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 45.0,
                remoteLedger = listOf(
                    entry(tsMs = NOW_TS - 30 * MINUTE_MS + 1L, grams = 15.0, seq = 1),
                    entry(minutesAgo = 10, grams = 15.0, seq = 2)
                )
            ),
            "rolling_30m_capacity_exhausted"
        )

        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 45.0,
                remoteLedger = listOf(
                    entry(minutesAgo = 30, grams = 15.0, seq = 1),
                    entry(minutesAgo = 10, grams = 15.0, seq = 2)
                )
            ),
            grams = 15.0,
            seq = 3
        )
    }

    @Test
    fun rollingTotalIsOrderIndependentAtDecigramBoundary() {
        val ledger = listOf(
            entry(minutesAgo = 20, grams = 0.1, seq = 1),
            entry(minutesAgo = 15, grams = 0.2, seq = 2),
            entry(minutesAgo = 10, grams = 29.699999999999996, seq = 3)
        )

        permutations(ledger).forEach { permutation ->
            assertBlocked(
                defaultInput(supportedLowerBoundGrams = 50.0, remoteLedger = permutation),
                "rolling_30m_capacity_exhausted"
            )
        }
    }

    @Test
    fun rollingLedgerConsumptionAroundThirtyGramsRoundsUpConservatively() {
        listOf(Math.nextDown(30.0), 30.0, Math.nextUp(30.0)).forEach { grams ->
            assertBlocked(
                defaultInput(
                    supportedLowerBoundGrams = 50.0,
                    remoteLedger = listOf(entry(minutesAgo = 10, grams = grams, seq = 1))
                ),
                "rolling_30m_capacity_exhausted"
            )
        }
    }

    @Test
    fun thirtyOneMinuteAgeDoesNotResetEpisodeCapacity() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(
                    entry(minutesAgo = 41, grams = 25.0, seq = 1),
                    entry(minutesAgo = 31, grams = 25.0, seq = 2)
                )
            ),
            "episode_capacity_exhausted"
        )
    }

    @Test
    fun finalEpisodeSendIsLimitedToOneDecigramAfterFortyNinePointNineDelivered() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 60.0,
                remoteLedger = listOf(entry(minutesAgo = 31, grams = 49.9, seq = 1))
            ),
            grams = 0.1,
            seq = 2
        )
    }

    @Test
    fun neverSendsPastFiftyGramEpisodeCap() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 60.0,
                remoteLedger = listOf(entry(minutesAgo = 31, grams = 50.0, seq = 1))
            ),
            "episode_capacity_exhausted"
        )
    }

    @Test
    fun episodeWindowStartsAtFirstDeliveredEntry() {
        assertSend(
            defaultInput(
                activeSinceTs = NOW_TS - 3 * HOUR_MS,
                supportedLowerBoundGrams = 15.0,
                remoteLedger = listOf(entry(minutesAgo = 59, grams = 5.0, seq = 1))
            ),
            grams = 10.0,
            seq = 2
        )
    }

    @Test
    fun activeEpisodeWithoutLedgerDoesNotExpireFromDetectionAge() {
        assertSend(
            defaultInput(activeSinceTs = NOW_TS - 3 * HOUR_MS),
            grams = 15.0,
            seq = 1
        )
    }

    @Test
    fun firstDeliveredEntryExactlySixtyMinutesOldClosesEpisode() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(entry(minutesAgo = 60, grams = 15.0, seq = 1))
            ),
            "episode_window_closed"
        )
    }

    @Test
    fun episodeWindowIsOpenAtFiftyNineMinutesFiftyNinePointNineNineNineSeconds() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(
                    entry(tsMs = NOW_TS - 60 * MINUTE_MS + 1L, grams = 5.0, seq = 1)
                )
            ),
            grams = 15.0,
            seq = 2
        )

        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(entry(minutesAgo = 60, grams = 15.0, seq = 1))
            ),
            "episode_window_closed"
        )
    }

    @Test
    fun episodeTotalIsOrderIndependentAtDecigramBoundary() {
        val ledger = listOf(
            entry(minutesAgo = 50, grams = 0.1, seq = 1),
            entry(minutesAgo = 35, grams = 0.2, seq = 2),
            entry(minutesAgo = 10, grams = 49.699999999999996, seq = 3)
        )

        permutations(ledger).forEach { permutation ->
            assertBlocked(
                defaultInput(supportedLowerBoundGrams = 60.0, remoteLedger = permutation),
                "episode_capacity_exhausted"
            )
        }
    }

    @Test
    fun episodeLedgerConsumptionAroundFiftyGramsRoundsUpConservatively() {
        listOf(Math.nextDown(50.0), 50.0, Math.nextUp(50.0)).forEach { grams ->
            assertBlocked(
                defaultInput(
                    supportedLowerBoundGrams = 60.0,
                    remoteLedger = listOf(entry(minutesAgo = 31, grams = grams, seq = 1))
                ),
                "episode_capacity_exhausted"
            )
        }
    }

    @Test
    fun multiWriteSequenceReachesFiftyGramsWithoutViolatingThirtyMinuteCap() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 50.0,
                remoteLedger = listOf(entry(minutesAgo = 10, grams = 15.0, seq = 1))
            ),
            grams = 15.0,
            seq = 2
        )
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 50.0,
                remoteLedger = listOf(
                    entry(minutesAgo = 20, grams = 15.0, seq = 1),
                    entry(minutesAgo = 10, grams = 15.0, seq = 2)
                )
            ),
            "rolling_30m_capacity_exhausted"
        )
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 50.0,
                remoteLedger = listOf(
                    entry(minutesAgo = 30, grams = 15.0, seq = 1),
                    entry(minutesAgo = 20, grams = 15.0, seq = 2)
                )
            ),
            grams = 15.0,
            seq = 3
        )
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 50.0,
                remoteLedger = listOf(
                    entry(minutesAgo = 40, grams = 15.0, seq = 1),
                    entry(minutesAgo = 30, grams = 15.0, seq = 2),
                    entry(minutesAgo = 10, grams = 15.0, seq = 3)
                )
            ),
            grams = 5.0,
            seq = 4
        )
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 50.1,
                remoteLedger = listOf(
                    entry(minutesAgo = 50, grams = 15.0, seq = 1),
                    entry(minutesAgo = 40, grams = 15.0, seq = 2),
                    entry(minutesAgo = 20, grams = 15.0, seq = 3),
                    entry(minutesAgo = 10, grams = 5.0, seq = 4)
                )
            ),
            "episode_capacity_exhausted"
        )
    }

    @Test
    fun lowerBoundRequiresTwoStableBuckets() {
        assertBlocked(defaultInput(lowerBoundStableBuckets = 1), "lower_bound_not_stable")
        assertSend(defaultInput(lowerBoundStableBuckets = 2), grams = 15.0, seq = 1)
    }

    @Test
    fun sensorTrustBoundaryIsInclusive() {
        assertBlocked(defaultInput(sensorTrust = 0.699999), "sensor_trust_below_min")
        assertSend(defaultInput(sensorTrust = 0.70), grams = 15.0, seq = 1)
    }

    @Test
    fun blockedSensorFailsClosed() {
        assertBlocked(defaultInput(sensorBlocked = true), "sensor_blocked")
    }

    @Test
    fun signedResidualMustBeStrictlyPositive() {
        assertBlocked(defaultInput(signedResidualMmol5 = 0.0), "signed_residual_not_positive")
        assertSend(defaultInput(signedResidualMmol5 = Double.MIN_VALUE), grams = 15.0, seq = 1)
    }

    @Test
    fun shortAverageMustBeStrictlyPositive() {
        assertBlocked(defaultInput(shortAverageDeltaMmol5 = 0.0), "short_average_not_positive")
        assertSend(defaultInput(shortAverageDeltaMmol5 = Double.MIN_VALUE), grams = 15.0, seq = 1)
    }

    @Test
    fun positiveFiniteEffectiveCobDoesNotBlockExport() {
        assertSend(defaultInput(effectiveCobGrams = 5.000001), grams = 15.0, seq = 1)
        assertSend(defaultInput(effectiveCobGrams = 120.0), grams = 15.0, seq = 1)
    }

    @Test
    fun negativeEffectiveCobFailsClosed() {
        assertBlocked(defaultInput(effectiveCobGrams = -0.001), "invalid_effective_cob")
    }

    @Test
    fun therapyCoverageBoundaryIsInclusive() {
        assertBlocked(defaultInput(therapyCoverage = 0.699999), "therapy_coverage_below_min")
        assertSend(defaultInput(therapyCoverage = 0.70), grams = 15.0, seq = 1)
    }

    @Test
    fun supportedLowerBoundMustBePresentFiniteAndPositive() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { value ->
                assertBlocked(
                    defaultInput(supportedLowerBoundGrams = value),
                    "invalid_supported_lower_bound"
                )
            }
    }

    @Test
    fun nonFiniteSignalAndQualityValuesFailClosedWithStableReasons() {
        val cases = listOf(
            defaultInput(confidence = Double.NaN) to "invalid_confidence",
            defaultInput(confidence = Double.POSITIVE_INFINITY) to "invalid_confidence",
            defaultInput(sensorTrust = Double.NaN) to "invalid_sensor_trust",
            defaultInput(sensorTrust = Double.NEGATIVE_INFINITY) to "invalid_sensor_trust",
            defaultInput(signedResidualMmol5 = Double.NaN) to "invalid_signed_residual",
            defaultInput(signedResidualMmol5 = Double.POSITIVE_INFINITY) to "invalid_signed_residual",
            defaultInput(shortAverageDeltaMmol5 = Double.NaN) to "invalid_short_average",
            defaultInput(shortAverageDeltaMmol5 = Double.NEGATIVE_INFINITY) to "invalid_short_average",
            defaultInput(effectiveCobGrams = Double.NaN) to "invalid_effective_cob",
            defaultInput(effectiveCobGrams = Double.POSITIVE_INFINITY) to "invalid_effective_cob",
            defaultInput(therapyCoverage = Double.NaN) to "invalid_therapy_coverage",
            defaultInput(therapyCoverage = Double.NEGATIVE_INFINITY) to "invalid_therapy_coverage"
        )

        cases.forEach { (input, reason) -> assertBlocked(input, reason) }
    }

    @Test
    fun normalizedMetricsOutsideUnitIntervalFailClosed() {
        val cases = listOf(
            defaultInput(confidence = Math.nextDown(0.0)) to "invalid_confidence",
            defaultInput(confidence = Math.nextUp(1.0)) to "invalid_confidence",
            defaultInput(sensorTrust = Math.nextDown(0.0)) to "invalid_sensor_trust",
            defaultInput(sensorTrust = Math.nextUp(1.0)) to "invalid_sensor_trust",
            defaultInput(therapyCoverage = Math.nextDown(0.0)) to "invalid_therapy_coverage",
            defaultInput(therapyCoverage = Math.nextUp(1.0)) to "invalid_therapy_coverage"
        )

        cases.forEach { (input, reason) -> assertBlocked(input, reason) }
    }

    @Test
    fun normalizedMetricEndpointsRemainValid() {
        assertBlocked(defaultInput(confidence = 0.0), "confidence_below_initial_min")
        assertSend(defaultInput(confidence = 1.0), grams = 15.0, seq = 1)
        assertBlocked(defaultInput(sensorTrust = 0.0), "sensor_trust_below_min")
        assertSend(defaultInput(sensorTrust = 1.0), grams = 15.0, seq = 1)
        assertBlocked(defaultInput(therapyCoverage = 0.0), "therapy_coverage_below_min")
        assertSend(defaultInput(therapyCoverage = 1.0), grams = 15.0, seq = 1)
    }

    @Test
    fun futureLedgerEntryFailsClosed() {
        assertBlocked(
            defaultInput(remoteLedger = listOf(entry(tsMs = NOW_TS + 1L, grams = 15.0, seq = 1))),
            "ledger_entry_in_future"
        )
    }

    @Test
    fun structuralLedgerInvalidityOutranksFutureRegardlessOfOrder() {
        val future = entry(tsMs = NOW_TS + 1L, grams = 15.0, seq = 1)
        val invalid = entry(minutesAgo = 10, grams = 0.0, seq = 2)
        listOf(listOf(future, invalid), listOf(invalid, future)).forEach { ledger ->
            assertBlocked(defaultInput(remoteLedger = ledger), "invalid_ledger")
        }

        val duplicatePast = entry(minutesAgo = 10, grams = 15.0, seq = 1)
        listOf(listOf(future, duplicatePast), listOf(duplicatePast, future)).forEach { ledger ->
            assertBlocked(defaultInput(remoteLedger = ledger), "invalid_ledger")
        }
    }

    @Test
    fun malformedLedgerEntriesFailClosed() {
        val malformedLedgers = listOf(
            listOf(entry(minutesAgo = 10, grams = 0.0, seq = 1)),
            listOf(entry(minutesAgo = 10, grams = -1.0, seq = 1)),
            listOf(entry(minutesAgo = 10, grams = Double.NaN, seq = 1)),
            listOf(entry(minutesAgo = 10, grams = Double.POSITIVE_INFINITY, seq = 1)),
            listOf(entry(minutesAgo = 10, grams = 15.0, seq = 0)),
            listOf(entry(minutesAgo = 10, grams = 15.0, seq = -1)),
            listOf(entry(tsMs = -1L, grams = 15.0, seq = 1)),
            listOf(
                entry(minutesAgo = 20, grams = 15.0, seq = 1),
                entry(minutesAgo = 10, grams = 15.0, seq = 1)
            )
        )

        malformedLedgers.forEach { ledger ->
            assertBlocked(defaultInput(remoteLedger = ledger), "invalid_ledger")
        }
    }

    @Test
    fun ledgerTotalOverflowFailsClosed() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = Double.MAX_VALUE,
                remoteLedger = listOf(
                    entry(minutesAgo = 20, grams = Double.MAX_VALUE, seq = 1),
                    entry(minutesAgo = 10, grams = Double.MAX_VALUE, seq = 2)
                )
            ),
            "invalid_ledger"
        )
    }

    @Test
    fun supportedLowerBoundConversionOverflowFailsClosed() {
        assertBlocked(
            defaultInput(supportedLowerBoundGrams = Double.MAX_VALUE),
            "invalid_supported_lower_bound"
        )
    }

    @Test
    fun exhaustedSequenceSpaceFailsClosed() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 30.0,
                remoteLedger = listOf(entry(minutesAgo = 10, grams = 5.0, seq = Int.MAX_VALUE))
            ),
            "ledger_sequence_exhausted"
        )
    }

    @Test
    fun treatmentTimestampIsFlooredToCurrentFiveMinuteBucket() {
        val decision = assertSend(defaultInput(), grams = 15.0, seq = 1)

        assertThat(decision.treatmentTs).isEqualTo((NOW_TS / FIVE_MINUTE_MS) * FIVE_MINUTE_MS)
        assertThat(NOW_TS - decision.treatmentTs).isAtLeast(0L)
        assertThat(NOW_TS - decision.treatmentTs).isLessThan(FIVE_MINUTE_MS)
    }

    @Test
    fun treatmentTimestampAtExactBucketBoundaryIsCurrentTime() {
        val exactBoundary = (NOW_TS / FIVE_MINUTE_MS) * FIVE_MINUTE_MS
        val decision = assertSend(
            defaultInput(
                nowTs = exactBoundary,
                activeSinceTs = exactBoundary - 10 * MINUTE_MS
            ),
            grams = 15.0,
            seq = 1
        )

        assertThat(decision.treatmentTs).isEqualTo(exactBoundary)
    }

    @Test
    fun zeroCurrentTimestampFailsSafelyAtActiveAgeGate() {
        assertBlocked(
            defaultInput(nowTs = 0L, activeSinceTs = 0L),
            "active_under_10m"
        )
    }

    @Test
    fun maximumCurrentTimestampQuantizesWithoutOverflow() {
        val decision = assertSend(
            defaultInput(
                nowTs = Long.MAX_VALUE,
                activeSinceTs = Long.MAX_VALUE - 10 * MINUTE_MS
            ),
            grams = 15.0,
            seq = 1
        )

        val ageMs = Long.MAX_VALUE - decision.treatmentTs
        assertThat(decision.treatmentTs).isAtMost(Long.MAX_VALUE)
        assertThat(ageMs).isAtLeast(0L)
        assertThat(ageMs).isLessThan(FIVE_MINUTE_MS)
    }

    @Test
    fun sendUsesSupportedRemainingCapacity() {
        assertSend(
            defaultInput(supportedLowerBoundGrams = 7.5),
            grams = 7.5,
            seq = 1
        )
    }

    @Test
    fun supportedLowerBoundIsRoundedDownAtOneUlpBoundaries() {
        val cases = listOf(
            Math.nextDown(15.0) to 14.9,
            15.0 to 15.0,
            Math.nextUp(15.0) to 15.0
        )

        cases.forEach { (supported, expectedSend) ->
            val decision = assertSend(
                defaultInput(supportedLowerBoundGrams = supported),
                grams = expectedSend,
                seq = 1
            )
            assertThat(decision.grams).isAtMost(supported)
        }
    }

    @Test
    fun supportedRemainingIsOrderIndependentAndNeverSubQuantum() {
        val ledger = listOf(
            entry(minutesAgo = 40, grams = 0.1, seq = 1),
            entry(minutesAgo = 10, grams = 0.2, seq = 2)
        )

        permutations(ledger).forEach { permutation ->
            assertBlocked(
                defaultInput(
                    supportedLowerBoundGrams = Math.nextDown(0.4),
                    remoteLedger = permutation
                ),
                "supported_lower_bound_fulfilled"
            )
            assertSend(
                defaultInput(supportedLowerBoundGrams = 0.4, remoteLedger = permutation),
                grams = 0.1,
                seq = 3
            )
        }
    }

    @Test
    fun sendUsesMaximumIncrementWhenEpisodeHasCapacity() {
        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 60.0,
                remoteLedger = listOf(entry(minutesAgo = 31, grams = 35.0, seq = 1))
            ),
            grams = 15.0,
            seq = 2
        )
    }

    @Test
    fun sendQuantizationNeverExceedsAnyRawRemainingCapacity() {
        val supported = Math.nextDown(15.0)
        val supportedDecision = assertSend(
            defaultInput(supportedLowerBoundGrams = supported),
            grams = 14.9,
            seq = 1
        )
        assertThat(supportedDecision.grams).isAtMost(supported)

        val rollingConsumed = 29.81
        val rollingDecision = assertSend(
            defaultInput(
                supportedLowerBoundGrams = 60.0,
                remoteLedger = listOf(entry(minutesAgo = 10, grams = rollingConsumed, seq = 1))
            ),
            grams = 0.1,
            seq = 2
        )
        assertThat(rollingDecision.grams).isAtMost(30.0 - rollingConsumed)

        val episodeConsumed = 49.81
        val episodeDecision = assertSend(
            defaultInput(
                supportedLowerBoundGrams = 60.0,
                remoteLedger = listOf(entry(minutesAgo = 31, grams = episodeConsumed, seq = 1))
            ),
            grams = 0.1,
            seq = 2
        )
        assertThat(episodeDecision.grams).isAtMost(50.0 - episodeConsumed)
    }

    @Test
    fun blocksWhenSupportedLowerBoundIsAlreadyDelivered() {
        assertBlocked(
            defaultInput(
                supportedLowerBoundGrams = 5.0,
                remoteLedger = listOf(entry(minutesAgo = 10, grams = 5.0, seq = 1))
            ),
            "supported_lower_bound_fulfilled"
        )
    }

    @Test
    fun nextSequenceUsesMaximumReconciledSequenceRegardlessOfLedgerOrder() {
        val ledger = listOf(
            entry(minutesAgo = 31, grams = 1.0, seq = 7),
            entry(minutesAgo = 45, grams = 1.0, seq = 2),
            entry(minutesAgo = 10, grams = 1.0, seq = 4)
        )
        val forward = UamExportPolicy.decide(
            defaultInput(supportedLowerBoundGrams = 15.0, remoteLedger = ledger)
        )
        val reversed = UamExportPolicy.decide(
            defaultInput(supportedLowerBoundGrams = 15.0, remoteLedger = ledger.reversed())
        )

        assertThat(forward).isEqualTo(reversed)
        assertThat(forward).isEqualTo(
            UamExportDecision.Send(
                grams = 12.0,
                treatmentTs = (NOW_TS / FIVE_MINUTE_MS) * FIVE_MINUTE_MS,
                seq = 8
            )
        )
    }

    @Test
    fun repeatedIdenticalInputReturnsEqualDecision() {
        val input = defaultInput(
            supportedLowerBoundGrams = 40.0,
            remoteLedger = listOf(entry(minutesAgo = 10, grams = 10.0, seq = 3))
        )

        assertThat(UamExportPolicy.decide(input)).isEqualTo(UamExportPolicy.decide(input))
    }

    @Test
    fun episodeIdMustBeNonBlank() {
        listOf("", " ", "\t\n").forEach { episodeId ->
            assertBlocked(defaultInput(episodeId = episodeId), "invalid_episode_id")
        }
    }

    @Test
    fun negativeCurrentTimestampFailsClosed() {
        assertBlocked(
            defaultInput(nowTs = -1L, activeSinceTs = -1L),
            "invalid_now_timestamp"
        )
    }

    @Test
    fun sourceSnapshotUsesTenMinuteWallClockLimitInsteadOfSeven() {
        assertSend(
            defaultInput(sourceSnapshotTs = NOW_TS - 10 * MINUTE_MS),
            grams = 15.0,
            seq = 1
        )
        assertBlocked(
            defaultInput(sourceSnapshotTs = NOW_TS - 10 * MINUTE_MS - 1L),
            "source_snapshot_stale"
        )
        assertBlocked(
            defaultInput(sourceSnapshotTs = NOW_TS + 1L),
            "source_snapshot_in_future"
        )
        assertBlocked(
            defaultInput(sourceSnapshotTs = -1L),
            "invalid_source_snapshot_timestamp"
        )
    }

    @Test
    fun changingEpisodeIdCannotResetGlobalThirtyMinuteCap() {
        val globalLedger = listOf(
            entry(minutesAgo = 20, grams = 15.0, seq = 1, episodeId = "old-episode"),
            entry(minutesAgo = 10, grams = 15.0, seq = 2, episodeId = "old-episode")
        )

        assertBlocked(
            defaultInput(globalRemoteLedger = globalLedger),
            "global_rolling_30m_capacity_exhausted"
        )
    }

    @Test
    fun changingEpisodeIdCannotResetGlobalSixtyMinuteCap() {
        val globalLedger = listOf(
            entry(minutesAgo = 55, grams = 15.0, seq = 1, episodeId = "old-episode"),
            entry(minutesAgo = 40, grams = 15.0, seq = 2, episodeId = "old-episode"),
            entry(minutesAgo = 31, grams = 15.0, seq = 3, episodeId = "old-episode"),
            entry(minutesAgo = 10, grams = 5.0, seq = 4, episodeId = "old-episode")
        )

        assertBlocked(
            defaultInput(globalRemoteLedger = globalLedger),
            "global_rolling_60m_capacity_exhausted"
        )
    }

    @Test
    fun anotherLiveEpisodeSharesGlobalCapacityWithoutBlocking() {
        assertSend(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(minutesAgo = 10, grams = 5.0, seq = 1, episodeId = "old-episode")
                )
            ),
            grams = 15.0,
            seq = 1
        )
    }

    @Test
    fun anotherLiveEpisodeStillSharesGlobalTenMinuteInterval() {
        assertBlocked(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(
                        tsMs = NOW_TS - 10 * MINUTE_MS + 1L,
                        grams = 5.0,
                        seq = 1,
                        episodeId = "old-episode"
                    )
                )
            ),
            "write_interval_under_10m"
        )
    }

    @Test
    fun globalSequenceIdentityIsScopedByEpisode() {
        assertSend(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(minutesAgo = 70, grams = 5.0, seq = 1, episodeId = "episode-a"),
                    entry(minutesAgo = 80, grams = 5.0, seq = 1, episodeId = "episode-b")
                )
            ),
            grams = 15.0,
            seq = 1
        )
        assertBlocked(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(minutesAgo = 70, grams = 5.0, seq = 1, episodeId = "episode-a"),
                    entry(minutesAgo = 80, grams = 5.0, seq = 1, episodeId = "episode-a")
                )
            ),
            "invalid_global_ledger"
        )
    }

    @Test
    fun globalLedgerRejectsInvalidAmountsTimestampsAndFutureEntries() {
        assertBlocked(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(minutesAgo = 70, grams = Double.NaN, seq = 1, episodeId = "episode-a")
                )
            ),
            "invalid_global_ledger"
        )
        assertBlocked(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(tsMs = -1L, grams = 5.0, seq = 1, episodeId = "episode-a")
                )
            ),
            "invalid_global_ledger"
        )
        assertBlocked(
            defaultInput(
                globalRemoteLedger = listOf(
                    entry(tsMs = NOW_TS + 1L, grams = 5.0, seq = 1, episodeId = "episode-a")
                )
            ),
            "global_ledger_entry_in_future"
        )
    }

    @Test
    fun sameEpisodeNormalContinuationKeepsSequenceAndLimits() {
        val currentLedger = listOf(
            entry(minutesAgo = 10, grams = 5.0, seq = 1, episodeId = "episode-1")
        )

        assertSend(
            defaultInput(
                supportedLowerBoundGrams = 15.0,
                remoteLedger = currentLedger,
                globalRemoteLedger = currentLedger
            ),
            grams = 10.0,
            seq = 2
        )
    }

    private fun defaultInput(
        nowTs: Long = NOW_TS,
        episodeId: String = "episode-1",
        activeSinceTs: Long? = nowTs - 10 * MINUTE_MS,
        confidence: Double = 0.80,
        supportedLowerBoundGrams: Double? = 15.0,
        lowerBoundStableBuckets: Int = 2,
        sensorTrust: Double = 0.90,
        sensorBlocked: Boolean = false,
        signedResidualMmol5: Double = 0.10,
        shortAverageDeltaMmol5: Double = 0.10,
        currentGlucoseMmol: Double = 8.0,
        forecastMinimumMmol: Double = 8.0,
        effectiveCobGrams: Double = 0.0,
        therapyCoverage: Double = 0.90,
        remoteLedger: List<UamExportLedgerEntry> = emptyList(),
        sourceSnapshotTs: Long = nowTs,
        globalRemoteLedger: List<UamExportLedgerEntry> = remoteLedger,
        maximumIncrementGrams: Double = UamExportPolicy.MAX_INCREMENT_G
    ) = UamExportPolicyInput(
        nowTs = nowTs,
        episodeId = episodeId,
        activeSinceTs = activeSinceTs,
        confidence = confidence,
        supportedLowerBoundGrams = supportedLowerBoundGrams,
        lowerBoundStableBuckets = lowerBoundStableBuckets,
        sensorTrust = sensorTrust,
        sensorBlocked = sensorBlocked,
        signedResidualMmol5 = signedResidualMmol5,
        shortAverageDeltaMmol5 = shortAverageDeltaMmol5,
        currentGlucoseMmol = currentGlucoseMmol,
        forecastMinimumMmol = forecastMinimumMmol,
        effectiveCobGrams = effectiveCobGrams,
        therapyCoverage = therapyCoverage,
        remoteLedger = remoteLedger,
        sourceSnapshotTs = sourceSnapshotTs,
        globalRemoteLedger = globalRemoteLedger,
        maximumIncrementGrams = maximumIncrementGrams
    )

    private fun entry(
        minutesAgo: Long? = null,
        tsMs: Long = NOW_TS - requireNotNull(minutesAgo) * MINUTE_MS,
        grams: Double,
        seq: Int,
        episodeId: String? = null
    ) = UamExportLedgerEntry(tsMs = tsMs, grams = grams, seq = seq, episodeId = episodeId)

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.size <= 1) return listOf(values)
        return values.indices.flatMap { index ->
            val remaining = values.filterIndexed { candidateIndex, _ -> candidateIndex != index }
            permutations(remaining).map { permutation -> listOf(values[index]) + permutation }
        }
    }

    private fun assertBlocked(input: UamExportPolicyInput, reason: String) {
        assertThat(UamExportPolicy.decide(input)).isEqualTo(UamExportDecision.Block(reason))
    }

    private fun assertSend(
        input: UamExportPolicyInput,
        grams: Double,
        seq: Int
    ): UamExportDecision.Send {
        val decision = UamExportPolicy.decide(input)
        assertThat(decision).isInstanceOf(UamExportDecision.Send::class.java)
        return (decision as UamExportDecision.Send).also { send ->
            assertThat(send.grams).isEqualTo(grams)
            assertThat(send.seq).isEqualTo(seq)
            assertThat(send.grams.isFinite()).isTrue()
            assertThat(send.grams).isAtLeast(UamExportPolicy.MIN_SEND_QUANTUM_G)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTE_MS = 5 * MINUTE_MS
        const val HOUR_MS = 60 * MINUTE_MS
        const val NOW_TS = 1_800_000_123_456L
    }
}
