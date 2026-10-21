# Local V3 Document Store

## Boundary

This store provides persistence for the stock AAPS 4.0 RC1 protocol inside
Copilot. The entries HTTP caller is described in LOCAL_NS_V3_ENTRIES.md. It does
not import clinical evidence, send treatments or announce storage notifications.
Other collections remain501. Collection membership alone is not a declaration
of API permissions or clinical support.

## Storage Contract

Room migration 35 to 36 is additive: a document table and a singleton revision
clock. Existing glucose, therapy, diary, settings and command rows are untouched.
No historical v3 revisions are invented from their clinical timestamps. The
store shares the existing private database, but has no clinical consumers.

Documents have an explicit collection, stable identifier, source identity,
optional event time, validated JSON body, created/modified revision, read-only
flag and deletion flag. Each protocol adapter must resolve stable source
identity and validate collection-specific schemas before calling the store.
The store cannot deduplicate two unrelated identifiers by guessing that similar
food or insulin records describe the same event.

JSON uses the existing bounded strict parser: valid UTF-8, object root, no
duplicate keys, maximum depth 16. Sorted object keys make formatting-only retries
idempotent; numeric forms, array ordering and null/absent fields are not coerced.
Server-owned metadata must be supplied separately, not forged in the body.
IDs and source keys are bounded; errors never include supplied JSON or secrets.

Creates with matching identity and content return the existing revision without
a second write. Conflicting creates are refused. Updates require the expected
revision; byte-equivalent normalized retries are no-ops. Source identity cannot
change. Deleted identities cannot be recreated, and read-only documents cannot
be modified or deleted. Deletion retains the last body/event time for subsequent
invalidation; repeated deletion is idempotent, not another revision.

Document, revision and capacity counters commit in one Room transaction. The
global clock allocates `max(wall time, previous revision + 1)` within an explicit
timestamp bound; restart and clock rollback never reuse a revision. A failed
write consumes neither a cursor nor capacity. Store receipts are local commit
evidence only, never AAPS application or insulin-delivery evidence.

## Paging And Retention

History is a convergent latest-document feed, not an immutable audit of every
intermediate version. It orders current documents, including tombstones, by
unique modification revision. A page advances only through returned rows, not
to the newest collection revision when truncated. Mutations occurring after a
page receive newer revisions and are visible in a subsequent catch-up. All
queries and page metadata share a read transaction.

First-load chronological pages exclude deleted records and preserve event times.
The final equal-time group is returned whole, within a hard group/page byte cap;
overflow is an explicit failure, never a partial group with an advancing cursor.
This is a storage primitive, not complete HTTP filter semantics. An eventual
first-load adapter must retain a modification watermark from before initial
loading to catch edits, late old records and deletions during that load.

Initial resource bounds: 100,000 identities, 64 MiB normalized body bytes,
64 KiB per body, 1,000 requested rows, 1,000 rows per boundary tie group and
1 MiB returned body bytes. A chronological page can contain fewer than 2,000
rows to finish its boundary group. Refusal never evicts another record. These
are safety bounds, not a measured production capacity promise.

No automatic pruning, purge or cursor reset is available in this stage. Deleted
identities retain capacity and revision metadata so an old create cannot revive
them. Existing clinical retention does not touch these tables. Before activation,
define operator-visible capacity handling and a separately verified retention /
full-resync protocol; never discard tombstones behind an old client cursor.

## Remaining Admission

Persistence-stage verification: 24 new native-SQLite/Room cases passed, covering migration,
restart, rollback, cancellation, concurrent writers, deduplication, CAS, deleted
and read-only identities, time ties, cursor boundaries, parser and capacity
limits. Focused store/migration run: 73 passed, one optional phone-copy skip.
Full Android run: 6,367 passed, six optional skips, no failures/errors; lint has
no errors and assembleDebug passed. Authentication cannot imply clinical READY.
These are synthetic local results, not device tests.

The entries adapter now implements typed validation, HTTP filters/envelopes/weak
ETags and source deduplication; see LOCAL_NS_V3_ENTRIES.md. Other collections,
clinical projection, native paired Client Control,
Android TLS trust and whole-app/device acceptance remain separate stages.
Storage subscriptions now read committed revisions; see LOCAL_NS_V3_STORAGE.md.
Do not enable an unfinished route on the strength of local persistence tests.
Rollback to an older APK after schema 36 is not supported without an explicit
compatible migration; never use destructive Room fallback to bypass this.
