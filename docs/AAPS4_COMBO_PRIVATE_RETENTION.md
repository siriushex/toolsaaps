# Private Combo Journal Retention

## Scope

`AapsV3ComboJournalStore` retains exact scoped journal bytes in a separate Room
database, `combo_retained_history_v1.db`, under Android `noBackupFilesDir`.
The main clinical database, JSON/AI exporters, native AAPS patch and live owners
are unchanged. There is no network endpoint, automatic import, background worker,
source acknowledgement or meal-time admission in this stage.

Owner UUID, pump binding, read UUID and SHA-256 are caller-supplied claims. A
matching BEGIN prefix scopes damaged bytes; it does not authenticate a pump or
current pairing. A future receiver must independently authenticate the peer,
destination, revocation state and current snapshot before using this store.

## Transaction And Integrity

The dedicated database stores one immutable manifest and up to 32 chunks of
65,536 bytes per read. Small rows avoid SQLite cursor-window limits for a 2 MiB
read. The factory explicitly sets and verifies a TRUNCATE rollback journal and
FULL synchronous mode on open. There is no destructive migration fallback.

A retain call checks the canonical identities, size, digest and existing strict
journal decoder before writing. Missing or mixed origin is refused. Scoped
corrupt/unclosed/adverse reads remain intact diagnostic evidence with their
original status, never repaired or promoted to complete delivery.

Manifest and all chunks commit in one Room transaction; the complete result is
reconstructed and verified inside that transaction before returning. This is a
dedicated repository-owned transaction boundary: callers must not wrap it in an
external transaction and publish a receipt before that outer transaction commits.
SQLite errors/cancellation cannot become successful receipts. Storage failures
have fixed redacted codes without raw exception causes or medical payloads.

An exact replay validates the stored bytes again and returns `newlyStored=false`,
including after reopen or at capacity. Changed content under the same
owner/binding/read identity is refused without overwriting the first evidence.
The same read UUID under another owner or pump binding has a separate identity.

Reads recheck manifest bounds, chunk count/order/length, complete SHA-256 and
origin. Inventory checks every scoped read in a consistent transaction; damage
refuses the whole inventory, not a truncated successful list. Missing individual
identities return absent, not proof of complete source history. Public result
collections/byte copies are immutable and string representations are redacted.

## Limits And Privacy

Default capacity is 4096 reads and 32 MiB of raw bytes across all namespaces,
with a 2 MiB per-read limit. Metadata totals are reconciled with stored chunk
lengths. These are logical-data limits; SQLite pages/journal overhead are additional.
There is no automatic pruning, delete API, conflict repair or transport resume.

The app-private directory and Android UID isolation are trusted. The factory
rejects pre-existing database/sidecar symlinks. No additional at-rest encryption
or defence against a compromised app UID is claimed. `noBackupFilesDir` excludes
ordinary Android backup; this separate database is not part of the current
clinical JSON export or AI history synchronization. Do not attach raw databases
or bindings to logs, CI or public reports.

## Verification And Next Gates

Use native SQLite Robolectric tests for exact reopen, all chunk boundaries,
duplicate/concurrent writes, scope isolation, mid-write abort, capacity, modified
bytes, missing/invalid chunks, same-byte diagnostic damage, private location and
explicit SQLite modes. Tests use synthetic fixtures only. Run full Android
quality, actual pristine SDK TLS and APK retention checks before source CI/review.

No synthetic test proves Android-device power-loss durability, storage latency,
thermal behaviour or physical insulin delivery. Before a production owner is
connected, measure bounded ingestion on the device and verify authenticated
transport, destination/revocation and receipt ordering. Clock epochs, initial
FIFO coverage, basal/suspension continuity and prospective meal-time safety
remain separate admission gates. Receipt flags for pump binding and delivery
completeness remain false.
