# Alarm Runtime Integration

Continue the approved repeat, scoped acknowledgement and genuine AlarmClock
design using the existing source/policy/store/player. The previous source stage
192a52f0 passed both Verify runs (38870159391, 38870150944); it did not activate
the coordinator.

1. Remove the actual OFF-versus-Room race. Revoke current delivery before the
   persistent mutex, prevent queued pre-control work from starting later, and
   cancel only the owned alert child rather than its forecast/therapy caller.
2. Establish native INITIAL handoff without replay, a second player or invented
   audio-start receipts. Bind current source/settings/OFF/capabilities throughout.
3. Connect scoped acknowledgement, foreground lifetime and system wake adapter.
   Preserve deliberate global OFF and independently show denied capabilities.
4. Verify synthetic cancellation, source expiry, clock/boot, permission and
   pending-action races, then full Android tests/lint/Kotlin/debug build and
   reviewed source publication with exact-head CI.

No new clinical thresholds, targets or AI therapy actions. A build and synthetic
test cannot establish physical audibility or user response. Real phone sound and
locked-screen acceptance require their own explicitly authorized test.

## Progress

- [x] Current control intent immediately revokes old delivery and queued work;
  original four REDs and348 focused passing cases retained privately.
- [x] Cross-owner source revival reproduced; publication now binds to its exact
  control revision. Final full quality includes this additional regression.
- [ ] Native INITIAL handoff and runtime admission.
- [ ] Scoped acknowledgement and Android wake lifecycle.
- [x] Full local unit/lint/Kotlin/debug-build:5868 cases/6 existing skips,
  0 failures/errors; lint0 errors and unchanged379 notices. Source review and
  16-path credential/identifier scan complete; APK v2 signature verified.
- [ ] Reviewed source publication and exact-head CI.

## Integration Finding

The native INITIAL callback currently holds the same Room operation mutex that
the local executor needs for its claim. Activating the coordinator inside that
callback would deadlock. Passing a source emission directly to the coordinator
afterward would instead repeat INITIAL while native sound/boost may still be
owned. The next change must explicitly settle this lock and volume handoff;
any-channel DELIVERED cannot be imported as a fabricated local audio STARTED.
