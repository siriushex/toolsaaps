# Compact Chat Archive Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the pure compact projector to a trusted, opt-in CHAT worker with atomic per-device source selection and an encrypted immutable per-question receipt.

**Architecture:** A focused compact_chat module reads bounded latest versions in one existing HistoryStore transaction and reuses the established observational accumulator for nonglucose summaries. Existing encrypted server_chat entries hold a bounded optional contextReceipt; no new database/schema, profile registry or plaintext archive. The worker chooses a separate prompt revision only when trusted server code supplies explicit window/focus settings and token accounting.

**Tech Stack:** Existing Python3.12, SQLite/AES-GCM, Pydantic history contracts, asyncio worker lifetime, pytest. No new dependency, HTTP route, runtime launch or deployment.

## Task 1: Red Tests For Archive And Receipt

**Files:** create backend/tests/test_ai_compact_chat.py.

- [x] Assert missing store preparation/receipt API with a focused failing test.
- [x] Test verified owner/device selection, unique latest revisions, authenticated
  deletions, future/expired records, guards and raw full-resolution statistics.
- [x] Retain existing bounded nonglucose summary limitations; exact event records
  in requested focus windows are mandatory. Do not claim complete domain export.
- [x] Test encrypted restart-stable receipt, exact frame hash/question/policy/job
  binding, no rewrite/replay, late uploads and final answer preservation.
- [x] Test quota, missing archive, malformed settings/token evidence, tamper and
  capacity failures without admitting an inference or persisting partial success.

## Task 2: Trusted Reader And Worker

**Files:** create backend/app/ai/compact_chat.py; modify history_store.py and
contextual_chat.py; extend tests above.

- [x] Add frozen CompactChatSettings with explicit window/zone/focus and required
  TokenBudget. Only trusted factory code can supply it; no caller SQL/path/identity.
- [x] Read all latest relevant versions under snapshot/as-of/expiry limits. AEAD
  authenticate tombstones before exclusion; bind all selected record bytes and
  source identity/highwater to the receipt. Enforce row/byte/SQL/wall budgets.
- [x] Prepare with the pure projector. Nonglucose summary is explicitly partial;
  current typed profile remains unavailable if not exported. Exact focus events
  keep time/source/revision and cannot silently fall out of the packet.
- [x] Persist a <=16KiB receipt in the already reserved encrypted server_chat row
  before dispatch. Match accepted question, request/route, model/prompt/instructions
  and clock; preserve expiry and unknown outcome. Existing result archival retains
  the receipt, and reads/old queries remain backward-compatible.
- [x] Add a separate compact prompt revision and opt-in worker argument. Default
  worker/host behavior stays unchanged. Preserve single use, deadline, cancellation
  joining, native framing and independent stop. No inference after preparation
  failure, unverified token accounting or cancellation.

## Task 3: Verify And Publish Source

- [x] Run focused compact/archive/worker/query/native tests with RED-first fixes.
- [x] Run full backend on CI-matching Python3.12, scoped lint/compile and diff checks.
- [x] Document exact opt-in and residual coverage/real-token/host admission work;
  no claim of actual model delivery, durable native thread or clinical accuracy.
- [ ] Review/selectively publish this stage without prior dirty Android/photo/timer
  changes. Verify exact remote PR7 head and inspect Verify workflow status.

## Operational Boundary

No real model request, medical upload, SSH/config/service change, database migration,
phone/APK update or therapy action. Existing native128KiB ceiling and model/reasoning
settings stay unchanged. A persisted PREPARED receipt proves only the frozen input
composition, not that the model accepted/read it or that its answer is correct.
Full typed profile and dense nonglucose trajectory compaction, public receipt UI,
native-thread persistence and operational rollout remain separate integrations.

## Follow-Up: Prunable Server Conversation

- [x] Reproduce immutable server prose exhausting context despite available pruning.
- [x] Project server text into referenced turns; keep metadata and excerpt flags mandatory.
- [x] Verify byte/token pressure preserves the exact question, focus events and receipt binding.
- [x] Pass full backend (1865 tests,27 skips), scoped lint/format/compile and diff checks.
- [ ] Inspect reviewed exact-head source publication and Verify CI.
- Host activation, real token admission, phone and APK publication remain separate.
