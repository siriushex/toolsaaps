import importlib
import importlib.util
from datetime import date, timedelta

import pytest

from app.ai.daily_glucose import AnalysisRange, build_daily_glucose, civil_day
from app.ai.history_contract import HistoryRecord
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, START
from test_ai_history_contract import record

BINDING = "ad" * 32
CONTEXT = dict(
    zoneId="UTC",
    sensorSessionId="session-1",
    calibrationModelId="calibration-1",
    glucoseBasis="RESOLVED",
    inputModelVersion="input-1",
)
RANGES = (
    AnalysisRange(
        startMs=START - 21 * 1440 * MINUTE,
        endMs=END,
        lowMmol=4.0,
        highMmol=8.0,
        version="range-1",
    ),
)


def comparison_module():
    assert importlib.util.find_spec("app.ai.glucose_comparison") is not None, (
        "glucose comparison calculator is absent"
    )
    return importlib.import_module("app.ai.glucose_comparison")


def facts(
    offset=0, value=6.0, *, minutes=1440, context=CONTEXT, quality="OK", ranges=RANGES
):
    day = (date.fromisoformat(DAY) - timedelta(days=offset)).isoformat()
    window = civil_day(day, "UTC")
    rows = [
        HistoryRecord.model_validate(
            record(
                eventAtMs=window.startMs + i * MINUTE,
                observedAtMs=NOW,
                quality=quality,
                context=context,
                data={"glucoseMmol": float(value)},
            )
        )
        for i in range(0, minutes + 1, 15)
    ]
    return build_daily_glucose(
        rows,
        report_date=day,
        zone_id="UTC",
        ranges=ranges,
        now_ms=NOW,
        snapshot_sequence=1000,
        source_binding=BINDING,
    )


def compare(current=None, history=()):
    return comparison_module().build_glucose_comparison(current or facts(), history)


def baseline(result, days=7):
    return next(b for b in result.analyses[0].baselines if b.lookbackDays == days)


def test_comparison_excludes_current_and_future_and_reports_missing_days():
    current = facts()
    result = compare(current, [facts(1), facts(8)])
    assert baseline(result).glucoseDays == 1
    assert baseline(result, 21).glucoseDays == 2
    assert len(baseline(result).missingDates) == 6
    assert current.window.reportDate not in [
        d.reportDate for d in baseline(result).days
    ]
    with pytest.raises(ValueError, match="invalid_glucose_comparison"):
        compare(current, [current])


def test_pooled_mean_variance_and_exposure_use_time_not_day_percentages():
    current = facts(value=7)
    result = compare(current, [facts(1, 4), facts(2, 10, minutes=1200)])
    value = baseline(result)
    expected = (1440 * 4 + 1200 * 10) / 2640
    assert value.glucoseDays == value.rangeDays == 2
    assert value.metrics.meanMmol == pytest.approx(expected)
    assert value.metrics.inRangePct == pytest.approx(1440 / 2640 * 100)
    assert value.metrics.sdMmol > 0
    assert value.deltaMeanMmol == pytest.approx(7 - expected)


def test_changed_range_excludes_exposure_but_not_compatible_glucose_moments():
    changed = (RANGES[0].model_copy(update={"version": "range-2"}),)
    value = baseline(compare(facts(), [facts(1, ranges=changed)]))
    assert value.glucoseDays == 1 and value.rangeDays == 0
    assert value.metrics.meanMmol == 6.0 and value.metrics.inRangePct is None
    assert value.days[-1].rangeReason == "RANGE_MISMATCH"


@pytest.mark.parametrize(
    "context", [None, {"zoneId": "UTC"}, CONTEXT | {"glucoseBasis": "UNKNOWN"}]
)
def test_missing_current_context_is_not_fabricated_as_same_sensor(context):
    result = compare(facts(context=context), [facts(1, context=context)])
    assert baseline(result).glucoseDays == 0
    assert "CURRENT_CONTEXT_UNAVAILABLE" in result.analyses[0].limitations
    assert not result.patterns


@pytest.mark.parametrize(
    "changes",
    [
        {"sensorSessionId": "different"},
        {"calibrationModelId": "different"},
        {"inputModelVersion": "different"},
        {"zoneId": "Asia/Tbilisi"},
    ],
)
def test_changed_sensor_calibration_model_or_zone_is_not_pooled(changes):
    value = baseline(compare(facts(), [facts(1, context=CONTEXT | changes)]))
    assert value.glucoseDays == 0
    assert value.days[-1].glucoseReason == "CONTEXT_MISMATCH"


def test_unknown_quality_and_sparse_day_do_not_supply_a_daily_baseline():
    result = compare(facts(), [facts(1, quality="UNKNOWN"), facts(2, minutes=120)])
    assert baseline(result).glucoseDays == 0
    assert {d.glucoseReason for d in baseline(result).days} >= {
        "QUALITY_NOT_OK",
        "LOW_DAY_COVERAGE",
    }


def test_two_hour_windows_work_with_sparse_days_and_count_distinct_days():
    current = facts(value=3, minutes=120)
    result = compare(current, [facts(1, 3, minutes=120), facts(2, 3, minutes=120)])
    assert baseline(result).glucoseDays == 0
    assert len(result.patterns) == 1
    pattern = result.patterns[0]
    assert pattern.band == "BELOW_RANGE" and pattern.startHour == 0
    assert pattern.qualifyingDays == pattern.eligibleWindows == 3
    assert pattern.status == "PROVISIONAL_RECURRENCE"
    assert len(pattern.evidence) == 3
    assert len({item.reportDate for item in pattern.evidence}) == 3


