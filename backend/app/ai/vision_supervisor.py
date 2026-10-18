"""Closed image-only Unix channel; no API-side decoding or native RPC relay."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
from dataclasses import dataclass

from .job_service import JobIdentity, JobWork
from .meal_photo import MealPhotoEstimate, MealPhotoRequest
from .meal_vision import FOOD_ONLY_PROMPT, MealVisionWorker, meal_vision_policy
from .text_supervisor import (
    SupervisorError,
    SupervisorTextRunner,
    TextSupervisor,
    _encode,
)

MAX_REQUEST = 1_500_000
MAX_RESPONSE = 131_072


def policy_digest(policy):
    if policy != meal_vision_policy(policy.model):
        raise ValueError("fixed_vision_policy_required")
    return hashlib.sha256(_encode(dict(task="MEAL_PHOTO", model=policy.model,
        route=policy.route_revision, prompt=FOOD_ONLY_PROMPT, request=MAX_REQUEST,
        response=MAX_RESPONSE, revision=1))).hexdigest()


def _serialize(work):
    return dict(jobId=work.job_id, requestDigest=work.request_digest, routeRevision=work.route_revision,
        deadlineMs=work.deadline_ms, acceptedMs=work.accepted_ms,
        ownerId=work.identity.owner_id, keyFingerprint=work.identity.key_fingerprint,
        imageBase64=base64.b64encode(work.request.image_bytes).decode("ascii"))


def _decode(value, policy, clock_ms):
    if type(value) is not dict or set(value) != {
            "jobId", "requestDigest", "routeRevision", "deadlineMs", "acceptedMs",
            "ownerId", "keyFingerprint", "imageBase64"}:
        raise SupervisorError("vision_work_rejected")
    request = MealPhotoRequest.decode(_encode(dict(schemaVersion=1, mimeType="image/jpeg",
                                                   imageBase64=value["imageBase64"])))
    work = JobWork(value["jobId"], request, value["requestDigest"], value["routeRevision"],
        value["deadlineMs"], policy, JobIdentity(value["ownerId"], value["keyFingerprint"]), value["acceptedMs"])
    checked = MealVisionWorker(work, clock_ms=clock_ms)
    checked._remaining()
    return work


def _result(value):
    return MealPhotoEstimate.decode(_encode(value)).as_dict()


@dataclass(frozen=True)
class _Payload:
    work: JobWork
    digest: str


class SupervisorMealVisionWorker(SupervisorTextRunner):
    """API-side worker sends raw bounded JPEG, never decodes or reads a profile."""
    usage_receipts = False
    max_request, max_response, run_seconds = MAX_REQUEST, MAX_RESPONSE, 60
    response_fields = SupervisorTextRunner.response_fields | {"bindingSha256"}
    _policy_digest = staticmethod(policy_digest)
    _result = staticmethod(_result)

    def __init__(self, work, path, peer, *, clock_ms):
        self._checked = MealVisionWorker(work, clock_ms=clock_ms)
        self.work = work
        self.route_revision, self.request_digest = work.route_revision, work.request_digest
        self._payload_json = _encode(_serialize(work))
        self.binding_digest = hashlib.sha256(self._payload_json).hexdigest()
        super().__init__(path, peer, work.policy)

    async def run(self):
        if self._task is not None or self._cancel:
            raise SupervisorError("vision_worker_already_used")
        self._checked._remaining()
        self._task = asyncio.create_task(self._exchange(self._payload_json))
        result = await asyncio.shield(self._task)
        self._checked._remaining()
        return result

    def _request(self, payload):
        return dict(version=1, runId=self.run_id, policySha256=self.digest, work=json.loads(payload))

    def _validate_extra(self, value):
        if value["bindingSha256"] != self.binding_digest:
            raise SupervisorError("vision_receipt_binding_rejected")


class VisionSupervisor(TextSupervisor):
    """Trusted-side fixed photo policy; factories must supply isolated workers."""
    usage_receipts = False
    max_request, max_response = MAX_REQUEST, MAX_RESPONSE
    _policy_digest = staticmethod(policy_digest)
    _result = staticmethod(_result)

    def __init__(self, policy, peer, worker_factory, *, clock_ms, run_seconds=60):
        if not callable(clock_ms) or not 0 < run_seconds <= 60:
            raise ValueError("vision_supervisor_configuration_rejected")
        self.clock_ms = clock_ms
        super().__init__(policy, peer, worker_factory, run_seconds=run_seconds)

    def _parse_request(self, value):
        self._header(value, {"version", "runId", "policySha256", "work"})
        work = _decode(value["work"], self.policy, self.clock_ms)
        return value["runId"], _Payload(work, hashlib.sha256(_encode(value["work"])).hexdigest())

    def _runner(self, payload):
        return self.factory(payload.work)

    async def _run(self, runner, payload):
        return await runner.run()

    def _receipt_extra(self, payload):
        return dict(bindingSha256=payload.digest)
