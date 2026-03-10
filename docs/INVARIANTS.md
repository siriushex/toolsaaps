# INVARIANTS

## Safety invariants
1. Kill switch blocks automatic actions only; manual actions remain available.
2. No automatic action is sent when data freshness/sensor policy blocks execution.
3. Every outbound action must have idempotency semantics.
4. Temp target values are always clamped to configured hard safety bounds.
5. Adaptive runtime base target is forced to `4.2 mmol/L` when `cob_grams >= 20`.
6. Activity spike protection may temporarily override adaptive temp target into `7.7..8.7 mmol/L`; recovery back to base is allowed only after sustained low-load signal.
7. Automatic outbound `temp_target` writes must obey a repository-level duplicate throttle:
   - repeated or near-identical targets are blocked inside `30 minutes`,
   - materially changed targets may pass immediately,
   - bypass is allowed only for explicit manual commands carrying the manual idempotency prefix.

## Prediction invariants
1. Forecast output must include 5m/30m/60m horizons.
2. Prediction values and CI are bounded to physiologic app clamp range.
3. Legacy mode behavior must remain stable when enhanced flags are disabled.
4. Enhanced local prediction must derive trend/volatility/UAM inputs from a canonical 5-minute CGM series rather than directly from raw minute-level points.
5. Forecast/runtime computation must be strictly causal: therapy events with `ts > prediction_now` must not influence prediction or replay metrics.
6. UAM contribution must not be double-counted with positive residual trend when UAM is active.
7. Synthetic/exported UAM carbs must never become announced-carb ground truth for forecast physiology or ISF/CR learning.
8. External/AAPS raw `COB` is a reference signal only; runtime `effective COB` used by forecast/controller must subtract residual synthetic `UAM_ENGINE` carbs before merge so exported UAM does not re-enter prediction through `COB` bias.
9. Enhanced V3 Kalman state must treat therapy and bounded historical UAM as known input before residual AR is fitted.
10. Recent forecast calibration must remain causal:
   - bias and CI width may use only already-matured forecast-vs-actual pairs inside the calibration lookback,
   - CI width calibration is based on empirical absolute residual quantiles, not future actuals.
11. Circadian pattern influence is a bounded secondary prior, not a replacement for physiology:
   - it may blend only after the core path simulation is produced,
   - maximum weights remain horizon-limited (`5m <= 0.10`, `30m <= setting`, `60m <= setting`),
   - it must be multiplied by pattern confidence and acute attenuation.
11. Active UAM event inference is the preferred runtime meal contour for forecast UAM when available:
   - forecast UAM steps may consume inferred `ingestionTs/carbs/confidence` as virtual-meal hint,
   - telemetry/controller must use the resolved unified UAM runtime state instead of arbitrating separate inference/forecast flags ad hoc.
12. Carbohydrate absorption is event-specific and must use one of three classes: `FAST`, `MEDIUM`, `PROTEIN_SLOW`.
13. Food catalog baseline sizes are fixed for current version: fast=100, medium=100, protein=50.
14. Carb class resolution order is deterministic: explicit payload -> catalog text -> glucose pattern -> medium fallback.
15. Runtime forecast bias from telemetry is horizon-aware and must keep output in physiologic clamps:
   - explicit metabolic bias uses normalized `cob_grams`/`iob_units`,
   - bounded context-bias uses normalized physiology factors (`set/sensor/activity/dawn/stress/hormone/steroid`) plus current pattern window,
   - low sensor quality can widen CI and limit displacement from current glucose.
