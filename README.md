# AAPS Predictive Copilot

Standalone Android + backend prototype for advanced AAPS/OpenAPS analytics:

- Full-history Nightscout sync + AAPS export baseline import
- Auto-bootstrap on first launch: AAPS export path discovery + optional root bootstrap for local DB
- Auto-connect discovery report in UI (found export path, detected root DB, installed AAPS/xDrip packages, Nightscout source)
- Local real-time intake via broadcast (`com.eveningoutpost.dexdrip.*`, `info.nightscout.client.*`)
- Telemetry layer for full context snapshot (IOB/COB/carbs/insulin/DIA/steps/activity/temp-target + raw incoming metrics)
- Incremental cloud sync pull/push (`/v1/sync/pull`, `/v1/sync/push`)
- 5m and 1h glucose forecasts (on-device + optional cloud override)
- Rule engine with hard safety guardrails and kill switch
- Tunable `PostHypoReboundGuard` thresholds (hypo threshold, rebound delta, temp target, duration, lookback)
- Rule control: enable/disable per rule, priority ordering, and dry-run simulation
- Single-action arbitration per automation cycle + configurable per-rule cooldown windows
- Live cooldown status per rule in `Rules & Automation`
- Automatic temp target actions via Nightscout API (idempotent)
- Weekday/weekend pattern discovery by hour with reliability gating (`min samples`, `min active days`)
- Adaptive target rule only on validated risk windows (reduces noisy auto-actions)
- Long-window ISF/CR estimation from historical events with outlier trimming and sample counters
- UAM-aware ISF/CR estimation: auto-filters noisy correction samples during UAM windows and reports UAM counters
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
- Dedicated `Telemetry` screen with coverage status for key parameters and full latest-by-key input list
- Dashboard action-delivery timeline (status + channel: Nightscout/fallback)

## Structure

- `android-app/` - Android app (Kotlin + Compose + Room + WorkManager)
- `backend/` - FastAPI cloud service

## Android app quick start

```bash
cd android-app
./gradlew :app:assembleDebug
```

### Local broadcast ingest

`AAPS Predictive Copilot` listens to these broadcast actions automatically:

- `com.eveningoutpost.dexdrip.BgEstimate`
- `com.eveningoutpost.dexdrip.BgEstimateNoData`
- `com.eveningoutpost.dexdrip.NS_EMULATOR`
- `info.nightscout.client.NEW_SGV`
- `info.nightscout.client.NEW_TREATMENT`

Each accepted local broadcast also enqueues an immediate reactive automation cycle
(`copilot.sync.reactive`) so rule evaluation/auto-temp-target can run without waiting for the
15-minute periodic worker.

This channel can be toggled in app UI:
`Onboarding & Connect -> Local xDrip/AAPS broadcast ingest`.
Optional strict mode is available in the same screen:
`Strict sender validation (Android 14+)`.

On Android 14+ the receiver also validates sender package where available:
- xDrip actions: packages starting with `com.eveningoutpost.dexdrip`
- AAPS client actions: `info.nightscout.androidaps` / `info.nightscout.aaps`
- If sender package is unavailable (OEM/OS-dependent behavior), known xDrip/AAPS action families are still accepted to avoid stale glucose stream in strict mode.

`com.eveningoutpost.dexdrip.BgEstimateNoData` is tracked as `sensor_state(blocked=true)` and
temporarily blocks safety-sensitive automation decisions.

For stable delivery on modern Android, send to explicit package
`io.aaps.predictivecopilot` (same setting should be used in xDrip broadcast config).

Transport note:
- Standard AAPS builds receive local BG broadcasts, but treatment commands are not consumed from the
  same local broadcast channel.
- For `temp target` and `carbs` delivery into AAPS loop, use Nightscout API path.
- Built-in local Nightscout emulator is available in app:
  `Onboarding & Connect -> Local Nightscout emulator (127.0.0.1)`.
  For `https://127.0.0.1:<port>` in AAPS, install Copilot certificate in Android as **CA certificate** (`.cer` preferred).
  Copilot exports:
  - `copilot-local-nightscout-root-ca.cer` (install this as CA on Android)
  - `copilot-local-nightscout-root-ca.crt` (same root CA in PEM)
  - `copilot-local-nightscout-server.crt` (server cert, for diagnostics only)
  It exposes:
  - `GET /api/v1/status.json`
  - `GET/POST /api/v1/entries(.json)` and `GET/POST /api/v1/entries/sgv.json`
  - `GET/POST /api/v1/treatments(.json)`
  - `GET/POST /api/v1/devicestatus(.json)`
  - `GET/POST /socket.io/` (Engine.IO v4 polling + Socket.IO protocol 5 for AAPS `NSClientV1`).
- Runtime is pinned by a foreground service and auto-restored on boot/package update when enabled.
- If requested local port is busy, Copilot auto-selects the next free loopback port and updates app settings.
- Cleartext `http://127.0.0.1:<port>` transport is enabled for local loopback integration.
- Use `Run Nightscout self-test` button in Onboarding to validate read/write transport immediately from phone.
- Each accepted local Nightscout POST (`entries`, `treatments`, `devicestatus`) enqueues reactive automation worker (`copilot.sync.reactive`) for faster rule evaluation.
- Nightscout sync also ingests `devicestatus` and maps known telemetry keys into local snapshot storage.
- Optional outbound local fallback relay for `temp target` and `carbs` is available (`Onboarding & Connect`), intended only for compatible AAPS forks.
- Primary delivery path remains Nightscout API; local relay is fallback-only when Nightscout delivery fails.

Quick manual test:

```bash
adb shell am broadcast \
  -p io.aaps.predictivecopilot \
  -a io.aaps.copilot.BROADCAST_TEST_INGEST \
  --es units mmol \
  --ef sgv 5.8 \
  --el timestamp $(date +%s)
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
