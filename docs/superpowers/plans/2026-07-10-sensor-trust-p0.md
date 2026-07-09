# Sensor Trust P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent every non-rollback automatic outbound action while sensor trust is blocked and stop unsafe lag-aligned blood-glucose checks from influencing calibration.

**Architecture:** Add `sensorBlocked` to the common rule safety policy, carry the same immutable cycle value into UAM export, and keep the existing sensor rollback as the sole automatic exception. Move bounded CGM matching and check assessment into a pure `GlucoseCalibrationGuard`; the repository remains responsible for Room access, reassessment persistence, model weighting, retirement, and audit.

**Tech Stack:** Kotlin 2.x, Android/Room, Kotlin coroutines, JUnit 4, Google Truth, Gradle, Android Studio JBR 21, adb.

---

## Scope and Workspace Safety

Implement only the approved P0 design in
`docs/superpowers/specs/2026-07-10-sensor-trust-p0-design.md`.

Do not add the later episode classifier, infusion-set diagnosis, notification
redesign, dosing logic, worker, poller, or schema migration.

The current tree contains pre-existing work that must be preserved:

- `AutomationRepository.kt` is already modified.
- `GlucoseCalibrationRepository.kt` and its current test are untracked.
- Many unrelated UI, database, alert, widget, and power changes are dirty.

Before each task, run `git status --short` and inspect the exact target diff.
Commit only tasks whose complete file set can be staged without capturing
pre-existing changes. Leave integration edits to dirty/untracked files
unstaged and report them explicitly; never reset, stash, or overwrite the
existing worktree.

## File Map

- Modify `android-app/app/src/main/kotlin/io/aaps/copilot/domain/safety/SafetyPolicy.kt`
  - Own the global deterministic sensor-block reason for rule proposals.
- Modify `android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/RuleEngine.kt`
  - Pass `RuleContext.sensorBlocked` into the common safety boundary.
- Modify `android-app/app/src/test/kotlin/io/aaps/copilot/domain/safety/SafetyPolicyTest.kt`
  - Prove the direct policy contract.
- Modify `android-app/app/src/test/kotlin/io/aaps/copilot/domain/rules/RuleEngineRuntimeConfigTest.kt`
  - Reproduce the fallback-rule bypass using `SegmentProfileGuardRule`.
- Modify `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/UamExportCoordinator.kt`
  - Block carbohydrate posts while retaining inference/reconciliation.
- Modify `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/UamExportCoordinatorTest.kt`
  - Prove blocked cycles perform no outbound post.
- Modify `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`
  - Carry the immutable cycle sensor flag to UAM export; retain rollback and
    keepalive behavior.
- Create `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuard.kt`
  - Pure bounded matching, aligned trust assessment, quality timestamp, and
    retirement transformations.
- Create `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuardTest.kt`
  - Unit coverage for interpolation, trust, mismatch, weighting timestamp, and
    model retirement.
- Modify `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationRepository.kt`
  - Delegate assessment, persist reassessment, use aligned quality, retire
    invalid models, and write bounded audit metadata.
- Create `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/SensorTrustP0ReplayTest.kt`
  - Synthetic, date-free replay of the observed failure shape. Do not commit
    raw device health data.

## Common Commands

Run Gradle commands from `android-app` with:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --console=plain --no-daemon
```

### Task 1: Add the direct SafetyPolicy sensor gate

**Files:**
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/domain/safety/SafetyPolicyTest.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/domain/safety/SafetyPolicy.kt`

- [ ] **Step 1: Write the failing policy test**

Add this test before changing production code:

```kotlin
@Test
fun blocks_whenSensorBlocked() {
    val decision = policy.evaluate(
        proposal = proposal(target = 5.5, duration = 30),
        config = SafetyPolicyConfig(killSwitch = false),
        dataFresh = true,
        actionsLast6h = 0,
        sensorBlocked = true
    )

    assertThat(decision.allowed).isFalse()
    assertThat(decision.reasons).containsExactly("sensor_blocked")
}
```

