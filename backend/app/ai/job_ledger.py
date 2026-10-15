"""Metadata-only pilot ledger. Authentication and process control are separate.

SQLite BEGIN IMMEDIATE serializes quota checks, reservations and claims across
processes. No prompt, image, result, credentials or free-form errors are stored.
RUNNING/UNKNOWN are never automatically retried after a process/server failure.
"""
from __future__ import annotations

import re
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID, uuid4

from sqlalchemy import (Column, Index, Integer, MetaData, String, Table,
                        UniqueConstraint, create_engine, func, inspect, select, update)
from sqlalchemy.engine import URL


class LedgerError(Exception):
    pass


KINDS = frozenset({"CHAT", "MEAL_PHOTO", "CLINICAL_REPORT", "ALERT_EXPLANATION",
                   "DAILY_REVIEW", "FORECAST_REVIEW"})
ACTIVE = ("QUEUED", "RUNNING", "CANCEL_REQUESTED")
EXECUTING = ("RUNNING", "CANCEL_REQUESTED")
metadata = MetaData()
jobs = Table(
    "ai_jobs", metadata,
    Column("id", String(36), primary_key=True),
    Column("owner_id", String(36), nullable=False),
    Column("session_id", String(36)),
    Column("key_hash", String(64)),
    Column("request_id", String(36), nullable=False),
    Column("kind", String(32), nullable=False),
    Column("digest", String(64), nullable=False),
    Column("route_revision", String(64), nullable=False),
    Column("state", String(24), nullable=False),
    Column("created_ms", Integer, nullable=False),
    Column("deadline_ms", Integer, nullable=False),
    Column("started_ms", Integer),
    Column("finished_ms", Integer),
    Column("claim_token", String(36)),
    UniqueConstraint("owner_id", "kind", "request_id"),
    Index("ai_jobs_state", "state"),
    Index("ai_jobs_owner_state", "owner_id", "state"),
)


@dataclass(frozen=True)
class Job:
    id: str
    owner_id: str
    session_id: str | None
    key_hash: str | None
    request_id: str
    kind: str
    digest: str
    route_revision: str
    state: str
    created_ms: int
    deadline_ms: int
    started_ms: int | None
    finished_ms: int | None


@dataclass(frozen=True)
class Claim:
    job: Job
    token: str


def _uuid(value):
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError("canonical_uuid_required")


def _time(value):
    if type(value) is not int or not 0 < value < 2**63:
        raise ValueError("invalid_timestamp")


def _job(row):
    return Job(**{key: row[key] for key in Job.__dataclass_fields__}) if row else None


