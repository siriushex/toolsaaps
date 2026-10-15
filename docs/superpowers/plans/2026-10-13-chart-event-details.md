# Chart Event Details Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in this task. Track each step below.

**Goal:** Make the existing chart event popup informative without changing therapy.

**Architecture:** Project selected CompensationEvent values into a pure typed detail model. Render compact sections in the existing AlertDialog; preserve cluster identity and dismiss-only behavior. Preserve blood-check display values in the existing mapper without new queries or changing clinical keys.

**Tech Stack:** Kotlin, Compose Material3, JUnit, Robolectric native graphics.

## 1. Typed Details And Regression Tests

- [x] Add `ui/foundation/screens/ChartEventDetailsUiTest.kt` for recorded carbs, canonical precedence, invalid/conflicting aliases, blood-check status, point timing, context timing, UAM, all types and sanitized notes.
- [x] Add source-mapper regression proving blood values currently disappear; run it and inspect assertion RED before production edits.
- [x] Add `ChartEventDetailsUi.kt`: typed headings/status/facts, using existing therapy component semantics for recorded carbs. Missing/invalid values remain null. Unknown raw attributes are never displayed. Blood checks show measurement only, not the six-hour context envelope.
- [x] Enrich `EventTimelineRepository.calibrationEvents` with canonical blood/matched CGM values and optional matched timestamp; preserve clinical status/trustPenalty and test identical LocalEventImpactAnalyzer output.

## 2. Existing Popup Rendering

- [x] Create `ChartEventDetailsDialog.kt` with compact unframed sections, localized labels, full date/time, wrap-safe fact rows, bounded LazyColumn, Close/dismiss only. Add English/Russian resources.
- [x] Replace the three-label block in `ClinicalForecastChart.kt`; retain represented IDs and clear vanished selections with a keyed effect so they do not reopen later.
- [x] Add `ChartEventDetailsDialogRobolectricTest.kt`: real single/cluster marker tap, informative rows, update/remove/re-add, close/back, light/dark, 320px, landscape and large fonts. Capture synthetic screenshots privately and inspect text bounds.

## 3. Verification And Publication

- [x] Run focused domain/source/native UI tests; resolve all failures and inspect synthetic images.
- [x] Run full local `:app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug` with current local toolchain workarounds. No settings or test suppression changes.
- [x] Update architecture, invariants, security review, plan and AI_NOTES with actual results and limitations.
- [x] Review explicit source paths, privacy, upstream divergence; commit/push only verified stage and inspect exact-source Verify workflow on existing PR4. No phone installation or therapy commands.

The earlier personal-meal run completed all unit tests, then was deliberately
stopped during lint when the popup stage was ready. It is not a completed gate.
Run full local verification once on the combined final sources before publishing
either stage. Keep the source and popup changes distinguishable in the review.

Final combined gate: BUILD SUCCESSFUL in24m5s;5384 tests passed,4 existing
private-data tests skipped, zero failures/errors. Lint: zero errors,326 warnings
and7 hints. Debug APK v2 signature verified; no installation performed.
Published source30f7c94b passed push Verify38368380803 and PR Verify38368388884.
These completion notes do not change the verified Android/backend source.
