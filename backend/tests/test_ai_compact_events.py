import hashlib
import importlib
import importlib.util
import json
from uuid import UUID

import pytest

from app.ai.compact_context import prepare_compact_context
from app.ai.history_context import HistoryContext
from app.ai.history_contract import HistoryError, HistoryRecord, encoded
from test_ai_compact_chat import MINUTE, START, accepted, prepare
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OTHER, OWNER, store


def module():
    assert importlib.util.find_spec("app.ai.compact_events") is not None, (
        "lossless meal/therapy tables are absent"
    )
    return importlib.import_module("app.ai.compact_events")


def meal(index, **changes):
    return record(
        **(
            dict(
                recordId=str(UUID(int=index + 10_000)),
                kind="MEAL",
                eventAtMs=START + index * MINUTE,
                observedAtMs=NOW - 1,
                source="USER",
                data=dict(
                    carbsGrams=24.5,
                    foodType="MIXED",
                    quantitySource="APPROXIMATE",
                    glycemicIndex=54.25,
                    giSource="MODEL_ESTIMATE",
                ),
            )
            | changes
        )
    )


def unpack(packet):
    result = []
    common = packet["columns"]
    for table in packet["tables"]:
        for row in table["rows"]:
            item = dict(zip(common + table["fields"], row, strict=True))
            cohort = packet["cohorts"][item.pop("cohort")]
            at = packet["fromMs"] + item.pop("eventOffsetMs")
            result.append(
                dict(
                    record=dict(
                        recordId=item.pop("recordId"),
                        revision=item.pop("revision"),
                        eventAtMs=at,
                        observedAtMs=at + item.pop("observedLagMs"),
                        kind=table["kind"],
                        deleted=False,
                        **cohort,
                        data={field: item.pop(field) for field in table["fields"]},
                    ),
                    receivedAtMs=at + item.pop("receivedLagMs"),
                    sequence=item.pop("sequence"),
                )
            )
            assert item == {}
    return sorted(
        result, key=lambda i: (i["record"]["eventAtMs"], i["record"]["recordId"])
    )


def test_table_round_trip_is_exact_and_does_not_infer_delivery_or_gi():
    builder = module().MealTherapyEvents(START, NOW)
    inputs = [
        HistoryRecord.model_validate(meal(1)),
        HistoryRecord.model_validate(meal(2, revision=3, quality="SUSPECT")),
        HistoryRecord.model_validate(
            record(
                kind="THERAPY",
                eventAtMs=START,
                observedAtMs=NOW - 1,
                data={"eventType": "BOLUS", "insulinUnits": 1.2345},
            )
        ),
    ]
    for i, row in enumerate(inputs, 1):
        builder.add(row, sequence=i, received_ms=NOW)
    packet = builder.finish()
    assert packet["recordCount"] == 3 and packet["omittedRecords"] == 0
    decoded = unpack(packet)
    expected = sorted(
        [
            dict(record=r.model_dump(), sequence=i, receivedAtMs=NOW)
            for i, r in enumerate(inputs, 1)
        ],
        key=lambda i: (i["record"]["eventAtMs"], i["record"]["recordId"]),
    )
    assert decoded == expected
    assert "RECORDED_NOT_CONFIRMED_DELIVERY" in packet["limitations"]
    assert "MEAL_AND_CARBS_MAY_OVERLAP_DO_NOT_SUM" in packet["limitations"]


def test_optional_nulls_and_input_order_do_not_change_payload_meaning():
    rows = [
        HistoryRecord.model_validate(
            meal(
                i,
                data=dict(
                    carbsGrams=20.0,
                    foodType="FAST",
                    quantitySource="UNKNOWN",
                    **({"glycemicIndex": None, "giSource": None} if i else {}),
                ),
            )
        )
        for i in range(2)
    ]
    packets = []
    for order in (rows, list(reversed(rows))):
        builder = module().MealTherapyEvents(START, NOW)
        for row in order:
            builder.add(row, sequence=int(UUID(row.recordId)), received_ms=NOW)
        packets.append(builder.finish())
    assert packets[0] == packets[1]
    assert (
        unpack(packets[0])[0]["record"]["data"]
        != unpack(packets[0])[1]["record"]["data"]
    )


