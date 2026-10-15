# Daily Glucose Facts

Stage 1 of the approved daily-review/admin design. This is an internal,
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

## Remaining Stages

7/21-day comparable baselines and the wider candidate catalogue; bounded
meal/GI/controller/notification/physiology adapters; encrypted report lifecycle;
dedicated daily-model policy/result validation; durable schedule and private
admin admission/UI. Approved prompt text is documentation, not an active prompt.
The local synthetic admin mockup is not deployed or included in the app.

Tests establish the stated software behavior, not medical validity, host
admission or actual CGM accuracy. No target, insulin, ISF/CR or Eating Soon
control path is changed.
