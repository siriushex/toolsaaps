# Combo Read Outbox Candidate

Status: internal outgoing lane, disconnected from runtime. This is not the live
Combo transfer, a clinical coverage receipt or an extended AAPS release.

## Contract

The candidate uses native SignedEnvelope framing/MAC and the explicit new type
`combo_history_read_v1`. Its signed payload contains only `type`, integer
`version: 1`, `masterInstallId`, UUID `requestId` and a closed typed `query`.

- Inventory: `kind: inventory`, limit 1..32; the first page has no cursor;
  subsequent pages require both canonical UUID `snapshotId` and `afterReadId`.
- Chunk: `kind: chunk`, snapshot/read UUIDs, 64-hex pump binding and full-read hash,
  total size, offset and exact derived length. Bounds match the existing encrypted
  chunk response candidate: at most 2 MiB per read, aligned 32 KiB fragments.
- The paired owner supplies client/master IDs, secret, shared counter and current
  issue time. Lifetime is 30 seconds; arbitrary caller envelopes are never signed.
- Request routing remains the existing settings command naming scheme. The stock
  RC1 closed payload schema refuses this extension. Native MAC compatibility alone
  does not advertise support or permit live sending.

## Local State

One encrypted `combo-read` row retains client/master identity, typed query,
request ID, counter, issue time, publication revision, roster revision and phase. The request,
outbox document and root counter commit together in one owned transaction.
No nested/external transaction may expose an uncommitted request or receipt.

Admission requires the current paired identity, enabled roster revision and a
fresh current-session authenticated pong. Outstanding liveness probes defer reads.
Queued target actions and unresolved target operations also defer them. Reads
never set or clear the target slot, acknowledge a target, or modify action status.
Target/probe issuance remains independent of low-priority record parsing.

Capacity checks preserve one journal row plus two maximum-sized settings documents
for target prepare/commit, in addition to the candidate request and reply documents. This is
headroom against this lane, not an isolation guarantee against other collections.
There is one outstanding request. New IDs cannot overwrite an admissible one.

## Recovery And Preemption

Recovery checks the exact committed revision/source/document and rebuilds the
candidate solely to verify its original signature context. JSON field ordering
outside the signed payload is insignificant because storage canonicalizes it.
The original document is returned with `changed: false`; no new publication,
counter, signature identity or deadline is issued. Clock/admission are checked
again after storage work, before the transaction returns.

- A larger shared counter, pending target or queued target marks the request
  SUPERSEDED. Recovery returns no sendable document.
- Expiry marks EXPIRED. Lost/disabled pairing marks REVOKED. These states remain
  terminal for the retained request ID even if control is later restored.
- The request pins its original roster revision. Any later accepted roster
  revision revokes it, even if control was disabled and restored without an
  intervening read snapshot. Identical roster observations retain the revision
  and do not cancel reads. Legacy read rows with no revision pin are revoked.
  This deliberately conservative rule does not change pairing/target writers or
  require them to decode low-priority history state.
- A new request ID may replace the singleton after terminal state; it must pass
  fresh admission. This is bounded state, not a full historical retry ledger.
- A changed/missing/deleted/revision-mismatched committed document fails closed;
  it is never silently repaired or republished.
- Old wire bytes may already be in transit or visible to the peer. Local
  supersession cannot cancel remote work. The native receiver must recheck
  deadlines, revocation and command priority after suspended I/O.

## Authenticated Reply Admission

The same paired owner can now receive the isolated history ACK directly from its
local settings store. No caller supplies the key, pairing secret or arbitrary
expected scope. The row must be current-owner, nondeleted and newer than the
request publication. Ordinary target ACKs are never read or changed by this lane.

Before receipt, recovery validates the exact outbox, shared counter, original
roster revision, session pong, priority and 30-second lifetime. Closed native ACK
fields, identity/counter, timestamp, bounded optional values and the stock HMAC
are validated. Success then requires AES-GCM authentication and typed decoding
against the original request. Inventory pages and exact chunks cannot exchange
contexts. Signed failure/expiry require no payload; arbitrary reasons are not
shown to the user or logged. Invalid responses fail without changing the journal.

RECEIVED stores only the encrypted response ACK, local source revision and receive
time in the existing encrypted singleton. It returns no raw data or sendable
request. Exact duplicate observations make no writes; changed responses cannot
replace the first receipt. Validation bytes are cleared. Final clock/liveness
checks and receipt commit share the owned transaction; cancellation or write
failure cannot return a successful receipt. No second transaction owner is added.

RECEIVED remains pending: it cannot be overwritten by a new read. Target/probe
preemption, roster changes and the original deadline still retire it and clear
its stored response. Recovery cannot extend that deadline or turn receipt into
physical-delivery authority. There is deliberately no post-deadline content
recovery or source acknowledgement: the future raw-retention coordinator needs
an explicit durable handoff before this lane can run continuously.

The native runtime still refuses history reads. This cold receiver is not called
by the live target channel and does not advertise capability. Next integration must
authenticate capability, retain raw bytes privately, then reconcile the committed
retention receipt through this owner before publishing any source acknowledgement.
It must never delete source history on a mere transport ACK or test success.

## Validation Boundary

Native SQLite regressions cover exact same-file recovery, one-counter concurrency,
target priority, reserved capacity, partial-write rollback, cancellation, clock
advance, explicit revocation and inherited transactions. The pristine RC1 driver
checks the native MAC and explicit unsupported-schema result for three queries.
Neither test changes a pump nor establishes insulin delivery, UTC mapping,
basal completeness or safe meal-time advice. Independent protocol review and
measured device I/O/priority acceptance remain mandatory before activation.
