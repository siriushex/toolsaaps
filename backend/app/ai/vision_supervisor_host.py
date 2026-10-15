"""Explicit privileged image host, never the nonroot HTTP API or an installer."""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, replace
from pathlib import Path
from uuid import UUID

from .contained_text_process import HOST_ENV, _require_host, _show
from .contained_vision import (
    NativeMealVisionTransport,
    SystemdVisionProcess,
    VisionRuntimeImage,
)
from .meal_decoder import DecoderRuntimeImage, DecoderStopVerifier, SystemdMealDecoder
from .meal_vision import MealVisionWorker, meal_vision_policy
from .native_vision import VisionSessionPolicy
from .runtime_admission import ImageEntry
from .text_supervisor_host import (
    HostConfiguration,
    HostError,
    _fields,
    _unique,
    owned_listener,
)
from .text_supervisor_host import read_configuration as read_protected
from .vision_supervisor import VisionSupervisor


@dataclass(frozen=True)
class VisionHostConfiguration:
    native: HostConfiguration
    decoder_image: DecoderRuntimeImage
    decoder_uid: int
    decoder_gid: int

    @property
    def policy(self):
        return meal_vision_policy(self.native.policy.model)

    @classmethod
    def decode(cls, raw):
        try:
            value = json.loads(raw.decode(), object_pairs_hook=_unique,
                parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            decoder = _fields(value.pop("decoder"), "image uid gid")
            native = HostConfiguration.decode(json.dumps(value).encode())
            source = native.image
            native = replace(native, image=VisionRuntimeImage(source.root, source.device, source.inode,
                source.entries, source.binary, source.binary_pin))
            image = _fields(decoder["image"], "root device inode entries binary binary_pin")
            if type(image["entries"]) is not list or not 1 <= len(image["entries"]) <= 256:
                raise ValueError()
            entries = tuple(ImageEntry(**_fields(entry, "relative mode size sha256")) for entry in image["entries"])
            runtime = DecoderRuntimeImage(Path(image["root"]), image["device"], image["inode"], entries,
                Path(image["binary"]), ImageEntry(**_fields(image["binary_pin"], "relative mode size sha256")))
            uid, gid = decoder["uid"], decoder["gid"]
            SystemdMealDecoder(runtime, uid, gid)
            if (uid in (native.api_peer.uid, native.guard.profile.uid)
                    or gid in (native.api_peer.gid, native.guard.profile.gid)):
                raise ValueError()
            for private in (native.guard.profile.path, native.guard.control.path):
                if any(private == path or private in path.parents or path in private.parents
                       for path in (runtime.root, runtime.binary)):
                    raise ValueError()
            if (runtime.root == native.image.root or runtime.root in native.image.root.parents
                    or native.image.root in runtime.root.parents or runtime.binary == native.image.binary):
                raise ValueError()
            SystemdVisionProcess(native.image, native.guard, native.egress, VisionSessionPolicy(native.policy.model))
            return cls(native, runtime, uid, gid)
        except Exception:
            raise HostError("vision_host_configuration_rejected") from None

    def fixed_identity(self):
        return self.native.fixed_identity(), self.decoder_uid, self.decoder_gid


def read_configuration(path):
    return read_protected(path, decode=VisionHostConfiguration.decode)


def check_decoder_idle():
    result = subprocess.run(("/usr/bin/systemctl", "list-units", "--all", "--type=service", "--no-pager",
        "--output=json", "copilot-ai-job-*.service"), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, timeout=5, check=False, env=HOST_ENV)
    if result.returncode != 0 or len(result.stdout) > 65536:
        raise HostError("decoder_reconciliation_required")
    rows = json.loads(result.stdout, object_pairs_hook=_unique)
    if type(rows) is not list or len(rows) > 256:
        raise HostError("decoder_reconciliation_required")
    for row in rows:
        unit = row["unit"]
        if not isinstance(unit, str) or not re.fullmatch(r"copilot-ai-job-[a-f0-9]{32}\.service", unit):
            raise HostError("decoder_reconciliation_required")
        state = _show(unit)
        if state["User"] != "copilot-image":
            continue
        run_id = str(UUID(hex=unit[len("copilot-ai-job-"):-len(".service")]))
        verifier = DecoderStopVerifier(run_id, "a" * 32)
        if (state["ActiveState"] not in {"inactive", "failed"} or state["SubState"] not in {"dead", "failed"}
                or not verifier._empty_cgroup()):
            raise HostError("decoder_reconciliation_required")


def isolated_worker(config, work, clock_ms):
    native = config.native
    policy = VisionSessionPolicy(native.policy.model)
    return MealVisionWorker(work, clock_ms=clock_ms,
        normalizer=SystemdMealDecoder(config.decoder_image, config.decoder_uid, config.decoder_gid),
        transport=NativeMealVisionTransport(SystemdVisionProcess(native.image, native.guard, native.egress, policy),
                                            policy, clock_ms=clock_ms))


async def serve(path, shutdown, *, ready=None, clock_ms=None):
    _require_host()
    clock = clock_ms if clock_ms is not None else lambda: time.time_ns() // 1_000_000
    initial = await asyncio.to_thread(read_configuration, path)
    native = initial.native
    native.egress.check_time()
    await asyncio.to_thread(native.image.verify, VisionSessionPolicy(native.policy.model), native.egress.hosts)
    await asyncio.to_thread(initial.decoder_image.verify)
    await asyncio.to_thread(native.check_idle)
    await asyncio.to_thread(check_decoder_idle)
    def factory(work):
        current = read_configuration(path)
        if current.fixed_identity() != initial.fixed_identity():
            raise HostError("vision_host_identity_changed")
        return isolated_worker(current, work, clock)
    with owned_listener(native.socket_path, native.api_peer.gid) as listener:
        supervisor = VisionSupervisor(initial.policy, native.api_peer, factory, clock_ms=clock)
        await supervisor.start(listener)
        try:
            if ready is not None:
                ready.set()
            await shutdown.wait()
        finally:
            stopped = await supervisor.close()
    return stopped


async def _main(path):
    shutdown = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(name, shutdown.set)
    try:
        return await serve(path, shutdown)
    finally:
        for name in (signal.SIGTERM, signal.SIGINT):
            loop.remove_signal_handler(name)


def main():
    parser = argparse.ArgumentParser(description="Run the trusted local food-image supervisor.")
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    try:
        return 0 if asyncio.run(_main(args.config)) else 1
    except Exception:
        print("image_host_start_or_stop_unconfirmed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
