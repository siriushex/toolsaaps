import hashlib
import importlib
import importlib.util
import json
from dataclasses import replace
from datetime import UTC, datetime
from uuid import UUID

import pytest

from app.ai.daily_glucose import AnalysisRange, build_daily_glucose
from app.ai.history_contract import HistoryRecord, encoded
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, START
from test_ai_history_contract import record


def module():
    assert importlib.util.find_spec("app.ai.compact_context") is not None, (
        "pure compact context projection is not implemented"
    )
    return importlib.import_module("app.ai.compact_context")


def point(minute, value=6.0, **changes):
    return HistoryRecord.model_validate(
        record(
            recordId=str(UUID(int=minute + 100_000)),
            eventAtMs=START + minute * MINUTE,
            observedAtMs=NOW,
            quality="OK",
            data={"glucoseMmol": float(value)},
            **changes,
        )
    )


def analysis_range():
    return AnalysisRange(
        startMs=START,
        endMs=END,
        lowMmol=4.0,
        highMmol=8.0,
        version="synthetic-analysis-range",
    )


def prepare(rows=(), **options):
    params = dict(
        question="Explain these observations",
        from_ms=START,
        until_ms=END,
        zone_id="UTC",
        now_ms=NOW,
        snapshot_sequence=7,
        ranges=(analysis_range(),),
    )
    return module().prepare_compact_context(rows, **(params | options))


def document(prepared):
    return json.loads(prepared.input_body)


def test_interior_minute_trough_and_peak_keep_exact_extrema_and_raw_metrics():
    rows = [point(i, 3.0 if i == 4 else 10.0 if i == 6 else 6.0) for i in range(11)]
    prepared = prepare(rows)
    view = document(prepared)["glucose"]
    assert view["columns"] == [
        "n",
        "covered_s",
        "first_q",
        "last_q",
        "mean_q",
        "min_q",
        "max_q",
    ]
    series = view["series"][0]
    assert series["rows"][0][0] == 10
    assert series["rows"][0][5:] == [300, 1000]
    assert series["extremaOffsetsMs"][0] == [4 * MINUTE, 6 * MINUTE]
    original = build_daily_glucose(
        rows,
        report_date=DAY,
        zone_id="UTC",
        now_ms=NOW,
        snapshot_sequence=7,
        ranges=(analysis_range(),),
    )
    assert view["cohorts"][0]["metrics"] == original.cohorts[0].metrics.model_dump()
    assert {e["band"] for e in view["episodes"]} == {"BELOW_RANGE", "ABOVE_RANGE"}
    assert len(prepared.input_body) <= 96 * 1024


def test_empty_and_single_measurement_have_no_invented_duration_or_zero_glucose():
    empty = document(prepare())["glucose"]
    assert empty["coveredMinutes"] == 0 and empty["missingMinutes"] == 1440
    assert not empty["series"]
    single = document(prepare([point(4)]))["glucose"]
    assert single["cohorts"][0]["metrics"]["meanMmol"] is None
    row = single["series"][0]["rows"][0]
    assert row == [1, 0, 600, 600, None, 600, 600]
    assert single["series"][0]["rows"][1] == [0, 0, None, None, None, None, None]


def test_long_gap_is_not_interpolated_and_irregular_sampling_uses_raw_time():
    view = document(prepare([point(0, 4), point(1, 10), point(11, 10), point(40, 6)]))[
        "glucose"
    ]
    assert view["coveredMinutes"] == 11
    assert view["cohorts"][0]["metrics"]["meanMmol"] == pytest.approx(107 / 11)
    assert any(e["reason"] == "GAP" for e in view["exceptions"])


def test_conflicts_suspect_quality_and_context_changes_break_continuity():
    conflicting = point(5, 9).model_copy(update={"recordId": str(UUID(int=8))})
    suspect = point(8, 3).model_copy(update={"quality": "SUSPECT"})
    rows = [point(0), point(5), conflicting, point(6), suspect, point(9)]
    view = document(prepare(rows))["glucose"]
    assert view["conflictingTimestamps"] == 1
    assert view["qualityCounts"]["SUSPECT"] == 1
    assert view["coveredMinutes"] == 0
    assert {e["reason"] for e in view["exceptions"]} >= {"CONFLICT", "SUSPECT"}


