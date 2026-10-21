import hashlib
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, START, point, target
from test_ai_daily_history import append
from test_ai_daily_reports import get, review, save
from test_ai_history_contract import body, record
from test_ai_history_store import KEY, MASTER, OTHER, OWNER, store


def check(value, **options):
    method = getattr(value, "check_daily_glucose_report_source", None)
    assert callable(method), "independent frozen daily source checker is absent"
    params = dict(report_date=DAY, zone_id="UTC", now_ms=NOW)
    return method(OWNER, KEY, **(params | options))


def upload(value, rows, now=NOW):
    return value.append(
        OWNER, KEY, body([r.model_dump(exclude_unset=True) for r in rows]), now_ms=now
    )


def test_frozen_check_replays_original_cuts_but_observes_current_snapshot(tmp_path):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    first = save(value)
    # The moving as-of hash is intentionally unsuitable for freshness checking.
    assert (
        review(value, now_ms=NOW + MINUTE).comparison.sourceDigest
        != first.facts.comparison.sourceDigest
    )
    result = check(value, now_ms=NOW + MINUTE)
    assert result.status == "MATCHED" and result.reason == "SOURCE_MATCHED"
    assert result.replayedSourceDigest == first.facts.comparison.sourceDigest
    assert result.factsDigest == first.factsDigest
    assert result.reportKey == first.reportKey and result.reportRevision == 1
    assert result.originalAsOfMs == NOW and result.checkedAtMs == NOW + MINUTE
    assert result.originalSnapshotSequence == result.checkedSnapshotSequence == 2
    assert (
        result.dataCompleteness == "UNKNOWN" and result.provenance == "DEVICE_DECLARED"
    )
    assert result.modelState == "NOT_REQUESTED"
    assert get(value) == first


def test_empty_archive_and_missing_report_never_migrate_or_create_data(tmp_path):
    value = store(tmp_path)
    assert check(value) is None
    assert not list((tmp_path / "history").glob("*.sqlite"))
    append(value, [point(0)])
    path = next((tmp_path / "history").glob("*.sqlite"))
    before = path.read_bytes()
    assert check(value) is None
    assert path.read_bytes() == before
    first = save(value)
    before = path.read_bytes()
    assert check(value).status == "MATCHED"
    assert path.read_bytes() == before and get(value) == first


@pytest.mark.parametrize("minute", [-21 * 1440 - 16, 1456])
def test_unrelated_outside_window_upload_does_not_stale_report(tmp_path, minute):
    value = store(tmp_path)
    first = save(value)
    append(value, [point(minute)])
    result = check(value, now_ms=NOW + 1)
    assert result.status == "MATCHED"
    assert result.checkedSnapshotSequence > result.originalSnapshotSequence
    assert result.replayedSourceDigest == first.facts.comparison.sourceDigest


@pytest.mark.parametrize("minute", [-21 * 1440 - 15, -21 * 1440, -1440, 1440, 1455])
def test_late_upload_including_boundary_guards_invalidates_frozen_report(
    tmp_path, minute
):
    value = store(tmp_path)
    first = save(value)
    upload(value, [point(minute)], now=NOW + MINUTE)
    result = check(value, now_ms=NOW + MINUTE)
    assert result.status == "CHANGED" and result.reason == "SOURCE_CHANGED"
    assert result.replayedSourceDigest != first.facts.comparison.sourceDigest
    assert get(value) == first


@pytest.mark.parametrize("change", ["revision", "delete", "quality"])
def test_revised_or_deleted_source_cannot_match_old_facts(tmp_path, change):
    value = store(tmp_path)
    row = point(0)
    append(value, [row, point(10)])
    first = save(value)
    updates = dict(revision=2)
    if change == "delete":
        updates.update(deleted=True, data=None)
    elif change == "quality":
        updates.update(quality="SUSPECT")
    else:
        updates.update(data={"glucoseMmol": 9.0})
    upload(value, [row.model_copy(update=updates)], now=NOW + MINUTE)
    result = check(value, now_ms=NOW + MINUTE)
    assert result.status == "CHANGED"
    assert result.sourceDigest == first.facts.comparison.sourceDigest
    assert result.replayedSourceDigest != result.sourceDigest


def test_newer_observation_is_changed_not_filtered_out_or_replayed_under_false_clock(
    tmp_path,
):
    value = store(tmp_path)
    save(value)
    upload(value, [point(0, observedAtMs=NOW + MINUTE)], now=NOW + MINUTE)
    result = check(value, now_ms=NOW + MINUTE)
    assert result.status == "CHANGED" and result.reason == "NEWER_OBSERVATION"
    assert result.replayedSourceDigest is None


def test_post_midnight_guard_that_did_not_exist_at_original_cut_is_not_ignored(
    tmp_path,
):
    value = store(tmp_path)
    upload(value, [point(1435, observedAtMs=END)], now=END)
    save(value, review(value, now_ms=END), now=END)
    upload(value, [point(1445, observedAtMs=END + 5 * MINUTE)], now=END + 5 * MINUTE)
    result = check(value, now_ms=END + 5 * MINUTE)
    assert result.status == "CHANGED" and result.reason == "NEWER_OBSERVATION"
    assert result.originalWindowEndMs == END
    assert result.windowEndMs == END + 5 * MINUTE


