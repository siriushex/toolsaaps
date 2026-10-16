import asyncio
import importlib
import importlib.util
import threading
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app.ai.job_executor import JobExecutor
from app.ai.job_ledger import JobLedger, jobs
from test_ai_checked_daily_review import fixture, update_source
from test_ai_daily_glucose import NOW, MINUTE
from test_ai_daily_queue import enqueue, queued, handoff
from test_ai_daily_worker import Runner
from test_ai_history_store import KEY, OWNER
from test_ai_native_text import MODEL


def module():
    assert importlib.util.find_spec("app.ai.daily_dispatcher") is not None, "owned daily dispatcher missing"
    return importlib.import_module("app.ai.daily_dispatcher")


def setup(tmp_path):
    history, rows, _ = fixture(tmp_path)
    entry = enqueue(history)
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    clock, runners = [NOW], []
    executor = JobExecutor(ledger, clock_ms=lambda: clock[0])
    def factory(job, given):
        assert ledger.get(job.owner_id, job.id).state == "RUNNING"
        runner = Runner(given.work)
        runners.append(runner)
        return runner
    dispatcher = module().DailyDispatcher(history, executor, model=MODEL,
        runner_factory=factory, clock_ms=lambda: clock[0])
    return history, rows, entry, ledger, clock, runners, dispatcher


def run(parts):
    return asyncio.run(parts[-1].dispatch(OWNER, KEY, parts[2].queueId))


def test_daily_roundtrip_archives_only_stopped_success_and_restart_reads_without_replay(tmp_path):
    parts = setup(tmp_path)
    history, _, entry, ledger, clock, runners, _ = parts
    first = run(parts)
    assert first.disposition == "ADMITTED" and first.archive.archive_state == "STORED"
    assert first.archive.record.review.publicationState == "NOT_ADMITTED"
    assert first.archive.record.executionState == "SUCCEEDED_AFTER_CONFIRMED_STOP"
    assert runners[0].calls == runners[0].stops == 1
    clock[0] += MINUTE
    second = run(parts)
    assert second.disposition == "RECORDED" and second.archive.record == first.archive.record
    assert len(runners) == 1 and queued(history, entry.queueId, now=clock[0]).state == "HANDOFF_RECORDED"
    ledger.engine.dispose()


def test_interactive_pending_defers_and_a_later_fresh_claim_uses_one_recorded_handoff(tmp_path):
    parts = setup(tmp_path)
    history, _, entry, ledger, clock, runners, _ = parts
    chat = ledger.reserve(owner_id=str(uuid4()), request_id=str(uuid4()), kind="CHAT",
        digest="b" * 64, route_revision="sample-v1", deadline_ms=NOW + 10 * MINUTE, now_ms=NOW)
    delayed = run(parts)
    assert delayed.disposition == "DEFERRED" and delayed.archive is None and not runners
    with ledger.engine.connect() as db:
        assert db.scalar(select(func.count()).select_from(jobs)) == 1
    assert queued(history, entry.queueId).state == "HANDOFF_RECORDED"
    ledger.cancel(chat.owner_id, chat.id, now_ms=NOW)
    clock[0] += MINUTE
    completed = run(parts)
    assert completed.archive.archive_state == "STORED" and len(runners) == 1
    ledger.engine.dispose()


