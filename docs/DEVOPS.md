# DEVOPS

## Daily Review Contract Source Stage (2026-10-16)

Only internal preparation and reference checking are implemented. The exact
source result schema and hash accompany canonical work input; result text stays
NOT_ADMITTED. No server descriptor, capability, public enum, native policy,
supervisor, source root, service, profile, timer or database schema is changed.
No provider or real-device test is included. Pure helpers are not deployment.

A future worker needs independent frozen-source freshness verification, a
separate explicitly admitted DAILY_REVIEW policy, shared profile/ledger ownership,
encrypted work/results and no-replay uncertain-state handling before opt-in
scheduling. Matching supplied hashes, valid JSON or REFERENCES_ONLY cannot
substitute for those gates. Keep the trusted model/reasoning choices unchanged.

## Daily Facts Archive Source Stage (2026-10-16)

No deployment or operator invocation accompanies these internal methods. Only
save_daily_glucose_report explicitly enables archive schema 3; it first preserves
the additive chat schema 2 and adds the report table/index. Failed report writes
roll back payloads; a successfully committed additive migration may remain.
Old source accepting only schema 1/2 cannot reopen schema 3. Do not downgrade an
enabled store, delete the report table or reset its version. A future operational
rollout needs a protected consistent copy/restore gate and compatible source;
retain original stores and uncertain work rather than attempting reverse SQL.

Existing purge and shared row/byte quotas include reports. Reports expire at
their original civil-day end plus 365 days, not the time of a refresh/retry.
Logical date/zone has at most 32 revisions; maximum encoded report is 512 KiB;
list reads return at most 20 authenticated summaries under SQL/wall-time bounds.
Reads never migrate, dispatch a model or rescan source history. An explicit
source check is a point-in-time comparison, not continuously monitored freshness.
No public/signed/admin route or background caller is enabled by this stage.

## Owner Text API Rollout (2026-10-16)

Source3b81c2a5 has passed both exact-head Verify runs (all six jobs). The
owner-authorized Linux rollout now runs a separate nonroot HTTP API behind TLS
and the existing proof/attestation boundary. The root Unix text controller alone
launches the distinct contained native identity. Private keys/stores remain
separate from the preserved owner profile; neither the API nor operator copied
login credentials. No medical payload was used for deployment tests.

Observed gates: differential permitted/denied TLS/BPF probes, actual empty-profile
kernel/start/stop/IO checks, protected nonroot API startup, and exactly one real
synthetic text turn (OK,9.085s,no retry). Independent checks found an idle original
profile and no running native job. The target-host synthetic store/context/API/
attestation/year suite passed170 tests. Its210241 encrypted records read in3.664s
cold,1.1319s verified-cache,3.4781s after restart and3.6287s after another writer's
revision, within unchanged budgets;239.7MiB peak,zero swap. These are synthetic
capacity results, not actual phone archive completeness or clinical accuracy.

The admitted deployment has PID-bound supervisor readiness, actual HTTP readiness,
socket activation and a singleton public-only admission renewal every5minutes.
Each renewal rechecks DNS/TLS and differential network denial before publishing a
12-minute descriptor; it cannot change the model, profile, peer or private binding.
Failure closes only the owned API/socket. Recovery of an unknown profile/job claim
is still manual, never a reset or replay. Known public image inventory is bounded;
reaching its maintenance limit also fails closed. Trust snapshots remain separately
operator-pinned: renew them under controlled drain before their seven-day freshness
limit. A successful network renewal does not renew Android trust.

Only the owned nginx site changed, after an exact private rollback copy and syntax
checks. It streams128KiB-bounded API bodies without disk body/response spooling or
request logging, preserves signed headers/raw request paths and strips forwarded
identity. Eleven public TLS negative checks passed, including401/400/404/405/413/
415 behavior. Owned API closure yielded public503; restart restored401. A401 is
access rejection, not a successful signed model exchange or device activation.

