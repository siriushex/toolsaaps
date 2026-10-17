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


def admission_reader(monkeypatch, image, change=None):
    module = component()
    value = settings(image)
    original = module.VisionHostConfiguration.decode(json.dumps(value).encode())
    source = configuration(image)
    if change is not None:
        change(source, original)
    current = module.HostConfiguration.decode(json.dumps(source).encode())
    vision_path, source_path = Path("/etc/personal-ai/image.json"), Path("/etc/personal-ai/text.json")
    calls = []
    def read(path, **kwargs):
        calls.append(path)
        assert path in (vision_path, source_path)
        return original if path == vision_path else current
    monkeypatch.setattr(module, "read_protected", read)
    return module, original, current, vision_path, source_path, calls


def test_vision_can_follow_verified_public_text_admission_without_changing_identity(image, monkeypatch):
    def renew(value, original):
        value["image"]["root"] = "/opt/personal-image/renewed"
        value["egress"]["admitted_at"] += 100
        value["egress"]["expires_at"] += 100
    module, original, current, path, source, calls = admission_reader(monkeypatch, image, renew)
    value = module.read_configuration(path, admission_source=source)
    assert calls == [path, source]
    assert value.fixed_identity() == original.fixed_identity()
    assert value.decoder_image == original.decoder_image
    assert value.native.image.root == current.image.root
    assert value.native.image.root != original.native.image.root
    assert value.native.egress == current.egress
    assert value.native.egress != original.native.egress
    assert type(value.native.image) is module.VisionRuntimeImage
    assert value.native.socket_path == original.native.socket_path


@pytest.mark.parametrize("field", ["model", "api", "profile", "control", "scope"])
def test_public_admission_cannot_change_private_identity_or_model(image, monkeypatch, field):
    def change(value, original):
        if field == "model":
            value[field] = "another-sample-model"
        elif field == "api":
            value[field]["uid"] += 1
        elif field in {"profile", "control"}:
            value[field]["inode"] += 1
        else:
            value[field]["slice_id"] = "11111111-1111-4111-8111-111111111111"
    module, _, _, path, source, _ = admission_reader(monkeypatch, image, change)
    with pytest.raises(module.HostError, match="vision_admission_identity_changed"):
        module.read_configuration(path, admission_source=source)


def test_followed_native_image_cannot_overlap_the_offline_decoder(image, monkeypatch):
    def change(value, original):
        value["image"]["root"] = str(original.decoder_image.root)
    module, _, _, path, source, _ = admission_reader(monkeypatch, image, change)
    with pytest.raises(module.HostError, match="vision_admission_layout_rejected"):
        module.read_configuration(path, admission_source=source)


def test_no_admission_source_preserves_explicit_fixed_descriptor(image, monkeypatch):
    module, original, _, path, _, calls = admission_reader(monkeypatch, image)
    assert module.read_configuration(path) is original
    assert calls == [path]


def test_vision_descriptor_cannot_be_its_own_text_admission(image, monkeypatch):
    module, _, _, path, _, calls = admission_reader(monkeypatch, image)
    with pytest.raises(module.HostError, match="vision_admission_identity_changed"):
        module.read_configuration(path, admission_source=path)
    assert calls == [path]


def test_unreadable_text_admission_cannot_fall_back_to_stale_values(image, monkeypatch):
    module, original, _, path, source, calls = admission_reader(monkeypatch, image)
    def read(value, **kwargs):
        if value == source:
            raise module.HostError("host_configuration_unavailable")
        return original
    monkeypatch.setattr(module, "read_protected", read)
    with pytest.raises(module.HostError, match="host_configuration_unavailable"):
        module.read_configuration(path, admission_source=source)


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
