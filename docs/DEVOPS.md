# DEVOPS

## Environments
- Local Android development: `android-app` via Gradle.
- Local backend development: `backend` via Python venv + uvicorn.

## Current quality commands
- Android build: `cd android-app && ./gradlew :app:assembleDebug`
- Android lint: `cd android-app && ./gradlew :app:lintDebug`
- Android tests: `cd android-app && ./gradlew :app:testDebugUnitTest`
- Android typecheck proxy: `cd android-app && ./gradlew :app:compileDebugKotlin`
- Backend tests: `cd backend && .venv/bin/python -m pytest -q`

## Gaps to close
- Backend lint is not standardized (recommended: `ruff check` + `ruff format --check`).
- Backend typecheck is not standardized (recommended: `mypy`).

## GitHub Workflow

Repository: https://github.com/siriushex/toolsaaps (public).

After each locally verified stage: fetch origin, inspect divergence and the
complete diff, scan for secrets/private data, explicitly stage reviewed paths,
commit and push the feature branch to its upstream. Use a PR against main;
merging remains a separate decision. Do not use force-push, background auto-commit
or automatic pull over a dirty worktree. If new upstream changes overlap with
local edits, stop publication and reconcile them explicitly without losing work.

`.github/workflows/verify.yml` runs on push, PR and manual dispatch:
- Python3.12: install pinned backend requirements and run the backend suite.
- Java17 / Android SDK36: unit tests, lint and debug APK build.
- Read-only repository permissions, pinned action SHAs, bounded job durations.
- No provider keys, production connections, phone databases, signing keys or
  deployment steps. CI debug APKs are not uploaded and are not phone releases.

Confirm the remote branch SHA matches the local commit, then inspect the Verify
result. A successful push with queued/failed CI is not a passed verification.
CI does not prove device migration, delivery or clinical correctness. Retain
private device evidence locally and link only non-sensitive summaries in PRs.
Required-check branch protection is not enabled by this file; it must be
configured separately before claiming main is protected.

## Rollback basics
- Android: reinstall last known-good apk artifact on device.
- Backend: redeploy previous image/version and run smoke endpoint checks.

## Migration basics
- Any DB schema change must include migration strategy and rollback note in PR + docs.

### Room 31 To 32

Migration32 only creates the local alarm state/cycle journal and its scheduling
indexes. Existing clinical/history/settings/receipts are not rewritten. Before
any separately authorized phone update, retain a fresh coherent private backup,
test ALL migrations on a disposable current-data copy and verify on-device
opening without destructive fallback. The storage-only source stage does not
enable new audio and does not prove device delivery or CPU/RAM savings.

An old schema31 APK cannot reopen a migrated32 database. Do not apply the generic
APK rollback to this transition or restore stale therapy data. Use a reviewed
forward fix or a separately validated non-destructive downgrade after a fresh
current-data backup. No destructive fallback is added for31/32.

### Room 30 To 31

Migration31 adds three nullable GI metadata columns to meal profile overrides
and pending meal intents. Existing therapy/history/settings and profile timing
are not rewritten; old GI remains unknown. Synthetic migration/restart tests
cover retention, foreign keys and integrity. Verify a fresh disposable working
database copy before the authorized in-place phone update, never the live DB
through a test harness. Retain a coherent private backup and signed old APK.

The old schema30 APK does not support a database already migrated to31. Do not
blindly reinstall it or restore an old backup over later therapy. Recovery
requires a reviewed forward fix or separately reviewed non-destructive downgrade
with a fresh current-data backup. The generic APK rollback above is insufficient
for this schema transition. No destructive fallback is added for30/31.
