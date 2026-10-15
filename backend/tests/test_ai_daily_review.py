import importlib
import importlib.util
import hashlib
import json
from dataclasses import replace

import pytest

from app.ai.daily_glucose import build_daily_glucose
from app.ai.daily_reports import DailyFactsReport
from app.ai.glucose_comparison import DailyGlucoseReview, build_glucose_comparison
from app.ai.history_contract import HistoryRecord, encoded
from test_ai_daily_glucose import DAY, END, MINUTE, NOW, START
from test_ai_daily_reports import get, save
from test_ai_glucose_comparison import BINDING, CONTEXT, RANGES, facts
from test_ai_history_contract import record
from test_ai_history_store import KEY, OTHER, OWNER, store


def module():
    assert importlib.util.find_spec("app.ai.daily_review") is not None, (
        "grounded daily review contract is absent"
    )
    return importlib.import_module("app.ai.daily_review")


def report(value=3.0, *, minutes=1440, history_days=2, quality="OK"):
    current = facts(value=value, minutes=minutes, quality=quality)
    history = [
        facts(i, value, minutes=minutes, quality=quality)
        for i in range(1, history_days + 1)
    ]
    return archive_review(current, history)


def archive_review(current, history=()):
    review = DailyGlucoseReview(
        current=current, comparison=build_glucose_comparison(current, history)
    )

    key = hashlib.sha256(encoded([current.sourceBinding, DAY, "UTC"])).hexdigest()
    return DailyFactsReport(
        archiveSequence=1,
        reportKey=key,
        reportRevision=1,
        createdAtMs=NOW,
        expiresAtMs=END + module().RETENTION_MS,
        factsDigest=hashlib.sha256(encoded(review.model_dump(mode="json"))).hexdigest(),
        sourceCheck="MATCHED",
        facts=review,
    )


def prepare(saved=None, **options):
    params = dict(now_ms=NOW, language="ru-RU")
    return module().prepare_review(saved or report(), **(params | options))


def packet(work):
    return json.loads(work.input_body)["input"]


def answer(work, **changes):
    data = packet(work)
    candidate = data["candidates"][0] if data["candidates"] else None
    findings = (
        []
        if candidate is None
        else [
            dict(
                candidateId=candidate["pattern"]["candidateId"],
                action=candidate["pattern"]["allowedAction"],
                alternativeCodes=[candidate["alternativeCodes"][0]],
                interpretation="Observed exposure warrants reviewing the recorded context.",
            )
        ]
    )
    result = dict(
        revision="daily-review-v1",
        factsDigest=data["factsDigest"],
        packetDigest=work.input_digest,
        status=data["requiredStatus"],
        findings=findings,
        missingInputCodes=data["missingInputCodes"],
        summary="Review the supplied observations and data limitations.",
    )
    return encoded(result | changes)


def validate(work, body=None, **options):
    params = dict(now_ms=NOW, current_source_digest=work.source_digest)
    return module().validate_review(
        work, answer(work) if body is None else body, **(params | options)
    )


def test_prepare_is_bounded_deterministic_explicit_and_does_not_select_a_model():
    value = report()
    work = prepare(value)
    document = packet(work)
    assert work == prepare(value, now_ms=NOW + 1)
    assert len(work.input_body) <= 65_536
    assert document["scope"] == "GLUCOSE_ONLY"
    assert document["dataCompleteness"] == "UNKNOWN"
    assert document["omissions"]["inputPatterns"] == 6
    assert len(document["candidates"]) == 6
    assert document["requiredStatus"] == "LIMITED_DATA"
    assert not {"model", "tools", "actions", "systemPrompt"} & document.keys()
    assert "MEALS_NOT_ANALYZED" in document["missingInputCodes"]
    assert work.prompt_digest == module().PROMPT_DIGEST


