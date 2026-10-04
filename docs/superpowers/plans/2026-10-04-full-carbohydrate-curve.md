# Full Carbohydrate Curve Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the full modeled remaining food curve with user-approved bounded GI shape adjustment, explicit provenance and no change to clinical forecasts or therapy control.

**Architecture:** Capture display-only food data in the existing exclusive prediction cycle and publish only its accepted generation. Reuse the current profile/canonical reconciliation, preserve the current remainder and finish time, and isolate the GI redistribution from clinical announced-pressure steps. Extend chart domain and optional meal metadata; do not add an observer, polling loop or treatment writer.

**Tech Stack:** Kotlin, Compose, Room, Gson, JUnit, Robolectric, existing Gradle/Android tooling.

## Authority And Execution

- [x] Written shape spec approved by human response: "Да, ограниченная поправка формы только для графика" on2026-10-04.
- [x] Existing feature worktree selected; no new branch, worktree or task required.
- [ ] Execute inline in this task, with focused RED/GREEN evidence and task checkpoints.

Spec: `docs/superpowers/specs/2026-10-04-full-carbohydrate-curve-design.md`.
Authoritative repository: `/Users/mac/.config/superpowers/worktrees/AAPSPredictiveCopilot/server-ai-jobs-20260913`.
Writable build copy: `/Users/mac/Andoidaps/artifacts/meal-grid-check-D4FrDH/repository`.
Evidence: `/Users/mac/Andoidaps/artifacts/carb-curve-20261004` (private, never publish phone data).

All commands begin with `rtk`. Android commands run from the build copy's
`android-app` directory with Java17 and the existing SDK. Promote only reviewed
changed files after tests; do not overwrite unrelated primary-worktree changes.

## Task 1: Reproduce Display Truncation

**Modify/Test:**
- `android-app/app/src/test/kotlin/io/aaps/copilot/ui/MealImpactChartPointsTest.kt`
- `android-app/app/src/test/kotlin/io/aaps/copilot/ui/foundation/components/InteractiveClinicalForecastChartDomainTest.kt`

- [x] Add these existing-API regressions before implementation:

```kotlin
@Test fun fullVersionedFoodTailIsNotCutAtThirtyMinutes() {
    val steps = List(25) { if (it == 0) 0.0 else 0.1 }
    val json = """{"schemaVersion":2,"cycle":"cycle-1","predictionAtMs":1000,"complete":true,"giAdjustedMeals":0,"modelVersion":"food_gi_shape_v1","steps":${steps}}"""
    val points = mealImpactChartPoints(json, "cycle-1", 2000L, anchor)
    assertEquals(25, points.size)
    assertEquals(anchor.ts + 120 * 60_000L, points.last().ts)
    assertEquals(anchor.value + 2.4, points.last().value, 1e-9)
}

@Test fun domainIncludesFullFoodTailWithoutChangingCurrentGlucoseClock() {
    val state = ClinicalForecastChartUiState(
        historyPoints = listOf(ChartPointUi(10L, 5.1), ChartPointUi(20L, 5.3)),
        mealImpactPoints = listOf(ChartPointUi(20L, 5.3), ChartPointUi(100L, 8.0))
    )
    assertThat(clinicalChartDomain(state)).isEqualTo(ClinicalChartDomain(10L, 100L, 20L))
}
```

- [x] Run `rtk proxy ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.ui.MealImpactChartPointsTest' --tests 'io.aaps.copilot.ui.foundation.components.InteractiveClinicalForecastChartDomainTest' --console=plain`.
- [x] Inspect terminal failure and JUnit XML; expected failures are empty versioned curve and domain end20 instead of100. Do not fix unrelated failures by weakening these assertions.

## Task 2: Pure GI Metadata And Food Projection

**Create:**
- `android-app/app/src/main/kotlin/io/aaps/copilot/domain/profile/MealGlycemicIndex.kt`
- `android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/MealFoodDisplayProjection.kt`
- Focused same-name tests under `android-app/app/src/test/kotlin/io/aaps/copilot/domain`.

**Modify:** `domain/profile/MealAbsorptionProfileResolver.kt` (optional display metadata on selection only).

- [x] Write tests for unknown/invalid/catalog provenance, immutable revision-bound context, GI ordering, remainder conservation, partial absorption, nonmonotonic/oversized inputs, no-food and720m truncation. Verify the missing-feature RED before code.
- [x] Implement `MealGlycemicIndex(value, source, reference)` as validated serializable metadata. Value finite0..200; USER has explicit user provenance, CATALOG requires nonblank bounded reference; invalid metadata resolves to null. Input accepts decimal comma without inventing a value for blank.
- [x] Add immutable `MealGlycemicIndexContext`, keyed by canonical identity and expected revision. Its lookup accepts only trusted nonconflicting `MealTherapyReference`; manual/global profile defaults do not broadcast one GI to unrelated meals.
- [x] Implement bounded display-only input/result types. Per-meal computation is:

