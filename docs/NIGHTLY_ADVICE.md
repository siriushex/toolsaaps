# Nightly Advice

## Scope

The Advice tab has a dedicated local CGM review for the trailing 3, 7 and 14
epoch days. These are descriptive observations and suggestions for what to
review, not an LLM result, diagnosis or a parameter/dose recommendation. Insulin,
meal timing, sensor age, illness and activity are not inferred from glucose alone.
Existing clinical and saved AI reports remain separate and retain their sources.
No backend route, medical upload, model call, consent or therapy authority changes.

## Calculation

- The cutoff is the most recent local 05:00, including today when it is due.
  Window lengths are exact 24-hour epoch days; labels include actual timestamps
  and zone, so a DST transition does not silently change the denominator.
- Read at most 100,001 compact CGM projections for 14 days. Overflow rejects the
  whole refresh, not a truncated source. Accepted qualities are OK/GOOD/VALID/
  TRUSTED. Existing canonical selection and five-minute buckets deduplicate
  sources; the existing clinical calculator supplies duration support and gaps.
- Aggregate fourteen one-day summaries by supported duration. The range is the
  existing report definition, 4.0-10.0 mmol/L, explicitly labelled in the UI,
  not a newly selected personal treatment target. Missing time is not in range.
- Display supported coverage and qualifying days. Pattern suggestions require
  at least three days each with 70% coverage and 70% overall window coverage.
  Two qualifying days with at least 15 supported minutes below 4.0, or 30 above
  10.0, form a repeat-pattern observation. These are conservative UI reporting
  filters, not validated medical, emergency or dosing thresholds.
- A recent comparison uses the last three days against the disjoint earlier
  days in a 7/14-day window. It requires all three recent days and at least three
  earlier days with 70% coverage, plus 70% overall earlier coverage. No comparison
  against an overlapping 14-day average. A five-percentage-point change gets an
  explanation, not an automatic medication response.

## Persistence And Scheduling

One fixed local telemetry identity stores only the latest versioned aggregate
JSON atomically. No Room migration or raw-series cache is added. The existing
server-history telemetry allowlist excludes this key; no new upload consumer is
introduced. Invalid cached metadata, oversized JSON or invalid bounds are refused.
The committed cutoff/zone is reused after duplicate work or process restart.
Failure/cancellation leaves the previous snapshot; it is not timestamped as new.
An invocation older than the committed generation cannot replace it, even after
a zone change. A backwards/inconsistent device clock holds refresh rather than
overwriting later evidence; the UI exposes the noncurrent period. Unchanged Room
rows are suppressed before JSON decoding and do not republish the review.

A dedicated network-free WorkManager task targets local 05:00. The next override
is renewed with UPDATE only after a committed refresh; running work is not
cancelled. Startup also requests unique catch-up work. A missed night produces
one current review, not fourteen queued analyses. Power-save defers history work.
Therapy arming is neither required nor changed by observation-only advice.
Android may delay work in Doze or while force-stopped; 05:00 is not an exact-alarm
or delivery guarantee. Prior snapshots remain visibly dated. After timezone/clock
changes, the next started app/work recalculates the current local schedule.

## Presentation

The newest saved aggregate is supplied by Room observation without a button or
AI request. A compact 3/7/14-day selector, period/update metadata, duration range
bar and coverage precede the advice. Sparse/stale history is explicit. Each
discussion action appends the exact source, cutoff, period and selected evidence
to an unsent chat draft. Other reports are a separate disclosure when a nightly
snapshot is present. Opening a tab cannot start a model or alter therapy.

## References And Verification

- [WorkManager next-schedule override](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest.Builder#setNextScheduleTimeOverride(long)):
  UPDATE preserves running work; an override is not an exact execution promise.
- [NIDDK CGM guidance](https://www.niddk.nih.gov/health-information/diabetes/overview/managing-diabetes/continuous-glucose-monitoring):
  CGM estimates glucose; uncertain readings may need comparison with a meter.
  This does not validate this app's thresholds or justify an automatic dose.
- Synthetic metric, repository, schedule/maintenance and native screen tests;
  full Android unit/lint/compile/assemble and exact-head CI are stage gates.
- Local final gate:6073 passed/6 existing skips; lint0 errors, unchanged377
  warnings/8 hints. Native320x640 Russian and1.8x-font cases were inspected.
- This source stage does not enable the separately unfinished server daily LLM
  report pipeline or establish physical-device overnight acceptance.

Run from android-app with JDK17:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=2 --console=plain
```