16. UAM inference may run on minute-level CGM input, but UAM action/export cycle is bucketed to 5-minute cadence.
17. Exported inferred carbs must be tagged (`UAM_ENGINE|id|seq|ver|mode`) and deduplicated against remote entries before sending.
18. Physiology-aware ISF/CR snapshot may override prediction sensitivity only when confidence is above threshold; otherwise fallback chain is mandatory.
19. Low-confidence ISF/CR fallback is metric-aware, not all-or-nothing:
   - metrics with weak or absent evidence must fall back independently,
   - metrics with strong compensation-derived global evidence may stay on bounded runtime candidate even while snapshot mode remains `FALLBACK`.
   - realtime ISF/CR evidence extraction must ignore `temp_target` and other control-only therapy rows; only physiologically relevant insulin/carbs plus set/sensor markers may enter the fast path.
18. Shadow-first mode is default for physiology-aware ISF/CR runtime; low-confidence snapshots must be marked `FALLBACK`.
19. Optional shadow auto-activation may switch `SHADOW -> ACTIVE` only after KPI-gate pass (sample count, mean confidence, mean absolute ISF/CR deltas) and must emit audit trace.
20. If daily quality gate is enabled, `SHADOW -> ACTIVE` promotion is additionally blocked unless latest 24h MAE(30m/60m), matched sample count, hypo-rate, CI coverage(30m/60m), and CI width(30m/60m) constraints pass.
21. `SHADOW -> ACTIVE` promotion is additionally blocked when daily ISF/CR data-quality risk level is at or above the configured threshold (`isfCrAutoActivationDailyRiskBlockLevel`, `2..3`, default `3`).
22. Daily ISF/CR risk gate must resolve risk level robustly: prefer numeric telemetry (`daily_report_isfcr_quality_risk_level`), fallback to latest text label (`daily_report_isfcr_quality_risk` -> `LOW/MEDIUM/HIGH`) when numeric is missing.
23. Daily risk gate diagnostics must expose resolved risk-level source (`numeric` / `text_fallback` / `missing_or_unknown`) in audit metadata for explainability.
24. `SHADOW -> ACTIVE` promotion is blocked when day-type stability gate fails: low same-day-type ratio or high day-type sparse-rate on recent realtime ISF/CR audit windows.
25. `SHADOW -> ACTIVE` promotion is additionally blocked when rolling replay quality gate fails: fewer than required available windows (`14d/30d/90d`) or any available rolling window failing quality bounds.
26. Realtime ISF/CR blending with evidence is blocked when local hour-window evidence (`±1h`) is below configured minima; diagnostics must expose hour-window counts and minima, and reasons must include `isf_hourly_evidence_below_min` / `cr_hourly_evidence_below_min`.
27. User-configurable hourly evidence minima are clamped to safe bounds (`0..12`) before persistence and before runtime engine usage.
28. ISF/CR confidence model must penalize high set/sensor wear age, high context ambiguity (`UAM/stress/manual tags`), and `sensor_quality_suspect_false_low`; CI must widen under these penalties.
29. ISF/CR evidence weighting must include wear-aware multiplier from latest infusion-set/sensor change markers; missing markers must keep neutral multiplier `1.0`.
30. ISF/CR realtime recent-evidence weighting must be day-type aware (`WEEKDAY/WEEKEND`): opposite day-type evidence gets reduced influence and day-type sparsity reasons are emitted when relevant.
27. Telemetry-derived ISF/CR values are priors/fallbacks, not independent sample streams: repeated telemetry updates must contribute at most one effective telemetry prior per family (`ISF`, `CR`) to any single estimate.
28. In `FALLBACK_IF_NEEDED`, sufficiently populated local ISF/CR history must remain source of truth; global/telemetry priors may stabilize only sparse windows and must not overwrite dense local hour/segment estimates.
29. Local forecast runtime must prefer the shared `ProfileEstimator` history pipeline for ISF/CR; any inline legacy heuristic may be used only as fallback when shared estimation yields no value.
30. Nightscout and local Nightscout treatment import must be payload-aware and causal: therapy rows may be normalized from label + payload (`insulin/carbs/...`), but only from data present in the imported treatment itself; no future treatment enrichment is allowed.
31. `profile_segment_estimates` rebuild is atomic and latest-only: analytics recalculation must clear old segment rows before inserting the current set, so runtime never mixes stale and fresh segment windows.
32. `profile_estimates` rebuild must delete legacy telemetry-polluted rows (`telemetryIsfSampleCount > 1` or `telemetryCrSampleCount > 1`) before publishing new profile snapshots.
33. App startup must not block `Overview / Forecast / Safety` on profile analytics self-heal:
   - rebuild is mandatory when active profile is missing, telemetry-polluted, or older than `12h`,
   - rebuild is mandatory when `profile_segment_estimates` are missing or stale relative to the active profile,
   - the rebuild must be route-lazy (`Analytics` / `AI Analysis`) and use the profile-only rebuild path capped to `90d`, so ordinary launch does not pay a full analytics repair cost.
