# Signed Server AI Jobs: R1a Transport Contract

## Status and boundary

Local transport implementation and its independent spec/quality reviews are
complete at code commit `2e3525a2149332146915f9d49d2794b0bda5f84b`.
The backend suite passed 313 tests; the final independent race recheck passed
six tests. This does not close the online executor or Android integration gates.

R1a is an optional, local/injected transport implementation. The default
`create_bound_ai_app(activation)` factory still exposes activation and session
status only, and reports `inference_enabled=false`. Job routes exist only when a
trusted `AiJobService` and `ContainedWorker` factory are injected.

This stage does not include a live Codex call, a production launcher, OS/cgroup
containment, systemd deployment, production attestation roots, device rollout or
staging validation. A `CodexRunner` return is not a containment stop receipt and
is not adapted as one. These are later release gates.

## HTTP contract

All responses carry `Cache-Control: no-store`. Paths are canonical, have no
query string and do not redirect.

| Method and path | Success | Request body |
|---|---:|---|
| `GET /api/ai/v1/capabilities` | `200` | empty |
| `POST /api/ai/v1/jobs` | `202` | strict CHAT JSON |
| `GET /api/ai/v1/jobs/{job_id}` | `200` | empty |
| `DELETE /api/ai/v1/jobs/{job_id}` | `200` | empty |

`job_id` and `X-Copilot-Request-Id` are lowercase canonical UUID strings.
`X-Copilot-Deadline-Ms` is an absolute decimal Unix timestamp in milliseconds.
A new request requires `now < deadline <= now + 900000`. The same absolute
deadline is reused on every retry. An exact retry may recover its original job
after that deadline; it cannot schedule another inference.

Every route above requires exactly one of each header:

- `Authorization: Bearer <access token>`
- `X-Copilot-Key: <64 lowercase hex P-256 key fingerprint>`
- `X-Copilot-Signature`
- `X-Copilot-Issued-Ms`
- `X-Copilot-Nonce: <canonical UUID>`

Submit additionally requires exactly one `X-Copilot-Request-Id` and
`X-Copilot-Deadline-Ms`. GET and DELETE proofs sign an empty body and use the
access token as the proof credential. Submit uses this exact ASCII proof
credential, without a trailing newline:

```text
<access token>\n<request UUID>\n<absolute deadline decimal>
```

The existing proof version signs canonical JSON with sorted keys, compact
separators and ASCII encoding. It contains `v=1`, audience
`https://diai.centv.ru`, method, exact path, SHA-256 of the exact body, SHA-256
of the proof credential, issued milliseconds and nonce. The P-256 ECDSA
signature uses SHA-256 and canonical base64url without padding. Proofs are
single-use and valid only inside the existing 60-second clock window.

Authentication is checked before a protected body is consumed when its size is
not already rejectable from headers. The exact body proof is checked after the
bounded read, then authentication/revocation/subscription is checked again.
Duplicate authentication/proof headers, content encoding, a body on GET/DELETE,
query strings, mismatched declared/actual size and unsupported content type are
rejected. GET/DELETE consume bounded ASGI frames and reject non-empty bytes even
without Content-Length. When the ASGI server supplies raw_path, it must exactly
match the ASCII path; percent-encoded aliases are rejected before routing.

Stable generic failures are `400 invalid_request`, `401 unauthorized`,
`404 not_found`, `405 method_not_allowed`, `409 request_conflict`,
`413 request_too_large`, `415 json_required|unsupported_encoding`,
`429 owner_busy|queue_full|rate_limited`, `431 headers_too_large` and
`503 service_unavailable`. Cross-owner or wrong-session/key GET/DELETE returns
`404` without disclosing existence.

## CHAT schema and byte limits

R1a supports only `CHAT` with `TEXT` input and output. Other kinds, modalities,
client model selection, argv, prompt-template, owner or rootfs configuration are
not accepted.

The POST content type is exactly `application/json` or
`application/json; charset=utf-8`. The HTTP body is at most `16384` bytes. Its
only valid shape is:

```json
{"text":"..."}
```

Input `text` is non-empty, at most `4096` Unicode code points and at most `8192`
UTF-8 bytes. Duplicate JSON keys, extra keys, non-string/nested values,
NaN/Infinity, malformed UTF-8/JSON and excessive nesting are invalid.

Worker output has exactly the same one-field shape. Result `text` is non-empty,
at most `8192` Unicode code points and at most `16384` UTF-8 bytes. A malformed
or oversized worker output fails the job and is never returned. The complete
status response is bounded to `65536` bytes; R1b must raise its current 16 KiB
response reader before integration.

`GET` returns lifecycle metadata and, only while available, nested
`"result":{"text":"..."}` plus `result_expires_ms`. Volatile result content
expires after `900000` ms and `result_available` becomes false. Expiry removes
content only; it does not erase metadata/idempotency or permit re-execution.

## Trusted policy and digest

The pinned R1a values are:

