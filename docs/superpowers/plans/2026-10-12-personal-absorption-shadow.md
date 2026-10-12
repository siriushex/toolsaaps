# Personal CGM Absorption Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline,
> task-by-task, preserving the already isolated source worktree.

**Goal:** Implement automatic timing estimation from independently qualified CGM
meal evidence, without clinical runtime influence or numeric laboratory-GI claims.

**Architecture:** A pure evidence adapter supplies immutable interval residuals
to a deterministic shape/duration evaluator. A separate read-only Room audit
exposes historical gaps; a private JVM replay tool makes the evaluator usable
without any production forecast, graph or command registration.

**Tech Stack:** Kotlin, existing MealAbsorptionCurve/MealPortionEvidence, Room,
JUnit/Truth/Robolectric; existing Gson/SQLite JDBC for optional private tooling.

---

## Task 1: Evidence And Timing Model

Files: create domain/nutrition/PersonalAbsorptionEvidence.kt and
PersonalAbsorptionEstimator.kt under android-app/app/src/main/kotlin/io/aaps/copilot;
create matching PersonalAbsorptionEstimatorTest.kt under app/src/test/kotlin.

- [ ] Write RED synthetic tests for learned CGM timing, absent context, each
  confound, provenance/revisions/tombstones, gaps, overlap, nonfinite inputs,
  causal availability, insufficient support, whole-day holdout, midnight matching,
  unchanged declared mass, ambiguous/boundary results and overflow.
- [ ] Run the missing-feature tests:
  `./gradlew :app:testDebugUnitTest --tests '*PersonalAbsorptionEstimatorTest' --console=plain --no-daemon`.
  Expected RED: new evidence/evaluator types do not yet exist.
- [ ] Implement immutable prepared episodes, reason-coded rejects and input caps.
  Use this exact cumulative definition for validated profile/p/duration:

```kotlin
fun cumulative(ageMinutes: Double): Double =
    MealAbsorptionCurve(profile, durationMinutes).absorbedFraction(ageMinutes).pow(exponent)
```

- [ ] Build corrected residuals using signed independently modeled insulin:

```kotlin
val corrected = glucoseEnd - glucoseStart + context.isfMmolPerUnit * insulinUnits
val foodResidual = corrected - frozenPreMealMedian
```

  Require context known by meal time, complete context review, covered insulin
  history, finite positive ISF/CR, >=90% grid coverage and no >10-minute CGM gap.
  Never substitute a missing exposure, glucose value or context flag.
- [ ] Evaluate the bounded grid with Huber0.3, equal meal weight and the spec's
  ambiguity envelope. Freeze chronological training before holdout; shrink
  p and duration toward the existing type prior by n/(n+8). Return versioned
  diagnostic status, digest, support, paired-day scores and no clinical authority.
- [ ] Run the focused tests GREEN; inspect every named exclusion and causal test.

## Task 2: Read-Only Audit And Usable Private Runner

Files: create data/repository/PersonalAbsorptionRepository.kt and matching Room
tests; create tools/PersonalAbsorptionReplayToolTest.kt in app/src/test/kotlin.

- [ ] Write RED Room tests for exact canonical override matching, stale/deleted
  identity, absent independent exposure context, overflow and no database writes.
- [ ] Read a bounded canonical timeline inside one Room transaction, map through
  existing toPortionObservation and report missing historical inputs. Do not
  guess timezone/action profile/availability from current settings or row time.
- [ ] Add an optional private JVM tool. Use a read-only SQLite URL and
  `PRAGMA query_only=ON` for audits; do not open the phone's live database.
  Prepared evidence JSON invokes the same tested pure evaluator. Report files
  require an explicit output path outside source; no private data in console.
- [ ] Run focused domain/Room/tool tests:
  `./gradlew :app:testDebugUnitTest --tests '*PersonalAbsorption*' --console=plain --no-daemon`.
  Expected GREEN. An unset optional private input is an explicit skipped test.

## Task 3: Verification And Source Publication

Files: update docs/ARCHITECTURE.md, docs/INVARIANTS.md, docs/PLAN.md, AI_NOTES.md
and this checklist; no UI, clinical engine, settings, schema or worker edits.

- [ ] Review the complete diff and source references; assert no production caller
  of the estimator/audit and no GI storage or command DTO change.
- [ ] Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --console=plain --no-daemon`.
  Expected exit0; record actual test counts/skips and lint warnings.
- [ ] Update documentation with real results and private-data limitations.
- [ ] Fetch origin and check divergence; stage only the reviewed paths after a
  secret/private-data scan. Commit and push existing feature branch without force.
- [ ] Confirm remote exact SHA and Verify CI. Keep the existing PR attached;
  report separately that phone installation/personal-data validation did not run.
