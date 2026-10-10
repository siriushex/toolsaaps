# Bounded Local Alarm Arbitration

Scope: pure multi-source selection plus read-only Room preview. No source adapter,
coordinator actor, service, runtime caller, sound, setting, permission or therapy
change. This implements selection, not operational night-time delivery.

1. Add missing-API RED tests for priorities, exact-owner retention/preemption,
   oldest-due fairness, duplicate stability, invalidation, OFF, ACK deadlines,
   bounded inputs and interrupted ownership without replay.
2. Implement domain arbitration using existing LocalAlarmPolicy admission.
   Requests retain pending age; accepted evidence remains separate authority.
   Selection never commits hypothetical policy START states or claims.
3. Add Room preview under existing shared mute lock and transaction. Read actual
   OFF and validate bounded requested states/claims, fail closed on corruption.
   Verify preview never changes ordinals, due, ACK or journal rows.
4. Inline review, focused tests, full Android unit/lint/compile/debug build.
   Update architecture, invariants, plan, security review and AI_NOTES.
5. Publish only reviewed source paths on the existing branch, maintain draft PR,
   inspect exact-source Verify. Device installation and activation are separate.

The future serialized owner must cancel/join exact old execution before claiming
a selected replacement, recover abandoned claims explicitly, and revalidate
current accepted authority/capabilities immediately before effects. No timer
polling, CGM/forecast cadence reduction or default-true admission is permitted.
