# Lenta: Separate Meal Absorption Estimate

Status: Lenta placement selected by the user; this written UI specification
awaits review before implementation. The implemented hybrid domain estimator
remains advisory. No phone installation or therapy changes are authorized here.

## Decision And Scope

Add a separate Estimate mode to the existing full carbohydrate Lenta dialog.
The Food form is not modified. Its portion/type choices, optional manual grams
and calories, Eating Soon checkbox, confirmation and submission identity retain
their existing behavior.

Alternatives considered were a compact preview inside the Food form and a new
analytics screen. The user selected Lenta: it keeps inspection in one place
without adding fields or changing the established food-entry workflow. Separating
modes is essential because the existing chart uses mmol/L and the new preview
uses modeled grams.

This is a passive connection of the already implemented default hybrid preview.
Qualified personal-history capture is a separate stage. Do not register the
current read-only history audit as a trained-model source.

## Screen And Interaction

Keep the current fullscreen dialog header, Close and Info controls. Add one
two-part Material segmented control below the header: Current and Estimate,
localized in English and Russian. Current is selected each time the dialog is
newly opened. Rotation restores the current dialog's selected mode and choices;
closing and reopening starts a fresh session, not a persisted app setting.

### Current

Preserve the existing COB readout, accepted food-influence chart, freshness,
calculation origin, known/unknown end and entered-meal timeline. No preview
quantity or completion time is mixed into these values.

Keep its existing full-range initial framing and gestures/accessibility actions;
do not add visible zoom buttons. Store its chart viewport and scroll position
separately so switching to Estimate and back does not discard an explored range.
Normal accepted-display updates still reconcile the viewport with its domain;
an obsolete source is not frozen merely because its mode was hidden.

### Estimate

The body contains, in order:

1. A compact menu using the existing food-type labels/images: Fast, Mixed,
   Fat/Protein. The closed control shows the selected type.
2. One segmented strip using the existing Small, Medium and Large portion labels.
3. A compact summary of modeled portion grams and modeled duration, followed by
   the source label Type-default model.
4. A full-horizon modeled-remaining-grams chart and a compact selected-point
   readout: elapsed time, modeled remaining and modeled absorbed grams.

Start at Mixed and Medium, matching the familiar Food form defaults. These are
independent inspection choices, not a selection of an existing meal and not a
draft that will silently be submitted. There is no GI input, manual quantity
input, Save/Apply action or automatic history-derived gram substitution here.

The horizontal axis is minutes since a hypothetical meal, from zero through the
model endpoint. The vertical axis is modeled remaining grams, from zero through
the selected portion's modeled mass. The curve starts at that mass and ends at
zero. Absorbed grams are available in the point readout, not a second duplicate
curve. All points come from HybridMealResponsePreview; do not reconstruct a
second absorption formula in the renderer.

Show the entire modeled horizon by default, not a 60-minute crop. Changing type
or portion updates the summary, curve, axis and inspection point together. On a
change, reset inspection to the start and discard the old readout. Tapping or
dragging selects the nearest available five-minute point, including both ends.
Accessible previous/next point actions provide equivalent inspection without
touch precision. No visible zoom controls or changing chart height on selection.

Estimate does not inherit Current's unavailable/stale/inactive state. It remains
available with no active COB, no meals or no connected clinical stream when the
portion settings are valid. Do not label the hypothetical model as a fresh CGM
calculation or the physiological completion of current COB.

### Shared Actions And Details

The existing Eating Soon and Add Meal footer routes remain available in both
modes. They open the same canonical UI as before. Preview choices and quantities
are not passed into either action, and no extra submission callback is added.
Selecting a mode/type/portion/point, showing Info or closing the dialog sends no
carbs, targets, insulin, calibration or sound commands.

Info is mode-specific. Current retains its existing legend and entered-meal
context. Estimate details explain the modeled units, portion-preset source,
default duration and that personal history has not been connected in this stage.
Keep the main surface to values and short labels; place explanation in Info.

## Data Flow And Availability

Use a focused pure UI adapter for HybridMealResponseEstimator. Its inputs are
the local selected type/portion and the already supplied OverviewUiState portion
settings. Capture one positive cutoff, valid zone and minute-of-day when the
dialog session is created, restore them on rotation and do not tick a clock.
The captured time satisfies the domain request; it is not clinical freshness.

Pass no prepared personal episodes and no declared grams. The quantity therefore
comes from the user's existing portion presets. Recompute only when type,
portion or those settings change, not on every glucose update/recomposition.
The existing default horizons are Fast45, Mixed120 and Fat/Protein270 minutes.
They are model assumptions, not measured personal absorption times.

