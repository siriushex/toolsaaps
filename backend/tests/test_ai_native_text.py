import asyncio
import copy
import json

import pytest

from app.ai.native_text import NativeTextError, NativeTextSession, TextSessionPolicy, restricted_catalog


MODEL = "sample-model"
POLICY = TextSessionPolicy(MODEL, "Provide an advisory answer based on supplied observations.")


def transcript(answer="Synthetic answer"):
    return [
        {"id": 1, "result": {"userAgent": "personal-ai/0.153.4 synthetic"}},
        {"method": "remoteControl/status/changed", "params": {"status": "disabled", "environmentId": None}},
        {"method": "thread/started", "params": {"thread": {"id": "sample-thread"}}},
        {"id": 2, "result": {"model": MODEL, "instructionSources": [], "thread": {"id": "sample-thread"},
            "cwd": "/work", "approvalPolicy": "never", "sandbox": {"type": "readOnly", "networkAccess": False}}},
        {"id": 3, "result": {"turn": {"id": "sample-turn"}}},
        {"method": "turn/started", "params": {"threadId": "sample-thread", "turn": {"id": "sample-turn"}}},
        {"method": "item/completed", "params": {"threadId": "sample-thread", "turnId": "sample-turn",
            "item": {"id": "sample-item", "type": "agentMessage", "phase": "final_answer", "text": answer}}},
        {"method": "turn/completed", "params": {"threadId": "sample-thread",
            "turn": {"id": "sample-turn", "status": "completed", "error": None}}},
    ]


class Transport:
    def __init__(self, frames):
        self.frames = iter(frames)
        self.sent = []

    async def send(self, value):
        self.sent.append(copy.deepcopy(value))

    async def receive(self):
        value = next(self.frames)
        if isinstance(value, BaseException):
            raise value
        return value if isinstance(value, bytes) else json.dumps(value).encode()


def run(frames, *, text="Sample question", policy=POLICY):
    transport = Transport(frames)
    session = NativeTextSession(transport, policy, containment_verified=True)
    return asyncio.run(session.run(text)), transport


def test_completed_text_uses_one_ephemeral_turn_and_preserves_model_settings():
    result, transport = run(transcript())
    assert result == {"text": "Synthetic answer"}
    assert [value["method"] for value in transport.sent] == [
        "initialize", "initialized", "thread/start", "turn/start"]
    params = transport.sent[2]["params"]
    assert params["model"] == MODEL and params["allowProviderModelFallback"] is False
    assert params["dynamicTools"] == params["environments"] == params["selectedCapabilityRoots"] == []
    assert params["ephemeral"] is True
    assert "reasoningEffort" not in params
    assert transport.sent[3]["params"]["environments"] == []


def test_completion_before_rpc_ack_is_correlated_to_the_acknowledged_turn():
    frames = transcript()
    acknowledgement = frames.pop(4)
    frames.append(acknowledgement)
    assert run(frames)[0]["text"] == "Synthetic answer"
    frames[-1]["result"]["turn"]["id"] = "foreign-turn"
    with pytest.raises(NativeTextError, match="correlation"):
        run(frames)


def test_status_before_thread_ack_requires_the_announced_thread():
    frames = transcript()
    frames.insert(3, {"method": "thread/status/changed", "params": {
        "threadId": "sample-thread", "status": {"type": "idle"}}})
    assert run(frames)[0]["text"] == "Synthetic answer"
    frames[3]["params"]["threadId"] = "foreign-thread"
    with pytest.raises(NativeTextError, match="correlation"):
        run(frames)
    frames.pop(2)
    with pytest.raises(NativeTextError, match="correlation"):
        run(frames)


@pytest.mark.parametrize("change", [
    lambda frames: frames[6]["params"].update(threadId="foreign-thread"),
    lambda frames: frames[6]["params"].update(turnId="foreign-turn"),
    lambda frames: frames[7]["params"]["turn"].update(status="failed"),
    lambda frames: frames[3]["result"].update(model="other-model"),
    lambda frames: frames[3]["result"].update(instructionSources=["unexpected"]),
    lambda frames: frames[3]["result"].update(approvalPolicy="on-request"),
    lambda frames: frames[3]["result"].update(sandbox={"type": "dangerFullAccess"}),
])
def test_foreign_or_failed_turn_never_becomes_an_answer(change):
    frames = transcript()
    change(frames)
    with pytest.raises(NativeTextError):
        run(frames)


