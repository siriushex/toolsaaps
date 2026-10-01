# Compact Meal Portions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Do not claim independent agents if unavailable.

**Goal:** Implement selected variant 2 with portion/profile image choices, hidden-by-default calories and exact confirmed gram submission.

**Architecture:** Keep UI draft selection separate from the manual therapy writer. Introduce bounded portion settings before optional history suggestions; keep synthetic UAM out of training labels and its limits unchanged. Reuse the existing absorption profile and submission/reconciliation paths.

**Tech Stack:** Kotlin, Compose, existing AppSettingsStore, local raster assets, existing unit and Android UI tests.

## Revised interaction, 2026-09-27

User explicitly replaced secondary confirmation with direct Send from Food.
Manual carbs is unchecked by default beside Eating soon; it reveals inline grams.
Send displays the exact quantity and invokes the existing immutable/idempotent
manual-meal path once. Unchecked Eating soon sends carbs only. Existing caps,
unknown-delivery reconciliation and target preflight remain unchanged. This
supersedes secondary-window instructions below, retained as implementation history.

## 1. Portion configuration

Files: `android-app/app/src/main/kotlin/io/aaps/copilot/config/AppSettingsStore.kt`; new `domain/nutrition/MealPortion.kt`; `ui/foundation/screens/SettingsScreen.kt`; `config/AppSettingsStoreTest.kt`.

- [x] Add tests for finite ordered boundaries, invalid ranges, persisted defaults and calorie visibility default false. Validate configured default inside its own interval. Shared 15/40 boundaries classify old data into the higher category. New record category provenance remains part of later submission integration.
- [x] Add a typed SMALL/MEDIUM/LARGE model and persist range plus default per category using existing settings conventions. Keep display ranges 7-15/15-40/40-80 separate from the currently allowed submission cap.
- [x] Preserve previous settings on invalid save and expose the error alongside the fields. Never overwrite user configuration merely because a model learned a different value.
- [x] Run `rtk ./gradlew :app:testDebugUnitTest --tests '*AppSettingsStoreTest'` from `android-app`, then inspect exact scoped diff.

Stage 1 evidence (2026-09-27): typed presets 10/25/60g; persistent calorie
visibility flag defaults false. Settings editor is wired to the settings store.
Focused58 and full4438 unit tests: no failures/errors, full suite skips3.
Lint has no errors (296 warnings, 4 hints); debug and isolated UI APKs build.
Initial RED was compilation failure for the absent new API, not an assertion-level
regression run. Instrumented interaction test compiles but has not run:
phone rejected isolated target installation with INSTALL_FAILED_USER_RESTRICTED.
Production APK was NOT updated. Existing meal dialog does not consume these
settings yet; calorie hiding and picture selection are stages 2/3.

## 2. Six icons and compact draft dialog

Files: new `ui/foundation/components/MealEntryDialog.kt`; `ui/foundation/screens/OverviewScreen.kt`; `ui/foundation/screens/FoodProfileSelector.kt`; new local drawable assets; values/values-ru strings; new `src/androidTest/kotlin/io/aaps/copilot/ui/foundation/screens/MealEntryDialogTest.kt`.

- [x] Generate the selected flat icon family from the middle column: three porridge portion sizes, large with two bread slices; fast/mixed/fat-protein food. Do not crop text and selection borders into production icons.
- [x] Add UI tests first: exactly one selected option per row, radio semantics, labels, hidden calories, cancelling and picture taps never call the send callback. Tests compile; device execution remains pending.
- [x] Extract only the existing meal dialog presentation from Overview into the new component. Leave source selection, calibration, target calculations and other Overview sections untouched.
- [x] Reuse FoodProfileSelector profile mapping with an explicit compact presentation that hides durations only here; preserve other call sites.
- [x] Show proposed grams in the confirmation action. Retain numeric correction in the secondary confirmation rather than permanently displaying a field. Show precise submitted grams and Eating soon action there.
- [x] Verify light/dark, 48/64dp icons and enlarged font locally; use opaque color pairs, not alpha-over-unknown-background text colors. Actual1.8x Russian Robolectric rendering passes; phone acceptance remains separate below.

