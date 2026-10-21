# Delivery Workstreams

## Scope And Current Dispatch

Source audit: 2026-10-21, combined release at `3f180d4b` and main at
`7eaa1862`. The owner requested parallel assignments and controlled main
integration. This does not prove a server rollout, device acceptance or clinical
validation. No medical data belongs in these briefs or public test fixtures.

Native subagent execution is unavailable in the current session. The assignments
below are prepared, not running agents. Do not substitute user-owned chats,
unapproved external executors or reduced model settings for native subagents.
The combined release was merged into main as ad678a84 with successful
Verify39580751668. The coordinator now implements A1's single-record native EPS
projection after the retained-timer release0.1.8/main01ccca90. A2's target
transaction/privacy source is present; actual clinical readiness and device
acceptance remain incomplete. The 48-case profile/TLS checks and full Android
verification passed (6635 passed/six existing skips). Exact-head CI and main
integration remain separate gates for this new slice. The
other assignments remain **ready** or **dependency blocked**, not running agents.

| Owner | Work | Current source state | Dispatch |
| --- | --- | --- | --- |
| Coordinator | Integration, review, main and release evidence | Retained-timer release delivered; A1 profile slice under verification | Active |
| A1 | AAPS 4 clinical input and profile adapter | Typed EPS slice under verification; active resolution/live clinical gate remain closed | Coordinator active |
| A2 | AAPS 4 target admission, TLS, JugglucoNG | Target-only transaction/privacy source present; trust/device gates remain | Runtime wiring waits for A1 |
| A3 | Meal timing and personal absorption evidence | Countdown lifetime repaired; recommendation/control not validated | Ready, research only |
| A4 | Structured device history and compact context | Core history upload exists; full typed clinical coverage incomplete | Ready |
| A5 | Durable device chat and chat attachments | Encrypted archive exists; native sessions ephemeral, text attachments only | Ready, staged |
| A6 | Nightly reviews and recommendation inbox | Source/worker/archive exist; GLUCOSE_ONLY contract | Depends on A4 contracts |
| A7 | Protected admin interface | Mockup/views are not a verified admin deployment | Ready |
| A8 | Food photo recovery and result quality | Separate vision route and fixes exist; device acceptance incomplete | Ready |
| A9 | Alerts and audio variants | ACK/foreground owner/alarm connected; device/audio checks remain | Ready, silent tests first |
| A10 | Standalone monitoring and contextual patterns | Manual diary exists; independent clinical pipeline incomplete | Ready, separate evidence stages |
| A11 | UI, export and performance acceptance | Focus+, owl UI and JSON implemented; physical acceptance remains | Ready |
| A12 | Unified public product | Architecture plan only; AAPS UI remains primary | Dependency blocked for implementation |

## Shared Ownership Rules

- Each agent receives one bounded stage, current source SHA, its owned paths and
  acceptance commands. Separate worktrees; no simultaneous edits to one file.
- `AppContainer.kt`, `MainViewModel.kt`, `CopilotFoundationRoot.kt`, Room schema,
  common settings/DTOs, build files, CI and shared docs are coordinator-owned.
  Agents return precise integration patches/contracts instead of editing these
  concurrently. Feature-specific docs and tests belong to the feature owner.
- Agent completion means reviewed diff plus test receipts, not a successful
  process exit. Record changed paths, exact SHA, tests/skips, limitations and next
  gate. Preserve unrelated work and old audit evidence.
- No direct agent push to main, deployment, phone action, model query against
  real patient data, credential change or clinical-admission bypass.
- Synthetic fixtures only. Preserve signing, subscription binding, request
  idempotency, hypoglycemia protection, Eating Soon ownership and uncertain
  command recovery. Never retry an ambiguous therapeutic/model request blindly.
- Coordinator integrates one stage at a time, runs the relevant combined suite,
  inspects exact-head CI, checks fresh main divergence, then performs an explicit
  reviewed merge. No auto-merge or force push. APK publication is a distinct gate.

## A1: Clinical Exchange

