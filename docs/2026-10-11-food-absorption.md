# Food-Profile Calculation Availability

## Defects

The diagnostic inference needs sensor-block evidence, but production wiring
loaded all telemetry from the start of the fourteen-completed-day window and
filtered the upper bound only after materializing it. Unrelated forecast rows
can dominate memory and prevent a useful diagnostic result.

The first diagnostic snapshot also depended solely on nightly work. Enabling
the profile could leave its automatic summary uncalculated until that worker ran.
Two source-wiring regressions reproduced these omissions before correction.
They establish source defects, not the exact cause of an unavailable phone's UI.

## Correction

- A time-bounded Room query retrieves only sensor-block rows. It preserves
  case-insensitive keys and text/double values with inclusive time bounds.
  Distinct key discovery uses the covering key index; matching rows use the
  existing key/time index. No new index, dependency or schema is required.
- Enabled/power-save transitions request unique initial work only when no
  snapshot exists. The existing diagnostic worker, KEEP policy, retry semantics
  and daily schedule are reused. No frequent timer or new therapeutic cycle.
- Completion audit follows saved/reused inference, never mere enqueue.

## Acceptance

| Surface | Verification |
| --- | --- |
| Data access | Native Room test excludes unrelated rows, preserves mixed-case/text flags and inclusive bounds, checks indexed query plan |
| Initial request | Missing snapshot, existing snapshot, disabled profile, power save, read/enqueue failure and cancellation |
| Clinical boundary | Existing inference, absorption, food-display/GI tests; replay influence remains disabled |
| Full software gate | Android unit, lint, Kotlin and APK; reviewed publication and exact-source CI |
| Phone | Requires a separately accessible device; current calculation, profile UI and restart are not established locally |

The automatic profile is a diagnostic estimate from eligible completed meal
windows, not a direct measurement of physiological absorption. Insufficient
history must not create a duration or unlock clinical influence. External COB
alone still cannot invent a meal curve or endpoint. No live therapy, alarm test,
personal-profile fabrication or claimed phone CPU/RAM reduction in this stage.
