import asyncio
import importlib
import importlib.util
import os
from pathlib import Path
import socket
import tempfile
from contextlib import asynccontextmanager

import pytest

from app.ai.daily_worker import daily_session_policy
from app.ai.text_supervisor import PeerIdentity, SupervisorError, SupervisorTextRunner, _write
from test_ai_contextual_chat import Process
from test_ai_daily_glucose import NOW, MINUTE
from test_ai_daily_review import answer, prepare
from test_ai_native_text import MODEL, POLICY, transcript

PEER = PeerIdentity(os.getuid(), os.getgid())


def module():
    assert importlib.util.find_spec("app.ai.daily_supervisor") is not None, "daily supervisor channel missing"
    return importlib.import_module("app.ai.daily_supervisor")


@asynccontextmanager
async def serving(work, process, *, clock=None, capacity=None):
    clock = clock or [NOW]
    with tempfile.TemporaryDirectory(prefix="daily-channel-", dir="/tmp") as directory:
        path = str(Path(directory) / "daily.sock")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path)
        listener.listen(2)
        policy = daily_session_policy(MODEL)
        supervisor = module().DailySupervisor(policy, PEER, lambda: process,
            clock_ms=lambda: clock[0], capacity=capacity)
        await supervisor.start(listener)
        try:
            yield supervisor, path, policy
        finally:
            await supervisor.close()


def client(path, policy, *, clock=None, peer=PEER):
    return module().SupervisorDailyReviewRunner(path, peer, policy, deadline_ms=NOW + MINUTE,
        clock_ms=lambda: NOW if clock is None else clock[0])


def test_dedicated_daily_json_crosses_real_socket_after_exact_stop_without_chat_wrapping():
    async def scenario():
        work = prepare()
        frames = transcript(answer(work).decode())
        frames.insert(6, {"method": "turn/moderationMetadata", "params": {
            "threadId": "sample-thread", "turnId": "sample-turn", "metadata": None}})
        process = Process(frames)
        async with serving(work, process) as (_, path, policy):
            runner = client(path, policy)
            assert await runner.run(work, policy) == answer(work)
            assert await runner.stop_and_confirm() is True
            assert process.started == [policy] and process.stops == 1
            assert process.transport.sent[-1]["params"]["input"][0]["text"] == work.input_body.decode()
            with pytest.raises(SupervisorError, match="already_used"):
                await runner.run(work, policy)
            chat = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await chat.run("Sample", POLICY)
            assert process.stops == 1
    asyncio.run(scenario())


@pytest.mark.parametrize("stopped", [False, None, 1])
def test_daily_failed_stop_does_not_expose_result_and_poison_is_shared(stopped):
    async def scenario():
        from app.ai.text_supervisor import SupervisorCapacity
        capacity = SupervisorCapacity()
        work = prepare()
        process = Process(transcript(answer(work).decode()), stop_receipt=stopped)
        async with serving(work, process, capacity=capacity) as (server, path, policy):
            runner = client(path, policy)
            with pytest.raises(SupervisorError):
                await runner.run(work, policy)
            assert not await runner.stop_and_confirm()
            assert server._poisoned and capacity.poisoned
    asyncio.run(scenario())


def test_daily_result_waits_for_stop_and_cancellation_has_no_second_turn():
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        class SlowStop(Process):
            async def stop_and_confirm(self):
                self.stops += 1
                entered.set()
                await release.wait()
                return True
        work = prepare()
        process = SlowStop(transcript(answer(work).decode()))
        async with serving(work, process) as (_, path, policy):
            runner = client(path, policy)
            task = asyncio.create_task(runner.run(work, policy))
            await entered.wait()
            assert not task.done()
            task.cancel()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert await runner.stop_and_confirm() is True
            assert process.stops == 1
    asyncio.run(scenario())


@pytest.mark.parametrize("change", ["extra", "model", "instructions", "expired", "too_late", "work_digest"])
def test_daily_closed_request_refuses_configuration_or_unbound_work_before_factory(change):
    async def scenario():
        work = prepare()
        created = []
        class Counting(Process):
            async def start(self, policy):
                created.append(True)
                return await super().start(policy)
        process = Counting(transcript(answer(work).decode()))
        async with serving(work, process) as (_, path, policy):
            runner = client(path, policy)
            frame = runner._request(runner._payload(work))
            if change in {"extra", "model", "instructions"}:
                frame[change] = "unexpected"
            elif change == "expired":
                frame["work"]["deadlineMs"] = NOW
            elif change == "too_late":
                frame["work"]["deadlineMs"] = NOW + 900_001
            else:
                frame["work"]["frozenWork"]["inputDigest"] = "0" * 64
            reader, writer = await asyncio.open_unix_connection(path)
            await _write(writer, frame, module().MAX_REQUEST)
            assert await reader.read() == b""
            writer.close()
            await writer.wait_closed()
            assert not created
    asyncio.run(scenario())


def test_unrelated_peer_and_expired_client_start_no_native_process():
    async def scenario():
        work, clock = prepare(), [NOW]
        process = Process(transcript(answer(prepare()).decode()))
        async with serving(work, process, clock=clock) as (_, path, policy):
            wrong = client(path, policy, peer=PeerIdentity(PEER.uid + 1, PEER.gid))
            with pytest.raises(SupervisorError):
                await wrong.run(work, policy)
            assert await wrong.stop_and_confirm()
            expired = client(path, policy, clock=clock)
            clock[0] += MINUTE
            with pytest.raises(SupervisorError, match="deadline"):
                await expired.run(work, policy)
            assert not process.started
    asyncio.run(scenario())


def test_shared_active_gate_refuses_background_start_without_poisoning_it():
    async def scenario():
        from app.ai.text_supervisor import SupervisorCapacity
        work = prepare()
        capacity = SupervisorCapacity(active=True)
        process = Process(transcript(answer(work).decode()))
        async with serving(work, process, capacity=capacity) as (_, path, policy):
            runner = client(path, policy)
            with pytest.raises(SupervisorError):
                await runner.run(work, policy)
            assert not process.started and not capacity.poisoned and capacity.active
    asyncio.run(scenario())


def test_reply_that_finishes_cleanup_after_deadline_is_not_exposed():
    async def scenario():
        work, clock = prepare(), [NOW]
        class LateStop(Process):
            async def stop_and_confirm(self):
                self.stops += 1
                clock[0] += MINUTE
                return True
        process = LateStop(transcript(answer(work).decode()))
        async with serving(work, process, clock=clock) as (_, path, policy):
            runner = client(path, policy, clock=clock)
            with pytest.raises(SupervisorError, match="deadline"):
                await runner.run(work, policy)
            assert await runner.stop_and_confirm() and process.stops == 1
    asyncio.run(scenario())
