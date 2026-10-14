# Personal Device History API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved local encrypted per-device history/API stage with365-day expiry and no inference or therapy authority.

**Architecture:** Reuse bound enrollment/proof authentication and optional app injection. Separate strict history contracts, one private encrypted SQLite file per authenticated device, and a synchronous service called through the existing threadpool boundary. Existing AI jobs and Android callers remain unchanged.

**Tech Stack:** Python, SQLite, Pydantic2, cryptography AES-GCM/HKDF, FastAPI, pytest.

## Task 1: Strict Typed Contract

Files: `backend/app/ai/history_contract.py`, `backend/tests/test_ai_history_contract.py`.

- [x] Write valid GLUCOSE batch and bounded query tests using the exact fields in the specification. Add duplicate/unknown keys, booleans-as-numbers, unsupported kind/source, future/stale time, missing GI provenance, reversed CI and oversized UTF-8 failures.
- [x] Run `PYTHONPATH=backend uv run --no-project --with-requirements backend/requirements.txt python -m pytest -q backend/tests/test_ai_history_contract.py`. Verify missing-contract assertions fail, not dependency/import setup.
- [x] Implement `HistoryError(status, code)`, closed typed payload models, `decode_batch(body, now_ms)` and `decode_query(body, now_ms)`. JSON decode rejects duplicate keys and nonfinite constants before Pydantic validation; all failures return a stable non-payload error.
- [x] Re-run the exact focused command; inspect every failure before proceeding.

Contract example used by tests:

```python
record = dict(recordId=str(uuid4()), revision=1, eventAtMs=now-1000,
              observedAtMs=now, kind="GLUCOSE", source="CGM", quality="UNKNOWN",
              deleted=False, data={"glucoseMmol": 6.4})
body = json.dumps({"batchId": str(uuid4()), "records": [record]}).encode()
batch = decode_batch(body, now_ms=now)
assert batch.records[0].data == {"glucoseMmol": 6.4}
```

## Task 2: Encrypted Device Store

Files: `backend/app/ai/history_store.py`, `backend/tests/test_ai_history_store.py`.

- [x] Test `HistoryStore(root, master_key=..., max_devices=..., max_rows=..., max_bytes=..., min_free_bytes=...)`, `append(owner_id, key_fingerprint, body, now_ms=...)`, `query(..., body, now_ms=...)` and `purge(now_ms=...)` before implementation. Require exact retry equality and rollback after a conflicting second record.
- [x] Run the store test file and verify failures are caused by the absent storage feature.
- [x] Implement private-path validation and dedicated schema/key manifest. Bind per-device derivation and ciphertext AAD to owner/device/record/version/time/source/quality. Store no master key or raw body in receipts.
- [x] Implement one transaction for version handling, row/quota checks and acknowledgment. Never extend expiry or resurrect tombstones. Preserve monotonic sequence and exact receipts after restart.
- [x] Implement high-water latest-revision pages with explicit continuation, a byte budget and current deletion/expiry exclusion. Implement bounded own-file purge without changing other directories.
- [x] Add concurrent retry/version, changed-key, symlink/foreign-schema, tamper, expired retry, missing-device read, large page and disk-pressure tests. Re-run focused contract/store tests.

Store behavior examples:

```python
ack = store.append(owner, fingerprint, body, now_ms=now)
assert store.append(owner, fingerprint, body, now_ms=now+1) == ack
assert store.query(owner, another_fingerprint, query_body, now_ms=now)["records"] == []
assert store.query(owner, fingerprint, query_body, now_ms=now)["records"][0]["record"]["recordId"] == record_id
```

## Task 3: Optional Signed Routes

Files: `backend/app/ai/history_service.py`, `backend/app/ai_server.py`, `backend/tests/test_ai_history_transport.py`.

- [x] Write signed real-attestation TestClient cases using existing `service`, `activate`, `signed` and `proof_headers` fixtures. Assert default routes remain404 and an injected history service works without an inference worker.
- [x] Run transport tests and reproduce the missing injection/route failure.
- [x] Implement `HistoryService(auth, proofs, store, owner_id=...)` with preauthorization and exact body proof plus current-identity recheck. No client device path/owner parameter is admitted.
- [x] Add keyword-only `history=None` injection. Register only the two specified POST routes and body limits when provided. Use history preauthorization for history-only apps and preserve job coexistence; route handlers use the same stable error mapping and no-store boundary.
- [x] Test missing/bad/reused proofs, body tamper, refresh, revocation, another device/owner, unknown fields, byte limits, query strings and generic storage failure. Verify no content/credential leaks in errors and no inference readiness is invented.

## Task 4: Review And Verified Publication

Files: `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/PLAN.md`, `docs/SECURITY_REVIEW.md`, `docs/DEVOPS.md`, `AI_NOTES.md`, `backend/README.md`.

- [x] Record actual local behavior and the explicit remaining key/storage/purge/deployment/Android/context/LLM gates. Do not document this as a deployed service.
- [x] Run the full existing backend suite with synthetic inputs and inspect terminal results. Android is unchanged; do not repeat its already-passed full gate without a relevant change.
- [ ] Review final diff and scan only explicit publication paths for secrets/private operational data and prohibited generated identifiers. Fetch origin, check divergence, commit reviewed paths and push the existing branch without force.
- [ ] Verify remote SHA and inspect exact-SHA Verify workflows. Keep PR unmerged and attach the existing PR if needed. Report local implementation separately from production activation.

Execution is inline in the current approved task. No external agent or remote runtime is started.