def test_same_timestamp_aliases_count_once_and_sources_do_not_pool():
    alias = point(0).model_copy(update={"recordId": str(UUID(int=9))})
    rows = [
        point(0),
        alias,
        point(1),
        point(2).model_copy(update={"source": "AAPS"}),
        point(3).model_copy(update={"source": "AAPS"}),
    ]
    view = document(prepare(rows))["glucose"]
    assert len(view["cohorts"]) == 2
    assert view["coveredMinutes"] == 2
    assert sorted(s["rows"][0][0] for s in view["series"]) == [2, 2]


def test_sensor_change_cuts_epoch_and_missing_analysis_range_is_unknown():
    change = HistoryRecord.model_validate(
        record(
            recordId=str(UUID(int=55)),
            kind="THERAPY",
            eventAtMs=START + 5 * MINUTE,
            observedAtMs=NOW,
            data={"eventType": "SENSOR_CHANGE"},
        )
    )
    view = document(
        prepare(
            [point(0), point(4), point(5), point(6), point(9)],
            sensor_changes=(change,),
            ranges=(),
        )
    )["glucose"]
    assert len(view["cohorts"]) == 2 and view["coveredMinutes"] == 7
    assert all(c["metrics"]["inRangePct"] is None for c in view["cohorts"])
    assert not view["episodes"]


def test_source_order_and_mutation_cannot_change_prepared_frame_or_manifest():
    rows = [point(0), point(5), point(10)]
    before = [r.model_dump() for r in rows]
    first = prepare(rows)
    second = prepare(list(reversed(rows)))
    assert first == second
    assert [r.model_dump() for r in rows] == before
    rows[0].data["glucoseMmol"] = 40.0
    assert document(first)["glucose"]["series"][0]["rows"][0][5] == 600
    manifest = json.loads(first.manifest_body)
    assert (
        manifest["frameDigest"]
        == first.input_digest
        == hashlib.sha256(first.input_body).hexdigest()
    )
    assert manifest["sourceDigest"] == first.source_digest
    assert manifest["tokenStatus"] == "UNVERIFIED"
    assert "DECODED_INPUT_NOT_AUTHENTICATED" in document(first)["provenance"]


@pytest.mark.parametrize("days", [3, 7, 14])
def test_dense_minute_history_fits_without_changing_raw_fact_counts(days):
    start = END - days * 1440 * MINUTE
    rows = [
        HistoryRecord.model_validate(
            record(
                recordId=str(UUID(int=i + 1)),
                eventAtMs=start + i * MINUTE,
                observedAtMs=NOW,
                quality="OK",
                data={"glucoseMmol": 6.0},
            )
        )
        for i in range(days * 1440 + 1)
    ]
    prepared = prepare(rows, from_ms=start, ranges=())
    view = document(prepared)["glucose"]
    assert view["coveredMinutes"] == days * 1440
    assert view["inputRecordCount"] == len(rows)
    assert len(prepared.input_body) <= 96 * 1024
    assert all(
        s["stepMinutes"] == 10 or s["endMs"] <= END - 3 * 1440 * MINUTE
        for s in view["series"]
    )


def test_focus_preserves_old_requested_window_when_other_background_coarsens():
    start = END - 14 * 1440 * MINUTE
    rows = [
        HistoryRecord.model_validate(
            record(
                recordId=str(UUID(int=i + 1)),
                eventAtMs=start + i * MINUTE,
                observedAtMs=NOW,
                quality="OK",
                data={"glucoseMmol": 6.0},
            )
        )
        for i in range(14 * 1440 + 1)
    ]
    focus = module().FocusWindow(start + 60 * MINUTE, start + 90 * MINUTE, 1)
    prepared = prepare(
        rows, from_ms=start, ranges=(), focus_windows=(focus,), max_bytes=48 * 1024
    )
    series = document(prepared)["glucose"]["series"]
    assert any(
        s["startMs"] == focus.start_ms
        and s["endMs"] == focus.end_ms
        and s["stepMinutes"] == 1
        for s in series
    )
    assert any(s["stepMinutes"] in (30, 60) for s in series)


