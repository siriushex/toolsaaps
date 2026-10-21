import importlib
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS
from test_ai_daily_glucose import DAY, END, NOW, point, target
from test_ai_daily_history import append
from test_ai_history_store import KEY, MASTER, OTHER, OWNER, store


def review(value, **options):
    params = dict(report_date=DAY, zone_id="UTC", ranges=(target(),), now_ms=NOW)
    return value.daily_glucose_review(OWNER, KEY, **(params | options))


def save(value, facts=None, *, owner=OWNER, device=KEY, now=NOW):
    assert callable(getattr(value, "save_daily_glucose_report", None)), (
        "encrypted daily report archive is absent"
    )
    return value.save_daily_glucose_report(
        owner, device, facts or review(value), now_ms=now
    )


def get(value, **options):
    params = dict(report_date=DAY, zone_id="UTC", now_ms=NOW)
    return value.get_daily_glucose_report(OWNER, KEY, **(params | options))


def test_report_roundtrip_restart_and_exact_retry_preserve_facts(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    facts = review(value)
    first = save(value, facts)
    assert first.reportRevision == 1
    assert first.facts.model_dump() == facts.model_dump()
    assert first.modelState == "NOT_REQUESTED"
    assert first.factsState == "FACTS_ONLY" and first.sourceCheck == "NOT_CHECKED"
    assert save(store(tmp_path), facts, now=NOW + 1) == first
    assert get(store(tmp_path)) == first


def test_missing_report_is_read_only_and_never_migrates(tmp_path):
    value = store(tmp_path)
    assert callable(getattr(value, "get_daily_glucose_report", None))
    assert get(value) is None
    assert not list((tmp_path / "history").glob("*.sqlite"))
    append(value, [point(0)])
    file = next((tmp_path / "history").glob("*.sqlite"))
    before = file.read_bytes()
    assert get(value) is None
    assert value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW).records == ()
    assert file.read_bytes() == before


def test_revision_and_source_check_do_not_rewrite_the_old_report(tmp_path):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    first = save(value)
    append(
        value,
        [rows[0].model_copy(update={"revision": 2, "data": {"glucoseMmol": 10.0}})],
    )
    changed = review(value)
    old = get(value, source_digest=changed.comparison.sourceDigest)
    assert old.factsState == "STALE_SOURCE" and old.sourceCheck == "CHANGED"
    assert old.facts == first.facts
    second = save(value, changed)
    assert second.reportRevision == 2 and second.archiveSequence > first.archiveSequence
    assert get(value).facts == changed
    assert get(value, report_revision=1) == first
    assert (
        get(value, source_digest=changed.comparison.sourceDigest).sourceCheck
        == "MATCHED"
    )
    assert save(value, first.facts) == first


def test_owner_device_binding_and_read_isolation(tmp_path):
    value = store(tmp_path)
    first = save(value)
    for owner, device in ((OWNER, OTHER), (str(uuid4()), KEY)):
        with pytest.raises(ValueError, match="daily_report"):
            save(value, first.facts, owner=owner, device=device)
        assert (
            value.get_daily_glucose_report(
                owner, device, report_date=DAY, zone_id="UTC", now_ms=NOW
            )
            is None
        )
        assert value.list_daily_glucose_reports(owner, device, now_ms=NOW).records == ()


def test_ciphertext_is_private_and_all_report_metadata_are_authenticated(tmp_path):
    value = store(tmp_path)
    first = save(value)
    file = next((tmp_path / "history").glob("*.sqlite"))
    assert b"daily-glucose-linear-v2" not in file.read_bytes()
    assert first.facts.comparison.sourceDigest.encode() not in file.read_bytes()
    wrong = type(value)(
        tmp_path / "history", master_key=bytes(reversed(MASTER)), min_free_bytes=0
    )
    with pytest.raises(HistoryError):
        get(wrong)


@pytest.mark.parametrize(
    "field,change",
    [
        ("ciphertext", b"broken"),
        ("nonce", b"z" * 12),
        ("created_ms", NOW - 1),
        ("expires_ms", END + RETENTION_MS + 1),
        ("report_revision", 2),
        ("report_key", "0" * 64),
        ("facts_digest", "1" * 64),
        ("sequence", 42),
    ],
)
def test_selected_corruption_fails_without_a_partial_report(tmp_path, field, change):
    value = store(tmp_path)
    save(value)
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        db.execute("UPDATE daily_reports SET " + field + "=?", (change,))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW)


def test_retention_is_fixed_to_day_end_and_cannot_be_extended(tmp_path):
    value = store(tmp_path)
    first = save(value)
    assert first.expiresAtMs == END + RETENTION_MS
    assert get(value, now_ms=first.expiresAtMs - 1) == first
    assert get(value, now_ms=first.expiresAtMs) is None
    with pytest.raises(ValueError, match="daily_report"):
        save(value, first.facts, now=first.expiresAtMs)
    value.purge(now_ms=first.expiresAtMs)
    file = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        assert db.execute("SELECT count(*) FROM daily_reports").fetchone()[0] == 0