No enrollment credential has been issued. Real hardware attestation, signed
phone history/chat and app UI acceptance remain pending because ADB is absent.
Public routing is the authenticated enrollment surface, not a bypass of that
device gate. Do not replace hardware proof with a synthetic root or bearer-only
fallback. The image supervisor/photo capability, daily model worker/schedule and
private admin are not enabled by this text deployment. Do not claim full AI
feature completion from the synthetic text receipt.

Rollback: close the owned API/socket, drain its exact controller/native invocation,
retain encrypted stores and any uncertain claims, verify the current site matches
the installed digest and restore only its saved original site before nginx test/
reload. Preserve original profile tokens and metadata. Operational configs,
private receipts and logs are not source-publication artifacts.

## Photo Supervisor And Decoder

The source CLI is app.ai.vision_supervisor_host, with the closed protected
configuration and separate public-image/user requirements documented in
[VISION_API_DECODER.md](VISION_API_DECODER.md). The optional owner vision socket
must not be configured until that host is independently admitted. No installer,
root HTTP process, automatic credential copying or nginx change is included.
Run the new decoder/supervisor/host and owner API suites, then the full backend
gate with pinned Pillow. Synthetic privileged records are not production proof.

## Native Meal Vision Verification

Focused gates: test_ai_native_vision, test_ai_contained_vision and the existing
native-text/process/profile suites; then full backend/tests with pinned Pillow.
See [CONTAINED_MEAL_VISION.md](CONTAINED_MEAL_VISION.md) for limits and pending
rollout gates. The native transport does not activate owner_api or replace the
public nginx503. Reuse the original guard binding, never initialize over it.
No image through the text supervisor, root HTTP API or capability-only bypass.
Rollback of a future admitted service must drain its exact invocations, retain
uncertain claims and private storage, and restore only its owned proxy mapping.

## Combined UI And Photo Gate

See [COMBINED_UI_PHOTO.md](COMBINED_UI_PHOTO.md) for exact source scope,
local synthetic capacity evidence, per-100g schema and inactive server boundary.
Install pinned backend requirements (now including Pillow 12.3.0) for image
decoder tests; missing optional decoder skips do not establish image readiness.
Run full Android unit tests, lint and assemble before the in-place device gate.
No service/profile activation or provider request is implied by source CI.

## Core Room Export

The independent server_ai_export_index_v1.db is created lazily in Android's
noBackupFilesDir; it contains hashed keys, fingerprints, times and progress, not
medical payloads. server_ai_room_export_pending_v1.bin holds an AES-GCM atomic
page with a separate Keystore alias/AAD from the existing signed transport plan.
Never clear either pending journal to unblock an uncertain send, regenerate a
decryption key, or move an index between device bindings. Clinical copilot.db
schema/version remain unchanged. Downgrading the app does not delete journals.

Local source checks: ServerAiRoomHistoryTest and RoomAiHistoryMaintenanceTest,
then existing ServerAi* tests plus the common testDebugUnitTest/lintDebug/
assembleDebug gate. Synthetic native SQLite checks are not actual Keystore,
power-loss or phone/server evidence. Inspect coverage and fresh signed requests
on the admitted device before considering the path operational.

For a fixture-data-only correction after a full run, keep the original suite
report and unchanged production lint/APK evidence; rerun the affected tests:
`./gradlew :app:testDebugUnitTest --tests '*ServerAi*Test' --tests '*RoomAiHistoryMaintenanceTest' --tests '*AiChatRepository*Test' --tests '*AiChatContextSummaryTest'`.
The core-history stage's full 5625-test execution found one invalid classification
fixture; after AAPS_REAL correction, 150 affected tests passed. Do not describe
that scoped rerun as a second complete-suite execution.

## Owner API Source Entry

