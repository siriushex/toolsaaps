# Explicit Eating Soon And Meal Submission

## Request And Reproduced Defect

The user reports differing positive/net IOB values, a raised active target despite
rising forecasts, and Eating Soon not taking effect when submitting food with
portion/GI context. A screenshot alone cannot identify accepted forecast intervals,
decision reason or actual AAPS delivery. ADB is unavailable during initial
investigation; synthetic reproduction cannot establish the phone's exact cause.
Public fixtures are synthetic; phone measurements and screenshots stay private.

Four regression tests reproduce a source defect:ManualMealSubmission attempts
Eating Soon only after successful carbohydrate transport AND profile persistence.
A carb block leaves the explicit target NOT_REQUESTED; profile failure marks it
BLOCKED without attempting the target. Message composition also unconditionally
claims Eating Soon was not sent when carbohydrate delivery is blocked/unknown.

## Decision

Treat the checked Eating Soon option as the same independent explicit user intent
already supported by the standalone button. Freeze/reserve the complete meal
input and retain its existing nonfatal research-intent observation. Attempt the
canonical guarded Eating Soon command BEFORE carbohydrate transport and profile
annotation. Carb failure/rate limiting or annotation failure must not erase that
target result. A rejected target does not manufacture or prevent the independently
requested carbohydrate record. Invalid form input still cannot submit a meal.

Preserve separate statuses for carbohydrates and Eating Soon. Until transport
starts, carbohydrates are NOT_REQUESTED; an interrupted attempt is UNKNOWN.
Mark target UNKNOWN before its callback. Cancellation propagates and repeated
same-input submission returns the recorded result without replaying either action.
Existing durable action-key/payload checks remain authoritative after restart.
Changed input cannot reuse an operation ID. Do not silently retry an ambiguous
target or carbohydrate request, or infer AAPS application from HTTP acceptance.

Compose both independent results in English/Russian even when carbohydrates were
not sent. Profile failure is reported only after submitted carbohydrates; it does
not relabel target success/failure. Existing sent-target text explicitly says that
application in AAPS is unconfirmed. No new notification, worker or polling loop.

## Priority And Safety Boundary

Eating Soon remains the canonical4.1mmol/L,30-minute explicit command through the
existing manual transport. OFF/SHADOW/ACTIVE manager modes do not turn it into an
automatic controller command. Existing arming, kill switch, bounds, CGM freshness,
calibration, chronology, trusted sensor and low-forecast checks remain mandatory.
The fix is not unconditional delivery when therapy is disabled or unsafe.

Confirmed AAPS Eating Soon keeps its existing30-minute window from observed
start; ordinary control/recovery/planned adaptation cannot overwrite it. Pending
manual intent still blocks queued ordinary manager writes through the existing
durable priority registration. Genuine protective paths remain available; this
stage does not weaken protections or change their qualification/priority rules.
Expiry resumes ordinary control without automatic Eating Soon keepalive/reset.

## IOB And Target Audit

The first dashboard quantity is effectivePositiveIobUnits=max(0,bolus)+max(0,basal).
The second is signed netIobUnits. With synthetic bolus4.0 and basal-1.5, these
are4.0 and2.5 respectively.
This is an illustrative component split, not recovered phone telemetry.
The immutable cycle carries both. Forecast residual is aligned signed net minus
already modeled active insulin, not the positive safety sum or a second complete
IOB subtraction. Target protective gating separately uses qualified fresh
positive IOB. Do not blanket-replace safety IOB with a smaller signed net number.

The official[AAPS screen documentation](https://androidaps.readthedocs.io/en/latest/DailyLifeWithAaps/AapsScreens.html)
distinguishes bolus/basal IOB and notes that reduced basal can make total IOB
negative. No source, physiological model or safety thresholds are changed here.

The controller already reacts to qualified rising forecasts. Target Manager can
decrease a raised target when a valid lower proposal has fresh qualified context. Its safety
gate rejects a decrease if minimum prediction/CI is at or below4.4mmol/L or
required horizons are unreliable; sensor, delivery, cadence, ownership and mode
can also block. A high mean alone is not proof that lowering is safe or that the
manager failed. Regression scenarios are not a replay of the screenshot.

## Verification And Deployment

TDD:observe the four new tests fail before the fix. Test independent statuses,
all food types/optional GI, duplicate/cancellation behavior, mocked HTTP manual
commands in every manager mode, safety refusal/no extra POST, exact30-minute
hold/expiry, signed-vs-positive IOB and explicit decrease/block scenarios.
Then full Android unit/lint/compile/assemble and exact-source CI before declaring
the source stage complete. No database migration, new permissions, direct bolus,
GI-driven target model, target tuning, phone stop/disable or installation.
