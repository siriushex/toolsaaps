# Transport Probe Profile Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in this existing task.

**Goal:** Generate and validate a credential-free fixed-provider transport probe.

**Architecture:** Pure immutable Python profile, Nginx TLS gateway configuration,
bounded curl command and strict status receipt. No launcher, credentials or public
API wiring. Separate synthetic Unix upstreams from public numeric destinations.

**Tech Stack:** Python standard library, pytest, Nginx HTTP/TLS, curl, systemd.

## Task 1: Red Profile Contracts

Files: create backend/tests/test_ai_egress_probe.py.

- [x] Test module presence as an assertion, then import its public API in fixtures.
- [x] Parameterize canonical v4 identity, root/bool UID/GID, IP/private/IPv6 and
  configuration-injection rejection; require immutable address tuples.
- [x] Assert synthetic/public separation, deny-default TLS/Host/method/target,
  credential/body suppression, certificate verification and logging limits.
- [x] Assert curl disables user config/proxies/redirects and yields status only.
- [x] Exercise real JSON parsing: 401 succeeds as transport; 000/nonzero TLS,
  duplicate/extra keys, nonbytes and oversized output fail without raw errors.
- [x] Run focused suite and retain its expected missing-module failure.

```sh
PYTHONPATH=backend uv run --no-project --with-requirements backend/requirements.txt python -m pytest -q backend/tests/test_ai_egress_probe.py
```

## Task 2: Pure Profile And Verification

Files: create backend/app/ai/egress_probe.py; update architecture, invariants,
DEVOPS, security review, PLAN and AI_NOTES with the source-only boundary.

- [x] Implement frozen TransportProbeProfile(run_id, uid, gid, public_ipv4=None),
  gateway_config(), gateway_properties(), worker_properties(), probe_command(host).
  Validate before interpolation; no caller-selected host/path/command/mount.
- [x] Implement parse_probe_receipt(raw, *, exit_code) returning frozen TransportReceipt with
  http_status, not a readiness/authorization claim. Stable ValueError codes only.
- [x] Run focused tests, isolated E/F checks and full backend tests. Inspect exact
  hashes before/after the final full run; do not rerun unchanged Android locally.
- [x] Where available, compile/test with actual Nginx1.24 using synthetic CA and
  Unix upstreams only. If unavailable, preserve that gap and do not claim runtime
  validation from string assertions. No server mutation is a fallback.
- [x] Privacy/diff review and fetch/divergence inspection before explicit staging.

Publication acceptance: commit/push existing branch and inspect exact-SHA Verify
jobs. This result belongs to the immutable GitHub check and private execution
receipt; do not manufacture a pre-commit CI success or merge/release.

```sh
PYTHONPATH=backend uv run --no-project --with-requirements backend/requirements.txt python -m pytest -q backend/tests
git diff --check
git fetch origin
git rev-list --left-right --count HEAD...@{upstream}
```

Acceptance is tested source/profile correctness, not deployed network isolation
or real inference. A remote test still requires reviewed exact files/units,
source hashes, stop/cleanup procedure and operational authorization.
