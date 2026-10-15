# Full Carbohydrate Curve And GI Provenance

## Scope

The user requested the complete carbohydrate absorption curve with glycemic
index (GI), then approved proceeding with confirmed-source/manual GI and an
existing-profile fallback when GI is unknown. During written review, the user
clarified that GI must affect the curve's shape, not merely appear as metadata.
This revision supersedes the metadata-only decision and awaits written review
of the bounded display-shaping rule below before implementation.

The Overview yellow curve remains cumulative announced-food influence without
insulin, anchored at current displayed glucose. "Complete" means its remaining
modeled influence is shown through the estimated completion of all active meals,
not only the next 30 minutes. Observed glucose history remains observed history;
this stage does not invent a historical food-only glucose trajectory. An
absorption percentage or grams series must not be mislabeled as glucose.

## Decision

Use the accepted engine's food component and resolved absorption profiles.
Extending the net glucose forecast to several hours would imply unvalidated
future insulin/controller coverage. Reconstructing food effects in the UI from
current ISF/CR would mix cycles and can disagree with the accepted engine.
Neither alternative is selected.

GI is recorded with its source and changes the displayed distribution of the
remaining food influence. It does not replace the accepted clinical profile,
multiply announced grams, alter current clinical COB, or determine an exact
absorption duration. There is no validated GI-to-hours conversion here. The
numerical shape adjustment is an explicit engineering heuristic for display,
not a physiological model or a change to clinical forecasts/target control.

The selected approach redistributes the current modeled remainder while keeping
its mass and completion time. An alternative that changes total duration from
GI alone is rejected: it would add an unsupported physiological timing claim.
The earlier metadata-only alternative does not meet the clarified user request.

## Display Shape Rule

For each confirmed meal, let C(t) be its existing cumulative absorbed fraction,
c0 = C(now), and R = grams * (1 - c0). Use the actual resolved profile, including
its existing cutoff, and do not reconstruct or modify past absorption. For a
future point with c0 < 1 define u = (C(t) - c0) / (1 - c0), bounded to [0, 1].

With supported GI, use exponent

    p = 1 + 0.25 * clamp((60 - GI) / 60, -1, 1)

and display cumulative future absorbed grams R * u^p. Without supported GI use
p = 1 and preserve the original food component exactly. Handle u = 0 and u = 1
exactly; fully absorbed or zero-gram meals contribute zero, without division by
zero. Reject invalid/nonmonotonic fractions rather than fabricate a component.

Higher GI lowers p and moves the remaining influence earlier; lower GI raises p
and moves it later. All future increments remain nonnegative; future cumulative
mass starts at zero and ends at the same R. The baseline absorption finish time,
already absorbed amount, announced grams and clinical COB do not change. Sum
per-meal contributions only after shaping each independently; do not apply an
invented average GI to a mixed set of meals.

The reference 60 and bounded 0.25 strength are explicit versioned display choices,
not calibrated medical parameters. The resulting curve is an estimate. Use model
version `food_gi_shape_v1`; no assertion of individual timing accuracy, clinical
benefit, or dose suitability is permitted. A different strength/duration model
requires new review and evidence. Unsupported GI falls back without adjustment.

## Accepted Food Data

- Generate a bounded, immutable announced-food display component during the
  existing exclusive prediction cycle, from the same causal meal records,
  deduplication, resolved profiles, sensitivity and prediction clock as the
  accepted decomposition. Reuse the existing cumulative absorption functions;
  do not invoke research simulations, add UAM or simulate future insulin.
- Use a five-minute grid through the last modeled active-meal completion, capped
  at 720 minutes. Preserve the existing clinical 5/30/60 forecasts exactly. Its
  initial 60-minute unadjusted food steps must agree with the accepted announced
  component. The GI-adjusted display steps are separately typed display data,
  never a replacement for the accepted clinical announced-pressure steps.
- Include prediction clock, accepted cycle identity, bounded display steps,
  shape model version, adjusted-meal count and an explicit completeness flag.
  Do not claim completion if nonzero residual mass remains at the cap.
  No-food is a verified flat component; missing data is not
  fabricated as zero. Preserve the existing no-food display duration.
- Publish only after acceptance, through the existing display telemetry
  and subscription. Retain accepted sensitivity/cycle/freshness checks. A display
  budget, invalid data or identity failure makes only this curve unavailable;
  it must not suppress valid clinical forecasts or target evaluation.
- Limit the display payload to 16 KiB and 145 five-minute points including now.
  Support the currently installed 13-step payload as a bounded legacy display,
  without claiming its tail is complete. Reject excessive payloads, nonfinite or
  negative steps, unordered/invalid timestamps and mismatched identities.

