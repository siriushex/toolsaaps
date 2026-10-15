# Evidence-Grounded Daily Review And Private Administration

Status: high-level design approved by the owner on 2026-10-15. Implementation is staged; this document does not activate the service.
Date: 2026-10-15. The source audit is retained privately; clinical references appear below.

## Goal And Boundaries

Give the owner a short, useful daily explanation of glucose variability and
application failures using the same-device authenticated history. Preserve
365-day per-device isolation. Recommendations support review and discussion;
they do not prescribe insulin, meal timing changes, ISF/CR changes or lower
targets, and cannot execute a treatment command. Smoothness must not be
optimized by hiding low exposure, missing data or measurement uncertainty.

Daily analytics is a new protected owner feature, not an activation of the
legacy app. No generic CHAT widening, root web process, login-profile access in
the API, arbitrary SQL, raw log upload or public enrollment administration.

## Approaches

1. Recommended: deterministic facts and episode candidates, followed by one
   constrained model interpretation. Metrics remain independently testable;
   unsupported findings are rejected and model outages leave useful facts.
2. Upload all raw history on every call. Rich detail, but redundant context,
   high cost/latency, truncation and weak reproducibility; not recommended.
3. Reword the existing daily prompt. Fast but insufficient: counts do not
   contain glucose, coverage or reasons for a controller decision. Reject.

## Daily Facts Pipeline

Flow: authenticated source records -> frozen membership/revisions -> canonical
intervals and event links -> daily facts and candidate patterns -> bounded AI
review -> reference validation -> encrypted report -> device/admin read model.

The source snapshot binds owner, device, sequence, event/receipt cutoffs and
latest eligible revisions/tombstones. Authenticate encrypted envelopes before
using them. Page bounds, SQL/decryption budgets and explicit failure remain.
There is no unbounded year scan on every UI request and no partial totals
published after timeout. Reuse the existing authenticated query/crypto and
lifecycle patterns, not a second raw database reader in the web UI.

### Time And Coverage

- Proposed default: 08:00 Asia/Tbilisi, previous completed local civil day.
  Owner can choose another schedule/zone. Persist the zone and exact UTC bounds;
  do not use host date or assume all civil days contain 24 hours.
- Daytime/hour patterns use the historical zone when known. A configured
  reporting zone is an explicit projection, not proof of the historical zone.
  With travel/unknown zones, label projections and withhold local-hour claims.
- Received, observed, issued and event times are different. A present-day export
  timestamp cannot make an old measurement current.
- Record coverage by domain: samples, accepted/rejected/unknown quality,
  covered minutes, missing minutes, maximum gap and export progress when known.
  A sequence high-water mark alone is never a completeness certificate.
- Initially allow a quantitative uploaded-data view with unknown quality, but
  label it unverified and keep it separate from quality-OK cohorts. Exclude
  suspect/invalid values from primary metrics and show their counts separately.
  Unknown context must not be relabelled as calibrated or merged into a known
  sensor/session. Unverifiable overlaps produce unknown intervals.
- For eligible consecutive CGM points, integrate a piecewise-linear segment
  only across gaps of at most 15 minutes and within a compatible declared stream
  and quality/context cohort. Split at report/hour/range boundaries. Do not fill
  across a sensor/calibration change, conflict or longer gap. Do not extrapolate
  beyond the first/last supporting sample. The 15-minute rule is a versioned
  engineering proposal, not a clinical accuracy guarantee.
- Time-in/below/above range uses covered minutes as denominator; uncovered
  minutes remain a separate category. Time-weighted mean and variance use the
  same piecewise-linear integration. No pooling of conflicting sources or
  averaging percentages with different denominators.
- Reporting range is a versioned owner-approved analytical setting, distinct
  from a temporary AAPS target. Historical range changes split intervals.
  If unavailable, range-based metrics are unavailable, not silently defaulted.
