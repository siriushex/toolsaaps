# PLAN

## RC1 Transport Privacy And Clinical Contract (2026-10-21)

- [x] Reproduce token-bearing URL logging on the actual pinned RC1 engine.
- [x] Add method/status-only logging, preserving requests and refresh behavior;
  all174 SDK tests passed, including four new synthetic regressions.
- [x] Verify admission and transport patches together:398 tests passed and the
  upstream checkout restored. No clinical runtime or device state changed.
- [x] Document exact profile/EPS/insulin semantics and remaining readiness gates
  in AAPS4_CLINICAL_PROFILE_CONTRACT.md.
- [x] Complete all16 recipe checks, including paired cleanup and preservation
  of concurrent edits; final combined helper succeeds and restores source.
- [ ] Verify exact-head CI/main integration.
- [ ] Implement catalog wire handling and typed observational projection.
- [ ] Verify scoped Android TLS and real JugglucoNG continuity on the Redmi.
- This source stage is separate from release0.1.6 and does not unlock targets.

## Internet Release 0.1.6 (2026-10-21)

- [x] Start from verified main with JSON export and countdown lifetime repair.
- [x] Increase versionCode to7/versionName0.1.6; retain package/signing identity.
- [x] Verify full local Android quality and release tooling: 6571 passed,
  6 private-input skips, zero failures/errors; lint zero errors/382 warnings;
  build successful; 14 publication-tool tests passed.
- [x] Verify signed APK metadata/class retention and current public signer:
  code7/name0.1.6, 39,609,340 bytes, all 7073 compiled app classes retained.
- [ ] Verify exact-head CI before publication.
- [ ] Publish only static APK/page/manifest with the previous pointer preserved.
- [ ] Independently download and verify public bytes, signature and Range resume.
- [ ] Merge reviewed source after checks and verify resulting main CI.
- No AAPS APK installation, clinical-admission unlock, server AI/profile change
  or therapeutic action. Clinical/profile/TLS/JugglucoNG work remains separate.

## RC1 Target Transaction Admission (2026-10-21)

- [x] Reproduce two target install/cancel admission failures before the fix.
- [x] Carry optional target-only admission into the executor and Room transaction.
- [x] Add real SQLite rollback/suspension and signed-refusal/no-replay tests.
- [x] Complete expanded native/SMS/database and full patched AAPS checks:
      224native +128app tests, instrumented compilation, lint and unsigned build.
- [x] Verify exact patch apply/reversal and full Copilot test/lint/build:
      clean RC1 restored,14recipe tests;6571pass/six prior skips,zero lint errors.
- [ ] Inspect exact-head CI, explicitly merge reviewed source and verify main.
- Typed clinical/profile projection, TLS and JugglucoNG device continuity remain
  separate gates. No production dispatch unlock, deployment or phone update.

## Controlled Main Integration (2026-10-21)

- Current backlog and isolated assignments: [WORKSTREAMS.md](WORKSTREAMS.md).
  Native subagents are unavailable in this session; briefs are not running jobs.
- [x] Reconcile main's independent history upload with the combined release.
- [x] Preserve signed photo/daily routes, silent countdown and local JSON export.
- [x] Use one durable history schedule and retain the existing insulin/timing
  context in its serialized upload path; remove the duplicate in-process loop.
- [x] Verify the merged tree locally:6568 Android pass,6private-input skips,
  lint/build/stock-SDK/APK guards pass; unchanged backend has successful CI.
- [x] Verify the exact merged source head on CI.
- [x] Explicitly integrate reviewed source into main and inspect its build:
      PR7 merged as ad678a84; Verify39580751668 succeeded (rechecked October21).
- Server/phone publication and clinical gate enablement remain separate actions.

## Local Clinical JSON Export (2026-10-21)

- [x] Review Room tables, local settings, coverage and explicit privacy exclusions.
- [x] Implement 7/14/30/60/full selector and streaming offline report/save flow.
- [x] First native Room/period/glucose/concurrent-write regressions pass.
- [x] Complete strengthened readback, cancellation, document-provider and native UI checks.
- [x] Complete full Android test/lint/build and APK size/class/signature guards.
- Exact-head source CI is recorded separately in the task checkpoint/PR after
  reviewed publication; local checks do not prove remote CI completion.
- No installation, release deployment or real medical export is part of this stage.

## Insulin Countdown Lifetime Repair (2026-10-21)

- [x] Reproduce one-minute disappearance, failed reconstruction and lost
  completed state in three failing regressions before the source fix.
- [x] Separate per-record estimate admission from countdown/completion lifetime.
- [x] Check stale-new-bolus rejection, explicit invalidation, bounded expiry,
  quiet notification and reconstruction through actual Room close/reopen.
- [x] Verify native header removal/recreation and Activity pause/resume; all
  seven native header cases pass, including320dp and2x text.
- [x] Complete full Android test/lint/compile/build and APK guards:
  6519passed/six private-input skips,zero test/lint errors;7014 application
  classes retained in the signed40,366,017-byte candidate.
- Reviewed source publication and exact-head Verify require separate receipts
  in the task checkpoint/PR, not an inference from local checks.
- This repairs presentation only. The existing research meal-timing planner
  remains unadmitted: onset is not an ideal or recommended meal-start interval.

## Seen Pause and Silent Insulin Countdown (2026-10-20)

- [x] Reproduce short ACK pause and absent insulin-only countdown before fixes.
- [x] Keep exact runtime acknowledgement and add a ten-minute minimum pause,
  independent of critical repeat; retain higher-risk preemption and global OFF.
- [x] Share one bounded insulin/model projection with header and silent system
  countdown; do not infer meal intent or delivery from positive IOB/correction.
- [x] Pass245 focused policy/owner/Room/notification cases.
- [x] Finish frozen combined Android tests/lint/build:6510pass,6private-input
  skips,0fail/errors; actual SDK TLS fixture passes. Backend:1942pass,27skip.
- [x] Pass APK signature/class/size guards:41,317,353bytes; inspect native header
  at320dp and2x text. Quiet-channel banner/customized-channel silence verified.
- Exact-head CI is a separate required source-publication receipt, recorded in
  the stage checkpoint and PR. Local checks never stand in for that receipt.
- Device installation, real notification/banner acceptance and separately
  consented audio tests are not performed by source verification.

## Internet Update Recovery And Size (2026-10-19)

- [x] Verify public APK bytes, hash, HTTPS transfer and Range support.
- [x] Reproduce75% interruption; add bounded recovery and explicit Resume.
- [x] Verify focused transport/controller/native UI and positive archive paths.
- [x] Measure APK contents and reduce119.24MB to39.38MB with preserved app classes.
- [x] Add CI size/removal/class-retention gate and its boundary regressions.
- [x] Complete full Android checks:6392 passed/six skips,lint/build,review/artifact gate.
- [ ] Publish reviewed source and inspect its CI before release publication.
- [ ] Verify the prepared internet APK and independently fetch published bytes.

## Stock AAPS 4.0 Compatibility (2026-10-18)

- [x] Follow owner direction: adapt Copilot first, leave AAPS source unchanged.
- [x] Inspect official RC1 data, authentication and incoming-treatment contracts.
- [x] Reproduce and fix false legacy treatment delivery through glucose-only receivers.
- [x] Pass focused/full Android tests, lint and build for the capability gate.
- [x] Verify capability-gate source 74fcf331 with both exact-head Verify runs.
- [x] Add bounded local refresh/bearer authentication without enabling v3 data.
- [x] Complete local v3 authentication checks: 6343 passed/six skips, lint/build pass.
- [x] Inspect exact-head CI for auth aafcfdce: PR/push Verify both succeeded.
- [x] Implement an isolated persistent v3 document/revision store and Room 35->36.
- [x] Complete store/migration checks:6367 passed,six optional skips; lint/build pass.
- [x] Publish store f387fb77; PR/push Verify39165726990/39165723595 succeeded.
- [x] Connect typed entries/status/lastModified to authenticated wire storage.
- [x] Check entry replay, partial updates, tombstones, filters and bounded paging.
- [x] Verify unchanged RC1 SDK over TLS including device-less calibration;
      full6381 passed/six skips,lint/build. Check SDK build without Android37.
- [x] Publish entries2c3abebb; both exact-head CI runs passed, including stock SDK.
- [x] Reproduce unsupported device status and add immutable typed create/read/history.
- [x] Verify device-status/full regressions and unchanged SDK round trip:
      6401 passed/six skips,lint/build,APK class/size guard passed.
- [x] Publish device-status67c31893; PR39220180297/push39220174285 both passed.
- [x] Resolve upstream JSON packaging conflict externally, retaining pristine source;
      real128 app tests, instrumented Kotlin compilation and release lint/build pass.
- [x] Verify published external JSON recipe regression checks in exact-head CI.
- [x] Add typed settings upsert/history/cleanup and target-only native ACK codec.
- [x] Verify actual RC1 SDK wire/crypto both directions; focused115 passed/one prior skip.
- [x] Complete full checks/CI for settings and target codec before live integration.
- [ ] Add durable pairing/counter/command journal and interrupted-commit reconciliation.
- [x] Add encrypted Room37 command journal with atomic wire outbox and no replay.
- [x] Check focused encryption/lifecycle/cancellation/capacity/reopen and migrations.
- [x] Complete full local checks/CI for journal:87ce15e8 Verify39315169287 succeeded.
- [x] Add isolated /storage subscriptions and postcommit persistent live events.
- [x] Check focused authentication/isolation/journal/rollback/restart/bounds cases.
- [x] Complete full local storage checks and unchanged SDK push fixture:
      6450 passed/six prior skips,lint/build/class-retention guard passed.
- [x] Inspect exact-source storage CI:0e7770d1 Verify39326467379 succeeded.
- [x] Implement PIN pairing, shared Hello/Ping counter and runtime ACK owner.
- [x] Add explicit-consent UI and paired-route isolation without legacy fallback.
- [x] Complete full local pairing/UI/native-SDK test/lint/build and size/class guard:
      6483 passed/six prior skips,zero errors; five UI and six recipe cases passed.
- [x] Inspect exact-source pairing-stage e8fa014a CI39383075611: all three jobs passed.
- [x] Add typed treatment wire CRUD/history with record ICfg and native unit normalization.
- [x] Exercise actual RC1 SDK treatment reads/patch/delete over TLS; retain the
      native mmol mismatch RED before correction. Additional retry/reopen checks follow.
- [x] Complete final treatment wire local test/lint/build:6495 passed/six prior
      skips,zero test/lint errors; APK retains all7003 compiled application classes.
- [x] Inspect exact-source treatment wire b5d3b3d7 CI39392761034: all three jobs passed.
- [x] Reproduce six expired/revoked/disabled command dispatch failures in real RC1 tests.
- [x] Verify the minimal receiver patch:183 native checks plus128 app tests,
      instrumented Kotlin,release lint/build and actual helper apply/reversal pass.
- [ ] Inspect exact-source receiver patch CI before source-stage completion.
- [x] Propagate target-only validity to the actual executor/persistence boundary;
      a receiver-only fix cannot admit live Target Manager publication.
- [ ] Complete typed clinical history and master-handler admission before opening
      production native Target Manager publication (AAPS4_PAIRING.md).
- [ ] Implement authenticated loopback v3 with durable collection revisions.
- [ ] Verify native paired Client Control and signed application ACKs before
      considering any AAPS command-bridge changes.
- [ ] Verify stock RC1 client and actual data/command reconciliation before update.
- Details and remaining gates: AAPS4_COMPATIBILITY.md. No device acceptance yet.

## Provider Token Usage (2026-10-18)

- [x] Preserve selected model/login; inspect public mapping and pinned usage schema.
- [x] Wire bounded post-response usage through local text and encrypted CHAT archive.
- [x] Check native, transport, compact, signed-job and isolation regressions.
- [x] Complete full backend: 1940 passed/27 skipped; scoped lint/compile/diff pass.
- [ ] Publish reviewed source and inspect exact-head CI.
- Separate admission remains for exact preflight accounting and protected rollout.

## Compact Budget Binding (2026-10-18)

- [x] Inspect pinned native source: heuristic estimate is not exact token accounting.
- [x] Reproduce false verification/zero-count/unbound-budget gaps in synthetic tests.
- [x] Bind budget to model/instructions and record actual reserve accounting.
- [x] Complete full backend: 1907 passed/27 skipped; scoped lint/format/compile/diff pass.
- [ ] Publish reviewed source and inspect exact-head CI.
- Real tokenizer/framing/host admission remains separate; no provider or therapy action.

## Compact Meal And Therapy Review (2026-10-18)

- [x] Review selected-event tables, provenance, capacity refusal and cohort reuse.
- [x] Add combined 3/7/14-day and dense basal-history regressions (19 event tests pass).
- [x] Measure pure/store packets with unchanged limits; preserve exact events and extrema.
- [x] Verify scoped Ruff E4/E7/E9/F, formatting and diff checks.
- [x] Finish full backend checks: 1886 passed/27 skipped; source compilation passes.
- [ ] Inspect reviewed exact-head source delivery and Verify CI.
- Host activation, real token accounting, dense-workload admission and complete
  profile/activity/sensitivity export remain separate. No therapy change.

## Concise Chat Presentation (2026-10-18)

- [x] Reproduce visible captions and missing concise instructions with synthetic tests.
- [x] Hide normal roles without losing accessibility, errors or voice metadata.
- [x] Share grounded answer-first style across personal and server text-chat routes.
- [x] Pass 65 focused Android/112 backend checks; inspect native normal/large rasters.
- [x] Complete full local checks: Android 6305 passed/six skips; backend 1882 passed/27 skips.
- [x] Compile, lint and build debug APK; no lint errors. Scoped fatal Python lint passes.
- [ ] Inspect reviewed exact-head source delivery and Verify CI.
- Live provider latency/quality and server/device rollout remain separate acceptance.

## Physical Activity Recovery Chronology (2026-10-18)

- [x] Reproduce minute-cadence recovery and sensitivity-alias failures.
- [x] Preserve physical acquisition identity through the actual Room-to-rule path.
- [x] Replace evaluation counts with supported elapsed rest and reset discontinuities.
- [x] Verify collector-specific heartbeat and sub-minute intensity boundaries (35 focused cases).
- [x] Complete final full Android tests, lint, compile and debug APK:6302 passed,six skips,zero failures/errors.
- [ ] Publish only reviewed source and inspect exact-head Verify CI.
- Personal multiday baselines, versioned coverage/export and observational UI/AI
  integration remain separate, as specified in the post-activity design.
