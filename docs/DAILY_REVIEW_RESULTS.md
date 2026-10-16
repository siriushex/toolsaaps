# Daily Review Results

## Internal Contract

`daily_results.execute_and_archive(executor, worker)` composes the exact existing
`JobExecutor` and `DailyReviewWorker`. It never accepts an external execution
outcome or supplied success/stop flag. The executor must finish its independent
contained-process cleanup and settle the shared metadata job as `SUCCEEDED`
before the private result sink is called. DailyDispatcher reuses that private
archive composition after its executor-owned idle admission; its initial worker
job can be the exact fresh RUNNING record rather than a fabricated QUEUED copy.
The sink rereads the actual ledger,
checks the request/model/deadline binding, consumed encrypted queue and exact
original archived work. Arbitrary Python injection is not a containment boundary;
the actual provider/process must still be independently admitted.

The returned `DailyArchiveExecution` distinguishes:

- `STORED`: an authenticated immutable encrypted result exists.
- `RESULT_UNAVAILABLE`: the job succeeded, but no retained result is available.
- `NO_SUCCESS`: no stopped-successful result is available for archival.

Failed/cancelled/expired jobs and unknown stop produce no result. Unconfirmed stop
continues to hold the original global capacity. Pending cancellation during the
owned write is shielded, the bounded storage thread is drained, then cancellation
propagates. No ephemeral model reply is exposed as a substitute for a failed save.

## Durability and Crash Windows

Only the private successful-result write enables additive history schema 5 and
`daily_review_results`. Each owner/device has one encrypted row per job and queue
UUID. Purpose `daily-review-result-v1` authenticates sequence, job/queue/report
keys, request digest and fixed storage/expiry metadata, separate from the facts,
queue and chat purposes. Original work, point-in-time source proof, grounded
answer, exact model/route and execution clocks are inside ciphertext. The shared
metadata ledger never contains these medical payloads or model errors.

Exact sink retries retain the original row/clocks; differing content conflicts.
Repeated execution calls only read an already-settled job/archive, never infer
again. A committed row survives lost acknowledgement and process restart.

Ledger and device store are separate databases, **not an atomic transaction**.
Crash after ledger success and before result commit can lose the answer. The
consumed queue and successful job remain consumed; a later call reports
`RESULT_UNAVAILABLE`. It does not synthesize an answer, free uncertain capacity,
replay inference or restore an older database. Crash before ledger settlement
retains existing unresolved-state behavior. This is not exactly-once inference
or forensic protection against privileged deletion/rollback of whole files.

## Reading and Freshness

`get_result(store, owner, device, job_id, now_ms=...)` authenticates the result and
independently replays current retained source against its original facts in the
same protected read snapshot. The outer view is `SOURCE_MATCHED`,
`SOURCE_CHANGED`, `SOURCE_UNAVAILABLE` or `SUPERSEDED`. A later facts revision
supersedes the old answer; missing facts or retention loss are unavailable.

`sourceAtReply` is immutable historical evidence, not current freshness. Even a
change during process stop is retained as history and detected on selected read.
The immutable record's `currentSourceCheck` always stays `NOT_CHECKED`.
`list_results` returns at most 20 summaries with frozen retained sequence
membership, never a freshness certificate. Opening a selected result performs the
actual bounded source replay. Audit reads remain possible after a prompt-pin
change under the same readable schema; new dispatch/saves still require current
pins. Future incompatible schema changes need explicit compatible readers.

All results remain `REFERENCES_ONLY`, `UNREVIEWED_AI_INTERPRETATION` and
`NOT_ADMITTED`, even with matched source and successful process stop. Syntax,
references and retained-source checks do not establish clinical correctness or
continuous data completeness. Glucose-only analysis does not diagnose illness or
infer meal, insulin, activity or delivery facts that were not analyzed.

## Bounds and Operations

- 128 KiB per encrypted payload, 512 result rows/device, 20 summaries/page.
- Existing shared row/byte/disk quotas and private-file/manifest/key rules apply.
- Fixed expiry is the original report's civil-day end plus 365 days; retry does
  not extend it. Shared purge includes result rows.
- Existing bounded lock and SQL snapshot budgets apply; the SQL wall/instruction
  budget is not an end-to-end provider/whole-API latency guarantee.
- Ordinary reads never create a device database or migrate schema. A failed save
  may leave committed additive schema 5, but not a partial result row.
- Older source accepting only schemas 1-4 cannot open 5. No downgrade SQL, table
  deletion or version reset is an operational rollback. Preserve originals and
  reconcile ledger/queue before any separately authorized restore.

This is source-only trusted composition. The dedicated daily channel and owned
internal dispatcher are described in DAILY_DISPATCH.md. No public/admin/device
route, provider service, timer or scheduler is activated. Advisory
quality/publication admission remains separate. No live host,
profile, model request, real medical payload, APK or therapy operation occurs.

## Verification

`cd backend && uv run --no-project --with-requirements requirements.txt python -m
pytest -q tests/test_ai_daily_results.py` covers restart, exact retry/conflict,
unknown stop, failure/cancel/deadline, both commit crash windows, source changes,
supersession/retention, read-only reads, identity/encryption/tampering, shared
quotas, bounded pages, cancellation draining and concurrent at-most-once claim.
These synthetic tests do not prove live model/OS containment or medical quality.