def test_exact_supplied_sections_and_question_survive_old_assistant_removal():
    turns = tuple(
        module().ContextTurn("assistant", "old text " * 500, f"exchange-{i}")
        for i in range(8)
    )
    profile, events = (
        encoded({"state": "OBSERVED", "iob": 2.0}),
        encoded([["meal", 24], ["insulin", 1.4]]),
    )
    prepared = prepare(
        [point(0), point(10)],
        profile_body=profile,
        events_body=events,
        chat_turns=turns,
        question="Keep this exact question",
        max_bytes=8 * 1024,
    )
    doc = document(prepared)
    assert doc["question"] == "Keep this exact question"
    assert doc["profile"] == json.loads(profile) and doc["events"] == json.loads(events)
    assert len(doc["conversation"]) < len(turns)
    assert json.loads(prepared.manifest_body)["omittedChatTurns"] > 0


def test_supplied_states_are_not_summed_and_remain_labelled_as_untrusted_data():
    events = [{"iob": 2.2, "at": START}, {"iob": 2.1, "at": START + MINUTE}]
    doc = document(prepare(events_body=encoded(events)))
    assert doc["events"] == events
    assert doc["profile"] == {"status": "NOT_SUPPLIED"}
    assert "NOT_CLINICAL_OR_DELIVERY_VALIDATION" in doc["limitations"]


def test_token_budget_is_explicit_and_counter_errors_cannot_admit_a_frame():
    budget = module().TokenBudget(20_000, 10_000, "synthetic-byte-counter", len)
    prepared = prepare([point(0), point(1)], token_budget=budget)
    manifest = json.loads(prepared.manifest_body)
    assert manifest["tokenStatus"] == "VERIFIED_BY_SUPPLIED_COUNTER"
    assert manifest["inputTokens"] == len(prepared.input_body) <= 10_000
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        prepare(
            [point(0), point(1)], token_budget=replace(budget, window_tokens=10_001)
        )
    with pytest.raises(ValueError, match="compact_token_accounting_failed"):
        prepare(token_budget=replace(budget, counter=lambda _: float("nan")))


@pytest.mark.parametrize(
    "bad",
    [
        b"null",
        b'"text"',
        b'{"x":NaN}',
        b'{"x":1,"x":2}',
        b'{ "x":1}',
        b"\xff",
        b"[]" * 100_000,
    ],
    ids=["null", "scalar", "nonfinite", "duplicate", "noncanonical", "utf8", "large"],
)
def test_bad_or_unbounded_supplied_section_is_rejected_without_raw_error(bad):
    with pytest.raises(ValueError, match="compact_context_invalid_input"):
        prepare(profile_body=bad)


@pytest.mark.parametrize(
    "changes",
    [
        dict(zone_id="not/a-zone"),
        dict(until_ms=NOW + 1),
        dict(snapshot_sequence=True),
        dict(max_bytes=131_073),
        dict(question=""),
        dict(question="x" * 8193),
        dict(focus_windows=((START, END),)),
    ],
)
def test_invalid_request_is_rejected(changes):
    with pytest.raises(ValueError, match="compact_context_invalid_input"):
        prepare(**changes)


def test_future_duplicate_deleted_or_other_kind_cannot_become_current_glucose():
    deleted = point(0).model_copy(update={"deleted": True, "data": None})
    assert not document(prepare([deleted]))["glucose"]["cohorts"]
    other = HistoryRecord.model_validate(
        record(
            kind="THERAPY",
            eventAtMs=START,
            observedAtMs=NOW,
            data={"eventType": "CARBS", "carbsGrams": 1.0},
        )
    )
    for rows in (
        [point(0), point(0)],
        [point(0).model_copy(update={"observedAtMs": NOW + 1})],
        [other],
    ):
        with pytest.raises(ValueError, match="compact_context_invalid_input"):
            prepare(rows)