Before provisioning, use protected owner configuration version2. Its attestation
object requires revocation_snapshot with exactly path and sha256, pointing to a
separate single-link0400/0600 regular file readable by the API. Pin the complete
official JSON response from https://android.googleapis.com/attestation/status;
do not translate serial keys into imaginary certificate hashes or replace a
failed fetch with an empty list. Set revocation_checked_ms from verified operator
intake, keep the existing freshness bound, and renew/restart under controlled
drain before that trust expires. The API never downloads trust from request data.
Retain the prior descriptor and snapshot when replacing them; no v1 fallback.

Storage validation runs concurrently with signed requests and worker admission.
Inventory must use independent checked descriptors, not a shared lifetime cursor.
The owner-api overlap regression checks ownership and descriptor cleanup; the
journal fault test must assert its injection actually ran after authentication
gates. Do not replace a503 assertion with retries that hide storage drift.

`COPILOT_AI_OWNER_CONFIG` selects the mandatory protected descriptor documented
in `backend/app/ai/owner_api.py`. Run `uvicorn app.ai.owner_api:create_app --factory`
as the dedicated nonroot API user, one worker, ASGI lifespan enabled, behind the
separately admitted TLS/proof boundary. Provision private storage and independent
keys; never copy login credentials into API configuration. No startup is performed
by imports or the normal backend app. Unresolved executing ledger claims require
trusted reconciliation, not database deletion or automatic replay.

Focused source gates: `backend/tests/test_ai_owner_api.py`, Android
ServerAiChatGateway/ServerAiHistoryClient/repository tests and AiChatContextSummary
tests, followed by the full backend suite and one shared Android unit/lint/build.
Worker source tests are not deployment or actual device evidence. Rollback of an
admitted deployment must drain only its owned service before releasing storage;
retain encrypted state, uncertain claims and the independent owner profile.

For narrow client corrections after a complete suite has identified an issue,
retain the unchanged suite evidence and run the affected client/legacy tests:
`./gradlew :app:testDebugUnitTest --tests '*ServerAi*Test' --tests '*AiChatRepository*Test' --tests '*AiChatContextSummaryTest' :app:lintDebug :app:assembleDebug`.
Record the full-run and focused-retest results separately, including skips;
do not label a scoped rerun as a zero-failure complete-suite run. Published CI
still runs the full suite on the exact new commit.

## Trusted Supervisor Entry Point

PYTHONPATH=backend python -m app.ai.text_supervisor_host --help describes the
explicit root host entry point. The operator configuration schema, protected
socket directory, reload and rollback rules are in
superpowers/specs/2026-10-15-text-supervisor-host.md. No root service is installed
automatically; do not run the HTTP API under this identity. Never point it at
an unverified image/descriptor or initialize over the existing owner profile.
Focused suite: test_ai_text_supervisor_host, then full backend gate. All lifecycle
tests are synthetic; active BPF/TLS and actual root/systemd evidence remain required.

## Concrete Runtime Verification

Focused suites: test_ai_runtime_admission and test_ai_contained_text_process,
followed by the full backend/tests gate. Synthetic tests use temporary public
images, proc records and actual local child pipes; privileged systemd/guard
operations are substituted. No provider, login, medical payload or host mutation.

Instantiate SystemdTextProcess only in the trusted supervisor with operator-pinned
RuntimeImage, initialized SessionProfileGuard, OfficialEgress and fixed policy.
The image layout/ownership, exact argv/resources, expiry and rollback rules are
in superpowers/specs/2026-10-15-contained-text-process.md. The source has no
automatic installer, root socket binding or default API activation. Never reuse
the old offline auth mount or silently capture current metadata as new authority.
Expired address admission requires fresh host validation and an admitted image;
it cannot keep serving indefinitely from the one-time model-test receipt.

Production still needs active deny/TLS/BPF controls, separate API UID/socket
permissions and startup reconciliation. Verify stop under disconnect/restart,
protected storage/key/purge/restore/load and Android before claiming activation.
Rollback disables admission, stops positively bound own invocations and preserves
profile/control/database state. Never clear an unbound/unknown marker to resume.