- No fixed lower target, phone update or APK publication is authorized here.

## Owl Motion Craft (2026-10-18)

- [x] Observe RED-first endpoint, layered-action and shared-waiting regressions.
- [x] Add bounded coordinated body/head/wing poses and shared finite waiting.
- [x] Inspect actual native small/large frames and silent motion reel (52 motion tests pass).
- [x] Pass focused/full Android quality: 6288 tests, no failures/errors, 6 skips; compile/lint/APK pass.
- [ ] Inspect scoped exact-head source delivery and Verify CI after publication.
- No phone, server, therapy, alert audio or palette change in this stage.

## Trusted Compact Chat Adapter (2026-10-18)

- [x] Add RED-first trusted archive/receipt/worker tests and immutable device/question binding.
- [x] Reuse bounded latest-version reads and encrypted existing server_chat storage without migration.
- [x] Wire explicit trusted compact policy/settings; preserve default host and native stop gates.
- [x] Pass120 focused and1862 full Python3.12 cases; scoped lint/compile/diff checks pass.
- [x] Inspect synthetic minute-history preparation for3/14days:0.610/2.932s,21107/87642-byte frames.
- [ ] Inspect reviewed exact-head source delivery and Verify CI.
- [ ] Admit real token accounting and protected host opt-in separately.
- Complete profile/trajectory export, native persistence and public receipt UI remain separate.

## Pure Compact Context Projector (2026-10-18)

- [x] Approve model-context-only format and write an isolated implementation plan.
- [x] Add RED-first projection, coverage, mutation, limits and resource regressions.
- [x] Reuse daily preparation/raw integration; build immutable frames/manifests.
- [x] Pass155 focused cases and measure actual synthetic3/7/14-day packets.
- [x] Pass full backend on CI-matching Python3.12:1840 passed,27 skipped; scoped lint/format/compile pass.
- [x] Inspect reviewed source publication and exact-head Verify CI.
- [x] Connect trusted source selection and encrypted per-answer receipt through an opt-in worker.
- [ ] Complete operational host/token and full-profile/domain coverage admission.
- No upload/archive, native-thread persistence, phone or therapy change.

## Compact Model Context Design (2026-10-18)

- [x] Confirm model-context-only scope; keep detailed uploads/archive unchanged.
- [x] Inspect reusable daily glucose facts, continuity/cohort rules and packet boundaries.
- [x] Size synthetic3/7/14-day JSON layouts and pass118 existing calculator tests.
- [x] Specify proposed adaptive buckets, mandatory events, packet budgets and context receipts.
- [x] Obtain written-format review before implementation.
- [x] Implement isolated pure projection and regressions.
- [ ] Complete authenticated CHAT/native/receipt acceptance before operational rollout.

## Per-Device AI Context Audit (2026-10-18)

- [x] Trace Android export, authenticated archive, bounded model context and ephemeral native thread.
- [x] Inspect deployed source/configuration and private read-only coverage metadata; pass254 local synthetic tests.
- [x] Record the required integration and acceptance boundaries in AI_DEVICE_CONTEXT.md.
- [ ] Add complete versioned typed profile/data coverage with explicit omissions and compatible sync receipts.
- [ ] Implement and validate encrypted per-device conversation/native-session ownership and recovery.
- [ ] Add question-specific history selection and immutable per-answer context manifests.
- [ ] Validate nightly ownership, actual deployment and intended-device end-to-end behavior separately.

## Eating Soon Across Concurrent Meals (2026-10-18)

- [x] Reproduce a new explicit target waiting behind an earlier carb POST.
- [x] Separate short operation reservation from serial carbohydrate delivery.
- [x] Preserve one owner/result per input, cancelled partial outcomes and bounded cache ownership.
- [x] Pass80 focused delivery/policy/ownership/presentation cases and assemble candidate.
- [x] Complete final combined Android tests/lint/compile/assemble:6276 passed,six skipped,zero failures/errors; no lint findings in changed files.
- [ ] Verify intended phone identity and existing failure receipt before device acceptance.
- Existing eligibility, confirmed30-minute hold and protective arbitration remain.

## Meal Timing History Audit (2026-10-18)

- [x] Trace12h onset heuristic versus isolated research timing planner.
- [x] Add completed3/7/14-day source audit and existing nightly AI-context integration.
- [x] Complete full Android test/lint/compile/assemble:6272 passed,six skipped,zero errors.
- [ ] Capture verified bolus-to-actual-meal linkage and qualify historical context.
- [ ] Validate personal response and counterfactual timing before clinical UI admission.
- No automatic meal-start recommendation or new therapy influence is enabled.

## Model Onset Header and Context (2026-10-18)

- [x] Add explicit approximate countdown from usable recorded meal insulin.
- [x] Show elapsed model time and finite owl/burger gesture without therapy actions.
- [x] Add configured insulin, DIA and model evidence to chat and daily observed history snapshots.
- [x] Pass152 focused tests, then five repeated header checks after the label edit; inspect native320px/2x-font layouts and assemble the signed candidate.
- [ ] Establish explicit meal association for insulin-only premeal records without treating correction/SMB/IOB as a meal.
- [ ] Validate independent nightly model refresh and any proposed therapeutic influence before activation.
- [x] Complete combined local Android quality for build4:6305 passed,six skips; compile/lint/build pass.
- Exact-head CI, internet publication and device installation are separate receipts.

## Food Photo Immediate Preview (2026-10-18)

- [x] Reproduce missing nutrition totals for a successful uncertain-mass result.
- [x] Add a labelled display-only interval using the existing calculator.
- [x] Check live image channel once with an explicitly approved synthetic image.
- [x] Reproduce lost review on close/reopen and add form-scoped memory retention.
- [x] Inspect native preview with normal and enlarged text.
- [x] Reproduce and reject late responses after review dismissal.
- [x] Finish final100 focused tests and candidate APK rebuild; signer unchanged.
- [x] Complete full Android regression and lint with the combined local candidate on2026-10-18.
- [ ] Prepare versioned APK, verify exact-head source CI and publish through the existing updater.
- On2026-10-18 the user authorized the combined internet APK release; installation
  still requires Android confirmation and is not inferred from publication.
- No live server replacement or real-photo/device acceptance is inferred.

## APK Update Failure Recovery (2026-10-18)

- [x] Reproduce late cancelled results and incorrect disk-error classification.
- [x] Guard terminal state by operation identity; separate write/read failures.
- [x] Complete focused and full Android test/lint/build checks:6231 passes,
  six unchanged skips, zero failures; no new lint issues.
- [x] Prepare higher-version signed APK and inspect native recovery UI.
- Reviewed source, exact-head CI and public APK readback use separate PR receipts.
- No phone installation or therapy changes are part of this repair.

## Internet APK Update (2026-10-18)

- [x] Inspect existing HTTPS host, Android identity, versioning and navigation.
- [x] Add About, explicit check/download/install, bounded integrity validation.
- [x] Add isolated static release publisher, monotonic version checks and tests.
- [x] Complete Android native/regression/lint/build verification:6225passes,
  six existing skips, no errors; two new nonblocking lint suggestions retained.
- [x] Publish APK over HTTPS and independently verify served bytes and routing.
- Reviewed source publication and exact-head CI are tracked by the PR receipts.
- Phone installation is separate from server publication and not assumed.

## Food Photo Failure Recovery (2026-10-18)

- [x] Reproduce malformed result enum types, numeric overflow and blank labels;
  36 expected failures precede decoder changes, with 24 prior cases passing.
- [x] Reject those values with fixed schema errors; signed synthetic API tests
  confirm one stopped failed job and a successful new explicit job. Focused
  decoder/native-vision/API coverage:153 passed.
- [x] Distinguish terminal photo-job failure/cancellation/deadline/result expiry
  from server capability or authentication loss in Android and native UI.
- [x] Full fixed-input backend:1803 passed/27 existing skips, zero failures.
- [x] Full fixed-input Android:6203 passed/six existing skips; lint/compile/build
  succeeded in20m50s. Lint has376 baseline warnings/eight hints, zero errors or
  changed-file issues. Private APK signature verified; source manifests match.
- Reviewed source publication and exact-head CI have independent PR receipts.
- No model/image upload, server deployment, phone or therapy operation.

## Food Photo Nutrition Response Guard (2026-10-18)

- [x] Trace parsed results through editor and existing nutrition validation;
  reproduce impossible facts and wholly unusable portions before production edits.
- [x] Validate response feasibility before review on Android and backend; keep
  uncertain intervals and unknown/subfraction semantics unchanged.
- [x] Align explicit mass editing/conversion with existing calculator bounds;
  explain the usable lower bound without auto-selecting or clamping grams.
- [x] Focused gates:113 Android cases including30 native photo cases;87 backend
  contract/vision/transport cases, zero failures/errors/skips.
- [x] Full fixed-input backend:1763 passed/27 existing skips. An initial macOS
  process-group PermissionError is retained; isolated runner30 and unchanged
  full repeat pass without weakening lifecycle checks.
- [x] Full fixed-input Android:6197 passed/six existing skips; lint/compile/build
  succeeded in34m24s, baseline376 warnings/eight hints and zero errors. No issue
  in changed photo files. Source manifests match and private APK verifies.
- Source publication/exact-head CI require separate receipts. No deployment,
  model, actual photo, phone or therapy operation belongs to this stage.

## Food Photo Camera Ownership (2026-10-18)

- [x] Trace camera result, prepared bytes, owned file/URI cleanup and food cancel.
- [x] Isolate the native fixture's per-application FileProvider root; final RED
  has four expected cleanup failures, 24 passed, before production edits.
- [x] Centralize idempotent exact-owned camera cleanup before inference and on
  launch failure, cancellation, read failure/finally and disposal.
- [x] Focused camera/gallery/form/session/transport gate: 106 passed, including
  all 28 native cases, zero failures/errors/skips.
- [x] Full fixed-input Android: 6190 passed/six existing skips, zero failures
  or errors; lint/compile/build and private APK signature checks passed.
- Publication/exact-head CI are separate post-commit PR receipts. No device,
  real image/model, server, nutrition, target or dose operation.

## Food Photo Review And Confirmation (2026-10-18)

- [x] Trace photo review through the parent food form and retain model mass bounds.
- [x] Native RED: three missing-behavior assertions fail before production edits;
  a fourth failure is a test substring matcher corrected without relaxing values.
- [x] Align photo carbohydrate entry with the existing 1..maximum food bounds;
  add localized, accessible invalid mass/percentage/carbohydrate feedback.
- [x] Successful one-shot photo transfer, final confirmation and large-text review;
  final 99-case focused gate and actual 1.8x label/error rasters pass.
- [x] Full fixed-input Android: 6183 passed/six existing skips, zero failures
  or errors. Lint/compile/build and private APK signature verification passed.
- Publication/CI are recorded after commit in the separate PR stage receipt;
  phone/provider acceptance remains separate.
- No model request, server, phone, nutrition domain or therapy change.

## Food Photo Selection And Recovery (2026-10-17)

- [x] Trace native launch, cancellation and post-selection failure transitions.
- [x] Seven expected native REDs before production edits; four prior cases pass.
- [x] Guard both selectors, contain launch errors and return refused access to
  the readiness gate without resubmitting an image or changing nutrition bounds.
- [x] Expanded 70-case native/photo/session/HTTP suite; private ordinary/1.8x
  error-state rasters inspected, including rapid pre-recomposition actions.
- [x] Full fixed-input Android: 6177 passed/six existing skips, zero failures
  or errors. Lint/compile/build and private APK signature verification passed.
- Publication/CI are recorded after commit in the separate PR stage receipt.
- No live inference, deployment, phone or therapy change.

## Food Photo HTTP Body Repair (2026-10-17)

- [x] Trace the concrete transport's conflicting 96000-byte generic limit.
- [x] Three expected actual-HTTP REDs before the production fix; unchanged
  negative bounds pass. No real photo or provider request is used.
- [x] Apply the existing 3 MiB allowance to the exact photo POST only.
- [x] Focused 59-case photo/session/HTTP/native regression suite passed.
- [x] Full Android: 6166 passed/six existing skips, zero failures/errors;
  lint/compile/build and private APK signature verification passed. Fixed inputs.
- [ ] Reviewed source publication and exact-head Verify; USB acceptance separate.
- No server rollout, photo upload, key, therapy or phone-state change.

## Authenticated Facts Host (2026-10-17)

- [x] Read-only live contour inventory and approved facts/design retrieval.
- [x] Observe16 expected missing-behavior REDs before production changes.
- [x] Default-off owner flag, bounded current roster, per-device fresh checks,
  off-loop producer, completion/backoff and lifecycle drain implemented.
- [x] Preserve selected-day analytical range inside the existing transaction.
- [x] Expanded240 affected auth/owner/service/schedule/dispatcher cases passed.
- [x] Full fixed-input backend:1752passed/27existing skips, zero failures;
  30 new cases included. Source manifests match, scoped lint/compile passed.
- [ ] Explicit source publication/exact-head Verify and separate PR receipt.
- No live config/service/consent/model, phone or therapy operation. See
  DAILY_FACTS_HOST.md for current boundaries and deployment prerequisites.

## Opt-In Daily Facts Preparation (2026-10-17)

- [x] Continue approved night-review design with persisted facts-only consent,
  local05:00, latest completed day and existing encrypted archive.
- [x] Observe27 missing-API/module assertion failures before production changes.
- [x] Implement atomic generation-CAS settings, schema6 and one-shot producer.
- [x] Independent mutable-range RED reproduced and fixed by validated freeze.
- [x] Initial expanded272-case source/report/history/queue/result gate passed.
- [x] Final41 new schedule cases included in full backend; prior77 affected
  schedule/report cases passed. Linux first CI's fixture path ordering fixed.
- [x] Complete portable backend:1722passed/27existing skips, no failures; scoped lint and
  compilation passed. New files full Ruff clean; three existing unrelated
  import-order notices retained in older modules. Final input manifests match.
- [ ] Reviewed source publication and exact-head Verify, separate PR receipt.
- No persistent host timer, enrollment, model, phone/APK or therapy operation.
  See DAILY_FACTS_SCHEDULE.md for schema rollback and remaining admission.

## Android Server Daily Archive (2026-10-17)

- [x] Continue approved daily-review/Focus+ design as separate unreviewed audit,
  not ordinary clinical publication. Inspect current signed API/native boundaries.
