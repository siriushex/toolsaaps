# Synthetic Host Isolation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in this task.

**Goal:** Validate the approved credential-free network design on the host using
only synthetic local TLS peers, temporary units and exact teardown receipts.

**Architecture:** Reuse TransportProbeProfile and SystemdStopVerifier. Extend the
stop verifier only for a trusted canonical-v4 generated shared slice; preserve
all default system.slice checks. A private one-off administrative controller
prepares new roots/neutral binary aliases, manifests files and pins inputs.
It is not installed in the HTTP process and is not an inference launcher.

**Tech Stack:** Python standard library, pytest/unittest, systemd255/cgroupv2,
Nginx1.24, curl, socket-proxyd, generated test certificates.

## Tasks

- [x] Add failing shared-slice receipt tests: matching identity, wrong parent,
  populated descendant, symlink, collected unit and absent parent after stop.
- [x] Implement optional slice_id in backend/app/ai/systemd_stop.py. No arbitrary
  cgroup/unit/user input or public API permission; default parent is system.slice.
- [x] Test private dependency parser, manifest boundaries, exact unit/curl
  allowlists and no mutation before preflight/manifest match.
- [x] Inspect host prerequisites read-only: source hashes, UID/GID, memory/disk,
  cgroupv2 and absence of global default environment overrides. Never retain
  environment values or authorization files.
- [x] Prepare exact private manifest before mutation. Synthetic peer/gateway,
  loopback namespace anchor, socket relay and individually identified probe jobs
  share512MiB/swap0/25%-of-one-core/64-task aggregate limits. No external listener
  or internet/model/auth request. Every active unit has a finite deadline.
- [x] Check successful synthetic401, wrong-name/route/header refusal, denied
  external connect, inaccessible readable host marker, and same-namespace
  deny-all versus loopback-allow cgroup policy controls.
- [x] Independently identify/stop every own service invocation; empty populated
  descendant state is mandatory. Stop own socket/slice; delete only recorded
  own files after bound stop, then independent absence/source-preservation check.
  Unknown identity/stop retains evidence and blocks another run; no broad cleanup.
- [ ] Full local backend gate, explicit privacy/source review, publish existing
  branch and inspect exact-SHA CI. Private operational inputs/keys stay outside Git.

Host execution is restricted to the latest owner continuation of the approved
temporary isolation check. Present exact paths/services/limits/rollback before
mutation. No existing Nginx/firewall/rootfs/auth/service, phone or therapy change.
Real provider transport, login and neutral inference remain later distinct gates.

## Host Findings

The first bounded attempt stopped at root-identity admission, before any HTTP
case. Its inactive static unit cleared InvocationID instead of being collected.
Added exact loaded/inactive/dead/blank-ID handling only after the original
invocation was positively bound and stopped; populated descendants still fail.
The root check now compares device/inode rather than proc symlink spelling.
Own first-attempt files were recovered only after all units were independently
inactive, no own jobs remained and the descendant cgroup was empty. Postflight
confirmed directory/file/cgroup/process absence and original source identities.
Inactive cached unit definitions are not resource leaks or collected-unit proof;
do not use a global daemon reload to conceal this distinction.

The final synthetic run passed all13 cases and all17 bound service stops.
Independent postflight passed own-file/directory/process/cgroup absence and
unchanged source identity checks; inactive cache definitions remain explicit.
Full final local backend489 passed, including22 real Nginx engine tests; focused
122 and private23 checks passed. Later harness-only changes passed18 affected
checks. Publication/CI remains outstanding until the exact commit is inspected.