```kotlin
val c0 = cumulativeAtOffset(0)
val remaining = grams * (1.0 - c0)
val exponent = gi?.let { 1.0 + 0.25 * ((60.0 - it.value) / 60.0).coerceIn(-1.0, 1.0) } ?: 1.0
val fraction = cumulativeAtOffset(offsetMinutes)
val future = when {
    remaining == 0.0 -> 0.0
    gi == null || exponent == 1.0 -> grams * (fraction - c0)
    fraction == c0 -> 0.0
    fraction == 1.0 -> remaining
    else -> remaining * ((fraction - c0) / (1.0 - c0)).coerceIn(0.0, 1.0).pow(exponent)
}
```

Validate fractions finite/in[0,1]/nondecreasing before use. Difference successive
future values into nonnegative steps, multiply by the frozen accepted CSF and
sum independent meals. No retained closures/mutable lists in the result. Limit
5000 meals,145 points and1,000,000 meal-point operations; an invalid display input
returns unavailable, not a clinical exception. Include immutable step list,
prediction clock, modeled residual/completeness, adjusted-meal count and model
version `food_gi_shape_v1`. Trim to last required meal completion; verified no-
food retains the original30-minute flat duration. Nonzero residual at720m must
remain visibly incomplete. GI does not change clinical current remainder.

- [x] Run focused pure tests; inspect zero failures and conservation/ordering assertions. Run existing profile resolver/curve and announced-food tests to check unchanged baseline behavior.

## Task 3: Canonical GI Persistence And Manual Entry

**Modify:**
- `data/local/entity/MealProfileOverrideEntity.kt`
- `data/local/entity/PendingMealProfileIntentEntity.kt`
- `data/local/CopilotMigrations.kt`, `data/local/CopilotDatabase.kt`
- `data/repository/EnergyProfileRepository.kt`
- `ui/foundation/components/MealEntryConfirmation.kt`, `MealEntryDialog.kt`
- `ui/MainViewModel.kt`, `ui/foundation/CopilotFoundationRoot.kt`, `ui/foundation/screens/OverviewScreen.kt`
- `res/values/meal_portions.xml`, `res/values-ru/meal_portions.xml`
- Related repository, confirmation, Room and layout tests.

- [x] First add failing tests for GI surviving pending-to-canonical promotion/restart, exact revision rejection and migration30->31 keeping old rows with unknown GI. Optional invalid GI must not block otherwise valid real-carb confirmation.
- [x] Add nullable `glycemicIndexValue: Double?`, `glycemicIndexSource: String?`, `glycemicIndexReference: String?` with null defaults to both meal entities. Add migration31 and register it:

```kotlin
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("meal_profile_overrides", "pending_meal_profile_intents")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `glycemicIndexValue` REAL")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `glycemicIndexSource` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `glycemicIndexReference` TEXT")
        }
    }
}
```

- [x] Preserve metadata through existing `stageSelectionAfterSubmittedCarbAction`, `toSelectionOrNull`, `overrideEntity` and trusted revision resolution. Add `mealGlycemicIndexContext(therapy)` independently of enabling the energy profile; only exact canonical identities/revisions enter it. Do not attach pending data by timestamp or alter the real carb command params/idempotency key.
- [x] Append nullable GI to selection/confirmation and manual entry plumbing. Add optional numeric field and localized semantic labels. Invalid GI shows an input error and is omitted, while existing gram/energy validation controls clinical confirmation. No AI estimate or catalog network lookup.
- [x] Repair historical migration test seeds to remove the new nullable columns when restoring earlier schemas; update only current-schema expectations to31. Test old data survives and foreign-key/integrity checks pass.
- [x] Run focused repository/Room/input/layout tests plus manual submission and safety tests; inspect valid GI persistence and unchanged amount/profile/energy/provenance/actions.

Evidence: `artifacts/carb-curve-20261004/{initial-red,domain-red,domain-green,migration-red,metadata-red,gi-read-failure-red,metadata-green}.log`
in the local workspace. Domain GREEN:24 tests, no failures/errors/skips. Metadata
GREEN:63 tests in11 suites, no failures/errors, one phone-copy test skipped until
a fresh disposable database copy is available. Optional metadata read exception
was assertion RED before isolation; cancellation still propagates.

## Task 4: Accepted-Cycle Runtime Binding

**Modify:** `domain/predict/HybridPredictionEngine.kt`, `data/repository/AutomationRepository.kt`.
**Test:** engine food display integration and accepted decomposition telemetry tests.

- [x] Write failing tests: GI changes only display steps; real engine forecasts, announced steps, resolved pressure, UAM, insulin and current residual remain identical. Invalid/unknown GI exactly matches baseline prefix; untrusted/conflicting revisions cannot adjust. Display-budget rejection does not reject clinical publication.
- [x] Add a resettable immutable GI display context setter to the engine. Load it from the repository inside `buildForecastRuntimeContext` before the existing `predict`, under the existing exclusive cycle. Do not read UI settings later or add a job/observer.
- [x] Reuse `buildTherapyStepSeries`' relevant announced events and actual `carbCumulativeWithCutoff`. Capture each trusted event's GI and produce the separate display projection after clinical food steps; add optional projection to diagnostics and `ForecastDecompositionSnapshot`, not the accepted clinical pressure object.
- [x] Publication keeps `forecast_meal_steps` and existing subscription. Use schemaVersion2 with:

