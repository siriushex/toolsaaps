# Local Alarm Cycle Executor

Scope: integrate one committed Room cycle with the existing volume lease and
guarded audio controller. No production caller, source arbitration, arming,
service, vibration, notification, permission, device or therapy change.

- [x] RED tests for journal read-only admission and executor behavior. Corrected
  required AppSettings fixture first; corrected RED shows only missing new APIs.
- [x] Implement read-only admission, serialized execution and exact cleanup.
  First focused run passed. Two real cleanup REDs reproduced after review:
  cancellation left CLAIMED ownership and failed audio stop reported FINISHED.
- [x] Test OFF/source/boot/freshness/capability loss, cancellation, duplicate
  callers, missed windows, volume override, audio/storage failure and durations.
  Focused143 tests/6 suites,23 new cases, no failures/errors/skips. Actual
  slow-read RED also reproduced a missed step; step-window admission fixed it.
- [x] Review boundaries and update architecture/invariants/security/notes.
- [x] Full Android unit/lint/compile/debug-build gate:4985 tests/427 suites,
  zero failures/errors,3 optional skips; fresh lint0 errors,305 existing warnings,
  4 hints, no executor issue. Updated one obsolete volume-consumer guard and
  reran the full gate. No runtime construction is allowed by its replacement.

External completion gate: explicit reviewed feature publication and exact-source
Verify. Record the actual commit/PR/Verify states in the private native checkpoint
after completion, not as a precommit assertion in this source snapshot.

Current source callbacks must invalidate admission and cancel the exact running
job immediately. Room admission checks authoritative OFF without evaluating a
new claim. Public start results remain API-start evidence, not completion or
hearing. Async media failure, native fallback, wake ownership and real-device
acceptance remain integration gates before the mode can be enabled.
