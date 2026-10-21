import hashlib
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError
from test_ai_daily_glucose import DAY, END, NOW, START, build, module, point, target
from test_ai_history_contract import body, record
from test_ai_history_store import KEY, MASTER, OTHER, OWNER, store


def append(value, rows):
    value.append(
        OWNER, KEY, body([r.model_dump(exclude_unset=True) for r in rows]), now_ms=NOW
    )


def daily(value, owner=OWNER, device=KEY, **options):
    assert callable(getattr(value, "daily_glucose_facts", None)), (
        "protected daily facts reader is absent"
    )
    params = dict(report_date=DAY, zone_id="UTC", ranges=(target(),), now_ms=NOW)
    return value.daily_glucose_facts(owner, device, **(params | options))


def test_empty_and_populated_history_are_bound_to_owner_device_and_sequence(tmp_path):
    value = store(tmp_path)
    assert daily(value).snapshotSequence == 0
    rows = [point(0), point(10)]
    append(value, rows)
    result = daily(value)
    binding = hashlib.sha256((OWNER + ":" + KEY).encode()).hexdigest()
    assert (
        result.model_dump()
        == build(rows, snapshot_sequence=2, source_binding=binding).model_dump()
    )
    assert daily(value, device=OTHER).inputRecordCount == 0
    assert daily(value, owner=str(uuid4())).inputRecordCount == 0


def test_latest_revisions_and_deletions_are_used_without_rewriting_history(tmp_path):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    changed = rows[0].model_copy(update={"revision": 2, "data": {"glucoseMmol": 10.0}})
    append(value, [changed])
    result = daily(value)
    assert result.cohorts[0].metrics.meanMmol == 8.0
    assert any(r.revision == 2 for e in result.episodes for r in e.evidence)
    append(
        value,
        [changed.model_copy(update={"revision": 3, "deleted": True, "data": None})],
    )
    assert daily(value).coveredMinutes == 0.0


def test_reader_limits_source_window_and_kind(tmp_path):
    value = store(tmp_path)
    append(
        value, [point(-16), point(-5), point(5), point(1435), point(1445), point(1456)]
    )
    value.append(
        OWNER,
        KEY,
        body(
            [
                record(
                    eventAtMs=START,
                    observedAtMs=NOW,
                    kind="THERAPY",
                    data={"eventType": "BOLUS", "insulinUnits": 3.0},
                )
            ]
        ),
        now_ms=NOW,
    )
    result = daily(value)
    assert result.inputRecordCount == 4
    assert result.coveredMinutes == 10.0 and result.scope == "GLUCOSE_ONLY"


def test_corrupt_selected_ciphertext_fails_without_partial_metrics(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute(
            "UPDATE history_records SET ciphertext=? WHERE sequence=2", (b"broken",)
        )
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        daily(value)


@pytest.mark.parametrize(
    "field,corrupt",
    [("digest", "0" * 64), ("quality", "SUSPECT"), ("nonce", b"z" * 12)],
)
def test_selected_metadata_and_payload_still_require_integrity(
    tmp_path, field, corrupt
):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute(
            "UPDATE history_records SET " + field + "=? WHERE sequence=1", (corrupt,)
        )
    with pytest.raises(HistoryError):
        daily(value)


def test_wrong_key_fails_and_successful_read_does_not_mutate_database(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    file = next((tmp_path / "history").glob("*.sqlite"))
    before = hashlib.sha256(file.read_bytes()).digest()
    daily(value)
    assert hashlib.sha256(file.read_bytes()).digest() == before
    wrong = type(value)(
        tmp_path / "history", master_key=bytes(reversed(MASTER)), min_free_bytes=0
    )
    with pytest.raises(HistoryError):
        daily(wrong)


def test_row_and_wall_time_budget_never_become_partial_success(tmp_path, monkeypatch):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    monkeypatch.setattr(module(), "MAX_INPUT_RECORDS", 1)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        daily(value)
    monkeypatch.setattr(module(), "MAX_INPUT_RECORDS", 50_000)
    ticks = iter([0, 0, 10])
    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: next(ticks, 10))
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        daily(value)


def test_invalid_or_unfinished_requests_fail_before_opening_storage(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("invalid request touched storage")
    )
    for options in (
        dict(now_ms=END - 1),
        dict(report_date="invalid"),
        dict(ranges=(target(), target())),
        dict(report_date="2024-01-01"),
    ):
        with pytest.raises(ValueError):
            daily(value, **options)


def test_concurrent_writer_waits_until_original_read_snapshot_is_finished(
    tmp_path, monkeypatch
):
    value, writer = store(tmp_path), store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    original = module().build_daily_glucose
    started = threading.Event()
    future = None

    def write():
        started.set()
        append(
            writer,
            [rows[0].model_copy(update={"revision": 2, "data": {"glucoseMmol": 10.0}})],
        )

    with ThreadPoolExecutor(max_workers=1) as pool:

        def collect(records, **options):
            nonlocal future
            if records and future is None:
                future = pool.submit(write)
                assert started.wait(1)
                assert not future.done()
            return original(records, **options)

        monkeypatch.setattr(module(), "build_daily_glucose", collect)
        result = daily(value)
        assert (
            result.snapshotSequence == 2 and result.cohorts[0].metrics.meanMmol == 6.0
        )
        future.result(timeout=5)
    assert daily(value).snapshotSequence == 3


def test_identical_records_from_different_devices_have_distinct_source_binding(
    tmp_path,
):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    value.append(
        OWNER, OTHER, body([r.model_dump(exclude_unset=True) for r in rows]), now_ms=NOW
    )
    first, other = daily(value), daily(value, device=OTHER)
    assert first.sourceBinding != other.sourceBinding
    assert first.sourceDigest != other.sourceDigest


def test_recorded_sensor_change_breaks_unknown_session_interpolation(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(4), point(6), point(10)])
    value.append(
        OWNER,
        KEY,
        body(
            [
                record(
                    eventAtMs=START + 5 * 60_000,
                    observedAtMs=NOW,
                    kind="THERAPY",
                    data={"eventType": "SENSOR_CHANGE"},
                )
            ]
        ),
        now_ms=NOW,
    )
    facts = daily(value)
    assert facts.coveredMinutes == 8.0
    assert len(facts.cohorts) == 2
    assert len(facts.sensorChanges) == 1


def test_deleted_sensor_change_no_longer_splits_a_new_snapshot(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(4), point(6), point(10)])
    event = record(
        eventAtMs=START + 5 * 60_000,
        observedAtMs=NOW,
        kind="THERAPY",
        data={"eventType": "SENSOR_CHANGE"},
    )
    value.append(OWNER, KEY, body([event]), now_ms=NOW)
    before = daily(value)
    value.append(
        OWNER,
        KEY,
        body([event | dict(revision=2, deleted=True, data=None)]),
        now_ms=NOW,
    )
    after = daily(value)
    assert after.coveredMinutes == 10.0 and not after.sensorChanges
    assert before.sourceDigest != after.sourceDigest


def test_sample_at_exact_sensor_change_time_is_ambiguous(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(5), point(10)])
    value.append(
        OWNER,
        KEY,
        body(
            [
                record(
                    eventAtMs=START + 5 * 60_000,
                    observedAtMs=NOW,
                    kind="THERAPY",
                    data={"eventType": "SENSOR_CHANGE"},
                )
            ]
        ),
        now_ms=NOW,
    )
    assert daily(value).coveredMinutes == 0.0
