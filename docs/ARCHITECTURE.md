# ARCHITECTURE

## Core Room History Adapter

RoomAiHistorySource reads indexed original-time/rowid keyset pages from five
allowlisted core sources. It preserves known units and unknown context, uses
existing therapy component/artifact semantics, excludes canonical carb aliases
and synthetic UAM, and never claims recorded insulin was delivered. Unlisted raw
JSON/text and GI overlays are not exported. Each page has at most32 source rows.

ServerAiRoomHistorySync maintains a separate no-backup Room metadata index, not
a migration of the clinical database. A separate AES-GCM atomic page journal
freezes records and index deltas before the existing signed history client sends
them. Exact retry and idempotent index/progress commit survive process/storage
failure. Changed records retain UUID/time and increase export revision; finished
scans tombstone missing records. Recreated tombstones use new UUIDs.

Device identity is carried into the connection's signing lock. SQL projects only
consumed bounded columns; deletion pages use a time/key index and durable cursor.
Expired observational attempts are retired with encrypted UUID/digest/time audit
before new attempts carry exactly unchanged unexpired JSON. Fully valid batch
bodies remain immutable, and this path never retries model jobs.

An initial six-hour scan precedes365-day backfill. Recent reconciliation runs
every15minutes; a full scan is due daily. Older corrections may wait for it.
Maintenance is app-lifecycle-owned, enabled only with ACTIVE server access and
power saving off, bounded to16pages per call. Explicit chat syncs four pages and
adds scope/omissions to its observational context. No global snapshot, full
database coverage, stopped-app execution guarantee or therapeutic authority is
claimed. See superpowers/specs/2026-10-15-room-history-export.md.

## Owner API And Application Chat

Every private inventory scan owns an independently opened, no-follow-checked
directory descriptor pinned to its expected identity. Lifetime database/root
descriptors are not reused for concurrent iteration; scans close their own
descriptors without weakening file/lock identity or permission checks.

The explicit nonroot `app.ai.owner_api:create_app` factory composes the existing
attested activation, signed jobs/history, encrypted per-device archive and typed
supervisor client. Imports and the default backend app do not activate it. A
protected descriptor supplies one owner, provisioned keys, dedicated storage,
attestation trust and supervisor identity. The API never opens the login profile.

Android AppContainer injects `ServerAiHistoryClient.syncChatContext` into
`ServerAiChatGateway`, then into the ordinary chat repository. Server routing is
decided before personal credentials; unloaded or configured/error states cannot
silently use the legacy API. One immutable model submission follows successful
context upload; correlation, bounded polling and cancellation remain explicit.

The display adapter uploads a labelled current snapshot and timestamp-free
local chat observations, alongside the bounded core Room adapter above, not a
complete database export. Typed structured upload and
signed conversation reads are available independently. Display context preserves
source/timestamp differences, unknowns, positive/net IOB and insulin onset units.
Full supported text replies are retained as numbered observational fragments,
bounded by actual encoded JSON size and Unicode code points; this never splits
or repeats a model request. Current display context follows imported messages so
the server's bounded latest-record selection retains it. Omitted older examples
remain explicitly counted rather than represented as complete detailed context.
Chat accepts limited history; extended-analysis readiness remains separate.
Text-only capabilities do not imply image, audio or speech support. Clinical AI
optimization and all therapy actions retain their existing paths and gates.

## Trusted Supervisor Host

The explicit text_supervisor_host CLI assembles TextSupervisor/SystemdTextProcess
from a closed protected operator descriptor. It derives the fixed contextual
policy, verifies image/egress/idle guard before listening, creates only its new
restricted Unix socket and drains work before removing that exact socket.
Only explicit new public image/address admissions may reload; peer/policy/private
bindings stay fixed for the host lifetime. No default app/service installation,
image provisioning or nonroot API bootstrap. See the2026-10-15 host spec.

## Contained Text Process

SystemdTextProcess is the trusted Linux implementation injected into TextSupervisor.
It verifies an explicitly pinned public RuntimeImage and expiring OfficialEgress,
reserves SessionProfileGuard, launches a fixed nonroot systemd command behind an
input barrier, then verifies actual kernel limits/mappings before native input.
Cancellation retains OS-creation identity; original-invocation stop and terminated
bounded pipes precede the receipt. Native provider/base URL are explicit and tools
remain disabled. Host assembly, network enforcement probes and Android deployment
are still separate work; this module starts no service at import or construction.
Active terminal outcomes remain pending in job status until durable archive
reconciliation finishes, including failed/malformed turns as well as answers.
See superpowers/specs/2026-10-15-contained-text-process.md.

## Local Text Supervisor Channel

ContextualChatWorker can use a typed Unix TextTurnRunner instead of owning native
IO. The trusted TextSupervisor alone drives NativeTextSession with its fixed
policy. Both peers check kernel UID/GID; only bounded text and a policy digest
cross the boundary, never native RPC, executable settings or credentials.
Answers follow independent stop/IO termination. Missing stop poisons admission;
the API also disables new requests when the executor retains its durable lease.
The concrete process above is injectable; host assembly remains a deployment dependency.
See the2026-10-15 text-supervisor-channel spec; no default route or service starts.

## Signed Conversation Read

The explicitly injected HistoryService also mounts POST history/conversations
for full server chat pages. Existing owner/device proof and current-access gates
apply; request identities cannot select another file. Separate4KiB request and
128KiB response bounds preserve all existing route limits. Device/window-bound
encrypted cursors fix membership in append order for15 minutes, with an original
row/job anchor preventing rowid reuse after purge. Outcomes remain current per
page, explicitly not a point-in-time snapshot. No migration/inference/write or
default route activation. See the2026-10-15 server-conversation-query spec.

## Persistent Server Conversation

An optional CHAT-only HistoryStore dependency records authenticated requests
before dispatch and executor-settled outcomes before answer publication. Schema2
adds an encrypted server_chat table in the same private owner/device file. The
server origin is separate from device-declared chat; the metadata job ledger
still holds no payload. Original365-day expiry, reserved answer quota and
idempotent immutable outcomes apply. Crash gaps are OUTCOME_NOT_RECORDED, never
automatically replayed or turned into fabricated success.

ContextualChatWorker v2 includes bounded recent server exchanges as untrusted
data, excluding the current question. Full stored text is not truncated, but
model context excerpts disclose limits. The signed read endpoint is described
above; no production launcher is introduced. See the2026-10-15 server-conversation spec for
migration, cancellation, retention and non-atomic ledger/archive failure windows.

## Contextual Native Text Adapter

The optional ContextualChatWorker binds signed JobWork identity to one encrypted
device history snapshot and one ephemeral native text turn. The immutable owner/
device origin comes from verified authentication, not client payloads. Bounded
latest-revision retrieval reports counts, UTC buckets, separate declared glucose
cohorts, recent per-kind examples and omissions; record-weighted means are not TIR.
No plaintext history, schema mutation or treatment inference is added.

NativeTextSession checks exact thread/turn/model correlation, read-only settings,
fixed byte/event limits and completed final output, refusing tools and unknown
events. Direct tool-free catalog policy is mandatory in the trusted process.
The injected process must independently verify OS containment and teardown;
this module does not launch it. Existing JobExecutor controls result settlement.
Server conversation archival is optional as described above; production
supervisor/bootstrap, Android sync/chat and actual-device verification remain
unimplemented integration work. See
superpowers/specs/2026-10-15-contextual-text-adapter.md.

## Inactive Session Profile Guard

session_profile is a trusted-host local control component, not an API/launcher.
One pinned profile/control mapping uses permanent flock plus durable reserved/
bound state. Frozen cgroup/slice identity persists with the run; only the exact
existing SystemdStopVerifier may close a bound lease/recovery after confirmed
stop and fresh metadata checks. Unbound/ambiguous crashes retain admission refusal.
No auth body/token is read or restored. Initialization requires an empty profile;
top-level bounded metadata checks do not replace recursive native containment.
Deployment, private owner code presentation and actual authenticated runtime
integration remain independent gates. See the2026-10-15 session-profile spec.

## Offline Session Storage Candidate

tools/session_store supplies a tested Rust helper and a source-hash-bound native
integration patch, separate from all backend routes/workers. It changes only file
persistence: private sibling temporary, complete write/file sync, atomic replace
and Unix directory sync. Native credential serialization and model settings are
unchanged. No server launcher, profile lock, real login or runtime is connected.
The existing transport and device-history components remain independent.

## Observed Upstream Transport

ObservedProbeProfile extends the pure transport profile with a gateway-owned
numeric upstream status only. Existing response hides remain at their original
HTTP scope; upstream status-header copies are hidden. Bounded three-field
receipts require successful process/local TLS and a matching final upstream
status below500. Local gateway refusal and upstream TLS/proxy failures cannot
become provider transport success. No server route, launcher or model is wired.
Anonymous403 from a fixed origin is transport evidence, not login/API readiness.

## Credential-Free Transport Probe Profile

TransportProbeProfile is an inactive pure configuration component, not a worker
launcher or OS containment proof. It produces a fixed HEAD-only Nginx TLS
gateway, bounded curl arguments, separate synthetic-Unix/public-IPv4 upstreams,
nonroot resource properties and strict status-only receipts. No credential,
prompt, history, inference route, public endpoint or Android caller is connected.
Host root preparation, relay/namespace composition, actual cgroup enforcement
and all-role invocation-bound stop/cleanup remain independent operational gates.
See superpowers/specs/2026-10-14-transport-probe-profile-design.md.

## Optional Personal History API

The owner-approved first server stage adds an independently injected history
service, not inference or Android synchronization. Two signed POST routes reuse
bound device authentication, exact body proofs and post-proof identity checks.
Path-specific admission preserves existing multi-owner job behavior while the
history service is configured for one owner. Default apps mount no history route.

Closed observational records support selected glucose/therapy/meal/forecast/
sensitivity/activity/device/alert/diagnostic/chat fields and optional historical
context. All metadata remain device declarations, not qualified model evidence.
Private generated per-key SQLite files use derived AES-GCM payload keys, atomic
version/batch receipts, fixed365-day expiry, tombstones and high-water byte-bounded
pages. Metadata remain readable; encrypted volumes, key provisioning/rotation,
idle purge and backup expiry are deployment prerequisites. No raw clinical import,
live model, UI, worker or therapy writer is enabled. See
superpowers/specs/2026-10-14-personal-history-api-design.md.

## Explicit Meal Target Intent

The checked Eating Soon option is an independent explicit manual target, like
the standalone button. ManualMealSubmission freezes/reserves both intentions,
then attempts the existing guarded target before carbohydrate transport and
profile annotation. Carb/annotation failure cannot prevent the target attempt
or overwrite its result. Cancellation/duplicates retain separate statuses with
no ambiguous retry. English/Russian messages show both independent outcomes.
Canonical duration, confirmed target priority, safety gates, IOB physiology and
automatic controller behavior are unchanged. See
superpowers/specs/2026-10-14-explicit-eating-soon-meal-intent.md.

## Contemporaneous Meal Input Observations

Room34 passively retains selected original/resolved CGM, original vs aligned
insulin identity, accepted sensitivity context, local kernel identity and source
quality after clinical fan-out in the existing nonfatal journal budget. There is
no extra prediction/model fit, interval-coverage inference, training producer or
clinical writer. First-write/post-commit semantics, isolated21-day retention,
bounded on-demand review and explicit duplicate/conflict/missingness handling
apply. Observation time is not original transport receipt. No raw payload export.

Research simulations now freeze actual CGM measurement basis and calibration ID:
local engine INPUT can already be calibrated, even though its OUTPUT precedes
forecast bias/cloud adjustments. Candidate and runtime receipts reject false raw
retagging. Qualified source cohort/digest v2 separates measurement transform from
temporal availability; unknown legacy metadata cannot fit a model. See
superpowers/specs/2026-10-14-meal-input-observations.md for remaining admission.

## Isolated Personal Meal Forecast

PersonalAbsorptionResearchModel freezes a qualified, later-day-evaluated timing
fit and, when independently supported, a known-GI term. Shared comparable-meal
selection and explicit sensor/calibration/effective-insulin-kernel cohorts avoid
pooling changed contexts. PersonalMealCandidateBuilder substitutes one confirmed
meal on a disposable engine fork: historical known food, UAM, future pressure,
residual COB and Kalman/AR revision handling use the same cumulative curve.
The existing hypothetical future meal-timing path remains unchanged.

The original stage only supported a declared raw-cgm-v1 LOCAL_ENGINE comparison;
the subsequent basis correction above binds raw/calibrated INPUT explicitly.
Exact runtime/model/meal
identity and pre-first-outcome availability/completion gates apply. Candidate
intervals remain absent. Existing accepted-runtime registration is still needed;
caller-supplied context labels are not independent source attestation. No runtime
subscriber, clinical forecast or target writer is added. Qualified live evidence,
prospective data and independent control admission remain outstanding. See
superpowers/specs/2026-10-14-personal-meal-forecast.md.

