# Fresh Glucose Recalculation

## Approved Scope

The user approved a complete forecast recalculation for every new current CGM
observation, including the beginning of a rise and a reversal. Target Manager
then evaluates the accepted same-cycle 5/30/60-minute forecasts with the existing
IOB/COB, meal, activity, sensitivity, sensor and delivery inputs. This is earlier
evaluation, not a new target policy or an instruction to lower a protective target.

## Decision

Use the existing durable invalidation coordinator rather than a second trend
detector or a shorter periodic timer. A trend-only detector introduces another
threshold and can miss the beginning of a change. A faster periodic loop spends
work when no input has changed. Recalculating current observations uses the
existing canonical five-minute trend computation and accepted forecast pipeline.

## Changes

- Broadcast persistence compares the valid, source-prioritized current CGM row
  before and after a mutation inside its Room transaction, at one frozen ingest
  clock. A new timestamp or corrected current value/provenance requests durable
  invalidation immediately, without the previous four-minute glucose throttle.
  Row IDs alone do not constitute an input change. Duplicate, historical,
  invalid and future-only records do not masquerade as a new current point.
- Therapy changes and existing telemetry invalidation/coalescing remain intact.
  Local Nightscout already invalidates new glucose and relevant changed inputs;
  no second queue or additional sensor observer is introduced.
- The reactive worker waits cancellably for the existing automation cycle lease.
  It reads settings and clinical inputs after acquiring it rather than losing an
  event to two short busy retries. Periodic/manual idle behavior stays unchanged.
  The existing worker deadline bounds the wait and computation together.
- The durable coordinator continues to merge a burst, retain newer pending input,
  preserve exact generation/token settlement and prevent overlapping dispatches.

## Safety And Acceptance

No schema, calibration, prediction gains, target thresholds, hard bounds,
protective ownership, repeat-send policy, sensor/forecast reliability, arm,
kill-switch or dispatch freshness guard changes. A newer observation during a
calculation still blocks a stale target send. No forced phone cycle or synthetic
therapy send is allowed during verification.

Acceptance requires failing-then-passing current-point regressions, Room
commit/rollback and duplicate tests, real mutex contention/cancellation tests,
worker route binding and related scheduling/target suites. Full Android unit,
lint, compile and build checks precede source publication. A signed in-place
phone update needs a fresh coherent private backup and natural runtime/UI
verification. Earlier forecasting is not evidence of clinical benefit; actual
input-to-forecast and forecast-to-target latency must be measured separately.