- One day's facts are descriptive. Stable-pattern interpretation uses adequate
  multi-day coverage; the 10-14-day / 70% guidance in ADA is not a daily-pass
  rule. Display actual coverage rather than a false clinical validity badge.

### Facts And Episode Evidence

Report covered-range exposure, variability, missing data and supported daily
episodes. Compare with the previous 7 and 21 comparable days; report eligible
days/opportunities and exclusions. Do not use future days or a baseline changed
by the same episode being assessed. Do not treat absent meal/activity records
as fasting or rest. Comparisons across model/profile changes stay stratified.

Canonical events retain source IDs/revisions. AAPS aliases, edited meals and
synthetic UAM cannot become extra real meals. Recorded boluses are not confirmed
delivery. IOB/COB snapshots are states, never amounts to sum as doses or meals.
Positive and signed net IOB keep their separate labels and uses.

Stored forecasts use their target timestamp. A new typed export must carry
verified issue time/cycle identity before prospective forecast metrics are
claimed. Legacy reconstructed issue times remain explicitly inferred. Validate
outcome pairing, maturity, same-series compatibility and missing outcomes;
stratify by model and horizon 5/30/60 minutes. Distinguish retrospective replay
from predictions actually available at a decision time.

## Candidate Pattern Catalogue

Each candidate includes a stable ID, metric/evidence references, episode counts,
eligible denominator, data-quality limits, alternative explanations and allowed
review actions. The model cannot create a new candidate or numerical effect.

| Candidate | Useful evidence | Important limitation | Review action |
| --- | --- | --- | --- |
| Repeated low exposure at similar hours | Covered intervals on distinct days, glucose quality, checks/notes if present | Compression/calibration artifacts and unknown sleep are not excluded by a CGM trace | Review measurements and the existing clinical plan |
| Repeated early or delayed post-meal rise | Confirmed real meal record, declared type/portion uncertainty, surrounding CGM, insulin evidence | Entry time need not be eating time; overlapping food/activity confounds attribution | Review recording accuracy and discuss a recurring pattern |
| COB inconsistency | Source-labelled observations plus confirmed edits/meal identity | A model estimate is not measured absorption; missing food is not a proven app bug | Inspect source/model provenance and meal edits |
| Changed effective ISF/CR | Same-profile/model snapshots and comparable windows, independent observed response where available | The app's adaptive ISF can already include illness/activity; avoid circular confirmation | Review context/model changes, not automatic recalibration |
| Possible delivery concern | Existing diagnostic codes, change records, observed response and corroborating evidence | High insulin use or high glucose alone does not diagnose a failed set | Review delivery diagnostics and the existing plan |
| Set reminder | Reliable change time and the owner's 72-hour reminder setting | Time is a reminder, not proof of mechanical failure | Check the recorded change time |
| Alarm path issue | Episode/channel attempts, suppression/failure/OS state | Posted is not heard; unknown receipt is not confirmed delivery | Inspect the indicated notification/battery/volume setting |
| Eating Soon / target delay | Request, candidate, guard/cadence reasons, command correlation, AAPS confirmation | A queued/sent command is not applied; protective blocks can be correct | Explain the exact hold/failure and relevant app setting |
| Forecast bias | Mature issued predictions and compatible observed outcomes | Replay/inferred issue time is not prospective validation | File a reproducible model diagnostic |
| Illness-associated change | Explicit dated user context and comparable observations | Hypothesis, not diagnosis; food, activity, sensor and delivery confound it | Review symptoms/context and the existing sick-day plan |

With one episode, show an observation. A provisional recurrence requires at
least three non-overlapping episodes on three distinct days with disclosed
denominator; this is an engineering display rule, not statistical significance.
Do not count multiple five-minute points as independent meals or episodes.
No supported pattern is different from insufficient data.

GI is a declared/catalogue/model-estimated covariate with provenance. CGM and
approximate portions do not identify a true food GI independently of insulin
and other influences. The report can describe the observed response profile,
not certify GI or convert it directly into a target adjustment.

## Missing Domain Adapters

