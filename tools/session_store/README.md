# Session Storage Candidate

Source-level repair for the pinned upstream file backend. **Not deployed and not
an authenticated runtime.** No client launcher, account/token import or provider
request to a real provider is included. Keep the existing runtime and owner
profile unchanged. The CI-only synthetic protocol harness below is separate.

The helper writes a private sibling temporary file, syncs its contents, atomically
persists it over the destination, then syncs the parent directory on Unix. Before
replacement, write/sync failure leaves the previous complete file. A failure
after replacement must not restore an older file or imply the update did not
happen. Ordinary errors remove the temporary file; forced termination can leave
a private orphan. Never auto-promote an orphan or restore an old refresh token.

## Verify

```sh
cargo test --locked --manifest-path tools/session_store/Cargo.toml
cargo clippy --locked --all-targets --manifest-path tools/session_store/Cargo.toml -- -D warnings
python3 -m unittest discover -s tools/session_store -p 'test_*.py'
python3 tools/session_store/verify_upstream.py
```

The last command fetches only two fixed public source files over verified HTTPS,
checks their SHA256, applies the candidate to a fresh private temporary copy and
verifies the three changed paths, dependency metadata and exact tested helper.
For offline verification, pass `--source-root` with a clean pinned source tree.
No installed runtime or authorization/profile file is read or changed.

`session_patch_bundle.py --source-root <clean-source> --check` checks without
modifying the source. Without `--check` it prints the source-bound patch. It
refuses wrong hashes or already-patched input; it does not build/install a client.
The integration only moves the already locked tempfile dependency from test to
runtime use and preserves the native JSON serialization and credential schema.

## Full Linux Build Check

`Native Client Candidate` uses a fresh ephemeral Ubuntu runner, no saved cache,
credentials or profile. It fetches the exact public annotated tag object and
checks its peeled commit, clean full checkout, native lock and toolchain hashes.
`native_build.py prepare` applies the same three-path patch and creates an
external digest receipt. `verify` refuses later source/lock/helper drift.

The published upstream tag has a pre-existing release metadata mismatch: all149
local packages remain version0.0.0 in Cargo.lock while their manifests inherit
0.153.4. Before the locked build, Cargo's `update --workspace` repairs that
metadata in the disposable checkout. `record-lock` parses both lockfiles and
admits exactly those149 version fields. Every external version, source, checksum,
dependency list and other field must remain identical. Original and derived lock
digests are both recorded. Any additional change stops before compilation; no
unlocked build or dependency substitution is allowed. The original storage patch
still touches only three source paths; the separately admitted metadata repair
adds the lockfile as the fourth changed path in the full-build checkout.

Native Rust1.95.0 builds the login library tests and actual CLI with `--locked`
and the upstream `dev-small` profile, two compile jobs and a90-minute job limit.
This is a development validation build, not a release or reproducibility claim.
Push builds upload no binary; explicit retention is described below. Nothing is
deployed. CI records source/patch/toolchain/binary
digests; these bind the observed build, not an independently signed attestation.

An isolation-only preflight precedes compilation, creates/verifies the private
scratch home on its fresh1777 tmpfs and does not execute a client. Host /tmp is
never mounted or chmodded. Process-local umask077 is applied and proven with a
default-mode directory creation before launch; native children inherit it.
The version-only smoke uses a fresh minimal mount root, private PID/network/IPC/
UTS namespaces, UID65534, no effective capabilities, no-new-privileges, cleared
environment, empty private home and time/CPU/address-space/process limits. Only
system runtime files, the binary and checker are mounted. The checker verifies
the network namespace differs from the host and has no external interfaces,
rejects host state mounts and requires exact quiet version output. Native startup
creates helper aliases even before parsing --version, so the fresh private home
may contain only its exact bounded scratch tree: four directories, one empty0600 lock
and four links to the mounted client. Any authorization/config/history file,
different path/link/type, nonempty lock or nonprivate alias group is refused.
Only counts are reported. This does not test a real login, TLS headers, model
access or cancellation, and no existing owner profile is mounted.

## Synthetic Native Protocol

After compilation/version validation, `protocol_smoke.sh` runs the actual CLI's
stdio app-server against a newly generated private CA and local TLS peer. The
native development build's supported login-issuer override selects only
`https://login.example.test:8443`. The source, native protocol and storage patch
are unchanged. This does not establish the release build's issuer selection,
production gateway behavior or real provider compatibility.

An early no-client preflight checks the same namespace, peer/trust setup and
teardown before expensive compilation. Only loopback is enabled inside a fresh
network namespace, with a file-only name mapping and no host resolver/profile.
Trusted setup holds network-administration privileges only until loopback is up;
the checker, TLS peer and client run as UID/GID65534, no supplementary groups,
all capabilities cleared and no-new-privileges. Read-only runtime/test inputs,
a128MiB private tmpfs and time/CPU/address-space/process/file-size limits bound
the run. Host /tmp, keyrings, auth, configuration and histories are not mounted.

