# AAPS Predictive Copilot Backend

FastAPI backend for hybrid analytics and forecasting.

## Run

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8080
```

Recommended runtime: Python 3.12 or 3.13.

## Env

- `OPENAI_API_KEY` (optional): enables AI daily insights.
- `COPILOT_TIMEZONE` (optional, default `UTC`): scheduler timezone.
- `COPILOT_DB_URL` (optional, default `sqlite:///./copilot.db`): persistent DB URL. Example Postgres: `postgresql+psycopg://user:pass@host:5432/copilot`.

## Endpoints

- `GET /v1/sync/pull?since=`
- `POST /v1/predict`
- `POST /v1/rules/evaluate`
- `POST /v1/actions/temp-target`
- `GET /v1/actions/{id}`
- `POST /v1/analysis/daily`
- `GET /v1/analysis/history?limit=&source=&status=&days=`
- `GET /v1/analysis/trend?weeks=&source=&status=`
- `GET /v1/models/active`
- `GET /v1/jobs/status`
- `POST /v1/replay/report`