class JobLedger:
    def __init__(self, path: Path, *, max_waiting: int = 5):
        if type(max_waiting) is not int or not 1 <= max_waiting <= 100:
            raise ValueError("invalid_queue_limit")
        self.max_waiting = max_waiting
        self.engine = create_engine(URL.create("sqlite", database=str(path.resolve())),
                                    connect_args={"timeout": 10, "check_same_thread": False})
        if set(inspect(self.engine).get_table_names()) - {"ai_jobs"}:
            self.engine.dispose()
            raise ValueError("dedicated_database_required")
        metadata.create_all(self.engine)
        columns = {column["name"] for column in inspect(self.engine).get_columns("ai_jobs")}
        with self.engine.begin() as db:
            if "session_id" not in columns:
                db.exec_driver_sql("ALTER TABLE ai_jobs ADD COLUMN session_id VARCHAR(36)")
            if "key_hash" not in columns:
                db.exec_driver_sql("ALTER TABLE ai_jobs ADD COLUMN key_hash VARCHAR(64)")

    @contextmanager
    def _write(self):
        with self.engine.connect() as db:
            db.exec_driver_sql("BEGIN IMMEDIATE")
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise

    @staticmethod
    def _find(db, owner_id, job_id, *, session_id=None, key_hash=None):
        conditions = [jobs.c.owner_id == owner_id, jobs.c.id == job_id]
        if session_id is not None or key_hash is not None:
            conditions.extend((jobs.c.session_id == session_id, jobs.c.key_hash == key_hash))
        return db.execute(select(jobs).where(*conditions)).mappings().first()

    @staticmethod
    def _expire(db, now_ms):
        return db.execute(update(jobs).where(
            jobs.c.state == "QUEUED", jobs.c.deadline_ms <= now_ms
        ).values(state="EXPIRED", finished_ms=now_ms)).rowcount

    def reserve(self, *, owner_id: str, request_id: str, kind: str, digest: str,
                route_revision: str, deadline_ms: int, now_ms: int,
                max_deadline_ms: int = 900_000) -> Job:
        return self.reserve_once(owner_id=owner_id, request_id=request_id, kind=kind,
            digest=digest, route_revision=route_revision, deadline_ms=deadline_ms,
            now_ms=now_ms, max_deadline_ms=max_deadline_ms)[0]

    def reserve_once(self, *, owner_id: str, request_id: str, kind: str, digest: str,
                     route_revision: str, deadline_ms: int, now_ms: int,
                     session_id: str | None = None,
                     key_hash: str | None = None,
                     max_deadline_ms: int = 900_000) -> tuple[Job, bool]:
        _uuid(owner_id)
        _uuid(request_id)
        _time(now_ms)
        _time(deadline_ms)
        if (session_id is None) != (key_hash is None):
            raise ValueError("invalid_request_binding")
        if session_id is not None:
            _uuid(session_id)
            if not isinstance(key_hash, str) or not re.fullmatch(r"[a-f0-9]{64}", key_hash):
                raise ValueError("invalid_request_binding")
        if (not isinstance(kind, str) or kind not in KINDS
                or not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest)):
            raise ValueError("invalid_request_metadata")
        if type(max_deadline_ms) is not int or not 0 < max_deadline_ms <= 900_000:
            raise ValueError("invalid_deadline")
        if (not isinstance(route_revision, str)
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", route_revision)):
            raise ValueError("invalid_route_revision")
        with self._write() as db:
            self._expire(db, now_ms)
            existing = db.execute(select(jobs).where(jobs.c.owner_id == owner_id,
                jobs.c.kind == kind, jobs.c.request_id == request_id)).mappings().first()
            if existing:
                if any(existing[key] != value for key, value in
                       (("digest", digest), ("route_revision", route_revision),
                        ("deadline_ms", deadline_ms), ("session_id", session_id),
                        ("key_hash", key_hash))):
                    raise LedgerError("request_conflict")
                return _job(existing), False
            if not now_ms < deadline_ms <= now_ms + max_deadline_ms:
                raise ValueError("invalid_deadline")
            if db.scalar(select(func.count()).select_from(jobs).where(
                    jobs.c.owner_id == owner_id, jobs.c.state.in_(ACTIVE))):
                raise LedgerError("owner_busy")
            if db.scalar(select(func.count()).select_from(jobs).where(jobs.c.state == "QUEUED")) >= self.max_waiting:
                raise LedgerError("queue_full")
            job_id = str(uuid4())
            db.execute(jobs.insert().values(id=job_id, owner_id=owner_id, request_id=request_id,
                session_id=session_id, key_hash=key_hash, kind=kind, digest=digest,
                route_revision=route_revision, deadline_ms=deadline_ms,
                created_ms=now_ms, state="QUEUED"))
            return _job(self._find(db, owner_id, job_id)), True

    def abandon_queued(self, *, now_ms: int) -> int:
        """Mark payload-less jobs unavailable when a volatile runtime starts."""
        _time(now_ms)
        with self._write() as db:
            result = db.execute(update(jobs).where(jobs.c.state == "QUEUED").values(
                state="UNKNOWN", finished_ms=now_ms))
            return result.rowcount

    def expire_queued(self, *, now_ms: int) -> int:
        _time(now_ms)
        with self._write() as db:
            return self._expire(db, now_ms)

    def get(self, owner_id: str, job_id: str, *, session_id: str | None = None,
            key_hash: str | None = None) -> Job | None:
        _uuid(owner_id)
        _uuid(job_id)
        if (session_id is None) != (key_hash is None):
            raise ValueError("invalid_request_binding")
        with self.engine.connect() as db:
            return _job(self._find(db, owner_id, job_id, session_id=session_id,
                                   key_hash=key_hash))

    def claim(self, owner_id: str, job_id: str, *, now_ms: int) -> Claim | None:
        _uuid(owner_id)
        _uuid(job_id)
        _time(now_ms)
        with self._write() as db:
            self._expire(db, now_ms)
            row = self._find(db, owner_id, job_id)
            if not row or row["state"] != "QUEUED":
                return None
            if now_ms < row["created_ms"]:
                raise ValueError("clock_regression")
            if db.scalar(select(func.count()).select_from(jobs).where(jobs.c.state.in_(EXECUTING))):
                return None
            token = str(uuid4())
            db.execute(update(jobs).where(jobs.c.id == job_id).values(
                state="RUNNING", started_ms=now_ms, claim_token=token))
            return Claim(_job(self._find(db, owner_id, job_id)), token)

    def cancel(self, owner_id: str, job_id: str, *, now_ms: int,
               session_id: str | None = None, key_hash: str | None = None) -> Job | None:
        _uuid(owner_id)
        _uuid(job_id)
        _time(now_ms)
        if (session_id is None) != (key_hash is None):
            raise ValueError("invalid_request_binding")
        with self._write() as db:
            row = self._find(db, owner_id, job_id, session_id=session_id,
                             key_hash=key_hash)
            if not row:
                return None
            if now_ms < (row["started_ms"] or row["created_ms"]):
                raise ValueError("clock_regression")
            if row["state"] == "QUEUED":
                db.execute(update(jobs).where(jobs.c.id == job_id).values(
                    state="CANCELLED", finished_ms=now_ms))
            elif row["state"] == "RUNNING":
                db.execute(update(jobs).where(jobs.c.id == job_id).values(state="CANCEL_REQUESTED"))
            return _job(self._find(db, owner_id, job_id))

    def finish(self, owner_id: str, job_id: str, token: str, state: str, *, now_ms: int) -> Job:
        return self._finish(owner_id, job_id, token, state, now_ms=now_ms, cancel_wins=False)

    def settle_stopped(self, owner_id: str, job_id: str, token: str, state: str, *, now_ms: int) -> Job:
        """Only after OS stop confirmation; cancellation wins atomically."""
        if state not in {"SUCCEEDED", "FAILED", "UNKNOWN"}:
            raise LedgerError("invalid_transition")
        return self._finish(owner_id, job_id, token, state, now_ms=now_ms, cancel_wins=True)

    def _finish(self, owner_id, job_id, token, state, *, now_ms, cancel_wins):
        _uuid(owner_id)
        _uuid(job_id)
        _uuid(token)
        _time(now_ms)
        with self._write() as db:
            row = self._find(db, owner_id, job_id)
            if not row or row["claim_token"] != token or row["state"] not in EXECUTING:
                raise LedgerError("invalid_claim")
            if cancel_wins and row["state"] == "CANCEL_REQUESTED":
                state = "CANCELLED"
            allowed = {"CANCELLED", "UNKNOWN"} if row["state"] == "CANCEL_REQUESTED" else {"SUCCEEDED", "FAILED", "UNKNOWN"}
            if state not in allowed:
                raise LedgerError("invalid_transition")
            if now_ms < row["started_ms"]:
                raise ValueError("clock_regression")
            if state == "SUCCEEDED" and now_ms >= row["deadline_ms"]:
                state = "EXPIRED"
            db.execute(update(jobs).where(jobs.c.id == job_id).values(
                state=state, finished_ms=now_ms, claim_token=None))
            return _job(self._find(db, owner_id, job_id))
