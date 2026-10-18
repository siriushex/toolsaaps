import asyncio
import hashlib
import importlib
import importlib.util
import json
import sqlite3
from dataclasses import replace
from threading import Event
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.ai.compact_context import FocusWindow, TokenBudget
from app.ai.contextual_chat import ContextualChatWorker
from app.ai.history_contract import HistoryError, RETENTION_MS, encoded
from app.ai.job_service import JobIdentity, JobWork
from app.ai.schemas import ChatRequest, ChatResult
from test_ai_contextual_chat import Process
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OTHER, OWNER, store
from test_ai_native_text import MODEL
from test_ai_server_chat import finished, job
from test_ai_activation import service  # noqa: F401 - shared synthetic fixture

MINUTE = 60_000
START = NOW - 60 * MINUTE


def module():
    assert importlib.util.find_spec("app.ai.compact_chat") is not None, (
        "trusted compact CHAT adapter is absent"
    )
    return importlib.import_module("app.ai.compact_chat")


def settings(**changes):
    return module().CompactChatSettings(
        **(
            dict(
                from_ms=START,
                until_ms=NOW,
                zone_id="UTC",
                token_budget=TokenBudget(
                    500_000, 100_000, "synthetic-byte-counter", len
                ),
            )
            | changes
        )
    )


def accepted(value, *, question="Explain the supplied observations"):
    policy = module().compact_chat_policy(MODEL)
    entry = job(route_revision=policy.route_revision)
    request = ChatRequest(question)
    work = JobWork(
        entry.id,
        request,
        entry.digest,
        entry.route_revision,
        entry.deadline_ms,
        policy,
        JobIdentity(OWNER, KEY),
        entry.created_ms,
    )
    value.accept_chat(entry, request, policy, now_ms=NOW)
    return entry, work


def point(minute, value=6.0, **changes):
    return record(
        recordId=str(UUID(int=minute + 100)),
        eventAtMs=START + minute * MINUTE,
        observedAtMs=NOW,
        quality="OK",
        data={"glucoseMmol": float(value)},
        **changes,
    )


def prepare(value, work, **options):
    return value.prepare_compact_chat(work, settings(**options), now_ms=NOW)


def receipt(value, entry, *, key=KEY, now=NOW + 2):
    return value.chat_context_receipt(OWNER, key, entry.id, now_ms=now)


def test_prepared_context_uses_only_bound_device_and_raw_extrema(tmp_path):
    value = store(tmp_path)
    value.append(
        OWNER, KEY, body([point(i, 3 if i == 4 else 6) for i in range(11)]), now_ms=NOW
    )
    value.append(OWNER, OTHER, body([point(0, 20)]), now_ms=NOW)
    entry, work = accepted(value)
    result = prepare(value, work)
    doc = json.loads(result.input_body)
    assert doc["question"] == work.request.text
    assert doc["glucose"]["inputRecordCount"] == 11
    assert doc["glucose"]["series"][0]["rows"][0][5] == 300
    assert doc["glucose"]["coveredMinutes"] == 10
    assert (
        OWNER not in result.input_body.decode()
        and KEY not in result.input_body.decode()
    )
    saved = json.loads(receipt(value, entry))
    assert (
        saved["manifest"]["frameDigest"]
        == hashlib.sha256(result.input_body).hexdigest()
    )
    assert saved["meaning"] == "PREPARED_NOT_DELIVERY_CONFIRMATION"


def test_receipt_is_encrypted_stable_after_restart_upload_and_final_answer(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value, question="Synthetic retained question")
    prepare(value, work)
    before = receipt(value, entry)
    value.append(OWNER, KEY, body([point(0)]), now_ms=NOW + 1)
    value.finish_chat(finished(entry), ChatResult("Synthetic answer"), now_ms=NOW + 2)
    assert receipt(store(tmp_path), entry) == before
    file = next(value.root.glob("*.sqlite"))
    assert (
        b"frameDigest" not in file.read_bytes()
        and b"Synthetic retained" not in file.read_bytes()
    )
    assert receipt(value, entry, key=OTHER) is None
    assert (
        value.chat_context(OWNER, KEY, now_ms=NOW + 2)["exchanges"][0]["answer"]
        == "Synthetic answer"
    )
    assert receipt(value, entry, now=NOW + RETENTION_MS) is None
    with pytest.raises(HistoryError, match="already_prepared"):
        value.prepare_compact_chat(work, settings(), now_ms=NOW + 2)


def test_latest_versions_and_authenticated_tombstones_do_not_resurrect_old_glucose(
    tmp_path,
):
    value = store(tmp_path)
    original = point(0, 12)
    value.append(OWNER, KEY, body([original, point(1)]), now_ms=NOW)
    value.append(
        OWNER,
        KEY,
        body([original | dict(revision=2, data=None, deleted=True)]),
        now_ms=NOW,
    )
    _, work = accepted(value)
    doc = json.loads(prepare(value, work).input_body)
    assert doc["glucose"]["inputRecordCount"] == 0 + 1
    assert doc["glucose"]["coveredMinutes"] == 0


