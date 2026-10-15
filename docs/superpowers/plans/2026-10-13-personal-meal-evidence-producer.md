# Personal Meal Evidence Producer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Produce bounded, canonically bound completed research episodes and explicit missing-input results without clinical changes.

**Architecture:** Reuse the bounded transactional Lenta canonical meal source and the unchanged eligibility/fit thresholds. A pure batch producer verifies explicit historical provenance and exact canonical labels before invoking the existing adapter. An on-demand repository entry point supplies current stored labels and optional explicitly prepared evidence; it never invents unavailable legacy metadata.

**Tech Stack:** Kotlin, Room, JUnit/Truth, Robolectric, existing Gradle/CI.

## Task 1: Historical Timezone Boundary

Files: `android-app/app/src/main/kotlin/io/aaps/copilot/domain/nutrition/PersonalAbsorptionEvidence.kt`, existing `PersonalAbsorptionEstimatorTest.kt`.

- [x] Add a real failing regression to the existing adapter:

```kotlin
@Test fun invalidHistoricalTimezoneRejectsInsteadOfPreparingAnEpisode() {
    val result = PersonalAbsorptionEvidenceAdapter().prepare(
        input(1).copy(recordedZoneId = "missing-zone"), cutoff)
    assertThat(result.episode).isNull()
    assertThat(result.rejections).contains(PersonalAbsorptionRejection.INVALID_CONTEXT)
}
```

- [x] Run the existing nutrition test class and confirm the new assertion fails.
- [x] Validate `ZoneId.of(input.recordedZoneId)` before preparation, returning existing `INVALID_CONTEXT` on invalid zone. Empty/invalid strings must not fall back to the current zone. Keep model/sample thresholds unchanged.
- [x] Rerun the regression and existing estimator/cohort/hybrid tests.

## Task 2: Pure Canonically Bound Producer

Create `android-app/app/src/main/kotlin/io/aaps/copilot/domain/nutrition/PersonalAbsorptionEpisodeProducer.kt` and its same-package unit test.

- [x] Add contract tests before implementation using this public boundary:

```kotlin
data class PersonalAbsorptionEpisodeEvidence(
    val input: PersonalAbsorptionInput,
    val sensorSessionId: String?,
    val calibrationIdentity: String?,
    val contextReviewReference: String?,
    val preparedAtMs: Long
)
// Output exposes immutable episodes, per-meal failures, source status and
// clinicalInfluenceAllowed=false; it retains no raw CGM/insulin payload.
```

Use the existing synthetic estimator fixture geometry: known meal, five-minute original CGM availability, independently covered insulin intervals, historical ISF/CR context, explicit UTC, reviewed checks and no confounds. Bind the fixture meal to the canonical list; valid input must produce one episode.

- [x] Cover exact canonical meal equality (ID/revision/timestamp/grams/type/portion/provenance/availability), unbound evidence, conflicting versions, duplicate invariance, invalid/missing provenance, unknown/future preparation, unknown CGM availability, missing/invalid/gapped insulin, absent model context/review, invalid/mismatched historical zone, outside/unfinished windows, known overlapping canonical meals and all existing adapter confounds.
- [x] Implement `produce(cutoffMs, recordedZoneId, canonicalMeals, evidence, sourceStatus)` with complete-day21-day selection, exact label binding, bounded groups and adapter preparation. Engineering limits are5000 canonical labels,240 evidence bundles,20000 total glucose rows and60000 total insulin intervals. Any overflow makes the whole snapshot OVERFLOW with no episodes, not a truncated complete result.
- [x] Copy/freeze returned collections; order outcomes by timestamp/identity and do not retain raw input arrays. Missing evidence returns all required source gaps; no values or reviewed checks are fabricated. Known overlap cannot be waived by a supplied empty confounds set.
- [x] Verify input permutation/duplicates preserve output and mutating supplied collections after the call cannot mutate returned evidence. A returned research episode never enables therapy.

## Task 3: Room Read-Only Source

Modify `PersonalAbsorptionRepository.kt` and its existing Robolectric test.

- [x] Add real Room tests before wiring the entry point:

```kotlin
val before = db.therapyDao().since(0)
val result = repository().produce(ZoneOffset.UTC)
assertThat(result.episodes).isEmpty()
assertThat(result.outcomes).hasSize(5)
assertThat(db.therapyDao().since(0)).isEqualTo(before)
```

Additional Room cases: explicitly prepared valid episode accepted only for its canonical current revision; changed canonical grams/revision/tombstone or accepted suggestion cannot be ground truth; mismatched prepared history never overrides stored labels.

- [x] Add `suspend fun produce(zone: ZoneId, evidence: List<PersonalAbsorptionEpisodeEvidence> = emptyList())`, capturing the injected clock once and delegating canonical loading to `LentaMealHistoryRepository.load`. Reuse its5001 sentinel and400-ID batching in one read transaction. Reject evidence overflow before querying Room. Map unavailable/overflow history to no complete episodes.
- [x] Do not query unbounded legacy CGM, add a schema/worker, register into AppContainer/clinical callbacks, or write any clinical history/settings. Legacy records lack qualification metadata and report those gaps explicitly. The existing audit API stays compatible.
- [x] Verify actual Room tables remain unchanged and source failure is not reported as a valid empty trained model.

## Task 4: Verify, Review And Publish

- [x] Run focused producer, adapter/estimator, source, Lenta, hybrid and display-only GI regressions. Runtime-only local Gradle command:

```sh
rtk proxy caffeinate -i ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.nutrition.PersonalAbsorption*' --tests 'io.aaps.copilot.data.repository.PersonalAbsorptionRepositoryTest' --tests 'io.aaps.copilot.data.repository.LentaMealHistoryRepositoryTest' --tests 'io.aaps.copilot.domain.nutrition.HybridMealResponseEstimatorTest' --tests 'io.aaps.copilot.domain.predict.MealFoodDisplayEngineTest' --no-daemon --max-workers=1 --offline --console=plain -Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false' -Pkotlin.compiler.execution.strategy=in-process -Pandroid.aapt2FromMavenOverride=/Users/mac/Library/Android/sdk/build-tools/36.0.0/aapt2
```

- [x] Run full `:app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug` under unchanged project build settings. Keep actual results separate from incomplete/running commands. No therapy or phone actions.
- [x] Update architecture/invariants/plan/security review/AI_NOTES and this checklist with inspected terminal results. Review bounded behavior and public-data privacy; `rtk proxy git diff --check` must pass.
- [x] Fetch/check divergence; stage only explicit reviewed source/tests/docs; commit and push existing feature branch under standing project authorization. Verify exact SHA and Verify CI before claiming the source stage complete. Do not publish private database/log/APK/checkpoint artifacts or activate clinical influence.

Published source30f7c94b passed all local Android gates and exact-source Verify
runs38368380803 (push) and38368388884 (PR). Completion documentation changes no code.

## Scope Check

This implements the evidence-source boundary of the approved target integration spec only. Predictive validation, new prospective capture contracts, control-safety assessment and eventual controlled target activation remain independent gates. The producer can qualify explicitly prepared historical evidence but must accurately expose that current legacy Room rows alone are insufficient.
