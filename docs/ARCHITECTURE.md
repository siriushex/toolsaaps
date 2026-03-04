# ARCHITECTURE

## Context
AAPS Predictive Copilot is a two-part system:
- Android app (`android-app`) for ingest, local forecasting, safety/rules, automation, and UI.
- Backend (`backend`) for optional cloud prediction override, analysis, replay, and scheduled insights.

## High-level boundaries
- Android is source of truth for local automation loop and outbound actions to Nightscout/AAPS transport.
- Backend is advisory/augmentation layer; cloud forecast may override local per horizon but must not bypass local safety constraints.
- Safety decisioning is deterministic rule/policy based. LLM/OpenAI output is insight-only.
- Local physical activity ingestion can be sourced from broadcasts/devicestatus and from on-device `TYPE_STEP_COUNTER` sensor (with `ACTIVITY_RECOGNITION` runtime permission on Android 10+).

## Android modules (logical)
- `data/local`: Room DB entities/dao for glucose, therapy, telemetry, forecasts, audit.
- `data/repository`: sync, ingestion, automation loop, action dispatch, analytics.
- `domain/predict`: prediction engines (legacy/v3), UAM estimator, profile estimators.
- `domain/isfcr`: physiology-aware ISF/CR extraction, base hourly fit, realtime multipliers, confidence/fallback.
- `domain/rules`: adaptive/post-hypo/pattern/segment rules + arbitration.
- `domain/safety`: global policy guardrails.
- `domain/uam inference`: inferred carbs + ingestion time, event state machine, optional boost mode.
- `ui`: Compose screens and state wiring.
- `service/scheduler`: app container, local NS emulation, workers.
- `service/local activity collector`: sensor listener, local activity aggregation, telemetry writes.
- `service/daily reporting`: 24h local forecast quality report generation (Markdown + CSV) with MARD/MAE/RMSE and hotspots.
  - replay attribution block includes factor contributions + coverage for metabolic/runtime drivers (`COB/IOB/UAM/CI`, `DIA`, `activity`, `sensor quality/age`, `ISF quality/confidence`, `set age`, `dawn/stress/hormone/steroid`, context ambiguity).
  - replay attribution additionally includes core-factor regime diagnostics (`LOW/MID/HIGH` bins for `COB/IOB/UAM/CI`) with per-bin MAE/MARD/bias for targeted tuning.
  - replay block also persists structured per-horizon top-miss context (`pred/actual/error + COB/IOB/UAM/CI/DIA/activity/sensorQ`) for targeted tuning.

## Backend modules (logical)
- API endpoints for sync/predict/rules/actions/analysis/replay.
- Scheduler jobs for daily analysis and weekly retraining.
- Persistence layer via SQLAlchemy.

## Integration contracts
- Forecast horizons required in app loop: 5m, 30m, 60m.
- Telemetry units in app domain: mmol/L for glucose and derived glucose deltas.
- Action channel priority: Nightscout API primary; local fallback optional.
- UAM carbs export channel: Nightscout treatments (`Carb Correction`) with backdated timestamp and deterministic note tag (`UAM_ENGINE|id=...|seq=...|...`).
- Temp target command must stay in hard range and pass policy checks before sending.
- Activity-protection override in adaptive rule:
  - if `activity_ratio`/`steps_count` spike indicates active load, temp target is overridden to `7.7..8.7 mmol/L` (load-scaled),
  - after sustained low-load period (`~30 min`) the rule sends recovery back to pre-activity base target.
- Timestamps written to local DB must be normalized and non-zero (`>0`) before persist.
- `cob_grams` and `iob_units` telemetry are allowed to bias local runtime forecasts before rule arbitration.
- Runtime context factors from physiology-aware ISF/CR snapshot (`set/sensor/activity/dawn/stress/hormone/steroid`) are exported to telemetry and applied as bounded forecast context-bias + CI widening before rule arbitration.
- Runtime `cob_grams/iob_units` are resolved with local fallback from therapy history (`insulin profile + carb absorption`) and confidence-weighted merge with external telemetry when both are available.
- `dia_hours` telemetry (if present) is applied to prediction engine insulin action curve by scaling insulin age progression relative to active insulin profile; missing/invalid DIA falls back to profile default.
- Activity telemetry is stored in canonical keys when available:
  - `steps_count`,
  - `activity_ratio`,
  - `distance_km`,
  - `active_minutes`,
  - `calories_active_kcal`.
