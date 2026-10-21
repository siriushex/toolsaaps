import hashlib
import json
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, RETENTION_MS, encoded
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, START, point
from test_ai_daily_history import append
from test_ai_daily_reports import get, review, save
from test_ai_daily_review import answer, packet
from test_ai_glucose_comparison import CONTEXT, RANGES
from test_ai_history_store import KEY, OTHER, OWNER, store


def prepare(value, **changes):
    method = getattr(value, "prepare_checked_daily_glucose_review", None)
    assert callable(method), "source-checked daily review preparation is absent"
    options = dict(report_date=DAY, zone_id="UTC", now_ms=NOW)
    return method(OWNER, KEY, **(options | changes))


def validate(value, work, result=None, *, owner=OWNER, device=KEY, now=NOW):
    method = getattr(value, "validate_checked_daily_glucose_review", None)
    assert callable(method), "source-checked daily review result boundary is absent"
    return method(
        owner, device, work, answer(work) if result is None else result, now_ms=now
    )


def fixture(tmp_path):
    value = store(tmp_path)
    rows = [
        point(-offset * 1440 + minute, value=3.0, context=CONTEXT)
        for offset in range(3)
        for minute in range(0, 121, 15)
    ]
    append(value, rows)
    saved = save(value, review(value, ranges=RANGES))
    return value, rows, saved


def test_prepare_and_result_use_independent_current_checks_without_claiming_admission(
    tmp_path,
):
    value, _, saved = fixture(tmp_path)
    prepared = prepare(value)
    work, source = prepared.work, prepared.source_check
    assert source.status == "MATCHED"
    assert (
        source.reportRevision == saved.reportRevision
        and source.factsDigest == work.facts_digest
    )
    assert source.sourceBinding == work.source_binding
    assert packet(work)["candidates"]
    checked = validate(value, work, now=NOW + MINUTE)
    assert checked.source_check.checkedAtMs == NOW + MINUTE
    assert checked.source_check.status == "MATCHED"
    assert checked.review.factsDigest == saved.factsDigest
    assert checked.review.publicationState == "NOT_ADMITTED"
    assert checked.review.validation == "REFERENCES_ONLY"
    assert checked.review.textMeaning == "UNREVIEWED_AI_INTERPRETATION"
    # Legacy inner contracts are unchanged; actual point-in-time proof is separate.
    assert checked.review.sourceFreshness == "NOT_INDEPENDENTLY_VERIFIED"
    assert get(value) == saved


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_stale_archive_cannot_prepare_even_if_old_hash_would_have_matched(
    tmp_path, change
):
    value, rows, saved = fixture(tmp_path)
    update_source(value, rows, change)
    assert (
        get(value, source_digest=saved.facts.comparison.sourceDigest).sourceCheck
        == "MATCHED"
    )
    with pytest.raises(HistoryError, match="daily_review_source_changed"):
        prepare(value, now_ms=NOW + MINUTE)


def update_source(value, rows, change):
    if change == "late":
        changed = point(5, context=CONTEXT)
    else:
        options = dict(revision=2)
        if change == "delete":
            options.update(deleted=True, data=None)
        elif change == "new_observation":
            options.update(observedAtMs=NOW + MINUTE)
        else:
            options.update(data={"glucoseMmol": 7.0})
        changed = rows[0].model_copy(update=options)
    from test_ai_daily_source import upload

    upload(value, [changed], now=NOW + MINUTE)


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_result_rechecks_source_instead_of_reusing_preparation_proof(tmp_path, change):
    value, rows, _ = fixture(tmp_path)
    prepared = prepare(value)
    result = answer(prepared.work)
    update_source(value, rows, change)
    assert prepared.source_check.status == "MATCHED"
    with pytest.raises(HistoryError, match="daily_review_source_changed"):
        validate(value, prepared.work, result, now=NOW + MINUTE)


def test_unrelated_upload_between_prepare_and_result_does_not_reject_valid_work(
    tmp_path,
):
    value, _, _ = fixture(tmp_path)
    prepared = prepare(value)
    append(value, [point(1456)])
    checked = validate(value, prepared.work, now=NOW + MINUTE)
    assert checked.source_check.status == "MATCHED"
    assert (
        checked.source_check.checkedSnapshotSequence
        > prepared.source_check.checkedSnapshotSequence
    )