def test_concurrent_saves_allocate_one_immutable_revision(tmp_path):
    value = store(tmp_path)
    facts = review(value)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: save(store(tmp_path), facts), range(8)))
    assert all(result == results[0] for result in results)
    assert len(value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW).records) == 1


def test_bounded_list_freezes_membership_and_returns_only_summaries(tmp_path):
    value = store(tmp_path)
    first = save(value)
    rows = [point(0), point(10)]
    append(value, rows)
    second = save(value)
    page = value.list_daily_glucose_reports(OWNER, KEY, limit=1, now_ms=NOW)
    assert page.hasMore and page.snapshotSequence == second.archiveSequence
    assert page.records[0].archiveSequence == first.archiveSequence
    assert "facts" not in page.records[0].model_dump()
    append(
        value,
        [rows[0].model_copy(update={"revision": 2, "data": {"glucoseMmol": 10.0}})],
    )
    third = save(value)
    rest = value.list_daily_glucose_reports(
        OWNER,
        KEY,
        limit=1,
        now_ms=NOW,
        after_sequence=page.nextAfterSequence,
        snapshot_sequence=page.snapshotSequence,
    )
    assert not rest.hasMore and rest.records[0].reportRevision == 2
    assert third.archiveSequence not in [r.archiveSequence for r in rest.records]


def test_summary_keeps_unknown_quality_and_missing_coverage_visible(tmp_path):
    value = store(tmp_path)
    append(value, [point(0), point(10)])
    saved = save(value)
    summary = value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW).records[0]
    assert getattr(summary, "provenance", None) == "DEVICE_DECLARED"
    assert summary.dataCompleteness == "UNKNOWN"
    assert (
        summary.qualityOkCoveredMinutes == saved.facts.current.qualityOkCoveredMinutes
    )
    assert (
        summary.unverifiedCoveredMinutes == saved.facts.current.unverifiedCoveredMinutes
    )
    assert summary.missingMinutes == saved.facts.current.missingMinutes


def test_report_limits_fail_atomically_without_partial_storage(tmp_path, monkeypatch):
    value = store(tmp_path)
    module = importlib.import_module("app.ai.daily_reports")
    monkeypatch.setattr(module, "MAX_REPORT_BYTES", 64)
    with pytest.raises(ValueError, match="daily_report"):
        save(value)
    assert get(value) is None
    monkeypatch.setattr(module, "MAX_REPORT_BYTES", 524_288)
    first = save(value)
    monkeypatch.setattr(module, "MAX_REPORT_REVISIONS", 1)
    append(value, [point(0), point(10)])
    with pytest.raises(HistoryError, match="daily_report_revision_limit"):
        save(value)
    assert get(value) == first


def test_shared_history_quota_rolls_back_new_report(tmp_path):
    value = store(tmp_path, max_rows=1)
    append(value, [point(0)])
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        save(value)
    assert get(value) is None


def test_old_chat_and_history_remain_usable_after_report_migration(tmp_path):
    from app.ai.job_policy import CHAT_POLICY
    from app.ai.schemas import ChatRequest
    from test_ai_server_chat import job

    value = store(tmp_path)
    save(value)
    value.accept_chat(
        job(created_ms=NOW, deadline_ms=NOW + 60_000),
        ChatRequest.decode(b'{"text":"synthetic question"}'),
        CHAT_POLICY,
        now_ms=NOW,
    )
    assert value.chat_context(OWNER, KEY, now_ms=NOW)["exchanges"]
    append(value, [point(0), point(10)])
    assert review(value).current.coveredMinutes == 10
    value.purge(now_ms=NOW)
    assert get(value) is not None


@pytest.mark.parametrize(
    "field,value",
    [
        ("sourceBinding", "0" * 64),
        ("currentDigest", "0" * 64),
        ("reportDate", "2026-01-01"),
        ("asOfMs", NOW - 1),
        ("snapshotSequence", 22),
    ],
)
def test_inconsistent_frozen_bindings_are_rejected_before_storage(
    tmp_path, field, value
):
    archive = store(tmp_path)
    facts = review(archive)
    changed = facts.model_copy(
        update={"comparison": facts.comparison.model_copy(update={field: value})}
    )
    with pytest.raises(ValueError, match="daily_report"):
        save(archive, changed)
    assert not list(archive.root.glob("*.sqlite"))


def test_deletion_produces_new_facts_and_keeps_original_evidence(tmp_path):
    value = store(tmp_path)
    rows = [point(0), point(10)]
    append(value, rows)
    first = save(value)
    append(
        value,
        [rows[0].model_copy(update={"revision": 2, "deleted": True, "data": None})],
    )
    second = save(value)
    assert first.facts.current.coveredMinutes == 10
    assert second.facts.current.coveredMinutes == 0
    assert get(value, report_revision=1).facts.current.coveredMinutes == 10
    assert (
        get(
            value, report_revision=1, source_digest=second.facts.comparison.sourceDigest
        ).factsState
        == "STALE_SOURCE"
    )


