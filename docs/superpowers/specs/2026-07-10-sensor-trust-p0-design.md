# Sensor Trust P0 Safety Design

**Status:** Proposed for implementation review
**Date:** 2026-07-10
**Scope:** Android application, deterministic safety and calibration paths

## Context

The 2026-07-03 through 2026-07-06 device-data audit identified two P0 defects:

1. Sensor quality was blocked during the 2026-07-07 00:00 cycle, but fallback
   rules could still send an automatic temporary target. Individual rules do not
   consistently enforce `RuleContext.sensorBlocked`.
2. Manual blood-glucose checks were validated against sensor quality at the
   blood-check timestamp instead of the lag-aligned CGM timestamp. A wide
   interpolation window then fabricated a value between an isolated false low
   and a later sample, allowing an invalid calibration check to remain `VALID`.

This design fixes those defects without adding a diagnostic claim about the
sensor, infusion set, insulin, or patient condition. The application remains an
advisory and safety-monitoring component. It must not calculate or issue a new
insulin dose as part of this work.

## Goals

- Fail closed for every automatic outbound action when sensor quality is
  blocked.
- Preserve the existing dedicated sensor-quality rollback as the only automatic
  action allowed to run while the sensor is blocked.
- Prevent synthetic UAM carbohydrate export while sensor quality is blocked,
  while retaining internal inference and audit data.
- Validate blood-glucose checks against the CGM sample and quality state at the
  lag-aligned timestamp.
- Reject unsafe interpolation and extreme blood-to-CGM mismatches before they
  affect a calibration model.
- Reassess existing checks so an already-invalid check cannot continue to
  influence the active model.
- Add regression coverage for the observed 2026-07-06/07 failure sequence.
- Add no recurring worker, database scan, or material CPU/memory cost.

## Non-goals

- Building the roller-coaster episode registry or cause classifier.
- Diagnosing infusion-set failure, insulin degradation, or sensor failure.
- Redesigning notifications or alert escalation.
- Changing prediction, IOB, COB, ISF, CR, or dosing formulas.
- Adding a new database status or schema migration.
- Changing manual commands initiated by an operator from the UI or an explicit
  command broadcast.
- Expanding automatic therapy behavior.

## Chosen Approach

Use a deterministic global safety gate for automatic outbound actions and a
conservative lag-aligned validation pipeline for calibration checks.

Per-rule sensor checks are insufficient because new or fallback rules can omit
them, as the incident demonstrated. The gate therefore belongs in the common
`SafetyPolicy`/`RuleEngine` path. Local checks may remain as defense in depth,
but they are not the safety boundary.

Machine learning is not appropriate for this P0 work. The defects are explicit
contract violations with deterministic inputs and expected outcomes. Evidence
fusion and shadow-mode classification remain the recommended later design for
distinguishing sensor mismatch from suspected delivery failure.

## Design

### 1. Global automatic-action sensor gate

Affected production paths include:

- `domain/safety/SafetyPolicy.kt`
- `domain/rules/RuleEngine.kt`
- `data/repository/AutomationRepository.kt`

`SafetyPolicy.evaluate` will accept `sensorBlocked`. `RuleEngine` will pass
`RuleContext.sensorBlocked` for every proposal. When true, the policy returns a
blocked result with reason `sensor_blocked` before an automatic action can be
dispatched.

This gate applies to every current and future proposal processed by
`RuleEngine`, including `SegmentProfileGuardRule` and
`PatternAdaptiveTargetRule`. Existing rule-local checks remain valid but are no
longer relied on for completeness.

The existing sensor-quality rollback is deliberately outside `RuleEngine` and
remains permitted. Its only purpose is to replace a potentially aggressive
automatic target with the established conservative rollback target. A cycle
that sends or attempts this rollback must not later send another automatic
therapy action from fallback rules in the same cycle. The immutable
`sensorBlocked` value computed for that cycle is the controlling signal for
both the rollback decision and all later gates; dispatch success must not be
used to clear it.

The adaptive keepalive path retains its explicit `sensorBlocked` check.

Synthetic UAM export is another automatic outbound path. Its internal inference
and audit processing may continue while blocked, but the effective export flag
must be:

```text
settings.enableUamExportToAaps && !sensorBlocked
```

This prevents untrusted CGM dynamics from being exported to AAPS as synthetic
carbohydrate events. `sensorBlocked` must therefore be passed into the current
UAM inference/export cycle. A small pure decision function may be extracted to
make this contract independently testable.

