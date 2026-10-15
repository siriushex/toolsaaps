# Personal Device History API: First Stage

The owner approved the contextual server architecture, own devices only and365-day
retention. This stage implements the local encrypted store and explicitly injected
signed API. It does not deploy a server, copy credentials, upload actual medical
data, connect Android, run inference or authorize GI/therapy changes.

## Contract

`POST /api/ai/v1/history/batches` accepts exactly `batchId` and `records`.
The batch ID and record IDs are canonical UUIDs. At most32 records and128KiB per
request are accepted. Records contain `recordId`, `revision`, `eventAtMs`,
`observedAtMs`, `kind`, `source`, `quality`, `deleted`, `data`, plus optional closed
`context` (historical IANA zone, sensor session, calibration model, measurement
basis and input model version). Missing context remains missing, not current
settings or a guessed calibration. These selected fields are a transport
foundation, not a complete export of every Android database column. No client owner,
device ID, path, credentials, arbitrary raw payload or executable command exists.
The source/quality/observation metadata are client declarations, not independent
attestation of CGM accuracy, physical delivery or historical arrival.

Supported typed observational categories are GLUCOSE, THERAPY, MEAL, FORECAST,
SENSITIVITY, ACTIVITY, DEVICE, ALERT, DIAGNOSTIC and CHAT. Unknown fields, invalid
numbers, inconsistent intervals, missing GI provenance and oversized text reject
the entire batch. CHAT imports are explicitly device-archive text, not evidence
that a server model produced or validated the answer.

`POST /api/ai/v1/history/query` accepts exactly `fromMs`, `untilMs`, `kinds`,
`afterSequence`, `snapshotSequence`, `limit`. A bounded page returns latest
revisions as of an explicit device-local high-water mark, with a resumable cursor,
remaining-page indication and64KiB response budget. End time is exclusive. This is
record retrieval, not statistical completeness or an LLM context builder.

Both routes require current bound access, P-256 exact-method/path/body proof and
one-use nonce. Authorization is rechecked after proof and before storage; an
already admitted request may finish during concurrent revocation. The body carries batch identity; its exact bytes are signed and
used for the durable retry digest. A retry needs a fresh proof. No inference
worker is needed and default app factories mount neither route.

## Identity And Storage

The service is explicitly configured for one owner. It derives storage identity
from that authenticated owner and enrolled device-key fingerprint. Same-owner
devices remain separate, and access-token refresh preserves the same store.
Key replacement/re-enrollment cannot silently merge old history; verified
reassociation is a separate stage. Client-provided identities cannot select files.

Use a private owned local directory and generated filenames; reject symlinks,
foreign databases, unsupported schemas and incorrect encryption keys. Each
device has an independent SQLite file. Payloads use per-device derived AES-GCM
keys and authenticated record metadata; master key is injected, never stored in
the database, URL or logs. Metadata remains readable and production still needs
encrypted storage/backups and an audited key-provisioning/restore procedure.

SQLite transactions commit all records and the batch receipt together. Exact
retries return the original acknowledgment; changed bodies conflict. Versions
increase, identity/time/kind/source remain fixed, identical revisions deduplicate,
conflicting or stale revisions reject atomically. Tombstones remove older payloads
and cannot be undone by reimport. Queries freeze a high-water mark; later updates
do not alter earlier pages, while deleted/expired records cannot reappear through
an old cursor.

Clinical records expire at original event time plus365 days. CHAT expires at its
original request event time plus365 days. Revisions and retries never extend the
clock. Out-of-window/future backfill is rejected. Reads/writes filter expired data;
explicit bounded purge removes it. Periodic idle-store purge, backup expiry and
physical-storage erasure are production deployment gates, not claims of this
local stage. Expired opaque retry receipts are also purged.

Bound device count, row count, encrypted-byte quota and response size. Check free
disk headroom and SQLite page budget; fail closed without partial acknowledgment
on quota, storage, integrity or authentication errors. No deletion of unrelated
files, no whole-directory restore and no automatic replay of model/therapy work.
Existing15-minute volatile job input/result policy is unchanged. Logical quotas
count ciphertext bodies/receipts; a separate SQLite page cap bounds physical
file/index overhead. These do not replace OS quotas or measured capacity planning.

## Acceptance

Synthetic tests cover typed contracts, encryption/tamper/wrong-key rejection,
device/owner isolation, retry/conflict/rollback, concurrent batches, tombstones,
retention boundaries, restart, stable pages, quotas/storage refusal, signed-body
proof/replay, revoked access and coexistence with existing AI job routes.
Full backend tests and exact-source CI gate publication. Real device transfer,
contextual model responses, security/load/restore checks and deployment remain
explicitly unverified. Local history storage has no clinical or inference writer.
