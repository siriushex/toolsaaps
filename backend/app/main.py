from __future__ import annotations

import os
import time
from contextlib import asynccontextmanager
from datetime import date
from typing import Callable

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI, HTTPException

from .models import (
    ActionResponse,
    ActiveModelsResponse,
    AnalysisHistoryItem,
    AnalysisWeeklyTrendItem,
    AnalysisWeeklyTrendResponse,
    DailyAnalysisHistoryResponse,
    DailyAnalysisRequest,
    DailyAnalysisResponse,
    EvaluateRulesRequest,
    EvaluateRulesResponse,
    JobsStatusResponse,
    JobStatusOut,
    PredictRequest,
    PredictResponse,
    ReplayReportRequest,
    ReplayReportResponse,
    SyncPullResponse,
    SyncPushRequest,
    SyncPushResponse,
    TempTargetRequest,
)
from .repository import CopilotRepository
from .services.analysis_service import run_daily_insight
from .services.predict_service import predict
from .services.replay_service import build_replay_report
from .services.rule_service import evaluate_post_hypo_rebound


def create_app(repository: CopilotRepository | None = None) -> FastAPI:
    repo = repository or CopilotRepository()
    scheduler = BackgroundScheduler(timezone=os.getenv("COPILOT_TIMEZONE", "UTC"))
    tracked_jobs = ("daily-analysis", "weekly-retrain")

    def _run_scheduled_job(job_id: str, work: Callable[[], str]) -> None:
        try:
            message = str(work())
            repo.record_job_status(job_id=job_id, status="SUCCESS", message=message or "ok")
        except Exception as exc:  # pragma: no cover - defensive safety around scheduler thread
            repo.record_job_status(job_id=job_id, status="FAILED", message=str(exc) or "unknown_error")

    def _run_daily_analysis(report_date: str, locale: str, source: str) -> tuple[str, list[str], list[str]]:
        glucose_count, therapy_count = repo.counts()
        summary, anomalies, recommendations = run_daily_insight(
            date=report_date,
            locale=locale,
            context_hint=f"source={source}, glucose_points={glucose_count}, therapy_events={therapy_count}",
        )
        repo.add_analysis_report(
            report_date=report_date,
            locale=locale,
            source=source,
            status="SUCCESS",
            summary=summary,
            anomalies=anomalies,
            recommendations=recommendations,
        )
        return summary, anomalies, recommendations

    def _daily_job() -> None:
        def _work() -> str:
            report_date = date.today().isoformat()
            try:
                _, anomalies, recommendations = _run_daily_analysis(
                    report_date=report_date,
                    locale="en-US",
                    source="scheduler",
                )
                return f"anomalies={len(anomalies)}, recommendations={len(recommendations)}"
            except Exception as exc:
                repo.add_analysis_report(
                    report_date=report_date,
                    locale="en-US",
                    source="scheduler",
                    status="FAILED",
                    summary="",
                    anomalies=[],
                    recommendations=[],
                    error_message=str(exc),
                )
                raise

        _run_scheduled_job("daily-analysis", _work)

    def _weekly_retrain_job() -> None:
        def _work() -> str:
            repo.touch_weekly_retrain()
            models = repo.list_active_models()
            return f"models_updated={len(models)}"

        _run_scheduled_job("weekly-retrain", _work)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        repo.init()
        repo.ensure_seed_data()
        scheduler.add_job(_daily_job, "cron", hour=2, minute=0, id="daily-analysis", replace_existing=True)
        scheduler.add_job(_weekly_retrain_job, "cron", day_of_week="sun", hour=3, minute=0, id="weekly-retrain", replace_existing=True)
        scheduler.start()
        try:
            yield
        finally:
            scheduler.shutdown(wait=False)

    app = FastAPI(title="AAPS Predictive Copilot API", version="0.2.0", lifespan=lifespan)

    @app.get("/v1/sync/pull", response_model=SyncPullResponse)
    def sync_pull(since: int = 0) -> SyncPullResponse:
        result = repo.pull_since(since)
        return SyncPullResponse(glucose=result.glucose, therapyEvents=result.therapy_events, nextSince=result.next_since)

    @app.post("/v1/sync/push", response_model=SyncPushResponse)
    def sync_push(payload: SyncPushRequest) -> SyncPushResponse:
        result = repo.push_sync_payload(glucose=payload.glucose, therapy_events=payload.therapyEvents)
        return SyncPushResponse(
            acceptedGlucose=result.accepted_glucose,
            acceptedTherapyEvents=result.accepted_therapy_events,
            nextSince=result.next_since,
        )

    @app.post("/v1/predict", response_model=PredictResponse)
    def predict_endpoint(payload: PredictRequest) -> PredictResponse:
        forecasts = predict(payload.glucose)
        return PredictResponse(forecasts=forecasts, reasonCodes=["trend", "local_ensemble"])

    @app.post("/v1/rules/evaluate", response_model=EvaluateRulesResponse)
    def evaluate_rules(payload: EvaluateRulesRequest) -> EvaluateRulesResponse:
        decision = evaluate_post_hypo_rebound(payload.glucose)
        return EvaluateRulesResponse(decisions=[decision])

    @app.post("/v1/actions/temp-target", response_model=ActionResponse)
    def create_temp_target(payload: TempTargetRequest) -> ActionResponse:
        record = repo.upsert_temp_target_action(
            action_id=payload.id,
            idempotency_key=payload.idempotencyKey,
            target_mmol=payload.targetMmol,
            duration_minutes=payload.durationMinutes,
        )
        duplicate = record.id != payload.id
        return ActionResponse(
            id=record.id,
            status=record.status,
            message="duplicate_suppressed" if duplicate else record.message,
        )

    @app.get("/v1/actions/{action_id}", response_model=ActionResponse)
    def get_action(action_id: str) -> ActionResponse:
        record = repo.get_action(action_id)
        if not record:
            raise HTTPException(status_code=404, detail="action_not_found")
        return ActionResponse(id=record.id, status=record.status, message=record.message)

    @app.post("/v1/analysis/daily", response_model=DailyAnalysisResponse)
    def daily_analysis(payload: DailyAnalysisRequest) -> DailyAnalysisResponse:
        try:
            summary, anomalies, recommendations = _run_daily_analysis(
                report_date=payload.date,
                locale=payload.locale,
                source="manual",
            )
            repo.record_job_status(
                job_id="daily-analysis",
                status="SUCCESS",
                message=f"manual=true, anomalies={len(anomalies)}, recommendations={len(recommendations)}",
            )
            return DailyAnalysisResponse(summary=summary, anomalies=anomalies, recommendations=recommendations)
        except Exception as exc:
            repo.add_analysis_report(
                report_date=payload.date,
                locale=payload.locale,
                source="manual",
                status="FAILED",
                summary="",
                anomalies=[],
                recommendations=[],
                error_message=str(exc),
            )
            repo.record_job_status(job_id="daily-analysis", status="FAILED", message=f"manual=true, error={exc}")
            raise HTTPException(status_code=500, detail="daily_analysis_failed")

    @app.get("/v1/analysis/history", response_model=DailyAnalysisHistoryResponse)
    def analysis_history(limit: int = 30, source: str | None = None, status: str | None = None, days: int = 60) -> DailyAnalysisHistoryResponse:
        safe_days = max(1, min(days, 365))
        since_ts = int((time.time() - safe_days * 24 * 60 * 60) * 1000)
        rows = repo.list_analysis_reports(limit=limit, source=source, status=status, since_ts=since_ts)
        return DailyAnalysisHistoryResponse(
            items=[
                AnalysisHistoryItem(
                    runTs=row.run_ts,
                    date=row.report_date,
                    locale=row.locale,
                    source=row.source,
                    status=row.status,
                    summary=row.summary,
                    anomalies=row.anomalies,
                    recommendations=row.recommendations,
                    errorMessage=row.error_message,
                )
                for row in rows
            ]
        )

    @app.get("/v1/analysis/trend", response_model=AnalysisWeeklyTrendResponse)
    def analysis_trend(weeks: int = 8, source: str | None = None, status: str | None = None) -> AnalysisWeeklyTrendResponse:
        rows = repo.weekly_analysis_trend(weeks=weeks, source=source, status=status)
        return AnalysisWeeklyTrendResponse(
            items=[
                AnalysisWeeklyTrendItem(
                    weekStart=row.week_start,
                    totalRuns=row.total_runs,
                    successRuns=row.success_runs,
                    failedRuns=row.failed_runs,
                    manualRuns=row.manual_runs,
                    schedulerRuns=row.scheduler_runs,
                    anomaliesCount=row.anomalies_count,
                    recommendationsCount=row.recommendations_count,
                )
                for row in rows
            ]
        )

    @app.get("/v1/models/active", response_model=ActiveModelsResponse)
    def models_active() -> ActiveModelsResponse:
        return ActiveModelsResponse(models=repo.list_active_models())

    @app.get("/v1/jobs/status", response_model=JobsStatusResponse)
    def jobs_status() -> JobsStatusResponse:
        jobs: list[JobStatusOut] = []
        for job_id in tracked_jobs:
            snapshot = repo.get_job_status(job_id)
            sched = scheduler.get_job(job_id)
            next_run_ts = int(sched.next_run_time.timestamp() * 1000) if sched and sched.next_run_time else None
            jobs.append(
                JobStatusOut(
                    jobId=job_id,
                    lastRunTs=snapshot.last_run_ts,
                    lastSuccessTs=snapshot.last_success_ts,
                    lastStatus=snapshot.last_status,
                    lastMessage=snapshot.last_message,
                    nextRunTs=next_run_ts,
                )
            )
        return JobsStatusResponse(timezone=str(scheduler.timezone), jobs=jobs)

    @app.post("/v1/replay/report", response_model=ReplayReportResponse)
    def replay_report(payload: ReplayReportRequest) -> ReplayReportResponse:
        now = int(time.time() * 1000)
        since = payload.since or now - 14 * 24 * 60 * 60 * 1000
        until = payload.until or now
        if since >= until:
            raise HTTPException(status_code=400, detail="invalid_range")

        glucose = repo.list_glucose(since=since, until=until)
        therapy = repo.list_therapy(since=since, until=until)

        return build_replay_report(
            glucose=glucose,
            therapy_events=therapy,
            since=since,
            until=until,
            step_minutes=payload.stepMinutes,
        )

    return app


app = create_app()