def test_validated_view_uses_exact_facts_and_is_not_medical_or_execution_admission():
    work = prepare(report(minutes=120))
    checked = validate(work)
    assert checked.validation == "REFERENCES_ONLY"
    assert checked.publicationState == "NOT_ADMITTED"
    assert checked.textMeaning == "UNREVIEWED_AI_INTERPRETATION"
    source = packet(work)["candidates"][0]["pattern"]
    finding = checked.findings[0]
    assert finding.pattern.model_dump(mode="json") == source
    assert finding.pattern.qualifyingDays == finding.pattern.eligibleWindows == 3
    assert finding.pattern.status == "PROVISIONAL_RECURRENCE"
    assert finding.action == source["allowedAction"]


def test_model_input_supplies_the_digest_it_must_echo():
    work = prepare(report(minutes=120))
    assert json.loads(work.input_body).get("packetDigest") == work.input_digest


def test_model_input_supplies_the_exact_closed_result_schema():
    work = prepare(report(minutes=120))
    schema = json.loads(work.input_body).get("resultSchema", {})
    assert schema.get("additionalProperties") is False
    assert set(schema["required"]) == {
        "revision",
        "factsDigest",
        "packetDigest",
        "status",
        "findings",
        "missingInputCodes",
        "summary",
    }


def test_model_result_schema_cannot_be_changed_without_source_admission():
    work = prepare(report(minutes=120))
    envelope = json.loads(work.input_body)
    envelope["resultSchema"]["additionalProperties"] = True
    changed = replace(work, input_body=encoded(envelope))
    with pytest.raises(ValueError, match="daily_review"):
        validate(changed, answer(work))


def test_matching_supplied_digest_is_not_an_independent_freshness_certificate():
    work = prepare(report(minutes=120))
    checked = validate(work)
    assert getattr(checked, "sourceFreshness", None) == "NOT_INDEPENDENTLY_VERIFIED"


def test_unknown_quality_is_limited_data_and_cannot_become_a_normal_day():
    work = prepare(report(quality="UNKNOWN"))
    assert packet(work)["requiredStatus"] == "LIMITED_DATA"
    assert not packet(work)["candidates"]
    assert "UNVERIFIED_GLUCOSE" in packet(work)["missingInputCodes"]
    assert validate(work).findings == ()
    with pytest.raises(ValueError, match="daily_review"):
        validate(work, answer(work, status="NO_SUPPORTED_PATTERN"))


def test_no_supported_pattern_differs_from_missing_data():
    work = prepare(report(value=6.0))
    assert packet(work)["requiredStatus"] == "NO_SUPPORTED_PATTERN"
    assert validate(work).status == "NO_SUPPORTED_PATTERN"


@pytest.mark.parametrize(
    "field,value",
    [
        ("factsDigest", "0" * 64),
        ("packetDigest", "0" * 64),
        ("status", "NO_SUPPORTED_PATTERN"),
        ("target", 4.5),
        ("tools", []),
        ("dose", 1.0),
        ("actionParameters", {}),
    ],
)
def test_unknown_binding_status_and_action_fields_are_rejected(field, value):
    work = prepare(report(minutes=120))
    with pytest.raises(ValueError, match="daily_review"):
        validate(work, answer(work, **{field: value}))


def test_unknown_candidates_actions_alternatives_and_duplicates_are_rejected():
    work = prepare(report(minutes=120))
    original = json.loads(answer(work))["findings"][0]
    for changed in (
        [original | {"candidateId": "0" * 64}],
        [original | {"action": "LOWER_TARGET"}],
        [original | {"alternativeCodes": ["ILLNESS_CONFIRMED"]}],
        [original, original],
        [original | {"alternativeCodes": [original["alternativeCodes"][0]] * 2}],
        [original | {"recordIds": ["invented"]}],
    ):
        with pytest.raises(ValueError, match="daily_review"):
            validate(work, answer(work, findings=changed))


@pytest.mark.parametrize(
    "text",
    [
        "Lower by 10 percent",
        "Value is \u0665",
        "Value is \u00b2",
        "<script>run()</script>",
        "https://example.invalid/action",
        "bad\u202etext",
        "bad\x00text",
        " " * 5,
    ],
)
def test_numerals_active_markup_links_and_controls_are_not_qualitative_text(text):
    work = prepare(report(minutes=120))
    with pytest.raises(ValueError, match="daily_review"):
        validate(work, answer(work, summary=text))


