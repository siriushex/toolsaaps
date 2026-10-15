# Parallel AI Integration

Baseline a74006dda0e852b7307b256e0bed25a75234078d:800 local backend tests passed,
26existing optional Nginx skips,1known warning. Root host/process source exists;
server/API/phone activation is not complete. No new provider or production action
is part of the implementation workers' authorization.

## Ownership

1. API worker: explicit nonroot owner-only API composition, protected config/keys,
   activation/attestation/proofs/jobs/history/archive and supervised contextual
   worker. Own new backend owner_api modules and focused tests.
2. Chat worker: signed job gateway, repository routing before personal credentials,
   bounded polling/correlation/cancellation and supported modality errors. Own
   AiChatRepository/new gateway and chat tests only.
3. History worker: dedicated signed observational/conversation limits, strict DTOs,
   snapshot synchronization and immutable upload/sequence handling. Own connection
   manager/new history client/helpers and tests only.
4. Controller: AppContainer DI, cross-module review, deployment readiness, shared
   docs/AI_NOTES, one integrated backend/Android quality gate, publication and CI.

Gateway contract: required suspend callback(String,List<AiChatTurn>)->Unit before
job submission. History client exposes syncChatContext with this signature; it
does not invent historical event times or imply complete Room-history export.
The controller wires both after independent changes are reviewed.

## Execution

Use isolated Git worktrees and ephemeral native CLI development sessions. The
desktop worktree operation targeted the outer repository and could not resolve
the nested repository's commit; detached Git worktrees use the exact nested base.
Preserve
configured development model/reasoning. No worker commits/pushes, edits shared
docs, deploys, reads real credentials/medical files or starts nested agents.
Do not run competing full Gradle builds. Controller integrates only reviewed
explicit patches and runs common checks once after relevant edits. Failed checks
are fixed before publication; results do not establish actual host/device rollout.

## Acceptance

- Owner/device proof,365day retention and advisory-only boundary remain intact.
- Configured server chat does not fall back to personal credentials on failure.
- Context upload is bounded/provenance-labelled; sequence/retry never mutates an
  already submitted body or repeats an ambiguous model turn.
- Cancellation and unknown outcomes stay explicit. Tools/therapy remain disabled.
- Full backend tests, Android unit/lint/build and exact published CI inspected.
- Actual activation requires concrete protected operational inputs and device
  verification; existing owner profile/control and uncertain outcomes are retained.

## Progress

- Done: all three isolated implementation workers finished. Controller reviewed
  and integrated their explicit14source/test paths and application DI.
- Verified controller edits: chat no longer requires24hours
  of data; display snapshots explicitly identify missing/stale values, separate
  positive/net IOB and insulin onset from action duration.
- Backend gate:879passed,26existing optional Nginx skips,1known warning.
- Integration review fixed nullable enum/fixture compilation and byte-boundary
  tests. New clock-skew tests pass; long replies are now fragmented losslessly
  within record budgets, with current context last for bounded server selection.
- Initial full Android run:5597tests,5590passed/6skipped/1history-length issue.
  Fixed the private input check;123affected tests passed without failures/skips.
  Final lintDebug/assembleDebug passed; APK signature/package verified. The scoped
  retest does not represent a second zero-failure complete-suite run.
- Pending: publish exact reviewed source and inspect its CI. Actual operational
  admission, full structured history and capability-aware UI remain later work.
