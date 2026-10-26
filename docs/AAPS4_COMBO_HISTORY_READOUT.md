# Retained Combo Journal Readout

## Scope

The existing private journal now provides an internal read-only snapshot and
bounded chunk reader. This prepares the source side of later paired transfer.
It does not add a public endpoint, uploader, second pump reader,
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

Only one snapshot handle is current. Another admitted snapshot request, any write attempt
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

### Reserved Control Responses

`combo-history-control.patch` isolates responses for the authenticated
`combo_history_read_` family at `aaps_clientcontrol_ack_history_<clientId>`.
The existing target/delivery key is unchanged; stock push/poll paths ignore
the new response key because it remains under the ACK prefix.

The family is currently refused before stock payload/Hello dispatch, with a
signed terminal failure and no payload. Expiry/control-disabled paths use the
same isolated response key. Invalid authentication/replay/skew are ignored.
A valid signature is not permission to disguise a stock action as a history
request. No successful inventory/chunk response or capability is implied.
Future asynchronous transfer must retain the response separation and perform
its own bounded query, epoch, admission, encryption and durable receipt checks.

### Query Contract

`combo-history-query.patch` supplies a pure `ComboHistoryReadRequest` decoder in
the shared native SDK, tested with actual Copilot-generated requests. It requires
the expected master installation, canonical UUIDs, matching outer/inner type,
version1, ACK request, counter>1 and issue<=now<expiry (maximum30seconds).

The signed payload is compact ASCII JSON <=2048characters, at most two object
levels, without escapes/arrays or duplicate fields. The only unions are inventory
with limit1..32 and either both cursor identifiers or neither, and chunk with exact
snapshot/read/binding/digest plus size/offset/length. Reads are <=2MiB; chunks are
32KiB-aligned and exact, including the shorter final fragment. No rounding or
implicit repair is permitted. Outer settings field reordering does not change the
payload string and is not restricted by this inner contract.

This decoder accepts structure, not authority: the paired owner still must verify
MAC, replay, control/driver generation and deadline after suspended work. It has no
secret, source reader or dispatcher; successful decoding does not bypass the
current receiver refusal, advertise capability or grant clinical completeness.

### Archive I/O And Writer Priority

Archive listing, reading, hashing and parsing no longer hold the writer monitor.
A short admission section captures a local read epoch, owner path and failure
state. Every write attempt and close invalidates the epoch. Scans recheck between
files and before committing; chunks recheck their exact snapshot after I/O and
hash validation. Concurrent changes never yield a mixed successful inventory.

A separate non-waiting permit admits at most one archive operation per journal.
Busy calls fail without queuing or invalidating the current reader. The permit
releases on every exit, including cancellation or fatal exceptions. Writes and
writer close never acquire it. The writer still serializes its own fsync work;
this is not a hard real-time latency guarantee. Calls inheriting the writer
monitor are refused before archive I/O rather than holding that outer monitor.

An obsolete/forged handle can invalidate only itself, never a newer snapshot.
Facade close sets a terminal visible flag without waiting for its read lock;
any late read must fail final validation. The local epoch is not a persistent
pairing epoch, clock mapping, receipt or physical delivery proof. Existing file
limits and private-directory trust remain unchanged.

Ten deterministic latch-based regressions cover write/close progress, stale
results, busy readers, cancellation, old handles and inherited monitor rejection.
The combined native recipe passed with 697 selected report tests and restored
the pinned checkout; no failures or skips. Sequential pristine SDK preparation
and a fresh Copilot suite passed (6991 passed, six existing skips), as did lint
and build. These checks establish concurrency behavior, not clinical admission.

### Native Driver Access

`combo-history-source.patch` adds `RetainedPumpHistorySource` to core interfaces
and implements it in ComboV2 through `ComboRetainedHistory`. The source uses only
the existing journal instance. Before the first ordinary retained read there is
no source; it never creates a writer to satisfy a remote request. Multiple paired
addresses are refused rather than selecting an arbitrary first address.

The adapter checks current pairing binding, writer identity and writer failure
before/after I/O, plus the journal's snapshot token. Its immutable metadata is a
historical inventory, not a continuously valid authorization: callers must use
`requireCurrent()` after suspended work and immediately before publication.
Observed owner loss, invalid requests and failed reads latch the handle invalid.
Changing a file is detected by the existing full-file hash on chunk reads.

The adapter narrows fragments to exact aligned 32 KiB units with a short final
fragment, matching Copilot's envelope. EOF/zero-length, wrong IDs, overflows and
other geometry fail closed. Returned byte arrays belong to the caller; failed
post-read validation clears the outgoing copy. `close()` invalidates only the
reader, not the pump writer. Cancellation is propagated without raw error details.

The lower-level journal still permits diagnostic reads after writer failure;
the plugin facade conservatively refuses its latched unavailable state. This
does not conceal damage from any successfully admitted inventory. A new valid
pairing may see no scoped files; that is not evidence of zero insulin delivery.

There is no persisted pairing epoch: an unobserved change-and-restore cannot be
detected by matching current values alone. The client-control receiver, signed
capability, native encrypted responses and persistent receipt remain unconnected.
No caller may infer that implementing this interface authorizes remote transfer.

Synthetic tests reconstruct a multi-chunk read byte-for-byte, reopen the journal,
preserve unclosed/damaged evidence and refuse stale/forged handles, wrong-pairing
IDs, invalid offsets, symlinks, replacement files and over-limit inventories.
Writer failure remains visible while readable evidence stays unverified.
Run the two native Combo suites, reversible combined recipe, recipe isolation,
pristine SDK interoperability and full Copilot quality commands before CI/review.

Readout is not connected to a production caller. Archive I/O is outside the writer
monitor; filesystem latency and memory behavior still require device measurement before
connecting background transfer. Independent pump binding, initial history,
clock epochs, physical basal continuity and prospective meal-time safety remain
open. No APK publication, phone/server deployment or therapy change is included.
