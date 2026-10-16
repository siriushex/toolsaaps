import asyncio
import importlib
import importlib.util
import sqlite3
import threading
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryError, encoded
from app.ai.history_store import HistoryStore
from test_ai_checked_daily_review import update_source
from test_ai_daily_reports import review, save

from test_ai_daily_worker import setup
from test_ai_history_store import KEY, MASTER, OTHER, OWNER, store
from test_ai_daily_glucose import MINUTE, NOW


def module():
    assert importlib.util.find_spec("app.ai.daily_results") is not None, (
        "durable daily result lifecycle is absent"
    )
    return importlib.import_module("app.ai.daily_results")


def test_stopped_daily_result_survives_restart_without_reinference(tmp_path):
    history, _, _, _, ledger, job, runner, worker, executor = setup(tmp_path)
    mod = module()
    outcome = asyncio.run(mod.execute_and_archive(executor, worker))
    assert outcome.job.state == "SUCCEEDED"
    assert outcome.archive_state == "STORED"
    saved = outcome.record
    assert saved.jobId == job.id
    assert saved.review.publicationState == "NOT_ADMITTED"
    assert saved.sourceAtReply.status == "MATCHED"
    selected = mod.get_result(store(tmp_path), OWNER, KEY, job.id, now_ms=NOW)
    assert selected.record == saved
    assert selected.currentState == "SOURCE_MATCHED"
    again = asyncio.run(mod.execute_and_archive(executor, worker))
    assert again.record == saved
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


def execute(parts):
    return asyncio.run(module().execute_and_archive(parts[-1], parts[-2]))


def get(history, job_id, *, now=NOW, owner=OWNER, device=KEY):
    return module().get_result(history, owner, device, job_id, now_ms=now)


def page(history, **options):
    return module().list_results(history, OWNER, KEY, now_ms=NOW, **options)


def database(tmp_path):
    return next((tmp_path / "history").glob("*.sqlite"))


@pytest.mark.parametrize("stopped", [False, None, 1])
def test_unknown_stop_cannot_create_an_archive_or_release_capacity(tmp_path, stopped):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, runner, *_ = parts
    runner.stopped = stopped
    outcome = execute(parts)
    assert outcome.archive_state == "NO_SUCCESS" and outcome.record is None
    assert outcome.job.state == "RUNNING"
    assert get(history, job.id) is None
    with sqlite3.connect(database(tmp_path)) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 4
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("mode", ["error", "invalid_reply", "cancel", "expired"])
def test_unsuccessful_worker_cannot_persist_a_result(tmp_path, mode):
    parts = setup(tmp_path)
    history, _, _, clock, ledger, job, runner, *_ = parts
    if mode == "error":
        runner.error = RuntimeError("private provider error")
    elif mode == "invalid_reply":
        runner.reply = b"{}"
    elif mode == "cancel":
        runner.before_reply = lambda: ledger.cancel(OWNER, job.id, now_ms=NOW)
    else:
        runner.before_reply = lambda: clock.__setitem__(0, job.deadline_ms)
    outcome = execute(parts)
    assert outcome.archive_state == "NO_SUCCESS" and outcome.record is None
    assert get(history, job.id, now=clock[0]) is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("phase", ["before_commit", "after_commit"])
def test_write_failure_never_replays_a_settled_model_turn(tmp_path, monkeypatch, phase):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, runner, *_ = parts
    mod = module()
    if phase == "before_commit":
        monkeypatch.setattr(
            history,
            "_check_quota",
            lambda db: (_ for _ in ()).throw(
                HistoryError(429, "history_capacity_reached")
            ),
        )
    else:
        original = mod._deadline

        def after_commit(end):
            with sqlite3.connect(database(tmp_path)) as db:
                committed = db.execute(
                    "SELECT count(*) FROM daily_review_results"
                ).fetchone()[0]
            if committed:
                raise HistoryError(503, "daily_result_unavailable")
            original(end)

        monkeypatch.setattr(mod, "_deadline", after_commit)
    with pytest.raises(HistoryError):
        execute(parts)
    monkeypatch.undo()
    assert ledger.get(OWNER, job.id).state == "SUCCEEDED"
    again = execute(parts)
    assert again.archive_state == (
        "RESULT_UNAVAILABLE" if phase == "before_commit" else "STORED"
    )
    assert (again.record is None) == (phase == "before_commit")
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


