# INVARIANTS

## Safety invariants
1. Kill switch blocks automatic actions only; manual actions remain available.
2. No automatic action is sent when data freshness/sensor policy blocks execution.
3. Every outbound action must have idempotency semantics.
4. Temp target values are always clamped to configured hard safety bounds.
5. Adaptive runtime base target is forced to `4.2 mmol/L` when `cob_grams >= 20`.

## Prediction invariants
1. Forecast output must include 5m/30m/60m horizons.
2. Prediction values and CI are bounded to physiologic app clamp range.
3. Legacy mode behavior must remain stable when enhanced flags are disabled.
4. UAM contribution must not be double-counted with positive residual trend when UAM is active.
5. Carbohydrate absorption is event-specific and must use one of three classes: `FAST`, `MEDIUM`, `PROTEIN_SLOW`.
6. Food catalog baseline sizes are fixed for current version: fast=100, medium=100, protein=50.
7. Carb class resolution order is deterministic: explicit payload -> catalog text -> glucose pattern -> medium fallback.
8. Runtime forecast bias from telemetry uses only normalized `cob_grams`/`iob_units`, is horizon-aware, and must keep output in physiologic clamps.

## Data invariants
1. Glucose and derived values in app domain use mmol/L.
2. Therapy payload parsing must tolerate key aliases but sanitize unsafe ranges.
3. Telemetry storage must preserve timestamp and source for auditability.
4. Persisted telemetry timestamps must be positive (`timestamp > 0`); zero/invalid timestamps are rejected or normalized.
5. Physical activity metrics must be normalized to canonical keys when present: `steps_count`, `activity_ratio`, `distance_km`, `active_minutes`, `calories_active_kcal`.
6. Forecast rows keep long retention (not short rolling window) to support month/year analysis.
7. Pattern and profile analytics must keep historical snapshots while runtime reads latest slices.
8. Local sensor activity ingestion requires `ACTIVITY_RECOGNITION` grant on Android 10+; without permission the collector must fail safe and not write synthetic values.

## Process invariants
1. Any behavior/contract change requires docs update (`ARCHITECTURE` and/or `INVARIANTS`).
2. Every implementation stage must append an entry to `AI_NOTES.md`.
3. No TODO/stub is allowed in delivered scope unless explicitly approved in plan.
