# PLAN

## Local Alarm Policy Kernel (2026-10-08)

- First implementation stage of the Oct7 design: pure bounded profiles, hardware
  targets and per-source repeat/OFF/acknowledgement transitions. Accepted source
  authorization and fresh elapsed-time bounds are inputs, not new risk detectors.
- Source/boot/generation/ordinal checks reject stale acknowledgements and results;
  duplicate evidence does not slide repeats. Acknowledgement survives a lower
  level until its original deadline; higher risk invalidates the pause.
- Plan: `superpowers/plans/2026-10-08-local-alarm-policy.md`.
  Focused 148 tests and all 52 new tests passed; full unit suite has 4875 cases,
  zero failures/errors and 3 optional phone-copy tests skipped. Full Android
  unit/lint/compile/debug-build gate passed. Fresh Lint has zero errors and no
  issues in new alarm files; warnings elsewhere are not part of this change.
- The kernel has no runtime consumers. Room31, settings, notification/audio
  owner, clinical prediction, targets and therapy remain unchanged. Runtime
  service/storage/UI/source integration and real-device acceptance are separate
  required stages; there is no claim of fixed or enabled phone alarms yet.

## Local Alarm Escalation Design (2026-10-07)

- Continue alarm reliability work before the pending COB display implementation.
  Written proposal: `superpowers/specs/2026-10-07-local-alarm-escalation-design.md`.
- Opt-in typed-source coordinator, bounded ramp/repeats, urgent-low floor,
  exact global OFF and fresh alert-only resume. Explicit source acknowledgement
  pauses one repeat interval; it does not resolve risk or silence other sources.
- Dedicated user-started foreground lifecycle and additive local-cycle storage;
  no clinical threshold, therapy, Telegram retry or local Nightscout change.
- Written human review precedes implementation/TDD. Fresh original-incident
  phone evidence, full Android quality, migration and separately consented
  installation/audio acceptance remain release gates. No runtime change yet.
- Phone is not available through ADB at this design stage. Partial historical
  evidence is not full-night validation. Existing-rule tests are not a fix.

## Persistent Food Curve During COB (2026-10-05)

- Human acknowledged the full-tail/continuous-display proposal. Working selection:
  existing yellow food-only influence, with its accepted origin retained during
  new CGM/COB arrivals and atomic replacement after the next accepted result.
- Written design:
  `superpowers/specs/2026-10-05-persistent-cob-food-curve-design.md`.
  Preserve full-tail scrolling/explore state and distinguish current, updating,
  outdated and unknown endpoints. External reference COB never silently rescales
  the accepted announced-food curve or becomes a physiological end-time claim.
- Written human review precedes implementation. No runtime change yet; existing
  GI conservation/limits, clinical freshness, forecasts/targets/therapy and cadence
  stay unchanged. One bounded UI snapshot, no worker/poller/Room migration.
- After approval: genuine RED lifetime/authority/viewport regressions, scoped
  implementation and full Android quality/publication CI; separately authorized
  fresh-backup installation and natural awake graphical acceptance. Never inject
  fake food/GI or force therapy to fill missing evidence.

## Full Carbohydrate Display (2026-10-04)

- User approved a bounded GI shape correction only for the display. Full
  remaining food influence, explicit provenance/unknown fallback, Room31 and
  accepted-cycle telemetry are implemented with domain/engine/migration/UI tests.
- GI coefficients are uncalibrated; clinical prediction and target management
  remain unchanged. No GI-dependent therapy tuning is in this stage.
- Focused results and implementation checklist are in
  `superpowers/plans/2026-10-04-full-carbohydrate-curve.md`; design in
  `superpowers/specs/2026-10-04-full-carbohydrate-curve-design.md`.
- Full Android quality and exact-source publication/CI passed at88b14bc7. Fresh
  coherent backup, disposable Room30->31 migration and working APK installation
  passed; installed hash, protected settings and runtime checks were verified.
  Awake Overview/full-tail visual acceptance awaits human unlock. No synthetic
  therapy/GI injection is allowed; natural missing GI remains a device-test
  limitation, not grounds to invent a measured value. The stage is not complete.

## Phone Resource Review (2026-10-05)

- Passive before/after CPU/RAM series and source/query review are complete;
  detailed evidence stays private. Different workload, foreground and restart
  state prevent claiming an update benefit. No optimization has been deployed.
