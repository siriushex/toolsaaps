import base64
import json

import pytest


def jpeg_bytes(size=32):
    # A tiny marker-shaped fixture is enough for the transport boundary. Full
    # image decoding belongs to the contained server launcher, not this DTO.
    return b"\xff\xd8\xff\xe0" + b"x" * (size - 6) + b"\xff\xd9"


def request_body(image=None, **overrides):
    payload = {
        "schemaVersion": 1,
        "mimeType": "image/jpeg",
        "imageBase64": base64.b64encode(jpeg_bytes() if image is None else image).decode("ascii"),
    }
    payload.update(overrides)
    return json.dumps(payload, separators=(",", ":")).encode("ascii")


def estimate_value(**overrides):
    value = {
        "schemaVersion": 1,
        "estimateId": "estimate-1",
        "mealName": "test meal",
        "ingredients": [{
            "id": "ingredient-1",
            "name": "rice",
            "massGrams": {"min": 90.0, "max": 120.0},
            "preparationState": "COOKED",
            "carbohydrateBasis": "TOTAL",
            "carbohydrates": {"min": 22.0, "max": 31.0},
            "protein": {"min": 2.0, "max": 4.0},
            "fat": None,
            "fiber": {"min": 0.0, "max": 1.0},
            "sugar": {"min": 0.0, "max": 2.0},
            "polyols": None,
            "energy": {"min": 130.0, "max": 180.0, "unit": "KCAL"},
        }],
        "suggestedProfile": "MIXED",
        "suggestedDurationMinutes": 120,
    }
    value.update(overrides)
    return value


def test_meal_photo_request_accepts_only_canonical_bounded_jpeg():
    from app.ai.meal_photo import MealPhotoRequest

    decoded = MealPhotoRequest.decode(request_body())
    assert decoded.schema_version == 1
    assert decoded.mime_type == "image/jpeg"
    assert decoded.image_bytes == jpeg_bytes()


@pytest.mark.parametrize("body", [
    request_body(mimeType="image/png"),
    request_body(image=b"not-a-jpeg"),
    b'{"schemaVersion":1,"mimeType":"image/jpeg","imageBase64":"%%%"}',
    b'{"schemaVersion":1,"mimeType":"image/jpeg","imageBase64":"AA==","imageBase64":"AA=="}',
    b'{"schemaVersion":1,"mimeType":"image/jpeg","imageBase64":"' +
        base64.b64encode(jpeg_bytes()).decode("ascii").encode("ascii") + b'","extra":true}',
])
def test_meal_photo_request_rejects_wrong_shape_mime_base64_and_duplicates(body):
    from app.ai.meal_photo import MealPhotoRequest, MealPhotoSchemaError

    with pytest.raises(MealPhotoSchemaError, match="^invalid_request$"):
        MealPhotoRequest.decode(body)


def test_meal_photo_request_rejects_body_or_image_over_limits():
    from app.ai.meal_photo import (MAX_IMAGE_BYTES, MAX_REQUEST_BODY_BYTES,
                                   MealPhotoRequest, MealPhotoSchemaError)

    with pytest.raises(MealPhotoSchemaError, match="^request_too_large$"):
        MealPhotoRequest.decode(request_body(image=jpeg_bytes(MAX_IMAGE_BYTES + 1)))

    oversized = b"{" + b"x" * MAX_REQUEST_BODY_BYTES + b"}"
    with pytest.raises(MealPhotoSchemaError, match="^request_too_large$"):
        MealPhotoRequest.decode(oversized)


def test_meal_photo_estimate_accepts_android_compatible_food_only_schema():
    from app.ai.meal_photo import MealPhotoEstimate

    decoded = MealPhotoEstimate.decode(estimate_value())
    assert decoded.estimate_id == "estimate-1"
    assert decoded.ingredients[0].mass_grams.minimum == 90.0
    assert decoded.suggested_duration_minutes == 120
    assert decoded.as_dict() == estimate_value()


@pytest.mark.parametrize("mutator", [
    lambda value: {**value, "doseUnits": 1.0},
    lambda value: {**value, "ingredients": [{**value["ingredients"][0], "catalogId": "trusted"}]},
    lambda value: {**value, "suggestedProfile": "FAST", "suggestedDurationMinutes": 120},
    lambda value: {**value, "ingredients": [{**value["ingredients"][0],
                                                "massGrams": {"min": 120.0, "max": 90.0}}]},
    lambda value: {**value, "ingredients": [{**value["ingredients"][0],
                                                "carbohydrates": {"min": 22.0, "max": 5001.0}}]},
])
def test_meal_photo_estimate_rejects_commands_unknown_fields_and_invalid_ranges(mutator):
    from app.ai.meal_photo import MealPhotoEstimate, MealPhotoSchemaError

    with pytest.raises(MealPhotoSchemaError, match="^invalid_result$"):
        MealPhotoEstimate.decode(mutator(estimate_value()))


