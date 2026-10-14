# DEVOPS

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
