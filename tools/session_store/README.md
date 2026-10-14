# Session Storage Candidate

Source-level repair for the pinned upstream file backend. **Not deployed and not
an authenticated runtime.** No client launcher, account/token import or provider
request is included. Keep the existing runtime and empty owner profile unchanged.

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
No binary is uploaded or deployed. CI records source/patch/toolchain/binary
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
