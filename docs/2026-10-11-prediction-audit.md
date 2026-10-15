# Glucose prediction: time integrity and refinement design

Date: 2026-10-11. Scope: local prediction and retrospective evaluation. Patient
databases, individual outcomes and private analysis artifacts are not published.

## Current execution path

`AppContainer` constructs `HybridPredictionEngine` with enhanced V3 enabled.
`AutomationRepository.buildForecastRuntimeContext` uses local control forecasts.
The current call supplies no cloud forecast gateway. The accepted output is
5/30/60 minutes; recalculation on a new current CGM observation already uses the
durable clinical-input coordinator. Faster input scheduling alone cannot fix
model inertia, uncertainty or forecast quality.

1. Resolve the current calibration, source priority, trusted sensor state,
   therapy history and shared sensitivity/insulin contexts. Freeze the cycle
   clock and preserve the separate CGM observation timestamp.
2. `Glucose5mCanonicalizer` builds a bounded six-hour, five-minute series anchored
   to the latest eligible glucose. Minute input can move this grid. Small gaps
   may be interpolated; large gaps must not become invented observations.
3. The physiology kernel projects event-specific insulin and announced food
   using the selected insulin profile, bounded DIA/onset and resolved ISF/CR.
   Events after the prediction origin are excluded. Synthetic exported UAM
   treatments are not announced carbohydrate ground truth.
4. `RevisionAwareKalmanFilter` separates known therapy input from residual rate.
   Genuine historical revisions replay the filter and clear learned residuals;
   validated source appends preserve residual history across moving-grid replay.
5. Unified UAM and announced food share `MealPressureResolver`. Overlap is bounded
   rather than summed blindly. Active forecast UAM suppresses positive residual
   trend. Historical `uamDelta` is currently zero; the existing historical input
   series models insulin plus announced carbs, not an independently learned UAM
   trajectory. This distinction matters for attribution.
6. `ResidualArModel` projects unexplained dynamics. For future step j:
   `r[j] = mu + phi^(j-1) * (r[0] - mu)`. With insufficient history, mu is zero
   and phi uses the existing 20-minute decay half-life. Learned mu is bounded;
   a nonzero mu can continue a prior tendency after the latest residual changes.
7. The engine integrates trend and resolved therapy in five-minute steps, applies
   the existing bounds and emits separate horizon envelopes. The envelope mixes
   volatility, missing-interval penalty, UAM, rate, filter variance and AR error.
   Its label is not evidence of a calibrated 95% prediction interval.
8. Runtime post-processing applies recent residual calibration, contextual
   factors, COB/signed residual IOB and circadian information. Sensor-lag and
   activity alternatives follow their existing active/shadow settings. One
   accepted forecast set is then consumed by UI and Target Manager.

Source owners: `domain/predict/HybridPredictionEngine.kt`, `ResidualArModel.kt`,
`RevisionAwareKalmanFilter.kt`, `Glucose5mCanonicalizer.kt`,
`MealPressureResolver.kt`, and `data/repository/AutomationRepository.kt`, under
`android-app/app/src/main/kotlin/io/aaps/copilot`.

## Reproduced defects and this stage's changes

### Residual learning across absent observations

The old AR history stored five-minute bucket keys but fit their values as an
unbroken sequence. A missing bucket therefore created a false five-minute
transition and retained a pre-gap mean. An engine-level regression reproduced
different warm/cold predictions after a long observation gap.

The history now restarts when an accepted observation skips a bucket. Existing
zero-mean decay is used until eight consecutive populated buckets are available.
Updates inside a bucket replace its sample; repeated calls do not inflate sample
count. Obsolete/non-finite samples cannot corrupt the current history. Simulation
copies retain the chronology independently. All coefficients, continuous-history
weights, physiology bounds and UAM constraints are unchanged.

The enhanced model is identified as `local-hybrid-v3.1`. Existing exact-version
reliability matching must collect evidence for this version; old version results
must not be relabelled as its validation. Sparse-cycle behavior intentionally
uses fallback until continuous evidence exists. This can reduce learned trend
contribution after gaps; it is not proof of improved patient outcomes.

### Incorrect reconstructed horizon clock

The missing-30m helper used `max(target timestamps) + 30m`, producing a target at
origin+90m when the anchors were 5m and 60m. It now derives both origins with the
existing checked subtraction helper, requires exact agreement, and places the
fallback at origin+30m. An unrelated longer horizon cannot move it. A complete
native forecast set is unchanged. The existing fallback value/width calculation
is retained; it is not a substitute for a calibrated native horizon.

### Premature retrospective scoring

The UI evaluator could use a last CGM sample as the outcome of a still-future
forecast through nearest-neighbour tolerance. It now receives an explicit
evaluation clock, ignores future CGM and refuses outcomes outside the observed
window. Exact observed endpoints and existing interior interpolation remain.
Binary search replaces repeated full-history matching scans.

This evaluator still receives the UI's sanitized raw CGM history, while forecasts
can use calibrated CGM. Fixing maturity does not resolve that reference mismatch.
Runtime calibration/reliability uses a different resolved-glucose path. A future
shared evaluation contract must explicitly distinguish raw CGM, contemporaneous
calibrated CGM and meter reference rather than silently mixing them.

## Prioritized algorithm work

