# Compact Clarity And Lenta Interface

Approved 2026-10-12 after written compact-design review. UI-only scope.

## Contract

- Keep six accepted metrics, current glucose, raw companion when needed,
  observed AAPS target versus proposal, and existing freshness/warning states.
- Use compact metric value/unit lines and a height-aware main chart. No smaller
  system fonts, clipped values, hidden warning or overlapping touch targets.
  Normal narrow screens should fit; enlarged fonts and extra warnings may scroll.
- Top Overview actions reuse runCycleNow/canRunCycleNow and the existing consumed
  BLOOD_CHECK editor request. Opening blood entry does not calibrate or reset.
- Remove root duplicated More menu; preserve its power-save action in the drawer.
  Safety/kill-switch, alerts, audit, settings and all existing destinations remain.
- Remove Clarity's duplicated lower clinical toolbar; preserve compensation-report
  navigation through the glucose summary and show applying/error messages beside
  sensitivity metrics with the same passive help and source routes.
- Healthy/unknown pump-channel information uses the same passive help through
  a48dp indicator. Attention states and open episodes retain their text row.
- Preserve a readable main chart minimum140dp. Combine its freshness captions;
  avoid the redundant main-tab title and extra lower tool row.
- COB opens the selected full Lenta food view. Plus opens the same existing food
  form. The food view offers small add-food/Eating Soon actions, closes before
  opening their original dialogs, and never submits on opening/cancelling.
- Remove manual GI input from the shared Food form only. Preserve portion/type,
  manual grams, calories preference, Eating Soon default and explicit submission.
  Keep domain/wire GI compatibility; no guessed or learned GI is inserted.
- Food view uses the accepted food-only points, their original clock/scope/status,
  and their actual mmol/L units. The browser's g/hour drawing was illustrative,
  not permission to relabel a clinical series or add an unvalidated rate estimator.
- Default food viewport contains its first/last accepted points. Remove visible
  start/fit/end/zoom controls. Preserve gestures and existing accessibility reset.
- Timeline shows only available real entered meal events and declared grams.
  No fake per-meal COB allocation, inferred meal confirmation, physiological end,
  or delayed-fat GI claim. Missing/unknown values stay missing.
- Individual event context duration does not establish full absorption. Only a
  complete currently accepted food display may offer its estimated aggregate end.
- Keep 5/30/60 glucose forecast domain, marker timestamps and accepted freshness.
  No new poller, database query/subscription, worker, setting or schema.

## Excluded

Historical numeric GI/carbohydrate estimation, target priority or clinical guard
changes, changing therapy thresholds/confirmation, backend/AAPS changes, device
installation, phone database/audio access and claims of CPU/RAM savings.

## Verification

Native RED/GREEN tests for COB route versus plus, absent GI and duplicated tools,
default full food horizon, unchanged editor confirmations, upper-action disabled
state, and source-message visibility. Inspect synthetic native light/dark/narrow/
large-font images. Run full Android unit/lint/Kotlin/APK gates and verify signature.
Publish reviewed source only; phone acceptance is separate.