Manual operator commands are out of scope for this automatic gate. Their
existing confirmation, safety bounds, and audit behavior must remain unchanged.

The P0 outbound-path inventory is:

| Path | Sensor-blocked behavior |
| --- | --- |
| `RuleEngine` proposals | Blocked by `SafetyPolicy` |
| Adaptive keepalive temporary target | Blocked by its existing explicit gate |
| Synthetic UAM carbohydrate export | Export disabled; inference and audit continue |
| Sensor-quality rollback temporary target | Allowed safety exception |
| UI and explicit broadcast commands | Unchanged manual path |

Tests must cover every automatic row in this table. Any new automatic outbound
path added later must either pass through the common safety policy or document
and test a narrowly defined safety exception.

### 2. Lag-aligned calibration validation

Affected production path:

- `data/repository/GlucoseCalibrationRepository.kt`

For a blood check at `bloodTs`:

1. Resolve sensor lag using the existing estimator at `bloodTs`.
2. Compute `alignedTs = bloodTs + resolvedLag` using the repository's existing
   lag direction convention.
3. Match the raw CGM value around `alignedTs`.
4. Read telemetry quality around `alignedTs`, not `bloodTs`.
5. Apply status checks in the following order:
   - unresolved session: retain the existing unresolved-session behavior;
   - aligned quality blocked or suspect: `REJECTED`;
   - no safe raw match: `OUT_OF_WINDOW`;
   - stale matched sample: `STALE`;
   - extreme blood-to-raw mismatch: `REJECTED`;
   - otherwise: `VALID`.

The same aligned-quality rule applies both when adding a check and when
reassessing stored checks after a model refresh.

### 3. Conservative raw-sample matching

Normal CGM cadence is approximately five minutes. Interpolation is allowed only
when all these bounds hold:

- total bracketing gap is at most 7 minutes;
- each bracketing sample is at most 6 minutes from `alignedTs`;
- nearest-sample fallback is at most 3 minutes from `alignedTs`.

These bounds preserve normal five-minute interpolation while rejecting the
observed nine-minute false-low gap. Interpolation must use finite, positive
values and the repository's existing duplicate/sanitization rules.

### 4. Extreme mismatch guard

After a safe raw match, calculate:

```text
absoluteGap = abs(bloodMmol - rawMmol)
relativeGap = absoluteGap / max(abs(bloodMmol), abs(rawMmol), 1.0)
```

Reject the check when either condition is true:

- `absoluteGap > 3.0 mmol/L`; or
- `relativeGap > 0.40`.

The observed invalid checks had gaps above 5 mmol/L and are rejected by both
criteria. Previously valid checks in the audited period had materially smaller
gaps. These thresholds are safety guards, not calibration targets, and should
remain named constants with unit tests.

### 5. Existing-check reassessment and model lifecycle

Model fitting and sample weighting must read sensor-quality telemetry at:

```text
check.lagAlignedTs ?: check.timestamp
```

Whenever the repository reassesses checks, it applies the same matching,
aligned-quality, and mismatch contracts used for a newly added check. A check
that no longer passes is persisted as `REJECTED` or `OUT_OF_WINDOW` with its
reason and is excluded from fitting.

If reassessment changes the eligible check set, the active calibration model is
recomputed using only eligible checks. If the minimum evidence requirement is
no longer met, the model is retired or falls back using the repository's
existing model-lifecycle behavior. This design does not introduce a new model
type or silently preserve parameters fitted from a rejected check.

## Data Flow

```text
CGM + telemetry quality
        |
        +--> sensor quality decision ---------------------------+
        |                                                       |
        |                                                       v
        |                                              global auto-action gate
        |                                                       |
        |                         +-----------------------------+------------------+
        |                         |                                                |
        |                   RuleEngine actions                         synthetic UAM export
        |                   blocked when unsafe                        blocked when unsafe
        |
blood check + resolved lag
        |
        v
lag-aligned timestamp
        |
        +--> aligned quality gate
        +--> bounded raw matching
        +--> absolute/relative mismatch gate
        |
        v
eligible stored check --> calibration model fitting
```

## Audit and Failure Handling

Use stable, machine-readable reasons:

