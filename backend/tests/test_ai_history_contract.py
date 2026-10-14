import importlib
import importlib.util
import json
from uuid import uuid4

import pytest

NOW = 1_800_000_000_000
DAY = 86_400_000


def contract():
    assert importlib.util.find_spec("app.ai.history_contract") is not None, (
        "history contract is absent"
    )
    return importlib.import_module("app.ai.history_contract")


def record(**changes):
    return (
        dict(
            recordId=str(uuid4()),
            revision=1,
            eventAtMs=NOW - 1000,
            observedAtMs=NOW,
            kind="GLUCOSE",
            source="CGM",
            quality="UNKNOWN",
            deleted=False,
            data={"glucoseMmol": 6.4},
        )
        | changes
    )


def body(rows=None, batch_id=None):
    return json.dumps(
        {"batchId": batch_id or str(uuid4()), "records": rows or [record()]}
    ).encode()


def query(**changes):
    return (
        dict(
            fromMs=NOW - DAY,
            untilMs=NOW + 1,
            kinds=[],
            afterSequence=0,
            snapshotSequence=None,
            limit=32,
        )
        | changes
    )


def test_valid_batch_and_query():
    c = contract()
    parsed = c.decode_batch(body(), now_ms=NOW)
    assert parsed.records[0].data == {"glucoseMmol": 6.4}
    assert c.decode_query(json.dumps(query()).encode(), now_ms=NOW).limit == 32


@pytest.mark.parametrize(
    "changes",
    [
        {"revision": True},
        {"eventAtMs": True},
        {"eventAtMs": NOW + 1},
        {"eventAtMs": NOW - 365 * DAY},
        {"observedAtMs": NOW + 1},
        {"recordId": "../outside"},
        {"source": "UNKNOWN_SERVER"},
        {"kind": "COMMAND"},
        {"quality": "TRUSTED"},
        {"data": {"glucoseMmol": True}},
        {"data": {"glucoseMmol": float("nan")}},
        {"data": {"glucoseMmol": 6.4, "apiKey": "synthetic-private-value"}},
        {"deleted": True},
        {"deleted": "false"},
    ],
)
def test_invalid_record_rejects_whole_batch(changes):
    c = contract()
    with pytest.raises(c.HistoryError) as failure:
        c.decode_batch(body([record(), record(**changes)]), now_ms=NOW)
    assert failure.value.code == "invalid_history_request"
    assert "synthetic-private-value" not in str(failure.value)


def test_duplicate_keys_and_client_device_override_are_rejected():
    c = contract()
    value = body()
    duplicate = value.replace(
        b'"glucoseMmol": 6.4', b'"glucoseMmol":6.4,"glucoseMmol":7.0'
    )
    with pytest.raises(c.HistoryError):
        c.decode_batch(duplicate, now_ms=NOW)
    with pytest.raises(c.HistoryError):
        c.decode_batch(value[:-1] + b',"deviceId":"other"}', now_ms=NOW)


@pytest.mark.parametrize(
    "kind,data",
    [
        ("THERAPY", {"eventType": "CARBS", "carbsGrams": 35.0}),
        (
            "MEAL",
            {"carbsGrams": 35.0, "foodType": "MIXED", "quantitySource": "APPROXIMATE"},
        ),
        (
            "FORECAST",
            {
                "horizonMinutes": 30,
                "valueMmol": 7.0,
                "ciLowMmol": 4.0,
                "ciHighMmol": 10.0,
                "modelVersion": "synthetic-v1",
            },
        ),
        (
            "SENSITIVITY",
            {"isfMmolPerUnit": 2.5, "crGramsPerUnit": 12.0, "netIobUnits": -0.4},
        ),
        ("ACTIVITY", {"steps": 120, "activeMinutes": 5.0}),
        (
            "DEVICE",
            {"sensorAgeHours": 72.0, "setAgeHours": 24.0, "deliveryState": "UNKNOWN"},
        ),
        (
            "ALERT",
            {
                "code": "delivery_check",
                "severity": "WARNING",
                "deliveryStatus": "UNKNOWN",
            },
        ),
        ("DIAGNOSTIC", {"code": "source_stale", "status": "UNKNOWN"}),
        (
            "CHAT",
            {
                "conversationId": str(uuid4()),
                "role": "USER",
                "text": "Synthetic question",
                "origin": "DEVICE_ARCHIVE",
            },
        ),
    ],
)
def test_supported_observational_kinds(kind, data):
    c = contract()
    assert (
        c.decode_batch(
            body([record(kind=kind, source="COPILOT", data=data)]), now_ms=NOW
        )
        .records[0]
        .data
        == data
    )


def test_gi_provenance_and_interval_consistency():
    c = contract()
    meal = {
        "carbsGrams": 35.0,
        "foodType": "MIXED",
        "quantitySource": "APPROXIMATE",
        "glycemicIndex": 60.0,
    }
    with pytest.raises(c.HistoryError):
        c.decode_batch(body([record(kind="MEAL", data=meal)]), now_ms=NOW)
    accepted = c.decode_batch(
        body([record(kind="MEAL", data=meal | {"giSource": "USER"})]), now_ms=NOW
    )
    assert accepted.records[0].data["giSource"] == "USER"
    with pytest.raises(c.HistoryError):
        c.decode_batch(
            body(
                [
                    record(
                        kind="FORECAST",
                        data={
                            "horizonMinutes": 30,
                            "valueMmol": 7.0,
                            "ciLowMmol": 9.0,
                            "ciHighMmol": 5.0,
                            "modelVersion": "synthetic-v1",
                        },
                    )
                ]
            ),
            now_ms=NOW,
        )


def test_query_and_utf8_limits_and_tombstone():
    c = contract()
    assert (
        c.decode_batch(body([record(deleted=True, data=None)]), now_ms=NOW)
        .records[0]
        .deleted
    )
    for change in (
        {"limit": 33},
        {"fromMs": NOW + 2},
        {"afterSequence": -1},
        {"kinds": ["COMMAND"]},
    ):
        with pytest.raises(c.HistoryError):
            c.decode_query(json.dumps(query(**change)).encode(), now_ms=NOW)
    with pytest.raises(c.HistoryError):
        c.decode_batch(
            body(
                [
                    record(
                        kind="CHAT",
                        data={
                            "conversationId": str(uuid4()),
                            "role": "USER",
                            "text": "x" * 8001,
                            "origin": "DEVICE_ARCHIVE",
                        },
                    )
                ]
            ),
            now_ms=NOW,
        )
    with pytest.raises(c.HistoryError):
        c.decode_batch(b" " * 131073, now_ms=NOW)


def test_optional_historical_context_preserves_unknowns_without_fabrication():
    c = contract()
    context = {
        "zoneId": "Asia/Tbilisi",
        "sensorSessionId": "synthetic-session",
        "calibrationModelId": None,
        "glucoseBasis": "RAW",
        "inputModelVersion": "synthetic-v1",
    }
    parsed = c.decode_batch(body([record(context=context)]), now_ms=NOW)
    assert parsed.records[0].model_dump(exclude_unset=True)["context"] == context
    assert "context" not in c.decode_batch(body(), now_ms=NOW).records[0].model_dump(
        exclude_unset=True
    )


def test_historical_context_does_not_accept_arbitrary_config():
    c = contract()
    for context in (
        {"zoneId": "../unknown"},
        {"accessToken": "synthetic-value"},
        {"glucoseBasis": "VERIFIED_BLOOD"},
        {},
    ):
        with pytest.raises(c.HistoryError):
            c.decode_batch(body([record(context=context)]), now_ms=NOW)
