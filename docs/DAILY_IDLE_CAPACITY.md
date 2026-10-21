# Daily Idle Capacity

## Internal Metadata Method

`JobLedger.reserve_and_claim_daily(...)` reserves and claims a new DAILY_REVIEW
job in one existing `BEGIN IMMEDIATE` transaction. It shares request validation
and immutable duplicate binding with `reserve_once`; normal reservations retain
their owner/waiting limits and queued/idempotent semantics. No table, enum,
public route, model policy or payload storage is added.

The returned frozen `DailyAdmission` distinguishes:

- `ADMITTED`: one new RUNNING job and one fresh Claim; creation/start clocks match.
- `DEFERRED`: another QUEUED/RUNNING/CANCEL_REQUESTED job exists. No new daily
  row, owner reservation, queue slot or claim is created.
- `RECORDED`: this owner/kind/request already exists with the exact immutable
  metadata. Return its audit Job, never its old claim token, in any state.

Only DAILY_REVIEW is selectable. Owner/request UUID, digest, route, deadline,
optional session/key binding and existing maximum lifetime rules are retained.
Conflicting metadata does not replace a recorded request, even after completion.
Regressed duplicate clocks cannot precede recorded creation/start/finish. Expiry
only clears stale QUEUED jobs; a past deadline never silently releases RUNNING
or CANCEL_REQUESTED capacity.

## Priority Boundary

The transaction is the linearization boundary. All prior active work blocks a new
idle-only daily admission. Independently, the normal `claim` method now prevents
a legacy QUEUED DAILY_REVIEW from bypassing any queued non-daily job. Expired
waiters are removed from that competition as before; two daily waiters do not
block each other. Non-daily claim behavior is unchanged.

This is **priority for already queued work**, not preemption or a zero-latency
promise. Interactive work arriving after a daily RUNNING claim may wait for its
positive stop; same-owner active limits still apply. The primitive does not
cancel a running analysis or free an uncertain slot. Continuous interactive work
may defer daily work until its dispatch window expires. No automatic backfill or
fairness/latency guarantee is introduced.

## Failure and Authorization

No fresh claim is exposed before commit. A failed transaction rolls back its new
row. If commit succeeds but acknowledgement is lost, RUNNING remains occupied;
restart/retry returns RECORDED without a token. The actual uncertain original
must be reconciled, not retried or erased. Wrong token cannot settle it. Only
existing positive-stop settlement releases executing capacity, cancellation wins
as before, and successful completion after deadline becomes expired.

This is metadata capacity, **not provider/process/publication admission**. A
caller-created DailyAdmission/Claim object is not an execution receipt. No OS
process, login profile, source/history read, model request, queue handoff, result
or clinical action occurs here. No claim/payload is logged by the new method.

Do not pass an already RUNNING job to `JobExecutor.execute` and expect execution;
that method owns fresh claims for QUEUED jobs only. `execute_daily` now obtains
and consumes fresh idle admission inside its own lifecycle; DailyDispatcher
coordinates checked handoff, worker and stopped-result archive. See
DAILY_DISPATCH.md. No API accepts caller claims; tokens/answers are not OS stop
receipts and an existing attempt never obtains its old claim again.

No public service calls this primitive by default. Priority-aware execution and
the dedicated daily supervisor now have an explicit internal composition; opt-in
scheduling, actual provider activation, quality/publication and device/admin
reads remain gated. No live host/profile/schema/data/model/phone/therapy change
accompanies this source integration.

## Checks

`cd backend && uv run --no-project --with-requirements requirements.txt python -m
pytest -q tests/test_ai_daily_admission.py tests/test_ai_job_ledger.py
tests/test_ai_job_executor.py tests/test_ai_daily_worker.py tests/test_ai_daily_results.py`

Synthetic checks cover all six active kinds, queued/running/cancel states,
terminal/legacy duplicates, immutable/scoped binding, malformed input, expiry,
clock rollback, multiple connections and same/different identities, ordered
interactive/daily transaction races, before/after commit failure and old-path
priority bypass. They prove local metadata behavior, not provider/OS/clinical
acceptance or actual host/phone load.
