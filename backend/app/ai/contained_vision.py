"""Trusted-host image transport, not an HTTP or supervisor activation path.

Shares the exact process/profile/kernel/stop lifecycle with text, but admits
only a separately pinned image-capable catalog and fixed food-only policy.
No decoding occurs here; the caller must supply an independently normalized
image. A process factory/isolated decoder and authenticated supervisor channel
must still be admitted before enabling the public photo route.
"""
from __future__ import annotations

import asyncio
import json

from .contained_text_process import PipeTextTransport, _SystemdProcess
from .job_executor import _complete_critical
from .meal_vision import (
    MealVisionBinding,
    MealVisionError,
    MealVisionReply,
    MealVisionTurn,
)
from .native_vision import (
    VISION_LINE_LIMIT,
    VISION_SEND_LIMIT,
    NativeMealVisionSession,
    VisionSessionPolicy,
    restricted_vision_catalog,
)
from .runtime_admission import RuntimeImage


class VisionRuntimeImage(RuntimeImage):
    def verify(self, policy: VisionSessionPolicy, hosts):
        if type(policy) is not VisionSessionPolicy:
            raise ValueError("vision_policy_required")
        super().verify(policy, hosts)
        # The shared catalog path is a fixed runtime compatibility layout.
        # Its filename never grants text or vision admission by itself.
        catalog = json.loads(self._small_file("work/text-model.json", 262144))
        if catalog != restricted_vision_catalog(catalog, policy.model):
            raise ValueError("unrestricted_image_model")


class PipeVisionTransport(PipeTextTransport):
    send_limit = VISION_SEND_LIMIT
    line_limit = VISION_LINE_LIMIT


class SystemdVisionProcess(_SystemdProcess):
    policy_type = VisionSessionPolicy
    pipe_type = PipeVisionTransport

    def __init__(self, image, guard, egress, policy):
        if type(image) is not VisionRuntimeImage:
            raise ValueError("vision_runtime_image_required")
        super().__init__(image, guard, egress, policy)


class NativeMealVisionTransport:
    """Single fixed estimate with local correlation and verified teardown.

    The trusted host supplies SystemdVisionProcess, never the public API. The
    local binding is returned unchanged and is never sent to the provider.
    """
    def __init__(self, process, policy: VisionSessionPolicy, *, clock_ms):
        if type(policy) is not VisionSessionPolicy or not callable(clock_ms):
            raise ValueError("vision_transport_configuration_required")
        self.process, self.policy, self.clock_ms = process, policy, clock_ms
        self._used = self._closing = False
        self._stopping = None

    def _remaining(self, binding):
        now = self.clock_ms()
        if (type(now) is not int or type(binding.deadline_ms) is not int
                or not 0 <= now < binding.deadline_ms < 2**63):
            raise MealVisionError("vision_deadline_rejected")
        return min(60, (binding.deadline_ms - now) / 1000)

    async def estimate(self, turn: MealVisionTurn) -> MealVisionReply:
        if self._used or self._closing:
            raise MealVisionError("vision_transport_already_used")
        self._used = True
        result = None
        try:
            if (type(turn) is not MealVisionTurn or type(turn.binding) is not MealVisionBinding
                    or turn.binding.model != self.policy.model):
                raise MealVisionError("vision_binding_rejected")
            async with asyncio.timeout(self._remaining(turn.binding)):
                pipe = await self.process.start(self.policy)
                if self._closing:
                    raise MealVisionError("vision_transport_stopping")
                result = await NativeMealVisionSession(pipe, self.policy, containment_verified=True).run(turn.image)
        except asyncio.CancelledError:
            raise
        except MealVisionError:
            raise
        except Exception:
            raise MealVisionError("vision_native_failed") from None
        finally:
            stopped, cancelled = await _complete_critical(self.stop_and_confirm())
            if cancelled:
                raise asyncio.CancelledError
        if stopped is not True:
            raise MealVisionError("vision_stop_unconfirmed")
        self._remaining(turn.binding)
        return MealVisionReply(turn.binding, result)

    async def stop_and_confirm(self):
        self._closing = True
        if self._stopping is None:
            self._stopping = asyncio.create_task(self.process.stop_and_confirm())
        try:
            return await asyncio.shield(self._stopping) is True
        except (Exception, asyncio.CancelledError):
            return False
