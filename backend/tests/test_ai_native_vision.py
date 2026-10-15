"""Pinned wire tests with synthetic frames, no provider or file/URL image input."""
import asyncio
import base64
import copy
import importlib
import json

import pytest
from app.ai.meal_photo import MAX_IMAGE_BYTES
from app.ai.meal_vision import FOOD_ONLY_PROMPT
from app.ai.meal_vision_image import NormalizedMealImage
from test_ai_native_text import MODEL, Transport, transcript
from test_meal_vision import estimate_bytes


def component():
    return importlib.import_module("app.ai.native_vision")


def run(frames=None, image=None):
    module = component()
    transport = Transport(transcript(estimate_bytes().decode()) if frames is None else frames)
    policy = module.VisionSessionPolicy(MODEL)
    session = module.NativeMealVisionSession(transport, policy, containment_verified=True)
    result = asyncio.run(session.run(image if image is not None else NormalizedMealImage(
        b"\xff\xd8\xffsample-jpeg\xff\xd9", 32, 24)))
    return result, transport, session


def test_one_image_uses_exact_pinned_v2_schema_and_fixed_food_prompt():
    result, transport, session = run()
    assert json.loads(result) == json.loads(estimate_bytes())
    assert [frame["method"] for frame in transport.sent] == [
        "initialize", "initialized", "thread/start", "turn/start"]
    thread = transport.sent[2]["params"]
    assert thread["model"] == MODEL and not thread["allowProviderModelFallback"]
    assert thread["baseInstructions"] == FOOD_ONLY_PROMPT
    assert thread["dynamicTools"] == thread["environments"] == []
    assert "reasoningEffort" not in thread
    inputs = transport.sent[3]["params"]["input"]
    assert inputs[0] == {"type": "text", "text": FOOD_ONLY_PROMPT, "textElements": []}
    assert set(inputs[1]) == {"type", "url"} and inputs[1]["type"] == "image"
    assert base64.b64decode(inputs[1]["url"].split(",", 1)[1], validate=True).startswith(b"\xff\xd8\xff")
    with pytest.raises(component().NativeVisionError, match="already_used"):
        asyncio.run(session.run(NormalizedMealImage(b"image", 1, 1)))


@pytest.mark.parametrize("image", [
    "https://example.test/food.jpg", "/tmp/food.jpg", b"jpeg",
    NormalizedMealImage(b"bad", 1, 1),
    NormalizedMealImage(b"\xff\xd8\xff" + b"x" * MAX_IMAGE_BYTES + b"\xff\xd9", 1, 1),
    NormalizedMealImage(b"\xff\xd8\xffok\xff\xd9", 1601, 1),
    NormalizedMealImage(b"\xff\xd8\xffok\xff\xd9", True, 1),
])
def test_invalid_or_noninline_image_is_rejected_before_initialization(image):
    with pytest.raises(component().NativeVisionError):
        run([], image)


def test_food_result_can_exceed_chat_limit_but_is_schema_validated():
    estimate = json.loads(estimate_bytes())
    item = estimate["ingredients"][0]
    estimate["ingredients"] = [dict(item, id=str(i) + "x" * 80, name="x" * 120) for i in range(20)]
    result = json.dumps(estimate)
    assert len(result) > 8192
    assert json.loads(run(transcript(result))[0]) == estimate
    for bad in ("Sample answer", '{"text":"sample"}', result.replace('"schemaVersion": 1', '"schemaVersion": 2')):
        with pytest.raises(component().NativeVisionError):
            run(transcript(bad))


@pytest.mark.parametrize("event", [
    {"id": 41, "method": "item/commandExecution/requestApproval", "params": {"value": "private-sentinel"}},
    {"method": "item/completed", "params": {"threadId": "sample-thread", "turnId": "sample-turn",
        "item": {"type": "imageView", "path": "/tmp/private-sentinel"}}},
    {"method": "warning", "params": {"summary": "private-sentinel"}},
])
def test_action_or_unknown_native_event_never_becomes_a_result(event):
    frames = transcript(estimate_bytes().decode())
    frames.insert(6, event)
    with pytest.raises(component().NativeVisionError) as error:
        run(frames)
    assert "private-sentinel" not in str(error.value)


def test_exact_image_modality_and_model_required_without_changing_reasoning():
    module = component()
    catalog = {"models": [{"slug": MODEL, "input_modalities": ["text", "image"],
        "default_reasoning_level": "high", "tool_mode": "code_mode_only"}]}
    original = copy.deepcopy(catalog)
    selected = module.restricted_vision_catalog(catalog, MODEL)["models"][0]
    assert catalog == original
    assert selected["default_reasoning_level"] == "high" and selected["tool_mode"] == "direct"
    assert selected["input_modalities"] == ["text", "image"]
    for modalities in (None, ["text"], "image", ["image", "image"]):
        catalog["models"][0]["input_modalities"] = modalities
        with pytest.raises(ValueError):
            module.restricted_vision_catalog(catalog, MODEL)
    with pytest.raises(ValueError):
        module.restricted_vision_catalog(original, "foreign-model")


def test_maximum_image_echo_fits_only_the_vision_protocol():
    image = NormalizedMealImage(b"\xff\xd8\xff" + b"x" * (MAX_IMAGE_BYTES - 5) + b"\xff\xd9", 1600, 1600)
    frames = transcript(estimate_bytes().decode())
    echo = {"method": "item/completed", "params": {
        "threadId": "sample-thread", "turnId": "sample-turn", "item": {
            "type": "userMessage", "content": [{"type": "image", "url":
                "data:image/jpeg;base64," + base64.b64encode(image.jpeg).decode()}]}}}
    frames.insert(6, echo)
    assert json.loads(run(frames, image)[0]) == json.loads(estimate_bytes())


@pytest.mark.parametrize("change", ["model", "thread", "turn", "duplicate", "failed"])
def test_image_result_rejects_mismatched_or_ambiguous_completion(change):
    frames = transcript(estimate_bytes().decode())
    if change == "model":
        frames[3]["result"]["model"] = "foreign-model"
    elif change == "thread":
        frames[6]["params"]["threadId"] = "foreign-thread"
    elif change == "turn":
        frames[6]["params"]["turnId"] = "foreign-turn"
    elif change == "duplicate":
        frames.insert(7, copy.deepcopy(frames[6]))
    else:
        frames[7]["params"]["turn"]["status"] = "failed"
    with pytest.raises(component().NativeVisionError):
        run(frames)
