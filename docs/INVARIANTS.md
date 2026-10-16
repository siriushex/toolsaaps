# INVARIANTS

## Bounded Native Context Echoes

- Native pipe and parser share the finite 6*131072+1024-byte line bound so an
  already admitted full context can return as an escaped user-message event.
- This does not raise the 128 KiB input, 1 MiB aggregate stream, 2048-event or typed
  answer limit. Oversized, malformed, foreign, tool and unknown frames still
  fail closed with fixed codes; no raw content enters errors or metadata.
- Two full-size echo notifications must cross actual child pipes and the Unix
  supervisor path without a second turn. Stop/IO confirmation remains required.
- This source fix alone grants no deployment, provider call, daily schedule,
  medical-data transfer, clinical validity or therapy authority.

## Independent Observation Authority

- Observation is explicit, defaults off and is bound to the no-backup install
  identity. Restored preferences, equipment selection and general settings writes
  cannot grant it. Failed/stale writes do not report successful activation.
- Observation permission never grants treatment authority. Every existing relay,
  target and dose transport gate remains therapy-only. Existing armed users are
  not silently disarmed by setup or observation controls.
- An explicit observation choice permanently excludes automatic legacy therapy
  arming for that installation, even after observation is turned off. The existing
  explicit two-step therapy confirmation remains available and authoritative.
- Independent background cycles use LOCAL_READ_ONLY: no remote refresh, clinical
  writer, action-repository access, alert/AI publication or sensitivity maintenance.
  Queued observer work cannot become normal work after subsequent therapy arming.
- Disable/power-save cancel observer work; restart reads persisted permission.
  Reactive local work cannot cancel a currently permitted observer merely because
  therapy is disarmed. Android execution/signal delivery is not guaranteed.
- Source-specific trust, TLS migration, permissions and freshness gates remain.
  Source ingestion settings remain independent of the background runtime switch.
  Manual diary and blood checks retain provenance and never fabricate CGM/IOB/COB.

## Local Manual Diary

- A local entry reports food eaten or insulin already taken. Saving never sends
  a treatment command, confirms hardware delivery, enables therapy or uploads it.
- Manual diary data is stored separately from therapy/CGM/telemetry tables and
  is not counted as observed IOB/COB or used by Target Manager/predictions.
- Stable identity and expected revision prevent stale replacement and duplicate
  creation after a lost commit acknowledgement. Retry cannot silently change
  the payload. Corrections/deletions/restores preserve prior revisions.
- Kind, gram/unit semantics, original occurrence time/time zone and manual source
  remain explicit. Future planned events, nonfinite/negative/zero values and
  missing injection identity/type are rejected; no suggested dose is populated.
- Deleted records remain locally recoverable, as disclosed before confirmation;
  deletion is not physical erasure or cancellation of delivered insulin.
- Migration34->35 is additive and preserves clinical tables. No new destructive
  fallback. An older APK cannot open the migrated DB; use forward repair.

## First-Start Setup

- Equipment selection and checklist completion never arm/disarm therapy, change
  targets, fabricate IOB/COB, emit CGM points, send food or upload medical data.
- Manual readings keep their existing blood-check provenance. Sparse entries
  are not continuous monitoring and cannot certify prediction/alert readiness.
- Only explicit user action opens the existing two-confirmation therapy flow.
  Existing arming and all transport guards remain authoritative; no-pump choices
  cannot silently stop previously authorized therapy or imply that it stopped.
- Failed/stale saves cannot advance/close the wizard as successful. Persisted
  Later/Complete stays dismissed; unknown install metadata does not interrupt an
  existing setup. Incomplete setup cannot block an already-armed runtime startup.

## Priority Reservation Cancellation

- Manual Eating Soon registration captures FIRST_RESERVED ownership before
  cancellation may discard a committed Room return. Cancellation is rechecked
  before queue admission; only the exact first owner proven unsent is blocked.
- The cancellation-shielded section is local registration, not mutex admission,
  delivery or retry. A cancelled duplicate cannot withdraw another owner, and
  cancellation after transport starts retains unknown/no-replay semantics.

## Scoped Runtime Acknowledgement

- The production notification binds its immutable action to an ephemeral owner
  session plus exact source/generation/ordinal/level. The non-exported receiver
  may acknowledge an existing owner only; it never starts one or grants consent.
- New alarm consent is installation-bound, defaults off and is not writable by
  generic clinical settings updates. It cannot enable therapy or observation.
- System wake tokens are not medical evidence. Reboot, clock drift, replacement,
  expiry, OFF and lost source authority prevent old-event replay. There is at
  most one critical AlarmClock, with no catch-up burst.
- An unknown local INITIAL timeout is not a decline. Native fallback requires
  either no admitted owner or completed confirmed cleanup. Notification, audio
  and vibration outcomes remain separate, and hearing is never inferred.

- Only the current explicitly running coordinator accepts acknowledgement; the
  command cannot start an owner or replace user opt-in. One pending intent is
  bounded and tied to exact source/generation/ordinal/level and current boot.
- Exact audio authority is revoked before Room waits. A claim callback must
  notice its pending exact acknowledgement before starting hardware effects.
  A stale action cannot cancel a different ordinal, generation or source.
- Audio/volume and terminal-journal cleanup finish before the pause transaction.
  Unconfirmed cleanup cannot become a successful acknowledgement or healthy owner.
- Current source, freshness and settings are rechecked around persistence. Lost
  authority rolls back the transaction, not just the return value. Another risk
  source keeps its playback; higher risk is not acknowledged by an older action.
- Receiver cancellation does not retract an already accepted intent. Runtime
  shutdown settles pending replies without claiming that a pause was persisted.
- Pending-claim waits use current source expiry. Expired acknowledgement cannot
  spin on a past deadline; every runner iteration checks cancellation.
- Duplicate actions never slide a pause. Configured critical/soft repeat, global
  OFF and worsening semantics remain unchanged. No notification posting implies
  acknowledgement, audio success or hearing; this API alone activates no runtime.

## Alert Control Intent Revocation

- Valid OFF/resume/toggle intent advances the shared database control revision
  before any Room wait. Invalid duration does not revoke an active delivery.
- Queued work and each admitted side-effect child bind to that revision. New
  controls cancel only that child; parent cancellation/fatal errors retain their
  original meaning and are never converted into normal continuation.
- Persistent control changes remain serialized after exact child cleanup. The
  immediate audio/vibration stop runs first; an ordinary hardware-stop error
  cannot skip the Room write. Failed writes never claim a successful OFF.
- Control cancellation records failure, not audio-start/hearing/acknowledgement.
  It neither fabricates a new clinical INITIAL nor dispatches a postcommit AI
  request as if the cancelled attempt succeeded.
- Cached glucose source admission requires its publication control revision,
  including across multiple owners of the same database. Ending a control or
  resuming cannot make old cached evidence current again.
- No new repeat owner, permission, default, therapy behavior or platform wake
  scheduling is activated by this prerequisite repair.

## Accepted Alarm Source Provenance

- Only accepted current decisions bound to committed matching OPEN episodes may
  publish glucose/diagnostic sources. Persisted events and UI acceptedAt do not
  authorize repeats; a latched severity is not the current confirmed candidate.
- Source expiry derives from existing measurement/forecast freshness. Duplicate
  CGM cannot slide either wall/elapsed deadline or reset generation/queue age.
  Unknown/changed boot, backward clocks, >5s disagreement and expiry revoke.
- Shared source mechanics do not detect clinical risk or grant hardware access.
  Future admission still requires current settings, authoritative Room OFF,
  platform capabilities and an explicit running single-audio owner.
- New data revokes old publication before persistence. Storage/read/cancellation
  failure cannot leave cached authority; stale failure tickets cannot revoke a
  newer publication. Cancellation/fatal errors are cleaned up then propagated.
- The default native initial delivery and receipts remain first. The explicit
  handoff may read source authority after committed claim but before its callback;
  neither authority nor notification success implies confirmed audio STARTED.
  Optional source readback is bounded; its error/timeout never replays INITIAL.
  Failed diagnostic posting does not itself resolve confirmed clinical risk.
- OFF/resume/toggle attempts revoke all feeds before writes. OFF expiry, restart
  and display refresh alone never republish. No extra timer, permission, sound,
  therapy action or production coordinator activation accompanies this stage.

## Native Audio Handoff

- Delivery ordering must not hold the persistence mutex across physical side
  effects. Local transactions independently reject pending/changed control intent;
  splitting locks must not reopen the gap before the persistent OFF write.
- A local transport lease is acquired before volume baseline capture. Old native
  boost restoration finishes first. Denied admission never steals urgent playback.
- Player stop alone does not release the external volume lease. New native/preview
  playback waits for exact-owner cleanup; unconfirmed cleanup never authorizes it.
- Confirmed transport release does not wait for the journal mutex. A blocked or
  failed terminal write is not audio ownership and cannot suppress an otherwise
  admitted urgent native replacement; its failure remains independently visible.
- Request fencing remains effective during cleanup waits and reentrant observers.
  OFF or a newer request prevents delayed playback from an older request.
- Normal retirement does not cancel the executor's caller. Preemption cancels only
  the local child; a completed claim/notification is never fabricated as sound.
- These source/transport prerequisites do not activate repeat, ACK or wake owners.

## Local Alarm Fatigue Policy

- Noncritical cycles start one short clip only; they never inherit a critical
  maximum. Critical floors, within-clip ramp and configured repeat stay intact.
- Unchanged noncritical identities double scheduled intervals to30 minutes.
  Current evidence refresh never resets backoff/slides an existing due. Lifetime
  ordinal is identity/replay protection, not a per-generation repeat count.
- Shared noncritical cooldown uses current-boot confirmed audio-start receipts
  across sources, not just current requests, notification posting or claim time.
  A live claim reserves one slot but never establishes successful sound/hearing.
- Shared-history uncertainty prevents only new noncritical sound. Critical
  selection/admission never waits for the unrelated shared-history query.
- Shared preview and committed admission use the same transactional gate.
  Deferral does not consume ordinal, fake a result or acknowledge any source.
- Exact ACK and global OFF remain effective. Neither history refresh, higher
  risk nor model output can bypass deliberate global OFF. No catch-up bursts.
- Policy alone cannot supply source authority or foreground/wake/ACK admission.
  The explicit glucose adapter supplies those gates; physical phone acceptance
  remains separate. No clinical threshold or therapy change is authorized.

## Accepted Alert Delivery Isolation

- Accepted forecast/UAM/sensitivity authority validation precedes every consumer.
  No invalid authority, cancelled owner or process-fatal error starts new effects.
- Normal permitted alert publication precedes the potentially slow writer stage.
  Each consumer is invoked at most once by this helper. It introduces no retry,
  replacement command, relaxed writer policy or clinical threshold change.
- Ordinary alert failure does not suppress otherwise permitted therapy. A therapy
  exception remains primary; a separate alert exception is suppressed, never
  swallowed or converted into a successful widget publication. Cancellation wins.
- Source-change read-only policy never publishes notifications or writes therapy.
- Successful notify invocation cannot override blocked global/channel/group state.
  Missing/unreadable channel state is not confirmed posting. No channel reset.
- Required audio/vibration failure stays visible even when another channel works.
  Any-channel receipt success preserves its bounded degradation summary and does
  not establish human hearing/ACK, media completion or full-channel success.
- Silent WATCH does not require audio/vibration. No repeat, ACK, wake/service,
  OFF behavior, schema/default, phone or therapy activation accompanies this stage.

## Daily Capacity Priority

- Idle-only daily reservation, active-work priority check and fresh claim share
  one committed metadata transaction. Busy adds no owner/queued reservation.
- A duplicate in any state is metadata only, never reissued claim authority.
  Commit uncertainty/restart leaves executing capacity held, never auto-replayed.
- Legacy DAILY_REVIEW claims yield to queued non-daily jobs. Expired waiting
  jobs do not block; running/cancel-requested jobs never expire implicitly.
- Priority is for already queued work, not preemption, fairness or zero latency.
  Interactive arrival after daily claim cannot imply successful process stop.
- Capacity metadata is not a provider/OS/execution/publication receipt. No source,
  model, queue handoff, schedule, result or therapy action follows from the call.

## Daily Result Durability

- Only the trusted exact executor/worker composition may call the private result
  sink after positive stop and actual SUCCEEDED ledger settlement. A supplied
  Job/outcome/status is not a public execution receipt or provider admission.
- Authenticate immutable owner/device/job/request/work/model/clocks/source/reply
  provenance under a distinct purpose. Shared quota, expiry and bounded reads
  include results; only explicit writes enable schema 5, never ordinary reads.
