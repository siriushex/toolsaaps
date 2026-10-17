# Daily Glucose Baselines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans task-by-task, with test-driven development and verification before completion.

**Goal:** Add same-device 7/21-day descriptive baselines and repeated exposure windows to the approved daily review foundation.

**Architecture:** Extend the existing linear calculator with sparse hourly metrics. A pure comparison module consumes one consistent set of daily facts. One bounded protected history snapshot supplies the report day and up to21 preceding days; no model, scheduler, persistent report, HTTP or therapy action is added.

**Tech Stack:** Existing Python, Pydantic closed contracts, ZoneInfo, authenticated HistoryStore and pytest. No new dependency.

## Explicit Decisions

- Daily facts become daily-glucose-linear-v2 because their output now includes
  sparse hourly metrics. Daily totals retain the tested linear integration.
- Clock-window comparison is available only on regular24-hour civil days.
  DST/discontinuous days still have correct daily totals; hourly recurrence is
  unavailable rather than inventing a comparison for a repeated/skipped hour.
- Comparable context requires device-declared OK quality and matching source,
  known original zone, sensor session, calibration, glucose basis and input-model
  version. Unknown metadata is a limitation, not evidence of a matching sensor.
  A recorded sensor change within the day excludes that day's comparisons.
- Daily metrics require80% covered time in that cohort. Two-hour recurrence
  windows require80% of that window, not80% of an otherwise irrelevant whole
  day. Five observed minutes outside the same versioned analytical range qualify
  a window; at least3 distinct qualifying days make a provisional recurrence.
  These are conservative versioned engineering rules, not clinical thresholds,
  diagnoses, statistical significance or a treatment recommendation.
- Baselines exclude the current day and future days. Expose every eligible date,
  excluded/missing date and denominator. Pool only compatible time-weighted
  moments and summed range minutes, not percentages with different denominators.
- Exposure baseline eligibility is separate from glucose moments; absent,
  partial, mixed or changed range configuration cannot supply a false TIR.
- Input identity, as-of cutoff and snapshot sequence must match. A typed object
  or source digest is not authentication; only the protected reader owns that
  boundary. No clinical validity is claimed by the comparison rules.

## Task 1: Hourly Facts

Files: backend/app/ai/daily_glucose.py; backend/tests/test_ai_daily_glucose.py.

- [x] Add tests for sparse hourly coverage, exact boundary/range crossings and
  unchanged daily totals; assert nonstandard civil days have no hourly claims.
- [x] Observe missing-feature failures, then add typed HourGlucose facts and
  reuse the existing segment accumulator for daily and hourly integration.
- [x] Run the full daily calculator regression suite. Invariants:

```python
assert sum(hour.metrics.coveredMinutes for hour in cohort.hours) == cohort.metrics.coveredMinutes
assert all(hour.metrics.coveredMinutes <= 60 for hour in cohort.hours)
assert facts.hourlyStatus == "UNAVAILABLE_CLOCK_DISCONTINUITY"  # DST test case
```

## Task 2: Pure Comparisons

Files: backend/app/ai/glucose_comparison.py; backend/tests/test_ai_glucose_comparison.py.

- [x] Add failing tests for context/range eligibility, 7/21-day membership,
  pooled moments/exposure, missing days and one/two/three qualifying-day labels.
- [x] Implement build_glucose_comparison(current, history) with bounded closed
  outputs. Reject mixed devices, duplicate/future dates or inconsistent cuts.
- [x] For each current cohort, calculate7/21-day glucose and range denominators
  separately; keep exclusion codes and source digests. Merge variance as:

```python
mean = sum(weight * value for weight, value in values) / total_weight
variance = sum(weight * (sd**2 + (value - mean)**2)
               for weight, value, sd in moments) / total_weight
```

- [x] Derive current two-hour exposure observations from complete hourly facts,
  never from a truncated episode list. Retain at most24 ranked patterns, explicit
  omissions, daily/cohort/episode references and their omission limitations.
- [x] Test sparse qualifying windows, unknown metadata, distinct-day counting,
  changed calibration/range, zero/current-only data, omitted episodes and digest
  determinism. No meal/GI/illness causality or treatment parameter is produced.

## Task 3: Protected Multi-Day Read

Files: backend/app/ai/history_store.py; backend/tests/test_ai_glucose_review.py.

- [x] Write failing integration tests for daily_glucose_review with one owner/
  device/cutoff/sequence, revisions/deletions, corruption, quotas and time limits.
- [x] Reuse one private glucose/therapy snapshot context for the existing daily
  reader and the multi-day reader; preserve crypto, lock/transaction and teardown.
- [x] Partition sorted records with timestamp bisection for the22 civil windows;
  retain per-day50000 and per-review100000-row limits, existing SQL/five-second
  wall budget, same source identity and no partial output. Clip unavailable
  retention days and label them missing rather than normal.
- [x] Run existing daily/store regressions plus new integration tests and scoped
  E/F lint, compilation, full backend suite and synthetic bounded capacity check.

## Task 4: Documentation And Publication

- [x] Update DAILY_GLUCOSE_FACTS.md, ARCHITECTURE, INVARIANTS, PLAN and AI_NOTES
  with actual boundaries/results; keep provider/admin/deployment work pending.
- [x] Review explicit source diff and sensitive-data/identifier boundaries.
- [ ] Fetch/check divergence, commit explicit reviewed paths and push existing
  feature branch; inspect exact-head CI. No merge/main/deployment operation.