def test_successful_old_executor_without_result_is_explicit_not_retried(tmp_path):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    assert asyncio.run(executor.execute(OWNER, job.id, worker)).result is not None
    assert execute(parts).archive_state == "RESULT_UNAVAILABLE"
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_selected_read_rechecks_source_without_rewriting_original_result(
    tmp_path, change
):
    parts = setup(tmp_path)
    history, rows, _, _, ledger, job, *_ = parts
    saved = execute(parts).record
    update_source(history, rows, change)
    selected = get(history, job.id, now=NOW + MINUTE)
    assert selected.currentState == "SOURCE_CHANGED"
    assert selected.sourceCheck.checkedAtMs == NOW + MINUTE
    assert selected.record == saved
    assert saved.sourceAtReply.checkedAtMs == NOW
    assert page(history).records[0].sourceCheck == "NOT_CHECKED"
    ledger.engine.dispose()


def test_change_during_stop_is_retained_only_as_historical_unreviewed_result(tmp_path):
    parts = setup(tmp_path)
    history, rows, _, clock, ledger, job, runner, *_ = parts

    async def stop():
        runner.stops += 1
        update_source(history, rows, "late")
        clock[0] += MINUTE
        return True

    runner.stop_and_confirm = stop
    saved = execute(parts).record
    assert saved.sourceAtReply.checkedAtMs == NOW
    selected = get(history, job.id, now=clock[0])
    assert selected.currentState == "SOURCE_CHANGED"
    assert selected.record.review.publicationState == "NOT_ADMITTED"
    ledger.engine.dispose()


def test_new_archive_revision_supersedes_not_overwrites_result(tmp_path):
    parts = setup(tmp_path)
    history, rows, _, _, ledger, job, *_ = parts
    saved = execute(parts).record
    update_source(history, rows, "revision")
    newer = save(history, review(history, now_ms=NOW + MINUTE), now=NOW + MINUTE)
    assert newer.reportRevision == 2
    selected = get(history, job.id, now=NOW + MINUTE)
    assert selected.currentState == "SUPERSEDED"
    assert selected.sourceCheck is None and selected.record == saved
    ledger.engine.dispose()


