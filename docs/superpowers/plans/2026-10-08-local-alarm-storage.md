# Durable Local Alarm Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Commit guarded local-cycle claims, state and independent channel outcomes before a later runtime executor performs any side effect.

**Architecture:** Add two Room tables and a bounded versioned codec. A transactional store reuses LocalAlarmPolicy and the existing database-scoped mute mutex; it never converts persisted authority into fresh accepted evidence. This independently testable storage stage does not start services, notify, play, vibrate, forecast or dispatch therapy.

**Tech Stack:** Existing Room 2.8.4, Gson 2.13.2, Kotlin coroutines, JUnit/Truth/Robolectric, Java17/SDK36.

## Scope and Baseline

The human asked to check the phone and continue development. Fresh ADB is empty;
Mac USB enumeration sees a storage device, not the phone. A reconnection question
is pending. Phone history/settings/audio/resource acceptance remain separate.
Reuse the existing linked feature worktree, clean at964f5f2b. Its local quality
and both exact-source Verify runs passed in the previous kernel stage; no new
dependency, worktree, background task or clinical algorithm is introduced.

This is the persistence part of the Oct7 design, not completed Android delivery.
Room31->32 is additive; clinical receipts, data and settings remain unchanged.
No APK installation or downgrade is authorized by a source commit. Foreground,
source adapters, executor/leases, UI and existing-housekeeping wiring are later
integration work, not placeholder methods in this stage.

## File Responsibilities

- Create `data/local/entity/AlertLocalStateEntity.kt`: state and numbered-cycle rows.
- Create `data/local/dao/AlertLocalDao.kt`: exact-key reads, revision CAS, unique claims, indexed bounded scheduling reads and terminal-row pruning.
- Modify `data/local/CopilotDatabase.kt`, `CopilotMigrations.kt`: entities/DAO and explicit31->32 migration.
- Create `domain/alerts/LocalAlarmPersistenceCodec.kt`: strict bounded state/outcome formats and channel-result value objects.
- Modify `domain/alerts/LocalAlarmPolicy.kt`: expose its existing structural state validator internally without changing clinical or admission behavior.
- Create `data/repository/RoomLocalAlarmStore.kt`: committed evaluate/recover/acknowledge/result/finish transitions with current Room mute and post-lock clocks.
- Create tests `data/local/LocalAlarmRoomMigrationTest.kt`, `domain/alerts/LocalAlarmPersistenceCodecTest.kt`, `data/repository/RoomLocalAlarmStoreTest.kt`.
- Update the six existing migration-test schema-head expectations only, plus PLAN/AI_NOTES/ARCHITECTURE/INVARIANTS/DEVOPS/SECURITY_REVIEW.
- Update the existing AlertsArchitectureTest head expectation after its actual
  full-suite31-vs32 failure; retain network/worker bans and enforce no journal
  writer in AlertsRepository. No production read-path change.

Paths above are relative to `android-app/app/src/main/kotlin/io/aaps/copilot`;
test paths are under the corresponding `android-app/app/src/test/kotlin` tree.

## Task 1: Additive Schema

- [x] Write the real Room migration regression first. Seed current Room data,
  reopen through ALL migrations and expect32; inspect its initial31-vs32 failure.
- [x] Add state with compound key(sourceKind,sourceId), generation,bootCount,
  ordinal,revision,updatedAtMs,nextDueElapsedMs,pauseUntilWallMs,
  cycleDeadlineElapsedMs,stateJson. Scheduling indexes pair boot with each
  deadline; the payload is never loaded by a full-history scan.
- [x] Add cycles with compound key(sourceKind,sourceId,generation,ordinal),
  bootCount,level,startedElapsedMs,deadlineElapsedMs,claimedAtMs,terminalAtMs,
  status,resultJson. Index claimedAtMs and(status,claimedAtMs).
- [x] Register Room32 and ALL migration tail. Migration only creates these
  tables/indexes, never rewrites old rows or adds destructive fallback.
- [x] After schema exists, convert the synthetic seed to31 by dropping only the
  two new tables and setting user_version31. Room opening must validate32,
  preserve old medical/receipt rows, pass foreign_key_check/integrity_check and
  reopen. Update existing head assertions to32, not unrelated revision values.

Migration RED was expected32/actual31. The migration/regression selection then
passed8 cases across7 suites, no failures/errors, one optional phone-copy skip.
The DAO follows existing ABORT/IGNORE and explicit SQL patterns:

```kotlin
@Query("UPDATE alert_local_state SET revision=:nextRevision, stateJson=:json WHERE sourceKind=:kind AND sourceId=:id AND revision=:expectedRevision")
suspend fun compareAndSetPayload(kind: String, id: String, expectedRevision: Long, nextRevision: Long, json: String): Int
```

