# Authenticated Daily Facts Host Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; native subagents are unavailable.

**Goal:** Connect existing per-device facts consent to one default-off owner API background task, without inference.

**Architecture:** The nonroot owner API owns one facts task during its ASGI lifetime. A bounded active-attested-session roster and fresh grant checks gate each opted-in device. Existing local05:00 facts production keeps original reports/ranges, deduplicates completed logical days and retries missing/error data with bounded backoff. Cancellation drains owned storage before API resource disposal. No new process, socket, public route, timer or model policy.

**Tech Stack:** Existing Python/asyncio, SQLAlchemy identity store, encrypted HistoryStore, pytest.

## Files And Contracts

- `backend/app/ai/client_auth.py`: `list_authorized_attested_sessions(owner_id, now_ms)` returns at most64 current owner-bound subscribed device sessions. Overflow fails, never a partial roster or bearer credential list.
- `backend/app/ai/daily_facts_service.py`: `DailyFactsMaintenance(auth, store, owner_id, clock_ms, storage_check)` has one serialized `tick(stop=None)` and stop-aware `run(stop)`. Read schedules every60seconds; completed generation/day is not rescanned. Safe failures/NO_DATA back off1/2/4/...30minutes, at most48 attempts per logical day/generation in one process. Restart uses the archive's persisted idempotency, not a model replay.
- `backend/app/ai/daily_schedule.py`: explicit `ranges=None` means preserve the selected day's exact archived analytical ranges, or unavailable if absent. Resolve inside the same schedule/source/archive transaction. Empty explicit tuple still intentionally selects unavailable range metrics.
- `backend/app/ai/owner_api.py`: optional actual-bool `daily_facts`, defaultfalse. Resources own the worker; lifespan creates and drains its task before closing auth/history. `_OwnerAuth` maintains current pinned storage/owner checks around the new roster.
- Tests: new `test_ai_daily_facts_service.py`; additions to schedule and owner API suites.

## Execution

- [x] Write missing roster/service/default-owner-flag and archived-range REDs using only synthetic stores and attestation fixtures.
- [x] Run before production changes:16 expected assertions failed, seven existing cases passed.
- [x] Implement bounded owner roster and independent reauthorization before/after each facts call. Revocation after a check is not an atomic cross-database stop; at most an already-admitted facts-only transaction can finish privately. No publication or inference follows.
- [x] Implement per-generation/day completion/backoff and prune memory to current bounded roster. No catch-up model queue or unbounded year scan.
- [x] Add the default-off lifespan task and positive thread-drain tests, preserving existing service teardown on failure/cancellation.
- [x] Review revocation/expiry, changed session/zone/consent, missing data, clock reversal, restart, failure backoff, no model/queue work, known-range preservation and concurrent tick refusal.
- [x] Focused240 cases, scoped Ruff/compilation and full fixed-input backend1752passed/27existing skips, no failures. Thirty new cases included.
- [ ] Update architecture/invariants/plan/devops/security/AI_NOTES/OPEN_FEATURES, review exact paths and publish under standing source authorization. Inspect exact-head Verify and record a separate PR receipt.

## Operational Boundary

Read-only2026-10-17 inspection on85 confirmed API/text/image units active and only admission renewal among personal-ai timers. This is not signed-phone/provider or daily execution acceptance. No configuration, database, key or process was modified. Actual rollout requires a separately reviewed compatible source installation, explicit device consent and current host/storage authorization. Model admission and primary clinical publication remain separate.

## Commands

From `backend`, prefix each shell command with `rtk proxy`:

```sh
env PYTHONPATH=.:tests uv run --no-project --with-requirements requirements.txt python -m pytest -q tests/test_ai_daily_facts_service.py tests/test_ai_daily_schedule.py tests/test_ai_owner_api.py
env PYTHONPATH=.:tests uv run --no-project --with-requirements requirements.txt python -m pytest -q
uv run --no-project --with ruff ruff check app/ai/daily_facts_service.py tests/test_ai_daily_facts_service.py
```