def test_exact_focus_events_and_partial_nonglucose_summary_are_distinct(tmp_path):
    value = store(tmp_path)
    meal = record(
        kind="MEAL",
        eventAtMs=START + 5 * MINUTE,
        observedAtMs=NOW,
        data=dict(carbsGrams=24.0, foodType="MIXED", quantitySource="APPROXIMATE"),
    )
    value.append(OWNER, KEY, body([meal, point(0), point(10)]), now_ms=NOW)
    _, work = accepted(value)
    doc = json.loads(
        prepare(
            value, work, focus_windows=(FocusWindow(START, START + 10 * MINUTE, 1),)
        ).input_body
    )
    assert doc["events"][0]["record"]["data"]["carbsGrams"] == 24.0
    assert doc["suppliedFacts"]["nonglucose"]["coverage"]["countsByKind"]["MEAL"] == 1
    assert (
        doc["suppliedFacts"]["nonglucoseDetail"]
        == "BOUNDED_EXAMPLES_NOT_COMPLETE_TRAJECTORIES"
    )
    assert doc["profile"] == {"status": "NOT_SUPPLIED"}


@pytest.mark.parametrize(
    "field", ["request_digest", "route_revision", "accepted_ms", "deadline_ms"]
)
def test_accepted_job_binding_mismatch_cannot_prepare_or_replace_receipt(
    tmp_path, field
):
    value = store(tmp_path)
    entry, work = accepted(value)
    original = getattr(work, field)
    changed = original + 1 if isinstance(original, int) else original + "x"
    with pytest.raises(HistoryError, match="compact_chat_binding"):
        prepare(value, replace(work, **{field: changed}))
    assert receipt(value, entry) is None


def test_missing_archive_and_missing_token_accounting_fail_before_model(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)
    with pytest.raises(HistoryError, match="compact_chat_binding"):
        prepare(value, replace(work, job_id=str(UUID(int=1))))
    with pytest.raises(ValueError, match="invalid_compact_chat_settings"):
        settings(token_budget=None)
    assert receipt(value, entry) is None


def test_ciphertext_tamper_is_not_treated_as_missing_successful_history(tmp_path):
    value = store(tmp_path)
    value.append(OWNER, KEY, body([point(0)]), now_ms=NOW)
    entry, work = accepted(value)
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        db.execute("UPDATE history_records SET ciphertext=zeroblob(length(ciphertext))")
    with pytest.raises(Exception):
        prepare(value, work)
    assert receipt(value, entry) is None


def test_compact_worker_persists_exact_frame_before_native_turn(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)
    process = Process()
    worker = ContextualChatWorker(
        work, value, process, clock_ms=lambda: NOW, compact_context=settings()
    )

    async def scenario():
        assert await worker.run() == {"text": "Synthetic answer"}
        assert await worker.stop_and_confirm()
        with pytest.raises(ValueError, match="already_used"):
            await worker.run()

    asyncio.run(scenario())
    text = process.transport.sent[-1]["params"]["input"][0]["text"]
    saved = json.loads(receipt(value, entry))
    assert saved["manifest"]["frameDigest"] == hashlib.sha256(text.encode()).hexdigest()
    assert saved["manifest"]["tokenStatus"] == "VERIFIED_BY_SUPPLIED_COUNTER"
    assert len(process.started) == process.stops == 1
    assert "time-weighted" in process.started[0].instructions


def test_compact_failure_or_late_deadline_cannot_start_native_turn(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)
    process = Process()
    times = iter([NOW, work.deadline_ms])
    worker = ContextualChatWorker(
        work, value, process, clock_ms=lambda: next(times), compact_context=settings()
    )
    with pytest.raises(ValueError, match="deadline"):
        asyncio.run(worker.run())
    assert process.started == []
    assert receipt(value, entry) is not None


def test_corrupted_latest_tombstone_is_authenticated_before_omission(tmp_path):
    value = store(tmp_path)
    original = point(0)
    value.append(OWNER, KEY, body([original]), now_ms=NOW)
    value.append(
        OWNER,
        KEY,
        body([original | dict(revision=2, deleted=True, data=None)]),
        now_ms=NOW,
    )
    entry, work = accepted(value)
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        db.execute(
            "UPDATE history_records SET ciphertext=zeroblob(length(ciphertext)) "
            "WHERE revision=2"
        )
    with pytest.raises(HistoryError):
        prepare(value, work)
    assert receipt(value, entry) is None


def test_quota_failure_rolls_back_preparation_and_preserves_accepted_question(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)
    value.max_bytes = 512
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        prepare(value, work)
    assert receipt(value, entry) is None
    exchange = value.chat_context(OWNER, KEY, now_ms=NOW)["exchanges"][0]
    assert exchange["question"] == work.request.text
    assert exchange["state"] == "OUTCOME_NOT_RECORDED"