## Local Supervisor Channel

Focused suites: test_ai_text_supervisor and test_ai_supervised_chat_transport.
Tests create private temporary Unix sockets and synthetic native transports only.
Full backend/Verify gates remain required. Use uv --offline when dependencies
are already cached; no provider/model request is part of these tests.

The host must supply the listener and independently admitted process factory.
Keep its socket under a supervisor-owned, non-writable-by-API directory, with
only the intended API group allowed to connect. Pin distinct numeric identities;
do not grant the API profile access, root, sudo or systemctl. Startup/recovery must
reconcile the existing durable ledger and SessionProfileGuard, not recreate them.
No root launcher/systemd template is installed by this source change. An ambiguous
stop disables new admission without deleting a claim, socket, profile or database.
See the2026-10-15 text-supervisor-channel spec for protocol and operational gates.

## Conversation Query Verification

Focused synthetic suites: test_ai_server_chat_query and test_ai_chat_query_transport.
Full gate: PYTHONPATH=backend uv run --no-project --with-requirements
backend/requirements.txt python -m pytest -q -rs backend/tests. The manifest now
pins pytest9.0.3 to address GHSA-6w46-j5rx-g56g; refresh the test environment from
that manifest. This does not deploy a runtime or close the default-branch alert.

The optional owner-scoped history injection mounts history/conversations with
its own4KiB/128KiB budgets. Preserve them in any future reverse proxy/client;
do not widen jobs or observational-history limits. Signed-body query only, no
URL credentials/filters or access logging. Cursor409 requires a fresh first
page; fixed membership is not a historical outcome snapshot. No migration or
database rewrite in this stage. Operational purge/key/load tests remain separate.

## Server Conversation Storage

The optional CHAT service accepts chat_archive=HistoryStore. No default route,
process or deployment is enabled. Focused synthetic suites: test_ai_server_chat
and test_ai_chat_archive_transport, plus existing contextual/job/history tests.
Run the full backend gate before publishing. No real payloads enter tests or Git.

First archive acceptance verifies the private key manifest and separately commits
additive schema1-to2 before storing data. Reads and observation writes do not
migrate schema1. Schema2 requires a compatible binary: disable archive admission
for rollback, retain the database/current key, do not restore an old snapshot or
drop the new table. The spec documents crash gaps and UNKNOWN completion.

Existing purge covers expired archive rows; production must schedule it for idle
devices and enforce backup retention too. Pending entries reserve128KiB in shared
quotas. Synthetic tests are not volume/load, power-loss or key-custody evidence.
Protected master-key provisioning, volume/backups encryption, restore and real
storage failure checks remain required before medical ingestion.

## Contextual Text Source Stage

Run PYTHONPATH=backend uv run --no-project --with-requirements
backend/requirements.txt python -m pytest -q backend/tests. Focused suites are
test_ai_native_text, test_ai_history_context and test_ai_contextual_chat. All
fixtures are synthetic; they do not launch systemd or call an upstream model.
No migration, server/profile replacement or APK is included in this change.

Before installing a production factory, independently pin native/catalog hashes,
the new owner profile/control identities, official egress and kernel limits. The
public API must not run as root. The narrow supervisor must serialize the profile,
verify before releasing input and positively stop the original invocation before
job settlement. Measure history scan/lock contention at the365-day volume; failures
must stay explicit. Configure archive/key/purge/restore and then exercise signed
device upload/chat; a synthetic adapter response is not deployment readiness.

## Inactive Profile Guard

backend/app/ai/session_profile.py is not installed or injected anywhere. Local
synthetic validation: PYTHONPATH=backend uv run --no-project --with-requirements
backend/requirements.txt python -m pytest -q backend/tests/test_ai_session_profile.py,
then the full backend/tests gate. Test children contain no credentials/medical
data and never call host systemctl; trusted stop behavior is injected on temporary
verifier objects. Existing stop-engine tests remain separate.

