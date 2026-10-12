# Base Target Schedule: Direct Interval Controls

Status: user approved permanently visible target steppers for every interval.
The user approved this written specification for implementation and verification.
The IOB and infusion stages are independently approved.

## Layout And Interaction

Keep the existing default-target stepper, AUTO switch, validation and Save/Cancel.
For every interval, show the same minus/value/plus target stepper permanently,
without first tapping Edit. Step remains0.1 mmol/L with the same supplied minimum
and maximum. Disable the corresponding action at a bound; no new limit or dose
calculation is introduced.

Use two compact rows rather than squeezing six controls onto one narrow row:
time range plus time-edit/delete actions above, the target stepper below. Remove
the duplicated target number from the time header. Edit now expands only the
existing start/end time controls; do not render a second target stepper there.
All intervals can be adjusted independently, not only the currently expanded row.
An alternative permanently expanded time editor would be much taller and is not
the selected design. The user explicitly chose direct target controls.

Keep existing time increments, overnight semantics, ordering, interval IDs and
maximum interval count. Changing a target calls the existing updateInterval with
unchanged start/end. No reorder, accidental duration change or hidden event write.
Adding a row still selects its time editor; deleting it removes only that draft.

## Save And Safety

All +/- actions update the local draft only. The existing single explicit Save
callback receives the validated complete schedule. Cancel/dismiss do not persist
or send a goal. Preserve overlap, zero duration, bounds and invalid-schedule errors
and disabled Save, including errors in a different interval. No changes to the
base-target resolver, automatic circadian policy, Target Manager, Eating Soon,
hypo protection or actual therapy dispatch. Tests never save a real phone setting.

## Scope And Accessibility

Limit production edits to BaseTargetScheduleDialog.kt and localized time-edit
accessibility text. Reuse TargetStepperRow and BaseTargetScheduleEditorPolicy;
do not duplicate their arithmetic or refactor the domain model. Ensure control
targets at least48dp, readable values and reflow at320dp/large font. Keep errors
next to their interval, existing body scroll and reachable Save/Cancel.

## Verification

Add a native RED that changes a collapsed interval target directly before clicking
Edit, then confirms only that target changed on explicit Save. Cover two or more
intervals, independence from the default goal, unchanged time/IDs, both bounds,
Cancel with zero saves, overnight rows, time-only expansion, invalid overlap and
large font/light/dark render bounds. Existing domain/editor tests must retain
their results. Run focused native checks and full Android unit/lint/Kotlin/APK;
source/privacy review and exact-SHA CI precede calling this UI stage complete.
Phone installation and real goal changes remain separate actions.
