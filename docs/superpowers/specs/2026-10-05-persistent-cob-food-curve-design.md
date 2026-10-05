# Persistent Full Food Curve During COB

## Scope And Review Status

The user requested the complete carbohydrate-work curve, scrolling through
modeled absorption completion, continuous availability while COB is positive,
and updates with COB dynamics. The human acknowledged the proposed approach.
The recommended working selection is the existing yellow food-only
glucose-influence line, not a new grams-axis chart. This written specification
requires human review before an implementation plan or runtime edits.

This extends the accepted display-only GI design of 2026-10-04. It does not
change clinical COB, carbohydrate absorption, GI coefficients, 5/30/60-minute
forecasts, UAM, insulin, Target Manager or treatment scheduling/dispatch.
Medical absorption completion is not established by a model endpoint.

## Alternatives And Decision

1. Preserve the accepted yellow curve with its own origin and explicit display
   state; replace it on a newer accepted food result. Recommended: preserves the
   existing chart and food-only meaning, with a small bounded presentation cache.
2. Add a separate COB-grams chart. Useful for directly inspecting the remaining
   quantity, but adds a distinct series, units and UI. Not selected in this stage.
3. Rescale the yellow line from the newest raw AAPS COB or move an old curve to
   the latest glucose point. Rejected: mixes clocks and may assign unknown or
   synthetic carbohydrates to announced food or invent a completion time.

The yellow line remains cumulative modeled announced-food influence, anchored
at the glucose origin of its accepted calculation. It is not remaining grams,
absorption rate or a long net glucose prediction. At modeled completion it
reaches a cumulative plateau; it need not descend to zero glucose influence.

## Confirmed Current Defect

`MainUiStateMappers.toOverviewUiState` passes the newest history point to
`mealImpactChartData`. The versioned parser correctly requires that origin to
match `predictionAtMs`. A newer CGM point can therefore erase an otherwise
accepted food tail before the replacement cycle finishes. Losing those points
also shrinks the interactive time domain and can clamp the explored viewport.

Do not remove the parser's clock check. Resolve the accepted origin explicitly,
freeze it with the display result, and keep the old curve on its original clock.
The existing full-tail domain support does not by itself fix the lifetime bug.

## Data And Ownership

- Use the existing accepted food projection, `forecast_meal_steps`, accepted
  sensitivity tuple, calibration authority and observed glucose history. No
  independent clinical calculation, worker, observer or polling loop is added.
- Decode at most one bounded payload into a display-only immutable snapshot:
  accepted cycle/generation, prediction clock, exact glucose origin, points,
  model version, GI-adjusted status, completeness and the acceptance scope.
- For schema 2, find the historical glucose origin whose timestamp equals the
  payload's prediction clock, rather than selecting the newest point. Origin
  and calibration context must be consistent with the validated accepted tuple.
  Missing origin cannot be substituted with a nearby/newer sample.
- Only a result accepted under the existing sensitivity/settings/calibration
  authority may populate or replace the last-good display snapshot. A loose
  latest telemetry row, rejected cycle or malformed payload cannot become fresh.
- Own the single snapshot in the existing primary UI state pipeline, with one
  writer. Keep mapping/rendering pure; do not share a mutable cache with clinical
  forecast/control code or concurrent diagnostic builders. Copy bounded lists.
- Retain at most one last-good snapshot in memory. Reconstruct after process
  restart only from validated existing Room evidence and its exact origin.
  Failure to reconstruct means explicit unavailable data, not a fabricated curve.
- No Room migration or new persisted clinical inputs. Preserve schema 2 and
  legacy payload compatibility; additional protocol fields are not required.

## COB And Update Semantics

- The Overview COB number is the fresh external/AAPS reference. It is separate
  from effective clinical COB and the model's announced-food remainder. Missing,
  invalid, future or stale COB is not zero. Reuse existing validity bounds.
- Read COB updates through existing UI subscriptions. They update the reference
  value and presentation state immediately; no direct raw-COB scaling of the
  yellow line or silent substitution into clinical inputs is permitted.
- On every newer accepted food result, replace the whole display snapshot
  atomically. Its shape, magnitude and modeled endpoint follow the latest
  reconciled meal/profile/time calculation, including already consumed inputs.
  Do not update individual points from different accepted cycles.
- While a new glucose/COB observation is newer than the displayed accepted
  calculation and no replacement is ready, retain the complete previous curve
  on its original timestamps and mark it as updating/last accepted estimate.
  This is display continuity, not fresh clinical forecast authority.
- The existing ingestion/coalescing/exclusive-cycle path remains responsible
  for calculation. Do not change its cadence or launch a clinical cycle from
  a mapper, chart gesture or presentation-state transition.