Production admission must freshly pin one canonical profile/control mapping and
immutable cgroup/slice scope. Never call capture to silently adopt drift. Keep
control state outside worker mounts. Initialize only an empty profile/control
root. Do not unlink lock/state to recover capacity. A reserved unbound crash,
orphan or uncertain stop is a refusal requiring separate trusted reconciliation;
only matching bound recovery uses the original stop verifier. No stale session
backup or orphan promotion. Atomic file/directory sync is not provider refresh
transaction safety. Actual runtime/credential presentation/adoption is not implied.

## Pinned Native Build Validation

The small compatibility fetch uses the public raw host at native_build's pinned
peeled commit, avoiding the unauthenticated API's shared runner rate limit. The
annotated-tag identity and original file hashes remain pinned. It adds no token
or custom User-Agent and does not change the native checkout/build workflow.

Native Client Candidate is a disposable build workflow, separate from Verify.
See tools/session_store/README.md. It fetches the fixed annotated tag and checks
the peeled commit, all clean checkout state, exact native lock/toolchain and the
three-path patch receipt before/after compilation. The upstream dev-small
profile and Rust1.95.0 are used without dependency/version substitution.
The official tag's lockfile has149 stale local package versions0.0.0 versus
manifest0.153.4. A separate Cargo workspace-only metadata update is admitted by
strict parsed equality except those fields; every external package/version/
source/checksum/dependency remains unchanged. Both lock digests are retained.
Tests and builds still require --locked. Additional deltas fail before compile.
Two compile jobs and a90-minute timeout bound the disposable Ubuntu runner;
production hosts and existing login roots are not build workers.

Isolation preflight executes no client. Native login library tests and the actual CLI build precede the no-network,
minimal-root, nonroot version smoke. A success is build/provenance evidence only,
not authenticated model access or production containment. Push builds upload no
binary. Owner-approved manual dispatch with retain_candidate=true retains only
the verified runtime candidate for seven days; see the tooling README for the
exact archive/digest contract. No release, deployment, keyring/profile mount or
real login is part of this workflow.
The same workflow now includes a separate synthetic native protocol harness;
see tools/session_store/README.md. It uses only a disposable private CA/loopback
peer, the development build's supported issuer override, six bounded negative/
cancel/termination cases and an independent namespace teardown check. Exact-source
execution is a gate, not inferred from unit tests. No real provider login,
token exchange, inference or production profile is involved. Explicit runtime-
change admission and actual production containment remain separate gates.
Keep existing runtime and owner profile intact.

The separate --exchange mode adds four synthetic-only cases after the original
six. Only this mode admits the exact OAuth form route and, on success, one fixed
synthetic0600 auth.json with strict schema/content/time/metadata checks. It verifies
account completion/readback and a separate native login status process with no
connection attempts. This is not authenticated app-server restart: pinned native
startup automatically fetches model metadata when already authenticated. Both
auth issuer and ChatGPT base URL stay local; no real provider/credential/signature
validation or model availability is claimed. CI retains no session/test-state
artifact; explicitly retained binaries contain only the bounded public build
candidate, provenance and notices. Never install from an archive's own digest:
independently pin the exact successful CI run/source and job-summary manifest
digest, verify the downloaded ZIP without extraction, then obtain separate
host/runtime-change admission. Production hosts are not build workers.

## Atomic Session Candidate

tools/session_store is an offline source candidate, not an installed runtime or
credential manager. Its README contains the locked Rust/Python checks and exact
upstream source provenance. The patch builder admits only the two pinned source
hashes and refuses an existing helper; it never reads an authorization profile.
The compatibility check applies the patch only to its own fresh public-source
copy and verifies dependency metadata, exact helper bytes and three changed paths.

The helper syncs a private sibling file before atomic replacement and the parent
directory afterward on Unix. Error after replacement is not rollback authority.
Killed writers may leave private orphans; no auto-promotion or stale-token restore.
File atomicity does not solve the provider-rotation/local-commit gap or certify
hardware power-loss behavior. Serialize profile runs and refuse ambiguous state.

