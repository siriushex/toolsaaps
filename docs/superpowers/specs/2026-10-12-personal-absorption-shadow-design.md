# Personal CGM Absorption: Shadow Design

The user selected personal CGM learning and, after the linked concrete proposal,
requested implementation. This stage implements the proposal's diagnostic-only
first phase. It does not estimate laboratory GI, infer carbohydrate grams, alter
clinical COB/forecasts/targets, or register a worker/UI subscription. Standard
GI is a controlled food/reference measurement, not routine CGM reconstruction:
[University of Sydney GI methodology](https://glycemicindex.com/about-gi/).

## Inputs And Provenance

Use confirmed canonical real meal identity/revision and entered/corrected grams
through the existing MealPortionEvidence boundary. A prepared diagnostic input
also needs the recorded timezone, pre-meal ISF/CR and insulin-model identity,
independent insulin interval exposures, CGM and explicit context review.
Unknown sensor/calibration/delivery/activity/illness/other-food context rejects
the episode. Known faults, rescue, overlap, hypo and unconfirmed/synthetic meals
are excluded. Connectivity cannot prove pump attachment.

The input adapter requires 45 minutes of pre-meal context and observation through
the maximum candidate duration. Five-minute intervals need >=90% coverage and
no CGM gap >10 minutes. Linear CGM interpolation is diagnostic, not new sensor
data. Require independently covered insulin intervals without overlap, no
inferred IOB boluses, and original availability timestamps. Missing values do
not become zero. Historical context must be known by meal start; no current
settings substitution or post-meal CGM-derived meal-time alignment.

Current glucose rows lack ingestion time and historical runtime summaries do
not prove independent five-minute exposures/profile/timezone history. A bounded
read-only Room audit therefore reports these gaps rather than fabricating an
eligible input. Fitting accepts independently prepared evidence only. No schema
or runtime capture is introduced to disguise missing historical evidence.

## Estimation

Reuse MealAbsorptionCurve: cumulative candidate fraction `F(age; type,D)^p`.
Fit timing only with p=0.75..1.25 by 0.025 and D on the existing five-minute
profile grid: FAST 30..60, MIXED 60..180, FAT_PROTEIN 180..360. Preserve declared
mass, monotonicity and endpoints. Limits are engineering priors, not measured
physiological completion. A fitted boundary remains explicitly flagged.

For each interval, residual glucose change is observed change plus the modeled
insulin effect correction minus frozen pre-meal background. The background is
the median of corrected pre-meal five-minute deltas, not an adaptive post-meal
trend. Keep negative residuals. Compare candidate food contribution using fixed
pre-meal ISF/CR with Huber loss (delta0.3 mmol/L per five minutes). Do not reuse
UAM, post-meal residual/trend or the model's own outputs as labels.

Each meal contributes its average interval loss, so long meals do not dominate.
Near-equivalent candidates within max(1e-6,1% best loss) define a parameter
envelope; wide envelopes are ambiguous. Shrink learned timing toward the type
prior by n/(n+8). These diagnostics have no clinical accuracy calibration.

## Cohorts And Validation

Use 21 completed local days. Query by explicit type, portion, recorded timezone
and circular +/-120-minute local meal-time neighborhood. Reject conflicts,
including tombstones, without input-order selection. Split by whole chronological
days: last max(2,ceil(days/3)) days held out, earlier days training. Require eight
training meals over five days and three held-out meals over two days. Training
outcomes/availability must precede the holdout start. Never tune on holdout.

Report mean per-meal delta MAE, signed bias, robust loss, per-day paired deltas,
support, rejection counts and parameter envelope. Compare the exact same
exposures for baseline/candidate. A 5% held-out delta-MAE improvement is an
engineering diagnostic status, not a clinical benefit or activation gate.
No gain, ambiguous shape, bounds and insufficient support are distinct results.
All scores are retrospective exposure-conditioned reconstruction. A prospective
forecast score is unavailable in this stage; future recorded insulin/outcomes
cannot be presented as real-time prediction accuracy.

## Resource And Output Boundary

Explicit offline execution only. Caps:5000 meal candidates,240 prepared meals,
10000 CGM points,30000 insulin rows. Overflow returns a reason, not a selected
partial sample. Domain result includes version, evidence cutoff/digest, support,
candidate and uncertainty, never USER/CATALOG GI or therapy authority. The Room
audit returns missing-source counts early, without loading unrelated telemetry.
An optional JVM diagnostic tool supports a prepared private JSON bundle or a
read-only coherent SQLite-copy audit. Neither is registered with the app.

Tests are synthetic. Real database files, JSON evidence and reports stay private.
Full Android tests/lint/Kotlin/build gates apply. Device installation and later
display integration remain separate approvals. Later visual use must define
freshness, known-GI precedence, full horizon and uncertainty without changing
clinical COB or stacking known GI and learned shape.
