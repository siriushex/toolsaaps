# Compact AI Context

Status: written format approved by the owner; pure first-stage implementation
is documented in ../../COMPACT_AI_CONTEXT.md. The opt-in authenticated CHAT
adapter and encrypted per-answer receipts are documented in
../../COMPACT_CHAT_ADAPTER.md. Actual host/token admission remains separate.
The owner explicitly selected model-context-only compaction. Phone collection,
phone-to-server upload, original encrypted archive, revisions/deletions and
retention remain unchanged. This design does not activate a native conversation,
nightly model call, new clinical estimator, target or dose writer.

## Decision And Alternatives

1. Selecting every tenth observation is small but can completely miss a short
   excursion, measurement conflict or gap. Reject this approach.
2. Fixed ten-minute statistical buckets preserve more evidence and suit ordinary
   three/seven-day single-source requests. Long or multi-source requests can
   still exceed the available context budget.
3. Recommended: ten-minute buckets plus exact events, source-derived episode
   facts and question-specific detail, with disclosed multiresolution background
   reduction only when necessary. The archive remains the source of detail.

The target is an analysis-preserving bounded view, not lossless reconstruction
of every raw sample and not a claim that an LLM considered all records correctly.

## Glucose Representation

Compute descriptive metrics from original eligible observations first. Do not
calculate exposure, duration or variability from a decimated/rounded curve.
Reuse daily_glucose continuity, cohort, range, coverage and uncertainty rules;
do not change clinical thresholds or fill unknown range/sensor/calibration data.

Use half-open UTC time bins and explicit reporting timezone. Ordinary background
resolution is ten minutes. A compact series declares start time, bin width,
source/cohort reference, column names and value scale once, instead of repeating
field names, epoch timestamps, source strings and units in every sample.

Proposed seven columns:

`[n, covered_s, first_q, last_q, mean_q, min_q, max_q]`

- n counts unique compatible observed timestamps, not duplicated aliases.
- covered_s is supported duration, not n times the sensor sampling interval.
- first/last/min/max summarize observed usable values; mean is the supported
  time-weighted mean. Rounding to integer hundredths is representation only.
- Missing bins remain explicit; absent values are null, never zero glucose.
- Unsupported duration, conflicting timestamps, suspect quality and changed
  source/session/calibration break continuity. Do not join incompatible cohorts.
- Align raw boundary guards and analytical ranges using existing integration
  rules. Never interpolate a bin merely because observations exist on both sides
  of an unsupported gap. No extrapolation beyond supported data.
- first/last indicate direction, not a normalized rate. A detailed rate or onset
  analysis needs original observation times, not an assumed ten-minute slope.

Retain exact event times and source-derived exception windows separately:
recorded out-of-range episodes under known historical analysis ranges, observed
extrema, fast changes under existing detection rules, measurement conflicts,
gaps and sensor/calibration/source changes. Preserve extrema times and coverage.
If an analytical range is unknown, do not invent an out-of-range classification.
Device-declared suspect lows remain uncertain observations, not confirmed hypoglycemia.

## Resolution Selection

- Prefer ten-minute bins for the requested period when the complete packet fits.
- Reserve requested dates/episodes at ten-minute or explicitly requested finer
  resolution before reducing unrelated background. A question about an old meal
  is not answered using only recent high-resolution data.
- For an ordinary fourteen-day overview exceeding the glucose allocation,
  retain the latest seventy-two hours at ten minutes and earlier background at
  thirty minutes. Older selected background can reach sixty minutes if needed.
  Every segment declares its actual width and supported duration.
- Meal/insulin-response, short-excursion or calibration investigations may need
  one-to-five-minute snippets from the original archive. Their selection and
  omitted detail must be explicit. Ten-minute buckets cannot justify precise
  insulin-onset or recommended meal-delay minutes.
- Coarsening never changes deterministic full-window metrics or the counted
  source-derived episode inventory. Do not imply that a bounded episode list
  contains every detailed episode when its omissions counter is nonzero.

## Other Domains

Keep one latest eligible user/therapy profile and its relevant version changes,
with effective/observed time, units and source. Do not repeat unchanged profile
fields in every historical row or assign current settings to older treatment.
Missing profile/export fields remain unavailable; compaction does not create them.

Keep recorded meals, portions/GI provenance and user-declared meal timing linked
to recorded insulin and canonical references. Food bolus/correction times must
not be reduced to an hourly sum. Approximate grams and estimated GI keep their
uncertainty; a recorded bolus is not confirmed delivery or proof of a food bolus.

Dense basal/SMB histories may have separate compact interval totals with exact
units, duration, source and delivery status, plus selected event detail. Never
combine recorded, suspected and confirmed delivery or double-count aliases.
State-valued IOB/COB are last/min/max trajectories, not quantities to sum as doses
or meals. ISF/CR retain profile/mode changes and source/time coverage; their
estimator changes are not independent proof of changed human physiology.

Activity requires interval facts or validated counter deltas, not sums of
repeated cumulative step/state values. Missing/reset boundaries remain explicit.
Represent device/sensor/set changes and alert episodes with timestamps and
actual receipt status. Missing receipt means unknown, not an alert heard.

Chat contributes the complete admitted current question, relevant recent turns
and referenced report facts. Older assistant prose and repeated explanations
are not medical truth and must not displace current profile or source evidence.
Nightly interpretation is distinguishable from deterministic facts and retained
only after its independently admitted generation and storage succeed.