33. Manual physiology quick-tags must be normalized before persistence (`hormonal_phase -> hormonal`, `steroid -> steroids`) to keep context model token matching deterministic.
34. `steroids` and `dawn` manual tags must be reflected in runtime factor trace (`manual_steroid_tag`, `manual_dawn_tag`, `steroid_factor`, `dawn_tag_factor`) and in ambiguity-aware confidence penalties.
35. Physiology tags must support point-close operation by tag id; closing one tag must not mutate unrelated active tags.
36. CR evidence extraction must reject low-integrity meal windows:
   - bolus linkage window is asymmetric (`-20m..+30m`) around meal,
   - gross CGM gaps (`max gap > 30m`) are hard-drop,
   - high `sensor_blocked` telemetry windows are hard-drop,
   - high UAM ambiguity telemetry windows are hard-drop.
   Rejections must emit explicit dropped-reason counters in diagnostics/audit.
37. CR evidence integrity thresholds are settings-backed and clamped before persistence/runtime:
   - max CGM gap (`10..60` minutes),
   - max sensor-blocked rate (`0..100%`),
   - max UAM ambiguity rate (`0..100%`).
35. Runtime DIA must be profile-derived:
   - selected insulin profile provides the base duration from its action curve,
   - optional daily real-profile estimate may refine effective DIA through profile-based onset/shape scaling,
   - raw external `dia_hours` telemetry may be stored for audit, but must not override runtime insulin action on its own.
36. Each automation forecast cycle must emit factor coverage audit (`forecast_factor_coverage`) so missing runtime drivers can be diagnosed (`ISF/CR`, DIA, COB/IOB, UAM, sensor/activity/context, pattern/history, and applied bias stages).
37. AI daily optimizer may tune only forecast calibration scales (`gain/maxUp/maxDown`) and must stay within bounded clamps before runtime use.
38. AI daily optimizer output must never bypass deterministic safety/rule engine and must never generate direct therapy commands.
39. Runtime AI calibration tuning is valid only for fresh optimizer payloads and quality-gated daily reports:
   - optimizer payload age must be bounded (`<= 36h`),
   - matched sample count must pass minimum threshold,
   - high ISF/CR data-quality risk blocks tuning application.
40. Nightscout treatment import must preserve payload aliases required for real bolus/carbs reconstruction:
   - `enteredInsulin <- enteredInsulin|bolusUnits|insulinUnits`,
   - `enteredCarbs <- enteredCarbs|mealCarbs|grams`,
   and normalization from generic Nightscout labels must depend only on those preserved payload fields.
