# Local Alarm Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Implement the deterministic, side-effect-free delivery-policy kernel of the local alarm escalation design.

**Architecture:** Separate bounded profiles/volume targets from per-source admission and repeat/acknowledgement state. The kernel consumes already-authorized evidence and explicit clocks; it never detects glucose risk or performs Android, storage, network or therapy operations. A subsequent runtime stage will connect it to the admitted foreground owner and durable cycle claims; this stage cannot enable the feature.

**Tech Stack:** Kotlin/JVM, JUnit4, Truth, existing Android Gradle quality gates.

---

## Scope and authorization

The human's Oct8 request to continue application/algorithm development follows
the written Oct7 proposal. Proceed with repository implementation, not phone
activation or sound tests. Preserve the existing clinical engine, legacy initial
notifier, receipt semantics, Room31 and all Settings/manifest/therapy behavior.
The existing linked feature worktree is reused. Exact-source baseline push and
PR Verify both passed before editing; the phone is absent through ADB.

This is the first independently verifiable kernel stage, not a complete alarm
release. Service/Doze/volume ownership, source adapters, Room32 durable claims,
notification acknowledgement UI and device acceptance have separate runtime
integration gates in the design. There are no placeholder runtime methods.

## File Responsibilities

- Create `android-app/app/src/main/kotlin/io/aaps/copilot/domain/alerts/LocalAlarmModels.kt`: typed source, delivery level, accepted-evidence envelope, clocks, per-source state, claim and acknowledgement value objects.
- Create `android-app/app/src/main/kotlin/io/aaps/copilot/domain/alerts/LocalAlarmProfiles.kt`: four bounded steps, strong clip timing, repeat bounds, safe integer hardware-volume targets.
- Create `android-app/app/src/main/kotlin/io/aaps/copilot/domain/alerts/LocalAlarmPolicy.kt`: pure evaluate/acknowledge/recordReached transitions.
- Create `android-app/app/src/test/kotlin/io/aaps/copilot/domain/alerts/LocalAlarmProfilesTest.kt`: profile and hardware edge regressions.
- Create `android-app/app/src/test/kotlin/io/aaps/copilot/domain/alerts/LocalAlarmPolicyTest.kt`: source admission, repeats, OFF, acknowledgement and state corruption regressions.
- Modify `docs/PLAN.md`, `AI_NOTES.md` and this plan: actual scope, checks and remaining integration gates.

## Task 1: Bounded Audible Profiles

- [x] Write profile tests before production files. Example contract:

```kotlin
val profile = LocalAlarmProfiles.create(LocalAlarmLevel.WARNING_30, LocalAlarmTiming())!!
assertThat(profile.steps.map { it.offsetMs }).containsExactly(0L, 15_000L, 30_000L, 45_000L).inOrder()
assertThat(profile.steps.map { it.targetPercent }).containsExactly(25, 50, 75, 100).inOrder()
assertThat(profile.steps.all { it.startsClip }).isTrue()
assertThat(LocalAlarmProfiles.targetVolume(70, 7, 0)).isEqualTo(5)
assertThat(LocalAlarmProfiles.targetVolume(25, 7, 7)).isEqualTo(7)
```