- Prioritize UI input coalescing before expensive mapping, exactly bounded
  sensor-block telemetry for daily diagnostic inference, and profiling bounded
  housekeeping batches. Confirm query/method attribution and old/new result
  equivalence before implementation. These are candidates, not proven savings.
- Preserve every-new-current-CGM recalculation, accepted provenance and all
  target/therapy safety gates. No AAPS change, reduced clinical cadence, shorter
  medical retention or phone power-policy change is authorized by this analysis.

## Meal Timing Research (2026-09-27)

- MealStateRepository stores original intents, explicit AAPS revisions and full
  posterior distributions transactionally (Room28->29). Correction/tombstone
  invalidation, CAS, duplicate samples, restart and failure rollback are covered.
  Manual input is persisted directly; protected AAPS imports atomically stage
  durable receipts (Room29->30). Exact identifiers only, conflated wakeups and
  startup redrive; early acknowledgements/tombstones wait for their input.
  Conflicts are quarantined. Pre-commit storage failure, legacy backfill and
  merge/split remain unresolved; diagnostics do not prove clinical completeness.

- Guarded CGM posterior persistence now checks storage revision, applied AAPS
  receipt, pending correction/quarantine and causal estimator evidence in one
  Room transaction. Rejected updates do not mutate state; SQL errors roll back.
  Live causal prediction production, trusted runtime capture and initial priors
  remain pending. This boundary alone does not enable meal-start notifications.

- Passive next-sample prediction now reuses the frozen V3 engine without moving
  inferred meal onset. Exact next-grid sampling, calculation completion deadline,
  explicit prior error scales, immutable evidence and real-engine-to-Room tests
  are implemented. No default noise/calibration or clinical confidence is assumed.
  Live capture/orchestration, learned error models and stage transitions remain.

- Planner retains all declared insulin worlds and rejects incomplete matrices.
  Plausibility uses aggregate stage mass so scenario subdivision cannot hide a
  conflicting timing preference. Research decisions still cannot notify.
- Room 27->28 adds an atomic quota ledger and explicit claimed aliases, retaining
  cooldown across restart. It is not a clinical authorization or a delivery
  coordinator; live freshness/permission checks and full reconciliation remain.

- Conditional matrix now crosses meal/start/delay cases with explicit insulin
  schedules, preserving scenario IDs and cache separation; joint/work budgets
  reject excess without truncation. Controller schedule generation remains pending.

- Frozen forward forecasts support explicit hypothetical future insulin impulses
  using the existing profile/DIA/onset/ISF kernel, without changing historical
  input or writing therapy. Remaining units and scenario total are explicit.
  This is not an AAPS controller model and cannot authorize notifications.

- Research `simulateUncertain` connects bounded expansion to conditional
  simulation with original weights/parent linkage, cancellation and complete
  batch return. Uncertain future onset remains unsupported rather than ignored.
  No runtime or notification integration; future control/uncertainty still pending.

- Implementation tracks `superpowers/plans/2026-09-27-meal-state-timing-planner.md`.
- Explicit boundary/profile expansion preserves parent weights and source
  metadata; budget/underflow reject without truncation. Endpoint weights are a
  research assumption, not validated probability or continuous-interval coverage.
- Bounded simulator now assembles the start/delay matrix for explicitly discrete
  six-hypothesis cases, caches identical calculations and rejects ambiguous input.
  It emits conditional means, not a validated planner uncertainty envelope.
- Pure estimator/planner, frozen engine/input copies, food/insulin components,
  uncertainty admission and canonical food replacement have test coverage.
- Conditional forward glucose path reuses V3 on isolated copies, with fixed
  historical state and bounded 60..720-minute research horizon. Modeled residual
  food/insulin and clipping are explicit; extended modeled UAM is unsupported.
  Future controller actions and calibrated trajectory uncertainty remain absent.
- Still pending: ingestion edge-case recovery/release review, complete scenario trajectories
  and calibrated uncertainty, replay, notification coordinator, runtime/UI and
  device validation. Research results do not authorize meal-start notifications.

## Contrast And Target Diagnostics (2026-09-27)

- Contrast fix tested, built and installed in place; Overview warning and
  Forecast inspected. No therapy change. See AI_NOTES.md and contrast artifacts.
- Target Manager ACTIVE and recent AAPS target receipt confirmed over USB.
- Follow-up: retain original decision reason when suppressing a duplicate;
  display failing horizon, sample count, MAE, bias and CI coverage for reliability
  decisions. Add regressions without changing therapy gates or dispatch cadence.