- [ ] **Step 2: Run RED and confirm the missing contract**

Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.safety.SafetyPolicyTest' --console=plain --no-daemon
```

Expected: compilation fails because `sensorBlocked` is not yet a parameter.
Do not continue if the new test passes.

- [ ] **Step 3: Implement the minimal policy gate with a temporary default**

Change the method to:

```kotlin
fun evaluate(
    proposal: ActionProposal,
    config: SafetyPolicyConfig,
    dataFresh: Boolean,
    actionsLast6h: Int,
    sensorBlocked: Boolean = false
): SafetyDecision {
    val reasons = mutableListOf<String>()
    if (config.killSwitch) reasons += "kill_switch"
    if (!dataFresh) reasons += "stale_data"
    if (sensorBlocked) reasons += "sensor_blocked"
    if (!proposal.type.equals("temp_target", ignoreCase = true) &&
        actionsLast6h >= config.maxActionsIn6Hours
    ) {
        reasons += "rate_limit_6h"
    }
    if (proposal.targetMmol !in config.minTargetMmol..config.maxTargetMmol) {
        reasons += "target_out_of_bounds"
    }
    if (proposal.durationMinutes !in 15..120) {
        reasons += "duration_out_of_bounds"
    }

    return SafetyDecision(
        allowed = reasons.isEmpty(),
        reasons = reasons
    )
}
```

The default exists only so Task 1 can isolate the direct policy behavior. Task
2 removes it after all call sites pass the flag explicitly.

- [ ] **Step 4: Run GREEN**

Run the Task 1 command again. Expected: `SafetyPolicyTest` passes.

- [ ] **Step 5: Commit the isolated clean task**

Verify both files were clean before this task, then run:

```bash
git add android-app/app/src/main/kotlin/io/aaps/copilot/domain/safety/SafetyPolicy.kt android-app/app/src/test/kotlin/io/aaps/copilot/domain/safety/SafetyPolicyTest.kt
git diff --cached --check
git commit -m "fix: block rule actions for unsafe sensor data"
```

### Task 2: Close the RuleEngine fallback bypass

**Files:**
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/domain/rules/RuleEngineRuntimeConfigTest.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/RuleEngine.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/domain/safety/SafetyPolicy.kt`
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/domain/safety/SafetyPolicyTest.kt`

- [ ] **Step 1: Add an incident-shaped fallback regression test**

Add imports:

```kotlin
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
```

Add the test:

```kotlin
@Test
fun blocksSegmentFallbackWhenSensorIsBlocked() {
    val rule = SegmentProfileGuardRule()
    val engine = RuleEngine(listOf(rule), SafetyPolicy())
    val context = RuleContext(
        nowTs = 1_000L,
        glucose = emptyList(),
        therapyEvents = emptyList(),
        forecasts = emptyList(),
        currentDayPattern = null,
        baseTargetMmol = 5.5,
        dataFresh = true,
        activeTempTargetMmol = null,
        actionsLast6h = 0,
        sensorBlocked = true,
        currentProfileEstimate = ProfileEstimate(
            isfMmolPerUnit = 2.0,
            crGramPerUnit = 10.0,
            confidence = 0.8,
            sampleCount = 20,
            isfSampleCount = 10,
            crSampleCount = 10,
            lookbackDays = 14
        ),
        currentProfileSegment = ProfileSegmentEstimate(
            dayType = DayType.WEEKDAY,
            timeSlot = ProfileTimeSlot.NIGHT,
            isfMmolPerUnit = 3.0,
            crGramPerUnit = 10.0,
            confidence = 0.8,
            isfSampleCount = 10,
            crSampleCount = 10,
            lookbackDays = 14
        )
    )

    val decision = engine.evaluate(
        context = context,
        config = SafetyPolicyConfig(killSwitch = false)
    ).single()

    assertThat(decision.ruleId).isEqualTo("SegmentProfileGuard.v1")
    assertThat(decision.state).isEqualTo(RuleState.BLOCKED)
    assertThat(decision.reasons).contains("sensor_blocked")
    assertThat(decision.actionProposal).isNull()
}
```

- [ ] **Step 2: Run RED and confirm the historical bypass**

Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.rules.RuleEngineRuntimeConfigTest' --console=plain --no-daemon
```

Expected: the new test receives `TRIGGERED`, because `RuleEngine` still uses the
temporary `sensorBlocked=false` default.

- [ ] **Step 3: Pass the cycle flag through RuleEngine**

Change the policy call to:

```kotlin
val safety = safetyPolicy.evaluate(
    proposal = decision.actionProposal,
    config = config,
    dataFresh = context.dataFresh,
    actionsLast6h = context.actionsLast6h,
    sensorBlocked = context.sensorBlocked
)
```

- [ ] **Step 4: Remove the temporary SafetyPolicy default**

Make the final signature explicit:

```kotlin
fun evaluate(
    proposal: ActionProposal,
    config: SafetyPolicyConfig,
    dataFresh: Boolean,
    actionsLast6h: Int,
    sensorBlocked: Boolean
): SafetyDecision
```

In every existing direct `SafetyPolicyTest` call, add:

```kotlin
sensorBlocked = false
```

Keep `sensorBlocked = true` in the new blocked test. This compile-time
requirement prevents future policy callers from silently omitting sensor trust.

- [ ] **Step 5: Run both focused suites**

Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.safety.SafetyPolicyTest' --tests 'io.aaps.copilot.domain.rules.RuleEngineRuntimeConfigTest' --console=plain --no-daemon
```

Expected: both classes pass; the segment proposal is `BLOCKED` with no action.

- [ ] **Step 6: Commit the isolated clean task**

```bash
git add android-app/app/src/main/kotlin/io/aaps/copilot/domain/safety/SafetyPolicy.kt android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/RuleEngine.kt android-app/app/src/test/kotlin/io/aaps/copilot/domain/safety/SafetyPolicyTest.kt android-app/app/src/test/kotlin/io/aaps/copilot/domain/rules/RuleEngineRuntimeConfigTest.kt
git diff --cached --check
git commit -m "fix: enforce sensor gate across fallback rules"
```

### Task 3: Fail closed at the UAM export boundary

**Files:**
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/UamExportCoordinatorTest.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/UamExportCoordinator.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`

- [ ] **Step 1: Write the failing coordinator test**

Add this test:

```kotlin
@Test
fun sensorBlockedReconcilesButDoesNotExport() = runBlocking {
    val nowTs = 1_760_000_000_000L
    val gateway = FakeGateway()
    val coordinator = UamExportCoordinator(gateway = gateway)
    val event = confirmedEvent(
        id = "evt-blocked",
        nowTs = nowTs,
        carbsDisplay = 25.0,
        exported = 0.0,
        seq = 0
    )

    val outcome = coordinator.process(
        nowTs = nowTs,
        events = listOf(event),
        config = defaultConfig(
            exportMode = UamExportMode.CONFIRMED_ONLY,
            sensorBlocked = true
        )
    )

    assertThat(gateway.fetchCalls).isEqualTo(1)
    assertThat(gateway.posts).isEmpty()
    assertThat(outcome.events).hasSize(1)
}
```

Change the test helper signatures to make trust explicit:

```kotlin
private fun defaultConfig(
    exportMode: UamExportMode,
    exportMaxBackdateMin: Int = 180,
    sensorBlocked: Boolean = false
) = UamExportCoordinator.Config(
    enableUamExportToAaps = true,
    sensorBlocked = sensorBlocked,
    exportMode = exportMode,
    dryRunExport = false,
    minSnackG = 15,
    maxSnackG = 60,
    snackStepG = 5,
    exportMinIntervalMin = 10,
    exportMaxBackdateMin = exportMaxBackdateMin,
    calculatedCarbsGrams = null,
    calculatedToOriginalMultiplier = 2.4
)
```