Stage 2 local evidence (2026-09-27): six transparent 192px assets visually
checked after deterministic cropping/centering. Draft reads saved defaults and
showCalories. Confirmation freezes values and validates exact corrections without
clamping; the existing manual cap remains at most 60g. Full4440 unit tests,
failures0/errors0/skipped3; lint errors0/warnings301/hints5. Debug and isolated
UI APKs built. Initial full run exposed an outdated source-location assertion in
EatingSoonWiringTest; it now checks the extracted component and forwarding path.
Device interactions, light/dark rendering and font scaling are NOT verified;
isolated installation previously rejected by the phone, permission requested.
No production deployment, therapy test write or history learner activation.

## 3. Immutable confirmation and manual cap

Files: `ui/MainViewModel.kt`; `data/repository/ManualMealSubmission.kt`; `data/repository/ManualMealSubmissionTest.kt`; `ui/MainViewModelManualMealEnergyTest.kt`.

- [x] Add regressions for frozen quantity during live settings/history changes, cancellation, double taps, failure/retry and independent carb/target outcomes. Retain submission ID across ambiguous retries.
- [x] Trace all manual cap checks before supporting the requested 80g. Separate the manual limit from automated UAM limits; test that an 80g manual draft cannot silently become 60g or multiple sends.
- [x] Preserve existing reconciliation and per-meal absorption metadata. Hidden calories must submit null; a hidden stale text field must not submit an old energy value.
- [x] Run `rtk ./gradlew :app:testDebugUnitTest --tests '*ManualMealSubmissionTest' --tests '*MainViewModelManualMealEnergyTest'`.

## 4. History suggestion, independent validation stage

New proposed files: `domain/nutrition/MealPortionEstimator.kt`, corresponding unit tests and `data/repository/MealPortionSuggestionRepository.kt`. Reuse canonical therapy data and existing meal-profile overrides; do not add a competing AAPS import path.

- [x] Define provenance for user-entered/corrected amounts versus accepted suggestions. Exclude synthetic UAM, cancelled/deleted records, duplicates, rescue carbs and preparatory fictitious carbs from ordinary-meal labels.
- [x] Add deterministic tests with an injected clock: fewer than five usable days returns configured fallback; insufficient comparable meals also returns fallback; future records and future confirmations are excluded; 15/40 boundaries are stable; no timezone/DST duplicate meal; previous predictions cannot train themselves.
- [x] Estimate a bounded weighted median of comparable confirmed meals near the local time, with fallback toward the configured default when support is weak. Five-day availability is necessary, not sufficient. A candidate support rule is five independent comparable meals across three distinct days, pending retrospective evaluation.
- [x] Cache by therapy/settings revision and local-time bucket. Recompute on an explicit offline candidate request, not on Compose recomposition or periodic polling. No UI activation without the next validation gate.
- [ ] Validate on temporally held-out independently confirmed meals against fixed presets; report absolute error and overestimation by category/time, not glucose smoothness as proof of carb accuracy. Keep the learner inactive if it fails the baseline or has inadequate labels.

## 5. Verification and delivery

Stage 4 prototype evidence (2026-09-27): added pure MealPortionEstimator with
injected Clock, canonical identity conflict rejection, explicit provenance,
14-day window, local-time matching and bounded recency-weighted median blended
with the configured default. Nine estimator tests and three settings-domain
tests pass. Initial RED was missing-API compilation, not assertion-level RED.
Prototype is NOT wired to UI, repositories or therapy. No validation against
held-out real meals yet. Provenance persistence and canonical adapter remain
required before activation; heuristic support/blending rules are unvalidated.

Canonical boundary follow-up: MealPortionEvidence and toPortionObservation reuse
the existing trusted therapy reference and component resolver. Exact canonical
identity, revision and confirmed grams are required; missing evidence, synthetic,
invalid, superseded and non-independent origins are rejected. Four new boundary
tests pass alongside the previous twelve portion tests. This is a pure adapter,
not persistence: Room remains v26; pending/profile entities do not yet carry
portion provenance. No historical records have been retroactively labelled and
no learner has been activated. Initial RED was missing-API compilation.

Persistence follow-up (2026-09-27): Room v27 adds nullable portion/provenance
to pending intents and canonical overrides, plus confirmed grams to overrides.
Migration 26->27 is registered, additive and preserves unknown legacy origins.
EnergyProfileRepository accepts optional typed provenance and promotes it with
the existing canonical reconciliation transaction. Migration and repository
focused tests pass; UI has not yet supplied these arguments. Full4457 run had
one local-server startup failure (returned null port), three skips; that test
passed in isolation. An earlier version-pinned alert test was updated from26
to27. APK builds. This is not a clean full-suite claim. Not installed on phone;
real database-copy migration and UI provenance wiring remain required.

