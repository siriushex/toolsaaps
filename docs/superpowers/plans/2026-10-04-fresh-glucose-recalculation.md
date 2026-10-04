# Fresh Glucose Recalculation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recalculate accepted forecasts and reevaluate Target Manager promptly for each new current CGM observation without changing therapy policy.

**Architecture:** Reuse the transactional broadcast outbox and durable clinical input coordinator. Identify a current input mutation through GlucoseDao's existing valid/distinct source priority query. Reactive execution waits for the existing cycle lease, then reads current settings and inputs; periodic execution keeps its idle-only behavior.

**Tech Stack:** Kotlin, Room, coroutines, WorkManager, JUnit, Robolectric and Gradle.

## Task 1: Current Glucose Persistence

Files: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/BroadcastIngestRepository.kt`; `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/BroadcastIngestEventInvalidationRoomIntegrationTest.kt`; `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/BroadcastIngestRepositoryTest.kt`.

- [x] Add a real Room regression: ingest a moderate point, then a new moderate point one minute later; assert both committed mutations invoke invalidation. Run the class on unchanged production source and inspect the second callback failure. Two failures confirmed with zero errors and preserved private XML.
- [x] Add a transient `currentGlucoseChanged: Boolean = false` result field. Compare current valid rows before/after the mutation using the same ingest clock, ignoring generated row IDs:

```kotlin
val before = db.glucoseDao().latestValidDistinctAtOrBefore(ingestNow, 1).firstOrNull()
val after = db.glucoseDao().latestValidDistinctAtOrBefore(ingestNow, 1).firstOrNull()
currentGlucoseChanged = after != null && after.copy(id = 0L) != before?.copy(id = 0L)
```

The two reads bracket the existing accepted glucose write in the Room transaction. Pass the flag to the result; persist the existing outbox before transaction commit. Inject a default wall-clock function for deterministic causal-clock tests.

- [x] Replace only the glucose scheduling branch; retain the existing telemetry interval and successful-dispatch timestamp semantics:

```kotlin
if (result.therapyImported > 0) return true
if (result.glucoseImported > 0) return result.currentGlucoseChanged
if (result.telemetryImported <= 0) return false
if (!telemetryOnlyCoalescedAction) return true
return slotAvailable(lastTelemetryOnlyReactiveAtMs, telemetryIntervalMs)
```

- [x] Add duplicate, same-value/new-timestamp, current correction, lower-priority relay, historical, future and rollback Room tests. Update policy tests to assert that successive current observations do not wait four minutes and old glucose cannot use the critical-value bypass.
- [x] Run focused Room and policy tests and inspect their terminal XML results.

## Task 2: Reactive Cycle Ownership

Files: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`; `android-app/app/src/main/kotlin/io/aaps/copilot/scheduler/SyncAndAutomateWorker.kt`; `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/AutomationCyclePolicyTest.kt`; new `android-app/app/src/test/kotlin/io/aaps/copilot/scheduler/ReactiveCycleLeaseWiringTest.kt`.

- [x] Reproduce the old idle-only behavior under a held mutex: a concurrent normal invocation returns unacquired without reading a later input. Preserve that baseline test. The old worker binding independently failed before the route correction.
- [x] Add the cancellable owned-lease boundary and route only reactive work through it:

```kotlin
internal suspend fun <T> runReactiveCycleUnderLeaseStatic(
    mutex: Mutex,
    block: suspend () -> T
): T = mutex.withLock { block() }

internal suspend fun runReactiveCycle(intent: AutomationCycleIntent): SensitivityRuntimeSnapshot? {
    require(intent != AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE)
    return runReactiveCycleUnderLeaseStatic(cycleMutex) {
        runCycle(intent, cycleLeaseOwned = true)
    }
}
```

`runCycle` gains a private default-false owned-lease parameter. Its existing settings/policy/revision preparation runs after the reactive lease is acquired. Its calculation uses the owned lease directly only for that private entrypoint; other callers retain `runNormalCycleIfIdleStatic`. Worker calls `runReactiveCycle(intent)` inside the existing execution evidence/deadline boundary.

- [x] Test newest-input read after waiting, no overlapping blocks, cancellation while waiting, cancellation after acquisition and subsequent lease usability. Assert real worker source binds the reactive route without changing periodic routes or allowing sensitivity source-change intent.
- [x] Run related coordinator, transport, evidence, worker, automation and target-policy suites. Focused498 tests passed with zero failures/errors/skips.

## Task 3: Quality And Delivery

Files: `docs/INVARIANTS.md`, `docs/ARCHITECTURE.md`, `AI_NOTES.md`, and the approved spec/plan above.

- [x] Document event/lease contracts and actual verification results, keeping private device evidence out of version control.
- [x] Run `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin` with Java 17 and the existing Android SDK. Terminal result passed in10m08s:4787 tests,0 failures/errors,3 existing skips; lint0 errors,305 warnings/4 hints. Candidate certificate matches the installed package.
- [ ] Review explicit source diff and compare the tested copy to the primary worktree. Fetch/check divergence, publish explicit reviewed files on the existing feature branch and inspect Verify CI.
- [ ] Verify candidate certificate, capture a fresh coherent private backup with the tested helper, restore normal running state, install only Copilot in place and read back its hash/settings/data continuity.
- [ ] Observe natural cycles and inspect the actual UI. Measure fresh-input/accepted-forecast timing and confirm any natural target receipt independently in AAPS. Keep unobserved qualifying target response pending; do not infer clinical benefit or force therapy.