Add fetch counting to `FakeGateway`:

```kotlin
var fetchCalls: Int = 0
    private set

override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
    fetchCalls += 1
    return Result.success(fetched)
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.data.repository.UamExportCoordinatorTest' --console=plain --no-daemon
```

Expected: compilation fails because `Config.sensorBlocked` does not exist.

- [ ] **Step 3: Add the boundary field and block posts**

Add the required field with no default:

```kotlin
data class Config(
    val enableUamExportToAaps: Boolean,
    val sensorBlocked: Boolean,
    val exportMode: UamExportMode,
    val dryRunExport: Boolean,
    val minSnackG: Int,
    val maxSnackG: Int,
    val snackStepG: Int,
    val exportMinIntervalMin: Int,
    val exportMaxBackdateMin: Int,
    val calculatedCarbsGrams: Double?,
    val calculatedToOriginalMultiplier: Double
)
```

Keep remote reconciliation before the gate, then use:

```kotlin
if (!config.enableUamExportToAaps ||
    config.sensorBlocked ||
    config.exportMode == UamExportMode.OFF
) {
    return Outcome(events = updated, remoteEntries = remote)
}
```

- [ ] **Step 4: Carry the immutable cycle flag through AutomationRepository**

At the existing call in prediction preparation, add:

```kotlin
val inferredUam = maybeProcessUamInferenceCycle(
    settings = settings,
    nowTs = nowTs,
    glucose = glucose,
    therapy = therapy,
    profile = currentProfile,
    calculatedSnapshot = calculatedUam,
    sensorBlocked = sensorBlocked
)
```

Change the private function signature to:

```kotlin
private suspend fun maybeProcessUamInferenceCycle(
    settings: AppSettings,
    nowTs: Long,
    glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
    therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
    profile: ProfileEstimate?,
    calculatedSnapshot: CalculatedUamSnapshot,
    sensorBlocked: Boolean
): UamInferenceCycleResult?
```

Pass it into the coordinator:

```kotlin
config = UamExportCoordinator.Config(
    enableUamExportToAaps = settings.enableUamExportToAaps,
    sensorBlocked = sensorBlocked,
    exportMode = settings.uamExportMode,
    dryRunExport = settings.dryRunExport,
    minSnackG = settings.uamMinSnackG,
    maxSnackG = settings.uamMaxSnackG,
    snackStepG = settings.uamSnackStepG,
    exportMinIntervalMin = settings.uamExportMinIntervalMin,
    exportMaxBackdateMin = settings.uamExportMaxBackdateMin,
    calculatedCarbsGrams = calculatedSnapshot.estimatedCarbsGrams,
    calculatedToOriginalMultiplier = CALCULATED_TO_ORIGINAL_MULTIPLIER
)
```

Do not return early from inference. Only outbound export is disabled.

- [ ] **Step 5: Run GREEN and existing UAM tests**

Run the Task 3 command again. Expected: all UAM coordinator tests pass,
`fetchCalls == 1`, and blocked cycles have no posts.

- [ ] **Step 6: Preserve the dirty worktree boundary**

Inspect:

```bash
git diff -- android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt
git diff -- android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/UamExportCoordinator.kt
git diff -- android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/UamExportCoordinatorTest.kt
```

Do not commit Task 3 in the current tree because its complete implementation
includes the pre-existing dirty `AutomationRepository.kt`. Leave all Task 3
edits unstaged and report that constraint.

### Task 4: Add conservative pure raw-CGM matching

**Files:**
- Create: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuard.kt`
- Create: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuardTest.kt`

- [ ] **Step 1: Write raw-matching tests**

Create the test file with:

```kotlin
package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test

class GlucoseCalibrationGuardTest {

    @Test
    fun interpolatesNormalFiveMinuteBracket() {
        val target = 1_000_000L
        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                point(target - 2 * MINUTE_MS, 8.0),
                point(target + 3 * MINUTE_MS, 10.0)
            ),
            targetTs = target
        )

        assertThat(matched).isNotNull()
        assertThat(matched!!.ts).isEqualTo(target)
        assertThat(matched.valueMmol).isWithin(1e-9).of(8.8)
    }

    @Test
    fun rejectsNineMinuteFalseLowBracket() {
        val target = 1_000_000L
        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                point(target - 5 * MINUTE_MS, 3.0),
                point(target + 4 * MINUTE_MS, 8.0)
            ),
            targetTs = target
        )

        assertThat(matched).isNull()
    }

    @Test
    fun rejectsNearestSampleBeyondThreeMinutes() {
        val target = 1_000_000L
        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(point(target - 4 * MINUTE_MS, 8.0)),
            targetTs = target
        )

        assertThat(matched).isNull()
    }

    private fun point(ts: Long, mmol: Double) = GlucosePoint(
        ts = ts,
        valueMmol = mmol,
        source = "test"
    )

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.data.repository.GlucoseCalibrationGuardTest' --console=plain --no-daemon
```

Expected: compilation fails because `GlucoseCalibrationGuard` does not exist.

- [ ] **Step 3: Implement only bounded matching**

Create the production file with:

