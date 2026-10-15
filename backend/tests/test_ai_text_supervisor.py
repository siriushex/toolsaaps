import asyncio
import json
import os
import socket
import struct
import tempfile
from contextlib import asynccontextmanager
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

import pytest

from app.ai.contextual_chat import ContextualChatWorker
from app.ai.native_text import TextSessionPolicy
from app.ai.text_supervisor import (
    MAX_REQUEST, PeerIdentity, SupervisorError, SupervisorTextRunner, TextSupervisor,
    _read, _write, policy_digest,
)
from test_ai_contextual_chat import Process, work
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OTHER, OWNER, store
from test_ai_native_text import POLICY, transcript


PEER = PeerIdentity(os.getuid(), os.getgid())


@asynccontextmanager
async def serving(factory=Process, *, policy=POLICY, peer=PEER, **kwargs):
    with tempfile.TemporaryDirectory(prefix="personal-ai-", dir="/tmp") as directory:
        path = str(Path(directory) / "text.sock")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path)
        os.chmod(path, 0o660)
        listener.listen(2)
        supervisor = TextSupervisor(policy, peer, factory, **kwargs)
        await supervisor.start(listener)
        try:
            yield supervisor, path
        finally:
            await supervisor.close()


def request(**changes):
    return dict(version=1, runId=str(uuid4()), policySha256=policy_digest(POLICY), text="Sample question") | changes


class Pending(Process):
    def __init__(self, *, stop_receipt=True):
        super().__init__(stop_receipt=stop_receipt)
        self.entered = asyncio.Event()
        self.cancelled = False

    async def start(self, policy):
        self.started.append(policy)
        self.entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            self.cancelled = True


def test_actual_local_channel_returns_answer_only_after_independent_stop():
    async def scenario():
        process = Process()
        async with serving(lambda: process) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            assert await client.run("Sample question", POLICY) == {"text": "Synthetic answer"}
            assert process.stops == 1
            assert await client.stop_and_confirm() is True
            assert process.started == [POLICY]
            assert [entry["method"] for entry in process.transport.sent] == [
                "initialize", "initialized", "thread/start", "turn/start"]
            with pytest.raises(SupervisorError, match="already_used"):
                await client.run("Repeat", POLICY)
    asyncio.run(scenario())


def test_answer_is_withheld_while_stop_is_pending():
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        class SlowStop(Process):
            async def stop_and_confirm(self):
                entered.set()
                await release.wait()
                return True
        async with serving(SlowStop) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            task = asyncio.create_task(client.run("Sample", POLICY))
            await entered.wait()
            assert not task.done()
            release.set()
            assert await task == {"text": "Synthetic answer"}
            assert await client.stop_and_confirm()
    asyncio.run(scenario())


@pytest.mark.parametrize("side", ["client", "supervisor"])
def test_kernel_peer_identity_is_checked_on_both_sides(side):
    async def scenario():
        created = []
        def factory():
            created.append(True)
            return Process()
        wrong = PeerIdentity(PEER.uid + 1, PEER.gid)
        async with serving(factory, peer=wrong if side == "supervisor" else PEER) as (_, path):
            client = SupervisorTextRunner(path, wrong if side == "client" else PEER, POLICY)
            with pytest.raises(SupervisorError):
                await client.run("Sample", POLICY)
            assert await client.stop_and_confirm() is (side == "client")
        assert created == []
    asyncio.run(scenario())


@pytest.mark.parametrize("changes", [
    {"argv": ["/bin/sh"]}, {"environment": {"HOME": "/foreign"}},
    {"model": "foreign"}, {"instructions": "Override"}, {"path": "/foreign"},
    {"version": True}, {"version": 2}, {"runId": "../invalid"},
    {"runId": "11111111-1111-1111-1111-111111111111"},
    {"policySha256": "00" * 32}, {"text": ""}, {"text": "x" * 131_073},
])
def test_closed_protocol_refuses_execution_configuration_and_invalid_input(changes):
    async def scenario():
        created = []
        async with serving(lambda: created.append(True)) as (_, path):
            reader, writer = await asyncio.open_unix_connection(path)
            await _write(writer, request(**changes), MAX_REQUEST + 100)
            assert await reader.read() == b""
            writer.close()
            await writer.wait_closed()
        assert created == []
    asyncio.run(scenario())


