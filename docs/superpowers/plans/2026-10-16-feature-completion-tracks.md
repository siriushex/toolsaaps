# Feature Completion Tracks

Owner request: audit unfinished features, retain approved Focus variant 1,
complete reliable alerts and the daily advisory workflow. Native subagent tools
are unavailable in this session; these are independently assignable work tracks,
not claims that agents have been launched. Work remains in the current task.

## Ownership And Acceptance

| Track | Exclusive implementation scope | Confirmed state | Next deliverable and acceptance |
| --- | --- | --- | --- |
| A. Audio ownership | AlertAudioOwnership, GlucoseAlertAudioController and audio tests | Actual legacy/preview/local transport now shares bounded request identity; adversarial observer tests reproduced three further races and pass after the fix | Complete full Android quality and exact-head CI; preserve urgent floor, absolute deadlines, source admission and global OFF |
| B. Foreground alarm and acknowledgement | LocalAlarmCoordinator, runtime composition, alarm/receiver/service adapters, scoped acknowledgement UI | Existing policy, storage and accepted source feeds exist; production coordinator remains inactive | Single-owner INITIAL handoff before foreground/genuine AlarmClock scheduling; visible scoped acknowledgement, worsening cancels pause, OFF expiry reassesses current evidence; cancellation/reboot/permission tests and separately authorized locked-screen device acceptance |
| C. Focus presentation | AiFocusWorkspace, Focus UI tests and localized resources | Variant 1 is implemented natively, report first; chat composer, recommendations, archive and reduced motion already exist | Show the newest supplied successful saved report directly in Reports; keep failed runs visible but out of clinical advice; retain date/source in chat drafts; small-screen/native raster tests |
| D. Nightly review pipeline | Dedicated daily dispatcher/supervisor and protected daily routes; existing daily queue/worker/results reused | Glucose facts, 7/21-day comparison, source checks, queue, worker, encrypted results and atomic idle capacity exist; no new daily provider/schedule/read route is active | Compose one logical day per device/zone, yield to interactive work, reconcile uncertain stop without retry, preserve 365-day retention, then add opt-in civil-day schedule and authenticated read projection; synthetic lifecycle/isolation tests before host admission |
| E. Evidence and recommendations | Typed daily evidence adapters, deterministic candidate catalogue, closed review contract and evaluations | Current protected daily review is GLUCOSE_ONLY; meal, insulin, activity, settings and illness evidence remain missing | Add dated provenance and quality/coverage for each domain, then evidence-linked review topics for ISF/CR/DIA, meal timing/nutrition, activity and lifestyle. Missing data must stay missing; no causal diagnosis or automatic prescription/application |
| F. Signed Android integration | Signed daily result client/repository/UI-model mapping and intended-phone acceptance; no edits to Focus presentation | Owner text API and client source exist; phone enrollment and daily result bridge remain open | Reuse signed owner/device binding for bounded result list/detail, retain per-device cache and source-change labels, then verify enrollment/history/chat/daily readback on the intended phone. No credential or medical payload in repository |
| G. Food photography | Dedicated meal_vision, meal_vision_image and supervisor/decoder admission; existing native food editor preserved | Vision and decoder source plus separate client entry exist; text service activation does not activate vision | Actual independently contained image transport/model/stop/storage admission, malformed/large image and cancellation tests, then signed phone result. Nutrition remains a confirmable estimate, never automatic carbs/therapy |
| H. Private administration | New protected owner read projections and admin UI; one designated owner of owner_api integration | Approved synthetic prototype and information architecture exist, no public admin delivery | Device-isolated queue/report/readiness/error views, authentication/CSRF, redaction, retained historical status and actual browser acceptance; no arbitrary profile/model/therapy controls |

Track A and C may run independently. Track D must finish its lifecycle and
publication contract before F/H expose results to Android/admin. G is independent
of the daily service. Track E may
develop pure evidence adapters independently, but cannot widen an active prompt
or clinical policy without their validation. Only the integrator edits shared
ARCHITECTURE, INVARIANTS, PLAN and AI_NOTES sections; do not assign overlapping
source files to simultaneous workers.

