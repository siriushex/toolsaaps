import importlib
import importlib.util
import math
from datetime import UTC, datetime
from uuid import uuid4

import pytest

from app.ai.history_contract import HistoryRecord
from test_ai_history_contract import record

DAY = "2026-10-13"
START = int(datetime(2026, 10, 13, tzinfo=UTC).timestamp() * 1000)
MINUTE = 60_000
END = START + 1440 * MINUTE
NOW = END + 60 * MINUTE


def module():
    assert importlib.util.find_spec("app.ai.daily_glucose") is not None, (
        "daily glucose facts calculator is absent"
    )
    return importlib.import_module("app.ai.daily_glucose")


def point(minutes, value=6.0, **changes):
    return HistoryRecord.model_validate(
        record(
            **(
                dict(
                    eventAtMs=START + int(minutes * MINUTE),
                    observedAtMs=NOW,
                    quality="OK",
                    data={"glucoseMmol": float(value)},
                )
                | changes
            )
        )
    )


def target(start=START, end=END, low=4.0, high=8.0, version="range-1"):
    return module().AnalysisRange(
        startMs=start, endMs=end, lowMmol=low, highMmol=high, version=version
    )


def build(points=(), ranges=None, **changes):
    options = dict(
        report_date=DAY,
        zone_id="UTC",
        now_ms=NOW,
        ranges=(target(),) if ranges is None else ranges,
        snapshot_sequence=7,
    )
    return module().build_daily_glucose(points, **(options | changes))


def metrics(points, **options):
    facts = build(points, **options)
    assert len(facts.cohorts) == 1
    return facts.cohorts[0].metrics


def test_empty_and_single_point_never_extrapolate_or_claim_normality():
    empty = build()
    assert empty.dayMinutes == 1440.0
    assert empty.coveredMinutes == 0.0 and empty.missingMinutes == 1440.0
    assert empty.provenance == "DEVICE_DECLARED"
    assert empty.dataCompleteness == "UNKNOWN"
    assert not empty.cohorts and not empty.episodes
    single = metrics([point(10)])
    assert single.coveredMinutes == 0.0
    assert single.meanMmol is single.inRangePct is None


def test_constant_segment_has_time_weighted_metrics_and_range_coverage():
    result = metrics([point(0), point(15)])
    assert result.coveredMinutes == 15.0
    assert result.meanMmol == 6.0 and result.sdMmol == result.cvPct == 0.0
    assert result.inRangeMinutes == 15.0 and result.inRangePct == 100.0
    assert result.rangeCoveredMinutes == 15.0 and result.rangeMissingMinutes == 0.0


def test_linear_crossings_integrate_exposure_mean_and_variance_exactly():
    facts = build([point(0, 2), point(12, 10)])
    result = facts.cohorts[0].metrics
    assert result.belowMinutes == result.aboveMinutes == 3.0
    assert result.inRangeMinutes == 6.0
    assert result.meanMmol == 6.0
    assert result.sdMmol == pytest.approx(math.sqrt(64 / 12))
    assert [(item.band, item.minutes) for item in facts.episodes] == [
        ("BELOW_RANGE", 3.0),
        ("ABOVE_RANGE", 3.0),
    ]
    assert all(item.estimatedBoundaries for item in facts.episodes)
    assert all(item.evidenceCount == 2 for item in facts.episodes)


def test_irregular_sampling_is_not_a_record_weighted_average():
    result = metrics([point(0, 4), point(1, 10), point(11, 10)])
    assert result.meanMmol == pytest.approx(107 / 11)
    assert result.meanMmol != 8.0


def test_gap_limit_and_no_extrapolation():
    facts = build([point(0), point(15), point(31), point(40)])
    assert facts.coveredMinutes == 24.0
    assert facts.maxGapMinutes == 16.0
    assert facts.missingMinutes == 1416.0


def test_boundary_guards_are_clipped_to_the_requested_day():
    facts = build([point(-5, 2), point(5, 10), point(1435, 10), point(1445, 2)])
    assert facts.coveredMinutes == 10.0
    result = facts.cohorts[0].metrics
    assert result.meanMmol == 8.0
    assert result.inRangeMinutes == 5.0 and result.aboveMinutes == 5.0
    assert facts.inDayRecordCount == 2 and facts.inputRecordCount == 4


def test_unknown_range_is_not_a_default_and_partial_range_has_own_denominator():
    points = [point(0), point(10)]
    unknown = metrics(points, ranges=())
    assert unknown.rangeMissingMinutes == 10.0
    assert unknown.inRangeMinutes is unknown.inRangePct is None
    partial = metrics(points, ranges=(target(start=START + 5 * MINUTE),))
    assert partial.rangeCoveredMinutes == partial.rangeMissingMinutes == 5.0
    assert partial.inRangeMinutes == 5.0 and partial.inRangePct == 100.0


