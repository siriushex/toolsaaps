"""Local lifecycle coordinator; NOT a contained-worker implementation.

Trusted workers bind immutable request/policy digests, validate their own typed
output and attest OS/cgroup teardown. Never adapt CodexRunner's return alone as
stop confirmation: escaped descendants require an independent OS receipt.
No public endpoint, background queue, payload persistence or model call here.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Callable, Protocol

from .job_ledger import Job, JobLedger


class ContainedWorker(Protocol):
    route_revision: str
    request_digest: str

    async def run(self) -> object: ...

    async def stop_and_confirm(self) -> bool: ...


@dataclass(frozen=True)
class ExecutionOutcome:
    job: Job
    result: object | None = None


class JobExecutor:
    def __init__(self, ledger: JobLedger, *, clock_ms: Callable[[], int] | None = None):
        self.ledger = ledger
        self.clock_ms = clock_ms or (lambda: time.time_ns() // 1_000_000)

    async def execute(self, owner_id: str, job_id: str, worker: ContainedWorker) -> ExecutionOutcome | None:
        job = await asyncio.to_thread(self.ledger.get, owner_id, job_id)
        if job is None:
            return None
        if job.state != "QUEUED":
            return ExecutionOutcome(job)
        if worker.route_revision != job.route_revision or worker.request_digest != job.digest:
            raise ValueError("worker_binding_mismatch")
        claim = await asyncio.to_thread(self.ledger.claim, owner_id, job_id, now_ms=self.clock_ms())
        if claim is None:
            return ExecutionOutcome(await asyncio.to_thread(self.ledger.get, owner_id, job_id))
        state, result, cancelled = "FAILED", None, False
        try:
            # Relative timeout uses the monotonic event-loop clock. Recheck the
            # absolute deadline again at settlement to reject late results.
            remaining = max(0, min(900, (claim.job.deadline_ms - self.clock_ms()) / 1000))
            if remaining <= 0:
                raise TimeoutError
            async with asyncio.timeout(remaining):
                result = await worker.run()
            state = "SUCCEEDED"
        except asyncio.CancelledError:
            cancelled = True
            await asyncio.to_thread(self.ledger.cancel, owner_id, job_id, now_ms=self.clock_ms())
        except Exception:
            # Exceptions and raw worker output must not enter the metadata DB.
            state = "FAILED"
        finally:
            stopped = False
            try:
                async with asyncio.timeout(20):
                    stopped = await worker.stop_and_confirm() is True
            except Exception:
                pass
            if stopped:
                job = await asyncio.to_thread(self.ledger.settle_stopped,
                    owner_id, job_id, claim.token, state, now_ms=self.clock_ms())
            else:
                # Unconfirmed workers retain global capacity, even if their
                # deadline expired. Startup reconciliation must inspect the OS.
                job = await asyncio.to_thread(self.ledger.get, owner_id, job_id)
        if cancelled:
            raise asyncio.CancelledError
        return ExecutionOutcome(job, result if job.state == "SUCCEEDED" else None)