## Work Packets

### B. One Runtime Owner

Read LocalAlarmCoordinator, LocalAlarmCycleExecutor, LocalAlarmSourceFeed and
AppContainer before editing composition. First test native INITIAL versus repeat
handoff, cancellation during Room work, unknown cleanup and source expiry. Do not
add a second independently sounding owner. Only then add the foreground/AlarmClock
adapter and exact acknowledgement generation/ordinal identity. Test permission
loss, process restart, boot change, clock changes, source resolution, stale
PendingIntent, worsening during pause and global OFF. A missing platform
capability must be visible, not reported as reliable delivery.

### D. One Closed Civil Day

Reuse daily_queue, DailyReviewWorker, daily_results and the atomic idle ledger;
do not create another model job/ledger or route daily JSON through CHAT. Select
the preceding completed civil day in the enrolled reporting zone, with DST
boundaries and late-upload/source-revision checks. Opt-in scheduling must not
enqueue missing-history days as normal, extend retention on retry or rerun an
uncertain handoff/model outcome. Test cancellation and crashes at queue claim,
process start/stop, ledger settlement and result commit. Install no cron/service
until contained provider and operational admission are independently checked.

### E. Useful Advice Needs Context

Build typed, dated coverage/provenance for meals, declared versus delivered
insulin, settings/ISF/CR/DIA, activity, sensor/calibration sessions and user illness
notes before expanding the glucose-only catalogue. Keep approximate grams,
unconfirmed delivery and effective algorithm parameters explicitly distinct.
Each review topic needs repeated comparable evidence, exclusions, an alternative
explanation and an allowed review action. ISF/CR/DIA topics are discussion of
evidence/settings, not instructions to adopt a model-generated number. Nutrition,
activity and lifestyle observations must use actual supplied records, not infer
behavior or diagnosis from a glucose shape. Evaluate missing/conflicting data,
prompt injection, unsupported causality, unsafe treatment and stale evidence.

### F-H. Deliver On Real Surfaces

Agree one bounded read DTO for result summaries/detail and its freshness/status
meaning before Android/admin work. Preserve owner/device isolation, revocation,
365-day retention and unknown-stop visibility. F owns the Android mapping; H owns
the protected server route integration, so no two workers edit owner_api together.
G remains a distinct contained image path. All three require real authorized
surface acceptance after synthetic tests; HTTP200/source build is insufficient.

Personalized GI/control influence remains its existing separate prospective
forecast and control-validation programme, not an AI recommendation shortcut.
Phone CPU/RAM/battery improvements require intended-device measurements before
and after a scoped change; no resource saving is claimed from this audit.

## Current Execution

- [x] Revalidate actual worktree and inspect unfinished source paths, not only prototypes.
- [x] Confirm Focus native implementation and the missing new daily-result bridge.
- [x] Reproduce and fix audio observer/cancellation/queued-stop ownership defects.
- [x] Finish Focus saved-report, source-aware discussion and diagnostic-copy
  guard; all24 native Focus tests pass and the320x480 saved-report raster was inspected.
- [x] Review all changed source, complete Android unit/lint/compile/assemble:
  5857 cases,5851 pass/6 existing skips,0 failures/errors; lint0 errors and unchanged
  379 notice keys. Debug v2 signature verified;18 reviewed text paths scanned.
- [ ] Publish only reviewed source/tests/docs and inspect exact-head Verify.
- [ ] Complete B, D, E, F, G and H as separately tested stages; no operational success
  or medical validation is implied by this delivery map.

## Boundaries

The existing legacy cloud daily scheduler is not the new protected per-device
review pipeline. Its history may be rendered as supplied, but cannot stand in
for a completed new daily model run or an admitted medical recommendation.
Report-viewing and discussing a finding never upload, submit or apply anything.

Numerical ISF/CR/DIA changes require adequate source evidence, confounder review
and independent clinical/control validation. UI and AI outputs cannot write
those parameters or bypass Eating Soon, low-glucose protections or AAPS limits.
No phone disabling, installation, real sound/volume test, host/service change,
new model request or therapy action is authorized by source publication.
