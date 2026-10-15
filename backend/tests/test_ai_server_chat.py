import sqlite3
from dataclasses import replace
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS, encoded
from app.ai.history_store import HistoryStore
from app.ai.job_ledger import Job
from app.ai.job_policy import CHAT_POLICY
from app.ai.schemas import ChatRequest, ChatResult
from app.ai.server_chat import MAX_CONTEXT_BYTES, MAX_ENTRY_BYTES
from test_ai_history_contract import NOW, body, query, record
from test_ai_history_store import KEY, OTHER, OWNER, store


def job(**changes):
    value = Job(str(uuid4()), OWNER, str(uuid4()), KEY, str(uuid4()), "CHAT",
                "c3" * 32, CHAT_POLICY.route_revision, "QUEUED", NOW, NOW + 60_000, None, None)
    return replace(value, **changes)


def finished(value, state="SUCCEEDED"):
    return replace(value, state=state, started_ms=NOW, finished_ms=NOW + 1)


def accept(value, entry, text="Synthetic private question"):
    value.accept_chat(entry, ChatRequest(text), CHAT_POLICY, now_ms=entry.created_ms)


def context(value, *, owner=OWNER, key=KEY, now=NOW + 2, **options):
    return value.chat_context(owner, key, now_ms=now, **options)


def test_archive_restart_encryption_and_internal_provenance(tmp_path):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    value.finish_chat(finished(entry), ChatResult("Synthetic private answer"), now_ms=NOW + 1)
    result = context(store(tmp_path))
    assert result["origin"] == "SERVER_ARCHIVE"
    exchange = result["exchanges"][0]
    assert exchange["question"] == "Synthetic private question"
    assert exchange["answer"] == "Synthetic private answer"
    assert exchange["state"] == "SUCCEEDED"
    assert context(value, key=OTHER)["exchanges"] == []
    assert context(value, owner=str(uuid4()))["exchanges"] == []
    file = next(value.root.glob("*.sqlite"))
    assert b"Synthetic private" not in file.read_bytes()
    assert file.stat().st_mode & 0o077 == 0
    assert value.query(OWNER, KEY, encoded(query()), now_ms=NOW + 2)["records"] == []