Own typed clinical adapters and their focused tests; use
`docs/AAPS4_COMPATIBILITY.md`, `LOCAL_NS_V3_TREATMENTS.md`,
`AAPS4_PAIRING.md` and `AAPS4_TARGET_JOURNAL.md` as contracts.
First stage: map a pinned native profile/treatment fixture into observational
records with units, provenance, revisions, deletion and coverage preserved.
Do not equate wire persistence with delivery or mark READY on partial data.
Acceptance: stock RC1 SDK fixtures, duplicate/edit/delete/restart/out-of-order,
wrong units/time/profile, unavailable collections and no therapy side effects.
Hand off a typed readiness contract to A2; do not enable production dispatch.

## A2: Target Safety And Device Compatibility

Own `tools/aaps4_compat/` and isolated target admission tests. No competing edits
to A1 adapters. First stage: guard the actual executor's target transaction
after queue/suspension delay, not only the receiver. Prove fresh expiry,
revocation, permissions and correlated ACK behavior on pinned RC1 source.
Keep AAPS changes minimal and preserve pristine source test coverage.
Then verify scoped Android CA trust and credential-bearing URL log redaction.
JugglucoNG acceptance requires an observed continuous glucose source before and
after migration, not stale preference names. No live target as a test fixture.
Physical migration is blocked until data/profile readiness, TLS, signer/package,
target recovery and the correct Redmi 23090RA98G are verified.

## A3: Timing, GI And Absorption

Own nutrition evidence/planner modules and synthetic/offline evaluation tests.
Use `MEAL_TIMING_EVIDENCE.md` and the meal-response integration specification.
Keep the repaired countdown anchored to recorded insulin across navigation and
process recreation. Insulin onset is not an ideal or safe meal waiting time.
First stage: confirmed bolus-to-meal episode linkage, profile versions, missing
data and confounding exclusions; evaluate later days separately from training.
Report 3/7/14-day coverage and calibration/error by horizon. Approximate meal
grams are not ground truth; CGM response is not laboratory GI. Minimum-data
fallback must expose uncertainty rather than invent personalized evidence.
No GI percentage directly changes a target. Prediction/control admission is a
separate safety review and validation stage, not a hidden setting to turn on.

## A4: History Coverage And Context

Own dedicated history projections/coverage tests on Android and server context
projection modules. Coordinate schema contracts first; coordinator wires owners.
First stage: versioned current and historical insulin/user profiles, schedules,
basal/targets, calibration, meal GI provenance and alert outcomes with explicit
omissions, revisions and deletion. Retain the full server archive.
Compact only model context: ten-minute source/quality bins with extrema/gaps;
older background may use thirty-minute bins, relevant episodes remain detailed.
Acceptance: two-device isolation, pagination/restart/backfill, category coverage,
missing versus zero, payload budgets and whole-request composition receipt.
Keep the current model and ChatGPT login. Do not label an approximate local
counter an exact tokenizer for an unsupported model.

## A5: Conversations And Attachments

Own server native-session adapter and registry modules/tests; no profile/auth
replacement. First stage: encrypted per-device conversation registry with
turn ownership, restart recovery, policy revision and context watermark.
Test token renewal, concurrent turns, account/device mismatch, stale/unknown
turn state, revocation and archive retention. Conversation identity is not a
claim that unlimited history fits the model's active context.
Second stage: explicit image/PDF capability contract, sandboxed decoding,
limits and attachment provenance. Reuse the contained image boundary where
appropriate; do not route general documents through food-only interpretation.
Retain text fallback and refuse unsupported/hostile files without leaking data.

## A6: Nightly Analysis

Own `backend/app/ai/daily_*` and dedicated recommendation archive/tests.
Start only after A4 defines versioned inputs. First stage: extend the closed
daily evidence schema beyond glucose without changing live therapy.
05:00 is device-local; test DST, reboot, offline catch-up, incomplete history,
one job per device/day, lease expiry, ambiguous output and duplicate delivery.
Retain yesterday's successful report while refreshing and mark its date/status.
Recommendations need cited episodes, uncertainty and contradictory evidence.
ISF/CR/DIA proposals remain advisory, never represented as applied settings or
automatically sent to AAPS. Food/activity/lifestyle advice uses actual history.
Verify both archived server result and the client report/advice surface.