def test_existing_success_with_lost_result_cannot_infer_again(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    ledger, runners = parts[3], parts[5]
    def lost(*args, **kwargs):
        raise OSError("synthetic_sink_failure")
    monkeypatch.setattr("app.ai.daily_results._save_success", lost)
    with pytest.raises(OSError):
        run(parts)
    again = run(parts)
    assert again.disposition == "RECORDED" and again.archive.archive_state == "RESULT_UNAVAILABLE"
    assert len(runners) == 1 and runners[0].calls == runners[0].stops == 1
    ledger.engine.dispose()


def test_consumed_handoff_without_a_ledger_request_can_admit_once(tmp_path):
    parts = setup(tmp_path)
    history, _, entry, ledger, clock, runners, _ = parts
    handoff(history, entry.queueId)
    clock[0] += MINUTE
    result = run(parts)
    assert result.archive.archive_state == "STORED" and len(runners) == 1
    assert run(parts).disposition == "RECORDED" and len(runners) == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_changed_source_before_dispatch_cannot_reserve_or_start_a_model(tmp_path, change):
    parts = setup(tmp_path)
    history, rows, _, ledger, clock, runners, _ = parts
    update_source(history, rows, change)
    clock[0] += MINUTE
    with pytest.raises(Exception):
        run(parts)
    assert not runners
    with ledger.engine.connect() as db:
        assert db.scalar(select(func.count()).select_from(jobs)) == 0
    ledger.engine.dispose()


def test_cancelled_queue_and_wrong_device_are_not_dispatched(tmp_path):
    parts = setup(tmp_path)
    history, _, entry, ledger, _, runners, dispatcher = parts
    assert asyncio.run(dispatcher.dispatch(OWNER, "b" * 64, entry.queueId)).disposition == "UNAVAILABLE"
    history.cancel_daily_glucose_review(OWNER, KEY, entry.queueId, now_ms=NOW)
    assert run(parts).disposition == "UNAVAILABLE" and not runners
    ledger.engine.dispose()


def test_unconfirmed_stop_is_recorded_without_replay_or_result(tmp_path):
    parts = setup(tmp_path)
    ledger, runners, dispatcher = parts[3], parts[5], parts[-1]
    original = dispatcher.runner_factory
    def factory(*args):
        runner = original(*args)
        runner.stopped = False
        return runner
    dispatcher.runner_factory = factory
    first = run(parts)
    assert first.archive.job.state == "RUNNING" and first.archive.record is None
    assert run(parts).disposition == "RECORDED" and len(runners) == 1
    ledger.engine.dispose()


def test_lost_admission_acknowledgement_never_constructs_a_runner(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    ledger, runners = parts[3], parts[5]
    original = ledger.reserve_and_claim_daily
    def lost(**kwargs):
        original(**kwargs)
        raise OSError("synthetic_commit_ack_lost")
    monkeypatch.setattr(ledger, "reserve_and_claim_daily", lost)
    with pytest.raises(OSError):
        run(parts)
    monkeypatch.setattr(ledger, "reserve_and_claim_daily", original)
    recorded = run(parts)
    assert recorded.disposition == "RECORDED" and recorded.archive.job.state == "RUNNING"
    assert not runners and recorded.archive.record is None
    ledger.engine.dispose()


def test_cancelled_handoff_reader_is_drained_before_return_and_starts_no_model(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    ledger, runners, dispatcher = parts[3], parts[5], parts[-1]
    original = dispatcher._handoff
    entered, release = threading.Event(), threading.Event()
    def delayed(*args):
        result = original(*args)
        entered.set()
        assert release.wait(3)
        return result
    monkeypatch.setattr(dispatcher, "_handoff", delayed)
    async def scenario():
        task = asyncio.create_task(dispatcher.dispatch(OWNER, KEY, parts[2].queueId))
        try:
            assert await asyncio.to_thread(entered.wait, 3)
            task.cancel()
            await asyncio.sleep(0)
            assert not task.done()
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
    asyncio.run(scenario())
    with ledger.engine.connect() as db:
        assert db.scalar(select(func.count()).select_from(jobs)) == 0
    assert not runners
    monkeypatch.setattr(dispatcher, "_handoff", original)
    assert run(parts).archive.archive_state == "STORED"
    ledger.engine.dispose()


def test_two_dispatchers_racing_start_one_model_and_keep_original_result(tmp_path):
    parts = setup(tmp_path)
    dispatcher, entry = parts[-1], parts[2]
    async def scenario():
        return await asyncio.gather(dispatcher.dispatch(OWNER, KEY, entry.queueId),
                                    dispatcher.dispatch(OWNER, KEY, entry.queueId))
    outcomes = asyncio.run(scenario())
    assert sorted(value.disposition for value in outcomes) == ["ADMITTED", "RECORDED"]
    assert len(parts[5]) == 1 and parts[5][0].calls == parts[5][0].stops == 1
    assert run(parts).archive.archive_state == "STORED"
    parts[3].engine.dispose()


def test_duplicate_with_raced_deadline_is_read_not_reissued(tmp_path, monkeypatch):
    parts = setup(tmp_path)
    first = run(parts)
    ledger, clock, runners = parts[3], parts[4], parts[5]
    clock[0] += MINUTE
    original, seen = ledger.get_request, []
    def racing(*args):
        seen.append(True)
        return None if len(seen) == 1 else original(*args)
    monkeypatch.setattr(ledger, "get_request", racing)
    second = run(parts)
    assert second.disposition == "RECORDED" and second.archive.record == first.archive.record
    assert len(runners) == 1 and len(seen) == 2
    ledger.engine.dispose()


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_source_change_during_turn_discards_reply_without_archiving(tmp_path, change):
    parts = setup(tmp_path)
    history, rows, _, ledger, clock, runners, dispatcher = parts
    original = dispatcher.runner_factory
    def factory(*args):
        runner = original(*args)
        def changed():
            update_source(history, rows, change)
            clock[0] += MINUTE
        runner.before_reply = changed
        return runner
    dispatcher.runner_factory = factory
    result = run(parts)
    assert result.archive.job.state == "FAILED" and result.archive.record is None
    assert runners[0].calls == runners[0].stops == 1
    ledger.engine.dispose()


def test_dispatch_window_expiry_creates_no_new_attempt(tmp_path):
    parts = setup(tmp_path)
    parts[4][0] = parts[2].dispatchBeforeMs
    assert run(parts).disposition == "UNAVAILABLE" and not parts[5]
    with parts[3].engine.connect() as db:
        assert db.scalar(select(func.count()).select_from(jobs)) == 0
    parts[3].engine.dispose()


def test_source_change_after_claim_before_worker_run_starts_no_model(tmp_path):
    parts = setup(tmp_path)
    history, rows, _, ledger, clock, runners, dispatcher = parts
    original = dispatcher.runner_factory
    def factory(*args):
        runner = original(*args)
        update_source(history, rows, "late")
        clock[0] += MINUTE
        return runner
    dispatcher.runner_factory = factory
    result = run(parts)
    assert result.archive.job.state == "FAILED" and result.archive.record is None
    assert len(runners) == 1 and runners[0].calls == runners[0].stops == 0
    ledger.engine.dispose()
