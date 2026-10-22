# No-Button Meal Timer Research Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. No native subagent tool is available in this task.

**Goal:** Implement a persistent no-button meal-message episode and an on-demand IOB/GI-aware scenario assessment without turning an assumed meal into consumed food or a clinical instruction.

**Architecture:** Keep the existing persistent insulin-onset timer and its notification owner unchanged. Add a private episode journal, then consume its immutable state in the existing frozen scenario simulator. A posted message records an assumption only; all six food hypotheses and explicit insulin worlds remain. GI sensitivity uses the existing research absorption shape, never the display-only GI correction. No new background loop or live message producer is admitted.

**Tech Stack:** Kotlin, coroutines, Preferences DataStore, JUnit, existing HybridPredictionEngine, Gradle and GitHub Verify.

## Task 1: Durable Message Episode

Files: `domain/meal/MealMessageEpisode.kt`, `data/repository/DataStoreMealMessageEpisodeStore.kt` and matching unit tests under `android-app/app/src/`.

- [x] Write failing tests for an episode linked explicitly to one meal revision and insulin record. Posting is idempotent; a conflicting/revised meal cannot inherit an assumption. No public confirmation transition exists.
- [x] Run `:app:testDebugUnitTest --tests '*MealMessageEpisode*'` and inspect the missing-symbol failure before implementing.
- [x] Implement immutable `MealMessageEpisode` and atomic DataStore journal. Preserve the original record and prompt time across reopening. Reject malformed state; never convert a posting attempt into observed ingestion. Use a separate private no-backup file, not a competing live timer owner.
- [x] Run the focused tests, including real file reopen, concurrent posting and corrupt-state rejection.

## Task 2: Conditional Research Assessment

Files: `domain/meal/MealScenarioSimulator.kt`, new `MealTimingResearchAssessment.kt`, matching tests.

Review addition: `domain/model/InsulinTimingRecordPolicy.kt` shares the existing onset exclusions with the research evaluator; `ui/InsulinOnsetCountdown.kt` delegates without changing its policy. Synthetic food and unconfirmed insulin reject before simulation instead of throwing deep inside the canonical projection.

- [x] First write tests asserting that GI changes only linked food timing, retains total mass/tails and leaves absent-food paths unchanged. Run and inspect missing API failure.
- [x] Add optional `PersonalAbsorptionShape` to the simulator. Require all eating scenarios to match its profile/duration and reuse `MealAbsorptionProjection.fromCumulative`; preserve memoization and CPU admission.
- [x] Implement on-demand assessment using `InsulinRuntimeSnapshotResolver.resolveTelemetry` at the frozen glucose anchor. Retain signed and positive IOB separately; greater than 2 U is salience, not meal proof. Do not subtract scalar IOB times ISF from glucose.
- [x] Bind known GI to the exact canonical meal revision and availability time. Reject missing, estimated, out-of-range or mismatched GI when GI sensitivity is requested. Require a declared research model; do not invent physiology from the display heuristic.
- [x] Keep full delayed/absent paths even after a recorded message. Report first conditional-mean threshold intervals on the five-minute grid, including an already-low state. Do not call these uncertainty bounds or a safe meal time.
- [x] Test missing/stale/future IOB, low IOB, missing GI, wrong revisions, retained message state, unrelated prior food, cancellation and future-insulin worlds. Notification/therapy authority always remains false.

## Task 3: Verification And Source Delivery

- [x] Update `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/PLAN.md` and `AI_NOTES.md` with implemented boundaries and remaining live-admission work.
- [x] Run full `:app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug` with the prepared pristine AAPS SDK interop classpath. Inspect JUnit totals and actual TLS execution, lint and APK size/retention guard. Final:6668passed/six existing skips, zero failures/errors; TLS2.111s, lint zero errors, all7132 app classes retained.
- [ ] Review explicit paths, fetch/check branch divergence, commit and push reviewed source/tests/docs. Create and attach a PR against main; inspect exact-head Verify on all three jobs.
- [ ] Record results in the task checkpoint and report remaining live-model admission honestly. Do not merge main, publish an APK, change AAPS, touch a phone/server or enable a meal recommendation in this stage.
