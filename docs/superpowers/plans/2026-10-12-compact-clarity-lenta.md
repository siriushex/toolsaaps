# Compact Clarity And Lenta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for inline execution in the already isolated feature worktree. Track steps below.

**Goal:** Implement the approved compact native interface and produce a verified APK.
**Architecture:** Reuse existing accepted Overview state and UI callbacks. Change only presentation and navigation, with a separate full food dialog; no inference or therapy-policy mutation.
**Tech Stack:** Kotlin, Jetpack Compose, Robolectric/JUnit, existing Gradle Android pipeline.

## Task 1: Native Route And Form Regressions

Files: app/src/test/kotlin/io/aaps/copilot/ui/foundation/screens/ClarityOverviewRobolectricTest.kt.

- [x] Replace the superseded COB-editor regression with two passive routes:
```kotlin
compose.onNodeWithTag("clarityCob").performClick()
compose.onNodeWithTag("clarityFoodDetails").assertIsDisplayed()
compose.onNodeWithTag("foodCurveEnd").assertDoesNotExist()
compose.onNodeWithTag("foodCurveFit").assertDoesNotExist()
compose.onNodeWithTag("clarityFoodAddMeal").performClick()
compose.onNodeWithTag("mealManualCarbs").performScrollTo().assertIsDisplayed()
compose.onNodeWithTag("mealGlycemicIndex").assertDoesNotExist()
assertEquals(0, changes)
```
- [x] Update full-food regression to read default foodFullCurve StateDescription
  before touching the chart and require first/last accepted timestamps.
- [x] Add compact-root-action native tests: one disabled refresh, one blood callback,
  no More action. Add current-source error reachability and normal compact fit.
- [x] Run filtered native suite with JDK17 and preserve actual RED reports.

## Task 2: Upper Actions And Unique Routes

Modify: ui/foundation/CopilotFoundationRoot.kt, screens/OverviewScreen.kt.
Create: ui/foundation/ClarityOverviewActions.kt and its native test.

- [x] Implement the actual top actions using existing tooltip button:
```kotlin
@Composable
internal fun ClarityOverviewActions(
    canRunCycle: Boolean, onRunCycle: () -> Unit, onBloodCheck: () -> Unit
) {
    Row {
        ClarityToolButton(Icons.Default.Refresh,
            stringResource(R.string.balance_recalculate), "overviewTopRefresh",
            enabled = canRunCycle, onClick = onRunCycle)
        ClarityToolButton(Icons.Default.Bloodtype,
            stringResource(R.string.overview_calibration_action), "overviewTopBloodCheck",
            onClick = onBloodCheck)
    }
}
```
  The implementation uses Refresh/Bloodtype, existing localized labels and
  explicit overviewTopRefresh/overviewTopBloodCheck test tags.
  Add an enabled overload to the existing tooltip helper, preserving its
  current four-argument/trailing-lambda overload for other callers; delegate
  to the same IconButton(enabled=enabled) implementation.
- [x] Wire Overview+Clarity route to viewModel::runCycleNow and the existing
  overviewAuthoritativeEditor BLOOD_CHECK consumed request. Other styles keep
  their existing screen actions.
- [x] Remove MoreVert/duplicated Destinations.more dropdown from root TopBar.
  Move power-save entry into the existing drawer, retaining its exact dialog.
- [x] Extract the existing sensitivity applying/error rendering into one reused
  UI function; keep help, test tag and source identity behavior unchanged.

## Task 3: Compact Main And Full Food View

Modify: screens/ClarityOverview.kt, ClarityGlucoseChart.kt, ClarityFoodCurve.kt,
components/MealEntryDialog.kt, localized strings.
Create: screens/ClarityFoodTimeline.kt and focused pure tests.

- [x] Main COB callback becomes read-only food dialog:
```kotlin
ClarityMetrics(state, onIob, { foodDetails = true }, onIsf, onCr, onUam, onBaseTarget)
```
  Remove duplicate food-open row and lower clinical toolbar. Glucose summary
  opens existing compensation report; preserve source error and profile warnings.
- [x] Use inline value/unit AnnotatedString with natural word wrapping and >=48dp
  metric hit area. Budget compact chart height from available content height;
  keep a readable minimum and existing LazyColumn fallback.
- [x] Keep range controls in expanded chart, one main expansion action, original
  current forecast markers and exact-path checks. Never change marker data.
- [x] Implement full-width food Dialog with close/header, aggregate COB/status/
  estimated end, existing food-only chart and bounded real-meal timeline.
  Its footer closes before calling original food/Eating Soon dialogs.
- [x] Remove food start/fit/end buttons; existing food-only chart domain already
  starts at its accepted origin and defaults to its full future end.
- [x] Timeline filters MEAL with USER/AAPS sources, positive finite declared carb
  attributes, <=accepted origin in the last12h, deterministic identity dedupe,
  sorted rows within the existing bounded event state. Exclude automatic/UAM/
  future events. No residual estimates or new history query.
- [x] Remove glycemicIndexRaw state/input from MealEntryDialog, leaving default
  optional GI empty through the existing confirmedMealFromInput helper.
  Preserve all other settings, defaults and validation.

## Task 4: Verification, Documentation And Publication

- [x] Run targeted native/domain/navigation/meal tests; inspect synthetic images
  and fix genuine layout issues with a preceding regression.
- [x] Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug`.
  Inspect JUnit counts/skips and lint diagnostics; do not claim warnings absent.
- [x] Verify APK signer/hash and copy only final binary to a private artifact path.
- [x] Update ARCHITECTURE/INVARIANTS/PLAN and AI_NOTES for actual scope/results.
  Inspect explicit diff and unchanged clinical modules.
- [ ] Fetch/check upstream divergence, stage only reviewed UI/tests/docs, commit
  and push existing branch. Verify remote SHA and exact-source Verify workflow;
  repair failed CI before stage completion. Never publish private artifacts.
- [ ] Return ready APK path with checks and any real device limitation.
  Phone installation is not authorized by this UI/build approval.