## A7: Admin

Own isolated admin routes/templates or frontend and authorization tests.
First stage: read-only authenticated device/queue/report status using metadata
and bounded pagination. No public raw medical-data endpoint or embedded token.
Test unauthorized access, device isolation, session expiration, CSRF where
applicable, escaping of report text and audit redaction; inspect desktop/mobile.
No deployment without a target/config diff, rollback and separate verification.

## A8: Food Photo

Own photo-session lifecycle, decoder/result validation and their tests; no edits
to general chat/session registry without A5 contract review.
First stage: recovery after rotation/process death, upload timeout, queued/running
job ownership, invalid/empty answer and nutrition validation. Preserve request ID
and no resubmission on unknown outcome. Confirmation precedes food recording.
Acceptance: synthetic image server fixture and native review UI; real model/phone
quality assessment remains separately authorized and must not create therapy.

## A9: Alerts

Own alert acknowledgement/audio-selection tests and isolated delivery classes.
Verify Android notification ACK across Doze, lock screen, reboot and worsening;
global OFF remains full mute, an episode ACK is not indefinite global silence.
First stage is silent fake-clock/audio tests. Actual sound/vibration needs
explicit approval and must not alter independent AAPS or pump alerts.
For three variants per alert with three-day rotation, first define a stable
severity motif, persisted selection and fallback. Rotate only between episodes,
not mid-alarm. Generated ElevenLabs assets need audition, volume normalization,
license/privacy checks and hearing-accessibility review before bundling.
Never promise 100% OS delivery; expose permission/channel/foreground limitations.

## A10: Independent Use And Context Patterns

Own diary/evidence read models and their tests. First stage: publish manual
observations to history with exact provenance; keep recorded dose distinct from
confirmed delivery and separate from pump-controlled IOB/COB.
Standalone forecast/alerts require a capability matrix and their own validation.
Illness and atypical activity work begins with multi-day baseline comparison,
post-event outcomes and competing explanations. Do not automatically lower
targets or recalculate therapy from an unvalidated label or isolated ISF change.

## A11: UI, Export And Performance

Own focused native UI tests and measurement scripts. Do not rebuild approved
Focus+, owl navigation, target controls or updater from scratch.
JSON implementation is complete locally at 3f180d4b: 7/14/30/60/full, ten-minute
glucose and 42 clinical sections, readback verification and explicit exclusions.
Remaining acceptance: actual document provider, low storage, interruption and
large retained history on the target device. Never upload the resulting file
without an explicit destination request.
Measure CPU/RAM/battery under the same workload, interval, device and build;
retain before/after evidence. A smaller APK is not a runtime-memory improvement.

## A12: Unified Product

Use the existing public-product plan. Preserve AAPS main interface and add
Copilot tabs. Implementation depends on proven A1/A2 contracts, sensor licensing,
security/update ownership and public-user validation. No clinical release claim
from simply embedding three codebases or passing developer fixtures.

## Integration Queue

1. Merge main upload settings/schedule without losing current UI, photo, daily
   archive, timer and JSON. Remove the duplicate application-loop upload owner.
2. Full Android tests/lint/build including stock SDK interop; backend and update
   tooling checks; APK size/class/signature checks. Retain exact skip counts.
3. Push reviewed source, inspect exact-head Verify, resolve fresh divergence,
   update PR scope and explicitly integrate into main only after success.
4. Check the resulting main SHA/build. A source merge is not an internet APK
   publication or a phone installation. Version/signature/manifest/remote-byte
   checks and rollback precede a separate release publication.
5. Dispatch independent stages when native subagent tools are available. Keep
   A1/A2 and A4/A5/A6 dependencies explicit; coordinator alone accepts shared
   wiring and clinical-admission changes.