- [x] Initial codec and independent cache/native missing-feature REDs observed.
- [x] Signed client/codec plus existing history suite:37 cases passed before
  cache/native work. Actual synthetic backend golden projection is included.
- [x] Implement bounded cache, current-session binding and native Reports slot.
- [x] Expanded38-case fault/native gate and ordinary/actual1.8x long-text
  raster inspection; disk-read identity and expiry/clock regressions included.
- [x] Full6168-test execution on reviewed inputs:6162 passed, six existing skips,
  zero failures/errors. Final lint/compile/assemble are a separate gate below.
- [x] Final Android lint/compile/build passed, zero errors/new lint issues;
  existing376 warnings/eight hints. Reviewed input manifests match byte-for-byte.
- Reviewed source/exact-head CI are post-commit gates in the PR receipt.
  No server/provider/schedule/phone/therapy activation. See
  ANDROID_SERVER_DAILY_ARCHIVE.md and OPEN_FEATURES.md.

## Protected Daily Result Reads (2026-10-17)

- [x] Continue approved daily-review read-projection track; distinguish stale
  delivery-map checkboxes from current source and separate device receipts.
- [x] Observe missing-projection/default-off REDs before production edits.
- [x] Strict list/detail projections and explicit bool owner composition, reusing
  signed HistoryService and encrypted bounded reads, without provider input.
- [x] Focused205 read/owner/history/archive tests; scoped lint and compilation.
- [x] Final full backend gate:1681 passed,27 existing skips,zero failures/errors;
  independent proof-path and expired-access checks are included.
- Reviewed source publication/exact-head Verify are post-commit gates, recorded
  separately in the PR receipt. No server/APK activation or clinical authority.
  Current source/acceptance boundaries are summarized in OPEN_FEATURES.md.

## Focus+ Android Display (2026-10-17)

- [x] Observe initial behavioral REDs for duplicate owl, composer and route work.
- [x] Implement global AI owl menu, six-tool composer and retained display state.
- [x] Add local-only 05:00/catch-up worker and bounded transactional projection.
- [x] Room/native/schedule/PDF/consent regressions and native raster inspection.
- [x] Full6130-test execution:6123passed/six skipped; one merged-tree UI finder
  corrected. Final121 affected tests pass, including all50 native workspace tests.
- [x] Final Android lint/compile/build:zero errors/new issues, baseline376
  warnings/eight hints. App source/test inputs remained fixed during the gate.
- Reviewed source publication/exact-head CI and intended-phone USB acceptance
  are separate post-commit gates recorded in the stage receipt, not assumed here.
- No server daily-model activation or therapy authority. See AI_FOCUS_PLUS.md.

## Meal Preparation And Automatic GI (2026-10-17)

- [x] Trace historical GI display/preview and separate timing research; identify
  empty manual parser in Food and missing compact preparation renderer.
- [x] Observe three expected behavioral REDs before production changes.
- [x] Wire a versioned MODEL_ESTIMATE through Food, immutable confirmation,
  exact canonical metadata, Lenta and event provenance. Independent known-GI
  history only; generated values cannot become training evidence.
- [x] Add finite food motion for explicit intent plus fresh IOB. A future
  canonical meal may supply a labelled plan; IOB cannot invent readiness time.
- [x] Focused synthetic, persistence, unchanged-forecast and native UI tests;
  full unit suite: 6102 passed, six skipped, zero failures/errors.
- [x] Full Android unit/lint/build; after mechanical lint cleanup, repeat45
  affected UI tests plus lint/build: zero new lint issues,376 warnings/eight hints.
- [ ] Reviewed source publication and exact-head Verify.
- No new therapy authority or timing-model clinical activation. See
  MEAL_PREPARATION_GI.md for coefficients and evidence boundaries.

## Restored Food Photo Connection (2026-10-17)

- [x] Trace ACTIVE-only photo gate against connection.load restoring SAVED.
- [x] Eight real-constructor regressions before production fix: 7 expected
  assertion failures, zero errors; no transport reached for restored sessions.
- [x] Let signed persistence/auth/refresh checks decide restored access and
  distinguish actual authorization refusal from unavailable photo service.
- [x] Targeted 40 tests pass, including signed successful result after restore.
- [x] Full Android unit suite: 6082 passed, 6 skipped, zero failures/errors.
- [x] Complete Android test/lint/build: no errors, existing lint baseline.
- [x] Persistent public admission renewal: scoped 61/full 1628 backend tests pass.
- [x] Reviewed source7394a56b and both exact-head Verify runs succeeded;
  receipt is attached to PR7. No public APK or private deployment data.
- [x] Explicitly authorized decoder admission/offline stop and one synthetic
  provider test: typed four-ingredient estimate, confirmed stop/idle guard.
- [x] Separate image host/API connected after verified owned-config rollback
  during first probe; auth and photo-only 3 MiB/text 128 KiB barriers checked.
- [ ] Intended Redmi installation and real UI acceptance; ADB inventory empty.

## Nightly 3-14 Day Advice (2026-10-17)

- [x] Trace mixed report advice and unanchored therapy-gated daily work.
- [x] Add first metric/schedule regressions and observe missing-feature RED.
- [x] Implement read-only canonical summaries, bounded atomic snapshot and 05:00
  WorkManager/catch-up with no therapy or provider authority.
- [x] Dated native advice, 3/7/14-day selector, sparse/stale status and unsent
  discussion; keep other AI reports separately sourced.
- [x] Synthetic/native QA and full Android test/lint/compile/build:6073 passed,
  6 existing skips, zero failures/errors; lint0 errors and unchanged377 warnings/
  8 hints. Final localized UI inspected, APK identity/signature checked.
- Reviewed publication/exact-head CI are post-commit gates, recorded in the PR
  only after their terminal results are inspected.
- [ ] Separate intended-phone overnight acceptance; no deployment/install here.

## Chat Motion And Bounded Attachments (2026-10-17)

- [x] Inspect Focus composer, native motion and actual provider bounds.
- [x] Finite pending-only owl/typing renderer and stable message identities.
- [x] Bounded imports, strict text and real personal image/PDF bytes; small text
  files on existing signed server route, no personal fallback.
- [x] Focused74 tests and final native raster/encoded motion inspection.
- [x] Full Android6043passed/6skips, lint0errors/377warnings/8hints, compile/build.
- Publication/CI is recorded by the exact-head PR receipt, not assumed here.
- [ ] Separate server image/PDF route and actual provider/device acceptance.

No live provider/medical upload, phone, server activation or therapy operation
belongs to this Android source stage. Picker availability is not image readiness.

## Owned Daily Dispatch (2026-10-17)

- [x] Revalidate actual deployed text repair without new inference.
- [x] Executor-owned atomic daily admission reuses existing stop/settlement.
- [x] Join checked queue/work, idle priority, one-turn worker and result archive.
- [x] Dedicated closed daily Unix channel, optional shared-capacity host binding.
- [x] RED regressions precede each behavior; targeted cancellation/source/commit/
  race/peer/deadline/stop checks pass. No ambiguous request replay.
- [x] Full backend1618 passed/27 existing opt-in skips; scoped lint, diff and
  source/security review pass. One host test module retains its imported-fixture
  F811 exclusion; no global lint relaxation or unrelated fixture rewrite.
- [ ] Reviewed publication and exact-head CI for this source stage.
- [ ] Separate persisted05:00 opt-in/facts/read API/Android default result stage.
- [ ] Exact reviewed live daily activation and real model/device acceptance.

The new source is not active on the existing server. Its chat fix remains healthy;
new daily work has no public schedule or result projection yet.

## Native Moderation Compatibility (2026-10-17)

- [x] A bounded synthetic host diagnostic isolated text_notification_rejected;
  the unknown method hash matches turn/moderationMetadata in the pinned schema.
- [x] Thirteen synthetic regressions fail before the source correction.
- [x] Add exact-envelope, correlated handling without interpreting metadata or
  weakening successful-completion, tool, ownership and cleanup requirements.
- [x] Focused191 cases and full backend1566 passed/27 existing opt-in skips;
  scoped lint, diff and source/security review pass.
- [ ] Reviewed publication and exact-head CI for this additional fix.
- [ ] Separately reviewed immutable rollout and actual large-context answer.

The earlier frame correction is installed as a live-base composition. Its real
long-context test failed safely on this second protocol incompatibility; do not
claim the chat is repaired from service readiness. No failed user job is replayed.

## Full-Context AI Framing (2026-10-17)

- [x] Trace the 128 KiB context/64 KiB native-line mismatch; four new synthetic
  regressions fail at parser/pipe limits before the production fix.
- [x] Share the bounded JSON-escape-aware native line cap, preserving aggregate
  stream/event/answer and source/tool/cancellation/stop controls.
- [x] Final focused 238 tests pass, including max-input ASCII/UTF-8 and escaped
  controls through real child pipes, supervisor, two echoes and positive stop.
- [x] Full backend: 1553 passed/27 existing opt-in skips, zero failures/errors.
  Scoped E/F lint and Python compilation pass. Three pre-existing imported
  pytest-fixture F811 findings are verified against HEAD and excluded only in
  that test file. Diff/source/security/docs reviewed; no new blocking finding.
- [x] Source59bba270 published; both exact-head Verify runs completed success.
- [ ] Separately authorized target-host rollout and actual model/device outcome.

No real model request, server deployment, Android change or nightly scheduler
activation is part of this source stage. A reproducible synthetic defect does
not establish the cause of an individual operational failure.

## Food Eating Soon Follow-through (2026-10-17)

- [x] Trace plus/Food through the same manual target channel and current safety
  policy. Three added behavioral regressions failed before implementation.
- [x] Move the target attempt before optional research persistence, retaining
  independent outcomes, carb-input ordering and no ambiguous replay.
- [x] Restore durable allowlisted refusal details and preserve them over local
  hints. Do not relax safety or claim an unobserved device refusal cause.
- [x] Focused Room/HTTP/native UI/priority/cancellation checks:141 passed,
  zero failures/errors/skips across9 nonempty suites.
- [x] Full Android unit, lint, Kotlin compile and debug APK gates:6033 cases,
  6 unchanged private-input skips,no failures/errors;lint0 errors,unchanged385
  notices. Debug v2 signature and native320x640 refusal view checked.
- Publication and exact-head Verify remain post-commit gates; record their exact
  source SHA and results in the PR, not as a source self-certification.
- Physical-device acceptance remains separate; no real food/target test,
  app stop/backup or phone installation is part of this source stage.

## Foreground Glucose Alarm Integration (2026-10-16)

- [x] Connect explicit checked INITIAL routing and existing single audio owner.
- [x] Add installation-bound opt-in, private foreground lifetime and scoped
  notification acknowledgement; preserve native technical alerts.
- [x] Add one genuine critical AlarmClock and nonclinical expiring wake token.
- [x] Final414 synthetic native fallback, cancellation, OFF, stale action,
  permission, clock/reboot, cleanup and large-text UI checks;0 failures.
- [x] Full Android quality:6011 cases/6005 pass/6 unchanged skips,0 failures;
  lint0 errors,377 warnings/8 hints (six reviewed new style notices); APK v2
  and unchanged source fingerprints verified.
- [ ] Reviewed source publication and exact-head CI (post-commit result in PR7).
- [ ] Separately authorized physical device/sound/Doze acceptance.

## Independent Local Observation (2026-10-16)

- [x] Audit foreground/boot/broadcast/scheduler/automation/transport boundaries.
- [x] Add install-bound opt-in, separate local-only periodic work and foreground
  cycle routing; preserve therapy-only transports and existing armed runtime.
- [x] Add native setup switch with actual permission state and explicit limits.
- [x] Legacy migration checks the explicit observation decision atomically;
  disabling observation later does not reopen automatic therapy arming.
- [x] Final127 focused cases passed; Russian320x640 enlarged-text layout
  inspected, including the full-row accessible observation switch.
- [x] Full Android unit/lint/Kotlin/debug build passed in38m36s:5973 cases,
  5967 passed/6 existing skips,0 failures/errors. Lint0 errors and unchanged379
  notices; final source fingerprints and APK v2 signature verified.
- [ ] Reviewed source publication and exact-head CI.
- [ ] Device acceptance, remote polling and independent alarms remain separate.

## Local Manual Diary (2026-10-16)

- [x] Inspect actual AAPS-only meal submission and independent-use limitations.
- [x] Add separate revisioned Room35 records with exact decimal units, provenance,
  optimistic concurrency, idempotent retries and recoverable deletion.
- [x] Add native list/editor/history, saved draft identity, menu/setup navigation;
  keep existing clinical inputs and AAPS Food workflow unchanged.
- [x] 50 focused storage/migration/UI/no-delivery cases pass. Native Russian
  320x640/large-text rasters inspected; adaptive buttons prevent broken words.
- [x] Full Android quality:5955 cases/5949 pass/6 existing skips,0 failures.
  Final two-label-only followup:19 UI cases and lint/build pass; no new lint
  notices. Native rasters reviewed; final source manifest and APK v2 verified.
- [x] Published e7913acb; push38907517550/PR38907524091 Verify passed. All5955
  Linux cases/outcomes matched local results. No phone installation.
- [ ] Validated diary analytics remain separate; local observation is above.

## First-Start Setup (2026-10-16)

- [x] Inspect actual manual, AAPS and startup paths; document capability gaps.
- [x] Add resumable five-step native checklist and Settings entry, existing editor
  links, explicit notifications and separate AAPS command review.
- [x] Preserve clinical preferences and existing installations; respect recorded
  refusal and keep already-armed startup independent of checklist progress.
- [x] 54 final focused persistence/native UI/startup/Eating Soon cases pass;
  native Russian 320x640 and enlarged-text rasters inspected. Deterministic
  Room commit/return cancellation regression repaired before the full rerun.
- [x] Full Android quality:5931 cases,5925 passed/6 existing skips,0 failures;
  lint0 errors, unchanged379 notices; debug APK v2 signature verified.
- [x] Published 29130a85; push38899831185/PR38899838081 passed. Linux/local 5931
  cases and outcomes matched exactly. No phone installation.
- [ ] Separate observation runtime authorization, manual-data analytics and
  device acceptance. Local diary is the next implemented stage above.

## Scoped Runtime Acknowledgement (2026-10-16)

- [x] Add bounded exact-identity acknowledgement to the explicitly running
  coordinator; revoke before Room and finish exact cleanup before pause writes.
