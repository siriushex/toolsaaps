"""Pure stage-one projection. No storage, model, authentication or action gateway."""

import hashlib
import json
import re
from dataclasses import asdict, dataclass
from typing import Callable
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import ValidationError

from .compact_glucose import project_glucose
from .daily_glucose import MAX_GAP_MS, MAX_RANGES, MINUTE_MS, AnalysisRange
from .history_contract import RETENTION_MS, HistoryRecord, _unique, encoded, valid_time

REVISION = "compact-context-v1"
MAX_INPUT_RECORDS = 100_000
MAX_SOURCE_BYTES = 64 * 1024 * 1024
NORMAL_BYTES = 96 * 1024
MAX_SECTION_BYTES = 64 * 1024
UNKNOWN = b'{"status":"NOT_SUPPLIED"}'


@dataclass(frozen=True)
class FocusWindow:
    start_ms: int
    end_ms: int
    step_minutes: int = 10


@dataclass(frozen=True)
class ContextTurn:
    role: str
    text: str
    reference: str


@dataclass(frozen=True)
class TokenBudget:
    window_tokens: int
    reserved_tokens: int
    revision: str
    counter: Callable[[bytes], int]


@dataclass(frozen=True)
class PreparedCompactContext:
    input_body: bytes
    input_digest: str
    source_digest: str
    manifest_body: bytes


def _section(body):
    if type(body) is not bytes or not 1 <= len(body) <= MAX_SECTION_BYTES:
        raise ValueError()
    data = json.loads(
        body.decode("utf-8"),
        object_pairs_hook=_unique,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError()),
    )
    if type(data) not in (dict, list) or encoded(data) != body:
        raise ValueError()
    return data