- Positive fresh COB keeps the food layer and its status available. With a valid
  retained projection, the complete line remains inspectable. If no valid shape
  exists, keep the layer's explicit unavailable/unknown-end state and COB value;
  do not hide the whole layer, draw fake zero food or invent a tail from COB alone.
- If the old modeled endpoint has passed while fresh COB remains positive, mark
  completion as unresolved pending a new valid result. The old curve may remain
  as an explicitly old estimate, but must not be called current/full absorption.
- A flat no-announced-food projection does not establish that positive external
  COB is absorbed. Distinguish COB without a usable associated food projection.
- A valid fresh zero reference does not by itself erase a still-active accepted
  modeled food component. Clear active food presentation only when supported
  current data establishes no remaining modeled component; unavailable data
  cannot perform this transition. Do not add a new physiological threshold.

## Display States And Invalidation

Expose concise localized data states: current estimate, updating, outdated,
unknown endpoint/unavailable, and no remaining modeled food. Keep the existing
GI-shaped, legacy and horizon-incomplete markers as independent information.

Generation freshness is unchanged for all clinical forecasts and controls.
A retained food curve outside that freshness is explicitly outdated and cannot
populate current horizons, confidence intervals, target inputs or clinical
acceptance flags. Original cycle/generation/clock must remain inspectable.

Invalidate the retained curve on incompatible sensitivity/settings source or
revision, changed calibration authority, invalid acceptance scope or reset of
its source data. Unknown scope cannot silently certify a cache. Positive COB
then retains the layer/status, not a falsely trusted curve. Never relabel the
snapshot to a different identity, glucose origin or acceptance generation.

## Full Tail And Interaction

- Preserve the existing five-minute food grid, maximum 720 minutes/145 points
  and 16 KiB payload cap. Nonzero remainder at the cap is explicitly incomplete,
  not full absorption. GI changes shape only within the existing approved rule.
- Include valid retained food timestamps in the domain. Chart now remains the
  actual observed glucose clock; food points cannot create observed history.
- Follow-live/reset includes the remaining valid tail. Horizontal pan, pinch
  zoom and accessibility controls can reach its endpoint. Explore mode survives
  ordinary arrivals and pending calculations without losing its full domain.
- Replace the domain only when a valid snapshot or supported empty state changes
  it. Clamp safely if a genuinely newer accepted tail has a different endpoint;
  do not force explore mode back to live on every update.
- Renderer bounds must include food-only portions when panned past the 30/60m
  clinical forecast. Handle empty visible clinical series without exceptions.
  Check the noninteractive fallback too; do not count food as actual glucose.
- Preserve readable labels and stable layout on narrow screens, both themes,
  font scales 1.0/1.8 and long/incomplete tails. New status labels describe data,
  not instructions for using the interface.

## Verification And Expected Surface

Expected Android files: `MainViewModel`, `MainUiStateMappers`,
`MealImpactChartPoints`, chart/screen models, `OverviewScreen`, relevant chart
domain/viewport/rendering helpers and localized meal-impact strings. Add a small
presentation snapshot/resolver only if it owns the above nonclinical lifetime
rules. Do not refactor unrelated UI, scheduling or forecasting infrastructure.

Start with actual failing regressions for new-CGM/old-accepted-origin continuity,
then cover atomic replacement, positive/decreasing/zero/missing/stale COB,
unmatched/raw-only COB, old endpoint with positive COB, source/revision/calibration
invalidation, malformed/rejected payloads and restart reconstruction. Assert
that the retained display cannot authorize or populate fresh clinical forecasts.

Test endpoint reachability after zoom/pan/accessibility actions, pending updates
without domain collapse, legitimate endpoint changes, no actual history, legacy
30m data, 720m truncation, overflow and empty visible clinical series. Render
the long tail with both themes and both font scales on narrow layouts.

Run focused display and accepted-authority regressions, then full Android
assemble/unit/lint/compile checks. Verify unchanged clinical outputs for the
same inputs. Review explicit paths, publish on the existing feature branch and
inspect exact-source Verify CI. No backend change or performance-saving claim.

Any later authorized working APK update requires an available intended phone,
fresh coherent backup, matching signature, installed hash/settings/schema checks
and natural awake graphical acceptance. Do not restore an old backup, insert
fake food/GI, change a target, force a clinical cycle or send therapy for tests.
Keep phone artifacts private. Phone absence or missing real GI remains an
explicit acceptance limit, not grounds to fabricate evidence.

## Self-Review

The selected layer, units, COB source, clock binding, freshness, cache ownership,
unknown-data handling and completion limits are explicit. Continuous layer
availability is not an unconditional physiological prediction when source data
is absent. Runtime implementation, clinical accuracy and device acceptance are
not claimed by this design document. Written human review is the next gate.