- [x] Recheck current source/settings around persistence; rollback on loss.
- [x] Cover stale actions, busy journal, duplicate taps, worsening, other sources,
  OFF, expiry, settings/capability loss, receiver and runtime cancellation.
- [x] Final187 focused cases and full Android unit/lint/Kotlin/debug build pass:
  5911 cases,5905 passed,6 unchanged private-data skips,0 failures/errors.
  Lint has0 errors and unchanged371 warnings/8 hints; APK v2 signature verified.
- [ ] Reviewed source publication and exact-head CI.
- [ ] Production owner, visible acknowledgement action, foreground/AlarmClock
  lifecycle and OFF-expiry reassessment remain open, with device acceptance later.

## Native Alert Handoff (2026-10-16)

- [x] Separate delivery ordering from short Room transactions; recheck pending
  control intent for local admission. Preserve OFF cleanup-before-write ordering.
- [x] Add explicit checked-source callback after committed INITIAL claim, without
  fabricating audio-start receipts or replaying the initial side effect.
- [x] Prepare local audio ownership before volume acquisition; retain retired
  leases until confirmed cleanup before native/preview replacement.
- [x] Current focused104 cases and final full Android unit/lint/Kotlin/debug
  build:5890 cases/6 existing skips/0 failures/errors, unchanged lint notices,
  verified APK v2 signature.
- [x] Published ec3642fc; Verify38887130917 and38887123575 passed. Linux XML
  exactly matches the local5890 cases and6 skips.
- [ ] Select the handoff in production; connect current settings/capabilities,
  scoped ACK, foreground/AlarmClock lifecycle and OFF-expiry reassessment.

No phone sound, installation, therapy or platform activation in this prerequisite
stage. Full runtime remains open in the existing alarm-runtime-integration plan.

## Immediate Alert Control Boundary (2026-10-16)

- [x] Reproduce four actual OFF-versus-lock/preparation/queued-delivery defects.
- [x] Cancel the exact admitted alert child before Room without cancelling its
  enclosing caller; wire immediate shared audio/vibration stop in AppContainer.
- [x] Verify348 focused cases, including42 actual-controller shadow-media cases.
- [x] Reproduce cross-owner cached-source revival; bind publication to the exact
  control revision and require fresh accepted evaluation after a control change.
- [x] Full Android unit/lint/Kotlin/debug-build passed in20m24s:5868 cases,
  5862 pass/6 existing skips,0 failures/errors; lint0 errors and unchanged379
  notice keys. APK v2 signature verified,16 reviewed text paths scanned.
- [ ] Exact-head source publication/CI; original behavioral failures and final
  reports are retained in private execution evidence.
- [ ] Continue safe native INITIAL/volume handoff, foreground/AlarmClock owner,
  scoped acknowledgement and fresh OFF-expiry reassessment. The coordinator
  remains inactive; this repair alone is not the completed runtime.

Plan: superpowers/plans/2026-10-16-alarm-runtime-integration.md. Previous source
stage192a52f0 passed both Verify runs38870159391/38870150944, including downloaded
reports matching5857 local cases; those checks do not verify the current changes.

## Feature Completion And Audio Ownership (2026-10-16)

- [x] Revalidate Focus implementation and actual source gaps; split exclusive
  work tracks with dependencies and acceptance in
  superpowers/plans/2026-10-16-feature-completion-tracks.md. Native subagents are
  unavailable; no external agent or user-owned chat was started as a substitute.
- [x] Reproduce actual audio queued-stop/preparation/duplicate callback races;
  bind bounded identity ownership to legacy, preview and local playback.
- [x] Three additional StateFlow observer REDs reproduced false confirmation
  and replacement-player cleanup; corrected ownership and publication ordering.
  Focused585 cases pass, including39 actual-controller and16 pure ownership cases.
- [x] Newest saved Focus report/source-aware discussion/error filters pass24
  native cases; raw diagnostic errors stay out of discussion drafts. Additional
  timer RED fixed stop installation before active-state observers. Final full
  Android unit/lint/Kotlin/debug-build passed:5857 cases/5851 pass/6 existing skips,
  0 failures/errors; lint0 errors and unchanged379 notice keys. APK v2 verified.
- [ ] Reviewed source publication and exact-head Verify.
- [ ] Foreground/genuine AlarmClock/scoped acknowledgement/OFF reassessment,
  daily dispatcher/schedule/read routes, evidence adapters and intended-device
  acceptance remain separate track deliverables, not enabled by this stage.

## Reliable Alerts: Accepted Sources (2026-10-16)

- [x] Shared checked feed with clock/boot/deadline, exact revocation and stable
  duplicate measurement generation/queue age; no hardware capability implied.
- [x] Actual glucose timestamp/current confirmed candidate to matching committed
  episode, preserving native initial delivery and existing thresholds/receipts.
- [x] Diagnostic source from validated current history/OPEN event, not retained
  display; revoke on failed/cancelled work, low risk, OFF, recovery or uncertainty.
- [x] AppContainer clock/revocation composition, no production coordinator owner.
- [x]555 targeted cases and complete Android quality pass:5820 cases,5814 passed,
  6 existing skips,0 failures/errors; lint0 errors,379 unchanged notices.
- [ ] Reviewed source publication and exact-head CI.
- [ ] Single-audio handoff, foreground/AlarmClock owner, scoped ACK UI, OFF-expiry
  fresh reassessment and separately authorized actual-phone acceptance.

Plan: superpowers/plans/2026-10-16-accepted-alarm-sources.md. Sources are checked
provenance only; this stage does not activate repeated sounds or prove hearing.

## Reliable Alerts: Fatigue Policy (2026-10-16)

- [x] Explicit owner approval of repeat/ACK/OFF/visible AlarmClock design.
- [x] Scoped plan and REDs reproduced four soft clips, missing bounded backoff,
  cross-source bursts and critical selection waiting on unrelated soft history.
- [x] One soft clip, base/2x/4x/max30-minute intervals and durable cross-source
  cooldown/reservation implemented in the existing inactive runtime. Critical
  cadence/floor and shared-history bypass remain independent.
- [x] Final210 alarm-focused and26 player-callback tests pass. Complete Android
  test/lint/compile/debug-build passed in9m56s:5751 cases,5745 passed/6 existing
  skips,0 failures/errors; lint0 errors,379 unchanged baseline notices. Source/
  security/diff review passed. First full run's2 old four-soft-clip fixtures were
  updated while retaining deadline coverage; complete quality rerun inspected.
- [ ] Reviewed source publication and exact-head CI receipt.
- [ ] Complete accepted-source bridge, real foreground/AlarmClock owner, scoped
  ACK UI, OFF expiry reassessment and separately authorized phone acceptance.

Plan: superpowers/plans/2026-10-16-alert-fatigue-policy.md. No production runtime
activation, phone operation, clinical threshold, schema/default or therapy change.

## Reliable Alert Delivery: Initial Isolation (2026-10-16)

- [x] Owner continued after written design review. Scope is staged source work,
  not phone arming or an assertion that the entire proposed runtime works.
- [x] Public notifier/Room and accepted-runtime tests reproduced blocked-channel
  false-positive posting, writer/target failure suppressing alert publication,
  and successful notification hiding explicit audio failure/timeout.
- [x] Selected-channel/group checks, required-channel diagnostics and persistent
  bounded degradation on any-channel receipts implemented. No channel override.
- [x] Expanded RED reproduced alert-behind-writer ordering. Accepted normal alerts
  now precede writers; ordinary consumer errors stay independent without retry.
- [x] Final126 focused cases/24 new tests pass. Complete Android test/lint/
  compile/debug-build passed in9m39s:5727 cases,5721 passed/6 existing opt-in
  skips,0 failures/errors; lint0 errors,379 existing notices. Source/security/diff
  review passed. Publication/exact-head Verify receipts are tracked separately.
- [ ] Next implementation stage: accepted source bridge and real single-owner
  foreground/wake delivery, scoped ACK, coalesced noncritical backoff and OFF
  expiry reassessment. No production coordinator activation in this first stage.
- [ ] Separately authorized intended-phone update and harmless physical delivery
  tests, including locked screen/idle, denied permissions, routes and cleanup.

Plan: superpowers/plans/2026-10-16-alert-delivery-failure-isolation.md.

## Atomic Daily Capacity (2026-10-16)

- [x] Inspect shared ledger/executor and daily lifecycle, choose idle-only metadata
  scope and observe initial3-case RED. No dispatcher/provider activation.
- [x] Share request/conflict validation; atomically create one RUNNING daily claim
  only while idle. Busy adds no row; any duplicate returns no claim authority.
- [x] Separate RED reproduced legacy daily claim bypass of a waiting chat; fixed
  inside the shared claim transaction. Non-daily/normal reserve behavior retained.
- [x] Final76 new synthetic cases/230 focused pass: kind/state priority, expiry,
  cancellation/stop, all terminal/legacy duplicates, malformed/scoped/conflicting
  metadata, restart, multiple connections/ordered races and commit uncertainty.
- [x] Full backend1542 passed/27 existing opt-in skips, no failures/errors. Scoped
  E/F lint, new-test format, compile, diff/source/security/docs review pass; no
  unrelated formatting of the existing shared ledger. No new blocking finding.
- [ ] Reviewed source publication and independent exact-head PR/Verify inspection.

No dispatcher/native supervisor/provider/schedule/host/model/phone/therapy change.
Already queued priority is not preemption; future lifecycle must internally own
fresh claim consumption, source/handoff/stop and result archiving. See DAILY_IDLE_CAPACITY.md.

## Durable Daily Results (2026-10-16)

- [x] Inspect daily worker/executor and encrypted source/queue/report contracts;
  record exact crash/admission scope and observe missing-lifecycle RED.
- [x] Add encrypted immutable schema 5 result sink, stopped-executor composition,
  selected fresh-source read and explicitly unchecked bounded summary pages.
- [x] Final52 new synthetic cases and309 focused checks pass: restart/retry,
  commit uncertainty/no-reinference, failed/cancelled/expired/unknown stop,
  changed/superseded/missing source, retention, isolation/tampering, quotas,
  bounded snapshots, prompt audit, concurrent claim and cancellation draining.
  Separate RED caught a cancelled repeat-read thread outliving its owner; fixed
  by draining owned reads as well as writes. Schema5 retains history/chat/facts/
  queue operations. Full1466 passed/27 existing opt-in skips, no failures/errors.
- [x] Scoped E/F lint, format, compile, diff/source/security/docs review pass.
  Cross-database loss/forward-only migration and unreviewed text remain explicit.
- [ ] Publish reviewed source on existing PR and inspect exact-head Verify.

No provider, supervisor/priority/scheduler/publication admission, server, phone,
medical-data or therapy activation. See DAILY_REVIEW_RESULTS.md. Successful
ledger settlement and durable result are separate facts, not an atomic commit.

## Approved Focus AI Interface (2026-10-16)

- [x] Human selected Focus/report-first; inspect existing UI, clinical disclosure,
  PDF tickets, chat state and independent loading boundaries.
- [x] Reproduce missing four-tab navigation/isolation in native Compose tests.
- [x] Implement report/chat/recommendations/archive views with retained drafts and
  scroll, compact period summary, pinned composer and accessible finite motion.
  Keep diagnostics/replay/CSV and clinical consent/unknown-outcome/PDF gates.
- [x] Reproduce tab-disposal send-latch regression and missing clinical advice;
  fix root-lifetime submission latch, report busy semantics and grounded advice.
  Final17 new native cases/48 focused checks pass; inspect English/Russian,
  320x640/320x480 and1.6font native rasters. No actual-phone claim.
- [x] Full5703-test run:5697 passed/6 existing skips,0 failures/errors. Full
  test/lint/compile/assemble and instrumented-source compile pass in13m48s.
  Lint0 errors,379 notices: five retired-text unused-resource warnings and two
  dialog-height recommendations added to the372-notice baseline. Scoped native
  rendering/source/security/docs reviewed; APK package/signing/hash recorded.
- [ ] Publish reviewed source on existing PR and independently verify exact-head CI.

No new provider, external upload, phone/therapy operation or fabricated glucose
curve. Existing clinical summaries are the available source, not raw day-series.

## Contained Daily Review Worker (2026-10-16)

- [x] Inspect the native text protocol, source-checked queue and shared executor.
- [x] Add a dedicated bounded JSON session and immutable daily request binding;
  do not widen the public CHAT policy or change model/reasoning settings.
- [x] Check the consumed queue/archive/source before inference and the current
  source again after the reply; drain owned readers on cancellation and require
  independent process stop before the existing executor exposes any result.
- [x] Final60 new cases/174 focused tests pass, full backend1414 passed/27 existing
  opt-in skips. Scoped lint/format/compile and source/security/docs review pass.
- [ ] Publish the reviewed source stage and independently inspect exact-head CI.

This stage does not install or activate a provider, scheduler, API route, phone
update or therapy path. Interactive-first dispatch and encrypted final-result
settlement remain separate admission work, not properties of a worker alone.

## Durable Daily Review Queue (2026-10-16)

- [x] Inspect shared ledger/profile and approved automatic logical-day rules;
  choose a queue that consumes no interactive/global model slot during preparation.
- [x] Observe missing-queue RED; implement encrypted reservation/get/cancel and
  fresh-source/current-report one-shot handoff with explicit additive schema 4.
- [x] Reproduce missing post-commit deadline RED; committed handoff markers remain
  consumed even when acknowledgement fails, with no automatic second delivery.
- [x] Final40 new cases/261 focused tests pass; full pinned backend1354 passed,
  27 existing opt-in skips. Scoped lint/compile and diff checks pass.
- [x] Final source/security/docs review passes. Synthetic6343-point/22-day queue
  survives restart and delivers once: enqueue0.93s/handoff0.7385s,104.7MiB process
  including fixture/imports. Not provider/target-host capacity or clinical evidence.
- [ ] Explicit reviewed source publication and independent exact-head CI receipts.

No worker, provider/global-slot admission, schedule, server, phone or therapy
activation. Durable results and actual priority/contained execution remain separate.
See DAILY_REVIEW_QUEUE.md; one-shot handoff is not exactly-once model execution.

## Checked Daily Review Boundaries (2026-10-16)

- [x] Inspect dedicated CHAT/photo queue contracts; no implicit daily fallback.
- [x] Observe missing checked-preparation RED; consume independent source replay
  atomically with canonical work construction and fresh reply evaluation.