```kotlin
mapOf(
    "schemaVersion" to 2, "cycle" to acceptedCycleId,
    "predictionAtMs" to projection.predictionAtMs,
    "complete" to projection.completeByHorizon,
    "modelVersion" to projection.modelVersion,
    "giAdjustedMeals" to projection.giAdjustedMeals,
    "steps" to projection.stepsMmol
)
```

Publish only the accepted decomposition's result. Unavailable stays null. Bound
payload to16 KiB. No fallback that labels a60-minute truncation as complete.

- [x] Run engine/repository/sensitivity/target tests, including legacy UAM/therapy provenance and exact unchanged forecasts for several GI values. No phone therapy test.

## Task 5: Full Chart And Explicit Estimate State

**Modify:** `ui/foundation/screens/MealImpactChartPoints.kt`, `MainUiStateMappers.kt`, `ScreenModels.kt`, `OverviewScreen.kt`, `domain/predict/MealRollingImpact.kt`, `ui/foundation/components/InteractiveClinicalForecastChart.kt`, localized `meal_impact.xml`.

- [x] Extend parser tests for legacy payload, version2 endpoints/flags, mismatched cycle, invalid timestamps, nonfinite/negative steps,145-point/16KiB caps and overflow-safe anchoring. First verify unsupported version2 failures.
- [x] Decode one bounded payload into points/completeness/GI estimate status. Version2 supports2..145 five-minute steps; legacy13-step payload retains its original30-minute curve and unknown completeness. Require positive accepted cycle/generation/anchor and finite positive values. Existing tuple freshness remains authoritative in caller.
- [x] Add full cumulative display helper while retaining the original legacy helper. Build absolute food-only curve as `anchor.value + cumulativeDisplayStep`; no clinical forecast extension or inferred UAM.
- [x] Include finite positive food timestamps in chart domain, without deriving `nowTs` from future food or counting food as sufficient actual glucose history. Preserve follow-live/reset full-tail coverage and explore pan/zoom state.
- [x] Carry `mealImpactGiAdjusted` and nullable completeness into chart state. Add separate localized legend for GI-shaped estimate and incomplete tail; unknown GI is never implied as measured or applied. Preserve unavailable and food-only semantics.
- [x] Run both original RED tests and chart/viewport/mapper/forecast-screen/layout suites. Verify default view includes the tail and gestures remain bounded/nonoverlapping.

Runtime/chart GREEN evidence: parser10, chart-domain5 and telemetry3 cases
passed; the combined366-case run had2 new normal-font GI label failures only.
External full-width label fixed those, and all6 UI cases passed at actual
fontScale1.0/1.8 in both themes. First full4821-case run found3 diagnostic
value-equality failures and1 stale schema expectation. Two additional actual
RED tests confirmed GI-copy context loss and missing value equality; contracts
are fixed and full quality is being repeated. No test assertion was removed.

## Task 6: Review, Publication And Authorized Device Update

- [ ] Update architecture/invariants/plan/AI_NOTES for actual code and explicit estimate limits. Review numerical/authority/retention/migration changes; search for secrets and forbidden generated identifiers in new probes/fixtures/comments.
- [x] Run `rtk proxy ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin --console=plain` on the exact reviewed source. Count JUnit failures/errors/skips and inspect lint errors, not merely exit status.

Final debug quality:4823 tests/417 suites,0 failures/errors,3 existing skips;
lint0 errors/305 warnings/4 hints, all requested tasks successful. Instrumental
UI review found five old callback signatures; actual compile RED then minimal
fixture arity repair and isolated UI/Android-test APK assembly GREEN12s. No
instrumentation execution or test APK installation is claimed.
- [ ] Promote explicit tested paths only; inspect primary diff, fetch origin, check divergence and clean diff. Commit/push current feature branch under standing repository authorization; inspect exact SHA and both Verify jobs. Do not merge main.
- [ ] Obtain fresh coherent private backup with the existing reviewed working-phone helper; check DB integrity, settings/history and signing certificate. Update APK in place only after source/tests/CI are verified.
- [ ] Verify installed APK hash, migration31, unchanged settings/retained history, natural accepted forecasts and awake real Overview. Check full visible food tail, GI estimate legend when real confirmed GI exists, narrow layout, both themes/large font via safe UI tests. Do not insert fake carbs or GI into real therapy data merely for screenshots; missing real GI evidence stays an explicit device-test limit.
- [ ] Save private evidence/checkpoint, mark each criterion passed only with inspected results, and report code/CI/device status and remaining medical-model limits honestly.
