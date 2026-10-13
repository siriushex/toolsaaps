# Infusion Set Indicator Implementation Plan

Use superpowers:executing-plans and test-driven-development. Human approved the
written specification. Goal: compact read-only timing/delivery indication and one
silent maintenance reminder at 72 elapsed hours from verified occurrence.

## Architecture

Pure policy and strict bounded canonical-event reader; separate reminder DataStore,
serialized repository, unique WorkManager generation, low-urgency notifier and
passive Overview details. No Room schema migration, clinical scans/network,
CGM requirement, arming gate, new diagnosis thresholds or therapy/audio changes.

Only explicit original date/mills/createdAt can establish occurrence. Resolve
conflicting timestamps/tombstones/unverified receipt fallback as unknown, not age0.
Generation is occurrence-scoped, stable across import IDs; invalidation cancels
obsolete work but retains attempt watermark so older records cannot renotify.

Global mute coordination precedes repository mutex. Commit source and claim before
scheduling/posting. Worker rereads bounded canonical source and deadline; stale
input cannot notify. A persisted claim has unknown outcome after interruption and
is terminal. Known permission/channel blocks get at most one retry after capability
restoration; muted due reminders are suppressed, not replayed on mute expiry.
Ordinary foreground/source reconciliation restores pending work without a periodic
service. WorkManager scheduling is best effort, not an exact alarm.

Delivery display uses existing accepted policy evidence only. Any row not currently
bound to fresh accepted evidence is explicitly retained history, never a fresh
device fault. Replacement timing cannot resolve independent delivery episodes.

## Task 1: Pure Evidence And Timing RED/GREEN

- [x] Add bounded reader/policy tests: missing date, received-at fallback, explicit
  timestamps/units, contradictory/future/nonfinite/overflow, duplicates, deleted or
  corrected latest events, sensor/reservoir/prime and Copilot echoes do not reset.
- [x] Observe missing API RED, implement reader/policy. Verify exactly 71:59:59/72h
  and elapsed DST/timezone independence, unknown never normal/age0.
- [x] Add bounded DAO query/observable of canonical change types (33 candidates
  detect cap32 overflow). No full-history observer or clinical ingestion rewrite.

## Task 2: Durable Reminder RED/GREEN

- [x] Test and implement separate typed DataStore with strict roundtrip/corruption
  failure, source generation, due, attempt status/count and chronological watermark.
- [x] Test serialized source replacement/deletion, stale worker, persistent restart,
  schedule-after-save, no-CGM/offline/disarmed scheduling, duplicate suppression,
  unknown post outcome and bounded known-block restoration retry.
- [x] Use uniquely named WorkManager job with generation input, no network/arming
  constraints. Worker rereads canonical source under serialization before posting.
- [x] Add silent IMPORTANCE_LOW notifier with permission/channel checks, separate
  identity, only-alert-once, private content and passive app-opening intent.
- [x] Bind committed source invalidation/startup and foreground reconciliation.
  Errors fail explicitly without impacting clinical startup or retrying therapy.

## Task 3: Read-Only UI And Existing Delivery Evidence

- [x] Add narrow read-only accepted/retained diagnostic adapter without modifying
  clinical thresholds or alert claims. Test stale/duplicate/source changes/invalid
  observations, recovery and replacement independence.
- [x] Add compact existing infusion asset, age/due/unknown distinction and 48dp
  passive Details. Separate from Bluetooth, no new change-event writer.
- [x] Details include source occurrence/deadline, 72h, notification limitations,
  existing delivery reason/time and honest unavailable/retained evidence labels.
- [x] Native light/dark, 320x640/taller/landscape/large-font header bounds and zero
  source/therapy writes; private screenshots inspected.

## Task 4: Release Gate

- [ ] Update architecture/invariants/PLAN/AI_NOTES and spec with actual behavior.
- [ ] Complete Android unit/lint/Kotlin/APK gate and signature/checksum inspection.
- [ ] Fetch/divergence/privacy review, explicit staging, commit/push existing branch
  and inspect exact-SHA Verify CI. No auto-merge or phone installation.
- [ ] Update task checkpoint with actual completion evidence and limitations.

Observed:26 core/platform cases and80 combined native/source/diagnostic cases
passed. Storage-help and finishing-worker retry REDs were reproduced; final fixes
await the complete gate. The first complete run stalled and was stopped; its
partial output is not success. A private sequential/1g test-JVM verification run
is active. No production Gradle configuration or device settings changed.

Update: full5320 cases/462 suites passed, zero failures/errors,4 private opt-in
skips. Final native screens checked. Lint remained active without a terminal
result in two configurations; both owned runs stopped, logs preserved. This is
not a complete release gate. Infusion source is included in the user's explicitly
authorized source-only CI stage, as the current Overview depends on it. No phone
changes or new APK acceptance.
