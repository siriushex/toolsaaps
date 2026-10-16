import asyncio
import importlib
import importlib.util
import threading
from dataclasses import replace
from uuid import uuid4

import pytest

from app.ai.job_executor import JobExecutor
from app.ai.job_ledger import JobLedger
from app.ai.native_text import NativeTextError, TextSessionPolicy
from test_ai_checked_daily_review import fixture, update_source
from test_ai_daily_glucose import MINUTE, NOW
from test_ai_daily_queue import enqueue, handoff, queued
from test_ai_daily_review import answer
from test_ai_history_store import KEY, OTHER, OWNER
from test_ai_native_text import MODEL, Transport, transcript
from test_ai_daily_reports import review, save
from test_ai_glucose_comparison import RANGES


def module():
    assert importlib.util.find_spec("app.ai.daily_worker") is not None, (
        "dedicated daily review worker is absent"
    )
    return importlib.import_module("app.ai.daily_worker")


class Runner:
    def __init__(self, work, *, stopped=True):
        self.work, self.stopped = work, stopped
        self.calls = self.stops = 0
        self.before_reply = None
        self.error = None
        self.reply = None

    async def run(self, work, policy):
        self.calls += 1
        assert work == self.work
        assert policy == module().daily_session_policy(MODEL)
        if self.before_reply:
            self.before_reply()
        if self.error:
            raise self.error
        return answer(work) if self.reply is None else self.reply

    async def stop_and_confirm(self):
        self.stops += 1
        return self.stopped


def setup(tmp_path):
    mod = module()
    history, rows, _ = fixture(tmp_path)
    entry = enqueue(history)
    given = handoff(history, entry.queueId)
    clock = [NOW]
    deadline = NOW + 5 * MINUTE
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    job = ledger.reserve(
        owner_id=OWNER,
        request_id=given.request_id,
        kind="DAILY_REVIEW",
        digest=mod.daily_request_digest(
            given.work, request_id=given.request_id, model=MODEL, deadline_ms=deadline
        ),
        route_revision=mod.ROUTE_REVISION,
        now_ms=NOW,
        deadline_ms=deadline,
    )
    runner = Runner(given.work)
    worker = mod.DailyReviewWorker(
        job,
        given,
        history,
        device=KEY,
        model=MODEL,
        clock_ms=lambda: clock[0],
        runner=runner,
    )
    executor = JobExecutor(ledger, clock_ms=lambda: clock[0])
    return history, rows, given, clock, ledger, job, runner, worker, executor


def execute(parts):
    *_, job, runner, worker, executor = parts
    return asyncio.run(executor.execute(job.owner_id, job.id, worker))


def test_daily_worker_roundtrip_uses_shared_executor_and_never_publishes(tmp_path):
    parts = setup(tmp_path)
    history, _, given, _, ledger, job, runner, worker, executor = parts
    outcome = execute(parts)
    assert outcome.job.state == "SUCCEEDED"
    assert outcome.result.review.publicationState == "NOT_ADMITTED"
    assert outcome.result.review.validation == "REFERENCES_ONLY"
    assert outcome.result.source_check.status == "MATCHED"
    assert runner.calls == runner.stops == 1
    assert queued(history, given.request_id).state == "HANDOFF_RECORDED"
    assert asyncio.run(executor.execute(OWNER, job.id, worker)).result is None
    assert runner.calls == runner.stops == 1
    with pytest.raises(ValueError, match="already_used"):
        asyncio.run(worker.run())
    ledger.engine.dispose()


def test_atomic_idle_admission_can_bind_worker_without_rewriting_claimed_metadata(tmp_path):
    parts = setup(tmp_path)
    history, _, given, clock, ledger, job, runner, _, _ = parts
    admitted = replace(job, state="RUNNING", started_ms=job.created_ms)
    worker = module().DailyReviewWorker(admitted, given, history, device=KEY, model=MODEL,
        clock_ms=lambda: clock[0], runner=runner)
    assert worker.job == admitted
    with pytest.raises(ValueError, match="binding"):
        module().DailyReviewWorker(replace(admitted, started_ms=job.created_ms + 1),
            given, history, device=KEY, model=MODEL, clock_ms=lambda: clock[0], runner=runner)
    ledger.engine.dispose()


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_source_changed_after_handoff_prevents_any_model_start(tmp_path, change):
    parts = setup(tmp_path)
    history, rows, _, clock, ledger, _, runner, _, _ = parts
    update_source(history, rows, change)
    clock[0] += MINUTE
    outcome = execute(parts)
    assert outcome.job.state == "FAILED" and outcome.result is None
    assert runner.calls == runner.stops == 0
    ledger.engine.dispose()


