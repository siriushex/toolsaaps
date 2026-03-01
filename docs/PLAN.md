# PLAN

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
