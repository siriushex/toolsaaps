# Device AI Conversation And Context Audit

The owner subsequently selected compaction only of model context, not phone
uploads or the detailed archive. The approved ten-minute statistical and adaptive
background format is specified in
[Compact AI Context](superpowers/specs/2026-10-18-compact-ai-context-design.md).
The pure first-stage projector is implemented in source; see COMPACT_AI_CONTEXT.md.
A separate trusted opt-in source adapter now connects selected archive reads,
CHAT and encrypted per-answer composition receipts; see COMPACT_CHAT_ADAPTER.md.
Default host factories remain unchanged. Synthetic byte measurements and test
token counters are not a production model-token benchmark or full-profile export.

Status: source/deployment audit and required design delta, not activation of a
persistent native conversation. The device owner requested an independent chat
with complete application context for each device. Existing archival isolation
does not implement that entire request.

## What Exists

- Verified owner plus device signing-key fingerprint selects a private encrypted
  history file. An activation code enrolls access; it is not the history identity
  and must not become a plaintext database name or conversation identifier.
- Signed observational uploads preserve original time, revisions, deletions,
  provenance and original-event365-day expiry. Server-recorded questions and
  settled answers are distinct from device-declared imported chat text.
- The Android exporter scans five core streams: glucose, selected recorded
  therapy, forecasts, effective sensitivity and allowlisted numeric telemetry.
  It does not export every Room table, preference or arbitrary JSON payload.
- Recent six-hour export precedes year backfill. The app-owned loop continues
  unfinished scans after5seconds, waits15minutes after completion and retries
  transport maintenance after60seconds on failure. Network/admission, lifecycle
  and power-saving gates still apply. Chat performs at most four source pages;
  regular maintenance at most sixteen, each of at most32 source rows.
- Current display context and imported local chat observations are uploaded
  before a chat job. They are timestamped observations, not a complete historical
  profile or confirmed delivery record.
- ContextualChatWorker selects the authenticated device's history and recent
  server exchanges, constructs one JSON frame and passes it to a tool-free
  contained native model turn. Previous assistant text is not clinical authority.
- NativeTextSession starts a new thread with `ephemeral=true`. It does not
  resume an earlier per-device native thread or persist its ID in the archive.

## Context Is Smaller Than Storage

The history accumulator counts eligible uploaded records and UTC daily buckets,
retaining at most12 recent examples per kind. Independent budgets limit daily
counts, glucose cohorts and examples inside a98,304-byte history envelope. This
is not a complete time-series analysis or a time-weighted TIR calculation.

The server conversation contribution holds at most12 exchanges inside16,384
bytes. Each question/answer excerpt is at most1200characters and may be reduced
further by its4000-byte encoded bound. The native input ceiling is131,072bytes.
Full archived answers are not thereby deleted; they are simply not all included
in a model request. Omission flags are necessary but do not supply missing detail.

A long native thread alone cannot guarantee that every old observation remains
in active model context. Durable structured storage must remain authoritative.

## Confirmed Gaps

1. No durable per-device native-thread registry or thread-resume path.
2. No complete typed automatic user/therapy-profile synchronization. Current
   display strings cannot guarantee that birth/height/weight, selected insulin,
   DIA, all AAPS schedules and their historical changes are always available.
   The local insulin-context candidate is not proof of an installed-phone rollout.
3. Core therapy export currently covers insulin/carbs and selected device changes,
   not every basal/target/delivery decision supported by the server contract.
   Historical GI overlays, plans, calibration/timezone history, alarm receipts and
   unlisted tables are explicitly excluded by the source adapter.
4. Generic whole-archive counts plus recent examples do not expose every meal's
   glucose/insulin/ISF/CR sequence over a requested3/7/14-day interval.
5. The default deployed chat path lacks an exact composition receipt. An opt-in
   source adapter now persists a bounded encrypted manifest before dispatch;
   operational/token admission remains required. PREPARED does not prove receipt.
6. Implemented nightly source components and opt-in flags must not be described
   as an active05:00 model review without verified target-host admission/results.

