# Personalized Meal Timing Evidence

Date: 2026-10-18. Status: source audit implemented; clinical timing not enabled.

## Required Distinction

The requested outcome is a useful meal-start recommendation, not a renamed
insulin-onset clock. Current `estimateRealInsulinOnsetMinutes` uses a12-hour
lookback and observed downward CGM changes after eligible insulin records. This
is not a3/7/14-day personal meal-timing model. Its current clinical prediction
use is unchanged by this stage.

An observed CGM decline cannot independently identify causal insulin onset:
prior insulin, ongoing absorption, endogenous/background changes, activity,
sensor delay/calibration and pump delivery all matter. A model's onset is not
the optimal waiting interval. No global subtraction from GI or ISF is added.

## Implemented Source Audit

`MealTimingHistoryReport` consumes the existing qualified episode producer and
counts completed local calendar days in3/7/14-day windows. Boundaries match that
producer's midnight convention, including DST. Today is not a completed day.
The05:00 maintenance boundary controls refresh, not the historical day boundary.

Each window reports candidate declared meals, qualified absorption episodes,
distinct days, sensor/calibration/insulin-model comparison cohorts, the largest
cohort's day support, independent GI coverage and structured rejection counts.
Several meals on one day cannot substitute for several observed days. Different
insulin/model/sensor cohorts are not pooled into one fitted history. These counts
do not claim that any cohort is comparable to the current treatment context.

The existing producer retains original ISF/CR, insulin context, sample availability
and source checks. Current settings do not repair missing historical context.
Only independently labelled USER/CATALOG GI for the exact meal revision counts;
model-estimated GI is not a training label. Declared grams retain their uncertain
origin. Availability after the audit cutoff and known confounds cannot qualify.

This source is limited to canonical declared meals with the existing metadata;
it does not prove all food or insulin has been captured. Empty successful history,
unavailable source and overflow are separate states. The current source lacks a
verified link between confirmed bolus delivery and actual meal start. Qualified
absorption episodes alone cannot supply that link or a premeal recommendation.

The bounded summary joins the existing authenticated insulin-context upload.
It is loaded only when the device/day/profile snapshot is due, not per CGM or
timer tick. It contains counts and fixed reason codes, no raw samples, meal IDs
or arbitrary notes. A failed optional audit preserves the insulin snapshot with
UNAVAILABLE; it does not trigger a new AI request. Retry waits for the next due
snapshot (or a failed upload retry); process restart may also reload it. No new
worker, server route, database schema, model execution or therapy writer is added.

## Requirements For A Recommendation

The research inventory must inspect interval evidence even without a dose-units
field. Rate-only absolute/percentage basal and explicit basal/suspend/resume
records cannot disappear from the bolus-only forecast. Unrecognized forms,
zero-rate suspension, malformed rates and old intervals with unknown support
remain unsupported. No duration or delivery is invented from those fields.
Assessment, direct simulation and direct forward projection all refuse such an
inventory, preserving the separate live prediction. Ordinary food/exercise
duration and null interval fields do not create insulin records.
This refusal closes an omission, not source/pump completeness: independent
upstream coverage and clinical validation below remain necessary.

1. Capture explicit meal intent, actual meal start and confirmed bolus delivery
   linkage separately. An entered timestamp, positive IOB, SMB or correction
   bolus must not silently become proof of a planned meal.
2. Reconstruct contemporaneous insulin type/profile, DIA, ISF/CR, IOB/COB,
   basal/bolus activity, glucose quality and slope, sensor/calibration identity,
   food type/GI/quantity provenance, activity, illness and delivery evidence.
   Missing context stays unknown; illness is not silently averaged into baseline.
3. Use3 days only as an exploratory history window, not sufficient validation by
   definition. Compare7/14 days and same time-of-day/type/portion/model cohorts.
   Keep earlier training and later validation days separate. Changes in profile
   require requalification, not borrowing the old profile's precise latency.
4. Estimate response intervals with uncertainty, not an exact minute of action
   inferred from the first downward CGM point. Test sensitivity to uncertain meal
   grams and GI without jointly fitting all unknowns from the same outcome.
5. Evaluate candidate meal times with the existing isolated scenario simulator
   and timing planner: current glucose/trend, active insulin/food, meal shape,
   delayed/skipped food, uncertain absorption, future pump actions and complete
   response tails. Historical glucose under old insulin is not a counterfactual
   outcome under a different eating schedule.
6. Validate the full trajectory uncertainty and prospective recommendation with
   clinically reviewed acceptance criteria before enabling a live eating signal.
   Existing pointwise5/30/60-minute confidence intervals cannot authorize it.
7. A future valid countdown must invalidate on changed source identity/profile,
   stale CGM, delivery problems or new risk. Hypoglycemia warnings must take
   precedence; a timer must never instruct continuing to wait through risk.
   No automatic changes to doses, targets or prescribed settings follow from it.

Nightly processing may prepare a versioned candidate and an audit report. A
nightly job itself is not validation and must not overwrite a validated model
with sparse or degraded evidence. Current timer UI and therapy admission remain
unchanged until these missing contracts are implemented and checked.

## Verification

Focused tests cover3/7/14-day windows, day-vs-episode support, DST, mixed insulin
cohorts, independent GI revision/provenance, future availability, illness, bounded
inputs, Room read-only behavior, daily upload deduplication, cancellation and
fixed unavailable-state handling. Synthetic tests establish software behavior,
not patient-specific accuracy or clinical safety.

## References

- [AID consensus recommendations](https://academic.oup.com/edrv/article/44/2/254/6692818).
- [Fiasp prescribing information](https://www.novo-pi.com/fiasp.pdf), sections2,
  5.3 and12: formulation-specific administration and variable time-action profile.
  This reference is not evidence of which insulin this user uses.
- Existing approved integration contract:
  `superpowers/specs/2026-10-13-meal-response-target-integration-design.md`.
