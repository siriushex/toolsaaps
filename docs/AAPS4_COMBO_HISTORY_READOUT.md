# Retained Combo Journal Readout

## Scope

The existing private journal now provides an internal read-only snapshot and
bounded chunk reader. This prepares the source side of later paired transfer.
It does not add a public endpoint, uploader, plugin callback, second pump reader,
exported Android component or new transport permission. Ordinary devicestatus
still carries counts only. No physical delivery or meal-time authority follows.

## Snapshot And Identity

Only the currently open journal owner can issue a snapshot. An active healthy
read is busy; after a latched writer failure, retained bytes can still be read
for diagnosis. Snapshot creation scans the bounded private inventory and derives
binding, damage flags and SHA-256 from the same bytes. Each matching read includes
its stable file ID, byte length, digest and closed/corrupt/adverse/failed flags.
An unknown binding contributes only to unscopedCorruptReads. Another pairing's
read IDs and raw bytes are not returned. Missing origin must not be treated as
zero history or assigned to the current pump.

Only one snapshot handle is current. Another snapshot request, any write attempt
or close invalidates it. Handles cannot be reused after reopening; stable read
IDs and digests let a future transport reconcile a freshly obtained inventory.
The snapshot is an in-process handle, not proof of authenticated destination,
current pump identity, upstream completeness or physical delivery.

## Exact Bytes And Limits

The reader accepts only an ID in its current immutable snapshot, an offset within
the read and a requested size from 1 to 65,536 bytes. It opens without following a
leaf symlink, rechecks full-file size and SHA-256 and returns copied original
bytes plus offset, total length, digest and explicit endOfRead. A short final
chunk is not loss; EOF is explicit. Mutation, deletion or invalid arguments fail
without a partial chunk and invalidate the handle, not the native writer.

Existing limits remain 32 MiB total, 4096 reads, 2 MiB per read, 256 frames and 16 KiB per
frame. Readout temporarily holds at most one 2 MiB raw read plus its bounded parsed
record/chunk, not the entire archive. Oversize/unreadable/invalid inventory fails
as a whole, without a truncated successful snapshot. Torn-tail files with a
validated BEGIN retain their original binding and exact damaged bytes. No repair,
deletion, pruning, UTC conversion or re-encoding is performed.

The app-private directory is trusted. CRC and SHA-256 detect accidental changes,
not malicious producer forgery. Raw files contain medical history and a private
pairing hash; they must never be attached to ordinary Nightscout uploads or logs.
Result strings are redacted and returned lists/byte buffers cannot mutate the
retained snapshot. A future paired transport needs separate authorization,
destination binding, ingestion, receipts and retention policy.

## Verification And Open Work

Synthetic tests reconstruct a multi-chunk read byte-for-byte, reopen the journal,
preserve unclosed/damaged evidence and refuse stale/forged handles, wrong-pairing
IDs, invalid offsets, symlinks, replacement files and over-limit inventories.
Writer failure remains visible while readable evidence stays unverified.
Run the two native Combo suites, reversible combined recipe, recipe isolation,
pristine SDK interoperability and full Copilot quality commands before CI/review.

Readout is not connected to a production caller. It is synchronized with journal
writes; filesystem latency and memory behavior require device measurement before
connecting background transfer. Independent pump binding, initial history,
clock epochs, physical basal continuity and prospective meal-time safety remain
open. No APK publication, phone/server deployment or therapy change is included.
