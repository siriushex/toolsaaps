import importlib
import importlib.util
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import pytest
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, point, target
from test_ai_daily_history import append
from test_ai_history_contract import body
from test_ai_history_store import KEY, OTHER, OWNER, store

from app.ai.history_contract import RETENTION_MS, HistoryError
from app.ai.history_store import HistoryStore

AT_FIVE = END + 300 * MINUTE


def module():
    assert importlib.util.find_spec("app.ai.daily_schedule") is not None, (
        "opt-in nightly facts preparation is absent"
    )
    return importlib.import_module("app.ai.daily_schedule")


def configure(value, **changes):
    method = getattr(value, "set_daily_facts_schedule", None)
    assert callable(method), "persisted nightly facts opt-in is absent"
    return method(
        OWNER,
        KEY,
        **(
            {"enabled": True, "zone_id": "UTC", "now_ms": NOW, "expected_generation": 0}
            | changes
        ),
    )


def run(value, **changes):
    method = getattr(value, "produce_scheduled_daily_facts", None)
    assert callable(method), "bounded nightly facts producer is absent"
    return method(OWNER, KEY, **({"ranges": (target(),), "now_ms": AT_FIVE} | changes))


def path(tmp_path):
    return tmp_path / "history" / (HistoryStore._identity(OWNER, KEY) + ".sqlite")


def test_missing_schedule_is_disabled_without_creating_or_migrating(tmp_path):
    module()
    value = store(tmp_path)
    assert value.get_daily_facts_schedule(OWNER, KEY, now_ms=NOW) is None
    assert run(value).state == "DISABLED"
    assert not list((tmp_path / "history").glob("*.sqlite"))
    append(value, [point(0)])
    before = path(tmp_path).read_bytes()
    assert run(value).state == "DISABLED"
    assert path(tmp_path).read_bytes() == before


def test_explicit_setting_is_encrypted_bound_and_survives_restart(tmp_path):
    value = store(tmp_path)
    saved = configure(value, zone_id="Asia/Tbilisi")
    assert saved.enabled is True and saved.generation == 1
    assert saved.zoneId == "Asia/Tbilisi" and saved.updatedAtMs == NOW
    assert store(tmp_path).get_daily_facts_schedule(OWNER, KEY, now_ms=NOW) == saved
    assert value.get_daily_facts_schedule(OWNER, OTHER, now_ms=NOW) is None
    assert b"Asia/Tbilisi" not in path(tmp_path).read_bytes()
    with sqlite3.connect(path(tmp_path)) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 6
        assert (
            db.execute("SELECT count(*) FROM daily_facts_schedule").fetchone()[0] == 1
        )


@pytest.mark.parametrize(
    "changes",
    [
        {"enabled": 1},
        {"enabled": "true"},
        {"zone_id": "wrong/zone"},
        {"zone_id": ""},
        {"now_ms": True},
        {"expected_generation": True},
        {"expected_generation": -1},
    ],
)
def test_invalid_settings_fail_before_storage(tmp_path, monkeypatch, changes):
    module()
    value = store(tmp_path)
    monkeypatch.setattr(value, "_open", lambda *a, **kw: pytest.fail("invalid storage"))
    with pytest.raises(ValueError):
        configure(value, **changes)


def test_generation_cas_noop_disable_and_clock_regression(tmp_path):
    value = store(tmp_path)
    first = configure(value)
    assert configure(value, expected_generation=1, now_ms=NOW + 1) == first
    with pytest.raises(HistoryError, match="daily_schedule_conflict"):
        configure(value, enabled=False)
    disabled = configure(value, enabled=False, expected_generation=1, now_ms=NOW + 1)
    assert disabled.generation == 2 and not disabled.enabled
    assert run(store(tmp_path)).state == "DISABLED"
    with pytest.raises(HistoryError, match="daily_schedule_clock_regression"):
        configure(value, expected_generation=2)
    with pytest.raises(HistoryError, match="daily_schedule_clock_regression"):
        value.get_daily_facts_schedule(OWNER, KEY, now_ms=NOW)