- Audit alone does not establish forecast accuracy or clinical effectiveness.

## Compact Meal Portions (2026-09-27)

Current follow-up, 2026-10-01: manual80g cap separation and immutable exact
submission/restart guards are implemented. Normal/enlarged1.8x Russian light/dark
rendering and local exact80g/error callbacks pass Robolectric tests. Canonical
read-only history, revision-aware cache and walk-forward comparison are implemented
as inactive research APIs. Full quality checks are recorded in AI_NOTES.md.
Phone/font acceptance now passes11 UI tests plus1 isolation test on the approved
isolated build. Eight fresh Russian light/dark1.0/1.8/error/80g images were inspected;
actual font density and exact callback-once behavior are asserted. Systemfont0.81
and the working APK remain unchanged. Independent real held-out scores remain
unavailable and the learner stays inactive. No working-app update, therapy test
send or database backfill. Extra isolated-variant lint has6 pre-existing errors
from intentionally stripped permissions/components; debug lint has0 errors.
The older numbered evidence below is historical, not current installed-state proof.

- Selected visual direction: variant 2 with the revised porridge-and-bread large
  portion. See [spec](superpowers/specs/2026-09-27-compact-meal-portions.md) and
  [staged plan](superpowers/plans/2026-09-27-compact-meal-portions.md).
- Stage 1: typed portion settings, validation, persistence and advanced editor
  implemented; defaults 10/25/60g within 7-15/15-40/40-80g. Calorie visibility
  preference defaults false and is now consumed by the compact meal dialog.
  Full4438 unit tests (no failures/errors,3 skipped), lint and APK builds pass.
  Isolated UI interaction test compiled, but target installation was rejected
  by the phone. No production deployment or therapy changes.
- Stage 2: six-picture draft, shared settings, optional calories, exact correction
  and immutable confirmation implemented locally. Full4440 tests have no
  failures/errors (3 skipped); lint has no errors,301 warnings/5 hints. All APK
  variants build. Device UI/contrast/font checks remain pending.
- Remaining gates: independent real held-out validation and activation decision.
  Current phone/font acceptance is verified only for the isolated build.
  Manual80g/retry/provenance code and inactive
  history reader/cache/evaluation tests are implemented; no activation is implied.
- Follow-up evidence supersedes the initial device blockers above: compact dialog
  was installed and 33 isolated UI tests passed in earlier runs. Provenance now
  flows through confirmation to persistence in local source, not the installed APK.
  Room v27 migration passed 41 focused tests, including a disposable September 1
  phone snapshot and Room native-SQLite schema validation. New UI provenance
  assertions compile; device execution of those assertions remains pending.
- Do not report history learning as active or Room v27 as installed on the phone.

## Background Runtime Performance (2026-09-27)

- Restored the existing foreground localhost transport on the connected phone;
  confirmed an actual AAPS target record, not only WorkManager completion.
- Copilot forecast maintenance now avoids full-history deduplication and deletes
  expired rows in batches of 256. Atomic accepted publication remains unchanged.
  Room regression, full unit/lint and debug assembly passed. Copilot updated
  in place on the phone; installed hash, background service and fresh runtime
  data verified. Natural post-update target receipt confirmed in AAPS at
  00:55:12 and Copilot SENT at 00:55:13, without a test therapy command.
- AndroidAPS temporary-target chart now uses one overlapping-range read per
  build, isolated from therapy lookups. Module tests and APK build passed.
- AAPS also updated in place, installed hash verified, startup pump READSTATUS
  and a subsequent connection cycle succeeded. Target graph layer measured
  0.10-0.39 seconds; larger basal/IOB layers remain costly.
- Confirmed screen-off sample completed: AAPS104.2% and Copilot37.0% mean CPU
  of one core. No overall CPU reduction proven; first mixed-screen capture
  excluded. Next address revision-aware rebuild coalescing and repeated
  basal/IOB graph queries and visual-work scheduling with replay checks.
- Nightscout long-held socket wake lock requires lifecycle/reconnect testing
  before changing it. Do not disable it or restrict AAPS/CGM/Bluetooth to reduce
  a CPU figure. Other-app restrictions require a separate choice because they
  may suppress banking, messaging or wallet notifications.

## Photo Meal Nutrition (2026-09-13)

