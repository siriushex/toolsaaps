# Authenticated Daily Facts Host

This source stage connects facts-only preparation to the existing nonroot owner
API lifecycle. It does not install a new process, root timer, public route or
provider policy, and is not a live deployment receipt.

## Admission

Protected owner configuration version2 has a new optional actual-boolean
`daily_facts`, defaultfalse. It is independent of `daily_reads`, vision and
inference. Absent/false creates no facts owner/task. Even when true, only an
explicit encrypted per-device facts setting enables work; an active device is
not implied consent. Existing signed enrollment/identity storage is unchanged.

ClientAuth's trusted roster selects at most64 current owner-bound attested
sessions with matching session owner, nonrevoked key/family, nonfuture key
enrollment and activated/unexpired subscription. Overflow refuses the entire
roster rather than processing a partial membership. No access/refresh tokens or
credential rows enter this roster. A roster is a point-in-time candidate list,
not a continuing grant. Owner-scoped composition checks pinned storage around
the read and fresh stable session/key authorization before/after facts work.

There is no atomic transaction across the identity and history databases.
Revocation after authorization can allow an already-started bounded private
facts transaction to finish. Its post-check cannot become a publication or model
grant; the device is excluded from later work. No instant revocation/erasure or
clinical interpretation is claimed.

## Schedule And Resource Use

One ASGI-owned coroutine polls only the bounded roster/settings every60seconds,
using the saved per-device reporting zone. Existing `due_report_date` selects
yesterday after local05:00, not the host day or a backlog of missed model calls.
This is best-effort maintenance after05:00, not an exact or urgent alarm.

Successful FACTS_READY/UNCHANGED marks a setting-generation/local-date complete
in bounded process memory. The host does not rescan a completed day on every
poll. Restart can call the producer again, but its persisted source/archive
idempotency preserves the original report/date. Changed consent generation or
new local date is considered separately. Inactive session entries are pruned;
the in-memory membership never exceeds the admitted bounded roster.

Missing data and safe source failures retry after1/2/4/8/16/...30minutes, at most
48attempts per generation/day in one process. Unreadable settings have a30-minute
read pause. Retry counters are not durable across process restart, but facts
idempotency is; no model execution exists on this path. Expected failures retain
only fixed diagnostic codes, not raw payload/exception text. Internal READY
means the maintenance tick ran, not that data are complete or an AI report exists.

All synchronous storage/identity calls run off the event loop and drain through
the existing critical-await helper. One tick is serialized. Stop prevents new
device work; an already-started SQLite operation cannot be cancelled physically
and must finish before API roots/engines are closed. Existing per-operation
history limits remain; this is not a new global latency or resource guarantee.
No profile/model slot is acquired while reading facts. Other source domains,
including notes, meals/insulin/settings and clinical interpretations, are not
invented by a glucose-only producer.

## Analytical Range

The internal producer's explicit `ranges=None` mode means reuse the selected
day's existing archived analytical ranges, or leave range metrics unavailable
when no such report exists. Resolve it inside the same schedule/source/archive
transaction. It cannot overwrite a known analytical range with an implicit empty
default or extend yesterday's range into another day. An explicit empty tuple
still requests unavailable range metrics. No live AAPS target or dose changes.

## Lifecycle And Verification

The facts task starts only after API resources and existing services are owned.
ASGI teardown signals its shared stop event, drains the facts task and ordinary
maintenance, then closes text/photo workers and storage. Constructor/import
creates no background work. The optional flag does not alter HTTP capabilities,
proof rules, model choice, reasoning, tools, requests or foreground services.

Synthetic tests use genuine fixture attestation/subscription setup and encrypted
history, not live credentials. They cover disabled/default admission, valid and
foreign owner membership, expired/revoked/future grants, roster overflow, consent,
clock reversal,05:00/restart/day/zone changes, missing-data backoff/cap, delayed
uploads, source failure, no model queue, cancellation/drain and API lifetime.
Full backend and exact-head publication remain separate gates.

## Deployment Boundary

No production unit, configuration, history schema, key, enrollment or service was
changed by this source work. A read-only service inventory is not signed device,
daily processing or provider acceptance. Actual rollout must install compatible
source for every history writer before any schema6 opt-in, verify current
owner/device consent and storage, then explicitly admit the host flag. Preserve
the original API/profile and uncertain jobs; never reset or copy login state.

Disabling `daily_facts` removes this host task after an orderly API restart;
per-device disable prevents later admitted work without re-enabling anything.
Retained facts/source metadata remain subject to their original365-day retention.
Never downgrade schema6, restore stale medical history or start an incompatible
older API merely to roll back the scheduler. Model policy/profile/dispatcher,
ordinary clinical publication, protected settings UI and real overnight/device
acceptance remain separate. See DAILY_FACTS_SCHEDULE.md and OPEN_FEATURES.md.
