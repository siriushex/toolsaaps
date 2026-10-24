# Combo Journal Consumer Integrity

## Scope And Authority

Copilot now has a bounded in-memory assembler and strict decoder for the retained
native journal format. This is the consumer integrity boundary for later paired
transfer, not a running receiver, durable ingestion service or clinical timeline.
No endpoint, worker, storage table, permission, pump command or timer activation
is added. Native production code is unchanged; its test pins the same synthetic
writer digest as the Copilot test.

The expected read ID, size, SHA-256 and pairing hash are caller-supplied claims.
CRC/SHA detect accidental damage, not producer authentication. Even a structurally
valid closed journal keeps pumpBindingVerified and deliveryCompletenessVerified
false. A validated prefix match is not an independent proof of the current pump.

## Assembly

One assembly owns one canonical UUID, one expected digest/size and one expected
binding. It accepts contiguous chunks of 1..65,536 bytes within a 2 MiB read,
at most 256 distinct chunks. Exact previously accepted chunks are idempotent;
gaps, overlap, changed duplicates, wrong identities/size/digest, premature EOF
and missing final EOF latch refusal and clear the in-memory buffer. The complete
SHA-256 is checked before parsing. Close invalidates the assembly. Nothing is
acknowledged as durably stored, and no restart/resume state is implied.

The existing settings payload limit is 65,536 bytes. A future encrypted/base64
wire envelope therefore needs smaller raw chunks, not a full 65,536-byte chunk
inserted directly into settings. Raw history must not enter ordinary devicestatus
uploads or an unbound Nightscout destination.

## Journal Validation

The decoder copies at most 2 MiB and checks up to 256 big-endian length/JSON/CRC32
frames, with at most 16 KiB per JSON body. UTF-8, duplicate keys, exact allowed
fields, types, version, binding, phase order and reader-state fields are strict.
Reader clocks/counters reuse the same parser as retained status diagnostics.
Start time stays identical across a read; finished-before-start remains explicit
clock-change evidence without rewriting either timestamp. Adverse flags cannot
disappear, counters must follow BEGIN/BLOCK/CONFIRMED/END transitions, and event
count increments must match the block header. Unknown events remain separate
from parsed events. Block size, error bytes and continuation/gap flags are checked.
RETURNED and later BLOCK phases must agree with the native reader's existing
continuation/remaining-event termination rule, without claiming initial coverage.

Raw event bytes are retained unchanged. Before accepting a BLOCK, each 18-byte
event must pass both little-endian CRC-16/MCRF4XX checks: bytes 0..9 for timestamp,
detail and type; bytes 12..15 for the event counter. The seed is 0xffff, reflected
polynomial 0x8408, with no final xor. A valid outer frame CRC cannot mask event
damage. Calendar validation includes the packed local timestamp for every type,
including unknown types, and the replacement local date/time for type 24.

Known types match the pinned RC1 parser: 4..11, 14..19, 24. Their count and the
separate unknown count must match the reader's exact per-block increments.
Unknown types require the unmapped flag, and type 24 requires the clock-change
flag. Unknown detail bytes are preserved without inventing their meaning.
Unsigned counter and local-time order are audited within each read, including
across confirmed block boundaries. Repetition of the first bytes observed for a
counter marks EVENT_DUPLICATE; different bytes under that counter mark
EVENT_COUNTER_REUSED. A smaller counter marks EVENT_COUNTER_REWIND, including
the possible 32-bit rollover case; no automatic unwrap or reset epoch is inferred.
A backwards local timestamp marks EVENT_TIME_REWIND. Equal timestamps are allowed.
Counter gaps alone imply neither missing events nor complete delivery.

These are adverse diagnostics, not a reason to discard or deduplicate valid
event bytes. They apply to unknown as well as known events and remain sticky
across later blocks or corruption. A block enters the order audit only after all
its checksums, calendars and reader counters pass; an invalid tail cannot supply
new order evidence. State is bounded by the existing read/frame limits, starts
fresh for each read and is never joined across caller-supplied bindings.
No ordering repair, deduplication, clock epoch or delivery semantics are inferred.
Valid backwards local dates stay unchanged; they are not UTC instants.
The reader clock is never substituted for delivery time. Neither CONFIRMED nor
END proves physical delivery, initial FIFO coverage, delivery-start time or
continuous basal insulin.

A torn tail preserves the valid prefix, exact original scoped bytes and all
previous hazards, but its status is invalid, never partial success. An interrupted
well-framed read remains adverse/unclosed. Unconfirmed blocks survive both paths.
A missing valid origin exposes no bytes. A mixed-owner file is rejected as a
whole, with no decoded frames or raw bytes returned under the first owner.
Results and payload accessors are immutable/copying and string output is redacted.

## Verification And Next Gate

The native writer and Copilot pin SHA-256
`905c31d3a78a019f31c3da1963e259e70cd519ab02f44c5b040d21d08b128e2c`
for the same synthetic gap/backwards-clock read. No phone data is used.
Tests cover every truncation offset, CRC/JSON/UTF-8 corruption, mixed ownership,
counter/clock/phase errors, unknown-event counts, unconfirmed/failed reads, limits,
mutable output attempts, chunk retries, substitution and permanently failed assembly.
Event tests additionally check every single-bit mutation of known/unknown events
with a freshly valid outer CRC, malformed dates with correct event checksums,
all calendar days in the packed 2000..2063 range, time-field boundaries, clock
changes, wrong type counts and rejection of a whole block with a damaged last
event. An unchanged upstream RC1 PumpIOTest event and its CRCTest checksum anchor
the independent synthetic CRC reference. No device data is used.
Order regressions cover identical and conflicting counter reuse, nonadjacent
repeats, unsigned sign/rollover boundaries, counter gaps, backwards times,
cross-block continuity, unknown events, corruption and isolation between reads.

Before production use, connect paired encrypted request/response to the existing
durable counter owner, validate recipient/revocation and current snapshot, add
private atomic persistence/receipts and bounded inventory retention. No second
consuming pump reader is allowed. Delivery semantic validation, independent binding,
historical clock epochs, complete delivery and prospective meal-time safety remain
separate gates. No therapy permission follows from these source-level tests.