- [x] Run `rtk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` from `android-app`; record actual pass/fail counts. Final totals and APK verification are recorded in AI_NOTES.md.
- [ ] Run interaction tests on an emulator or isolated test build. Do not send test carbs, targets or calibration records to the real therapy app.
- [ ] Check APK signature, update only in place when deployment is requested, and verify the rendered dialog on the phone without confirming a test meal.
- [ ] Save screenshots, check initial/selected/error/loading states, and verify no new long-running service, worker or periodic timer was introduced.
- [x] Update AI_NOTES and PLAN with actual stage completion, not planned behavior. Preserve existing unrelated backend/performance changes and stage only reviewed feature files.

## Status

### Current Code Follow-Up, 2026-10-01

Stage3 code is implemented: exact1..80g manual limit, finite-value checks,
unmodified automatic/UAM caps, canonical real-food model/COB handling and exact
localized Send/receipt quantities. Inputs and local provenance are frozen before
transport and compared with persisted commands across restart. A changed ID input
is blocked rather than falsely acknowledged; existing unknown-delivery guards stay.
Focused154 manual/model/UI-format tests passed without failures/errors/skips.

Stage2 local rendering now covers real font scales1.0 and1.8 in Russian,
light/dark, whole-word captions, scroll reachability, hidden calories, over-cap
error and exact80g callback-once behavior. Stable64dp images use horizontal rows
only at enlarged scale. Screenshots are local native Robolectric draws, not phone
acceptance. Six UI cases pass; no test callback writes therapy.

Stage4 infrastructure is implemented but inactive: existing canonical timeline
reader, atomic matching independent evidence, conflict/tombstone exclusion,
confirmation availability, revision-aware bounded cache and temporally held-out
MAE/positive-overestimation comparison by portion/time. Historical unknown records
are not relabelled. The final full run includes39 feature tests with no failures.
API RED for the absent reader/evaluator and assertion RED for future-confirmation,
missing-availability, learning-suppression and pre-cache-conflict guards observed.
No sufficient independent real labels are available in the existing consistent
September30 snapshot, so real accuracy scores and activation remain unavailable.

Full current checks and publication evidence are recorded in AI_NOTES.md.
The phone reconnected and the user approved isolated target/test APK updates only.
Their isolation test passed1 case. Keyguard/NotificationShade prevented UI focus;
the UI run was stopped and device/font acceptance remains pending user unlock.
The working app is not updated, no test meal/target/calibration is sent, and no
worker/timer/active learner is introduced.
The older status below is preserved as historical evidence, not current completion.

USB follow-up: new confirmation/provenance assertions now executed successfully
on isolated device target. Final6 tests pass including isolation and two theme
tests. Normal-scale light/dark screenshots inspected. Font-scale override was
ineffective and its identical images are rejected evidence; enlarged-font
acceptance remains open. Working APK and therapy settings were not changed.

Latest verification (2026-09-27): immutable provenance wiring is implemented;
20 focused tests passed in the prior run. Current follow-up adds explicit UI
assertions for accepted versus edited grams and upgrades the disposable-copy
migration test from v26 to v27. All 41 migration tests passed, including an
813.5 MB archived phone snapshot from September 1 (not a fresh USB snapshot).
Counts, integrity and foreign keys are preserved. A separate native-SQLite Room
test restores affected tables to v26 and verifies Room's v27 schema validation.
No new phone installation or learner activation. Full regression checks remain
separate from these focused results.

Stages 1 and 2 code implemented. Working app updated in place on 2026-09-27;
production dark-theme meal dialog inspected and cancelled without a therapy write.
All 33 isolated UI tests passed after correcting test state setup, localized
fallback text, source-aware labels and exact editable-value assertions.
Light/large-font coverage remains open. Stage 3 is in progress: confirmation
serialization no longer rounds grams to one decimal; an unknown delivery does
not claim its profile was saved. Focused tests and full unit/APK build passed;
these latest fixes are not yet installed on the phone.
Stages 3-5 are not complete. No active history learner and no therapy setting
changes. Evidence: /Users/mac/Andoidaps/artifacts/meal-portions-stage2-20260927/.
