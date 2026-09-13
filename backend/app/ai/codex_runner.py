"""Bounded text subprocess adapter, not an OS security boundary.

Only trusted server code supplies the executable, policy and output model.
Deployment must separately restrict mounts, tools, network, CPU, disk and PIDs.
This module is deliberately not wired to a public endpoint.
"""
from __future__ import annotations

import asyncio
import json
import math
import os
import re
import signal
import stat
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import TypeVar

from pydantic import BaseModel, ValidationError

Result = TypeVar("Result", bound=BaseModel)


class RunnerError(Exception):
    """Stable status code only; never expose subprocess text or raw exceptions."""


@dataclass(frozen=True)
class RunnerPolicy:
    model: str
    timeout_seconds: float = 120
    max_prompt_bytes: int = 131072
    max_result_bytes: int = 65536
    max_stream_bytes: int = 1048576

    def __post_init__(self):
        if not isinstance(self.model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,127}", self.model):
            raise ValueError("invalid_model")
        if (type(self.timeout_seconds) not in (int, float)
                or not math.isfinite(self.timeout_seconds)
                or not 0 < self.timeout_seconds <= 300):
            raise ValueError("invalid_timeout")
        for size in (self.max_prompt_bytes, self.max_result_bytes, self.max_stream_bytes):
            if type(size) is not int or not 1 <= size <= 16777216:
                raise ValueError("invalid_limit")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_key")
        result[key] = value
    return result


class _BoundedProcess(asyncio.SubprocessProtocol):
    """Use public transports so overflow/cancel can close pipes before loop exit."""

    def __init__(self, prompt: bytes, limit: int):
        loop = asyncio.get_running_loop()
        self.done = loop.create_future()
        self.closed = loop.create_future()
        self.prompt = prompt
        self.limit = limit
        self.counts = {1: 0, 2: 0}

    def connection_made(self, transport):
        self.transport = transport
        stdin = transport.get_pipe_transport(0)
        stdin.write(self.prompt)
        stdin.close()
        self.prompt = b""

    def pipe_data_received(self, fd, data):
        if fd in self.counts:
            self.counts[fd] += len(data)
            if self.counts[fd] > self.limit and not self.done.done():
                self.done.set_exception(RunnerError("output_limit"))

    def connection_lost(self, exc):
        if not self.done.done():
            if exc is not None or self.transport.get_returncode() != 0:
                self.done.set_exception(RunnerError("process_failed"))
            else:
                self.done.set_result(None)
        if not self.closed.done():
            self.closed.set_result(None)


@dataclass(frozen=True)
class CodexRunner:
    executable: tuple[str, ...]
    codex_home: Path
    scratch_root: Path
    policy: RunnerPolicy

    def __post_init__(self):
        if not self.executable or not Path(self.executable[0]).is_absolute():
            raise ValueError("absolute_executable_required")
        object.__setattr__(self, "executable", tuple(self.executable))
        object.__setattr__(self, "codex_home", self.codex_home.resolve(strict=True))
        object.__setattr__(self, "scratch_root", self.scratch_root.resolve(strict=True))

    async def run(self, prompt: str, output_model: type[Result]) -> Result:
        try:
            encoded = prompt.encode("utf-8")
        except (UnicodeError, AttributeError):
            raise RunnerError("invalid_input") from None
        if len(encoded) > self.policy.max_prompt_bytes:
            raise RunnerError("input_limit")
        if output_model.model_config.get("extra") != "forbid":
            raise ValueError("closed_output_model_required")
        schema = json.dumps(output_model.model_json_schema(), allow_nan=False).encode()
        if len(schema) > 65536:
            raise ValueError("schema_limit")
        with tempfile.TemporaryDirectory(prefix="codex-job-", dir=self.scratch_root) as folder:
            work = Path(folder)
            schema_path, result_path = work / "schema.json", work / "result.json"
            schema_path.write_bytes(schema)
            args = (*self.executable, "exec", "--ephemeral", "--ignore-user-config",
                    "--ignore-rules", "--skip-git-repo-check", "--sandbox", "read-only",
                    "--model", self.policy.model, "--color", "never", "--json",
                    "-c", "features.shell_tool=false", "-c", "agents.enabled=false",
                    "-c", "features.apps=false", "-c", "features.hooks=false",
                    "-c", "features.plugins=false", "-c", "features.multi_agent=false",
                    "-c", "features.remote_plugin=false",
                    "-c", 'web_search="disabled"',
                    "--output-schema", str(schema_path),
                    "--output-last-message", str(result_path), "-")
            try:
                await asyncio.wait_for(self._execute(args, work, encoded), self.policy.timeout_seconds)
            except TimeoutError:
                raise RunnerError("deadline_exceeded") from None
            return self._read_result(result_path, output_model)

    async def _execute(self, args: tuple[str, ...], work: Path, prompt: bytes):
        environment = {"PATH": "/usr/bin:/bin", "HOME": str(work),
                       "CODEX_HOME": str(self.codex_home), "TMPDIR": str(work),
                       "LANG": "C.UTF-8"}
        protocol = _BoundedProcess(prompt, self.policy.max_stream_bytes)
        try:
            transport, _ = await asyncio.get_running_loop().subprocess_exec(
                lambda: protocol, *args, cwd=work, env=environment, start_new_session=True,
                stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE)
        except OSError:
            raise RunnerError("launch_failed") from None
        try:
            await protocol.done
        finally:
            # Also reap descendants that keep the group alive after its leader exits.
            try:
                os.killpg(transport.get_pid(), signal.SIGKILL)
            except ProcessLookupError:
                pass
            transport.close()
            await protocol.closed

    def _read_result(self, path: Path, output_model: type[Result]) -> Result:
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            try:
                info = os.fstat(fd)
                if not stat.S_ISREG(info.st_mode) or info.st_size > self.policy.max_result_bytes:
                    raise RunnerError("invalid_result_file")
                raw = os.read(fd, self.policy.max_result_bytes + 1)
            finally:
                os.close(fd)
            if len(raw) > self.policy.max_result_bytes:
                raise RunnerError("output_limit")
        except FileNotFoundError:
            raise RunnerError("missing_result") from None
        except OSError:
            raise RunnerError("invalid_result_file") from None
        try:
            data = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object)
            json.dumps(data, allow_nan=False, ensure_ascii=False).encode("utf-8")
            if not isinstance(data, dict):
                raise ValueError("object_required")
            return output_model.model_validate(data, strict=True)
        except (ValueError, ValidationError, RecursionError):
            raise RunnerError("invalid_result") from None