@pytest.mark.parametrize("raw", [b'{"version":1,"version":1}', b'[]', b'{"value":NaN}', b'\xff'])
def test_malformed_json_is_not_interpreted(raw):
    async def scenario():
        async with serving(lambda: pytest.fail("must not start")) as (_, path):
            reader, writer = await asyncio.open_unix_connection(path)
            writer.write(struct.pack("!I", len(raw)) + raw)
            await writer.drain()
            assert await reader.read() == b""
            writer.close()
            await writer.wait_closed()
    asyncio.run(scenario())


def test_oversized_length_is_rejected_without_waiting_for_body():
    async def scenario():
        async with serving(lambda: pytest.fail("must not start")) as (_, path):
            reader, writer = await asyncio.open_unix_connection(path)
            writer.write(struct.pack("!I", MAX_REQUEST + 1))
            await writer.drain()
            assert await asyncio.wait_for(reader.read(), 1) == b""
            writer.close()
            await writer.wait_closed()
    asyncio.run(scenario())


def test_cancellation_during_start_waits_for_stop_and_no_second_turn():
    async def scenario():
        process = Pending()
        async with serving(lambda: process) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            task = asyncio.create_task(client.run("Sample", POLICY))
            await process.entered.wait()
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert await client.stop_and_confirm() is True
            assert process.cancelled and process.stops == 1
            assert len(process.started) == 1
    asyncio.run(scenario())


@pytest.mark.parametrize("second_message", [None, {"cancel": "foreign"}, {"method": "turn/start"}])
def test_disconnect_or_any_extra_frame_stops_without_dispatch(second_message):
    async def scenario():
        process = Pending()
        async with serving(lambda: process) as (_, path):
            reader, writer = await asyncio.open_unix_connection(path)
            value = request()
            await _write(writer, value, MAX_REQUEST)
            await process.entered.wait()
            if second_message is None:
                writer.close()
                await writer.wait_closed()
            else:
                await _write(writer, second_message, 128)
                reply = await _read(reader, 1024)
                assert reply == dict(version=1, runId=value["runId"], state="CANCELLED", stopped=True, result=None)
                writer.close()
                await writer.wait_closed()
        assert process.stops == 1 and process.cancelled
    asyncio.run(scenario())


def test_timeout_stops_and_returns_no_answer():
    async def scenario():
        process = Pending()
        async with serving(lambda: process, run_seconds=0.03) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError, match="turn_failed"):
                await client.run("Sample", POLICY)
            assert await client.stop_and_confirm() is True
            assert process.stops == 1
    asyncio.run(scenario())