@pytest.mark.parametrize("change", ["late", "revision", "delete", "new_observation"])
def test_source_changed_during_model_turn_discards_reply_and_stops(tmp_path, change):
    parts = setup(tmp_path)
    history, rows, _, clock, ledger, _, runner, _, _ = parts

    def update():
        update_source(history, rows, change)
        clock[0] += MINUTE

    runner.before_reply = update
    outcome = execute(parts)
    assert outcome.job.state == "FAILED" and outcome.result is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("stopped", [False, None, 1])
def test_unconfirmed_stop_exposes_no_result_and_keeps_global_slot(tmp_path, stopped):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    runner.stopped = stopped
    outcome = execute(parts)
    assert outcome.job.state == "RUNNING" and outcome.result is None
    waiting = ledger.reserve(
        owner_id=str(uuid4()),
        request_id=str(uuid4()),
        kind="CHAT",
        digest="a" * 64,
        route_revision="sample-text-v1",
        now_ms=NOW,
        deadline_ms=NOW + MINUTE,
    )
    assert ledger.claim(waiting.owner_id, waiting.id, now_ms=NOW) is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


def test_daily_session_returns_strict_json_without_chat_wrapping(tmp_path):
    _, _, given, _, ledger, *_ = setup(tmp_path)
    mod = module()
    raw = answer(given.work)
    transport = Transport(transcript(raw.decode()))
    session = mod.NativeDailyReviewSession(
        transport, mod.daily_session_policy(MODEL), containment_verified=True
    )
    assert asyncio.run(session.run(given.work)) == raw
    sent = transport.sent
    assert [frame["method"] for frame in sent] == [
        "initialize",
        "initialized",
        "thread/start",
        "turn/start",
    ]
    assert sent[3]["params"]["input"] == [
        {"type": "text", "text": given.work.input_body.decode(), "textElements": []}
    ]
    assert sent[2]["params"]["model"] == MODEL
    assert "reasoningEffort" not in sent[2]["params"]
    assert sent[2]["params"]["dynamicTools"] == []
    with pytest.raises(NativeTextError, match="already_used"):
        asyncio.run(session.run(given.work))
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "reply",
    [
        "ordinary text",
        '{"text":"wrapped"}',
        "{}",
        "[]",
        '{"summary":"x","summary":"y"}',
        "x" * 16_385,
    ],
)
def test_daily_native_session_rejects_wrong_or_unbounded_reply(tmp_path, reply):
    _, _, given, _, ledger, *_ = setup(tmp_path)
    mod = module()
    session = mod.NativeDailyReviewSession(
        Transport(transcript(reply)),
        mod.daily_session_policy(MODEL),
        containment_verified=True,
    )
    with pytest.raises(NativeTextError, match="daily_reply_rejected") as caught:
        asyncio.run(session.run(given.work))
    assert reply not in str(caught.value)
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "change",
    [
        dict(kind="CHAT"),
        dict(route_revision="other-route"),
        dict(digest="0" * 64),
        dict(request_id=str(uuid4())),
        dict(owner_id=str(uuid4())),
        dict(state="RUNNING"),
        dict(started_ms=NOW),
        dict(finished_ms=NOW),
        dict(created_ms=NOW + 6 * MINUTE),
        dict(deadline_ms=NOW + 16 * MINUTE),
        dict(key_hash=OTHER),
    ],
)
def test_wrong_job_binding_is_rejected_before_storage_or_native_io(tmp_path, change):
    history, _, given, _, ledger, job, runner, *_ = setup(tmp_path)
    with pytest.raises(ValueError):
        module().DailyReviewWorker(
            replace(job, **change),
            given,
            history,
            device=KEY,
            model=MODEL,
            clock_ms=lambda: NOW,
            runner=runner,
        )
    assert runner.calls == runner.stops == 0
    assert ledger.get(OWNER, job.id).state == "QUEUED"
    ledger.engine.dispose()


