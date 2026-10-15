# Device Context And Native Text Adapter

This local implementation follows the approved owner-only365-day history and
advisory AI design. It connects the existing signed job contract to history and
the pinned native text protocol through an explicitly injected contained process.
It does not install a launcher, expose a new route or change Android/therapy.

## Authenticated Context

JobWork now contains immutable owner/device-key identity taken from the already
verified AttestedDevice, plus server acceptance time. Neither question text nor
history metadata can select another device. Dispatch retains its existing fresh
session authorization. The external CHAT DTO and route limits are unchanged.

HistoryStore.context_snapshot is an internal read-only single SQLite transaction.
It decrypts latest nondeleted records within the original365-day expiry and not
later than the server's event/observation/receipt cutoff. It does not read another
device, mutate the schema, materialize a plaintext database or modify a record.
Five seconds of scan time,50million SQLite instructions and the existing row
quota bound work. A corrupt/over-budget scan fails, never reports partial totals.
The permanent file lock remains held during the bounded scan; contention is an
explicit availability risk to measure before deployment, not hidden background
work. Result encoding is additionally bounded independently of scan limits.

Counts include every eligible record and UTC daily counts. Glucose cohorts stay
separate by declared source, quality and full historical context. Their means are
record-weighted, not time-weighted or time-in-range. Missing labels remain unknown;
a shared unknown bucket does not prove a shared sensor/calibration. Sources,
quality and values remain device declarations, even when source says SERVER.

Recent examples are chosen by original event time, not backfill sequence, with
up to12 per kind and a round-robin byte budget. Daily detail, glucose cohorts and
raw examples have independent budgets; omission counts and actual sample dates
accompany the result. The98,304-byte context is not a claim to include every raw
record in the prompt. There is no inference of normal glucose from absent data,
confirmed delivery from a bolus record, GI from CGM or absorption from carb entry.

## Text Session

The trusted contextual policy changes prompt_revision and therefore the existing
request digest. The selected model is explicit; no fallback or reasoning-effort
override is added. A neutral initialized client uses an ephemeral thread, empty
environment/dynamic-tool/capability roots, read-only sandbox and never approval.
The pinned catalog must use direct dispatch with all capability fields disabled:
feature flags alone cannot override a code-mode-only model descriptor.

The adapter accepts one request/turn, bounded unique-key JSON frames, matching
thread/turn/model identity and exactly one completed final answer. It verifies
reported sandbox/approval/cwd settings and refuses drift, tool requests, tool
items, unknown warnings/events, malformed/oversized output and duplicate answers.
Only the exact hashed known startup notice is permitted. Raw warnings/reasoning
are not retained. System instructions stay separate from the untrusted JSON data.

ContextualChatWorker preserves the immutable job binding, checks its deadline
before and after context retrieval, and delegates process start/stop to a trusted
ContainedTextProcess. Cancellation joins the bounded history reader; even a
cancelled start requires independent process-stop confirmation. No retry occurs.
Existing JobExecutor settlement remains authoritative for publishing results.

## Evidence And Remaining Work

Synthetic tests cover signed API to context to native protocol to settled answer,
same-owner device isolation, revisions/deletions/expiry/clock rollback, bounded
omissions, corrupted storage, cancellation and refusal of changed runtime settings.
They do not prove a deployed worker or model reasoning quality on clinical data.

The independently approved synthetic native check previously returned exact OK
with positive teardown. This repository adapter still requires actual pinned
runtime integration, an unprivileged API plus narrow trusted supervisor, persistent
server request/result archive, storage/purge/restore/load validation, Android
history synchronization/server-chat routing and actual-device end-to-end testing.
Imported CHAT history remains DEVICE_ARCHIVE, not verified server conversation.
No automatic therapy or live clinical model admission is introduced.
