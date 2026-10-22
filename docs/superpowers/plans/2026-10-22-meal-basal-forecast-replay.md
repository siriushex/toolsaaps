# Basal Replay And Meal-Time Safety

User scope: integrate basal history, verify meal-time safety, then distribute a
tested APK through the existing updater. No AAPS migration is part of this stage.

## Implemented

- One immutable receipt shares exact interval convolution across every candidate.
  Both historical known inputs and future signed basal effects use the same
  frozen kernel and ISF. Fresh research residual fitting avoids double counting
  effects already present in observed glucose. Live estimator state is unchanged.
- Coverage extends before the earliest observed CGM interval by full kernel
  support. Reject gaps, late evidence, incompatible historical kernels, raw CGM
  gaps/quality failures, unsupported delivery and excess work without truncation.
- Physical and signed tails are distinct. Retain no-food, delayed-food and
  supplied future-insulin worlds; speculative future choices cannot alter history.
- Both absent-food hypotheses supply the earliest no-food risk, including after
  a message has been posted. Risk cannot be averaged away by meal probability.

## Evidence And Limitations

Focused148tests and release helper14tests pass. Frozen full Android quality passed
in8m25s:6708passed, six existing skips, no failures/errors. Real SDK TLS ran3.055s;
lint has zero errors/382existing warnings. Exact-head CI, final signature/retention
and public-byte verification remain release gates.

These tests establish software contracts, not patient-specific safe meal timing.
The historical interval input is still a declaration, not an attested upstream
delivery inventory. Historical variable ISF, clinical forecast validation,
simultaneous uncertainty and future AAPS response remain outstanding. The
notification/therapy gates stay closed; the existing persistent onset clock is
unchanged. Basal scenario results cannot become training labels or consumed food.

Clinical timing depends on insulin formulation, premeal glucose and anticipated
carbohydrates, not solely an onset parameter or threshold crossing. Reference:
ADA Standards of Care2026, section9, https://doi.org/10.2337/dc26-S009 . This
reference supplies clinical context, not validation of this implementation.

## Distribution

Version0.1.9/code10 uses the existing signing identity and static updater. Publish
only APK/page/manifest; retain0.1.8and its pointer for rollback. Do not restart AI,
copy credentials/databases, send therapy tests, or claim phone installation from
the download receipt. Notes must distinguish research changes from the live clock.
