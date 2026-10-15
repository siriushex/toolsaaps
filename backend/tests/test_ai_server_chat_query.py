import base64
import sqlite3
from dataclasses import replace
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS, encoded
from app.ai.job_policy import CHAT_POLICY
from app.ai.schemas import ChatRequest, ChatResult
from app.ai.server_chat_query import CURSOR_TTL_MS, MAX_RESPONSE_BYTES, decode_query
from test_ai_history_contract import NOW, body
from test_ai_history_store import KEY, OTHER, OWNER, store
from test_ai_server_chat import job


def save(history, *, owner=OWNER, key=KEY, now=NOW, question="Synthetic question", answer="Synthetic answer"):
    entry = job(owner_id=owner, key_hash=key, created_ms=now, deadline_ms=now + 60_000)
    history.accept_chat(entry, ChatRequest(question), CHAT_POLICY, now_ms=now)
    if answer is not None:
        history.finish_chat(replace(entry, state="SUCCEEDED", started_ms=now, finished_ms=now + 1),
                            ChatResult(answer), now_ms=now + 1)
    return entry


def request(**changes):
    return dict(fromMs=NOW - 1000, untilMs=NOW + 10, cursor=None, limit=20) | changes


def read(history, query=None, *, now=NOW + 10, owner=OWNER, key=KEY):
    return history.query_chats(owner, key, encoded(request() if query is None else query), now_ms=now)


@pytest.mark.parametrize("changes", [
    {"limit": 0}, {"limit": 21}, {"limit": True}, {"limit": "1"},
    {"fromMs": NOW + 10}, {"untilMs": NOW + 12}, {"fromMs": NOW - RETENTION_MS - 1},
    {"cursor": "x"}, {"cursor": "x" * 1025}, {"cursor": "?" * 40},
    {"ownerId": OWNER}, {"deviceId": KEY},
])
def test_closed_query_contract_rejects_bad_values(changes):
    with pytest.raises(HistoryError) as error:
        decode_query(encoded(request(**changes)), now_ms=NOW + 10)
    assert error.value.status == 400


@pytest.mark.parametrize("raw", [b"{}", b"x" * 4097,
    b'{"fromMs":1,"fromMs":2,"untilMs":3,"cursor":null,"limit":1}',
    b'{"fromMs":NaN,"untilMs":3,"cursor":null,"limit":1}', b'\xff'])
def test_query_duplicate_nan_size_and_encoding_are_closed(raw):
    with pytest.raises(HistoryError):
        decode_query(raw, now_ms=NOW)


def test_complete_text_survives_result_cache_window_and_internal_fields_are_hidden(tmp_path):
    history = store(tmp_path)
    entry = save(history, question="q" * 4096, answer="a" * 8192)
    result = read(store(tmp_path), now=NOW + 1_000_000)
    assert result["origin"] == "SERVER_ARCHIVE" and result["snapshotScope"] == "MEMBERSHIP_ONLY"
    assert result["hasMore"] is False and result["nextCursor"] is None
    row = result["records"][0]
    assert row["jobId"] == entry.id and row["question"] == "q" * 4096 and row["answer"] == "a" * 8192
    assert row["updatedAtMs"] == NOW + 1 and row["state"] == "SUCCEEDED"
    for field in ("requestDigest", "instructionsDigest", "ownerId", "key_hash", "sessionId"):
        assert field not in row
    assert read(history, key=OTHER)["records"] == []
    assert read(history, owner=str(uuid4()))["records"] == []


def test_read_does_not_create_or_migrate_store(tmp_path):
    history = store(tmp_path)
    assert read(history)["records"] == []
    assert not list(history.root.glob("*.sqlite"))
    history.append(OWNER, KEY, body(), now_ms=NOW)
    assert read(history)["records"] == []
    with sqlite3.connect(next(history.root.glob("*.sqlite"))) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 1


def test_pagination_freezes_membership_across_same_clock_insert_and_restart(tmp_path):
    history = store(tmp_path)
    originals = [save(history) for _ in range(3)]
    first = read(history, request(limit=1))
    assert first["hasMore"]
    later = save(history, now=NOW - 1)
    second = read(store(tmp_path), request(cursor=first["nextCursor"], limit=1))
    third = read(history, request(cursor=second["nextCursor"], limit=1))
    assert [page["records"][0]["jobId"] for page in (first, second, third)] == [entry.id for entry in originals]
    assert third["hasMore"] is False and third["nextCursor"] is None
    assert later.id in [row["jobId"] for row in read(history)["records"]]


