"""Synthetic admissions and actual local pipes; no privileged/provider action."""
import asyncio
import hashlib
import importlib
import json
from dataclasses import replace
from types import SimpleNamespace

import pytest
from app.ai import contained_text_process as text
from app.ai.meal_vision import MealVisionReply, MealVisionTurn
from app.ai.meal_vision_image import NormalizedMealImage
from app.ai.runtime_admission import ImageEntry
from test_ai_contained_text_process import host as _host
from test_ai_native_text import MODEL, POLICY, Transport, transcript
from test_ai_runtime_admission import egress
from test_ai_runtime_admission import image as _image
from test_meal_vision import estimate_bytes, work

host = _host
image = _image


def component():
    return importlib.import_module("app.ai.contained_vision")


def repin(image, data):
    target = image.root / "work/text-model.json"
    target.chmod(0o644)
    target.write_bytes(json.dumps(data).encode())
    target.chmod(0o444)
    entry = ImageEntry("work/text-model.json", 0o444, target.stat().st_size,
                       hashlib.sha256(target.read_bytes()).hexdigest())
    return replace(image, entries=tuple(entry if old.relative == entry.relative else old for old in image.entries))


def test_vision_image_requires_pinned_exact_image_modality(image):
    module = component()
    vision_image = module.VisionRuntimeImage(image.root, image.device, image.inode,
                                            image.entries, image.binary, image.binary_pin)
    policy = module.VisionSessionPolicy(MODEL)
    with pytest.raises(ValueError, match="image_model"):
        vision_image.verify(policy, egress().hosts)
    catalog = json.loads((image.root / "work/text-model.json").read_bytes())
    catalog["models"][0]["input_modalities"] = ["text", "image"]
    vision_image = repin(vision_image, catalog)
    vision_image.verify(policy, egress().hosts)
    text_image = repin(vision_image, dict(models=[dict(catalog["models"][0], input_modalities=["text"])]))
    with pytest.raises(ValueError, match="image_model"):
        text_image.verify(policy, egress().hosts)


def test_vision_process_has_separate_fixed_pipe_budget_and_policy(host):
    module = component()
    process, _, _, _, _ = host
    image = process.image
    vision_image = module.VisionRuntimeImage(image.root, image.device, image.inode,
                                            image.entries, image.binary, image.binary_pin)
    catalog = json.loads((image.root / "work/text-model.json").read_bytes())
    catalog["models"][0]["input_modalities"] = ["text", "image"]
    vision_image = repin(vision_image, catalog)
    policy = module.VisionSessionPolicy(MODEL)
    vision = module.SystemdVisionProcess(vision_image, process.guard, process.egress, policy)
    with pytest.raises(ValueError):
        module.SystemdVisionProcess(image, process.guard, process.egress, policy)
    async def scenario():
        with pytest.raises(text.ProcessError):
            await vision.start(POLICY)
        pipe = await vision.start(policy)
        assert type(pipe) is module.PipeVisionTransport
        assert pipe.send_limit > text.SEND_LIMIT
        assert await vision.stop_and_confirm()
    asyncio.run(scenario())


@pytest.mark.parametrize("host", [transcript(estimate_bytes().decode())], indirect=True)
def test_real_image_pipe_completes_after_shared_guard_and_exact_stop(host):
    module = component()
    process, events, children, calls, _ = host
    image = process.image
    vision_image = module.VisionRuntimeImage(image.root, image.device, image.inode,
                                            image.entries, image.binary, image.binary_pin)
    catalog = json.loads((image.root / "work/text-model.json").read_bytes())
    catalog["models"][0]["input_modalities"] = ["text", "image"]
    vision_image = repin(vision_image, catalog)
    policy = module.VisionSessionPolicy(MODEL)
    vision = module.SystemdVisionProcess(vision_image, process.guard, process.egress, policy)
    transport = module.NativeMealVisionTransport(vision, policy, clock_ms=lambda: 1000)
    async def scenario():
        result = await transport.estimate(turn())
        assert json.loads(result.result_json) == json.loads(estimate_bytes())
        assert events == ["reserved", "spawned", "bound", "verified", "stopped", "idle", "unlocked"]
        assert all(child.returncode is not None for child in children)
    asyncio.run(scenario())
    assert len(calls) == 1 and calls[0][1]["limit"] == module.PipeVisionTransport.line_limit