def test_unavailable_original_facts_does_not_become_current(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    saved = execute(parts).record
    with sqlite3.connect(database(tmp_path)) as db:
        db.execute("DELETE FROM daily_reports")
    selected = get(history, job.id)
    assert selected.currentState == "SOURCE_UNAVAILABLE"
    assert selected.sourceCheck is None and selected.record == saved
    ledger.engine.dispose()


def test_expiry_is_fixed_to_original_day_and_purge_includes_results(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    saved = execute(parts).record
    assert get(history, job.id, now=saved.expiresAtMs) is None
    assert (
        not module().list_results(history, OWNER, KEY, now_ms=saved.expiresAtMs).records
    )
    history.purge(now_ms=saved.expiresAtMs)
    with sqlite3.connect(database(tmp_path)) as db:
        for table in ("daily_reports", "daily_review_queue", "daily_review_results"):
            assert db.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 0
    ledger.engine.dispose()


def test_reads_do_not_create_or_migrate_any_store(tmp_path):
    history = store(tmp_path)
    job_id = str(uuid4())
    assert get(history, job_id) is None and not page(history).records
    assert not list((tmp_path / "history").glob("*.sqlite"))
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    before = database(tmp_path).read_bytes()
    assert get(history, job.id) is None and not page(history).records
    assert database(tmp_path).read_bytes() == before
    ledger.engine.dispose()


@pytest.mark.parametrize("owner,device", [(OWNER, OTHER), (str(uuid4()), KEY)])
def test_owner_device_isolation_and_encrypted_payload(tmp_path, owner, device):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    saved = execute(parts).record
    assert get(history, job.id, owner=owner, device=device) is None
    assert not module().list_results(history, owner, device, now_ms=NOW).records
    raw = database(tmp_path).read_bytes()
    assert saved.review.summary.encode() not in raw
    assert saved.queue.frozenWork.inputDigest.encode() not in raw
    wrong = HistoryStore(
        tmp_path / "history", master_key=bytes(reversed(MASTER)), min_free_bytes=0
    )
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        get(wrong, job.id)
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "column,value",
    [
        ("sequence", 3),
        ("queue_id", str(uuid4())),
        ("report_key", "a" * 64),
        ("stored_ms", NOW + 1),
        ("expires_ms", NOW + MINUTE),
        ("request_digest", "a" * 64),
        ("nonce", bytes(12)),
        ("ciphertext", b"bad"),
    ],
)
def test_all_row_metadata_and_ciphertext_are_authenticated(tmp_path, column, value):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    execute(parts)
    with sqlite3.connect(database(tmp_path)) as db:
        db.execute(f"UPDATE daily_review_results SET {column}=?", (value,))
    with pytest.raises(HistoryError, match="history_storage_unavailable"):
        get(history, job.id)
    ledger.engine.dispose()


def test_job_identity_is_authenticated(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    execute(parts)
    changed = str(uuid4())
    with sqlite3.connect(database(tmp_path)) as db:
        db.execute("UPDATE daily_review_results SET job_id=?", (changed,))
    with pytest.raises(HistoryError):
        get(history, changed)
    ledger.engine.dispose()


@pytest.mark.parametrize("kind", ["rows", "bytes", "disk", "entries", "payload"])
def test_storage_limits_fail_without_replaying_inference(tmp_path, monkeypatch, kind):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, runner, *_ = parts
    mod = module()
    if kind == "rows":
        with sqlite3.connect(database(tmp_path)) as db:
            count = sum(
                db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
                for table in ("history_records", "daily_reports", "daily_review_queue")
            )
        monkeypatch.setattr(history, "max_rows", count)
    elif kind == "bytes":
        original = history._check_quota

        def cap(db):
            history.max_bytes = 1
            original(db)

        monkeypatch.setattr(history, "_check_quota", cap)
    elif kind == "disk":
        monkeypatch.setattr(history, "min_free_bytes", 2**62)
    elif kind == "entries":
        monkeypatch.setattr(mod, "MAX_ENTRIES", 0)
    else:
        monkeypatch.setattr(mod, "MAX_ENTRY_BYTES", 10)
    with pytest.raises(HistoryError):
        execute(parts)
    monkeypatch.undo()
    assert ledger.get(OWNER, job.id).state == "SUCCEEDED"
    assert get(history, job.id) is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("value", [-1, True, 2**63, "1"])
def test_invalid_list_cursors_are_rejected_before_read(tmp_path, value):
    history = store(tmp_path)
    with pytest.raises(ValueError, match="cursor"):
        page(history, after_sequence=value)


def test_clock_regression_and_missing_snapshot_fail_closed(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    execute(parts)
    with pytest.raises(HistoryError, match="clock_regression"):
        get(history, job.id, now=NOW - 1)
    with pytest.raises(HistoryError, match="snapshot_unavailable"):
        page(history, snapshot_sequence=2)
    ledger.engine.dispose()


def test_list_is_bounded_with_retained_snapshot_membership(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    history, _, _, _, ledger, _, *_ = parts
    mod = module()
    saved = execute(parts).record
    # Trusted encrypted unit fixtures isolate pagination from model admission.
    with sqlite3.connect(database(tmp_path)) as db:
        for sequence in range(2, 4):
            record = saved.model_copy(
                update=dict(
                    sequence=sequence,
                    jobId=str(uuid4()),
                    queue=saved.queue.model_copy(update=dict(queueId=str(uuid4()))),
                )
            )
            values, nonce = mod._metadata(record), bytes([sequence]) * 12
            values.update(
                nonce=nonce,
                ciphertext=history._cipher(OWNER, KEY).encrypt(
                    nonce,
                    encoded(record.model_dump(mode="json")),
                    history._aad(
                        OWNER, KEY, mod.PURPOSE, [values[k] for k in mod.META]
                    ),
                ),
            )
            db.execute(
                "INSERT INTO daily_review_results("
                + ",".join(values)
                + ") VALUES("
                + ",".join("?" for _ in values)
                + ")",
                tuple(values.values()),
            )
    monkeypatch.setattr(mod, "MAX_PAGE_ENTRIES", 1)
    first = page(history, snapshot_sequence=2)
    assert len(first.records) == 1 and first.hasMore and first.snapshotSequence == 2
    second = page(history, after_sequence=first.nextAfterSequence, snapshot_sequence=2)
    assert len(second.records) == 1 and not second.hasMore
    assert second.records[0].sequence == 2
    assert page(history).snapshotSequence == 3
    assert all(r.publicationState == "NOT_ADMITTED" for r in first.records)
    ledger.engine.dispose()


def test_concurrent_calls_infer_and_store_once(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, runner, worker, executor = parts

    async def both():
        return await asyncio.gather(
            *(module().execute_and_archive(executor, worker) for _ in range(2))
        )

    outcomes = asyncio.run(both())
    assert any(o.archive_state == "STORED" for o in outcomes)
    assert get(history, job.id).record.jobId == job.id
    assert runner.calls == runner.stops == 1
    assert len(page(history).records) == 1
    ledger.engine.dispose()


def test_cancelled_persistence_is_drained_and_then_propagates(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, runner, worker, executor = parts
    mod = module()
    entered, release, finished = threading.Event(), threading.Event(), threading.Event()
    original = mod._save_success

    def blocked(*args, **kwargs):
        entered.set()
        assert release.wait(5)
        try:
            return original(*args, **kwargs)
        finally:
            finished.set()

    monkeypatch.setattr(mod, "_save_success", blocked)

    async def run():
        task = asyncio.create_task(mod.execute_and_archive(executor, worker))
        assert await asyncio.to_thread(entered.wait, 5)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert finished.is_set()

    asyncio.run(run())
    assert get(history, job.id) is not None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


def test_fake_executor_or_worker_is_not_an_execution_receipt(tmp_path):
    *_, ledger, _, _, worker, executor = setup(tmp_path)
    mod = module()
    for engine, given in ((object(), worker), (executor, object())):
        with pytest.raises(ValueError, match="executor_required"):
            asyncio.run(mod.execute_and_archive(engine, given))
    ledger.engine.dispose()


def test_immutable_sink_accepts_only_exact_retry_of_same_stopped_result(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, _, worker, executor = parts
    mod = module()
    outcome = asyncio.run(executor.execute(OWNER, job.id, worker))
    saved = mod._save_success(executor, worker, outcome.job, outcome.result, now_ms=NOW)
    assert (
        mod._save_success(executor, worker, outcome.job, outcome.result, now_ms=NOW + 1)
        == saved
    )
    from dataclasses import replace

    different = replace(
        outcome.result,
        review=outcome.result.review.model_copy(
            update={"summary": "Other context needs review."}
        ),
    )
    with pytest.raises(HistoryError, match="conflict"):
        mod._save_success(executor, worker, outcome.job, different, now_ms=NOW + 1)
    assert get(history, job.id).record == saved
    ledger.engine.dispose()


def test_old_prompt_is_auditable_but_not_new_dispatch_authority(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, _, worker, _ = parts
    saved = execute(parts).record
    from app.ai import daily_review

    monkeypatch.setattr(daily_review, "PROMPT_DIGEST", "f" * 64)
    assert get(history, job.id).record == saved
    assert page(history).records[0].publicationState == "NOT_ADMITTED"
    with pytest.raises(ValueError):
        daily_review._packet(worker.work)
    ledger.engine.dispose()


def test_retention_loss_is_visible_while_result_is_still_retained(tmp_path):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    saved = execute(parts).record
    later = saved.expiresAtMs - MINUTE
    history.purge(now_ms=later)
    selected = get(history, job.id, now=later)
    assert selected.record == saved
    assert selected.currentState == "SOURCE_UNAVAILABLE"
    assert selected.sourceCheck.reason == "RETENTION_WINDOW"
    ledger.engine.dispose()


def test_selected_read_and_list_enforce_final_database_deadline(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, *_ = parts
    execute(parts)
    monkeypatch.setattr(
        module(),
        "_deadline",
        lambda end: (_ for _ in ()).throw(
            HistoryError(503, "daily_result_unavailable")
        ),
    )
    with pytest.raises(HistoryError, match="daily_result_unavailable"):
        get(history, job.id)
    with pytest.raises(HistoryError, match="daily_result_unavailable"):
        page(history)
    ledger.engine.dispose()


def test_shared_ledger_never_contains_review_input_or_result(tmp_path):
    parts = setup(tmp_path)
    *_, ledger, _, _, _, _ = parts
    saved = execute(parts).record
    raw = (tmp_path / "jobs.sqlite").read_bytes()
    assert saved.review.summary.encode() not in raw
    assert saved.queue.frozenWork.inputJson.encode() not in raw
    assert saved.queue.frozenWork.inputDigest.encode() not in raw
    ledger.engine.dispose()


def test_schema_five_preserves_history_chat_facts_and_queue_operations(tmp_path):
    from test_ai_daily_glucose import point
    from test_ai_daily_history import append
    from test_ai_server_chat_query import save as save_chat

    parts = setup(tmp_path)
    history, _, given, _, ledger, job, *_ = parts
    saved = execute(parts).record
    entry = save_chat(history, now=NOW)
    exchanges = history.chat_context(OWNER, KEY, now_ms=NOW + 2)["exchanges"]
    assert exchanges[0]["jobId"] == entry.id
    assert exchanges[0]["answer"] == "Synthetic answer"
    append(history, [point(200)])
    assert review(history).current.sourceDigest
    assert (
        history.get_queued_daily_glucose_review(
            OWNER, KEY, given.request_id, now_ms=NOW + 2
        ).state
        == "HANDOFF_RECORDED"
    )
    assert get(history, job.id, now=NOW + 2).record == saved
    with sqlite3.connect(database(tmp_path)) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 5
        assert db.execute("SELECT count(*) FROM server_chat").fetchone()[0] == 1
        assert db.execute("SELECT count(*) FROM daily_reports").fetchone()[0] == 1
    ledger.engine.dispose()


def test_cancelled_repeat_read_is_drained_before_cancellation_propagates(
    tmp_path, monkeypatch
):
    parts = setup(tmp_path)
    *_, ledger, _, _, worker, executor = parts
    execute(parts)
    mod = module()
    entered, release, finished = threading.Event(), threading.Event(), threading.Event()
    original = mod._get_archived

    def blocked(*args, **kwargs):
        entered.set()
        assert release.wait(5)
        try:
            return original(*args, **kwargs)
        finally:
            finished.set()

    monkeypatch.setattr(mod, "_get_archived", blocked)

    async def run():
        task = asyncio.create_task(mod.execute_and_archive(executor, worker))
        assert await asyncio.to_thread(entered.wait, 5)
        task.cancel()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        pending_until_drained = not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert pending_until_drained, "archive read thread outlived its cancelled owner"
        assert finished.is_set()

    try:
        asyncio.run(run())
    finally:
        release.set()
        ledger.engine.dispose()