## GI Data

- Add optional numeric GI and provenance to the existing manual meal editor and
  confirmed meal metadata. Manual input means user-supplied, not laboratory
  verification. A confirmed catalog value must retain its item and source
  reference. Do not infer GI from a photograph, food name or absorption profile.
- Blank means unknown. Accept finite values from 0 through 200 as a conservative
  input bound, not a physiological claim that GI cannot exceed 100. Invalid GI
  is rejected as GI metadata and shown as an input error. Invalid or missing
  optional GI must not block the existing real-carb confirmation/delivery path;
  it never changes carb grams.
- Preserve GI metadata locally across restart, keyed to the same confirmed
  canonical meal identity and revision. Use the existing pending-to-canonical
  reconciliation; do not attach by timestamp proximity or publish unconfirmed
  metadata as accepted. Existing meals migrate to unknown GI.
- A conflicting revision, partial ingredient GI coverage or missing available-
  carbohydrate quantities does not produce an invented average meal GI. Show
  only the supported value and source, otherwise unknown.
- Show GI next to its meal/profile information. Its confirmed profile and duration
  remain the base curve, with per-meal override precedence unchanged. Supported
  GI adds only the bounded display-shape adjustment above. Mark this estimate as
  GI-adjusted and food-only, not as the clinical prediction's food decomposition.
- Reuse supplied confirmed catalog metadata only; no external catalog service,
  AI schema expansion or automatic network lookup is introduced.

## Chart Behavior

- Include valid food points when computing the chart domain. Follow-live and
  reset expose the complete remaining tail; pan/zoom still preserves explore
  mode and the existing bounded point selection and per-pixel reduction.
- Retain actual timestamps, the common glucose origin and the food-only legend.
  When any meal is adjusted, the legend must identify a GI-shaped model estimate
  without insulin; missing GI for other meals must not be implied as known.
  Give a truncated tail or unknown estimate an explicit status rather than
  silently clipping it. Do not change insulin, glucose forecast or CI semantics.
- Test narrow screens, large font scale and both themes; extending time must not
  hide labels, produce overlaps or force an unreadable fixed-size layout.

## Files And Verification

The expected Android surface is the prediction food-component diagnostics,
`AutomationRepository`, accepted telemetry UI mapping, `MealImpactChartPoints`,
`MealRollingImpact`, clinical chart domain/viewport tests, the existing manual
meal confirmation/editor, canonical profile metadata persistence and its Room
migration. Keep each change bounded to display data and GI provenance. Update
`docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/PLAN.md` and `AI_NOTES.md`.

Start with failing focused regressions for tails beyond 30/60 minutes, partial
absorption, overlapping meals, exact unadjusted-prefix agreement, completeness/
mass bounds, legacy payloads, identity/freshness/budget failures and food-domain
inclusion.
GI shape tests cover high/low ordering, exact unchanged unknown-GI output, bounded
nonnegative steps, endpoint/remaining-mass conservation, partial absorption,
independent overlapping meals and unchanged finish time. Metadata tests cover
unknown, invalid, user/catalog provenance, restart, migration, canonical
reconciliation and no clinical-profile/gram changes. Shared control regressions
must prove clinical forecast and target inputs are unchanged for different GI.

Run `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug` and
`:app:compileDebugKotlin`, review explicit paths and publish on the existing
feature branch with exact Verify CI evidence. Any authorized working APK update
requires a fresh coherent private backup, matching signing certificate and
natural runtime/UI checks. Never insert test carbs, change a target, force a
clinical cycle or send test insulin to verify a chart. Keep phone data private.

The pending manual-SENT ownership correction is excluded. This display change
does not validate individual absorption timing or establish clinical benefit.

## Source Basis

Current code: `MealRollingImpact.nextThirtyMinutes` displays 0..30 minutes,
decomposition telemetry publishes 13 steps, and `clinicalChartDomain` omits food
points. `MealAbsorptionCurve` and `CarbAbsorptionProfiles` already supply timing
functions, while `MealAbsorptionProjection` demonstrates a bounded carbohydrate-
only grid up to 720 minutes. Do not wire its research caller as a clinical shortcut.

The University of Sydney describes GI as a relative two-hour glucose-response
measurement and associates higher GI with more rapid digestion/absorption:
https://glycemicindex.com/about-gi/ and https://glycemicindex.com/faqs/.
These sources support the qualitative distinction, not the exponent, reference
or adjustment strength above. The shape rule is our bounded display heuristic.
