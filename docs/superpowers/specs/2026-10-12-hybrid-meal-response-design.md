# Hybrid Meal Response With Sparse History

Status: proposed combination requested by the user; written design awaits review.
This is not an implemented feature or approval for automatic therapy. It extends
the existing offline diagnostic stage, not its clinical authority.

## Goal And Meaning

Provide a useful provisional absorption curve with no personal history, then
describe and conditionally refine it using comparable completed real meals and
CGM/insulin context. The user requests an approximate GI by selected food type.
The implementable output is a personal response/timing estimate, not a laboratory
GI reconstructed from routine glucose. The distinction must remain visible.

GI requires a food/reference carbohydrate comparison. Neither a FAST/MIXED/
FAT_PROTEIN label nor age/height/weight supplies that reference. Do not invent a
GI number by mapping duration, body mass or glucose amplitude to a0..100 scale.
Known USER/CATALOG GI remains separate exact-identity metadata. No new automatic
GI provenance is written into that existing type or database fields.

## Proposed First Implementation

Add a pure HybridMealResponseEstimator and synthetic tests. Reuse the existing
type curve, portion-prior and prepared-personal-evidence boundaries. Return a
full advisory preview curve, history summary, source/status and support counts.
No UI/runtime/worker registration or stored settings/schema changes in this
first implementation. A later passive UI integration needs separately reviewed
freshness and accepted-display rules; this design does not replace clinical curves.

This scope needs user review because the ultimate prior request included actual
therapy changes, while this stage cannot validate or enable them.

## Cold Start And Sparse Data

- With zero usable history, always return the existing selected-type model:
  FAST45, MIXED120 or FAT_PROTEIN270 minutes, with exponent1. These are engineering
  defaults, not measured absorption times. Unknown type uses the existing MIXED
  default and explicitly records that source.
- Preserve confirmed entered grams. If only a portion is selected, use the
  existing portion settings and MealPortionEstimator.describePrior suggestion.
  A suggested quantity is not written or treated as measured mass.
- When history is insufficient for that existing portion estimator, retain the
  preset; do not bypass its provenance/minimum-support requirements.
- One or a few individually qualified episodes may produce a descriptive
  historical response summary. They do not produce a validated personal timing
  fit or replace the default preview shape.
- Missing or faulty source data excludes learning, not the type-based preview.
  Invalid request parameters produce an explicit error, not a fake zero curve.
- Profile fields may be shown as context/validation status. They neither change
  GI/duration through arbitrary multipliers nor increase evidence quality merely
  because age, height and weight have been filled in.

## Historical Cohort

Use the existing21 completed local-day window, exact selected type/portion,
recorded zone and circular +/-120-minute meal-time neighborhood. The current
unfinished day is not training evidence. Do not infer an unrecorded type from
the current selection or meal clock alone. Approximate gram labels remain noisy.

Reuse the PersonalAbsorptionEvidenceAdapter checks for real canonical entered/
corrected meals, original revision/availability, independent insulin intervals,
pre-meal ISF/CR/model, and explicit sensor/calibration/delivery/activity/illness/
other-food context. Retain existing conflict/tombstone, rescue, hypo, overlap,
unknown-source, stale-sensor, chronology and overflow exclusions.

Meal cohort preparation must be shared with PersonalAbsorptionEstimator instead
of duplicating or weakening its selection. Extract only that focused helper if
needed, with unchanged existing regression results. UAM/accepted suggestions and
the model's own COB or curve are never independent labels.

Legacy database audit reports missing independent context. It cannot use current
settings as historical profile truth or substitute total IOB for insulin activity.
Until valid evidence exists, the hybrid output is honestly type-default.

## Descriptive Average Response

Use prepared five-minute corrected residuals, not average raw glucose levels.
The adapter already calculates observed delta plus modeled insulin effect minus
the frozen pre-meal background. Never attribute every glucose rise to food.

For each episode, describe the residual per declared gram and historical carb
sensitivity, conditional on those entered values. Retain signed/negative values.
Aggregate comparable episodes using medians and observed interquartile spread
with one episode per contribution; a long meal or repeated telemetry must not
dominate. Report meal/day count and original engineering units explicitly.
This robust average is an apparent response, not GI, absorbed grams or confidence
in actual mass. At one episode, report that observation with no invented spread.
Do not fill absent intervals with zero, clip negative residuals to food, normalize
every observed episode to force full absorption, or advertise a calibrated percent
confidence. Statistics of observations are not the generated monotone curve.

## Personal Timing Candidate

Reuse PersonalAbsorptionEstimator without lowering its8 training meals/5 days
and3 held-out meals/2 days requirements. Whole-day chronological holdout and
original evidence availability remain unchanged. Current correction behavior
cannot be treated as a no-intervention counterfactual.

Only an EVALUATED result may supply the separate advisory candidate preview;
AMBIGUOUS, NOT_IMPROVED, BOUNDED_CANDIDATE, OVERFLOW and INSUFFICIENT_DATA retain
the default preview and expose the reason. The existing n/(n+8) timing shrinkage
already applies: do not blend or apply known-GI shaping a second time.

Generate cumulative monotone curve points from the returned shape only. Preserve
declared modeled mass and endpoints. Model completion is not observed physiological
completion, and reconstruction scores are not prospective prediction accuracy.

## Output And Safety Boundary

Typed output includes selected/default type, quantity origin, curve shape/full
modeled duration, signed response summary, status, eligible meal/day counts,
rejections, evidence cutoff/digest and model version. No numerical learned GI,
actual eaten-mass accuracy, target adjustment percent or calibrated confidence.
Every status retains clinicalInfluenceAllowed=false and
actualMassAccuracyValidated=false.

No clinical COB/5/30/60 forecast changes, automatic carb entry, target writes,
boluses, insulin changes, Eating Soon override or weakened hypo protections.
Building, publishing or displaying this diagnostic model is not therapy approval.

## Resource Limits And Tests

Keep existing bounded input caps and full-horizon display limits. Pure calculation
uses one explicit cutoff and no network/polling/new scheduled work. An overflow is
an explicit default-preview reason, never a partial trained cohort.

Synthetic regressions must cover zero/one/few/qualified episodes, type/portion/
time-zone matching including midnight, signed outliers, missing steps, duplicate/
tombstone/conflict, late/future evidence, inadequate insulin/context, invalid
profile/quantity, all personal-fit statuses, no double shaping and complete curve
mass/endpoints. Invalid/complete anthropometry cannot manufacture GI or evidence.
Old timing and quantity-prior tests must retain their behavior after cohort reuse.
Full Android tests/lint/Kotlin/APK and exact-source CI follow implementation.
No executable tests or phone installation are implied by this written proposal.

## References

- [Existing qualified timing design](2026-10-12-personal-absorption-shadow-design.md).
- [University of Sydney GI definition and methodology](https://glycemicindex.com/about-gi/).
- [Official AAPS COB and insulin/ISF/I:C dependence](https://wiki.aaps.app/en/dev/DailyLifeWithAaps/CobCalculation.html).