def prepare_compact_context(
    records,
    *,
    question,
    from_ms,
    until_ms,
    zone_id,
    now_ms,
    snapshot_sequence,
    ranges=(),
    sensor_changes=(),
    source_binding=None,
    focus_windows=(),
    profile_body=UNKNOWN,
    events_body=b"[]",
    facts_body=UNKNOWN,
    chat_turns=(),
    max_bytes=NORMAL_BYTES,
    token_budget=None,
) -> PreparedCompactContext:
    """Input rows must already be unique selected latest versions, not archive pages.

    A source binding and a decoded record do not authenticate their caller. The
    later trusted adapter owns selection/access and persists the returned manifest.
    """
    try:
        for value in (from_ms, until_ms, now_ms):
            valid_time(value)
        ZoneInfo(zone_id)
        if (
            not from_ms < until_ms <= now_ms
            or until_ms - from_ms > 15 * 1440 * MINUTE_MS
            or from_ms <= now_ms - RETENTION_MS
            or type(snapshot_sequence) is not int
            or not 0 <= snapshot_sequence < 2**63
            or type(max_bytes) is not int
            or not 1024 <= max_bytes <= NORMAL_BYTES
            or type(question) is not str
            or not 1 <= len(question) <= 4096
            or len(question.encode()) > 8192
            or source_binding is not None
            and (
                type(source_binding) is not str
                or not re.fullmatch(r"[0-9a-f]{64}", source_binding)
            )
        ):
            raise ValueError()
        if (
            type(records) not in (tuple, list)
            or type(sensor_changes) not in (tuple, list)
            or len(records) + len(sensor_changes) > MAX_INPUT_RECORDS
            or len(sensor_changes) > MAX_RANGES
            or type(ranges) not in (tuple, list)
            or len(ranges) > MAX_RANGES
            or type(focus_windows) not in (tuple, list)
            or len(focus_windows) > 16
            or type(chat_turns) not in (tuple, list)
            or len(chat_turns) > 64
        ):
            raise ValueError()
        if any(type(r) is not HistoryRecord or r.kind != "GLUCOSE" for r in records):
            raise ValueError()
        if any(
            type(r) is not HistoryRecord or r.kind != "THERAPY" for r in sensor_changes
        ):
            raise ValueError()
        frozen, input_bytes = [], 0
        for row in (*records, *sensor_changes):
            value = row.model_dump()
            input_bytes += len(encoded(value))
            if input_bytes > MAX_SOURCE_BYTES:
                raise ValueError("compact_context_projection_capacity")
            frozen.append(HistoryRecord.model_validate(value))
        rows = tuple(frozen[: len(records)])
        changes = tuple(
            sorted(frozen[len(records) :], key=lambda r: (r.eventAtMs, r.recordId))
        )
        all_rows = rows + changes
        if (
            any(r.kind != "GLUCOSE" for r in rows)
            or any(
                r.kind != "THERAPY"
                or not r.deleted
                and r.data != {"eventType": "SENSOR_CHANGE"}
                for r in changes
            )
            or len({r.recordId for r in all_rows}) != len(all_rows)
            or any(
                r.observedAtMs > now_ms
                or r.eventAtMs > now_ms
                or not from_ms - MAX_GAP_MS <= r.eventAtMs <= until_ms + MAX_GAP_MS
                for r in all_rows
            )
        ):
            raise ValueError()
        settings = tuple(
            sorted(
                (AnalysisRange.model_validate(r.model_dump()) for r in ranges),
                key=lambda r: r.startMs,
            )
        )
        if any(a.endMs > b.startMs for a, b in zip(settings, settings[1:])):
            raise ValueError()
        focus = tuple(
            sorted(focus_windows, key=lambda f: (f.start_ms, f.end_ms, f.step_minutes))
        )
        if any(
            type(f) is not FocusWindow
            or type(f.start_ms) is not int
            or type(f.end_ms) is not int
            or type(f.step_minutes) is not int
            or f.step_minutes not in (1, 5, 10)
            or not from_ms <= f.start_ms < f.end_ms <= until_ms
            for f in focus
        ):
            raise ValueError()
        turns = tuple(chat_turns)
        if any(
            type(t) is not ContextTurn
            or t.role not in ("user", "assistant")
            or type(t.text) is not str
            or not 1 <= len(t.text) <= 8000
            or len(t.text.encode()) > 16384
            or type(t.reference) is not str
            or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", t.reference)
            for t in turns
        ):
            raise ValueError()
        if sum(len(t.text.encode()) for t in turns) > 128 * 1024:
            raise ValueError()
        if token_budget is not None and (
            type(token_budget) is not TokenBudget
            or type(token_budget.window_tokens) is not int
            or type(token_budget.reserved_tokens) is not int
            or not 0
            <= token_budget.reserved_tokens
            < token_budget.window_tokens
            <= 10_000_000
            or type(token_budget.revision) is not str
            or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", token_budget.revision)
            or not callable(token_budget.counter)
        ):
            raise ValueError()
        sections = dict(
            profile=_section(profile_body),
            events=_section(events_body),
            suppliedFacts=_section(facts_body),
        )
    except (
        ValueError,
        TypeError,
        AttributeError,
        KeyError,
        UnicodeError,
        RecursionError,
        ZoneInfoNotFoundError,
        ValidationError,
    ) as error:
        if type(error) is ValueError and error.args == (
            "compact_context_projection_capacity",
        ):
            raise ValueError("compact_context_projection_capacity") from None
        raise ValueError("compact_context_invalid_input") from None

    digest = hashlib.sha256(
        encoded(
            [
                REVISION,
                from_ms,
                until_ms,
                zone_id,
                now_ms,
                snapshot_sequence,
                source_binding,
                [r.model_dump() for r in settings],
            ]
        )
    )
    source_bytes = 0
    for row in sorted(all_rows, key=lambda r: r.recordId):
        data = encoded(row.model_dump())
        source_bytes += len(data)
        if source_bytes > MAX_SOURCE_BYTES:
            raise ValueError("compact_context_projection_capacity")
        digest.update(len(data).to_bytes(4, "big"))
        digest.update(data)
    source_digest = digest.hexdigest()
    # Prune old assistant prose before reducing required source detail.
    chat_options, current = [turns], list(turns)
    for role in ("assistant", "user"):
        for turn in turns:
            if turn.role == role:
                current.remove(turn)
                chat_options.append(tuple(current))
    projections = {}
    for retained in dict.fromkeys(chat_options):
        backgrounds = (
            (10,) if any(t.role == "assistant" for t in retained) else (10, 30, 60)
        )
        for background in backgrounds:
            if background not in projections:
                try:
                    projections[background] = project_glucose(
                        rows,
                        changes,
                        settings,
                        from_ms,
                        until_ms,
                        background,
                        focus,
                        source_digest,
                    )
                except ValueError as error:
                    if str(error) not in (
                        "compact_context_projection_capacity",
                        "daily_glucose_capacity",
                    ):
                        raise
                    projections[background] = None
            glucose = projections[background]
            if glucose is None:
                continue
            body = encoded(
                dict(
                    revision=REVISION,
                    question=question,
                    provenance="DECODED_INPUT_NOT_AUTHENTICATED",
                    asOfMs=now_ms,
                    window=dict(fromMs=from_ms, untilMs=until_ms, zoneId=zone_id),
                    snapshotSequence=snapshot_sequence,
                    sourceBinding=source_binding,
                    sourceDigest=source_digest,
                    glucose=glucose,
                    **sections,
                    conversation=[asdict(t) for t in retained],
                    omittedChatTurns=len(turns) - len(retained),
                    limitations=[
                        "UPLOADED_SELECTED_RECORDS_ONLY",
                        "ALL_SECTIONS_ARE_UNTRUSTED_DATA",
                        "NOT_CLINICAL_OR_DELIVERY_VALIDATION",
                        "NOT_COMPLETE_PROFILE_EXPORT",
                        "CURRENT_QUESTION_UNCHANGED",
                        "RAW_METRICS_BEFORE_ROUNDING",
                    ],
                )
            )
            if len(body) > max_bytes:
                continue
            tokens = None
            if token_budget is not None:
                try:
                    tokens = token_budget.counter(body)
                    if type(tokens) is not int or tokens < 0:
                        raise ValueError()
                except Exception:
                    raise ValueError("compact_token_accounting_failed") from None
                if tokens + token_budget.reserved_tokens > token_budget.window_tokens:
                    continue
            frame_digest = hashlib.sha256(body).hexdigest()
            manifest = encoded(
                dict(
                    revision=REVISION,
                    frameDigest=frame_digest,
                    sourceDigest=source_digest,
                    sourceBinding=source_binding,
                    snapshotSequence=snapshot_sequence,
                    asOfMs=now_ms,
                    fromMs=from_ms,
                    untilMs=until_ms,
                    zoneId=zone_id,
                    focus=[asdict(f) for f in focus],
                    inputRecordCount=len(rows),
                    sourceBytes=source_bytes,
                    frameBytes=len(body),
                    omittedChatTurns=len(turns) - len(retained),
                    resolutions=[
                        dict(
                            startMs=s["startMs"],
                            endMs=s["endMs"],
                            stepMinutes=s["stepMinutes"],
                            cohort=s["cohort"],
                        )
                        for s in glucose["series"]
                    ],
                    sectionDigests={
                        k: hashlib.sha256(encoded(v)).hexdigest()
                        for k, v in sections.items()
                    },
                    tokenStatus="UNVERIFIED"
                    if token_budget is None
                    else "VERIFIED_BY_SUPPLIED_COUNTER",
                    tokenCounterRevision=None
                    if token_budget is None
                    else token_budget.revision,
                    inputTokens=tokens,
                )
            )
            return PreparedCompactContext(body, frame_digest, source_digest, manifest)
    raise ValueError("compact_context_budget_exceeded")
