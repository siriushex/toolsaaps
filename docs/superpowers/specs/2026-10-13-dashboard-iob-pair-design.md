# Dashboard IOB Pair

Status: exact slash-pair presentation requested by the user; written specification
awaits review before implementation. This is display-only, not an IOB model change.

## Requested Presentation

Replace the single primary-panel IOB value with current IOB / signed net IOB,
followed by the existing unit once. Order is fixed: current first, signed total
second. Illustrative values: 1.8 / -0.4 U, 1.8 / 1.2 U or 1.8 / -- U.
Use the existing decimal formatting and precision; do not change global locale
rules or force an extra plus sign where existing positive IOB formatting omits it.
Negative net IOB is not clipped to zero or converted to an absolute quantity.

The user explicitly chose a slash pair on the primary panel. A separate row would
consume more space, while leaving net IOB only in Details would not meet the ask.
Retain the current IOB label, metric navigation and original detailed dialog.
Provide a localized accessibility description naming the two values in order.
No long permanent explanation, extra card, button or menu is added.

## Source And Boundary

Use OverviewUiState.currentIobUnits and state.iobDetails.signedNetIobUnits exactly
as supplied by the existing UI mapper. They already map latestIobUnits and
latestIobRealUnits; the latter resolves existing iob_net_units/iob_real_units.
Do not recompute the net value from bolus/basal, infer it from current IOB or
change source selection, freshness, telemetry or signed-net physiology.

Missing or nonfinite values display the existing -- placeholder independently.
Do not manufacture a net zero, copy the current amount after the slash or label
both values AAPS merely because a mockup displays that source name. The existing
source/age/fallback information remains accessible in Details.

Use one small pure presentation helper shared by the actual dashboard paths that
render this primary IOB metric. Balance/Clarity is the selected surface; the
alternative Overview primary metric should not silently retain conflicting
semantics if it remains reachable. Do not change diagnostic telemetry chips or
other metrics, nor the detailed IOB values and action routes.

## Compact Layout

Keep the established theme typography. Allow a deliberate break at the pair
separator or before the unit when necessary, without dropping either number,
ellipsis, overlapping adjacent COB/ISF or shrinking system accessibility text.
Reserve predictable value space so changing signs or quantities does not shift
the chart during routine updates. The slash remains visually attached to the
second value if the pair wraps. Existing metric-grid reflow at large font remains.

## Verification

TDD covers positive, negative, zero, missing and nonfinite values, independent
placeholders, unchanged order, precision and one unit. Native regressions cover
the real primary IOB surface, its original Details route and zero therapy actions
on open/cancel. Inspect 320x640 and taller screens, light/dark and large font,
including large finite values and differing signs, for text/control bounds.

After implementation run Android unit/lint/Kotlin/APK and exact-source CI with
the usual scoped privacy review. No schema, worker, runtime controller, IOB
calculation, targets, doses, Eating Soon or phone operation is changed/authorized.