def test_one_or_two_days_are_observations_not_learned_patterns():
    current = facts(value=3, minutes=120)
    for history, count in [((), 1), ([facts(1, 3, minutes=120)], 2)]:
        pattern = compare(current, history).patterns[0]
        assert pattern.status == "OBSERVATION" and pattern.qualifyingDays == count


def test_missing_windows_are_not_normal_or_part_of_eligible_denominator():
    current = facts(value=3, minutes=120)
    result = compare(
        current,
        [facts(1, 3, minutes=120), facts(2, 6, minutes=120), facts(3, 3, minutes=30)],
    )
    pattern = result.patterns[0]
    assert pattern.qualifyingDays == 2 and pattern.eligibleWindows == 3
    assert any(e.exposureMinutes == 0 for e in pattern.evidence)
    assert len(pattern.excludedDays) == 19


def test_omitted_episode_list_cannot_hide_positive_hourly_exposure(monkeypatch):
    import app.ai.daily_glucose as daily

    monkeypatch.setattr(daily, "MAX_EPISODES", 0)
    result = compare(
        facts(value=3, minutes=120),
        [facts(1, 3, minutes=120), facts(2, 3, minutes=120)],
    )
    assert result.patterns[0].status == "PROVISIONAL_RECURRENCE"
    assert all(e.omittedEpisodes > 0 for e in result.patterns[0].evidence)


def test_pattern_limit_is_explicit_and_does_not_erase_baselines(monkeypatch):
    monkeypatch.setattr(comparison_module(), "MAX_PATTERNS", 2)
    result = compare(facts(value=3), [facts(1, 3), facts(2, 3)])
    assert len(result.patterns) == 2 and result.omittedPatterns == 10
    assert baseline(result).glucoseDays == 2


@pytest.mark.parametrize(
    "changes",
    [{"sourceBinding": "bb" * 32}, {"snapshotSequence": 1001}, {"asOfMs": NOW + 1}],
)
def test_mixed_device_or_snapshot_fails_closed(changes):
    with pytest.raises(ValueError, match="invalid_glucose_comparison"):
        compare(facts(), [facts(1).model_copy(update=changes)])


def test_duplicate_days_and_unbound_facts_are_rejected():
    previous = facts(1)
    with pytest.raises(ValueError, match="invalid_glucose_comparison"):
        compare(facts(), [previous, previous])
    with pytest.raises(ValueError, match="invalid_glucose_comparison"):
        compare(facts().model_copy(update={"sourceBinding": None}))


def test_comparison_digest_is_deterministic_and_binds_history_and_policy():
    current, previous, older = facts(), facts(1), facts(2)
    a, b = compare(current, [previous, older]), compare(current, [older, previous])
    assert a.model_dump() == b.model_dump()
    assert a.sourceDigest != compare(current, [previous]).sourceDigest


def test_future_day_is_rejected_even_if_its_snapshot_cut_has_already_passed():
    future_cut = NOW + 3 * 1440 * MINUTE
    current = facts().model_copy(update={"asOfMs": future_cut})
    future = facts(1).model_copy(
        update={
            "asOfMs": future_cut,
            "window": civil_day(
                (date.fromisoformat(DAY) + timedelta(days=1)).isoformat(), "UTC"
            ),
        }
    )
    with pytest.raises(ValueError, match="invalid_glucose_comparison"):
        compare(current, [future])


def test_partial_or_mixed_range_history_is_not_a_comparable_daily_range():
    for ranges in (
        (RANGES[0].model_copy(update={"startMs": START - 1440 * MINUTE + MINUTE}),),
        (
            RANGES[0].model_copy(update={"endMs": START - 720 * MINUTE}),
            RANGES[0].model_copy(
                update={"startMs": START - 720 * MINUTE, "version": "range-2"}
            ),
        ),
    ):
        result = baseline(compare(facts(), [facts(1, ranges=ranges)]))
        assert result.glucoseDays == 1 and result.rangeDays == 0
        assert result.days[-1].rangeReason == "RANGE_UNAVAILABLE"


def test_sparse_current_day_has_no_daily_delta_but_retains_eligible_baseline():
    result = baseline(compare(facts(value=3, minutes=120), [facts(1)]))
    assert result.glucoseDays == 1
    assert result.deltaMeanMmol is result.deltaInRangePercentagePoints is None


def test_dst_day_keeps_daily_comparison_but_has_no_clock_window_patterns():
    from app.ai.daily_glucose import HOUR_MS

    current_day, previous_day = "2026-03-29", "2026-03-28"
    cut = civil_day(current_day, "Europe/Berlin").endMs + HOUR_MS

    def berlin(day):
        window = civil_day(day, "Europe/Berlin")
        observations = [
            HistoryRecord.model_validate(
                record(
                    eventAtMs=at,
                    observedAtMs=cut,
                    quality="OK",
                    context=CONTEXT | {"zoneId": "Europe/Berlin"},
                    data={"glucoseMmol": 3.0},
                )
            )
            for at in range(window.startMs, window.endMs + 1, 15 * MINUTE)
        ]
        return build_daily_glucose(
            observations,
            report_date=day,
            zone_id="Europe/Berlin",
            ranges=(),
            now_ms=cut,
            snapshot_sequence=1000,
            source_binding=BINDING,
        )

    result = compare(berlin(current_day), [berlin(previous_day)])
    assert baseline(result).glucoseDays == 1
    assert baseline(result).deltaMeanMmol == 0.0
    assert "CURRENT_CLOCK_DISCONTINUITY" in result.analyses[0].limitations
    assert not result.patterns