def test_durable_handoff_is_required_not_just_a_copied_work_object(tmp_path):
    parts = setup(tmp_path)
    history, _, given, _, ledger, job, runner, *_ = parts
    fake_id = str(uuid4())
    fake = replace(given, request_id=fake_id)
    fake_job = replace(
        job,
        request_id=fake_id,
        digest=module().daily_request_digest(
            fake.work,
            request_id=fake_id,
            model=MODEL,
            deadline_ms=job.deadline_ms,
        ),
    )
    worker = module().DailyReviewWorker(
        fake_job,
        fake,
        history,
        device=KEY,
        model=MODEL,
        clock_ms=lambda: NOW,
        runner=runner,
    )
    with pytest.raises(ValueError, match="handoff_unavailable"):
        asyncio.run(worker.run())
    assert runner.calls == runner.stops == 0
    ledger.engine.dispose()


@pytest.mark.parametrize("during", [False, True])
def test_new_archive_revision_rejects_old_work_before_or_after_model(tmp_path, during):
    parts = setup(tmp_path)
    history, _, _, clock, ledger, _, runner, *_ = parts

    def supersede():
        clock[0] += MINUTE
        save(history, review(history, ranges=RANGES, now_ms=clock[0]), now=clock[0])

    if during:
        runner.before_reply = supersede
    else:
        supersede()
    outcome = execute(parts)
    assert outcome.job.state == "FAILED" and outcome.result is None
    assert runner.calls == runner.stops == int(during)
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "reply",
    [
        b"malformed",
        b"x" * 16_385,
        "not bytes",
        b'{"text":"ordinary chat reply"}',
    ],
)
def test_runner_cannot_bypass_closed_result_contract(tmp_path, reply):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    runner.reply = reply
    outcome = execute(parts)
    assert outcome.job.state == "FAILED" and outcome.result is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "change",
    [
        dict(packetDigest="0" * 64),
        dict(factsDigest="0" * 64),
        dict(missingInputCodes=[]),
        dict(findings=[]),
    ],
)
def test_valid_json_with_wrong_references_or_hidden_missing_data_fails(
    tmp_path, change
):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    runner.reply = answer(runner.work, **change)
    outcome = execute(parts)
    assert outcome.job.state == "FAILED" and outcome.result is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("phase", ["read", "reply", "checked"])
def test_deadline_checks_surround_all_slow_operations(tmp_path, monkeypatch, phase):
    parts = setup(tmp_path)
    history, _, _, clock, ledger, job, runner, worker, _ = parts

    def expire():
        clock[0] = job.deadline_ms

    if phase == "read":
        original = worker._prepare

        def prepare(now):
            original(now)
            expire()

        monkeypatch.setattr(worker, "_prepare", prepare)
    elif phase == "checked":
        original = history.validate_checked_daily_glucose_review

        def validate(*args, **kwargs):
            checked = original(*args, **kwargs)
            expire()
            return checked

        monkeypatch.setattr(history, "validate_checked_daily_glucose_review", validate)
    else:
        runner.before_reply = expire
    outcome = execute(parts)
    assert outcome.result is None and outcome.job.state != "SUCCEEDED"
    assert runner.calls == runner.stops == int(phase != "read")
    ledger.engine.dispose()


def test_wall_clock_rollback_during_turn_rejects_reply(tmp_path):
    parts = setup(tmp_path)
    _, _, _, clock, ledger, _, runner, *_ = parts
    runner.before_reply = lambda: clock.__setitem__(0, NOW - 1)
    # The existing ledger also refuses settlement before its recorded start.
    # Preserve that unresolved slot rather than invent a successful stop time.
    with pytest.raises(ValueError, match="clock_regression"):
        execute(parts)
    assert ledger.get(OWNER, parts[5].id).state == "RUNNING"
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


