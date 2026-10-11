# Native Balance Overview

## Scope

Implements the user's approved Balance layout within the existing Clarity style.
No new style migration or settings defaults are needed. Glucose, observed AAPS
target and current Target Manager proposal remain different values. Therapy,
forecast calculations, acceptance clocks, alarms, backend and schema32 are unchanged.

## Presentation

- CGM/exchange ages share a short row. Glucose and observed target are adjacent
  unless narrow width or system font scaling requires vertical stacking.
- IOB, COB, actual-source ISF/CR, calculated UAM and effective base target are
  visible before the glucose chart. Responsive3/2/1-column layout wraps text;
  no viewport-dependent font reduction, nested cards or fabricated zero values.
  COB opens the existing meal-entry dialog, identical to the add-meal action.
  IOB, source ISF/CR, UAM and base target keep their existing menus.
- The large numerical forecast row is removed. Small5/30/60 marks open the
  existing read-only value/interval dialog. Marks require current, non-stale
  horizons and an exactly matching timestamp/value in the existing future path.
  Off-viewport, missing, invalid or mismatched points cannot create hit targets.
- Each mark retains a48dp hit area. Clustered marks fan out with leaders instead
  of overlapping; marks near the top appear below their points. This changes
  label layout only, never the underlying timestamp or forecast value.
- Main glucose chart height190dp, expanded300dp. Requested1/3/6-hour history
  windows and pan/zoom reuse the existing bounded viewport, clamped to available
  data. Controls share a row on sufficient width and split for narrow/large text.
- The full carbohydrate tail does not stretch the main glucose forecast axis.
  Its separate food-influence row/dialog retains the original accepted display, quality
  labels and full-period/start/end controls. No COB rescaling or GI change.
- Existing source, IOB/UAM, target, profile warning, pump diagnostic and clinical
  report destinations remain. Recalculate/measurement/details icons have tooltips.
  Pump-link text reports technical evidence, never physical attachment or delivery.
- Eating Soon confirmation, queue/unknown handling and canonical sender are reused.
  Opening any passive detail or graph does not submit therapy or save settings.
  Opening/cancelling meal entry also does not submit; only its existing explicit
  confirmation invokes the unchanged guarded manual-meal callback.

## Primary Navigation

Returning from a secondary destination first pops to an existing requested primary
entry when present, retaining that entry's UI state. Otherwise the secondary
destination is not saved/restored as a primary tab. Ordinary primary switches keep
their prior save/restore policy. No secondary form is automatically saved.

## Verification

Two new native layout/marker tests failed against the former UI before changes.
Native Robolectric tests cover open metrics, real NavHost return/state restoration,
marker hit areas, light/dark Russian large-text rendering, explicit confirmation,
unknown delivery/no replay, missing/stale forecasts and full food-tail navigation.
Marker math tests cover clustered targets, invalid/off-viewport points and accepted
path mismatch. Rendered synthetic images are inspected privately, not published.

Local Android gate passed:5201 cases,zero failures/errors,3 optional phone-copy
skips; lint zero errors,315 warnings,4 hints; Kotlin compile and signed APK build
passed. One unchanged report test timed out in the initial full run; isolation
and the final full run passed without changing deadlines or assertions.
Exact-source remote Verify remains a separate publication gate. These checks do not
prove actual phone rendering, TalkBack behavior, clinical accuracy, therapy delivery,
alarm audibility or CPU/RAM savings. Installation requires separate authorization
and fresh private backup/device checks; no phone update is part of this source stage.

## Carbohydrate Entry Correction (2026-10-12)

The user's COB feedback restores the familiar meal-entry route without changing
the form itself. A native RED established that COB opened the graph instead of
the existing editor. Both COB and add-meal now reuse that editor; the food-influence
row remains the separate graph. Existing IOB, ISF/CR, UAM and base-target menus
are independently checked for open/cancel behavior without mutation callbacks.

Focused16 cases pass. Full Android unit/lint/compile/APK gate passes:5203 cases,
zero failures/errors,3 optional phone-copy skips; lint315 warnings/4 hints and
zero errors. Exact-source CI and corrected on-device acceptance are separate gates.
