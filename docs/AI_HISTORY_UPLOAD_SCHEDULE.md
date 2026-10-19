# Periodic AI History Upload

## Scope

Stage one of owner-requested advisory reviews: configurable durable client
history upload. This is not a scheduled model call, a clinical recommendation
publisher, or a therapy writer. No server, model/login, AAPS or phone is changed.
Work is isolated in the periodic-ai-sync-20261018 checkout from base 74fcf331.

## Client

- Existing activated connections keep background upload enabled by default.
  New installations cannot upload without their existing signed activation.
- Interval choices are 60, 120, 180 and 240 minutes; default 120. Values are
  persisted in the existing settings DataStore. Unknown persisted intervals
  use 120, not a shorter unbounded interval. Explicit invalid edits are rejected.
- One unique WorkManager periodic task replaces the process-owned 15-minute
  maintenance coroutine. UPDATE changes its interval without adding another
  periodic task. Startup restores credentials before schedule admission, so
  an initially empty UI state cannot cancel a worker restoring the process.
  Workers await the shared initial load instead of resetting connection/readiness
  UI on every round; each signed request still rereads and checks credentials.
- The task requires a connected network and checks session/background/power
  admission again when running. Its settings are independent of therapy arming.
- The periodic clock coalesces one automatic drain chain. Each drain has a
  120-second budget and at most four slices of 64 existing export pages.
  Successful partial scans append one deterministic successor with a five-second
  initial delay. Enqueue-before-success recovery reuses its ID. Only actual
  transient errors/timeouts use retry backoff, not successful backfill progress.
  Continuation may run sooner than the selected normal interval. Initial large
  backfill and OS/offline delays can take longer than one normal interval.
- Exact pending payloads, record revisions, tombstones and acknowledgements use
  the existing encrypted client/Room export ledgers. No new transport schema,
  full-database copy, downsampling or medical-data logging is introduced.
- Only network/unavailable/rate-limit failures receive automatic short retries.
  Authorization, binding, malformed data and storage failures are not treated
  as transient network errors. Failed rounds preserve the pending ledgers.
- An explicit one-shot upload can run with background upload disabled. Repeated
  taps coalesce with KEEP. Manual and periodic passes serialize the complete
  source/profile operation; the existing export mutex also protects chat sync.
- Session loss cancels scheduled work. Power saving cancels queued manual work
  and cooperatively stops any in-flight upload, but retains the periodic clock
  so a timed pause can expire even without local monitoring/therapy enabled.
  Background OFF alone does not cancel an explicit manual request.
- Manual UI acknowledgement says queued, never delivered or analyzed. No exact
  wall-clock schedule or guaranteed execution during force-stop is claimed.

## Preserved Boundaries

Profile/onset context uses the existing observed-settings exporter. The source
still is a bounded core-history projection, not all clinical tables. Configured
insulin does not prove the medication for every historical bolus; recorded
insulin is not confirmed delivery. Export completion does not mean complete
medical evidence or successful clinical/model review.

## Remaining Advisory Stages

1. Versioned clinical dataset adapters: basal/SMB/delivery confirmations,
   profile history and effective settings, context, quality and completeness.
2. Server-side per-device periodic jobs after a complete acknowledged dataset,
   source-version deduplication, cancellation, bounded resource use and retained
   results. Do not reinterpret the current glucose-only daily schema as this.
3. Separate numeric analytical candidates and explanatory AI text; disjoint
   validation days, uncertainty and alternative explanations. Distinguish DIA,
   onset, configured ISF and dynamic/effective ISF. No guessed parameters when
   evidence is insufficient or delivery/sensor quality is suspect.
4. Native recommendation inbox with evidence, expiry and consider/reject/chat.
   Considered is not applied. Any future application needs a separate validated
   command flow and explicit preview/confirmation, not a server model action.

Urgent local glucose/delivery alerts remain independent of this 1-4 hour cycle.
Remote upload settings do not reconfigure their thresholds or audio.

## Planned Server Review Contract (Not Implemented Here)

- A signed, versioned per-device review preference must carry the selected
  cadence to the server. The client settings added in this stage are local
  transport settings only; they do not yet create recurring model jobs.
- Admit a review only against an acknowledged dataset revision. Coalesce jobs
  for the same device, source revision and analysis version. Duplicate uploads
  must not create duplicate recommendations. An offline phone or incomplete
  backfill must be visible as a coverage limitation, not a normal full review.
- Recent changes and historical evidence are separate inputs. Review the new
  interval against actual available 3/7/14-day cohorts, with coverage, exclusions
  and time-of-day counts; do not repeat a parameter change every upload interval.
- Compute features and validation outside the language model. Candidate ISF/CR
  findings must name their source units, profile version, recorded versus
  confirmed delivery, meal uncertainty and effective versus configured values.
  DIA/onset findings also need the known insulin profile and sufficient
  identifiable response evidence. Missing evidence produces a limitation, not
  a guessed precise coefficient or a claimed safe meal-start time.
- Delivery concern, changing sensitivity, illness/activity context and sensor
  uncertainty are separate hypotheses. A glucose rise alone must not be labelled
  as proven infusion failure or used to justify stronger therapy automatically.
- The recommendation envelope needs a stable ID/version, source revision,
  generation/expiry time, category, evidence references, exclusions, confidence,
  current setting when known, candidate when validated, and readable rationale.
  A changed insulin/profile version invalidates related old candidates.
- Persist recommendations independently of push delivery. Push is only a
  non-sensitive hint to fetch; the phone also reconciles an incremental inbox
  when opened or synchronized, so a missed notification cannot lose a result.
- Inbox actions are consider, reject and discuss, with idempotent signed
  acknowledgements and retained decision history. Considered is never applied.
  Applying a parameter, if separately implemented and validated, requires an
  explicit old/new preview, current-profile revision check, local safety gates
  and an actual application receipt. The model has no write tool for therapy.

## Verification

The final unchanged-source run completed successfully on 2026-10-18 UTC
(2026-10-19 in Tbilisi), using Java 17:

```sh
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- 6345 unit tests discovered across 560 suites: 6339 passed, six optional
  private-data/phone-copy checks skipped, zero failures/errors.
- All 18 new settings, schedule, cancellation/backfill and UI checks pass.
  The complete run also includes existing signed-history and Room recovery tests.
- Lint: zero errors, 378 warnings and eight hints; no issues in new source/test
  files. Debug APK assembly succeeded. Total run: 26m31s.
- Native Russian 320dp and 1.8x font-scale screenshots were inspected: labels
  and controls fit. Source manifests before/after the final run match.
- `git diff --check` and neutral operational-identifier review pass.

Device/offline/Doze behavior still requires a separate installation and
real-device check. Include background/manual overlap, process death during
an upload receipt, and chat latency during a large initial backfill: these
paths share the existing serialized Room exporter, and local tests do not
establish real-device timing or power consumption. Android force-stop is not
a supported guaranteed-delivery state. No release or live activation is
implied by local verification.
