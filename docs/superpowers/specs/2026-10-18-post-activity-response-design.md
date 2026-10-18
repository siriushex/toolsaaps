# Post-Activity Response Review

Status: source audit and proposed observational design. No clinical activation.

## Decision

Treat unusual movement after several low-activity days as a reason to examine
the subsequent glucose response. It is not proof that glucose will rise. A
fixed automatic target of 4.1-4.8 mmol/L immediately after activity is not
admitted by this design. No new target, dose, ISF or CR adjustment is specified.

Activity-related hyperglycemia and delayed hypoglycemia can coexist. Illness,
food, insulin delivery, current IOB and measurement uncertainty can explain an
apparent response. The system must retain both upward and downward outcomes.

## Verified Source Behavior

- `domain/rules/AdaptiveTargetControllerRule.kt` detects immediate activity
  ratio/step changes and proposes protection. It has no multiday inactivity
  comparator. Recovery uses six low-load evaluations, not elapsed measured rest.
  The existing recovery test advances five minutes per evaluation, so it does
  not establish equivalent behavior at minute-rate or duplicate evaluations.
- Its activity metric resolver also accepts sensitivity-ratio aliases. Whether
  production normalization excludes these at every call site needs a separate
  end-to-end audit; autosensitivity is not evidence of physical movement.
- `domain/activity/ActualActivityEpisodeBuilder.kt` already builds bounded
  observed movement episodes from trusted physical-source buckets. Its end is
  the last supported bucket, not independently confirmed cessation of exercise.
- `domain/profile/ActivityTargetProposalFactory.kt` requires personal evidence,
  replay evidence and a same-cycle controller candidate for a LOWER proposal.
  These must not be replaced by an arbitrary nonempty evidence string. The
  source explicitly states production has no canonical lower-direction replay
  evidence. `domain/target/TargetManager.kt` also enforces clinical gates.
- `domain/rules/SustainedRiseTargetPolicy.kt` is a separate, existing response
  to an observed sustained rise. Its existence does not qualify a prospective
  post-activity decrease or justify bypassing protective target ownership.
- `data/repository/RoomAiHistorySource.kt` uploads activity counters but maps
  both local sensor and Health Connect origins to the general application
  origin. `backend/app/ai/history_contract.py::Activity` has no counter mode,
  reset/session identity, acquisition interval, wear evidence or reporting zone.
  Consequently existing server counter records cannot prove low total daily
  activity or the end of a walk. They must not be summed as interval increments.

These are source findings, not a reconstruction of a particular device episode.

## Input Contract

Reuse local physical-source selection and episode building. Extend the versioned
history contract before calculating a server-side personal baseline:

1. Preserve the original source, event/observed/received timestamps, quality,
   counter meaning (daily cumulative, session cumulative, interval total),
   counter reset/session identity, unit and local day/zone attribution.
2. Record the covered acquisition interval and known collector gaps. Collector
   availability is not proof that the phone was carried or a wearable was worn.
   Missing or ambiguous coverage remains unknown; missing samples never mean rest.
3. Resolve overlapping phone/watch records with one documented source-selection
   policy. Never add totals across sources or count repeated uploads twice.
4. Associate glucose with source, sensor/calibration cohort and quality. Preserve
   missing intervals, conflicting readings and change events.
5. Carry recorded meals, portion/GI provenance, insulin delivery certainty,
   current safety IOB, COB, insulin profile/DIA, ISF/CR source, active target and
   pump/sensor status. Unknown values do not become zero or normal.
6. Treat illness, recovery, medication and unusual exertion as explicitly sourced
   context. A low step count does not establish illness. A user illness tag is
   not a medical diagnosis, and an effective ISF change may be algorithm-derived.

Old archive records retain their original meaning. Do not silently backfill
missing coverage/provenance or treat a new upload schema as historical proof.

## Detection And Comparison

Use two separate comparisons so a short illness does not erase the usual baseline:

- recent observed activity on the preceding three completed local days;
- usual comparable activity over seven to fourteen prior completed days, with
  illness-tagged and recovery days shown separately.

Three days allow a descriptive comparison only. The number of valid days,
excluded dates and completeness must accompany each comparison. Compare the
same time of day, type of activity and supported duration; do not compare a
partial current day against complete prior days. A universally fixed step
threshold or a large ratio with an almost-zero denominator is insufficient.

Use robust within-person distributions of supported duration, cadence and
intensity. Calibrate candidate unusual-load thresholds on completed historical
episodes before assigning a prediction. With insufficient comparable history,
report an unusual observation and uncertain response rather than inventing a
personal probability. Recalculate daily, using only history available then.

Episode states:

