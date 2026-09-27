# Guarded Sustained-Rise Target

The adaptive controller may propose 4.1 mmol/L for 30 minutes when current
glucose is above 8.5 mmol/L and distinct, good-quality CGM points prove a
continuous rise covering at least 10 minutes. Gaps must not exceed 5 minutes;
conflicting duplicates, stale or future evidence reject qualification.

Accepted 5/30/60-minute forecasts must have the same fresh issue timestamp,
finite ordered confidence intervals and lower bounds above 4.0 mmol/L.
Qualified IOB and configured bounds are required. Only ordinary PI/deadband
output may be replaced; existing protective controller branches take priority.

Target Manager remains the only writer. Its freshness, ownership, kill switch,
sensor/delivery trust, forecast reliability and low-risk protections remain.
The stricter configured low-risk threshold still applies. Holding 4.1 requires
the same safety checks as lowering a target. Blind keepalive is forbidden:
renewal needs a newly eligible same-cycle sustained-rise proposal.

Once evidence stops qualifying, the controller resumes its ordinary calculation.
Calculated-base restoration is not discarded when another target is active.
A small upward release may bypass duplicate cadence only when accepted state,
active AAPS target and the last successful command prove matching ownership.
Without communication the temporary target expires; immediate delivery is not
guaranteed. No additional polling, worker or therapeutic record type is added.

## Verification

Run `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` from android-app.
SustainedRiseTargetRuleTest and SustainedRiseTargetManagerTest cover trigger,
release, gaps, duplicates, stale/mismatched forecasts, low-risk holds, uncertain
delivery, renewal and ownership-restricted cadence. Simulated tests do not
establish clinical effectiveness or exclude future hypoglycemia.

## Release Boundary

This main-branch change does not import the unrelated meal-planner development
or its Room v27 migration. A phone already on schema 27 must not receive a
schema-26 APK. Such a phone needs a compatible, separately verified build;
record its exact SHA-256 and source lineage separately from this main commit.
No real therapy command is generated as an installation test.
