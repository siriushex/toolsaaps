# Minute Forecast Test

## Contract

Settings > Advanced > Debug > Forecast test starts an explicit one-hour session.
It is off by default, survives screen navigation, and does not survive process
restart. The switch or the silent foreground notification stops only this test.
There is no settings migration, new permission, server request or AAPS change.

The existing foreground owner runs a complete local `FORECAST_TEST` cycle every
60 seconds. The ordinary five-minute local-service cycle, fifteen-minute remote
worker and input-driven recalculation retain their existing cadence. A test-only
service is not an ordinary-cycle owner: when both local sources are disabled,
the worker must continue normal sync, evaluation and alerts. A minute attempt
skips if the shared calculation lease is busy; missed
slots do not queue or catch up in a burst. Each attempt has a cooperative 45-second
timeout. Status distinguishes accepted recalculation, busy/rejected input,
failure, timeout and termination. Completed count and last attempt duration are
visible in settings.

Normal, input-driven and settings-change callers register priority before taking
the shared lease. They cancel and join an in-flight test attempt; pending normal
callers keep new test attempts out. Preemption skips the attempt, not the session.
This prevents periodic phase alignment from starving ordinary calculation.

The test uses the existing bounded, full forecast pipeline: calibrated glucose,
therapy history, IOB/COB, insulin and meal/GI context, accepted ISF/CR, activity,
sensor quality, circadian history and forecast-error calibration. The latter's
five-minute cache is invalidated under the calculation lease for test cycles.
Input history windows, physiological coefficients, admission gates and forecast
horizons are not expanded or invented. Existing ingestion continues to feed the
local database. The test does not independently poll remote sources or retrain
and activate new models every minute.

Accepted results use the existing atomic publication and UI/widget path. Extra
cycles cannot send targets, carbohydrate exports or other therapy commands,
publish duplicate alerts, auto-activate sensitivity models, or run normal
maintenance. Ordinary armed automation and alerts retain their existing policy.
This is a computational test of the existing model, not an independent clinical
algorithm or a new medical recommendation.

## Resource Boundaries

- No artificial CPU burn or fixed 100% utilization target. Useful calculation
  may use available CPU; no higher priority, thermal bypass or new parallel fit.
- Monotonic one-hour deadline, sampled together with admission every second.
- Stop on severe Android thermal status, Android battery saver, Copilot power
  saving, disabled monitoring, or loss of the foreground owner.
- A timed partial wake lock exists only during the explicit session and is
  released in `finally`; expiry also bounds an unexpected release failure.
- OS termination, device sleep/scheduling and non-cooperative library calls can
  still prevent an exact real-time deadline. No 100% delivery promise.
- The normal mode does not own this wake lock or run the additional loop.

## Verification

Synthetic virtual-clock tests cover cadence, single ownership, cancellation,
expiry, thermal interruption, power saving, timeouts, failed/skipped attempts,
restart isolation and no catch-up. Policy tests cover all arming/kill-switch
combinations and publication without extra therapy writers. Notification tests
exercise the actual Android notification and stop action without sound.

Run focused tests, the full Android unit suite, lint, Kotlin compilation and APK
assembly, then exact-head CI and review. On-device verification remains separate:
enable the test explicitly, check navigation/background status, stop from the
notification, and compare CPU/battery and mature 5/30/60-minute forecast errors.
More computation is not evidence of better accuracy; do not label it an accuracy
improvement without prospective comparisons on held-out outcomes.