def test_existing_interactive_execution_prevents_daily_worker_start(tmp_path):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    interactive = ledger.reserve(
        owner_id=str(uuid4()),
        request_id=str(uuid4()),
        kind="CHAT",
        digest="a" * 64,
        route_revision="sample-text-v1",
        now_ms=NOW,
        deadline_ms=NOW + MINUTE,
    )
    assert ledger.claim(interactive.owner_id, interactive.id, now_ms=NOW)
    outcome = execute(parts)
    assert outcome.job.state == "QUEUED" and outcome.result is None
    assert runner.calls == runner.stops == 0
    ledger.engine.dispose()


def test_competing_executors_run_daily_request_once(tmp_path):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts

    async def scenario():
        original = runner.run
        entered, release = asyncio.Event(), asyncio.Event()

        async def slow(work, policy):
            entered.set()
            await release.wait()
            return await original(work, policy)

        runner.run = slow
        first = asyncio.create_task(executor.execute(OWNER, job.id, worker))
        await asyncio.wait_for(entered.wait(), 3)
        try:
            second = await executor.execute(OWNER, job.id, worker)
            assert second.result is None and second.job.state == "RUNNING"
        finally:
            release.set()
        assert (await first).job.state == "SUCCEEDED"

    asyncio.run(scenario())
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


def test_reader_not_joined_within_cleanup_budget_keeps_global_slot(
    tmp_path, monkeypatch
):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    entered, release = threading.Event(), threading.Event()
    original = worker._prepare

    def slow(now):
        entered.set()
        assert release.wait(3)
        return original(now)

    monkeypatch.setattr(worker, "_prepare", slow)
    monkeypatch.setattr("app.ai.job_executor.STOP_TIMEOUT_SECONDS", 0.02)

    async def scenario():
        task = asyncio.create_task(executor.execute(OWNER, job.id, worker))
        assert await asyncio.to_thread(entered.wait, 3)
        try:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert ledger.get(OWNER, job.id).state == "CANCEL_REQUESTED"
        finally:
            release.set()
            await worker.stop_and_confirm()

    asyncio.run(scenario())
    assert runner.calls == runner.stops == 0
    ledger.engine.dispose()


def test_failed_native_output_does_not_leak_into_ledger_or_trigger_retry(tmp_path):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    runner.error = RuntimeError("synthetic-private-response")
    assert execute(parts).job.state == "FAILED"
    assert asyncio.run(executor.execute(OWNER, job.id, worker)).result is None
    assert runner.calls == runner.stops == 1
    assert b"synthetic-private-response" not in (tmp_path / "jobs.sqlite").read_bytes()
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "error", [RuntimeError("private-output"), asyncio.CancelledError()]
)
def test_native_failure_or_cancel_is_not_retried_and_is_stopped(tmp_path, error):
    parts = setup(tmp_path)
    *_, ledger, job, runner, worker, executor = parts
    runner.error = error
    if isinstance(error, asyncio.CancelledError):
        with pytest.raises(asyncio.CancelledError):
            execute(parts)
        assert ledger.get(OWNER, job.id).state == "CANCELLED"
    else:
        outcome = execute(parts)
        assert outcome.job.state == "FAILED" and outcome.result is None
    assert runner.calls == runner.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("phase", ["prepare", "validate"])
def test_cancellation_joins_reader_before_returning_cleanup_proof(
    tmp_path, monkeypatch, phase
):
    parts = setup(tmp_path)
    history, _, _, _, ledger, job, runner, worker, executor = parts
    entered, release, finished = threading.Event(), threading.Event(), threading.Event()
    target = worker if phase == "prepare" else history
    method = (
        "_prepare" if phase == "prepare" else "validate_checked_daily_glucose_review"
    )
    original = getattr(target, method)

    def slow(*args, **kwargs):
        entered.set()
        assert release.wait(3)
        try:
            return original(*args, **kwargs)
        finally:
            finished.set()

    monkeypatch.setattr(target, method, slow)

    async def scenario():
        task = asyncio.create_task(executor.execute(OWNER, job.id, worker))
        assert await asyncio.to_thread(entered.wait, 3)
        try:
            task.cancel()
            await asyncio.sleep(0.02)
            assert not task.done() and not finished.is_set()
            assert runner.stops == 0
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert finished.is_set()

    asyncio.run(scenario())
    assert ledger.get(OWNER, job.id).state == "CANCELLED"
    assert runner.calls == runner.stops == int(phase == "validate")
    ledger.engine.dispose()