- [x] Fix observed contract errors incorrectly becoming storage-unavailable;
  distinct errors preserve actual integrity failures and prevent false retries.
- [x] Final33 new cases/205 focused tests pass: stale source/proofs/hash echo,
  supersession, canonical forgery, isolation, expiry/retention, corruption, closed
  reply/reference checks, concurrency and complete-operation deadlines.
- [x] Complete pinned backend1314 passed/27 existing opt-in skips; scoped E/F
  lint, compile, diff/source/security/docs review pass. Synthetic6343-point/22-day
  flow prepares in0.7607s and checks a reply in0.7021s locally; not provider,
  target-host capacity or clinical accuracy evidence.
- [ ] Reviewed source publication and exact-head CI evidence.

No job reservation, provider, queue activation, publication, schema or deployment.
Actual point-in-time source checks do not admit medical text or future dispatch.
See CHECKED_DAILY_REVIEW.md for the remaining dedicated-worker lifecycle.

## Frozen Daily Source Verification (2026-10-16)

- [x] Observe missing-check RED; inspect full archived ranges and moving cuts.
- [x] Share protected report/source snapshot and deterministic replay, with actual
  check bindings and explicit changed/unavailable states; no storage migration.
- [x] Reproduce missed post-midnight guard RED and expand the checked right guard.
- [x] Synthetic regressions cover late/revised/deleted/irrelevant source, integrity,
  retention, rollback, full-facts mismatch, concurrency, ranges, clocks and limits.
- [x] Final43 new cases and172 focused tests; full pinned backend1281 passed,
  27 existing opt-in skips. Scoped lint/compile, source/security/docs review pass.
  A separate synthetic6343-point/22-day check completes locally in0.5709s;
  this is not target-host capacity, provider or medical validation.
- [ ] Reviewed source publication and independent exact-head CI receipts.

No worker integration, provider, public/admin route, scheduler, host, APK or therapy.
See DAILY_SOURCE_VERIFICATION.md; this point-in-time result is not clinical admission.

## Grounded Daily Review Contract (2026-10-16)

- [x] Observe missing-module RED; add bounded trusted preparation and read-only
  archive bridge, fixed prompt, exact result schema and grounded reference view.
- [x] Review REDs: missing echo digest/schema, full21-day input overflow and missing
  independent-freshness disclaimer. Explicit compact evidence/baseline projection
  preserves original metrics and denominators, with complete omission labels.
- [x] Final52 new synthetic cases and129 focused cases pass, including the21-day projection and
  source/candidate/action/schema/type/quality/priority failures; source review adds
  explicit NOT_INDEPENDENTLY_VERIFIED freshness and its regression check.
- [x] Full pinned backend1238 passed/27 existing opt-in skips; scoped E/F lint,
  compilation, diff/source/security and documentation review pass. Synthetic
  complete21-day facts project from136566 archive bytes to25591 envelope bytes,
  with36 selected/96 omitted selected-candidate windows explicitly disclosed.
- [ ] Reviewed source publication and independent exact-head CI receipts.

No real provider, daily policy/admission, encrypted result lifecycle, scheduler,
server/phone/UI or therapy activation. See DAILY_REVIEW_CONTRACT.md. Actual
frozen-source verification remains separate, not a supplied hash comparison.

## Daily Facts Archive (2026-10-16)

- [x] Audit discussed unfinished features; source/operational distinctions in
  OPEN_FEATURES.md. Select protected daily facts storage, not phone-dependent work.
- [x] Observe missing-archive RED; internal encrypted save/get/list, immutable
  revisions, explicit source freshness projection and additive schema 3.
- [x] 37 new synthetic checks and 145 relevant checks pass: isolation, corruption,
  exact retry, revisions/deletion, retention, limits, concurrency, quality labels
  and history/chat compatibility. A separate local 365-empty-report exercise
  passes bounded 19-page reads; it is not host or medical-payload capacity proof.
- [x] Full backend1186 passed/27 existing skips, scoped lint/compilation and
  final source/security review. Missing optional Pillow in the first ambient
  environment is recorded; final checks use the pinned complete requirements.
- [ ] Reviewed source publication and exact-head CI evidence.

No provider, host, public/admin route, schedule, phone or therapy operation.
Missing domain adapters and the actual review worker/admin remain separate.

## Native Navigation Follow-through (2026-10-16)

- [x] Reproduce retained page fade with real NavHost and inspect resolved sources.
- [x] Reject snap-only replacement; use immediate page changes while retaining
  the approved owl and drawer gestures. Opaque pixels and draft retention pass.
- [x] Rapid-pop raw/accessibility/native-pixel and draft-retention checks pass;
  wait for actual native commit within96ms,not only semantic-tree presence.
- [x] Focused55 native tests passed,zero skips; raster inspected.
- [x] Full5686-test run:5680 passed,6 existing opt-in skips,zero errors/failures.
- [x] Final unit/lint/assemble/Kotlin gate passed in10m38s;0lint errors,unchanged
  warning keys. Corrected debug APK signature/hash verified; source reviewed.
- Observe exact published SHA/Verify and retain the publication receipt before
  integrating this stage; an earlier green head is not evidence for this source.
- [ ] Separate intended-phone acceptance; current USB/ADB inventories empty.

## Approved Owl Navigation (2026-10-16)

- [x] Owner approved the navigation/notification prototype independently of the
  unselected AI-analysis layout variants. Native scope and privacy recorded.
- [x] Red native regression for missing owl menu action (expected missing tag,
  before production edits; original XML/log retained privately).
- [x] Fixed48dp owl action, explicit route/menu/busy gestures and no replay after
  suppression; preserve compact actions and return focus on drawer closure.
- [x] Remove duplicate chat owl, retain real busy semantics; verify existing
  single notification artwork/public redaction without changing delivery.
- [x] Focused native raster/semantics/policy/notification tests:51 passed.
- [x] Full final Android unit run:5682 tests,5676 passed,6 opt-in private-data
  skips,zero failures/errors. Original focused fixture failures retained.
- [x] Final lint/assemble/Kotlin gate passed in11m07s; debug v2 APK signature
  verified. Reviewed source/tests/docs prepared for feature-branch publication.
- [ ] Separate physical Redmi/notification/TalkBack/performance acceptance.

See superpowers/specs/2026-10-16-owl-navigation-native-design.md. No app install,
backend operation, medical payload or therapy change is part of this stage.
Observe exact published SHA and Verify after push; an earlier green branch head
does not verify this change. Retain its publication receipt in task evidence.

## Owner API Activation (2026-10-16)

- [x] Fresh read-only host/API/profile metadata inspection; existing API remains
  explicitly503, owner session journal idle, no auth body read or token copy.
- [x] Prepare isolated public source and frozen dependency runtime; import gate
  passes without starting API, model or phone work.
- [x] Reproduce/fix serial revocation adapter gap; protected v2 descriptor and
  all-chain status checks pass121 focused tests.
- [x] Final full backend1149 passed/27 existing skips; scoped lint/compilation
  and documentation/source review. Snapshot remains an operator-pinned input.
- [x] Source3b81c2a5 published; both exact-head Verify runs/all six jobs passed.
- [x] Actual immutable image, expiring differential DNS/TLS/BPF admission/renewal,
  kernel and exact lifecycle checks; separate nonroot API/private keys/stores.
- [x] One actual synthetic provider turn, idle/stop confirmation; target-host
  170-test synthetic storage/retention/restore/year-capacity gate passed.
- [x] Readiness and socket/timer startup; private rollback and syntax-checked TLS
  enrollment/API routing;11 public negative tests and503/recovery behavior.
- [ ] Real hardware enrollment, signed phone history/chat and app acceptance.
- [ ] Separate photo admission, daily worker/schedule and private admin delivery.

Text routing now exists after operational checks, not through a capability edit.
No enrollment credential issued; full phone activation remains unverified. See
DEVOPS.md. Android trust renewal remains an operator duty before seven-day expiry.

## Comparable Glucose History (2026-10-16)

- [x] Sparse hourly v2 facts preserve daily integration; DST clock matching is
  explicitly unavailable without dropping correct daily totals.
- [x] Same-context weighted 7/21-day baselines, separate range eligibility,
  missing-day reasons and no sparse-current daily delta.
- [x] Evidence-bound two-hour observations and provisional distinct-day
  recurrences, explicit denominators/omissions and no cause/treatment inference.
- [x] Protected single 22-day snapshot, revisions/deletions, corruption and
  quota/deadline rejection, retention clipping and concurrent-writer tests.
- [x] Focused 145 passed, scoped lint/compilation and local synthetic capacity.
- [x] Final full backend 1130 passed/27 existing skips. An unchanged legacy
  macOS runner cleanup failure in the initial run remains recorded; isolated
  tests and two full repeats passed without a runner source change.
- [ ] Source publication and exact-head CI evidence.
- [ ] Other domain adapters, encrypted report lifecycle, bounded model review,
  durable schedule and private admin implementation/admission.

See [DAILY_GLUCOSE_FACTS.md](DAILY_GLUCOSE_FACTS.md). No model, phone, deployment
or therapy operation. The admin artifact remains a synthetic offline mockup.

## Daily Review Foundation (2026-10-15)

- [x] Owner approved deterministic facts, bounded advisory review and private
  admin direction; approved design/prompt text promoted to specs.
- [x] Closed daily window/range/facts/evidence contracts, linear metrics,
  cohorts, missing-time semantics and bounded descriptive episodes.
- [x] Protected read snapshot, source binding, latest revisions/deletions,
  integrity/budget failure and known sensor-change boundaries.
- [x] Focused 72 tests, scoped E/F lint and Python compilation passed.
- [x] Final full backend1086 passed/27 existing skips; scoped lint and compilation.
- [x] Source 4006e505 published; both exact-head workflows and all 6 jobs passed.
- [ ] Other domain adapters, encrypted report lifecycle,
  model review worker/schedule and private admin implementation/admission.

See [DAILY_GLUCOSE_FACTS.md](DAILY_GLUCOSE_FACTS.md). No public service, model or
phone activation is part of this source stage. Admin mockup uses synthetic data.

## Photo API And Decoder (2026-10-15)

- [x] Dedicated raw-photo Unix protocol and immutable receipt binding; text's
  closed contract unchanged through shared private lifecycle hooks.
- [x] Separate offline decoder image/process/user, source pins, kernel gate,
  original stop/IO and root host assembly; no default deployment.
- [x] Optional protected owner API composition, signed synthetic photo path,
  full two-service shutdown and independent normalizer-before-model gate.
- [x] Focused175 tests passed using synthetic data and actual local sockets/pipes.
- [x] Full backend1032 passed/27 existing opt-in skips; scoped lint and review.
- [ ] Source publication and exact-head CI.
- [ ] Operational public image/user/network/storage provisioning/admission,
  scoped provider and signed-device proof before public activation.

See [VISION_API_DECODER.md](VISION_API_DECODER.md).

## Contained Meal Vision (2026-10-15)

- [x] Reproduce the missing native image path and locate the explicit public503.
- [x] Add dedicated pinned v2 inline image protocol, exact modality checks and
  concrete process transport with independent image budgets; share strict checks.
- [x] Fix shared profile idle ordering after independent IO confirmation.
- [x] Focused protocol/process/profile tests, including actual local child pipes.
- [x] Full backend994 passed/27 existing opt-in skips; scoped lint/source review.
- [ ] Source publication and exact-head CI.
- [ ] OS-isolated decoder and authenticated vision supervisor channel, actual
  host/network/storage/model/signed-device admission before public activation.

See [CONTAINED_MEAL_VISION.md](CONTAINED_MEAL_VISION.md). No provider/service/phone
operation is included in the source stage.

## Combined UI And Photo Integration (2026-10-15)

- [x] Merge PR #6 with current owner/Room source, preserve both doc histories.
- [x] Fix photo coordinator and implement signed task-specific client.
- [x] Connect camera/gallery/review to existing meal form without auto-send.
- [x] Review/integrate context capacity and inactive typed vision adapter.
- [x] Full backend with decoder, independent synthetic capacity and Ruff.
- [x] Frozen combined Android test/lint/APK (5667 tests, 6 private-fixture skips,
  zero failures/errors); signed APK retained locally.
- [x] Source publication and exact-head CI at06ddd75 (all6 checks successful).
- [x] In-place update and main/food/photo-state/background smoke on the intended
  device; installed APK hash and unchanged app/DB identities verified.
- [ ] Separate contained image transport/decoder admission and real server
  activation. This is NOT completed by the source adapter or client UI.

Details and limits: [COMBINED_UI_PHOTO.md](COMBINED_UI_PHOTO.md).

## Core Room Context (2026-10-15)

- Previous fe4a546 checks are all successful, including both Android CI runs.
- Implement bounded read-only core source pages, separate durable metadata and
  encrypted frozen-page recovery, revisions/deletions and signed upload before
  observational chat context. No clinical schema migration or therapy change.
- Admission-aware maintenance: recent six hours first, then365days; recent scans
  every15minutes and daily full reconciliation. Scope and omissions remain visible
  to the model; unlisted domains/GI overlays remain outside this core adapter.
- Native Room tests cover time keysets, aliases, revisions/tombstones, exact retry,
  failed storage/clear, binding refusal and lifecycle cancellation/backoff.
  Full execution (5625 tests) found one incorrect fixture (REAL instead of AAPS_REAL);
  after correcting only the fixture, 150 affected tests passed without failures
  or skips. Full lint/APK gates passed with unchanged executable source. Three
  relevant backend contract tests passed. Preserve full-run/scoped evidence
  separately; source publication/exact-head CI remain independent checks.
- Actual enrollment, server/phone activation, full archive UI and remaining domain
  adapters continue as separate work. Source checks do not establish deployment.

## Parallel Owner Chat Integration (2026-10-15)

- CI follow-up: isolate inventory directory cursors after an intermittent signed
  status503. Deterministic overlap and real journal-fault coverage added; backend
  880passed/26existing skips. Android remains unchanged. Verify the follow-up's
  exact CI head, not a single passing run at the earlier commit.

- Three isolated source workers: nonroot API composition, Android chat routing,
  signed history transport/client. Controller owns DI, shared docs and one common
  Android/backend gate. See the parallel-ai-integration plan for exact ownership.
- Current controller changes remove the24hour chat lock and correct observational
  context labels. Extended analytics/clinical thresholds remain unchanged.
