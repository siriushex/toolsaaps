import importlib
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS
from test_ai_checked_daily_review import fixture
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, point
from test_ai_daily_history import append
from test_ai_daily_reports import get, review, save
from test_ai_glucose_comparison import RANGES
from test_ai_history_store import KEY, MASTER, OTHER, OWNER, store


def enqueue(value, **changes):
    method = getattr(value, "enqueue_daily_glucose_review", None)
    assert callable(method), "durable daily review queue is absent"
    return method(
        OWNER, KEY, **(dict(report_date=DAY, zone_id="UTC", now_ms=NOW) | changes)
    )


def queued(value, identity, *, now=NOW, owner=OWNER, device=KEY):
    return value.get_queued_daily_glucose_review(owner, device, identity, now_ms=now)


def handoff(value, identity, *, now=NOW):
    return value.handoff_daily_glucose_review(OWNER, KEY, identity, now_ms=now)


def test_queue_roundtrip_restart_and_immutable_logical_day_dedup(tmp_path):
    value, _, saved = fixture(tmp_path)
    first = enqueue(value)
    assert first.state == "QUEUED" and first.handoffAtMs is None
    assert (
        first.logicalKey == saved.reportKey and first.expiresAtMs == saved.expiresAtMs
    )
    assert first.dispatchBeforeMs == END + 1440 * MINUTE
    assert queued(store(tmp_path), first.queueId) == first
    assert enqueue(store(tmp_path), now_ms=NOW + MINUTE, language="en-US") == first
    assert (
        first.frozenWork.to_work()
        == value.prepare_checked_daily_glucose_review(
            OWNER, KEY, report_date=DAY, zone_id="UTC", now_ms=NOW
        ).work
    )


def test_prompt_or_report_revision_change_cannot_create_a_second_automatic_job(
    tmp_path, monkeypatch
):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    save(value, review(value, ranges=RANGES, now_ms=NOW + MINUTE), now=NOW + MINUTE)
    monkeypatch.setattr(
        "app.ai.daily_review.PROMPT_REVISION", "advisory-daily-review-v2"
    )
    assert queued(value, first.queueId) == first
    assert enqueue(value, now_ms=NOW + MINUTE) == first
    assert queued(value, first.queueId).state == "QUEUED"


def test_work_is_handed_off_once_and_restart_never_reissues_it(tmp_path):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    result = handoff(value, first.queueId, now=NOW + MINUTE)
    assert result.request_id == first.queueId
    assert result.work == first.frozenWork.to_work()
    assert result.source_check.status == "MATCHED"
    stored = queued(store(tmp_path), first.queueId, now=NOW + MINUTE)
    assert stored.state == "HANDOFF_RECORDED" and stored.handoffAtMs == NOW + MINUTE
    assert handoff(store(tmp_path), first.queueId, now=NOW + 2 * MINUTE) is None
    assert enqueue(store(tmp_path), now_ms=NOW + 2 * MINUTE) == stored


def test_concurrent_enqueue_and_handoff_have_one_winner(tmp_path):
    fixture(tmp_path)
    with ThreadPoolExecutor(max_workers=4) as pool:
        records = list(pool.map(lambda _: enqueue(store(tmp_path)), range(6)))
    assert all(item == records[0] for item in records)
    identity = records[0].queueId
    with ThreadPoolExecutor(max_workers=4) as pool:
        deliveries = list(
            pool.map(lambda _: handoff(store(tmp_path), identity), range(6))
        )
    assert sum(item is not None for item in deliveries) == 1


@pytest.mark.parametrize("change", ["late", "revision", "delete", "report"])
def test_handoff_checks_current_source_and_latest_report_without_consuming_failed_job(
    tmp_path, change
):
    value, rows, _ = fixture(tmp_path)
    first = enqueue(value)
    if change == "report":
        save(value, review(value, ranges=RANGES, now_ms=NOW + MINUTE), now=NOW + MINUTE)
    elif change == "late":
        append(value, [point(5)])
    else:
        updates = dict(revision=2, data={"glucoseMmol": 6.0})
        if change == "delete":
            updates.update(deleted=True, data=None)
        append(value, [rows[0].model_copy(update=updates)])
    with pytest.raises(
        HistoryError, match="daily_review_(source_changed|report_superseded)"
    ):
        handoff(value, first.queueId, now=NOW + MINUTE)
    assert queued(value, first.queueId) == first


def test_cancellation_is_durable_and_cannot_create_another_automatic_job(tmp_path):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    cancelled = value.cancel_daily_glucose_review(
        OWNER, KEY, first.queueId, now_ms=NOW + 1
    )
    assert cancelled.state == "CANCELLED"
    assert queued(store(tmp_path), first.queueId, now=NOW + 1) == cancelled
    assert handoff(value, first.queueId, now=NOW + 1) is None
    assert enqueue(value, now_ms=NOW + 1) == cancelled


