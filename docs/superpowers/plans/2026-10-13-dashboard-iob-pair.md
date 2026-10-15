# Dashboard IOB Pair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show current IOB / signed net IOB on the primary panel without changing calculation or therapy.

**Architecture:** A small pure formatter supplies two validated display strings and their pair. Both existing primary renderers use it, with a localized description and their unchanged Details routes. The accepted UI values, source/freshness mapping and controllers are untouched.

**Tech Stack:** Kotlin, Compose Material3, JUnit, Robolectric native graphics, Gradle.

---

## Task 1: Native Functional RED

**Modify:** android-app/app/src/test/kotlin/io/aaps/copilot/ui/foundation/screens/ClarityOverviewRobolectricTest.kt.

- [x] Add a native test using `ready().copy(currentIobUnits = 1.8, iobDetails = IobRuntimeDetailsUi(signedNetIobUnits = -0.4))`; assert the existing `clarityIob` node contains `1.8 / -0.4`, inspect text bounds, open/cancel original Details and assert zero action callbacks.
- [x] Run `rtk proxy ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.ui.foundation.screens.ClarityOverviewRobolectricTest.iobPairRetainsSignedTotalAndOriginalPassiveDetails'`. Expect a functional failure: primary node still contains only current IOB.

## Task 2: Shared Presentation And GREEN

**Create:** android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/OverviewIobPresentation.kt.
**Create:** android-app/app/src/test/kotlin/io/aaps/copilot/ui/foundation/screens/OverviewIobPresentationTest.kt.
**Modify:** ClarityOverview.kt, OverviewScreen.kt and existing values/values-ru strings.xml.

- [x] Add pure assertions for `(1.8, -0.4)`, positive/zero totals, missing sides and all nonfinite variants; expect unresolved new formatter before implementation.
- [x] Implement the formatter using existing formatting only:

```kotlin
internal data class OverviewIobPresentation(val current: String, val signedNet: String) {
    val value: String get() = "$current /\u00a0$signedNet"
}

internal fun overviewIobPresentation(current: Double?, signedNet: Double?) =
    OverviewIobPresentation(
        UiFormatters.formatUnits(current?.takeIf(Double::isFinite), 1),
        UiFormatters.formatUnits(signedNet?.takeIf(Double::isFinite), 1)
    )
```

- [x] Use `state.currentIobUnits` and `state.iobDetails.signedNetIobUnits` in both actual primary renderers; unit remains separate/once. Add localized description with current/net/unit arguments. Preserve all original click callbacks and source resolution.
- [x] Reserve two value lines on the paired Clarity metric without changing other metric formatting; render full text without ellipsis. Use shared atomic FlowRow items for current number and slash/net number, preventing digit splits. Keep signs and reserve two lines in both renderers.
- [x] Run both focused suites; expect zero failures. Native tests cover missing values, large signed values, stable metrics bounds, light/dark and large font; retain old ordinary no-scroll and passive menu regressions.

## Task 3: Verification And Publication

- [x] Inspect generated native screenshots and bounds. Evidence stays private outside the repository.
- [x] Update architecture/invariants/plan/AI_NOTES and spec status to implemented only after actual checks.
- [x] Run `rtk proxy ./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug`; inspect terminal success, XML counts and lint errors.
- [x] Verify the built APK package/signature/checksum, keep a private stage artifact; do not install.
- [x] Fetch origin, inspect divergence and exact privacy diff; explicitly stage reviewed source/test/docs paths, commit/push the existing branch and inspect Verify CI on that exact SHA. Existing PR4 stays attached; no merge.
- [x] Record checkpoint completion and move to separately approved infusion stage. Schedule written review is approved and shares this UI gate. Lenta Estimate remains pending.

Focused gate inspected: 99 tests, 7 suites, zero failures/errors/skips. Classic
bounds check covers the changed IOB metric, not pre-existing hero delta overflow.
Full local Android gate passed: 5282 cases, zero failures/errors, 4 private opt-in
fixtures skipped; lint zero errors/fatal, 322 warnings. Package and v2 signature
verified. Exact-source Verify push38310293860 and PR38310300722 passed on
be9cc67bb9436ed5bbb218c9d4dc49693d6863ff. No phone actions.