def test_missing_inputs_cannot_be_hidden_or_invented():
    work = prepare(report(minutes=120))
    for codes in ([], ["INVENTED"], packet(work)["missingInputCodes"] * 2):
        with pytest.raises(ValueError, match="daily_review"):
            validate(work, answer(work, missingInputCodes=codes))


def test_stale_expired_and_unknown_source_reports_cannot_be_prepared():
    saved = report(minutes=120)
    for changed in (
        saved.model_copy(update={"sourceCheck": "NOT_CHECKED"}),
        saved.model_copy(
            update={"sourceCheck": "CHANGED", "factsState": "STALE_SOURCE"}
        ),
        saved.model_copy(update={"factsDigest": "0" * 64}),
    ):
        with pytest.raises(ValueError, match="daily_review"):
            prepare(changed)
    with pytest.raises(ValueError, match="daily_review"):
        prepare(saved, now_ms=saved.expiresAtMs)


def test_changed_source_and_tampered_work_cannot_validate_a_reply():
    work = prepare(report(minutes=120))
    for changed, options in (
        (work, {"current_source_digest": "0" * 64}),
        (replace(work, input_body=work.input_body + b" "), {}),
        (replace(work, source_digest="0" * 64), {}),
        (replace(work, prompt_digest="0" * 64), {}),
    ):
        with pytest.raises(ValueError, match="daily_review"):
            validate(changed, answer(work), **options)


def test_duplicate_json_nonfinite_invalid_utf8_and_large_answers_fail_closed():
    work = prepare(report(minutes=120))
    for body in (
        answer(work)[:-1] + b',"status":"FINDINGS"}',
        b'{"value":NaN}',
        b"\xff",
        b"x" * 16_385,
        b"[]",
        b"null",
        b"{" + b"[" * 2000,
    ):
        with pytest.raises(ValueError, match="daily_review"):
            validate(work, body)


def test_input_limit_is_explicit_failure_not_partial_success(monkeypatch):
    saved = report(minutes=120)
    monkeypatch.setattr(module(), "MAX_INPUT_BYTES", 64)
    with pytest.raises(ValueError, match="daily_review"):
        prepare(saved)


def test_protected_store_bridge_requires_explicit_matching_digest(tmp_path):
    value = store(tmp_path)
    saved = save(value)
    assert callable(getattr(value, "prepare_daily_glucose_review", None)), (
        "protected preparation adapter is absent"
    )
    with pytest.raises(ValueError, match="daily_review"):
        value.prepare_daily_glucose_review(
            OWNER,
            KEY,
            report_date=DAY,
            zone_id="UTC",
            source_digest="0" * 64,
            now_ms=NOW,
        )
    work = value.prepare_daily_glucose_review(
        OWNER,
        KEY,
        report_date=DAY,
        zone_id="UTC",
        source_digest=saved.facts.comparison.sourceDigest,
        now_ms=NOW,
    )
    assert packet(work)["reportKey"] == get(value).reportKey
    assert packet(work)["requiredStatus"] == "LIMITED_DATA"
    assert validate(work).findings == ()


def test_low_exposure_cannot_be_replaced_by_a_high_only_explanation():
    rows = [
        HistoryRecord.model_validate(
            record(
                eventAtMs=START + i * MINUTE,
                observedAtMs=NOW,
                quality="OK",
                context=CONTEXT,
                data={"glucoseMmol": 3.0 if i < 120 else 10.0 if i < 240 else 6.0},
            )
        )
        for i in range(0, 1441, 15)
    ]
    current = build_daily_glucose(
        rows,
        report_date=DAY,
        zone_id="UTC",
        ranges=RANGES,
        now_ms=NOW,
        snapshot_sequence=1000,
        source_binding=BINDING,
    )
    work = prepare(archive_review(current))
    data = packet(work)
    assert data["requiredStatus"] == "FINDINGS"
    assert data["candidates"][0]["pattern"]["band"] == "BELOW_RANGE"
    high = next(c for c in data["candidates"] if c["pattern"]["band"] == "ABOVE_RANGE")
    above_only = dict(
        candidateId=high["pattern"]["candidateId"],
        action=high["pattern"]["allowedAction"],
        alternativeCodes=[high["alternativeCodes"][0]],
        interpretation="Review the observed high exposure and recorded context.",
    )
    with pytest.raises(ValueError, match="daily_review"):
        validate(work, answer(work, findings=[above_only]))
    assert validate(work).findings[0].pattern.band == "BELOW_RANGE"