def test_image_sized_rpc_cannot_use_text_pipe_but_fits_vision_pipe():
    module = component()
    class Writer:
        def __init__(self):
            self.writes = []
        def write(self, raw):
            self.writes.append(raw)
        async def drain(self):
            pass
        def close(self):
            pass
    async def scenario():
        for pipe_type in (text.PipeTextTransport, module.PipeVisionTransport):
            writer = Writer()
            errors = asyncio.StreamReader()
            errors.feed_eof()
            pipe = pipe_type(SimpleNamespace(stdin=writer, stderr=errors))
            message = dict(id=3, params=dict(input=[dict(type="image", url="data:image/jpeg;base64," + "x" * 1_398_104)]))
            try:
                if pipe_type is text.PipeTextTransport:
                    with pytest.raises(text.ProcessError, match="input_limit"):
                        await pipe.send(message)
                    assert not writer.writes
                else:
                    await pipe.send(message)
                    assert len(writer.writes) == 1
                    with pytest.raises(text.ProcessError):
                        await pipe.send({"value": "x" * pipe.send_limit})
            finally:
                await pipe.close()
    asyncio.run(scenario())


class Process:
    def __init__(self, output=None, stopped=True):
        self.pipe = Transport(transcript((output or estimate_bytes()).decode()))
        self.stopped, self.starts, self.stops = stopped, 0, 0
    async def start(self, policy):
        self.starts += 1
        return self.pipe
    async def stop_and_confirm(self):
        self.stops += 1
        return self.stopped


def turn(model=MODEL):
    from app.ai.meal_vision import MealVisionBinding
    value = work(b"\xff\xd8\xffsample\xff\xd9")
    binding = MealVisionBinding(value.job_id, value.request_digest, value.route_revision,
                                value.identity, value.deadline_ms, model)
    return MealVisionTurn(binding, NormalizedMealImage(value.request.image_bytes, 32, 24))


@pytest.mark.parametrize("stopped", [True, False])
def test_result_requires_stop_and_exact_local_binding(stopped):
    module = component()
    process = Process(stopped=stopped)
    transport = module.NativeMealVisionTransport(process, module.VisionSessionPolicy(MODEL), clock_ms=lambda: 1000)
    value = turn()
    async def scenario():
        if stopped:
            reply = await transport.estimate(value)
            assert type(reply) is MealVisionReply and reply.binding == value.binding
            assert json.loads(reply.result_json) == json.loads(estimate_bytes())
        else:
            with pytest.raises(module.MealVisionError, match="stop_unconfirmed"):
                await transport.estimate(value)
        with pytest.raises(module.MealVisionError, match="already_used"):
            await transport.estimate(value)
    asyncio.run(scenario())
    assert process.starts == process.stops == 1
    serialized = json.dumps(process.pipe.sent)
    assert value.binding.job_id not in serialized and value.binding.identity.key_fingerprint not in serialized


def test_cancelled_start_is_joined_by_independent_stop():
    module = component()
    async def scenario():
        started, stopped = asyncio.Event(), asyncio.Event()
        class Pending(Process):
            async def start(self, policy):
                started.set()
                await stopped.wait()
                raise ValueError("private-sentinel")
            async def stop_and_confirm(self):
                stopped.set()
                return True
        transport = module.NativeMealVisionTransport(Pending(), module.VisionSessionPolicy(MODEL), clock_ms=lambda: 1000)
        task = asyncio.create_task(transport.estimate(turn()))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert stopped.is_set()
    asyncio.run(scenario())
