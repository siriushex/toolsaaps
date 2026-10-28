# Prospective Meal Forecast Validation Contract

Date:2026-10-13.
Scope:observational source prerequisite accepted after the live-activation audit.

## Outcome

Make future-glucose comparison reviewable without treating reconstruction of
past CGM under recorded insulin as a prospective forecast. The requested actual
target integration remains subject to model and control assessment in
2026-10-13-meal-response-target-integration-design.md. No direct GI multiplier or
new therapeutic threshold is introduced.

## Registration

MealProspectiveFrame.register accepts a baseline and optional candidate for
exact5/30/60 horizons. Prediction times derive from the same CGM anchor. Freeze
membership/order/values and complete registration strictly before anchor+5min.
The baseline must already be completed; the candidate starts after the frozen
baseline snapshot and completes before receipt. Invalid/nonfinite/missing values
or half-specified intervals reject. Missing candidate records BASELINE_ONLY.

The identity includes accepted forecast digest, generation/cycle/settings,
source/session, glucose basis/calibration model, historical zone and explicit
LOCAL_ENGINE or ACCEPTED_CONTROL comparison basis. Both sides must match.
Candidate model version/evidence digest, training-through and availability are
frozen. Training and source coverage end no later than the input anchor, and
availability precedes calculation. Relabeling a retrospective forecast with an
earlier timestamp is not authenticated prospective evidence.

MealProspectiveCapture.register adapts the existing exact-readback runtime
snapshot. Known sensor session, exact source/quality and supplied current
identity must match. The registration clock is read only after validation and
cancellation checks. This API has no storage/action/controller dependencies and
is not registered as a production consumer. It does not establish currentness by
itself: the future collector must obtain identity from the current accepted cycle
and authenticate the candidate's measurement basis and input provenance.

## Paired Outcomes

Evaluation cutoff is explicit. A later occurrence or later receipt is unavailable
at an earlier cutoff. An unknown receipt time is a rejected observation. Use only
the exact future timestamp, same source/session/calibration/glucose basis, finite
positive mmol/L and OK quality. Do not interpolate, borrow a nearby point, apply
today's calibration to old CGM or remove low values to improve error metrics.

Duplicate identical observations have one vote. Conflicting value or quality
rejects the outcome. Repeated forecasts for the same anchor/cohort collapse only
when all source/model/calculation content matches; conflicting cycles reject the
whole anchor instead of selecting the better prediction. Registration time is
not a second vote. Returned collections are immutable.

Missing candidate count is independent of paired scores. Each model artifact and
runtime/measurement cohort has its own report; no best-cohort selection. Errors
use the same eligible outcomes for both models. Samples average within the
prediction's recorded local day, and days have equal weight. Report paired
sample/day support, MAE/RMSE/signed bias and maximum positive overprediction.
Empty support produces null errors, not zero.

The caller supplies an explicit low threshold for diagnostic counting; this code
does not choose a treatment threshold. Report actual low samples and missed lows
for the means. For both provided intervals, additionally report common interval
support/coverage and missed lows in the lower bounds. No interval is invented or
inherited when the candidate only supplies a mean. Coverage does not establish
calibration or safety of a changed control policy.

### Interval Observation Audit (2026-10-28)

Endpoint error alone cannot describe lows between 5/30/60-minute checkpoints.
For each non-conflicting unique receipt and completed horizon, audit observations
strictly after registration up to and including that horizon's endpoint. Retain
the same identity, availability, value, quality and duplicate/conflict rules.
Do not score observations occurring before registration, even if they arrived
later. Future-availability evidence remains excluded at the evaluation cutoff.

Report evaluated windows, windows without usable observations, with an observed
low, with a low outside forecast checkpoints, with conflicting observations and
with rejected observations. These categories may overlap. Counts describe receipt
windows, not distinct low episodes: one low can occur in multiple windows. The
audit does not invent intermediate predictions, missed-low forecast scores,
interpolated glucose or confidence bands. Missing endpoint observations do not
hide valid intermediate lows. Sparse or absent readings never prove complete
coverage, and observationCoverageVerified remains false.

The private aggregate report includes both horizons and baselineOnlyHorizons,
plus windowObservationCoverageVerified=false. A baseline-only dataset must not
export an empty score list merely because no candidate was registered.

Separately, MealTimingResearchAssessment reports below56 alongside below46,
below39 and below30 for every retained no-food insulin scenario. This diagnostic
uses the frozen simulated glucose trajectory, including its current value, not
elapsed time alone. The first strictly lower sampled value produces a five-minute
grid interval; already-below is [0,0], no crossing is null. It is neither an exact
crossing nor a safe interval to wait. Declared new-food GI cannot affect a no-food
path. No notification, clinical admission or recommended start follows from it.

## Limits And Meaning

- At most7000 receipt frames,24000 outcomes and256 distinct model/context cohorts.
  Overflow is structured and never a silently truncated evaluation.
- Every result has clinicalInfluenceAllowed=false and controlSafetyValidated=false.
- No live controller invocation, target journal, dispatcher, food/COB write,
  model training, new schema, timer, worker, subscriber or phone operation.
- Actual future insulin/control and activity affect observed glucose. These
  paired observational errors cannot establish outcomes of a changed target.
- Baseline-only/missing/conflicting support must remain visible; favorable
  candidate availability cannot be presented as complete population accuracy.
- This stage supplies a capture/evaluation contract. It does not yet provide a
  qualified personalized candidate or collect a durable real-device dataset.

## Private Tool

MealProspectiveEvaluationToolTest.evaluatePrivateReceiptBundle takes explicit
COPILOT_PROSPECTIVE_EVALUATION_JSON and COPILOT_PROSPECTIVE_EVALUATION_OUTPUT_JSON
paths. Both must stay outside the source project; input is at most8MiB and output
uses CREATE_NEW. The bundle contains receipt DTOs, outcomes, cutoff and diagnostic
threshold. DTOs are reconstructed through registration checks, not deserialized
into trusted receipt objects. Export contains aggregate support/rejection/error
metrics and explicit false capture-authentication/control/clinical flags.

A supplied file cannot prove historical timestamps or trusted pre-outcome storage.
Never create such provenance from legacy forecast rows, whose target timestamps
do not record original completion/availability. Real phone data and reports remain
private. Synthetic examples verify only the software contract.

## Verification And Remaining Work

Focused tests cover clock boundaries, future evidence, exact identities, immutable
values, same-window scoring, source/calibration changes, unavailable/invalid/
conflicting outcomes, lows, missing intervals, repeat voting, cohort separation,
day balancing, overflow, accepted/local basis and real-engine state isolation.
The actual private report tool is additionally run on a synthetic bundle.
Full local Android unit/lint/compile/assemble and exact-source CI are code gates.

Subsequent work requires trusted restart-safe receipt/outcome storage, honest
availability/context capture, a qualified prospective personalized forecast,
independent observed-data evaluation and professional control-safety assessment.
Changing internal Target Manager inputs still affects real therapy via its
existing dispatcher; that route is not enabled by this contract.
