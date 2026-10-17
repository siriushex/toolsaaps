# Atomic Idle-Only Daily Capacity

Scope: shared metadata ledger source only. No dispatcher, provider, process,
schedule, host, real data, phone or therapy activation.

1. Reproduce missing atomic daily admission in synthetic tests.
2. Share existing request metadata/conflict validation without changing normal
   reserve_once semantics. Add one explicit DAILY_REVIEW-only method that checks
   all active work and creates a RUNNING job/claim in one BEGIN IMMEDIATE commit.
3. Busy means DEFERRED, no new row or owner/waiting capacity. Existing request
   means RECORDED, no claim token reissued in any state. New idle admission means
   ADMITTED and one fresh claim. Expire only stale QUEUED work as before.
   Close the observed legacy claim bypass: DAILY_REVIEW claims also yield to
   pending non-daily jobs inside the same transaction. Normal reservations and
   non-daily claims retain their existing behavior.
4. Cover all job kinds/states, expiry/cancellation, immutable conflicts, malformed
   input, restart, multiple connections, races and both commit uncertainty cases.
5. Run full backend and focused shared-ledger/executor/daily/API regressions,
   scoped lint/format/compile, source/security/docs review. Update governing docs
   and AI_NOTES, then publish explicit reviewed paths and inspect exact-head CI.

The linearization point is the committed metadata transaction. Already queued
interactive work wins. Interactive arrival after daily admission cannot preempt
it; future dispatcher/supervisor must separately own process start, source/queue
handoff, stop, cancellation and result archival. This method alone is neither
model/provider admission nor a callable-worker receipt. Crash after commit keeps
RUNNING occupied; duplicates do not recover its token or automatically rerun it.
