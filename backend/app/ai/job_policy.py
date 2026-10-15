"""Trusted, non-client-configurable policy for the R1a CHAT route."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from uuid import UUID

from .meal_photo import (MAX_REQUEST_BODY_BYTES,
                         MAX_RESULT_BYTES as MAX_MEAL_RESULT_BYTES,
                         MealPhotoRequest)
from .schemas import (ChatRequest, MAX_RESPONSE_BYTES, MAX_RESULT_BYTES,
                      MAX_RESULT_CHARS, MAX_TEXT_BYTES, MAX_TEXT_CHARS)


@dataclass(frozen=True)
class JobPolicy:
    kind: str
    input_modality: str
    output_modality: str
    schema_revision: str
    prompt_revision: str
    model: str
    route_revision: str
    system_prompt: str
    max_text_chars: int
    max_text_bytes: int
    max_result_chars: int
    max_result_bytes: int
    max_response_bytes: int
    max_deadline_ms: int
    result_ttl_ms: int
    max_request_body_bytes: int
    allow_tools: bool
    allow_actions: bool


CHAT_POLICY = JobPolicy(
    kind="CHAT",
    input_modality="TEXT",
    output_modality="TEXT",
    schema_revision="chat-text-v1",
    prompt_revision="advisory-text-v1",
    model="trusted-server-model-v1",
    route_revision="server-codex-chat-r1a.1",
    system_prompt=(
        "Return advisory text only. Do not use tools, perform actions, or issue "
        "therapy commands. Do not claim that text replaces deterministic safety policy."
    ),
    max_text_chars=MAX_TEXT_CHARS,
    max_text_bytes=MAX_TEXT_BYTES,
    max_result_chars=MAX_RESULT_CHARS,
    max_result_bytes=MAX_RESULT_BYTES,
    max_response_bytes=MAX_RESPONSE_BYTES,
    max_deadline_ms=900_000,
    result_ttl_ms=900_000,
    max_request_body_bytes=16_384,
    allow_tools=False,
    allow_actions=False,
)


MEAL_PHOTO_POLICY = JobPolicy(
    kind="MEAL_PHOTO",
    input_modality="IMAGE",
    output_modality="MEAL_ESTIMATE",
    schema_revision="meal-photo-v1",
    prompt_revision="food-estimate-v1",
    model="trusted-server-vision-model-v1",
    route_revision="server-codex-meal-photo-r1.1",
    system_prompt=(
        "Return only the bounded food estimate schema. Do not use tools, open URLs, "
        "perform actions, infer therapy commands, or issue insulin, carbohydrate, "
        "target, calibration, or medical instructions."
    ),
    max_text_chars=MAX_MEAL_RESULT_BYTES,
    max_text_bytes=MAX_MEAL_RESULT_BYTES,
    max_result_chars=MAX_MEAL_RESULT_BYTES,
    max_result_bytes=MAX_MEAL_RESULT_BYTES,
    max_response_bytes=MAX_MEAL_RESULT_BYTES,
    max_deadline_ms=60_000,
    result_ttl_ms=900_000,
    max_request_body_bytes=MAX_REQUEST_BODY_BYTES,
    allow_tools=False,
    allow_actions=False,
)


def _canonical_uuid(value: str) -> str:
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError("canonical_uuid_required")
    return value


def request_digest(policy: JobPolicy, *, request_id: str, deadline_ms: int,
                   request: ChatRequest | MealPhotoRequest, body: bytes) -> str:
    _canonical_uuid(request_id)
    if (type(deadline_ms) is not int or not 0 < deadline_ms < 2**63
            or type(body) is not bytes):
        raise ValueError("invalid_deadline")
    if isinstance(request, ChatRequest):
        input_document = {"text": request.text}
    elif isinstance(request, MealPhotoRequest):
        input_document = {
            "schema_version": request.schema_version,
            "mime_type": request.mime_type,
            "image_sha256": hashlib.sha256(request.image_bytes).hexdigest(),
        }
    else:
        raise ValueError("invalid_request_type")
    document = {
        "allow_actions": policy.allow_actions,
        "allow_tools": policy.allow_tools,
        "body_sha256": hashlib.sha256(body).hexdigest(),
        "deadline_ms": deadline_ms,
        "input": input_document,
        "input_modality": policy.input_modality,
        "output_modality": policy.output_modality,
        "kind": policy.kind,
        "model": policy.model,
        "max_text_chars": policy.max_text_chars,
        "max_text_bytes": policy.max_text_bytes,
        "max_deadline_ms": policy.max_deadline_ms,
        "result_ttl_ms": policy.result_ttl_ms,
        "max_response_bytes": policy.max_response_bytes,
        "max_request_body_bytes": policy.max_request_body_bytes,
        "max_result_bytes": policy.max_result_bytes,
        "max_result_chars": policy.max_result_chars,
        "prompt_revision": policy.prompt_revision,
        "system_prompt_sha256": hashlib.sha256(
            policy.system_prompt.encode("utf-8")).hexdigest(),
        "request_id": request_id,
        "route_revision": policy.route_revision,
        "schema_revision": policy.schema_revision,
        "version": 1,
    }
    encoded = json.dumps(document, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def submit_proof_credential(access_token: str, *, request_id: str,
                            deadline_ms: int) -> str:
    _canonical_uuid(request_id)
    if (not isinstance(access_token, str) or "\n" in access_token
            or type(deadline_ms) is not int or not 0 < deadline_ms < 2**63):
        raise ValueError("invalid_proof_credential")
    return f"{access_token}\n{request_id}\n{deadline_ms}"