- Cross-database success-before-save loss stays RESULT_UNAVAILABLE. Commit with
  lost acknowledgement may recover the original row; neither path re-infers.
  Cancellation drains owned storage work. Uncertain stop keeps capacity held.
- Original sourceAtReply and summary membership are not currentness. Selected
  reads replay retained source/latest report within one protected snapshot and
  expose changes, unavailable evidence or supersession, never rewrite history.
- Stored text remains REFERENCES_ONLY/NOT_ADMITTED/unreviewed. No inferred
  medical validity, provider/publication admission, route, schedule or therapy.

## Focus AI Presentation

- Daily analytics loading/failure must not hide usable chat or local report.
- A tab visit does not submit, refresh, reprepare or retry an AI job. Discuss
  appends to the unsent draft; sending and disclosure remain explicit actions.
- Confirmed-send latch and PDF ticket effects outlive tab disposal; process death
  cannot restore an unreviewed approval. Existing backend/controller gates remain.
- Clinical recommendations retain typed source/period/evidence. Missing raw
  glucose series cannot become a fabricated day curve or therapeutic conclusion.
- One active panel, retained scroll/draft, accessible targets and finite native
  motion; no hidden page may keep intercepting input or run a decorative loop.

## Contained Daily Worker

- Dedicated daily JSON/prompt/request bindings must not widen CHAT/photo policy.
- Consumed durable handoff and independently current canonical archive/source
  are checked before native start; reply references/source are checked again.
- Failed/cancelled start still requires exact process stop. Reader cancellation
  is not thread termination; join owned work before any cleanup confirmation.
- Only JobExecutor settlement after a positive stop may expose a result. Unknown
  stop or regressed ledger clock keeps the slot unresolved, never auto-retried.
- A worker does not confer provider/priority/publication/clinical admission.
  Results remain NOT_ADMITTED and cannot write therapy or suppress protection.

## Durable Daily Reservations

- One automatic owner/device/date/zone reservation, independent of prompt,
  language/report revision or retry. Only the latest completed civil day may
  be newly enqueued; original frozen work/audit are never silently replaced.
- Authenticate complete encrypted queue payload and metadata under a distinct
  purpose. Only explicit enqueue enables schema 4; reads/cancel/handoff do not.
  Existing history/chat/report quotas and fixed report-day retention include it.
- Recheck latest source/report and exact current work under serialized handoff.
  Record handoff before returning work, at most once across concurrent callers
  and restarts. A committed marker with missing acknowledgement is not retried.
- HANDOFF_RECORDED is not model submission/success/stop or publication. Queued
  cancellation cannot pretend to stop handed work. No global slot is reserved;
  actual worker admission/priority/ownership/settlement remain separate.
- Dispatch deadline is next civil-day end, respecting DST; audit expiry never
  extends. An old prompt can be read for audit but not handed off under new pins.
  No provider, scheduler, public/API/therapy activation follows from these methods.

## Checked Daily Review Boundaries

- Preparation and reply evaluation independently consume authenticated source
  replay in the same protected transaction as work/result construction. There is
  no hash/proof argument to bypass the second check or reuse old MATCHED state.
- Latest visible archive revision must match work; a newer revision supersedes
  old work. Reconstruct exact canonical work from that authenticated archive;
  a self-consistent rehashed packet is not sufficient evidence for its metrics.
- Bind owner/device before storage. Preserve response size/schema/reference/
  priority gates and complete-operation deadlines; contract errors inside the
  lock are not mislabeled as storage corruption. No partial checked object.
- Actual point-in-time source checks remain separate from unchanged legacy
  inner contracts. No continuous freshness, provider stop, publication, medical
  quality or therapy authority follows. No queue/policy/schema widening occurs.

## Frozen Daily Source Checks

- Selected archive and latest retained source share one bounded protected read
  transaction. Reuse original cuts only for deterministic replay, never as new
  current snapshot metadata. Actual check time/sequence/window remain distinct.
- Compare complete facts as well as source digest. Observe late boundary inputs
  beyond the original midnight cutoff; never hide them behind that cutoff.
  Authenticate selected tombstones before treating evidence as absent.
- Retention loss, source rollback or non-reproducing derived facts cannot be
  MATCHED. Integrity/row/SQL/wall-budget failures produce no partial assertion.
- MATCHED is only retained GLUCOSE_ONLY evidence at the check moment, not source
  completeness, medical validity, current range configuration or future freshness.
  No worker, legacy preparation promotion, provider or publication authority exists.

## Grounded Daily Review

- Only authenticated archived calculator output enters trusted preparation. A
  supplied matching digest is not independently verified currentness. No implicit
  all-history scan, cache freshness certificate, model call or public DTO exists.
- Canonical input includes exact closed result schema and its digest. The model
  receives the packet hash it must echo; schema replacement, stale/expired
  assertions and unknown/duplicate references cannot pass reference checking.
- Bounded selection preserves original metrics/eligible denominators, explicitly
  counts omissions and keeps source/data-quality limitations visible. Baseline day
  reasons are aggregated, not clinical metrics re-estimated. Low exposure has
  priority; absent data cannot become a reassuring normal-day finding.
- No free numeric/action fields or unknown review action. Visible counts/times/
  units come from frozen evidence, not model arithmetic. Free prose remains
  unreviewed; syntactic/reference checking cannot establish medical correctness.
- Returned view always remains REFERENCES_ONLY / NOT_ADMITTED with independently
  unverified freshness. It cannot be promoted by a status string, reused as CHAT
  admission, sent to therapy or treated as native process stop confirmation.

## Daily Glucose Facts

- Daily facts archive writes require trusted owner/device-bound calculator
  output and consistent date/window/as-of/snapshot/digest bindings. They are not
  a client ingestion or public read contract, clinical validation or model result.
- Report revisions are immutable and exact retries do not rewrite creation time
  or retention. Expiry is civil-day end plus 365 days, including late generation.
  A new revision cannot resurrect an expired date or roll back source chronology.
- Authenticated archive metadata binds owner/device, purpose, sequence, logical
  date/zone key, revision, creation/expiry and canonical full-facts digest.
  Corrupt selected rows, quota or deadline failures yield no partial read/write.
- Bounded report summaries expose NOT_CHECKED source state. Only an explicitly
  supplied trusted current digest may mark MATCHED/STALE_SOURCE; it never changes
  the original report. Retained membership is not complete/fresh source evidence.

- Reporting range is explicit versioned analytical input, never a temporary
  target/default. Unknown range or uncovered time cannot become in-range time.
- Only adjacent compatible observations within 15 minutes support linear
  segments. Suspect/conflicting samples and known sensor changes break them.
  Quality OK is device-declared; UNKNOWN contexts are not calibrated by default.
- Keep cohort statistics separate. Integer duration totals, exact range crossings,
  original-time guards and civil-day/DST bounds are required. A state/record
  count is not time-in-range and a single episode is not a learned pattern.
- Internal history reads authenticate selected records in one bounded snapshot,
  bind owner/device/version/range and fail without partial totals. They are not
  complete-source certificates or full out-of-window integrity scans.
- No schema migration, clinical write, model request, scheduler/public route,
  therapy dispatch, prompt activation or deployment follows from this calculator.
- Multi-day facts share owner/device, as-of cutoff and snapshot sequence. Only
  prior 7/21-day compatible cohorts with known original context supply baselines;
  current/future dates and unknown calibration/session cannot be pooled by default.
- Pool moments and range minutes using their eligible covered-time denominators.
  Sparse current days have no daily delta. Missing/changed range history cannot
  supply comparable TIR even if glucose moments are otherwise usable.
- Hourly recurrence requires a regular civil clock and adequate window coverage;
  three distinct days is a provisional engineering label, not a diagnosis or
  treatment rule. Truncated episodes cannot erase integrated hourly exposure.
  Selected evidence and day-wide omissions stay explicit; missing is not normal.

## Photo API Composition

- No decoder or login-profile access in the nonroot API. Its optional vision
  endpoint is distinct, kernel-peer checked and operator-controlled.
- Freeze and correlate the entire canonical work/image body. No task crossover,
  model/argv/prompt selection, URL/file input or ambiguous automatic replay.
- The root photo host must inject the isolated decoder; never use the development
  thread. Decoder UID/GID, public image and namespace are separate from native/API.
- No authenticated model image until decoder stop/IO is confirmed. No result or
  idle release after unknown native stop. Drain both API services on any shutdown.
- Public API activation requires actual admission, not synthetic readiness or a
  capability-response edit. No phone, medical data or therapy authority is added.

## Native Image Isolation

- Fixed inline JPEG and food prompt only; no paths, remote URLs, clinical history,
  tools, provider fallback or retry. Preserve model/reasoning metadata.
- Text input/pipe/stream limits remain unchanged. Image echo and food-result
  budgets belong only to the dedicated native vision path.
- Exact invocation/cgroup stop AND successful terminated IO precede the shared
  profile's idle journal write. IO failure retains bound state, never a reset.
- Concrete source transport is not a host/decoder/channel admission or public
  photo capability. No synthetic readiness or provider-result claim.

## Food Photo And Context Cache

- CHAT readiness never grants IMAGE authority; validate the selected route.
- Freeze photo body and request identity; no parallel same-ID inference,
  photo rebinding or ambiguous automatic resubmission.
- Never use an uncorrelated receipt to poll/cancel another job.
- Schema-v1 nutrients/energy are per 100 g, mass is selected explicitly,
  missing facts are unknown, and the existing final meal confirmation remains.
- No photo-derived GI, target/dose action, Room migration or history upload.
- Full eligible encrypted-envelope fingerprints are mandatory for context
  reuse; revisions, retention, AEAD/digests and resource budgets stay intact.
- Injectable vision peers and declared model modality do not admit a real
  contained server worker. Default image inference remains disabled.

## Core Room Export

- The exporter never writes clinical tables or changes their schema/version.
  Only its independent no-backup metadata index and encrypted page journal change.
- Source SQL/columns/units are allowlisted; original time is not observation time.
  Unknown calibration/session/timezone/GI are not borrowed from current settings.
  A stored dose is not delivered insulin; UAM is not confirmed real food.
- Index UUID/revision and frozen page records commit only after signed upload.
  Clear/ack failure cannot rebuild the page or replay a model request. Device
  mismatch refuses reuse; there is no automatic reset of uncertain state.
- Original-time retention can retire an unacceptably expired observational
  batch, never change its body. Its UUID/digest/time are durable before new
  attempts for unchanged still-valid record JSON. No model job is replayed.
  Expected device identity is checked inside the signing/transport mutex.
- Page budgets, indexed keysets and explicit partial coverage bound work. Completed
  core scans are neither database-wide snapshots nor proof of full history.
  Old corrections/deletions may await daily full reconciliation.
- Full-scan absence permits tombstones only inside that scan's original-time
  window. Tombstoned identity is never resurrected; expired server records cannot
  acquire a later lifetime from a sync timestamp. Missing sources stay omissions.
- Maintenance requires ACTIVE server access and power saving off; cancellation
  preserves pending recovery. App-lifecycle work is not an OS execution guarantee.

## Owner Application Chat

- Explicit owner API requires a nonroot service, protected configuration and
  keys, distinct private storage, current attestation policy and a fixed trusted
  supervisor peer. No default activation, root API or bearer-only shortcut.
- Owner descriptor v2 requires a protected, digest-pinned serial revocation
  snapshot. SHA256 revocations alone cannot represent Google's serial status
  list. Check every chain certificate; suspended keys remain denied. No client
  supplies trust, no snapshot fetch occurs inside enrollment, and existing
  freshness/root/application/hardware/boot checks are not relaxed.
- A lifetime lock prevents concurrent API owners. Unresolved executing claims
  block startup; unknown execution is never replay authority.
- Concurrent inventories never share a lifetime directory cursor. Each owns a
  fresh checked/pinned descriptor and closes it on success or failure. A storage
  rejection is not permission to bypass guards or retry model submission.
- Configured or not-yet-loaded server routing precedes personal-key reads and
  has no failure fallback. The repository rejects unsupported modalities before
  reading their files or personal credentials. The chat UI disables unsupported
  media and speech actions, including during unloaded routing; stale picker and
  recorder callbacks recheck the route. An existing recording always remains
  stoppable. Existing clinical optimization is not rerouted.
- UI request failures are typed status entries, never assistant observations in
  model history. History filters invalid/blank/status messages before applying
  its ten-turn bound. Ambiguous outcomes retain their uncertainty and never
  authorize automatic submission retry. Composer guards apply to keyboard and
  click callbacks; a synchronous latch and finally cleanup bound one request.
- Model jobs follow successful signed context sync; ambiguous submission is
  UNKNOWN, never an automatic new request. Only correlated jobs can be cancelled.
- Nonexpired upload retries retain exact batch bytes/identity and persisted device binding.
  Server high-water sequence is not proof of completeness or delivery.
