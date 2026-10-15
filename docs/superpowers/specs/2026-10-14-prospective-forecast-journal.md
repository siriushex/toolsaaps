# Prospective Forecast Journal

## Scope

Observational prerequisite for meal-response forecast validation. No experimental
forecast enters clinical inputs, COB, recorded grams, Target Manager or doses.
No phone operation belongs to source publication.

## Collection

After authenticated accepted Room readback and completed clinical fan-out,
AutomationRepository performs a nonfatal observational write with a one-second
coroutine budget. It does not subscribe to or reproduce the prediction engine.
Accepted5/30/60 outputs, digest, generation, settings revision, model versions,
contemporaneous calibrated CGM, source/quality, accepted sensor/calibration
identity and device zone are frozen in an independent table. Blocked/suspected
sensor quality is not clean. Unknown sessions remain unknown. Recording does
not prove sensor accuracy.

An insert transaction commits before a separate post-commit finalization marker.
Cancellation/crash before finalization leaves an unfinished receipt. Registration
uses the existing pre-first-outcome checks. No old history is backfilled.
Observed/finalized clocks describe journal availability at an accepted cycle,
NOT original transport arrival. Outcomes are subsequent accepted-cycle
observations, not all raw arrivals. Missed cycles and nonexact timestamps reduce
support rather than create interpolated outcomes.

Cycle IDs are insert-only: retries cannot replace a prediction. Differing cycles
for one anchor/cohort are rejected, not selected by later accuracy. Identical
observations have one vote; contradictions remain conflicts. Indexed21-day
retention cannot delete clinical tables. No history fitting, polling or network
delivery is added.

## Review

Analytics / Quality offers a passive on-demand check. One Room snapshot loads at
most7001 rows; exceeding7000 or256 cohorts rejects scoring, not truncates it.
Closing cancels the read. Refresh is explicit; CGM/UI ticks do not rerun it.

Baseline-only scores are separate from personalized paired comparisons. They
reuse exact-time/source/session/calibration/quality/availability matching and
day-balanced errors. Baseline versions also separate cohorts. Missing accuracy
is null; actual lows remain. Compact rows show horizon, MAE, samples/days, missed
mean-predicted lows, pending/unavailable outcomes and excluded/unknown-session
receipts. No personalized candidate is claimed.

## Migration And Limits

Room32 ->33 creates meal_forecast_journal and two indexes only. Clinical rows and
existing alarm/meal journals remain. No destructive fallback. A schema32 APK
cannot open33; use reviewed forward repair, never stale therapy restore.

The app-owned ledger establishes durable application ordering, not cryptographic
attestation against a modified device/database. Wall-clock rollback during
finalization leaves an unfinished receipt. Scores are observations under actual
unchanged control, not counterfactual target safety. A qualified causal personal
candidate and independent control assessment are still required. No inferred GI.

## Checks

Synthetic strict baseline/cohort/low/conflict tests; real Room post-commit,
cancellation, immutable retry, missing/corrupt/late/overflow/retention checks;
native file-backed32 ->33 migration and restart; compact/large-font passive UI.
Full local unit/lint/compile/assemble precedes publication and exact-source CI.
No real personal-accuracy or phone CPU/RAM result is established by these tests.
