# Personal Meal Forecast Research

## Decision

Continue the approved qualified-absorption / prospective-forecast route with an
isolated candidate, not direct GI percentages or experimental applied targets.
Existing future meal-timing scenarios intentionally cannot change past food or
learn historical trend from hypothetical future actions. Do not repurpose them
for a personalized historical meal curve.

## Qualified Model

PersonalAbsorptionResearchModel.fit accepts a frozen qualified source snapshot,
the matching cutoff/zone/request and an explicit sensor/calibration/insulin-model
cohort. The existing complete21-day, circular two-hour, type/portion selection is
shared with GI analysis. Unknown independent insulin/context/availability is not
filled from legacy therapy. At most240 qualified episodes and5000 GI labels are
accepted. Whole-day training/holdout separation and the existing minimum8 meals /
5 training days and3 meals /2 validation days remain unchanged.

Only an EVALUATED, non-ambiguous timing fit becomes an immutable research model.
No support or no held-out improvement means no artifact. The optional known-GI
term additionally needs an EVALUATED timing fit, no search-boundary coefficient,
and improvement over both personal and type models at every5/30/60 horizon.
Otherwise the explicitly versioned timing-only artifact is used. An accepted GI
artifact cannot silently fall back when the current meal lacks a valid known GI
or lies outside the training range. GI labels remain independently known USER or
CATALOG attributes, never labels inferred from the response being scored.

The artifact fixes its request, cohort, shape, optional GI model, evidence digest
and completion availability. Its conservative training-through time is the
source cutoff, including held-out evidence used in admission. These conditional
response scores do not establish prospective glucose accuracy or safe control.

## Disposable Engine Calculation

forecastPersonalMeal substitutes exactly one confirmed canonical revision with
the same timestamp, grams and resolved food type. Deleted, synthetic, rescue,
conflicted, repeated, future or mass-mismatched evidence rejects. The source
engine is never modified and no persistent override setter exists.

The same personal cumulative curve feeds historical known food inputs, current
UAM attribution, future announced food, residual COB and Kalman revalidation.
The existing revision-aware Kalman replay resets residual AR history whenever
the known inputs change. Tests populate AR history before proving that reset.
Other meals and the insulin kernel are untouched. No separate GI food-pressure
term is added; existing UAM/announced-food reconciliation remains the single
food-pressure path. The display-only GI curve stays independent and unchanged.

The frozen effective insulin-kernel identity includes the action profile/points,
age scale and onset shift. Changes cannot reuse a differently qualified cohort.
Update the kernel identity version if its cumulative algorithm changes.

## Prospective Candidate

PersonalMealCandidateBuilder uses a frozen MealSimulationContext on demand.
Only LOCAL_ENGINE and the explicit raw-cgm-v1 measurement basis are supported;
an adjusted ACCEPTED_CONTROL baseline or its calibration/CI cannot be borrowed.
The exact anchor/cycle/settings/local model version, zone, source and reviewed
sensor/calibration/insulin cohort must match. Current meal type/portion and
circular hour remain binding. Current declared grams must stay within the
training mass range; matching a portion label cannot justify mass extrapolation.
Known GI is revision-bound and available by the
anchor; guessed MODEL values and extrapolation reject.

Model selection and input occurrence cannot pass the anchor. Model availability
must precede calculation; input availability is conservatively frozen runtime
capture, not original CGM transport receipt. Start/completion must follow capture
and finish before the first5-minute outcome. Clock rollback or late completion
rejects. The existing MealProspectiveFrame gate rechecks the candidate. The caller
must then use MealProspectiveCapture.register against the same authenticated
accepted runtime tuple, retaining a trusted pre-outcome receipt. Caller metadata
and a hash alone cannot attest original observation, calibration or review.

Candidate intervals are null. Existing engine heuristic intervals have not been
calibrated for the new model. Candidate versions/evidence/context stay separate
in the existing paired evaluator; future outcomes cannot tune a registered fit.

## Scope And Remaining Admission

No AppContainer registration, runtime subscriber, background worker, journal
schema change, clinical forecast replacement, target writer, phone operation,
meal-mass overwrite or therapy command. Baseline journal behavior is unchanged.
Next work is a trustworthy qualified evidence producer and pre-outcome candidate
collection, observed prospective evaluation and independent control-safety
assessment. These tests do not establish clinical accuracy or allow activation.

GI is not identified from age/height/weight or arbitrary compensated CGM alone.
The distinction follows the standardized food measurement described by the
[University of Sydney GI service](https://glycemicindex.com/about-gi/). Algorithm
verification and clinical/control evidence are separate, consistent with the
[FDA description of artificial-pancreas controllers](https://www.fda.gov/medical-devices/artificial-pancreas-device-system/what-pancreas-what-artificial-pancreas-device-system).

## Verification

- Exact default-path reproduction; future meal-timing semantics retained.
- Historical/future food and remaining mass agree with the same personal curve.
- Populated old residual history resets; repeated forks are deterministic.
- Other meals, insulin, display GI, UAM reconciliation and source state retained.
- Qualified timing/GI fits, day split, sparse/unknown rejection and no extrapolation.
- Local/raw identity, version/context/meal/time/availability gates, absent CI.
- Full local unit tests, lint, Kotlin compilation and debug assembly before push.