- Snapshots without original timestamps remain observations, not historical
  measurements. Missing values are unknown; onset is not action duration, net
  IOB is not interchangeable with positive IOB. No chat result changes therapy.
- Numbered archive fragments preserve complete supported reply text within
  record/plan bounds; arbitrary oversized input fails before upload. Fragmentation
  is observational only, never authority to split or retry a model job. Response
  clock tolerance does not change event timestamps or clinical freshness checks.
## Native Artwork Isolation

- Interactive root destinations do not crossfade. Motion-policy changes must not
  replace the NavHost/controller or reset route drafts to cancel an old effect.
- The owl menu retains the existing click owner and a fixed48dp target. Only
  explicit route/menu/busy edges create finite header gestures; palette updates,
  recomposition and same-route events cannot replay them. Suppressed requests
  remain consumed. Accessibility waiting ends only with the real busy state.
- Color comes from existing fresh current CGM, not report/forecast values or
  navigation time. One private notification owl never removes its required risk
  smallIcon, public redaction, clinical text, delivery claims or actions. No
  artwork-only notification scheduling, sound or vibration is introduced.
- Icon feedback never scales touch targets or delays an existing action. Material
  retains gesture cancellation and disabled semantics. A disabled drawer exit
  cannot retain an invisible input-blocking overlay; visible content state stays.
- No therapy, database, network, permission, acknowledgement or risk mutation
  callback belongs to artwork. Visual bands are not clinical thresholds.
- Urgent LOW_NOW/CRITICAL_5 and native notifications stay static. No animation
  defers a message, infers resolution or completes an AI request by time.
- Finite RESUMED-only clocks cancel in background and obey system/app power
  save and reduced motion. Resume and CGM recomposition do not restart gestures.
- Waiting remains indeterminate to accessibility after its visual cap and when
  motion is disabled. Artwork timing never becomes request-completion evidence.
- Public notifications reveal neither values nor glucose-band colors. Optional
  image failure cannot discard the underlying alert. Clinical actions stay intact.
- Main reconciliation retained exact verified Android source a74006dd before
  this presentation patch. Phone installation remains a separate verification.

## Trusted Host Lifetime

- Root CLI configuration is bounded, closed, root-owned/no-follow and stable
  across its read. No client payload can choose a model, path or native command.
- API and worker identities are distinct and nonroot. Kernel peer checks remain
  mandatory; socket filesystem permissions alone grant no protocol authority.
- Never unlink a pre-existing/replaced socket or clear a non-idle profile journal.
  SIGTERM/SIGINT drain active work before socket cleanup and exit.
- Descriptor reload cannot change peer/policy/private bindings. Public admission
  updates require explicit protected operator state and the same runtime checks.

## Contained Runtime Admission

- The API never supplies executable/image/profile/address settings or launches
  root commands. Only the trusted supervisor constructs the concrete process.
- Explicit immutable pins, no-follow public-image validation, private guard and
  actual kernel checks precede START/context; no automatic drift adoption.
- Fixed official provider/hosts, empty tools and exact model/reasoning policy.
  Expired egress admission fails; no DNS/proxy/fallback in the process adapter.
- A cancelled start retains creation identity. Only the original owned invocation
  and empty descendant cgroup authorize profile release. Unbound/unknown state
  requires trusted reconciliation; no marker deletion or credential restoration.
- Native and helper IO must terminate before a successful stop receipt. No raw
  stderr, environment, authorization or clinical context enters operational logs.
- Active terminal job outcomes remain pending until archive reconciliation ends;
  public failure status must not race the durable conversation outcome.
- Synthetic process/kernel records are not actual systemd/BPF/TLS enforcement
  evidence. Fresh host admission and operational storage/Android checks remain.

## Text Supervisor Isolation

- Kernel-authenticated local peers, fixed policy and a closed text-only protocol;
  no API-selected argv, paths, tools, models, instructions or native RPC relay.
- One turn per connection, no automatic reconnect/replay. Connection UUID is
  correlation only; existing signed job ledger remains the idempotency authority.
- A result is not public until independent stop and native IO termination.
  Ambiguous start/send/stop retains the durable claim and refuses new admission.
- Controller shutdown drains teardown before waiting for connections. An ended
  worker with RUNNING/CANCEL_REQUESTED makes API readiness false; no queue growth.
- Protocol/local-socket tests do not attest the concrete process factory, kernel
  isolation, account availability, deployment or clinical correctness.

## Signed Conversation Read

- Full-text archive access requires current enrolled owner/device proof for each
  exact body/path. A continuation token alone grants no authority or identity.
- Page membership excludes later inserts. Missing/reused anchor and expired
  cursor require restart; partial/corrupt scans cannot look complete.
- Outcome state is current per page, not frozen with membership. Original expiry
  still wins; unknown completion remains unknown and never starts a model replay.
- Read does not migrate or create device databases. Full text is bounded by page
  bytes, never silently truncated. Existing job/history route limits stay intact.

## Server Conversation Persistence

- Only the trusted CHAT service writes SERVER_ARCHIVE. Device-declared source
  labels/imported chat cannot gain that origin; model text remains untrusted.
- Request archival precedes dispatch; settled valid answer archival precedes
  publication. Archive failure never authorizes retry or skips process teardown.
- Ledger/archive are not one transaction. Missing outcomes remain explicitly
  unrecorded; no inferred answer, replay or automatic repair after restart.
- Owner/device AAD and immutable job binding isolate full encrypted payloads.
  Original acceptance fixes365-day expiry; final writes cannot extend it.
- Pending answer budget shares the existing byte quota. Same quotas/purge apply
  to schema2 without changing imported observation sequence or provenance.
- Read-only history does not migrate schema1. Additive archive migration cannot
  justify a destructive downgrade, old-database restore or live deployment.

## Contextual Text Isolation

- Worker history identity comes only from authenticated JobWork. No client field,
  question, archived message or declared SERVER source grants identity/authority.
- Latest eligible versions only; deletion, expiry, observation/receipt cutoff and
  scan failure cannot produce stale resurrection or fabricated complete totals.
- Samples and aggregates carry scope, quality and omissions. Unknown context is
  not known calibration; record-weighted glucose means are never TIR/duration.
- Context remains data, not system instructions or executable/therapy commands.
  One native turn, exact model, direct dispatch and empty tool capabilities only.
- No output publication before the existing executor's independent stop/settlement.
  Cancelled process start still needs teardown; cancelled history reads are joined.
- Injected protocol tests and containment flags are not OS proof or activation.
  Production launcher, archive storage operations and Android wiring require
  their own verification.

## Session Profile Serialization

- One trusted manifest maps one profile to one permanent private control root.
  Same-host flock is not a distributed lock or a same-UID/root compromise defense.
- Directory/lock/journal identities and recorded cgroup/slice cannot drift during
  a lease or across recovery. Closing/crashing never clears the durable run marker.
- Only exact bound invocation stop plus fresh metadata permits idle. Cancellation
  drains the stop task and retains the marker; unbound recovery is refused.
- No force-reset, stale-token restore, orphan deletion/promotion or credential-body
  reading. Initialization cannot adopt existing auth. Top-level metadata admission
  does not attest recursive native state, valid auth or provider continuity.
- The guard is inactive trusted-host source, never HTTP-selected paths/verifiers,
  deployment, authorization, model execution or therapy authority.

## Session Candidate Isolation

- Exact public source hashes and tested helper bytes bind the candidate patch;
  no arbitrary runtime version, credential schema or profile import is selected.
- Pre-replacement errors preserve the live file; post-replacement failure cannot
  justify rollback. Forced termination can leave private orphan state.
- Atomic files are not atomic provider-token rotation. No stale-token restore,
  orphan promotion, guaranteed session continuity or power-cut claim.
- Standalone helper/source checks do not attest a full installed client, active
  containment or authorization. No runtime replacement follows from passing CI.
- Synthetic native protocol runs use only a fresh private CA/local peer and
  development issuer override. They cannot broaden the independent HEAD-only
  public transport profile or authorize real login/provider token exchange.

## Transport Probe Isolation

- Observed receipts require a gateway-owned matching upstream status, successful
  exit/local TLS and a non-5xx final response. Upstream status-header spoofing,
  local refusals and failed upstream TLS cannot pass. Keep inherited hides.
- Actual public403 is transport-only, not authentication or model readiness.
  Cgroup enforcement and fresh fixed-host/public-CA admission precede public
  HTTP. No credentials or old profile can be used as transport-test fallback.
- Profiles are pure trusted inputs, never HTTP-selected argv, mounts or hosts.
  Canonical v4 run identity, nonroot numeric IDs and immutable public IPv4 pairs
  are validated before interpolation. IPv6 and runtime DNS are not supported.
- Only HEAD / to two fixed TLS names. No auth/model path, forwarded client
  headers/body, credential/profile mount or inference readiness from HTTP200.
- Synthetic Unix upstreams and public numeric destinations cannot fall back to
  each other. TLS verification and fixed upstream Host/SNI remain mandatory.
- Generated properties/receipts do not prove actual network, cgroup, filesystem,
  relay environment, stop or cleanup. No deployment or API activation is implied.
- Trusted stop is bound to the original invocation and exact generated parent.
  Cleared static-unit identity is accepted only after stop in the exact inactive
  state with empty descendants, never as entry authority. Cached inactive units
  are not collected units. Compare kernel root identities, not link spelling.
- Every HTTP temporary path stays under the bounded work mount. A failed probe
  command or TLS error is not proof of denied network routing. Synthetic network
  evidence never grants authentication, inference or clinical readiness.

## Personal History Isolation

- Only an explicitly injected owner-scoped service mounts history routes. A
  request's authenticated owner/device key selects its file; body fields cannot.
- Body proof, one-use nonce, current subscription and post-proof identity checks
  precede storage. Other devices/owners, replay and revoked access cannot read it.
  This admission does not preempt an already authorized in-flight transaction.
- Payloads are typed observations, not commands or trusted training labels.
  Missing historical context stays unknown. Imported assistant text is not a
  verified server response. No clinical or inference authority is introduced.
- All records and retry acknowledgment commit together. Exact-body retries do not
  duplicate data; conflicts roll back. Revisions cannot move source event time,
  change source/kind, extend expiry or resurrect tombstones.
- Original event time determines365-day expiry, including archived chat requests.
  Reads hide expired data; own-file purge removes it. Retention of idle files and
  backups, physical erasure and authenticated key restoration remain release gates.
- Private files, per-device payload encryption/AAD, byte/row/device/page/disk and
  query-work bounds fail closed. No keys, raw bodies or provider errors in receipts
  or HTTP failures. Metadata encryption is not claimed by payload encryption.
- No endpoint fallback, remote deployment, Android transfer, model execution,
  existing job-cache lifetime or GI/target/dose change is implicit in this stage.

## Independent Explicit Eating Soon

- A checked meal target is independent of carb transport/annotation success.
  Attempt the existing guarded canonical target before optional research
  persistence as well as carbs/annotation; preserve both outcomes. Research input
  still precedes the carb transport and has no target authority.
- No mode switch, GI/portion value, carb throttle or annotation failure can
  turn explicit intent into an automatic proposal or invent target success.
- Cancellation and same-key duplicate submission cannot replay ambiguous actions.
  Unstarted carbs, attempted unknown carbs and target statuses remain distinct.
- Existing safety/arming/chronology and confirmed30-minute priority remain.
  A sent request is not proof of AAPS application. No automatic renewal.
- Food-origin refusal details survive UI/process recreation through the exact
  canonical terminal receipt and the shared allowlist. Never expose raw payload
  errors or let a null/stale local hint replace the stored reason.
- Signed net IOB for residual prediction and positive safety IOB stay distinct;
  high mean forecasts alone cannot bypass low-bound/reliability protections.

## Meal Input Observation Isolation

- Preserve actual raw/resolved measurement and source calibration basis. Local
  engine output is not synonymous with uncalibrated input; frozen basis and
  model ID must match the candidate. Unknown legacy basis is never guessed.
- Source cohort/digest v2 includes measurement transform, separately from temporal
  availability. Retrospective recalculation cannot become contemporaneous data.
- Preserve original insulin, its timestamp and source independently of causal
  alignment. A modeled activity sample is not physical delivery or interval
  coverage; missing context/review cannot be synthesized as clean or zero.
- Capture only after clinical fan-out, in the existing bounded nonfatal journal
  budget, with no extra prediction, fit, controller, subscriber or action.
- First insert and post-commit finalization are immutable. Retry/restart cannot
  promote interrupted receipt time; collection time is not wire arrival.
- Retention touches only the observation table. Review is bounded and rejects
  partial coverage on overflow. Repeats have one vote and conflicts no winner.
