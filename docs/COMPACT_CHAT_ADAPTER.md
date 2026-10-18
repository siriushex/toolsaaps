# Compact Chat Archive Adapter

Status: implemented opt-in source integration, not a live-host activation.
The existing worker and server factories remain on their previous path by default.
The model, reasoning settings, tools/actions prohibition and native128KiB input
ceiling are unchanged. No schema, raw archive, upload or therapy change.

## Trusted Composition

Trusted server factory code supplies `CompactChatSettings` with explicit UTC
window, reporting timezone, optional focus/ranges and required `TokenBudget`.
It must also select `compact_chat_policy(model)` for the accepted job and inject
`compact_context=settings` into `ContextualChatWorker`. This is not an HTTP option
or caller-selectable owner, SQL query, native thread, runtime path or tool.
No installed host factory, descriptor or actual token counter is enabled here.

The new prompt revision distinguishes original-segment time-weighted glucose
facts from statistical bins and partial nonglucose examples. Existing prompts
remain unchanged. System/native/output reserve and the counter must correspond
to the admitted model and framing; synthetic byte counters in tests prove only
dependency/admission behavior, not production token fit or cost savings.

## Atomic Preparation

`HistoryStore.prepare_compact_chat(work, settings, now_ms=...)`:

1. Uses the already authenticated JobWork owner/device identity and an existing
   accepted server_chat record. Verifies exact question, request/route binding,
   accepted/deadline times, model, prompt revision and instructions digest.
2. Opens one bounded existing device transaction. Pins history highwater/as-of;
   selects unique latest versions with received/observed/expiry limits and glucose
   boundary guards. Authenticates selected tombstones before excluding deletion
   payloads. No older version is resurrected to manufacture coverage.
3. Reuses the pure compact glucose projector and original metrics. Nonglucose
   records use the existing bounded counts/examples accumulator, explicitly
   `BOUNDED_EXAMPLES_NOT_COMPLETE_TRAJECTORIES`. Requested focus events are exact
   mandatory observations with time, revision, source and server receive time.
   Complete typed profile remains NOT_SUPPLIED; imported chat is not a profile.
4. Reads recent server exchanges inside that same transaction, excluding the
   current question. These retain existing excerpt/hasMore/unknown-outcome labels.
   A source-binding digest covers selected record bytes, device binding and pinned
   snapshot even when a nonglucose row contributes only to summary counts.
5. Freezes the canonical frame and verifies byte/token admission, then stores a
   bounded optional contextReceipt in the already reserved encrypted chat entry.
   Commits before the worker can pass the exact frame to the contained runner.

Limits:100,000 selected rows,64MiB canonical selected record bytes, at most15
elapsed days,64KiB mandatory focus events, existing five-second/fifty-million-SQL-
instruction budget,96KiB frame and16KiB receipt. SQLite progress, explicit wall
checks, existing file lock/quota/page limits and fresh free-space admission apply.
No oversized mandatory evidence is silently truncated to claim success.

## Receipt And Outcome

The immutable receipt has revision `chat-context-receipt-v1`, prepared cutoff and
meaning `PREPARED_NOT_DELIVERY_CONFIRMATION`. It contains the projector manifest:
frame/source hashes, selected settings/highwater, actual resolution, section
digests and token-accounting state. Existing encrypted entry fields bind it to
the accepted question, model/policy and request, not an activation code label.
It proves frozen input composition, not that a model received/read every value.

`HistoryStore.chat_context_receipt(owner, device, job_id, now_ms=...)` is an internal
trusted read returning immutable canonical bytes or None. No new public route or
Android UI is added. Existing chat query/context projections do not expose the
receipt or enlarge their budgets. Restart, late uploads and final result archival
preserve its bytes. Original question-based365-day expiry and existing AES-GCM
metadata binding/quota remain authoritative. No plaintext context file is stored.

Preparation cannot overwrite or replay a job with a receipt. Failure rolls back;
failed, cancelled or unknown execution never becomes a successful model answer.
Cancellation may leave a prepared receipt, but the worker joins its bounded
reader and never infers after cancellation. Deadline is checked again before
native dispatch; independent stop confirmation still gates final result archival.

## Compatibility And Remaining Gates

Local synthetic minute-history acceptance through ordinary encrypted ingestion
and the actual adapter:4321 records over3days prepare in0.610s, yielding21107-byte
frame and1070-byte receipt.20161 records over14days prepare in2.932s, yielding
87642-byte frame and1073-byte receipt. Raw supported duration and saved frame
digest were verified. These are glucose-only synthetic workload measurements
on the development machine, not production-host latency, full-profile coverage
or model token accounting. Real dense nonglucose workloads still need admission.

The optional encrypted entry field requires no schema migration. Older archive
readers select known projection fields; existing final-entry updates retain extra
entry fields. Source tests cover these compatibility paths. Do not deploy a mix
of unverified writers or restore old data/token profiles as an operational rollback.
Returning the trusted factory/policy to its previous configuration is the intended
source-level disable; preserve all prepared receipts and uncertain outcomes.

Before operational activation, separately admit real model token accounting and
protected host configuration; run exact-head backend/native/owner tests and CI,
validate dense uploaded workloads and affected same-device surfaces. No actual
model call, SSH change, real medical upload or phone action was used for this stage.

Full versioned profile export, broad exact meal/basal/SMB trajectories, automatic
question-to-focus selection, public per-answer completeness UI, persistent native
threads and05:00 model review remain separate work. This adapter does not turn
bounded nonglucose examples into complete coverage or causal/clinical evidence.