```kotlin
package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.GlucosePoint
import kotlin.math.abs

internal object GlucoseCalibrationGuard {

    fun matchRawGlucoseAt(
        glucose: List<GlucosePoint>,
        targetTs: Long
    ): GlucosePoint? {
        val sorted = GlucoseSanitizer.filterPoints(glucose)
            .filter { it.valueMmol.isFinite() && it.valueMmol > 0.0 }
            .sortedBy { it.ts }
        val exact = sorted.firstOrNull { it.ts == targetTs }
        if (exact != null) return exact

        val before = sorted.lastOrNull { it.ts < targetTs }
        val after = sorted.firstOrNull { it.ts > targetTs }
        if (before != null && after != null) {
            val beforeDelta = targetTs - before.ts
            val afterDelta = after.ts - targetTs
            val totalGap = after.ts - before.ts
            if (beforeDelta <= INTERPOLATION_MAX_SIDE_MS &&
                afterDelta <= INTERPOLATION_MAX_SIDE_MS &&
                totalGap in 1..INTERPOLATION_MAX_TOTAL_GAP_MS
            ) {
                val ratio = beforeDelta.toDouble() / totalGap.toDouble()
                return before.copy(
                    ts = targetTs,
                    valueMmol = before.valueMmol +
                        (after.valueMmol - before.valueMmol) * ratio
                )
            }
        }

        val nearest = sorted.minByOrNull { abs(it.ts - targetTs) } ?: return null
        return nearest
            .takeIf { abs(it.ts - targetTs) <= NEAREST_MATCH_MAX_MS }
            ?.copy(ts = targetTs)
    }

    private const val INTERPOLATION_MAX_SIDE_MS = 6L * 60_000L
    private const val INTERPOLATION_MAX_TOTAL_GAP_MS = 7L * 60_000L
    private const val NEAREST_MATCH_MAX_MS = 3L * 60_000L
}
```

- [ ] **Step 4: Run GREEN**

Run the Task 4 command again. Expected: all three matching tests pass.

- [ ] **Step 5: Commit the isolated pure component**

These files are newly created by this task and can be committed independently:

```bash
git add android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuard.kt android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuardTest.kt
git diff --cached --check
git commit -m "fix: bound lag-aligned glucose matching"
```

### Task 5: Assess checks at the aligned timestamp and wire the repository

**Files:**
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuardTest.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuard.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationRepository.kt`

- [ ] **Step 1: Add aligned-trust and mismatch tests**

Add imports:

```kotlin
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
```

Add these tests:

```kotlin
@Test
fun readsTrustAtLagAlignedTimestamp() {
    val bloodTs = 1_000_000L
    var queriedTs: Long? = null
    val assessment = GlucoseCalibrationGuard.assess(
        sessionKey = "sensor-1",
        bloodTs = bloodTs,
        bloodMmol = 10.8,
        lagMinutes = 16.0,
        rawGlucose = listOf(point(bloodTs + 16 * MINUTE_MS, 10.6)),
        telemetryAt = { timestamp ->
            queriedTs = timestamp
            safeTrust()
        }
    )

    assertThat(queriedTs).isEqualTo(bloodTs + 16 * MINUTE_MS)
    assertThat(assessment.status).isEqualTo(BloodGlucoseCheckStatus.VALID)
}