- Verification: backend879passed/26existing optional skips; initial Android
  full5597tests found one history-length defect(5590passed/6skipped). The fix and
  boundary cases passed123affected tests with no failures/skips. Final lint/APK
  gates passed(zero lint errors); source publication/CI follow independently.
  No service/device deployment is implied.
- Remaining after source verification: real structured Room backfill with stable
  IDs/revisions/timestamps, archive restoration UI, protected server provisioning,
  kernel/network/storage/purge/restore/load admission and actual Redmi checks.
  Multimodal support requires a separately verified server capability contract.
## Native Motion Follow-Up (2026-10-15)

- Refined icon press feedback, coordinated drawer motion and short owl/glyph
  accents from main 7ca72a24. Clinical state and all original actions unchanged.
- Local 23 motion tests and full 5,549-test suite passed (6 private-fixture skips),
  Kotlin/lint/APK passed. Includes reduced-motion cancellation red/green evidence.
- Source-only feature PR requires exact-head Verify. Main merge and physical
  phone installation remain separate; see `NATIVE_MOTION_REFINEMENT.md`.
## Narrow Periodic History Integration (2026-10-19)

- [x] Isolate on main and import only signed history/export dependencies.
- [x] Connect persisted upload settings, durable scheduler and current profile.
- [x] Verify main-based Android tests/lint/build and compact native UI locally.
- [ ] Verify exact-head CI before integration.
- [ ] Merge only the reviewed narrow PR after fresh main divergence checks.
- Server recurring advisory reviews and recommendation acceptance are not part
  of this client integration. See AI_HISTORY_UPLOAD_SCHEDULE.md.

## Feather 01 Main Integration (2026-10-15)

- User approved native artwork plus Git main. Isolated integration reconciles
  main 120e6309 with verified a74006dd, excluding unfinished owner-AI files.
  Android matched a74006dd exactly before the presentation patch; both docs
  histories remain. Duplicate sustained-rise behavior was not reimplemented.
- Shared native owl/glyph drawing, optical sizes, launcher/event assets, finite
  foreground motion, static notification risk icons and private owl artwork.
- Local model/recipe, native raster/lifecycle/pixel/semantics, compact-header
  regressions, notification privacy, event details, unit/lint/compile/APK and
  source review passed. Full regression: 5,541 tests with 6 private-fixture skips;
  after the last waiting-role fix, 35 targeted tests and 10 event tests passed.
- Integration gate: require successful exact-head Verify and a fresh main
  divergence check. Record publication/main evidence in the PR and task audit;
  a local build is not deployment evidence.
- No phone install, alarm test or new clinical activation is authorized here.

## Trusted Supervisor Entry Point (2026-10-15)

- Added explicit Linux root host CLI, protected closed configuration, fixed
  derived contextual policy, idle-guard startup and exact owned-socket lifetime.
- Explicit image/egress admission reload preserves identities/policy/private
  bindings; signal shutdown drains active work. Synthetic real socket tests
  cover full host lifecycle and rejection cases. No service/model/phone operation.
- Nonroot public API bootstrap, real image/network/storage provisioning and
  Android context/chat remain the next integration work. See the host spec.

## Concrete Contained Process (2026-10-15)

- Implemented RuntimeImage/OfficialEgress admission and SystemdTextProcess for
  the existing typed supervisor. Fixed command, input barrier, durable profile
  lease, kernel/resource/mount checks and independent exact teardown.
- Synthetic validation includes real child pipes and Unix transport, malformed
  image/egress/kernel state, creation/stop cancellation and output backpressure.
  Full backend gate and source review/publication are required for this stage.
- Previous2f61b68 CI is fully successful: native candidate, session checks and
  both backend/Android jobs. It did not install a runtime or application.
- Next: actual immutable image/expiring egress admission and narrow supervisor/
  nonroot API composition, storage operations, Android signed context/chat and
  actual host/device verification. Keep the existing guarded owner login.
- Spec: superpowers/specs/2026-10-15-contained-text-process.md.

## Typed Supervisor Channel (2026-10-15)

- Previous28a2a6a Verify is fully successful, including Android. Added typed Unix
  API-to-supervisor text transport, fixed policy, kernel peer checks and bounded
  single-turn teardown. Synthetic signed API/history/archive integration uses
  real local sockets; no provider or host service is invoked.
- Fixed false API readiness after an unconfirmed worker stop. Existing leases
  remain held and queued payload expiry remains independent.
- Still needed: concrete contained-process factory, nonroot host/bootstrap and
  actual Linux/storage/load validation, then Android signed history/chat and
  separately admitted rollout. This channel alone does not enable application AI.

## Signed Conversation Query (2026-10-15)

- Added owner/device-signed full-text archive pages with bounded, identity/window-
  bound continuation, append-membership snapshots and honest current outcome
  timestamps. Read-only access uses existing HistoryService; no schema change.
- Updated pytest to the fixed9.0.3 release for GHSA-6w46-j5rx-g56g. Full backend
  validation and exact-head CI remain recorded independently in AI_NOTES.
- Still required: constrained production supervisor/API composition, Android
  history/server-chat adapters and actual key/storage/runtime/device validation.
  The read API is not a deployment or full application AI activation.

## Server Conversation Archive (2026-10-15)

- Previous9fcf0982 exact-head Verify passed all backend, Android and session jobs.
- Implemented optional encrypted365-day server request/outcome persistence,
  additive schema2 migration, bounded follow-up context and failure/cancellation
  handling. Full synthetic verification and exact-head CI are tracked in AI_NOTES.
- Signed archive retrieval is implemented above. Still required: concrete constrained
  supervisor/nonroot API composition, Android history/chat wiring and measured
  storage/runtime/device rollout gates. No server/model/phone action in this stage.

## Contextual Text Integration (2026-10-15)

- Independent owner login and one admitted direct/text-only synthetic native
  request succeeded with verified teardown. No application/medical rollout.
- Added authenticated JobWork identity, bounded read-only device context and the
  single-turn native text adapter; existing signed API is exercised end-to-end
  with a synthetic contained process. Clinical algorithms and Android unchanged.
- Local backend581passed/26existing Nginx skips/1existing warning,45new focused
  cases and scoped lint passed before source publication. Exact-source Verify
  is still an independent gate.
- Next: narrow trusted supervisor plus unprivileged API bootstrap, operational
  archive validation, Android signed history/current-state export and
  server-chat adapter. Then actual runtime/load/storage/phone checks. The adapter
  alone is not full AI activation. See the2026-10-15 contextual-text spec.

## Runtime Candidate Delivery (2026-10-15)

- Owner approved seven-day CI retention of the verified Linux candidate, not
  release/deployment/authorization. Add bounded source-bound package creation,
  external-manifest directory/ZIP verification and resolved dependency notices.
- Test the packaged bytes in existing offline and ten synthetic protocol cases;
  retain only on explicit manual dispatch. Default push uploads no binary or
  session/test-state. Exact-source native/Verify CI and downloaded-archive checks
  remain gates; do not infer runtime adoption from successful unit tests.
- Next: host ABI/library/licensing review, immutable installation/rollback,
  launcher/profile-controller integration and private owner code presentation.
  Existing server, phone, authorization and therapy paths remain unchanged.

## Inactive Session Profile Guard (2026-10-15)

- Added the source-only local serialization/admission component after synthetic
  exchange passed at9291475d.38 focused synthetic file/process checks and the full
  local backend536passed/26skipped/1warning pass. Exact-source publication/Verify
  CI remains a gate; skipped checks were not executed.
- Permanent lock, durable crash marker, pinned directory/journal/run/scope,
  cancellation-drained exact stop and explicit bound recovery only. No existing
  profile/auth, real stop/server/phone/model or therapy operation is connected.
- Next: immutable runtime delivery/containment manifest, private owner challenge
  presentation and admitted real provider/authenticated startup/model behavior.

## Synthetic Session Exchange (2026-10-15)

- Previous six actual native protocol cases passed atb076302b; candidate remains
  undeployed. Add a separate four-case exchange mode: full synthetic success,
  native persisted login-status readback, OAuth403, malformed tokens and invalid
  identity rejection. Nine failing-first guards and all66 local tooling cases
  pass; exact-source native/Verify CI remains a gate for this new stage.
- Default unauthenticated admission stays unchanged. No real profile/account,
  model call, production gateway/controller or phone/therapy change. Next gates
  remain serialized profile/orphan admission, immutable runtime delivery,
  private owner code presentation and contained authenticated runtime/model tests.

## Synthetic Native Protocol (2026-10-14)

- Exact-source native component tests/full CLI/offline version/provenance and
  push/PR Verify passed at11e03445. The candidate remains undeployed.
- Implemented six CI-only local TLS protocol cases plus no-client preflight,
  identity/route/body/syscall/private-state guards and independent namespace
  teardown. All44 local tooling cases, Ruff and shell syntax passed. Actual
  compiled-client protocol and exact-source publication/CI remain gates.
- No real account login/token exchange, profile controller, production gateway,
  authenticated inference, device history/chat or phone/therapy change.

## Atomic Session Storage Candidate (2026-10-14)

- Original Rust file operations reproduced destructive interruption. The narrow
  tempfile/sync/replace candidate passed10 parent tests and9 verifier/fetch cases;
  source compatibility verifies clean application and exact tested helper bytes.
  Local format/Clippy/Python checks passed; an independent Linux CI gate is added.
- Subsequent native-build validation passed; this is not deployment or an
  authenticated runtime. Resolve provider-rotation gaps and bounded serialized orphan admission,
  obtain runtime-change approval and capture the actual isolated client before
  real owner login. No old auth, host keyring, phone or therapy change.

## Independent Login Profile (2026-10-14)

- Owner approved one new private profile for separate ChatGPT login. Sixteen
  private creation/admission/rollback tests passed; fresh remote admission and
  independent post-creation checks confirmed an empty service-owned0700 directory.
  No old authorization, existing service/configuration or phone was touched.
- Pinned official source identifies device-code account login and neutral client
  initialization as the next candidate. Actual contained protocol/header/TLS and
  private-state/cancellation verification precede real owner participation.
  No authenticated inference, history ingestion, contextual chat or therapy gate
  is satisfied by profile creation. Operational receipts remain private.

## Public Transport Validation (2026-10-14)

- Added inactive observed profile/strict status-only receipt; unchanged original
  profile.31 contract and4 actual-engine checks passed in the full local524 gate,
  with zero skips/failures/errors and one existing warning. Private14 passed.
- One bounded owner-approved operation proved host-network deny/allow before
  two anonymous provider HEAD requests; matching local/upstream403 and verified
  TLS passed for both fixed names.13 controls and16 bound stops passed; independent
  own-object absence and source/public-CA preservation checks passed.
- Exact-source publication/CI remains separate. Minimal independent login,
  pinned-client protocol capture, real neutral inference, contained API/retrieval
  and Android context transfer/chat remain gates. No credentials, medical data,
  phone, therapy, public API or existing production-service change.

## Credential-Free Transport Profile (2026-10-14)

- Owner approved isolated gateway design. Implemented the first pure source slice:
  immutable inputs, distinct synthetic/public profiles, HEAD-only TLS gateway,
  bounded curl command and strict transport receipts. No privileged launcher.
- Focused85 and full local468 checks passed, including22 actual Nginx1.24/curl
  cases using local Unix TLS peers. Three Python hashes stayed stable through
  the full gate. Configuration/engine tests are not host namespace/cgroup proof.
- Before any remote test: exact prepared-root/file/unit/hash manifest, actual
  enforcement/environment admission, all-role stop receipts and cleanup approval.
  Authorization/model request, signed API/context and Android chat are later gates.

## Personal Device History API (2026-10-14)

- Approved own-device architecture and365-day retention. First local stage:
  typed observational transport, private per-key encrypted payload storage and
  explicitly injected signed append/query API, independent of inference jobs.
- The56 new cases pass in the full suite after reproducing missing contract/routes and
  storage edge cases: corrupt duplicates being acknowledged and a metadata-read
  failure leaking a connection. Added revision/rollback/tombstone/retention/
  pagination/quota/restart/restore/identity/replay/revocation/coexistence checks.
- Final backend passed383 tests with one existing deprecation warning. The seven
  reviewed Python file hashes stayed unchanged. Isolated E/F checks passed;
  imported pytest fixture redefinitions are explicitly exempted. Exact-source
  publication/CI remains a gate. No Android changes;
  its previously passed gate is separate from this backend implementation.
- Later stages remain: complete Android export contract/adapters, durable
  enrollment reassociation, production storage/key/purge/backup/load validation,
  contained real inference, retrieved context and actual device chat. No live
  clinical GI activation or production setup is claimed.

## Explicit Eating Soon Meal Intent (2026-10-14)

- Reproduced four failures before the fix:target called after carb/profile,
  skipped on carb block, erased by profile failure and hidden by carb messages.
- Attempt the existing guarded explicit target before carbohydrate transport and
  profile annotation. Separate localized results, immutable input and no replay.
- Added all-profile/optional-GI, mode/HTTP safety, cancellation, IOB split and
  exact30-minute priority/expiry regressions. Final focused208 checks passed
  across9 suites. Full local unit/lint/compile/assemble passed in12m58s:5520
  passed,6 optional private-input skips, zero failures/errors and lint errors.
  All12 reviewed Android Kotlin/XML hashes unchanged; existing APK v2 signer
  verified. After public fixtures were anonymized, final exact-source full gate
  passed in4m51s with the same counts and all12 final hashes unchanged.
  Exact-source publication/CI remains a gate.
- No new target/IOB physiology, relaxed safety, GI activation or phone operation.

## Meal Input Observations (2026-10-14)

- Correct falsely hardcoded raw CGM research identity: freeze actual resolved
  basis/calibration ID, require source measurement metadata and comparison v2.
- Add passive Room34 accepted-input observations after clinical fan-out. Original
  CGM/insulin/context remain distinct from calibration/alignment and inferred
  complete intervals. No training or active-target admission.
- Strengthened focused source/basis/Room/migration checks passed83 cases with1
  optional external-copy skip. Corrected full local unit/lint/compile/assemble
  passed in11m38s:5510 passed,6 optional private-input skips, zero failures/errors
  and lint errors across484 suites. All29 reviewed Android hashes unchanged;
  existing APK v2 signer verified. Exact-source publication/Verify remains a gate.
- Next: independently qualified interval/context review and prospective candidate
  collection/evaluation, not automatic therapy experiments.