def test_additive_migration_preserves_observations_and_read_does_not_migrate(tmp_path):
    value = store(tmp_path)
    sample = record()
    value.append(OWNER, KEY, body([sample]), now_ms=NOW)
    assert context(value)["exchanges"] == []
    file = next(value.root.glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 1
    accept(value, job())
    with sqlite3.connect(file) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 2
    assert value.query(OWNER, KEY, encoded(query()), now_ms=NOW)["records"][0]["record"] == sample
    value.append(OWNER, KEY, body([record()]), now_ms=NOW)
    assert value.context_snapshot(OWNER, KEY, now_ms=NOW)["coverage"]["eligibleRecords"] == 2


def test_device_cannot_inject_server_conversation(tmp_path):
    value = store(tmp_path)
    fake = record(kind="CHAT", source="SERVER", data=dict(
        conversationId=str(uuid4()), role="ASSISTANT", text="Synthetic claimed answer", origin="DEVICE_ARCHIVE"))
    value.append(OWNER, KEY, body([fake]), now_ms=NOW)
    assert context(value)["exchanges"] == []
    fake["data"]["origin"] = "SERVER_ARCHIVE"
    with pytest.raises(HistoryError):
        value.append(OWNER, KEY, body([fake]), now_ms=NOW)


def test_idempotency_does_not_downgrade_or_extend_finished_entry(tmp_path):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    accept(value, entry)
    answer = ChatResult("Synthetic response")
    value.finish_chat(finished(entry), answer, now_ms=NOW + 1)
    value.finish_chat(finished(entry), answer, now_ms=NOW + 2)
    accept(value, entry)
    assert len(context(value)["exchanges"]) == 1
    assert context(value)["exchanges"][0]["state"] == "SUCCEEDED"
    with pytest.raises(HistoryError, match="conflict"):
        accept(value, entry, "Different question")
    with pytest.raises(HistoryError, match="conflict"):
        value.finish_chat(finished(entry), ChatResult("Different answer"), now_ms=NOW + 2)
    with pytest.raises(HistoryError, match="conflict"):
        value.finish_chat(finished(entry, "FAILED"), None, now_ms=NOW + 2)


@pytest.mark.parametrize("field,value", [("digest", "e5" * 32), ("request_id", str(uuid4())),
                                         ("created_ms", NOW - 1)])
def test_outcome_requires_immutable_binding(tmp_path, field, value):
    history, entry = store(tmp_path), job()
    accept(history, entry)
    with pytest.raises(HistoryError):
        history.finish_chat(replace(finished(entry), **{field: value}),
                            ChatResult("Synthetic response"), now_ms=NOW + 2)


@pytest.mark.parametrize("state", ["QUEUED", "RUNNING", "CANCEL_REQUESTED"])
def test_unsettled_outcome_has_no_answer(tmp_path, state):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    with pytest.raises(HistoryError):
        value.finish_chat(finished(entry, state), ChatResult("Not publishable"), now_ms=NOW + 2)
    result = context(store(tmp_path))["exchanges"][0]
    assert result["state"] == "OUTCOME_NOT_RECORDED" and result["answer"] is None


@pytest.mark.parametrize("state", ["FAILED", "CANCELLED", "EXPIRED", "UNKNOWN"])
def test_failed_cancelled_expired_unknown_outcome_is_preserved_without_text(tmp_path, state):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    with pytest.raises(HistoryError):
        value.finish_chat(finished(entry, state), ChatResult("Not publishable"), now_ms=NOW + 2)
    value.finish_chat(finished(entry, state), None, now_ms=NOW + 2)
    assert context(value)["exchanges"][0]["state"] == state
    assert context(value)["exchanges"][0]["answer"] is None


def test_success_requires_result_and_before_deadline(tmp_path):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    for terminal, answer in [(finished(entry), None),
                             (replace(finished(entry), finished_ms=entry.deadline_ms), ChatResult("Late"))]:
        with pytest.raises(HistoryError):
            value.finish_chat(terminal, answer, now_ms=entry.deadline_ms)


def test_original_retention_cutoff_and_purge(tmp_path):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    value.finish_chat(finished(entry), ChatResult("Synthetic answer"), now_ms=NOW + 2)
    assert context(value, now=NOW + RETENTION_MS - 1)["exchanges"]
    assert context(value, now=NOW + RETENTION_MS)["exchanges"] == []
    value.purge(now_ms=NOW + RETENTION_MS)
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        assert db.execute("SELECT count(*) FROM server_chat").fetchone()[0] == 0


@pytest.mark.parametrize("column,change", [("created_ms", "created_ms+1"),
                                          ("budget_bytes", "budget_bytes-1"),
                                          ("ciphertext", "zeroblob(length(ciphertext))")])
def test_ciphertext_and_index_metadata_tampering_fail_closed(tmp_path, column, change):
    value, entry = store(tmp_path), job()
    accept(value, entry)
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        db.execute(f"UPDATE server_chat SET {column}={change}")
    with pytest.raises(HistoryError, match="storage_unavailable"):
        context(value)
    with pytest.raises(HistoryError):
        accept(value, entry)


def test_wrong_key_unknown_schema_and_migration_failure_are_closed(tmp_path, monkeypatch):
    value = store(tmp_path)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    from app.ai import server_chat
    monkeypatch.setattr(server_chat, "SCHEMA", server_chat.SCHEMA + ("INVALID SQL",))
    with pytest.raises(HistoryError):
        accept(value, job())
    file = next(value.root.glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 1
        assert not db.execute("SELECT 1 FROM sqlite_master WHERE name='server_chat'").fetchall()
    wrong = HistoryStore(value.root, master_key=b"z" * 32, min_free_bytes=0)
    with pytest.raises(HistoryError):
        context(wrong)
    with sqlite3.connect(file) as db:
        db.execute("PRAGMA user_version=3")
    with pytest.raises(HistoryError):
        context(value)


def test_quota_reserves_answer_capacity_and_rolls_back_request(tmp_path):
    value, entry = store(tmp_path, max_bytes=MAX_ENTRY_BYTES, max_rows=1), job()
    accept(value, entry, "\u0001" * 4096)
    with pytest.raises(HistoryError, match="capacity"):
        accept(value, job())
    value.finish_chat(finished(entry), ChatResult("\u0002" * 8192), now_ms=NOW + 2)
    assert len(context(value)["exchanges"]) == 1
    assert context(value)["exchanges"][0]["answerTruncated"] is True
    with pytest.raises(HistoryError, match="capacity"):
        value.append(OWNER, KEY, body(), now_ms=NOW)


def test_bounded_context_excludes_current_and_future_without_truncating_storage(tmp_path):
    value = store(tmp_path)
    entries = []
    for i in range(15):
        entry = job(created_ms=NOW + i)
        entries.append(entry)
        accept(value, entry, "x" * 4096)
    recent = context(value, now=NOW + 100, exclude_job_id=entries[-1].id)
    assert recent["hasMore"] is True
    assert len(encoded(recent)) <= MAX_CONTEXT_BYTES
    assert all(item["questionTruncated"] for item in recent["exchanges"])
    assert entries[-1].id not in encoded(recent).decode()
    assert len(context(value, now=NOW)["exchanges"]) == 1
    assert context(value, now=NOW - 1)["exchanges"] == []


def test_missing_archive_fails_instead_of_inventing_question(tmp_path):
    with pytest.raises(HistoryError):
        store(tmp_path).finish_chat(finished(job()), ChatResult("Synthetic answer"), now_ms=NOW + 2)
