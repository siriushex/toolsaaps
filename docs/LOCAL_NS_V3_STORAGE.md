# Local V3 Storage Subscription

The existing loopback TLS listener now implements the stock RC1 Socket.IO
`/storage` namespace over its advertised Engine.IO 4 polling transport. This
subscription is wire access, not pairing, clinical ingestion or READY.

## Authentication And Isolation

Connect with `40/storage,`, then emit `subscribe` with a packet acknowledgement
identifier. Its object must contain exactly `accessToken` and `collections`.
The credential is the configured raw local access token, not the HTTP bearer or
legacy SHA-1 secret. It is never logged. The collection list is nonempty, unique
and restricted to the stock names: devicestatus, entries, profile, treatments,
foods and settings. Unsupported HTTP collections still fail501; accepting their
notification names does not grant whole-app permissions.

Success returns the native `success`/`collections` acknowledgement. Failed
reauthorization clears the namespace subscription and its queued events, while
legacy root access remains separate. Root reauthorization cannot discard storage
events. Root permission cannot drain unauthorized storage events. Namespace
disconnect removes only storage access; Engine.IO close or service restart
revokes every namespace and session.

## Committed Changes

Subscription captures current persistent collection heads. Initial/reconnect
catch-up belongs to the stock client's HTTP loop, which its subscribe callback
starts before treating initialization as complete. Live notifications follow
those heads. The first poll rechecks history even if invalidation registration
and subscription race a write.

A listener-scoped Room invalidation observer wakes existing long polls after
document changes. It does not cache documents, allocate commands, run a timer or
start a coroutine for each change. The poll reads bounded persistent revision
pages, including changes committed directly by the target journal. It emits only
committed state; transaction rollback cannot create an event. Latest-state
storage may coalesce successive edits to one identity, as the HTTP history does.
Each subscribed collection retains its own monotonic cursor. A later invalidation
during capture is read on the next iteration rather than silently consumed.

Native `create`/`update` frames contain `{colName,doc}` with identifier,
srvCreated/srvModified, provenance and validity metadata. `delete` contains only
`{colName,identifier}`: a deleted settings envelope must not be dispatched as an
executable update. The stock food notification name is `foods`.

## Limits And Recovery

Existing session count, one-poll/one-POST, heartbeat, packet-count and byte limits
remain. Active authenticated storage traffic refreshes session idle expiry,
without allowing unauthenticated traffic to extend its absolute allowance.
History read failure, page overflow or queued packet/byte overflow closes the
session before any partial response. Persistent history is not removed; a new
connection invokes native HTTP catch-up. Missing revisions are never represented
as an empty successful page. No websocket upgrade or `/alarm` service is claimed.

## Verification And Remaining Gates

Synthetic TLS regressions cover raw-token authentication, namespace isolation,
rejected subscription replacement, direct journal publication, rollback,
long-poll wakeup, revision order, edits/deletion, reconnect, restart, TTL and
backlog bounds. The unchanged RC1 SDK fixture uses stock collection/framing
contracts, verifies a settings update via storage, then receives the journal's
target-only commit through storage and checks it with native DTO/HMAC code.
The polling peer is a test driver, not the actual Android service or master
executor. Device TLS trust and real master application remain separate checks.

Owner PIN enrollment, durable Hello/counter ownership, current authorized roster,
background ACK reconciliation and Target Manager routing are not activated by
this transport. Treatments/profile/food adapters, trustworthy clinical projection
and device CGM/pump/settings migration are also required before a phone update.
No live target, insulin action, phone setting or AAPS source is changed here.
