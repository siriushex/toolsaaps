# GI Contribution Analysis

## Scope And Meaning

The user requested comprehensive GI analysis and eventual Target Manager
influence. This implements the next OFFLINE analysis stage of the approved
meal-response integration design. It does not complete or activate clinical
integration. No phone, live forecast, COB, food grams, target or dose is changed.

Food GI and personal response are separate quantities. The University of Sydney
describes standardized GI measurement using a reference glucose challenge and
multiple participants. Ordinary CGM under insulin treatment is not that protocol.
See https://glycemicindex.com/about-gi/ (checked 2026-10-13).

AAPS documents absorption estimation from glucose deviations, I:C and ISF, with
IOB and fallback absorption affecting COB. Therefore a residual under recorded
insulin is not independent proof of actual grams or GI. See
https://wiki.aaps.app/en/dev/DailyLifeWithAaps/CobCalculation.html
(checked 2026-10-13). These sources motivate the distinction, not clinical
validation of the experimental model below.

## Inputs And Selection

- Reuse the qualified completed-episode producer and its immutable evidence.
  Missing insulin/model/context/availability stays missing. No legacy metadata
  is promoted to a reviewed assertion and no threshold is relaxed.
- Bind known USER/CATALOG GI to the same canonical meal revision. Preserve
  availability, source and catalog reference. No GI is assigned from broad food
  type, age, weight, a CGM rise or a model-generated label.
- Match the existing21-day completed-day, type, portion and circular two-hour
  neighborhood. Keep sensor session, calibration identity, insulin model and
  insulin duration cohorts separate. More than one cohort requires explicit
  selection; the largest or best-performing cohort is not chosen automatically.
- Freeze the earlier/later whole-day boundary BEFORE missing GI removes rows.
  Training labels and reviewed episode evidence must be available before that
  boundary. Keep the existing8 meals/5 training days and3 meals/2 validation
  days as research support requirements, not clinical validation criteria.
- Bounds:5000 GI records,240 prepared episodes, bounded metadata. Source producer
  aggregate limits are unchanged. Overflow never produces a partial fit.

## Comparison

1. Existing type-based absorption shape.
2. Existing personal timing fit, with declared grams and ISF/CR fixed.
3. The same personal shape plus an empirical GI timing term fitted on training
   data only. This is an ablation comparison on identical eligible validation
   meals, not a new hand-selected GI-to-target percentage.

The third shape uses `exponent + slope * (GI - trainingMeanGI) / 100`.
The research slope search is -0.25 through0.25 in0.025 increments, ties prefer
zero, and support shrinks it by `n / (n + 8)`. This search grid is an experimental
regularization choice, NOT a clinically calibrated coefficient or a claim that
GI scales absorption linearly. The existing0.75..1.25 exponent bounds and
per-type duration limits remain. No extrapolation outside training GI support.
The uncalibrated display-only GI exponent is not reused.

The model preserves declared total mass and the personal curve's endpoint.
Unknown/constant GI cannot identify a GI contribution. Non-improving, ambiguous
or boundary fits remain visible diagnostic results; none can enable therapy.

Report5/30/60-minute errors of summed insulin-corrected response increments,
including MAE, signed bias and maximum overprediction. Average windows within a
meal, meals within a day, then days equally. Count meals/days/windows separately.
Only contiguous observed windows count. Missing windows are unknown, not zero.
Overlapping windows are not independent observations or additional support days.
Report0.8x and1.2x declared-mass stress scenarios with the model fixed; these are
engineering sensitivity checks, not measured mass bounds or a new gram estimate.

These errors use recorded post-meal insulin and reviewed historical context.
They are NOT prospective glucose-forecast accuracy, low-risk sensitivity,
calibrated confidence intervals or counterfactual target outcomes. Clinical
glucose thresholds and target limits are not estimated by this stage.

## Integration And Target Boundary

`PersonalAbsorptionRepository.analyzeGi` performs one on-demand bounded history
read and joins matching GI from the same snapshot. No worker, AppContainer,
CGM subscription, settings toggle, schema or runtime writer is registered.
`PersonalGiAnalysisToolTest` accepts an explicitly prepared private JSON bundle
and creates an exclusive aggregate report outside the source tree. No automatic
capture or ingestion-time reconstruction is added.

Every result has `clinicalInfluenceAllowed=false`, no inferred laboratory GI and
no target percentage. The report explicitly lists outstanding prospective
forecast validation, independent closed-loop safety review and clinical
activation authorization. These are reporting blockers, NOT a new enforcement
gate or a bypass of Target Manager's existing guards.

Before actual target influence, capture causal as-of prediction inputs, apply
the candidate consistently to historical and future food effects without UAM
double counting, evaluate later-day forecasts and calibrated lower bounds, and
independently review closed-loop control safety. Historical-insulin replay alone
cannot establish outcomes under a different target. Eating Soon, actual hypo
protection, sensor/delivery checks, bounds, ownership and single-writer dispatch
must remain unchanged. No EVALUATED/ANALYZED status is therapy authorization.

## Verification

Synthetic tests cover learned versus absent GI effect, held-out independence,
missing/invalid/conflicting/late GI, preserved split, no extrapolation, source
and cohort failures, mass conservation, missing windows and frozen outputs.
Real Room tests verify same-revision GI joins and unchanged therapy records.
Existing timing/evidence regression suites and full Android unit/lint/compile/
assemble gates are required. Synthetic gains do not establish personal accuracy.

Optional private tool environment:
`COPILOT_GI_ANALYSIS_JSON` and `COPILOT_GI_ANALYSIS_OUTPUT_JSON`.
The bundle contains `request`, independently sourced `canonicalMeals`, prepared
`evidence`, known `gi`, and optional `comparisonCohort`. Missing prepared evidence
is a valid insufficient-data audit, not permission to invent it. Run only
`io.aaps.copilot.tools.PersonalGiAnalysisToolTest` with the normal unit-test task.