Before server adoption: full pinned-source client build/provenance, explicit
runtime-change admission, bounded profile/orphan policy and contained actual
client TLS/headers/protocol/cancel tests. Never replace the installed binary,
mount a host keyring, copy old auth or start a real login from this candidate.

## Independent Login Profile Boundary

The owner selected a separate ChatGPT login and approved a new private profile.
Only an empty service-owned0700 directory has been created and independently
verified. It is persistent state, not one of the temporary transport-test roots.
Existing authorization files and profiles remain unread and unchanged. The
directory itself proves neither authentication nor successful model access.

Before first login, capture the pinned client's supported device-code protocol
with synthetic state in containment. Verify private-CA support, effective neutral
headers, exact methods/routes, no extra egress, cancellation and bounded private
state writes. Keep one serialized owner profile and preserve atomic refreshes.
Present a real one-time code only directly to its initiating owner, never to
application logs, this project's artifacts, model context or source control.
Login/provider refusal must not broaden routes or switch credential/billing mode.

Rollback can remove only the original newly created, still-empty directory after
identity verification. If state appears or identity changes, preserve it and
inspect; do not recursively delete it or overwrite it with an old snapshot.
No login worker, authentication gateway, model request or API is enabled here.

## Public Transport Validation Boundary

The owner-approved anonymous check recorded in AI_NOTES passed actual public
transport to both fixed names with matching local/upstream403 and verified TLS.
Before public HEAD, the trusted controller pinned fresh fixed-name DNS/public CA
inputs and proved same-address host-network deny/allow with a private test CA.
The latter cannot send HTTP to a public origin. Gateway upstream trust then used
only the pinned public bundle, fixed IPv4 addresses and fixed provider Host/SNI.

Only two public HEAD requests were made; no redirects, credentials, prompts or
model/auth routes. All13 controls and16 bound stops passed. Independent checks
confirmed own-file/process/cgroup absence and unchanged sources/public CA.
Operational roots/addresses/keys/manifests remain private, not installed services.

ObservedProbeProfile outputs only HTTP status, local TLS result and its own
upstream status. It uses curl's header write-out support, introduced in7.84.0;
see https://curl.se/docs/manpage.html#-w. Optional real-engine coverage is
backend/tests/test_ai_upstream_probe_nginx.py with NGINX_TEST_BINARY. Raw headers
or provider bodies are never receipts. Original profile semantics are unchanged.

A403 may be an anonymous-access or edge-policy refusal; it is not authenticated
model access or permission to bypass challenges. Login needs a separately reviewed
minimal profile, fixed-version protocol capture and owner participation. Do not
copy root/old authorization, broaden paths after refusal, expose privileged HTTP
installation, upload device data or claim personal AI readiness from this check.

## Synthetic Host Validation Boundary

The owner-approved bounded synthetic operation recorded in AI_NOTES passed13
cases using private TLS peers, isolated roots/namespaces and an aggregate
512MiB/swap0/25%-of-one-core/64-task slice. Each of17 original service invocations
was positively bound before stop; an independent read-only check confirmed own
file/process/cgroup absence and preserved source identities. Exact operational
manifests, certificates and evidence remain private, not a production launcher.

SystemdStopVerifier defaults to system.slice; slice_id admits only a canonical
v4 identity mapped to the fixed generated slice name. A loaded static unit may
clear InvocationID only after successful original-invocation binding and stop,
in the exact inactive/dead/blank-ID/empty-group state. Entry without the original
ID, a different ID/user/unit/group or populated descendant still fails closed.
Root validation compares device/inode, not proc symlink spelling. All Nginx HTTP
temporary paths must use the bounded /work mount, not distribution defaults.

