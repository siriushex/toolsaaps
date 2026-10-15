"""Strict, food-only transport types for the ``MEAL_PHOTO`` job.

This module deliberately stops at a typed boundary.  It does not decode a
bitmap, call a model, persist a photo, or create an AAPS action.  The contained
worker will be responsible for re-decoding and normalising the JPEG before a
vision-capable Codex invocation.
"""
from __future__ import annotations

import base64
import binascii
import json
import math
from dataclasses import dataclass
from typing import Any


MAX_IMAGE_BYTES = 1 * 1024 * 1024
MAX_REQUEST_BODY_BYTES = 3 * 1024 * 1024
MAX_RESULT_BYTES = 64 * 1024
MAX_INGREDIENTS = 20
MAX_TEXT_LENGTH = 120
MAX_ID_LENGTH = 128
MAX_MASS_GRAMS = 5_000.0
MAX_NUTRIENT_GRAMS = 5_000.0
MAX_ENERGY = 100_000.0
SCHEMA_VERSION = 1

_PROFILE_DURATIONS = {
    "FAST": (30, 60),
    "MIXED": (60, 180),
    "FAT_PROTEIN": (180, 360),
}
_PREPARATION_STATES = {"RAW", "COOKED", "UNKNOWN"}
_CARBOHYDRATE_BASES = {"TOTAL", "AVAILABLE", "UNKNOWN"}
_ENERGY_UNITS = {"KCAL", "KJ"}


class MealPhotoSchemaError(Exception):
    """Stable public error code for transport and typed-result rejection."""


def _unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate_key")
        value[key] = item
    return value


def _reject_constants(_value):
    raise ValueError("non_finite_number")


def _json_object(body: bytes, *, maximum: int, error: str) -> dict[str, Any]:
    if type(body) is not bytes or len(body) > maximum:
        raise MealPhotoSchemaError(error)
    try:
        value = json.loads(body.decode("utf-8"), object_pairs_hook=_unique,
                           parse_constant=_reject_constants)
    except (ValueError, UnicodeError, RecursionError):
        raise MealPhotoSchemaError(error) from None
    if not isinstance(value, dict):
        raise MealPhotoSchemaError(error)
    return value


def _string(value: object, *, maximum: int, error: str) -> str:
    if (not isinstance(value, str) or not value or len(value) > maximum
            or any(ord(char) < 0x20 for char in value)):
        raise MealPhotoSchemaError(error)
    return value


