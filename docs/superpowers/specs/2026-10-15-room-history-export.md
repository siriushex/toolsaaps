# Bounded Room History Export

Scope: advisory server context only. No clinical database migration, treatment
write, prediction input, target, dose, alert or provider operation.

The core source reads bounded original-time/rowid keyset pages from glucose, therapy, forecasts,
sensitivity snapshots and an allowlist of numeric telemetry. Original event time
is retained; observation time is export observation time. Missing sensor,
calibration and historical timezone are not inferred from today's settings.
Recorded insulin never grants confirmed delivery. Existing therapy component and
artifact policies suppress synthetic/invalid/superseded carbohydrates.

A separate private no-backup Room database stores only hashed source identities,
content fingerprints, export UUID/revision, original time and scan progress.
It does not change copilot.db. One encrypted atomic page journal freezes actual
records and index deltas before sending. The existing signed immutable batch
client remains the network retry authority. After upload, index/progress commit
together; crash recovery reuses exact records and idempotently commits the page.
Device binding cannot change or silently clear either journal.

The expected device fingerprint also crosses the upload API and is checked inside
the connection's signing/transport mutex before refresh and immediately before
transport. A metadata comparison before an asynchronous call is insufficient.

Expiry is a separate observational-attempt retirement, not a mutable retry:
after original event-time retention elapses, an old mixed batch cannot be
accepted again. Persist its UUID/body digest/time in the encrypted transport
journal before creating replacement attempts for still-valid records. Those
records retain exact JSON, UUID, revision and original/observation times; fully
valid batches retain their exact bodies/IDs. Fully expired batches send nothing.
Retirement metadata is bounded to1024 entries and the existing1MiB checkpoint;
expired metadata is pruned on a later retirement. Storage/quota failure keeps
the original plan and sends nothing. No model job follows this retirement path.
The exporter can then commit its page cursor without re-uploading expired data.

The first scan covers the recent six hours, followed by a365-day backfill with a
ten-minute expiry margin. Thereafter, recent scans run every15minutes and a full
reconciliation at most daily. Older corrections/deletions can therefore await the
next full scan. Scans use a fixed original-time window, not a database-wide
point-in-time snapshot. Unchanged rows are
marked seen without uploading a new revision; changed payloads increment the
export revision without moving event time. A finished scan produces tombstones
for previously exported, now absent/unsupported records in its window. Recreated
tombstoned observations receive a new UUID. Retention removes expired index rows;
server expiry remains tied to the original event time.

Each invocation has a page budget. Progress and omissions remain explicit in the
chat context. App-owned maintenance runs only with ACTIVE server access and power
saving off:16pages then5seconds pause,15minutes after completion,60seconds after
failure. Disconnect/power saving cancels it; durable state survives process loss.
This is not an OS guarantee of execution while the application is stopped.
An explicit chat synchronizes up to four pages before its observational context.
A completed core scan does not claim complete database coverage:
GI/profile overlays, unlisted raw telemetry, plans, alarms/receipts, audit prose,
calibration models and other domain tables are not exported by this adapter.
Unlisted text and raw JSON never become a generic upload channel.

Validation: native SQLite/Room tests for bounded paging, original times, therapy
semantics, revisions, deletion/recreation, exact retry after source edits, index
commit/clear failure and device mismatch. Follow with the existing AI-client
tests and one integrated Android unit/lint/build gate. No device or server
activation follows from those checks.