- Observational counts cannot emit qualified episodes or grant clinical/model
  admission. No old-data backfill, external export, phone operation or therapy.

## Personal Meal Candidate Isolation

- Require qualified, explicitly selected sensor/calibration/insulin cohorts and
  later-day support; sparse/ambiguous/bounded/unimproved timing cannot fit.
- Shared comparison selection preserves complete-day cutoffs, portion/type and
  circular hour. Missing GI never becomes a fitted food attribute. Accepted GI
  models reject absent, guessed, late, revised or out-of-training-range labels.
- Replace one canonical revision without changing grams, other meals or insulin.
  One cumulative curve drives past known inputs, UAM and future food/COB; revised
  known inputs invalidate old residual trend. Never use a future-only scenario
  to impersonate consistent personalized historical absorption.
- Source/default engine and display GI are unchanged; no persistent personal
  setter, controller access, target write, worker or production subscription.
- Model-selection evidence and input occurrence cannot pass the anchor;
  availability precedes calculation, which finishes before the first outcome.
  Effective insulin kernel and explicitly bound LOCAL_ENGINE measurement basis
  remain exact. Heuristic CI cannot become candidate-calibrated uncertainty.
- Conditional fit admission is research-only, not prospective accuracy, source
  attestation or closed-loop safety. Register against the authenticated runtime
  receipt before observed-data evaluation; no activation through this adapter.

## Prospective Journal Isolation

- Record exact authenticated outputs and frozen calibrated observation/context
  after clinical fan-out, without a second engine/controller run.
- A post-commit marker and strict pre-first-outcome registration are required.
  Unfinished/crashed/late rows cannot be promoted by retry, restart or backfill.
- Journal observation time is not CGM transport arrival or true blood glucose.
  Unknown sessions and sensor warnings cannot become clean evidence.
- Baseline-only scores never impersonate a personal candidate. Baseline model
  versions/settings/calibration/sensor cohorts remain separate.
- Review is bounded, passive and on demand: no clinical writes, export, polling
  or activation. Overflow refuses partial accuracy. Independent21-day retention
  cannot delete clinical rows. Applied-target model/control admission is pending.

## Prospective Meal Comparison

- Freeze both forecasts and register before the first5-minute outcome. Candidate
  model training and input coverage cannot include future samples; availability
  must precede calculation. A future evaluation cutoff cannot fix a late receipt.
- Compare the same anchor, accepted cycle/digest, revision, measurement basis,
  sensor/calibration and zone. Local engine and adjusted control are explicit
  alternative bases, never silently mixed. A label is not independent attestation.
- Exact observed timestamps, explicit availability and trusted same-series
  quality are required. Missingness is not a zero error; lows are retained.
  Conflicts cannot choose the more favorable sample or generation. Repeats have
  one vote. Days and separate model/context cohorts keep explicit support.
- Missing candidate intervals do not inherit baseline CI or become calibrated
  uncertainty. Report common interval support and lower-bound missed lows.
- Capture/evaluation has no clinical DAO or action dependency. It cannot call
  the live stateful controller, journal a target, dispatch, learn from outcomes,
  overwrite COB/grams or authorize activation. Scores are observational under
  actual historical control, not counterfactual outcomes of a different target.
- Receipt file validation cannot prove capture occurred before an outcome.
  Restart-safe trusted storage/collector and clinical model/control assessment
  remain required. Never fabricate a phone ledger from old forecast rows.

## GI Contribution Research

- GI is an independently sourced, revision-bound food attribute, not an inferred
  CGM label. Missing GI/context cannot become a numerical default or clean data.
- Training uses earlier complete days and available evidence only. Missing GI
  cannot shift the held-out boundary; later outcomes cannot tune parameters.
- Sensor, calibration and insulin-model cohorts are explicit. Do not select the
  best cohort, extrapolate GI, silently truncate source or score gaps as zeros.
- All comparisons use identical eligible windows. Gram stress is separate from
  unchanged declared mass. Learned shape keeps monotonicity, mass and endpoint.
- Conditional response error is not prospective forecast/control validation.
  Analysis status and report blockers cannot enable live forecasts, COB, targets
  or doses. Existing clinical writers, Eating Soon and safety policy are unchanged.

## Primary COB Source

- Primary COB is a fresh, source-qualified runtime Copilot estimate, never a
  hardcoded value, new absorption calculation or raw-AAPS fallback.
- Source, units, quality, timestamp and finite bounds are validated before
  presentation. An invalid/conflicting latest result cannot revive an older one.
- Both source clocks expire without a database emission. AAPS reference remains
  separate and cannot reanimate an expired curve or grant therapy authority.
- Changing the displayed number does not rewrite accepted projection points,
  food records, GI, forecast/controller inputs, targets, doses or existing limits.

## Event Compensation Review

- Popup reads cannot write metadata or dispatch therapy. Saved GI must match the
  selected canonical meal revision; missing/invalid/model-provenance GI is unknown.
- Query overflow is not complete history. Future values, mixed sensor streams,
  conflicting duplicates and low-quality/gapped coverage cannot supply a range.
- Glucose range and observed peak time are retrospective associations, not
  measured food absorption or proof of incorrect bolus timing/dose.
- ISF changes compare historical same-source explicit units, not current settings
  or mixed model/profile values. Sensor age alone cannot establish expiry.
- Low CGM episodes cannot trigger advice to delay food. Without verified meal
  start and delivered-bolus times, never manufacture an earlier/later minute value.
- Transient canonical presentation identity/revision cannot alter persistence,
  existing24 clinical attributes, event impact or wire serialization.

## Chart Event Detail Semantics

- Details are passive selected-record data. They cannot dispatch commands or
  infer a delivered dose, per-meal COB, true GI or completed absorption.
- Point blood checks retain their timestamp and reference status. Their trust
  context duration is not a measurement procedure or applied calibration.
- Missing/nonfinite/conflicting quantities remain unknown; authoritative carb
  semantics take precedence over legacy aliases. Optional display fields never
  displace clinical attributes at the24-field cap.
- Apply the existing attribute cap before removing/regenerating reserved display
  keys, so previously clipped clinical fields cannot become newly visible.
- Diagnostic labels retain reported/suspected meaning. Raw policy markers and
  JSON do not become explanatory text. Deleted selections cannot reopen later.
- Transient presentation quantities cannot consume clinical attribute slots or
  become persisted/wire authority. A full payload still shows its independently
  source-resolved amounts; conflicting amounts remain unavailable.

## Qualified Meal Research Source

- Prepared evidence cannot create or revise its own canonical ground-truth meal.
  Identity/revision/time/mass/type/portion/provenance/availability must match.
- Missing insulin, CGM availability, zone, model context or review/session identity
  is unknown, never zero or a fabricated reviewed assertion. Research-source
  attestations cannot authorize clinical decisions or imply physical delivery.
- Conflicting labels/evidence, known overlap, unfinished day/tail and invalid
  adapter inputs cannot qualify. Historical timezone cannot fall back to current
  settings. Later review availability belongs to episode chronology and digest.
- Source queries and aggregate prepared rows are bounded. Overflow/unavailable
  is not a complete partial training source; returned collections are immutable.
- Freeze packet membership/arrays before the canonical read suspends. Explicit
  contemporaneous glucose basis is required; unknown/retrospective is rejected.
- READY is source-read status, not trained accuracy. Clinical influence and
  actual mass accuracy remain false. No runtime/therapy/worker/schema change.

## Lenta Estimate History Boundary

- Model grams are approximate declared/preset estimates, never measured COB or
  automatic food-entry replacement. One matching meal has only bounded influence.
- History labels require canonical revision and independent local confirmation.
  Synthetic UAM, accepted suggestions, tombstones and conflicts cannot train it.
- Known historical GI is displayed as historical context; absent GI is unknown.
  Do not construct CGM timing inputs by treating missing insulin/context as clean.
- Estimate interactions issue no therapy/calibration/sound commands. Its complete
  gram/minute curve cannot replace accepted Current mmol/L/COB display.
- Reads are once per dialog session, bounded and off the main thread. Overflow or
  unavailable source uses a type model and cannot claim personal support.

## Infusion Timing And Presentation

- Recorded set age is not proof of physical wear or delivery. No green healthy
  claim, neutral-age0 fallback, future-date clamp or bare-CAGE unit assumption.
- 72h starts at verified original occurrence, not receipt/import/calendar midnight.
  Reservoir/sensor/prime records do not reset cannula timing. Missing/conflicting,
  deleted, synthetic/echo, future or overflow evidence remains unknown.
- Durable generation precedes scheduling; stale workers must recheck canonical
  evidence and deadline. Posting claims and chronological watermark survive restart,
  deletion and reimport. Unknown post outcomes cannot blindly repeat.
- Maintenance is silent, separate and best effort. No network/CGM/therapy arming
  dependency. Existing OFF and urgent glucose/delivery priority remain intact.
- Set replacement affects timing only, not independent diagnostic episodes.
  Current delivery labels require existing policy-accepted fresh evidence; an OPEN
  row alone is retained history. Duplicate input cannot renew accepted timestamps.
- Passive details cannot write source events, goals, doses or settings. Corrupt
  reminder state is distinct from missing original dates and never resets history.

## Dashboard Presentation And Target Draft

- Primary IOB is current / signed net from existing accepted UI values, not a new
  calculation. Negative net must not be clamped, made absolute or substituted
  from current IOB. Either missing side remains independently unavailable.
- IOB numbers/slash-net items cannot split digits; both renderers retain their
  original passive Details routes. Presentation cannot write therapy or sources.
- Every schedule interval has exactly one always-visible target control. Edit
  affects only time visibility; adjusting targets does not change IDs/times/default.
- Draft changes use the same limits, 0.1 step and validation as before. Only an
  explicit valid Save applies; Cancel/close applies nothing. Clinical protections
  and Target Manager arbitration are not changed by this UI stage.

## Hybrid Sparse-History Preview

- Zero/sparse history, missing body profile and unavailable independent context
  cannot disable a valid request's type-based advisory preview. Missing type is
  explicitly MIXED, not a discovered personal food type. Invalid request data
  errors rather than pretending zero food.
- A single completed qualified episode can supply signed descriptive observations,
  never validated personal timing, measured grams, laboratory GI or target percent.
  No one-sample spread or arbitrary accuracy/confidence percentage is invented.
- Shared cohort eligibility and existing held-out support requirements remain
  unchanged. Unknown insulin/activity/sensor context cannot become zero or clean.
  Missing intervals contribute no sample, and nonfinite normalization excludes
  its entire episode. Long meals and duplicate telemetry cannot dominate a point.
- Only an existing EVALUATED timing result with explicit food type can supply a
  separate diagnostic shape. Failed/ambiguous/bounded results retain defaults;
  known-GI shaping is not applied a second time. Lists of preview points are frozen.
- Approximate entered mass remains entered mass. Quantity suggestions keep the
  existing14-day rules, actual availability cutoff and explicit selected-hour
  neighborhood; hybrid requests exclude the current unfinished local day.
- Every result status is unqualified for clinical influence/actual-mass accuracy.
  No live graph, clinical COB/forecast, carb write, target, insulin, priority,
  hypo guard, worker, settings/schema or phone operation is authorized or changed.

## Personal Apparent Absorption Diagnostics

- Declared approximate grams remain declared, not measured truth. Empirical
  declared-portion quantiles are not confidence bounds for actual eaten mass.
- Synthetic UAM, accepted model suggestions, rescue, deleted/conflicting records
  and missing provenance cannot become supervised independent meal labels.
- Timing fits cannot jointly change grams, sensitivity or insulin action. Unknown
  critical context, gaps, future/late evidence and overflow reject rather than
  become zero values or silently selected subsets. Prepared steps are immutable.
- Chronological whole-day holdout uses earlier completed evidence only. Model
  comparison conditions on recorded insulin exposures; it is not a prospective
  forecast or counterfactual therapy-effect evaluation. Digests are checksums of
  source/model context, not authentication of externally supplied clinical truth.
- Every diagnostic status retains false clinicalInfluenceAllowed and false
  actualMassAccuracyValidated. It emits no laboratory GI or target percent.
  No source publication/build/diagnostic pass authorizes live graph, clinical
  COB, gram write, target, insulin, worker or phone changes.

## Balance Display And Primary Navigation

- Small forecast marks require current/non-stale horizons and matching existing
  future-path timestamps/values. Label separation cannot move actual chart points
  or give retained food/old forecasts fresh clinical authority.
- Every mark keeps a48dp hit area; nearby marks separate rather than overlap.
  Layout wraps/reflows with system font scale instead of shrinking display text.
- Six primary metrics reuse existing accepted values, sources and missing states.
  Opening source/target/food/status details does not save or send a command.
