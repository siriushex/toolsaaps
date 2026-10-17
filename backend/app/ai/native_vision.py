"""One fixed food-image turn using the pinned v2 image/url wire schema.

Public source 3d2ee51ca2d5db578f328aa75e20aa22c0197c9a, protocol/v2/turn.rs.
The host must independently admit containment and the exact model catalog.
No paths, URLs, clinical context, tools, retries or fallback are accepted.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass

from .meal_photo import MAX_IMAGE_BYTES, MAX_RESULT_BYTES, MealPhotoEstimate
from .meal_vision import FOOD_ONLY_PROMPT
from .meal_vision_image import MAX_IMAGE_EDGE, MAX_IMAGE_PIXELS, NormalizedMealImage
from .native_text import _NativeSession, restricted_catalog

VISION_LINE_LIMIT = 2_097_152
VISION_SEND_LIMIT = 1_500_000
VISION_STREAM_LIMIT = 8_388_608


class NativeVisionError(Exception):
    """Fixed diagnostics only, never native or image contents."""


def restricted_vision_catalog(catalog: dict, model: str) -> dict:
    value = restricted_catalog(catalog, model)
    modalities = value["models"][0].get("input_modalities")
    if (type(modalities) is not list or not modalities or any(type(item) is not str for item in modalities)
            or len(set(modalities)) != len(modalities) or "image" not in modalities):
        raise ValueError("exact_image_model_required")
    return value


@dataclass(frozen=True)
class VisionSessionPolicy:
    model: str

    def __post_init__(self):
        restricted_catalog({"models": [{"slug": self.model}]}, self.model)

    @property
    def instructions(self):
        return FOOD_ONLY_PROMPT

    @property
    def max_stream_bytes(self):
        return VISION_STREAM_LIMIT

    @property
    def max_events(self):
        return 2048


class NativeMealVisionSession(_NativeSession):
    line_limit = VISION_LINE_LIMIT

    def __init__(self, transport, policy: VisionSessionPolicy, *, containment_verified: bool):
        if containment_verified is not True or type(policy) is not VisionSessionPolicy:
            raise ValueError("contained_vision_transport_required")
        super().__init__(transport, policy)

    @staticmethod
    def _decode_answer(value):
        if type(value) is not str or not 1 <= len(value.encode()) <= MAX_RESULT_BYTES:
            raise NativeVisionError("vision_answer_limit")
        raw = value.encode()
        MealPhotoEstimate.decode(raw)
        return raw

    async def run(self, image: NormalizedMealImage) -> bytes:
        if self._used:
            raise NativeVisionError("vision_session_already_used")
        if (type(image) is not NormalizedMealImage or type(image.jpeg) is not bytes
                or not 1 <= len(image.jpeg) <= MAX_IMAGE_BYTES
                or not image.jpeg.startswith(b"\xff\xd8\xff") or not image.jpeg.endswith(b"\xff\xd9")
                or any(type(size) is not int or not 1 <= size <= MAX_IMAGE_EDGE
                       for size in (image.width, image.height))
                or image.width * image.height > MAX_IMAGE_PIXELS):
            self._used = True
            raise NativeVisionError("vision_image_rejected")
        try:
            return await self._run([
                {"type": "text", "text": FOOD_ONLY_PROMPT, "textElements": []},
                {"type": "image", "url": "data:image/jpeg;base64," + base64.b64encode(image.jpeg).decode("ascii")},
            ])
        except NativeVisionError:
            raise
        except Exception:
            raise NativeVisionError("vision_protocol_rejected") from None