## Durable Prospective Forecast Journal

An independent Room33 journal records authenticated accepted forecasts and
contemporaneous calibrated CGM after clinical fan-out, with a nonfatal one-second
budget and post-insert-commit finalization. No engine fork, historical backfill,
personalized candidate, clinical writer or export is added. Unknown/poor-quality
and unfinished/late captures remain explicit. Availability is journal observation
of an accepted cycle, not transport arrival. Analytics offers a bounded on-demand
21-day baseline check; versions and sensor/calibration/settings cohorts remain
separate. Candidate pairs stay independent. See
superpowers/specs/2026-10-14-prospective-forecast-journal.md for limits/admission.

## Prospective Meal Forecast Validation

MealProspectiveFrame freezes paired5/30/60 predictions before the first future
outcome. Explicit generation/cycle/settings, accepted-output digest, sensor,
calibration, glucose basis and historical zone identify the comparison.
Candidate training cutoff, model availability, input coverage/availability and
calculation completion are checked; late/future/mismatched inputs cannot register.
Missing candidates remain counted baseline-only receipts, not zero-error pairs.

MealProspectiveCapture adapts an already authenticated MealAcceptedRuntimeSnapshot
on demand. It selects LOCAL_ENGINE or ACCEPTED_CONTROL explicitly and checks the
supplied current identity against that snapshot. It has no DAO, controller,
dispatcher, AppContainer registration or background subscriber. Caller identity
and basis attestations still require a trusted producer; matching metadata alone
does not independently establish calibration, model correctness or freshness.

MealProspectiveEvaluator scores identical exact-time, source/session/basis-matched
future observations with known availability and OK quality. No interpolation,
nearest-neighbor substitution or low-value exclusion is used. Conflicting
generations/outcomes reject; duplicates do not vote. Model/runtime cohorts stay
separate, days have equal weight, and missing scores/intervals remain null.
Mean and lower-bound missed lows, interval support/coverage, worst overprediction
and missingness are reported. Explicit bounds reject oversized input, not sample
it. Every result denies clinical/control-safety admission.

The optional private tool reconstructs receipts through the same registration
checks and exports aggregates only. A supplied file cannot authenticate original
capture. The original contract stage has no personalized candidate or clinical
input changes; the subsequent journal above adds observation, not therapy. See
superpowers/specs/2026-10-13-meal-prospective-validation.md for remaining gates.

## Qualified GI Contribution Analysis

PersonalGiAnalyzer consumes a qualified source snapshot and exact-revision known
GI. It isolates sensor/calibration/insulin-model cohorts, freezes whole-day splits
before missing-GI filtering, reuses the personal timing fit and learns a bounded
research-only GI exponent term. Identical validation windows compare type,
personal and personal-plus-GI response reconstruction, with5/30/60-minute metrics
and declared-mass sensitivity scenarios. These are NOT causal forecast scores.
PersonalAbsorptionRepository.analyzeGi joins GI in the same single on-demand
history read. A private opt-in aggregate-report tool is provided; no production
subscription, clinical input or Target Manager writer is registered.
See superpowers/specs/2026-10-13-gi-contribution-analysis.md for model/search
limits, missingness and outstanding prospective/control-safety admission.

## Primary COB Presentation

Dashboard COB and Current Lenta use the existing `cob_effective_grams` sample
from `copilot_runtime_cob_iob`, labeled Copilot. The UI does not recalculate
absorption or prefer a newer external sample. Both MainUiState builders carry
explicit effective value/time fields separately from the legacy fallback field.
The exact runtime source, OK quality, gram unit, finite existing runtime bounds
and inclusive configured freshness window are required. An invalid/future or
conflicting newest runtime result cannot resurrect an earlier good value.

AAPS COB retains its independent selector, range and timestamp as a reference in
food information and Pro telemetry. A missing/expired effective value stays
unknown even while AAPS is fresh. Existing one-shot UI expiry scheduling covers
both clocks without new queries, timers that poll, workers or database writes.
Accepted food-curve identity, points, GI shaping and endpoint are not rescaled
by the primary number. These are model estimates, not measured absorption;
forecast/controller inputs, doses, targets, carb entry limits and storage are
unchanged by this presentation decision.

## Event Compensation Context

Opening a meal marker requests one bounded read-only Room snapshot through
EventCompensationContextRepository. MainViewModel exposes a suspend loader;
the chart/dialog has no action dependency. At most eight selected meals are
loaded sequentially per selected-record version. Closing cancels the work;
CGM clock ticks do not create subscriptions or repeated queries.

The stored therapy row must still match the displayed event, including transient
canonical meal identity/revision. GI comes only from an exact-revision saved
override with existing USER/CATALOG validation, never an inferred laboratory GI.
No override resolver with cleanup writes is used. Missing GI is explicit.

Context uses up to four hours after the event, cut off at observation time;
600 glucose,1200 telemetry and400 nearby therapy rows plus overflow sentinels
bound each query. Continuous same-source glucose with a near-start baseline
supports observed range/peak timing, not a causal meal-effect estimate. The
source-series label explicitly excludes Copilot correction; retrospective
application of the current calibration model is not invented for these values.
Quality warnings, sensor changes, conflicting duplicates, gaps and mixed streams
prevent that comparison. A nearby meal is an explicit attribution caveat.

ISF compares same-source, explicit-unit historical telemetry near one hour before
and at the event, never current runtime ISF or an assertion of physiology. Sensor
age requires a recent historical hours sample; no universal replacement deadline
is guessed. This source has no verified meal-start/bolus-delivery linkage, so
minute-specific food timing advice is unavailable. No forecast, targets, doses,
COB, schema, worker or background history scan changes.

## Chart Event Details

The chart keeps its existing read-only AlertDialog and represented marker IDs.
ChartEventDetailsUi projects only each selected CompensationEvent; the dialog
has a dismiss callback, an optional read-only context loader and no therapy/action
dependencies. Current source rows resolve selected IDs on every update; vanished
IDs are removed from selection.

EventDisplayQuantities reuses clinical component semantics only for presentation,
rejecting conflicting display aliases without changing clinical parsing policy.
EventTimelineRepository carries source-resolved amounts and blood-check values
as optional display attributes without queries, schema changes or eviction of
clinical keys. Incoming reserved display keys are discarded and regenerated.
The original event timestamp is retained. A graph context envelope is not a
confirmed uptake/delivery duration; accepted reference status is not applied
calibration. UAM remains explicitly model context.

Source-resolved meal quantities also travel as two transient typed presentation
fields, so a full24-field clinical payload cannot erase the detail amounts. They
are excluded from Gson serialization; existing attributes, persistence and
clinical impact are unchanged. Popup text removes transport correlation markers
without interpreting them as source, confirmation or delivery evidence.

## Qualified Personal Meal Evidence Source

PersonalAbsorptionRepository.produce is an explicit read-only research request,
not an AppContainer/worker/clinical subscription. It reuses Lenta's bounded
canonical21-day transaction and batched overrides, then joins explicitly prepared
source evidence to exact stored meal identity/revision/quantity/type/portion and
confirmation availability. Legacy glucose rows alone lack availability and
sensor/calibration/review provenance; no prepared context is invented from them.

Prepared packets are frozen before the Room suspension. Unknown or retrospectively
recalculated glucose cannot become contemporaneous CGM by having a source label.
PersonalAbsorptionEpisodeProducer qualifies historical zone, source attestations,
preparation availability and known overlaps before the existing adapter. It
preserves eligibility/fit thresholds, includes later review availability in the
episode cutoff/digest and exposes immutable outcomes without raw CGM/insulin
retention.5000 canonical labels,240 prepared packets and aggregate20000 glucose/
60000 insulin rows are resource caps; overflow rejects the whole source snapshot.
READY describes a successful canonical read, not a trained or clinically cleared
model. Supplied research attestations are not independently verified physical
delivery evidence. Missing/invalid inputs remain explicit failures.
No forecast, COB, food writes, target/insulin policy, schema or runtime registration
changes. See superpowers/specs/2026-10-13-meal-response-target-integration-design.md.

## Lenta Personal-History Preview

The separate Lenta Estimate mode requests one read-only history snapshot on first
selection. LentaMealHistoryRepository bounds a21-day completed-day canonical
meal query, joins matching local confirmation evidence in400-ID batches and
rejects ambiguous/revised/synthetic/self-generated labels. No schema change,
worker, network, CGM Flow or continuous scan is introduced.
LentaMealEstimator reuses the fourteen-day type/portion/circular-hour selector
and default HybridMealResponseEstimator. Sparse declared portions receive a
bounded, preset-shrunk correction beginning at one comparable meal. Valid stored
USER/CATALOG GI, if present, adjusts only preview timing via the existing display
exponent, further shrunk by support. This is historical GI context, not a
CGM-derived laboratory GI; unknown insulin/sensor/timezone evidence stays unknown.
The full gram/minute curve and passive inspection do not feed clinical models or
Food action payloads. Existing Current COB and its saved viewport stay separate.
See superpowers/specs/2026-10-12-lenta-meal-estimate-design.md amendment.

## Infusion Maintenance Indication

InfusionSetEvidenceReader reads at most32 canonical change candidates plus an
overflow sentinel through indexed type queries. Only qualified Nightscout/local
Nightscout original dates matching the stored occurrence establish wear timing;
receipt fallback, conflicting/deleted/echo/inferred markers and age aliases do not.
InfusionSetPolicy uses72 elapsed hours, with unknown/future/overflow failure closed.

InfusionSetReminderRepository serializes generation/attempt transitions in an
independent no-backup DataStore, retaining a chronological attempt watermark.
Source is committed before unique WorkManager enqueue. The worker rereads source;
a brief Room read transaction binds the final canonical/deadline check to the
synchronous silent Android notification post. Work has no network, CGM, clinical
arming or forecast dependency. Enqueue uses existing asynchronous WorkManager
style; restart/resume reconciles durable source. This is not an exact alarm.

Claim precedes post; interrupted/unknown outcomes are terminal. Known capability
blocks have at most one retry after restoration. OFF consumes/suppresses due
reminders, does not move deadlines or replay them on expiry. Maintenance has a
separate low-importance silent slot and cannot affect glucose/delivery playback.

Overview exposes a48dp passive icon and details, distinct from PumpLink. Current
delivery presentation is projected from the existing validated diagnostic state
and fresh accepted assessment. Duplicates retain original timestamps; startup
readback and insufficient/invalid/new-basis evidence are retained history only.
Projection does not change diagnostic thresholds, claims, recovery or therapy.

## Dashboard IOB Pair And Direct Target Controls

Both primary Overview renderers share a display-only IOB presentation from
currentIobUnits and iobDetails.signedNetIobUnits. Each finite value is formatted
independently; missing/nonfinite values remain unavailable. OverviewIobValue uses
atomic FlowRow items for each number and the slash/net item, with reserved two-line
height so narrow screens cannot split signed digits. Original Details navigation
and accepted-source mapping/calculations are unchanged.

BaseTargetScheduleDialog keeps one local draft and the existing editor policy.
Every interval exposes its single target stepper without expanding Edit. Edit
expands only start/end controls. Existing limits, 0.1 step, validation, revision,
IDs, overnight handling and explicit Save/Cancel remain authoritative. Controls
have 48dp bounds; large system fonts put the long default label above its controls.

## Hybrid Sparse-History Meal Preview

HybridMealResponseEstimator is a pure, unregistered advisory calculation. Zero
history uses the existing selected-type curve and portion preset; an absent type
explicitly falls back to MIXED. Personal-profile completeness is context only,
not an invented age/BMI coefficient or evidence multiplier. Entered grams remain
separate from history/preset suggestions and cannot be overwritten by the preview.

PersonalAbsorptionCohort shares the unchanged21-day canonical selection and
prepared evidence with PersonalAbsorptionEstimator. One qualified completed
episode produces signed apparent-response observations; singleton spread is
absent. Missing intervals are not zero, and nonfinite normalization excludes the
whole descriptive episode. Median/IQR summarize normalized per-meal residuals,
not measured absorption, GI or prospective forecast precision.

Only the existing EVALUATED personal timing status and an explicit food type may
replace the advisory default shape. Sparse/ambiguous/no-gain/bounded/overflow
states keep it available. The five-minute full curve conserves modeled mass and
ends at modeled completion. This cannot replace accepted live food or clinical COB.
Quantity-prior calls may specify a meal-time neighborhood while retaining the
real cutoff; omitted optional minute arguments preserve existing callers. The
hybrid excludes unfinished-day quantities and keeps the existing14-day prior.
No runtime/UI/worker/database/settings/AAPS/controller registration is added.
See [hybrid design](superpowers/specs/2026-10-12-hybrid-meal-response-design.md).