## Personal Meal Forecast Candidate (2026-10-14)

- Implement qualified immutable timing/optional-known-GI research artifact and
  disposable causal engine replacement of one confirmed canonical meal.
- Past/future/COB/UAM consistency, Kalman/AR revision handling, unchanged default
  and meal-timing scenarios, strict local/raw/context/availability gates.
- No clinical writer, live subscription, phone or new database schema. Next:
  independently qualified live source and pre-outcome collection, prospective
  evaluation and separate control-safety review; no applied-target admission.
- Initial87 and strengthened88 focused checks passed, including populated AR,
  held-out evidence, training-mass and future-clock guards. Full local Android
  unit/lint/compile/assemble passed in11m31s:5492 cases passed,6 optional private-
  input skips, zero failures/errors and lint errors. All9 reviewed Android hashes
  remained unchanged; existing APK v2 signer verified. Exact-source CI is separate.

## Prospective Journal (2026-10-14)

- Connect accepted-cycle observational capture and restart-safe Room33 storage,
  with post-commit/unfinished semantics and isolated21-day retention.
- Separate baseline-only scores and model cohorts; passive on-demand Analytics
  review of support/MAE/lows/missingness, not clinical admission.
- Focused96 checks and native compact/large-font review pass. Full local unit/
  lint/compile and resumed assemble pass:5479 cases passed,6 optional external-
  input skips, zero failures/errors and lint errors. Unchanged reviewed Android
  hashes and existing APK v2 signer verified. Exact-source CI is separate.
  No phone, real personal accuracy or therapy activation claim.
- Qualified causal personal candidate, original transport availability and
  independent control assessment remain separate prerequisites.

## Prospective Meal Forecast Contract (2026-10-13)

- Implemented immutable pre-outcome paired receipts, strict causal/model/input
  identity checks and an on-demand accepted-runtime adapter with no action path.
- Exact paired outcomes, day-balanced mean errors, separate model/context
  cohorts, low/lower-bound misses, interval support and explicit missingness.
  Overflow rejects instead of truncating; conflicting generations cannot cherry
  pick a forecast. Caller-supplied private receipts export aggregate diagnostics.
- First35-case focused run reproduced2 defects; both corrected. Final focused
  run passed35 checks with the optional file tool skipped. Further import review
  reproduced and fixed a missing comparison-basis rejection. Final focused checks
  passed36 cases with1 optional skip. That tool separately
  passed an explicit synthetic-bundle/report check. Full local Android unit/lint/
  compile/assemble passed:5462 cases passed,6 optional external-input skips,
  zero failures/errors and zero lint errors. Existing APK v2 signer verified.
  Exact-source publication/CI remains a separate gate for this source stage.
- Not a completed real-data accuracy evaluation or live collection feature.
  Next prerequisites: trusted restart-safe receipt/outcome collection, qualified
  prospective personal candidate, observed-data evaluation and independently
  reviewed control-safety admission. No experimental target activation.

## GI Contribution Analysis (2026-10-13)

- Implemented qualified known-GI contribution analysis: frozen chronological
  split, context cohorts, type/personal/GI ablation,5/30/60 conditional response
  errors, mass stress, missingness and explicit target-integration blockers.
- On-demand same-snapshot Room join and private aggregate replay tool, without
  clinical registration. First focused source/domain/Room pass:72 tests.
- Expanded direct checks passed74 cases plus an explicit synthetic report run.
  Full local Android unit/lint/compile/assemble passed:5440 tests passed,5 optional
  external-input skips, zero failures/errors and zero lint errors. Exact-source
  publication/CI remains a separate gate. Actual clinical forecast validation
  and control-safety admission are not completed. No phone/therapy activation.

## Primary Mixed COB Display (2026-10-13)

- Implemented presentation-only primary runtime COB with explicit Copilot label
  and independent AAPS reference/time in food information.
- Strict source/quality/range/latest-conflict handling; independent one-shot
  expiry, zero vs unavailable, passive compact/large-font UI and retained curve
  regression coverage. All146 affected-suite tests pass and native screenshots
  are inspected. Full local unit/lint/compile/assemble pass:5418 tests passed,
  4 existing private-data skips, zero failures/errors and zero lint errors.
- No GI inference, therapy changes, phone stop or installation in this stage.
  Publication and exact-source CI remain separate verification gates.

## Event Compensation Context (2026-10-13)

- Short Carbs/Insulin labels, GI with saved source or explicit missing state.
- Bounded on-demand historical CGM/ISF/sensor context, canonical revision checks,
  invalid/stale/mixed/overflow handling and passive native popup coverage.
- Source queries/no-write behavior, pure edge cases and compact/large-font
  dialog verification pass48 focused cases. Full local unit/lint/compile/APK
  gates pass:5408 passed,4 existing skips, zero failures/errors and lint errors.
  Exact-source CI and separately authorized device verification remain gates.
- Individual minute-specific food timing advice needs a separate validated
  meal-start/delivered-bolus evidence contract; it is not inferred from a marker.
- No therapy or phone operation is authorized by this UI change. Existing phone
  recovery and installation acceptance remain separate operational work.

## Chart Event Popup (2026-10-13)

- Device follow-up: correct full-payload amount display and transport-marker
  text. Three synthetic assertion failures reproduced; fixed focused/native
  checks pass27 cases. Full local unit/lint/compile/assemble passed:5387 tests,
  4 opt-in private-data skips, zero failures/errors and zero lint errors. Exact
  source CI and separately approved corrective device verification remain gates.
- Expanded existing AlertDialog approved and implemented with typed read-only
  details, preserved cluster selection and current-record updates.
- Missing blood-value, two display review regressions and the attribute-cap
  regression reproduced and fixed. Final focused/native verification passed24
  cases; narrow, large-font and landscape screenshots were inspected.
- Full local unit/lint/compile/APK gates passed:5384 passed,4 existing private-data
  skips, zero errors/failures. Published source30f7c94b passed exact-source Verify
  for push38368380803 and PR38368388884.
- No phone update or runtime therapy activation belongs to this stage.

## Qualified Personal Meal Evidence (2026-10-13)

- Written target-integration specification approved; this stage is the research
  source prerequisite, not clinical forecast or target activation.
- Implement a bounded canonical Room join and explicit prepared-episode producer;
  missing legacy metadata, conflicts and unsafe contexts stay rejected.
- Real adapter timezone RED reproduced; focused source/domain/Room verification
  passed81 cases. Final combined local Android gates and exact-source push/PR
  Verify passed for published source30f7c94b. The source stage is complete.
- Model predictive validation, prospective capture contract and separate control
  safety assessment remain subsequent stages. No numerical target percentages.

## Lenta History Connection (2026-10-13)

- Add separate Estimate mode with full modeled-gram horizon and passive point
  inspection. Preserve Current COB and canonical Food/Eating Soon routes.
- Connect bounded confirmed-meal history immediately, with sparse quantity and
  provenance-backed historical GI influence. Do not fake missing CGM context.
- Unit/source/native/layout and full Android lint/compile/APK/CI are release
  gates. Implementation is in progress; no new release or phone install is claimed.
- CGM-trained GI remains unavailable without qualified historical context.

## Approved Infusion Indicator (2026-10-13)

- Strict bounded original-event timing and pure72h policy, independent reminder
  DataStore/generation/watermark, silent notifier and WorkManager lifecycle wiring.
- Worker final canonical check/post binding is tested against actual Room source;
  no-CGM, stale source/deletion, overflow and in-flight receipt serialization covered.
- Native primary indication/details, age unknown/due versus current/retained
  diagnostic evidence, zero action callbacks, compact/large-font/landscape checks.
- First26 core/platform/store checks passed; initial80 combined checks exposed
  landscape test framing, corrected with explicit scroll and fresh screenshot.
  Complete release gate and exact-source CI are required before stage completion.
- No new consumption/ISF detector, clinical control or phone/sound activation.

## Approved Dashboard Controls (2026-10-13)

- Written IOB and direct schedule specifications approved by the user.
- Shared IOB display preserves signed totals, independent missing values, atomic
  number wrapping and original passive Details in both primary renderers.
- Every interval target stepper stays visible; Edit expands time only. Single
  explicit validated Save and no-write Cancel preserve all existing target policy.
- Functional REDs cover missing IOB pair, split digits, hidden interval controls,
  undersized buttons and a split default label. Focused native/domain verification
  and complete Android/CI release gates are tracked in the implementation plans.
- Approved infusion indicator is the next independent stage; Lenta Estimate
  written review remains pending. No phone or real therapy/settings changes.

## Approved Hybrid Minimal-Data Preview (2026-10-12)

- Implements the requested combination with zero-history type/portion defaults
  and observations beginning at one independently qualified completed episode.
  Body-profile data is optional context, not a numerical GI/shape multiplier.
- Shares personal evidence preparation and retains existing chronological fit
  validation. Descriptive medians/IQR cannot activate sparse personal timing.
- Complete immutable model curve, declared/suggested quantity origins and rejection
  reasons remain advisory/unregistered; no UI, worker or clinical/phone changes.
- Missing-feature and unfinished-day/digest-order functional REDs preceded fixes;
  focused65 cases pass. Full Android gate passes:5267 cases,zero failures/errors,
  four optional-input skips; lint zero errors, Kotlin/APK/signature verified.
  Exact-source publication/CI remains a separate final gate, with no clinical
  calibration or device acceptance implied.
- Detailed plan: superpowers/plans/2026-10-12-hybrid-meal-response.md.

## Approximate Meal Estimation Diagnostics (2026-10-12)

- Implements declared-portion priors with honest empirical spread and a pure
  conditional CGM timing evaluator, following approximate-history clarification.
- Uses explicit evidence qualification, bounded chronological holdout, immutable
  steps and source/model digests. No numeric learned GI or measured-mass claim.
- Read-only Room/private-copy audit and prepared-JSON runner remain offline;
  no production forecast/controller/display/worker registration or phone change.
- Actual therapy integration remains outside this stage, pending independently
  specified and validated clinical control requirements.
- Detailed plan: superpowers/plans/2026-10-12-personal-absorption-shadow.md.

## Approved Compact Clarity And Lenta (2026-10-12)

- Implements the approved compact main view, top refresh/blood actions and
  removal of duplicated More/lower tools. Preserve unique safety and source help.
- COB opens the selected full food Lenta; plus keeps the existing meal form.
  No manual GI input, guessed GI, per-meal remaining COB or therapy-policy change.
- Native regressions cover full default horizon, source errors, action enablement,
  normal/enlarged layouts and passive/explicit-confirmation routes.
- Full Android gates, exact-source CI and signed APK are required before stage
  completion. Device installation and historical estimation are separate work.
- Detailed checked plan: superpowers/plans/2026-10-12-compact-clarity-lenta.md.

## Preserve Familiar Metric Menus (2026-10-12)

- Restore COB to the existing meal-entry dialog shared with add-meal. Retain the
  separate full food-curve row and all existing meal fields/validation/sending.
- Native regression: COB and add-meal open the same form; curve remains separate.
  IOB, ISF/CR, UAM and base-target menus reuse existing routes, with no automatic
  submission/settings change on open or cancel. Preserve compact Balance.
- Android unit/lint/compile/APK and exact-source CI are separate gates. Corrected
  phone update requires fresh coherent backup and explicit pause authorization;
  no real meal, target, bolus, profile or alarm test is authorized.

## Approved Balance Layout (2026-10-11)

- Replace the large forecast row with small passive chart marks; expose six core
  metrics before the compact chart and keep the full food-tail view separate.
- Reuse current/missing/stale and observed/proposed target semantics, existing
  warning correction routes and explicit Eating Soon submission guards.
- Correct secondary-to-primary navigation without saving forms or losing an
  existing requested primary entry. Native NavHost and responsive rendering gates.
- Full Android unit/lint/compile/APK, reviewed source publication and exact-SHA CI.
  Actual phone install/rendering/TalkBack and CPU/RAM remain separate acceptance.

## Food-Profile Calculation Availability (2026-10-11)

- Reproduce unrestricted telemetry loading and the absent initial refresh route.
- Load only sensor-block evidence within the completed-day range using existing
  indexes; retain case/text compatibility and all inference eligibility checks.
- Request unique initial diagnostic work when an enabled profile has no snapshot,
  preserving power-save exclusion and the existing nightly cadence.
- Verify real Room selection/query plans, startup guards/errors/cancellation,
  inference, absorption/GI/food-display regressions and full Android quality.
- Publish reviewed source and inspect exact-commit CI. Actual device failure,
  calculation completion and CPU/RAM remain separate unavailable-device checks.


## Profile Save Completion (2026-10-11)

- Reproduce premature form dismissal and loss of edits after a rejected save.
- Acknowledge profile, joint correction, food, activity and calorie writes only
  after persistence. Keep errors and drafts in the editor; allow explicit retry.
- Apply the same confirmation to the adjacent planned-activity editor after a
  separate native reproduction, preserving its clinical-input persistence path.
- Revalidate/merge within DataStore, preserving independent current settings and
  the existing leased physiological-sex change, pediatric policy and bounds.
- Verify native forms, real preferences-file close/reopen, cancellation, failures
  and concurrent updates; run full Android unit/lint/compile/APK and exact-SHA CI.
- Phone profile/restart checks remain separate. No synthetic therapy commands,
  live personal-profile fabrication or unapproved alarm-hardware activation.

## Native Clarity And Accepted Food Lifetime (2026-10-11)

- Implement selected native Clarity with compact glucose/Target Manager,
  explicit canonical Eating Soon delivery states and passive typed details.
- Keep one scoped accepted food-display tail at its original clock. Provide
  full-period/start/end navigation without inventing grams, rate or endpoint.
- Gate on narrow native light/dark/large-font checks, authority/lifetime tests,
  full Android unit/lint/compile/build and reviewed exact-commit CI.
- Phone backup/install/passive verification remains a separate gate. No test
  therapy commands, AAPS/pump stop or unvalidated clinical/alarm activation.

## Prediction Time Integrity And Refinement (2026-10-11)

- Reproduced and corrected residual AR learning across missing buckets, the
  reconstructed 30m timestamp and premature UI quality scoring. Explicit clocks
  and binary-search matching retain existing observed interpolation behavior.
- Enhanced version becomes `local-hybrid-v3.1`; exact-version reliability needs
  its own matured evidence. Continuous-history coefficients and target guards
  are unchanged. Full source verification/publication is recorded in AI_NOTES.