1. `ACTIVITY_OBSERVED`: movement evidence is present; no future direction claim.
2. `END_UNCERTAIN`: movement stopped reporting without adequate rest evidence.
3. `POST_ACTIVITY_OBSERVATION`: fresh supported rest follows observed movement.
4. `RISE_OBSERVED`, `FALL_OBSERVED`, `NO_CLEAR_DIRECTION` or `INSUFFICIENT_DATA`:
   use serial fresh glucose observations, not one jump or upload arrival time.
5. `FOLLOW_UP_COMPLETE`: retain early response and later nadir separately.

Review glucose before, during and after the episode. Proposed analytical windows
are the first two hours and later follow-up through the next day, preserving
coverage for each window. These are research windows, not prescribed treatment
durations or validated physiological boundaries. Incomplete follow-up remains
censored. Do not train only on rises or discard later falls from a rising episode.

For each episode retain alternative explanations: recent meal/rescue carbs,
bolus/basal change or interruption, target change, measurement/calibration change,
ongoing illness, missing context and overlapping activity. Comparing a person's
episodes is observational evidence, not a causal estimate of extra insulin need.

## Display And AI

Use a compact observation row in the existing activity event details. Proposed
Russian labels are: "После нагрузки: рост", "После нагрузки: снижение" and
"Реакция пока неясна". Expanding it shows the interval, measured glucose change,
history coverage, comparable episode count and competing explanations.

Do not issue a new repeated audible alert solely because activity is unusual.
Existing glucose/delivery alerts keep their ownership. One event identifier owns
the observation; later readings update it instead of creating notification spam.

Keep detailed evidence in the device-bound server archive. The AI packet receives
the bounded episode summary, source references, comparable-day denominator,
missing fields and late-follow-up outcome. The existing compact glucose view
can preserve a detailed focus window around the episode. AI may explain supplied
facts and uncertainty; it must not infer a guaranteed rise, diagnose illness or
write treatment commands from step counts.

## Target Manager Boundary

The observational feature produces no `TargetProposal`. Keep existing manual
intent, confirmed Eating Soon ownership, low-glucose protection, source freshness,
delivery checks and target bounds. An observed rise can already enter the
existing forecast/controller through actual glucose measurements.

Any later therapeutic feature requires independent evaluation of predictive
value and controller effects, including delayed lows. Historical glucose under
the old controller cannot establish what would have happened under a lower
target. Offline replay is necessary for software checks but does not establish
clinical safety. A suitable clinician must review individual exercise/illness
management and target settings before clinical use. Test-build status does not
make an unvalidated automatic decrease safe.

## Implementation Order And Acceptance

1. Audit and test elapsed-time activity completion independently of evaluation
   cadence, duplicate calls, stale telemetry, clock reversal and missing samples.
   Verify production source provenance before changing control behavior.
2. Version and test source/coverage export and server decoding, including old
   record compatibility. Keep raw history and user/device isolation intact.
3. Implement the pure observational comparator and completed-episode evaluator;
   connect them to event details and bounded AI evidence, without a therapy path.
4. Run chronological evaluation: fit on earlier days and evaluate later days.
   Report usable/excluded episodes, coverage, rise/fall false alarms, missed
   events, warning lead time and calibration with uncertainty by subgroup.
5. Verify phone-visible evidence, deduplication and CPU/RAM impact before release.

Required synthetic cases include: ordinary walk after inactivity with a rise;
the same activity with a fall; early rise followed by a delayed low; exercise
without step evidence; no sensor/wear coverage; counter reset at midnight; a
timezone change; duplicated sources; a meal or pump interruption after exercise;
illness-tagged versus usual-day comparisons; stale/conflicting CGM; and repeated
evaluation of the same sample. None may create a new automatic low target.

## References

Source-audit verification on 2026-10-18: 46 existing tests passed across
`AdaptiveTargetControllerRuleTest`, `ActivityTargetProposalFactoryTest`,
`SustainedRiseTargetManagerTest`, `ActualActivityEpisodeBuilderTest`,
`PhysicalActivityTelemetryPolicyTest` and `ActivityEffectModelTest`. No failures,
errors or skips. This is not validation or implementation of the proposed
pattern; no application behavior changed in this documentation stage.

- EASD/ISPAD position statement, Diabetologia 2025, DOI
  [10.1007/s00125-024-06308-z](https://doi.org/10.1007/s00125-024-06308-z).
  Individual exercise responses vary and management depends on the expected
  response and insulin context. This does not validate a fixed automatic
  post-activity target for this application.
- ADA position statement, Diabetes Care 2016, DOI
  [10.2337/dc16-1728](https://doi.org/10.2337/dc16-1728).
  Post-exercise hyperglycemia must be considered alongside delayed low risk.

The engineering thresholds, outcome definitions and admission requirements above
are a proposed application design, not clinical recommendations from these papers.
