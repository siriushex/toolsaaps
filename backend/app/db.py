from __future__ import annotations

import os
from typing import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.pool import StaticPool


class Base(DeclarativeBase):
    pass


def _engine_kwargs(db_url: str) -> dict:
    if db_url == "sqlite:///:memory:":
        return {
            "connect_args": {"check_same_thread": False},
            "poolclass": StaticPool,
        }
    if db_url.startswith("sqlite"):
        return {"connect_args": {"check_same_thread": False}}
    return {}


def default_db_url() -> str:
    return os.getenv("COPILOT_DB_URL", "sqlite:///./copilot.db")


class DbRuntime:
    def __init__(self, db_url: str | None = None):
        self.db_url = db_url or default_db_url()
        self.engine = create_engine(self.db_url, future=True, **_engine_kwargs(self.db_url))
        self.session_local = sessionmaker(bind=self.engine, autoflush=False, autocommit=False, future=True)

    def session(self) -> Generator[Session, None, None]:
        with self.session_local() as db:
            yield db
