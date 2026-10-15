# Profile Save Verification

## Defects And Correction

The native editor dismissed immediately after invoking an asynchronous writer.
Validation, sensitivity recalculation or storage failure therefore appeared to
the user as a successful save followed by an empty or unchanged profile. Two
native regression tests reproduced dismissal and lost edits before correction.
The writer also held a pre-cycle profile snapshot, which could overwrite an
independent food/activity/calorie change made while calculation was suspended.

The energy-profile settings editors now receive `Saved` or `Failed` only
after their writer completes. Pending state prevents duplicate submission and
dismissal. Failure retains inputs and shows a localized, actionable error. An
explicit retry captures the retained draft; success closes the editor. A
form-scoped coroutine and once-only completion protect disposal and retries.
Progress retains the localized button geometry and has an accessible label.

The adjacent planned-activity event editor had the same premature-dismissal
defect, reproduced in a separate native test. It now uses the same completion
contract, retains its fields on failure and provides a scrollable form with
full-width type/intensity menus. The existing schedule validation, Room writer
and clinical-input invalidation semantics remain unchanged. No-op saves retain
their existing successful result; invalid or failed writes cannot close the form.

`persistEnergyProfileSettingsUpdate` evaluates the existing policy before any
side effect, applies physiological sex only through the existing sensitivity
coordinator, then revalidates/merges the transform inside DataStore's transaction.
A concurrent sex conflict cannot be written through the ordinary setter. Policy
violations, calculation rejection, storage failure and cancellation cannot be
reported as a successful profile save.

This does not make the accepted sensitivity cycle and subsequent ordinary write
one atomic operation. If that later write fails, an already accepted sex change
may remain saved, while the form retains all edits for an explicit retry. The
ordinary write cannot silently roll back another accepted sensitivity revision.
No clinical guard, pediatric policy, target, prediction coefficient, database
schema, therapy command or alarm setting changes in this correction.

## Verification Matrix

| Area | Automated verification | Remaining device acceptance |
| --- | --- | --- |
| Personal profile | Native date picker, comma decimals, validation, pending save, error retention, retry/reopen; real preferences-file close/reopen | User-entered values, real process restart and update retention |
| Other settings | Food/activity/calorie pending/error/success, joint correction, concurrent merge; planned-event pending/error/retry and validation | Actual selected modes and schedule on the installed build |
| Sensitivity | Existing lease/source application and fingerprint guards; rejection/cancellation tests | Accepted cycle from fresh actual inputs |
| Glucose and Target Manager | Full existing forecast, source/chronology, authority and target-policy suite | Real CGM trend and read-only AAPS confirmation |
| Eating Soon and meals | Existing canonical submission, priority, idempotency, confirmation and food-curve suite | Real user-authorized submission; no test therapy |
| Notifications | Existing alert policies, ownership, scheduling and capability tests | OS permissions, audible/vibration delivery and device lifecycle |
| History and storage | Settings persistence, Room migration and integrity regressions | Fresh private device-copy migration and data retention |
| Presentation | Native Compose/Robolectric, narrow screen, light/dark and enlarged Russian text | Real device screen and OEM behavior |

Run the full Android unit suite, lint, Kotlin compilation and debug assembly on
the final source. Inspect test counts, skipped cases and lint findings, verify
the APK signer and exact-source CI. A green synthetic suite is not proof of
clinical effectiveness, hardware delivery or every possible device interaction.
Backend source is unchanged; the repository Verify workflow tests it separately.

## Update And Recovery

No schema migration is introduced; existing schema32 remains. A phone update
requires separately authorized coherent backup and matching package/signer.
Never clear app data or restore old therapy history to repair this UI bug. Do
not stop AAPS or the pump and do not send fabricated targets/boluses for testing.
The existing inactive alarm coordinator remains inactive; this stage does not
enable unfinished notification hardware or claim pump-wear detection.
