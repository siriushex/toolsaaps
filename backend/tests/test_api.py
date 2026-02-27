from __future__ import annotations

import tempfile
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import create_app
from app.repository import CopilotRepository


def build_client() -> TestClient:
    tmp = tempfile.TemporaryDirectory()
    db_path = Path(tmp.name) / "copilot-test.db"
    repo = CopilotRepository(db_url=f"sqlite:///{db_path}")
    app = create_app(repository=repo)
    client = TestClient(app)
    client._tmp_dir = tmp  # keep temp dir alive while client exists
    return client


def test_sync_pull_has_seed_data() -> None:
    with build_client() as client:
        response = client.get("/v1/sync/pull", params={"since": 0})
        assert response.status_code == 200
        data = response.json()
        assert len(data["glucose"]) > 10
        assert len(data["therapyEvents"]) >= 1


def test_sync_push_upserts_without_duplicates() -> None:
    ts = 1_710_000_000_000
    payload = {
        "glucose": [
            {"ts": ts, "valueMmol": 6.2, "source": "android", "quality": "OK"},
            {"ts": ts, "valueMmol": 6.2, "source": "android", "quality": "OK"},
        ],
        "therapyEvents": [
            {"id": "android-temp-1", "ts": ts + 1_000, "type": "temp_target", "payload": {"targetBottom": "5.0"}},
            {"id": "android-temp-1", "ts": ts + 1_000, "type": "temp_target", "payload": {"targetBottom": "5.0"}},
        ],
    }

    with build_client() as client:
        first = client.post("/v1/sync/push", json=payload)
        assert first.status_code == 200
        assert first.json()["acceptedGlucose"] == 1
        assert first.json()["acceptedTherapyEvents"] == 1
        assert first.json()["nextSince"] == ts + 1_000

        second = client.post("/v1/sync/push", json=payload)
        assert second.status_code == 200
        assert second.json()["acceptedGlucose"] == 1
        assert second.json()["acceptedTherapyEvents"] == 1

        pulled = client.get("/v1/sync/pull", params={"since": ts - 1})
        assert pulled.status_code == 200
        data = pulled.json()
        pushed_glucose = [row for row in data["glucose"] if row["ts"] == ts and row["source"] == "android"]
        pushed_therapy = [row for row in data["therapyEvents"] if row["id"] == "android-temp-1"]
        assert len(pushed_glucose) == 1
        assert len(pushed_therapy) == 1


def test_temp_target_is_idempotent() -> None:
    payload = {
        "id": "action-1",
        "targetMmol": 5.0,
        "durationMinutes": 60,
        "idempotencyKey": "same-key",
    }
    with build_client() as client:
        first = client.post("/v1/actions/temp-target", json=payload)
        assert first.status_code == 200
        assert first.json()["message"].startswith("temp target")

        second = client.post(
            "/v1/actions/temp-target",
            json={**payload, "id": "action-2"},
        )
        assert second.status_code == 200
        assert second.json()["message"] == "duplicate_suppressed"
        assert second.json()["id"] == "action-1"


def test_replay_report_returns_stats() -> None:
    with build_client() as client:
        response = client.post("/v1/replay/report", json={"stepMinutes": 5})
        assert response.status_code == 200
        data = response.json()
        assert data["points"] > 10
        assert "forecastStats" in data
        assert "ruleStats" in data
        assert "dayTypeStats" in data
        assert "hourlyStats" in data
        assert "driftStats" in data


def test_jobs_status_has_scheduled_jobs() -> None:
    with build_client() as client:
        response = client.get("/v1/jobs/status")
        assert response.status_code == 200
        data = response.json()
        assert data["timezone"]
        job_ids = {job["jobId"] for job in data["jobs"]}
        assert "daily-analysis" in job_ids
        assert "weekly-retrain" in job_ids


def test_daily_analysis_updates_job_status() -> None:
    with build_client() as client:
        response = client.post("/v1/analysis/daily", json={"date": "2026-02-27", "locale": "ru-RU"})
        assert response.status_code == 200

        jobs = client.get("/v1/jobs/status")
        assert jobs.status_code == 200
        daily = next(job for job in jobs.json()["jobs"] if job["jobId"] == "daily-analysis")
        assert daily["lastStatus"] == "SUCCESS"
        assert daily["lastRunTs"] is not None


def test_analysis_history_returns_recent_reports() -> None:
    with build_client() as client:
        first = client.post("/v1/analysis/daily", json={"date": "2026-02-26", "locale": "ru-RU"})
        assert first.status_code == 200
        second = client.post("/v1/analysis/daily", json={"date": "2026-02-27", "locale": "en-US"})
        assert second.status_code == 200

        response = client.get("/v1/analysis/history", params={"limit": 2})
        assert response.status_code == 200
        data = response.json()
        assert len(data["items"]) == 2
        assert data["items"][0]["status"] in {"SUCCESS", "FAILED"}
        assert data["items"][0]["source"] in {"manual", "scheduler"}
        assert isinstance(data["items"][0]["runTs"], int)

        filtered = client.get(
            "/v1/analysis/history",
            params={"limit": 10, "source": "manual", "status": "SUCCESS", "days": 365},
        )
        assert filtered.status_code == 200
        for item in filtered.json()["items"]:
            assert item["source"] == "manual"
            assert item["status"] == "SUCCESS"


def test_analysis_trend_returns_weekly_aggregation() -> None:
    with build_client() as client:
        for date_value in ("2026-02-20", "2026-02-21", "2026-02-27"):
            response = client.post("/v1/analysis/daily", json={"date": date_value, "locale": "en-US"})
            assert response.status_code == 200

        trend = client.get("/v1/analysis/trend", params={"weeks": 8, "source": "manual", "status": "SUCCESS"})
        assert trend.status_code == 200
        data = trend.json()
        assert "items" in data
        assert len(data["items"]) >= 1
        first = data["items"][0]
        assert first["weekStart"]
        assert first["totalRuns"] >= 1
        assert first["successRuns"] >= 1
