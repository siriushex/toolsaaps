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
- [x] Split delivery and persistence locks, preserve pending-OFF denial, add an
  explicit checked-source handoff and settle native/local volume ownership.
- [x] Production glucose INITIAL route selection and explicit runtime admission.
- [x] Scoped runtime acknowledgement command with immediate exact revocation,
  cleanup-before-pause ordering and source revalidation in the transaction.
- [x] Visible acknowledgement action and Android wake lifecycle, off by default.
- [x] Full local unit/lint/Kotlin/debug-build:5868 cases/6 existing skips,
  0 failures/errors; lint0 errors and unchanged379 notices. Source review and
  16-path credential/identifier scan complete; APK v2 signature verified.
- [x] Previous OFF source stage afa44533 published; exact-head Verify runs
  38877759914 and38877754037 passed, with matching local/CI unit cases.
- [x] Final handoff full Android quality:5890 cases/5884 pass/6 unchanged skips,
  0 failures/errors; lint0 errors with unchanged379 notices; APK v2 verified.
- [x] Handoff published ec3642fc; Verify38887130917 and38887123575 passed,
  with Linux XML matching local5890 cases and6 unchanged skips.
- [x] Current acknowledgement full local quality:5911 cases/5905 passed,
  6 unchanged skips,0 failures/errors; unchanged lint and verified APK signature.
- [x] Previous acknowledgement published as dbb9903a and retained in the current
  verified baseline70914112; baseline Verify38918464040 and38918450778 passed.
- [x] Foreground glucose owner, explicit opt-in, scoped notification action and
  critical system clock connected;414 focused cases passed.
- [x] Foreground integration final full quality:6011 cases/6005 passed/6 existing
  skips,0 failures/errors; lint0 errors,377 warnings/8 hints; APK v2 verified.
  Six new style notices reviewed; synchronous token persistence is intentional.
- [ ] Foreground integration reviewed publication and exact-head CI (see PR7).
- [ ] Separately authorized physical audio and locked-screen/Doze acceptance.

## Integration Finding

Two tests reproduced the native/technical callback deadlock. Delivery ordering
now remains serialized without holding the short persistence lock required by a
local claim. A separate regression reproduced pending-OFF admission before its
write; guarded local transactions now recheck the shared control revision.

coordinateWithLocalAlarmSource permits checked authority after committed claim,
before its callback, without inventing an audio receipt. Local audio preparation
finishes native boost cleanup before volume acquisition. Legacy/preview
replacement waits for the retired local lease's exact audio/volume cleanup.
The transport-release promise completes before terminal Room persistence; a busy
journal does not block an independently admitted urgent native replacement.

Production now chooses one glucose INITIAL route using current settings and
capabilities. Source emissions alone do not authorize sound. A proven native
audio START defers the local repeat; any-channel DELIVERED cannot be imported as
local audio STARTED. Failed or timed-out cleanup does not authorize a second
player. Technical delivery alerts retain their native path.
