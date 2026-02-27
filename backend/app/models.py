from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, Field


class GlucosePoint(BaseModel):
    ts: int
    valueMmol: float
    source: str
    quality: str = "OK"


class TherapyEvent(BaseModel):
    id: str
    ts: int
    type: str
    payload: Dict[str, str] = Field(default_factory=dict)


class ForecastOut(BaseModel):
    ts: int
    horizon: int
    valueMmol: float
    ciLow: float
    ciHigh: float
    modelVersion: str


class PredictRequest(BaseModel):
    glucose: List[GlucosePoint]
    therapyEvents: List[TherapyEvent] = Field(default_factory=list)


class PredictResponse(BaseModel):
    forecasts: List[ForecastOut]
    reasonCodes: List[str]


class ActionProposal(BaseModel):
    type: str
    targetMmol: float
    durationMinutes: int
    reason: str


class RuleDecisionOut(BaseModel):
    ruleId: str
    state: Literal["TRIGGERED", "BLOCKED", "NO_MATCH"]
    reasons: List[str]
    actionProposal: Optional[ActionProposal] = None


class EvaluateRulesRequest(BaseModel):
    glucose: List[GlucosePoint]
    therapyEvents: List[TherapyEvent] = Field(default_factory=list)


class EvaluateRulesResponse(BaseModel):
    decisions: List[RuleDecisionOut]


class SyncPullResponse(BaseModel):
    glucose: List[GlucosePoint]
    therapyEvents: List[TherapyEvent]
    nextSince: int


class SyncPushRequest(BaseModel):
    glucose: List[GlucosePoint] = Field(default_factory=list)
    therapyEvents: List[TherapyEvent] = Field(default_factory=list)


class SyncPushResponse(BaseModel):
    acceptedGlucose: int
    acceptedTherapyEvents: int
    nextSince: int


class TempTargetRequest(BaseModel):
    id: str
    targetMmol: float
    durationMinutes: int
    idempotencyKey: str


class ActionResponse(BaseModel):
    id: str
    status: str
    message: str


class DailyAnalysisRequest(BaseModel):
    date: str
    locale: str


class DailyAnalysisResponse(BaseModel):
    summary: str
    anomalies: List[str]
    recommendations: List[str]


class AnalysisHistoryItem(BaseModel):
    runTs: int
    date: str
    locale: str
    source: str
    status: str
    summary: str
    anomalies: List[str]
    recommendations: List[str]
    errorMessage: Optional[str] = None


class DailyAnalysisHistoryResponse(BaseModel):
    items: List[AnalysisHistoryItem]


class AnalysisWeeklyTrendItem(BaseModel):
    weekStart: str
    totalRuns: int
    successRuns: int
    failedRuns: int
    manualRuns: int
    schedulerRuns: int
    anomaliesCount: int
    recommendationsCount: int


class AnalysisWeeklyTrendResponse(BaseModel):
    items: List[AnalysisWeeklyTrendItem]


class ModelInfo(BaseModel):
    horizon: int
    modelVersion: str
    mae: float
    updatedAt: int


class ActiveModelsResponse(BaseModel):
    models: List[ModelInfo]


class JobStatusOut(BaseModel):
    jobId: str
    lastRunTs: Optional[int] = None
    lastSuccessTs: Optional[int] = None
    lastStatus: Optional[str] = None
    lastMessage: Optional[str] = None
    nextRunTs: Optional[int] = None


class JobsStatusResponse(BaseModel):
    timezone: str
    jobs: List[JobStatusOut]


class ReplayReportRequest(BaseModel):
    since: Optional[int] = None
    until: Optional[int] = None
    stepMinutes: int = 5


class ReplayForecastStats(BaseModel):
    horizon: int
    sampleCount: int
    mae: float
    rmse: float
    mardPct: float


class ReplayRuleStats(BaseModel):
    ruleId: str
    triggered: int
    blocked: int
    noMatch: int


class ReplayDayTypeStats(BaseModel):
    dayType: Literal["WEEKDAY", "WEEKEND"]
    forecastStats: List[ReplayForecastStats]


class ReplayHourStats(BaseModel):
    hour: int
    sampleCount: int
    mae: float
    rmse: float
    mardPct: float


class ReplayDriftStats(BaseModel):
    horizon: int
    previousMae: float
    recentMae: float
    deltaMae: float


class ReplayReportResponse(BaseModel):
    since: int
    until: int
    points: int
    forecastStats: List[ReplayForecastStats]
    ruleStats: List[ReplayRuleStats]
    dayTypeStats: List[ReplayDayTypeStats]
    hourlyStats: List[ReplayHourStats]
    driftStats: List[ReplayDriftStats]


@dataclass
class ActionRecord:
    id: str
    status: str
    message: str
    idempotency_key: str