Inactive unit definitions can remain in systemd's cache after own files are
deleted; do not claim they were collected or globally reload unrelated units
just to clear them. Unknown start/stop identity retains own files for inspection.
After an interrupted operation, inspect actual state before retrying; never
replace a missing receipt with a blanket stop/delete or reuse a job identity.

This synthetic result does not establish provider connectivity, independent
minimal login, authenticated neutral inference, malicious-context containment,
real workload cancellation, retained history or device chat. Those are separate
reviewed gates; no existing credentials, model profiles, phone or therapy are
implicitly authorized by these results.

## Transport Profile Source Stage

app.ai.egress_probe only generates profiles and validates status receipts; it
does not construct roots, install units or perform networking. Synthetic mode
requires two TLS Unix peers and an isolated test upstream CA; public mode requires
one independently verified public IPv4 per fixed provider and the public CA bundle.
The source has no mixed mode, runtime DNS, authorization or model request path.

Optional real-engine suite: set NGINX_TEST_BINARY to a trusted Nginx1.24+ binary
and run backend/tests/test_ai_egress_probe_nginx.py. It uses generated short-lived
test certificates and filesystem sockets only. Tests rebase filesystem references
for an unprivileged local directory, not the HTTP/TLS policy. Without the explicit
binary these checks skip honestly; ordinary unit tests do not prove live egress.

Before remote use, separately review exact root/library/config/certificate/socket
and unit manifest. Worker and gateway properties alone omit relay/namespace setup,
shared512MiB/25%-of-one-core/64-task budget, fixed launch environment, source/CA
provenance, active cgroup filtering and independent stop receipts for every role.
Do not compensate with host networking, broad mounts, credential reuse or a
privileged public API. Existing services/firewall/profile remain untouched.

## Personal History Storage Stage

This optional local library/API stage is not a server deployment. Default app
factories expose no history route. Compose HistoryStore and HistoryService with
trusted activation/proof services, one explicit owner and an externally supplied
32-byte master key; never place keys in history directories, URLs or logs.
The POSIX private directory must be owned by the runtime account with0700 mode.
Files use0600, local SQLite and per-device payload encryption; metadata remain
plaintext and production still requires an encrypted local volume and backups.

Records expire365 days after original event time; requests use original request
time. Successful writes purge expired own-file data and queries filter it.
HistoryStore.purge provides bounded maintenance for idle files; deployment must
schedule it and enforce backup/key expiry. No automatic maintenance service is
installed here. Key changes must fail closed, not initialize over existing files.

Before enabling real ingestion: capacity/OS quotas, private master-key custody,
audited restore and key rotation, current enrollment policy, monitoring without
payload logging and storage-failure tests. Preserve current deployment before any
separate rollout; never restore stale medical history to roll back application
code. Existing per-device files are never deleted or rewritten as a fallback.

## Environments

### Offline Runtime Validation Boundary

The owner-approved version-only operational check recorded in AI_NOTES passed
inside the existing offline root with the separate nonroot identity. Actual
properties and an invocation-bound stop receipt were inspected, then disposable
unit/cgroup/directory absence was independently checked. Private operational
evidence is retained outside Git; no credentials or medical payloads were used.

This is not an online worker or deployment acceptance. A version string does not
prove model compatibility, authenticated inference, provider-only network egress,
hostile-context containment, cancellation under load or contextual device chat.
The default API readiness remains unchanged. Any later online validation needs
its own reviewed exact-target plan, rollback and explicit authorization; do not
reuse the offline result as permission to mount credentials or upload history.

### Development

- Local Android development: `android-app` via Gradle.
- Local backend development: `backend` via Python venv + uvicorn.

## Current quality commands
- Android build: `cd android-app && ./gradlew :app:assembleDebug`
- Android lint: `cd android-app && ./gradlew :app:lintDebug`
- Android tests: `cd android-app && ./gradlew :app:testDebugUnitTest`
- Android typecheck proxy: `cd android-app && ./gradlew :app:compileDebugKotlin`
- Backend tests: `cd backend && .venv/bin/python -m pytest -q`