def test_before_five_missing_data_and_sparse_data_are_distinct(tmp_path):
    value = store(tmp_path)
    configure(value)
    assert run(value, now_ms=AT_FIVE - 1).state == "NOT_DUE"
    missing = run(value)
    assert missing.state == "NO_DATA" and missing.report is None
    assert value.list_daily_glucose_reports(OWNER, KEY, now_ms=AT_FIVE).records == ()
    append(value, [point(10)])
    sparse = run(value)
    assert sparse.state == "FACTS_READY" and sparse.report.reportRevision == 1
    assert sparse.report.facts.current.coveredMinutes == 0
    assert sparse.report.facts.comparison.status == "LIMITED_DATA"
    assert sparse.report.modelState == "NOT_REQUESTED"


def test_prepare_once_and_restart_keep_original_report_without_reaging(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    first = run(value)
    assert first.state == "FACTS_READY"
    report = first.report
    assert report.facts.current.window.reportDate == DAY
    assert report.facts.current.coveredMinutes == 10
    assert report.expiresAtMs == END + RETENTION_MS
    second = run(store(tmp_path), now_ms=AT_FIVE + MINUTE)
    assert second.state == "UNCHANGED" and second.report == report
    assert second.source_check.status == "MATCHED"
    assert second.source_check.checkedAtMs == AT_FIVE + MINUTE
    assert report.createdAtMs == AT_FIVE
    with sqlite3.connect(path(tmp_path)) as db:
        assert db.execute("SELECT count(*) FROM daily_reports").fetchone()[0] == 1
        assert db.execute("SELECT count(*) FROM daily_review_queue").fetchone()[0] == 0
        assert (
            db.execute("SELECT count(*) FROM daily_review_results").fetchone()[0] == 0
        )


def test_missing_analysis_range_does_not_invent_target(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    facts = run(value, ranges=()).report.facts.current
    assert facts.ranges == () and facts.cohorts[0].metrics.inRangePct is None


def test_host_implicit_range_preserves_known_range_or_remains_unavailable(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    first = run(value).report
    assert run(value, ranges=None, now_ms=AT_FIVE + 1).report == first
    configure(value, zone_id="Asia/Tbilisi", expected_generation=1, now_ms=AT_FIVE + 2)
    unknown = run(value, ranges=None, now_ms=AT_FIVE + 2).report
    assert unknown.facts.current.ranges == ()


def test_existing_report_retry_keeps_original_behavior_after_quota_reduction(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    report = run(value).report
    value.max_rows = 3
    assert (
        value.save_daily_glucose_report(OWNER, KEY, report.facts, now_ms=AT_FIVE + 1)
        == report
    )


@pytest.mark.parametrize("change", ["edit", "delete", "late", "range"])
def test_late_changes_create_facts_only_revision_and_preserve_original(
    tmp_path, change
):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    configure(value)
    first = run(value).report
    if change == "range":
        options = {"ranges": (target(high=10.0, version="range-2"),)}
    else:
        options = {}
        updated = point(5)
        if change in ("edit", "delete"):
            updated = rows[0].model_copy(
                update={"revision": 2, "data": {"glucoseMmol": 10.0}}
            )
            if change == "delete":
                updated = updated.model_copy(update={"deleted": True, "data": None})
        append(value, [updated])
    second = run(value, now_ms=AT_FIVE + MINUTE, **options)
    assert second.state == "FACTS_READY" and second.report.reportRevision == 2
    assert second.report.modelState == "NOT_REQUESTED"
    assert (
        value.get_daily_glucose_report(
            OWNER,
            KEY,
            report_date=DAY,
            zone_id="UTC",
            now_ms=AT_FIVE + MINUTE,
            report_revision=1,
        )
        == first
    )
    assert run(value, now_ms=AT_FIVE + 2 * MINUTE, **options).state == "UNCHANGED"


def test_catchup_only_latest_day_and_never_replays_previous_due_before_five(tmp_path):
    value = store(tmp_path)
    configure(value)
    append(value, [point(0), point(10)])
    assert run(value, now_ms=AT_FIVE + 2 * 1440 * MINUTE).state == "NO_DATA"
    assert run(value, now_ms=END + 2 * 1440 * MINUTE + 100 * MINUTE).state == "NOT_DUE"
    assert (
        value.list_daily_glucose_reports(
            OWNER,
            KEY,
            now_ms=END + 3 * 1440 * MINUTE,
        ).records
        == ()
    )


@pytest.mark.parametrize(
    "day,now,minutes",
    [
        ("2026-03-29", "2026-03-30T03:00:00+00:00", 1380),
        ("2026-10-25", "2026-10-26T04:00:00+00:00", 1500),
    ],
)
def test_saved_zone_and_dst_determine_previous_completed_day(
    tmp_path, day, now, minutes
):
    module()
    value = store(tmp_path)
    stamp = int(datetime.fromisoformat(now).timestamp() * 1000)
    configure(value, zone_id="Europe/Berlin", now_ms=stamp - 1)
    assert run(value, now_ms=stamp, ranges=()).state == "NO_DATA"
    assert module().due_report_date("Europe/Berlin", stamp) == day
    window = importlib.import_module("app.ai.daily_glucose").civil_day(
        day, "Europe/Berlin"
    )
    assert (window.endMs - window.startMs) // MINUTE == minutes
    assert module().due_report_date("Europe/Berlin", stamp - 1) is None
    rows = [
        point(0, eventAtMs=window.startMs + i * MINUTE, observedAtMs=stamp)
        for i in (0, 10)
    ]
    value.append(
        OWNER, KEY, body([r.model_dump(exclude_unset=True) for r in rows]), now_ms=stamp
    )
    prepared = run(value, now_ms=stamp, ranges=())
    assert prepared.report.facts.current.window == window
    assert prepared.report.facts.current.dayMinutes == minutes
    assert prepared.report.facts.current.coveredMinutes == 10.0


def test_concurrent_producers_share_one_frozen_revision(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: run(store(tmp_path)), range(4)))
    assert sum(r.state == "FACTS_READY" for r in results) == 1
    assert all(r.report == results[0].report for r in results)


@pytest.mark.parametrize(
    "field,broken",
    [
        ("generation", 2),
        ("updated_ms", NOW + 1),
        ("nonce", b"z" * 12),
        ("ciphertext", b"broken"),
    ],
)
def test_corrupt_setting_fails_closed_without_facts(tmp_path, field, broken):
    value = store(tmp_path)
    configure(value)
    with sqlite3.connect(path(tmp_path)) as db:
        db.execute(f"UPDATE daily_facts_schedule SET {field}=?", (broken,))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        run(value)


def test_quota_failure_rolls_back_facts_and_keeps_setting(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    setting = configure(value)
    value.max_rows = 3
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        run(value)
    assert value.get_daily_facts_schedule(OWNER, KEY, now_ms=AT_FIVE) == setting
    assert value.list_daily_glucose_reports(OWNER, KEY, now_ms=AT_FIVE).records == ()


def test_purge_does_not_reenable_or_reage_optin_settings(tmp_path):
    value = store(tmp_path)
    setting = configure(value, enabled=False)
    value.purge(now_ms=NOW + RETENTION_MS)
    assert (
        value.get_daily_facts_schedule(OWNER, KEY, now_ms=NOW + RETENTION_MS) == setting
    )
    assert run(value, now_ms=NOW + RETENTION_MS).state == "DISABLED"


def test_analytical_ranges_are_frozen_before_source_scan(tmp_path, monkeypatch):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    ranges = [target()]
    read = value._read_glucose_records

    def change_ranges(*args, **kwargs):
        result = read(*args, **kwargs)
        ranges.clear()
        return result

    monkeypatch.setattr(value, "_read_glucose_records", change_ranges)
    assert run(value, ranges=ranges).report.facts.current.ranges == (target(),)


def test_initial_optin_failure_rolls_back_schema_and_setting(tmp_path, monkeypatch):
    module()
    value = store(tmp_path)
    append(value, [point(0)])

    def refuse(*args):
        raise HistoryError(429, "history_capacity_reached")

    monkeypatch.setattr(value, "_check_quota", refuse)
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        configure(value)
    with sqlite3.connect(path(tmp_path)) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 1
        assert not db.execute(
            "SELECT name FROM sqlite_master WHERE name='daily_facts_schedule'"
        ).fetchall()
    assert value.get_daily_facts_schedule(OWNER, KEY, now_ms=NOW) is None


def test_empty_revision_after_deletion_does_not_leave_old_facts_current(tmp_path):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    configure(value)
    first = run(value).report
    append(
        value,
        [
            r.model_copy(update={"revision": 2, "deleted": True, "data": None})
            for r in rows
        ],
    )
    removed = run(value, now_ms=AT_FIVE + 1)
    assert removed.state == "FACTS_READY" and removed.report.reportRevision == 2
    assert removed.report.facts.current.coveredMinutes == 0
    assert removed.report.facts.current.inDayRecordCount == 0
    assert removed.report.facts.comparison.status == "LIMITED_DATA"
    assert (
        value.check_daily_glucose_report_source(
            OWNER,
            KEY,
            report_date=DAY,
            zone_id="UTC",
            report_revision=first.reportRevision,
            now_ms=AT_FIVE + 1,
        ).status
        == "CHANGED"
    )


def test_disable_is_serialized_with_the_whole_source_and_archive_transaction(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    read = value._read_glucose_records
    started = threading.Event()
    future = None

    def disable():
        started.set()
        return configure(
            store(tmp_path), enabled=False, expected_generation=1, now_ms=AT_FIVE
        )

    with ThreadPoolExecutor(max_workers=1) as pool:

        def scan(*args, **kwargs):
            nonlocal future
            future = pool.submit(disable)
            assert started.wait(1)
            assert not future.done()
            return read(*args, **kwargs)

        monkeypatch.setattr(value, "_read_glucose_records", scan)
        assert run(value).state == "FACTS_READY"
        assert future.result(timeout=5).enabled is False
    assert run(store(tmp_path), now_ms=AT_FIVE + 1).state == "DISABLED"


def test_source_budget_failure_keeps_optin_and_no_partial_report(tmp_path, monkeypatch):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    saved = configure(value)
    monkeypatch.setattr("app.ai.glucose_comparison.MAX_REVIEW_INPUT_RECORDS", 1)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        run(value)
    assert value.list_daily_glucose_reports(OWNER, KEY, now_ms=AT_FIVE).records == ()
    assert value.get_daily_facts_schedule(OWNER, KEY, now_ms=AT_FIVE) == saved


@pytest.mark.parametrize("foreign_first", [False, True])
def test_foreign_device_schedule_ciphertext_cannot_grant_optin(tmp_path, foreign_first):
    value = store(tmp_path)

    def configure_foreign():
        value.set_daily_facts_schedule(
            OWNER,
            OTHER,
            enabled=False,
            zone_id="UTC",
            now_ms=NOW,
            expected_generation=0,
        )

    if foreign_first:
        configure_foreign()
    configure(value)
    if not foreign_first:
        configure_foreign()
    with sqlite3.connect(path(tmp_path)) as db:
        row = db.execute("SELECT nonce,ciphertext FROM daily_facts_schedule").fetchone()
    other = tmp_path / "history" / (value._identity(OWNER, OTHER) + ".sqlite")
    with sqlite3.connect(other) as db:
        db.execute("UPDATE daily_facts_schedule SET nonce=?,ciphertext=?", row)
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        value.get_daily_facts_schedule(OWNER, OTHER, now_ms=NOW)


def test_source_rollback_is_not_refreshed_into_a_new_valid_report(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    report = run(value).report
    monkeypatch.setattr(value, "_highwater", lambda db: 0)
    with pytest.raises(HistoryError, match="daily_schedule_source_unavailable"):
        run(value, now_ms=AT_FIVE + 1)
    assert (
        value.get_daily_glucose_report(
            OWNER,
            KEY,
            report_date=DAY,
            zone_id="UTC",
            now_ms=AT_FIVE + 1,
        )
        == report
    )


def test_concurrent_setting_edits_have_one_generation_winner(tmp_path):
    module()
    store(tmp_path)

    def edit(_):
        try:
            return configure(store(tmp_path))
        except HistoryError as error:
            assert error.code == "daily_schedule_conflict"
            return None

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(edit, range(4)))
    assert sum(r is not None for r in results) == 1
    assert (
        store(tmp_path).get_daily_facts_schedule(OWNER, KEY, now_ms=NOW).generation == 1
    )


def test_zone_change_reprojects_without_rewriting_original_day(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value)
    original = run(value).report
    configure(value, zone_id="Asia/Tbilisi", expected_generation=1, now_ms=AT_FIVE + 1)
    result = run(value, now_ms=AT_FIVE + 1)
    assert result.report.reportKey != original.reportKey
    assert result.report.facts.current.window.zoneId == "Asia/Tbilisi"
    assert (
        value.get_daily_glucose_report(
            OWNER,
            KEY,
            report_date=DAY,
            zone_id="UTC",
            now_ms=AT_FIVE + 1,
        )
        == original
    )


def test_facts_refresh_preserves_frozen_queue_and_blocks_old_handoff(tmp_path):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    configure(value)
    report = run(value).report
    queued = value.enqueue_daily_glucose_review(
        OWNER,
        KEY,
        report_date=DAY,
        zone_id="UTC",
        now_ms=AT_FIVE,
    )
    append(
        value,
        [rows[0].model_copy(update={"revision": 2, "data": {"glucoseMmol": 9.0}})],
    )
    revised = run(value, now_ms=AT_FIVE + 1).report
    assert revised.reportRevision == report.reportRevision + 1
    retained = value.enqueue_daily_glucose_review(
        OWNER,
        KEY,
        report_date=DAY,
        zone_id="UTC",
        now_ms=AT_FIVE + 1,
    )
    assert retained == queued
    with pytest.raises(HistoryError, match="daily_review_report_superseded"):
        value.handoff_daily_glucose_review(
            OWNER, KEY, queued.queueId, now_ms=AT_FIVE + 1
        )
    assert (
        value.get_queued_daily_glucose_review(
            OWNER, KEY, queued.queueId, now_ms=AT_FIVE + 1
        )
        == queued
    )


def test_failure_after_archive_insert_rolls_back_the_new_revision(
    tmp_path, monkeypatch
):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    configure(value)
    report = run(value).report
    append(
        value,
        [rows[0].model_copy(update={"revision": 2, "data": {"glucoseMmol": 9.0}})],
    )
    original = module().daily_reports._save_report_in_transaction

    def interrupted(*args, **kwargs):
        original(*args, **kwargs)
        raise HistoryError(503, "daily_report_unavailable")

    monkeypatch.setattr(
        module().daily_reports, "_save_report_in_transaction", interrupted
    )
    with pytest.raises(HistoryError, match="daily_report_unavailable"):
        run(value, now_ms=AT_FIVE + 1)
    assert (
        value.get_daily_glucose_report(
            OWNER,
            KEY,
            report_date=DAY,
            zone_id="UTC",
            now_ms=AT_FIVE + 1,
        )
        == report
    )


def test_producer_uses_saved_zone_not_host_date_or_unconfigured_device(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    configure(value, zone_id="Asia/Tbilisi")
    prepared = run(value, now_ms=NOW)
    assert prepared.state == "FACTS_READY"
    assert prepared.report.facts.current.window.reportDate == DAY
    assert module().due_report_date("UTC", NOW) is None
    assert (
        value.produce_scheduled_daily_facts(OWNER, OTHER, ranges=(), now_ms=NOW).state
        == "DISABLED"
    )
