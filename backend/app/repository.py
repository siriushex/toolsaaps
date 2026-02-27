from __future__ import annotations

import json
import time
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Dict, List, Optional

from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from .db import Base, DbRuntime
from .models import ActionRecord, GlucosePoint, ModelInfo, TherapyEvent
from .orm import AnalysisReportOrm, ActionOrm, GlucosePointOrm, KvOrm, ModelRegistryOrm, TherapyEventOrm


@dataclass
class PullResult:
    glucose: List[GlucosePoint]
    therapy_events: List[TherapyEvent]
    next_since: int


@dataclass
class PushResult:
    accepted_glucose: int
    accepted_therapy_events: int
    next_since: int


@dataclass
class JobStatusSnapshot:
    job_id: str
    last_run_ts: Optional[int]
    last_success_ts: Optional[int]
    last_status: Optional[str]
    last_message: Optional[str]


@dataclass
class AnalysisHistoryRecord:
    run_ts: int
    report_date: str
    locale: str
    source: str
    status: str
    summary: str
    anomalies: List[str]
    recommendations: List[str]
    error_message: Optional[str]


@dataclass
class AnalysisWeeklyTrendRecord:
    week_start: str
    total_runs: int
    success_runs: int
    failed_runs: int
    manual_runs: int
    scheduler_runs: int
    anomalies_count: int
    recommendations_count: int