- COB opens the full accepted food curve; add-meal and the food-view shortcut
  reuse the existing manual-meal editor and explicit submission path.
  Opening/cancelling any metric menu cannot submit therapy or change settings.
- Top refresh retains canRunCycleNow; blood entry retains the consumed editor
  request, not a direct calibration action. Safety and power save stay reachable
  after removal of the duplicated More menu.
- No manual GI input or automatic GI estimate is introduced by this UI stage.
  Existing GI domain/storage/wire compatibility and clinical guards stay intact.
- A recorded meal amount is not remaining COB; event duration is not absorption
  end. Food-view target shading is omitted without changing clinical bounds.
- Returning from secondary screens cannot restore them as primary tab content or
  save their forms. Existing primary entries and normal tab save/restore remain.

## Food-Profile Diagnostic Refresh

- Inference telemetry retrieval must be bounded to the completed-day window and
  relevant sensor-block key, preserving legacy casing and text-valued flags.
  Missing markers are not invented; eligibility and inference coefficients stay
  unchanged. No telemetry/history/settings record is rewritten.
- Initial calculation is unique diagnostic work, not a clinical cycle. It
  requires an enabled profile, inactive power save and absent snapshot. Existing
  snapshots prevent startup recalculation; cancellation/errors cannot report a
  completed calculation. Enqueue is not proof of a saved result.
- Existing replay/clinical influence gates remain closed. Automatic diagnostic
  duration is not measured physiological absorption and cannot authorize therapy.

## Clarity And Accepted Food Display

- Opening a target, horizon, IOB, COB or delivery detail does not send therapy.
  Standalone Eating Soon requires explicit confirmation and canonical existing
  policy; queue, sent, observed confirmation and unknown are distinct.
- UI observation cannot write safety clock markers or trigger telemetry loops.
  Exact matching active readback is required for confirmed Eating Soon.
- Retained food projections keep their original timestamp and one bounded
  accepted snapshot. Fresh external COB does not rescale that snapshot. Scope
  reset invalidates it; outdated display cannot grant clinical authority.
- The Clarity migration changes only visual preference once. It does not arm
  therapy, enable new alert hardware or change clinical settings/revisions.

## Diagnostic Navigation

- Tapping a warning or its help action is read-only navigation. It cannot change
  arming, kill switch, target mode, limits, mute, volume, therapy or retry state.
- Profile issue causes come from the existing policy. Missing/invalid personal
  fields lead to the user editor; calorie problems lead to the energy editor,
  or the joint correction form when personal fields also need correction.
  Missing/stale calculation snapshots must not suggest altering valid fields.
- Unknown error codes receive conservative journal guidance, not an invented
  diagnosis. Clinical blocking conditions are explained, never bypassed.
- Opening forms never saves them. Profile, food, activity, calorie and planned-event corrections
  retain validation bounds and dismiss only after a successful durable save
  completion. Pending saves disable duplicate submission and dismissal. Failure
  retains the edited draft, shows a localized error and allows an explicit retry.
  Late/duplicate completions cannot dismiss a disposed or newly opened editor.
- Joint correction merges only the edited personal fields and calorie value into
  current settings. It cannot overwrite current modes or sharing from an old form.
- Energy-profile updates revalidate and merge against the actual DataStore
  transaction, not a pre-suspension snapshot. Physiological sex still requires
  the existing leased accepted calculation; a changed or unapplied sex fails
  closed before the ordinary settings write. No clinical lease bypass is added.

## Confirmed Eating Soon Handoff

- Reserve canonical Eating Soon durably before waiting for the shared target
  write mutex, so existing queued manager preflights see pending manual intent.
  This does not preempt a delivery already admitted to that mutex. Freeze its
  parameters; reject noncanonical manual-entry payload before reserving it.
- Only the first reservation owner cancelled before mutex admission may mark
  the request known-not-sent. Cancelling a duplicate cannot withdraw the owner;
  cancellation after admission retains existing uncertainty and no-replay rules.
  The generic manager retry entry point delegates Eating Soon keys to ordinary
  idempotent submission, never manager reconciliation retry.
- Imported target notes alone cannot establish Eating Soon. Derive context in
  the current local-safety Room transaction from exactly one matching SENT
  canonical manual command and the resolved active AAPS observation, including
  exact key, existing transport-rounded target, 30-minute window and causal time.
- Existing Eating Soon 4.1 mmol/L / 30-minute eligibility, full 5/30/60 forecasts,
  kill/arm/sensor/chronology guards and delivery uncertainty remain unchanged.
- Confirmed Eating Soon retains its observed window against ordinary control
  and recovery. Existing qualified protective increases may replace it only
  under the existing Copilot-priority opt-in and all manager/dispatch safety gates.
  This intent is not an unrelated external-writer conflict; no cooldown is
  removed for other manual/foreign targets.
- Recheck context identity at dispatch; loss/change of proof fails closed.
  Never keep Eating Soon alive, extend its observed expiry or replay delivery.
- An older SENT Eating Soon receipt ceases to block a later manager target only
  when that current resolved target matches one unique durable SENT command.
  A newer independently confirmed Eating Soon intent can likewise supersede
  older canonical SENT Eating Soon holds, never its own current request.
  Pending, failed/unknown and unrelated manual holds retain their existing guard.
- No schema/settings/network/clinical threshold change or synthetic device
  therapy trigger is authorized by this handoff fix.

## Pump-Link Source Boundary

- Current technical source comes from checked durable monitor transitions,
  never UI history, an OPEN episode or notification delivery alone.
- Superseding accepted evaluation withdraws old publication before suspension;
  cancellation/storage failure, recovery, intentional disconnect, unknown
  provenance and muted evaluation cannot retain audible-source metadata.
- Rejected duplicates cannot renew observed time or move the episode origin.
  Fresh evidence retains the existing legacy episode/claim behavior.
- A previous-boot episode is historical provenance only. Never compare its
  elapsed origin with current-boot elapsed time or use it alone as authority.
  A fresh accepted current-boot packet must not lose a confirmed technical fault.
- Capture deadline scheduling time after source publication; subscriber work
  cannot slide the existing absolute heartbeat/status deadline.
- Cached flow is only a wake/provenance feed. Synchronous current read rechecks
  boot, elapsed time, canonical condition/deadline and exact source identity.
  Clock failure denies admission; cancellation propagates. No invented TTL.
- Committed OFF/resume cleanup revokes without reentering Room/monitor locks.
  OFF expiry alone cannot republish. Pre-lock intent revocation and complete
  multi-source context/capability mapping remain future integration gates.
- No coordinator consumer, extra timer/poll, schema, arming, hardware or clinical
  change. Technical pump connectivity never proves physical attachment/delivery.

## Audio Request Fencing

- Request identity is transport state, never clinical admission or persistent OFF.
  At most one requested and one physical active ticket exist; no generation map.
- Global/preview/exact-cycle Stop revoke known matching tickets before queued
  Main cleanup. Old cancellation/cleanup never releases a newer pending/active
  request. A denied local preflight cannot preempt an urgent native owner.
- Physical cleanup and volume/focus restoration precede idle-state publication.
  Observable-state callbacks and clock reads may revoke/replace a request; reread
  exact ownership before confirming start or installing its stop timer. Install
  the stop timer before observable active-state callbacks can delay publication.
- Prepared/seek callbacks are one-shot. Do not extend absolute local deadlines,
  remove the native urgent floor or treat played=true as evidence of hearing.

## Alarm Runtime Ownership

- Constructor/signal cannot auto-start or manufacture accepted authority.
  Only the explicitly enabled foreground glucose owner constructs the coordinator;
  construction, journal recovery and a system wake cannot grant opt-in.
- One database-instance lifetime owner, distinct from the global OFF lock.
  Revocation/stop must not wait for Room before cleanup starts on Main.
- A committed in-flight claim belongs only to its actual execution job, not a
  journal-derived owner. Exact cancel/join/cleanup precedes a replacement claim.
- Suspended preview selections must match current input; callbacks report exact
  ownership before hardware and terminal outcome after independent cleanup.
- Abandoned claims become UNCERTAIN without speculative START; due/ordinal/
  reached maximum/ACK survive. They are never adopted, replayed or retried.
- Unavailable cleanup/storage latches the database-instance runtime closed in
  process. A new coordinator cannot bypass it; no automatic reset is added.
- One conflated wake and nearest timer, no source poll/forecast/AI/therapy work.
  No live delivery, wake/vibration/notification or resource benefit is claimed.

## Local Alarm Source Selection

- Selection reuses accepted-source policy and never commits preview START
  states/claims. Queue age and persisted JSON cannot authorize an alarm.
- Higher eligible priority requests exact preemption; equal priority retains
  the admitted owner until cycle end, then oldest-due/stable-key order applies.
  Duplicate evidence cannot slide retained cycle/repeat or pending queue age.
- Missing/stale/unauthorized/changed evidence or OFF/disabled/unarmed state
  requests exact cancellation. Paused/waiting/silent sources cannot preempt.
- Unowned/conflicting persisted active claims require explicit recovery, never
  adoption/replay. Cancellation and cleanup must finish before replacement.
- A complete immutable current batch contains <=64 unique keys including owner.
  Room freezes it before suspending reads; choosing an unchecked switched-in
  source is forbidden. Corrupt storage fails the entire preview closed.
- Preview reads actual Room OFF under the shared lock/transaction and changes
  no journal, ordinal, repeat, pause or progress. No migration or production caller.
- Only the nearest strictly future expiry/due/ACK/OFF/cycle deadline is returned.
  Checked wall conversion, no backlog/polling/AI or clinical cadence reduction.
- Source production, capabilities/foreground/wake,
  notification/vibration/native fallback and opt-in/device acceptance remain
  required. This stage does not improve measured phone or night delivery.

## Local Alarm Cycle Executor

- Only a newly committed exact START is executed. BUSY callers create no claim;
  ACTIVE claims are never adopted and delayed work is not replayed.
- Room admission is read-only and honors actual global OFF. Injected fresh
  accepted source/boot/generation/capabilities must agree before side effects.
  Persisted state alone is not authority and this runner cannot arm itself.
- Current source/OFF/capability events must revoke and cancel exact ownership.
  A stale cancel cannot stop a replacement cycle; cleanup always unlocks even
  if a resource operation throws cancellation or cannot be confirmed.
- Confirmed volume precedes audio, including LOW_NOW70%. Both hardware and
  player admission enforce the current absolute step window, not just55s.
  Missed steps are skipped; observed override/uncertainty stops further work.
- Audio start, requested target and unrequested channels remain distinct.
  Failed stop/cleanup cannot become FINISHED; cancellation is propagated and
  independent cleanup operations still run. No ambiguous attempt is retried.
- One coroutine/lease/existing player, at most four steps and bounded current
  journal reads. No source polling, second engine, notification/vibration,
  foreground/wake permission or production caller of this executor is added.
- Clinical/target/therapy/Telegram and default legacy alert behavior stay intact.
  Operational source coordination and device/night acceptance remain separate.

## Local Alarm Playback Transport

- New playback requires explicit exact-cycle/step admission; persisted state
  or this transport never manufactures fresh source authority.
- Only canonical audible start steps are accepted. Preparation/seek cannot
  launch a missed step; playing duration cannot be scheduled past cycle55s.
- Duplicate prepared/seek callbacks do not restart or extend a local clip.
  Exact stop and focus/player/session ownership reject stale callbacks.
- Slow post-start admission cannot slide the stop timer; it subtracts elapsed
  work from the captured absolute end. Backwards/expired clocks stop the owner.
- Cancellation propagates and cleanup completes on Main. No preparing player
  remains on denial, media error, timeout or a clock jump at timer capture.
- New mode shares the existing single player and uses alarm attributes/gains;
  it does not alter stream volume, ringer/DND/route or clinical calculations.
- Coordinator admission includes the confirmed urgent floor and calls stop on
  current OFF/source/capability loss while playing. No default true authority or
  automatic opt-in is allowed. Only the cycle executor through the foreground
  owner consumes the local audio API.
- Default legacy alerts remain. Platform URI/timer behavior is not a Doze
  delivery guarantee; foreground/wake and real-device acceptance remain.

## Alarm Volume Lease

- A volume lease cannot authorize an alarm, invent accepted source evidence or
  bypass OFF/capabilities. Its future coordinator must serialize these guards.
- Exactly one full cycle identity owns the lease. Mismatched callbacks cannot
  read/write/release it; acquisition/restoration cannot be reentered by a new
  owner and cancellation during admission prevents a subsequent volume write.
- Raises do not lower current volume. Setter return is not confirmation;
  valid readback, unchanged maximum and current admission are required.
- A caller cannot weaken the canonical first profile target. In particular,
  admitted LOW_NOW raises retain the70% minimum, not a caller's quieter request.
