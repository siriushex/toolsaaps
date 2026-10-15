# Daily Glucose Facts

Stages 1 and 2 of the approved daily-review/admin design. This is an internal,
deterministic descriptive calculator and protected history reader, not an AI
worker, scheduler, public route or medical decision rule.

## Interface

```python
from app.ai.daily_glucose import AnalysisRange

facts = history_store.daily_glucose_facts(
    owner_id, device_fingerprint,
    report_date="2026-10-13", zone_id="Asia/Tbilisi",
    ranges=(AnalysisRange(startMs=start_ms, endMs=end_ms,
                          lowMmol=low, highMmol=high, version="range-1"),),
    now_ms=now_ms,
)
document = facts.model_dump(mode="json")

review = history_store.daily_glucose_review(
    owner_id, device_fingerprint,
    report_date="2026-10-13", zone_id="Asia/Tbilisi",
    ranges=historical_analysis_ranges, now_ms=now_ms,
)
# review.current contains daily facts; review.comparison contains baselines
# and bounded descriptive windows, not a model response or treatment advice.
```

The caller must already own trusted owner/device identity. No bearer-only or
HTTP shortcut is introduced. The pure build_daily_glucose function accepts
decoded typed records for deterministic tests; it does not authenticate them.
Only the store reader binds sourceBinding to the selected owner/device hash.
The digest binds that identity, date/zone, as-of cut, snapshot sequence, ranges,
record bodies/revisions and known sensor-change context. It is a reproducibility
binding, not a signature or proof of complete device history.

## Calculation

- Consecutive local calendar midnights define UTC bounds. DST days can contain
  23 or 25 hours; nonexistent dates and unfinished days are rejected.
- Read at most 50000 selected glucose/therapy records and 256 range/context
  changes, within the day plus 15-minute boundary guards. Global scope remains
  GLUCOSE_ONLY; only SENSOR_CHANGE therapy is used as contextual evidence.
  Other therapy payloads are authenticated to identify their type but neither
  analyzed as doses nor returned in facts.
- Interpolate only globally adjacent, compatible points separated by at most
  15 minutes. Suspect/conflicting timestamps, changed source/quality/session/
  calibration context and recorded sensor-change boundaries break continuity.
  A point exactly at a recorded sensor change is ambiguous and not bridged.
  The change ordinal is local to this window, not an invented sensor identity.
- Separate up to 128 cohorts. OK is device-declared quality, not verified sensor
  accuracy. UNKNOWN quality remains a distinct unverified view. Never pool
  means across cohorts or select a conflicting source arbitrarily.
- Clip segments at day and range boundaries. Use linear-segment moments and
  range crossings, not sample counts, for mean, SD/CV and exposure. Covered
  duration and known-range duration accumulate as integer milliseconds to avoid
  drift on dense series. Output minutes are converted only after accumulation.
- No range history means unknown exposure percentages. Partial range history
  uses rangeCoveredMinutes as its denominator and separately discloses
  rangeMissingMinutes. A temporary target is not supplied implicitly.
- maxGapMinutes is the largest adjacent observation separation intersecting the
  day. maxUncoveredMinutes also includes unsupported day edges and conflicts.
  Neither missing intervals nor zero/single-point input count as normal glucose.
- BELOW_RANGE and ABOVE_RANGE episodes are observations, not diagnoses or
  learned patterns. Keep up to 32 longest episodes with at most 16 endpoint
  references each; explicit omission counts preserve the distinction from
  complete evidence. Summary limits do not discard integrated time.
- daily-glucose-linear-v2 adds sparse per-cohort hourly metrics using the same
  segment integration. Missing hours are not zero-exposure hours. Hourly clock
  comparisons are withheld on DST/discontinuous days; daily totals still use
  the actual civil-day length.

## Comparable History

The multi-day reader calculates the completed report day plus up to 21 preceding
days from one protected owner/device snapshot and one as-of cutoff. The previous
7 and 21 days supply separate baselines; the report day and future dates never do.
Days outside retention are not restored. Every absent or excluded day has an
explicit reason; missing uploads cannot count as normal glucose.

