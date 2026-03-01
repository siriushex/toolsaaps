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
- `domain/rules`: adaptive/post-hypo/pattern/segment rules + arbitration.
- `domain/safety`: global policy guardrails.
- `ui`: Compose screens and state wiring.
- `service/scheduler`: app container, local NS emulation, workers.
- `service/local activity collector`: sensor listener, local activity aggregation, telemetry writes.

## Backend modules (logical)
- API endpoints for sync/predict/rules/actions/analysis/replay.
- Scheduler jobs for daily analysis and weekly retraining.
- Persistence layer via SQLAlchemy.

## Integration contracts
- Forecast horizons required in app loop: 5m, 30m, 60m.
- Telemetry units in app domain: mmol/L for glucose and derived glucose deltas.
- Action channel priority: Nightscout API primary; local fallback optional.
- Temp target command must stay in hard range and pass policy checks before sending.
- Timestamps written to local DB must be normalized and non-zero (`>0`) before persist.
- `cob_grams` and `iob_units` telemetry are allowed to bias local runtime forecasts before rule arbitration.
- Activity telemetry is stored in canonical keys when available:
  - `steps_count`,
  - `activity_ratio`,
  - `distance_km`,
  - `active_minutes`,
  - `calories_active_kcal`.
- If `cob_grams >= 20`, adaptive runtime base target is forced to `4.2 mmol/L` (within hard target bounds) for automation decisions.

## Architectural decisions
- Keep prediction engine strategy switchable via flags (legacy vs enhanced versions).
- Keep insulin action profile configurable and persisted in settings; default profile is NOVORAPID.
- Keep controller/rule execution idempotent by time buckets and command keys.
- Keep carbohydrate absorption event-aware:
  - food catalog classes (`FAST`, `MEDIUM`, `PROTEIN_SLOW`),
  - event classification by payload text/cross-language aliases and glucose pattern fallback,
  - dynamic cumulative absorption curve per event for therapy and UAM residual calculations.
- Keep residual carbs visible in diagnostics (`now`, `30m`, `60m`, `120m`) for model explainability.
- Keep COB/IOB influence explicit and deterministic:
  - forecast bias is horizon-aware (`5/30/60`) and bounded,
  - adaptive controller receives normalized `COB/IOB` telemetry as additional control inputs.
- Keep forecast history long enough for month/year analysis (`~400 days` retention in app DB).
- Keep analytics snapshots historically:
  - `pattern_windows` append snapshots and query latest per (`dayType`,`hour`) for runtime.
  - `profile_segment_estimates` append snapshots and query latest per (`dayType`,`timeSlot`) for runtime.
  - `profile_estimates` stores both `active` record and timestamped snapshots (`snapshot-*`).

## Open architecture questions
- Formal plugin contract for adding new prediction engines without touching automation repository.
- Unified backend lint/typecheck standards to match Android quality gate strictness.