class CopilotRepository:
    def __init__(self, db_url: str | None = None):
        self.runtime = DbRuntime(db_url)

    def init(self) -> None:
        Base.metadata.create_all(self.runtime.engine)

    def _session(self) -> Session:
        return self.runtime.session_local()

    def ensure_seed_data(self) -> None:
        with self._session() as db:
            seeded = db.get(KvOrm, "seeded")
            if seeded and seeded.value == "1":
                return

            now = int(time.time() * 1000)
            rows: List[GlucosePointOrm] = []
            for i in range(72):
                rows.append(
                    GlucosePointOrm(
                        ts=now - (71 - i) * 5 * 60 * 1000,
                        value_mmol=5.8 + (i % 6 - 3) * 0.1,
                        source="seed",
                        quality="OK",
                    )
                )
            db.add_all(rows)

            db.merge(
                TherapyEventOrm(
                    id="seed-temp-target",
                    ts=now - 30 * 60 * 1000,
                    event_type="temp_target",
                    payload_json=json.dumps({"targetBottom": "5.5", "duration": "60"}),
                )
            )

            db.merge(
                ModelRegistryOrm(
                    horizon=5,
                    model_version="cloud-hf-v1",
                    mae=0.42,
                    updated_at=now,
                )
            )
            db.merge(
                ModelRegistryOrm(
                    horizon=60,
                    model_version="cloud-ensemble-v1",
                    mae=1.08,
                    updated_at=now,
                )
            )

            db.merge(KvOrm(key="seeded", value="1"))
            db.commit()

    def pull_since(self, since: int) -> PullResult:
        with self._session() as db:
            glucose_rows = db.scalars(select(GlucosePointOrm).where(GlucosePointOrm.ts >= since).order_by(GlucosePointOrm.ts.asc())).all()
            therapy_rows = db.scalars(select(TherapyEventOrm).where(TherapyEventOrm.ts >= since).order_by(TherapyEventOrm.ts.asc())).all()

            glucose = [
                GlucosePoint(ts=row.ts, valueMmol=row.value_mmol, source=row.source, quality=row.quality)
                for row in glucose_rows
            ]
            therapy = [
                TherapyEvent(id=row.id, ts=row.ts, type=row.event_type, payload=json.loads(row.payload_json or "{}"))
                for row in therapy_rows
            ]

            next_since = since
            if glucose:
                next_since = max(next_since, glucose[-1].ts)
            if therapy:
                next_since = max(next_since, therapy[-1].ts)

            return PullResult(glucose=glucose, therapy_events=therapy, next_since=next_since)

    def push_sync_payload(self, glucose: List[GlucosePoint], therapy_events: List[TherapyEvent]) -> PushResult:
        with self._session() as db:
            dedup_glucose: Dict[tuple[int, str], GlucosePoint] = {(point.ts, point.source): point for point in glucose}
            dedup_therapy: Dict[str, TherapyEvent] = {event.id: event for event in therapy_events}

            accepted_glucose = 0
            accepted_therapy = 0

            if dedup_glucose:
                ts_values = {key[0] for key in dedup_glucose.keys()}
                existing_rows = db.scalars(select(GlucosePointOrm).where(GlucosePointOrm.ts.in_(ts_values))).all()
                existing_by_key = {(row.ts, row.source): row for row in existing_rows}

                for key, point in dedup_glucose.items():
                    existing = existing_by_key.get(key)
                    if existing is None:
                        db.add(
                            GlucosePointOrm(
                                ts=point.ts,
                                value_mmol=point.valueMmol,
                                source=point.source,
                                quality=point.quality,
                            )
                        )
                    else:
                        existing.value_mmol = point.valueMmol
                        existing.quality = point.quality
                    accepted_glucose += 1

            if dedup_therapy:
                event_ids = list(dedup_therapy.keys())
                existing_rows = db.scalars(select(TherapyEventOrm).where(TherapyEventOrm.id.in_(event_ids))).all()
                existing_by_id = {row.id: row for row in existing_rows}

                for event_id, event in dedup_therapy.items():
                    payload_json = json.dumps(event.payload)
                    existing = existing_by_id.get(event_id)
                    if existing is None:
                        db.add(
                            TherapyEventOrm(
                                id=event.id,
                                ts=event.ts,
                                event_type=event.type,
                                payload_json=payload_json,
                            )
                        )
                    else:
                        existing.ts = event.ts
                        existing.event_type = event.type
                        existing.payload_json = payload_json
                    accepted_therapy += 1

            db.commit()

            next_since = 0
            if dedup_glucose:
                next_since = max(next_since, max(point.ts for point in dedup_glucose.values()))
            if dedup_therapy:
                next_since = max(next_since, max(event.ts for event in dedup_therapy.values()))

            return PushResult(
                accepted_glucose=accepted_glucose,
                accepted_therapy_events=accepted_therapy,
                next_since=next_since,
            )

    def list_glucose(self, since: int | None = None, until: int | None = None) -> List[GlucosePoint]:
        with self._session() as db:
            query = select(GlucosePointOrm)
            conditions = []
            if since is not None:
                conditions.append(GlucosePointOrm.ts >= since)
            if until is not None:
                conditions.append(GlucosePointOrm.ts <= until)
            if conditions:
                query = query.where(and_(*conditions))
            rows = db.scalars(query.order_by(GlucosePointOrm.ts.asc())).all()
            return [GlucosePoint(ts=row.ts, valueMmol=row.value_mmol, source=row.source, quality=row.quality) for row in rows]

    def list_therapy(self, since: int | None = None, until: int | None = None) -> List[TherapyEvent]:
        with self._session() as db:
            query = select(TherapyEventOrm)
            conditions = []
            if since is not None:
                conditions.append(TherapyEventOrm.ts >= since)
            if until is not None:
                conditions.append(TherapyEventOrm.ts <= until)
            if conditions:
                query = query.where(and_(*conditions))
            rows = db.scalars(query.order_by(TherapyEventOrm.ts.asc())).all()
            return [
                TherapyEvent(id=row.id, ts=row.ts, type=row.event_type, payload=json.loads(row.payload_json or "{}"))
                for row in rows
            ]

    def upsert_temp_target_action(self, action_id: str, idempotency_key: str, target_mmol: float, duration_minutes: int) -> ActionRecord:
        with self._session() as db:
            existing = db.scalar(select(ActionOrm).where(ActionOrm.idempotency_key == idempotency_key))
            if existing is not None:
                return ActionRecord(
                    id=existing.id,
                    status=existing.status,
                    message=existing.message,
                    idempotency_key=existing.idempotency_key,
                )

            message = f"temp target {target_mmol} mmol/L for {duration_minutes}m"
            now = int(time.time() * 1000)
            action = ActionOrm(
                id=action_id,
                status="accepted",
                message=message,
                idempotency_key=idempotency_key,
                created_ts=now,
            )
            db.add(action)

            therapy_event = TherapyEventOrm(
                id=f"action-{action_id}",
                ts=now,
                event_type="temp_target",
                payload_json=json.dumps(
                    {
                        "targetBottom": str(target_mmol),
                        "targetTop": str(target_mmol),
                        "duration": str(duration_minutes),
                        "reason": "copilot_auto",
                    }
                ),
            )
            db.merge(therapy_event)
            db.commit()

            return ActionRecord(
                id=action.id,
                status=action.status,
                message=action.message,
                idempotency_key=action.idempotency_key,
            )

    def get_action(self, action_id: str) -> ActionRecord | None:
        with self._session() as db:
            row = db.get(ActionOrm, action_id)
            if row is None:
                return None
            return ActionRecord(id=row.id, status=row.status, message=row.message, idempotency_key=row.idempotency_key)

    def list_active_models(self) -> List[ModelInfo]:
        with self._session() as db:
            rows = db.scalars(select(ModelRegistryOrm).order_by(ModelRegistryOrm.horizon.asc(), ModelRegistryOrm.updated_at.desc())).all()
            return [
                ModelInfo(
                    horizon=row.horizon,
                    modelVersion=row.model_version,
                    mae=row.mae,
                    updatedAt=row.updated_at,
                )
                for row in rows
            ]

    def touch_weekly_retrain(self) -> None:
        now = int(time.time() * 1000)
        with self._session() as db:
            rows = db.scalars(select(ModelRegistryOrm)).all()
            for row in rows:
                row.updated_at = now
            db.commit()

    def counts(self) -> tuple[int, int]:
        with self._session() as db:
            glucose_count = len(db.scalars(select(GlucosePointOrm.id)).all())
            therapy_count = len(db.scalars(select(TherapyEventOrm.id)).all())
            return glucose_count, therapy_count

    def record_job_status(self, job_id: str, status: str, message: str) -> None:
        now = int(time.time() * 1000)
        trimmed = message.strip()[:512]
        with self._session() as db:
            self._set_kv(db, self._job_key(job_id, "last_run_ts"), str(now))
            self._set_kv(db, self._job_key(job_id, "last_status"), status)
            self._set_kv(db, self._job_key(job_id, "last_message"), trimmed)
            if status.upper() == "SUCCESS":
                self._set_kv(db, self._job_key(job_id, "last_success_ts"), str(now))
            db.commit()

    def get_job_status(self, job_id: str) -> JobStatusSnapshot:
        with self._session() as db:
            return JobStatusSnapshot(
                job_id=job_id,
                last_run_ts=self._get_kv_int(db, self._job_key(job_id, "last_run_ts")),
                last_success_ts=self._get_kv_int(db, self._job_key(job_id, "last_success_ts")),
                last_status=self._get_kv_text(db, self._job_key(job_id, "last_status")),
                last_message=self._get_kv_text(db, self._job_key(job_id, "last_message")),
            )

    def _job_key(self, job_id: str, field: str) -> str:
        return f"job.{job_id}.{field}"

    def _set_kv(self, db: Session, key: str, value: str) -> None:
        db.merge(KvOrm(key=key, value=value))

    def _get_kv_text(self, db: Session, key: str) -> Optional[str]:
        row = db.get(KvOrm, key)
        if row is None:
            return None
        return row.value

    def _get_kv_int(self, db: Session, key: str) -> Optional[int]:
        raw = self._get_kv_text(db, key)
        if raw is None:
            return None
        try:
            return int(raw)
        except ValueError:
            return None

    def add_analysis_report(
        self,
        report_date: str,
        locale: str,
        source: str,
        status: str,
        summary: str,
        anomalies: List[str],
        recommendations: List[str],
        error_message: Optional[str] = None,
    ) -> None:
        now = int(time.time() * 1000)
        with self._session() as db:
            db.add(
                AnalysisReportOrm(
                    run_ts=now,
                    report_date=report_date,
                    locale=locale,
                    source=source,
                    status=status,
                    summary=summary.strip()[:4000],
                    anomalies_json=json.dumps(anomalies),
                    recommendations_json=json.dumps(recommendations),
                    error_message=error_message.strip()[:512] if error_message else None,
                )
            )
            db.commit()

    def list_analysis_reports(
        self,
        limit: int,
        source: Optional[str] = None,
        status: Optional[str] = None,
        since_ts: Optional[int] = None,
    ) -> List[AnalysisHistoryRecord]:
        safe_limit = max(1, min(limit, 365))
        with self._session() as db:
            query = select(AnalysisReportOrm)
            conditions = []
            if source:
                conditions.append(AnalysisReportOrm.source == source)
            if status:
                conditions.append(AnalysisReportOrm.status == status)
            if since_ts:
                conditions.append(AnalysisReportOrm.run_ts >= since_ts)
            if conditions:
                query = query.where(and_(*conditions))

            rows = db.scalars(query.order_by(AnalysisReportOrm.run_ts.desc(), AnalysisReportOrm.id.desc()).limit(safe_limit)).all()
            return [
                AnalysisHistoryRecord(
                    run_ts=row.run_ts,
                    report_date=row.report_date,
                    locale=row.locale,
                    source=row.source,
                    status=row.status,
                    summary=row.summary,
                    anomalies=json.loads(row.anomalies_json or "[]"),
                    recommendations=json.loads(row.recommendations_json or "[]"),
                    error_message=row.error_message,
                )
                for row in rows
            ]

    def weekly_analysis_trend(
        self,
        weeks: int,
        source: Optional[str] = None,
        status: Optional[str] = None,
    ) -> List[AnalysisWeeklyTrendRecord]:
        safe_weeks = max(1, min(weeks, 52))
        since_ts = int((time.time() - safe_weeks * 7 * 24 * 60 * 60) * 1000)
        reports = self.list_analysis_reports(limit=safe_weeks * 32, source=source, status=status, since_ts=since_ts)

        buckets: Dict[str, Dict[str, int]] = {}
        for row in reports:
            report_day = date.fromisoformat(row.report_date)
            week_start = report_day - timedelta(days=report_day.weekday())
            key = week_start.isoformat()
            bucket = buckets.setdefault(
                key,
                {
                    "total_runs": 0,
                    "success_runs": 0,
                    "failed_runs": 0,
                    "manual_runs": 0,
                    "scheduler_runs": 0,
                    "anomalies_count": 0,
                    "recommendations_count": 0,
                },
            )
            bucket["total_runs"] += 1
            if row.status == "SUCCESS":
                bucket["success_runs"] += 1
            if row.status == "FAILED":
                bucket["failed_runs"] += 1
            if row.source == "manual":
                bucket["manual_runs"] += 1
            if row.source == "scheduler":
                bucket["scheduler_runs"] += 1
            bucket["anomalies_count"] += len(row.anomalies)
            bucket["recommendations_count"] += len(row.recommendations)

        keys_desc = sorted(buckets.keys(), reverse=True)[:safe_weeks]
        keys = list(reversed(keys_desc))
        return [
            AnalysisWeeklyTrendRecord(
                week_start=key,
                total_runs=buckets[key]["total_runs"],
                success_runs=buckets[key]["success_runs"],
                failed_runs=buckets[key]["failed_runs"],
                manual_runs=buckets[key]["manual_runs"],
                scheduler_runs=buckets[key]["scheduler_runs"],
                anomalies_count=buckets[key]["anomalies_count"],
                recommendations_count=buckets[key]["recommendations_count"],
            )
            for key in keys
        ]
