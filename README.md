# AAPS Predictive Copilot

Standalone Android + backend prototype for advanced AAPS/OpenAPS analytics:

- Full-history Nightscout sync + AAPS export baseline import
- Incremental cloud sync pull/push (`/v1/sync/pull`, `/v1/sync/push`)
- 5m and 1h glucose forecasts (on-device + optional cloud override)
- Rule engine with hard safety guardrails and kill switch
- Rule control: enable/disable per rule, priority ordering, and dry-run simulation
- Automatic temp target actions via Nightscout API (idempotent)
- Weekday/weekend pattern discovery by hour with reliability gating (`min samples`, `min active days`)
- Adaptive target rule only on validated risk windows (reduces noisy auto-actions)
- Long-window ISF/CR estimation from historical events with outlier trimming and sample counters
- Segmented ISF/CR profile (`weekday/weekend x night/morning/afternoon/evening`)
- Segment-aware auto rule (`SegmentProfileGuard`) for gentle temp-target bias by sensitivity slot
- Configurable pattern thresholds + analytics lookback directly in Safety settings
- Forecast quality metrics (MAE/RMSE/MARD) + delta vs AAPS baseline
- Backend replay report API for historical E2E validation (global, weekday/weekend, hourly, drift)
- Replay Lab screen with filters (days/step/horizon) and CSV/PDF export
- Sync health observability (data age, NS sync lag, cloud push backlog)
- Cloud scheduler status view (daily analysis + weekly retrain)
- Daily analysis history with filters (source/status/days) + weekly trend (weeks)
- Insights export (history + trend) to CSV/PDF
- Daily AI insights via OpenAI on backend

## Structure

- `android-app/` - Android app (Kotlin + Compose + Room + WorkManager)
- `backend/` - FastAPI cloud service

## Android app quick start

```bash
cd android-app
./gradlew :app:assembleDebug
```

## Backend quick start

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8080
```

## Safety defaults

- Kill switch available globally
- Max 3 auto-actions per 6h
- Data freshness required (`<=10m`)
- Target bounds constrained to `4.4..8.0 mmol/L`

This project is intended for personal R&D and is **not** a medical device.