- If `cob_grams >= 20`, adaptive runtime base target is forced to `4.2 mmol/L` (within hard target bounds) for automation decisions.
- Daily worker must generate a local 24h forecast report from on-device data even when cloud analysis is unavailable.

## Architectural decisions
- Keep prediction engine strategy switchable via flags (legacy vs enhanced versions).
- Keep insulin action profile configurable and persisted in settings; default profile is NOVORAPID.
- Keep controller/rule execution idempotent by time buckets and command keys.
- Keep ISF/CR controlled activation auditable:
  - KPI evaluation event `isfcr_shadow_activation_evaluated`,
  - promotion event `isfcr_shadow_auto_promoted`,
  - evaluation cadence limited (not executed on every cycle tick).
- Keep ISF/CR realtime context factors physiology-aware and toggle-safe:
  - `useManualTags=false` disables only manual tag influence (`stress/illness/hormonal`) without disabling latent telemetry stress signals,
  - sensor wear age (`sensor_change`) contributes a bounded `sensor_age_factor`,
  - activity modifier can derive `steps_rate_15m` from local `steps_count` telemetry when direct rate is absent.
- Keep manual physiology tags operationally usable from UI:
  - quick-tag presets support adjustable severity and duration,
  - quick-tag aliases are normalized (`hormonal_phase -> hormonal`, `steroid -> steroids`) before persistence,
  - tags can be closed individually (`closeById`) without clearing all active context tags.
- Keep ISF/CR uncertainty conservative under ambiguity:
  - confidence model applies explicit penalties for high infusion-set age, high sensor age, context ambiguity (`UAM/stress/manual tags`), and `sensor_quality_suspect_false_low`,
  - CI width is expanded by the same ambiguity penalties to reduce unsafe overconfidence.
- Keep manual `steroids`/`dawn` tags connected to runtime physiology model:
  - `manual_steroid_tag` affects sensitivity through `steroid_factor`,
  - `manual_dawn_tag` affects morning resistance through `dawn_tag_factor` (merged into `dawn_factor`),
  - these factors are included in realtime factor trace and ambiguity penalties.
- Keep ISF/CR evidence weighting wear-aware:
  - evidence sample weight is `quality_score * wear_weight`,
  - `wear_weight` combines infusion-set and sensor-age decay from the latest `set_change`/`sensor_change` markers (neutral `1.0` when markers are absent).
- Keep CR evidence extraction quality-gated by telemetry and CGM continuity:
  - meal+bolus candidate requires bolus window `[-20m, +30m]` around meal,
  - CR windows are dropped on gross CGM gaps (`max gap > 30m`),
  - CR windows are dropped under high sensor-blocked telemetry or strong UAM ambiguity telemetry,
  - dropped reasons are persisted in ISF/CR diagnostics/audit for explainability.
- Keep UAM inference/export cycle on 5-minute buckets even with 1-minute CGM input.
- Keep UAM export idempotent via `id+seq` tags and remote fetch dedup before post.
- Keep carbohydrate absorption event-aware:
  - food catalog classes (`FAST`, `MEDIUM`, `PROTEIN_SLOW`),
  - event classification by payload text/cross-language aliases and glucose pattern fallback,
  - dynamic cumulative absorption curve per event for therapy and UAM residual calculations.
- Keep residual carbs visible in diagnostics (`now`, `30m`, `60m`, `120m`) for model explainability.
- Keep COB/IOB influence explicit and deterministic:
  - forecast bias is horizon-aware (`5/30/60`) and bounded,
  - adaptive controller receives normalized `COB/IOB` telemetry as additional control inputs.
- Keep forecast context factor influence explicit and bounded:
  - context-bias uses only normalized factors (`set/sensor/activity/dawn/stress/hormone/steroid + pattern window`),
  - value shift and CI inflation are horizon-aware and clamped,
  - low sensor quality applies additional displacement guard relative to current glucose.
- Keep per-cycle observability of forecast inputs:
  - audit event `forecast_factor_coverage` reports availability/usage of ISF/CR, DIA, COB/IOB, UAM, sensor/activity/context factors, pattern/history, and whether bias stages were applied.
- Keep forecast history long enough for month/year analysis (`~400 days` retention in app DB).
- Keep analytics snapshots historically:
  - `pattern_windows` append snapshots and query latest per (`dayType`,`hour`) for runtime.
  - `profile_segment_estimates` append snapshots and query latest per (`dayType`,`timeSlot`) for runtime.
  - `profile_estimates` stores both `active` record and timestamped snapshots (`snapshot-*`).
  - `isf_cr_snapshots` stores realtime effective ISF/CR with CI/confidence/factor trace.
  - `isf_cr_evidence` stores extracted ISF/CR evidence windows and quality weights.
  - `isf_cr_model_state` stores active hourly base model.
  - `physio_context_tags` stores user/system context tags (`stress/illness/hormonal/...`).
