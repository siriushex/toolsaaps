# Meal Delivery Semantics

Continue from main8c5bbc38. Inspect main CI, then close delivery-form gaps in the
existing on-demand meal research evaluator before considering clinical admission.

1. Reproduce omitted insulin payloads and non-instant delivery treated as a bolus.
2. Audit research inventory independently of the legacy predictor's type filter.
   Reject extended, basal, emulated, ambiguous and malformed delivery forms;
   retain a bounded reason summary without raw records or identifiers.
3. Keep unknown delivery duration from expiring under an instantaneous kernel.
   Recorded SMB remains insulin exposure, never a meal-intent anchor.
4. Run focused tests, complete Android test/lint/compile/build and actual stock
   RC1 TLS interoperability. Review source, publish a feature PR and verify CI.

No live forecast, durable onset clock, hypoglycemia alert, target, dose, AAPS,
phone or server modification. Do not turn on a recommended meal time solely on
software test results. Remaining admission evidence includes authoritative
upstream history coverage, historical profile/sensitivity, future control and
prospective simultaneous forecast validation. Local consistency and a Nightscout
record are not proof of physical delivery or complete pump history.

Status: six failures reproduced before their fixes. Final focused suite324passed.
Full Android quality passed in15m2s:6735passed, six existing skips, no errors or
failures. Actual pristine RC1 SDK TLS ran3.631s; lint zero errors and382existing
warnings. Frozen source hashes stayed unchanged. Main CI39849796709 passed all
three jobs. Reviewed feature PR and exact-head CI remain to be published/checked.

The native SDK's TreatmentMapper and NSExtendedBolus separate duration,
isBasalInsulin, emulated delivery and per-record insulin configuration. This
stage rejects unsupported forms, not estimates their delivery from a bolus.
Existing AAPS4_CLINICAL_PROFILE_CONTRACT.md remains the integration contract:
source-bound coverage/revision/invalidation and historical insulin context are
needed before a locally usable inventory can become a clinical readiness claim.