41. Nightscout/local-Nightscout treatment normalization must be shared and deterministic: the same payload must resolve to the same therapy `type` whether it entered through remote sync, local loopback POST, repair pass, or replay.
42. Treatment repair may only reclassify therapy rows from persisted payload and source metadata; it must never synthesize missing insulin/carbs values or infer future events.
43. `profile_segment_estimates` recomputation is replace-all, not append: stale segment rows must be deleted before inserting the current recalculated set.
44. `profile_estimates` cleanup must remove telemetry-polluted legacy rows before writing the rebuilt profile snapshot, so repeated telemetry cannot inflate confidence/sample counts across recalculations.
45. When real insulin-like Nightscout therapy history is below bootstrap minimum, treatment sync must keep running widened `created_at` recovery fetches until sufficient real insulin rows are present; narrow incremental windows alone are not allowed to become the terminal state.
46. Effective runtime DIA must stay anchored to the selected insulin profile and may deviate only within `50%..150%` of that profile duration, even when a real-profile fit is available.
47. Real-profile DIA refinement must be slow-moving: recomputation cadence is `72h` for stable estimates, with earlier recompute allowed only for algorithm-version changes or empty fallback templates.
48. Telemetry must preserve both DIA layers when available:
   - `dia_profile_hours` for the selected profile default,
   - `dia_real_raw_hours` for the historical fit before runtime blending,
   - `dia_effective_hours` for the bounded runtime value actually used by prediction.
49. Circadian prior must degrade safely under acute or low-quality conditions:
    - `abs(delta5) > 0.30 mmol/5m`, `COB >= 15`, `UAM active`, or `IOB >= 3` force acute attenuation,
    - dormant low-confidence `UAM` without current rise/absorption (`low confidence`, low `gAbs`, low `uci0`, calm `delta5`) may only soft-attenuate circadian prior (`0.7`) instead of full acute suppression,
    - `sensor_quality_suspect_false_low` or stale/sensor-blocked state disable circadian prior entirely.
50. Requested `WEEKDAY/WEEKEND` circadian segments must fall back to `ALL` when coverage is insufficient; fallback reason and segment source must be persisted in snapshot metadata and shown in analytics.
49a. Replay-aware circadian bucket sufficiency is day-type aware for `15m` slot density:
    - `WEEKDAY` replay buckets require at least `5` samples,
    - `WEEKEND` replay buckets require at least `4` samples,
    - `ALL` replay buckets require at least `6` samples,
    in addition to existing `coverageDays`, `winRate`, `maeImprovement`, and acute attenuation gates.
49b. Replay bucket harm classification must stay conservative:
    - buckets are `HARMFUL` only for clear regression (`maeImprovement >= 0.05`) or for weak-but-positive regression with strongly poor win-rate (`maeImprovement > 0.02` and `winRate < 0.40`),
    - near-neutral noisy buckets must stay `NEUTRAL`, not `HARMFUL`, so they do not zero out circadian prior without evidence of real degradation.
51. Circadian analytics state must self-heal before analytics routes/runtime depend on it:
    - missing `circadian_slot_stats`, `circadian_transition_stats`, `circadian_pattern_snapshots`, or `circadian_replay_slot_stats` must trigger a bounded circadian rebuild,
    - stale circadian snapshots/replay stats (`> 12h`) must trigger the same rebuild,
    - qualified replay buckets with legacy zero-quality signature (`sampleCount >= 8`, minimum day coverage, `maeImprovementMmol == 0`, `winRate == 0`) must trigger the same rebuild,
    - self-heal must use a capped lookback (`<= 21d`), read only a circadian-specific telemetry whitelist, and must not force a full profile/ISF-CR rebuild,
    - self-heal failure must degrade to audit-visible warning, not process crash,
    - `Overview` cold-start must not eagerly trigger circadian self-heal.

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
28. Any consumer of `glucose_samples` that derives runtime decisions, analytics, local Nightscout payloads, replay, or quality gates must deduplicate rows by `timestamp` through the shared `GlucoseSanitizer` winner policy before use.
29. Analytics ISF/CR charts and current-value tiles must keep three distinct sources visible:
   - compensation-derived evidence,
   - Copilot fallback runtime,
   - AAPS raw telemetry.
   These sources must never be visually collapsed into a single line/value in Analytics.