Private live counts, timestamps, paths, identifiers and state evidence belong in
the task's private audit report, never in public source fixtures or documentation.
Missing uploaded categories are a coverage finding, not proof that no such event
occurred on the device. Metadata counts alone do not authenticate ciphertext or
establish nutritional/clinical accuracy.

## Required Integration

### Stable Device Conversation

Add an encrypted registry bound to verified owner/device identity, with a stable
application conversation ID, schema/policy revision, native thread reference,
turn state and synchronization watermark. Access-token refresh must not split
history. Reinstall/key rotation must use an explicit authorized migration; never
guess that a new key belongs to an old archive from a label or activation code.

A persistent native thread is a separate runtime/storage admission. Verify
thread/start and thread/resume, state encryption/retention, temporary mounts,
per-device isolation, profile locking and restart/teardown before enabling it.
Do not merely flip `ephemeral`, copy authentication profiles or allow an HTTP
caller to select native paths, IDs, RPC methods or tools. A native session must
not create a second unencrypted permanent medical archive.

Record a turn's pending identity before dispatch and its settled result after
independent stop. On unknown completion, retain unknown state and reconcile;
never automatically replay a question, create a replacement inference, or attach
another device's thread to recover availability. Daily reviews and foreground
chat need explicit ownership/ordering if they share this conversation.

### Complete Versioned Data Coverage

Inventory application tables/settings and classify each field as synchronized,
derived locally, intentionally excluded, unsupported, or unknown. Exclude secrets,
signing/authentication material, arbitrary diagnostic bodies and raw photos unless
the separate attachment path explicitly admits them. Do not equate all data with
copying a whole database into a prompt.

Add versioned typed user and therapy profiles with effective/observed times,
source, units and unknown values. Include configured insulin/DIA/curve identity,
ISF/CR/basal/target schedules, user measurements and nutritional/activity settings
where available. Preserve prior profile revisions; never backfill historical
insulin identity or delivery using today's settings.

Extend explicit meal/GI/portion, target decision/refusal, basal/delivery,
calibration/device-change, activity and alert-result exports with existing
canonical-source and duplicate protections. Compatibility/schema admission and
precise completeness receipts are required before a client sends new kinds.

### Context Assembly For Every Turn

Always pin the latest eligible user/therapy profile and fresh current state into
the context frame, outside the generic recent CHAT examples budget. Distinguish
configured, estimated, prescribed, recorded and confirmed-delivered quantities.

Retrieve question-relevant time-series and event windows from the authenticated
archive. For meal/compensation analysis, include requested3/7/14-day glucose,
insulin, carbohydrate/GI, sensitivity, basal/target and activity sequences with
coverage and uncertainty. Provide independently computed descriptive statistics
and source references, not an assumed causal explanation or inferred true GI.

Longer history needs bounded multiresolution summaries and deterministic detail
selection, not silently raising all byte limits or handing the model arbitrary
SQL/filesystem/network tools. A future retrieval tool requires separate narrow
read-only admission; the existing empty tool policy remains unchanged here.

Before dispatch persist an encrypted immutable context manifest: conversation/job,
source highwater/revisions, selected profile, current-state times, requested and
covered periods, selected/omitted counts, generator revision and exact frame
digest. It must remain bound to that question/result despite later uploads.

### Nightly And User-Visible Completeness

An admitted per-device timezone05:00 review should use the same archive and
profile/context assembly. Persist completed reports and recommendations once,
then let ordinary chat reference them. No model replay on unknown outcomes.

Expose last successful sync, covered periods, excluded/missing categories,
conversation identity/state and the particular answer's context receipt. Do not
label partially synchronized data as full history or an arbitrary context token
as proof that the model considered every record correctly.

## Verification And Scope

The audit ran254 existing local synthetic tests for native text, contextual
worker, owner routing, history context/transport and server conversation/query
behavior. They passed with one dependency deprecation warning. Core deployed
context/worker/archive-fragment sources were compared to local hashes; the live
native thread lifecycle was inspected separately. Other deployed modules differ
from the current source tree, so these are not full target-image acceptance tests.

No new model question, medical upload/decryption export, login/profile change,
service restart, scheduler opt-in, phone operation or therapy action is part of
this audit. The above integration remains unimplemented and must retain existing
authorization, cryptographic isolation, expiry, resource and clinical boundaries.
