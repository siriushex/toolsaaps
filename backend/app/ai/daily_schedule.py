"""Trusted opt-in and one-shot local05:00 facts production, never inference.

No listener, recurring task or enrollment authority is installed here. A host
must check current owner/device authorization before calling these operations.
"""

from __future__ import annotations

import secrets
import shutil
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import Field, model_validator

from . import daily_glucose, daily_reports, daily_source, glucose_comparison
from .history_contract import Closed, HistoryError, Time, encoded, valid_time

MAX_SETTING_BYTES = 4096
MAX_GENERATION = 2**31 - 1
PURPOSE = "daily-facts-schedule-v1"
SCHEMA = (
    (
        "CREATE TABLE daily_facts_schedule(id INTEGER PRIMARY KEY CHECK(id=1),"
        "generation INTEGER NOT NULL,updated_ms INTEGER NOT NULL,"
        "nonce BLOB NOT NULL,ciphertext BLOB NOT NULL)"
    ),
)


class DailyFactsSchedule(Closed):
    revision: Literal["daily-facts-schedule-v1"] = PURPOSE
    meaning: Literal["FACTS_ONLY_NO_MODEL_ADMISSION"] = "FACTS_ONLY_NO_MODEL_ADMISSION"
    generation: Annotated[int, Field(ge=1, le=MAX_GENERATION)]
    enabled: bool
    zoneId: Annotated[str, Field(min_length=1, max_length=64)]
    localHour: Literal[5] = 5
    updatedAtMs: Time

    @model_validator(mode="after")
    def zone(self):
        daily_glucose.civil_day("2026-01-01", self.zoneId)
        return self


@dataclass(frozen=True)
class ScheduledDailyFacts:
    state: Literal["DISABLED", "NOT_DUE", "NO_DATA", "UNCHANGED", "FACTS_READY"]
    schedule: DailyFactsSchedule | None
    report: daily_reports.DailyFactsReport | None = None
    source_check: daily_source.DailySourceCheck | None = None