@pytest.mark.parametrize("changes", [
    {"massGrams": {"min": 0, "max": 0}},
    {"massGrams": {"min": 0, "max": 0.05}},
    {"carbohydrates": {"min": 28, "max": 102}},
    {"protein": {"min": 2, "max": 80}},
    {"sugar": {"min": 0, "max": 102}},
    {"polyols": {"min": 0, "max": 102}},
    {"carbohydrateBasis": "AVAILABLE", "carbohydrates": {"min": 90, "max": 90},
     "fiber": {"min": 10, "max": 10}},
    {"carbohydrates": {"min": 97.01, "max": 97.01}},
])
def test_meal_photo_estimate_rejects_results_that_android_cannot_calculate(changes):
    from app.ai.meal_photo import MealPhotoEstimate, MealPhotoSchemaError

    value = estimate_value()
    value["ingredients"][0].update(changes)
    with pytest.raises(MealPhotoSchemaError, match="^invalid_result$"):
        MealPhotoEstimate.decode(value)


@pytest.mark.parametrize("basis", ["TOTAL", "UNKNOWN"])
def test_meal_photo_estimate_does_not_double_count_fiber_sugar_or_polyols(basis):
    from app.ai.meal_photo import MealPhotoEstimate

    value = estimate_value()
    value["ingredients"][0].update({
        "massGrams": {"min": 0.1, "max": 0.1},
        "carbohydrateBasis": basis,
        "carbohydrates": {"min": 97, "max": 97},
        "fiber": {"min": 10, "max": 10},
        "sugar": {"min": 20, "max": 20},
        "polyols": {"min": 30, "max": 30},
    })
    assert MealPhotoEstimate.decode(value).as_dict() == value


def test_meal_photo_estimate_keeps_uncertainty_that_includes_zero_when_mass_is_reviewable():
    from app.ai.meal_photo import MealPhotoEstimate

    value = estimate_value()
    value["ingredients"][0]["massGrams"]["min"] = 0
    assert MealPhotoEstimate.decode(value).as_dict() == value


@pytest.mark.parametrize("encoded", [False, True], ids=["object", "json"])
@pytest.mark.parametrize("field", ["suggestedProfile", "preparationState", "carbohydrateBasis", "unit"])
@pytest.mark.parametrize("invalid", [[], {}], ids=["array", "object"])
def test_meal_photo_enum_types_fail_with_fixed_schema_error(encoded, field, invalid):
    from app.ai.meal_photo import MealPhotoEstimate, MealPhotoSchemaError

    value = estimate_value()
    target = value if field == "suggestedProfile" else value["ingredients"][0]
    if field == "unit":
        target = target["energy"]
    target[field] = invalid
    with pytest.raises(MealPhotoSchemaError, match="^invalid_result$"):
        MealPhotoEstimate.decode(json.dumps(value).encode() if encoded else value)


@pytest.mark.parametrize("encoded", [False, True], ids=["object", "json"])
@pytest.mark.parametrize("field", ["massGrams", "carbohydrates", "energy"])
@pytest.mark.parametrize("endpoint", ["min", "max"])
def test_meal_photo_large_integer_rejects_without_float_overflow(encoded, field, endpoint):
    from app.ai.meal_photo import MealPhotoEstimate, MealPhotoSchemaError

    value = estimate_value()
    value["ingredients"][0][field][endpoint] = 10 ** 400
    with pytest.raises(MealPhotoSchemaError, match="^invalid_result$"):
        MealPhotoEstimate.decode(json.dumps(value).encode() if encoded else value)


@pytest.mark.parametrize("field", ["estimateId", "mealName", "id", "name"])
@pytest.mark.parametrize("blank", ["   ", "\u00a0\u2003"])
def test_meal_photo_blank_labels_reject_before_android_review(field, blank):
    from app.ai.meal_photo import MealPhotoEstimate, MealPhotoSchemaError

    value = estimate_value()
    target = value if field in {"estimateId", "mealName"} else value["ingredients"][0]
    target[field] = blank
    with pytest.raises(MealPhotoSchemaError, match="^invalid_result$"):
        MealPhotoEstimate.decode(json.dumps(value).encode())