def test_receipt_capacity_does_not_silently_drop_manifest_detail(tmp_path, monkeypatch):
    value = store(tmp_path)
    entry, work = accepted(value)
    original = module().prepare_compact_context

    def inflated(*args, **kwargs):
        prepared = original(*args, **kwargs)
        return replace(prepared, manifest_body=encoded(dict(detail="x" * (17 * 1024))))

    monkeypatch.setattr(module(), "prepare_compact_context", inflated)
    with pytest.raises(HistoryError, match="compact_chat_unavailable"):
        prepare(value, work)
    assert receipt(value, entry) is None


def test_signed_compact_turn_keeps_receipt(service, tmp_path):  # noqa: F811
    from uuid import uuid4
    from app.ai.job_ledger import JobLedger
    from app.ai.job_service import AiJobService
    from app.ai_server import create_bound_ai_app
    from test_ai_job_transport import (
        NOW as API_NOW,
        MutableClock,
        activate,
        key_fingerprint,
        submit,
        wait_for_terminal,
    )

    value, clock = store(tmp_path), MutableClock()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    processes = []

    def factory(work):
        process = Process()
        processes.append(process)
        return ContextualChatWorker(
            work,
            value,
            process,
            clock_ms=clock,
            compact_context=settings(from_ms=API_NOW - 60 * MINUTE, until_ms=API_NOW),
        )

    jobs = AiJobService(
        service[1],
        service[0].proofs,
        ledger,
        factory,
        policy=module().compact_chat_policy(MODEL),
        clock_ms=clock,
        chat_archive=value,
    )
    try:
        with TestClient(
            create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)
        ) as client:
            owner, key, tokens = activate(client, service)
            fingerprint = key_fingerprint(key)
            value.append(
                owner,
                fingerprint,
                body([record(eventAtMs=API_NOW - MINUTE, observedAtMs=API_NOW)]),
                now_ms=API_NOW,
            )
            accepted_response = submit(
                client,
                key,
                tokens,
                b'{"text":"Sample question"}',
                request_id=str(uuid4()),
                deadline_ms=API_NOW + MINUTE,
            )
            assert accepted_response.status_code == 202
            job_id = accepted_response.json()["job_id"]
            response = wait_for_terminal(client, key, tokens, job_id)
            assert response.json()["state"] == "SUCCEEDED"
            assert processes[0].stops == 1
            text = processes[0].transport.sent[-1]["params"]["input"][0]["text"]
            saved = json.loads(
                value.chat_context_receipt(owner, fingerprint, job_id, now_ms=clock())
            )
            assert (
                saved["manifest"]["frameDigest"]
                == hashlib.sha256(text.encode()).hexdigest()
            )
            exchange = value.chat_context(owner, fingerprint, now_ms=clock())[
                "exchanges"
            ][0]
            assert (
                exchange["state"] == "SUCCEEDED"
                and exchange["answer"] == "Synthetic answer"
            )
    finally:
        ledger.engine.dispose()


@pytest.mark.parametrize(
    "changes",
    [
        dict(zone_id="unavailable-zone"),
        dict(token_budget=TokenBudget(10, 11, "synthetic-accounting", len)),
        dict(token_budget=TokenBudget(1000, 500, "synthetic-accounting", None)),
    ],
)
def test_bad_settings_are_fixed_errors_before_archive_reads(changes):
    with pytest.raises(ValueError, match="invalid_compact_chat_settings"):
        settings(**changes)


def test_storage_pressure_or_token_failure_never_commits_a_receipt(tmp_path):
    value = store(tmp_path)
    entry, work = accepted(value)
    value.min_free_bytes = 2**50
    with pytest.raises(HistoryError):
        prepare(value, work)
    assert receipt(value, entry) is None
    value.min_free_bytes = 0
    with pytest.raises(HistoryError, match="compact_context_budget_exceeded"):
        prepare(
            value, work, token_budget=TokenBudget(100, 99, "synthetic-accounting", len)
        )
    assert receipt(value, entry) is None


def test_row_capacity_is_explicit_and_does_not_write_partial_context(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    value.append(OWNER, KEY, body([point(0), point(1)]), now_ms=NOW)
    entry, work = accepted(value)
    monkeypatch.setattr(module(), "MAX_INPUT_RECORDS", 1)
    with pytest.raises(HistoryError, match="compact_chat_unavailable"):
        prepare(value, work)
    assert receipt(value, entry) is None


def test_cancelled_compact_preparation_is_joined_without_inference(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    _, work = accepted(value)
    entered, release = Event(), Event()
    original = value.prepare_compact_chat

    def slow(*args, **kwargs):
        entered.set()
        assert release.wait(3)
        return original(*args, **kwargs)

    monkeypatch.setattr(value, "prepare_compact_chat", slow)
    process = Process()
    worker = ContextualChatWorker(
        work, value, process, clock_ms=lambda: NOW, compact_context=settings()
    )

    async def scenario():
        pending = asyncio.create_task(worker.run())
        assert await asyncio.to_thread(entered.wait, 1)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        stopping = asyncio.create_task(worker.stop_and_confirm())
        await asyncio.sleep(0)
        assert not stopping.done()
        release.set()
        assert await stopping

    try:
        asyncio.run(scenario())
    finally:
        release.set()
    assert process.started == []