Only expose relevant preview fields to the UI. Do not display supportMeals=0 as
proof of zero historical meals: no history was loaded. Do not manufacture a
missing-body-profile error from the request's default profile resolution. Existing
user-profile diagnostics remain authoritative and unchanged. Age, height and
weight do not acquire arbitrary GI or duration multipliers.

Validate portion settings and the complete preview before rendering. For invalid
settings or an invalid result, show a concise estimate-unavailable reason and no
old curve or fake zero. Current and the shared actions remain usable. Catch only
expected validation failures; do not swallow arbitrary programming errors.

Do not add Room/history queries, a Flow subscription, AppContainer registration,
network calls, timers, polling, scheduled work, persistent options or a database
migration. No full history scans or phone-data read is part of this UI stage.

## Components And State

- ClarityFoodDetailsDialog owns the saveable mode and independent preview choices.
- Preserve Current content and saveable chart/scroll state with a keyed state
  holder or a small equivalent hoist. Keep the existing domain reconciliation.
- A focused estimate-state adapter supplies a ready/unavailable presentation
  result and exact chart points; it has no clinical/action dependency.
- A small Compose chart owns point inspection, axes and accessibility. Reuse
  existing theme/formatting conventions, but not the clinical chart's mmol/L
  DTO or timestamp axes as a disguised grams/minutes transport.
- Add localized labels and concise mode-specific Info strings to the existing
  resource structure. Keep helpers out of the large MainViewModel.

MealEntryDialog, MealEntryConfirmation, canonical action/provenance contracts,
clinical forecast and live accepted food/COB models are outside the edit scope.

## Layout And Accessibility

Use the existing Balance/Lenta styling: unframed content, restrained separators,
compact headings and theme colors. A touch target remains at least48dp even when
the visual icon is small. Add tooltips/content descriptions to unfamiliar icons
and selected-state semantics to mode/type/portion controls.

Use stable responsive chart bounds. At320x640 and normal font, both modes keep
header, controls, primary values and chart visible without requiring scroll for
the primary workflow. Use48dp type and portion control rows rather than two tall
picture grids; the Food form's existing picture controls are not restyled.
Current's long meal history may still scroll. At large
font, allow body scroll rather than clipping values, selectors or the footer.
Respect system insets; keep footer actions reachable in light/dark themes and
portrait/landscape. Neither color nor a line alone is the only indicator of value
or selected mode. Do not duplicate overflow menus or add explanatory cards.

## Safety Boundary

No laboratory GI number, measured absorption completion, personal-learning claim
or actual-eaten-mass accuracy is displayed. The hybrid result remains
clinicalInfluenceAllowed=false and actualMassAccuracyValidated=false.

No actual COB, gram record/provenance, 5/30/60 forecast, target, dose, Eating Soon
priority or hypo-protection changes. The UI cannot feed its preview parameters
into therapy. Source publication and an APK build do not authorize installation
or clinical activation.

## Verification After Written-Spec Approval

Use TDD for the adapter and native Compose interaction regressions. Cover all
default types/portions, custom and invalid portion settings, full endpoints and
mass conservation, exact selected-point values, parameter-change reset and no
history/profile claims. No history-query source should be needed by the adapter.

Verify default Current mode, independent preview choices, rotation retention,
fresh reopening, separate scroll/viewport retention and accepted-display updates.
Exercise every passive interaction with action counters to prove zero commands;
the original explicit Food confirmation and Eating Soon routes retain their
existing single-invocation behavior and exact payloads.

Inspect synthetic native screenshots, text/control bounds and rendered curve
pixels at320x640 and taller sizes, light/dark, large fonts and landscape. Check
that a no-COB or stale Current state does not suppress Estimate, and invalid
settings do not leave a stale chart visible. Keep synthetic render evidence
private and do not represent it as a real-user/device clinical validation.

Run the full Android unit, lint, Kotlin compile and APK gates after code changes,
review exact source/privacy diff and inspect exact-SHA CI on the existing feature
branch. Backend and device operations are not required by this passive UI stage.

## References

- [Approved hybrid domain design](2026-10-12-hybrid-meal-response-design.md).
- ClarityFoodCurve.kt: accepted Current chart and canonical footer routes.
- InteractiveClinicalChartState.kt: saved viewport and domain reconciliation.
- HybridMealResponseEstimator.kt: advisory mass points and immutable safety flags.
- PersonalAbsorptionRepository.kt: read-only audit, not prepared learning input.