## Gaps to close
- Backend lint is not standardized (recommended: `ruff check` + `ruff format --check`).
- Backend typecheck is not standardized (recommended: `mypy`).

## GitHub Workflow

Repository: https://github.com/siriushex/toolsaaps (public).

After each locally verified stage: fetch origin, inspect divergence and the
complete diff, scan for secrets/private data, explicitly stage reviewed paths,
commit and push the feature branch to its upstream. Use a PR against main;
merging remains a separate decision. Do not use force-push, background auto-commit
or automatic pull over a dirty worktree. If new upstream changes overlap with
local edits, stop publication and reconcile them explicitly without losing work.

`.github/workflows/verify.yml` runs on push, PR and manual dispatch:
- Python3.12: install pinned backend requirements and run the backend suite.
- Java17 / Android SDK36: unit tests, lint and debug APK build.
- Read-only repository permissions, pinned action SHAs, bounded job durations.
- No provider keys, production connections, phone databases, signing keys or
  deployment steps. CI debug APKs are not uploaded and are not phone releases.

Confirm the remote branch SHA matches the local commit, then inspect the Verify
result. A successful push with queued/failed CI is not a passed verification.
CI does not prove device migration, delivery or clinical correctness. Retain
private device evidence locally and link only non-sensitive summaries in PRs.
Required-check branch protection is not enabled by this file; it must be
configured separately before claiming main is protected.

## Rollback basics
- Android: reinstall last known-good apk artifact on device.
- Backend: redeploy previous image/version and run smoke endpoint checks.

## Migration basics
- Any DB schema change must include migration strategy and rollback note in PR + docs.

### Room 33 To 34

Migration34 adds the independent meal-input observation table and two indexes.
No historical backfill, clinical/forecast/settings rewrite or model activation.
Native migration/restart/integrity and supported older tails must pass. A schema33
APK cannot open a migrated34 database; use a reviewed forward repair and fresh
coherent backup, never APK-only downgrade, stale therapy restore or destructive
fallback. This source stage does not authorize phone installation/disable/stop.

### Room 32 To 33

Migration33 creates the independent prospective forecast journal and two indexes
only. No backfill or clinical/alarm/meal mutation. File-backed migration/restart
and supported migration tails must pass. A schema32 APK cannot open migrated33;
use a reviewed forward fix and fresh coherent current-data backup. Do not restore
stale therapy or use APK-only downgrade. No destructive fallback. Publication
does not authorize phone installation.

### Room 31 To 32

Migration32 only creates the local alarm state/cycle journal and its scheduling
indexes. Existing clinical/history/settings/receipts are not rewritten. Before
any separately authorized phone update, retain a fresh coherent private backup,
test ALL migrations on a disposable current-data copy and verify on-device
opening without destructive fallback. The storage-only source stage does not
enable new audio and does not prove device delivery or CPU/RAM savings.

An old schema31 APK cannot reopen a migrated32 database. Do not apply the generic
APK rollback to this transition or restore stale therapy data. Use a reviewed
forward fix or a separately validated non-destructive downgrade after a fresh
current-data backup. No destructive fallback is added for31/32.

### Room 30 To 31

Migration31 adds three nullable GI metadata columns to meal profile overrides
and pending meal intents. Existing therapy/history/settings and profile timing
are not rewritten; old GI remains unknown. Synthetic migration/restart tests
cover retention, foreign keys and integrity. Verify a fresh disposable working
database copy before the authorized in-place phone update, never the live DB
through a test harness. Retain a coherent private backup and signed old APK.

The old schema30 APK does not support a database already migrated to31. Do not
blindly reinstall it or restore an old backup over later therapy. Recovery
requires a reviewed forward fix or separately reviewed non-destructive downgrade
with a fresh current-data backup. The generic APK rollback above is insufficient
for this schema transition. No destructive fallback is added for30/31.