Comparable cohorts require device-declared OK quality, matching source and known
original timezone, sensor session, calibration, glucose basis and input-model
version. Original zone must match the configured report projection. A recorded
sensor change excludes that day's comparisons. Unknown metadata is not filled
from current settings. This intentionally means current core exports with unknown
context may yield LIMITED_DATA rather than personalized claims.

Daily baseline eligibility requires 80% covered time in that cohort. Means and
variance are pooled by covered minutes, including between-day variance. Range
percentages use summed minutes from eligible days with complete, unchanged,
versioned analytical ranges, not an average of percentages. Range eligibility is
separate: a day may supply glucose moments but not comparable exposure. A sparse
current day has no daily delta, even when its historical baseline is available.

Current two-hour clock windows with at least 80% covered time and five observed
minutes outside a complete, matching range become OBSERVATION candidates. Three
distinct qualifying days across the current day plus previous 21 days produce
PROVISIONAL_RECURRENCE. These versioned engineering screening rules do not prove
clinical validity, causality, significance or a stable learned pattern. Sparse
days can support one adequately observed window without supplying a daily
baseline. Valid normal windows remain in the denominator; missing windows do not.

Keep at most 24 ranked candidates, preferring below-range observations, more
qualifying days and greater current exposure, then deterministic hour/cohort order.
Exposure derives from hourly integration, not the truncated episode list. Each
candidate retains per-day covered/missing/exposure minutes, source/cohort/episode
references, excluded dates and explicit omission counts. omittedEpisodes is the
day-wide omission count, not a claim those omissions belong to this window.
The sole allowed action is review of measurement and recorded context. No food,
GI, bolus timing, illness or insulin-delivery cause is inferred from glucose alone.

## Storage And Failure

One existing private store lock and one SQLite read transaction freeze selected
membership/high-water. Latest nondeleted revisions are selected with original
event/observation/receipt/expiry cuts. Existing AEAD/AAD and digest validation
protect every selected body; no DB migration or medical write is performed.

The reader is window-indexed, not a full forensic inventory/integrity scan of
every out-of-window row. Selection, absent uploads and source completeness are
not certified. The output always says DEVICE_DECLARED and dataCompleteness
UNKNOWN, including when all available intervals have device-declared OK quality.

Existing SQL instruction and five-second wall-time bounds apply. An exceeded
row/time bound or corrupt selected record raises a fixed error, never partial
successful totals. No new cache, background process, model/profile access or
automatic retry is created. Reads do not extend retention or restore revisions.
The multi-day read admits at most 100000 selected glucose/therapy rows, while
each daily calculation retains its 50000-row limit. The deadline is checked after
calculation as well as during selection; failure discards the entire review.
All comparisons share snapshotSequence, asOfMs and sourceBinding. A separate
versioned digest binds the daily input digests and comparison rules.

## Remaining Stages

The wider candidate catalogue; bounded meal/GI/controller/notification/physiology
adapters; encrypted report lifecycle;
dedicated daily-model policy/result validation; durable schedule and private
admin admission/UI. Approved prompt text is documentation, not an active prompt.
The local synthetic admin mockup is not deployed or included in the app.

Tests establish the stated software behavior, not medical validity, host
admission or actual CGM accuracy. No target, insulin, ISF/CR or Eating Soon
control path is changed.

Verification for this stage includes 145 focused tests, scoped E/F lint and
Python compilation. One synthetic local macOS capacity run with 31681 encrypted
one-minute observations over 22 days took 3.919s, with 133.6MiB process peak RSS;
output was 26973 bytes. Setup took 11.848s and is excluded from read time. This is
not a server/phone CPU, latency, memory or deployment admission result. Final full
backend: 1130 passed, 27 existing opt-in skips and one dependency deprecation
warning. An initial unchanged legacy runner cleanup test failed on macOS;
isolated tests and two full repeats passed. This was not a runner bug fix.
Exact-head publication/CI evidence remains a separate verification step.
