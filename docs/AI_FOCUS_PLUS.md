# Focus+ Android Workspace

## Scope

Approved 01 Focus+ replaces the inner heading/owl/Info strip with one global
header owl. Its menu opens AI diagnostics, the main drawer or settings;
diagnostics has a back action. Global alert actions are unchanged. Fresh-glucose
color and existing finite/lifecycle/reduced-motion/urgent/power policies remain.

The pinned chat composer has six 48 dp tools: photo, file, recording/stop,
questions, read-replies toggle and send. Reading replies is not dictation.
TEXT capabilities still disable photo, new recording and TTS; an active recording
can always be stopped. Attachments remain visible above the four-line input;
selection, questions and discussion never send automatically. The waiting row
has no second owl. Pending semantics remain after the finite header gesture rests.

## Display Is Not Preparation

ClinicalDisplayRepository owns an aggregate-only display projection in two fixed
Room telemetry rows: clinical_display_snapshot_v1 and clinical_display_attempt_v1,
source clinical_display. No schema migration, new file, raw series, profile or
rendered provider prose is added. The existing clinical_reports table remains
the original report history. Successful LOCAL_READY/COMPLETE source rows are
observed separately and normalized; partial, failed and malformed results cannot
become a displayed answer. COMPLETE uses the existing strict typed parser.

The dated projection contains three validated summaries and, only after actual
successful receipt, the typed advisory result and real provider/model metadata.
It has no send/consent/PDF/retry capability. The original action state and
two-minute PREPARED_DATASET_TTL_MS are unchanged. Full details, period metrics,
disclosure, explicit preparation/send, cancellation and guarded PDF/retry remain
under Details and report actions. A discussion appends facts/source/limitations
to the unsent chat draft.

Snapshots are at most 128 KiB UTF-8 and attempt records at most 1024 characters.
Writes use the existing Room transaction API. The normal rolling cleanup retains
only these two fixed source/key/id tuples; they do not accumulate history.
The Room AI-history numeric allowlist excludes both records. Display reads
cannot upload the projection, restore authorization or invoke a provider.

## Local Daily Refresh

An independent WorkManager periodic task is anchored to the next local 05:00,
plus one unique catch-up on process start. It reuses NightlyAdviceSchedule for
calendar/DST boundaries. Android can defer work; this is not an exact alarm.
Power saving defers the local task. No network constraint or therapy permission
is required. The existing local dataset builder is reused with a 120-second
deadline; only summaries are persisted. No new model job is scheduled.

A transaction claims the latest completed 05:00 slot before calculation. Token,
process owner, cutoff, zone and wall-clock checks prevent concurrent duplicates,
late overwrites and rollback. Failure/cancellation retains the last success and
does not automatically replay that slot. A foreign-process RUNNING marker is a
failed/interrupted local attempt, not an instruction to reissue a request.
Explicit local retry can recover a failed or corrupt projection. A new day may
run once, without a backlog of previous days. A future-dated retained result is
still visible after clock rollback and is not relabelled as a new result.

Local summaries are labelled Local summary, not AI output. This source stage
does not activate the separate server daily-review API, schedule or model.
No target, dose, carb, Eating Soon, alert or prediction algorithm is changed.

## Acceptance

Synthetic tests cover Room close/reopen, failures/cancellation, concurrent claims,
clock/zone changes, typed result validation, cleanup, history export exclusion,
05:00/DST scheduling, compact native controls and explicit consent/PDF boundaries.
Native rasters must be inspected at 320 dp, short height and large font before
acceptance. Full Android unit/lint/compile/build, exact-head CI and intended-phone
USB acceptance are separate gates; their results belong in the stage receipt.

## Local Verification (2026-10-17)

- Three initial behavioral REDs preceded production changes. Subsequent tests
  exposed rollback loss and the checked strict-parser exception; both corrected.
- Full unit execution:6130 cases,6123passed, six existing skips and one failed
  UI finder. The large-font node existed only in the unmerged semantics tree;
  corrected the finder, not the renderer or an assertion on absent content.
- Final121 affected tests all pass, including all50 native workspace cases and
  actual Room reopen, cleanup, history exclusion, concurrency and failure tests.
  Discussion retains the selected period through refresh/failure and never sends.
- Inspected native320x480, large-font and chat pending rasters. Period is visible
  with source; large metrics use full width rather than breaking long labels.
- Final test/lint/assemble command exits0. Lint has zero errors/new issues against
  the preceding source stage:376 existing warnings/eight hints. Main/test input
  hashes match before/after the admitted gate. Package/debug signer verified.
- An intermediate lint run overlapped source edits and was rejected, not treated
  as completion; no lint baseline or suppression was introduced to make it pass.

These are synthetic/local software checks, not real model, overnight device or
clinical validation. Publication/CI and intended-phone acceptance are recorded
separately after their actual outcomes. No new provider or therapy admission.