def test_cancel_cannot_pretend_to_stop_already_handed_work(tmp_path):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    handoff(value, first.queueId)
    with pytest.raises(HistoryError, match="daily_queue_handoff_recorded"):
        value.cancel_daily_glucose_review(OWNER, KEY, first.queueId, now_ms=NOW + 1)


def test_expired_dispatch_window_cannot_be_replayed_but_audit_is_retained(tmp_path):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    with pytest.raises(HistoryError, match="daily_queue_dispatch_expired"):
        handoff(value, first.queueId, now=first.dispatchBeforeMs)
    assert queued(value, first.queueId, now=first.dispatchBeforeMs) == first
    assert queued(value, first.queueId, now=first.expiresAtMs) is None
    value.purge(now_ms=first.expiresAtMs)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT count(*) FROM daily_review_queue").fetchone()[0] == 0


@pytest.mark.parametrize("date", ["2026-10-12", "2026-10-14", "invalid"])
def test_automatic_queue_only_accepts_latest_completed_day_before_storage(
    tmp_path, monkeypatch, date
):
    value = store(tmp_path)
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("old/future day touched storage")
    )
    with pytest.raises(ValueError):
        enqueue(value, report_date=date)


def test_missing_and_old_schema_reads_never_create_or_migrate(tmp_path):
    value = store(tmp_path)
    assert callable(getattr(value, "get_queued_daily_glucose_review", None))
    assert queued(value, str(uuid4())) is None
    with pytest.raises(HistoryError, match="daily_review_report_unavailable"):
        enqueue(value)
    assert not list((tmp_path / "history").glob("*.sqlite"))
    append(value, [point(0)])
    path = next((tmp_path / "history").glob("*.sqlite"))
    before = path.read_bytes()
    assert queued(value, str(uuid4())) is None
    assert path.read_bytes() == before


def test_queue_migration_preserves_reports_history_and_chat(tmp_path):
    from app.ai.job_policy import CHAT_POLICY
    from app.ai.schemas import ChatRequest
    from test_ai_server_chat import job

    value, _, saved = fixture(tmp_path)
    first = enqueue(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 4
    assert get(value) == saved
    assert (
        value.check_daily_glucose_report_source(
            OWNER, KEY, report_date=DAY, zone_id="UTC", now_ms=NOW
        ).status
        == "MATCHED"
    )
    value.accept_chat(
        job(created_ms=NOW, deadline_ms=NOW + MINUTE),
        ChatRequest.decode(b'{"text":"synthetic question"}'),
        CHAT_POLICY,
        now_ms=NOW,
    )
    assert value.chat_context(OWNER, KEY, now_ms=NOW)["exchanges"]
    assert queued(value, first.queueId) == first


@pytest.mark.parametrize(
    "field,corrupt",
    [
        ("ciphertext", b"broken"),
        ("nonce", b"z" * 12),
        ("state", "HANDOFF_RECORDED"),
        ("created_ms", NOW - 1),
        ("updated_ms", NOW + 1),
        ("dispatch_before_ms", END),
        ("expires_ms", END + RETENTION_MS + 1),
        ("logical_key", "0" * 64),
        ("sequence", 22),
    ],
)
def test_queue_metadata_and_payload_are_authenticated(tmp_path, field, corrupt):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        db.execute("UPDATE daily_review_queue SET " + field + "=?", (corrupt,))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        queued(value, first.queueId)


def test_owner_device_key_isolation_and_no_plaintext_payload(tmp_path):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    assert b"daily-review-input-v1" not in path.read_bytes()
    assert first.frozenWork.inputDigest.encode() not in path.read_bytes()
    assert queued(value, first.queueId, device=OTHER) is None
    assert queued(value, first.queueId, owner=str(uuid4())) is None
    wrong = type(value)(
        tmp_path / "history", master_key=bytes(reversed(MASTER)), min_free_bytes=0
    )
    with pytest.raises(HistoryError):
        queued(wrong, first.queueId)


def test_shared_quotas_and_queue_payload_limit_roll_back_failed_reservation(
    tmp_path, monkeypatch
):
    value, rows, _ = fixture(tmp_path)
    module = importlib.import_module("app.ai.daily_queue")
    monkeypatch.setattr(module, "MAX_ENTRY_BYTES", 32)
    with pytest.raises(HistoryError, match="daily_queue_too_large"):
        enqueue(value)
    monkeypatch.setattr(module, "MAX_ENTRY_BYTES", 131072)
    value.max_rows = len(rows) + 1
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        enqueue(value)
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT count(*) FROM daily_review_queue").fetchone()[0] == 0


def test_commit_after_deadline_retains_one_shot_marker_without_returning_work(
    tmp_path, monkeypatch
):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    original = value._open
    clock = [0]

    class DelayedCommit:
        def __init__(self, db):
            self.db = db

        def __getattr__(self, name):
            return getattr(self.db, name)

        def commit(self):
            self.db.commit()
            clock[0] = 6

    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: DelayedCommit(original(*a, **kw))
    )
    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: clock[0])
    with pytest.raises(HistoryError, match="daily_queue_unavailable"):
        handoff(value, first.queueId)
    clock[0] = 0
    monkeypatch.setattr(value, "_open", original)
    assert queued(value, first.queueId).state == "HANDOFF_RECORDED"
    assert handoff(value, first.queueId) is None