def test_new_report_revision_supersedes_work_even_if_original_source_is_unchanged(
    tmp_path,
):
    value, _, _ = fixture(tmp_path)
    prepared = prepare(value)
    newer = save(
        value, review(value, ranges=RANGES, now_ms=NOW + MINUTE), now=NOW + MINUTE
    )
    assert newer.reportRevision == prepared.source_check.reportRevision + 1
    with pytest.raises(HistoryError, match="daily_review_report_superseded"):
        validate(value, prepared.work, now=NOW + MINUTE)
    current = prepare(value, now_ms=NOW + MINUTE)
    assert current.source_check.reportRevision == newer.reportRevision
    assert (
        validate(value, current.work, now=NOW + MINUTE).source_check.status == "MATCHED"
    )


@pytest.mark.parametrize("stage", ["prepare", "result"])
def test_retention_loss_cannot_produce_checked_work_or_result(tmp_path, stage):
    value, _, _ = fixture(tmp_path)
    prepared = prepare(value)
    now = START - 21 * 1440 * MINUTE - 15 * MINUTE + RETENTION_MS
    with pytest.raises(HistoryError, match="daily_review_source_unavailable"):
        if stage == "prepare":
            prepare(value, now_ms=now)
        else:
            validate(value, prepared.work, now=now)


@pytest.mark.parametrize("stage", ["prepare", "result"])
def test_missing_or_expired_archive_never_returns_a_checked_object(tmp_path, stage):
    value = store(tmp_path)
    if stage == "prepare":
        with pytest.raises(HistoryError, match="daily_review_report_unavailable"):
            prepare(value)
        assert not list((tmp_path / "history").glob("*.sqlite"))
    else:
        _, _, _ = fixture(tmp_path)
        prepared = prepare(value)
        with pytest.raises(ValueError, match="daily_review.*expired"):
            validate(value, prepared.work, now=END + RETENTION_MS)


@pytest.mark.parametrize("owner,device", [(str(uuid4()), KEY), (OWNER, OTHER)])
def test_work_cannot_cross_owner_device_before_storage(
    tmp_path, monkeypatch, owner, device
):
    value, _, _ = fixture(tmp_path)
    work = prepare(value).work
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("foreign work touched storage")
    )
    with pytest.raises(ValueError, match="daily_review.*binding"):
        validate(value, work, owner=owner, device=device)


def test_rehashed_canonical_packet_is_not_trusted_over_authenticated_archive(tmp_path):
    value, _, _ = fixture(tmp_path)
    work = prepare(value).work
    envelope = json.loads(work.input_body)
    envelope["input"]["day"]["missingMinutes"] = 1.0
    digest = hashlib.sha256(encoded(envelope["input"])).hexdigest()
    envelope["packetDigest"] = digest
    altered = replace(work, input_body=encoded(envelope), input_digest=digest)
    with pytest.raises(HistoryError, match="daily_review.*archive") as error:
        validate(value, altered)
    assert error.value.status == 409


@pytest.mark.parametrize(
    "result_change",
    [
        dict(target=4.5),
        dict(findings=[]),
        dict(missingInputCodes=[]),
        dict(summary="Change your target", dose=1.0),
        dict(factsDigest="0" * 64),
    ],
)
def test_new_boundary_preserves_closed_result_and_reference_checks(
    tmp_path, result_change
):
    value, _, _ = fixture(tmp_path)
    work = prepare(value).work
    with pytest.raises((ValueError, HistoryError), match="daily_review") as error:
        validate(value, work, answer(work, **result_change))
    if isinstance(error.value, HistoryError):
        assert (
            error.value.status == 422
            and error.value.code == "invalid_daily_review_result"
        )


def test_invalid_language_and_oversized_result_fail_before_storage(
    tmp_path, monkeypatch
):
    value, _, _ = fixture(tmp_path)
    work = prepare(value).work
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("invalid input touched storage")
    )
    with pytest.raises(ValueError, match="daily_review"):
        prepare(value, language="unknown")
    with pytest.raises(ValueError, match="daily_review"):
        validate(value, work, b"x" * 16385)