- An observed manual/system change permanently stops raises/restoration for
  this lease. Uncertain write outcome, fixed/invalid hardware or changed maximum
  grants no restoration. A still-known owned value may be cleaned up after OFF.
- Cleanup never overwrites a differing index, retries an old release or restores
  a persisted lease after process restart. Cancellation propagates.
- Only STREAM_ALARM is accessed with flags0. No media/call/ringer/DND/route,
  sound/vibration/timer/wake-lock or clinical/therapy behavior is changed.
- Observation comparison cannot identify every user gesture or route change;
  it is not system-atomic CAS, measured loudness or a delivery guarantee.
- There are no runtime consumers. Default legacy urgent-low protection remains.

## Local Alarm Persistence

- A local cycle START is a committed unique source/generation/ordinal claim,
  not proof of audio, vibration, notification visibility or human response.
- State and claim commit atomically. Clock capture follows the shared mute
  lock; actual Room OFF overrides caller hints. Cancellation propagates.
- Unsupported/corrupt payloads or mirrored metadata never reset an ordinal or
  manufacture fresh authorization. Source evidence is revalidated separately.
- ACK and results bind to the exact source/generation/ordinal/level. Results
  require current accepted evidence and an active pre-deadline claim. Confirmed
  progress alone advances volume history; conflicting/stale writes reject.
- Recovery marks interrupted ownership UNCERTAIN, preserves ordinal/due and
  does not replay a backlog. Terminal rows alone are pruned at30d in batches
  <=100; source state is retained and scheduling reads remain <=64.
- Room31->32 is additive, with no new destructive fallback. An old schema31
  APK cannot reopen a32 database; recovery requires fresh data and a reviewed
  forward fix, not an old backup over subsequent therapy.
- Storage has no runtime side effects or consumers in this stage. Existing
  urgent alerts, therapy/target/forecast rules and user settings are unchanged.

## Food Display And GI

- GI is optional confirmed metadata, not a clinical absorption calibration.
  It changes only the separate display shape, never clinical5/30/60, announced
  pressure, insulin, UAM, current COB, target bounds or dispatch authority.
- Each GI is bound to the exact trusted canonical meal identity/revision.
  Pending, missing, conflicting and guessed values cannot become accepted GI.
  Unknown preserves baseline; unrelated meals receive no invented shared GI.
- Display shaping conserves each current modeled remainder and finish time.
  Engineering coefficients cannot be described as physiologically validated.
- The complete remaining curve is bounded to720m/145 points/16 KiB. Invalid
  display inputs are unavailable, not clinical failures or fake zero food.
  Nonzero capped remainder is incomplete, not fully absorbed.
- The accepted cycle and glucose clock must match. Food cannot fabricate actual
  glucose history or advance chart now; explored viewports retain their state.
- Invalid optional GI does not block a valid real-carb confirmation. Editing,
  screenshots and tests cannot send real therapy; no new dosing authority.
- Room31 is additive. Preserve fresh data before any recovery; an old schema30
  APK is not an automatic rollback path for an already migrated database.

## Compact Meal Safety And Research

- The Food dialog confirms exact1..80g manual meals. This separate maximum never
  increases automatic/UAM writer limits or bypasses arm, throttle or target gates.
- A manual submission ID binds immutable grams, profile, portion/provenance,
  energy and Eating soon intent before I/O. Changed input cannot receive a false
  SENT acknowledgement or overwrite the original, including after restart.
- Hidden calories are null. Picture taps and cancellation cannot dispatch therapy.
- Only trusted non-conflicting canonical REAL food can use the larger model/COB
  bound. Synthetic and unknown inputs retain existing computation constraints.
- Portion training requires matching canonical identity, revision, amount and
  independent confirmation. Accepted suggestions, unknown legacy origins,
  synthetic/rescue/preparatory carbs, invalid records and tombstones are excluded.
- Confirmation availability must not precede the meal or leak into earlier
  predictions. Held-out target categories cannot be inferred from target grams.
- Offline history candidates never alter saved defaults, dispatch therapy or
  register polling/workers. Cache entries are revision/settings/time-zone scoped.
- Missing real labels produce no accuracy claim and never activate learning.
- Robolectric rendering is local UI evidence, not phone/deployment acceptance.

## September 2026 Publication Contract

See [RELEASE_2026-09-13.md](RELEASE_2026-09-13.md) for the current release
scope and deployment gates. The older baseline below is retained as history;
its numeric controller thresholds require current-code verification before use.

- Accepted NORMAL and SENSITIVITY_SOURCE_CHANGE cycles invoke the widget callback; LOCAL_READ_ONLY does not.
- A published runtime tuple must use the same settings identity, snapshot cycle
  and forecast generation, not independently selected latest rows.
- A valid server subscription does not imply live AI analysis is available.
- Refresh and recovery cannot extend the fixed subscription expiry.
- No release test may submit insulin, carbohydrates, targets or calibration to
  a real therapy endpoint.
- Meal research capture occurs under the cycle lease only after exact accepted
  Room readback. Raw local baseline and adjusted control forecasts are separate.
  No observer means no engine copy; new cycles invalidate old research context.
  The bounded, replay-free stream and its snapshots never authorize notifications
  or therapy. Cancellation propagates; ordinary rejection does not block existing
  clinical publication. Consumers must revalidate freshness and revision.

## Optional server AI job invariants

- The default bound app has no job routes and reports inference unavailable;
  R1a routes require an explicitly injected service and contained-worker factory.
- R1a accepts only strict bounded `CHAT` text. Model, prompt, route, tools,
  actions, argv, owner and containment configuration are server-controlled.
- Every job route requires current attested bound access and one exact P-256
  method/path/body/credential/nonce proof. Dispatch and result read recheck
  revocation and subscription; dispatch binds the stable session/key grant so
  access-token refresh alone cannot cancel work.
- Quota and idempotency are scoped to the subscription owner across sessions;
  read/cancel and request reuse remain bound to the accepting session/key.
- Exact-body and trusted-policy digests are durable metadata. Exact retries
  recover one job even after deadline/result expiry; mismatches return conflict
  and terminal jobs never execute again.
- Raw input/result/provider/error content is volatile only. Idle expiry must
  purge it within 15 minutes without deleting ledger metadata.
- There is one worker globally, no more than five waiting, no more than one
  active job per owner, and no unbounded per-request task/timer creation.
- Cancellation, deadline and shutdown release capacity only after independent
  positive stop confirmation. Unconfirmed stop retains capacity and is exposed
  as unavailable/unknown. Process restart never automatically replays a job.
- Server AI output is advisory text only and never a therapy command. Online
  launcher, OS containment, production authority/attestation, device, routing,
  staging and deployment remain separate release gates.
- The exact R1a wire and digest rules are in
  [signed server AI jobs](2026-09-13-signed-ai-jobs.md).
- The minimal Android job client reuses connection identity and encrypted
  credentials. It cannot select another host, fall back to personal keys or
  issue an automatic repeat after an ambiguous send. An explicit retry retains
  the exact prepared UUID, deadline and body; only its access proof is renewed.
- Job responses have a separate 64 KiB cap; activation/status retain 16 KiB.
  Neither the isolated test APK nor this optional client stage authorizes
  clinical uploads or changes an existing therapy/AI caller.

## Safety invariants
Telegram forwarding starts disabled and needs explicit trusted-private-chat enrollment.
Recipients are addressed by verified numeric chat ID, never an unverified username.
There are no incoming Telegram therapy commands. Global alert mute suppresses new
automatic Telegram sends; cancellation cannot recall an already accepted message.
A durable pre-send claim prevents automatic retry after an uncertain HTTP outcome.

1. Kill switch blocks automatic actions only; manual actions remain available.
2. No automatic action is sent when data freshness/sensor policy blocks execution.
3. Every outbound action must have idempotency semantics.
4. Temp target values are always clamped to configured hard safety bounds.
5. Adaptive runtime base target is forced to `4.2 mmol/L` when `cob_grams >= 20`.
6. Activity spike protection may temporarily override adaptive temp target into `7.7..8.7 mmol/L`; recovery back to base is allowed only after sustained low-load signal.
7. Automatic outbound `temp_target` writes must obey a repository-level duplicate throttle:
   - repeated or near-identical targets are blocked inside `30 minutes`,
   - materially changed targets may pass immediately,
   - manual bypass requires the explicit manual idempotency prefix,
   - an eligible manager episode release may pass only with its exact pending ACTIVE journal, a fresh decision, unchanged confirmed previous automatic command, exact target binding and the managed delivery preflight. Labels or reason strings alone cannot authorize a release,
   - ordinary adaptive trend releases only move an exactly owned NORMAL_CONTROL target toward, never across, the effective base. A new glucose observation beyond the previous send, trusted sensor/delivery, non-flat canonical trend and concordant same-cycle 5m forecast are required. All decrease reliability/low-risk/ownership/arm/freshness gates remain in force. Protective targets are not released by this exception.

## Prediction invariants
Residual AR learning must not bridge a missing five-minute observation bucket
as one ordinary transition. Repeated same-bucket observations do not increase
sample count; obsolete/non-finite input cannot mutate current residual history.
Any reconstructed horizon must use the common validated origin of its anchors.
Retrospective UI scoring must exclude future CGM and forecasts outside the
observed window at its explicit evaluation clock.

New current CGM mutations request the existing durable clinical recalculation
without a four-minute glucose scheduling hold. The mutation comparison and
outbox write are transactional and share a frozen ingest clock; duplicate row
IDs, older history and future-only records cannot claim a new current input.
Reactive cycles wait cancellably for the existing cycle lease, bounded by the
worker deadline, and read settings/inputs after acquisition. Periodic/manual
idle-only behavior, atomic accepted tuples and all target guards are unchanged.

1. Forecast output must include 5m/30m/60m horizons.
2. Prediction values and CI are bounded to physiologic app clamp range.
3. Legacy mode behavior must remain stable when enhanced flags are disabled.
4. Enhanced local prediction must derive trend/volatility/UAM inputs from a canonical 5-minute CGM series rather than directly from raw minute-level points.
5. Forecast/runtime computation must be strictly causal: therapy events with `ts > prediction_now` must not influence prediction or replay metrics.
   - Recent therapy and sensor-lag therapy histories must use the same frozen cycle clock as an inclusive Room query upper bound, including after slow calibration preparation. Later arrivals stay stored for subsequent cycles; accepted maintenance keeps its causal assertion and must not substitute a later wall clock.
6. UAM contribution must not be double-counted with positive residual trend when UAM is active.
7. Synthetic/exported UAM carbs must never become announced-carb ground truth for forecast physiology or ISF/CR learning.
8. External/AAPS raw `COB` is a reference signal only; runtime `effective COB` used by forecast/controller must subtract residual synthetic `UAM_ENGINE` carbs before merge so exported UAM does not re-enter prediction through `COB` bias.
9. Enhanced V3 Kalman state treats insulin and announced-food effects as known
   input before residual AR is fitted. Current historical UAM input is zero;
   positive residual extrapolation is suppressed when forward UAM is active.
10. Recent forecast calibration must remain causal:
   - bias and CI width may use only already-matured forecast-vs-actual pairs inside the calibration lookback,
   - CI width calibration is based on empirical absolute residual quantiles, not future actuals.
11. Circadian pattern influence is a bounded secondary prior, not a replacement for physiology:
   - it may blend only after the core path simulation is produced,
   - maximum weights remain horizon-limited (`5m <= 0.10`, `30m <= setting`, `60m <= setting`),
   - it must be multiplied by pattern confidence and acute attenuation.
11. Active UAM event inference is the preferred runtime meal contour for forecast UAM when available:
   - forecast UAM steps may consume inferred `ingestionTs/carbs/confidence` as virtual-meal hint,
   - telemetry/controller must use the resolved unified UAM runtime state instead of arbitrating separate inference/forecast flags ad hoc.
12. Carbohydrate absorption is event-specific and must use one of three classes: `FAST`, `MEDIUM`, `PROTEIN_SLOW`.
13. Food catalog baseline sizes are fixed for current version: fast=100, medium=100, protein=50.
14. Carb class resolution order is deterministic: explicit payload -> catalog text -> glucose pattern -> medium fallback.
15. Runtime forecast bias from telemetry is horizon-aware and must keep output in physiologic clamps:
   - explicit metabolic bias uses normalized `cob_grams`/`iob_units`,
   - bounded context-bias uses normalized physiology factors (`set/sensor/activity/dawn/stress/hormone/steroid`) plus current pattern window,
   - low sensor quality can widen CI and limit displacement from current glucose.
