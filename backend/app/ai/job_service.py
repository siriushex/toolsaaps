"""Bounded volatile orchestration for the optional signed CHAT transport.

This module owns no launcher. A trusted factory must return a ContainedWorker
whose stop receipt is independently verified by that worker implementation.
"""
from __future__ import annotations

import asyncio
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Callable
from uuid import UUID

from .client_auth import AttestedDevice, AuthError, ClientAuth
from .job_executor import ContainedWorker, JobExecutor
from .job_ledger import Job, JobLedger, LedgerError
from .job_policy import CHAT_POLICY, JobPolicy, request_digest
from .request_proof import RequestProofVerifier
from .schemas import ChatRequest, ChatResult, SchemaError


class JobServiceError(Exception):
    def __init__(self, status: int, code: str):
        super().__init__(code)
        self.status = status
        self.code = code


@dataclass(frozen=True)
class JobWork:
    job_id: str
    request: ChatRequest
    request_digest: str
    route_revision: str
    deadline_ms: int
    policy: JobPolicy


@dataclass(frozen=True)
class _Payload:
    owner_id: str
    session_id: str
    key_fingerprint: str
    work: JobWork


@dataclass(frozen=True)
class _CachedResult:
    value: ChatResult
    expires_ms: int


class _ValidatingWorker:
    def __init__(self, work: JobWork, factory: Callable[[JobWork], ContainedWorker]):
        self._work = work
        self._factory = factory
        self._worker: ContainedWorker | None = None
        self._construction_attempted = False
        self.route_revision = work.route_revision
        self.request_digest = work.request_digest

    async def run(self):
        # JobExecutor calls run only after acquiring the durable global claim.
        self._construction_attempted = True
        self._worker = self._factory(self._work)
        if (self._worker.route_revision != self.route_revision
                or self._worker.request_digest != self.request_digest):
            raise ValueError("worker_binding_mismatch")
        return ChatResult.decode(await self._worker.run())

    async def stop_and_confirm(self) -> bool:
        if self._worker is None:
            # A failed constructor may have started resources before raising.
            return not self._construction_attempted
        return await self._worker.stop_and_confirm()


