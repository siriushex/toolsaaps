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


def test_verified_cache_reuses_counts_but_refreshes_time_and_copies_output(tmp_path, monkeypatch):
    value = store(tmp_path)
    value.append(OWNER, KEY, body([record(), record()]), now_ms=NOW)
    first = snapshot(value)
    expected = first["recentRecords"][0]["record"]["data"]["glucoseMmol"]
    first["coverage"]["eligibleRecords"] = -1
    first["recentRecords"][0]["record"]["data"]["glucoseMmol"] = -1

    def unexpected_decryption(*args, **kwargs):
        pytest.fail("unchanged authenticated envelopes should reuse the bounded aggregate")

    monkeypatch.setattr(value, "_read_plain", unexpected_decryption)
    cached = snapshot(value, now_ms=NOW + 100)
    assert cached["coverage"]["eligibleRecords"] == 2
    assert cached["recentRecords"][0]["record"]["data"]["glucoseMmol"] == expected
    assert cached["generatedAtMs"] == NOW + 100
    assert cached["fromMs"] == NOW + 100 - RETENTION_MS + 1
    assert cached["untilMs"] == NOW + 101
    assert cached["recentRecords"][0]["ageMs"] == 1100


@pytest.mark.parametrize("field,corrupted", [
    ("ciphertext", b"broken"), ("nonce", b"x" * 12), ("nonce", 1),
    ("digest", "0" * 64), ("quality", "OK"), ("observed_ms", NOW - 1),
    ("expires_ms", NOW + RETENTION_MS), ("revision", 2),
    ("received_ms", NOW - 1), ("event_ms", NOW - 2000), ("kind", "FORECAST"),
    ("source", "USER"), ("record_id", "a02a155c-34e8-42f4-8b10-e0b90393ee97"),
])
def test_warm_cache_cannot_hide_corrupt_record_at_unchanged_highwater(tmp_path, field, corrupted):
    value = store(tmp_path)
    value.append(OWNER, KEY, body([record(), record()]), now_ms=NOW)
    before = snapshot(value)
    file = next(value.root.glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute("UPDATE history_records SET " + field + "=? WHERE sequence=1", (corrupted,))
        assert value._highwater(db) == before["snapshotSequence"]
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        snapshot(value)


@pytest.mark.parametrize("warm", [False, True])
def test_unselected_non_glucose_record_still_requires_ciphertext_integrity(tmp_path, warm):
    value = store(tmp_path)
    rows = [record(kind="FORECAST", source="COPILOT", eventAtMs=NOW - (30 - index) * 1000,
                   data=dict(horizonMinutes=30, valueMmol=7.0, ciLowMmol=4.0,
                             ciHighMmol=10.0, modelVersion="synthetic-v1")) for index in range(30)]
    value.append(OWNER, KEY, body(rows), now_ms=NOW)
    if warm:
        result = snapshot(value)
        assert result["omitted"]["recentByKind"] == {"FORECAST": 18}
        assert all(item["sequence"] != 1 for item in result["recentRecords"])
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        db.execute("UPDATE history_records SET ciphertext=? WHERE sequence=1", (b"broken",))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        snapshot(value)


def test_other_writer_revisions_tombstones_and_expiry_invalidate_aggregate(tmp_path):
    value, writer = store(tmp_path), store(tmp_path)
    changed, deleted = record(), record()
    expires = record(eventAtMs=NOW - RETENTION_MS + 1)
    value.append(OWNER, KEY, body([changed, deleted, expires]), now_ms=NOW)
    assert snapshot(value)["coverage"]["eligibleRecords"] == 3
    writer.append(OWNER, KEY, body([
        changed | dict(revision=2, data={"glucoseMmol": 10.0}),
        deleted | dict(revision=2, deleted=True, data=None),
    ]), now_ms=NOW)
    result = snapshot(value, now_ms=NOW + 1)
    assert result["snapshotSequence"] == 5
    assert result["coverage"]["eligibleRecords"] == 1
    assert result["glucoseCohorts"][0]["meanMmol"] == 10.0
    assert result["recentRecords"][0]["record"]["revision"] == 2
    assert snapshot(value, now_ms=NOW - 1)["coverage"]["eligibleRecords"] == 0
    assert snapshot(value, now_ms=NOW + 2)["coverage"]["eligibleRecords"] == 1
    writer.purge(now_ms=NOW + RETENTION_MS)
    assert snapshot(value, now_ms=NOW + RETENTION_MS)["coverage"]["eligibleRecords"] == 0


def test_cache_never_skips_reader_budget_or_row_quota(tmp_path, monkeypatch):
    value = store(tmp_path)
    value.append(OWNER, KEY, body([record(), record()]), now_ms=NOW)
    snapshot(value)
    value.max_rows = 1
    with pytest.raises(HistoryError, match="history_context_unavailable"):
        snapshot(value)
    value.max_rows = 1_000_000
    clock = iter([0, 0, 10])
    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: next(clock, 10))
    with pytest.raises(HistoryError):
        snapshot(value)


def test_authenticated_projection_matches_full_decode_for_all_counts_and_examples(tmp_path):
    value = store(tmp_path)
    rows = []
    for index in range(90):
        kind = "GLUCOSE" if index % 3 == 0 else "FORECAST"
        data = {"glucoseMmol": 4.0 + index % 7} if kind == "GLUCOSE" else dict(
            horizonMinutes=30, valueMmol=7.0, ciLowMmol=4.0, ciHighMmol=10.0,
            modelVersion="synthetic-v1")
        rows.append(record(kind=kind, data=data, eventAtMs=NOW - (index % 23) * 1000,
                           quality="OK" if index % 2 else "UNKNOWN",
                           context={"sensorSessionId": "session-" + str(index % 4)}))
    for start in range(0, len(rows), 30):
        value.append(OWNER, KEY, body(rows[start:start + 30]), now_ms=NOW)
    reference = HistoryContext(now_ms=NOW, snapshot=len(rows))
    for sequence, row in enumerate(rows, 1):
        reference.add(dict(sequence=sequence, receivedAtMs=NOW, record=row))
    expected = reference.finish()
    assert snapshot(value) == expected
    assert snapshot(value) == expected


def test_cohort_keys_preserve_null_missing_and_context_order():
    value = HistoryContext(now_ms=NOW, snapshot=4)
    contexts = [None, {"calibrationModelId": None},
                {"sensorSessionId": "sample", "calibrationModelId": "model"},
                {"calibrationModelId": "model", "sensorSessionId": "sample"}]
    for sequence, context in enumerate(contexts, 1):
        value.add(dict(sequence=sequence, receivedAtMs=NOW, record=record(context=context)))
    result = value.finish()
    assert sorted(group["count"] for group in result["glucoseCohorts"]) == [1, 1, 2]


def test_year_context_query_avoids_ciphertext_temp_sort_and_keeps_revision_index(tmp_path):
    from app.ai.history_store import CONTEXT_META, CONTEXT_ROWS, META_FIELDS

    value = store(tmp_path)
    value.append(OWNER, KEY, body(), now_ms=NOW)
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        db.row_factory = sqlite3.Row
        query = "SELECT r.*," + CONTEXT_META + " AS aad_values" + CONTEXT_ROWS
        args = (NOW - RETENTION_MS, NOW, NOW, NOW)
        row = db.execute(query, args).fetchone()
        assert encoded([row[key] for key in META_FIELDS]) == row["aad_values"].encode()
        plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + query, args)]
        assert not any("TEMP B-TREE" in item for item in plan)
        assert any("history_record_versions" in item for item in plan)