- [x] Run the focused suite; inspect the expected missing new-API compilation failure, not a toolchain failure.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/mac/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.alerts.LocalAlarmProfilesTest --no-daemon --max-workers=2 --console=plain
```

- [x] Implement profiles and the value objects. Levels are NONE/WATCH_60 (silent), SOFT_HIGH_RISK/PUMP_LINK/DELIVERY_DIAGNOSTIC/WARNING_30 (soft), CRITICAL_5/LOW_NOW (strong). Priority is the written design order, not a new clinical severity rule.
- [x] Use soft targets25/50/75/100 at0/15/30/45s; strong targets at quarter-clip offsets with only the first starting a clip. LOW_NOW targets70/85/100/100 immediately preserve its safety floor. Apply the actually reached progression as a minimum, not an assumed100% outcome.
- [x] Reject invalid durations/repeats/progression; soft1-5s and5-30min, strong15-30s and1-10min, maximum cycle55s. Hardware targets use checked Long integer arithmetic:

```kotlin
if (percent !in 1..100 || maxVolume <= 0 || currentVolume !in 0..maxVolume) return null
return maxOf(currentVolume, ((maxVolume.toLong() * percent + 99L) / 100L).toInt())
```

- [x] Cover silence, all levels, timing extrema, Int.MAX_VALUE hardware range, corrupt bounds, progression, no volume reduction and legacy urgent-floor equivalence.
- [x] Run the focused suite to green and inspect its XML counts.

## Task 2: Guarded Per-Source Policy

- [x] Write tests first using a neutral episode-a fixture, boot7, elapsed1_000_000, wall10_000_000 and explicit validity bounds. Each test calls real pure policy functions.
- [x] `evaluate(evidence, previous, environment, timing)` returns state, optional START claim/profile, whether to cancel the previous active claim and a typed admission result. `acknowledge` receives an action bound to key/generation/ordinal/level. `recordReached` accepts only the current active claim and valid reached target.
- [x] Run the new focused suite; inspect the expected missing-policy API failure.
- [x] Implement these ordered transitions without any side effect:

| Order | Condition | Transition |
| --- | --- | --- |
| 1 | Invalid clocks/state/source key | Cancel active claim, no start; preserve the known ordinal when possible |
| 2 | Evidence unauthorized, wrong boot, future, expired or source/level mismatch | Cancel active, no start; no fabricated source authority |
| 3 | Silent level, disabled feature or unarmed runtime | Cancel active and next due; no start |
| 4 | OFF deadline strictly greater than current wall clock | Cancel active and next due; no start even for urgent low |
| 5 | New generation/boot | Clear pause/progression, retain per-source ordinal, cancel old claim |
| 6 | Higher current level | Invalidate source pause, cancel/preempt old level and admit current risk |
| 7 | Valid unexpired acknowledgement | Cancel active, no start; leave other sources untouched |
| 8 | Same valid claim before absolute55s deadline | No additional claim or duplicate ordinal |
| 9 | Future elapsed repeat deadline | Wait; duplicate evidence never slides the deadline |
| 10 | Due, including expiry/resume | Claim exactly one current cycle; next due is now+current repeat interval, not accumulated backlog |

- [x] Use exact expiry comparisons and checked addition for elapsed cycle/repeat and wall pause deadlines:

```kotlin
private fun addTime(now: Long, delay: Long): Long? =
    if (now < 0L || delay < 0L || now > Long.MAX_VALUE - delay) null else now + delay
```

- [x] Validate state/source consistency and bounds, reject future active starts/corrupt pause ranges/counter overflow. A changed wall clock does not reset elapsed repeats; acknowledgement keeps its explicit wall deadline. An actual boot change cannot reuse old evidence.
- [x] Acknowledge only the current admitted ordinal and level of fresh authority; no screen-navigation ACK. Pause for the current profile repeat interval. Clearing/cancellation never counts as resolution or as playback success.
- [x] Record progress only for the exact still-active claim; stale callbacks cannot advance a cancelled or newer source. Never advance progress merely because a timeline was created.
- [x] Test exact OFF/ACK boundaries, suppressed-source resume, duplicate evidence, early/late repeat, no backlog, stronger risk, stale ACK, old boot, invalid/future authority, overflow and late outcome callbacks.
- [x] Run both new suites plus existing alert engine, episode receipt/mute/urgent volume and pump policy suites to green.

## Task 3: Verification and Publication

- [x] Run full Android quality with Java17/SDK above:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --no-daemon --max-workers=2 --console=plain
```

- [x] Inspect test XML counts and terminal lint/build result. Distinguish unchanged warnings from new regressions. No phone-sound/resource/overnight-success claim is permitted.
- [x] Self-review state transitions and isolation: no Android/repository/therapy/network imports or singleton wiring; no new settings/schema/permission. Check diff whitespace, neutral fixtures and public content; final staging is separately verified below.
- [x] Update PLAN/AI_NOTES/checkpoints with exact results and remaining runtime requirements. Mark each step complete only after actual verification.
- [ ] Fetch/check divergence, stage explicit reviewed paths, commit, push the current feature branch and verify exact SHA/Verify/PR. No merge or deployment.

Local verification: focused 148 cases (52 new), zero failures/errors/skips.
Full suite: 4875 cases in 419 suites, zero failures/errors, 3 optional private
phone-copy fixture tests skipped. Full Android unit/lint/compile/assemble gate
passed. Fresh Lint: zero errors; 305 warnings and 4 hints outside the new files.
Both self-review behavior regressions were reproduced before fixing them:
acknowledgement identity after lowering risk and cancellation of corrupt active
state without a claim. Publication/CI is a post-commit gate, not a phone test.

## Design Coverage for This Stage

Profiles, urgency floor, duplicate-stable repeats, OFF resume admission,
acknowledgement pause/invalidation, accepted-source freshness/boot guards,
progression, no backlog and arithmetic bounds are implemented here.
Priority is represented for future arbitration; this per-source kernel does
not own the multi-source executor. Android effects/capabilities, wake-lock and
volume leases, durable transactional claims, retention, UI/receivers and actual
device/resource acceptance remain explicit runtime integration gates, not
implemented or enabled by these pure functions.