- kind `CHAT`, schema `chat-text-v1`, prompt `advisory-text-v1`
- model policy label `trusted-server-model-v1`
- route revision `server-codex-chat-r1a.1`
- tools false, actions false
- system prompt: `Return advisory text only. Do not use tools, perform actions, or issue therapy commands. Do not claim that text replaces deterministic safety policy.`

The model label is local route metadata, not evidence that an online model is
configured. A future launcher/model choice must update the trusted revision and
must not become a client field.

R1a accepts only the pinned integer input, result, response and deadline limits
listed above. Alternate limits are rejected at service construction, not merely
advertised while the parsers or ledger enforce something else. A shorter integer
result TTL is supported and actually enforced; it cannot exceed 15 minutes.
Dynamic byte/character/deadline configuration is not part of this transport slice.

The request digest is SHA-256 of UTF-8 canonical JSON (`sort_keys=true`, compact
separators, `ensure_ascii=false`) containing exactly:

```text
allow_actions, allow_tools, body_sha256, deadline_ms, input:{text},
input_modality, output_modality, kind, max_text_chars, max_text_bytes,
max_deadline_ms, result_ttl_ms, max_response_bytes, max_result_bytes,
max_result_chars, model, prompt_revision, request_id, route_revision, schema_revision,
system_prompt_sha256, version:1
```

`body_sha256` binds the exact HTTP bytes, including JSON whitespace. Reusing the
same owner/kind/request UUID with any different body byte, deadline, session/key
binding or trusted policy digest returns `409`; an exact retry returns the same
job receipt even if the first `202` response was lost.

## Ownership and lifecycle

The ledger's `owner_id` is the subscription owner and is the quota/idempotency
scope across all of that owner's sessions. The attested `session_id` and key
fingerprint are separate metadata-only access bindings. Access-token refresh
keeps the same session/key grant, so it does not cancel queued work. A different
session/key for the same owner cannot read/cancel an existing job and cannot
reuse its request UUID.

The SQLite ledger stores identifiers, hashes, revisions, timestamps and states
only. Raw requests, results, credentials, prompts, provider logs and exception
text remain out of it. Volatile memory is bounded to one running plus five
waiting payloads and 100 results. One event-driven drain owns at most one
execution task and one nearest-expiry wakeup, including while a worker is
active; there are no per-request timers or unbounded tasks.

Dispatch rechecks the stable session/key grant, revocation and subscription
without depending on the admission access token. The ledger permits one
executing worker globally, at most five waiting globally and at most one active
job per owner. FIFO is preserved in the volatile queue.

The worker factory is deferred until JobExecutor holds a durable claim. Neither
construction nor run is permitted behind an unknown-stop slot. If construction
raises before returning a worker, its resource outcome is unknown and the claim
remains occupied; no stop receipt is invented for that case.

Cancellation, deadline and shutdown cancel the execution task and require the
worker's independent positive `stop_and_confirm()` receipt before releasing the
slot. An unconfirmed stop stays internally occupied and is reported as
`UNKNOWN`; later work cannot start. After process loss, payload-less queued jobs
become `UNKNOWN`, and running/cancel-pending metadata is reported unavailable
without automatic replay. Graceful close cancels queued work and stops active
work before the service lifecycle ends.

## Publication and failure supervision

The public status remains RUNNING, with no finished timestamp, while a succeeded
executor outcome still awaits publication into the volatile result cache. POST
recovery and GET use the same status path. A client cannot mistake this brief
publication interval for a terminal success with a lost result. After actual TTL
expiry or process loss, terminal success with result_available=false remains valid.
Completed metadata and its result are cached as one immutable tuple. If an older
DB read overlaps publication, the tuple supplies the matching completed status.
If the runtime has already retired/evicted the result, an older non-terminal DB
snapshot is rechecked once before UNKNOWN can be reported. Normal reads do not
add a second query or new polling loop.

Claim completion and bounded stop/settlement are protected from caller
cancellation. Cancel remains effective, but it cannot interrupt receipt collection
and strand a slot after a confirmed stop. Unknown stops and uncertain constructor
outcomes still retain capacity; cancellation never fabricates a stop receipt.

A drain failure immediately disables admission and clears volatile content, then
requests active execution cleanup. It does not retry inference or keep accepting
jobs into an unserviced queue. Capabilities and session status expose the same
readiness; new submissions receive a generic 503 while unavailable. Recovery
requires a fresh service instance and the existing startup reconciliation rules.

This volatile runtime requires one API process and one AiJobService per ledger.
Concurrent replicas, multiple Uvicorn workers and overlapping rolling restarts
are unsupported. Deployment must stop the old service before starting a replacement;
multi-process leasing is a separate design, not supplied by the SQLite job claim.
An injected ledger must allow at most five waiting jobs.

## Remaining gates

R1b still needs the typed Android exchange, its larger response bound and
device-level activation/refresh/job/cancel behavior tests. Production remains
blocked on explicit server authority, current attestation and release-signer
policy, device enrollment/revocation operations, a real contained launcher and
independent OS stop receipt, route/model validation, deployment configuration,
staging security/load tests and real-device verification. Local synthetic tests
do not complete those gates or establish clinical safety.