- Detailed follow-on experiments: [prediction audit](2026-10-11-prediction-audit.md).
  Priorities are reversal response, residual drift, paired bias-layer ablation,
  consistent evaluation provenance and held-out uncertainty calibration. These
  candidate algorithms are not enabled by the time-integrity corrections.


## Actionable Errors (2026-10-10)

- Implement typed passive help and navigation for shared screen errors, stale
  banners, app health, profile warnings, pump-link information, Target Manager
  status and failed Safety checks.
- Profile reasons distinguish editable fields from missing/stale calculations;
  direct settings requests select the tab/section and open the matching editor.
- Cause mapping, exact editor routing, explicit/joint saves, comma decimals and
  enlarged Russian native UI are verified. Intermediate full5117 unit cases
  passed with3 optional phone-copy skips; final targeted129 cases pass without
  failures/errors/skips. Final lint/compile/APK and isolated UI-test compile pass;
  lint has zero errors,313 warnings and4 hints. Full Verify runs on the published
  commit separately; retain its exact-SHA result rather than infer it from a push.
- No working phone update or clinical algorithm changes in this source stage.

## Eating Soon Queue Priority (2026-10-10)

- Reproduced three REDs: queued manual intent absent from Room, queue cancellation
  without known-not-sent evidence and noncanonical manual-entry payload admission.
- Reserve exact frozen Eating Soon before target-mutex waiting; reuse existing
  manager pending-manual guard. First-owner pre-admission cancellation is BLOCKED;
  duplicates/post-admission uncertainty are not replayed or reclassified.
- Initial focused52 cases/4 suites pass. Expanded cancellation/duplicate/borrowed
  payload verification passes332 cases/18 suites. A late real RED showed the
  generic retry entry point replaying unknown Eating Soon; these keys now reuse
  ordinary no-replay submission. Final focused372 cases/18 suites pass with zero
  failures/errors and1 optional phone-copy skip.
- Initial full5081 cases/433 suites, zero failures/errors and3 optional phone-copy
  skips; lint0errors/305warnings, compile/build passed9m14s before the late source
  boundary change. Final full5082 cases/433 suites pass with zero failures/errors
  and3 optional phone-copy skips; final unit/lint/compile/APK passed11m6s with
  zero lint errors. Inline review completed; exact-head publication/CI proof stays
  in private task artifacts. No synthetic therapy test. USB disconnected before
  the newly approved coherent backup/update; no real failure cause is claimed.

## Eating Soon Handoff (2026-10-10)

- Three failing regressions established ordinary overwrite, protective writer
  delay and missing Room intent. Bind unique canonical SENT manual request to
  current resolved AAPS target, without changing eligibility or clinical limits.
- Retain its window against ordinary control/recovery; existing protective
  increases retain all safety/priority/dispatch gates. Context identity is
  rechecked; observed newer manager target plus unique SENT proof can release
  the older SENT hold, never pending/unknown requests. No automatic keepalive.
- Focused230 cases/13 executed suites pass. Full unit5075 cases/433 suites pass with
  zero failures/errors and2 optional phone-copy skips. Official migrations on
  a disposable current-phone copy pass. Full unit/lint/compile/debug-build passed
  in18m3s; fresh lint has zero errors/305 warnings. New-SHA Verify is recorded
  separately after publication and actual completion. Overlapping confirmed
  Eating Soon holds have a separate real RED/GREEN boundary regression.
- Plan:superpowers/plans/2026-10-10-eating-soon-target-handoff.md. Device update
  and natural clinical handoff verification remain separate. ADB currently empty;
  no test therapy action, new alarm activation or sound/settings change.

## Pump-Link Source Producer (2026-10-10)

- Added checked immutable current technical-source publication to the existing
  monitor. Exact saved provenance, current boot/elapsed/condition/deadline read,
  superseding/failure/recovery/intentional-disconnect revocation and committed
  OFF/resume cleanup are covered without a new timer or alarm activation.
- Plan: superpowers/plans/2026-10-10-pump-link-source.md. Final95 tests/7 suites
  pass,20 new cases, zero failures/errors/skips. Full Android quality passed
  in4m24s:5059 cases/432 suites, zero failures/errors,3 optional phone-copy skips.
  Fresh lint: zero errors,305 existing warnings,4 hints, no new source issues.
  Exact-source publication/Verify is recorded separately after completion.
  Actual REDs covered current-boot faults with retained historical episodes and
  deadline sliding during a slow subscriber. Old-boot elapsed origin is not
  compared with current time; existing post-publication timer capture remains.
- Next: accepted glucose/diagnostic producers and complete current-context bridge
  with stable alarm identity/age, explicit OFF-intent cancellation and platform
  capability/opt-in admission. Async failures/native fallback, foreground/wake,
  visual/vibration channels, ACK UI and device/night acceptance still remain.
- No production coordinator consumer, clinical/legacy change or phone action.
  Original incident missing history, persistent COB and matched resources remain
  open. Technical link evidence cannot determine whether the pump is worn.

## Inactive Alarm Coordinator (2026-10-10)

- Added explicitly run serialized ownership, conflated wake/nearest timer,
  synchronous authority revocation and cancel/join before replacement claims.
  Executor reports committed ownership and terminal cleanup even on cancellation.
  Separate lifetime/global-OFF locks and metadata-only UNCERTAIN recovery retain
  due/ordinal/progress/ACK, without speculative START or abandoned replay.
- Unconfirmed cleanup/storage stops and latches this database-instance runtime
  unavailable in process; another constructor cannot bypass it. No reset/retry.
- Plan: superpowers/plans/2026-10-10-local-alarm-coordinator.md. Final relevant
 216 cases/14 suites pass, including27 new cases. Full Android unit/lint/compile/
  debug-build passed in4m38s:5039 cases/431 suites, zero failures/errors,3 optional
  phone-copy skips. Fresh lint has zero errors,305 existing warnings,4 hints and
  no new coordinator/ownership issues. Reviewed publication/exact-source Verify
  is recorded separately only after actual completion.
- CI follow-up: original9f14b549 push Verify succeeded but PR Verify failed one
  duplicate-update timing test. Controlled suspended wait-registration reproduced
  a fixture RED. Clock/wait and terminal-row synchronization fix it without
  changing production code, increasing timeouts or weakening assertions.
  Coordinator17-case GREEN passes; full Android recheck passed in2m51s with
 5039 cases/431 suites, zero failures/errors,3 optional phone-copy skips and
  zero lint errors. New-SHA CI is recorded separately after actual completion.
- No production construction/source hooks, opt-in/service/permission/schema,
  notification/vibration/wake/native fallback or clinical change. Accepted
  source/OFF producers, async media errors, dedicated foreground lifecycle,
  capability/UI and device/night acceptance remain. ADB still has no phone.

## Read-Only Alarm Arbitration (2026-10-09)

- Added bounded priority/oldest-due selection, exact-owner retention/preemption,
  source/OFF invalidation decisions, explicit abandoned-claim recovery and one
  nearest deadline. Room preview freezes inputs and validates actual OFF/state
  without creating claims or mutating journal/ACK/repeat/progress.
- Plan: superpowers/plans/2026-10-09-local-alarm-arbitration.md. New tests cover
  domain and native Room boundaries. Missing-API REDs and a real borrowed-input
  race RED were reproduced; the indexed-read batch is now frozen. Final focused
 112 tests/5 suites pass,27 new cases, zero failures/errors/skips.
- Full Android unit/lint/compile/debug-build passed in6m25s:5012 tests/429 suites,
  zero failures/errors,3 optional phone-copy skips. Fresh lint has zero errors,
 305 existing warnings,4 hints and no arbitration issues. Reviewed publication
  and exact-source Verify are recorded separately after actual completion.
- Remaining: accepted source producers plus serialized cancel/join/claim runtime;
  async failure/native fallback, visual/vibration/wake channels, foreground
  lifecycle/capabilities, default-off opt-in and ACK UI; fresh device migration,
  authorized install and locked-screen acceptance. No runtime caller/activation.

## Local Alarm Cycle Executor (2026-10-09)

- Implemented the next bounded integration: one new Room claim, read-only
  authoritative OFF admission, exact-window confirmed volume and existing audio.
  No runtime caller or activation. Plan:
  superpowers/plans/2026-10-09-local-alarm-cycle-executor.md.
- Focused143 tests/6 suites pass, including23 new tests. Three real review REDs
  reproduced then fixed: unfinished ownership during cleanup cancellation,
  false FINISHED after stop failure and a missed step admitted by a slow read.
- Final full Android unit/lint/compile/debug-build gate passed:4985 cases/
  427 suites, zero failures/errors,3 optional phone-copy skips; fresh lint has
  zero errors,305 existing warnings,4 hints and no executor issues. An obsolete
  volume-consumer architecture assertion was updated to permit only the inactive
  executor and still prohibit runtime construction; full gate rerun passed.
  Exact-source publication/Verify is an external gate, recorded in the private
  native checkpoint only after actual completion. No main merge or phone update.
- Remaining alarm work: serialized multi-source priority/fairness and accepted
  source/OFF events; notification/vibration/wake/native fallback and async media
  failure; dedicated foreground lifecycle/capability status; default-off opt-in
  and explicit ACK UI; fresh migration/install/locked-screen acceptance.

## User-Requested Follow-Through

- Alarm integration remains first because delivery is incomplete. Original20h
  incident audit still lacks the final11h27m; no new phone snapshot replaces it.
- Persistent full-tail COB curve design is acknowledged, implementation remains
  pending after alarms. Preserve accepted display origin and explore/scroll
  state; external reference COB cannot invent food mass or absorption timing.
- CPU/RAM work needs matched phone evidence and query attribution. Source-side
  input coalescing and bounded diagnostics remain candidates, not measured gains.
  Keep every-new-current-CGM calculation and all existing clinical safety gates.
- Meal timing research still lacks calibrated trajectories/controller/nuisance
  scenarios, live orchestration and clinical notification admission. Complete
  those evidence gates without converting preliminary models into therapy.
- Full code/CI gates do not close device, calibration or clinical-release gates.

## Guarded Local Alarm Playback (2026-10-09)

- Bounded transport stage after policy/journal/volume: reuse the existing player
  with explicit current admission, canonical clip-start windows, preparation
  timeout, absolute cycle bound and exact cancellation. No runtime caller.
- Plan: superpowers/plans/2026-10-09-guarded-alarm-playback.md.
- Missing-API RED was inspected after correcting a test-only callback signature.
  Initial focused25 cases passed. Self-review then demonstrated two actual RED
  defects: missed timer capture left a player open; duplicate prepared callback
  extended a clip. A late actual RED also caught stop-delay extension during
  post-start admission; the captured absolute end now bounds its timer.
  Final focused80 cases across7 suites pass with zero failures/errors/skips,
  including30 new cases. A paused seek callback
  needed explicit dispatch in the fallback fixture; no production workaround.
  Final Android unit/lint/compile/assemble passed in9m15s:4962 cases across426
  suites, zero failures/errors,3 optional phone-copy skips. Fresh lint XML:
  zero errors,305 existing warnings,4 hints; the controller's two SDK-guard
  warnings already existed in the baseline. APK contains the new window/API.
  Reviewed publication and exact-source CI remain the external source gate.
- No service/source/UI/setting/permission, volume lease consumer, vibration,
  wake lock, notification, Room, clinical/therapy/target/forecast/backend change.
- Actual device/incident audit, authorized fresh-backup update and sound tests,
  matched CPU/RAM evidence, and complete opt-in runtime integration remain gates.
  Fresh read-only ADB still has no phone; no device writes were performed.

## Guarded Alarm Volume Lease (2026-10-08)

- Next bounded prerequisite after policy/storage: single exact-cycle volume
  ownership and an alarm-only Android adapter, without production consumers.
- Fresh admission/elapsed guards, safe round-up, no downward raise, confirmed
  readback, sticky observed override/uncertainty and guarded baseline cleanup.
  Reentrant acquisition/restoration and post-cancellation writes reject.
- Plan: `superpowers/plans/2026-10-08-alarm-volume-lease.md`. Absent APIs were
  verified RED; three real callback/reentrancy regressions failed before fixes.
  A late floor regression also failed: a25% caller request weakened LOW_NOW.
  The lease now enforces the canonical first-target minimum. Focused92 cases,
  including32 new cases, pass with zero failures/errors/skips.
- Full Android unit/lint/compile/assemble passed:4932 cases across424 suites,
  zero failures/errors,3 optional phone-copy skips. Fresh lint XML has0 errors,
  305 existing warnings,4 hints and no new volume-file issues. Candidate APK
  contains both new classes but is not installed. Reviewed publication and
  exact-source Verify are tracked as the external source gate.
- No service/player/source/UI/settings/Room/clinical change.
- Fresh ADB is empty. APK installation, incident/device/audio acceptance and
  matched CPU/RAM measurements remain separate gates; no active ramp claim.

## Durable Local Alarm Storage (2026-10-08)

- Persistence stage of the Oct7 design: additive Room32 local state/cycle journal,
  strict bounded codecs, atomic claims and independent channel results.
- Shared Room mute ordering, post-lock clocks, exact callback/ACK identity,
  revision CAS, uncertain recovery and bounded indexed scheduling/retention.
  Interrupted steps are not replayed and ordinals are never reset on bad state.
- Plan: `superpowers/plans/2026-10-08-local-alarm-storage.md`. Migration RED
  expected32/actual31, absent-codec/store API RED and three actual self-review
  regressions were inspected. Focused94 cases passed, including25 new cases,
  zero failures/errors and one optional current-phone-copy skip.
- Full Android unit/lint/compile/debug-build gate passed:4900 cases, zero
  failures/errors,3 optional phone-copy skips. The old31 architecture head
  expectation was repaired and read-path bans strengthened. Refreshed Lint:
  zero errors,305 existing warnings,4 hints, no alarm-file issues.
- Storage has no runtime consumers; new sound,
  foreground lifecycle, UI, source adapters and pruning scheduling are separate
  required integration stages. Therapy/target/forecast/settings remain unchanged.
- Fresh read-only ADB still has no phone. No APK installation, activation,
  audio/volume/DND change, full-night validation or CPU/RAM measurement occurred.
  Fresh data backup and disposable/device migration remain release gates.

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
  originally paused one repeat interval; the 2026-10-20 fix separates this into
  a minimum ten-minute pause. Neither resolves risk nor silences other sources.
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