Keep source work bounded and read-only to clinical tables:

1. A typed coverage/progress receipt and original source context where available.
   It remains device-declared, not proof of complete physiology.
2. Meal identity/revision/type/GI/quantity provenance and declared eating times.
3. Target Manager and Eating Soon lifecycle: requested/considered/blocked/sent/
   confirmed/expired, reason codes, effective intervals and correlation IDs.
4. Alert/channel attempts and sanitized result codes, not raw diagnostic logs.
5. Dated physiological tags/notes and revisions/deletions; calibration/session/
   change/measurement context where the original source actually provides it.

No arbitrary winner/command JSON, secrets, tokens, source logs or entire profile
are exported. Notes are bounded untrusted medical context. Extend strict DTOs
and matching Android/server validation together; existing unsupported sources
stay visibly missing until an adapter is present and tested. Reuse the durable
frozen-page transport and stable identity/revision rules.

## Prompt And Result Contract

See 2026-10-15-daily-review-prompts.md. Use a dedicated DAILY_REVIEW policy and strict request/
result types; the existing enum is not admission. Preserve the current trusted
model/reasoning choices and empty tool/action policy. The supervisor must admit
the exact new policy explicitly; do not pass it through the existing CHAT policy.

Proposed prompt payload limit: 64 KiB inside the existing 128 KiB native input
ceiling. Include deterministic daily facts, comparable-day summaries, at most
six ranked evidence episodes and bounded current-day notes. Omission counts
are mandatory. Do not silently truncate partial records. Full immutable source
evidence remains locally retrievable, not all appended to each prompt.

Structured output selects candidate IDs and allowlisted review actions and
provides short explanatory text. The UI renders all counts, times, units,
strength and metric values from facts, not model arithmetic. Unknown references,
schema violations, injected actions or mismatched digests reject publication.
Free explanatory text remains labelled AI interpretation; schema validation and
prompt rules do not establish medical correctness. No recommendation can be
deserialized into the therapy/action pipeline. User feedback is an annotation,
not a clinical label or authority to learn a dosing change.

## Scheduling And Report Lifecycle

- Explicit per-device opt-in and provisioned protected service only. No import-
  time scheduler or Codex task automation. Daily facts can run while AI is off.
- One automatic logical review per owner/device/local-date/reporting-zone.
  Store a durable schedule reservation and freeze facts/model/prompt/schema/
  range versions before dispatch. A new prompt version is not permission to
  run the same day's automatic review again.
- Share existing global profile/ledger ownership. New background work yields
  the next free slot to interactive work; it does not preempt an active turn or
  hold a slot while scanning history. No parallel model access.
- Separate states for facts, data coverage, model admission, model execution,
  validation and publication. FACTS_ONLY, LIMITED_DATA, VALIDATED_REVIEW,
  FAILED, OUTCOME_UNKNOWN and STALE_SOURCE are not interchangeable successes.
- Retry safe fact-building/pre-dispatch failures with bounded backoff. Once
  model submission may have happened, correlate the original job; never silently
  create another request. Unknown outcomes require trusted reconciliation.
- Late uploads/revisions/deletions invalidate affected facts. Recompute facts
  without a model request and mark the previous interpretation stale. Keep its
  audit and original snapshot; only an explicit re-analysis may create a new
  AI revision. Do not silently rewrite an earlier conclusion.
- Restart catches up at most the most recent missed day, not a year of model
  calls. Older days can have deterministic facts without automatic AI replay.
- Store encrypted per-device reports with distinct crypto purpose, bounded
  quotas and immutable source/prompt/version bindings. Do not add a plaintext
  medical admin database. Report/question/answer retention remains 365 days
  from original report/acceptance time; never extend it by refresh/retry.
  Do not treat expired evidence as current; disclose unavailable drill-down.
- The daily report is retrospective, not an urgent monitoring/alarm channel.
  Missing data/provider service cannot silence existing Android alerts.

## Admin Information Architecture

