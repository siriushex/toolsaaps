# Native Combo Reply Codec

## Scope

`combo-history-reply.patch` adds `ComboHistoryReadReply` and common SDK tests to
the pinned RC1 compatibility recipe. It produces encrypted wire payloads from a
bounded immutable catalog and exact caller-supplied fragments. It does not open a
source, select a driver, authenticate a client, allocate a counter, publish an ACK
or advertise capability. The existing native receiver still refuses live reads.

The owner must supply the previously authenticated request and a current source
snapshot. Constructing a request/catalog does not grant authorization. Source
ownership, active-driver/roster generation, target priority and request deadline
must be rechecked after I/O/crypto and before publishing. These checks cannot be
replaced by the codec's supplied `now` value.

## Contract

- Pin client/master IDs, snapshot UUID and pump binding. Entries are copied and
  kept private, strictly ordered and unique, with positive size and exact digest.
- At most 4096 entries including unscoped damaged reads, 32 MiB total declared
  bytes and 2 MiB per read. No pruning or sorting to hide invalid input.
- Inventory pages contain at most 32 entries. A continuation must match this
  snapshot and an existing non-final cursor. Whole-catalog count, byte total,
  SHA-256 and every adverse flag are retained on each page.
- A chunk must match an existing read's snapshot, binding, ID, size and digest,
  plus the decoder's exact aligned 32 KiB geometry (or shorter final fragment).
  The codec cannot prove that supplied bytes actually came from the declared
  source or verify a whole-read hash from a single fragment. The source and
  downstream complete-read verifier retain those responsibilities.
- Reply framing matches Copilot: version 1, canonical Base64 salt/IV/ciphertext,
  48 KiB maximum wire, inventory plaintext at most 16 KiB, chunk at most 32 KiB.

The shared native cryptography provider supplies HKDF-SHA256 and AES-256-GCM.
Each operation draws a new 256-bit salt and 96-bit IV. The exact expected request
context is both HKDF info and authenticated additional data, with distinct page
and chunk domains. No new primitive or dependency is introduced. The pairing key
is copied per call, never retained on the catalog. Owned key/plaintext buffers
are cleared on exit; caller buffers are unchanged. Provider-internal copies and
immutable JVM/Kotlin strings are not claimed to be securely erased.

All errors have a fixed redacted code without their original cause. Cancellation
propagates. The codec is stateless with respect to replay: the paired owner must
still admit counters and lifecycle before each call. Retrying encryption is not
permission to resend a request or grant source-retention acknowledgement.

## Verification

The native common suite covers pages/digest/hazards, mutation isolation, empty and
maximum catalogs, scope/cursor/chunk mismatches, key/time/size bounds, fresh entropy
and independent request context. The external JVM driver compiles the exact
candidate decoder and reply source beside the unchanged RC1 SDK. It accepts only
synthetic signed requests and builds fixed synthetic rows, never real history.

`AapsV3ComboNativeReplyTest` decrypts these replies using actual Copilot codecs,
checks whole-catalog digest and flags, full/short/max-size fragments, tampering,
wrong request context, empty/max pages and source mismatches. No manually copied
native encoder is used by that oracle. CI requires the native driver fixture.

The codec is the eighth frozen patch; a ninth, isolated error-timeout patch fixes
the upstream lifecycle race exposed by this stage's CI. The recipe restores only
its own unchanged edits. SDK preparation extracts both protocol files in an owned
temporary directory; missing/wrong sources refuse before Gradle. No production
runtime, database, APK version or therapy change follows from a passing test.

Next integration still requires an authenticated asynchronous owner, actual
read-only source mapping, generation/priority/revocation tests and measured device
I/O. Physical delivery, pump UTC epochs, basal continuity and prospective meal
recommendation safety remain independent gates.