@Test
fun rejectsBlockedAlignedTrust() {
    val assessment = assessment(
        bloodMmol = 10.8,
        rawMmol = 10.6,
        trust = safeTrust().copy(sensorBlocked = true)
    )

    assertThat(assessment.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
    assertThat(assessment.reason).isEqualTo("sensor_blocked_aligned")
}

@Test
fun rejectsAbsoluteGapAboveThreeMmol() {
    val assessment = assessment(bloodMmol = 11.0, rawMmol = 7.8)

    assertThat(assessment.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
    assertThat(assessment.reason).isEqualTo("raw_blood_gap_extreme")
}

@Test
fun rejectsRelativeGapAboveFortyPercent() {
    val assessment = assessment(bloodMmol = 4.0, rawMmol = 6.8)

    assertThat(assessment.absoluteGapMmol).isWithin(1e-9).of(2.8)
    assertThat(assessment.relativeGap).isGreaterThan(0.40)
    assertThat(assessment.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
}

private fun assessment(
    bloodMmol: Double,
    rawMmol: Double,
    trust: CalibrationTrustSnapshot = safeTrust()
): CalibrationCheckAssessment {
    val bloodTs = 1_000_000L
    return GlucoseCalibrationGuard.assess(
        sessionKey = "sensor-1",
        bloodTs = bloodTs,
        bloodMmol = bloodMmol,
        lagMinutes = 10.0,
        rawGlucose = listOf(point(bloodTs + 10 * MINUTE_MS, rawMmol)),
        telemetryAt = { trust }
    )
}

private fun safeTrust() = CalibrationTrustSnapshot(
    sensorBlocked = false,
    sensorSuspectFalseLow = false,
    stale = false
)
```

- [ ] **Step 2: Run RED**

Run the Task 4 focused command. Expected: compilation fails because the
assessment types and `assess` method do not exist.

- [ ] **Step 3: Add the pure assessment contract**

Add these types above the object:

```kotlin
internal data class CalibrationTrustSnapshot(
    val sensorBlocked: Boolean,
    val sensorSuspectFalseLow: Boolean,
    val stale: Boolean
)

internal data class CalibrationCheckAssessment(
    val lagAlignedTs: Long,
    val matchedRaw: GlucosePoint?,
    val status: BloodGlucoseCheckStatus,
    val reason: String,
    val absoluteGapMmol: Double?,
    val relativeGap: Double?,
    val alignedTrust: CalibrationTrustSnapshot
)
```

Add imports:

```kotlin
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import kotlin.math.max
```

Add this method to `GlucoseCalibrationGuard`:

```kotlin
fun assess(
    sessionKey: String?,
    bloodTs: Long,
    bloodMmol: Double,
    lagMinutes: Double,
    rawGlucose: List<GlucosePoint>,
    telemetryAt: (Long) -> CalibrationTrustSnapshot
): CalibrationCheckAssessment {
    val lagAlignedTs = bloodTs +
        (lagMinutes.coerceIn(0.0, 20.0) * 60_000.0).toLong()
    val matchedRaw = matchRawGlucoseAt(rawGlucose, lagAlignedTs)
    val alignedTrust = telemetryAt(lagAlignedTs)
    val absoluteGap = matchedRaw?.let { abs(bloodMmol - it.valueMmol) }
    val relativeGap = matchedRaw?.let {
        absoluteGap!! / max(max(abs(bloodMmol), abs(it.valueMmol)), 1.0)
    }

    val (status, reason) = when {
        sessionKey.isNullOrBlank() ->
            BloodGlucoseCheckStatus.OUT_OF_WINDOW to "sensor_session_unresolved"
        alignedTrust.sensorBlocked || alignedTrust.sensorSuspectFalseLow ->
            BloodGlucoseCheckStatus.REJECTED to "sensor_blocked_aligned"
        matchedRaw == null ->
            BloodGlucoseCheckStatus.OUT_OF_WINDOW to "aligned_glucose_gap"
        alignedTrust.stale ->
            BloodGlucoseCheckStatus.STALE to "stale_sensor_context"
        absoluteGap!! > MAX_ABSOLUTE_GAP_MMOL || relativeGap!! > MAX_RELATIVE_GAP ->
            BloodGlucoseCheckStatus.REJECTED to "raw_blood_gap_extreme"
        else -> BloodGlucoseCheckStatus.VALID to "aligned"
    }

    return CalibrationCheckAssessment(
        lagAlignedTs = lagAlignedTs,
        matchedRaw = matchedRaw,
        status = status,
        reason = reason,
        absoluteGapMmol = absoluteGap,
        relativeGap = relativeGap,
        alignedTrust = alignedTrust
    )
}

private const val MAX_ABSOLUTE_GAP_MMOL = 3.0
private const val MAX_RELATIVE_GAP = 0.40
```

- [ ] **Step 4: Replace both repository assessment paths**

Add this repository helper:

```kotlin
private fun assessCalibrationCheck(
    sessionKey: String?,
    timestamp: Long,
    bloodMmol: Double,
    lagMinutes: Double,
    rawGlucose: List<GlucosePoint>,
    telemetry: CalibrationTelemetryIndex
): CalibrationCheckAssessment = GlucoseCalibrationGuard.assess(
    sessionKey = sessionKey,
    bloodTs = timestamp,
    bloodMmol = bloodMmol,
    lagMinutes = lagMinutes,
    rawGlucose = rawGlucose,
    telemetryAt = { alignedTs ->
        val snapshot = telemetrySnapshotAt(alignedTs, telemetry)
        CalibrationTrustSnapshot(
            sensorBlocked = snapshot.sensorBlocked,
            sensorSuspectFalseLow = snapshot.sensorSuspectFalseLow,
            stale = snapshot.stale
        )
    }
)
```

In `addManualBloodGlucoseCheck`, replace direct interpolation/status logic with:

```kotlin
val assessment = assessCalibrationCheck(
    sessionKey = sessionKey,
    timestamp = timestamp,
    bloodMmol = mmol,
    lagMinutes = lagMinutes,
    rawGlucose = fitContext.glucose,
    telemetry = fitContext.telemetry
)
```

Construct the check from:

```kotlin
lagAlignedTs = assessment.lagAlignedTs,
matchedRawGlucose = assessment.matchedRaw?.valueMmol,
status = assessment.status,
reason = assessment.reason
```

Replace the audit event selector and metadata so no removed local variable is
referenced:

```kotlin
auditLogger.info(
    if (check.status == BloodGlucoseCheckStatus.VALID) {
        "blood_glucose_check_added"
    } else {
        "blood_glucose_check_rejected"
    },
    mapOf(
        "id" to check.id,
        "mmol" to check.mmol,
        "units" to check.units,
        "status" to check.status.name,
        "reason" to check.reason,
        "sensorSessionKey" to (check.sensorSessionKey ?: "missing"),
        "bloodTs" to check.timestamp,
        "lagAlignedTs" to assessment.lagAlignedTs,
        "resolvedLagMinutes" to lagMinutes,
        "matchedRawGlucose" to assessment.matchedRaw?.valueMmol,
        "absoluteGapMmol" to assessment.absoluteGapMmol,
        "relativeGap" to assessment.relativeGap,
        "alignedSensorBlocked" to assessment.alignedTrust.sensorBlocked,
        "alignedSensorSuspectFalseLow" to assessment.alignedTrust.sensorSuspectFalseLow,
        "alignedTelemetryStale" to assessment.alignedTrust.stale
    )
)
```

In `reassessCheck`, call the same helper and return:

```kotlin
val assessment = assessCalibrationCheck(
    sessionKey = sessionKey,
    timestamp = check.timestamp,
    bloodMmol = check.mmol,
    lagMinutes = lagMinutes,
    rawGlucose = rawGlucose,
    telemetry = telemetry
)
return check.copy(
    sensorSessionKey = sessionKey,
    lagAlignedTs = assessment.lagAlignedTs,
    matchedRawGlucose = assessment.matchedRaw?.valueMmol,
    status = assessment.status,
    reason = assessment.reason
)
```

Remove the repository's old `interpolateRawGlucoseAt` function and these old
constants:

```kotlin
MAX_ALLOWED_BLOOD_SENSOR_DELTA_MMOL
INTERPOLATION_MAX_SIDE_MS
INTERPOLATION_MAX_TOTAL_GAP_MS
NEAREST_MATCH_MAX_MS
```

- [ ] **Step 5: Run GREEN and repository tests**

Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.data.repository.GlucoseCalibrationGuardTest' --tests 'io.aaps.copilot.data.repository.GlucoseCalibrationRepositoryTest' --console=plain --no-daemon
```

Expected: all calibration tests pass.

- [ ] **Step 6: Preserve the untracked repository boundary**

Do not stage or commit Task 5 as a complete task: the existing
`GlucoseCalibrationRepository.kt` is an untracked pre-existing file. Keep the
new diff in the workspace and report it separately.

### Task 6: Use aligned quality for model fitting and retire invalid models

**Files:**
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuardTest.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuard.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationRepository.kt`

- [ ] **Step 1: Add quality-timestamp and retirement tests**

Add imports:

```kotlin
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
```

Add tests:

```kotlin
@Test
fun qualityTimestampPrefersLagAlignedTime() {
    val check = bloodCheck(timestamp = 1_000L, lagAlignedTs = 61_000L)

    assertThat(calibrationQualityTimestamp(check)).isEqualTo(61_000L)
}

@Test
fun qualityTimestampFallsBackForLegacyCheck() {
    val check = bloodCheck(timestamp = 1_000L, lagAlignedTs = null)

    assertThat(calibrationQualityTimestamp(check)).isEqualTo(1_000L)
}

@Test
fun retiresOnlyNonRetiredModelsInInvalidSession() {
    val active = model(id = "active", session = "sensor-1", status = GlucoseCalibrationModelStatus.ACTIVE)
    val shadow = model(id = "shadow", session = "sensor-1", status = GlucoseCalibrationModelStatus.SHADOW)
    val retired = model(id = "retired", session = "sensor-1", status = GlucoseCalibrationModelStatus.RETIRED)
    val other = model(id = "other", session = "sensor-2", status = GlucoseCalibrationModelStatus.ACTIVE)

    val result = retireCalibrationModelsForInvalidFit(
        models = listOf(active, shadow, retired, other),
        sensorSessionKey = "sensor-1",
        retiredAt = 50_000L
    )

    assertThat(result.map { it.id }).containsExactly("active", "shadow")
    assertThat(result.all { it.status == GlucoseCalibrationModelStatus.RETIRED }).isTrue()
    assertThat(result.all { it.validToTs == 50_000L }).isTrue()
}
```

Add complete fixture helpers:

```kotlin
private fun bloodCheck(timestamp: Long, lagAlignedTs: Long?) = BloodGlucoseCheck(
    id = "check-1",
    timestamp = timestamp,
    mmol = 8.0,
    units = "mmol/L",
    source = "MANUAL",
    note = null,
    enteredAt = timestamp,
    sensorSessionKey = "sensor-1",
    lagAlignedTs = lagAlignedTs,
    matchedRawGlucose = 7.8,
    status = BloodGlucoseCheckStatus.VALID,
    reason = "aligned"
)

private fun model(
    id: String,
    session: String,
    status: GlucoseCalibrationModelStatus
) = GlucoseCalibrationModel(
    id = id,
    sensorSessionKey = session,
    createdAt = 1_000L,
    validFromTs = 1_000L,
    validToTs = 100_000L,
    modelType = GlucoseCalibrationModelType.OFFSET,
    gain = 1.0,
    offsetMmol = 0.2,
    confidence = 0.8,
    checkCount = 2,
    sensorAgeHours = 120.0,
    lagMinutesAtFit = 10.0,
    status = status,
    diagnosticsJson = "{}"
)
```

- [ ] **Step 2: Run RED**

Run the focused guard test. Expected: compilation fails because both helper
functions are absent.

- [ ] **Step 3: Implement the pure lifecycle helpers**

Add below the object:

```kotlin
internal fun calibrationQualityTimestamp(check: BloodGlucoseCheck): Long =
    check.lagAlignedTs ?: check.timestamp

internal fun retireCalibrationModelsForInvalidFit(
    models: List<GlucoseCalibrationModel>,
    sensorSessionKey: String,
    retiredAt: Long
): List<GlucoseCalibrationModel> = models
    .asSequence()
    .filter { it.sensorSessionKey == sensorSessionKey }
    .filter { it.status != GlucoseCalibrationModelStatus.RETIRED }
    .map { model ->
        model.copy(
            status = GlucoseCalibrationModelStatus.RETIRED,
            validToTs = minOf(model.validToTs, retiredAt)
        )
    }
    .toList()
```

Add required imports for the three model types.

- [ ] **Step 4: Use aligned timestamps for every fitting quality lookup**

Replace weighting with one snapshot:

```kotlin
val weightedChecks = checks.map { check ->
    val telemetryPoint = telemetrySnapshotAt(
        calibrationQualityTimestamp(check),
        telemetry
    )
    val recencyWeight = recencyWeight(nowTs = nowTs, ts = check.timestamp)
    val weight = (
        recencyWeight *
            (0.45 + telemetryPoint.sensorQualityScore * 0.55) *
            telemetryPoint.trendConsistency
        ).coerceIn(0.1, 1.0)
    WeightedCheck(check = check, weight = weight)
}
```

Replace session-age lookup with:

```kotlin
val values = checks.mapNotNull { check ->
    telemetrySnapshotAt(calibrationQualityTimestamp(check), telemetry).sensorAgeHours
}
```

- [ ] **Step 5: Retire invalid-session models immediately**

Add:

```kotlin
private suspend fun retireInvalidCalibrationModels(
    sensorSessionKey: String,
    nowTs: Long
) {
    val dao = db.glucoseCalibrationModelDao()
    val retiredOtherSessions = dao.retireOtherSessions(
        sensorSessionKey = sensorSessionKey,
        retiredAt = nowTs
    )
    val existing = dao
        .bySensorSession(sensorSessionKey)
        .map { it.toDomain() }
    val retired = retireCalibrationModelsForInvalidFit(
        models = existing,
        sensorSessionKey = sensorSessionKey,
        retiredAt = nowTs
    )
    retired.forEach { model ->
        dao.upsert(model.toEntity())
    }
    if (retiredOtherSessions > 0 || retired.isNotEmpty()) {
        auditLogger.warn(
            "glucose_calibration_models_retired_invalid_checks",
            mapOf(
                "sensorSessionKey" to sensorSessionKey,
                "retiredCurrentSession" to retired.size,
                "retiredOtherSessions" to retiredOtherSessions,
                "retiredAt" to nowTs
            )
        )
    }
}
```

In the `fit.validChecks.isEmpty() || fit.model == null` branch, call it before
updating the memo:

```kotlin
retireInvalidCalibrationModels(
    sensorSessionKey = latestSessionKey,
    nowTs = nowTs
)
refreshMemo = CalibrationRefreshMemo(
    computedAt = nowTs,
    latestCheckTs = latestCheckTs,
    modelId = null,
    model = null
)
return null
```

- [ ] **Step 6: Audit reassessment transitions without high-frequency logs**

After reassessment and before persistence, identify only changed checks:

```kotlin
val changedChecks = sessionChecks.zip(reassessed)
    .filter { (before, after) ->
        before.status != after.status ||
            before.reason != after.reason ||
            before.lagAlignedTs != after.lagAlignedTs ||
            before.matchedRawGlucose != after.matchedRawGlucose
    }

changedChecks.forEach { (before, after) ->
    val absoluteGap = after.matchedRawGlucose?.let { abs(after.mmol - it) }
    val relativeGap = after.matchedRawGlucose?.let {
        absoluteGap!! / max(max(abs(after.mmol), abs(it)), 1.0)
    }
    val alignedTelemetry = telemetrySnapshotAt(
        calibrationQualityTimestamp(after),
        telemetry
    )
    auditLogger.warn(
        "blood_glucose_check_reassessed",
        mapOf(
            "id" to after.id,
            "bloodTs" to after.timestamp,
            "lagAlignedTs" to after.lagAlignedTs,
            "resolvedLagMinutes" to after.lagAlignedTs?.let {
                (it - after.timestamp) / 60_000.0
            },
            "previousStatus" to before.status.name,
            "status" to after.status.name,
            "reason" to after.reason,
            "matchedRawGlucose" to after.matchedRawGlucose,
            "absoluteGapMmol" to absoluteGap,
            "relativeGap" to relativeGap,
            "alignedSensorQualityScore" to alignedTelemetry.sensorQualityScore,
            "alignedSensorBlocked" to alignedTelemetry.sensorBlocked,
            "alignedSensorSuspectFalseLow" to alignedTelemetry.sensorSuspectFalseLow,
            "alignedTelemetryStale" to alignedTelemetry.stale
        )
    )
}
```

This logs only state transitions during model refresh, not every telemetry
sample or automation cycle.

- [ ] **Step 7: Run GREEN and check for old timestamp usage**

Run the Task 5 focused test command. Then run:

```bash
rg -n 'telemetrySnapshotAt\(check\.timestamp|INTERPOLATION_MAX_SIDE_MS|MAX_ALLOWED_BLOOD_SENSOR_DELTA_MMOL' android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationRepository.kt
```

Expected: tests pass and `rg` returns no matches.

- [ ] **Step 8: Preserve the dirty/untracked boundary**

Do not commit Task 6 as a complete task in this worktree. The repository file
predates this plan as untracked, so automatic staging would capture unrelated
work.

### Task 7: Add a synthetic P0 replay and run full local verification

**Files:**
- Create: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/SensorTrustP0ReplayTest.kt`

- [ ] **Step 1: Add a date-free, non-identifying replay test**

Create:

```kotlin
package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.SegmentProfileGuardRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.domain.safety.SafetyPolicyConfig
import org.junit.Test

class SensorTrustP0ReplayTest {

    @Test
    fun unsafeSensorShapeCannotCalibrateOrDispatchFallback() {
        val bloodTs = 1_000_000L
        val alignedTs = bloodTs + 10 * 60_000L
        val calibration = GlucoseCalibrationGuard.assess(
            sessionKey = "synthetic-session",
            bloodTs = bloodTs,
            bloodMmol = 11.0,
            lagMinutes = 10.0,
            rawGlucose = listOf(
                GlucosePoint(alignedTs - 5 * 60_000L, 3.0, "synthetic"),
                GlucosePoint(alignedTs + 4 * 60_000L, 8.0, "synthetic")
            ),
            telemetryAt = {
                CalibrationTrustSnapshot(
                    sensorBlocked = true,
                    sensorSuspectFalseLow = true,
                    stale = false
                )
            }
        )
        val rollbackAllowed = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 8.0,
            baseTargetMmol = 5.5,
            assessment = AutomationRepository.SensorQualityAssessment(
                score = 0.2,
                blocked = true,
                reason = "synthetic_rapid_delta",
                suspectFalseLow = false,
                delta5Mmol = 2.0,
                noiseStd5Mmol = 0.8,
                gapMinutes = 1.0
            )
        )

        val ruleDecision = RuleEngine(
            rules = listOf(SegmentProfileGuardRule()),
            safetyPolicy = SafetyPolicy()
        ).evaluate(
            context = RuleContext(
                nowTs = 2_000_000L,
                glucose = emptyList(),
                therapyEvents = emptyList(),
                forecasts = emptyList(),
                currentDayPattern = null,
                baseTargetMmol = 5.5,
                dataFresh = true,
                activeTempTargetMmol = null,
                actionsLast6h = 0,
                sensorBlocked = true,
                currentProfileEstimate = ProfileEstimate(
                    2.0, 10.0, 0.8, 20, 10, 10, 14
                ),
                currentProfileSegment = ProfileSegmentEstimate(
                    DayType.WEEKDAY,
                    ProfileTimeSlot.NIGHT,
                    3.0,
                    10.0,
                    0.8,
                    10,
                    10,
                    14
                )
            ),
            config = SafetyPolicyConfig(killSwitch = false)
        ).single()

        assertThat(calibration.status).isNotEqualTo(BloodGlucoseCheckStatus.VALID)
        assertThat(rollbackAllowed).isTrue()
        assertThat(ruleDecision.state).isEqualTo(RuleState.BLOCKED)
        assertThat(ruleDecision.actionProposal).isNull()
        assertThat(ruleDecision.reasons).contains("sensor_blocked")
    }
}
```

The fixture is synthetic and rounded. Do not copy timestamps or raw values from
the device database into source control.

- [ ] **Step 2: Run the replay test**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.data.repository.SensorTrustP0ReplayTest' --console=plain --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Run all focused P0 tests together**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.safety.SafetyPolicyTest' --tests 'io.aaps.copilot.domain.rules.RuleEngineRuntimeConfigTest' --tests 'io.aaps.copilot.data.repository.UamExportCoordinatorTest' --tests 'io.aaps.copilot.data.repository.GlucoseCalibrationGuardTest' --tests 'io.aaps.copilot.data.repository.GlucoseCalibrationRepositoryTest' --tests 'io.aaps.copilot.data.repository.SensorTrustP0ReplayTest' --console=plain --no-daemon
```

Expected: all focused classes pass.

- [ ] **Step 4: Run the complete unit suite**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 5: Build the debug APK**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL` and
`app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Audit the complete outbound path inventory**

Run:

```bash
rg -n 'submitTempTarget\(|submitCarbs\(|postCarbEntry\(' app/src/main/kotlin/io/aaps/copilot
```

Confirm:

- rule proposals pass `SafetyPolicy`;
- adaptive keepalive checks `sensorBlocked`;
- UAM posts are blocked by `Config.sensorBlocked`;
- sensor-quality rollback is the only automatic blocked-sensor exception;
- UI and explicit broadcast calls remain manual paths.

- [ ] **Step 7: Review the complete diff without staging user work**

Run from repository root:

```bash
git diff --check
git status --short
git diff -- android-app/app/src/main/kotlin/io/aaps/copilot/domain/safety/SafetyPolicy.kt android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/RuleEngine.kt android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/UamExportCoordinator.kt android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationGuard.kt android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseCalibrationRepository.kt
```

Expected: no whitespace errors and no unrelated files changed by this plan.

### Task 8: Install, verify runtime safety, and check resource impact

**Files:**
- No source changes.
- Local evidence only under `/Users/mac/Andoidaps/artifacts/`; do not commit
  device databases or health logs.

- [ ] **Step 1: Confirm the connected device and installed package**

Run separately:

```bash
adb devices -l
adb shell dumpsys package io.aaps.predictivecopilot
```

Expected: device `J7EYCEEE7TK74XAQ` is online and the package is present. Stop
if the device or package identity differs.

- [ ] **Step 2: Install the verified debug APK without clearing data**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run from `android-app`. Expected: `Success`. Do not use `-d`, uninstall, clear
data, or replace the signing key.

- [ ] **Step 3: Verify process and bounded safety logs**

Run separately:

```bash
adb shell monkey -p io.aaps.predictivecopilot -c android.intent.category.LAUNCHER 1
adb shell pidof io.aaps.predictivecopilot
adb logcat -d --pid="$(adb shell pidof io.aaps.predictivecopilot | tr -d '\r')"
```

Expected: the process is running and no crash loop is present. Do not expect
Room audit rows in Logcat, and do not trigger a real therapy action for testing.

- [ ] **Step 4: Verify UAM and calibration evidence passively**

Observe at least one natural automation cycle, then create a stopped, read-only
database copy using the same package-private export method used by the audit:

```bash
mkdir -p /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710
chmod 700 /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710
adb shell am force-stop io.aaps.predictivecopilot
adb exec-out run-as io.aaps.predictivecopilot sh -c 'cd databases && tar -czf - copilot.db copilot.db-wal copilot.db-shm' > /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710/copilot.tar.gz
chmod 600 /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710/copilot.tar.gz
tar -xzf /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710/copilot.tar.gz -C /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710
sqlite3 /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710/copilot.db 'PRAGMA integrity_check;'
sqlite3 /Users/mac/Andoidaps/artifacts/sensor-trust-p0-device-20260710/copilot.db "SELECT timestamp, message, metadataJson FROM audit_logs WHERE message IN ('sensor_quality_gate_blocked','sensor_quality_rollback_sent','blood_glucose_check_rejected','blood_glucose_check_reassessed','glucose_calibration_models_retired_invalid_checks') ORDER BY timestamp DESC LIMIT 100;"
adb shell monkey -p io.aaps.predictivecopilot -c android.intent.category.LAUNCHER 1
```

Expected: `PRAGMA integrity_check` returns `ok`. Confirm audit records show:

- internal UAM inference may continue while blocked;
- no UAM carbohydrate export occurs while blocked;
- rejected calibration records include blood and aligned timestamps plus the
  bounded mismatch fields;
- no active model remains based only on newly invalid checks.

- [ ] **Step 5: Measure CPU and memory without adding instrumentation**

Take three idle samples at least one minute apart:

```bash
adb shell dumpsys cpuinfo
adb shell dumpsys meminfo io.aaps.predictivecopilot
adb shell dumpsys batterystats io.aaps.predictivecopilot
```

Compare with the pre-change evidence under
`/Users/mac/Andoidaps/artifacts/perf-energy-20260623-002516/`. Expected: no new
worker/wake-lock activity, no sustained CPU increase, and no material retained
memory increase. Record exact values and observation duration; do not infer
battery improvement from a single sample.

- [ ] **Step 6: Final safety review**

Confirm all acceptance criteria from the approved design, list every touched
file, list any edits intentionally left uncommitted because of the dirty base,
and report test/build/device evidence. Do not describe the app as diagnosing a
sensor or infusion-set failure and do not provide dosing advice.