def test_native_runner_failed_start_still_requires_exact_process_stop(tmp_path):
    _, _, given, _, ledger, *_ = setup(tmp_path)

    class Process:
        starts = stops = 0

        async def start(self, policy):
            self.starts += 1
            raise RuntimeError("private-host-details")

        async def stop_and_confirm(self):
            self.stops += 1
            return False

    process = Process()
    runner = module().NativeDailyReviewRunner(process)
    assert asyncio.run(runner.stop_and_confirm()) is True and process.stops == 0
    with pytest.raises(RuntimeError):
        asyncio.run(runner.run(given.work, module().daily_session_policy(MODEL)))
    assert asyncio.run(runner.stop_and_confirm()) is False
    with pytest.raises(ValueError, match="already_used"):
        asyncio.run(runner.run(given.work, module().daily_session_policy(MODEL)))
    assert process.starts == process.stops == 1
    ledger.engine.dispose()


def test_native_worker_process_roundtrip_and_policy_pin(tmp_path):
    history, _, given, _, ledger, job, _, _, executor = setup(tmp_path)
    mod = module()

    class Process:
        starts = stops = 0

        async def start(self, policy):
            assert policy == mod.daily_session_policy(MODEL)
            self.starts += 1
            return Transport(transcript(answer(given.work).decode()))

        async def stop_and_confirm(self):
            self.stops += 1
            return True

    process = Process()
    worker = mod.DailyReviewWorker(
        job,
        given,
        history,
        device=KEY,
        model=MODEL,
        clock_ms=lambda: NOW,
        process=process,
    )
    outcome = asyncio.run(executor.execute(OWNER, job.id, worker))
    assert outcome.job.state == "SUCCEEDED" and outcome.result is not None
    assert process.starts == process.stops == 1
    with pytest.raises(ValueError, match="contained_daily"):
        mod.NativeDailyReviewSession(
            Transport([]), mod.daily_session_policy(MODEL), containment_verified=False
        )
    with pytest.raises(ValueError, match="contained_daily"):
        mod.NativeDailyReviewSession(
            Transport([]),
            TextSessionPolicy(MODEL, "Other prompt"),
            containment_verified=True,
        )
    ledger.engine.dispose()


@pytest.mark.parametrize(
    "event",
    [
        {"id": 91, "method": "item/commandExecution/requestApproval", "params": {}},
        {
            "method": "item/completed",
            "params": {
                "threadId": "foreign-thread",
                "turnId": "sample-turn",
                "item": {"type": "agentMessage", "text": "private-reply"},
            },
        },
        {
            "method": "item/started",
            "params": {
                "threadId": "sample-thread",
                "turnId": "sample-turn",
                "item": {"type": "commandExecution"},
            },
        },
        {"method": "warning", "params": {"message": "private-warning"}},
    ],
)
def test_daily_protocol_does_not_accept_tools_foreign_turns_or_warnings(
    tmp_path, event
):
    _, _, given, _, ledger, *_ = setup(tmp_path)
    frames = transcript(answer(given.work).decode())
    frames.insert(6, event)
    session = module().NativeDailyReviewSession(
        Transport(frames),
        module().daily_session_policy(MODEL),
        containment_verified=True,
    )
    with pytest.raises(NativeTextError):
        asyncio.run(session.run(given.work))
    ledger.engine.dispose()


def test_daily_digest_is_bound_to_model_request_and_deadline(tmp_path):
    _, _, given, _, ledger, job, *_ = setup(tmp_path)
    mod = module()
    args = dict(request_id=given.request_id, model=MODEL, deadline_ms=job.deadline_ms)
    base = mod.daily_request_digest(given.work, **args)
    assert base == job.digest
    assert (
        len(
            {
                base,
                *(
                    mod.daily_request_digest(given.work, **(args | change))
                    for change in (
                        dict(request_id=str(uuid4())),
                        dict(model="other-model"),
                        dict(deadline_ms=job.deadline_ms + 1),
                    )
                ),
            }
        )
        == 4
    )
    ledger.engine.dispose()
