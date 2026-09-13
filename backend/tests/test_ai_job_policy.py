import json
from dataclasses import replace
from uuid import uuid4

import pytest


def test_chat_request_accepts_only_bounded_text():
    from app.ai.schemas import ChatRequest, SchemaError

    body = json.dumps({"text": "synthetic hello"}, separators=(",", ":")).encode()
    assert ChatRequest.decode(body).text == "synthetic hello"

    invalid = (
        b'{"text":"first","text":"second"}',
        b'{"text":"hello","model":"client-choice"}',
        b'{"text":1}',
        b'{"text":{"nested":true}}',
        b'{"text":NaN}',
        b'{"text":""}',
        json.dumps({"text": "x" * 4097}).encode(),
        json.dumps({"text": "\u20ac" * 3000}, ensure_ascii=False).encode(),
    )
    for candidate in invalid:
        with pytest.raises(SchemaError, match="^invalid_request$"):
            ChatRequest.decode(candidate)


def test_worker_result_is_strict_text_only():
    from app.ai.schemas import ChatResult, SchemaError

    assert ChatResult.decode({"text": "synthetic response"}) == ChatResult("synthetic response")
    for candidate in ({"text": "ok", "tool": "called"}, {"text": 1}, "raw", None):
        with pytest.raises(SchemaError, match="^invalid_result$"):
            ChatResult.decode(candidate)


def test_request_digest_binds_full_request_and_trusted_route_policy():
    from app.ai.job_policy import CHAT_POLICY, request_digest
    from app.ai.schemas import ChatRequest

    request_id = str(uuid4())
    request = ChatRequest("synthetic hello")
    body = b'{"text":"synthetic hello"}'
    deadline_ms = 1_800_000_120_000
    digest = request_digest(CHAT_POLICY, request_id=request_id, deadline_ms=deadline_ms,
                            request=request, body=body)
    assert digest == request_digest(CHAT_POLICY, request_id=request_id,
                                    deadline_ms=deadline_ms, request=request, body=body)
    variants = (
        dict(request_id=str(uuid4())),
        dict(deadline_ms=deadline_ms + 1),
        dict(request=ChatRequest("synthetic changed")),
        dict(body=b'{ "text": "synthetic hello" }'),
        dict(policy=replace(CHAT_POLICY, route_revision="server-codex-chat-r1a.2")),
        dict(policy=replace(CHAT_POLICY, model="trusted-model-revision-2")),
        dict(policy=replace(CHAT_POLICY, prompt_revision="advisory-text-v2")),
    )
    baseline = dict(policy=CHAT_POLICY, request_id=request_id,
                    deadline_ms=deadline_ms, request=request, body=body)
    assert all(request_digest(**(baseline | variant)) != digest for variant in variants)


def test_every_trusted_policy_field_changes_the_digest():
    from dataclasses import fields
    from app.ai.job_policy import CHAT_POLICY, request_digest
    from app.ai.schemas import ChatRequest

    args = dict(request_id=str(uuid4()), deadline_ms=1_800_000_120_000,
                request=ChatRequest("synthetic"), body=b'{"text":"synthetic"}')
    expected = request_digest(CHAT_POLICY, **args)
    for field in fields(CHAT_POLICY):
        value = getattr(CHAT_POLICY, field.name)
        changed = (not value if type(value) is bool else
                   value + 1 if type(value) is int else value + "-changed")
        assert request_digest(replace(CHAT_POLICY, **{field.name: changed}), **args) != expected, field.name


def test_policy_is_closed_to_chat_text_and_advisory_execution():
    from app.ai.job_policy import CHAT_POLICY

    assert CHAT_POLICY.kind == "CHAT"
    assert CHAT_POLICY.input_modality == CHAT_POLICY.output_modality == "TEXT"
    assert CHAT_POLICY.max_text_chars == 4096
    assert CHAT_POLICY.max_text_bytes == 8192
    assert CHAT_POLICY.max_deadline_ms <= 900_000
    assert CHAT_POLICY.result_ttl_ms <= 900_000
    assert CHAT_POLICY.allow_tools is False
    assert CHAT_POLICY.allow_actions is False
    assert "advisory" in CHAT_POLICY.system_prompt.lower()
    assert "therapy" in CHAT_POLICY.system_prompt.lower()


def test_submit_proof_credential_binds_request_headers_without_exposing_policy_controls():
    from app.ai.job_policy import submit_proof_credential

    request_id = str(uuid4())
    credential = submit_proof_credential("access.synthetic", request_id=request_id,
                                         deadline_ms=1_800_000_120_000)
    assert credential == f"access.synthetic\n{request_id}\n1800000120000"
    assert "model" not in credential
    assert "route" not in credential
