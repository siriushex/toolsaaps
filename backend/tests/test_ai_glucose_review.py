import hashlib
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from uuid import uuid4

import pytest

from app.ai.daily_glucose import civil_day
from app.ai.history_contract import HistoryError
from test_ai_glucose_comparison import CONTEXT, RANGES, comparison_module
from test_ai_daily_glucose import DAY, NOW, MINUTE
from test_ai_history_contract import body, record
from test_ai_history_store import OWNER, KEY, OTHER, store


def rows(offset, value=3.0, context=CONTEXT):
    day = (date.fromisoformat(DAY) - timedelta(days=offset)).isoformat()
    start = civil_day(day, "UTC").startMs
    return [
        record(
            eventAtMs=start + minute * MINUTE,
            observedAtMs=NOW,
            quality="OK",
            context=context,
            data={"glucoseMmol": value},
        )
        for minute in range(0, 121, 15)
    ]


def append(value, observations):
    for start in range(0, len(observations), 32):
        value.append(OWNER, KEY, body(observations[start : start + 32]), now_ms=NOW)


def review(value, owner=OWNER, device=KEY, **options):
    assert callable(getattr(value, "daily_glucose_review", None)), (
        "multi-day protected reader is absent"
    )
    params = dict(report_date=DAY, zone_id="UTC", ranges=RANGES, now_ms=NOW)
    return value.daily_glucose_review(owner, device, **(params | options))


def test_review_uses_one_sequence_and_matches_existing_current_day_reader(tmp_path):
    value = store(tmp_path)
    append(value, rows(0) + rows(1) + rows(2))
    result = review(value)
    current = value.daily_glucose_facts(
        OWNER, KEY, report_date=DAY, zone_id="UTC", ranges=RANGES, now_ms=NOW
    )
    assert result.current.model_dump() == current.model_dump()
    assert result.comparison.snapshotSequence == 27
    assert result.comparison.patterns[0].qualifyingDays == 3


def test_missing_historical_uploads_are_not_normal_days(tmp_path):
    value = store(tmp_path)
    append(value, rows(0))
    result = review(value)
    pattern = result.comparison.patterns[0]
    assert pattern.qualifyingDays == pattern.eligibleWindows == 1
    assert len(pattern.excludedDays) == 21
    assert len(result.comparison.analyses[0].baselines[1].missingDates) == 21


def test_empty_and_other_identity_cannot_reuse_someone_else_history(tmp_path):
    value = store(tmp_path)
    append(value, rows(0) + rows(1))
    for options in (dict(device=OTHER), dict(owner=str(uuid4()))):
        result = review(value, **options)
        assert not result.comparison.patterns and result.current.inputRecordCount == 0
        assert result.comparison.status == "LIMITED_DATA"


def test_historical_revision_and_deletion_change_comparison_not_therapy(tmp_path):
    value = store(tmp_path)
    previous = rows(1)
    append(value, rows(0) + previous + rows(2))
    before = review(value)
    append(value, [r | dict(revision=2, deleted=True, data=None) for r in previous])
    after = review(value)
    assert before.comparison.patterns[0].qualifyingDays == 3
    assert after.comparison.patterns[0].qualifyingDays == 2
    assert before.comparison.sourceDigest != after.comparison.sourceDigest


def test_corrupt_historical_record_blocks_entire_review(tmp_path):
    value = store(tmp_path)
    append(value, rows(0) + rows(21))
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute(
            "UPDATE history_records SET ciphertext=? WHERE sequence=15", (b"broken",)
        )
    with pytest.raises(HistoryError):
        review(value)


def test_review_read_does_not_write_database_or_create_results(tmp_path):
    value = store(tmp_path)
    append(value, rows(0) + rows(1))
    file = next((tmp_path / "history").glob("*.sqlite"))
    digest = hashlib.sha256(file.read_bytes()).digest()
    review(value)
    assert hashlib.sha256(file.read_bytes()).digest() == digest
    assert len(list((tmp_path / "history").glob("*.sqlite"))) == 1


def test_review_row_and_wall_budgets_fail_without_partial_output(tmp_path, monkeypatch):
    value = store(tmp_path)
    append(value, rows(0) + rows(1))
    monkeypatch.setattr(comparison_module(), "MAX_REVIEW_INPUT_RECORDS", 1)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        review(value)
    monkeypatch.setattr(comparison_module(), "MAX_REVIEW_INPUT_RECORDS", 100_000)
    ticks = iter([0, 0, 10])
    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: next(ticks, 10))
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        review(value)