def test_preparation_and_validation_are_read_only_and_have_no_model_side_effects(
    tmp_path,
):
    value, _, saved = fixture(tmp_path)
    path = next((tmp_path / "history").glob("*.sqlite"))
    before = path.read_bytes()
    for language in ("ru-RU", "en-US"):
        prepared = prepare(value, language=language)
        assert packet(prepared.work)["language"] == language
        checked = validate(value, prepared.work)
        assert checked.source_check.modelState == "NOT_REQUESTED"
    assert path.read_bytes() == before and get(value) == saved


def test_input_capacity_failure_is_not_reported_as_storage_corruption(
    tmp_path, monkeypatch
):
    value, _, _ = fixture(tmp_path)
    monkeypatch.setattr("app.ai.daily_review.MAX_INPUT_BYTES", 1)
    with pytest.raises(HistoryError, match="daily_review_input_unavailable") as error:
        prepare(value)
    assert error.value.status == 422


def test_old_check_object_is_not_accepted_as_work_or_as_new_proof(
    tmp_path, monkeypatch
):
    value, _, _ = fixture(tmp_path)
    prepared = prepare(value)
    monkeypatch.setattr(
        value, "_open", lambda *a, **kw: pytest.fail("invalid work touched storage")
    )
    for invalid in (prepared, prepared.source_check, object()):
        with pytest.raises(ValueError, match="invalid_daily_review_work"):
            validate(value, invalid, b"{}")


@pytest.mark.parametrize("stage", ["prepare", "result"])
def test_budget_covers_actual_work_and_result_construction(
    tmp_path, monkeypatch, stage
):
    from app.ai import daily_review

    value, _, _ = fixture(tmp_path)
    work = prepare(value).work
    name = "prepare_review" if stage == "prepare" else "validate_review"
    original = getattr(daily_review, name)
    clock = [0]

    def late(*args, **kwargs):
        result = original(*args, **kwargs)
        clock[0] = 6
        return result

    monkeypatch.setattr("app.ai.history_store.time.monotonic", lambda: clock[0])
    monkeypatch.setattr(daily_review, name, late)
    with pytest.raises(HistoryError, match="daily_glucose_unavailable"):
        prepare(value) if stage == "prepare" else validate(value, work)


@pytest.mark.parametrize("table", ["history_records", "daily_reports"])
def test_corruption_cannot_produce_checked_result(tmp_path, table):
    value, _, _ = fixture(tmp_path)
    work = prepare(value).work
    path = next((tmp_path / "history").glob("*.sqlite"))
    with sqlite3.connect(path) as db:
        db.execute("UPDATE " + table + " SET ciphertext=?", (b"broken",))
    with pytest.raises(HistoryError):
        validate(value, work)


@pytest.mark.parametrize("stage", ["prepare", "result"])
def test_writer_waits_through_actual_work_or_result_construction(
    tmp_path, monkeypatch, stage
):
    from app.ai import daily_review

    value, rows, _ = fixture(tmp_path)
    work = prepare(value).work
    writer = store(tmp_path)
    name = "prepare_review" if stage == "prepare" else "validate_review"
    original = getattr(daily_review, name)
    started, future = threading.Event(), None

    def write():
        started.set()
        append(
            writer,
            [rows[0].model_copy(update=dict(revision=2, data={"glucoseMmol": 7.0}))],
        )

    with ThreadPoolExecutor(max_workers=1) as pool:

        def guarded(*args, **kwargs):
            nonlocal future
            if future is None:
                future = pool.submit(write)
                assert started.wait(1)
                assert not future.done()
            return original(*args, **kwargs)

        monkeypatch.setattr(daily_review, name, guarded)
        checked = prepare(value) if stage == "prepare" else validate(value, work)
        assert checked.source_check.status == "MATCHED"
        future.result(timeout=5)
    with pytest.raises(HistoryError, match="daily_review_source_changed"):
        validate(value, work)