## Approximate Meal Estimation Diagnostics

MealPortionEstimator.describePrior reuses the existing fourteen-day eligibility
and suggestion, adding empirical P10/P90 of declared portions. Those quantiles
are not accuracy bounds for eaten mass; no range is invented with insufficient
support. Original entries/presets and existing estimate behavior are preserved.

PersonalAbsorptionEvidenceAdapter requires canonical, independently declared
meal provenance and explicitly prepared CGM/insulin/model/context evidence.
It builds frozen pre-meal-corrected residuals for a pure, bounded timing search.
PersonalAbsorptionEstimator keeps declared mass fixed, compares earlier/later
whole days and emits a source-scoped apparent-response diagnostic, never GI,
measured grams, prospective forecast accuracy or therapy authority. No controller,
forecast, UI, worker, settings, schema, AAPS or network registration exists.

PersonalAbsorptionRepository performs explicit read-only bounded meal audits in
one Room transaction. The optional private JVM runner audits a saved SQLite
copy or evaluates a prepared JSON bundle, outside source with exclusive report
creation. Unknown historical ingestion/timezone/exposure context remains unknown.
See [diagnostic design](superpowers/specs/2026-10-12-personal-absorption-shadow-design.md).

## Compact Balance Presentation

The selected Clarity Overview now implements approved Balance: six open metrics,
compact glucose/observed-target panel and current forecast marks on the existing
chart. Marks match actual future-path timestamps/values and remain read-only.
COB opens the selected full Lenta carbohydrate view. The add-meal action opens
the existing meal-entry dialog; its manual GI field is removed, without adding
an estimator. The remaining fields, defaults and explicit preparation remain.
Food-view shortcuts close before opening the original forms or Eating Soon
confirmation/status. Opening or cancelling cannot submit or persist a meal.
The separate full food display retains its original accepted lifetime/quality;
it does not extend the main glucose forecast domain. Primary navigation avoids
saving/restoring secondary destinations as tabs. No settings, clinical, schema,
background subscription or timer change. See [native Balance](2026-10-11-native-balance.md).

## Compact Lenta Navigation

Clarity Overview puts cycle refresh and the consumed blood-entry request in the
top bar. The duplicated More dropdown and lower clinical toolbar are removed;
power save lives in the scrollable drawer and the original Safety destination
keeps emergency controls. Glucose opens the existing compensation report, while
sensitivity-source errors and profile/pump warnings remain directly visible.
Healthy/unknown pump-channel details use a passive header indicator; attention
conditions and any open episode retain their text row and existing help.

The food Dialog fits the full accepted domain by default without visible zoom
buttons. It preserves chart gestures and accessibility actions, estimate status,
origin, units and complete/unknown end. Its food-only rendering omits the glucose
target band, not food points or forecast/controller data. Recorded meal grams
are displayed as context, never as per-meal remaining COB or confirmed absorption.
Rows use real USER/AAPS meals within the12h ending at the retained curve origin,
latest identity revisions and finite non-conflicting declared carb aliases.
No new database query, worker, setting, GI inference or target policy is added.
See [approved scope](superpowers/specs/2026-10-12-compact-clarity-lenta-design.md).

## Initial Food-Profile Diagnostics

The diagnostic energy-profile inference reads only sensor-block telemetry inside
the completed-day window. Case-insensitive key discovery uses the existing
covering key index; exact keys/time use the composite index. It does not load the
entire telemetry history into memory or change evidence eligibility.
An enabled profile with no saved snapshot requests one unique initial execution
of the existing daily diagnostic worker. Existing snapshots, disabled profiles
and power-save mode suppress this request; enabled/power-save transitions are
deduplicated. The worker rechecks power save and retains existing retry behavior.
Nightly scheduling is unchanged. Completion is logged only after a saved or
reused snapshot, not on enqueue. Diagnostic inference remains separate from
live food curves and cannot enable clinical forecast or target influence.
See [food absorption diagnostics](2026-10-11-food-absorption.md).

## Native Clarity Presentation

Clarity is a selectable native Overview with separate observed AAPS target,
proposal and accepted forecast state. Explicit Eating Soon confirmation uses
the canonical existing submission path. Readback reuses exact target evidence
in a bounded read-only Room transaction; UI does not advance the safety clock.
One UI pipeline writer retains one accepted food-display snapshot scoped to
settings/source/calibration identity and exact historical origin. It cannot
populate fresh clinical horizons or target authority. See
[native Clarity](2026-10-11-native-clarity.md) for lifetime and rollback details.

## Prediction Time Integrity

Enhanced `local-hybrid-v3.1` retains the current physiology and continuous-history
coefficients. Residual AR fits only the uninterrupted suffix of five-minute
observation buckets; a missing bucket starts the existing decay fallback until
enough new evidence arrives. Simulation copies preserve their own chronology.
The missing-30m helper requires matching 5m/60m origins. UI quality evaluation
uses an explicit clock and only observed outcomes, matched with binary search.
See [prediction audit and refinement](2026-10-11-prediction-audit.md) for the
remaining reference/provenance gaps and paired-validation design.

## Actionable UI Diagnostics

Visible error banners and primary Overview/Safety warnings open passive help.
`UiIssueKind` uses localized explanations and typed `IssueDestination` values;
unknown errors lead to the diagnostic journal, not a guessed repair or retry.
Profile diagnostics reuse `EnergyProfilePolicy`, distinguish missing/invalid
fields from absent/stale snapshots, and retain the specific causes in UI state.
Valid personal data is never described as missing because a snapshot is stale.

The root navigation provider opens the exact settings tab/section or existing
diagnostic destination. User-profile and energy-goal links open the existing
editors after lazy-list positioning; requests are consumed and do not reopen
on ordinary recomposition. Field errors and explicit save validation remain
in the editor. Decimal height/weight input accepts either comma or point.
If personal fields and stored calories are invalid together, the profile form
offers both corrections in one explicit save through the existing validated
settings update. It preserves current modes, sharing flags and unrelated fields.
Energy-profile settings and planned-event editors use an explicit save-result callback.
The form-scoped save state waits for persistence, blocks duplicates, retains the
draft after failure and ignores late completions after disposal. The ViewModel
reuses the existing policy and the leased sensitivity-change coordinator, then
revalidates its field-specific transform within the current DataStore edit.
No old pre-cycle profile snapshot is written over concurrent settings changes.
Planned-event callbacks preserve the existing Room save, validation and clinical
input invalidation semantics; the editor no longer dismisses before their result.
See [profile save verification](2026-10-11-profile-save.md) for failure cases and
the distinction between synthetic coverage and device acceptance.
Transient operation messages offer Details without assuming every outcome is a
failure and retain timed dismissal; long unknown text stays inside the scrollable
diagnostic body.
No database schema, therapy guard, forecast acceptance, settings default,
command replay, pump connection, alert acknowledgment or audio operation changes.

## Checked Pump-Link Source Publication

PumpLinkHealthMonitor exposes an immutable PumpLinkAlarmSource only after the
existing validated transition and all required durable writes finish. It carries
the exact saved snapshot/episode, current technical condition, elapsed evaluation
and existing policy deadline. Receipt disposition is not source authority.
Construction, unknown/unsupported/unpaired provenance, unconfirmed recovery,
intentional disconnect, muted evaluation and monitor failure publish no source.
A superseding accepted transition withdraws the old source before suspending
writes; rejected duplicates leave its identity and evaluation time unchanged.
Fresh packets preserve the existing episode origin and legacy receipt semantics.
A retained episode can originate on an older boot. Its elapsed origin is history,
not current authority; compare it with evaluation time only on the same boot.
A fresh validated current-boot packet can establish a fault without rewriting
that legacy episode. Old persisted packets alone still cannot do so after reboot.

alarmSource is a conflated wake/provenance feed, not hardware admission.
currentAlarmSource rechecks current boot/elapsed time, the existing policy
condition/deadline and exact publication identity after clock callbacks. Cached
data cannot retain an expired condition, survive reboot/backwards time or a
concurrent revocation. Missing-heartbeat evidence uses the existing technical
absence policy; no fabricated TTL, clinical risk or off-body detection is added.
No additional timer, poll, persistence schema or accepted packet cadence changes.
Existing deadline scheduling captures time after publication, so synchronous
subscriber work cannot move the absolute heartbeat deadline into the future.

The existing committed global OFF/resume/overview cleanup withdraws this source
without Room/monitor reentry and before legacy player cleanup. OFF expiry alone
does not republish it; another validated monitor evaluation is required. This is
post-commit source invalidation, not pre-lock OFF-intent cancellation of an actor.
No production consumer constructs or arms LocalAlarmCoordinator. A complete
current-context bridge still needs glucose/diagnostic producers, stable alarm
key/generation/queue-age mapping, OFF-intent invalidation and capability/opt-in
admission. Service/channel/UI/device acceptance gates remain unchanged. Technical
link status cannot establish that a pump is worn or insulin enters the body.

## Inactive Serialized Alarm Coordinator

LocalAlarmCoordinator has no production constructor call. Construction/signals
cannot start it; an explicitly running future owner must supply current accepted
typed requests, saved settings, boot/clocks/OFF and platform capabilities. The
coordinator never infers arming from journal, UI risk or notification history.

One database-instance lifetime mutex is separate from the short global OFF/Room
lock. One conflated wake channel and nearest deadline drive bounded arbitration,
without per-source polling, forecast or AI work. A starting job reports its exact
committed cycle before hardware; completed/cancelled jobs report only after all
cleanup attempts. Preview cannot mistake that in-flight claim for an orphan.
Changed snapshots after suspending reads are discarded. Current-source/OFF/
capability invalidation synchronously revokes the guard and cancels the owned job;
Main cleanup does not wait for journal locking before audio stop/volume release.
Priority preemption cancels and joins the old job before re-preview/new START.
Equal priority and duplicate updates retain cycle/due; no overdue steps replay.

An abandoned exact claim is marked UNCERTAIN without policy evaluation/new START;
ordinal, reached maximum, ACK and due are preserved before a fresh preview.
Missing/unarmed authority idles without a timer. Storage/cleanup failure stops
the actor and latches that database-instance runtime unavailable in this process,
so constructing another actor cannot bypass unconfirmed cleanup. No reset API or
automatic retry is provided. API reports do not prove hearing/media completion.
Source publication, service/foreground capabilities, async errors/native fallback,
notification/vibration/wake ownership, opt-in/ACK UI and real-device acceptance
remain mandatory. Default legacy alerts and clinical writers are unchanged.

## Read-Only Local Alarm Arbitration

LocalAlarmArbitrationPolicy selects from a complete current immutable batch of
accepted typed source requests and separately validated journal state. It reuses
LocalAlarmPolicy admission; hypothetical START evaluations never become claims.
Pending queue age is retained across duplicate updates and reset by the future
owner on generation/level/eligibility change. It is not clinical authority.
Ready sources sort by priority, oldest due and stable typed key. Exact admitted
ownership survives equal/lower priority until cycle end; an eligible higher
source produces exact cancellation plus replacement selection. ACK/waiting,
silent, stale, unauthorized or wrong-boot sources cannot preempt. Missing or
invalid owner evidence, OFF, disabled/unarmed state or changed identity requests
cancellation, never hysteresis-based sound retention.

At most64 unique keys including an omitted owner's key are considered. Duplicate
keys, bad queue age/environment/timing or corrupt state fail closed. A persisted
active claim not matching the runtime owner requires explicit UNCERTAIN recovery;
it is never adopted. This decision does not perform recovery or cancellation.
One nearest future elapsed deadline covers eligible source expiry, cycle end,
repeat, ACK or OFF expiry. Wall deadlines use checked conversion; no overdue
wake/backlog, periodic poll, forecast or AI work is generated.

RoomLocalAlarmStore.previewArbitration freezes the bounded request batch before
suspending indexed reads under the existing database mute lock/transaction. It
reads actual Room OFF and validates the exact same keys' state/claim/result
payloads. Null means unavailable/corrupt storage. Preview never changes ordinals,
due, ACK, progress or cycle journal rows, even after an expired deadline.

Only the inactive coordinator uses preview. No production caller constructs it.
It cancels and joins old execution before claiming a selected replacement; future
source producers must publish current accepted authority/OFF invalidation immediately.
The decision/timestamp cannot arm a service or authorize hardware. Notification,
vibration/wake/native fallback, lifecycle, opt-in/ACK UI and device/night delivery
remain separate gates; legacy and clinical behavior are unchanged.

## Local Alarm Cycle Execution

LocalAlarmCycleExecutor combines one newly committed Room claim, the existing
volume lease and the existing guarded audio controller through LocalAlarmAudioPort.
Only the inactive coordinator constructs it; no armed service does. Initial
claims use the saved repeat settings; actual strong-slot duration
is resolved for the cycle ordinal. Only START is executable; an existing ACTIVE
claim is never adopted or replayed.

