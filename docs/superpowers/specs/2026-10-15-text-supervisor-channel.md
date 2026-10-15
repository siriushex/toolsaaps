# Local Text Supervisor Channel

## Scope

Source-only typed Unix IPC between the unprivileged API and the trusted native
runtime controller. No service installation, new provider request, owner profile
access, Android transfer or clinical action is performed by this stage. The
concrete contained process factory and host bootstrap remain separate work.

## Boundary

ContextualChatWorker accepts exactly one local process or TextTurnRunner. Its
authenticated history collection, policy revision and deadline remain unchanged.
NativeTextRunner owns the existing native protocol. SupervisorTextRunner sends
only one bounded text envelope to TextSupervisor; it cannot select argv, paths,
environment, native methods, tools, model, instructions or reasoning settings.
The trusted supervisor owns the full TextSessionPolicy and compares its canonical
SHA-256, including limits, before constructing a process. A digest is a policy
binding, not authentication or an attestation of the runtime.

Both sides verify kernel-reported UID/GID before releasing input. Linux uses
SO_PEERCRED; local macOS testing uses libc getpeereid. No caller-supplied UID and
no TCP fallback. The host provides a bound AF_UNIX stream listener, owns its
private path and lifetime, and must use distinct API/supervisor identities. The
module never unlinks or replaces a socket. Same-identity/root compromise is
outside this boundary; tests using one local user are not production separation.

## Closed Protocol

Each frame has a four-byte network-order length followed by a JSON object.
Duplicate keys, nonfinite values, invalid UTF-8, unknown fields and excess bytes
are refused without raw diagnostics. There is no native RPC passthrough.

The request has exactly version=1, canonical v4 runId, policySha256 and text.
The text maximum is the fixed policy limit, at most131072 UTF-8 bytes. Framing
allows worst-case JSON escaping separately: request maximum787456 bytes and
response maximum99328 bytes. Existing public HTTP budgets are unchanged.

The response has exactly version, matching runId, state, stopped and result.
Only SUCCEEDED with a boolean true stop receipt and a valid existing ChatResult
can return text. Other states are FAILED, CANCELLED or REJECTED, with null result.
No native diagnostics, tokens, reasoning trace or profile paths are returned.

One connection admits one turn. Any second frame, malformed extra input or EOF
cancels it; the client uses a small cancel frame. There is no IPC reconnect or
model retry. The run UUID correlates a connection, not a cross-restart idempotency
key. Durable signed request idempotency remains the existing API job ledger;
the private local socket must not be exposed as a second public job API.

## Lifecycle And Bounds

- At most two handled connections, one active native turn, no supervisor queue.
- Five-second handshake, at most840-second run,15-second stop and bounded replies.
- API cancellation leaves the exchange alive only to receive the independent
  stop receipt; failed connection after a partial send remains ambiguous.
- Native start is marked attempted before awaiting it. Failure/cancellation must
  still call independent process stop. Constructor failure is also ambiguous.
- Success is withheld until stop and native IO termination. Unconfirmed stop or
  lingering IO poisons the controller instance and retains unfinished task
  references. It cannot regain capacity merely because a connection closed.
- Shutdown closes admission, cancels handlers, drains teardown, then waits for
  sockets. Waiting for socket closure first deadlocked on Python3.13 and is
  covered by a real local-socket regression test.
- API execution reconciliation now disables new admission after an ended worker
  retains RUNNING/CANCEL_REQUESTED. HTTP reports UNKNOWN; no success is archived.
  Already queued payloads still expire normally. The durable slot is not cleared.

The protocol receipt is trusted only through the admitted supervisor identity.
It does not itself prove kernel/cgroup containment. The process factory must
still verify the pinned runtime/catalog, serialized owner profile, effective
filesystem/network/resource settings, and exact invocation teardown. A native
process that suppresses cancellation can leave retained IO; this requires host
reconciliation, never a restart or token/database rollback to regain capacity.

## Verification

Real local Unix sockets with synthetic native transcripts cover peer mismatch,
strict fields/lengths, escaping, fixed policy, forbidden native tools, cancellation
during connect/start, EOF, busy admission, timeout, repeated use, lost/malformed
receipts, shutdown and unconfirmed/late teardown.

A signed HTTP test traverses existing authentication, owner/device history,
supervisor IPC, native protocol validation, executor stop, encrypted archive and
signed archive retrieval. It checks follow-up context and same-owner device
isolation. Synthetic data only; no provider or systemd execution is claimed.

Run full backend tests and exact-head Verify before integration. Production still
needs the admitted contained-process implementation, nonroot API/socket/bootstrap,
actual Linux identities/permissions/teardown and storage tests, Android wiring,
and separately authorized host/device rollout. No default service is activated.