def test_clock_and_source_regression_do_not_create_new_revision(tmp_path):
    value = store(tmp_path)
    original = review(value)
    first = save(value, original, now=NOW + 10)
    with pytest.raises(HistoryError, match="daily_report_clock_regression"):
        save(value, original, now=NOW)
    # A different earlier snapshot cannot silently become the latest revision.
    rows = [point(0), point(10)]
    append(value, rows)
    newer = review(value)
    save(value, newer, now=NOW + 10)
    changed = original.model_copy(
        update={
            "comparison": original.comparison.model_copy(
                update={"sourceDigest": "1" * 64}
            )
        }
    )
    with pytest.raises(HistoryError, match="daily_report_source_regression"):
        save(value, changed, now=NOW + 10)
    assert get(value, now_ms=NOW + 10).reportRevision == first.reportRevision + 1


def test_report_schema_failure_preserves_the_existing_archive(tmp_path, monkeypatch):
    value = store(tmp_path)
    append(value, [point(0)])
    module = importlib.import_module("app.ai.daily_reports")
    monkeypatch.setattr(module, "SCHEMA", module.SCHEMA + ("INVALID SQL",))
    with pytest.raises(HistoryError):
        save(value)
    file = next(value.root.glob("*.sqlite"))
    with sqlite3.connect(file) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 2
        assert db.execute("SELECT count(*) FROM history_records").fetchone()[0] == 1
        assert not db.execute(
            "SELECT 1 FROM sqlite_master WHERE name='daily_reports'"
        ).fetchall()
    assert review(value).current.inDayRecordCount == 1


@pytest.mark.parametrize(
    "options",
    [
        {"limit": 21},
        {"limit": True},
        {"after_sequence": -1},
        {"snapshot_sequence": True},
        {"after_sequence": 2**63},
    ],
)
def test_invalid_page_values_fail_before_storage(tmp_path, monkeypatch, options):
    value = store(tmp_path)
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("invalid query opened storage")
    )
    with pytest.raises(ValueError, match="daily_report"):
        value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW, **options)


def test_read_deadline_never_returns_partial_page(tmp_path, monkeypatch):
    value = store(tmp_path)
    save(value)
    ticks = iter([0, 0, 10])
    monkeypatch.setattr("app.ai.daily_reports.time.monotonic", lambda: next(ticks, 10))
    with pytest.raises(HistoryError, match="daily_report_unavailable"):
        value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW)


def test_version_three_reads_do_not_rewrite_the_encrypted_archive(tmp_path):
    import hashlib

    value = store(tmp_path)
    saved = save(value)
    file = next(value.root.glob("*.sqlite"))
    before = hashlib.sha256(file.read_bytes()).digest()
    assert get(value) == saved
    value.list_daily_glucose_reports(OWNER, KEY, now_ms=NOW)
    assert hashlib.sha256(file.read_bytes()).digest() == before


def test_existing_finished_chat_survives_report_migration(tmp_path):
    from app.ai.job_policy import CHAT_POLICY
    from app.ai.schemas import ChatRequest, ChatResult
    from test_ai_server_chat import job

    value = store(tmp_path)
    entry = job(created_ms=NOW, deadline_ms=NOW + 60_000)
    value.accept_chat(entry, ChatRequest("synthetic question"), CHAT_POLICY, now_ms=NOW)
    settled = replace(entry, state="SUCCEEDED", started_ms=NOW, finished_ms=NOW + 1)
    value.finish_chat(settled, ChatResult("synthetic answer"), now_ms=NOW + 1)
    before = value.chat_context(OWNER, KEY, now_ms=NOW + 1)
    save(value, now=NOW + 1)
    assert value.chat_context(OWNER, KEY, now_ms=NOW + 1) == before
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 3


def test_shared_byte_quota_and_aead_purpose_are_enforced(tmp_path):
    from cryptography.exceptions import InvalidTag

    value = store(tmp_path, max_bytes=512)
    with pytest.raises(HistoryError, match="history_capacity_reached"):
        save(value)
    assert get(value) is None
    value = store(tmp_path)
    save(value)
    module = importlib.import_module("app.ai.daily_reports")
    with sqlite3.connect(next(value.root.glob("*.sqlite"))) as db:
        db.row_factory = sqlite3.Row
        row = db.execute("SELECT * FROM daily_reports").fetchone()
    with pytest.raises(InvalidTag):
        value._cipher(OWNER, KEY).decrypt(
            row["nonce"],
            row["ciphertext"],
            value._aad(OWNER, KEY, "server-chat", [row[k] for k in module.META_FIELDS]),
        )