16. UAM inference may run on minute-level CGM input, but UAM action/export cycle is bucketed to 5-minute cadence.
17. Exported inferred carbs must be tagged (`UAM_ENGINE|id|seq|ver|mode`) and deduplicated against remote entries before sending.
18. Physiology-aware ISF/CR snapshot may override prediction sensitivity only when confidence is above threshold; otherwise fallback chain is mandatory.
19. Low-confidence ISF/CR fallback is metric-aware, not all-or-nothing:
   - metrics with weak or absent evidence must fall back independently,
   - metrics with strong compensation-derived global evidence may stay on bounded runtime candidate even while snapshot mode remains `FALLBACK`.
   - realtime ISF/CR evidence extraction must ignore `temp_target` and other control-only therapy rows; only physiologically relevant insulin/carbs plus set/sensor markers may enter the fast path.
18. Shadow-first mode is default for physiology-aware ISF/CR runtime; low-confidence snapshots must be marked `FALLBACK`.
19. Optional shadow auto-activation may switch `SHADOW -> ACTIVE` only after KPI-gate pass (sample count, mean confidence, mean absolute ISF/CR deltas) and must emit audit trace.
20. If daily quality gate is enabled, `SHADOW -> ACTIVE` promotion is additionally blocked unless latest 24h MAE(30m/60m), matched sample count, hypo-rate, CI coverage(30m/60m), and CI width(30m/60m) constraints pass.
21. `SHADOW -> ACTIVE` promotion is additionally blocked when daily ISF/CR data-quality risk level is at or above the configured threshold (`isfCrAutoActivationDailyRiskBlockLevel`, `2..3`, default `3`).
22. Daily ISF/CR risk gate must resolve risk level robustly: prefer numeric telemetry (`daily_report_isfcr_quality_risk_level`), fallback to latest text label (`daily_report_isfcr_quality_risk` -> `LOW/MEDIUM/HIGH`) when numeric is missing.
23. Daily risk gate diagnostics must expose resolved risk-level source (`numeric` / `text_fallback` / `missing_or_unknown`) in audit metadata for explainability.
24. `SHADOW -> ACTIVE` promotion is blocked when day-type stability gate fails: low same-day-type ratio or high day-type sparse-rate on recent realtime ISF/CR audit windows.
25. `SHADOW -> ACTIVE` promotion is additionally blocked when rolling replay quality gate fails: fewer than required available windows (`14d/30d/90d`) or any available rolling window failing quality bounds.
26. Realtime ISF/CR blending with evidence is blocked when local hour-window evidence (`±1h`) is below configured minima; diagnostics must expose hour-window counts and minima, and reasons must include `isf_hourly_evidence_below_min` / `cr_hourly_evidence_below_min`.
27. User-configurable hourly evidence minima are clamped to safe bounds (`0..12`) before persistence and before runtime engine usage.
28. ISF/CR confidence model must penalize high set/sensor wear age, high context ambiguity (`UAM/stress/manual tags`), and `sensor_quality_suspect_false_low`; CI must widen under these penalties.
29. ISF/CR evidence weighting must include wear-aware multiplier from latest infusion-set/sensor change markers; missing markers must keep neutral multiplier `1.0`.
30. ISF/CR realtime recent-evidence weighting must be day-type aware (`WEEKDAY/WEEKEND`): opposite day-type evidence gets reduced influence and day-type sparsity reasons are emitted when relevant.
27. Telemetry-derived ISF/CR values are priors/fallbacks, not independent sample streams: repeated telemetry updates must contribute at most one effective telemetry prior per family (`ISF`, `CR`) to any single estimate.
28. In `FALLBACK_IF_NEEDED`, sufficiently populated local ISF/CR history must remain source of truth; global/telemetry priors may stabilize only sparse windows and must not overwrite dense local hour/segment estimates.
29. Local forecast runtime must prefer the shared `ProfileEstimator` history pipeline for ISF/CR; any inline legacy heuristic may be used only as fallback when shared estimation yields no value.
30. Nightscout and local Nightscout treatment import must be payload-aware and causal: therapy rows may be normalized from label + payload (`insulin/carbs/...`), but only from data present in the imported treatment itself; no future treatment enrichment is allowed.
31. `profile_segment_estimates` rebuild is atomic and latest-only: analytics recalculation must clear old segment rows before inserting the current set, so runtime never mixes stale and fresh segment windows.
32. `profile_estimates` rebuild must delete legacy telemetry-polluted rows (`telemetryIsfSampleCount > 1` or `telemetryCrSampleCount > 1`) before publishing new profile snapshots.
33. App startup must not block `Overview / Forecast / Safety` on profile analytics self-heal:
   - rebuild is mandatory when active profile is missing, telemetry-polluted, or older than `12h`,
   - rebuild is mandatory when `profile_segment_estimates` are missing or stale relative to the active profile,
   - the rebuild must be route-lazy (`Analytics` / `AI Analysis`) and use the profile-only rebuild path capped to `90d`, so ordinary launch does not pay a full analytics repair cost.
33. Manual physiology quick-tags must be normalized before persistence (`hormonal_phase -> hormonal`, `steroid -> steroids`) to keep context model token matching deterministic.
34. `steroids` and `dawn` manual tags must be reflected in runtime factor trace (`manual_steroid_tag`, `manual_dawn_tag`, `steroid_factor`, `dawn_tag_factor`) and in ambiguity-aware confidence penalties.
35. Physiology tags must support point-close operation by tag id; closing one tag must not mutate unrelated active tags.
36. CR evidence extraction must reject low-integrity meal windows:
   - bolus linkage window is asymmetric (`-20m..+30m`) around meal,
   - gross CGM gaps (`max gap > 30m`) are hard-drop,
   - high `sensor_blocked` telemetry windows are hard-drop,
   - high UAM ambiguity telemetry windows are hard-drop.
   Rejections must emit explicit dropped-reason counters in diagnostics/audit.
37. CR evidence integrity thresholds are settings-backed and clamped before persistence/runtime:
   - max CGM gap (`10..60` minutes),
   - max sensor-blocked rate (`0..100%`),
   - max UAM ambiguity rate (`0..100%`).
35. Runtime DIA must be profile-derived:
   - selected insulin profile provides the base duration from its action curve,
   - optional daily real-profile estimate may refine effective DIA through profile-based onset/shape scaling,
   - raw external `dia_hours` telemetry may be stored for audit, but must not override runtime insulin action on its own.
36. Each automation forecast cycle must emit factor coverage audit (`forecast_factor_coverage`) so missing runtime drivers can be diagnosed (`ISF/CR`, DIA, COB/IOB, UAM, sensor/activity/context, pattern/history, and applied bias stages).
37. AI daily optimizer may tune only forecast calibration scales (`gain/maxUp/maxDown`) and must stay within bounded clamps before runtime use.
38. AI daily optimizer output must never bypass deterministic safety/rule engine and must never generate direct therapy commands.
39. Runtime AI calibration tuning is valid only for fresh optimizer payloads and quality-gated daily reports:
   - optimizer payload age must be bounded (`<= 36h`),
   - matched sample count must pass minimum threshold,
   - high ISF/CR data-quality risk blocks tuning application.
40. Nightscout treatment import must preserve payload aliases required for real bolus/carbs reconstruction:
   - `enteredInsulin <- enteredInsulin|bolusUnits|insulinUnits`,
   - `enteredCarbs <- enteredCarbs|mealCarbs|grams`,
   and normalization from generic Nightscout labels must depend only on those preserved payload fields.
41. Nightscout/local-Nightscout treatment normalization must be shared and deterministic: the same payload must resolve to the same therapy `type` whether it entered through remote sync, local loopback POST, repair pass, or replay.
42. Treatment repair may only reclassify therapy rows from persisted payload and source metadata; it must never synthesize missing insulin/carbs values or infer future events.
43. `profile_segment_estimates` recomputation is replace-all, not append: stale segment rows must be deleted before inserting the current recalculated set.
44. `profile_estimates` cleanup must remove telemetry-polluted legacy rows before writing the rebuilt profile snapshot, so repeated telemetry cannot inflate confidence/sample counts across recalculations.
45. When real insulin-like Nightscout therapy history is below bootstrap minimum, treatment sync must keep running widened `created_at` recovery fetches until sufficient real insulin rows are present; narrow incremental windows alone are not allowed to become the terminal state.
46. Effective runtime DIA must stay anchored to the selected insulin profile and may deviate only within `50%..150%` of that profile duration, even when a real-profile fit is available.
47. Real-profile DIA refinement must be slow-moving: recomputation cadence is `72h` for stable estimates, with earlier recompute allowed only for algorithm-version changes or empty fallback templates.
48. Telemetry must preserve both DIA layers when available:
   - `dia_profile_hours` for the selected profile default,
   - `dia_real_raw_hours` for the historical fit before runtime blending,
   - `dia_effective_hours` for the bounded runtime value actually used by prediction.
49. Circadian prior must degrade safely under acute or low-quality conditions:
    - `abs(delta5) > 0.30 mmol/5m`, `COB >= 15`, `UAM active`, or `IOB >= 3` force acute attenuation,
    - dormant low-confidence `UAM` without current rise/absorption (`low confidence`, low `gAbs`, low `uci0`, calm `delta5`) may only soft-attenuate circadian prior (`0.7`) instead of full acute suppression,
    - `sensor_quality_suspect_false_low` or stale/sensor-blocked state disable circadian prior entirely.
50. Requested `WEEKDAY/WEEKEND` circadian segments must fall back to `ALL` when coverage is insufficient; fallback reason and segment source must be persisted in snapshot metadata and shown in analytics.
49a. Replay-aware circadian bucket sufficiency is day-type aware for `15m` slot density:
    - `WEEKDAY` replay buckets require at least `5` samples,
    - `WEEKEND` replay buckets require at least `4` samples,
    - `ALL` replay buckets require at least `6` samples,
    in addition to existing `coverageDays`, `winRate`, `maeImprovement`, and acute attenuation gates.
49b. Replay bucket harm classification must stay conservative:
    - buckets are `HARMFUL` only for clear regression (`maeImprovement >= 0.05`) or for weak-but-positive regression with strongly poor win-rate (`maeImprovement > 0.02` and `winRate < 0.40`),
    - near-neutral noisy buckets must stay `NEUTRAL`, not `HARMFUL`, so they do not zero out circadian prior without evidence of real degradation.
51. Circadian analytics state must self-heal before analytics routes/runtime depend on it:
    - missing `circadian_slot_stats`, `circadian_transition_stats`, `circadian_pattern_snapshots`, or `circadian_replay_slot_stats` must trigger a bounded circadian rebuild,
    - stale circadian snapshots/replay stats (`> 12h`) must trigger the same rebuild,
    - qualified replay buckets with legacy zero-quality signature (`sampleCount >= 8`, minimum day coverage, `maeImprovementMmol == 0`, `winRate == 0`) must trigger the same rebuild,
    - self-heal must use a capped lookback (`<= 21d`), read only a circadian-specific telemetry whitelist, and must not force a full profile/ISF-CR rebuild,
    - self-heal failure must degrade to audit-visible warning, not process crash,
    - `Overview` cold-start must not eagerly trigger circadian self-heal.