- Keep Room schema transition from `v9 -> v10` non-destructive:
  - explicit migration creates `isf_cr_*` and `physio_context_tags` tables/indexes,
  - destructive fallback is allowed only for legacy start versions (`1..8`).
- `HybridPredictionEngine` accepts optional runtime ISF/CR override from physiology-aware snapshot:
  - `ACTIVE` confident mode applies full override (`blendWeight=1.0`),
  - `SHADOW` confident mode applies conservative soft-blend (`~0.25..0.65`) to improve forecast sensitivity without full controller promotion,
  - low-confidence snapshots remain blocked by fallback chain.
- Shadow auto-activation (optional) may promote ISF/CR mode from `SHADOW` to `ACTIVE` only when KPI thresholds pass on recent `isfcr_shadow_diff_logged` audit history.
- Optional shadow auto-activation can additionally enforce daily quality gate (24h):
  - requires latest daily report metrics (`daily_report_mae_30m`, `daily_report_mae_60m`, `daily_report_matched_samples`),
  - requires CI calibration bounds (`daily_report_ci_coverage_30m_pct`, `daily_report_ci_coverage_60m_pct`, `daily_report_ci_width_30m`, `daily_report_ci_width_60m`),
  - enforces daily ISF/CR data-quality risk gate from telemetry (`daily_report_isfcr_quality_risk_level`) and blocks promotion at settings-backed threshold (`2=MEDIUM` or `3=HIGH`, default `3`),
  - if numeric risk level is missing, runtime falls back to parse latest `daily_report_isfcr_quality_risk` text label (`LOW/MEDIUM/HIGH`),
  - risk gate audit includes risk-level source (`numeric` / `text_fallback` / `missing_or_unknown`) for diagnostics,
  - requires 24h hypo-rate below configured bound,
  - logs `isfcr_shadow_quality_gate_evaluated` before any promotion.
- Shadow auto-activation additionally enforces rolling replay quality gate (`14d/30d/90d`):
  - consumes telemetry `rolling_report_{14|30|90}d_*` (MAE/CI/matched samples for `30m/60m`),
  - requires configured minimum available rolling windows (`1..3`) and all available windows to pass relaxed thresholds,
  - rolling relax factors (`MAE/CI-coverage/CI-width`) are settings-backed and clamped before runtime use,
  - logs `isfcr_shadow_rolling_gate_evaluated` with per-window diagnostics.
- Shadow auto-activation also enforces day-type stability gate from realtime ISF/CR audit:
  - uses `hourWindow*SameDayType` and `hourWindow*Evidence` diagnostics from `isfcr_realtime_computed`,
  - blocks promotion when same-day-type ratio is too low or day-type sparse-rate is too high,
  - logs `isfcr_shadow_day_type_gate_evaluated` with reason and aggregated ratios.
- Shadow auto-activation additionally enforces sensor-quality stability gate from realtime ISF/CR audit:
  - uses `qualityScore`, `sensorFactor`, `wearConfidencePenalty`, `sensorAgeHours` diagnostics from `isfcr_realtime_computed`,
  - uses `sensorQualitySuspectFalseLow` / reason `sensor_quality_suspect_false_low` to aggregate false-low instability rate,
  - blocks promotion when mean quality/sensor factor degrades, wear penalty is too high, sensor-age-high rate spikes, or suspect-false-low rate is above threshold,
  - logs `isfcr_shadow_sensor_gate_evaluated` with reason and aggregated sensor metrics (including false-low rate).
- Sensor-quality gate thresholds are user-configurable from Settings (`ISF/CR auto-activation`) with safe clamps in persistence/runtime.
- Day-type stability gate thresholds are user-configurable from Settings (`ISF/CR auto-activation`) with safe clamps in persistence/runtime.
- CR evidence integrity thresholds are user-configurable from Settings (`ISF/CR engine`):
  - max allowed CGM gap in meal-window (`cr max gap minutes`),
  - max allowed sensor-blocked telemetry share,
  - max allowed UAM-ambiguity telemetry share,
  with safe clamps before persistence/runtime mapping.
