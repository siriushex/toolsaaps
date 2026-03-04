# AAPS Predictive Copilot — UI Handoff for Figma

## 1) App shell
- Platform: Android, Jetpack Compose, Material 3.
- Root nav:
  - Bottom tabs: `Overview`, `Forecast`, `UAM`, `Safety`.
  - Overflow/More: `Audit Log`, `Analytics`, `Settings`.
- Global top area on every screen:
  - TopAppBar with title + menu + health icon + more menu.
  - AppHealthBanner (stale data / kill switch / last sync).
- State wrapper on each screen:
  - `LOADING` (skeleton cards + chart placeholder).
  - `EMPTY` (centered empty message).
  - `ERROR` (SafetyBanner error).
  - `READY` (content + stale warning banner when needed).

## 2) Design tokens (from code)
### Spacing
- `xxs=4`, `xs=8`, `sm=12`, `md=16`, `lg=24`, `xl=32`.

### Elevation
- `level1=1dp`, `level2=3dp`, `level3=6dp`.

### Shapes
- small `12dp`
- medium `16dp`
- large `20dp`
- pill radius `999dp`
- section cards typically `18dp`

### Typography
- Material 3 base typography + numeric typography:
  - valueLarge: Monospace, 36sp, Bold
  - valueMedium: Monospace, 24sp, SemiBold
  - valueSmall: Monospace, 16sp, Medium

### Color system
- Material 3 dynamic color on Android 12+.
- Fallback light/dark palettes defined in theme.
- Status usage is semantic (container + onContainer):
  - success: `secondaryContainer` + `onSecondaryContainer`
  - warning: `tertiaryContainer` + `onTertiaryContainer`
  - error: `errorContainer` + `onErrorContainer`
  - neutral cards: `surface` / `surfaceVariant` + `onSurface*`

## 3) Reusable UI patterns/components
- Section card (`18dp`, `outlineVariant` border, `surface` background).
- Info card/cell (`12dp`, `surfaceVariant`, `outlineVariant`).
- Pill/chip (`999dp`) with icon + text.
- Status pill levels:
  - `INFO`, `WARN`, `ERROR`, `OK`.
- Chart card:
  - grid, axis labels, now/30m/60m markers, CI area, history/future lines.
- Toggle rows in Settings.
- Action rows with 2-column button groups.

## 4) Screens (current app)

### 4.1 Overview
Purpose:
- Fast clinical summary + quick actions.

Layout blocks:
1. Header row: app title + LIVE/STALE pill.
2. Current glucose block:
   - big glucose number (animated)
   - delta/ROC (animated)
   - sample age
   - risk pill (LOW/HIGH/STALE/etc)
   - warning for wide CI horizon
3. Prediction mini-cards: 5m / 30m / 60m with CI.
4. UAM status block: active, uci0, inferred carbs 60m, mode.
5. Telemetry chips: IOB/COB/activity/steps.
6. Last action block: temp target/carbs summary + status + idempotency.
7. Actions: `Run cycle now`, `Kill switch`.

Key design details:
- Dominant numeric hierarchy for glucose.
- Risk/status never color-only (icon + text).

### 4.2 Forecast
Purpose:
- Explain predicted trajectory and decomposition.

Layout blocks:
1. Controls:
   - range selector (`3h/6h/24h`).
   - layer chips: Trend/Therapy/UAM/CI.
2. Main chart:
   - history line + future path
   - CI translucent area
   - markers at now/30m/60m
   - grid + axis helper labels
3. Horizon cards 5/30/60.
4. Decomposition card:
   - trend60 / therapy60 / uam60
   - net change
   - pro metrics (residualRoc0, sigmaE, kfSigmaG)
5. Quality lines card.

### 4.3 UAM
Purpose:
- UAM events review and manual control.

Layout blocks:
1. Summary card: inferred vs calculated UAM metrics.
2. Export status pill (disabled / dry-run / live).
3. Pending attention warning pill (if pending events > 0).
4. Event list cards:
   - id, state+mode, confidence, mode, start time, carbs, seq, tag
   - anti-duplicate status
   - actions: mark correct/wrong, merge with manual, export to AAPS
5. Event details bottom sheet with same actions.

### 4.4 Safety
Purpose:
- Hard safety controls and policy visibility.

Layout blocks:
1. Kill switch card with highlighted state banner + switch.
2. Limits card:
   - stale minutes, max actions/6h
   - base target, hard bounds, adaptive bounds
   - local NS status + TLS text
3. Cooldown card lines.
4. Safety checklist list (ok/fail rows with icon+text).
5. Summary card (manual/automated mode + checks passed).

### 4.5 Audit Log
Purpose:
- Traceability of decisions and actions.

Layout blocks:
1. Filters card:
   - windows `6h/24h/7d`
   - `only errors` toggle chip
2. Summary card:
   - total / warn / error + info-ok count
3. Audit rows:
   - timestamp
   - level pill (`Info/Warn/Alert/OK`)
   - summary
   - source/idempotency/payload chips
   - expandable details section (context, idempotency, payload).

### 4.6 Analytics
Purpose:
- Quality and ISF/CR analytics.

Tabs:
- `ISF/CR` (default)
- `Quality`

ISF/CR tab blocks:
1. Overview metrics: ISF real/merged, CR real/merged.
2. Window chips: day/week/month/year/all (resolver-based window).
3. ISF chart:
   - real solid line
   - merged dashed line
4. CR chart:
   - same pattern
5. Legend + stats min/max/last + time range.
6. Optional deep diagnostics lines.

Quality tab blocks:
- quality lines
- baseline delta lines

### 4.7 Settings
Purpose:
- Source, UAM, adaptive, debug, privacy configuration.

Sections:
1. Data sources:
   - NS URL, resolved URL, local NS on/off
   - source status pills (Broadcast/Nightscout)
   - toggles: local ingest, strict sender validation
2. UAM settings:
   - inference, boost, export, dry-run toggles
   - export mode (readonly)
   - params summary (`min/max/step`)
3. Adaptive settings:
   - enabled toggle
   - base target, insulin profile, post-hypo threshold/target
   - adaptive safety summary tiles
4. Debug:
   - Pro mode
   - Verbose logs
5. Privacy:
   - retention days
6. Disclaimer card (not medical device)
7. App info card (name/version)

## 5) Attached visual evidence (live screenshots)
- `01_overview.png`
- `02_forecast.png`
- `03_uam.png`
- `04_safety.png`
- `08_audit.png`
- `09_analytics.png`
- `10_settings.png`

All screenshots were captured from device build `io.aaps.predictivecopilot` (1080p+ density device, portrait).

## 6) Figma transfer guidance (for exact parity)
1. Create one page `00 App Shell` with top bar, app health banner, bottom nav.
2. Create one page per screen (`Overview`, `Forecast`, `UAM`, `Safety`, `Audit`, `Analytics`, `Settings`).
3. For each page place the matching screenshot as locked reference layer.
4. Rebuild components on top using token values from section 2.
5. Create component sets:
   - section card
   - info card/cell
   - status pills (info/warn/error/ok)
   - telemetry chip
   - action button row
   - chart legend chips
6. Keep both Light and Dark variants; for Android 12+ dynamic color, keep semantic styles (not raw hardcoded colors) in Figma tokens.
