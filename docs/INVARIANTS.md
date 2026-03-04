# INVARIANTS

## Safety invariants
1. Kill switch blocks automatic actions only; manual actions remain available.
2. No automatic action is sent when data freshness/sensor policy blocks execution.
3. Every outbound action must have idempotency semantics.
4. Temp target values are always clamped to configured hard safety bounds.
5. Adaptive runtime base target is forced to `4.2 mmol/L` when `cob_grams >= 20`.
6. Activity spike protection may temporarily override adaptive temp target into `7.7..8.7 mmol/L`; recovery back to base is allowed only after sustained low-load signal.

## Prediction invariants
1. Forecast output must include 5m/30m/60m horizons.
2. Prediction values and CI are bounded to physiologic app clamp range.
3. Legacy mode behavior must remain stable when enhanced flags are disabled.
4. UAM contribution must not be double-counted with positive residual trend when UAM is active.
5. Carbohydrate absorption is event-specific and must use one of three classes: `FAST`, `MEDIUM`, `PROTEIN_SLOW`.
6. Food catalog baseline sizes are fixed for current version: fast=100, medium=100, protein=50.
7. Carb class resolution order is deterministic: explicit payload -> catalog text -> glucose pattern -> medium fallback.
8. Runtime forecast bias from telemetry is horizon-aware and must keep output in physiologic clamps:
   - explicit metabolic bias uses normalized `cob_grams`/`iob_units`,
   - bounded context-bias uses normalized physiology factors (`set/sensor/activity/dawn/stress/hormone/steroid`) plus current pattern window,
   - low sensor quality can widen CI and limit displacement from current glucose.
9. UAM inference may run on minute-level CGM input, but UAM action/export cycle is bucketed to 5-minute cadence.
10. Exported inferred carbs must be tagged (`UAM_ENGINE|id|seq|ver|mode`) and deduplicated against remote entries before sending.
11. Physiology-aware ISF/CR snapshot may override prediction sensitivity only when confidence is above threshold; otherwise fallback chain is mandatory.
12. Shadow-first mode is default for physiology-aware ISF/CR runtime; low-confidence snapshots must be marked `FALLBACK`.
13. Optional shadow auto-activation may switch `SHADOW -> ACTIVE` only after KPI-gate pass (sample count, mean confidence, mean absolute ISF/CR deltas) and must emit audit trace.
14. If daily quality gate is enabled, `SHADOW -> ACTIVE` promotion is additionally blocked unless latest 24h MAE(30m/60m), matched sample count, hypo-rate, CI coverage(30m/60m), and CI width(30m/60m) constraints pass.
15. `SHADOW -> ACTIVE` promotion is additionally blocked when daily ISF/CR data-quality risk level is at or above the configured threshold (`isfCrAutoActivationDailyRiskBlockLevel`, `2..3`, default `3`).
16. Daily ISF/CR risk gate must resolve risk level robustly: prefer numeric telemetry (`daily_report_isfcr_quality_risk_level`), fallback to latest text label (`daily_report_isfcr_quality_risk` -> `LOW/MEDIUM/HIGH`) when numeric is missing.
17. Daily risk gate diagnostics must expose resolved risk-level source (`numeric` / `text_fallback` / `missing_or_unknown`) in audit metadata for explainability.
18. `SHADOW -> ACTIVE` promotion is blocked when day-type stability gate fails: low same-day-type ratio or high day-type sparse-rate on recent realtime ISF/CR audit windows.
19. `SHADOW -> ACTIVE` promotion is additionally blocked when rolling replay quality gate fails: fewer than required available windows (`14d/30d/90d`) or any available rolling window failing quality bounds.
20. Realtime ISF/CR blending with evidence is blocked when local hour-window evidence (`±1h`) is below configured minima; diagnostics must expose hour-window counts and minima, and reasons must include `isf_hourly_evidence_below_min` / `cr_hourly_evidence_below_min`.
21. User-configurable hourly evidence minima are clamped to safe bounds (`0..12`) before persistence and before runtime engine usage.
22. ISF/CR confidence model must penalize high set/sensor wear age, high context ambiguity (`UAM/stress/manual tags`), and `sensor_quality_suspect_false_low`; CI must widen under these penalties.
23. ISF/CR evidence weighting must include wear-aware multiplier from latest infusion-set/sensor change markers; missing markers must keep neutral multiplier `1.0`.
24. ISF/CR realtime recent-evidence weighting must be day-type aware (`WEEKDAY/WEEKEND`): opposite day-type evidence gets reduced influence and day-type sparsity reasons are emitted when relevant.
25. Manual physiology quick-tags must be normalized before persistence (`hormonal_phase -> hormonal`, `steroid -> steroids`) to keep context model token matching deterministic.
26. `steroids` and `dawn` manual tags must be reflected in runtime factor trace (`manual_steroid_tag`, `manual_dawn_tag`, `steroid_factor`, `dawn_tag_factor`) and in ambiguity-aware confidence penalties.
27. Physiology tags must support point-close operation by tag id; closing one tag must not mutate unrelated active tags.
28. CR evidence extraction must reject low-integrity meal windows:
   - bolus linkage window is asymmetric (`-20m..+30m`) around meal,
   - gross CGM gaps (`max gap > 30m`) are hard-drop,
   - high `sensor_blocked` telemetry windows are hard-drop,
   - high UAM ambiguity telemetry windows are hard-drop.
   Rejections must emit explicit dropped-reason counters in diagnostics/audit.