Six scenarios cover initialize/device-code/pending polling, wrong-ID/correct-ID/
repeat cancellation, process-group termination, untrusted CA, trusted CA with a
wrong certificate name, provider403 and malformed user-code JSON. Only two exact
POST routes and synthetic bodies are accepted; OAuth token exchange is not
allowed. Initialization and effective HTTP identities must be neutral, or absent
for the native raw-auth client. Credential headers are refused.

A bounded syscall trace admits only the mounted client execution and loopback
peer connection attempts. Each scenario checks private owner/modes, bounded
file counts/bytes and absence of auth.json. This is a bounded scratch inventory,
not a complete native filename allowlist or proof against malicious client code.
Polling must stop after cancellation; process groups/listener threads must exit.
An independent host-side kernel namespace-reference check follows Bubblewrap's
PID-namespace teardown. Passing runs print only count/status receipts. Failure
diagnostics bound synthetic-only stderr/syscall tails and selected process
status; never reuse this reporter with real credentials/profiles. Generated peer
keys are removed by the wrapper; no test-state artifact is uploaded. Binary
retention is a separate explicit opt-in below.

Actual native protocol results remain pending until the exact-source workflow
finishes. Synthetic codes are not credentials, successful cancellation is not
authenticated inference, and none of this authorizes a real owner login or
server runtime replacement.

