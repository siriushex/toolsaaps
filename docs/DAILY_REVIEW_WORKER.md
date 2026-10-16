# Contained Daily Review Worker

Dedicated JSON protocol and worker. The owned internal dispatcher/channel are
now composed in DAILY_DISPATCH.md; no daily service, schedule or publication
endpoint is activated by this source.

## Separate Protocol And Binding

`daily_worker.daily_session_policy` pins the existing daily SYSTEM_PROMPT and
64 KiB input bound to an operator-selected exact model. It uses the existing
TextSessionPolicy/SystemdTextProcess containment contract without changing native
model or reasoning settings. It does not adapt daily work to a ChatRequest or
change CHAT_POLICY, the text supervisor's chat-result contract or public routes.
The existing chat supervisor cannot be reused as a raw daily JSON channel.

NativeDailyReviewSession reuses the pinned native correlation, notification,
tool-rejection, ephemeral/thread and stream checks. One frozen canonical envelope
is sent as text. Only a strict, bounded daily-review-v1 JSON reply is returned
as bytes, not wrapped/truncated through ChatResult. Malformed, duplicate/unknown
fields, wrong schemas, invalid Unicode and replies over16KiB fail closed. The
session-level check is syntax only; trusted source/reference evaluation follows.

`daily_request_digest` is distinct from the CHAT/photo request digest. It binds
the original logical-day queue UUID, model, absolute deadline, route revision,
frozen envelope/source identity, prompt and all session/output/deadline limits.
The envelope itself binds schema and complete facts/source/input digests. Tools
and actions are fixed false. No raw content is copied to the metadata ledger.

## Worker Lifecycle

DailyReviewWorker accepts an immutable QUEUED DAILY_REVIEW Job, or an exact fresh
RUNNING idle-admission Job with started_ms equal to created_ms, plus handoff/device,
HistoryStore, trusted clock and exactly one contained process or injected runner.
Construction validates binding without reading storage or starting a process.
The job must match owner/device/request/digest/route and have at most a15-minute
lifetime within the frozen report's expiry. An optional enrolled key binding
must match the device. A handoff object's old source proof is not trusted.

Run only through JobExecutor, whose queued path or owned execute_daily path
atomically claims the shared ledger slot before invoking the worker. It is not an admission/security
boundary against arbitrary trusted Python code calling it directly.

1. Read the authenticated queue entry. It must have HANDOFF_RECORDED, exact frozen
   work and a handoff time no later than job creation. The execution deadline
   cannot exceed its civil-day dispatch window.
2. Independently prepare the current latest archive/source again and require
   exact work equality. Late uploads, corrected/deleted observations or a new
   report revision prevent any model start.
3. Check time again, attempt exactly one native turn with the pinned daily policy.
   Mark the process attempt before start so failed/cancelled start still needs
   independent stop. No retry or model fallback.
4. Validate the bounded closed result against the actual current archive/source
   with validate_checked_daily_glucose_review. Changes during inference, wrong
   references, omitted required low findings or hidden missing inputs discard it.
   Recheck the deadline after that potentially slow read too.
5. Join any owned reader and obtain the injected process's independent stop
   receipt. JobExecutor returns a result only after positive exact True cleanup
   and successful ledger settlement. None/False/truthy values, exceptions or stop
   timeout leave the durable slot held and expose no result.

Clock readings must not regress within a worker run. The shared ledger separately
refuses settlement earlier than its recorded start. Such a clock error can escape
as a fixed clock_regression while retaining the running record; do not substitute
an invented timestamp, free the slot or automatically resubmit the day.

SQLite work runs in bounded background threads under the existing HistoryStore
locks and budgets. Cancellation of the awaiting coroutine does not kill a reader
thread: stop waits for it. If the cleanup budget expires, the slot stays held.
Actual Linux cgroup/profile/IO stop verification remains the admitted process's
responsibility, not the return of a model answer or the synthetic tests.

## Result Meaning And Remaining Admission

Returned SourceCheckedDailyReview has a point-in-time source check. Its inner
view remains REFERENCES_ONLY, UNREVIEWED_AI_INTERPRETATION and NOT_ADMITTED.
Free-text schema checks do not establish medical correctness or prove causality.
This source is still GLUCOSE_ONLY; missing delivery/meal/activity/context adapters
stay explicit. Nothing writes doses, targets, ISF/CR, GI or therapy commands.

The worker does not reserve/claim the ledger, consume a queue handoff or persist
the final result itself. DailyDispatcher and the dedicated daily channel now
compose those steps explicitly; there is no automatic polling loop or public
route. Profile/owner/provider admission and actual recurring activation remain
operational gates rather than permissions granted by this library.
Do not reserve background owner capacity merely while scanning/waiting. Shared
mutual exclusion is tested here, not interactive-first dispatch. Crash recovery
must reconcile ledger/queue/profile originals and never replay uncertain work.

Before activation: exact runtime/model/egress and cgroup verification, real host
stop/cancel acceptance, encrypted result storage, review/publication admission,
protected admin/device projections, opt-in civil-day schedule and capacity tests.
No source publication alone authorizes any of those operations. No live database
migration or server/phone/provider operation occurred in this stage.

## Verification

Run the pinned backend requirements with pytest on test_ai_daily_worker.py plus
test_ai_daily_queue.py, test_ai_checked_daily_review.py, test_ai_native_text.py and
test_ai_job_executor.py. Full backend tests protect the unchanged public policies.
Synthetic transports/runners prove protocol and orchestration only, not live
model quality, OS containment, medical accuracy or target-host capacity.
