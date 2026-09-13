# PLAN

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
