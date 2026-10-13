# Direct Base Target Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adjust every interval target directly with the existing0.1 step and explicit validated Save.

**Architecture:** Keep the existing local draft/editor policy. Move the single interval TargetStepperRow outside the time-edit conditional; Edit controls only start/end. All policy/clinical save gates remain unchanged.

**Tech Stack:** Kotlin, Compose Material3, JUnit/Robolectric native rendering.

---

## Task 1: Functional Native RED

**Create:** android-app/app/src/test/kotlin/io/aaps/copilot/ui/foundation/components/BaseTargetScheduleDialogRobolectricTest.kt.

- [x] Render the actual dialog with two synthetic valid intervals and existing min4/max10; assert collapsed `baseTargetIntervalTarget-night` is displayed, increment it without Edit, assert zero Save callbacks before explicit Save and then unchanged default/time/IDs with only that value changed.
- [x] Run `rtk proxy ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.ui.foundation.components.BaseTargetScheduleDialogRobolectricTest.collapsedIntervalCanChangeTargetWithoutEditingTime'`. Expect missing collapsed target node, not a malformed fixture.

## Task 2: Minimal UI GREEN

**Modify:** android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/components/BaseTargetScheduleDialog.kt; existing English/Russian strings.xml.

- [x] Remove the duplicated interval target text from its time/header row. Add48dp Edit/Delete controls with stable tags; time-edit description names its purpose.
- [x] Place the existing target row directly after that header and before conditional time controls:

```kotlin
TargetStepperRow(
    label = stringResource(R.string.base_target_schedule_target),
    targetMmol = interval.targetMmol,
    minTargetMmol = minTargetMmol,
    maxTargetMmol = maxTargetMmol,
    testTag = "baseTargetIntervalTarget-${interval.id}",
    onChange = { onChange(interval.startMinuteOfDay, interval.endMinuteOfDay, it) }
)
```

- [x] Remove the target row inside `if (editing)`, leaving exactly the two time controls. Preserve updateInterval, validation and Save/Cancel callbacks. Enlarge time-control touch bounds to48dp without altering15-minute behavior.
- [x] Add tests for independent targets/default, min/max disabling and clamps, Cancel with zero saves, time-only expansion, unchanged overnight times, invalid overlap and large-font native bounds. Keep existing editor/domain suites.
- [x] Run the native suite and BaseTargetScheduleEditorPolicyTest/BaseTargetScheduleTest/BaseTargetScheduleCodecTest/EffectiveBaseTargetResolverTest. Expect zero failures; inspect private native screenshots.

## Task 3: Combined Dashboard UI Verification

- [x] Verify IOB and schedule regressions together; update source docs/AI_NOTES with actual results.
- [x] Run the complete Android unit/lint/Kotlin/APK gate, retain signed private APK and inspect exact-source CI after scoped publication with the approved IOB stage.
- [x] Do not install on a phone, save real target settings or weaken automatic safety. Continue separately approved infusion work after this verified UI stage.

Focused gate inspected: 99 tests, 7 suites, zero failures/errors/skips. Classic
bounds check covers the changed IOB metric, not pre-existing hero delta overflow.
Full local Android gate passed: 5282 cases, zero failures/errors, 4 private opt-in
fixtures skipped; lint zero errors/fatal, 322 warnings. Package and v2 signature
verified. Exact-source Verify push38310293860 and PR38310300722 passed on
be9cc67bb9436ed5bbb218c9d4dc49693d6863ff. No phone actions.