def test_adapter_keeps_all_events_not_only_recent_examples(tmp_path):
    value = store(tmp_path)
    rows = [meal(i) for i in range(30)]
    value.append(OWNER, KEY, body(rows), now_ms=NOW)
    value.append(OWNER, OTHER, body([meal(45)]), now_ms=NOW)
    entry, work = accepted(value)
    prepared = prepare(value, work)
    doc = json.loads(prepared.input_body)
    packet = doc["suppliedFacts"]["mealTherapyEvents"]
    assert len(unpack(packet)) == 30
    assert {i["record"]["recordId"] for i in unpack(packet)} == {
        r["recordId"] for r in rows
    }
    summary = doc["suppliedFacts"]["nonglucose"]
    assert summary["fromMs"] == START and summary["untilMs"] == NOW
    assert len(summary["recentRecords"]) == 12
    assert summary["omitted"]["recentByKind"]["MEAL"] == 18
    saved = value.chat_context_receipt(OWNER, KEY, entry.id, now_ms=NOW + 1)
    assert (
        json.loads(saved)["manifest"]["frameDigest"]
        == hashlib.sha256(prepared.input_body).hexdigest()
    )


def test_latest_revision_and_tombstone_are_applied_before_event_projection(tmp_path):
    value = store(tmp_path)
    first, second = meal(0), meal(1)
    value.append(OWNER, KEY, body([first, second]), now_ms=NOW)
    revised = first | dict(revision=2, data=first["data"] | {"carbsGrams": 27.0})
    value.append(
        OWNER,
        KEY,
        body([revised, second | dict(revision=2, deleted=True, data=None)]),
        now_ms=NOW,
    )
    _, work = accepted(value)
    packet = json.loads(prepare(value, work).input_body)["suppliedFacts"][
        "mealTherapyEvents"
    ]
    assert [i["record"]["recordId"] for i in unpack(packet)] == [first["recordId"]]
    assert unpack(packet)[0]["record"]["revision"] == 2
    assert unpack(packet)[0]["record"]["data"]["carbsGrams"] == 27.0


def test_capacity_refuses_instead_of_committing_a_partial_context(
    tmp_path, monkeypatch
):
    event_module = module()
    monkeypatch.setattr(event_module, "MAX_EVENT_BYTES", 1000)
    value = store(tmp_path)
    value.append(OWNER, KEY, body([meal(i) for i in range(30)]), now_ms=NOW)
    entry, work = accepted(value)
    with pytest.raises(HistoryError, match="compact_context_budget_exceeded"):
        prepare(value, work)
    assert value.chat_context_receipt(OWNER, KEY, entry.id, now_ms=NOW + 1) is None


def test_returned_tables_cannot_mutate_a_later_projection():
    builder = module().MealTherapyEvents(START, NOW)
    row = HistoryRecord.model_validate(meal(1, context={"sensorSessionId": "sensor-a"}))
    builder.add(row, sequence=1, received_ms=NOW)
    before = encoded(builder.finish())
    packet = builder.finish()
    packet["columns"].clear()
    packet["limitations"].clear()
    packet["cohorts"][0]["context"]["sensorSessionId"] = "changed-session"
    packet["tables"][0]["rows"][0][0] = "changed-record"
    assert encoded(builder.finish()) == before


@pytest.mark.parametrize(
    "payload",
    [
        {"eventType": "CARBS", "carbsGrams": 20.25},
        {"eventType": "BOLUS", "insulinUnits": 1.2345},
        {"eventType": "BASAL", "basalUnitsPerHour": 0.75, "durationMinutes": 30.0},
        {"eventType": "TEMP_TARGET", "targetMmol": 5.7, "durationMinutes": 30.0},
        {"eventType": "CANCEL_TARGET"},
        {"eventType": "SENSOR_CHANGE"},
        {"eventType": "SET_CHANGE"},
        {"eventType": "DELIVERY_STATE", "deliveryState": "UNKNOWN"},
    ],
)
def test_all_therapy_payloads_remain_observations_without_hourly_summing(payload):
    builder = module().MealTherapyEvents(START, NOW)
    row = HistoryRecord.model_validate(
        record(kind="THERAPY", eventAtMs=START, observedAtMs=NOW, data=payload)
    )
    builder.add(row, sequence=1, received_ms=NOW)
    packet = builder.finish()
    assert unpack(packet)[0]["record"] == row.model_dump()
    assert len(encoded(packet)) <= module().MAX_EVENT_BYTES


def test_failed_builder_cannot_return_a_successful_partial_table(monkeypatch):
    event_module = module()
    monkeypatch.setattr(event_module, "MAX_EVENT_BYTES", 1000)
    builder = event_module.MealTherapyEvents(START, NOW)
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        for i in range(30):
            builder.add(
                HistoryRecord.model_validate(meal(i)), sequence=i + 1, received_ms=NOW
            )
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        builder.finish()