RoomLocalAlarmStore.admits is a read-only exact-claim gate under the database
mute lock/transaction. It reads actual Room OFF and validates state/result shape,
source evidence and elapsed bounds, without creating a later claim or persisting
a pure-policy evaluation. The runner rechecks this gate between side effects.
Injected current accepted evidence/OFF/capabilities guard synchronous hardware
calls and callbacks as well. The future source owner must publish invalidation
and cancel the exact running cycle immediately; this stage supplies no producer
of that authority and cannot make Room and platform side effects atomic.

One try-locked coroutine owns execution; competing calls return BUSY without
claims. At most four absolute step windows are visited, with cancellable waits
bounded by source expiry and cycle end. Late windows are skipped, not replayed.
Volume readback must confirm each target before audio; the same step window is
rechecked inside volume/player admission, so a slow hardware read cannot admit
an old step. Strong clips start once, soft clips at most four times. Later cycles
retain the confirmed target maximum. Indexed bounded journal reads replace
neither source freshness nor Android capability admission; there is no polling,
forecast, AI or therapy work on this path.

Audio-start, volume-target and NOT_REQUESTED notification/vibration results are
journaled independently. API-start is not media completion or hearing. Audio or
journal failure stops further steps. Exact cancellation revokes admission before
cleanup; noncancellable cleanup attempts audio stop, guarded volume release and
journal finish independently, then always drops ownership/unlocks. Cancellation
propagates; unconfirmed cleanup is not FINISHED, and interrupted claims require
existing UNCERTAIN recovery without replay. No automatic retry is introduced.

Operational multi-source ownership, fresh source/OFF publication, async playback
failure/native fallback, visual notification, vibration/wake ownership, dedicated
foreground lifecycle, explicit opt-in and real-device acceptance remain release
gates. Default legacy behavior, settings/schema and clinical writers are unchanged.

## Guarded Local Alarm Playback

GlucoseAlertAudioController now exposes an explicit playLocalAlarm(cycle, step,
settings, admitted) transport entry point and exact-cycle stopLocalAlarm.
Its only caller is the inactive cycle executor. The future source owner must supply
the committed claim, fresh accepted source/OFF/capability checks and confirmed
volume floor before playback; this API cannot authorize or arm an alarm.

LocalAlarmPlaybackWindow reuses canonical policy/profile validation. Soft starts
use absolute0/15/30/45s windows; only the first strong step starts a clip.
Preparation/seek must finish before the next profile step or final55s deadline.
Late callbacks fail closed, playback duration is clipped at the cycle deadline,
and duplicate callbacks cannot restart or extend a clip. The most recent cycle's
consumed steps are not retried; durable deduplication belongs to the journal.
The stop timer subtracts post-start admission time from the captured absolute
clip end, rather than reusing a full duration after a slow external callback.

One existing player/focus owner, selected clip resolver, preflight built-in
fallback and gains are reused. New clips and focus use USAGE_ALARM without
writing any volume; the independent lease belongs to future execution.
Admission is checked before takeover/focus/player creation, after preparation/
seek and around start. Exact cancellation and player/focus tokens isolate stale
callbacks. A preparation timeout releases the player/focus even without media
callbacks. OFF/source events still require the owner to call exact stop while
playing; no source polling or complete escalation coordinator is introduced.

Legacy slot selection, attributes and urgent-low floor remain. Synchronous URI
resolution is not a guaranteed bounded platform operation; elapsed validation
after it prevents a late start. Handler timer delivery alone is not a real-time
55s/Doze guarantee. Foreground service, wake/vibration/notification ownership,
asynchronous custom-file failure fallback, source/UI integration, observability
and real-device acceptance remain separate integration/release gates.

