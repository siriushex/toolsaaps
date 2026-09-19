# PLAN

## Photo Meal Nutrition (2026-09-13)

- Local nutrition models, deterministic calculations and validation are complete
  for the current bounded contract; photo image preparation and strict typed
  response parsing are also implemented with focused tests. A foreground-only
  gateway coordinator, explicit draft editor and accepted-runtime food-effect
  timeline boundary are now covered by focused tests as well.
- Server photo jobs, camera/picker wiring, editable confirmation, Room
  persistence, the chart/UI layer and device verification remain separate
  dependent stages. The existing server job route is still text-only and is not
  used for images.
- [Implementation status and acceptance boundaries](2026-09-13-photo-meal-implementation.md).

## R1b Android Signed AI Client (2026-09-13)

- Minimal CHAT capabilities/submit/status/cancel client implemented over the
  existing connection manager and hardware request proof.
- Focused 36 tests and full Android verification passed: 4392 tests with three
  conditional skips, no failures/errors; lint and all three APK builds passed.
- Spec review accepted the clock-recovery correction. Quality findings were
  reproduced RED and fixed; independent recheck accepted both fixes.
- Phone read-only baseline completed. Isolated installation was rejected by
  the system; device tests require installer approval, without bypassing it.
- The connected phone later changed to a device without Copilot. Confirm the
  target before any installation; do not reuse the previous phone's baseline.
- The confirmed original Copilot phone was updated with `adb install -r`, with
  matching read-back APK hash, preserved package data directory and successful
  cold start. Device data-flow and live-AI checks remain pending. The immediate
  empty-state screenshot does not establish whether the glucose database was
  empty; the contained live-AI route was not verified during that check.
- No existing clinical callers, photo inference, server deployment or therapy
  behavior changed. [Client scope and gates](2026-09-13-android-ai-jobs.md).

## R1a Signed Server AI Job Transport (2026-09-13)

- Implemented an optional owner-scoped, app-key-signed `CHAT`/`TEXT` transport
  over the existing activation, proof, metadata ledger and executor modules.
- The route is injected only; the default bound app remains inference-closed.
- Local tests use synthetic contained workers only. No real Codex call, server,
  phone, secret or medical payload is part of R1a verification.
- Exact wire, byte, status, schema, digest, ownership and lifecycle contract:
  [signed server AI jobs](2026-09-13-signed-ai-jobs.md).
- R1b Android transport, production authority/attestation/device operations,
  online contained launcher, route validation, staging and deployment remain
  pending gates. R1a is not a production inference release.

## Gentle Alerts and Telegram Integration (2026-09-13)

- Integrate the isolated alert/Telegram candidate on the published September source.
- Preserve server-AI settings, secret namespaces, activation flow and backup exclusions.
- Verify the full Android unit suite, lint and debug build before updating local main.
- Keep phone installation, bot credentials and real recipient verification separate.
- Design and setup: [soft alerts and Telegram](2026-09-13-soft-alerts-telegram.md).

## Stage map

### Stage 1: Governance baseline (completed)
Goal:
- Introduce persistent project operating rules and documentation anchors.

Inputs:
- Existing repository structure and current implementation state.

Outputs:
- Root `AGENTS.md`.
- Baseline docs in `docs/*`.
- Initial `AI_NOTES.md` entry.

DoD:
- Governance files exist in repo root/docs.
- Commands and quality gates are explicit.
- Team can run new threads using contour process.

Verification:
- `ls AGENTS.md docs AI_NOTES.md`
- manual review of command sections and contour instructions.

### Stage 2: Architecture + invariants hardening
Goal:
- Expand architecture docs with sequence diagrams and component ownership.

Outputs:
- Updated `docs/ARCHITECTURE.md` and `docs/INVARIANTS.md` with deeper integration contracts.

Verification:
- Contract checklist review against code paths in automation/predict/action repositories.

### Stage 3: Quality gate unification
Goal:
- Add standardized lint/typecheck for backend and integrate into CI process.

Outputs:
- Backend lint/typecheck commands.
- CI configuration and `docs/DEVOPS.md` update.

Verification:
- Green pipeline on build/lint/test/typecheck.

### Stage 4: Security review cycle
Goal:
- Perform structured threat review and mitigation tracking.

Outputs:
- Updated `docs/SECURITY_REVIEW.md` with MUST/SHOULD/NICE items and status.

Verification:
- Checklist pass and remediation backlog linked to code tasks.
