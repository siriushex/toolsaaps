from __future__ import annotations

from sqlalchemy import Float, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class GlucosePointOrm(Base):
    __tablename__ = "glucose_points"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ts: Mapped[int] = mapped_column(Integer, index=True)
    value_mmol: Mapped[float] = mapped_column(Float)
    source: Mapped[str] = mapped_column(String(64), default="unknown")
    quality: Mapped[str] = mapped_column(String(24), default="OK")


class TherapyEventOrm(Base):
    __tablename__ = "therapy_events"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    ts: Mapped[int] = mapped_column(Integer, index=True)
    event_type: Mapped[str] = mapped_column(String(64), index=True)
    payload_json: Mapped[str] = mapped_column(Text)


class ActionOrm(Base):
    __tablename__ = "actions"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    status: Mapped[str] = mapped_column(String(32), index=True)
    message: Mapped[str] = mapped_column(String(512))
    idempotency_key: Mapped[str] = mapped_column(String(256), unique=True, index=True)
    created_ts: Mapped[int] = mapped_column(Integer, index=True)


class ModelRegistryOrm(Base):
    __tablename__ = "model_registry"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    horizon: Mapped[int] = mapped_column(Integer, index=True)
    model_version: Mapped[str] = mapped_column(String(128), index=True)
    mae: Mapped[float] = mapped_column(Float)
    updated_at: Mapped[int] = mapped_column(Integer, index=True)


class KvOrm(Base):
    __tablename__ = "kv_store"

    key: Mapped[str] = mapped_column(String(128), primary_key=True)
    value: Mapped[str] = mapped_column(Text)


class AnalysisReportOrm(Base):
    __tablename__ = "analysis_reports"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_ts: Mapped[int] = mapped_column(Integer, index=True)
    report_date: Mapped[str] = mapped_column(String(32), index=True)
    locale: Mapped[str] = mapped_column(String(32))
    source: Mapped[str] = mapped_column(String(32), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True)
    summary: Mapped[str] = mapped_column(Text)
    anomalies_json: Mapped[str] = mapped_column(Text)
    recommendations_json: Mapped[str] = mapped_column(Text)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)


Index("ix_model_registry_horizon_version", ModelRegistryOrm.horizon, ModelRegistryOrm.model_version, unique=True)