@pytest.mark.parametrize("event", [
    {"id": 91, "method": "item/commandExecution/requestApproval", "params": {"secret": "sample-private"}},
    {"method": "item/started", "params": {"threadId": "sample-thread", "turnId": "sample-turn",
        "item": {"type": "commandExecution"}}},
    {"method": "warning", "params": {"message": "sample-private"}},
    {"method": "error", "params": {"message": "sample-private"}},
    {"method": "remoteControl/status/changed", "params": {"status": "connected"}},
    {"method": "configWarning", "params": {"summary": "sample-private"}},
    {"method": "unrecognized", "params": {}},
])
def test_actions_unknown_events_and_warnings_are_refused_without_payload_disclosure(event):
    frames = transcript()
    frames.insert(6, event)
    with pytest.raises(NativeTextError) as error:
        run(frames)
    assert "sample-private" not in str(error.value)


@pytest.mark.parametrize("raw", [b'{"id":1,"id":1,"result":{}}', b'{"id":true,"result":{}}',
                                 b'[]', b'{"id":1,"result":{"value":NaN}}', b'x' * 65_537])
def test_malformed_or_overlong_frame_is_refused(raw):
    with pytest.raises(NativeTextError):
        run([raw])


def test_duplicate_or_oversized_answer_and_missing_final_fail():
    frames = transcript()
    frames.insert(7, copy.deepcopy(frames[6]))
    with pytest.raises(NativeTextError, match="duplicate_answer"):
        run(frames)
    with pytest.raises(NativeTextError):
        run(transcript("x" * 8193))
    frames = transcript()
    frames.pop(6)
    with pytest.raises(NativeTextError, match="missing_answer"):
        run(frames)


def test_limits_and_cancellation_are_not_retried():
    with pytest.raises(NativeTextError, match="input_limit"):
        run([], text="longer", policy=TextSessionPolicy(MODEL, "Sample", max_input_bytes=2))
    with pytest.raises(NativeTextError, match="stream_limit"):
        run(transcript(), policy=TextSessionPolicy(MODEL, "Sample", max_events=2))
    with pytest.raises(asyncio.CancelledError):
        run([asyncio.CancelledError()])


def test_invalid_unicode_input_is_a_fixed_error_before_native_io():
    with pytest.raises(NativeTextError, match="input_limit"):
        run([], text="\ud800")


def test_session_and_containment_admission_cannot_be_reused():
    with pytest.raises(ValueError, match="contained"):
        NativeTextSession(Transport([]), POLICY, containment_verified=False)
    session = NativeTextSession(Transport(transcript()), POLICY, containment_verified=True)
    asyncio.run(session.run("Sample"))
    with pytest.raises(NativeTextError, match="already_used"):
        asyncio.run(session.run("Sample"))


def test_tool_catalog_restricts_dispatch_and_preserves_identity_and_reasoning():
    source = {"models": [{"slug": MODEL, "tool_mode": "code_mode_only", "default_reasoning_level": "medium",
        "experimental_supported_tools": ["clock"], "shell_type": "unified_exec",
        "apply_patch_tool_type": "freeform", "supports_search_tool": True, "base_instructions": "Sample"}]}
    original = copy.deepcopy(source)
    value = restricted_catalog(source, MODEL)["models"][0]
    assert source == original
    assert value["tool_mode"] == "direct" and value["shell_type"] == "disabled"
    assert value["experimental_supported_tools"] == []
    assert value["apply_patch_tool_type"] is None and value["supports_search_tool"] is False
    assert value["slug"] == MODEL and value["default_reasoning_level"] == "medium"
    assert value["base_instructions"] == "Sample"


def test_changed_settings_or_foreign_model_notification_are_rejected():
    frames = transcript()
    frames.insert(6, {"method": "thread/settings/updated", "params": {
        "threadId": "sample-thread", "threadSettings": {"model": MODEL, "cwd": "/work",
            "approvalPolicy": "never", "sandboxPolicy": {"type": "readOnly", "networkAccess": False}}}})
    assert run(frames)[0]["text"] == "Synthetic answer"
    frames[6]["params"]["threadSettings"]["model"] = "foreign-model"
    with pytest.raises(NativeTextError, match="configuration"):
        run(frames)
    frames[6] = {"method": "model/verification", "params": {
        "threadId": "sample-thread", "turnId": "foreign-turn", "verifications": []}}
    with pytest.raises(NativeTextError, match="correlation"):
        run(frames)