29. CR evidence integrity thresholds are settings-backed and clamped before persistence/runtime:
   - max CGM gap (`10..60` minutes),
   - max sensor-blocked rate (`0..100%`),
   - max UAM ambiguity rate (`0..100%`).
30. `dia_hours` telemetry must affect insulin action in prediction engine when present: insulin cumulative progression is scaled by DIA/profile-duration ratio within safe bounds; absent/invalid DIA must revert to profile default behavior.
31. Each automation forecast cycle must emit factor coverage audit (`forecast_factor_coverage`) so missing runtime drivers can be diagnosed (`ISF/CR`, DIA, COB/IOB, UAM, sensor/activity/context, pattern/history, and applied bias stages).

## Data invariants
1. Glucose and derived values in app domain use mmol/L.
2. Therapy payload parsing must tolerate key aliases but sanitize unsafe ranges.
3. Telemetry storage must preserve timestamp and source for auditability.
4. Persisted telemetry timestamps must be positive (`timestamp > 0`); zero/invalid timestamps are rejected or normalized.
5. Physical activity metrics must be normalized to canonical keys when present: `steps_count`, `activity_ratio`, `distance_km`, `active_minutes`, `calories_active_kcal`.
6. Forecast rows keep long retention (not short rolling window) to support month/year analysis.
7. Pattern and profile analytics must keep historical snapshots while runtime reads latest slices.
8. Local sensor activity ingestion requires `ACTIVITY_RECOGNITION` grant on Android 10+; without permission the collector must fail safe and not write synthetic values.
9. ISF/CR evidence and snapshot retention must be time-bounded and cleanup must run automatically.
10. Room upgrade `v9 -> v10` must preserve user data via explicit migration; destructive migration is not allowed for this path.
11. ISF/CR runtime observability must persist dropped evidence reasons (coded counters) in audit for each realtime computation cycle.
12. Analytics quality diagnostics must include dropped evidence reason aggregates for rolling `24h` and `7d` windows when source audit data is available.
13. ISF/CR `useManualTags=false` must neutralize only manual-tag factors while keeping latent telemetry factors (for example stress/activity telemetry) available.
14. Realtime ISF/CR audit event (`isfcr_realtime_computed`) must include wear/context fields (`setAgeHours`, `sensorAgeHours`, wear factors, ambiguity, wear penalty) required for quality analytics.
15. Analytics quality diagnostics must include wear-impact aggregates for rolling `24h` and `7d` windows when realtime audit metadata is available.
16. Daily forecast reporting must publish rolling replay KPI telemetry for `14d/30d/90d` windows (`rolling_report_*`) with horizon metrics (`5m/30m/60m`) to support controlled-activation trend diagnostics.
17. Realtime ISF/CR audit diagnostics must preserve day-type context and same-day-type hour-window counters for explainability (`currentDayType`, `hourWindow*SameDayType`).
18. Analytics quality summaries must include day-type stability indicators (same-day-type ratio and sparse-flag rate) computed from realtime ISF/CR audit metadata.
19. Settings state must expose recent physiology tag journal entries (active and recently ended) for explainable manual context management.
20. ISF/CR shadow auto-promotion must be blocked when sensor-quality stability gate fails (`qualityScore/sensorFactor/wearConfidencePenalty/sensorAgeHighRate`) based on realtime audit metadata.
21. Sensor-quality gate thresholds used for shadow auto-promotion must be clamped before persistence/runtime (`score/factor/penalty: 0..1`, `age-high-rate: 0..100`).
22. Sensor-quality gate for shadow auto-promotion must also enforce `suspect_false_low_rate` bound derived from realtime ISF/CR diagnostics (`sensorQualitySuspectFalseLow` or `sensor_quality_suspect_false_low` reason code).
23. `maxSuspectFalseLowRatePct` threshold must be clamped before persistence/runtime (`0..100`) and surfaced in settings/audit metadata.
24. Day-type stability gate thresholds used for shadow auto-promotion must be clamped before persistence/runtime (`same-day ratio: 0..1`, `sparse-rate: 0..100`).
25. Realtime ISF/CR reasons must include `sensor_quality_suspect_false_low` when corresponding telemetry flag is active, and CI/confidence must become more conservative.
26. Shadow auto-promotion must emit rolling-gate audit with per-window diagnostics (`isfcr_shadow_rolling_gate_evaluated`) before promotion decisions.
27. Rolling-gate settings used in shadow auto-promotion must be clamped before persistence/runtime (`min windows: 1..3`, `MAE relax: 1.0..1.5`, `CI coverage relax: 0.70..1.0`, `CI width relax: 1.0..1.5`).

