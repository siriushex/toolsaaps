# Combo Chunk Envelope Candidate

Status: pure codec and synthetic interoperability only. No runtime endpoint,
capability advertisement, paired outbox or clinical permission is introduced.
Independent protocol review is required before activation.

## Scope

A response contains one immutable fragment from an authenticated retained-read
manifest. The expected request must come from the paired owner, not from the
response. The codec does not allocate counters, validate the roster, consume pump
history, acknowledge retention or write a therapy target.

`AapsV3ComboChunkBinding` has canonical lowercase UUID client/master/request/
snapshot/read IDs, lowercase 64-hex pump binding and full-read SHA-256, shared
counter greater than one, issue/expiry epoch milliseconds, total byte count and
offset. The expiry is later than issue by at most 30,000 ms. Accept only
`issuedAt <= now < validUntil`; caller clock and authorization are not established
by this comparison.

Total size is 1 through 2,097,152 bytes. Offset is nonnegative, smaller than total,
and a multiple of 32,768. Exact plaintext length is
`min(32768, totalBytes - offset)`. There are at most 64 chunks per read. An empty
EOF fragment is neither requested nor accepted. Full-read digest verification
remains the existing private assembly's responsibility.

## Cryptographic Contract

The 32-byte pairing secret is HKDF input key material, not the AES key. No PIN
key, stored enrollment envelope or direct command signature is repurposed.

For each new encryption, generate 32 random salt bytes and 12 random IV bytes
from the platform CSPRNG. Apply RFC5869 HKDF-SHA256 with the salt, the pairing
secret and the following ASCII `info` to obtain exactly 32 key bytes:

```text
aaps-combo-journal-chunk-v1|master-to-client|aes-256-gcm|clientId|masterInstallId|requestId|snapshotId|readId|pumpBinding|sha256|counter|issuedAt|validUntil|totalBytes|offset|chunkBytes
```

Identifiers/numbers above are replaced by their canonical values, without spaces,
quotes or a trailing newline. Closed alphabets prohibit the delimiter in fields.
The identical bytes are AES-GCM associated data. Encrypt with AES-256-GCM and a
128-bit tag appended to the ciphertext. The RFC construction needs one expand
block for this key length; the Android implementation delegates HMAC and AES to
JCE. The native test oracle independently uses RC1's cryptography-kotlin HKDF
and AES-GCM provider and reconstructs a pinned synthetic request context.

The entire wire object is UTF-8 JSON with exactly these fields:

| Field | Value |
| --- | --- |
| `version` | Integer `1` |
| `saltB64` | Canonical padded standard Base64 of 32 bytes |
| `ivB64` | Canonical standard Base64 of 12 bytes |
| `ciphertextB64` | Canonical padded standard Base64 of `chunkBytes + 16` bytes |

Field order is not significant. Duplicate/unknown keys, malformed UTF-8,
noncanonical numeric or Base64 forms and nonmatching lengths are rejected.
The envelope is limited to 49,152 bytes before parsing; the enclosing settings
document still has its independent 65,536-byte bound. Maximum fragment wrappers
are tested below 49,152 bytes. No compression or streaming unauthenticated
plaintext is used. Context, plaintext and provider errors do not enter logging.

## Ownership And Retry

`seal` copies the plaintext and clears its copy and derived key on completion.
`open` returns authenticated bytes to the caller, which must clear them after
private assembly/retention. `close` clears the codec's copy of the pairing secret.
Provider key schedules and immutable JVM copies cannot be guaranteed erased.

Each seal receives a fresh salt and IV, including a restart/retry. The eventual
outbox must instead persist and replay the identical accepted envelope where
exact retry is required. Never reuse a stored salt/IV to encrypt changed bytes.
The codec intentionally permits reopening the same valid response: replay
admission and committed receipt reconciliation belong to the shared paired owner,
not to an in-memory crypto object. A new request/counter/deadline changes the key
context and cannot accept an old response.

## Required Before Runtime

Authenticated manifest/capability exchange, source/pump epoch provenance, shared
counter/outbox priority, revocation checks after suspensions, aggregate quotas,
atomic private retention plus reconciled receipts, crash recovery and measured
device I/O latency are still required. Routing, sizes and timing are observable.
Successful authentication is not proof of physical insulin delivery, complete
basal history, clock mapping or safe meal timing. All existing clinical gates stay.

Reference: [RFC5869](https://www.rfc-editor.org/rfc/rfc5869.html).
