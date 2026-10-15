# Server Conversation Archive

## Scope

Optional persistent text-chat history for authenticated owner/device identities.
This source stage does not deploy the API, start the native runtime, transmit
medical data or modify Android/therapy. Existing client job/result contracts and
their fifteen-minute result-cache lifetime remain unchanged.

## Persistence And Origin

AiJobService may receive chat_archive=HistoryStore for CHAT only. After the
metadata ledger reserves a request, the service saves its full decoded question,
request/job identity, original acceptance/deadline, request digest, model, prompt
revision and system-instruction hash before making it dispatchable. Authentication
selects owner/device; no new identity or origin field is accepted from HTTP.

Server entries use SERVER_ARCHIVE, separate from imported DEVICE_ARCHIVE chat.
Client-declared source=SERVER cannot manufacture a server entry. Both question
and model answer remain untrusted content, never system or medical authority.
The archive contains no auth, tool trace, reasoning transcript or raw errors.
It does not retain the exact expanded history prompt: reproducible clinical
inference or byte-identical reconstruction is not claimed.

Payloads share the existing private per-device SQLite file and derived AES-GCM
key. A distinct AAD purpose binds job identity, original/updated times, expiry
and reserved bytes. Complete questions/answers are encrypted, not truncated.
Index metadata remain plaintext; full-volume/backups encryption is a deployment
gate. No independent public archive read/delete endpoint is added here.

## Lifecycle And Failure

Reservation and archive use separate databases, not a distributed transaction.
If archiving fails, the new ledger reservation is cancelled, dispatch is refused
and service readiness becomes false. HTTP cancellation drains the acceptance
operation; a committed request may still execute once when its HTTP response is
lost. The original stable request ID is necessary for a safe status/retry lookup.

JobExecutor remains the authority for positive stop and final ledger settlement.
Only its settled SUCCEEDED plus validated ChatResult can become an archived
answer. Failure/cancellation/expiry/unknown outcomes contain no answer. Queued
cancellation, expiry, dispatch revocation and orderly shutdown are recorded.
Result archival completes before volatile successful-answer publication. A late
cancel after settlement cannot interrupt that commit or replace the outcome.
Archive errors cannot prevent active process teardown; they disable admission
and never trigger another model call.

Crash windows remain explicit. A reserved job lost before archival has no saved
question. A saved request without an archived outcome stays OUTCOME_NOT_RECORDED,
even if ledger settlement occurred just before a crash. This means unknown
completion, not a running job, failure or synthetic answer. Startup never replays
it. Existing ledger startup abandonment/retained running claims are unchanged.
An archived final outcome is immutable; exact writes are idempotent, conflicts
fail closed. No automatic repair from an old profile or old result cache exists.

## Retention And Bounds

Original server acceptance determines the fixed365-day expiry. Updates/retries
do not extend it. Reads hide expired entries; existing store purge removes them.
Each pending entry reserves128KiB against the shared per-device byte quota so
ordinary history uploads cannot consume its answer allowance. Final commit
releases unused reserved bytes. Chat rows count against the shared row limit;
physical disk failure can still prevent a commit and is not masked.

ContextualChatWorker reads the recent same-device server exchanges separately
from observational history, excludes its own job and keeps at most12 exchanges
within16KiB. Individual excerpts have explicit truncation flags; hasMore exposes
omissions. SQL work is bounded. The complete combined envelope must fit the
native input limit before any process start. These are recent-context excerpts,
not a claim that every365-day conversation is included in each model request.
Policy revision becomes advisory-device-context-v2; model/reasoning stay intact.

## Schema And Rollback

History schema1 remains readable and is not migrated by reads or ordinary
observation writes. First explicit server-chat acceptance authenticates the
existing manifest, then runs the additive schema1-to2 migration in its own
transaction: server_chat and two indexes only. It commits before archive data;
a subsequent data failure can leave an empty schema2 table. A failed DDL migration
rolls back completely. Observation rows/receipts are not rewritten.

Schema2 is readable by this version, not by older schema1-only binaries. Rollback
must use a schema2-compatible build with archive admission disabled, preserving
the database and current key. Do not downgrade, drop the archive table or restore
an old clinical snapshot as a code rollback. Unknown schema/key/table sets fail
closed. No production migration has run in this stage.

## Verification And Remaining Gates

Synthetic tests cover encryption, migration rollback, existing history, imported
origin forgery, wrong keys, tampered metadata, isolation, quotas, expiry, bounded
context, restart, cancellation, revocation, write failures, positive teardown,
commit-before-publication, late cancellation and disconnect/idempotency races.
Run the full backend pytest suite and scoped E/F lint before source publication.

Production still needs a concrete constrained process/supervisor and nonroot API
composition, protected key provisioning, scheduled idle purge, audited restore,
365-day-scale resource/load checks, explicit signed archive retrieval/export and
Android history/chat wiring. Source and synthetic tests alone do not enable AI
in the app or authorize another provider turn/server deployment.
