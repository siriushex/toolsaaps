import asyncio
from uuid import uuid4

import pytest

from app.ai.job_ledger import JobLedger
from app.ai.job_executor import JobExecutor


class Worker:
    route_revision = "pilot-v1"
    request_digest = "a" * 64

    def __init__(self, *, stopped=True, fail=False):
        self.runs = 0
        self.stops = 0
        self.stopped = stopped
        self.fail = fail

    async def run(self):
        self.runs += 1
        if self.fail:
            raise RuntimeError("private-model-output")
        return "synthetic-validated-result"

    async def stop_and_confirm(self):
        self.stops += 1
        return self.stopped


def setup(tmp_path, deadline=10_000):
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    job = ledger.reserve(owner_id=str(uuid4()), request_id=str(uuid4()), kind="CHAT",
                         digest="a" * 64, route_revision="pilot-v1", deadline_ms=deadline, now_ms=1000)
    return ledger, job, JobExecutor(ledger, clock_ms=lambda: 2000)


def test_success_and_duplicate_never_reexecute(tmp_path):
    ledger, job, executor = setup(tmp_path)
    worker = Worker()
    first = asyncio.run(executor.execute(job.owner_id, job.id, worker))
    assert first.job.state == "SUCCEEDED"
    assert first.result == "synthetic-validated-result"
    second = asyncio.run(executor.execute(job.owner_id, job.id, worker))
    assert second.job.state == "SUCCEEDED"
    assert second.result is None
    assert worker.runs == worker.stops == 1
    ledger.engine.dispose()


@pytest.mark.parametrize("stopped", [False, None, 1])
def test_no_release_or_result_without_positive_stop_receipt(tmp_path, stopped):
    ledger, job, executor = setup(tmp_path)
    result = asyncio.run(executor.execute(job.owner_id, job.id, Worker(stopped=stopped)))
    assert result.job.state == "RUNNING"
    assert result.result is None
    assert ledger.get(job.owner_id, job.id).state == "RUNNING"
    ledger.engine.dispose()


def test_failure_is_terminal_only_after_stop(tmp_path):
    ledger, job, executor = setup(tmp_path)
    outcome = asyncio.run(executor.execute(job.owner_id, job.id, Worker(fail=True)))
    assert outcome.job.state == "FAILED"
    assert outcome.result is None
    ledger.engine.dispose()


def test_cancel_during_completion_discards_result(tmp_path):
    ledger, job, executor = setup(tmp_path)
    class CancelWorker(Worker):
        async def stop_and_confirm(self):
            ledger.cancel(job.owner_id, job.id, now_ms=2000)
            return True
    outcome = asyncio.run(executor.execute(job.owner_id, job.id, CancelWorker()))
    assert outcome.job.state == "CANCELLED"
    assert outcome.result is None
    ledger.engine.dispose()


def test_async_cancellation_stops_before_releasing_claim(tmp_path):
    ledger, job, executor = setup(tmp_path)
    async def run():
        started = asyncio.Event()
        class SlowWorker(Worker):
            async def run(self):
                started.set()
                await asyncio.Future()
        worker = SlowWorker()
        task = asyncio.create_task(executor.execute(job.owner_id, job.id, worker))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert worker.stops == 1
        assert ledger.get(job.owner_id, job.id).state == "CANCELLED"
    asyncio.run(run())
    ledger.engine.dispose()


def test_wrong_owner_and_policy_never_start_worker(tmp_path):
    ledger, job, executor = setup(tmp_path)
    worker = Worker()
    assert asyncio.run(executor.execute(str(uuid4()), job.id, worker)) is None
    worker.route_revision = "changed"
    with pytest.raises(ValueError, match="worker_binding_mismatch"):
        asyncio.run(executor.execute(job.owner_id, job.id, worker))
    assert worker.runs == 0
    assert ledger.get(job.owner_id, job.id).state == "QUEUED"
    ledger.engine.dispose()


def test_deadline_passed_after_claim_does_not_start_worker(tmp_path):
    ledger, job, _ = setup(tmp_path)
    times = iter([2000, 11_000, 11_000])
    executor = JobExecutor(ledger, clock_ms=lambda: next(times))
    worker = Worker()
    result = asyncio.run(executor.execute(job.owner_id, job.id, worker))
    assert worker.runs == 0
    assert worker.stops == 1
    assert result.result is None
    ledger.engine.dispose()


def test_worker_timeout_and_stop_exception_remain_claimed(tmp_path):
    ledger, job, executor = setup(tmp_path, deadline=2001)
    class TimeoutWorker(Worker):
        async def run(self):
            await asyncio.Future()
        async def stop_and_confirm(self):
            raise RuntimeError("unconfirmed_os_teardown")
    result = asyncio.run(executor.execute(job.owner_id, job.id, TimeoutWorker()))
    assert result.job.state == "RUNNING"
    assert result.result is None
    ledger.engine.dispose()


def test_second_cancel_during_stop_never_frees_capacity(tmp_path):
    ledger, job, executor = setup(tmp_path)
    async def run():
        running, stopping = asyncio.Event(), asyncio.Event()
        class BlockingWorker(Worker):
            async def run(self):
                running.set()
                await asyncio.Future()
            async def stop_and_confirm(self):
                stopping.set()
                await asyncio.Future()
        task = asyncio.create_task(executor.execute(job.owner_id, job.id, BlockingWorker()))
        await running.wait()
        task.cancel()
        await stopping.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert ledger.get(job.owner_id, job.id).state == "CANCEL_REQUESTED"
    asyncio.run(run())
    ledger.engine.dispose()