def due_report_date(zone_id, now_ms):
    valid_time(now_ms)
    # Validate even before the due hour; no host-zone fallback on bad input.
    daily_glucose.civil_day("2026-01-01", zone_id)
    try:
        local = datetime.fromtimestamp(now_ms // 1000, ZoneInfo(zone_id))
        day = (local.date() - timedelta(days=1)).isoformat()
        daily_glucose.civil_day(day, zone_id)
    except (ValueError, OverflowError, OSError):
        raise ValueError("invalid_daily_schedule_time") from None
    return day if local.hour >= 5 else None


def _load(store, owner, device, db, now_ms):
    if db is None or db.execute("PRAGMA user_version").fetchone()[0] < 6:
        return None
    rows = db.execute("SELECT * FROM daily_facts_schedule").fetchall()
    if len(rows) != 1:
        raise ValueError("invalid_daily_schedule_row")
    row = rows[0]
    if (
        row["id"] != 1
        or type(row["nonce"]) is not bytes
        or len(row["nonce"]) != 12
        or type(row["ciphertext"]) is not bytes
        or not 16 < len(row["ciphertext"]) <= MAX_SETTING_BYTES + 16
    ):
        raise ValueError("invalid_daily_schedule_ciphertext")
    raw = store._cipher(owner, device).decrypt(
        row["nonce"],
        row["ciphertext"],
        store._aad(owner, device, PURPOSE, [row["generation"], row["updated_ms"]]),
    )
    setting = DailyFactsSchedule.model_validate_json(raw)
    if (setting.generation, setting.updatedAtMs) != (
        row["generation"],
        row["updated_ms"],
    ):
        raise ValueError("invalid_daily_schedule_metadata")
    if now_ms < setting.updatedAtMs:
        raise HistoryError(409, "daily_schedule_clock_regression")
    return setting


def get_schedule(store, owner, device, *, now_ms):
    valid_time(now_ms)
    store._identity(owner, device)
    with store._locked():
        db = store._open(owner, device, create=False)
        try:
            return _load(store, owner, device, db, now_ms)
        finally:
            if db is not None:
                db.close()


def set_schedule(
    store, owner, device, *, enabled, zone_id, now_ms, expected_generation
):
    valid_time(now_ms)
    store._identity(owner, device)
    if (
        type(expected_generation) is not int
        or not 0 <= expected_generation < MAX_GENERATION
    ):
        raise ValueError("invalid_daily_schedule_generation")
    setting = DailyFactsSchedule(
        generation=expected_generation + 1,
        enabled=enabled,
        zoneId=zone_id,
        updatedAtMs=now_ms,
    )
    with store._locked():
        if shutil.disk_usage(store.root).free < store.min_free_bytes:
            raise HistoryError(503, "history_storage_unavailable")
        db = store._open(owner, device, create=True)
        try:
            db.execute("BEGIN IMMEDIATE")
            end = store._daily_budget(db)
            old = _load(store, owner, device, db, now_ms)
            if (old.generation if old else 0) != expected_generation:
                raise HistoryError(409, "daily_schedule_conflict")
            if old is not None and (old.enabled, old.zoneId) == (enabled, zone_id):
                daily_reports._deadline(end)
                db.commit()
                return old
            store._enable_daily_schedule(db)
            nonce = secrets.token_bytes(12)
            plain = encoded(setting.model_dump(mode="json"))
            if len(plain) > MAX_SETTING_BYTES:
                raise ValueError("invalid_daily_schedule_size")
            ciphertext = store._cipher(owner, device).encrypt(
                nonce,
                plain,
                store._aad(owner, device, PURPOSE, [setting.generation, now_ms]),
            )
            db.execute(
                "INSERT INTO daily_facts_schedule VALUES(1,?,?,?,?) "
                "ON CONFLICT(id) DO UPDATE SET generation=excluded.generation,"
                "updated_ms=excluded.updated_ms,nonce=excluded.nonce,ciphertext=excluded.ciphertext",
                (setting.generation, now_ms, nonce, ciphertext),
            )
            store._check_quota(db)
            daily_reports._deadline(end)
            db.commit()
            return setting
        finally:
            db.close()


def produce_facts(store, owner, device, *, ranges, now_ms):
    valid_time(now_ms)
    store._identity(owner, device)
    with store._locked():
        db = store._open(owner, device, create=False)
        try:
            if db is not None:
                db.execute("BEGIN IMMEDIATE")
            end = store._daily_budget(db)
            setting = _load(store, owner, device, db, now_ms)
            if setting is None or not setting.enabled:
                return ScheduledDailyFacts("DISABLED", setting)
            day = due_report_date(setting.zoneId, now_ms)
            if day is None:
                return ScheduledDailyFacts("NOT_DUE", setting)
            key = daily_reports._key(store, owner, device, day, setting.zoneId)
            old = daily_reports._read_report(
                store,
                owner,
                device,
                db,
                key=key,
                now_ms=now_ms,
                report_revision=None,
            )
            options = {
                "report_date": day, "zone_id": setting.zoneId, "now_ms": now_ms,
                "ranges": (old.facts.current.ranges if old else ()) if ranges is None else ranges,
                "source_binding": store._identity(owner, device),
            }
            empty = daily_glucose.build_daily_glucose([], snapshot_sequence=0, **options)
            options["ranges"] = empty.ranges
            snapshot = store._highwater(db)
            if old is not None and old.facts.current.ranges == empty.ranges:
                source = daily_source._check_selected_source(
                    store,
                    owner,
                    device,
                    db,
                    old,
                    now_ms=now_ms,
                    snapshot=snapshot,
                    stop_at=end,
                )
                if source.status == "UNAVAILABLE":
                    raise HistoryError(503, "daily_schedule_source_unavailable")
                if source.status == "MATCHED":
                    daily_reports._deadline(end)
                    return ScheduledDailyFacts("UNCHANGED", setting, old, source)
            windows = glucose_comparison.review_windows(
                day, setting.zoneId, now_ms=now_ms
            )
            rows, changes = store._read_glucose_records(
                owner,
                device,
                db,
                from_ms=max(1, windows[0].startMs - daily_glucose.MAX_GAP_MS),
                until_ms=min(now_ms, windows[-1].endMs + daily_glucose.MAX_GAP_MS),
                now_ms=now_ms,
                row_limit=glucose_comparison.MAX_REVIEW_INPUT_RECORDS,
                snapshot=snapshot,
                stop_at=end,
            )
            if old is None and not any(
                empty.window.startMs <= r.eventAtMs < empty.window.endMs for r in rows
            ):
                daily_reports._deadline(end)
                return ScheduledDailyFacts("NO_DATA", setting)
            facts = glucose_comparison.build_glucose_review(
                rows,
                sensor_changes=changes,
                snapshot_sequence=snapshot,
                **options,
            )
            if shutil.disk_usage(store.root).free < store.min_free_bytes:
                raise HistoryError(503, "history_storage_unavailable")
            report = daily_reports._save_report_in_transaction(
                store,
                owner,
                device,
                db,
                facts,
                now_ms=now_ms,
                end=end,
            )
            daily_reports._deadline(end)
            db.commit()
            return ScheduledDailyFacts("FACTS_READY", setting, report)
        finally:
            if db is not None:
                db.close()