def _number(value: object, *, maximum: float, error: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise MealPhotoSchemaError(error)
    converted = float(value)
    if not math.isfinite(converted) or converted < 0.0 or converted > maximum:
        raise MealPhotoSchemaError(error)
    return converted


@dataclass(frozen=True)
class MealPhotoRequest:
    schema_version: int
    mime_type: str
    image_bytes: bytes

    @classmethod
    def decode(cls, body: bytes) -> "MealPhotoRequest":
        if type(body) is not bytes or len(body) > MAX_REQUEST_BODY_BYTES:
            raise MealPhotoSchemaError("request_too_large")
        value = _json_object(body, maximum=MAX_REQUEST_BODY_BYTES,
                             error="invalid_request")
        if set(value) != {"schemaVersion", "mimeType", "imageBase64"}:
            raise MealPhotoSchemaError("invalid_request")
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != SCHEMA_VERSION:
            raise MealPhotoSchemaError("invalid_request")
        if value["mimeType"] != "image/jpeg":
            raise MealPhotoSchemaError("invalid_request")
        encoded = value["imageBase64"]
        if not isinstance(encoded, str) or not encoded or not encoded.isascii():
            raise MealPhotoSchemaError("invalid_request")
        try:
            image = base64.b64decode(encoded.encode("ascii"), validate=True)
        except (binascii.Error, ValueError):
            raise MealPhotoSchemaError("invalid_request") from None
        if not image:
            raise MealPhotoSchemaError("invalid_request")
        if len(image) > MAX_IMAGE_BYTES:
            raise MealPhotoSchemaError("request_too_large")
        if (base64.b64encode(image).decode("ascii") != encoded
                or not image.startswith(b"\xff\xd8\xff")
                or not image.endswith(b"\xff\xd9")):
            raise MealPhotoSchemaError("invalid_request")
        return cls(SCHEMA_VERSION, "image/jpeg", bytes(image))


@dataclass(frozen=True)
class MealPhotoRange:
    minimum: float
    maximum: float

    def as_dict(self) -> dict[str, float]:
        return {"min": self.minimum, "max": self.maximum}


@dataclass(frozen=True)
class MealPhotoIngredient:
    ingredient_id: str
    name: str
    mass_grams: MealPhotoRange
    preparation_state: str
    carbohydrate_basis: str
    carbohydrates: MealPhotoRange | None
    protein: MealPhotoRange | None
    fat: MealPhotoRange | None
    fiber: MealPhotoRange | None
    sugar: MealPhotoRange | None
    polyols: MealPhotoRange | None
    energy: tuple[MealPhotoRange, str] | None

    def as_dict(self) -> dict[str, object]:
        def optional(value):
            return None if value is None else value.as_dict()

        return {
            "id": self.ingredient_id,
            "name": self.name,
            "massGrams": self.mass_grams.as_dict(),
            "preparationState": self.preparation_state,
            "carbohydrateBasis": self.carbohydrate_basis,
            "carbohydrates": optional(self.carbohydrates),
            "protein": optional(self.protein),
            "fat": optional(self.fat),
            "fiber": optional(self.fiber),
            "sugar": optional(self.sugar),
            "polyols": optional(self.polyols),
            "energy": None if self.energy is None else {
                "min": self.energy[0].minimum,
                "max": self.energy[0].maximum,
                "unit": self.energy[1],
            },
        }


@dataclass(frozen=True)
class MealPhotoEstimate:
    schema_version: int
    estimate_id: str
    meal_name: str
    ingredients: tuple[MealPhotoIngredient, ...]
    suggested_profile: str | None
    suggested_duration_minutes: int | None

    @classmethod
    def decode(cls, value: object) -> "MealPhotoEstimate":
        if isinstance(value, bytes):
            if len(value) > MAX_RESULT_BYTES:
                raise MealPhotoSchemaError("invalid_result")
            value = _json_object(value, maximum=MAX_RESULT_BYTES,
                                 error="invalid_result")
        if not isinstance(value, dict):
            raise MealPhotoSchemaError("invalid_result")
        expected = {
            "schemaVersion", "estimateId", "mealName", "ingredients",
            "suggestedProfile", "suggestedDurationMinutes",
        }
        if set(value) != expected:
            raise MealPhotoSchemaError("invalid_result")
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != SCHEMA_VERSION:
            raise MealPhotoSchemaError("invalid_result")
        estimate_id = _string(value["estimateId"], maximum=MAX_ID_LENGTH,
                              error="invalid_result")
        meal_name = _string(value["mealName"], maximum=MAX_TEXT_LENGTH,
                            error="invalid_result")
        raw_ingredients = value["ingredients"]
        if (not isinstance(raw_ingredients, list) or not raw_ingredients
                or len(raw_ingredients) > MAX_INGREDIENTS):
            raise MealPhotoSchemaError("invalid_result")
        ingredients = []
        ids = set()
        for raw in raw_ingredients:
            ingredient = cls._decode_ingredient(raw)
            if ingredient.ingredient_id in ids:
                raise MealPhotoSchemaError("invalid_result")
            ids.add(ingredient.ingredient_id)
            ingredients.append(ingredient)
        profile = value["suggestedProfile"]
        if profile is not None and profile not in _PROFILE_DURATIONS:
            raise MealPhotoSchemaError("invalid_result")
        duration = value["suggestedDurationMinutes"]
        if duration is not None:
            if (type(duration) is not int or profile is None
                    or duration not in range(_PROFILE_DURATIONS[profile][0],
                                             _PROFILE_DURATIONS[profile][1] + 1)):
                raise MealPhotoSchemaError("invalid_result")
        elif profile is None:
            duration = None
        return cls(SCHEMA_VERSION, estimate_id, meal_name, tuple(ingredients),
                   profile, duration)

    @classmethod
    def _decode_ingredient(cls, value: object) -> MealPhotoIngredient:
        expected = {
            "id", "name", "massGrams", "preparationState",
            "carbohydrateBasis", "carbohydrates", "protein", "fat", "fiber",
            "sugar", "polyols", "energy",
        }
        if not isinstance(value, dict) or set(value) != expected:
            raise MealPhotoSchemaError("invalid_result")
        ingredient_id = _string(value["id"], maximum=MAX_ID_LENGTH,
                                error="invalid_result")
        name = _string(value["name"], maximum=MAX_TEXT_LENGTH,
                       error="invalid_result")
        preparation = value["preparationState"]
        basis = value["carbohydrateBasis"]
        if preparation not in _PREPARATION_STATES or basis not in _CARBOHYDRATE_BASES:
            raise MealPhotoSchemaError("invalid_result")
        return MealPhotoIngredient(
            ingredient_id=ingredient_id,
            name=name,
            mass_grams=cls._range(value["massGrams"], MAX_MASS_GRAMS),
            preparation_state=preparation,
            carbohydrate_basis=basis,
            carbohydrates=cls._optional_range(value["carbohydrates"], MAX_NUTRIENT_GRAMS),
            protein=cls._optional_range(value["protein"], MAX_NUTRIENT_GRAMS),
            fat=cls._optional_range(value["fat"], MAX_NUTRIENT_GRAMS),
            fiber=cls._optional_range(value["fiber"], MAX_NUTRIENT_GRAMS),
            sugar=cls._optional_range(value["sugar"], MAX_NUTRIENT_GRAMS),
            polyols=cls._optional_range(value["polyols"], MAX_NUTRIENT_GRAMS),
            energy=cls._energy(value["energy"]),
        )

    @staticmethod
    def _range(value: object, maximum: float) -> MealPhotoRange:
        if not isinstance(value, dict) or set(value) != {"min", "max"}:
            raise MealPhotoSchemaError("invalid_result")
        minimum = _number(value["min"], maximum=maximum, error="invalid_result")
        maximum_value = _number(value["max"], maximum=maximum, error="invalid_result")
        if maximum_value < minimum:
            raise MealPhotoSchemaError("invalid_result")
        return MealPhotoRange(minimum, maximum_value)

    @classmethod
    def _optional_range(cls, value: object, maximum: float) -> MealPhotoRange | None:
        return None if value is None else cls._range(value, maximum)

    @classmethod
    def _energy(cls, value: object) -> tuple[MealPhotoRange, str] | None:
        if value is None:
            return None
        if not isinstance(value, dict) or set(value) != {"min", "max", "unit"}:
            raise MealPhotoSchemaError("invalid_result")
        unit = value["unit"]
        if unit not in _ENERGY_UNITS:
            raise MealPhotoSchemaError("invalid_result")
        return cls._range({"min": value["min"], "max": value["max"]}, MAX_ENERGY), unit

    def as_dict(self) -> dict[str, object]:
        return {
            "schemaVersion": self.schema_version,
            "estimateId": self.estimate_id,
            "mealName": self.meal_name,
            "ingredients": [ingredient.as_dict() for ingredient in self.ingredients],
            "suggestedProfile": self.suggested_profile,
            "suggestedDurationMinutes": self.suggested_duration_minutes,
        }