## Process invariants
1. Any behavior/contract change requires docs update (`ARCHITECTURE` and/or `INVARIANTS`).
2. Every implementation stage must append an entry to `AI_NOTES.md`.
3. No TODO/stub is allowed in delivered scope unless explicitly approved in plan.
4. A detailed local forecast quality report for the last 24 hours must be generated by daily analysis worker runs (with MARD/MAE/RMSE per horizon and stored report artifacts).
5. Daily quality report must include CI calibration per horizon (coverage % and mean CI width) and publish it to telemetry keys for runtime diagnostics.
6. Daily replay attribution must persist both factor contribution and factor coverage metrics; when telemetry is available, attribution factor-space includes `DIA`, `sensor age`, and `steroid` alongside existing `COB/IOB/UAM/CI` and physiology/context factors.
7. Daily replay must persist per-horizon (`5m/30m/60m`) top-miss context payload (timestamp, pred/actual/abs-error and main runtime factors) as structured telemetry/JSON for UI and audit diagnostics.
8. Daily replay must persist per-horizon core-factor regime diagnostics (`COB/IOB/UAM/CI`, buckets `LOW/MID/HIGH`) as structured telemetry/JSON with per-bucket `mean/MAE/MARD/Bias/n`.
9. Daily replay must persist per-horizon core-factor pair regime diagnostics (`COB×IOB`, `COB×UAM`, `COB×CI`, `IOB×CI`, `UAM×CI`) as structured telemetry/JSON with pair quadrants (`LOW/HIGH × LOW/HIGH`) and `meanA/meanB/MAE/MARD/Bias/n`.
10. Daily replay must also persist per-horizon top-pair quick summaries (`daily_report_replay_top_pair_{5|30|60}m` and `daily_report_replay_top_pair_hint_{5|30|60}m`) for fast UI diagnostics without full JSON parsing.
11. Daily replay must persist per-horizon day-type-aware error-cluster diagnostics (`WEEKDAY/WEEKEND` + hour-window + mean `COB/IOB/UAM/CI`) as structured telemetry/JSON and quick keys (`daily_report_replay_error_cluster_{5|30|60}m`, `daily_report_replay_error_cluster_hint_{5|30|60}m`).
12. Daily replay must persist per-horizon weekday/weekend gap diagnostics (`daily_report_replay_daytype_gap_json`) and quick keys (`daily_report_replay_daytype_gap_{5|30|60}m`, `daily_report_replay_daytype_gap_hint_{5|30|60}m`) so UI can expose asymmetric day-type error windows.