## Bounded Packet

Proposed normal JSON target: 96 KiB. Existing native user-input ceiling remains
128 KiB; do not enlarge it, its frame/stream limits or the separate daily route.

| Section | Normal Allocation |
| --- | ---: |
| Current state and selected user/therapy profiles | 8 KiB |
| Glucose background and exception detail | 36 KiB |
| Meal, insulin, sensitivity, activity and device events | 20 KiB |
| Deterministic daily/hourly facts | 8 KiB |
| Relevant conversation and report references | 12 KiB |
| Complete admitted current question | 8 KiB |
| Manifest, codebooks and omissions | 4 KiB |

Allocations sum to 96 KiB. Use actual canonical UTF-8 encoded JSON length, not
character count or compressed network bytes. Unused allocations may be borrowed
under the same target, but mandatory profile/question/limitations cannot be
silently truncated. Maintain bounded codebooks and reference every series source.

Byte size is not the model token window. Implementation must also use the
admitted model's context/token accounting, count system instructions/framing and
existing native history, and reserve output space without changing selected
model/reasoning settings. Do not claim measured token/cost savings from these
layout byte calculations. Unknown token-budget evidence is not a full-fit claim.

Overflow priority: remove irrelevant old assistant prose/redundant detail first,
then reduce unrelated background resolution with an explicit manifest. Preserve
question-relevant exceptions, source uncertainty and omitted-event counts. If
even required detail cannot fit, return an explicit limited-context/focused-
period requirement. Never silently omit a requested cohort/critical episode,
fabricate a complete report, split a model request into automatic replay, or
enable arbitrary SQL/filesystem/network tools to escape the limit.

## Architecture And Compatibility

Add a pure versioned projection between the trusted owner/device history reader
and CHAT context assembly, not an archive rewrite or an Android clinical filter.
Use existing authenticated latest-revision/tombstone/expiry/cohort semantics and
bounded indexed reads. No caller-selected owner, native path or raw SQL.

Separate delivery into a pure projection/size/manifest stage, then a trusted
reader/CHAT/archive-receipt integration stage. Native thread persistence and new
profile export are dependencies with their own acceptance, not hidden additions
to the compaction implementation. Written-format approval does not deploy a host.

Freeze selected profile, current-state time, archive highwater/as-of, requested
and covered periods, source/reference map, resolution segments, raw/bucket counts,
omissions, generator revision and exact frame digest in an encrypted context
manifest bound to that question/result. Later uploads cannot change this receipt.

The existing daily-review-v1 envelope is closed and GLUCOSE_ONLY. Reuse its pure
facts/uncertainty logic, but do not pass therapy/profile data through that contract
or silently change its scope/prompt. Its extension and persistent native thread
ownership remain separately versioned work from AI_DEVICE_CONTEXT.md.

## Sizing Evidence

Local canonical compact-JSON layout calculation for one cohort, ordinary24-hour
days, seven columns and illustrative integer glucose values up to50mmol/L:

| Period | Minute Samples | Ten-Minute Rows | Ten-Minute Bytes | Adaptive Rows | Adaptive Bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| 3 days | 4,320 | 432 | 14,977 | 432 | 14,977 |
| 7 days | 10,080 | 1,008 | 34,561 | 1,008 | 34,561 |
| 14 days | 20,160 | 2,016 | 68,833 | 960 | 33,541 |

Adaptive fourteen-day layout uses older eleven days at thirty minutes plus
three days at ten minutes. These are illustrative array-format byte counts, not
output from an implemented compressor, actual archived-device measurements,
complete packet sizes or model token usage. Sparse exception windows, extra
cohorts and nonglucose data must still share their declared budgets. DST changes
actual local-day bin counts; never hardcode144 bins for every local calendar day.

118 existing daily-glucose, comparison and daily-review tests pass. They cover
the reusable calculators, including unsupported gaps, irregular sampling and
bounded evidence projection; they do not validate a new ten-minute compactor.

## Implementation Acceptance

- Deterministic input/output at the same authenticated snapshot and cutoff;
  original archives, uploads and clinical outputs remain unchanged.
- Synthetic one-minute spikes/troughs and gaps survive as extrema/exception
  evidence even when selecting every tenth point would miss them.
- Full-resolution metrics match the reused original calculator exactly before
  representation rounding; incompatible sources and changed ranges never pool.
- Missing/conflicting/future/deleted/revised/expired data, DST, source changes,
  state-versus-dose semantics and byte-budget saturation have regression tests.
- Three/seven/fourteen-day, multi-cohort, dense SMB/basal, many exceptions,
  UTF-8/escaped text and maximum admitted question stress cases obey total
  encoded byte/token budgets or return an explicit limitation without inference.
- The exact per-answer manifest survives restart and remains bound to its
  result, with existing encryption/idempotency/expiry and uncertain-outcome rules.
- Native echo/framing, cancellation, independent stop, full backend gates and
  exact-source CI pass before an operational rollout or actual model acceptance.

The owner approved implementation after selecting model-context-only compaction.
The pure first stage implements statistical projection, capacity checks and
immutable manifest bytes. The opt-in adapter adds authenticated selection and
receipt persistence. Complete domain export and live/native acceptance are not
implied by these local tests.
No server change is part of this stage.
