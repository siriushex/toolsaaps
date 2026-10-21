"""Actual local sockets and synthetic workers, never provider/credential access."""
import asyncio
import importlib
import json
import os
import socket
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from app.ai.meal_vision import MealVisionError
from app.ai.native_text import TextSessionPolicy
from app.ai.text_supervisor import (
    PeerIdentity,
    SupervisorError,
    SupervisorTextRunner,
    _write,
)
from test_meal_vision import estimate_bytes, work

PEER = PeerIdentity(os.getuid(), os.getgid())


def component():
    return importlib.import_module("app.ai.vision_supervisor")


class Worker:
    def __init__(self, value, *, stopped=True):
        self.work, self.stopped, self.stops = value, stopped, 0
    async def run(self):
        return json.loads(estimate_bytes())
    async def stop_and_confirm(self):
        self.stops += 1
        return self.stopped


@asynccontextmanager
async def serving(factory=Worker, **kwargs):
    module = component()
    value = work(b"\xff\xd8\xffsample\xff\xd9")
    with tempfile.TemporaryDirectory(prefix="personal-image-", dir="/tmp") as directory:
        path = str(Path(directory) / "image.sock")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path)
        os.chmod(path, 0o660)
        listener.listen(2)
        supervisor = module.VisionSupervisor(value.policy, PEER, factory, clock_ms=lambda: 1000, **kwargs)
        await supervisor.start(listener)
        try:
            yield supervisor, path, value
        finally:
            await supervisor.close()


def test_raw_image_crosses_only_dedicated_channel_with_exact_work_binding():
    async def scenario():
        created = []
        def factory(value):
            created.append(Worker(value))
            return created[-1]
        async with serving(factory) as (_, path, value):
            client = component().SupervisorMealVisionWorker(value, path, PEER, clock_ms=lambda: 1000)
            assert await client.run() == json.loads(estimate_bytes())
            assert await client.stop_and_confirm()
            assert len(created) == 1 and created[0].work == value
            assert created[0].stops == 1
            with pytest.raises(SupervisorError):
                await client.run()
            text = SupervisorTextRunner(path, PEER, TextSessionPolicy(value.policy.model, "Sample"))
            with pytest.raises(SupervisorError):
                await text.run("Sample", text.policy)
            assert len(created) == 1
    asyncio.run(scenario())


def test_result_is_not_released_until_stop_and_failed_stop_poison_is_retained():
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        class Slow(Worker):
            async def stop_and_confirm(self):
                entered.set()
                await release.wait()
                return False
        async with serving(Slow) as (server, path, value):
            client = component().SupervisorMealVisionWorker(value, path, PEER, clock_ms=lambda: 1000)
            task = asyncio.create_task(client.run())
            await entered.wait()
            assert not task.done()
            release.set()
            with pytest.raises(SupervisorError):
                await task
            assert not await client.stop_and_confirm() and server._poisoned
    asyncio.run(scenario())


def test_expired_job_and_foreign_peer_do_not_reach_factory():
    async def scenario():
        created = []
        async with serving(lambda value: created.append(value)) as (_, path, value):
            expired = component().SupervisorMealVisionWorker(value, path, PEER, clock_ms=lambda: value.deadline_ms)
            with pytest.raises(MealVisionError, match="deadline"):
                await expired.run()
            wrong = component().SupervisorMealVisionWorker(value, path, PeerIdentity(PEER.uid + 1, PEER.gid),
                                                           clock_ms=lambda: 1000)
            with pytest.raises(SupervisorError):
                await wrong.run()
            assert await wrong.stop_and_confirm()
            assert not created
    asyncio.run(scenario())


@pytest.mark.parametrize("change", ["extra", "path", "policy", "owner", "deadline", "image", "binding"])
def test_closed_image_protocol_rejects_configuration_and_invalid_work(change):
    async def scenario():
        created = []
        async with serving(lambda value: created.append(value)) as (_, path, value):
            client = component().SupervisorMealVisionWorker(value, path, PEER, clock_ms=lambda: 1000)
            frame = client._request(client._payload_json)
            if change == "extra":
                frame["argv"] = ["/bin/sh"]
            elif change == "path":
                frame["work"]["path"] = "/private-sentinel"
            elif change == "policy":
                frame["policySha256"] = "0" * 64
            elif change == "owner":
                frame["work"]["ownerId"] = "invalid-owner"
            elif change == "deadline":
                frame["work"]["deadlineMs"] = 1000
            elif change == "image":
                frame["work"]["imageBase64"] = "https://example.test/food.jpg"
            else:
                frame["work"]["requestDigest"] = "invalid-digest"
            reader, writer = await asyncio.open_unix_connection(path)
            try:
                await _write(writer, frame, component().MAX_REQUEST)
                assert await reader.read() == b""
            finally:
                writer.close()
                await writer.wait_closed()
            assert not created
    asyncio.run(scenario())