def test_dst_day_uses_actual_elapsed_window_without_a_144_bin_assumption():
    start = int(datetime(2026, 10, 25, tzinfo=UTC).timestamp() * 1000)
    end = start + 25 * 60 * MINUTE
    rows = [
        HistoryRecord.model_validate(
            record(
                recordId=str(UUID(int=i + 1)),
                eventAtMs=start + i * 10 * MINUTE,
                observedAtMs=end + MINUTE,
                quality="OK",
                data={"glucoseMmol": 6.0},
            )
        )
        for i in range(151)
    ]
    doc = document(
        prepare(
            rows,
            from_ms=start,
            until_ms=end,
            now_ms=end + MINUTE,
            zone_id="Europe/Berlin",
            ranges=(),
        )
    )
    assert doc["glucose"]["coveredMinutes"] == 1500
    assert len(doc["glucose"]["series"][0]["rows"]) == 150


def test_shared_daily_preparation_does_not_retain_extra_unusable_payloads():
    from app.ai.daily_glucose import _prepare_series

    suspect = point(0).model_copy(update={"quality": "SUSPECT"})
    prepared = _prepare_series([suspect], [], START, END)
    assert prepared.counts["SUSPECT"] == 1
    assert prepared.unusable == []


def test_oldest_assistant_is_removed_without_erasing_a_recent_relevant_answer():
    old = module().ContextTurn("assistant", "old " * 400, "old-answer")
    recent = module().ContextTurn("assistant", "recent " * 300, "recent-answer")
    exact_limit = len(prepare(chat_turns=(recent,)).input_body)
    result = prepare(chat_turns=(old, recent), max_bytes=exact_limit)
    doc = document(result)
    assert [t["reference"] for t in doc["conversation"]] == ["recent-answer"]
    assert doc["omittedChatTurns"] == 1


def test_chat_pruning_projects_each_glucose_resolution_at_most_once(monkeypatch):
    implementation = module()
    original = implementation.project_glucose
    calls = []

    def counted(*args):
        calls.append(args[5])
        return original(*args)

    monkeypatch.setattr(implementation, "project_glucose", counted)
    turns = tuple(
        implementation.ContextTurn("assistant", "prior " * 500, f"answer-{i}")
        for i in range(10)
    )
    doc = document(prepare([point(0), point(1)], chat_turns=turns, max_bytes=8192))
    assert doc["omittedChatTurns"] > 1
    assert len(calls) == len(set(calls))


def test_reordering_sensor_changes_cannot_change_the_same_snapshot_frame():
    changes = [
        HistoryRecord.model_validate(
            record(
                recordId=str(UUID(int=i + 50)),
                kind="THERAPY",
                eventAtMs=START + i * MINUTE,
                observedAtMs=NOW,
                data={"eventType": "SENSOR_CHANGE"},
            )
        )
        for i in (2, 7)
    ]
    rows = [point(0), point(1), point(8), point(9)]
    assert prepare(rows, sensor_changes=changes) == prepare(
        rows, sensor_changes=list(reversed(changes))
    )


def test_partial_bins_clip_supported_segments_and_keep_actual_extrema_offsets():
    doc = document(
        prepare(
            [point(0), point(5), point(10)],
            from_ms=START + 30_000,
            until_ms=START + 9 * MINUTE + 30_000,
        )
    )
    series = doc["glucose"]["series"][0]
    assert doc["glucose"]["coveredMinutes"] == 9
    assert len(series["rows"]) == 1 and series["rows"][0][1] == 540
    assert series["extremaOffsetsMs"] == [[270_000, 270_000]]


def test_utf8_control_text_and_maximum_question_are_exact_data_not_instructions():
    question = "я" * 4096
    profile = encoded({"note": '\n\x00" preserve text; not system instructions'})
    result = prepare(question=question, profile_body=profile)
    assert document(result)["question"] == question
    assert document(result)["profile"] == json.loads(profile)


def test_mandatory_events_or_exception_saturation_refuses_instead_of_hiding_detail():
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        prepare(events_body=encoded(["required" * 1000]), max_bytes=1024)
    rows = [point(i).model_copy(update={"quality": "SUSPECT"}) for i in range(300)]
    with pytest.raises(ValueError, match="compact_context_budget_exceeded"):
        prepare(rows)
