# Combo Inventory Candidate

Status: bounded pure catalog, encryption and assembly, not a live endpoint. It
does not consume the pump, allocate counters, persist receipts or admit therapy.
The existing paired read outbox remains the only request owner. AAPS production
sources and the retained native patch are unchanged in this stage.

## Pages

An inventory identifies one immutable retained snapshot and its pump-binding
digest. Read IDs are unique canonical lowercase UUIDs in ascending lexical order.
Each row has exact `readId`, `byteCount`, `sha256`, `closed`, `corrupt`, `adverse`,
`readFailed` fields. Size is 1..2097152; digest is lowercase 64-hex. Aggregate
limits are 4096 scoped plus unscoped corrupt reads and 32 MiB scoped bytes.
The flags are retained, not converted into a successful delivery diagnosis.

Every page contains exactly `version` (integer1), `snapshotId`, `pumpBinding`,
`sha256` (whole inventory digest), `totalReads`, `totalBytes`,
`unscopedCorruptReads`, `writerFailed`, `hasMore`, `reads`. No cursor is trusted
from the response: it comes from the expected signed request. Queries use the
existing read protocol, limit1..32 and either no snapshot/cursor or both present.
Nonfinal pages must fill the requested limit. Empty is valid only for an empty
initial inventory. Plain JSON is at most16384 bytes; no unknown/duplicate fields,
noncanonical numbers, malformed UTF-8 or implicit boolean conversion.

## Digest And Encryption

SHA-256 covers these ASCII lines in order, including the final LF. Values use
canonical decimal, lowercase UUID/hex and literal `true`/`false`; no escaping:

```text
aaps-combo-inventory-v1|snapshotId|pumpBinding|totalReads|totalBytes|unscopedCorruptReads|writerFailed
readId|byteCount|sha256|closed|corrupt|adverse|readFailed
```

The second line repeats for each ordered read. This binds flags and counts as
well as read hashes, detecting missing pages/entries and mixed snapshot content.

Pages reuse the private HKDF-SHA256/AES256-GCM implementation of the chunk codec,
with fresh salt/IV and a separate authenticated context (HKDF info and GCM AAD):

```text
aaps-combo-inventory-page-v1|master-to-client|aes-256-gcm|clientId|masterInstallId|requestId|counter|issuedAt|validUntil|snapshotIdOrDash|afterReadIdOrDash|limit
```

The initial null fields become `-`. Issue/expiry are canonical epoch millis,
maximum30s; accept at issue<=now<expiry. Wire is the unchanged closed version1,
saltB64, ivB64, ciphertextB64 envelope, at most49152 bytes. Expected context comes
from the request owner. This does not prove roster freshness or clock trust.

## Assembly

Assembly returns no inventory until count, byte total and full digest match.
Each continuation must use the same client/master, increasing shared counter,
new request ID and cursor equal to the previous page's final read. All header
fields must remain exact. An identical replay of the latest response is harmless
while current; other replay/order/scope/expiry errors latch failure and clear
partial state. Lists are defensive immutable copies. Assembly is in memory,
not a crash-recovery receipt and not the durable shared-counter authority.

Even a verified empty/complete inventory reports `pumpBindingVerified=false`
and `deliveryCompletenessVerified=false`. The inventory describes retained files,
not all events that ever occurred in the pump. No clock epoch, basal continuity
or safe meal-time permission can be inferred from this result.

## Remaining Runtime Gates

Native capability and paired command dispatch, target-first master arbitration
with post-I/O revocation checks, private committed response/receipt reconciliation,
authenticated pump epochs, clock changes and continuous basal accounting remain.
Independent protocol review and real-device resource/prospective safety evidence
are required before activating recommended meal time. No placeholder clinical
permission or live consumer is added to make tests appear complete.

## Checks

Inventory tests cover request binding, expiry, exact replay, omissions/reordering,
mixed scopes, every digest field, malformed wire, tampering, empty/4096 entries,
memory ownership and immutable copies. The offline driver independently frames
the fixture and uses pinned AAPS4 cryptography providers in both directions.
Existing chunk RFC vector and interoperability tests protect its unchanged wire.

Run `tools/aaps4_compat/prepare.sh` with the pinned AAPS_RC1_DIR and JAVA_HOME,
then full Android test/lint/compile/assemble with AAPS_INTEROP_CLASSPATH and
AAPS_INTEROP_JAVA_HOME. CI requires these provider tests, not silent skips.