- `sensor_blocked` for the global automatic-action gate;
- `sensor_blocked_aligned` for blocked/suspect quality at the aligned timestamp;
- `aligned_glucose_gap` when bounded matching cannot produce a safe sample;
- `raw_blood_gap_extreme` when either mismatch threshold is exceeded.

Calibration audit details should include the blood timestamp, aligned timestamp,
resolved lag, matched raw value when available, absolute gap, relative gap, and
aligned quality state. Reuse the existing bounded audit payload conventions; do
not add continuous high-frequency logging or duplicate raw time series.

If telemetry or matching fails, calibration validation fails closed. If the
global gate cannot determine sensor trust, it must preserve the existing
explicit `sensorBlocked` value and must never convert a blocked state to safe.

## Test Strategy

Tests are added before production changes and must reproduce the failure before
the fix.

### Safety tests

- `SafetyPolicyTest`: `sensorBlocked=true` blocks an otherwise valid proposal
  with reason `sensor_blocked`.
- `RuleEngineTest`: a triggered `SegmentProfileGuardRule` is blocked when the
  context is sensor-blocked.
- A regression sequence verifies that a sensor-quality rollback cannot be
  followed by another automatic rule action in the same cycle.
- A pure UAM-export decision test verifies that settings-enabled export becomes
  disabled when sensor-blocked, while inference remains enabled.
- Existing kill-switch, stale-data, bounds, and rule-priority tests continue to
  pass.

### Calibration tests

- Quality is read at `alignedTs`, not `bloodTs`.
- A blocked/suspect aligned sample rejects the check even if quality at
  `bloodTs` is normal.
- The observed isolated-low sequence with a nine-minute gap cannot be
  interpolated into a valid check.
- A blood/raw difference around 5.05 mmol/L is rejected.
- A difference above 40 percent is rejected even when the absolute difference
  is at most 3.0 mmol/L.
- Normal five-minute bracketing remains eligible.
- Representative previously valid checks with gaps below 1.5 mmol/L remain
  eligible.
- Reassessment removes an invalid stored check and refits or retires the active
  model without that check.

### Verification levels

1. Focused unit tests for each new failing contract.
2. Full `:app:testDebugUnitTest` suite under Android Studio JBR 21.
3. `:app:assembleDebug` under Android Studio JBR 21.
4. Deterministic replay of the 2026-07-06/07 incident data, asserting no
   non-rollback automatic action while blocked and no valid fabricated check.
5. Device installation and runtime verification only after implementation is
   reviewed: process state, bounded logcat audit, and absence of invalid
   automatic actions.

## Resource Impact

All new decisions execute inside existing automation and calibration cycles.
They add constant-time comparisons and reuse data already loaded by those
paths. The design adds no worker, wake lock, polling loop, database-wide query,
or new foreground service. Expected incremental CPU and memory use is
negligible.

## Rollout

1. Add failing unit and regression tests.
2. Implement the global gate and aligned calibration validation.
3. Run the focused tests, full unit suite, build, and incident replay.
4. Review the exact diff against the safety invariants.
5. Install the debug APK on the connected phone only after review.
6. Observe bounded safety/calibration audit events for 24 hours before any
   broader cause-classification work.

This rollout does not enable new automatic therapy behavior.

## Acceptance Criteria

- No `RuleEngine` automatic action is dispatched while `sensorBlocked=true`.
- A dedicated sensor-quality rollback remains possible, and no later automatic
  action in that cycle overrides it.
- Synthetic UAM carbohydrate export is disabled while sensor-blocked.
- The 2026-07-06 manual checks cannot become `VALID` through the observed
  false-low interpolation sequence.
- Calibration status and fitting use quality at the lag-aligned timestamp.
- Invalid stored checks are excluded from the active model after reassessment.
- Existing deterministic safety behavior and manual command behavior remain
  unchanged.
- All focused tests, the full unit suite, the debug build, and the incident
  replay pass.
- No new recurring execution or measurable resource regression is introduced.

## Deferred Follow-up Designs

After this P0 is verified, separate specifications should cover:

1. A shadow-mode episode registry with evidence-fusion states such as
   `SENSOR_MISMATCH` and `DELIVERY_SUSPECTED`, without diagnostic certainty or
   dosing advice.
2. Cause-aware alert escalation, deduplication, acknowledgement, and the
   existing 30-minute mute action.
3. AAPS relay expansion for direct pump evidence such as delivery confirmation,
   reservoir status, occlusion alarms, and infusion-set age, where available.
