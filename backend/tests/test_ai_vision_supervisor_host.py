import asyncio
import importlib
import json
import os
import tempfile
from dataclasses import replace
from pathlib import Path

import pytest
from app.ai.text_supervisor import PeerIdentity
from app.ai.vision_supervisor import SupervisorMealVisionWorker
from test_ai_runtime_admission import image as _image
from test_ai_text_supervisor_host import configuration
from test_ai_vision_supervisor import Worker
from test_meal_vision import estimate_bytes, work

image = _image


def component():
    return importlib.import_module("app.ai.vision_supervisor_host")


def settings(image):
    value = configuration(image)
    public = dict(value["image"], root="/opt/personal-image/root", binary="/opt/personal-image/client")
    value["socket"] = "/run/personal-ai/image.sock"
    value["decoder"] = dict(image=public, uid=994, gid=986)
    return value


def test_configuration_keeps_decoder_identity_disjoint_from_api_and_profile(image):
    module = component()
    value = settings(image)
    config = module.VisionHostConfiguration.decode(json.dumps(value).encode())
    assert config.decoder_uid == 994 and config.native.guard.profile.uid == 993
    assert config.policy == component().meal_vision_policy("sample-model")
    for uid in (0, 1001, 993, True):
        value["decoder"]["uid"] = uid
        with pytest.raises(module.HostError):
            module.VisionHostConfiguration.decode(json.dumps(value).encode())


def test_host_builds_only_isolated_worker_after_both_image_admissions(image, monkeypatch):
    module = component()
    from app.ai import text_supervisor_host as text_host
    with tempfile.TemporaryDirectory(prefix="personal-image-", dir="/tmp") as directory:
        value = settings(image)
        value["socket"] = directory + "/image.sock"
        value["api"] = dict(uid=os.getuid(), gid=os.getgid())
        config = module.VisionHostConfiguration.decode(json.dumps(value).encode())
        monkeypatch.setattr(module, "_require_host", lambda: None)
        monkeypatch.setattr(module, "read_configuration", lambda path: config)
        monkeypatch.setattr(module, "check_decoder_idle", lambda: None)
        monkeypatch.setattr(text_host, "_parents", lambda path: None)
        monkeypatch.setattr(type(config.native), "check_idle", lambda self: None)
        monkeypatch.setattr(type(config.native.image), "verify", lambda *args: None)
        monkeypatch.setattr(type(config.decoder_image), "verify", lambda *args: None)
        monkeypatch.setattr(type(config.native.egress), "check_time", lambda self: None)
        constructed = []
        def factory(current, value, clock):
            assert current is config
            constructed.append(value)
            return Worker(value)
        monkeypatch.setattr(module, "isolated_worker", factory)
        async def scenario():
            stop, ready = asyncio.Event(), asyncio.Event()
            task = asyncio.create_task(module.serve(Path("/etc/personal-ai/image.json"), stop,
                                                   ready=ready, clock_ms=lambda: 1000))
            try:
                await asyncio.wait_for(ready.wait(), 2)
                value = replace(work(b"\xff\xd8\xffsample\xff\xd9"), policy=config.policy,
                                route_revision=config.policy.route_revision)
                runner = SupervisorMealVisionWorker(value, str(config.native.socket_path),
                    PeerIdentity(os.getuid(), os.getgid()), clock_ms=lambda: 1000)
                assert await runner.run() == json.loads(estimate_bytes())
                assert len(constructed) == 1
            finally:
                stop.set()
                assert await task
            assert not config.native.socket_path.exists()
        asyncio.run(scenario())
