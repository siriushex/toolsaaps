"""Independent bounded JPEG normalization; optional decoder, no installation.

The parent must review Pillow==12.3.0 before adding it to the runtime image and
requirements. Only that locally tested decoder is accepted. No image is logged,
stored, opened by URL or passed to a tool. This is not an OS containment proof.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from io import BytesIO
import warnings

from .meal_photo import MAX_IMAGE_BYTES


DECODER_DEPENDENCY = "Pillow==12.3.0"
MAX_IMAGE_EDGE = 1600
MAX_IMAGE_PIXELS = 1600 * 1600


class MealVisionImageError(Exception):
    """Fixed codes, with no decoder diagnostics or image contents."""


@dataclass(frozen=True)
class NormalizedMealImage:
    jpeg: bytes = field(repr=False)
    width: int
    height: int


class _BoundedBuffer(BytesIO):
    def write(self, data):
        if self.tell() + len(data) > MAX_IMAGE_BYTES:
            raise MealVisionImageError("vision_image_output_limit")
        return super().write(data)


def _decoder():
    try:
        from PIL import Image, ImageFile, __version__
    except ImportError:
        raise MealVisionImageError("vision_decoder_unavailable") from None
    if __version__ != "12.3.0" or ImageFile.LOAD_TRUNCATED_IMAGES:
        raise MealVisionImageError("vision_decoder_configuration_rejected")
    return Image


def normalize_meal_jpeg(raw: bytes) -> NormalizedMealImage:
    """Check dimensions before raster allocation, fully decode, copy only pixels.

    Input is already oriented by the canonical client contract. EXIF, comments,
    ICC/XMP and other supplied metadata are neither forwarded nor interpreted as
    instructions. A fresh RGB image removes them even when the decoder retained
    metadata. Native/decoder CPU and memory isolation still require host admission.
    """
    if (type(raw) is not bytes or not 1 <= len(raw) <= MAX_IMAGE_BYTES
            or not raw.startswith(b"\xff\xd8\xff") or not raw.endswith(b"\xff\xd9")):
        raise MealVisionImageError("vision_image_rejected")
    Image = _decoder()
    try:
        with warnings.catch_warnings():
            # Decoder warnings can contain supplied metadata as well as sizes.
            warnings.simplefilter("error")
            with Image.open(BytesIO(raw), formats=("JPEG",)) as source:
                width, height = source.size
                if (source.format != "JPEG" or source.mode not in {"L", "RGB", "CMYK"}
                        or not 1 <= width <= MAX_IMAGE_EDGE
                        or not 1 <= height <= MAX_IMAGE_EDGE
                        or width * height > MAX_IMAGE_PIXELS):
                    raise MealVisionImageError("vision_image_dimensions_rejected")
                source.load()  # Header/marker checks alone cannot establish a JPEG.
                with Image.new("RGB", (width, height)) as clean:
                    with source.convert("RGB") as pixels:
                        clean.paste(pixels)
                    with _BoundedBuffer() as output:
                        clean.save(output, format="JPEG", quality=85,
                                   optimize=False, progressive=False)
                        return NormalizedMealImage(output.getvalue(), width, height)
    except MealVisionImageError:
        raise
    except Exception:
        # Decoder errors can contain supplied metadata. Never propagate them.
        raise MealVisionImageError("vision_image_rejected") from None