def test_range_changes_do_not_merge_episodes_across_versions():
    ranges = (
        target(end=START + 5 * MINUTE, high=5.0),
        target(start=START + 5 * MINUTE, high=5.0, version="range-2"),
    )
    facts = build([point(0), point(10)], ranges=ranges)
    assert facts.cohorts[0].metrics.aboveMinutes == 10.0
    assert [item.rangeVersion for item in facts.episodes] == ["range-1", "range-2"]


@pytest.mark.parametrize(
    "changes",
    [
        dict(quality="UNKNOWN"),
        dict(source="AAPS"),
        dict(context={"sensorSessionId": "session-b"}),
        dict(context={"calibrationModelId": "calibration-b"}),
    ],
)
def test_incompatible_observation_breaks_interpolation(changes):
    facts = build([point(0), point(5, **changes), point(10), point(15)])
    assert facts.coveredMinutes == 5.0


def test_suspect_values_break_interpolation_and_unknown_is_separate():
    facts = build(
        [
            point(0),
            point(5, quality="SUSPECT"),
            point(10),
            point(15),
            point(20, quality="UNKNOWN"),
            point(25, quality="UNKNOWN"),
        ]
    )
    assert facts.coveredMinutes == 10.0
    assert facts.qualityOkCoveredMinutes == facts.unverifiedCoveredMinutes == 5.0
    assert facts.qualityCounts.SUSPECT == 1
    assert {item.quality for item in facts.cohorts} == {"OK", "UNKNOWN"}


def test_same_timestamp_duplicates_do_not_inflate_time_and_conflicts_break_it():
    duplicate = build([point(0), point(0), point(10)])
    assert duplicate.coveredMinutes == 10.0
    assert duplicate.conflictingTimestamps == 0
    conflict = build([point(0), point(5, 6), point(5, 7), point(10)])
    assert conflict.coveredMinutes == 0.0 and conflict.conflictingTimestamps == 1


def test_repeated_id_is_not_an_independent_observation_or_revision_selection():
    row = point(0)
    with pytest.raises(ValueError, match="invalid_daily_glucose_input"):
        build([row, row])


def test_episode_evidence_and_summary_are_bounded_without_losing_metrics():
    points = [point(i, 3) for i in range(61)]
    facts = build(points)
    episode = facts.episodes[0]
    assert episode.minutes == 60.0 and episode.evidenceCount == 61
    assert len(episode.evidence) <= module().MAX_EPISODE_REFERENCES
    assert episode.omittedEvidenceCount == 61 - len(episode.evidence)
    assert facts.cohorts[0].metrics.belowMinutes == 60.0


def test_source_digest_and_output_are_deterministic_but_bind_revisions():
    points = [point(0), point(10)]
    a, b = build(points), build(list(reversed(points)))
    assert a.model_dump() == b.model_dump()
    changed = points[0].model_copy(update={"revision": 2})
    assert build([changed, points[1]]).sourceDigest != a.sourceDigest
    assert a.snapshotSequence == 7 and len(a.sourceDigest) == 64


@pytest.mark.parametrize(
    "day,zone,minutes",
    [
        ("2026-03-29", "Europe/Berlin", 1380),
        ("2026-10-25", "Europe/Berlin", 1500),
        ("2026-10-13", "Asia/Tbilisi", 1440),
    ],
)
def test_local_civil_days_are_not_fixed_utc_days(day, zone, minutes):
    window = module().civil_day(day, zone)
    assert (window.endMs - window.startMs) / MINUTE == minutes


@pytest.mark.parametrize(
    "day,zone",
    [
        ("20261013", "UTC"),
        ("2026-02-30", "UTC"),
        ("2011-12-30", "Pacific/Apia"),
        (DAY, "Unknown/Zone"),
    ],
)
def test_invalid_or_nonexistent_days_rejected(day, zone):
    with pytest.raises(ValueError, match="invalid_daily_window"):
        module().civil_day(day, zone)


def test_unfinished_day_and_overlapping_ranges_are_rejected():
    with pytest.raises(ValueError, match="invalid_daily_glucose_input"):
        build(now_ms=END - 1)
    with pytest.raises(ValueError, match="invalid_daily_glucose_input"):
        build(ranges=(target(), target(version="other")))


def test_input_limits_and_outside_scope_fail_closed(monkeypatch):
    monkeypatch.setattr(module(), "MAX_INPUT_RECORDS", 1)
    with pytest.raises(ValueError, match="daily_glucose_capacity"):
        build([point(0), point(10)])
    wrong = HistoryRecord.model_validate(
        record(
            recordId=str(uuid4()),
            kind="THERAPY",
            data={"eventType": "BOLUS", "insulinUnits": 2.0},
        )
    )
    with pytest.raises(ValueError, match="invalid_daily_glucose_input"):
        build([wrong])