def test_outcomes_are_current_per_page_not_claimed_as_historical_snapshot(tmp_path):
    history = store(tmp_path)
    save(history)
    pending = save(history, answer=None)
    first = read(history, request(limit=1))
    history.finish_chat(replace(pending, state="FAILED", started_ms=NOW, finished_ms=NOW + 15),
                        None, now_ms=NOW + 15)
    second = read(history, request(cursor=first["nextCursor"], limit=1), now=NOW + 20)
    assert second["records"][0]["state"] == "FAILED"
    assert second["records"][0]["updatedAtMs"] == NOW + 15
    assert second["generatedAtMs"] == NOW + 20 and second["snapshotScope"] == "MEMBERSHIP_ONLY"


def test_response_byte_bound_pages_without_truncating_escaped_messages(tmp_path):
    history = store(tmp_path)
    for _ in range(3):
        save(history, question="\u0001" * 4096, answer="\u0002" * 8192)
    query = request()
    seen = []
    for _ in range(3):
        page = read(history, query)
        assert len(encoded(page)) <= MAX_RESPONSE_BYTES
        assert len(page["records"]) == 1
        row = page["records"][0]
        assert row["question"] == "\u0001" * 4096 and row["answer"] == "\u0002" * 8192
        seen.append(row["jobId"])
        query = request(cursor=page["nextCursor"])
    assert len(set(seen)) == 3 and page["hasMore"] is False


def test_cursor_is_bound_to_device_owner_window_and_authentication_tag(tmp_path):
    history = store(tmp_path)
    save(history)
    save(history)
    cursor = read(history, request(limit=1))["nextCursor"]
    raw = bytearray(base64.urlsafe_b64decode(cursor))
    raw[-1] ^= 1
    tampered = base64.urlsafe_b64encode(raw).decode()
    for query, scope in [
        (request(cursor=tampered), {}),
        (request(cursor=cursor, fromMs=NOW - 999), {}),
        (request(cursor=cursor, untilMs=NOW + 9), {}),
        (request(cursor=cursor), dict(key=OTHER)),
        (request(cursor=cursor), dict(owner=str(uuid4()))),
    ]:
        with pytest.raises(HistoryError) as error:
            read(history, query, **scope)
        assert error.value.status == 400
    assert len(list(history.root.glob("*.sqlite"))) == 1


def test_cursor_expiry_does_not_extend_when_turning_pages(tmp_path):
    history = store(tmp_path)
    for _ in range(3):
        save(history)
    first = read(history, request(limit=1))
    second = read(history, request(limit=1, cursor=first["nextCursor"]), now=NOW + CURSOR_TTL_MS)
    with pytest.raises(HistoryError, match="chat_cursor_expired") as error:
        read(history, request(cursor=second["nextCursor"]), now=NOW + 10 + CURSOR_TTL_MS)
    assert error.value.status == 409


def test_expired_rows_remain_hidden_as_retention_edge_moves_between_pages(tmp_path):
    history = store(tmp_path)
    start = NOW - RETENTION_MS
    for i in range(1, 4):
        save(history, now=start + i)
    query = request(fromMs=start, untilMs=NOW + 1, limit=1)
    first = read(history, query, now=NOW)
    second = read(history, query | dict(cursor=first["nextCursor"]), now=NOW + 2)
    assert [row["acceptedAtMs"] for row in second["records"]] == [start + 3]
    assert not second["hasMore"]


def test_purged_anchor_with_reused_rowid_refuses_stale_cursor(tmp_path):
    history = store(tmp_path)
    start = NOW - RETENTION_MS
    for i in range(1, 4):
        save(history, now=start + i)
    query = request(fromMs=start, untilMs=NOW + 1, limit=1)
    first = read(history, query, now=NOW)
    history.purge(now_ms=NOW + 3)
    for _ in range(3):
        save(history, now=NOW + 4)
    with pytest.raises(HistoryError, match="chat_cursor_stale"):
        read(history, query | dict(cursor=first["nextCursor"]), now=NOW + 5)


def test_corrupt_anchor_or_row_never_returns_partial_success(tmp_path):
    history = store(tmp_path)
    first = save(history)
    save(history)
    with sqlite3.connect(next(history.root.glob("*.sqlite"))) as db:
        db.execute("UPDATE server_chat SET ciphertext=zeroblob(length(ciphertext)) WHERE job_id=?", (first.id,))
    with pytest.raises(HistoryError) as error:
        read(history)
    assert error.value.status == 503
