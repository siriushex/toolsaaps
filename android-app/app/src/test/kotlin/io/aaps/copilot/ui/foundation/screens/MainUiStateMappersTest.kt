package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.AuditRecordRowUi
import io.aaps.copilot.ui.GlucoseHistoryRowUi
import io.aaps.copilot.ui.IsfCrHistoryPointUi
import io.aaps.copilot.ui.LastActionRowUi
import io.aaps.copilot.ui.MainUiState
import org.junit.Test

class MainUiStateMappersTest {
    private fun state(block: MainUiState.() -> Unit): MainUiState = MainUiState().apply(block)

    @Test
    fun overviewMapping_mapsCoreMetricsTelemetryAndWarnings() {
        val state = state {
            latestGlucoseMmol = 8.7
            glucoseDelta = 0.24
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            latestIobUnits = 1.76
            latestCobGrams = 22.4
            latestActivityRatio = 1.16
            latestStepsCount = 1234.0
            forecast5m = 8.9
            forecast5mCiLow = 8.5
            forecast5mCiHigh = 9.2
            forecast30m = 9.8
            forecast30mCiLow = 8.0
            forecast30mCiHigh = 10.7
            forecast60m = 10.2
            forecast60mCiLow = 9.0
            forecast60mCiHigh = 11.4
            calculatedUamActive = true
            calculatedUci0Mmol5m = 0.19
            inferredUamCarbsGrams = 18.0
            lastAction = LastActionRowUi(
                type = "temp_target",
                status = "SENT",
                timestamp = 1_800_000_000_000L,
                tempTargetMmol = 5.2,
                durationMinutes = 30
            )
        }

        val ui = state.toOverviewUiState(isProMode = true)

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.isStale).isFalse()
        assertThat(ui.glucose).isEqualTo(8.7)
        assertThat(ui.horizons).hasSize(3)
        assertThat(ui.horizons.first { it.horizonMinutes == 30 }.warningWideCi).isTrue()
        assertThat(ui.telemetryChips).hasSize(4)
        assertThat(ui.telemetryChips.first { it.label == "IOB" }.value).isEqualTo("1.8")
        assertThat(ui.telemetryChips.first { it.label == "COB" }.value).isEqualTo("22.4")
        assertThat(ui.lastAction?.type).isEqualTo("temp_target")
        assertThat(ui.uamActive).isTrue()
        assertThat(ui.uci0Mmol5m).isEqualTo(0.19)
        assertThat(ui.uamModeLabel).isEqualTo("BOOST")
    }

    @Test
    fun forecastMapping_appliesRangeFilterAndBuildsFuturePath() {
        val now = 1_800_000_000_000L
        val state = state {
            latestGlucoseMmol = 6.4
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            forecast5m = 6.6
            forecast30m = 7.0
            forecast60m = 7.5
            forecast5mCiLow = 6.3
            forecast5mCiHigh = 6.9
            forecast30mCiLow = 6.4
            forecast30mCiHigh = 7.6
            forecast60mCiLow = 6.7
            forecast60mCiHigh = 8.3
            trend60ComponentMmol = 0.5
            therapy60ComponentMmol = 0.3
            uam60ComponentMmol = 0.2
            residualRoc0Mmol5m = 0.08
            sigmaEMmol5m = 0.11
            kfSigmaGMmol = 0.09
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = now - 4 * 60 * 60_000L, valueMmol = 6.0),
                GlucoseHistoryRowUi(timestamp = now - 2 * 60 * 60_000L, valueMmol = 6.2),
                GlucoseHistoryRowUi(timestamp = now, valueMmol = 6.4)
            )
        }

        val ui = state.toForecastUiState(range = ForecastRangeUi.H3, layers = ForecastLayerState(), isProMode = true)

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.historyPoints).hasSize(2)
        assertThat(ui.futurePath).hasSize(13)
        assertThat(ui.futureCi).hasSize(13)
        assertThat(ui.decomposition.trend60).isEqualTo(0.5)
        assertThat(ui.decomposition.therapy60).isEqualTo(0.3)
        assertThat(ui.decomposition.uam60).isEqualTo(0.2)
    }

    @Test
    fun safetyMapping_usesConfiguredHardBounds() {
        val state = state {
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            killSwitch = false
            maxActionsIn6Hours = 4
            baseTargetMmol = 5.5
            safetyMinTargetMmol = 4.6
            safetyMaxTargetMmol = 9.4
            localNightscoutEnabled = true
            localNightscoutPort = 17582
        }

        val ui = state.toSafetyUiState()

        assertThat(ui.hardMinTargetMmol).isEqualTo(4.6)
        assertThat(ui.hardMaxTargetMmol).isEqualTo(9.4)
        assertThat(ui.hardBounds).isEqualTo("4.6..9.4")
        assertThat(ui.adaptiveBounds).isEqualTo("4.6..9.4")
    }

    @Test
    fun auditMapping_appliesWindowAndErrorFilters() {
        val now = System.currentTimeMillis()
        val state = state {
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            auditRecords = listOf(
                AuditRecordRowUi(
                    id = "info_recent",
                    ts = now - 60 * 60_000L,
                    source = "audit",
                    level = "INFO",
                    summary = "all good",
                    context = "ctx"
                ),
                AuditRecordRowUi(
                    id = "error_recent",
                    ts = now - 30 * 60_000L,
                    source = "action",
                    level = "ERROR",
                    summary = "delivery failed",
                    context = "ctx"
                ),
                AuditRecordRowUi(
                    id = "error_old",
                    ts = now - 8 * 60 * 60_000L,
                    source = "rule",
                    level = "ERROR",
                    summary = "old error",
                    context = "ctx"
                )
            )
        }

        val errors6h = state.toAuditUiState(window = AuditWindowUi.H6, onlyErrors = true)
        val all24h = state.toAuditUiState(window = AuditWindowUi.H24, onlyErrors = false)

        assertThat(errors6h.rows.map { it.id }).containsExactly("error_recent")
        assertThat(all24h.rows.map { it.id }).containsExactly("info_recent", "error_recent", "error_old")
    }

    @Test
    fun analyticsMapping_includesIsfCrSummaryAndHistory() {
        val now = System.currentTimeMillis()
        val state = state {
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            profileIsf = 2.31
            profileCr = 11.4
            profileCalculatedIsf = 2.45
            profileCalculatedCr = 10.8
            qualityMetrics = listOf(
                io.aaps.copilot.ui.QualityMetricUi(
                    horizonMinutes = 30,
                    sampleCount = 120,
                    mae = 0.42,
                    rmse = 0.71,
                    mardPct = 6.8
                )
            )
            dailyReportGeneratedAtTs = now
            dailyReportMatchedSamples = 246
            dailyReportForecastRows = 1200
            dailyReportPeriodStartUtc = "2026-03-02T00:00:00Z"
            dailyReportPeriodEndUtc = "2026-03-03T00:00:00Z"
            dailyReportMarkdownPath = "/storage/emulated/0/Documents/forecast-reports/forecast-report-2026-03-03.md"
            dailyReportMetrics = listOf(
                io.aaps.copilot.ui.DailyReportMetricUi(
                    horizonMinutes = 5,
                    sampleCount = 80,
                    mae = 0.24,
                    rmse = 0.35,
                    mardPct = 4.8,
                    bias = 0.03,
                    ciCoveragePct = 82.5,
                    ciMeanWidth = 0.54
                ),
                io.aaps.copilot.ui.DailyReportMetricUi(
                    horizonMinutes = 60,
                    sampleCount = 80,
                    mae = 0.74,
                    rmse = 0.98,
                    mardPct = 10.1,
                    bias = -0.09,
                    ciCoveragePct = 68.0,
                    ciMeanWidth = 1.42
                )
            )
            dailyReportRecommendations = listOf(
                "CR extraction has many gross CGM gaps (24.0% of dropped windows).",
                "ISF/CR extraction is often blocked by sensor quality (22.0% of dropped windows)."
            )
            dailyReportIsfCrQualityLines = listOf(
                "source=isfcr_evidence_extracted, events=24, dropped=88",
                "CR integrity drop-rate: gap=26.1%, sensorBlocked=24.0%, uamAmbiguity=20.5%"
            )
            dailyReportReplayHotspots = listOf(
                io.aaps.copilot.ui.DailyReportReplayHotspotUi(
                    horizonMinutes = 5,
                    hour = 8,
                    sampleCount = 18,
                    mae = 0.31,
                    mardPct = 4.7,
                    bias = 0.02
                ),
                io.aaps.copilot.ui.DailyReportReplayHotspotUi(
                    horizonMinutes = 60,
                    hour = 19,
                    sampleCount = 22,
                    mae = 1.14,
                    mardPct = 13.2,
                    bias = -0.28
                )
            )
            dailyReportReplayFactors = listOf(
                io.aaps.copilot.ui.DailyReportReplayFactorUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    sampleCount = 120,
                    corrAbsError = 0.52,
                    maeHigh = 1.35,
                    maeLow = 0.78,
                    upliftPct = 73.1,
                    contributionScore = 0.61
                ),
                io.aaps.copilot.ui.DailyReportReplayFactorUi(
                    horizonMinutes = 30,
                    factor = "CI",
                    sampleCount = 120,
                    corrAbsError = 0.44,
                    maeHigh = 0.88,
                    maeLow = 0.54,
                    upliftPct = 62.9,
                    contributionScore = 0.52
                )
            )
            dailyReportReplayCoverage = listOf(
                io.aaps.copilot.ui.DailyReportReplayCoverageUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    sampleCount = 118,
                    coveragePct = 95.2
                ),
                io.aaps.copilot.ui.DailyReportReplayCoverageUi(
                    horizonMinutes = 30,
                    factor = "UAM",
                    sampleCount = 87,
                    coveragePct = 70.8
                )
            )
            dailyReportReplayRegimes = listOf(
                io.aaps.copilot.ui.DailyReportReplayRegimeUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    bucket = "HIGH",
                    sampleCount = 40,
                    meanFactorValue = 31.5,
                    mae = 1.28,
                    mardPct = 12.4,
                    bias = -0.22
                ),
                io.aaps.copilot.ui.DailyReportReplayRegimeUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    bucket = "LOW",
                    sampleCount = 38,
                    meanFactorValue = 7.1,
                    mae = 0.76,
                    mardPct = 8.5,
                    bias = -0.06
                )
            )
            dailyReportReplayPairs = listOf(
                io.aaps.copilot.ui.DailyReportReplayPairUi(
                    horizonMinutes = 60,
                    factorA = "COB",
                    factorB = "IOB",
                    bucketA = "HIGH",
                    bucketB = "HIGH",
                    sampleCount = 18,
                    meanFactorA = 30.8,
                    meanFactorB = 2.0,
                    mae = 1.22,
                    mardPct = 12.1,
                    bias = -0.20
                ),
                io.aaps.copilot.ui.DailyReportReplayPairUi(
                    horizonMinutes = 60,
                    factorA = "COB",
                    factorB = "IOB",
                    bucketA = "LOW",
                    bucketB = "LOW",
                    sampleCount = 17,
                    meanFactorA = 7.2,
                    meanFactorB = 0.8,
                    mae = 0.71,
                    mardPct = 8.2,
                    bias = -0.05
                )
            )
            dailyReportReplayTopMisses = listOf(
                io.aaps.copilot.ui.DailyReportReplayTopMissUi(
                    horizonMinutes = 5,
                    ts = now - 15 * 60_000L,
                    absError = 0.42,
                    pred = 7.2,
                    actual = 6.8,
                    cob = 14.0,
                    iob = 1.2,
                    uam = 0.10,
                    ciWidth = 0.9,
                    diaHours = 4.0,
                    activity = 1.05,
                    sensorQuality = 0.92
                ),
                io.aaps.copilot.ui.DailyReportReplayTopMissUi(
                    horizonMinutes = 60,
                    ts = now - 40 * 60_000L,
                    absError = 1.32,
                    pred = 10.4,
                    actual = 9.1,
                    cob = 32.0,
                    iob = 1.4,
                    uam = 0.28,
                    ciWidth = 1.6,
                    diaHours = 4.2,
                    activity = 0.98,
                    sensorQuality = 0.88
                )
            )
            dailyReportReplayErrorClusters = listOf(
                io.aaps.copilot.ui.DailyReportReplayErrorClusterUi(
                    horizonMinutes = 60,
                    hour = 19,
                    dayType = "WEEKDAY",
                    sampleCount = 20,
                    mae = 1.18,
                    mardPct = 12.6,
                    bias = -0.21,
                    meanCob = 31.2,
                    meanIob = 1.9,
                    meanUam = 0.24,
                    meanCiWidth = 1.55,
                    dominantFactor = "COB",
                    dominantScore = 1.56
                )
            )
            dailyReportReplayDayTypeGaps = listOf(
                io.aaps.copilot.ui.DailyReportReplayDayTypeGapUi(
                    horizonMinutes = 60,
                    hour = 19,
                    worseDayType = "WEEKEND",
                    weekdaySampleCount = 12,
                    weekendSampleCount = 10,
                    weekdayMae = 0.86,
                    weekendMae = 1.24,
                    weekdayMardPct = 9.8,
                    weekendMardPct = 12.9,
                    maeGapMmol = 0.38,
                    mardGapPct = 3.1,
                    worseMeanCob = 27.4,
                    worseMeanIob = 1.7,
                    worseMeanUam = 0.22,
                    worseMeanCiWidth = 1.49,
                    dominantFactor = "COB",
                    dominantScore = 1.37
                )
            )
            dailyReportReplayTopFactorsOverall = "COB=0.612;CI=0.521;IOB=0.344"
            rollingReportLines = listOf(
                "14d: n=840, MAE30=0.52, MAE60=0.81, MARD30=7.4%, MARD60=10.9%, CI60=63.0%, W60=1.39",
                "30d: n=1740, MAE30=0.49, MAE60=0.78, MARD30=7.1%, MARD60=10.5%, CI60=64.2%, W60=1.34"
            )
            baselineDeltaLines = listOf("60m UAM: +0.09 mmol/L")
            isfCrHistoryPoints = listOf(
                IsfCrHistoryPointUi(
                    timestamp = now - 60 * 60_000L,
                    isfMerged = 2.30,
                    crMerged = 11.3,
                    isfCalculated = 2.42,
                    crCalculated = 10.9
                ),
                IsfCrHistoryPointUi(
                    timestamp = now,
                    isfMerged = 2.31,
                    crMerged = 11.4,
                    isfCalculated = 2.45,
                    crCalculated = 10.8
                )
            )
            isfCrHistoryLastUpdatedTs = now
            isfCrDeepLines = listOf("day/06:00-09:00 ISF=2.4 CR=10.9 conf=74%")
            isfCrActivationGateLines = listOf(
                "KPI gate (03 Mar 12:00): eligible=true, reason=ok, n=360, conf=72%",
                "Daily gate (03 Mar 12:00): eligible=true, reason=ok, n=288"
            )
            isfCrDroppedReasons24hLines = listOf(
                "Events=12, dropped total=21",
                "isf_small_units=9"
            )
            isfCrDroppedReasons7dLines = listOf(
                "Events=70, dropped total=88",
                "cr_no_bolus_nearby=24"
            )
            isfCrWearImpact24hLines = listOf(
                "Events=12, mean set/sensor age=48.0h/60.0h",
                "High wear set>72h=20%, sensor>120h=10%"
            )
            isfCrWearImpact7dLines = listOf(
                "Events=70, mean set/sensor age=54.0h/84.0h",
                "Mean factors set/sensor=0.93/0.96"
            )
            isfCrRuntimeDiagTs = now - 5 * 60_000L
            isfCrRuntimeDiagMode = "SHADOW"
            isfCrRuntimeDiagConfidence = 0.71
            isfCrRuntimeDiagConfidenceThreshold = 0.55
            isfCrRuntimeDiagQualityScore = 0.83
            isfCrRuntimeDiagUsedEvidence = 18
            isfCrRuntimeDiagDroppedEvidence = 4
            isfCrRuntimeDiagDroppedReasons = "isf_small_units=2;cr_no_bolus_nearby=2"
            isfCrRuntimeDiagCurrentDayType = "WEEKDAY"
            isfCrRuntimeDiagIsfBaseSource = "day_type"
            isfCrRuntimeDiagCrBaseSource = "hourly"
            isfCrRuntimeDiagIsfDayTypeBaseAvailable = true
            isfCrRuntimeDiagCrDayTypeBaseAvailable = false
            isfCrRuntimeDiagHourWindowIsfEvidence = 1
            isfCrRuntimeDiagHourWindowCrEvidence = 0
            isfCrRuntimeDiagHourWindowIsfSameDayType = 1
            isfCrRuntimeDiagHourWindowCrSameDayType = 0
            isfCrRuntimeDiagMinIsfEvidencePerHour = 2
            isfCrRuntimeDiagMinCrEvidencePerHour = 2
            isfCrRuntimeDiagCrMaxGapMinutes = 30.0
            isfCrRuntimeDiagCrMaxSensorBlockedRatePct = 25.0
            isfCrRuntimeDiagCrMaxUamAmbiguityRatePct = 60.0
            isfCrRuntimeDiagCoverageHoursIsf = 6
            isfCrRuntimeDiagCoverageHoursCr = 5
            isfCrRuntimeDiagReasons = "isf_evidence_sparse,isf_hourly_evidence_below_min,cr_hourly_evidence_below_min,isf_day_type_evidence_sparse"
            isfCrRuntimeDiagLowConfidenceTs = now - 2 * 60 * 60_000L
            isfCrRuntimeDiagLowConfidenceReasons = "low_quality"
            isfCrRuntimeDiagFallbackTs = now - 90 * 60_000L
            isfCrRuntimeDiagFallbackReasons = "low_confidence_fallback"
        }

        val ui = state.toAnalyticsUiState()

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.currentIsfReal).isEqualTo(2.45)
        assertThat(ui.currentCrReal).isEqualTo(10.8)
        assertThat(ui.currentIsfMerged).isEqualTo(2.31)
        assertThat(ui.currentCrMerged).isEqualTo(11.4)
        assertThat(ui.historyPoints).hasSize(2)
        assertThat(ui.historyLastUpdatedTs).isEqualTo(now)
        assertThat(ui.deepLines).containsExactly("day/06:00-09:00 ISF=2.4 CR=10.9 conf=74%")
        assertThat(ui.qualityLines).hasSize(1)
        assertThat(ui.baselineDeltaLines).containsExactly("60m UAM: +0.09 mmol/L")
        assertThat(ui.dailyReportGeneratedAtTs).isEqualTo(now)
        assertThat(ui.dailyReportMatchedSamples).isEqualTo(246)
        assertThat(ui.dailyReportForecastRows).isEqualTo(1200)
        assertThat(ui.dailyReportPeriodStartUtc).isEqualTo("2026-03-02T00:00:00Z")
        assertThat(ui.dailyReportPeriodEndUtc).isEqualTo("2026-03-03T00:00:00Z")
        assertThat(ui.dailyReportMarkdownPath).contains("forecast-report-2026-03-03.md")
        assertThat(ui.dailyReportHorizonStats).hasSize(2)
        assertThat(ui.dailyReportRecommendations).hasSize(2)
        assertThat(ui.dailyReportRecommendations.first()).contains("gross CGM gaps")
        assertThat(ui.dailyReportIsfCrQualityLines).hasSize(2)
        assertThat(ui.dailyReportIsfCrQualityLines.first()).contains("source=isfcr_evidence_extracted")
        assertThat(ui.dailyReportReplayHotspots).hasSize(2)
        assertThat(ui.dailyReportReplayHotspots.first { it.horizonMinutes == 60 }.hour).isEqualTo(19)
        assertThat(ui.dailyReportReplayFactorContributions).hasSize(2)
        assertThat(ui.dailyReportReplayFactorContributions.first { it.horizonMinutes == 60 }.factor).isEqualTo("COB")
        assertThat(ui.dailyReportReplayFactorCoverage).hasSize(2)
        assertThat(ui.dailyReportReplayFactorCoverage.first { it.horizonMinutes == 60 }.coveragePct).isEqualTo(95.2)
        assertThat(ui.dailyReportReplayFactorRegimes).hasSize(2)
        assertThat(ui.dailyReportReplayFactorRegimes.first { it.bucket == "HIGH" }.mae).isEqualTo(1.28)
        assertThat(ui.dailyReportReplayFactorPairs).hasSize(2)
        assertThat(ui.dailyReportReplayFactorPairs.first { it.bucketA == "HIGH" && it.bucketB == "HIGH" }.mae)
            .isEqualTo(1.22)
        assertThat(ui.dailyReportReplayTopMisses).hasSize(2)
        assertThat(ui.dailyReportReplayTopMisses.first { it.horizonMinutes == 60 }.absError).isEqualTo(1.32)
        assertThat(ui.dailyReportReplayErrorClusters).hasSize(1)
        assertThat(ui.dailyReportReplayErrorClusters.first().dominantFactor).isEqualTo("COB")
        assertThat(ui.dailyReportReplayErrorClusters.first().dayType).isEqualTo("WEEKDAY")
        assertThat(ui.dailyReportReplayDayTypeGaps).hasSize(1)
        assertThat(ui.dailyReportReplayDayTypeGaps.first().worseDayType).isEqualTo("WEEKEND")
        assertThat(ui.dailyReportReplayDayTypeGaps.first().maeGapMmol).isEqualTo(0.38)
        assertThat(ui.dailyReportReplayTopFactorsOverall).contains("COB")
        assertThat(ui.dailyReportHorizonStats.first { it.horizonMinutes == 5 }.mardPct).isEqualTo(4.8)
        assertThat(ui.dailyReportHorizonStats.first { it.horizonMinutes == 5 }.ciCoveragePct).isEqualTo(82.5)
        assertThat(ui.dailyReportHorizonStats.first { it.horizonMinutes == 60 }.ciMeanWidth).isEqualTo(1.42)
        assertThat(ui.rollingReportLines).hasSize(2)
        assertThat(ui.rollingReportLines.first()).contains("14d")
        assertThat(ui.activationGateLines).hasSize(2)
        assertThat(ui.activationGateLines.first()).contains("KPI gate")
        assertThat(ui.droppedReasons24hLines).containsExactly("Events=12, dropped total=21", "isf_small_units=9")
        assertThat(ui.droppedReasons7dLines).containsExactly("Events=70, dropped total=88", "cr_no_bolus_nearby=24")
        assertThat(ui.wearImpact24hLines).containsExactly(
            "Events=12, mean set/sensor age=48.0h/60.0h",
            "High wear set>72h=20%, sensor>120h=10%"
        )
        assertThat(ui.wearImpact7dLines).containsExactly(
            "Events=70, mean set/sensor age=54.0h/84.0h",
            "Mean factors set/sensor=0.93/0.96"
        )
        assertThat(ui.runtimeDiagnostics).isNotNull()
        assertThat(ui.runtimeDiagnostics?.mode).isEqualTo("SHADOW")
        assertThat(ui.runtimeDiagnostics?.usedEvidence).isEqualTo(18)
        assertThat(ui.runtimeDiagnostics?.currentDayType).isEqualTo("WEEKDAY")
        assertThat(ui.runtimeDiagnostics?.isfBaseSource).isEqualTo("day_type")
        assertThat(ui.runtimeDiagnostics?.crBaseSource).isEqualTo("hourly")
        assertThat(ui.runtimeDiagnostics?.isfDayTypeBaseAvailable).isTrue()
        assertThat(ui.runtimeDiagnostics?.crDayTypeBaseAvailable).isFalse()
        assertThat(ui.runtimeDiagnostics?.hourWindowIsfEvidence).isEqualTo(1)
        assertThat(ui.runtimeDiagnostics?.hourWindowCrEvidence).isEqualTo(0)
        assertThat(ui.runtimeDiagnostics?.hourWindowIsfSameDayType).isEqualTo(1)
        assertThat(ui.runtimeDiagnostics?.hourWindowCrSameDayType).isEqualTo(0)
        assertThat(ui.runtimeDiagnostics?.minIsfEvidencePerHour).isEqualTo(2)
        assertThat(ui.runtimeDiagnostics?.minCrEvidencePerHour).isEqualTo(2)
        assertThat(ui.runtimeDiagnostics?.crMaxGapMinutes).isEqualTo(30.0)
        assertThat(ui.runtimeDiagnostics?.crMaxSensorBlockedRatePct).isEqualTo(25.0)
        assertThat(ui.runtimeDiagnostics?.crMaxUamAmbiguityRatePct).isEqualTo(60.0)
        assertThat(ui.runtimeDiagnostics?.fallbackReasons).contains("low_confidence_fallback")
        assertThat(ui.runtimeDiagnostics?.droppedReasonCodes).contains("isf_small_units")
        assertThat(ui.runtimeDiagnostics?.droppedReasonCodes).contains("cr_no_bolus_nearby")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("isf_evidence_sparse")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("isf_hourly_evidence_below_min")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("cr_hourly_evidence_below_min")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("isf_day_type_evidence_sparse")
        assertThat(ui.runtimeDiagnostics?.lowConfidenceReasonCodes).contains("low_quality")
        assertThat(ui.runtimeDiagnostics?.fallbackReasonCodes).contains("low_confidence_fallback")
        assertThat(ui.selectedInsulinProfileId).isEqualTo("NOVORAPID")
        assertThat(ui.insulinProfileCurves).isNotEmpty()
        assertThat(ui.insulinProfileCurves.any { it.id == "NOVORAPID" && it.isSelected }).isTrue()
        assertThat(ui.insulinProfileCurves.all { it.points.size >= 2 }).isTrue()
    }

    @Test
    fun analyticsMapping_keepsReferenceCurveAndAddsRealDailyOverlay() {
        val state = state {
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            insulinProfileId = "NOVORAPID"
            insulinRealProfileCurveCompact = "0:0.0000;15:0.0600;30:0.2100;60:0.5600;90:0.7900;120:1.0000"
            insulinRealProfileUpdatedTs = 1_800_000_000_000L
            insulinRealProfileConfidence = 0.74
            insulinRealProfileSamples = 6
            insulinRealProfileOnsetMinutes = 20.0
            insulinRealProfilePeakMinutes = 72.0
            insulinRealProfileScale = 1.18
            insulinRealProfileStatus = "estimated_daily"
        }

        val ui = state.toAnalyticsUiState()

        assertThat(ui.insulinRealProfileAvailable).isTrue()
        assertThat(ui.selectedInsulinProfileId).isEqualTo("NOVORAPID")
        val selectedCurve = ui.insulinProfileCurves.first { it.id == "NOVORAPID" && it.isSelected }
        assertThat(selectedCurve.label).doesNotContain("REAL daily")
        assertThat(selectedCurve.points).isNotEmpty()
        assertThat(ui.insulinRealProfileCurvePoints).hasSize(6)
        assertThat(selectedCurve.points).isNotEqualTo(ui.insulinRealProfileCurvePoints)
        assertThat(ui.insulinRealProfileCurvePoints.first().minute).isEqualTo(0.0)
        assertThat(ui.insulinRealProfileCurvePoints.first().cumulative).isEqualTo(0.0)
        assertThat(ui.insulinRealProfileCurvePoints.last().minute).isEqualTo(120.0)
        assertThat(ui.insulinRealProfileCurvePoints.last().cumulative).isEqualTo(1.0)
    }

    @Test
    fun settingsMapping_includesUamSnackParameters() {
        val state = state {
            baseTargetMmol = 5.6
            nightscoutUrl = "https://example.ns"
            resolvedNightscoutUrl = "https://127.0.0.1:17582"
            insulinProfileId = "NOVORAPID"
            localNightscoutEnabled = true
            localBroadcastIngestEnabled = true
            strictBroadcastSenderValidation = false
            enableUamInference = true
            enableUamBoost = false
            enableUamExportToAaps = true
            uamExportMode = "CONFIRMED_ONLY"
            dryRunExport = false
            uamMinSnackG = 20
            uamMaxSnackG = 70
            uamSnackStepG = 10
            isfCrMinIsfEvidencePerHour = 3
            isfCrMinCrEvidencePerHour = 4
            isfCrCrMaxGapMinutes = 35
            isfCrCrMaxSensorBlockedRatePct = 28.0
            isfCrCrMaxUamAmbiguityRatePct = 66.0
            isfCrAutoActivationRequireDailyQualityGate = true
            isfCrAutoActivationMinDailyMatchedSamples = 144
            isfCrAutoActivationMaxDailyMae30Mmol = 0.85
            isfCrAutoActivationMaxDailyMae60Mmol = 1.35
            isfCrAutoActivationMaxHypoRatePct = 5.5
            isfCrAutoActivationMinDailyCiCoverage30Pct = 57.0
            isfCrAutoActivationMinDailyCiCoverage60Pct = 54.0
            isfCrAutoActivationMaxDailyCiWidth30Mmol = 1.75
            isfCrAutoActivationMaxDailyCiWidth60Mmol = 2.45
            isfCrAutoActivationRollingMinRequiredWindows = 3
            isfCrAutoActivationRollingMaeRelaxFactor = 1.22
            isfCrAutoActivationRollingCiCoverageRelaxFactor = 0.88
            isfCrAutoActivationRollingCiWidthRelaxFactor = 1.31
            isfCrAutoActivationMinSensorQualityScore = 0.48
            isfCrAutoActivationMinSensorFactor = 0.92
            isfCrAutoActivationMaxWearConfidencePenalty = 0.11
            isfCrAutoActivationMaxSensorAgeHighRatePct = 65.0
            isfCrAutoActivationMaxSuspectFalseLowRatePct = 22.0
            isfCrAutoActivationMinDayTypeRatio = 0.34
            isfCrAutoActivationMaxDayTypeSparseRatePct = 61.0
            isfCrAutoActivationDailyRiskBlockLevel = 2
            adaptiveControllerEnabled = true
            safetyMinTargetMmol = 4.3
            safetyMaxTargetMmol = 9.2
            postHypoThresholdMmol = 3.2
            postHypoTargetMmol = 4.6
            analyticsLookbackDays = 400
        }

        val ui = state.toSettingsUiState(verboseLogsEnabled = true, proModeEnabled = false)

        assertThat(ui.uamMinSnackG).isEqualTo(20)
        assertThat(ui.uamMaxSnackG).isEqualTo(70)
        assertThat(ui.uamSnackStepG).isEqualTo(10)
        assertThat(ui.enableUamExportToAaps).isTrue()
        assertThat(ui.uamExportMode).isEqualTo("CONFIRMED_ONLY")
        assertThat(ui.nightscoutUrl).isEqualTo("https://example.ns")
        assertThat(ui.isfCrMinIsfEvidencePerHour).isEqualTo(3)
        assertThat(ui.isfCrMinCrEvidencePerHour).isEqualTo(4)
        assertThat(ui.isfCrCrMaxGapMinutes).isEqualTo(35)
        assertThat(ui.isfCrCrMaxSensorBlockedRatePct).isEqualTo(28.0)
        assertThat(ui.isfCrCrMaxUamAmbiguityRatePct).isEqualTo(66.0)
        assertThat(ui.isfCrAutoActivationRequireDailyQualityGate).isTrue()
        assertThat(ui.isfCrAutoActivationMinDailyMatchedSamples).isEqualTo(144)
        assertThat(ui.isfCrAutoActivationMaxDailyMae30Mmol).isEqualTo(0.85)
        assertThat(ui.isfCrAutoActivationMaxDailyMae60Mmol).isEqualTo(1.35)
        assertThat(ui.isfCrAutoActivationMaxHypoRatePct).isEqualTo(5.5)
        assertThat(ui.isfCrAutoActivationMinDailyCiCoverage30Pct).isEqualTo(57.0)
        assertThat(ui.isfCrAutoActivationMinDailyCiCoverage60Pct).isEqualTo(54.0)
        assertThat(ui.isfCrAutoActivationMaxDailyCiWidth30Mmol).isEqualTo(1.75)
        assertThat(ui.isfCrAutoActivationMaxDailyCiWidth60Mmol).isEqualTo(2.45)
        assertThat(ui.isfCrAutoActivationRollingMinRequiredWindows).isEqualTo(3)
        assertThat(ui.isfCrAutoActivationRollingMaeRelaxFactor).isEqualTo(1.22)
        assertThat(ui.isfCrAutoActivationRollingCiCoverageRelaxFactor).isEqualTo(0.88)
        assertThat(ui.isfCrAutoActivationRollingCiWidthRelaxFactor).isEqualTo(1.31)
        assertThat(ui.isfCrAutoActivationMinSensorQualityScore).isEqualTo(0.48)
        assertThat(ui.isfCrAutoActivationMinSensorFactor).isEqualTo(0.92)
        assertThat(ui.isfCrAutoActivationMaxWearConfidencePenalty).isEqualTo(0.11)
        assertThat(ui.isfCrAutoActivationMaxSensorAgeHighRatePct).isEqualTo(65.0)
        assertThat(ui.isfCrAutoActivationMaxSuspectFalseLowRatePct).isEqualTo(22.0)
        assertThat(ui.isfCrAutoActivationMinDayTypeRatio).isEqualTo(0.34)
        assertThat(ui.isfCrAutoActivationMaxDayTypeSparseRatePct).isEqualTo(61.0)
        assertThat(ui.isfCrAutoActivationDailyRiskBlockLevel).isEqualTo(2)
        assertThat(ui.safetyMinTargetMmol).isEqualTo(4.3)
        assertThat(ui.safetyMaxTargetMmol).isEqualTo(9.2)
    }
}
