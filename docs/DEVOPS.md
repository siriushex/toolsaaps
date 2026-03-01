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
- Unified CI workflow file is not yet documented here.

## Rollback basics
- Android: reinstall last known-good apk artifact on device.
- Backend: redeploy previous image/version and run smoke endpoint checks.

## Migration basics
- Any DB schema change must include migration strategy and rollback note in PR + docs.
