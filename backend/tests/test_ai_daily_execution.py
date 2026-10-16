import asyncio
import threading
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app.ai.job_executor import JobExecutor
from app.ai.job_ledger import JobLedger, KINDS, jobs


class Worker:
    route_revision = "sample-daily-v1"
    request_digest = "a" * 64

    def __init__(self, stopped=True):
        self.runs = self.stops = 0
        self.stopped = stopped

    async def run(self):
        self.runs += 1
        return "synthetic"

    async def stop_and_confirm(self):
        self.stops += 1
        return self.stopped


def setup(tmp_path):
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    values = dict(owner_id=str(uuid4()), request_id=str(uuid4()), digest="a" * 64,
                  route_revision="sample-daily-v1", deadline_ms=20_000)
    return ledger, JobExecutor(ledger, clock_ms=lambda: 2000), values


def test_idle_daily_admission_constructs_only_after_atomic_claim_and_never_replays(tmp_path):
    ledger, executor, values = setup(tmp_path)
    worker, seen = Worker(), []
    def factory(job):
        assert ledger.get(job.owner_id, job.id) == job
        assert job.state == "RUNNING" and job.started_ms == job.created_ms == 2000
        seen.append(job)
        return worker
    first = asyncio.run(executor.execute_daily(**values, worker_factory=factory))
    second = asyncio.run(executor.execute_daily(**values, worker_factory=factory))
    assert first.disposition == "ADMITTED" and first.outcome.job.state == "SUCCEEDED"
    assert first.outcome.result == "synthetic"
    assert second.disposition == "RECORDED" and second.outcome.result is None
    assert len(seen) == worker.runs == worker.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("kind", sorted(KINDS))
def test_pending_work_defers_daily_without_constructing_worker_or_holding_owner(tmp_path, kind):
    ledger, executor, values = setup(tmp_path)
    ledger.reserve(owner_id=str(uuid4()), request_id=str(uuid4()), kind=kind,
        digest="b" * 64, route_revision="sample-v1", deadline_ms=20_000, now_ms=1000)
    called = []
    result = asyncio.run(executor.execute_daily(**values, worker_factory=lambda job: called.append(job)))
    assert result.disposition == "DEFERRED" and result.outcome is None and not called
    with ledger.engine.connect() as db:
        assert db.scalar(select(func.count()).select_from(jobs)) == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("stopped", [False, None, 1])
def test_unknown_stop_keeps_exact_daily_slot_and_cannot_be_replayed(tmp_path, stopped):
    ledger, executor, values = setup(tmp_path)
    worker = Worker(stopped)
    result = asyncio.run(executor.execute_daily(**values, worker_factory=lambda _: worker))
    assert result.outcome.job.state == "RUNNING" and result.outcome.result is None
    duplicate = asyncio.run(executor.execute_daily(**values, worker_factory=lambda _: pytest.fail("replayed")))
    assert duplicate.disposition == "RECORDED" and duplicate.outcome.job.state == "RUNNING"
    assert worker.runs == worker.stops == 1
    ledger.engine.dispose()


def test_cancellation_after_committed_admission_never_constructs_worker(tmp_path, monkeypatch):
    ledger, executor, values = setup(tmp_path)
    entered, release = threading.Event(), threading.Event()
    original = ledger.reserve_and_claim_daily
    admitted = []
    def delayed(**kwargs):
        value = original(**kwargs)
        admitted.append(value)
        entered.set()
        assert release.wait(3)
        return value
    monkeypatch.setattr(ledger, "reserve_and_claim_daily", delayed)
    called = []
    async def run():
        task = asyncio.create_task(executor.execute_daily(**values, worker_factory=lambda job: called.append(job)))
        try:
            assert await asyncio.to_thread(entered.wait, 3)
            task.cancel()
        finally:
            release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
    asyncio.run(run())
    assert not called and ledger.get(values["owner_id"], admitted[0].job.id).state == "CANCELLED"
    ledger.engine.dispose()


def test_exceptional_factory_does_not_optimistically_release_claim(tmp_path):
    ledger, executor, values = setup(tmp_path)
    seen = []
    def factory(job):
        seen.append(job)
        raise RuntimeError("private-details")
    with pytest.raises(ValueError, match="daily_worker_construction_unconfirmed") as caught:
        asyncio.run(executor.execute_daily(**values, worker_factory=factory))
    assert "private-details" not in str(caught.value)
    assert ledger.get(values["owner_id"], seen[0].id).state == "RUNNING"
    ledger.engine.dispose()


def test_worker_binding_failure_runs_no_model_and_requires_positive_cleanup(tmp_path):
    ledger, executor, values = setup(tmp_path)
    worker = Worker()
    worker.request_digest = "b" * 64
    result = asyncio.run(executor.execute_daily(**values, worker_factory=lambda _: worker))
    assert result.outcome.job.state == "FAILED" and result.outcome.result is None
    assert worker.runs == 0 and worker.stops == 1
    ledger.engine.dispose()


def test_request_lookup_is_owner_kind_bound_and_never_returns_a_claim(tmp_path):
    ledger, executor, values = setup(tmp_path)
    result = asyncio.run(executor.execute_daily(**values, worker_factory=lambda _: Worker()))
    job = ledger.get_request(values["owner_id"], "DAILY_REVIEW", values["request_id"])
    assert job == result.outcome.job and not hasattr(job, "token")
    assert ledger.get_request(str(uuid4()), "DAILY_REVIEW", values["request_id"]) is None
    assert ledger.get_request(values["owner_id"], "CHAT", values["request_id"]) is None
    with pytest.raises(ValueError):
        ledger.get_request(values["owner_id"], "UNKNOWN", values["request_id"])
    ledger.engine.dispose()
