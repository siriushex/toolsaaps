package io.aaps.copilot.ui

import java.util.Locale

internal fun buildAiChatContextSummary(state: MainUiState, generatedAtMs: Long): String {
    fun number(value: Double?): String = value?.takeIf(Double::isFinite)
        ?.let { String.format(Locale.US, "%.2f", it) } ?: "--"

    val metrics = state.dailyReportMetrics.joinToString("\n") { metric ->
        "${metric.horizonMinutes}m -> MAE=${number(metric.mae)}, RMSE=${number(metric.rmse)}, " +
            "MARD=${number(metric.mardPct)}%, BIAS=${number(metric.bias)}, n=${metric.sampleCount ?: "unknown"}"
    }.ifBlank { "daily_report_metrics: none" }
    val factors = state.dailyReportReplayFactors.sortedByDescending { it.contributionScore }
        .take(6).joinToString("; ") {
            "${it.horizonMinutes}m:${it.factor} score=${number(it.contributionScore)} uplift=${number(it.upliftPct)}%"
        }.ifBlank { "none" }
    val hotspots = state.dailyReportReplayHotspots.sortedByDescending { it.mae }
        .take(6).joinToString("; ") {
            "${it.horizonMinutes}m H${it.hour}: MAE=${number(it.mae)} MARD=${number(it.mardPct)}%"
        }.ifBlank { "none" }

    return buildString {
        appendLine("context_kind=DEVICE_DISPLAY_SNAPSHOT")
        appendLine("context_generated_at_ms=$generatedAtMs")
        appendLine("snapshot_is_complete_history=false")
        appendLine("missing_numeric_value=-- timestamps_are_epoch_ms=true")
        appendLine("coverage_span_hours=${number(state.aiDataCoverageHours)}")
        appendLine("coverage_is_not_time_in_range_or_completeness=true")
        appendLine("analytics_minimum_hours=${state.aiMinDataHours} analytics_ready=${state.aiAnalysisReady}")
        appendLine("glucose_current=${number(state.latestGlucoseMmol)} mmol/L")
        appendLine("glucose_age_minutes=${state.latestDataAgeMinutes ?: "unknown"}")
        appendLine("glucose_raw_mmol=${number(state.rawGlucoseMmol)} glucose_calibrated_mmol=${number(state.calibratedGlucoseMmol)}")
        appendLine("calibration_status=${state.glucoseCalibrationStatus ?: "unknown"} confidence=${number(state.glucoseCalibrationConfidence)}")
        appendLine("delta=${number(state.glucoseDelta)} mmol/L")
        appendLine("forecast_5=${number(state.forecast5m)} ci=[${number(state.forecast5mCiLow)}..${number(state.forecast5mCiHigh)}]")
        appendLine("forecast_30=${number(state.forecast30m)} ci=[${number(state.forecast30mCiLow)}..${number(state.forecast30mCiHigh)}]")
        appendLine("forecast_60=${number(state.forecast60m)} ci=[${number(state.forecast60mCiLow)}..${number(state.forecast60mCiHigh)}]")
        appendLine("forecast_accepted_generation_ms=${state.forecastAcceptedGenerationTs ?: "unknown"}")
        appendLine("iob_positive_units=${number(state.latestIobUnits)} iob_net_units=${number(state.latestIobRealUnits)}")
        appendLine("iob_source=${state.latestIobRuntimeSource ?: "unknown"} iob_evidence_timestamp_ms=${state.latestIobEvidenceTimestamp ?: "unknown"}")
        appendLine("cob_grams=${number(state.latestCobGrams)} insulin_onset_minutes=${number(state.insulinRealOnsetMinutes)}")
        appendLine("cob_external_grams=${number(state.latestExternalCobGrams)} timestamp_ms=${state.latestExternalCobTimestamp ?: "unknown"}")
        appendLine("cob_effective_grams=${number(state.latestEffectiveCobGrams)} timestamp_ms=${state.latestEffectiveCobTimestamp ?: "unknown"}")
        appendLine("insulin_action_duration_hours=not_provided")
        appendLine("isf_real=${number(state.isfCrRealtimeIsfEff)} isf_base=${number(state.isfCrRealtimeIsfBase)}")
        appendLine("cr_real=${number(state.isfCrRealtimeCrEff)} cr_base=${number(state.isfCrRealtimeCrBase)}")
        appendLine("isfcr_conf=${number(state.isfCrRealtimeConfidence)} quality=${number(state.isfCrRealtimeQualityScore)} mode=${state.isfCrRealtimeMode ?: "--"}")
        appendLine("activity_ratio=${number(state.latestActivityRatio)} steps=${number(state.latestStepsCount)}")
        appendLine("uam_calc_active=${state.calculatedUamActive ?: "unknown"} uci0=${number(state.calculatedUci0Mmol5m)} uam_carbs=${number(state.calculatedUamCarbsGrams)}")
        appendLine("sensor_quality_score=${number(state.sensorQualityScore)} sensor_blocked=${state.sensorQualityBlocked ?: "unknown"}")
        appendLine("sensor_age_hours=${number(state.sensorAgeHours)} source=${state.sensorAgeSource ?: "unknown"}")
        appendLine("sensor_quality_reason=${state.sensorQualityReason ?: "unknown"} suspect_false_low=${state.sensorQualitySuspectFalseLow ?: "unknown"}")
        appendLine("adaptive_state=${state.controllerState ?: "--"} next_target=${number(state.controllerNextTarget)} confidence=${number(state.controllerConfidence)}")
        appendLine("target_manager_mode=${state.targetManagerMode} base_target_mmol=${number(state.baseTargetMmol)}")
        state.eatingSoonObservedTarget?.let {
            appendLine("observed_target_mmol=${number(it.targetMmol)} started_ms=${it.startedAt} expires_ms=${it.expiresAt}")
            appendLine("observed_target_evidence_resolved=${it.evidenceResolved} eating_soon_confirmed=${it.eatingSoonConfirmed}")
        }
        appendLine("daily_report_generated_ts=${state.dailyReportGeneratedAtTs ?: "unknown"}")
        appendLine("daily_report_metrics:")
        appendLine(metrics)
        appendLine("daily_replay_top_factors=$factors")
        appendLine("daily_replay_hotspots=$hotspots")
        appendLine("daily_recommendations=${state.dailyReportRecommendations.take(6).joinToString("; ").ifBlank { "none" }}")
    }
}