The implemented CAS updates the complete mirrored metadata in the same statement.
Every dynamic limit is clamped with MAX(1,MIN(:limit,64)); pruning uses at most100
terminal cycles. A claim/state write is one transaction; any collision rolls back.

## Task 2: Bounded Formats

- [x] Write codec tests before production: roundtrip active/paused states and
  four independent step outcomes; corrupt/unknown version, unknown/duplicate
  fields, wrong JSON type, fractional/overflow number, oversize and excess steps.
- [x] Run to the expected absent-API RED, then implement with the existing Gson
  strict streaming reader. State is a flat schemaVersion1 object, at most4096
  UTF-8 bytes. Outcomes are schemaVersion1 with at most4 step objects and2048
  UTF-8 bytes; no recursive unknown structure is parsed or silently skipped.
- [x] Reuse kernel structural validation; actual clock/boot/freshness still runs
  against fresh accepted evidence in the store. Decoding is not authorization.
- [x] Keep separate notification POSTED/BLOCKED/FAILED, audio STARTED/FAILED/
  UNAVAILABLE and vibration REQUESTED/FAILED/UNAVAILABLE. None means heard,
  acknowledged, successful insulin delivery or resolution.

```kotlin
val encoded = LocalAlarmPersistenceCodec.encodeState(state)!!
assertThat(LocalAlarmPersistenceCodec.decodeState(encoded)).isEqualTo(state)
assertThat(LocalAlarmPersistenceCodec.decodeState(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))).isNull()
```

## Task 3: Guarded Store

- [x] Write Room tests for two stores sharing one database, duplicate/due
  evidence, current Room mute vs a stale caller hint, post-lock clocks, ACK,
  cancellation, recovery, stale callbacks, corrupt mirrored metadata, write
  rollback and bounded retention preserving the ordinal.
- [x] Inspect the absent-store API RED; implement `RoomLocalAlarmStore(db,
  environmentProvider)` using EpisodeAlertOperationLocks and db.withTransaction.
- [x] Read environment after acquiring the operation mutex. Read authoritative
  global mute in that transaction; caller mute hints cannot bypass it. Require
  fresh accepted evidence for evaluation, ACK and step admission.
- [x] Return a START only after state/unique cycle commit. Update revision with
  checked addition and exact CAS. Invalid/newer payloads cannot reset ordinals
  or create claims. SQL errors return unavailable; cancellation propagates.
- [x] Recovery marks an interrupted claim UNCERTAIN, never replays its steps,
  and preserves due/ordinal while current fresh authority is reassessed.
- [x] Record a step only for the exact currently active cycle before absolute
  deadline. Duplicate step writes are idempotent; contradictory/stale results
  reject. Only explicit confirmed volume progress advances the kernel floor.
- [x] Finish/cancel marks a terminal cycle and clears active ownership without
  inventing an acknowledgement or resetting the next due. Prune only terminal
  cycles older than30 days in batches<=100; never delete ordinal state.
- [x] Run migration/codec/store, all kernel and existing receipt/mute regression
  suites; inspect counts and every failure rather than broadening runtime scope.

Focused94 cases across12 suites pass, zero failures/errors and one optional
phone-copy skip. The25 new cases include1 migration,4 codec and20 store tests.
Self-review added actual failing regressions for earlier-step idempotence,
future journal progress and later timing settings; each fix is covered by GREEN.

## Task 4: Quality and Publication

- [x] Self-review scope and clock/mute/transaction ordering; inspect diff, fixture
  neutrality and secrets. No actual player/service/UI/source/therapy consumer.
- [x] Run full Android unit/lint/compile/assemble, inspect fresh XML/report/APK.
- [x] Update required docs and native checkpoint with actual results and Room32
  forward-recovery/device limitations. No real-night or CPU savings claim.
Publication is a post-commit gate: fetch/check divergence, explicitly stage
reviewed files, commit/push the current feature branch and inspect exact-SHA
Verify/PR. Preserve worktree/PR. Its live outcomes are recorded in the matching
native checkpoint after the commit exists, not pre-claimed in this source file.

Full gate passed4900 cases /422 suites, failures0 errors0 skips3. The only first
full-suite failure was an obsolete31 architecture head; its five cases pass
after updating32 and strengthening the read-path bans. Lint report was explicitly
regenerated after cached reuse: errors0 warnings305 hints4, no alarm-file issues.
Candidate debug APK is built and includes the new code, not installed or enabled.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/mac/Library/Android/sdk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --no-daemon --max-workers=2 --console=plain
```

Migration guidance was checked against the official
[Room migration documentation](https://developer.android.com/training/data-storage/room/migrating-db-versions).
Use the project's existing Room2 APIs, not a Room3/dependency upgrade. A fresh
coherent phone backup and disposable migration/device acceptance are release
gates; an older schema31 APK cannot simply reopen a migrated32 database.
