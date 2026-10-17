# Owned Daily Execution

This source stage joins the existing source-checked queue, idle metadata
admission, contained worker and encrypted result archive. It adds a dedicated
daily Unix channel and an explicit optional host binding. It does not register a
public daily route, schedule05:00 or opt a device into recurring analysis.

## Execution Ownership

JobExecutor.execute_daily owns reserve_and_claim_daily and consumes only its
fresh ADMITTED claim. Its caller supplies metadata and a worker factory, never
an old claim/token or caller-supplied success flag. DEFERRED creates no daily
metadata row/owner slot; RECORDED returns immutable metadata without execution.
The existing execution/timeout/cancellation/independent-stop lifecycle is shared
with normal jobs. Failed/unknown cleanup retains the exact global claim.

The factory is invoked after the claim. Cancellation before invoking it can
withdraw the proven never-started metadata claim without constructing anything.
A factory that raises might already have side effects: the claim remains held
for explicit reconciliation. Factories must construct without starting models.
The worker accepts the exact RUNNING/started-at-created record from idle
admission, rather than fabricating a QUEUED copy. Legacy queued execution stays.

## Queue And Crash Windows

DailyDispatcher.dispatch takes trusted owner/device/queue identity. It checks
the retained authenticated queue and any existing request, then performs the
one-shot handoff and fresh source/work verification. Preparation owns no model
slot. The atomic ledger boundary gives already-queued interactive work priority;
this is not preemption or a zero-latency guarantee for later arrivals.

A recorded handoff means no provider/slot admission. If no ledger request was
ever created, a deferred or handoff-only crashed attempt may reconstruct its
exact retained work and current source proof, then obtain a first fresh claim.
It cannot reissue the handoff, replace frozen work or reacquire an old claim.
Any existing request, including FAILED/RUNNING/UNKNOWN, is read and never run.
Concurrent deadline conflicts resolve by reading the original immutable request,
not changing its binding or making another reservation.

Before and after inference the existing DailyReviewWorker rechecks actual
retained source. The result sink requires positive process cleanup, successful
ledger settlement and exact job/work/digest/source binding. Cancellation drains
owned history readers and writes. A lost archive commit/acknowledgement never
permits another inference; result-unavailable remains separate from success.
Recorded archive reads are historical, not a fresh-source/publication certificate.

## Dedicated Unix Contract

SupervisorDailyReviewRunner sends only the frozen daily work and absolute
deadline over a peer-checked Unix socket. Policy digest names the daily task and
fixed model/prompt/limits. Request/response binding digest covers complete work
and deadline. No native RPC, executable, environment, tools, instructions or
alternate policy can be supplied. NativeDailyReviewSession validates the closed
16KiB daily JSON grammar; source/reference validation stays in the API worker.
JSON grammar and source matching do not establish medical accuracy.

The trusted DailySupervisor uses the existing bounded one-turn protocol, fixed
daily policy, provider admission and exact stop contract. It returns no result
until positive cleanup. Wrong peers, malformed/oversized frames, foreign binding,
expired work and failed cleanup fail closed without raw-content diagnostics.
Normal CHAT payloads cannot use this channel, nor can daily JSON use CHAT.

TextSupervisor can share one SupervisorCapacity with DailySupervisor. The busy
and poisoned states span both listeners: an active model excludes the other
channel, and unknown cleanup prevents further starts across both. This is local
host arbitration in addition to durable ledger and independent profile/cgroup
ownership, not a replacement for either.

## Explicit Host Binding

The default text host is unchanged in capability: chat only. An operator may
explicitly supply --daily-socket under the same protected parent, distinct from
the chat socket. Both listeners share fixed API peer/model/profile and capacity;
daily prompt is compiled, never read from request configuration. Readiness is
announced only after both channels start. Shutdown closes both even if one fails;
pre-existing/replaced paths are preserved. Source does not install this flag.

Before any activation, retain exact source/dependencies/descriptors and rollback
plan, verify both sockets/PID peers and unchanged runtime restrictions. Private
credentials are never copied or reset. Do not restart over uncertain claims.

Still required: enrolled-device authorization, persisted opt-in/time-zone05:00
policy, facts production, protected result read API, quality/publication labeling,
default Android presentation and separately reviewed real host/model/device
acceptance. No new timer, live source deployment, medical upload, inference,
phone/APK or therapy change occurs in this stage. Retention remains365days;
results remain glucose-only/unreviewed/NOT_ADMITTED, not dosing instructions.
