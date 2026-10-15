# Sustained Rise Target

Approved scope: glucose above 8.5 mmol/L and at least 10 minutes of continuous
observed rise may propose 4.1 mmol/L for 30 minutes. Stop selecting this proposal
as soon as rise ends or evidence fails; recompute the ordinary adaptive target.
Keep all existing safety decisions and the Target Manager single-writer path.

Implementation boundaries:
- Use calibrated RuleContext history and accepted 5/30/60-minute forecasts.
- Require distinct ordered samples covering 10 minutes, gaps at most 5 minutes,
  OK quality, positive interval changes and a fresh last sample. Duplicate copies
  are not extra evidence; conflicting duplicates reject.
- Require finite ordered confidence intervals above 4.0, fresh matching forecast
  issue times, qualified cycle IOB, and configured bounds permitting 4.1.
- Only override normal control output, never hypo/activity/IOB safety output.
- A dedicated proposal owner identifies this episode; no blind keepalive without
  a newly eligible proposal. Recheck reliability, sensor/delivery trust and low
  risk even when the requested target equals the active target.
- Replacing the episode with a higher calculated target must not be suppressed
  merely because that target equals base or differs by less than normal cadence.
  Any cadence exception is restricted to confirmed ownership and upward release.
- No new background polling or therapy writer. No phone installation in this
  side task; no live test carbohydrates, insulin, calibration or target commands.

USB observations on 2026-09-27, Asia/Tbilisi:
- ACTIVE decision at21:48:18 sent5.7; AAPS canonical target at21:48:35 is103mg/dL
  (5.72mmol/L), ending22:18:35.
- That decision had base6.7, IOB3.61, ordinary minimum5.7 and aggressive-rise
  eligibility false. Existing aggressive branch requires IOB<=1.5 among other
  conditions. This is not proof that a lower target is clinically safe.
- Forecast issued21:54: 5m10.91 [10.13,11.69], 30m10.28 [8.14,12.42],
  60m8.82 [5.74,11.90]. These are forecasts, not future measurements.

Verification: focused rule and Target Manager RED/GREEN tests, full Android unit
suite, APK and lint. Parent meal-simulation files must remain untouched.

Verified 2026-09-27T18:14:13Z:
- Rule RED: 5 tests, 3 failures before implementation. Manager RED: 5 tests,
  4 failures before implementation. Focused GREEN then passed.
- Full `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` exited 0.
  XML totals: 4604 tests, 0 failures/errors, 3 skipped; includes concurrent
  parent work, not a count of tests added by this change.
- New regression classes: SustainedRiseTargetRuleTest (7) and
  SustainedRiseTargetManagerTest (5), all passed.
- Lint XML: 0 errors, 305 warnings, 4 hints. Existing warnings were not cleaned
  up as part of this scoped task. `git diff --check` passed.
- Evidence: android-app/app/build/test-results/testDebugUnitTest/TEST-*.xml
  and android-app/app/build/reports/lint-results-debug.xml. Build evidence is
  mutable because this worktree is also used by the parent task.
- No APK installation or live target command. Clinical effectiveness and
  real-device delivery of the new rule have not been validated.
