# Periodic Client History Upload

## Narrow Main Integration

Owner chose only the upload feature and necessary dependencies on 2026-10-19.
This branch starts at main 7ca72a24, not the combined release. Scheduler/settings
come from a53f6505; signed history transport and Room export dependencies come
from b32f7f3d (including its earlier transport contract). Observed insulin
configuration comes from e74f5de1. Unrelated PR #7 UI, chat, photo, daily review,
clinical algorithms, database migrations and server deployment are not imported.

## Behavior

- Existing signed activation remains mandatory. Background upload defaults to
  enabled with a 120-minute interval. Choices: 60, 120, 180, 240 minutes.
- Settings persist independently of therapy arming. Background OFF cancels
  automatic work but permits an explicit manual upload. Repeated manual taps
  coalesce. The UI acknowledges queueing, not successful upload or analysis.
- One WorkManager periodic clock requests a serialized drain. Connected network,
  restored session and current power policy gate execution. Power pause retains
  the clock so it can resume without local monitoring or therapy enabled.
- Each drain has a 120-second budget and at most four slices of 64 bounded
  source pages. Partial scans enqueue a stable five-second successor. Successful
  backfill progress does not trigger escalating retry delay. Android may defer
  work; force-stop and exact clock deadlines are not supported guarantees.
- Pending encrypted plans, receipt checks, stable record identities/revisions,
  tombstones and a separate metadata-only export index preserve exact recovery.
  No writes or migrations to the medical database. No raw payload logging.
- Core history includes glucose, recorded therapy, forecasts, effective
  sensitivity and allowlisted numeric telemetry. Recent six-hour context goes
  first; initial/yearly retention backfill and daily reconciliation recover older
  records/edits. This is not a complete clinical-database export.
- Current selected insulin profile and available seven-day onset/DIA telemetry
  remain labelled observed configuration, not proof of historical medication or
  delivery. Meal-timing research is explicitly unavailable in this narrow port;
  no timer, readiness recommendation or absorption algorithm is imported.
- Only temporary transport failures retry. Auth/device/storage/malformed data
  errors preserve state without being misclassified as temporary connectivity.

## Server Boundary

The existing signed history contract is reused. This source integration does
not create or change an API host, model session, clinical report, recurring model
job, recommendation inbox or therapy writer. Existing main chat routing remains
unchanged. A receiving server must separately provide the authenticated history
routes; no request is made against a live device/account for local verification.

Future advisory reviews require a separate per-device cadence contract, complete
acknowledged source revision, bounded/coalesced jobs, evidence and uncertainty,
retained recommendations and idempotent consider/reject actions. ISF, CR, DIA,
onset, infusion issues and sensitivity changes cannot be inferred as established
facts from an upload receipt. Considered must not be represented as applied.

## Verification And Rollback

Main-based local verification passed on 2026-10-19 using Java 17 and one worker:
83 focused tests; full Android run 5625 tests with 5619 passing, six optional
private-data skips and zero failures/errors. Lint has zero errors, 362 warnings
and eight hints, with no issues in added source. Debug assembly succeeded.
Full test/lint/assemble took 23m58s. Source hashes stayed unchanged, and native
320dp/1.8x font screenshots were inspected. The earlier development-branch
green result is not used as evidence for this port.

Regression scope includes signed transport, Room paging/edits/deletions and
recovery, profile provenance, worker admission/retry and native settings UI.
Verify exact-head CI and re-fetch main before merge. No APK installation or
server rollout follows automatically. Disable background transport using its
switch; preserve pending files and export metadata rather than deleting them.
Device/Doze, initial-backfill latency and battery consumption remain unverified.