30. Analytics ISF/CR charts must keep per-line visibility toggles for all three primary sources and optional overlay toggles for `COB`, `UAM`, and `activity`; disabling a line must only affect rendering, never stored history or fallback selection.
31. Compensation-derived ISF evidence must ignore synthetic `UAM_ENGINE` carbs when checking correction-window carb contamination; tagged synthetic carbs are not allowed to invalidate a real correction sample via `carbsAround`.
32. Realtime ISF/CR confidence must be computed from separate `ISF`/`CR` evidence coverage, with additional support from strong global/hour-window evidence and same-day-type ratios; one-metric-only evidence must still incur a cross-metric confidence penalty.
33. A realtime ISF/CR snapshot may only leave `FALLBACK` into `SHADOW` below the main threshold when both metrics have keepable compensation-derived candidates, `sensor_quality_suspect_false_low` is clear, and runtime application remains disabled.
34. Realtime ISF/CR extraction must not be capped by recent `temp_target` spam: if the raw therapy query saturates its limit and too few relevant insulin/carb/set/sensor rows remain after filtering, the repository must widen the therapy scan before computing evidence.
35. Circadian analytics storage uses `15-minute` slots and keeps three related tables in sync:
    - `circadian_slot_stats`,
    - `circadian_transition_stats`,
    - `circadian_pattern_snapshots`,
    - `circadian_replay_slot_stats`.
    Slot/transition rows are replace-all per recalculation window, and snapshots must include stable window, recency window, fallback reason, and segment source.
