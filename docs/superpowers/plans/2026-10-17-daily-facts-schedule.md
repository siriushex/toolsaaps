# Opt-In Daily Facts Preparation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Native subagents are unavailable in this session.

**Goal:** Persist an explicit per-device opt-in and prepare yesterday's deterministic glucose facts after local05:00 without inference.

**Architecture:** Add one encrypted settings row to the existing dedicated history database. A trusted, one-shot producer reads that row, selects only the latest completed civil day after05:00 and uses the existing bounded facts/source/archive code in one transaction. No import-time scheduler, daemon, HTTP route, provider, queue handoff or therapy action is added.

**Tech Stack:** Python, SQLite, existing AES-GCM history storage, zoneinfo, Pydantic, pytest.

## Contract And Scope

- `HistoryStore.set_daily_facts_schedule(owner, device, enabled, zone_id, now_ms, expected_generation)` is trusted administration only. A missing row is disabled. Generation0 means no previous row; concurrent or stale edits fail.
- `get_daily_facts_schedule` reads without creating/migrating a store. Settings are not medical history; explicit opt-in survives ordinary365-day history purge. Encrypted facts retain original retention.
- `produce_scheduled_daily_facts(owner, device, ranges, now_ms)` is one bounded synchronous call, not a timer or authorization service. Host composition must independently verify current owner/device authorization before use.
- Before05:00 return NOT_DUE, never replay an older expired dispatch day. After05:00 catch up only yesterday. Civil-day/DST boundaries use the saved zone, not the host date. Repeated local clock hours cannot create another logical report.
- No supplied reporting range means unavailable range metrics, not an invented personal target. Missing current-day history yields NO_DATA without a normal report. Sparse/unknown/suspect observations remain explicitly limited.
- An unchanged source/range returns the original report/date. Late edits/deletions or changed analytical ranges produce facts revisions only; earlier AI/queue records remain immutable and their existing source checks can mark them stale.
- Source scan, schedule check and archive write share one transaction and existing limits. Errors/quotas/clock regression roll back. No internal automatic retry or model admission.

## Task 1: Reproduce Missing Behavior

Files: `backend/tests/test_ai_daily_schedule.py`.

- [x] Add strict opt-in/read/CAS/clock/isolation/encryption/schema tests using only synthetic history.
- [x] Add05:00, restart/latest-day, DST, sparse/missing-data, late-edit/delete and range tests.
- [x] Run the new tests;27 expected missing schedule API/module assertions observed before production changes.

## Task 2: Implement Atomic Storage And Producer

Files: `backend/app/ai/daily_schedule.py`, `history_store.py`, `daily_reports.py`.

- [x] Add exact schema6 and independently purpose-bound encrypted schedule row with generation CAS and bounded payload.
- [x] Extract the existing daily report transaction body into an internal helper so the producer can reuse it without nested locks or an unlocked check/write race. Existing save behavior stays unchanged.
- [x] Add one-shot due/source/facts producer under the same protected transaction and budget. Return fixed states with no model/job authority.
- [x] Verify unchanged data does not re-age results and revisions/deletions cannot leave the latest facts silently current.

## Task 3: Fault Review And Verification

- [x] Exercise concurrent calls/disable, corruption, quota and interrupted writes; no partial schedule/report adoption. Independent range-freeze and legacy retry regressions reproduced before fixes.
- [x] Run focused schedule/report/source/history/queue/result suites, scoped Ruff and Python compilation. Initial272 affected cases, final77 schedule/report cases; three unrelated legacy import-order notices retained.
- [x] Run complete backend suite on final reviewed inputs:1721passed/27existing skips, one existing dependency warning; before/after manifests identical. Unrelated Android sources remain unchanged.
- [x] Update `AI_NOTES.md`, architecture/invariants/plan/devops/security and `OPEN_FEATURES.md` with exact source-versus-deployment boundaries.
- [ ] Review explicit paths, fetch/check divergence, commit/push current branch and inspect exact-head Verify. No deployment/phone/model operations are included.

Commands (from `backend`, with the required `rtk proxy` prefix):

```sh
env PYTHONPATH=.:tests uv run --no-project --with-requirements requirements.txt python -m pytest -q tests/test_ai_daily_schedule.py
env PYTHONPATH=.:tests uv run --no-project --with-requirements requirements.txt python -m pytest -q
uv run --no-project --with ruff ruff check app/ai/daily_schedule.py tests/test_ai_daily_schedule.py
```
