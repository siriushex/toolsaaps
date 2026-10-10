# Android Server AI Jobs: R1b Client

## Scope

The minimal `ServerAiJobClient` implements the signed CHAT transport described
in [the server contract](2026-09-13-signed-ai-jobs.md). It reuses the existing
`ServerAiConnectionManager`, device key and encrypted connection record.
There is no additional key store, caller-selected endpoint, provider fallback,
background worker or implicit model request.

This is a client library stage. Existing chat, reports, alert analysis and photo
flows are not routed through it yet. Online Codex deployment, real enrollment,
R2 caller routing and photo nutrition remain separate gates. Constructing or
loading the client does not demonstrate that live inference is available.

## Client Lifecycle

- `prepareChat(text, deadlineMs)` validates bounded text and creates an immutable
  submission with one UUID, absolute server-time deadline and exact UTF-8 body.
- `submit(submission)` requires HTTP 202 and validates the matching request ID
  and deadline in the receipt. It never transparently repeats a model request.
- The concrete HTTP transport uses a one-shot POST body and suppresses the
  native `503 Retry-After: 0` follow-up for every signed method. Disabling
  connection retries alone does not prevent this OkHttp status retry. The
  original 503 status/body still reaches the bounded typed-error path.
- A caller recovering an ambiguous send reuses the same submission; changed
  bytes, UUID or deadline represent a different intent, not a network retry.
- `capabilities()` reads readiness and the pinned transport revision and limits.
- `status(jobId)` and `cancel(jobId)` accept canonical UUIDs only. Both use
  empty-body proofs bound to the existing session and device identity.
- Refresh is serialized with connection operations. An expired token returned
  by idempotent refresh recovery cannot sign or send a job.
- Coroutine cancellation propagates to HTTP. The client has no polling loop;
  later caller/UI integration must bound its own status reads by lifecycle and
  deadline rather than introduce persistent polling.

Prepared submissions and results are in memory only in this stage. Process-loss
recovery in a future UI must retain only appropriate non-content job metadata
and never automatically invent a new request when the outcome is unknown.
Do not persist clinical payloads or credentials in UI state or diagnostics.

## Validation and Errors

Activation/status responses retain the existing 16 KiB limit. Job responses
are separately bounded to 64 KiB, including streamed bodies. Request/result
limits count Unicode code points and UTF-8 bytes, not UTF-16 code units alone.
Before encoding, a constant-time UTF-16 length gate and bounded code-point check
reject oversized local text without allocating another copy proportional to it.
Malformed UTF-8, duplicate fields, unknown fields/states, non-finite or invalid
numbers and inconsistent receipt metadata are rejected with generic errors.

HTTP errors expose typed reasons, including unauthorized, conflict, rate-limited
and service unavailable, without server body text or credentials. Diagnostic
string representations redact request and result content.

Terminal success and result availability are distinct. After cache expiry a
successful job may legitimately have no downloadable result; this does not
authorize re-execution. UNKNOWN is explicit and must not be presented as success.
Server wall-clock timestamps are not a monotonic duration source: restart
abandonment may finish UNKNOWN before the recorded creation time, and cache
publication after a clock correction may have expiry earlier than the previous
finish timestamp. The client preserves these server-authoritative cases while
retaining positive timestamps, known states, required fields and result bounds.

## Verification and USB Gate

The first candidate passed 17 client tests and nine existing connection tests.
Two independently identified wall-clock cases reproduced RED and increased the
passing client suite to 19. Quality review then found the native 503 replay and
pre-limit allocation. Four regression tests reproduced those defects; after the
fix, 20 client tests, nine connection tests and seven real-OkHttp tests pass.
The local HTTP fixture preserves production client policies while remapping
the fixed HTTPS host to loopback only through test reflection. It covers exact
wire request count/body/path, streamed limits, actual-call cancellation and
redirect refusal. It does not prove device TLS, attestation or live inference.
Full Android unit/lint/APK checks and independent spec/quality review are
tracked in `AI_NOTES.md`; focused tests alone do not close these gates.
The final full run at `e5432c52` completed successfully: 4392 tests with zero
failures/errors and three conditional phone-copy skips; lint and all three
APK builds passed. Independent spec review and the scoped quality recheck are
accepted. This accepts the local client code, not a production rollout.

On 2026-09-13 the phone was available over USB, and the existing production
Overview and foreground service were inspected without changing therapy. The
system rejected the isolated `.uitest` package with
`INSTALL_FAILED_USER_RESTRICTED`. No device instrumentation or production update
can be claimed from that attempt. The isolated package must retain its lack of
network, AAPS and production-service permissions; it is not an enrolled live-AI
client and must never be allowlisted in production merely to pass a test.

Later in the same run, the connected USB target changed to a different phone
with AAPS but no Copilot package. No installation was attempted there; target
confirmation is required. The earlier baseline and denial belong only to the
first phone, not to this replacement device.

The original Copilot phone was subsequently reconnected after user confirmation.
The candidate debug APK was installed over the existing package with `adb install
-r`; the read-back APK hash matched the candidate, the data directory and signer
were preserved, and `MainActivity` cold-started successfully. This device had no
local glucose data at the time, so the launch check does not establish forecast,
calibration, AAPS bridge, or live server-AI behavior.

The public unauthenticated status probe returned HTTP 503 with valid TLS. No
real code activation, medical upload or live Codex request was performed.