36. Circadian analytics UI must expose separate sections for `WEEKDAY`, `WEEKEND`, and `ALL` fallback when available, plus explicit window selection (`5d/7d/10d/14d`).
37. Circadian analytics UI must also expose a direct state card with raw circadian storage diagnostics (`READY / PARTIAL / STALE / EMPTY`, slot/transition/snapshot/replay counts, latest snapshot/replay timestamps, active source mapping) so phone-side validation does not depend on copying a live WAL-backed DB.
37. Runtime telemetry must expose circadian prior diagnostics (`confidence`, `delta30`, `delta60`, `residual bias`, `segment source`, attenuation/stale flags`) so forecast behavior can be audited without reading pattern tables directly.
38. Circadian replay summary shown in `Analytics` and `AI Analysis` must be causal and comparable:
    - it scores `24h/7d` windows from stored forecasts plus deduplicated glucose history,
    - it compares `baseline` against `baseline + circadian prior`,
    - if a stored row already includes `|circadian_v1` or `|circadian_v2`, baseline must be reconstructed by inverting the same circadian blend that was used at write-time; `circadian_v2` inversion must reuse replay-aware horizon weights rather than a neutral fallback weight.
39. Circadian runtime bias for `30m/60m` must be replay-aware:
    - replay multiplier is derived from persisted `circadian_replay_slot_stats`,
    - bootstrap fit of `circadian_replay_slot_stats` must evaluate the circadian template without runtime replay gating and only on `low-acute` rows where circadian produced a real non-zero shift,
    - if the exact replay slot is `INSUFFICIENT`, runtime may use only bounded neighbouring replay slots within `±2` `15-minute` slots (`±30 minutes`) and must down-weight them by slot distance; this fallback is allowed only for replay quality, not for replacing the underlying glucose-template slot,
    - `HARMFUL` replay buckets must not add replay bias and must hard-cap circadian weight to weak mode,
    - `INSUFFICIENT` replay buckets must not promote weight above neutral fallback behavior,
    - slot stability and horizon-specific transition quality must also reduce `30m/60m` circadian weight when the time-of-day template is noisy.
40. Circadian replay may apply a small positive helpful-bucket boost only under strict conditions:
    - the bucket status must already be `HELPFUL`,
    - `sampleCount >= 12`,
    - `acuteAttenuation >= 0.85`,
    - `winRate` and `maeImprovementMmol` must pass horizon-specific thresholds,
    - the resulting replay multiplier may exceed `1.0` only in bounded form (`<= 1.25`) and must still respect all final horizon shift clamps.
41. Circadian `30m/60m` integration may apply bounded median reversion only when:
    - current glucose is outside the historical slot `p25..p75` band,
    - sensor state is not stale/suspect-false-low,
    - replay bucket is not `HARMFUL`,
    - and the resulting reversion remains inside the horizon clamp (`30m <= 0.18 mmol`, `60m <= 0.32 mmol` before final blend clamps).
42. `AI Analysis` user-facing window selection is restricted to `3d`, `5d`, `7d`, and `30d`; source/status/week filters are not allowed on that screen.
43. `AI tuning blocked` must reflect the real optimizer gate reason:
    - transport timeout,
    - empty/invalid structured JSON,
    - missing API key,
    - authorization/rate-limit/server error,
    - or explicit quality/apply gate failure.
    Generic `BLOCKED` without the concrete cause is not acceptable.
44. OpenAI optimizer parsing must accept all supported `Responses API` structured-output shapes used by the app (`output_text`, nested text content, `text.value`, `parsed`, `json`, `arguments`) before concluding that structured output is empty.
45. `AI Analysis` must keep the AI chat composer above the reporting cards and support three interaction modes from the same stateful surface:
    - plain text chat,
    - multimodal attachments (`image/*` directly, text-like files via bounded local preview),
    - voice mode (`RECORD_AUDIO` -> OpenAI transcription -> optional OpenAI TTS reply).
46. Voice mode must remain advisory-only:
    - transcription and TTS are transport features,
    - they may not bypass the normal AI chat prompt construction,
    - they may not emit therapy commands or hidden outbound actions.
47. `AI Analysis` refresh is lazy by route:
    - opening `Overview` must not eagerly trigger `cloud jobs`, `analysis history/trend`, or `local daily report` warm-up,
    - those refreshes may start when the user navigates to `AI Analysis`,
    - startup optimization must not change the advisory-only semantics of the AI contour.
48. Primary navigation must remain `Overview / Forecast / Analytics / Safety`; `Analytics` must not be demoted back to overflow while it is used as a daily operational screen.
49. `UAM` must not exist as a separate primary tab:
    - `UAM` summary and event actions belong inside `Audit Log`,
    - removing the dedicated tab must not remove any existing UAM event actions (`mark correct`, `mark wrong`, `merge`, `export`).
50. `Overview` must expose an operator-facing quick `Base target` control:
    - target changes must stay bounded by configured safety min/max,
    - control must be reachable without opening `Settings` or `Safety`,
    - replacing the app-health banner on `Overview` must not hide stale-data or kill-switch state.
51. Replay-aware circadian runtime must be allowed to override a harmful exact 15-minute replay slot with a healthier local neighborhood:
    - only if the neighborhood improves bucket class (`HARMFUL -> NEUTRAL/HELPFUL`, or `NEUTRAL -> HELPFUL` with no weaker coverage/sample support),
    - only within the same day-type/window/horizon contour,
    - and only as a runtime prior-selection rule, not by mutating stored replay-fit rows.
52. `LOW_ACUTE` replay diagnostics must be conservative:
    - missing telemetry may not be silently converted into zero-valued `delta5/COB/IOB/UAM`,
    - if a replay row lacks current dynamics or metabolic telemetry, it must not be classified as `LOW_ACUTE`,
    - otherwise `LOW_ACUTE` analytics will overstate circadian quality and distort `30m/60m` tuning decisions.

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
13. In OpenAI mode, daily analysis must still produce local report; optimizer failure must degrade safely to local-only behavior and persist explicit failure telemetry (`daily_report_ai_opt_error`).
14. Daily optimizer telemetry must always publish neutral runtime keys on failure (`apply_flag=0`, scales=`1.0`) to prevent stale previous APPLY payload from leaking into runtime.