Platform references: [MediaPlayer lifecycle](https://developer.android.com/reference/android/media/MediaPlayer)
and [audio focus admission](https://developer.android.com/media/optimize/audio-focus).
For target35+ the foreground/top-app requirement is a real admission gate,
not something these synthetic tests establish for a connected phone.

## Guarded Alarm Volume Ownership

`LocalAlarmVolumeLease` is a volume-only prerequisite for the Oct7 executor.
One exact LocalAlarmCycle owns the observed baseline/maximum and confirmed
index. Current elapsed time and injected coordinator admission guard capture
and every raise; indices round up through LocalAlarmProfiles and never lower
an already louder stream. The canonical first profile target is a minimum even
if a caller asks for a quieter step; LOW_NOW retains its70% floor. Readback,
not setter return, confirms a raised index.
Observed manual/system override or uncertain hardware ownership stops this
lease. Cleanup restores only a confirmed, still-matching owned value; OFF or
expiry does not prevent guarded cleanup. Cancellation propagates.

Synchronized access and acquisition/restoration markers prevent a second owner
or reentrant callback from replacing pending ownership. Raise revalidates the
same owner after external admission/hardware calls. Release drops ownership
even on failure and does not replay cleanup. AndroidAlarmVolumePort accesses
only STREAM_ALARM with flags0; no player, vibration, route, DND, focus or timer.

There are no production consumers in this stage. The future serialized
coordinator must provide committed claim, fresh source, global OFF and platform
capability admission; this helper supplies none of that authority. Android
observations are not atomic volume CAS. A change-and-return gesture or route
change with identical indices may escape comparison. Indices are not measured
loudness or proof of human response. Legacy delivery and its urgent floor stay
unchanged; service/player/source/UI integration and real-device tests remain.

## Durable Local Alarm Journal

The storage-only stage of the Oct7 alarm design adds Room32 tables
`alert_local_state` (one monotonic ordinal/revision per typed source) and
`alert_local_cycles` (unique source/generation/ordinal attempts). Clinical alert
receipts and Telegram initial-delivery semantics are independent and unchanged.

`RoomLocalAlarmStore` serializes transitions with the existing database-scoped
mute mutex, then reads current clocks and authoritative Room OFF within a
transaction. A START can be returned only after a unique claim and its state
commit together. Persisted JSON is not accepted source authority: new evidence
still passes the pure policy's freshness, boot, generation and admission guards.
Strict versioned formats cap state at4096 UTF-8 bytes and results at2048 bytes/
four steps. Notification, audio, vibration and confirmed volume results are
separate; none implies the person heard it or insulin reached the body.

Interrupted ownership is journaled UNCERTAIN and never replays missed steps.
The ordinal/repeat deadline survives recovery; terminal-cycle retention is30d
with bounded batches, while ordinal state is retained. Indexed scheduling reads
are capped at64. This stage has no service/player/UI/source/housekeeping runtime
consumer and does not enable the proposed ramp. Foreground admission, bounded
hardware ownership, fresh device migration and actual delivery remain separate
integration/release gates.

## Full Food Display Projection

The accepted V3 cycle builds a separate `MealFoodDisplayProjection` from its
actual announced-food events, resolved cumulative profiles and frozen CSF.
It covers the remaining modeled tail on a five-minute grid, bounded to720m and
145 points. Current glucose is the origin; this is food-only influence without
insulin, not a longer clinical glucose forecast. Clinical announced steps,
5/30/60 forecasts, pressure, UAM, residual COB and target inputs are unchanged.

Optional `MealGlycemicIndex` has explicit USER or referenced CATALOG provenance.
Room30->31 adds nullable metadata to canonical meal overrides and pending
intents, preserving the existing exact-identity/revision promotion. Old meals
remain unknown. Failed optional reads fall back without GI; cancellation is
not swallowed. No photograph/profile/name inference or averaged mixed-meal GI.

`food_gi_shape_v1` redistributes each modeled remainder through a bounded
normalized-fraction exponent. High GI moves influence earlier, low GI later;
current mass and finish time stay fixed. Reference60/strength0.25 are uncalibrated
display coefficients. This separate result cannot authorize clinical action.
The existing `forecast_meal_steps` publishes accepted schemaVersion2 data,
cycle/prediction clock, completion and GI-adjusted count, at most16 KiB. The UI
requires matching accepted identity and glucose origin, includes the full tail
in follow-live/reset domain and marks incomplete estimates. Legacy13-step data
retains its30m limit and unknown completeness. No new observer or worker.

## Current Clinical Input Recalculation

Broadcast ingestion compares the latest valid, source-prioritized current glucose
before and after a write inside one Room transaction, using one ingest clock.
A new timestamp or corrected current value/provenance persists the existing
clinical invalidation outbox without a glucose scheduling interval. Generated
row IDs alone, exact duplicates, older history and future-only samples do not
count as new current observations. Therapy and telemetry invalidation policies
are retained; Local Nightscout continues its existing input-change routes.

The existing durable coordinator coalesces bursts and keeps newer pending input.
Reactive WorkManager execution waits cancellably for the automation cycle lease
inside its existing deadline/evidence boundary, then reads current settings and
clinical inputs and runs the full accepted forecast/Target Manager pipeline.
Periodic/manual cycles retain idle-only execution. No second prediction engine,
trend threshold, overlapping calculation or therapy transport is introduced.
Recalculation alone cannot authorize a target: all existing protective, ownership,
forecast reliability, arm, sensor and dispatch freshness checks remain mandatory.

## Meal Timing Research Kernel

ManualMealSubmission persists an immutable input before its existing therapy
send; no simulation runs on this path. An input persistence failure is diagnosed
but does not block the user's carbohydrate send, so this failure is NOT covered
by a lossless-intent claim. The protected AAPS importer persists minimal receipts
inside the SAME Room transaction as therapy import, then signals only after
commit. Room29->30 adds meal_state_receipts; it keeps latest canonical record
versions/tombstones and exact input linkage, not free notes or page hashes.
Receipts arriving before the input wait durably. A newer note-free correction
can receive the exact identity from an older acknowledgement without losing its
newer values. Conflicting revisions/identities are durably quarantined, not guessed.
Unchanged receipts are not rewritten. Existing canonical ownership and receipts
are read in batches. Original input alone creates no fabricated prior.

AppContainer owns one conflated wakeup channel. Events are never stored in the
channel: committed inputs/receipts survive signal loss and restart. Startup and
new source events drain up to64 eligible receipts per transaction, yielding
between batches; state reconciliation and applied markers commit together.
Storage failures leave work pending until the next event/startup; there is no
timer, polling or new wake lock. Failure/rejection counters and persisted conflict
counts feed throttled diagnostics without identifiers. Negative/invalid input
pages still reject from the research path. Unknown input IDs are not reconstructed.
Pre-commit input failure, legacy backfill, source-instance changes, merge/split,
conflict resolution/eligibility and retention need release review. The journal
keeps one compact snapshot per canonical record, not every callback. Accepted
runtime orchestration and clinical readiness are not implemented.

`MealStateRepository` persists original input, explicit AAPS identity/revision and
the research posterior separately in normalized Room tables (migration28->29).
It does not infer that food was eaten from its input or acknowledgement. Original
timestamps/gram ranges remain unchanged, inferred onset belongs to the scenarios.
Canonical identity has a unique owner; nearby meals are not matched by time.
Every accepted AAPS revision, including tombstones, invalidates the old posterior.
Older callbacks are ignored and contradictory equal revisions reject. A storage
revision CAS spans both reconciliation and posterior updates. Duplicate samples,
stale results and updates to deleted records cannot overwrite the current state.
All parent/child writes and reads are transactional; bounded reads validate full
distributions/model versions rather than inventing a fallback prior on corruption.
Pending inputs can be stored without any fabricated probabilities. This local
repository has no dependency on therapy writers or notification senders. Runtime
belief updates, merge/split reconciliation and clinical release remain separate work.

`MealStateRepository.observe` adds a transactional posterior update boundary:
storage CAS, exact applied AAPS receipt, conflict quarantine, sample receive age
and causal estimator checks run before the posterior can be written. A staged
correction blocks writes even before the inbox drains. `saveBelief` also checks
known receipts/quarantine, while retaining the offline initialization path with
no receipt. Corrections never invent a replacement prior. SQL failure rolls back
all posterior rows; rejection returns a typed reason without a partial update.
The expected-observation map is copied before suspension. This is persistence,
not authenticated runtime capture: the future producer must supply distributions
made before the CGM sample and verify runtime/calibration provenance. The current
accepted forecast already contains the current CGM and cannot serve as independent
likelihood evidence for that sample. No live caller or notification is added here.

`MealObservationForecast.prepare` now produces a passive, one-step conditional
prediction from the frozen engine. It preserves each discrete hypothesis onset,
including future onset, rather than borrowing the planner's start/delay
interventions. The canonical announced meal is replaced once; original therapy
is untouched. Exact scenario keys and explicit runtime-bound error scales are
required, available no later than the input anchor. Scales have no default and
are not inferred from an ordinary forecast CI. This is not empirical calibration.
All predictions must finish before the next five-minute sample; completion is
checked with the supplied clock. Only that exact sample can form an observation,
with matching runtime/belief and a fresh, trusted value. No rounding/interpolation
or delayed reuse occurs. Equivalent projections share work, without merging their
hypothesis identities. Unexpanded ranges, missing links, clipped paths and excess
work reject rather than drop evidence. A real-engine-to-Room test covers the path.
The passive producer is still conditional on known therapy and fixed historical
state; it does not model unknown future AAPS control or nuisance causes. Runtime
capture is available as described below, but error-model calibration, prior
initialization and stage transitions remain pending. No observation worker or
clinical sender is connected.

`AutomationRepository.mealRuntimeUpdates` is a research-only, bounded SharedFlow.
Under the existing cycle lease it invalidates previous context at cycle entry and
captures only after exact Room readback/finalization. Without a subscriber it
does not read the capture clock, fork the engine or reproduce a forecast. Capture
rechecks sensitivity values/entity, accepted rows/digest, generation/freshness and
calibration model/session identity. Frozen local engine input/output remains
separate from accepted calibrated/control output; reproduction never compares
the raw local forecast to adjusted control forecasts. Cancellation propagates;
ordinary capture rejection cannot abort the existing clinical writers.
The stream has no replay and one DROP_OLDEST buffer slot: it is not a delivery
ledger, a historical sample source or clinical authorization. A late observer
waits for a new cycle. Consumers must independently recheck age/cycle/revision
before using retained work; a snapshot is not permanently current. No production
consumer, background polling, scenario calibration or meal notification is added.

The research scenario matrix accepts up to four explicit `MealInsulinScenario`
schedules. Each meal/start/delay trajectory carries its insulin scenario ID;
the batch retains immutable schedules. No probability is invented for these
schedules. A null argument retains the old known-insulin-only projection and
does not assert that the pump stops. An explicitly empty scenario list rejects.
The product of expanded meal cases and insulin schedules is capped at24 (96 with
reaction-delay variants); work admission includes future insulin convolution.
All anchors/horizons/IDs are checked before matrix execution. Cache identity
includes the actual delivery sequence, not just the scenario label. Identical
schedules reuse calculation but keep separate output identities. These are still
conditional means, not generated/validated AAPS controller scenarios; no safety
or notification gate is relaxed.

`MealFutureInsulinPlan` describes bounded hypothetical delivered impulses,
not commands, basal rates or confirmed AAPS events. The frozen forward engine
uses its existing insulin profile/DIA/onset/ISF kernel to convolve these impulses
on the five-minute grid, adding only future insulin steps. It preserves historical
therapy/Kalman/AR and the original cold-start trend. Null/empty plans retain the
baseline path. Scenario output reports hypotheticalFutureInsulinUnits and the
remaining known plus hypothetical insulin at the horizon. A shifted kernel with
nonzero cumulative effect at age zero rejects nonempty plans rather than implying
effect before delivery. Wrong anchors, omitted out-of-horizon delivery, duplicate
offsets, nonfinite values and more than145 impulses reject, not truncate.
No new projection executes in ordinary production prediction. This is a research
primitive for explicit schedules, not a future AAPS controller/pump simulator;
futureControlSimulated and trajectoryUncertaintyValidated remain false. Automatic
schedule generation, continuous basal semantics and delivery uncertainty remain
pending. No therapy records are fabricated or written.

`MealScenarioExpansion` preserves hypothesis probability mass while expanding
carbohydrate/onset endpoints and each supplied absorption alternative into
explicit cases. Profile weights remain supplied weights; equal endpoint weights
are the versioned research policy `equal-boundary-support-v1`, not learned
probabilities or a guarantee that interior timings are bounded by the endpoints.
Original input, evidence revision and observation metadata are retained. Stable
case IDs map to their immediate parent; output lists/maps are immutable. Budget
overflow, identity collision and probability underflow reject the whole result.
No ranges are silently replaced by their midpoint or discarded. Expanded cases
still need context/reconciliation, intervention semantics and calibrated
uncertainty. The research-only `MealScenarioSimulator.simulateUncertain` entry
expands and simulates as one cancellable operation, returning the expansion and
complete batch together. This retains case weights and immediate parent IDs;
candidate times/reaction delays do not multiply probability mass. Its budget is
at most 24 expanded cases, matching the simulator, with no silent truncation.
An uncertain UPCOMING onset still rejects: intervention time cannot silently
replace the hypothesized future timing. Past onset intervals can be expanded.
There is no production caller or notification authorization.

`MealScenarioSimulator` builds a bounded conditional-mean matrix using the same
start/delay grid as MealTimingPlanner. It requires all six hypothesis kinds and
explicit discrete grams/onset/profile cases linked to one reconciled canonical
meal. It rejects unresolved ranges rather than selecting a midpoint. Upcoming
cases use the candidate time plus reaction delay; past cases keep their supplied
past onset; NOT_HAPPENING/NO_NEW_MEAL contribute zero new food. These last cases
do not model the alternative cause of a glucose change. PREVIOUS_MEAL requires
an explicit past onset and the same canonical linkage; cross-record alias
resolution and historical refitting are not performed here.
Equivalent immutable forecasts are cached only within a batch. Allocation/work
admission occurs before engine execution, cancellation is checked between calls,
and no partial batch is returned. Output carries source cycle/settings and belief
revision, but is deliberately not a MealTimingEnvelope with fabricated CI.
Controller/nuisance scenarios, complete intervention semantics and validated
uncertainty remain separate prerequisites before runtime use.

`MealSimulationContext.forwardForecast` uses a disposable frozen engine and the
existing V3 glucose calculation for a conditional path (60 minutes by default,
bounded to 720 minutes for research). Replacement
is assembled internally from trusted announced-food components by canonical ID.
Future announced steps and residual COB are replaced before meal-pressure
reconciliation; historical known inputs, Kalman updates and AR trend remain
conditioned on observed history (including the cold-start baseline correction).
The requested tail length does not change the first-hour AR scaling, CI or path.
The existing fitted AR parameters, sensitivity and numerical clamps are held
fixed into the conditional tail. This extrapolation is not a validated long-term
glucose model. Nonzero modeled UAM steps reject an extended request because no
long UAM tail is available. Insulin projection checks for known active delivery
outside the engine lookback before simulation. Remaining modeled food/insulin
and numericLimitsReached are explicit; completion of these curves does not prove
complete therapy history, actual absorption or safe timing.
No extra projection is run in ordinary production prediction. Results are immutable;
the normal three pointwise forecast intervals are NOT calibrated scenario-wide
uncertainty. Explicit hypothetical insulin impulses can be supplied separately;
future AAPS control is not simulated. This API does not infer past
onset or reconcile residual UAM identities. It supplies a conditional mean tail,
not the planner's required validated uncertainty/control envelope. Only
research/test callers exist; it cannot authorize notifications.

`MealScenarioFoodProjection` replaces the selected canonical meal's projected
food components, including explicitly linked UAM, before summing the retained
meals and replacement. Unresolved linkage and overlapping retained canonical
meals reject the scenario; time proximity is never used as identity. It does
not discover links or resolve UAM attribution itself. Input curves share one
anchor/grid, results are immutable and work is bounded. Past absorption and
unfinished tails remain separate. `MealComponentProjection` can overlay this
aggregate with the same modeled insulin/CSF without modifying therapy records.
Only test callers currently exist; this is not the full scenario simulator.

Frozen MealSimulationContext now exposes announcedFoodProjection from the engine.
It reuses profileCarbEvents and carbCumulativeWithCutoff, including legacy curves,
per-meal revision overrides and synthetic-UAM exclusion. Trusted canonical IDs
and nonblank revisions are required; duplicate canonical meals reject rather than
sum. MealAbsorptionProjection.fromCumulative validates a bounded monotone CDF and
separates past absorption from future steps. This adapter covers only the existing
announced-food component and existing lookback, not residual UAM, missing therapy
history, future pump actions or complete glucose/scenario uncertainty.

`domain/meal` has research runtime capture, but no production estimator/planner
consumer or therapy writer. MealStateEstimator consumes
scenario predictions made before the observed CGM sample, preserves original
input time and rejects duplicate/correlated or revision-mismatched evidence.
MealTimingPlanner validates a complete scenario/start/delay matrix and its tail,
checks low-risk trajectories before ranking and abstains on incompatible optima.
Conditional eating paths retain low-risk checks even for low-weight worlds.
NOT_HAPPENING is assessed separately: its earliest low-CI point is returned as
noFoodRisk, and a low within the maximum modeled reaction delay yields
NO_FOOD_URGENCY rather than routine meal advice. Candidate waiting cannot consume
that reaction margin. Among mutually near-equivalent feasible starts the earliest
wins; a tiny numerical improvement is not a reason to postpone food. These are
research policy rules, not calibrated clinical thresholds or a live hypo sender.
Its only positive research result is SHADOW_READY, never permission to notify.
The envelope must declare causal, same-cycle/runtime uncertainty support over
the entire evaluated five-minute trajectory and delayed meal tail. Missing,
pointwise-only or shorter support yields UNCERTAINTY_UNSUPPORTED. Provenance is
a producer contract, not proof of calibration: no production producer exists.
The existing AutomationRepository per-horizon residual calibration must not be
relabeled as simultaneous long-horizon coverage. No extrapolation is provided.
The 120-minute clock policy is pure eligibility, not a durable atomic claim.
The integrated planner, calibrated policy and production delivery wiring remain
required. No clinical performance or notification-delivery guarantee is claimed.

`HybridPredictionEngine.forkForMealSimulation()` now copies configured insulin
profile/DIA/onset, sensitivity overrides, carb limits, meal absorption and UAM
contexts, Kalman state/covariance/revision history and residual AR buckets.
The stateless profile estimator retains its captured timezone. Each candidate
must fork the untouched seed; diagnostics and the live logger are not shared.
Capture requires exclusive ownership of the source engine, not concurrent
setters/prediction. The existing dry-run factory is unchanged. This primitive
does not freeze therapy input lists, extend the 60-minute horizon, generate
scenarios, guarantee clinical safety or enable notifications by itself.

`MealSimulationContext.capture` freezes CGM, therapy payload maps and the local
baseline forecast, then reproduces that forecast in a disposable engine copy.
Mismatches, future evidence, unordered samples, stale capture, invalid revision
labels and allocation-budget excess are rejected without truncation. The caller
must own the cycle lock and supply the same cycle's accepted local output and
revision labels; the context does not independently authenticate those labels.
Cancellation is checked before capture, during therapy copying and after the
baseline calculation. No runtime capture call site is wired yet.

`MealAbsorptionProjection` reuses MealAbsorptionCurve for a hypothetical onset,
including a future onset. It keeps already-absorbed grams separate from future
five-minute absorption and reports unabsorbed tail mass explicitly. Up to 720
minutes may be inspected for this carbohydrate component only; this does not
extend the glucose engine's validated horizon or model future insulin delivery.
No future food is inserted into therapy history or treated as an observed CGM.

`MealSimulationContext.insulinProjection` reuses the frozen engine's canonical
CGM processing, sensitivity estimator, event eligibility, insulin profile and
DIA/onset transformation. The first 60-minute insulin steps match the existing
V3 component exactly. Longer component tails report remaining modeled units;
inferred entries remain explicitly counted, not relabelled confirmed delivery.
An active known event outside the engine's 8-hour lookback or an exceeded work
budget rejects the projection. Missing older history cannot be inferred from
this check; input coverage remains an independent runtime admission requirement.
`MealComponentProjection` combines one hypothetical food curve with this insulin
component using the same CSF and requires identical time anchors and horizons.
It is a delta decomposition, not absolute glucose, not future AAPS control and
not an uncertainty envelope. Complete modeled tails do not grant notification
permission or prove adequate therapy coverage. Existing live forecasts unchanged.

## Food Impact Chart

Overview projects the accepted engine's announced-carbohydrate five-minute steps
into cumulative deltas at offsets 0..30 minutes, added to the latest displayed
glucose sample. Yellow starts at that sample and shares the glucose axis; it is
a food-only projection, not the net glucose forecast. Accepted-cycle-tagged telemetry is
published only after acceptance; UI requires matching forecastCycleId, sensitivity
identity and freshness. Missing steps remain unavailable, not a fabricated zero.
No extra inferred UAM contribution, insulin effect, polling or therapy writer is
introduced. Legacy stored forecasts need a new accepted cycle to supply this layer.

## Meal Portion Configuration

MealPortionSettings holds independent SMALL/MEDIUM/LARGE ranges and default
amounts plus a showCalories preference (false by default). AppSettingsStore
validates the complete configuration before its atomic edit; invalid input
leaves prior settings unchanged. Shared historical boundaries select the larger
category. These settings do not override submission caps, Auto UAM limits or
target policy. The advanced settings editor and Overview consume the same saved
settings via MainUiState. MealEntryDialog owns a local, saveable draft with two
radio-choice rows and optional calories. Picture taps never dispatch therapy.
The Food dialog's Send button freezes grams, profile, energy, Eating soon and a
submission ID and invokes Overview's existing manual-meal callback immediately.
There is no secondary confirmation. Manual carbs starts unchecked and exposes
an inline numeric input only when selected; its value is ignored when unchecked.
The exact grams are shown on Send without one-decimal rounding and validated
against the separate manual-meal limit of 80g.
Over-cap defaults require explicit correction, not silent clamping. These UI
changes do not add a new writer or a background task. Independent historical
learning remains an inactive, independently validated stage.

MealCarbLimits separates explicit manual:meal submissions from the configured
20..60g automatic/UAM cap. Only trusted, non-conflicting, valid canonical real
food can raise announced-food modelling and causal recent COB bounds to 80g;
unknown references keep the computation cap. Synthetic UAM generation and
automatic writer bounds are unchanged. This is not clinical validation of the
larger manual amount and does not authorize a phone update.

ManualMealSubmission copies and freezes its command parameters before I/O,
including local profile, portion/provenance, energy and Eating soon metadata.
These fields are persisted locally, not sent as Nightscout treatment fields.
An existing submission ID with changed input is blocked, including after restart;
an exact already-SENT request is acknowledged without a second POST. Existing
arm, target preflight, throttling and uncertain-delivery reconciliation remain.

At enlarged font scales, the compact picture choices use full-width horizontal
rows with stable64dp image frames; normal font retains three equal columns.
The choices and checkbox rows remain scrollable above the fixed Send action.

MealPortionSuggestionRepository is an explicit offline candidate reader, not
registered in AppContainer, Compose or background work. It reads the existing
bounded therapy timeline and matching overrides atomically without resolving or
deleting stale evidence. Conflicts/tombstones are grouped before label conversion.
Only exact canonical revision/quantity and supported COPILOT_UI confirmation
metadata with independent origin can produce labels; legacy origins stay unknown.
The single-entry cache includes usable therapy/evidence revisions, settings,
zone, portion/profile and minute. Availability time is the override confirmation
time, not the meal timestamp. Overflow (>5000 rows) falls back without sampling.

MealPortionValidation performs walk-forward scoring against fixed configured
presets using only labels available before each target. Target categories must
be explicit. It reports MAE and mean positive overestimation by portion and
six-hour local-time block; no labels means absent scores, not zero error.
Neither synthetic tests nor this report enable the learner. Independent real
held-out validation, adequate support and phone acceptance remain open gates.

Confirmation also freezes portion provenance: unchanged numeric proposals are
ACCEPTED_SUGGESTION, edited quantities are USER_CORRECTED, and callers without
a proposal remain UNKNOWN. Metadata follows the existing ManualMealSubmission
and EnergyProfileRepository pending-to-canonical reconciliation path. A retry
cannot change provenance under the same operation ID. Room v27 adds nullable
metadata without retroactively labelling legacy records. The inactive estimator
requires independent evidence matched to canonical identity, revision and grams;
accepted suggestions cannot become training labels.

## September 2026 Source Release

The current publication and server-AI boundaries are documented in
[RELEASE_2026-09-13.md](RELEASE_2026-09-13.md). The baseline sections below
describe the earlier architecture and are not an exhaustive specification of
the current controller or its thresholds.

Accepted NORMAL and SENSITIVITY_SOURCE_CHANGE cycles invoke the widget callback; LOCAL_READ_ONLY does not.
The callback requires accepted Room read-back and a committed runtime snapshot;
ISF/CR settings identity and forecast generation must match.

Server activation is user-initiated and does not add polling. Subscription
validity is independent of inference readiness. The identity factory does not
yet run live Codex jobs, and AI output must never become a therapy command.

## Context
Trusted Telegram delivery is an optional, outbound-only Android observer of locally
delivered alert episodes and completed local report summaries. It never delays local
alerts or calls therapy repositories. Its independent Keystore namespace preserves
the server-AI connection identity and has separate backup exclusions. See
[gentle alerts and Telegram](2026-09-13-soft-alerts-telegram.md) for the pairing,
mute, deduplication and delivery-limit contracts.

AAPS Predictive Copilot is a two-part system:
- Android app (`android-app`) for ingest, local forecasting, safety/rules, automation, and UI.
- Backend (`backend`) for optional cloud prediction override, analysis, replay, and scheduled insights.

## High-level boundaries
- Android is source of truth for local automation loop and outbound actions to Nightscout/AAPS transport.
- Backend is advisory/augmentation layer; cloud forecast may override local per horizon but must not bypass local safety constraints.
- Safety decisioning is deterministic rule/policy based. LLM/OpenAI output is insight-only.
- Local physical activity ingestion can be sourced from broadcasts/devicestatus and from on-device `TYPE_STEP_COUNTER` sensor (with `ACTIVITY_RECOGNITION` runtime permission on Android 10+).

## Android modules (logical)
- `data/local`: Room DB entities/dao for glucose, therapy, telemetry, forecasts, audit.
- `data/repository`: sync, ingestion, automation loop, action dispatch, analytics.
- `domain/predict`: prediction engines (legacy/v3), UAM estimator, profile estimators.
- `domain/predict`: prediction engines (legacy/v3), UAM estimator, profile estimators, circadian pattern engine (`weekday/weekend/all`, `15m` slots, `5/7/10/14d` templates).
- `domain/isfcr`: physiology-aware ISF/CR extraction, base hourly fit, realtime multipliers, confidence/fallback.
- `domain/rules`: adaptive/post-hypo/pattern/segment rules + arbitration.
- `domain/safety`: global policy guardrails.
- `domain/uam inference`: inferred carbs + ingestion time, event state machine, optional boost mode.
- `ui`: Compose screens and state wiring.
- `service/scheduler`: app container, local NS emulation, workers.
- `service/local activity collector`: sensor listener, local activity aggregation, telemetry writes.
- `service/daily reporting`: 24h local forecast quality report generation (Markdown + CSV) with MARD/MAE/RMSE and hotspots.
  - replay attribution block includes factor contributions + coverage for metabolic/runtime drivers (`COB/IOB/UAM/CI`, `DIA`, `activity`, `sensor quality/age`, `ISF quality/confidence`, `set age`, `dawn/stress/hormone/steroid`, context ambiguity).
  - replay attribution additionally includes core-factor regime diagnostics (`LOW/MID/HIGH` bins for `COB/IOB/UAM/CI`) with per-bin MAE/MARD/bias for targeted tuning.
  - replay block also persists structured per-horizon top-miss context (`pred/actual/error + COB/IOB/UAM/CI/DIA/activity/sensorQ`) for targeted tuning.
  - when OpenAI endpoint is configured, daily optimizer runs in background (`responses` API), emits bounded calibration scales to telemetry (`daily_report_ai_opt_*`) and never emits therapy commands.
  - optimizer telemetry always writes neutral fallback values on failure (`apply_flag=0`, scales=`1.0`) so runtime cannot reuse stale tuning from older successful runs.

## Backend modules (logical)
- API endpoints for sync/predict/rules/actions/analysis/replay.
- Scheduler jobs for daily analysis and weekly retraining.
- Persistence layer via SQLAlchemy.

## Integration contracts
- Forecast horizons required in app loop: 5m, 30m, 60m.
- Telemetry units in app domain: mmol/L for glucose and derived glucose deltas.
- Enhanced local forecasting operates on a canonical 5-minute CGM series derived from raw `1..5` minute sensor input before trend/UAM/Kalman calculations.
- Circadian pattern fitting operates on deduplicated canonical glucose history and stores a separate `15-minute` pattern layer for `WEEKDAY`, `WEEKEND`, and `ALL`.
- On-device circadian verification must not depend on copying the live WAL-backed SQLite database; the app surfaces circadian state directly in `Analytics` (`READY / PARTIAL / STALE / EMPTY`, row counts, latest snapshot/replay timestamps, active segment sources).
- Enhanced V3 Kalman filtering consumes deterministic insulin and announced-food
  known input on each canonical interval. Historical UAM input is currently zero;
  active forward UAM instead suppresses positive residual extrapolation before AR.
- Circadian prior is a bounded secondary forecast prior:
  - it is blended after physiology/path simulation,
  - weights are horizon-aware (`5m/30m/60m`) and confidence-gated,
  - prior is attenuated during acute state (`rapid delta`, `COB`, `UAM`, `IOB`) and disabled on stale/suspect sensor states,
  - `30m/60m` may additionally apply bounded reversion toward historical slot median when current glucose sits outside the slot `p25..p75` band and replay quality does not mark the bucket harmful.
- Forecast post-processing may apply recent empirical calibration:
  - center bias from rolling residual mean by horizon/value-bucket,
  - CI half-width calibration from rolling weighted quantiles of absolute residuals,
  - calibration stays causal and uses only past forecast-vs-actual pairs inside the lookback window.
- Action channel priority: Nightscout API primary; local fallback optional.
- Canonical manual Eating Soon intent is durably reserved before waiting on the
  shared temporary-target write mutex. Existing manager manual-command preflight
  sees this pending intent; an already admitted delivery is not preempted.
  Only its first owner cancelled before admission can record known non-delivery;
  duplicates and post-admission uncertainty retain existing idempotency rules.
  Eating Soon keys cannot enter the manager-specific reconciliation retry path.
- UAM carbs export channel: Nightscout treatments (`Carb Correction`) with backdated timestamp and deterministic note tag (`UAM_ENGINE|id=...|seq=...|...`).
- Temp target command must stay in hard range and pass policy checks before sending.
- Automatic temp target writes must also pass an outbound duplicate-throttle at action-repository level:
  - repeated or near-identical targets are blocked for `30 minutes`,
  - materially changed targets may pass immediately,
  - manual commands may bypass the throttle only via explicit manual idempotency prefix.
- Activity-protection override in adaptive rule:
  - if `activity_ratio`/`steps_count` spike indicates active load, temp target is overridden to `7.7..8.7 mmol/L` (load-scaled),
  - after sustained low-load period (`~30 min`) the rule sends recovery back to pre-activity base target.
- Timestamps written to local DB must be normalized and non-zero (`>0`) before persist.
- `cob_grams` and `iob_units` telemetry are allowed to bias local runtime forecasts before rule arbitration.
- Runtime context factors from physiology-aware ISF/CR snapshot (`set/sensor/activity/dawn/stress/hormone/steroid`) are exported to telemetry and applied as bounded forecast context-bias + CI widening before rule arbitration.
- Runtime `cob_grams/iob_units` are resolved with local fallback from therapy history (`insulin profile + carb absorption`) and confidence-weighted merge with external telemetry when both are available.
- External/AAPS raw `COB` remains observable as reference telemetry, but runtime `effective COB` used by forecast/controller must subtract residual synthetic `UAM_ENGINE` carb export before merge to avoid double-counting `UAM + COB`.
- Runtime insulin duration source of truth is profile-derived:
  - selected insulin profile defines base DIA from its action curve,
  - optional daily real-insulin-profile estimate may refine effective DIA via profile-based onset/shape scaling,
  - external/raw `dia_hours` telemetry is observational only and must not override runtime insulin action by itself.
- Activity telemetry is stored in canonical keys when available:
  - `steps_count`,
  - `activity_ratio`,
  - `distance_km`,
  - `active_minutes`,
  - `calories_active_kcal`.
- If `cob_grams >= 20`, adaptive runtime base target is forced to `4.2 mmol/L` (within hard target bounds) for automation decisions.
- Daily worker must generate a local 24h forecast report from on-device data even when cloud analysis is unavailable.
- In OpenAI mode, daily worker may run optimizer-only path (without custom cloud backend) and persist conservative calibration tuning for runtime forecast-bias stage.
- Runtime applies AI tuning only when payload is fresh (`<=36h`), confidence-gated, sample-coverage-gated and not blocked by high ISF/CR quality-risk level.

## Architectural decisions
- Keep prediction engine strategy switchable via flags (legacy vs enhanced versions).
- Keep prediction inputs causally correct:
  - local forecast/runtime may use only therapy events with `ts <= prediction_now`,
  - future therapy events are invalid for both runtime and offline replay.
- Keep latent UAM separated from exported synthetic carb treatments:
  - `synthetic=true` / `source=uam_engine` / `UAM_ENGINE|...` tagged carbs are excluded from announced-carb prediction and ISF/CR training paths,
  - duplicate guards may still treat them as existing UAM coverage to prevent re-creation of the same meal episode.
- Keep UAM runtime contour unified:
  - `UamInferenceEngine` remains the source of active meal-event hypotheses (`ingestionTs/carbs/confidence`),
  - `HybridPredictionEngine` consumes the active inferred event as the preferred virtual-meal hint for forecast UAM steps,
  - runtime telemetry publishes a single resolved UAM state (`uam_runtime_*` and `uam_value`) so controller/UI do not reconcile independent inference and forecast branches.
- Keep insulin action profile configurable and persisted in settings; default profile is NOVORAPID.
- Keep controller/rule execution idempotent by time buckets and command keys.
- Keep ISF/CR controlled activation auditable:
  - KPI evaluation event `isfcr_shadow_activation_evaluated`,
  - promotion event `isfcr_shadow_auto_promoted`,
  - evaluation cadence limited (not executed on every cycle tick).
- Keep ISF/CR realtime context factors physiology-aware and toggle-safe:
  - `useManualTags=false` disables only manual tag influence (`stress/illness/hormonal`) without disabling latent telemetry stress signals,
  - sensor wear age (`sensor_change`) contributes a bounded `sensor_age_factor`,
  - activity modifier can derive `steps_rate_15m` from local `steps_count` telemetry when direct rate is absent.
- Keep manual physiology tags operationally usable from UI:
  - quick-tag presets support adjustable severity and duration,
  - quick-tag aliases are normalized (`hormonal_phase -> hormonal`, `steroid -> steroids`) before persistence,
  - tags can be closed individually (`closeById`) without clearing all active context tags.
- Keep ISF/CR uncertainty conservative under ambiguity:
  - confidence model applies explicit penalties for high infusion-set age, high sensor age, context ambiguity (`UAM/stress/manual tags`), and `sensor_quality_suspect_false_low`,
  - CI width is expanded by the same ambiguity penalties to reduce unsafe overconfidence.
- Keep manual `steroids`/`dawn` tags connected to runtime physiology model:
  - `manual_steroid_tag` affects sensitivity through `steroid_factor`,
  - `manual_dawn_tag` affects morning resistance through `dawn_tag_factor` (merged into `dawn_factor`),
  - these factors are included in realtime factor trace and ambiguity penalties.
- Keep ISF/CR evidence weighting wear-aware:
  - evidence sample weight is `quality_score * wear_weight`,
  - `wear_weight` combines infusion-set and sensor-age decay from the latest `set_change`/`sensor_change` markers (neutral `1.0` when markers are absent).
- Keep CR evidence extraction quality-gated by telemetry and CGM continuity:
  - meal+bolus candidate requires bolus window `[-20m, +30m]` around meal,
  - CR windows are dropped on gross CGM gaps (`max gap > 30m`),
  - CR windows are dropped under high sensor-blocked telemetry or strong UAM ambiguity telemetry,
  - dropped reasons are persisted in ISF/CR diagnostics/audit for explainability.
- Keep UAM inference/export cycle on 5-minute buckets even with 1-minute CGM input.
- Keep UAM export idempotent via `id+seq` tags and remote fetch dedup before post.
- Keep carbohydrate absorption event-aware:
  - food catalog classes (`FAST`, `MEDIUM`, `PROTEIN_SLOW`),
  - event classification by payload text/cross-language aliases and glucose pattern fallback,
  - dynamic cumulative absorption curve per event for therapy and UAM residual calculations.
- Keep residual carbs visible in diagnostics (`now`, `30m`, `60m`, `120m`) for model explainability.
- Keep COB/IOB influence explicit and deterministic:
  - forecast bias is horizon-aware (`5/30/60`) and bounded,
  - adaptive controller receives normalized `COB/IOB` telemetry as additional control inputs.
- Keep forecast context factor influence explicit and bounded:
  - context-bias uses only normalized factors (`set/sensor/activity/dawn/stress/hormone/steroid + pattern window`),
  - value shift and CI inflation are horizon-aware and clamped,
  - low sensor quality applies additional displacement guard relative to current glucose.
- Keep per-cycle observability of forecast inputs:
  - audit event `forecast_factor_coverage` reports availability/usage of ISF/CR, DIA, COB/IOB, UAM, sensor/activity/context factors, pattern/history, and whether bias stages were applied.
- Keep forecast history long enough for month/year analysis (`~400 days` retention in app DB).
- Analytics ISF/CR history must render three independent layers instead of a merged line:
  - `Compensation-derived evidence` from history/runtime evidence path,
  - `Copilot fallback runtime` from the app fallback/base path,
  - `AAPS raw` from telemetry,
  so users can see which value is inferred, which one is fallback, and which one came directly from AAPS.
- Analytics ISF/CR charts must allow per-line visibility control in UI and may overlay normalized support traces (`COB`, `UAM`, `activity_ratio`) on the same time axis:
  - primary ISF/CR axis remains in native ISF/CR units,
  - overlay lines are auto-scaled independently inside the visible window for readability,
  - overlay series are diagnostic only and do not replace primary ISF/CR values.
- Circadian analytics persist a separate pattern store:
  - `circadian_slot_stats` for `15m` slot percentiles and telemetry overlays,
  - `circadian_transition_stats` for `+15/+30/+60` deltas and residual forecast bias,
  - `circadian_pattern_snapshots` for requested day-type resolution (`weekday/weekend/all`, fallback reason, stable/recency windows),
  - `circadian_replay_slot_stats` for replay-aware forecast quality by `dayType × windowDays × slotIndex × horizon`.
- Replay quality fit for `circadian_replay_slot_stats` is bootstrap-evaluated:
  - it measures the circadian template itself without runtime replay gating,
  - it uses only `low-acute` rows where circadian actually produced a non-zero shift,
  - runtime replay buckets are later allowed to downweight or disable harmful slots, but that gating must not poison the stored replay-quality estimates.
- Runtime `circadian_v2` may apply a small positive boost only for high-quality `HELPFUL` replay buckets:
  - the boost is allowed only in near-low-acute conditions,
  - it requires minimum replay sample count and strong `winRate/maeImprovement`,
  - harmful and insufficient buckets never receive this boost,
  - all forecast displacement clamps remain unchanged.
- Circadian analytics state is self-healed independently from profile analytics:
  - `Analytics`/`AI Analysis` route entry first checks whether circadian tables are missing or stale,
  - replay tables are also considered unhealthy when qualified buckets still look like legacy zero-quality rows (`maeImprovementMmol == 0` and `winRate == 0`),
  - if so, a bounded circadian-only rebuild runs with capped lookback (`<= 21d`) and refreshes both circadian tables and derived `pattern_windows`,
  - circadian self-heal reads only a bounded whitelist of circadian-relevant telemetry keys rather than the full telemetry history,
  - the same bounded entity lists are reused for both pattern fit and replay fit,
  - self-heal must fail safe and emit audit instead of crashing the app process,
  - this path does not force a full profile/ISF-CR rebuild,
  - `Overview` cold-start must not pay the circadian repair cost.
- Existing `pattern_windows` remain a derived compatibility layer for rule-engine consumers; they are rebuilt from the selected circadian stable snapshot rather than acting as the primary pattern source.
- Keep analytics snapshots historically:
  - `pattern_windows` append snapshots and query latest per (`dayType`,`hour`) for runtime.
  - `profile_segment_estimates` is rebuilt atomically on each analytics recalculation and keeps only the latest clean segment rows used by runtime.
  - `profile_estimates` stores `active` record and timestamped snapshots (`snapshot-*`), but analytics rebuild must remove legacy telemetry-polluted rows before publishing new profile values.
  - `isf_cr_snapshots` stores realtime effective ISF/CR with CI/confidence/factor trace.
  - `isf_cr_evidence` stores extracted ISF/CR evidence windows and quality weights.
  - `isf_cr_model_state` stores active hourly base model.
  - `physio_context_tags` stores user/system context tags (`stress/illness/hormonal/...`).
- Keep Room schema transition from `v9 -> v10` non-destructive:
  - explicit migration creates `isf_cr_*` and `physio_context_tags` tables/indexes,
  - destructive fallback is allowed only for legacy start versions (`1..8`).
- `HybridPredictionEngine` accepts optional runtime ISF/CR override from physiology-aware snapshot:
  - `ACTIVE` confident mode applies full override (`blendWeight=1.0`),
  - `SHADOW` confident mode applies conservative soft-blend (`~0.25..0.65`) to improve forecast sensitivity without full controller promotion,
  - low-confidence snapshots remain blocked by fallback chain.
- Local forecast sensitivity inside `HybridPredictionEngine` should prefer `ProfileEstimator` as the single history-based ISF/CR source; legacy inline heuristic is retained only as safe fallback when estimator cannot resolve values.
- `ProfileEstimator` must treat telemetry ISF/CR as latest prior/fallback rather than as repeated observation stream:
  - only the newest telemetry value per family (`ISF` / `CR`) participates in estimator blending,
  - sufficiently populated local history is not overwritten in `FALLBACK_IF_NEEDED`,
  - sparse hour/segment windows may shrink toward global prior for stability.
- Nightscout treatment ingest must be payload-aware on both remote sync and local Nightscout server paths:
  - `eventType` labels such as `Bolus`, `Bolus Wizard`, `Meal Bolus`, `Correction Bolus`, `Carb Correction`, and partial/blank rows are normalized using both label and payload (`insulin`, `carbs`, `enteredInsulin`, `enteredCarbs`),
  - imported treatment payloads persist `eventType` and `source`, so later repair/reclassification can recover real insulin/carbs therapy rows without relying on future events.
- Shadow auto-activation (optional) may promote ISF/CR mode from `SHADOW` to `ACTIVE` only when KPI thresholds pass on recent `isfcr_shadow_diff_logged` audit history.
- Optional shadow auto-activation can additionally enforce daily quality gate (24h):
  - requires latest daily report metrics (`daily_report_mae_30m`, `daily_report_mae_60m`, `daily_report_matched_samples`),
  - requires CI calibration bounds (`daily_report_ci_coverage_30m_pct`, `daily_report_ci_coverage_60m_pct`, `daily_report_ci_width_30m`, `daily_report_ci_width_60m`),
  - enforces daily ISF/CR data-quality risk gate from telemetry (`daily_report_isfcr_quality_risk_level`) and blocks promotion at settings-backed threshold (`2=MEDIUM` or `3=HIGH`, default `3`),
  - if numeric risk level is missing, runtime falls back to parse latest `daily_report_isfcr_quality_risk` text label (`LOW/MEDIUM/HIGH`),
  - risk gate audit includes risk-level source (`numeric` / `text_fallback` / `missing_or_unknown`) for diagnostics,
  - requires 24h hypo-rate below configured bound,
  - logs `isfcr_shadow_quality_gate_evaluated` before any promotion.
- Shadow auto-activation additionally enforces rolling replay quality gate (`14d/30d/90d`):
  - consumes telemetry `rolling_report_{14|30|90}d_*` (MAE/CI/matched samples for `30m/60m`),
  - requires configured minimum available rolling windows (`1..3`) and all available windows to pass relaxed thresholds,
  - rolling relax factors (`MAE/CI-coverage/CI-width`) are settings-backed and clamped before runtime use,
  - logs `isfcr_shadow_rolling_gate_evaluated` with per-window diagnostics.
- Shadow auto-activation also enforces day-type stability gate from realtime ISF/CR audit:
  - uses `hourWindow*SameDayType` and `hourWindow*Evidence` diagnostics from `isfcr_realtime_computed`,
  - blocks promotion when same-day-type ratio is too low or day-type sparse-rate is too high,
  - logs `isfcr_shadow_day_type_gate_evaluated` with reason and aggregated ratios.
- Shadow auto-activation additionally enforces sensor-quality stability gate from realtime ISF/CR audit:
  - uses `qualityScore`, `sensorFactor`, `wearConfidencePenalty`, `sensorAgeHours` diagnostics from `isfcr_realtime_computed`,
  - uses `sensorQualitySuspectFalseLow` / reason `sensor_quality_suspect_false_low` to aggregate false-low instability rate,
  - blocks promotion when mean quality/sensor factor degrades, wear penalty is too high, sensor-age-high rate spikes, or suspect-false-low rate is above threshold,
  - logs `isfcr_shadow_sensor_gate_evaluated` with reason and aggregated sensor metrics (including false-low rate).
- Sensor-quality gate thresholds are user-configurable from Settings (`ISF/CR auto-activation`) with safe clamps in persistence/runtime.
- Day-type stability gate thresholds are user-configurable from Settings (`ISF/CR auto-activation`) with safe clamps in persistence/runtime.
- CR evidence integrity thresholds are user-configurable from Settings (`ISF/CR engine`):
  - max allowed CGM gap in meal-window (`cr max gap minutes`),
  - max allowed sensor-blocked telemetry share,
  - max allowed UAM-ambiguity telemetry share,
  with safe clamps before persistence/runtime mapping.
- Daily forecast report telemetry also publishes uncertainty calibration metrics per horizon:
  - `daily_report_ci_coverage_{5|30|60}m_pct`,
  - `daily_report_ci_width_{5|30|60}m`,
  enabling CI coverage/width tracking in Analytics and activation diagnostics.
- Daily forecast report telemetry also publishes rolling replay KPI windows:
  - `rolling_report_{14|30|90}d_*` (matched samples + MAE/RMSE/MARD/Bias + CI coverage/width for 5/30/60),
  enabling medium/long-horizon drift tracking beyond 24h in Analytics.
- Daily forecast report telemetry also publishes replay factor-regime payload:
  - `daily_report_replay_factor_regime_json` with per-horizon core-factor buckets (`LOW/MID/HIGH`) and per-bucket `mean/MAE/MARD/Bias/n`,
  enabling direct detection of high-load factor zones where forecast error escalates.
- Daily forecast report telemetry also publishes replay core-factor pair regimes:
  - `daily_report_replay_factor_pair_json` with per-horizon pair quadrants (`LOW/HIGH` × `LOW/HIGH`) for
    `COB×IOB`, `COB×UAM`, `COB×CI`, `IOB×CI`, `UAM×CI`,
    including `meanA/meanB/MAE/MARD/Bias/n`,
  - plus per-horizon quick summaries:
    - `daily_report_replay_top_pair_{5|30|60}m`,
    - `daily_report_replay_top_pair_hint_{5|30|60}m`,
  enabling detection of combined-factor zones where error rises only when factors co-occur.
- Daily forecast report telemetry also publishes replay error-cluster diagnostics:
  - `daily_report_replay_error_cluster_json` with per-horizon top day-type hour-clusters (`dayType`, `hour`, `MAE`, `MARD`, `Bias`,
    mean `COB/IOB/UAM/CI width`, dominant normalized factor),
  - plus per-horizon quick summaries:
    - `daily_report_replay_error_cluster_{5|30|60}m`,
    - `daily_report_replay_error_cluster_hint_{5|30|60}m`,
  enabling faster triage of stable high-error windows (beyond single top-miss points).
- Daily forecast report telemetry also publishes replay weekday/weekend gap diagnostics:
  - `daily_report_replay_daytype_gap_json` with per-horizon strongest `WEEKDAY` vs `WEEKEND` hour gaps
    (`ΔMAE/ΔMARD`, weekday/weekend MAE & sample counts, worse-day-type factor context),
  - plus per-horizon quick summaries:
    - `daily_report_replay_daytype_gap_{5|30|60}m`,
    - `daily_report_replay_daytype_gap_hint_{5|30|60}m`,
  enabling direct triage of hour windows where one day-type systematically underperforms.
- Android analytics also builds an on-device circadian replay summary:
  - it compares `baseline` versus `baseline + circadian prior` for `24h` and `7d`,
  - it uses deduplicated glucose, stored forecasts, telemetry, and persisted circadian tables,
  - if a stored forecast row already contains `|circadian_v1` or `|circadian_v2`, replay reconstructs the baseline by inverting the circadian blend before scoring,
  - the resulting `MAE30/MAE60` summary is surfaced in both `Analytics` and `AI Analysis`.
- Circadian runtime bias is replay-aware in `circadian_v2`:
  - `30m/60m` weights are additionally modulated by persisted replay bucket quality (`HELPFUL/NEUTRAL/HARMFUL/INSUFFICIENT`),
  - if the exact replay bucket for the active `15-minute` slot is `INSUFFICIENT`, runtime may resolve replay quality from bounded neighbouring replay buckets within `±2` slots (`±30 minutes`) using distance weights, while keeping the glucose-template slot itself unchanged,
  - horizon weight also depends on slot stability and horizon-specific transition quality,
  - current out-of-band glucose may add bounded `median reversion` toward slot median for `30m/60m`,
  - replay residual bias is applied only on `30m/60m` and is hard-clamped,
  - analytics exposes current-slot replay diagnostics (`bucket status`, `win rate`, `MAE baseline -> circadian`, fallback-to-`ALL`).
- `AI Analysis` intentionally exposes only operator windows `3d / 5d / 7d / 30d`:
  - screen-level source/status/week filters are removed from UI,
  - history/trend fetches still use the existing repository APIs internally with fixed `source=all`, `status=all`, and derived trend window.
- OpenAI daily optimizer parsing is tolerant to multiple `Responses API` structured-output shapes:
  - raw `output_text`,
  - nested `output[].content[].text`,
  - `text.value`,
  - `parsed` / `json`,
  - function-style `arguments`.
- OpenAI daily optimizer transport uses a longer timeout budget than normal chat:
  - chat remains on the short client,
  - optimizer request/polling uses dedicated extended read/call timeout because background `responses` jobs are slower and should not be misclassified as local `BLOCKED` solely due to transport timing.
- `AI Analysis` keeps the AI chat composer pinned at the top of the screen as the primary interaction surface:
  - text turns use the standard chat path,
  - image attachments are sent to the OpenAI `Responses API` as `input_image`,
  - text-like file attachments are reduced to bounded local previews and injected as attachment context,
  - voice mode is a chained OpenAI flow: `audio/transcriptions` (`gpt-4o-mini-transcribe`) -> normal AI chat answer -> optional `audio/speech` (`gpt-4o-mini-tts`) playback.
- `AI Analysis` warm-up is route-lazy:
  - `cloud jobs`, `analysis history/trend`, and `local daily report generation` are not started from `MainViewModel.init`,
  - they are triggered when the user opens the `AI Analysis` route,
  - this keeps cold-start on `Overview` lighter and avoids paying AI refresh cost on every app launch.
- Primary bottom navigation is intentionally kept lean:
  - `Overview / Forecast / Analytics / Safety`,
  - `Analytics` stays in the primary bar because it is used as an operational screen, not a rare overflow tool.
- `UAM` is no longer a primary destination:
  - `UAM` summary and event actions live inside `Audit Log` under a dedicated `Log / UAM` switch,
  - this keeps UAM event review attached to audit context instead of occupying a separate main tab.
- `Overview` replaces the app-health banner with a quick `Base target` banner:
  - operator can adjust the base target in `0.1 mmol/L` steps without leaving the screen,
  - stale-data / kill-switch state is still visible in the same banner,
  - full app-health banner remains available on non-Overview routes.
- ISF/CR runtime diagnostics must include dropped evidence reason counters (per extractor reason code) and expose them in both audit payload (`droppedReasons`) and Analytics diagnostics UI.
- Realtime ISF/CR sensor-quality false-low telemetry (`sensor_quality_suspect_false_low`) is treated as ambiguity signal:
  - adds explicit reason-code in snapshot diagnostics,
  - reduces confidence/quality and widens CI bounds in the same cycle.
- Analytics Quality tab must expose dropped evidence reason summaries aggregated over `24h` and `7d` audit windows to support data-quality triage.
- Analytics Quality tab must expose wear-impact summaries aggregated over `24h` and `7d` (`set/sensor age`, wear high-rate, mean wear factors, ambiguity/penalty) using realtime ISF/CR audit metadata.
- ISF/CR realtime computation applies hour-window evidence minimums (`minIsfEvidencePerHour` / `minCrEvidencePerHour`) around local hour (`±1h`), with explicit diagnostics (`hourWindow*`, `min*`) and low-confidence fallback reasons when minima are not met.
- Hour-window minima are configurable from app settings and propagated through `AppSettings -> IsfCrSettings` (bounded range `0..12`) for runtime tuning without code changes.
- ISF/CR realtime evidence blending is day-type aware:
  - weighted recent evidence applies additional weight for same day type (`WEEKDAY/WEEKEND`) and reduced weight for opposite day type,
  - runtime reasons include `isf_day_type_evidence_sparse` / `cr_day_type_evidence_sparse` when hour-window evidence exists but same day-type evidence is absent.
- Quality analytics for ISF/CR include day-type stability signals:
  - same-day-type ratio in hour-window evidence,
  - frequency of day-type sparsity flags,
  alongside wear-impact diagnostics.
- `glucose_samples` are treated as a timestamp-canonical signal:
  - runtime readers in UI, analytics, local Nightscout responses, calibration, replay, and daily quality gates must deduplicate by `timestamp` using the shared source/quality winner policy from `GlucoseSanitizer`,
  - background maintenance may physically delete losing duplicate rows, but logical readers must still apply sanitizer so historical mixed-source datasets remain stable.
- Nightscout treatment import is payload-first for therapy reconstruction:
  - DTO parsing must preserve `enteredInsulin/enteredCarbs` aliases (`bolusUnits`, `insulinUnits`, `mealCarbs`, `grams`),
  - treatment type normalization may upgrade generic labels (`Bolus`, `Bolus Wizard`, `Carb Correction`) into `correction_bolus` / `meal_bolus` / `carbs` only from the imported treatment payload itself,
  - local Nightscout server and remote Nightscout sync must use the same payload builder and normalizer so replay/runtime see identical therapy semantics.
- Nightscout therapy repair is incremental and idempotent:
  - after each treatment sync, recent Nightscout/local-Nightscout rows may be re-normalized from persisted payload,
  - repair may only change therapy `type`; it must not mutate timestamps, ids, or payload provenance.
- If real insulin-like Nightscout history is still missing, treatment sync must enter recovery mode:
  - `created_at` backfill widens to the bootstrap lookback window,
  - fetch count is elevated to bootstrap scale even outside the initial bootstrap attempt,
  - recovery remains causal and read-only; it only increases history visibility so real boluses can be imported and reclassified.
- Profile analytics rebuild is reset-based:
  - `profile_segment_estimates` are cleared before each recomputation and repopulated from current evidence only,
  - `profile_estimates` legacy telemetry-polluted snapshots are deleted before publishing rebuilt estimates, so runtime ISF/CR never mixes stale telemetry-inflated rows with fresh history.
  - profile-state self-heal is route-lazy:
    - rebuild triggers when active profile is missing, telemetry-polluted, older than `12h`, or when profile segments are missing/stale,
    - opening `Analytics` / `AI Analysis` runs a profile-only rebuild (no pattern recomputation) capped to `90d`,
    - ordinary `Overview` launch does not block on this repair path.
- Runtime DIA is profile-anchored and slow-moving:
  - selected insulin profile remains the base duration source of truth,
  - a real-profile insulin fit may publish `dia_real_raw_hours` from historical onset/shape evidence,
  - effective DIA is a confidence-weighted blend of profile DIA and raw real-profile DIA, clamped to `50%..150%` of the selected profile duration.
- Realtime ISF/CR fallback is metric-aware:
  - low-confidence snapshots may keep `CR` or `ISF` from compensation-derived runtime candidate if that metric has strong global evidence and acceptable quality,
  - missing/weak metrics still fall back independently,
  - overall snapshot may remain `FALLBACK` while preserving the stronger metric to avoid collapsing both values to AAPS/default fallback.
  - realtime extraction must ignore control-noise therapy rows such as `temp_target`; only insulin-like, carb-like, and set/sensor marker events are allowed into the fast evidence path.
- Compensation-derived ISF confidence is evidence-structure aware:
  - confidence saturates from separate `ISF` and `CR` evidence counts rather than a single slow total-count ramp,
  - strong hour-window/global evidence and same-day-type support can raise runtime confidence,
  - missing one metric (`ISF` or `CR`) still applies a cross-metric penalty so `CR-only` or `ISF-only` windows do not masquerade as a full operational snapshot.
- Synthetic `UAM_ENGINE` carbs must not poison correction-window ISF evidence:
  - `extractIsfSample()` ignores synthetic UAM carbs when checking `carbsAround` contamination,
  - this preserves real correction evidence even when the UAM contour exported tagged carb entries nearby.
- Low-confidence realtime ISF/CR may promote to `SHADOW` without going `ACTIVE`:
  - only when both metrics have keepable compensation-derived candidates, sensor false-low gate is clear, and quality/confidence exceed the soft-shadow floor,
  - runtime application still remains blocked in `SHADOW`; this is a visibility/diagnostics mode, not a dosing-control override.
- Realtime ISF/CR evidence extraction is not allowed to starve on `temp_target` noise:
  - the cheap realtime path now scans a `72h` glucose/therapy horizon,
  - if the raw therapy query is saturated but too few relevant insulin/carb/set/sensor rows survive filtering, the repository widens the therapy scan before extracting evidence,
  - this keeps compensation-derived evidence based on real therapy density rather than on how many `temp_target` rows were written recently.
- Real-profile insulin fit is not recomputed every cycle:
  - a stable estimate is recomputed on a `72h` cadence (or earlier on version mismatch / empty fallback),
  - telemetry keepalive may still republish the latest estimate hourly so UI/runtime diagnostics stay fresh without re-running the expensive fit.

## Open architecture questions
- Formal plugin contract for adding new prediction engines without touching automation repository.
- Unified backend lint/typecheck standards to match Android quality gate strictness.