| Priority | Question | Concrete experiment / completion criterion |
| --- | --- | --- |
| 1 | Does short-horizon inertia delay a genuine reversal? | Replay frozen inputs through stable-trend and faster-response candidates. Measure reversal delay, 5m error, noise-induced false reversals and lower-tail errors. Change only the residual component; retain sensor-quality gating. |
| 1 | Does learned residual mu overextend old direction? | Compare current bounded AR with zero-mean damped residual and change-point-reset candidates on identical episodes. Retain a candidate only with held-out improvement and no low-event regression. |
| 1 | Are physiology and scalar adjustments overlapping? | Paired ablations of announced food versus COB adjustment, and sensitivity context versus direct context bias. Capture every intermediate forecast from the same immutable inputs. Removing a term without this comparison is not justified. |
| 1 | Is reported horizon reliability meaningful? | Stable algorithm/config/input identities, fixed reference type and matured cohorts. Compare MAE/RMSE/bias and paired skill over persistence separately at 5/30/60m. |
| 2 | Is uncertainty calibrated by regime? | Fit signed residual quantiles on earlier data, evaluate coverage and width on later days. Separate meals/UAM, exercise, sensor changes and gaps with minimum coverage and conservative sparse fallback. |
| 2 | Do slow meal tails or missing delivery explain errors? | Compare accepted food/insulin trajectories with contemporaneous authoritative observations; preserve unknown delivery and meal-onset uncertainty. GI display adjustments are not clinical coefficients. |
| 2 | Does a circadian prior help during rapid change? | Same-episode with/without prior comparison, including existing acute attenuation. Do not compare unmatched model-label populations as causal evidence. |
| 3 | Can equivalent accuracy use less work? | Measure prediction and evaluation cost with a fixed replay. Bound history, reuse accepted contexts and preserve cancellation. Do not reduce input/safety processing cadence to improve CPU numbers. |

### Reversal candidate design

A new CGM point requests the ordinary full recalculation. Detection uses causal
short and longer rates plus the known-input residual and sensor quality. One
opposite noisy point increases uncertainty; a supported change allows the fast
candidate to attenuate historical drift. Food and insulin projections remain
separate. No direction flag by itself grants permission to lower a target.

Candidate thresholds and weights are intentionally not activated in this stage.
Choose them on training episodes, then freeze them before later validation.
Report an explicit tradeoff between response delay and false transitions. A
faster curve that misses lows or chases sensor noise is not an improvement.

### Honest time and data provenance

Keep separate `inputObservedAt`, `computedAt`, `publishedAt`, and each `targetAt`.
Current forecast rows retain target/horizon/model label, but not a complete
build/config/input/calibration identity or publication timestamp. Inferring origin
from target minus horizon is useful for audits, not exact issuance proof.
Therapy received after the CGM origin must not be retroactively inserted into
an earlier-origin forecast. Supporting a computation-time origin requires an
explicit state propagation design and updated acceptance contracts.

Preserve already-written forecast vintages. Cohort selection must precede
outcome matching; never select a later revised forecast because it looks better.
Do not reconstruct old predictions using today's settings or calibration.

## Validation contract

- Reference: declare raw/calibrated CGM or meter explicitly. Require valid observed
  timestamps by the evaluation clock; document matching tolerance and exclusions.
- Split: train, validate and test chronologically by whole days/episodes. Keep
  overlapping forecast windows together. No random split of neighbouring rows.
- Baselines: persistence, bounded damped trend and current production engine.
  Use identical eligible cohorts and inputs, including failures and exclusions.
- Numerical outcomes: horizon N, MAE, RMSE, signed bias, P90 absolute error,
  prediction-interval coverage/width, and paired differences from baselines.
- Dynamics: rising/falling reversals, stable periods, meals/UAM, exercise,
  missing data, calibration changes and uncertain delivery. Report subgroup N.
- Event outcomes: missed/false low warnings and lead time by episode. Point-level
  threshold matches cannot stand in for alarm performance or audibility.
- Uncertainty in comparisons: block by day/episode, not independent bootstrap of
  correlated five-minute rows. Require enough data before promoting a candidate.
- Forecast validation and target-policy validation are distinct. Recorded future
  insulin/food can affect realized outcomes; changed-target counterfactuals need
  a controller-aware evaluation, not merely the old realized CGM curve.
- Device acceptance follows source checks: latency, accepted tuple consistency,
  restart/gap behavior and resource use. No clinical gain is claimed by a build.

## Research context

[OpenAPS reference design](https://openaps.org/reference-design/) motivates
inspectable predictions based on glucose, insulin history and configured
physiology, with conservative behavior under conflicting or missing information.
It does not validate Copilot's implementation or authorize changing its guards.

[GLYFE](https://arxiv.org/abs/2006.15946) evaluates personalized prediction models
at multiple horizons with both numerical and clinical-acceptability metrics.
Its reproducible comparison approach supports the evaluation contract above;
published results from its datasets are not transferable performance estimates
for this app or this user's history.

## Verification

The original production behavior reproduced 11 failed assertions in 26 focused
cases. The expanded relevant suite then passed 315 cases after correcting a
legacy drift fixture to use actual five-minute updates; sparse ten-minute input
has separate fallback coverage. Additional explicit-clock and boundary tests
cover the evaluator. Final full Android and publication results are recorded in
`AI_NOTES.md` after completion. No schema, settings default, therapy transport or
device installation is changed by this stage.