- Local nutrition models, deterministic calculations and validation are complete
  for the current bounded contract; photo image preparation and strict typed
  response parsing are also implemented with focused tests. A foreground-only
  gateway coordinator, explicit draft editor and accepted-runtime food-effect
  timeline boundary are now covered by focused tests as well.
- A separate typed `MEAL_PHOTO` server job contract and route are now covered by
  synthetic-worker tests. It accepts one bounded canonical JPEG and returns only
  the food-estimate schema; it is not a text fallback and does not call a real
  model yet. Camera/picker wiring, contained vision launcher, editable
  confirmation, Room persistence, the chart/UI layer and device verification
  remain separate dependent stages.
- [Implementation status and acceptance boundaries](2026-09-13-photo-meal-implementation.md).

## R1b Android Signed AI Client (2026-09-13)

- Minimal CHAT capabilities/submit/status/cancel client implemented over the
  existing connection manager and hardware request proof.
- Focused 36 tests and full Android verification passed: 4392 tests with three
  conditional skips, no failures/errors; lint and all three APK builds passed.
- Spec review accepted the clock-recovery correction. Quality findings were
  reproduced RED and fixed; independent recheck accepted both fixes.
- Phone read-only baseline completed. Isolated installation was rejected by
  the system; device tests require installer approval, without bypassing it.
- The connected phone later changed to a device without Copilot. Confirm the
  target before any installation; do not reuse the previous phone's baseline.
- The confirmed original Copilot phone was updated with `adb install -r`, with
  matching read-back APK hash, preserved package data directory and successful
  cold start. Device data-flow and live-AI checks remain pending. The immediate
  empty-state screenshot does not establish whether the glucose database was
  empty; the contained live-AI route was not verified during that check.
- No existing clinical callers, photo inference, server deployment or therapy
  behavior changed. [Client scope and gates](2026-09-13-android-ai-jobs.md).

## R1a Signed Server AI Job Transport (2026-09-13)

- Implemented an optional owner-scoped, app-key-signed `CHAT`/`TEXT` transport
  over the existing activation, proof, metadata ledger and executor modules.
- The route is injected only; the default bound app remains inference-closed.
- Local tests use synthetic contained workers only. No real Codex call, server,
  phone, secret or medical payload is part of R1a verification.
- Exact wire, byte, status, schema, digest, ownership and lifecycle contract:
  [signed server AI jobs](2026-09-13-signed-ai-jobs.md).
- R1b Android transport, production authority/attestation/device operations,
  online contained launcher, route validation, staging and deployment remain
  pending gates. R1a is not a production inference release.

## Gentle Alerts and Telegram Integration (2026-09-13)

- Integrate the isolated alert/Telegram candidate on the published September source.
- Preserve server-AI settings, secret namespaces, activation flow and backup exclusions.
- Verify the full Android unit suite, lint and debug build before updating local main.
- Keep phone installation, bot credentials and real recipient verification separate.
- Design and setup: [soft alerts and Telegram](2026-09-13-soft-alerts-telegram.md).

## Stage map

### Stage 1: Governance baseline (completed)
Goal:
- Introduce persistent project operating rules and documentation anchors.

Inputs:
- Existing repository structure and current implementation state.

Outputs:
- Root `AGENTS.md`.
- Baseline docs in `docs/*`.
- Initial `AI_NOTES.md` entry.

DoD:
- Governance files exist in repo root/docs.
- Commands and quality gates are explicit.
- Team can run new threads using contour process.

Verification:
- `ls AGENTS.md docs AI_NOTES.md`
- manual review of command sections and contour instructions.

### Stage 2: Architecture + invariants hardening
Goal:
- Expand architecture docs with sequence diagrams and component ownership.

Outputs:
- Updated `docs/ARCHITECTURE.md` and `docs/INVARIANTS.md` with deeper integration contracts.

Verification:
- Contract checklist review against code paths in automation/predict/action repositories.

### Stage 3: Quality gate unification
Goal:
- Add standardized lint/typecheck for backend and integrate into CI process.

Outputs:
- Backend lint/typecheck commands.
- CI configuration and `docs/DEVOPS.md` update.

Verification:
- Green pipeline on build/lint/test/typecheck.

### Stage 4: Security review cycle
Goal:
- Perform structured threat review and mitigation tracking.

Outputs:
- Updated `docs/SECURITY_REVIEW.md` with MUST/SHOULD/NICE items and status.

Verification:
- Checklist pass and remediation backlog linked to code tasks.
