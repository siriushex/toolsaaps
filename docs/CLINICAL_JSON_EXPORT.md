# Local Clinical JSON Export

## User Flow

AI analysis > Report > Export JSON is available without AI credentials or network.
Choose 7, 14, 30, 60 rolling 24-hour days, or Full, then create a document through the
Android system picker. Thirty days is the default. Cancelling the picker sends
nothing and does not start export. The file contains personal health information
and clinical notes; the user chooses its destination and any later recipient.
The export itself does not upload data, call a model or change therapy/settings.

Full means all retained local clinical history before the captured cutoff. It
cannot restore purged records or fetch server-only history. Future timestamps
are excluded. The selected range, time zone and per-section retained bounds/counts
are explicit; they do not assert complete upstream synchronization.

## Contract

- `schema`: `copilot.clinical-history`; `schemaVersion`: 1; reviewed Room schema 37.
  An unknown database version fails instead of silently missing new tables.
- `range`: inclusive lower bound, exclusive cutoff in UTC epoch milliseconds.
  Full has a null lower bound. Units and interpretation notes accompany the data.
- `currentProfile`: typed clinical settings, including selected insulin profile,
  energy profile, meal portions, target schedule and controller safety settings.
  This is current state, not a reconstructed historical profile. Settings are
  read immediately before the database snapshot, not in a shared transaction.
- `glucose`: ten-minute UTC bins separated by source and quality. Each contains
  first/last, mean, minimum/maximum with timestamps, sample/finite counts and gaps.
  Empty bins are omitted; no interpolation or rejection of low-quality points.
  Negative legacy timestamps remain individually represented in Full. Bin
  statistics cannot reconstruct exact trajectories, TIR durations or insulin onset.
- `sections`: 42 reviewed clinical tables, original selected rows without count
  caps. Includes therapy events, telemetry, forecasts, calibration, profile and
  sensitivity history, meals/absorption, target decisions, commands and statuses,
  alerts, activity/context, energy and clinical reports. The implementation's
  `ClinicalJsonSchema.sections` is the exhaustive table/time-column registry.
- Each section declares its selection rule, timestamp basis, available local
  bounds/count and exported count. Current-state tables also include older state;
  interval records overlapping the range are retained. Meal scenario/absorption
  rows use their parent episode timestamp. Override rows use their update time.
- Valid JSON-valued columns are decoded structurally, including nested payloads.
  Malformed text remains a string. SQL integers, including stored boolean 0/1,
  remain integers. Nonfinite numbers become null with `_nonfiniteFields` names.
- Sources, revisions, deleted/invalid states and delivery status are not rewritten.
  Parallel records/revisions are not additional meals/doses. GI and estimates
  retain stored provenance; export performs no clinical recalculation.
- `complete:true` is written only after every selected section succeeds. It
  describes export completion, not complete clinical coverage.

## Privacy And Lifetime

No whole preferences/account dump. Raw transport staging, pairing journals,
operational free-text logs, sync counters and internal playback/dedup state are
excluded with explicit reasons. Credential-named structured fields and transport
idempotency keys are omitted; credential-named telemetry values are null-marked.
Clinical free-text notes are intentionally retained, so users should review the
file before sharing. The JSON is not encrypted by this feature.

The export uses a separate read-only WAL transaction and streaming JSON, not a
writer transaction or an in-memory database copy. Only one glucose bin and one
clinical row/payload are materialized at a time. The finished private cache file
is flushed and synced, then copied to the selected document. A streaming SHA-256
readback must match its length and digest before the UI reports success.

An activity-scoped ViewModel owns work across recomposition/navigation. Cancellation
or error removes only the document created for that export; cleanup failure is
explicitly shown. A restored saved-state receipt cleans an interrupted document
without restarting export. Force-stop/provider failures can still leave a partial
document or private cache file; a plain file must never be treated as a backup or
proof of success. Ordinary completion/cancellation removes the private temporary.

## Verification

Tests exercise actual Room schema 37 with native SQLite on Android 31 and 35,
concurrent clinical writes, all periods, more than 1200 records, mixed glucose
sources/quality/extrema/gaps, strict JSON preservation, linked-row coverage and
credential exclusion. Store tests use an actual document provider and readback.
ViewModel tests cover duplicate clicks, immediate cancellation, restored work and
cleanup failure. Native Compose tests cover all five options, explicit save,
cancellation and 320dp with double-size text. Exact results belong to AI_NOTES and
the task checkpoint; local checks do not prove behavior on the intended phone.