def test_old_policy_is_auditable_but_cannot_be_handed_off(tmp_path, monkeypatch):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    monkeypatch.setattr(
        "app.ai.daily_review.PROMPT_REVISION", "advisory-daily-review-v2"
    )
    assert queued(value, first.queueId) == first
    with pytest.raises(HistoryError, match="daily_queue_work_policy_changed"):
        handoff(value, first.queueId)
    assert queued(value, first.queueId).state == "QUEUED"


def test_clock_regression_cannot_reissue_work(tmp_path):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    handoff(value, first.queueId, now=NOW + MINUTE)
    with pytest.raises(HistoryError, match="daily_queue_clock_regression"):
        handoff(value, first.queueId)


def test_queue_capacity_is_separate_from_shared_history_limit(tmp_path, monkeypatch):
    value, _, _ = fixture(tmp_path)
    monkeypatch.setattr("app.ai.daily_queue.MAX_ENTRIES", 0)
    with pytest.raises(HistoryError, match="daily_queue_capacity_reached"):
        enqueue(value)


def test_deadline_before_handoff_commit_rolls_back_without_consuming_work(
    tmp_path, monkeypatch
):
    from app.ai import checked_daily_review

    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    original = checked_daily_review._bind_work_to_report
    clock = [0]

    def late(*args, **kwargs):
        result = original(*args, **kwargs)
        clock[0] = 6
        return result

    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: clock[0])
    monkeypatch.setattr(checked_daily_review, "_bind_work_to_report", late)
    with pytest.raises(HistoryError, match="daily_queue_unavailable"):
        handoff(value, first.queueId)
    clock[0] = 0
    assert queued(value, first.queueId) == first


def test_enqueue_does_not_take_the_shared_interactive_ledger_slot(tmp_path):
    from app.ai.job_ledger import JobLedger

    value, _, _ = fixture(tmp_path)
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    chat = ledger.reserve(
        owner_id=OWNER,
        request_id=str(uuid4()),
        kind="CHAT",
        digest="a" * 64,
        route_revision="text-v1",
        deadline_ms=NOW + MINUTE,
        now_ms=NOW,
    )
    enqueue(value)
    assert ledger.claim(OWNER, chat.id, now_ms=NOW) is not None


@pytest.mark.parametrize("day,hours", [("2026-03-07", 23), ("2026-10-31", 25)])
def test_dispatch_window_uses_next_civil_day_not_fixed_24_hours(tmp_path, day, hours):
    from app.ai.daily_glucose import civil_day
    from test_ai_daily_glucose import target
    from test_ai_daily_source import upload

    zone = "America/New_York"
    window = civil_day(day, zone)
    now = window.endMs + 60 * MINUTE
    value = store(tmp_path)
    upload(value, [point(0, eventAtMs=window.startMs, observedAtMs=now)], now=now)
    facts = review(
        value,
        report_date=day,
        zone_id=zone,
        now_ms=now,
        ranges=(target(start=window.startMs, end=window.endMs),),
    )
    save(value, facts, now=now)
    item = enqueue(value, report_date=day, zone_id=zone, now_ms=now)
    assert item.dispatchBeforeMs == window.endMs + hours * 60 * MINUTE
    assert handoff(value, item.queueId, now=now).request_id == item.queueId


def test_low_disk_rejects_writes_without_changing_queued_state(tmp_path, monkeypatch):
    import shutil

    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    value.min_free_bytes = 100
    monkeypatch.setattr(
        "app.ai.daily_queue.shutil.disk_usage",
        lambda _: shutil._ntuple_diskusage(1000, 1000, 0),
    )
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        handoff(value, first.queueId)
    assert queued(value, first.queueId) == first


def test_row_limit_failure_cannot_consume_delivery_marker(tmp_path, monkeypatch):
    value, _, _ = fixture(tmp_path)
    first = enqueue(value)
    monkeypatch.setattr("app.ai.glucose_comparison.MAX_REVIEW_INPUT_RECORDS", 1)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        handoff(value, first.queueId)
    assert queued(value, first.queueId) == first


@pytest.mark.parametrize("operation", ["get", "handoff", "cancel"])
def test_invalid_queue_identity_fails_before_storage(tmp_path, monkeypatch, operation):
    value = store(tmp_path)
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("invalid identity touched storage")
    )
    with pytest.raises(ValueError):
        if operation == "get":
            queued(value, "invalid")
        elif operation == "handoff":
            handoff(value, "invalid")
        else:
            value.cancel_daily_glucose_review(OWNER, KEY, "invalid", now_ms=NOW)
