import sqlite3
from uuid import uuid4

import pytest

from app.ai.history_context import HistoryContext, MAX_CONTEXT_BYTES
from app.ai.history_contract import DAY_MS, RETENTION_MS, HistoryError, encoded
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OTHER, OWNER, store


def snapshot(value, **changes):
    return value.context_snapshot(OWNER, KEY, now_ms=changes.get("now_ms", NOW))


def test_empty_context_is_not_a_claim_of_complete_device_history(tmp_path):
    result = snapshot(store(tmp_path))
    assert result["coverage"]["eligibleRecords"] == 0
    assert result["coverage"]["expectedSamplesUnknown"] is True
    assert result["provenance"] == "DEVICE_DECLARED"
    assert result["recentRecords"] == result["glucoseCohorts"] == []


def test_context_uses_latest_revision_no_deleted_expired_or_future_data(tmp_path):
    value = store(tmp_path)
    old = record(data={"glucoseMmol": 5.0})
    deleted = record()
    expires = record(eventAtMs=NOW - RETENTION_MS + 1)
    value.append(OWNER, KEY, body([old, deleted, expires]), now_ms=NOW)
    value.append(OWNER, KEY, body([
        old | dict(revision=2, data={"glucoseMmol": 7.0}),
        deleted | dict(revision=2, deleted=True, data=None)]), now_ms=NOW)
    result = snapshot(value, now_ms=NOW + 1)
    assert result["coverage"]["eligibleRecords"] == 1
    assert result["glucoseCohorts"][0]["meanMmol"] == 7.0
    assert result["recentRecords"][0]["record"]["revision"] == 2
    assert snapshot(value, now_ms=NOW - 1)["coverage"]["eligibleRecords"] == 0


def test_owner_device_isolation_and_declared_server_is_not_trusted(tmp_path):
    value = store(tmp_path)
    value.append(OWNER, OTHER, body(), now_ms=NOW)
    value.append(str(uuid4()), KEY, body(), now_ms=NOW)
    assert snapshot(value)["coverage"]["eligibleRecords"] == 0
    value.append(OWNER, KEY, body([record(source="SERVER")]), now_ms=NOW)
    assert snapshot(value)["provenance"] == "DEVICE_DECLARED"


def test_sparse_quality_and_calibration_contexts_are_not_merged(tmp_path):
    value = store(tmp_path)
    rows = [record(context={"calibrationModelId": "first"}, quality="OK", data={"glucoseMmol": 4.0}),
            record(context={"calibrationModelId": "first"}, quality="OK", data={"glucoseMmol": 8.0}),
            record(context={"calibrationModelId": "second"}, quality="OK"),
            record(context={"calibrationModelId": "first"}, quality="SUSPECT"), record()]
    value.append(OWNER, KEY, body(rows), now_ms=NOW)
    result = snapshot(value)
    assert len(result["glucoseCohorts"]) == 4
    pair = next(group for group in result["glucoseCohorts"] if group["count"] == 2)
    assert pair["minMmol"] == 4.0 and pair["maxMmol"] == 8.0 and pair["meanMmol"] == 6.0
    assert result["coverage"]["countsByQuality"] == {"OK": 3, "SUSPECT": 1, "UNKNOWN": 1}


def test_latest_examples_use_event_time_not_backfill_order(tmp_path):
    value = store(tmp_path)
    rows = [record(eventAtMs=NOW - index * 1000) for index in range(1, 30)]
    value.append(OWNER, KEY, body(rows), now_ms=NOW)
    result = snapshot(value)
    assert result["coverage"]["eligibleRecords"] == 29
    assert result["omitted"]["recentByKind"]["GLUCOSE"] == 17
    assert [item["ageMs"] for item in result["recentRecords"]] == list(range(1000, 13000, 1000))


def test_bounded_context_reports_omissions_and_keeps_all_counts():
    result = HistoryContext(now_ms=NOW, snapshot=5000)
    for index in range(5000):
        row = record(eventAtMs=NOW - (index % 365) * DAY_MS,
                     context={"sensorSessionId": "sample-" + str(index)})
        result.add(dict(sequence=index + 1, receivedAtMs=NOW, record=row))
    document = result.finish()
    assert document["coverage"]["eligibleRecords"] == 5000
    included = sum(group["count"] for group in document["glucoseCohorts"])
    assert included + document["omitted"]["glucoseRecords"] == 5000
    assert len(encoded(document)) <= MAX_CONTEXT_BYTES
    assert len(document["recentRecords"]) <= 12


def test_corrupt_history_fails_instead_of_returning_partial_context(tmp_path):
    value = store(tmp_path)
    value.append(OWNER, KEY, body([record(), record()]), now_ms=NOW)
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute("UPDATE history_records SET ciphertext=? WHERE sequence=2", (b"broken",))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        snapshot(value)


def test_reader_budget_fails_without_fabricated_totals(tmp_path, monkeypatch):
    value = store(tmp_path)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    clock = iter([0, 0, 10])
    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: next(clock, 10))
    with pytest.raises(HistoryError):
        snapshot(value)
