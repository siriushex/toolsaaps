# Durable Daily Review Queue

Internal encrypted reservations and one-shot work handoff. No provider, native
worker, scheduler, public route, global-slot admission or therapy activation.

## Logical Reservation

HistoryStore.enqueue_daily_glucose_review accepts only the most recent completed
civil day in the configured reporting zone, not arbitrary backfill. It freezes
source-checked DailyReviewWork, exact input/schema/prompt/range/source bindings
and an original random queue UUID. The UUID is the future correlation request ID.
Owner/device/date/zone form a unique logical key, independent of prompt, language
or report revision. Repeated calls, including after restart or cancellation,
return that original entry rather than making another automatic request.

Enqueue uses a read lookup, existing protected preparation and a separately
serialized write with a second uniqueness check. Source can change between
preparation and insertion: queued work is not a currentness or dispatch permit.
The handoff rechecks it. An old frozen entry is not silently refreshed or rerun
when new facts/policy appear. Explicit manual re-analysis is a separate future
workflow, not deletion/recreation of the automatic reservation.

Only explicit enqueue enables additive history schema 4, preserving schema 1
history, schema 2 chat and schema 3 reports. Read/get/handoff/cancel never migrate
or create a device database. A successfully committed additive migration may
remain after a failed payload write; the failed entry itself is rolled back.

## Protected Storage And Bounds

daily_review_queue resides in the existing private per-device HistoryStore file,
using its key derivation and distinct daily-review-queue-v1 AEAD purpose. Sequence,
UUID/logical key, state and original/update/dispatch/expiry clocks are authenticated
along with the complete encrypted payload. Minimal metadata remains visible in
the protected file and is not anonymized medical metadata.

One entry is at most 128 KiB, with at most 512 retained entries/device. Existing
history/chat/report row/byte/device/free-space quotas include queue payloads.
Each active SQL transaction shares the existing five-second/50-million-instruction
budget, including work reconstruction, quota checks and commit/exit check. Lock
acquisition/private-file preflight/additive migration are separate existing bounds;
enqueue has several bounded lifetimes, not a five-second total API guarantee.

Dispatch is allowed only before the end of the next civil day. This respects
23/25-hour DST days; it is not a fixed 24-hour addition. Entry/audit expiry is fixed
to report-day end plus 365 days and never extended by reads, cancellation or retries.
An expired dispatch window does not erase its retained reservation/audit. Purge
removes an entry only at its fixed retention expiry.

Frozen payload reads check envelope/hash/source/date/metadata bindings without
requiring today's prompt pin. An old v1 prompt remains auditable; actual handoff
requires current exact work/prompt/schema bindings. Future codec revisions must
preserve the old read types rather than silently changing frozen defaults.

## One-Shot Handoff

HistoryStore.handoff_daily_glucose_review authenticates the queued entry, current
latest report and latest retained source, then reconstructs exact canonical work
inside one serialized write transaction. Expiry, changed source, superseded report,
policy/input mismatch, integrity or resource failures do not return work.

Before returning, it persists HANDOFF_RECORDED and its original handoff time. At
most one concurrent caller obtains DailyQueueHandoff. Further calls/restarts return
no work. If commit succeeded but acknowledgement/exit deadline failed, the marker
remains and work is not reissued. It is at-most-once delivery, not exactly-once
inference, proof of model submission, successful analysis or confirmed process stop.
Uncertain delivery can lose an opportunity to analyze; trusted reconciliation is
required, never an automatic new model request.

The returned request_id/work/source_check is not provider or global-slot admission.
The entry explicitly says NO_PROVIDER_OR_SLOT_ADMISSION. No JobLedger/profile slot
is reserved while queuing/scanning. Interactive chat can still use its existing
slot; actual interactive-first dispatch arbitration remains part of the dedicated
worker, not a claim that handing off alone launches or prioritizes model work.

HistoryStore.cancel_daily_glucose_review cancels only queued work and preserves
the logical reservation. It cannot pretend to stop handed-off work. The get
method is an internal authenticated audit read; viewing frozen work is not a
permission to bypass handoff or rerun a model. No public/admin projection exists.

## Separate Execution Gates

A future worker needs explicit per-device opt-in, dedicated DAILY_REVIEW policy/
transport admission, current trusted model/reasoning, shared profile/ledger
ownership, interactive-first slot arbitration and independently confirmed stop/IO.
It must freshly coordinate source checks with actual submission and result
publication. Encrypted results, durable settlement, uncertain-outcome reconciliation,
quality admission and read-only device/admin routes are not implemented here.
No result is marked validated/published by this queue.

## Migration And Recovery

There is no live migration, server install or backup in this source stage. Code
accepting only schemas 1-3 cannot reopen schema 4. Do not drop its table, lower
user_version or restore an older queue snapshot to cause another submission.
Any operational rollout needs compatible code and a coordinated protected restore/
ledger reconciliation plan; uncertain original objects must be preserved.
This is not forensic anti-rollback/deletion detection for an operator-writable
database. Queue expiry cannot release an unknown global native execution slot.

Synthetic tests cover restart/dedup, concurrent enqueue/handoff, source/report
changes, cancellation, uncertain commit, old prompt audit, clocks/DST, corruption,
owner/device/key isolation, retention, quotas, no migration on reads, shared old
history/chat compatibility and no slot reservation. No real model or medical data.