Proposed first version: private, read-only web console reached through an SSH
tunnel, with separate authenticated owner session; no public admin mapping.
Clinical data come from narrow owner/device-bound read projections, not direct
SQL, file paths or login-profile access. Enrollment/revocation/key material stay
in the trusted CLI. A separate admin admission/auth/CSRF design and test gate
must precede any listening service; a tunnel alone is not web authentication.

Use dense unframed tables and a right-side details panel, restrained colors,
fixed icon controls/tooltips, no marketing area or nested cards:

- Devices: alias, last original CGM time, last received upload, daily coverage,
  missing domains, pending backfill, app/model versions and next review.
- Daily reviews: date/device filters; separate coverage, facts and AI states;
  last source revision; inspect evidence and compare report revisions.
- Patterns: observation/recurrence distinction, n/N, affected days and confounds;
  resolve into dated evidence, never only a red warning and generic advice.
- Jobs: queued/claimed/running/stopping/settled/unknown, wait and run durations,
  fixed failure codes and correlation; no payloads or raw stderr in ops logs.
- Quality: report range/version, coverage timeline, prospective/replay forecast
  separation, model/prompt comparison using eligible held-out later periods.
- Prompts: deployed revision/hash, fixed instructions, output contract and
  synthetic evaluation results; no live arbitrary system-prompt editor.
- Infrastructure: API/supervisor/provider admission shown separately, retention,
  storage, aggregate CPU/RAM and queue pressure; no tokens, cookies or auth files.

Opening a report fetches only its selected evidence window. No live all-year
scan, frequent full refresh or model request on navigation. Pause background
polling in hidden tabs; use bounded pagination and changed-state refresh.
Render imported/model text as inert escaped text, no active HTML or remote media.
The first version has no target/dose controls, raw DB download or hidden retry.
Manual re-analysis and operational mutations require a separately reviewed
authenticated/CSRF-protected action contract and confirmation; do not display
nonfunctional placeholder buttons in the initial console.

## Implementation Stages And Acceptance

1. Pure facts/episode contracts and tests, no inference or scheduler activation.
   Add fixed-window authenticated read support only where existing interfaces
   cannot provide bounded consistent evidence. No clinical table writes.
2. Encrypted report storage/read API plus missing bounded source adapters in
   independently verified increments. Verify updates/deletions and device access.
3. Dedicated prompt/worker/result validator and durable daily orchestration.
   Synthetic supervisor tests first; no generic CHAT or legacy route bypass.
4. Private admin projections/auth/UI. Desktop/mobile/keyboard and real browser
   inspection required; a static DOM test is not visual verification.
5. Actual host/storage/model/device admission, then scoped end-to-end review.
   A source merge or installed binary is not operational completion.

Required tests include midnight/DST/zone changes, sparse/invalid/conflicting
CGM, range boundaries, linear integration crossings, aliases/edits/tombstones,
IOB state vs dose, inferred/verified forecast issue time, cancelled/unknown
commands, alarm suppression, empty and minimal data, circular ISF evidence,
prompt injection in notes, invented references, cross-device access, restart,
late uploads, provider disconnect, unknown stop, quotas, retention, auth/CSRF,
escaping and bounded resource/load tests. Model output must never reach therapy
dispatch. Compare generated facts against hand-computable synthetic fixtures.

Do not claim medical validity from these tests. Provider quality evaluation,
source consent, deployment and user-facing verification remain separate gates.

## Approved Direction

Owner approval: deterministic-facts approach; 08:00 Asia/Tbilisi previous-day
default; 7/21-day comparisons; advisory-only report; private read-only admin
before any public admin or mutation controls. The owner also approved preparing
a local synthetic admin mockup; visual review remains pending. The owner authorized implementation starting with daily facts. Server activation,
provider calls and phone operations remain separate from source development.

## Clinical References

- https://doi.org/10.2337/dc26-s006
- https://pubmed.ncbi.nlm.nih.gov/31177185/

These sources do not validate new engineering thresholds or individual recommendations.