def test_cohort_selection_discloses_omissions_and_never_becomes_a_normal_day():
    rows = [
        HistoryRecord.model_validate(
            record(
                eventAtMs=START + i * MINUTE,
                observedAtMs=NOW,
                quality="OK",
                context=CONTEXT
                | {"sensorSessionId": "session-" + str(min(i // 180, 7))},
                data={"glucoseMmol": 6.0},
            )
        )
        for i in range(0, 1441, 15)
    ]
    current = build_daily_glucose(
        rows,
        report_date=DAY,
        zone_id="UTC",
        ranges=RANGES,
        now_ms=NOW,
        snapshot_sequence=1000,
        source_binding=BINDING,
    )
    work = prepare(archive_review(current))
    data = packet(work)
    assert len(data["cohorts"]) == 6
    assert data["omissions"]["inputCohorts"] == 2
    assert "INPUT_SELECTION_OMITTED" in data["missingInputCodes"]
    assert data["requiredStatus"] == "LIMITED_DATA"


def test_cross_device_preparation_and_answer_substitution_are_refused(tmp_path):
    value = store(tmp_path)
    first = save(value)
    with pytest.raises(ValueError, match="daily_review"):
        value.prepare_daily_glucose_review(
            OWNER,
            OTHER,
            report_date=DAY,
            zone_id="UTC",
            source_digest=first.facts.comparison.sourceDigest,
            now_ms=NOW,
        )
    work = prepare(report(minutes=120))
    other = prepare(report(value=10.0, minutes=120))
    with pytest.raises(ValueError, match="daily_review"):
        validate(work, answer(other))


def test_identical_observations_on_different_device_bindings_cannot_share_answers():
    rows = [
        HistoryRecord.model_validate(
            record(
                eventAtMs=START + i * MINUTE,
                observedAtMs=NOW,
                quality="OK",
                context=CONTEXT,
                data={"glucoseMmol": 3.0},
            )
        )
        for i in range(0, 121, 15)
    ]
    options = dict(
        report_date=DAY,
        zone_id="UTC",
        ranges=RANGES,
        now_ms=NOW,
        snapshot_sequence=1000,
    )
    first = prepare(
        archive_review(build_daily_glucose(rows, source_binding=BINDING, **options))
    )
    second = prepare(
        archive_review(build_daily_glucose(rows, source_binding="ab" * 32, **options))
    )
    assert first.source_binding != second.source_binding
    assert first.facts_digest != second.facts_digest
    with pytest.raises(ValueError, match="daily_review"):
        validate(first, answer(second))


def test_read_only_preparation_and_no_implicit_chat_or_policy_registration(tmp_path):
    from app.ai.job_policy import CHAT_POLICY, MEAL_PHOTO_POLICY

    value = store(tmp_path)
    saved = save(value)
    file = next(value.root.glob("*.sqlite"))
    before = hashlib.sha256(file.read_bytes()).digest()
    value.prepare_daily_glucose_review(
        OWNER,
        KEY,
        report_date=DAY,
        zone_id="UTC",
        source_digest=saved.facts.comparison.sourceDigest,
        now_ms=NOW,
    )
    assert hashlib.sha256(file.read_bytes()).digest() == before
    assert CHAT_POLICY.kind == "CHAT" and MEAL_PHOTO_POLICY.kind == "MEAL_PHOTO"
    assert not hasattr(module(), "DAILY_REVIEW_POLICY")
    assert not hasattr(module(), "dispatch")


def test_reference_check_is_not_a_semantic_medical_text_approval():
    work = prepare(report(minutes=120))
    checked = validate(
        work, answer(work, summary="An unverified medical interpretation.")
    )
    assert checked.textMeaning == "UNREVIEWED_AI_INTERPRETATION"
    assert checked.publicationState == "NOT_ADMITTED"


@pytest.mark.parametrize("body", [b"", "not-bytes", None, b"null", b"{}"])
def test_missing_and_wrong_scalar_results_do_not_become_empty_success(body):
    work = prepare(report(minutes=120))
    with pytest.raises(ValueError, match="daily_review"):
        module().validate_review(
            work, body, now_ms=NOW, current_source_digest=work.source_digest
        )


def test_interpretation_has_the_same_plain_qualitative_boundary_as_summary():
    work = prepare(report(minutes=120))
    finding = json.loads(answer(work))["findings"][0]
    for text in ("Changed by 10 percent", "<b>interpretation</b>", "bad\ntext"):
        with pytest.raises(ValueError, match="daily_review"):
            validate(work, answer(work, findings=[finding | {"interpretation": text}]))


def test_expired_or_future_reply_is_not_reference_checked():
    work = prepare(report(minutes=120))
    for now in (NOW - 1, work.expires_ms):
        with pytest.raises(ValueError, match="daily_review"):
            validate(work, now_ms=now)


@pytest.mark.parametrize("language", ["unknown", "", None, True])
def test_language_is_a_closed_server_choice(language):
    with pytest.raises(ValueError, match="daily_review"):
        prepare(report(minutes=120), language=language)


def test_translation_changes_input_identity_not_source_or_prompt_policy():
    saved = report(minutes=120)
    russian, english = prepare(saved), prepare(saved, language="en-US")
    assert russian.input_digest != english.input_digest
    assert russian.facts_digest == english.facts_digest
    assert russian.source_binding == english.source_binding
    assert russian.prompt_digest == english.prompt_digest
    assert packet(english)["language"] == "en-US"


def test_free_text_does_not_upgrade_to_a_clinically_admitted_result():
    work = prepare(report(minutes=120))
    checked = validate(
        work, answer(work, summary="An unsupported clinical conclusion.")
    )
    assert checked.validation == "REFERENCES_ONLY"
    assert checked.publicationState == "NOT_ADMITTED"


def test_preparation_does_not_change_any_unrelated_domain_or_registered_route(tmp_path):
    value = store(tmp_path)
    saved = save(value)
    work = value.prepare_daily_glucose_review(
        OWNER,
        KEY,
        report_date=DAY,
        zone_id="UTC",
        source_digest=saved.facts.comparison.sourceDigest,
        now_ms=NOW,
    )
    document = packet(work)
    assert (
        not {
            "messages",
            "notes",
            "insulin",
            "meals",
            "targets",
            "profile",
            "credentials",
        }
        & document.keys()
    )
    assert "INSULIN_NOT_ANALYZED" in document["missingInputCodes"]
    assert "PHYSIOLOGY_NOT_ANALYZED" in document["missingInputCodes"]


def test_full_twenty_one_day_history_fits_without_hiding_evidence_projection():
    work = prepare(report(history_days=21))
    data = packet(work)
    assert len(work.input_body) <= 65_536
    for item in data["candidates"]:
        pattern = item["pattern"]
        assert pattern["qualifyingDays"] == pattern["eligibleWindows"] == 22
        assert len(pattern["windowEvidence"]) == 6
        assert pattern["omittedEvidenceWindows"] == 16
        assert pattern["windowEvidence"][-1]["reportDate"] == DAY
    assert data["omissions"]["selectedCandidateEvidenceWindows"] == 96
    assert data["requiredStatus"] == "LIMITED_DATA"


def test_compact_baselines_keep_original_metrics_and_exclusion_denominators():
    saved = report(value=6.0)
    work = prepare(saved)
    data = packet(work)["cohorts"][0]["comparison"]["baselines"][0]
    original = saved.facts.comparison.analyses[0].baselines[0]
    assert "days" not in data
    assert data["metrics"] == original.metrics.model_dump(mode="json")
    assert data["glucoseDays"] == original.glucoseDays
    assert data["rangeDays"] == original.rangeDays
    assert data["missingDates"] == list(original.missingDates)
    assert data["omittedDayDetailCount"] == len(original.days)
    assert sum(item["count"] for item in data["glucoseExclusions"]) == sum(
        day.glucoseReason is not None for day in original.days
    )
