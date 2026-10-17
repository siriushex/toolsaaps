# Daily Glucose Facts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Compute bounded, reproducible daily glucose metrics and descriptive episodes from protected per-device history, without a model or therapy operation.

**Architecture:** A pure typed calculator owns civil-day bounds, cohort separation and piecewise-linear integration. A narrow HistoryStore method reads a single authenticated SQLite snapshot and calls it. No public route, schedule, credentials, prompts or legacy app activation is added.

**Tech Stack:** Python standard library, existing Pydantic closed contracts, existing HistoryStore crypto/SQLite and pytest.

## Scope

This implements stage 1's glucose foundation, not the full pattern catalogue,
7/21-day comparison, source adapters, report persistence, daily dispatch or admin.
Those remain separately verifiable stages of the approved high-level design.

Create backend/app/ai/daily_glucose.py and backend/tests/test_ai_daily_glucose.py.
Modify backend/app/ai/history_store.py; add backend/tests/test_ai_daily_history.py.
Update docs/ARCHITECTURE.md, docs/INVARIANTS.md, docs/PLAN.md and AI_NOTES.md.
All paths are relative to /Users/mac/Andoidaps/.worktrees/combined-release-20261015.

## Task 1: Pure Daily Facts

- [x] Add failing tests for module presence and the following API:

```python
window = civil_day("2026-10-13", "UTC")
facts = build_daily_glucose(
    records, report_date="2026-10-13", zone_id="UTC", ranges=ranges,
    now_ms=window.endMs + 1, snapshot_sequence=3,
)
assert facts.dayMinutes == 1440.0
assert facts.provenance == "DEVICE_DECLARED"
```

- [x] Run the new tests and inspect the expected missing-feature failure.
- [x] Add closed immutable output/range/reference contracts. Reject invalid dates,
  zones, future days, overlapping ranges, duplicate record identities and limits.
  Inputs are already decoded HistoryRecord objects; this does not authenticate
  a caller. The store adapter remains the trusted source boundary.
- [x] Calculate UTC bounds from consecutive local calendar midnights, rejecting
  nonexistent days. Never add a fixed 86400000 milliseconds for a civil day.
- [x] Sort observations into timestamp buckets. Same-time equal records in one
  cohort can share a point; different values/cohorts conflict. Invalid/suspect
  or conflicting buckets break interpolation. Only globally adjacent compatible
  points at most 15 minutes apart support a segment; no per-source interpolation
  across an intervening incompatible observation.
- [x] Integrate clipped linear segments and range crossings exactly:

```python
mean = (left_value + right_value) / 2
second_moment = (left_value**2 + left_value*right_value + right_value**2) / 3
crossing_fraction = (threshold - left_value) / (right_value - left_value)
```

- [x] Keep OK and UNKNOWN quality cohorts separate. Compute coverage, weighted
  mean/SD/CV and range exposure per cohort; daily totals describe only coverage.
  Missing range history produces unknown range metrics, not a default target.
  Range gaps have their own denominator and never disappear as covered-in-range.
- [x] Emit descriptive BELOW_RANGE/ABOVE_RANGE episodes only. Preserve range
  version/thresholds, endpoint record references, evidence omission counts and
  explicit estimated boundaries. Cap displayed episodes independently of totals.
- [x] Verify hand-computable ramps/constants, sparse/minimal input, conflicting
  timestamps, quality/source/session changes, range changes, boundary guards,
  DST/skipped days, evidence bounds and deterministic digests.

## Task 2: Authenticated Store Integration

- [x] Write integration tests first using existing synthetic store helpers.
  Call HistoryStore.daily_glucose_facts(owner, device, report_date, zone_id,
  ranges, now_ms); assert exact revisions, device isolation and decryption.
- [x] Run the tests and inspect the expected absent-method failure.
- [x] Inside one existing store lock and SQLite read transaction, select latest
  nondeleted glucose and therapy records in [dayStart-15min, dayEnd+15min], with event,
  observation, receipt and retention cutoffs; preserve high-water membership.
  Reuse _read_record for AEAD/digest verification. Limit rows and SQL/wall time.
  Use only recorded SENSOR_CHANGE therapy as an explicit session boundary; do not analyze doses or read unrelated kinds, or write tables/caches.
- [x] Reject a budget/row/integrity failure without any partial facts. Empty
  device history returns empty descriptive coverage, not fabricated normality.
- [x] Test revision/deletion, wrong owner/device/key, tampering, concurrent writes
  during calculation, expired/future context, row/time bounds and no DB mutation.

## Task 3: Verification And Publication

- [ ] Run the new tests and related history tests with the existing Python runtime:

```sh
rtk env PYTHONPATH=backend /Users/mac/.cache/uv/archive-v0/NAWF1MmBAf3_8K86/bin/python -m pytest -q backend/tests/test_ai_daily_glucose.py backend/tests/test_ai_daily_history.py backend/tests/test_ai_history_store.py
```

- [ ] Run full backend pytest using the existing optional image dependency path;
  inspect actual skips/failures. Run scoped Ruff E/F and compile checks.
- [ ] Review exact diff, output/crypto/cancellation/resource boundaries and test
  gaps. Update governing docs and AI_NOTES with measured results and limitations.
- [ ] Fetch origin, inspect branch/divergence, stage explicit reviewed paths,
  commit and push the current feature branch. Verify remote SHA and CI state.
  Never merge main, activate API, run a provider turn or update a phone here.

The calculation is observational engineering, not a validated clinical detector.
