"""Local, typed text-only supervisor channel; never a shell or native RPC relay.

The host supplies an already bound Unix listener and an independently contained
process factory. This module does not install services or attest OS containment.
"""
from __future__ import annotations

import asyncio
import ctypes
import hashlib
import json
import socket
import struct
import sys
from dataclasses import asdict, dataclass
from uuid import UUID, uuid4

from .contextual_chat import NativeTextRunner
from .job_executor import _complete_critical
from .native_text import TextSessionPolicy
from .schemas import ChatResult

MAX_REQUEST = 6 * 131_072 + 1024
MAX_RESPONSE = 6 * 16_384 + 1024
HANDSHAKE_SECONDS = 5
STOP_SECONDS = 15
CLIENT_STOP_SECONDS = 18
RUN_SECONDS = 840


class SupervisorError(Exception):
    """Fixed codes only, without socket errors, native output or request bodies."""


@dataclass(frozen=True)
class PeerIdentity:
    uid: int
    gid: int

    def __post_init__(self):
        if any(type(value) is not int or not 0 <= value < 2**31 for value in (self.uid, self.gid)):
            raise ValueError("invalid_local_peer")

    def check(self, writer):
        try:
            peer = writer.get_extra_info("socket")
            if peer.family != socket.AF_UNIX:
                raise ValueError
            if hasattr(socket, "SO_PEERCRED"):
                _, uid, gid = struct.unpack("3i", peer.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            elif sys.platform == "darwin":
                # Python does not expose getpeereid on all macOS builds.
                uid_value, gid_value = ctypes.c_uint(), ctypes.c_uint()
                function = ctypes.CDLL(None, use_errno=True).getpeereid
                function.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
                function.restype = ctypes.c_int
                if function(peer.fileno(), ctypes.byref(uid_value), ctypes.byref(gid_value)) != 0:
                    raise ValueError
                uid, gid = uid_value.value, gid_value.value
            else:
                raise ValueError
            if (uid, gid) != (self.uid, self.gid):
                raise ValueError
        except (OSError, ValueError, AttributeError, struct.error):
            raise SupervisorError("supervisor_peer_rejected") from None


@dataclass
class SupervisorCapacity:
    active: bool = False
    poisoned: bool = False

    def __post_init__(self):
        if type(self.active) is not bool or type(self.poisoned) is not bool:
            raise ValueError("invalid_supervisor_capacity")


def policy_digest(policy: TextSessionPolicy) -> str:
    if not isinstance(policy, TextSessionPolicy):
        raise ValueError("invalid_supervisor_policy")
    return hashlib.sha256(_encode(asdict(policy))).hexdigest()


def _encode(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"),
                      sort_keys=True).encode()


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError
        result[key] = value
    return result


async def _read(reader, maximum):
    try:
        size = struct.unpack("!I", await reader.readexactly(4))[0]
        if not 1 <= size <= maximum:
            raise ValueError
        value = json.loads((await reader.readexactly(size)).decode(), object_pairs_hook=_unique,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(value, dict):
            raise ValueError
        return value
    except (ValueError, UnicodeError, RecursionError, asyncio.IncompleteReadError, OSError):
        raise SupervisorError("supervisor_frame_rejected") from None


async def _write(writer, value, maximum):
    raw = _encode(value)
    if not 1 <= len(raw) <= maximum:
        raise SupervisorError("supervisor_frame_limit")
    writer.write(struct.pack("!I", len(raw)) + raw)
    await writer.drain()


async def _close(writer):
    writer.close()
    try:
        async with asyncio.timeout(1):
            await writer.wait_closed()
    except (OSError, TimeoutError):
        pass


class SupervisorTextRunner:
    """API-side one-shot client. Never retries an ambiguous request/connection."""

    max_request, max_response, run_seconds = MAX_REQUEST, MAX_RESPONSE, RUN_SECONDS
    response_fields = frozenset({"version", "runId", "state", "stopped", "result"})
    _policy_digest = staticmethod(policy_digest)

    def __init__(self, path: str, peer: PeerIdentity, policy: TextSessionPolicy):
        if (not isinstance(path, str) or not path.startswith("/") or "\x00" in path
                or len(path.encode()) > 103 or not isinstance(peer, PeerIdentity)):
            raise ValueError("invalid_supervisor_endpoint")
        self.path, self.peer, self.policy = path, peer, policy
        self.digest = self._policy_digest(policy)
        self.run_id = str(uuid4())
        self._task = self._writer = None
        self._submitted = self._cancel = self._cancel_sent = self._stopped = False

    async def run(self, text: str, policy: TextSessionPolicy):
        if self._task is not None or self._cancel:
            raise SupervisorError("supervisor_runner_already_used")
        if policy != self.policy or not isinstance(text, str) or not 1 <= len(text.encode()) <= policy.max_input_bytes:
            raise SupervisorError("supervisor_request_rejected")
        self._task = asyncio.create_task(self._exchange(text))
        return await asyncio.shield(self._task)

    async def _exchange(self, text):
        try:
            async with asyncio.timeout(HANDSHAKE_SECONDS):
                reader, self._writer = await asyncio.open_unix_connection(self.path, limit=self.max_response + 4)
                self.peer.check(self._writer)
                if self._cancel:
                    raise SupervisorError("supervisor_cancelled")
                # Mark before writing: failure after a partial send is ambiguous.
                self._submitted = True
                await _write(self._writer, self._request(text), self.max_request)
            async with asyncio.timeout(self.run_seconds + STOP_SECONDS + HANDSHAKE_SECONDS):
                value = await _read(reader, self.max_response)
            if (set(value) != self.response_fields
                    or type(value["version"]) is not int or value["version"] != 1
                    or value["runId"] != self.run_id or type(value["stopped"]) is not bool
                    or value["state"] not in {"SUCCEEDED", "FAILED", "CANCELLED", "REJECTED"}):
                raise SupervisorError("supervisor_receipt_rejected")
            self._validate_extra(value)
            result = self._result(value["result"]) if value["state"] == "SUCCEEDED" else None
            if (value["state"] != "SUCCEEDED" and value["result"] is not None
                    or value["state"] == "SUCCEEDED" and not value["stopped"]):
                raise SupervisorError("supervisor_receipt_rejected")
            self._stopped = value["stopped"]
            if value["state"] != "SUCCEEDED":
                raise SupervisorError("supervisor_turn_failed")
            return result
        except SupervisorError:
            raise
        except Exception:
            raise SupervisorError("supervisor_unavailable") from None
        finally:
            if self._writer is not None:
                await _close(self._writer)

    def _request(self, text):
        return dict(version=1, runId=self.run_id, policySha256=self.digest, text=text)

    def _validate_extra(self, value):
        pass

    @staticmethod
    def _result(value):
        return ChatResult.decode(value).as_dict()

    async def stop_and_confirm(self):
        self._cancel = True
        if self._task is None:
            return True
        try:
            async with asyncio.timeout(CLIENT_STOP_SECONDS):
                if self._submitted and not self._task.done() and not self._cancel_sent:
                    self._cancel_sent = True
                    await _write(self._writer, {"cancel": self.run_id}, 128)
                await asyncio.shield(self._task)
        except Exception:
            pass
        finally:
            if not self._task.done():
                self._task.cancel()
                if self._writer is not None:
                    self._writer.close()
                await asyncio.gather(self._task, return_exceptions=True)
        return self._stopped or not self._submitted


class TextSupervisor:
    """Trusted-side protocol/lifecycle owner, with one active native turn.

    The admitted factory constructs without side effects; start and stop must
    enforce the independent runtime/profile/cgroup gates. A missing stop receipt
    poisons this supervisor instance instead of releasing capacity optimistically.
    """

    max_request, max_response = MAX_REQUEST, MAX_RESPONSE
    _policy_digest = staticmethod(policy_digest)

    def __init__(self, policy: TextSessionPolicy, peer: PeerIdentity, process_factory,
                 *, run_seconds: float = RUN_SECONDS, capacity: SupervisorCapacity | None = None):
        if (not isinstance(peer, PeerIdentity) or not callable(process_factory)
                or type(run_seconds) not in (int, float) or not 0 < run_seconds <= RUN_SECONDS
                or capacity is not None and type(capacity) is not SupervisorCapacity):
            raise ValueError("invalid_text_supervisor")
        self.policy, self.peer, self.factory = policy, peer, process_factory
        self.digest = self._policy_digest(policy)
        self.run_seconds = run_seconds
        self._server = None
        self._handlers = set()
        self._unfinished = set()
        self._capacity = capacity if capacity is not None else SupervisorCapacity()
        self._closing = False

    @property
    def _active(self):
        return self._capacity.active

    @_active.setter
    def _active(self, value):
        self._capacity.active = value

    @property
    def _poisoned(self):
        return self._capacity.poisoned

    @_poisoned.setter
    def _poisoned(self, value):
        self._capacity.poisoned = value

    async def start(self, listener: socket.socket):
        if (self._server is not None or self._closing or listener.family != socket.AF_UNIX
                or listener.type != socket.SOCK_STREAM or not listener.getsockname()
                or sys.platform == "linux" and listener.getsockopt(socket.SOL_SOCKET, socket.SO_ACCEPTCONN) != 1):
            raise ValueError("bound_local_listener_required")
        # Host owns path permissions/lifetime; never unlink/rebind an existing socket.
        self._server = await asyncio.start_unix_server(self._accept, sock=listener, limit=self.max_request + 4)

    def _header(self, value, fields):
        if (set(value) != fields or type(value["version"]) is not int or value["version"] != 1
                or not isinstance(value["runId"], str) or str(UUID(value["runId"])) != value["runId"]
                or UUID(value["runId"]).version != 4 or value["policySha256"] != self.digest):
            raise SupervisorError("supervisor_request_rejected")

    def _parse_request(self, value):
        self._header(value, {"version", "runId", "policySha256", "text"})
        if not isinstance(value["text"], str) or not 1 <= len(value["text"].encode()) <= self.policy.max_input_bytes:
            raise SupervisorError("supervisor_request_rejected")
        return value["runId"], value["text"]

    def _runner(self, payload):
        return NativeTextRunner(self.factory())

    async def _run(self, runner, payload):
        return await runner.run(payload, self.policy)

    _result = staticmethod(SupervisorTextRunner._result)

    def _receipt_extra(self, payload):
        return {}

    def _accept(self, reader, writer):
        if self._closing or len(self._handlers) >= 2:
            writer.close()
            return
        task = asyncio.create_task(self._handle(reader, writer))
        self._handlers.add(task)
        task.add_done_callback(self._handlers.discard)

    async def _handle(self, reader, writer):
        runner = execution = watcher = None
        claimed = False
        stopped = True
        run_id = None
        payload = None
        state, result = "REJECTED", None
        try:
            self.peer.check(writer)
            async with asyncio.timeout(HANDSHAKE_SECONDS):
                value = await _read(reader, self.max_request)
            run_id, payload = self._parse_request(value)
            if self._active or self._poisoned or self._closing:
                return
            self._active = claimed = True
            # An exceptional factory may have side effects: no optimistic receipt.
            stopped = False
            runner = self._runner(payload)
            execution = asyncio.create_task(self._run(runner, payload))
            watcher = asyncio.create_task(_read(reader, 128))
            async with asyncio.timeout(self.run_seconds):
                done, _ = await asyncio.wait({execution, watcher}, return_when=asyncio.FIRST_COMPLETED)
                # Any second message or EOF cancels this single-turn connection.
                if watcher in done:
                    state = "CANCELLED"
                else:
                    result = self._result(await execution)
                    state = "SUCCEEDED"
        except asyncio.CancelledError:
            state = "CANCELLED"
        except Exception:
            state = "FAILED"
        finally:
            if claimed:
                async def finish():
                    if execution is not None:
                        execution.cancel()
                    confirmed = False
                    try:
                        async with asyncio.timeout(STOP_SECONDS):
                            confirmed = runner is not None and await runner.stop_and_confirm() is True
                    except (Exception, asyncio.CancelledError):
                        pass
                    if execution is not None:
                        # Native IO must unblock after independently confirmed stop.
                        done, _ = await asyncio.wait({execution}, timeout=1)
                        if not done:
                            confirmed = False
                            self._unfinished.add(execution)
                            execution.add_done_callback(self._discard_unfinished)
                        else:
                            await asyncio.gather(execution, return_exceptions=True)
                    return confirmed
                stopped, _ = await _complete_critical(finish())
                self._poisoned |= not stopped
                self._active = False
            if watcher is not None:
                watcher.cancel()
                await asyncio.gather(watcher, return_exceptions=True)
            if not stopped:
                state, result = "FAILED", None
            if state != "SUCCEEDED":
                result = None
            try:
                if run_id is not None:
                    async with asyncio.timeout(1):
                        await _write(writer, dict(version=1, runId=run_id, state=state,
                            stopped=stopped, result=result, **self._receipt_extra(payload)), self.max_response)
            except Exception:
                pass
            await _close(writer)

    def _discard_unfinished(self, task):
        self._unfinished.discard(task)
        if not task.cancelled():
            task.exception()

    async def close(self):
        self._closing = True
        if self._server is not None:
            self._server.close()
        tasks = list(self._handlers)
        for task in tasks:
            task.cancel()
        if tasks:
            async def drain():
                await asyncio.gather(*tasks, return_exceptions=True)
            await _complete_critical(drain())
        if self._server is not None:
            await self._server.wait_closed()
        return not self._poisoned
