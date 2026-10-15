# AAPS Predictive Copilot Backend

FastAPI backend for hybrid analytics and forecasting.

## Optional Personal History

The bound AI app accepts an explicit `history=HistoryService(...)` dependency.
Default app factories mount no history route. This is independent of model jobs:

- `POST /api/ai/v1/history/batches`: typed observational batch, atomic acknowledgment.
- `POST /api/ai/v1/history/query`: device-local revision snapshot with bounded pages.

Both require the existing bound access/key and exact-body P-256 proof. The owner
is server-configured; device identity never comes from the body. Selected typed
fields cover glucose, recorded therapy, meals/GI provenance, forecasts, sensitivity,
activity, device/alert/diagnostic state and archived chat. This is not a complete
Android database export, contextual model or therapy interface.

HistoryStore uses a private local POSIX directory, separate device-key databases
and externally supplied32-byte master key. AES-GCM encrypts record/receipt bodies;
metadata remain plaintext. Clinical and archived request records expire365 days
after original event/request time, not retry time. Query hides expiry and purge
provides maintenance for idle files. Production needs encrypted volume/backups,
key provisioning/restore, periodic purge and measured quotas before activation.
The existing volatile AI job cache and live Android behavior are unchanged.
See `../docs/superpowers/specs/2026-10-14-personal-history-api-design.md`.

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