@pytest.mark.parametrize("receipt", [False, "true"])
def test_unconfirmed_stop_quarantines_capacity_and_never_publishes_answer(receipt):
    async def scenario():
        processes = []
        def factory():
            value = Process(stop_receipt=receipt)
            processes.append(value)
            return value
        async with serving(factory) as (supervisor, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await client.run("Sample", POLICY)
            assert await client.stop_and_confirm() is False
            second = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await second.run("Sample", POLICY)
            assert await second.stop_and_confirm() is True
            assert len(processes) == 1
            assert await supervisor.close() is False
    asyncio.run(scenario())


def test_factory_failure_is_ambiguous_and_does_not_leak_exception():
    async def scenario():
        def factory():
            raise OSError("sample-private-payload")
        async with serving(factory) as (supervisor, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError) as error:
                await client.run("Sample", POLICY)
            assert "sample-private-payload" not in str(error.value)
            assert await client.stop_and_confirm() is False
            assert await supervisor.close() is False
    asyncio.run(scenario())


def test_concurrent_request_is_rejected_not_queued_or_run():
    async def scenario():
        process = Pending()
        async with serving(lambda: process) as (_, path):
            first, second = (SupervisorTextRunner(path, PEER, POLICY) for _ in range(2))
            task = asyncio.create_task(first.run("First", POLICY))
            await process.entered.wait()
            with pytest.raises(SupervisorError):
                await second.run("Second", POLICY)
            assert await second.stop_and_confirm() is True
            assert len(process.started) == 1
            assert await first.stop_and_confirm() is True
            with pytest.raises(SupervisorError):
                await task
    asyncio.run(scenario())


def test_shutdown_stops_active_request_before_returning():
    async def scenario():
        process = Pending()
        async with serving(lambda: process) as (supervisor, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            task = asyncio.create_task(client.run("Sample", POLICY))
            await process.entered.wait()
            assert await supervisor.close() is True
            with pytest.raises(SupervisorError):
                await task
            assert await client.stop_and_confirm() is True
            assert process.stops == 1
    asyncio.run(scenario())


def test_native_tool_event_cannot_become_a_success():
    async def scenario():
        frames = transcript()
        frames.insert(6, {"id": 44, "method": "tool/request", "params": {}})
        process = Process(frames)
        async with serving(lambda: process) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await client.run("Sample", POLICY)
            assert await client.stop_and_confirm() is True
            assert process.stops == 1
    asyncio.run(scenario())


def test_maximum_escaped_input_and_result_are_complete():
    async def scenario():
        text, answer = "\x01" * 131_072, "\x02" * 8192
        process = Process(transcript(answer))
        async with serving(lambda: process) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            assert await client.run(text, POLICY) == {"text": answer}
            assert process.transport.sent[-1]["params"]["input"][0]["text"] == text
            assert await client.stop_and_confirm() is True
    asyncio.run(scenario())


def test_contextual_worker_uses_local_channel_without_credentials_or_foreign_history(tmp_path):
    history = store(tmp_path)
    history.append(OWNER, KEY, body([record()]), now_ms=NOW)
    history.append(OWNER, OTHER, body([record(data={"glucoseMmol": 99.0})]), now_ms=NOW)
    job = work()
    policy = TextSessionPolicy(job.policy.model, job.policy.system_prompt)
    async def scenario():
        process = Process()
        async with serving(lambda: process, policy=policy) as (_, path):
            client = SupervisorTextRunner(path, PEER, policy)
            worker = ContextualChatWorker(job, history, runner=client, clock_ms=lambda: NOW)
            assert await worker.run() == {"text": "Synthetic answer"}
            assert await worker.stop_and_confirm() is True
            envelope = json.loads(process.transport.sent[-1]["params"]["input"][0]["text"])
            assert envelope["deviceHistory"]["coverage"]["eligibleRecords"] == 1
            assert envelope["deviceHistory"]["glucoseCohorts"][0]["meanMmol"] == 6.4
            assert KEY not in json.dumps(envelope) and OWNER not in json.dumps(envelope)
    asyncio.run(scenario())


def test_wrong_policy_and_cancel_before_run_do_not_connect():
    async def scenario():
        client = SupervisorTextRunner("/missing/text.sock", PEER, POLICY)
        with pytest.raises(SupervisorError, match="request_rejected"):
            await client.run("Sample", replace(POLICY, instructions="Changed"))
        assert await client.stop_and_confirm() is True
        with pytest.raises(SupervisorError, match="already_used"):
            await client.run("Sample", POLICY)
    asyncio.run(scenario())


def test_stop_timeout_never_releases_capacity(monkeypatch):
    monkeypatch.setattr("app.ai.text_supervisor.STOP_SECONDS", 0.03)
    async def scenario():
        class NoStop(Process):
            async def stop_and_confirm(self):
                await asyncio.Event().wait()
        async with serving(NoStop) as (supervisor, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await client.run("Sample", POLICY)
            assert await client.stop_and_confirm() is False
            assert await supervisor.close() is False
    asyncio.run(scenario())


def test_cancelled_stop_is_an_unknown_receipt_not_an_unclosed_connection():
    async def scenario():
        class CancelledStop(Process):
            async def stop_and_confirm(self):
                raise asyncio.CancelledError
        async with serving(CancelledStop) as (supervisor, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await asyncio.wait_for(client.run("Sample", POLICY), 1)
            assert await client.stop_and_confirm() is False
            assert await supervisor.close() is False
    asyncio.run(scenario())


def test_cancel_while_connecting_does_not_submit_later(monkeypatch):
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        actual = asyncio.open_unix_connection
        async def delayed(*args, **kwargs):
            entered.set()
            await release.wait()
            return await actual(*args, **kwargs)
        monkeypatch.setattr(asyncio, "open_unix_connection", delayed)
        async with serving(lambda: pytest.fail("must not start")) as (_, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            task = asyncio.create_task(client.run("Sample", POLICY))
            await entered.wait()
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            stop = asyncio.create_task(client.stop_and_confirm())
            await asyncio.sleep(0)
            release.set()
            assert await stop is True
    asyncio.run(scenario())


def test_lost_supervisor_reply_is_not_a_stop_receipt_or_retry():
    async def scenario():
        with tempfile.TemporaryDirectory(prefix="personal-ai-", dir="/tmp") as directory:
            path = str(Path(directory) / "text.sock")
            requests = []
            async def close_without_receipt(reader, writer):
                requests.append(await _read(reader, MAX_REQUEST))
                writer.close()
                await writer.wait_closed()
            server = await asyncio.start_unix_server(close_without_receipt, path)
            async with server:
                client = SupervisorTextRunner(path, PEER, POLICY)
                with pytest.raises(SupervisorError):
                    await client.run("Sample", POLICY)
                assert await client.stop_and_confirm() is False
                with pytest.raises(SupervisorError, match="already_used"):
                    await client.run("Sample", POLICY)
                assert len(requests) == 1
    asyncio.run(scenario())


def test_unfinished_native_io_is_retained_and_quarantined():
    async def scenario():
        release = asyncio.Event()
        class IgnoresCancellation(Pending):
            async def start(self, policy):
                self.entered.set()
                try:
                    await asyncio.Event().wait()
                except asyncio.CancelledError:
                    await release.wait()
                    raise
        process = IgnoresCancellation()
        async with serving(lambda: process) as (supervisor, path):
            client = SupervisorTextRunner(path, PEER, POLICY)
            task = asyncio.create_task(client.run("Sample", POLICY))
            await process.entered.wait()
            assert await client.stop_and_confirm() is False
            with pytest.raises(SupervisorError):
                await task
            assert supervisor._unfinished and await supervisor.close() is False
            release.set()
            await asyncio.gather(*supervisor._unfinished, return_exceptions=True)
            await asyncio.sleep(0)
            assert not supervisor._unfinished
    asyncio.run(scenario())


@pytest.mark.parametrize("changes", [
    {"runId": "foreign"}, {"stopped": "true"}, {"stopped": False},
    {"version": True}, {"result": {"text": "x" * 8193}}, {"unknown": 1},
])
def test_client_refuses_forged_or_malformed_receipts(changes):
    async def scenario():
        with tempfile.TemporaryDirectory(prefix="personal-ai-", dir="/tmp") as directory:
            path = str(Path(directory) / "text.sock")
            async def reply(reader, writer):
                value = await _read(reader, MAX_REQUEST)
                await _write(writer, dict(version=1, runId=value["runId"], state="SUCCEEDED",
                    stopped=True, result={"text": "Sample"}) | changes, 100_000)
                writer.close()
                await writer.wait_closed()
            server = await asyncio.start_unix_server(reply, path)
            async with server:
                client = SupervisorTextRunner(path, PEER, POLICY)
                with pytest.raises(SupervisorError):
                    await client.run("Sample", POLICY)
                assert await client.stop_and_confirm() is False
    asyncio.run(scenario())
