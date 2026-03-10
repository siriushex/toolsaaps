# Sensor Lag V1 QA

## Automated Checks

### JVM checks

Run from:

```bash
cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app
```

Compile:

```bash
./gradlew --no-daemon :app:compileDebugKotlin -Pkotlin.incremental=false
```

Run focused unit tests:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest \
  --tests io.aaps.copilot.data.repository.SensorLagRuntimeEstimatorTest \
  --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest \
  --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest \
  -Pkotlin.incremental=false
```

### Instrumentation smoke

Assemble the app and test APK:

```bash
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest -Pkotlin.incremental=false
```

Install both APKs:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run the sensor-lag UI smoke directly via instrumentation:

```bash
adb shell am instrument -w \
  -e class io.aaps.copilot.ui.foundation.screens.SensorLagUiFlowTest \
  io.aaps.predictivecopilot.test/androidx.test.runner.AndroidJUnitRunner
```

Expected result:

```text
OK (3 tests)
```

Note:
- `:app:connectedDebugAndroidTest` may currently fail because of missing UTP host artifact `com.android.tools.utp:android-test-plugin-host-additional-test-output:31.13.2`.
- This is external to the sensor-lag code path. The direct `adb shell am instrument ...` route is the current working fallback.

## Manual Runtime QA

### Settings and mode transitions

1. Open `Settings`.
2. Verify `sensorLagCorrectionMode` defaults to `OFF`.
3. Switch to `SHADOW`.
4. Wait for a fresh automation cycle.
5. Confirm `Overview` still shows raw glucose as control value and `Estimated now` only as advisory.
6. Open the rollout verdict chip in `Overview` and verify it deep-links into `Analytics > Quality > Sensor-lag 24h detail`.
7. In the detail dialog, press `Keep in SHADOW` and confirm the mode remains `SHADOW`.
8. If current bucket guidance is `ACTIVE candidate` and no runtime gate is blocking, press `Switch to ACTIVE`.
9. Confirm the mode becomes `ACTIVE` without app restart.

### Runtime gating

Verify that `ACTIVE` is blocked when any of the following is true:

1. latest glucose is stale
2. fewer than 4 valid points exist in the last 20 minutes
3. `sensor_quality_score < 0.55`
4. `sensor_quality_blocked = true`
5. `sensor_quality_suspect_false_low = true`
6. sensor age is missing
7. latest glucose input kind is `raw`

Expected behavior:

1. runtime mode falls back to `SHADOW` or remains non-active
2. diagnostics show a readable gate reason
3. raw glucose remains visible

### Age resolution

Check three cases:

1. explicit `sensor_change` or `sensor_start` event exists
2. no explicit event, but age is inferred from gap/source boundary
3. no explicit event and no reliable inferred boundary

Expected behavior:

1. explicit age uses full lag profile up to 20 minutes
2. inferred age uses reduced confidence, capped lag, and attenuated correction
3. missing age does not allow `ACTIVE`

### Replay and analytics

1. Run daily analysis after enough local data is present.
2. Open `Analytics > Quality`.
3. Verify `Sensor lag replay by wear age` is present.
4. Verify age buckets appear as `<24h`, `1-10d`, `10-12d`, `12-14d`, `>14d`.
5. Verify `Sensor Lag Diagnostics` shows current config/runtime mode, age, lag, confidence, raw vs corrected glucose, and gate reason if blocked.
6. Open `Open 24h chart`.
7. Verify the detail dialog shows:
   - rollout guidance for current bucket
   - adjacent bucket comparison
   - lag drift chart
   - correction drift chart
   - runtime mode timeline
   - wear bucket timeline

## Real-Data Scenarios

Use replay or real sensor history to validate these cases:

1. fresh sensor, rising glucose: corrected glucose is above raw, lag near 8-10 minutes
2. 11-14 day sensor, rising glucose: lag increases toward 20 minutes, correction remains clipped within `±1.5 mmol/L`
3. falling glucose: correction becomes negative
4. flat trend: correction stays near zero
5. `SHADOW`: live decisions do not change, but shadow telemetry records rule-change rate and target delta
6. `ACTIVE`: corrected glucose and lag-biased forecasts influence Copilot decisions, while raw storage and AndroidAPS core remain unchanged

## Remaining Gaps

Still not fully covered by automation:

1. no full-loop repository integration test for `runAutomationCycle`
2. no automated replay fixture that asserts persisted raw forecast rows stay unchanged while `ACTIVE` switches only the control path
3. real-world validation still depends on local sensor history and operator review
