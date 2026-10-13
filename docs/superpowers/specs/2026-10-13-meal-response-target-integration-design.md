# Meal Response And Target Manager

Date: 2026-10-13.
Status: architecture choice approved; written specification awaiting review.

## Outcome

The requested outcome is actual automatic target influence through a validated
personal meal-response forecast, not another passive-only estimate. Known GI may
be a supporting food feature; a personal CGM response is not laboratory GI.
The user approved this route instead of a direct GI-to-target percentage.
No therapy activation follows from this design approval alone.

## Approaches

1. Personal absorption through the existing forecast/controller chain, selected.
   Requires qualified historical insulin, glucose and meal context, prediction
   evaluation and a separate control-safety assessment before actual use.
2. Provenance-backed GI for a specifically identified food through a validated
   forecast model. Does not supply personal CGM learning or identify a mixed meal
   from a broad food-type label; remains a possible supporting feature.
3. Direct target percentage from GI/grams, rejected. No calibrated relationship
   or control-safety evidence supports it; food already affects the controller.

## Current Boundaries

LentaMealEstimator is passive; its output and selected preview controls cannot
feed the controller. MealFoodDisplayProjection's GI exponent is an uncalibrated
display heuristic and must not be reused as a clinical parameter. The clinical
engine already resolves meal profiles into known historical and future food
influence. AdaptiveTargetControllerRule consumes the accepted 5/30/60 forecasts,
CI, glucose, COB and safety IOB before TargetProposalFactory/TargetManager.
PersonalAbsorptionRepository does not currently provide qualified prepared
episodes. EVALUATED research status is not permission to alter therapy.

## First Implementation Boundary: Qualified Evidence

Implement a bounded read-only producer of completed personal-response episodes
using existing canonical identity, revision and provenance rules. It must provide
original sample occurrence/availability, sensor/calibration identity, historical
timezone, insulin model and contemporaneous ISF/CR, independently covered basal
and bolus activity, meal identity/quantity origin and available context evidence.

Audit each required field before constructing PersonalAbsorptionInput. Missing
history/availability/context is a structured rejection, not zero insulin, a
receipt-time substitute, guessed timezone, or invented reviewed checks. Reject
synthetic UAM, rescue treatment, ambiguous/revised/deleted labels and material
delivery/sensor/activity/illness/other-food confounds. Do not silently loosen
PersonalAbsorptionEvidenceAdapter to make episodes qualify. An audit-only result
must not be presented as a trained model.

Use declared meal mass as a conditional input, preserving its approximate origin.
Do not jointly fit eaten grams, ISF and absorption from the same single curve.
If the existing historical source cannot supply qualified insulin/context,
return explicit missing-input findings; prospective capture needs a separately
reviewed source/schema contract. No collector may fabricate those inputs.
Do not add continuous history scans or a worker to each CGM callback.

This first stage changes no clinical forecast, COB, food command, target,
insulin, protection priority, settings or phone. It supplies the prerequisite
for the requested clinical integration, not a replacement for that outcome.

## Subsequent Model And Control Boundaries

Freeze earlier/later complete-day cohorts with an as-of cutoff and independent
labels. Compare baseline and candidate with the same calibration/reference,
input availability and sensor/model/config cohort. Report per-horizon MAE/bias,
lower-bound behavior, episode/day support, missingness and sensitivity to
approximate quantities. Existing research sample thresholds are not clinical
validation thresholds. Sparse, ambiguous or non-improving evidence retains the
baseline; do not invent precise GI or therapeutic confidence from a default.

Evaluate control safety separately. Replay of glucose under historical insulin
does not establish the outcome of a different target. Require an independently
reviewed closed-loop assessment, failure scenarios and clinically reviewed
acceptance criteria before any policy activation. This specification deliberately
does not assign patient-specific target percentages, doses or new clinical limits.

Once separately qualified, bind the clinical model to confirmed meal identities,
not UI drafts. Apply its curve consistently to known historical and future food
components and verify MealPressureResolver/Unified UAM avoid double counting.
Preserve declared grams, source AAPS COB and prescribed ISF/CR/basal parameters.
Derived residuals need distinct provenance; automatic gram writes or a new dosing
controller are not part of this target integration.

Evaluate baseline and candidate using isolated controller state. Diagnostic
comparison cannot mutate live PI state, safety latches, target journals or dispatch
rights. Target influence is a result of the existing controller's validated
forecast input, not an independent GI multiplier or a second therapy writer.

## Clinical Admission And Failure Behavior

- Existing OFF/SHADOW/ACTIVE, kill switch, arming, bounds, ownership, freshness,
  safety IOB, sensor/delivery, horizon-reliability and low-risk gates remain.
- Food influence is NORMAL_CONTROL. It does not get protective/manual priority
  or bypass throttling, journals, idempotency or managed command preflight.
- Confirmed Eating Soon retains its ordinary-target hold for 30 minutes. Actual
  protective hypo/sensor/delivery decisions remain above it; usual meal time does
  not prove food was consumed or bypass protection.
- Candidate forecasts cannot erase a baseline low-risk signal. The uncertainty
  admission rule needs independent calibration/control review, not arbitrary CI
  tightening. No universal safe correction percentage is assumed.
- Freeze model/cohort/input digest, configuration, calibration, meal revision
  and accepted forecast cycle/generation. Revalidate publication and dispatch
  against current evidence; changed inputs invalidate the old decision.
- If unavailable, use the baseline within the same safety/ownership policy.
  Failure is not authority to cancel another owner's or protective target.
- Unknown command outcomes never cause blind retries; SENT is not observed AAPS
  application. No direct bolus API is added, but a changed target still changes
  therapy indirectly through AAPS.
- Learning runs outside clinical callbacks; cached model use stays bounded and
  current CGM invalidation/queue cadence remains unchanged. CPU/RAM acceptance
  requires actual measurements, not an optimization claim from architecture.

## Verification And Release

First-stage tests cover canonical identity/provenance, independent insulin
coverage, missing fields, future leakage, chronology, conflicts, confounds,
bounded queries and no clinical writes. Room/source tests use synthetic data;
medical databases and raw reports are never published.

Later tests add consistent food/UAM attribution, unchanged display-only GI
invariance, separate diagnostic/live controller state, Eating Soon/protective
arbitration, stale revision/cycle rejection and restart/unknown delivery.
Full Android unit/lint/compile/assemble and exact-source CI remain code gates,
not clinical clearance. Device application/readback and a controlled opt-in
release follow only separately reviewed model and control admission.

## References

- [University of Sydney GI methodology](https://glycemicindex.com/about-gi/):
  standardized food/reference testing distinguishes GI from personal CGM response.
- [Faggionato et al., DOI 10.1109/TBME.2023.3324206](https://doi.org/10.1109/TBME.2023.3324206):
  research on appearance/retention/sensitivity from CGM and pump data supports
  investigating qualified meal-response models, not this implementation's safety.
- [OpenAPS Reference Design](https://openaps.org/reference-design/): complete
  treatment history, conservative handling and verified actuation are relevant
  design principles; no clinical validation is transferred to a new policy.
