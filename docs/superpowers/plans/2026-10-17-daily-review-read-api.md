# Protected Daily Review Reads Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task. Native subagent tools are unavailable; implementation stays in this task.

**Goal:** Deliver bounded signed reads of stored same-device daily reviews, without starting analysis or granting clinical authority.

**Architecture:** Continue the approved 2026-10-15 daily-review/admin design and existing F-H read-projection track. Reuse HistoryService authorization/proofs and daily_results authenticated reads; never expose queue/frozen native input. A strict optional protected owner flag defaults off. No live deployment, schedule, model call, new upload or therapy change belongs to this source stage.

**Tech Stack:** Existing Python, FastAPI, Pydantic and encrypted HistoryStore; no new dependency or migration.

## Contract

- POST `/api/ai/v1/history/daily-results`: exact body `{"afterSequence":0,"snapshotSequence":null}`. Existing durable membership, maximum20 summaries, no caller-selected owner/device or arbitrary limit.
- POST `/api/ai/v1/history/daily-result`: exact body `{"jobId":"<canonical UUID>"}`. Selected reads recheck the source through the existing bounded reader.
- Both use existing access/key proof, exact method/path/body, nonce and current owner/subscription/device checks. Request ceiling1024bytes; response ceiling128KiB; no partial response.
- List retains `RETAINED_ARCHIVE_ONLY`, `NOT_CHECKED` and `NOT_ADMITTED`. Detail returns revision, job/date/zone/report revision/model/storage clocks, current source state/check, historical source check and grounded review. It excludes queue/frozenWork, raw request/credentials, execution claims and native input.
- `SOURCE_MATCHED` is not clinical approval. `UNREVIEWED_AI_INTERPRETATION`, `REFERENCES_ONLY`, `GLUCOSE_ONLY`, missing domains and `NOT_ADMITTED` remain unchanged.
- Optional owner descriptor `daily_reads` must be an actual boolean, defaultfalse. Enabled reads require HistoryService; existing descriptors/routes/capabilities stay unchanged by default.

## Task 1: Behavioral REDs

Files: new `backend/tests/test_ai_daily_read_api.py`; existing owner API tests for the optional flag.

- [x] Test first a real encrypted stopped result projected without internal execution input, preserving source/quality and no new runner calls.
- [x] Test exact query grammar, boolean/negative/overflow cursor rejection, duplicate keys, size bounds, missing/expired/foreign results, current source change/supersession and unchanged storage.
- [x] Test signed HTTP success and access/proof/revocation/owner/device isolation, default-off routes and invalid opt-in values. Observe intended missing-module/default-flag assertions before production edits.

Command: `cd backend && uv run --no-project --with-requirements requirements.txt python -m pytest -q tests/test_ai_daily_read_api.py`.

## Task 2: Minimal Read Projection

Files: new `backend/app/ai/server_daily_query.py`; `backend/app/ai_server.py`; `backend/app/ai/owner_api.py`.

- [x] Reuse Closed/_decode/encoded and existing list_results/get_result with fixed diagnostic codes and response bound.
- [x] Register two signed history routes only for explicit bool opt-in. Reuse the existing route authorization rather than a second access path.
- [x] Decode the optional owner flag strictly and pass it into composition. No scheduler/worker factory, capability widening, database write or new storage root.

## Task 3: Verification And Delivery Map

- [x] Focused205 read/archive/source/HTTP/owner tests; final full1681 passed/27 existing skips; compile and scoped lint passed. Actual output inspected.
- [x] Review all paths and synthetic fixtures for secrets, unsafe output and unrelated changes; retain private logs outside Git.
- [x] Update OPEN_FEATURES, PLAN, ARCHITECTURE, INVARIANTS, DEVOPS, SECURITY_REVIEW and AI_NOTES with actual outcomes and remaining schedule/device/admin gates.
- Publication gate: fetch/check divergence, commit explicit reviewed paths, push existing feature branch, verify exact-head Verify and source-only PR receipt. These post-commit results are recorded in the PR stage receipt, not assumed by this source checklist. No server/APK activation is implied.

Do not rerun already-passed Android tests merely for unchanged app source. Exact-head CI remains a separate required gate. If any requirement needs a wider policy or data source, stop that expansion and record it separately instead of adding an implicit fallback.
