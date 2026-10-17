# Meal Preparation And Automatic GI

## Scope

Restore a native preparation indicator and a bounded automatic GI **estimate**
in Food/Lenta. No inferred meal start, target, ISF/CR, insulin dose or clinical
COB change is authorized by these display values. Test-build permission cannot
establish clinical validity. The existing action and Eating Soon guards remain.

## Historical Findings

- `88b14bc7` introduced confirmed optional GI and a bounded display-only shape.
- `bb73dd62` connected independent same-type/portion/hour historical previews.
- `bde8a89a` contains the separate meal-timing research kernel, not an admitted
  live recommendation. Its unresolved calibration/control gates remain.
- The current Food dialog never supplies GI automatically. It only calls the
  manual parser with an empty string. The compact action uses a static icon.

## Automatic Estimate

Use coarse engineering type priors (FAST80, MIXED60, FAT_PROTEIN45), clearly
labelled model estimates, not measured food values. Qualified independent
USER/CATALOG history in the existing fourteen-day, +/-120-minute neighborhood
can shift the prior toward its median with weight n/(n+8), at most one half.
Type, portion, revision, availability, completed-day and source guards remain.
Generated estimates never become independent known-GI training observations.
An unavailable/overflowing history falls back explicitly to the type prior.

Use one estimate in the form, immutable confirmation, persisted exact meal
metadata, event details and display curve. Keep its MODEL_ESTIMATE source and
versioned reference separate from measured USER/CATALOG provenance. No lab GI
is derived from body profile, raw CGM slope, total IOB or apparent response.

## Preparation

A fresh positive IOB is a contextual observation, not evidence that a bolus
was for food. Require a recent explicit meal intent before food motion appears.
No decorative activity on stale, future-clock, low-glucose or stopped contexts.
Use the shared lifecycle/reduced-motion/power policy and a finite gesture; the
resting icon and text retain status without a perpetual frame loop.

Show a meal clock only when a future canonical AAPS meal entry supplies that
time, explicitly labelled as recorded/planned, not calculated safe readiness.
Otherwise show that start time is unconfirmed. No fixed prebolus delay or
"safe to eat" claim is reconstructed from IOB.

## Verification

Synthetic regression tests for source/restore/confirmation, history filtering
and self-training rejection; preparation intent/freshness/clock/low guards;
native compact/large-font light/dark layouts and finite motion frames. Complete
Android unit/lint/build and exact-head Verify before source stage completion.
No real photo, patient-data publication, therapy command or server request.

## Evidence Boundaries

[University of Sydney](https://glycemicindex.com/about-gi/) defines GI through
standardized food testing, not a personal CGM excursion. [ADA2026](https://doi.org/10.2337/dc26-S009)
states meal-insulin timing depends on formulation, premeal glucose and anticipated
carbohydrates. Neither source validates this app's engineering coefficients.