The native test uses an empty nonwritable command-search directory, not the
system executable search path. Native platform warnings are not suppressed.
Read-only public OS release metadata supports the pinned os_info library's
normal file fallback. Its exact lsb_release -a and getconf LONG_BIT lookups may
fail ENOENT; at most16 attempts per command at the two fixed search locations are accepted, but
successful additional executable/tool invocation is refused. This constrained
test environment is not a claim about a normally configured production PATH.
The1GiB address-space ceiling stays unchanged. MALLOC_ARENA_MAX=2 is tested as
a bounded allocator configuration after observed native allocation failure;
it is not a measured RAM reduction claim. See the
[glibc arena limit](https://sourceware.org/glibc/manual/latest/html_node/Memory-Allocation-Tunables.html).
Traced preflight observed libc's lookup of the absent /var/run/nscd/socket.
At most8 exact ENOENT failures are counted separately. Any successful Unix
service connection, different path or different failure is refused. /var and
/run are still absent, not host mounts; no resolver-service access is enabled.
Syscall output is split per process to avoid cross-thread unfinished/resumed
interleaving. Only owned0600 regular, single-link numeric-suffix trace files
are read, with128-file/1MiB total limits; symlinks and extra filename shapes fail.
Trace/private-state failures include bounded synthetic command/path context,
not file bodies. Core-size allowance is zero for the contained process tree.
The native CLI also starts plugin-catalog synchronization automatically. The
auth-only command explicitly sets the supported features.plugins=false option;
failed git attempts are not added to the audit allowlist. Pinned source gates
startup marketplace/sync tasks on this feature. Model and permission modes are
unchanged. Actual no-extra-execution/network validation still precedes real use.
Pinned native installation metadata intentionally chmods its installation_id
to0644 even if pre-existing. The sole file-mode exception is the exact
profile/installation_id: owned regular/single-link, exactly36 bytes containing
a canonical v4 UUID, mode0644, with all enclosing scratch directories still0700.
Its value is never printed. Every other regular file remains0600 and auth.json
is forbidden in these original six cases. This is metadata compatibility, not credential admission.
The no-client process inventory also runs in preflight. It admits the trusted
root PID1 bwrap reaper in this launcher-created private PID namespace without
ptracing it; every other process must retain the fixed nonroot identity and be
inspectable, and no mounted client may remain. After exit, the independent host
observer still requires complete namespace absence. This is not a process-name
allowlist for arbitrary host workloads. See the
[PID namespace behavior](https://github.com/containers/bubblewrap#sandboxing).

## Synthetic Exchange Mode

The same private wrapper accepts --exchange as a separate mode with four fresh
profiles. Success exercises the actual device-code grant, exact URL-encoded OAuth
form, token parsing/private atomic save, matching completion/account notification
and account/read. OAuth403, malformed token JSON and invalid identity claims must
fail completion, leave account empty and persist no auth.json. The original six
cases retain their two-route/no-auth defaults.

Only successful synthetic exchange explicitly admits the exact profile/auth.json:
owned regular single-link0600, bounded content, exact fixed synthetic claims and
tokens, ChatGPT mode/no API key, fresh timezone-aware last_refresh, no unknown or
duplicate JSON fields or known writer temporary orphan. All parents remain0700.
These unsigned fake JWTs are not provider credentials; signature validation and
real account entitlement are not established. The plus-plan fixture avoids the
pinned workspace-only cloud-config loader. Both issuer and ChatGPT base URL are
fixed to the private TLS peer; no credential header or inference route is allowed.

After stopping the app-server, a second actual native process reads the saved
profile with login status. Exact quiet ChatGPT status, exit0 and zero connection
attempts are mandatory. This is not an authenticated app-server restart test:
pinned app-server starts a model-catalog refresh worker when auth already exists.
Do not broaden this test to real model/provider routes or disable TLS as fallback.
Trace/private-state/process/namespace checks apply to both native processes;
receipts print only counts and booleans, not session contents. The same disposable
tmpfs is destroyed afterward. No credential/test-state artifact is uploaded.
Actual exchange results require the exact-source native workflow to finish.

## Retained Runtime Candidate

`runtime_bundle.py create` first rechecks the locked native source receipt and
packages the actual Linux ELF as `bin/client`, that receipt, the integration
patch, upstream/patch licenses and a resolved Cargo dependency notice inventory.
No profile, authorization, build cache, diagnostic log or database is included.
Only the trusted Cargo executable input may have one or two hardlinks, because
Cargo links the profile-root executable to its hashed build output. Its bytes
are copied into an exclusively created single-link file, never linked. Candidate
files and all other packaging inputs still require one link; CI records the
observed executable input link count separately.
The same packaged bytes run the offline version and all ten synthetic protocol/
exchange cases. A final directory verification precedes any upload.

Normal push builds retain nothing. An owner-approved manual workflow dispatch
with `retain_candidate=true` uploads only the allowlisted candidate for seven
days. It is not a GitHub Release, deployment or production-runtime admission.
The public repository's artifact can be downloaded by signed-in GitHub users.
The manifest SHA256 and separate count/hash receipt are in the CI job summary;
retain the trusted exact-run receipt separately from any downloaded archive.

`runtime_bundle.py verify --zip candidate.zip --manifest-sha256 <trusted-digest>`
checks the archive without extraction or execution. `--root` checks a directory.
Both require the external manifest digest, exact closed file set, streamed content
hashes, canonical paths, fixed ELF architecture/interpreter and bounded JSON,
member counts and bytes. Links, duplicates, extra files/directories, traversal,
special/encrypted members and unbounded expansion are refused. Legacy single-disk
ZIP only; ZIP64/multipart archives are outside this narrow verifier's contract.
The directory verifier pins a root descriptor and traverses with no-follow opens;
it does not isolate a hostile same-UID/root process that can modify regular bytes.
Archive permissions are not installation permissions or executable admission.

Dependency metadata comes from locked Cargo resolution, filtered for Linux, not
from a user profile. Only regular public-source-root license targets are read;
source notice links may resolve within those public roots, never outside them,
and are copied as regular bytes, not retained as links. Candidate links are refused;
local build paths are omitted from the index. Workspace crates inherit the pinned
upstream license. Packages without a discoverable top-level/explicit notice are
reported as `declared-only`, not certified complete. This inventory is not a legal
compliance audit, a minimal linked-dependency set, a signed attestation or proof
against malicious build inputs. Host ABI/library closure, licensing review,
immutable installation/rollback, launcher containment and profile-controller
integration remain separate gates before real authorization or model use.

## Limits

- Tests execute the actual Rust helper on synthetic files, including controlled
  child-process termination and concurrent readers. The ignored child test is a
  subprocess entry point invoked by the parent tests, not an omitted scenario.
- Injected checkpoints exercise error paths; they are not hardware/disk-failure
  injection. File and directory sync requests are not power-cut certification.
- Atomic replacement does not transact provider token rotation with local disk.
  A crash before local commit may still require fresh owner login. A profile-wide
  serialized lock, bounded orphan admission and no stale-token rollback remain
  controller requirements. This helper does not implement that controller.
- POSIX storage behavior is tested; directory sync is Unix-only. A successful full
  build check still does not admit server adoption. Reviewed binary provenance,
  Linux production-host containment,
  actual TLS/header/protocol/cancellation capture and owner approval of a runtime
  change remain required before real authorization.
- No host keyring, API-key billing, model substitution, medical history transfer,
  phone operation or therapy authority is introduced.

## Provenance

Native source reference: OpenAI's official repository at
`042fb41b7c813ac7999105e886b2b7aa715b5081` (rust-v0.153.4).
[File backend](https://github.com/openai/codex/blob/042fb41b7c813ac7999105e886b2b7aa715b5081/codex-rs/login/src/auth/storage.rs).
Input hashes are fixed in the bundle verifier. Source identity does not attest an
installed binary. New helper/package code is Apache-2.0; native patch context
retains the upstream Apache-2.0 license. tempfile3.27.0 is already in that source's
lock file; the standalone harness lock reuses the same dependency versions.