## Data invariants
1. Glucose and derived values in app domain use mmol/L.
2. Therapy payload parsing must tolerate key aliases but sanitize unsafe ranges.
3. Telemetry storage must preserve timestamp and source for auditability.
4. Persisted telemetry timestamps must be positive (`timestamp > 0`); zero/invalid timestamps are rejected or normalized.
5. Physical activity metrics must be normalized to canonical keys when present: `steps_count`, `activity_ratio`, `distance_km`, `active_minutes`, `calories_active_kcal`.
6. Forecast rows keep long retention (not short rolling window) to support month/year analysis.
7. Pattern and profile analytics must keep historical snapshots while runtime reads latest slices.
8. Local sensor activity ingestion requires `ACTIVITY_RECOGNITION` grant on Android 10+; without permission the collector must fail safe and not write synthetic values.
9. ISF/CR evidence and snapshot retention must be time-bounded and cleanup must run automatically.
10. Room upgrade `v9 -> v10` must preserve user data via explicit migration; destructive migration is not allowed for this path.
11. ISF/CR runtime observability must persist dropped evidence reasons (coded counters) in audit for each realtime computation cycle.
12. Analytics quality diagnostics must include dropped evidence reason aggregates for rolling `24h` and `7d` windows when source audit data is available.
13. ISF/CR `useManualTags=false` must neutralize only manual-tag factors while keeping latent telemetry factors (for example stress/activity telemetry) available.
14. Realtime ISF/CR audit event (`isfcr_realtime_computed`) must include wear/context fields (`setAgeHours`, `sensorAgeHours`, wear factors, ambiguity, wear penalty) required for quality analytics.
15. Analytics quality diagnostics must include wear-impact aggregates for rolling `24h` and `7d` windows when realtime audit metadata is available.
16. Daily forecast reporting must publish rolling replay KPI telemetry for `14d/30d/90d` windows (`rolling_report_*`) with horizon metrics (`5m/30m/60m`) to support controlled-activation trend diagnostics.
17. Realtime ISF/CR audit diagnostics must preserve day-type context and same-day-type hour-window counters for explainability (`currentDayType`, `hourWindow*SameDayType`).
18. Analytics quality summaries must include day-type stability indicators (same-day-type ratio and sparse-flag rate) computed from realtime ISF/CR audit metadata.
19. Settings state must expose recent physiology tag journal entries (active and recently ended) for explainable manual context management.
20. ISF/CR shadow auto-promotion must be blocked when sensor-quality stability gate fails (`qualityScore/sensorFactor/wearConfidencePenalty/sensorAgeHighRate`) based on realtime audit metadata.
21. Sensor-quality gate thresholds used for shadow auto-promotion must be clamped before persistence/runtime (`score/factor/penalty: 0..1`, `age-high-rate: 0..100`).
22. Sensor-quality gate for shadow auto-promotion must also enforce `suspect_false_low_rate` bound derived from realtime ISF/CR diagnostics (`sensorQualitySuspectFalseLow` or `sensor_quality_suspect_false_low` reason code).
23. `maxSuspectFalseLowRatePct` threshold must be clamped before persistence/runtime (`0..100`) and surfaced in settings/audit metadata.
24. Day-type stability gate thresholds used for shadow auto-promotion must be clamped before persistence/runtime (`same-day ratio: 0..1`, `sparse-rate: 0..100`).
25. Realtime ISF/CR reasons must include `sensor_quality_suspect_false_low` when corresponding telemetry flag is active, and CI/confidence must become more conservative.
26. Shadow auto-promotion must emit rolling-gate audit with per-window diagnostics (`isfcr_shadow_rolling_gate_evaluated`) before promotion decisions.
27. Rolling-gate settings used in shadow auto-promotion must be clamped before persistence/runtime (`min windows: 1..3`, `MAE relax: 1.0..1.5`, `CI coverage relax: 0.70..1.0`, `CI width relax: 1.0..1.5`).
28. Any consumer of `glucose_samples` that derives runtime decisions, analytics, local Nightscout payloads, replay, or quality gates must deduplicate rows by `timestamp` through the shared `GlucoseSanitizer` winner policy before use.
29. Analytics ISF/CR charts and current-value tiles must keep three distinct sources visible:
   - compensation-derived evidence,
   - Copilot fallback runtime,
   - AAPS raw telemetry.
   These sources must never be visually collapsed into a single line/value in Analytics.
30. Analytics ISF/CR charts must keep per-line visibility toggles for all three primary sources and optional overlay toggles for `COB`, `UAM`, and `activity`; disabling a line must only affect rendering, never stored history or fallback selection.
31. Compensation-derived ISF evidence must ignore synthetic `UAM_ENGINE` carbs when checking correction-window carb contamination; tagged synthetic carbs are not allowed to invalidate a real correction sample via `carbsAround`.
32. Realtime ISF/CR confidence must be computed from separate `ISF`/`CR` evidence coverage, with additional support from strong global/hour-window evidence and same-day-type ratios; one-metric-only evidence must still incur a cross-metric confidence penalty.
33. A realtime ISF/CR snapshot may only leave `FALLBACK` into `SHADOW` below the main threshold when both metrics have keepable compensation-derived candidates, `sensor_quality_suspect_false_low` is clear, and runtime application remains disabled.
34. Realtime ISF/CR extraction must not be capped by recent `temp_target` spam: if the raw therapy query saturates its limit and too few relevant insulin/carb/set/sensor rows remain after filtering, the repository must widen the therapy scan before computing evidence.
35. Circadian analytics storage uses `15-minute` slots and keeps three related tables in sync:
    - `circadian_slot_stats`,
    - `circadian_transition_stats`,
    - `circadian_pattern_snapshots`,
    - `circadian_replay_slot_stats`.
    Slot/transition rows are replace-all per recalculation window, and snapshots must include stable window, recency window, fallback reason, and segment source.
36. Circadian analytics UI must expose separate sections for `WEEKDAY`, `WEEKEND`, and `ALL` fallback when available, plus explicit window selection (`5d/7d/10d/14d`).
37. Circadian analytics UI must also expose a direct state card with raw circadian storage diagnostics (`READY / PARTIAL / STALE / EMPTY`, slot/transition/snapshot/replay counts, latest snapshot/replay timestamps, active source mapping) so phone-side validation does not depend on copying a live WAL-backed DB.
37. Runtime telemetry must expose circadian prior diagnostics (`confidence`, `delta30`, `delta60`, `residual bias`, `segment source`, attenuation/stale flags`) so forecast behavior can be audited without reading pattern tables directly.
38. Circadian replay summary shown in `Analytics` and `AI Analysis` must be causal and comparable:
    - it scores `24h/7d` windows from stored forecasts plus deduplicated glucose history,
    - it compares `baseline` against `baseline + circadian prior`,
    - if a stored row already includes `|circadian_v1` or `|circadian_v2`, baseline must be reconstructed by inverting the same circadian blend that was used at write-time; `circadian_v2` inversion must reuse replay-aware horizon weights rather than a neutral fallback weight.
39. Circadian runtime bias for `30m/60m` must be replay-aware:
    - replay multiplier is derived from persisted `circadian_replay_slot_stats`,
    - bootstrap fit of `circadian_replay_slot_stats` must evaluate the circadian template without runtime replay gating and only on `low-acute` rows where circadian produced a real non-zero shift,
    - if the exact replay slot is `INSUFFICIENT`, runtime may use only bounded neighbouring replay slots within `±2` `15-minute` slots (`±30 minutes`) and must down-weight them by slot distance; this fallback is allowed only for replay quality, not for replacing the underlying glucose-template slot,
    - `HARMFUL` replay buckets must not add replay bias and must hard-cap circadian weight to weak mode,
    - `INSUFFICIENT` replay buckets must not promote weight above neutral fallback behavior,
    - slot stability and horizon-specific transition quality must also reduce `30m/60m` circadian weight when the time-of-day template is noisy.
40. Circadian replay may apply a small positive helpful-bucket boost only under strict conditions:
    - the bucket status must already be `HELPFUL`,
    - `sampleCount >= 12`,
    - `acuteAttenuation >= 0.85`,
    - `winRate` and `maeImprovementMmol` must pass horizon-specific thresholds,
    - the resulting replay multiplier may exceed `1.0` only in bounded form (`<= 1.25`) and must still respect all final horizon shift clamps.
41. Circadian `30m/60m` integration may apply bounded median reversion only when:
    - current glucose is outside the historical slot `p25..p75` band,
    - sensor state is not stale/suspect-false-low,
    - replay bucket is not `HARMFUL`,
    - and the resulting reversion remains inside the horizon clamp (`30m <= 0.18 mmol`, `60m <= 0.32 mmol` before final blend clamps).
42. `AI Analysis` user-facing window selection is restricted to `3d`, `5d`, `7d`, and `30d`; source/status/week filters are not allowed on that screen.
43. `AI tuning blocked` must reflect the real optimizer gate reason:
    - transport timeout,
    - empty/invalid structured JSON,
    - missing API key,
    - authorization/rate-limit/server error,
    - or explicit quality/apply gate failure.
    Generic `BLOCKED` without the concrete cause is not acceptable.
44. OpenAI optimizer parsing must accept all supported `Responses API` structured-output shapes used by the app (`output_text`, nested text content, `text.value`, `parsed`, `json`, `arguments`) before concluding that structured output is empty.
45. `AI Analysis` must keep the AI chat composer above the reporting cards and support three interaction modes from the same stateful surface:
    - plain text chat,
    - multimodal attachments (`image/*` directly, text-like files via bounded local preview),
    - voice mode (`RECORD_AUDIO` -> OpenAI transcription -> optional OpenAI TTS reply).
46. Voice mode must remain advisory-only:
    - transcription and TTS are transport features,
    - they may not bypass the normal AI chat prompt construction,
    - they may not emit therapy commands or hidden outbound actions.
47. `AI Analysis` refresh is lazy by route:
    - opening `Overview` must not eagerly trigger `cloud jobs`, `analysis history/trend`, or `local daily report` warm-up,
    - those refreshes may start when the user navigates to `AI Analysis`,
    - startup optimization must not change the advisory-only semantics of the AI contour.
48. Primary navigation must remain `Overview / Forecast / Analytics / Safety`; `Analytics` must not be demoted back to overflow while it is used as a daily operational screen.
49. `UAM` must not exist as a separate primary tab:
    - `UAM` summary and event actions belong inside `Audit Log`,
    - removing the dedicated tab must not remove any existing UAM event actions (`mark correct`, `mark wrong`, `merge`, `export`).
50. `Overview` must expose an operator-facing quick `Base target` control:
    - target changes must stay bounded by configured safety min/max,
    - control must be reachable without opening `Settings` or `Safety`,
    - replacing the app-health banner on `Overview` must not hide stale-data or kill-switch state.
51. Replay-aware circadian runtime must be allowed to override a harmful exact 15-minute replay slot with a healthier local neighborhood:
    - only if the neighborhood improves bucket class (`HARMFUL -> NEUTRAL/HELPFUL`, or `NEUTRAL -> HELPFUL` with no weaker coverage/sample support),
    - only within the same day-type/window/horizon contour,
    - and only as a runtime prior-selection rule, not by mutating stored replay-fit rows.
52. `LOW_ACUTE` replay diagnostics must be conservative:
    - missing telemetry may not be silently converted into zero-valued `delta5/COB/IOB/UAM`,
    - if a replay row lacks current dynamics or metabolic telemetry, it must not be classified as `LOW_ACUTE`,
    - otherwise `LOW_ACUTE` analytics will overstate circadian quality and distort `30m/60m` tuning decisions.

## Process invariants
1. Any behavior/contract change requires docs update (`ARCHITECTURE` and/or `INVARIANTS`).
2. Every implementation stage must append an entry to `AI_NOTES.md`.
3. No TODO/stub is allowed in delivered scope unless explicitly approved in plan.
4. A detailed local forecast quality report for the last 24 hours must be generated by daily analysis worker runs (with MARD/MAE/RMSE per horizon and stored report artifacts).
5. Daily quality report must include CI calibration per horizon (coverage % and mean CI width) and publish it to telemetry keys for runtime diagnostics.
6. Daily replay attribution must persist both factor contribution and factor coverage metrics; when telemetry is available, attribution factor-space includes `DIA`, `sensor age`, and `steroid` alongside existing `COB/IOB/UAM/CI` and physiology/context factors.
7. Daily replay must persist per-horizon (`5m/30m/60m`) top-miss context payload (timestamp, pred/actual/abs-error and main runtime factors) as structured telemetry/JSON for UI and audit diagnostics.
8. Daily replay must persist per-horizon core-factor regime diagnostics (`COB/IOB/UAM/CI`, buckets `LOW/MID/HIGH`) as structured telemetry/JSON with per-bucket `mean/MAE/MARD/Bias/n`.
9. Daily replay must persist per-horizon core-factor pair regime diagnostics (`COB×IOB`, `COB×UAM`, `COB×CI`, `IOB×CI`, `UAM×CI`) as structured telemetry/JSON with pair quadrants (`LOW/HIGH × LOW/HIGH`) and `meanA/meanB/MAE/MARD/Bias/n`.
10. Daily replay must also persist per-horizon top-pair quick summaries (`daily_report_replay_top_pair_{5|30|60}m` and `daily_report_replay_top_pair_hint_{5|30|60}m`) for fast UI diagnostics without full JSON parsing.
11. Daily replay must persist per-horizon day-type-aware error-cluster diagnostics (`WEEKDAY/WEEKEND` + hour-window + mean `COB/IOB/UAM/CI`) as structured telemetry/JSON and quick keys (`daily_report_replay_error_cluster_{5|30|60}m`, `daily_report_replay_error_cluster_hint_{5|30|60}m`).
12. Daily replay must persist per-horizon weekday/weekend gap diagnostics (`daily_report_replay_daytype_gap_json`) and quick keys (`daily_report_replay_daytype_gap_{5|30|60}m`, `daily_report_replay_daytype_gap_hint_{5|30|60}m`) so UI can expose asymmetric day-type error windows.
13. In OpenAI mode, daily analysis must still produce local report; optimizer failure must degrade safely to local-only behavior and persist explicit failure telemetry (`daily_report_ai_opt_error`).
14. Daily optimizer telemetry must always publish neutral runtime keys on failure (`apply_flag=0`, scales=`1.0`) to prevent stale previous APPLY payload from leaking into runtime.