class AiJobService:
    MAX_VOLATILE_PAYLOADS = 6
    MAX_VOLATILE_RESULTS = 100
    MAX_HTTP_BODY_BYTES = 16_384

    def __init__(self, auth: ClientAuth, proofs: RequestProofVerifier, ledger: JobLedger,
                 worker_factory: Callable[[JobWork], ContainedWorker], *,
                 policy: JobPolicy = CHAT_POLICY, clock_ms=None):
        if (not isinstance(auth, ClientAuth) or not isinstance(proofs, RequestProofVerifier)
                or not isinstance(ledger, JobLedger) or not callable(worker_factory)
                or not 1 <= ledger.max_waiting <= 5
                or not isinstance(policy, JobPolicy)
                or policy.kind != "CHAT" or policy.input_modality != "TEXT"
                or policy.output_modality != "TEXT" or policy.allow_tools
                or policy.allow_actions or type(policy.result_ttl_ms) is not int
                or not 0 < policy.result_ttl_ms <= 900_000
                or any(type(getattr(policy, name)) is not int
                       or getattr(policy, name) != getattr(CHAT_POLICY, name)
                       for name in ("max_text_chars", "max_text_bytes", "max_result_chars",
                                    "max_result_bytes", "max_response_bytes", "max_deadline_ms"))):
            raise ValueError("invalid_job_service")
        self.auth = auth
        self.proofs = proofs
        self.ledger = ledger
        self.worker_factory = worker_factory
        self.policy = policy
        self.clock_ms = clock_ms or (lambda: time.time_ns() // 1_000_000)
        self.executor = JobExecutor(ledger, clock_ms=self.clock_ms)
        self._payloads: OrderedDict[str, _Payload] = OrderedDict()
        self._results: OrderedDict[str, _CachedResult] = OrderedDict()
        self._event = asyncio.Event()
        self._lock = asyncio.Lock()
        self._drain_task: asyncio.Task | None = None
        self._active_task: asyncio.Task | None = None
        self._active_job_id: str | None = None
        self._closing = False
        self._failed = False

    @property
    def inference_ready(self) -> bool:
        return (not self._closing and not self._failed and self._drain_task is not None
                and not self._drain_task.done())

    def preauthorize(self, access_token: str, key_fingerprint: str,
                     now_ms: int) -> AttestedDevice:
        device = self.auth.authenticate_attested(access_token,
            key_fingerprint=key_fingerprint, now_ms=now_ms)
        if (device.subscription_expires_ms is None
                or now_ms >= device.subscription_expires_ms):
            raise AuthError()
        return device

    def verify_request(self, *, preauthorized: AttestedDevice, access_token: str,
                       key_fingerprint: str, method: str, path: str, body: bytes,
                       proof_credential: str, signature: str, issued_ms: int,
                       nonce: str, now_ms: int) -> AttestedDevice:
        if (preauthorized.key_fingerprint != key_fingerprint
                or not preauthorized.session_id):
            raise AuthError()
        self.proofs.verify(public_key_der=preauthorized.public_key_der,
            signature=signature, method=method, path=path, body=body,
            credential=proof_credential, issued_ms=issued_ms, nonce=nonce,
            now_ms=now_ms)
        current = self.preauthorize(access_token, key_fingerprint, now_ms)
        if (current.session_id != preauthorized.session_id
                or current.owner_id != preauthorized.owner_id):
            raise AuthError()
        return current

    async def start(self) -> None:
        if self._closing or self._failed:
            raise RuntimeError("job_service_closed")
        if self._drain_task is not None:
            return
        await asyncio.to_thread(self.ledger.abandon_queued, now_ms=self.clock_ms())
        self._drain_task = asyncio.create_task(self._drain())

    async def close(self) -> None:
        if self._drain_task is None:
            return
        self._closing = True
        async with self._lock:
            queued = list(self._payloads.items())
            active = self._active_task
        for job_id, payload in queued:
            await asyncio.to_thread(self.ledger.cancel, payload.owner_id, job_id,
                                    now_ms=self.clock_ms())
        if active is not None and not active.done():
            active.cancel()
            try:
                await active
            except asyncio.CancelledError:
                pass
        self._event.set()
        await self._drain_task
        async with self._lock:
            self._payloads.clear()
            self._results.clear()
            self._active_task = None
            self._active_job_id = None
        self._drain_task = None

    def capabilities(self) -> dict[str, object]:
        return {
            "revision": self.policy.route_revision,
            "inference_enabled": self.inference_ready,
            "task_kinds": [self.policy.kind],
            "input_modalities": [self.policy.input_modality],
            "output_modalities": [self.policy.output_modality],
            "max_text_chars": self.policy.max_text_chars,
            "max_text_bytes": self.policy.max_text_bytes,
            "max_result_chars": self.policy.max_result_chars,
            "max_result_bytes": self.policy.max_result_bytes,
            "max_response_bytes": self.policy.max_response_bytes,
            "max_deadline_ms": self.policy.max_deadline_ms,
        }

    async def submit(self, device: AttestedDevice, *, request_id: str,
                     deadline_ms: int, body: bytes, now_ms: int) -> dict[str, object]:
        if not self.inference_ready:
            raise JobServiceError(503, "service_unavailable")
        try:
            request = ChatRequest.decode(body)
            digest = request_digest(self.policy, request_id=request_id,
                                    deadline_ms=deadline_ms, request=request, body=body)
            job, created = await asyncio.to_thread(self.ledger.reserve_once,
                owner_id=device.owner_id, request_id=request_id, kind=self.policy.kind,
                digest=digest, route_revision=self.policy.route_revision,
                deadline_ms=deadline_ms, now_ms=now_ms,
                session_id=device.session_id, key_hash=device.key_fingerprint)
        except SchemaError:
            raise JobServiceError(400, "invalid_request") from None
        except ValueError:
            raise JobServiceError(400, "invalid_request") from None
        except LedgerError as exc:
            if str(exc) == "request_conflict":
                raise JobServiceError(409, "request_conflict") from None
            if str(exc) in {"owner_busy", "queue_full"}:
                raise JobServiceError(429, str(exc)) from None
            raise
        if created:
            work = JobWork(job_id=job.id, request=request, request_digest=digest,
                           route_revision=self.policy.route_revision,
                           deadline_ms=deadline_ms, policy=self.policy)
            rejection = None
            async with self._lock:
                if not self.inference_ready:
                    rejection = JobServiceError(503, "service_unavailable")
                elif len(self._payloads) >= self.MAX_VOLATILE_PAYLOADS:
                    rejection = JobServiceError(429, "queue_full")
                else:
                    self._payloads[job.id] = _Payload(device.owner_id, device.session_id,
                                                     device.key_fingerprint, work)
            if rejection is not None:
                try:
                    await asyncio.to_thread(self.ledger.cancel, device.owner_id,
                                            job.id, now_ms=now_ms)
                except Exception:
                    pass
                raise rejection
            self._event.set()
        return await self.get(device, job.id, now_ms=now_ms)

    async def get(self, device: AttestedDevice, job_id: str,
                  *, now_ms: int) -> dict[str, object]:
        self._canonical_job_id(job_id)
        job = await asyncio.to_thread(self.ledger.get, device.owner_id, job_id,
            session_id=device.session_id, key_hash=device.key_fingerprint)
        if job is None:
            raise JobServiceError(404, "not_found")
        async with self._lock:
            cached = self._results.get(job_id)
            if cached is not None and cached.expires_ms <= now_ms:
                self._results.pop(job_id, None)
                cached = None
            available = (job_id in self._payloads or job_id == self._active_job_id)
        return self._receipt(job, cached=cached, unavailable=not available,
                             pending_result=available and cached is None and job.state == "SUCCEEDED")

    async def cancel(self, device: AttestedDevice, job_id: str,
                     *, now_ms: int) -> dict[str, object]:
        self._canonical_job_id(job_id)
        job = await asyncio.to_thread(self.ledger.cancel, device.owner_id,
            job_id, now_ms=now_ms, session_id=device.session_id,
            key_hash=device.key_fingerprint)
        if job is None:
            raise JobServiceError(404, "not_found")
        async with self._lock:
            if job.state == "CANCELLED":
                self._payloads.pop(job_id, None)
            active = self._active_task if job_id == self._active_job_id else None
        if active is not None and not active.done():
            active.cancel()
            try:
                await active
            except asyncio.CancelledError:
                pass
            job = await asyncio.to_thread(self.ledger.get, device.owner_id, job_id,
                session_id=device.session_id, key_hash=device.key_fingerprint)
        self._event.set()
        return self._receipt(job, unavailable=job.state in {"RUNNING", "CANCEL_REQUESTED"})

    @staticmethod
    def _canonical_job_id(job_id: str) -> None:
        try:
            if not isinstance(job_id, str) or str(UUID(job_id)) != job_id:
                raise ValueError()
        except ValueError:
            raise JobServiceError(404, "not_found") from None

    def _receipt(self, job: Job, *, cached: _CachedResult | None = None,
                 unavailable: bool = False, pending_result: bool = False) -> dict[str, object]:
        state = "RUNNING" if pending_result else "UNKNOWN" if unavailable and job.state in {
            "QUEUED", "RUNNING", "CANCEL_REQUESTED"
        } else job.state
        result = {
            "job_id": job.id,
            "request_id": job.request_id,
            "kind": job.kind,
            "state": state,
            "created_ms": job.created_ms,
            "deadline_ms": job.deadline_ms,
            "started_ms": job.started_ms,
            "finished_ms": None if pending_result else job.finished_ms,
            "result_available": cached is not None and state == "SUCCEEDED",
        }
        if cached is not None and state == "SUCCEEDED":
            result["result"] = cached.value.as_dict()
            result["result_expires_ms"] = cached.expires_ms
        return result

    async def _drain(self) -> None:
        try:
            await self._drain_until_closed()
        except (Exception, asyncio.CancelledError):
            self._failed = True
            async with self._lock:
                self._results.clear()
                self._payloads.clear()
                active = self._active_task
            if active is not None:
                if not active.done():
                    active.cancel()
                try:
                    await active
                except (Exception, asyncio.CancelledError):
                    pass
            async with self._lock:
                self._active_task = None
                self._active_job_id = None

    async def _drain_until_closed(self) -> None:
        while True:
            self._event.clear()
            await self._expire_volatile()
            if self._closing:
                return
            can_dispatch = await self._collect_active()
            if can_dispatch:
                await self._dispatch_next()
            timeout = await self._next_expiry_delay()
            try:
                if timeout is None:
                    await self._event.wait()
                else:
                    await asyncio.wait_for(self._event.wait(), timeout)
            except TimeoutError:
                pass

    def _active_finished(self, task: asyncio.Task) -> None:
        if self._active_task is task:
            self._event.set()

    async def _collect_active(self) -> bool:
        async with self._lock:
            active = self._active_task
            job_id = self._active_job_id
        if active is None:
            return True
        if not active.done():
            return False
        try:
            outcome = active.result()
        except asyncio.CancelledError:
            outcome = None
        except Exception:
            outcome = None
        async with self._lock:
            if self._active_task is active:
                self._active_task = None
                self._active_job_id = None
            if outcome is None:
                self._payloads.pop(job_id, None)
                return True
            if outcome.job.state == "QUEUED":
                return False
            self._payloads.pop(job_id, None)
            if outcome.job.state == "SUCCEEDED" and isinstance(outcome.result, ChatResult):
                expires = min(self.clock_ms() + self.policy.result_ttl_ms, 2**63 - 1)
                self._results[job_id] = _CachedResult(outcome.result, expires)
                while len(self._results) > self.MAX_VOLATILE_RESULTS:
                    self._results.popitem(last=False)
        return True

    async def _dispatch_next(self) -> None:
        while not self._closing:
            async with self._lock:
                if self._active_task is not None:
                    return
                pending = next(iter(self._payloads.items()), None)
            if pending is None:
                return
            job_id, payload = pending
            try:
                await asyncio.to_thread(self.auth.authorize_attested_session,
                    session_id=payload.session_id,
                    key_fingerprint=payload.key_fingerprint,
                    now_ms=self.clock_ms())
            except AuthError:
                await asyncio.to_thread(self.ledger.cancel, payload.owner_id,
                                        job_id, now_ms=self.clock_ms())
                async with self._lock:
                    self._payloads.pop(job_id, None)
                continue
            worker = _ValidatingWorker(payload.work, self.worker_factory)
            async with self._lock:
                if self._closing:
                    return
                active = asyncio.create_task(self.executor.execute(
                    payload.owner_id, job_id, worker))
                self._active_task = active
                self._active_job_id = job_id
            active.add_done_callback(self._active_finished)
            return

    async def _next_expiry_delay(self) -> float | None:
        async with self._lock:
            deadlines = [payload.work.deadline_ms for job_id, payload in self._payloads.items()
                         if job_id != self._active_job_id]
            deadlines.extend(cached.expires_ms for cached in self._results.values())
        if not deadlines:
            return None
        return max(0, (min(deadlines) - self.clock_ms()) / 1000)

    async def _expire_volatile(self) -> None:
        now_ms = self.clock_ms()
        await asyncio.to_thread(self.ledger.expire_queued, now_ms=now_ms)
        async with self._lock:
            for job_id, payload in list(self._payloads.items()):
                if job_id != self._active_job_id and payload.work.deadline_ms <= now_ms:
                    self._payloads.pop(job_id, None)
            for job_id, cached in list(self._results.items()):
                if cached.expires_ms <= now_ms:
                    self._results.pop(job_id, None)