- Daily forecast report telemetry also publishes uncertainty calibration metrics per horizon:
  - `daily_report_ci_coverage_{5|30|60}m_pct`,
  - `daily_report_ci_width_{5|30|60}m`,
  enabling CI coverage/width tracking in Analytics and activation diagnostics.
- Daily forecast report telemetry also publishes rolling replay KPI windows:
  - `rolling_report_{14|30|90}d_*` (matched samples + MAE/RMSE/MARD/Bias + CI coverage/width for 5/30/60),
  enabling medium/long-horizon drift tracking beyond 24h in Analytics.
- Daily forecast report telemetry also publishes replay factor-regime payload:
  - `daily_report_replay_factor_regime_json` with per-horizon core-factor buckets (`LOW/MID/HIGH`) and per-bucket `mean/MAE/MARD/Bias/n`,
  enabling direct detection of high-load factor zones where forecast error escalates.
- Daily forecast report telemetry also publishes replay core-factor pair regimes:
  - `daily_report_replay_factor_pair_json` with per-horizon pair quadrants (`LOW/HIGH` × `LOW/HIGH`) for
    `COB×IOB`, `COB×UAM`, `COB×CI`, `IOB×CI`, `UAM×CI`,
    including `meanA/meanB/MAE/MARD/Bias/n`,
  - plus per-horizon quick summaries:
    - `daily_report_replay_top_pair_{5|30|60}m`,
    - `daily_report_replay_top_pair_hint_{5|30|60}m`,
  enabling detection of combined-factor zones where error rises only when factors co-occur.
- Daily forecast report telemetry also publishes replay error-cluster diagnostics:
  - `daily_report_replay_error_cluster_json` with per-horizon top day-type hour-clusters (`dayType`, `hour`, `MAE`, `MARD`, `Bias`,
    mean `COB/IOB/UAM/CI width`, dominant normalized factor),
  - plus per-horizon quick summaries:
    - `daily_report_replay_error_cluster_{5|30|60}m`,
    - `daily_report_replay_error_cluster_hint_{5|30|60}m`,
  enabling faster triage of stable high-error windows (beyond single top-miss points).
- Daily forecast report telemetry also publishes replay weekday/weekend gap diagnostics:
  - `daily_report_replay_daytype_gap_json` with per-horizon strongest `WEEKDAY` vs `WEEKEND` hour gaps
    (`ΔMAE/ΔMARD`, weekday/weekend MAE & sample counts, worse-day-type factor context),
  - plus per-horizon quick summaries:
    - `daily_report_replay_daytype_gap_{5|30|60}m`,
    - `daily_report_replay_daytype_gap_hint_{5|30|60}m`,
  enabling direct triage of hour windows where one day-type systematically underperforms.
- ISF/CR runtime diagnostics must include dropped evidence reason counters (per extractor reason code) and expose them in both audit payload (`droppedReasons`) and Analytics diagnostics UI.
- Realtime ISF/CR sensor-quality false-low telemetry (`sensor_quality_suspect_false_low`) is treated as ambiguity signal:
  - adds explicit reason-code in snapshot diagnostics,
  - reduces confidence/quality and widens CI bounds in the same cycle.
- Analytics Quality tab must expose dropped evidence reason summaries aggregated over `24h` and `7d` audit windows to support data-quality triage.
- Analytics Quality tab must expose wear-impact summaries aggregated over `24h` and `7d` (`set/sensor age`, wear high-rate, mean wear factors, ambiguity/penalty) using realtime ISF/CR audit metadata.
- ISF/CR realtime computation applies hour-window evidence minimums (`minIsfEvidencePerHour` / `minCrEvidencePerHour`) around local hour (`±1h`), with explicit diagnostics (`hourWindow*`, `min*`) and low-confidence fallback reasons when minima are not met.
- Hour-window minima are configurable from app settings and propagated through `AppSettings -> IsfCrSettings` (bounded range `0..12`) for runtime tuning without code changes.
- ISF/CR realtime evidence blending is day-type aware:
  - weighted recent evidence applies additional weight for same day type (`WEEKDAY/WEEKEND`) and reduced weight for opposite day type,
  - runtime reasons include `isf_day_type_evidence_sparse` / `cr_day_type_evidence_sparse` when hour-window evidence exists but same day-type evidence is absent.
- Quality analytics for ISF/CR include day-type stability signals:
  - same-day-type ratio in hour-window evidence,
  - frequency of day-type sparsity flags,
  alongside wear-impact diagnostics.

## Open architecture questions
- Formal plugin contract for adding new prediction engines without touching automation repository.
- Unified backend lint/typecheck standards to match Android quality gate strictness.