def test_expired_baseline_days_are_not_restored_or_requested():
    from app.ai.history_contract import RETENTION_MS

    oldest_day = (date.fromisoformat(DAY) - timedelta(days=350)).isoformat()
    windows = comparison_module().review_windows(oldest_day, "UTC", now_ms=NOW)
    assert 1 < len(windows) < 22
    assert all(window.startMs > NOW - RETENTION_MS for window in windows)


def test_unknown_context_stays_limited_even_with_repeated_glucose_shape(tmp_path):
    value = store(tmp_path)
    append(value, rows(0, context=None) + rows(1, context=None) + rows(2, context=None))
    result = review(value)
    assert not result.comparison.patterns
    assert "CURRENT_CONTEXT_UNAVAILABLE" in result.comparison.analyses[0].limitations
    assert len(result.comparison.analyses[0].baselines[1].missingDates) == 19


def test_review_snapshot_is_not_mixed_with_a_concurrent_writer(tmp_path, monkeypatch):
    value, writer = store(tmp_path), store(tmp_path)
    observations = rows(0) + rows(1) + rows(2)
    append(value, observations)
    original = comparison_module().build_glucose_review
    started = threading.Event()
    future = None

    def write():
        started.set()
        append(writer, [observations[-1] | dict(revision=2, deleted=True, data=None)])

    with ThreadPoolExecutor(max_workers=1) as pool:

        def collect(records, **options):
            nonlocal future
            future = pool.submit(write)
            assert started.wait(1) and not future.done()
            return original(records, **options)

        monkeypatch.setattr(comparison_module(), "build_glucose_review", collect)
        before = review(value)
        future.result(timeout=5)
    monkeypatch.setattr(comparison_module(), "build_glucose_review", original)
    after = review(value)
    assert before.current.snapshotSequence == before.comparison.snapshotSequence == 27
    assert after.current.snapshotSequence == after.comparison.snapshotSequence == 28
    assert before.comparison.sourceDigest != after.comparison.sourceDigest


def test_deadline_after_calculation_rejects_result_and_releases_lock(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    append(value, rows(0))
    original = comparison_module().build_glucose_review
    expired = False

    def slow(records, **options):
        nonlocal expired
        result = original(records, **options)
        expired = True
        return result

    with monkeypatch.context() as patch:
        patch.setattr(
            "app.ai.history_store.time.monotonic", lambda: 10 if expired else 0
        )
        patch.setattr(comparison_module(), "build_glucose_review", slow)
        with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
            review(value)
    append(value, rows(1))
    assert review(value).comparison.snapshotSequence == 18


def test_wrong_master_key_has_no_partial_facts(tmp_path):
    from app.ai.history_store import HistoryStore

    value = store(tmp_path)
    append(value, rows(0) + rows(1))
    wrong = HistoryStore(tmp_path / "history", master_key=b"q" * 32, min_free_bytes=0)
    with pytest.raises(HistoryError):
        review(wrong)


def test_recorded_sensor_change_day_is_not_mistaken_for_repeated_pattern(tmp_path):
    value = store(tmp_path)
    observations = rows(0) + rows(1) + rows(2)
    append(value, observations)
    append(
        value,
        [
            record(
                eventAtMs=observations[-1]["eventAtMs"],
                observedAtMs=NOW,
                kind="THERAPY",
                data={"eventType": "SENSOR_CHANGE"},
            )
        ],
    )
    pattern = review(value).comparison.patterns[0]
    assert pattern.qualifyingDays == 2
    assert pattern.status == "OBSERVATION"
    assert any(day.reason == "SENSOR_CHANGE_DAY" for day in pattern.excludedDays)


@pytest.mark.parametrize("case", ["duplicate", "kind", "sensor_event", "future"])
def test_pure_batch_rejects_malformed_snapshot(case):
    from app.ai.history_contract import HistoryRecord

    observations = [HistoryRecord.model_validate(item) for item in rows(0)]
    changes = ()
    if case == "duplicate":
        observations.append(observations[0])
    elif case == "kind":
        changes, observations = observations, []
    elif case == "sensor_event":
        changes = [
            HistoryRecord.model_validate(
                record(
                    eventAtMs=observations[0].eventAtMs,
                    observedAtMs=NOW,
                    kind="THERAPY",
                    data={"eventType": "SET_CHANGE"},
                )
            )
        ]
    else:
        observations[0] = observations[0].model_copy(update={"eventAtMs": NOW + 1})
    with pytest.raises(ValueError, match="invalid_glucose_review"):
        comparison_module().build_glucose_review(
            observations,
            sensor_changes=changes,
            report_date=DAY,
            zone_id="UTC",
            ranges=RANGES,
            now_ms=NOW,
            snapshot_sequence=100,
            source_binding="ad" * 32,
        )
