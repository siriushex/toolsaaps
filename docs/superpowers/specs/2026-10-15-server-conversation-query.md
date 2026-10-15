# Signed Server Conversation Read

## Scope

Read full server-recorded chat after the short job-result cache expires. This
extends the explicitly injected owner-scoped HistoryService; default apps mount
no route. No model invocation, archive write, schema migration, Android wiring,
deployment or therapy action is added by querying.

## Contract

POST /api/ai/v1/history/conversations uses the existing enrolled device identity,
exact-body/path signature, one-use request proof and post-proof access recheck.
Owner and device come from authentication, never request fields. Subscription
expiry/revocation still refuse access. No bearer-only or alternate-owner fallback.

The closed JSON object has exactly fromMs, untilMs, cursor and limit. cursor is
null on the first page; limit is1..20. The time interval is inclusive/exclusive
and covers at most365 days plus the existing one-millisecond end convention.
First-page dates cannot precede the retained window; continuation permits only
the original cursor's bounded drift at the retention edge. Expired rows are
always hidden using the current server time.

Request limit is4KiB. Response limit is128KiB, separately scoped to this route;
existing job, activation and observational-history limits are unchanged. The
larger response accommodates one complete maximally escaped valid chat exchange.
Byte-limited pages never truncate stored questions or answers. Storage or an
unrepresentable record fails explicitly, not as a partial successful page.

Response fields: revision=server-chat-archive-v1, origin=SERVER_ARCHIVE,
generatedAtMs, snapshotScope=MEMBERSHIP_ONLY, records, hasMore and nextCursor.
Each record includes jobId/requestId, original acceptance/deadline, start/finish
and update times, state, full question/answer, model and prompt revision. Internal
request/instruction digests, session identity and keys are not exposed. Missing
completion remains OUTCOME_NOT_RECORDED with no answer. Server provenance is
not evidence of clinical correctness or authority to change therapy.

## Pagination Semantics

Pages follow archive append order, not an inferred medical timeline. Original
timestamps are explicit. First read captures the highest SQLite rowid and its
job identity under the existing private store lock/read transaction. Later
inserts, even with the same/backdated acceptance time, cannot join that membership.
The encrypted continuation token binds owner/device, route revision, exact time
window, upper-row/job identity, last returned row and original issuance time.
Changing the page size is allowed; changing identity/window is not.

Tokens expire15 minutes after original issuance, without renewal on next-page
reads. Tokens are not credentials; every request still needs fresh authentication
and proof. If the original anchor disappears or its rowid is reused after purge,
return409 chat_cursor_stale instead of mixing datasets. Expired tokens return409
chat_cursor_expired; malformed/tampered tokens return400 invalid_chat_cursor.
Clients restart from a null cursor for either409. Cursor replay with a new valid
proof can reread a page; proof replay itself is refused.

Only membership is frozen. A request may finish between pages; its outcome is
read at that page's generatedAtMs, with updatedAtMs per record. Clients needing
fresh outcomes must reread those records/window. This is not a point-in-time
outcome snapshot, incremental synchronization feed or evidence that a previously
unknown outcome succeeded. Retention can remove records during a scan.

SQL work and wall time are bounded. No public IDs select a filesystem path. The
current private/AES-GCM manifest and payload checks apply, including an authenticated
anchor. No no-write/read-only claim implies protection against a privileged actor
replacing the whole database; backup restore remains an independent audited gate.

## Dependency And Verification

The test dependency is updated from pytest8.4.2 to9.0.3, the fixed version listed
in GHSA-6w46-j5rx-g56g for vulnerable tmpdir handling. This is not a claim that
every dependency is vulnerability-free or that the default-branch alert is closed.

Synthetic tests cover closed inputs, complete text after cache expiry, no schema
mutation, same-clock insertion/restart pagination, current outcomes, worst-case
escaped response size, cursor identity/filter/tamper/expiry checks, retention and
rowid reuse, corruption, signed HTTP scope, refresh/revocation/proof replay and
safe failure responses. Run the full backend suite on the pinned pytest version.
Real host, protected key, encrypted volume/backups, operational purge/restore/load,
constrained runtime and actual Android end-to-end checks remain deployment gates.
