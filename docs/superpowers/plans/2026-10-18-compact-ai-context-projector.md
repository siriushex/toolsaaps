# Compact AI Context Projector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved first stage as a pure bounded glucose/context projector with immutable bytes and a reproducibility manifest, without changing archive or live inference behavior.

**Architecture:** Reuse daily-glucose preparation and segment integration rather than maintaining another continuity or exposure algorithm. A glucose projection supplies raw-derived metrics, statistical bins and exception evidence; a separate packet assembler combines already prepared observational sections under byte/token budgets. The authenticated store/CHAT/persistent-manifest adapter is the next separately verified stage.

**Tech Stack:** Python standard library, existing Pydantic HistoryRecord/AnalysisRange and pytest. Existing backend requirements and quality commands; no new dependency or model request.

## Task 1: Test The Pure Contract

**Files:** Create `backend/tests/test_ai_compact_context.py`.

- [x] Add an importlib assertion that the new module exists, then tests that call
  `prepare_compact_context(records, question=..., from_ms=..., until_ms=...,
  zone_id=..., now_ms=..., snapshot_sequence=..., ranges=...)`.
- [x] Assert a one-minute interior trough remains in the ten-minute minimum and
  exact extrema timestamp, while raw metrics equal `build_daily_glucose`.
- [x] Assert missing bins, conflicting/suspect data, sensor changes and incompatible
  cohorts cannot acquire interpolated coverage. Empty/single-point facts stay unknown.
- [x] Assert three/seven/fourteen-day dense series, protected focus windows, DST,
  canonical section limits, UTF-8, maximum question and immutable digests.
- [x] Run the new file and inspect expected missing-feature assertion failures.

Minimal invocation used by the tests:

```python
prepared = prepare_compact_context(
    records, question="Explain the supplied observations", from_ms=start,
    until_ms=end, zone_id="UTC", now_ms=as_of, snapshot_sequence=7,
    ranges=(),
)
document = json.loads(prepared.input_body)
assert document["glucose"]["columns"][0] == "n"
assert hashlib.sha256(prepared.input_body).hexdigest() == prepared.input_digest
```

## Task 2: Implement Projection Without A Second Physiology Algorithm

**Files:** Modify `backend/app/ai/daily_glucose.py`; create
`backend/app/ai/compact_glucose.py` and `backend/app/ai/compact_context.py`.

- [x] Extract the existing daily point/cohort/conflict preparation into
  `_prepare_series(rows, changes, start_ms, end_ms)` and call it from the existing
  daily calculator without changing its digest, sorting, metrics or output.
- [x] In `compact_glucose.py`, use that preparation, `_Accumulator`, `_add_segment`
  and `_Episode` for original-segment metrics and episodes. Clip original segments
  separately into declared bins; first/last/min/max remain observed values.
- [x] Encode seven statistical columns and parallel exact extrema offsets. Retain
  suspect/conflict/gap/change evidence; excessive mandatory evidence returns a
  fixed capacity failure rather than a successful incomplete view.
- [x] Sweep requested focus and recent/background boundaries into explicit grid
  segments. Try ten-minute, older-thirty-minute and older-sixty-minute layouts;
  preserve the latest seventy-two hours and caller-selected finer focus.
- [x] In `compact_context.py`, deep-validate already selected unique latest
  HistoryRecord inputs and immutable prepared section bytes. Reject future,
  unbounded, malformed, duplicate identity or incompatible scope inputs.
- [x] Add frozen `FocusWindow`, `ContextTurn`, `TokenBudget` and
  `PreparedCompactContext` value objects. The prepared result contains
  `input_body`, `input_digest`, `source_digest` and `manifest_body` bytes/digests.
- [x] Assemble full question and mandatory supplied profile/events/facts exactly.
  Remove oldest assistant context before coarsening background; only then reduce
  older chat. Do not infer treatment fields, sum IOB/COB or parse arbitrary raw logs.
- [x] Enforce actual canonical encoded size at normal96KiB and unchanged native
  ceiling128KiB. Optional trusted token accounting reserves system/history/output;
  missing accounting is explicitly UNVERIFIED, not proof of model fit.
- [x] Bind raw input/settings/window/as-of/highwater and supplied-section hashes
  into the external immutable manifest. Frame digest is external to avoid a
  self-referential hash. No write, model, service, scheduler or action gateway.
- [x] Run the new tests and the existing daily-glucose/comparison/review gates.

## Task 3: Adversarial Verification And Source Delivery

**Files:** New tests above; scoped `AI_NOTES.md`, `docs/PLAN.md`,
`docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/SECURITY_REVIEW.md` and
`docs/AI_DEVICE_CONTEXT.md`.

- [x] Add regression tests before fixing any discovered size, mutation, boundary,
  validation, token or resource issue. Inspect dense actual projector outputs,
  not only the prior hypothetical format fixture.
- [x] Run focused pytest; then full backend tests, scoped Ruff and Python compile.
  Use `PYTHONPATH=backend uv run --no-project --python 3.12 --with-requirements
  backend/requirements.txt python -m pytest -q backend/tests`.
- [x] Record stage1 completion and explicit unconnected stages: authenticated
  archive reader, production CHAT assembly, persistent manifest, complete profile
  export and operational rollout. No synthetic result proves clinical accuracy.
- [x] Inspect diff and secret/private-data scope, fetch origin, commit only reviewed
  paths, push existing feature branch, verify exact PR head and inspect Verify CI.
  Preserve all prior dirty Android/photo/timer work. No APK/server deployment.

## Boundary Review

User approved the written adaptive format and model-context-only scope. This plan
implements its standalone first stage, not a second clinical database, provider
thread storage or an implicit live-model experiment. Prepared nonglucose sections
are supplied untrusted observations; complete domain exporters and authoritative
owner/device binding remain the responsibility of the subsequent trusted adapter.
