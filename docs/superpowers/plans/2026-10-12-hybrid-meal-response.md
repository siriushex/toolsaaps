# Hybrid Meal Response Implementation Plan

> For agentic workers: use superpowers:executing-plans with test-driven-development.

**Goal:** Return an honest complete advisory preview even with zero history, and
descriptive reaction statistics starting from one independently qualified meal.

**Architecture:** Reuse type defaults, quantity priors and personal timing
evaluation. Share cohort preparation rather than repeat eligibility. Sparse
observations cannot enable clinical effects or relax held-out timing validation.

**Tech Stack:** Kotlin/JVM, existing domain models, JUnit/Truth, Gradle.

## Task 1: Regression Contract

Create `android-app/app/src/test/kotlin/io/aaps/copilot/domain/nutrition/HybridMealResponseEstimatorTest.kt`.

- [x] Add synthetic zero/one/few/qualified history assertions before production code.
- [x] Observe the missing-feature RED for the new estimator API.
- [x] Require defaults with no/incomplete/invalid anthropometry, confirmed grams
  unchanged, signed median reaction, no fabricated spread for one sample, no GI
  or therapy qualification and full monotone mass-conserving curve endpoints.

Representative executable contract:
```kotlin
val result = HybridMealResponseEstimator().preview(request, emptyList())
assertThat(result.curveSource).isEqualTo(HybridMealCurveSource.TYPE_DEFAULT)
assertThat(result.curve.last().modeledRemainingGrams).isEqualTo(0.0)
assertThat(result.clinicalInfluenceAllowed).isFalse()
assertThat(result.laboratoryGi).isNull()
```

Run from `android-app`:
```sh
rtk proxy env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.nutrition.HybridMealResponseEstimatorTest' --console=plain --no-daemon
```
Expected first outcome: unresolved new hybrid API, not a fixture/runtime fault.

## Task 2: Shared Cohort

Create `android-app/app/src/main/kotlin/io/aaps/copilot/domain/nutrition/PersonalAbsorptionCohort.kt`.
Modify only cohort consumption in `PersonalAbsorptionEstimator.kt`.

- [x] Move existing cohort selection unchanged into `preparePersonalAbsorptionCohort`.
- [x] Preserve public `evaluate(request, inputs)` via an internal prepared-cohort
  evaluator, so hybrid preparation runs once.
- [x] Run original personal timing regressions alongside new hybrid tests.

Contract:
```kotlin
fun evaluate(request: PersonalAbsorptionRequest, inputs: List<PersonalAbsorptionInput>) =
    evaluatePrepared(request, preparePersonalAbsorptionCohort(request, inputs))
```
Selection retains21 completed local days, circular +/-120 minutes, canonical
conflict/overlap/availability checks and240-input overflow handling. No support
threshold or timing coefficient changes.

## Task 3: Always-Available Advisory Result

Create `android-app/app/src/main/kotlin/io/aaps/copilot/domain/nutrition/HybridMealResponseEstimator.kt`.

- [x] Validate cutoff/time zone/portion settings and optional1..500 declared grams.
- [x] Use selected type or explicit MIXED fallback and current default duration.
- [x] Reuse `describePrior` with its14-day quantity eligibility; overflow falls
  back to presets without partial sampling. Preserve entered grams separately.
- [x] Summarize normalized signed per-meal residuals by five-minute age using
  median/IQR and support counts. Exclude whole nonfinite normalization episodes.
  Missing points are not zero; singleton quartiles remain absent.
- [x] Use personal shape only for existing EVALUATED status and explicit type.
  Preserve all other diagnostic states/default shape; no extra GI shaping.
- [x] Generate immutable5-minute preview points through modeled completion.
- [x] Verify phenotype fields report context only, never alter numeric shape or
  empirical support. All clinical/mass qualification getters stay false.

Normalization and preview:
```kotlin
val normalizedDelta = step.residualMmol / (episode.meal.grams * episode.csfMmolPerGram)
val fraction = shape.cumulative(ageMinutes.toDouble())
val absorbed = declaredOrSuggestedGrams * fraction
val remaining = declaredOrSuggestedGrams - absorbed
```
The normalized quantity is conditional apparent response, not measured absorbed
grams or GI. Negative statistics are retained; only the separate bounded model
curve has monotone cumulative fractions.

## Task 4: Edges, Quality And Publication

- [x] Exercise conflicts/tombstones, future/late/unfinished days, gaps, unknown
  context/insulin, midnight and zone/type/portion mismatch, overflow, immutable
  outputs, baseline/no-gain/ambiguous/bounded fit and degenerate normalization.
- [x] Verify existing quantity/insulin/GI/curve tests without changing assertions.
- [x] Update architecture, invariants, plan, approved design and AI_NOTES by fact.
- [x] Run full Android unit/lint/Kotlin/APK. Inspect exact terminal results, XML
  counts, lint severity and APK signature/hash. Backend source remains unchanged;
  its exact-source CI regression is part of the publication gate below.
- [ ] Scan/review explicit source paths, fetch/check divergence, commit/push the
  existing feature branch and verify exact-SHA push/PR CI. Keep private logs/data
  outside publication. No phone installation or live model registration.

Full command from `android-app`:
```sh
rtk proxy env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --console=plain --no-daemon
```