def test_retention_guard_boundary_is_explicit_and_purge_does_not_hide_loss(tmp_path):
    value = store(tmp_path)
    first = save(value)
    start = START - 21 * 1440 * MINUTE - 15 * MINUTE
    assert check(value, now_ms=start + RETENTION_MS - 1).status == "MATCHED"
    value.purge(now_ms=start + RETENTION_MS)
    result = check(value, now_ms=start + RETENTION_MS)
    assert result.status == "UNAVAILABLE"
    assert get(value) == first


def test_source_highwater_rollback_is_not_accepted_as_a_match(tmp_path):
    value = store(tmp_path)
    append(value, [point(0)])
    save(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        db.execute("DELETE FROM history_records")
        db.execute("UPDATE sqlite_sequence SET seq=0 WHERE name='history_records'")
    result = check(value)
    assert result.status == "UNAVAILABLE" and result.reason == "HISTORY_ROLLBACK"


def test_same_source_digest_is_not_enough_when_full_facts_do_not_reproduce(tmp_path):
    value = store(tmp_path)
    facts = review(value)
    altered = facts.model_copy(
        update=dict(current=facts.current.model_copy(update=dict(missingMinutes=1.0)))
    )
    save(value, altered)
    result = check(value)
    assert result.status == "UNAVAILABLE" and result.reason == "FACTS_MISMATCH"
    assert result.replayedSourceDigest == result.sourceDigest
    assert result.replayedFactsDigest != result.factsDigest


def test_writer_cannot_change_source_between_report_selection_and_replay(
    tmp_path, monkeypatch
):
    from app.ai import daily_reports

    value, writer = store(tmp_path), store(tmp_path)
    row = point(0)
    append(value, [row, point(10)])
    save(value)
    original = daily_reports._read_report
    started = threading.Event()
    future = None

    def write():
        started.set()
        append(
            writer, [row.model_copy(update=dict(revision=2, data={"glucoseMmol": 9.0}))]
        )

    with ThreadPoolExecutor(max_workers=1) as pool:

        def select(*args, **kwargs):
            nonlocal future
            selected = original(*args, **kwargs)
            if future is None:
                future = pool.submit(write)
                assert started.wait(1)
                assert not future.done()
            return selected

        monkeypatch.setattr(daily_reports, "_read_report", select)
        result = check(value)
        assert result.status == "MATCHED" and result.checkedSnapshotSequence == 2
        future.result(timeout=5)
    assert check(value).status == "CHANGED"


@pytest.mark.parametrize(
    "day,zone",
    [
        ("2026-03-08", "America/New_York"),
        ("2026-11-01", "America/New_York"),
        ("2026-10-13", "Asia/Kathmandu"),
    ],
)
def test_source_replay_uses_original_civil_clock_including_dst(tmp_path, day, zone):
    from app.ai.daily_glucose import civil_day

    value = store(tmp_path)
    window = civil_day(day, zone)
    now = window.endMs + 60 * MINUTE
    upload(
        value,
        [
            point(0, eventAtMs=window.startMs, observedAtMs=now),
            point(0, eventAtMs=window.startMs + 10 * MINUTE, observedAtMs=now),
        ],
        now=now,
    )
    ranges = (target(start=window.startMs, end=window.endMs),)
    facts = review(value, report_date=day, zone_id=zone, now_ms=now, ranges=ranges)
    save(value, facts, now=now)
    result = check(value, report_date=day, zone_id=zone, now_ms=now + MINUTE)
    assert result.status == "MATCHED"
    assert result.originalAsOfMs == now and result.checkedAtMs == now + MINUTE


def test_full_historical_ranges_are_replayed_not_only_current_day(tmp_path):
    value = store(tmp_path)
    ranges = (target(start=START - 21 * 1440 * MINUTE, end=START), target())
    append(value, [point(-1440), point(-1430), point(0), point(10)])
    facts = review(value, ranges=ranges)
    assert len(facts.current.ranges) == 2
    save(value, facts)
    assert check(value, now_ms=NOW + MINUTE).status == "MATCHED"


@pytest.mark.parametrize("days", [344, 345])
def test_retention_loss_never_becomes_matched_even_when_original_input_was_empty(
    tmp_path, days
):
    value = store(tmp_path)
    save(value)
    result = check(value, now_ms=NOW + days * 1440 * MINUTE)
    assert result.status == "UNAVAILABLE" and result.reason == "RETENTION_WINDOW"
    assert result.replayedSourceDigest is None


def test_expired_report_returns_no_proof(tmp_path):
    value = store(tmp_path)
    save(value)
    assert check(value, now_ms=END + RETENTION_MS) is None


def test_owner_device_and_report_revision_are_exact(tmp_path):
    value = store(tmp_path)
    first = save(value)
    append(value, [point(0)])
    second = save(value)
    assert check(value, report_revision=1).status == "CHANGED"
    result = check(value, report_revision=2)
    assert result.status == "MATCHED" and result.factsDigest == second.factsDigest
    assert first.reportKey == result.reportKey
    for owner, device in ((str(uuid4()), KEY), (OWNER, OTHER)):
        assert (
            value.check_daily_glucose_report_source(
                owner, device, report_date=DAY, zone_id="UTC", now_ms=NOW
            )
            is None
        )


def test_irrelevant_therapy_does_not_claim_a_changed_glucose_source(tmp_path):
    value = store(tmp_path)
    save(value)
    value.append(
        OWNER,
        KEY,
        body(
            [
                record(
                    kind="THERAPY",
                    eventAtMs=START,
                    observedAtMs=NOW + MINUTE,
                    data={"eventType": "BOLUS", "insulinUnits": 2.0},
                )
            ]
        ),
        now_ms=NOW + MINUTE,
    )
    assert check(value, now_ms=NOW + MINUTE).status == "MATCHED"


@pytest.mark.parametrize("change", ["add", "delete"])
def test_sensor_change_membership_is_part_of_source(tmp_path, change):
    from app.ai.history_contract import HistoryRecord

    value = store(tmp_path)
    event = HistoryRecord.model_validate(
        record(
            kind="THERAPY",
            eventAtMs=START,
            observedAtMs=NOW,
            data={"eventType": "SENSOR_CHANGE"},
        )
    )
    if change == "delete":
        append(value, [event])
    save(value)
    upload(
        value,
        [
            event
            if change == "add"
            else event.model_copy(update=dict(revision=2, deleted=True, data=None))
        ],
        now=NOW + MINUTE,
    )
    assert check(value, now_ms=NOW + MINUTE).status == "CHANGED"


@pytest.mark.parametrize(
    "field,corrupt", [("ciphertext", b"broken"), ("deleted", 1), ("nonce", b"z" * 12)]
)
def test_selected_live_or_tombstone_metadata_is_authenticated(tmp_path, field, corrupt):
    value = store(tmp_path)
    row = point(0)
    append(value, [row])
    save(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        db.execute("UPDATE history_records SET " + field + "=?", (corrupt,))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        check(value)


def test_corrupt_deleted_record_is_not_treated_as_trustworthy_absence(tmp_path):
    value = store(tmp_path)
    row = point(0)
    append(value, [row])
    save(value)
    append(value, [row.model_copy(update=dict(revision=2, deleted=True, data=None))])
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        db.execute("UPDATE history_records SET ciphertext=?", (b"broken",))
    with pytest.raises(HistoryError):
        check(value)


def test_report_and_history_key_corruption_fail_without_proof(tmp_path):
    value = store(tmp_path)
    save(value)
    wrong = type(value)(
        tmp_path / "history", master_key=bytes(reversed(MASTER)), min_free_bytes=0
    )
    with pytest.raises(HistoryError):
        check(wrong)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        db.execute("UPDATE daily_reports SET facts_digest=?", ("0" * 64,))
    with pytest.raises(HistoryError):
        check(value)


def test_row_deadline_and_post_calculation_deadline_fail_closed(tmp_path, monkeypatch):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    save(value)
    monkeypatch.setattr("app.ai.glucose_comparison.MAX_REVIEW_INPUT_RECORDS", 1)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        check(value)
    monkeypatch.setattr("app.ai.glucose_comparison.MAX_REVIEW_INPUT_RECORDS", 50_000)
    ticks = iter([0, 0, 10])
    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: next(ticks, 10))
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        check(value)


def test_replay_finishing_after_deadline_cannot_return_a_proof(tmp_path, monkeypatch):
    from app.ai import glucose_comparison

    value = store(tmp_path)
    save(value)
    original = glucose_comparison.build_glucose_review
    clock = [0]

    def late(*args, **kwargs):
        result = original(*args, **kwargs)
        clock[0] = 6
        return result

    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: clock[0])
    monkeypatch.setattr(glucose_comparison, "build_glucose_review", late)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        check(value)


@pytest.mark.parametrize(
    "options",
    [
        dict(now_ms=True),
        dict(report_revision=True),
        dict(report_revision=0),
        dict(report_revision=33),
        dict(report_date="invalid"),
        dict(zone_id="invalid"),
    ],
)
def test_invalid_selection_fails_before_storage(tmp_path, monkeypatch, options):
    value = store(tmp_path)
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("invalid input touched storage")
    )
    with pytest.raises(ValueError):
        check(value, **options)


def test_read_only_result_contains_no_raw_records_or_report_content(tmp_path):
    value = store(tmp_path)
    append(value, [point(0)])
    save(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    before = hashlib.sha256(path.read_bytes()).digest()
    result = check(value)
    assert (
        result.scope == "GLUCOSE_ONLY" and result.meaning == "RETAINED_HISTORY_AT_CHECK"
    )
    assert not {"records", "facts", "data", "ranges"}.intersection(result.model_dump())
    assert hashlib.sha256(path.read_bytes()).digest() == before