def synthetic_packet_inputs(days, *, basal_interval_minutes=60):
    start = NOW - days * 1440 * MINUTE
    glucose = [
        HistoryRecord.model_validate(
            record(
                recordId=str(UUID(int=i + 1)),
                eventAtMs=start + i * MINUTE,
                observedAtMs=NOW,
                quality="OK",
                data={
                    "glucoseMmol": 3.5
                    if i % 1440 == 4
                    else 12.0
                    if i % 1440 == 5
                    else 6.0
                },
            )
        )
        for i in range(days * 1440 + 1)
    ]
    events = []

    def append(at, kind, source, data):
        events.append(
            HistoryRecord.model_validate(
                record(
                    recordId=str(UUID(int=100_000 + len(events))),
                    eventAtMs=at,
                    observedAtMs=at + 1,
                    kind=kind,
                    source=source,
                    quality="OK",
                    data=data,
                )
            )
        )

    for day in range(days):
        for hour in (8, 13, 19):
            at = start + (day * 1440 + hour * 60) * MINUTE
            append(
                at,
                "MEAL",
                "USER",
                dict(
                    carbsGrams=24.5,
                    foodType="MIXED",
                    quantitySource="APPROXIMATE",
                    glycemicIndex=54.25,
                    giSource="MODEL_ESTIMATE",
                ),
            )
            append(
                at - 15 * MINUTE,
                "THERAPY",
                "AAPS",
                dict(
                    eventType="BOLUS",
                    insulinUnits=1.2345,
                ),
            )
        for minute in range(0, 1440, basal_interval_minutes):
            append(
                start + (day * 1440 + minute) * MINUTE,
                "THERAPY",
                "AAPS",
                dict(
                    eventType="BASAL",
                    basalUnitsPerHour=0.75,
                    durationMinutes=float(basal_interval_minutes),
                ),
            )
    return start, glucose, events


def combined_context(start, glucose, events):
    builder = module().MealTherapyEvents(start, NOW)
    summary = HistoryContext(now_ms=NOW, snapshot=len(glucose) + len(events))
    for i, row in enumerate(events, 1):
        builder.add(row, sequence=i, received_ms=NOW)
        summary.add(dict(sequence=i, receivedAtMs=NOW, record=row.model_dump()))
    nonglucose = summary.finish()
    nonglucose.update(fromMs=start, untilMs=NOW)
    facts = dict(
        nonglucose=nonglucose,
        mealTherapyEvents=builder.finish(),
        profileExport="NOT_COMPLETE",
    )
    return prepare_compact_context(
        glucose,
        question="Explain only these synthetic observations",
        from_ms=start,
        until_ms=NOW,
        now_ms=NOW,
        zone_id="UTC",
        snapshot_sequence=len(glucose) + len(events),
        facts_body=encoded(facts),
    )


@pytest.mark.parametrize("days", [3, 7, 14])
def test_combined_minute_glucose_and_hourly_therapy_fit_without_losing_events(days):
    start, glucose, events = synthetic_packet_inputs(days)
    prepared = combined_context(start, glucose, events)
    doc = json.loads(prepared.input_body)
    packet = doc["suppliedFacts"]["mealTherapyEvents"]
    assert [item["record"] for item in unpack(packet)] == [
        r.model_dump() for r in sorted(events, key=lambda r: (r.eventAtMs, r.recordId))
    ]
    assert packet["recordCount"] == days * 30 and packet["omittedRecords"] == 0
    assert len(prepared.input_body) <= 96 * 1024
    assert doc["glucose"]["inputRecordCount"] == days * 1440 + 1
    assert doc["glucose"]["coveredMinutes"] == days * 1440
    bins = [row for series in doc["glucose"]["series"] for row in series["rows"]]
    assert min(row[5] for row in bins if row[5] is not None) == 350
    assert max(row[6] for row in bins if row[6] is not None) == 1200
    assert all(
        series["stepMinutes"] == 10 or series["endMs"] <= NOW - 3 * 1440 * MINUTE
        for series in doc["glucose"]["series"]
    )
    assert json.loads(prepared.manifest_body)["tokenStatus"] == "UNVERIFIED"


def test_dense_therapy_history_cannot_silently_become_partial_success():
    start, _, events = synthetic_packet_inputs(14, basal_interval_minutes=5)
    builder = module().MealTherapyEvents(start, NOW)
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        for i, row in enumerate(events, 1):
            builder.add(row, sequence=i, received_ms=NOW)
        builder.finish()
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        builder.finish()
