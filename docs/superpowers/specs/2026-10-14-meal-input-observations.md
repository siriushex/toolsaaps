# Contemporaneous Meal Input Observations

## Decision And Corrected Basis

The accepted local engine is fed resolved CGM from prepareCalibrationRuntimeContext,
not necessarily raw readings. The preceding personal-candidate stage incorrectly
required a raw-cgm-v1 label without binding it to the frozen simulation input.
Fix this before trusting prospective pairs. LOCAL_ENGINE still excludes later
forecast biases/cloud adjustments; this does not mean its INPUT is uncalibrated.

GlucoseMeasurementBasis distinguishes UNKNOWN, RAW and CONTEMPORANEOUS_CALIBRATED.
The prepared resolved point and accepted calibration identity determine the basis.
Applied calibration must identify the same model; unchanged raw values require no
calibration model. Inconsistent or missing evidence remains UNKNOWN. Simulation
capture freezes basis and calibration ID. Candidate and accepted-runtime adapters
must match this basis; arbitrary raw retagging rejects. Existing hypothetical
meal timing is unchanged and can still operate without research basis admission.

Qualified historical episodes explicitly identify BOTH temporal availability
(contemporaneous vs retrospective) and measurement transform (raw vs calibrated).
The two are not interchangeable. Comparison cohort/digest v2 includes measurement
basis. Legacy bundles without it reject rather than inherit a default. Raw
evidence uses raw-cgm-v1 as the explicit no-calibration identity; calibrated
evidence requires an independently reviewed calibration identity.

## Passive Collection

Room34 adds meal_input_observations only. One immutable accepted-cycle receipt
holds generation/revision/digest, source/session/calibration, recorded zone,
actual measurement basis and a bounded typed payload. The payload preserves:

- Original raw CGM and the resolved point actually used by the local engine.
- Original insulin snapshot, its own evidence/sample time, source and metadata.
- Aligned insulin timestamp/reason separately; never substitute aligned values
  for an independently observed original sample.
- Accepted ISF/CR, source/confidence and known time; the Copilot insulin-kernel
  identity/duration, explicitly NOT an attestation of the AAPS insulin model.
- Sensor trust and quality as observed, without upgrading unknown context.

Capture runs AFTER clinical fan-out in the existing nonfatal one-second journal
budget. No second prediction, controller run, model fit, action or extra polling.
The kernel identity is read from the same owned engine cycle inside that
post-clinical nonfatal budget; there is no engine fork. Clinical/alert work does
not depend on journal success.
Payloads are typed selected values, not raw provider JSON, notes, credentials or
food photographs. No network/export path is added.

## Time And Durability

The observation clock means accepted-cycle observation, NOT original transport
arrival. No historical backfill, guessed receipt time or fabricated sensor ID.
An INSERT IGNORE cycle ID preserves the first write. A separate post-commit clock
finalizes it. Interrupted/rolled-back/future finalization remains unfinished;
retry cannot promote an old pending row or overwrite a completed observation.

Independent21-day retention deletes only this table's rows. Review uses a bounded
7001-row sentinel for a7000-row budget; overflow refuses partial counts. Stale
or unknown-quality CGM cannot count as usable. Repeated same-series observations
have one vote; a later stale replay cannot erase an earlier on-time observation.
Conflicting glucose or original insulin samples select no winner.
Context cohorts stay separate for glucose; original AAPS insulin times are
deduplicated independently of how often a cycle reused that snapshot.

## What Is Not Qualified Yet

Even a complete fresh AAPS_COMPONENTS snapshot is only a modeled activity sample.
It is not physical pump-delivery verification or independent full-interval
coverage. No multiplication by five minutes silently creates a covered insulin
interval; no gaps, illness/activity/review or insulin history are set to clean.
This follows the distinction in the official
[AAPS screen documentation](https://androidaps.readthedocs.io/en/latest/DailyLifeWithAaps/AapsScreens.html).

The review always reports zero qualified episodes and denies clinical influence.
It can count original source-valid samples and missing/derived/unknown/conflicting
inputs, but cannot emit training episodes, fit a model or change COB, grams,
forecasts, Eating Soon, targets or doses. A trustworthy interval/context review
producer and prospective/control-safety admission remain separate prerequisites.

## Migration And Verification

Migration33->34 creates one independent table and two indexes, with no backfill
or mutation of clinical/baseline/forecast-journal rows. Test native SQLite
migration/restart/integrity plus supported older migration tails. A schema33 APK
cannot reopen a migrated34 database: reviewed forward repair and fresh coherent
backup only, no destructive fallback or stale-data restore. This source stage
does not install, stop, disable or otherwise operate the phone.

Tests cover measurement transformation, frozen calibrated/raw candidate binding,
source-cohort separation, original vs aligned insulin, accepted-tuple mismatch,
first receipt, post-commit failure/cancellation, duplicate/conflict handling,
bounded invalid-row review, isolated retention, overflow and restart migration.
Full local unit/lint/compile/assemble precedes explicit source-only publication.