@pytest.mark.parametrize("value", [4.0, 8.0])
def test_range_endpoints_are_inside(value):
    result = metrics([point(0, value), point(10, value)])
    assert result.inRangePct == 100.0
    assert result.belowMinutes == result.aboveMinutes == 0.0


def test_different_sources_at_identical_time_are_not_selected_arbitrarily():
    facts = build([point(0), point(5), point(5, source="AAPS"), point(10)])
    assert facts.conflictingTimestamps == 1 and facts.coveredMinutes == 0.0


def test_episode_summary_limit_does_not_reduce_integrated_exposure(monkeypatch):
    monkeypatch.setattr(module(), "MAX_EPISODES", 2)
    facts = build([point(i, 3 if i % 2 == 0 else 9) for i in range(11)])
    assert len(facts.episodes) == 2 and facts.omittedEpisodes > 0
    result = facts.cohorts[0].metrics
    assert result.coveredMinutes == 10.0
    assert result.belowMinutes == pytest.approx(10 / 6)
    assert result.aboveMinutes == pytest.approx(10 / 6)


@pytest.mark.parametrize(
    "options",
    [
        dict(snapshot_sequence=True),
        dict(now_ms=True),
        dict(snapshot_sequence=-1),
        dict(ranges=["wrong-type"]),
    ],
)
def test_invalid_scalar_types_fail_closed(options):
    with pytest.raises(ValueError, match="invalid_daily_glucose_input"):
        build(**options)


def test_mutated_model_data_is_revalidated_not_trusted_as_a_frozen_instance():
    row = point(0)
    row.data["glucoseMmol"] = float("nan")
    with pytest.raises(ValueError, match="invalid_daily_glucose_input"):
        build([row])


def test_maximum_uncovered_interval_includes_day_edges():
    facts = build([point(10), point(20)])
    assert facts.maxGapMinutes == 10.0
    assert facts.maxUncoveredMinutes == 1420.0


def test_cohort_limit_is_explicit_failure(monkeypatch):
    monkeypatch.setattr(module(), "MAX_COHORTS", 1)
    with pytest.raises(ValueError, match="daily_glucose_capacity"):
        build([point(0), point(10, quality="UNKNOWN")])


def test_exposure_partition_and_weighted_moments_for_fixed_seed_ramps():
    import random

    randomizer = random.Random(713)
    for _ in range(50):
        left, right = randomizer.uniform(1, 12), randomizer.uniform(1, 12)
        duration = randomizer.uniform(0.1, 15)
        actual_duration = int(duration * MINUTE) / MINUTE
        result = metrics([point(0, left), point(duration, right)])
        assert result.coveredMinutes == pytest.approx(actual_duration)
        assert result.meanMmol == pytest.approx((left + right) / 2)
        assert result.sdMmol == pytest.approx(abs(right - left) / math.sqrt(12))
        assert (
            result.belowMinutes + result.inRangeMinutes + result.aboveMinutes
            == pytest.approx(actual_duration)
        )
        assert result.belowPct + result.inRangePct + result.abovePct == pytest.approx(
            100
        )


def test_dense_full_day_coverage_does_not_accumulate_duration_roundoff():
    facts = build([point(i * 1440 / 4000) for i in range(4001)])
    assert facts.coveredMinutes == facts.dayMinutes == 1440.0
    assert facts.coveragePct == 100.0 and facts.missingMinutes == 0.0
    assert facts.cohorts[0].metrics.rangeCoveredMinutes == 1440.0


def test_hourly_metrics_are_sparse_and_split_crossings_without_changing_totals():
    facts = build([point(55, 2), point(65, 10)])
    cohort = facts.cohorts[0]
    assert facts.hourlyStatus == "AVAILABLE"
    assert [h.hour for h in cohort.hours] == [0, 1]
    assert [h.metrics.coveredMinutes for h in cohort.hours] == [5.0, 5.0]
    assert cohort.hours[0].metrics.belowMinutes == 2.5
    assert cohort.hours[1].metrics.aboveMinutes == 2.5
    assert cohort.metrics.coveredMinutes == sum(
        h.metrics.coveredMinutes for h in cohort.hours
    )


def test_discontinuous_clock_keeps_daily_totals_but_withholds_hourly_claims():
    facts = build([], report_date="2026-03-29", zone_id="Europe/Berlin")
    assert facts.dayMinutes == 1380.0
    assert facts.hourlyStatus == "UNAVAILABLE_CLOCK_DISCONTINUITY"
